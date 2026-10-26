package kr.chacademy.portrait.net;

import kr.chacademy.portrait.ChacaPortraitPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.portrait.PortraitProtocol;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 일러스트 채널. 버전 handshake(HELLO/HELLO_ACK)를 마친 클라이언트에게만 보낸다.
 * 클라이언트가 HELLO에 캐시된 SHA-256을 실어 보내면, 같을 때는 META(조각 0)만 보내 다시 받지 않게 한다.
 * PNG는 192KB 조각으로 틱당 1개씩 보낸다 (접속 직후 대역폭 몰림 방지).
 * 다른 서버에서 완성된 일러스트는 {@link #syncRemote()}가 주기적으로 SHA를 비교해 전달한다.
 */
public final class PortraitChannel implements PluginMessageListener, Listener {

    private final ChacaPortraitPlugin plugin;
    private final Map<UUID, Boolean> capable = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gate = new ConcurrentHashMap<>();
    private final Map<UUID, Long> helloAt = new ConcurrentHashMap<>();
    /** 이번 접속에서 클라이언트에게 마지막으로 알려 준 일러스트 SHA ("" = 없음). HELLO를 마친 플레이어만 들어 있다. */
    private final Map<UUID, String> sentSha = new ConcurrentHashMap<>();
    private volatile boolean syncing;

    public PortraitChannel(ChacaPortraitPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, PortraitProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, PortraitProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        transfers.values().forEach(BukkitTask::cancel);
        transfers.clear();
        otherTransfers.values().forEach(BukkitTask::cancel);
        otherTransfers.clear();
        otherSlot.clear();
        otherWaiting.clear();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PortraitProtocol.REQUEST, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, PortraitProtocol.RESPONSE);
        capable.clear();
        sentSha.clear();
    }

    public boolean capable(Player p) {
        return Boolean.TRUE.equals(capable.get(p.getUniqueId()))
                && p.getListeningPluginChannels().contains(PortraitProtocol.RESPONSE);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        capable.remove(id);
        gate.remove(id);
        helloAt.remove(id);
        sentSha.remove(id);
        BukkitTask t = transfers.remove(id);
        if (t != null) {
            t.cancel();
        }
        dropOther(id);
        otherWaiting.remove(id);
        otherAt.remove(id);
        otherPairAt.remove(id);
        pumpOther();
    }

    // ------------------------------------------------------------------ 상대 일러스트 (정보창 v4, 2026-10-26)
    /** 동시에 보내는 남의 그림 수 상한 (DB 읽기 포함). 나머지는 줄을 선다. */
    private static final int OTHER_SLOTS = 2, OTHER_QUEUE = 32;
    /** 한 사람의 요청 간격 · 같은 대상 다시 요청 간격 (ms). */
    private static final long OTHER_GAP_MS = 250, OTHER_PAIR_GAP_MS = 3000;
    /** 조각 보내는 간격 (틱). 본인 그림(1틱)보다 느슨하게 나눠 보낸다. */
    private static final long OTHER_CHUNK_TICKS = 2;
    private record OtherRequest(UUID requester, UUID target, String cached) {}
    /** 요청한 사람 → 기다리는 요청 (한 사람당 하나, 새 요청이 옛 요청을 바꾼다). 메인 스레드. */
    private final java.util.LinkedHashMap<UUID, OtherRequest> otherWaiting = new java.util.LinkedHashMap<>();
    /** 요청한 사람 → 지금 자리를 쓰는 요청 번호 (DB 읽기·전송 중). 크기 = 쓰는 자리 수. */
    private final Map<UUID, Long> otherSlot = new java.util.HashMap<>();
    private final Map<UUID, BukkitTask> otherTransfers = new java.util.HashMap<>();
    private final Map<UUID, Long> otherAt = new java.util.HashMap<>();
    private final Map<UUID, Map<UUID, Long>> otherPairAt = new java.util.HashMap<>();
    private long otherSerial;

    /** 메인 스레드: 정보창이 다른 사람(대상 UUID)의 일러스트를 요청. 원본 그대로 보낸다 (줄이지 않음). */
    private void other(Player p, PortraitProtocol.Packet r) {
        UUID target = PortraitProtocol.uuid(r.text());
        if (!capable(p) || target == null) {
            return;
        }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = otherAt.get(id);
        if (last != null && now - last < OTHER_GAP_MS) {
            return;
        }
        otherAt.put(id, now);
        var pairs = otherPairAt.computeIfAbsent(id, k -> new java.util.HashMap<>());
        Long pair = pairs.get(target);
        if (pair != null && now - pair < OTHER_PAIR_GAP_MS) {
            return;
        }
        pairs.put(target, now);
        if (pairs.size() > 64) {
            pairs.values().removeIf(t -> now - t > OTHER_PAIR_GAP_MS);
        }
        String cached = PortraitProtocol.validSha(r.extra()) ? r.extra() : "";
        dropOther(id); // 같은 사람의 옛 요청·전송은 그만둔다 (정보창은 한 번에 하나)
        otherWaiting.remove(id);
        var request = new OtherRequest(id, target, cached);
        if (otherSlot.size() >= OTHER_SLOTS) {
            if (otherWaiting.size() >= OTHER_QUEUE) {
                send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_OTHER_BUSY, target.toString(), 0, ""));
                return;
            }
            otherWaiting.put(id, request);
            return;
        }
        startOther(request);
    }

    private void startOther(OtherRequest q) {
        long gen = ++otherSerial;
        otherSlot.put(q.requester(), gen);
        plugin.service().then(plugin.db().call(() -> plugin.storage().sha(q.target())), sha -> {
            Player p = currentOther(q, gen);
            if (p == null) {
                releaseOther(q.requester(), gen);
                return;
            }
            if (sha == null || sha.equals(q.cached())) {
                // 그림 없음 → 3D 스킨 / 같은 그림 → 클라이언트 캐시 그대로
                send(p, new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_META, q.target().toString(), 0, sha == null ? "" : sha, sha != null, null));
                releaseOther(q.requester(), gen);
                return;
            }
            plugin.service().then(plugin.db().call(() -> plugin.storage().portrait(q.target())), portrait -> {
                Player again = currentOther(q, gen);
                if (again == null) {
                    releaseOther(q.requester(), gen);
                    return;
                }
                if (portrait == null || portrait.png() == null || portrait.png().length == 0 || portrait.png().length > PortraitProtocol.MAX_IMAGE_BYTES) {
                    send(again, new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_META, q.target().toString(), 0, "", false, null));
                    releaseOther(q.requester(), gen);
                    return;
                }
                pushOther(again, q, gen, portrait.sha(), portrait.png());
            }, ex -> otherFailed(q, gen, ex));
        }, ex -> otherFailed(q, gen, ex));
    }

    private void otherFailed(OtherRequest q, long gen, Throwable ex) {
        plugin.getLogger().warning("[ChacaPortrait] 상대 일러스트 읽기 실패: " + ex.getMessage());
        Player p = currentOther(q, gen);
        if (p != null) {
            send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_OTHER_BUSY, q.target().toString(), 0, ""));
        }
        releaseOther(q.requester(), gen);
    }

    /** 요청한 사람이 아직 접속 중이고 이 요청이 최신이면 그 플레이어. */
    private Player currentOther(OtherRequest q, long gen) {
        if (!java.util.Objects.equals(otherSlot.get(q.requester()), gen)) {
            return null;
        }
        Player p = Bukkit.getPlayer(q.requester());
        return p != null && capable(p) ? p : null;
    }

    private void pushOther(Player p, OtherRequest q, long gen, String sha, byte[] png) {
        int chunks = PortraitProtocol.chunks(png.length);
        String who = q.target().toString();
        send(p, new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_META, who, png.length, sha, false, null));
        int[] next = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player now = currentOther(q, gen);
            if (now == null || next[0] >= chunks) {
                self[0].cancel();
                otherTransfers.remove(q.requester(), self[0]);
                releaseOther(q.requester(), gen);
                return;
            }
            int from = next[0] * PortraitProtocol.CHUNK_BYTES;
            int to = Math.min(png.length, from + PortraitProtocol.CHUNK_BYTES);
            send(now, new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_CHUNK, who, next[0], sha, false, Arrays.copyOfRange(png, from, to)));
            next[0]++;
        }, 1L, OTHER_CHUNK_TICKS);
        otherTransfers.put(q.requester(), self[0]);
    }

    /** 이 사람의 상대 일러스트 요청·전송을 그만두고 자리를 비운다. */
    private void dropOther(UUID requester) {
        BukkitTask t = otherTransfers.remove(requester);
        if (t != null) {
            t.cancel();
        }
        otherSlot.remove(requester);
    }

    private void releaseOther(UUID requester, long gen) {
        if (otherSlot.remove(requester, gen)) {
            BukkitTask t = otherTransfers.get(requester);
            if (t != null && t.isCancelled()) {
                otherTransfers.remove(requester);
            }
        }
        pumpOther();
    }

    /** 자리가 비면 줄 선 요청을 차례로 시작한다. */
    private void pumpOther() {
        var it = otherWaiting.values().iterator();
        while (otherSlot.size() < OTHER_SLOTS && it.hasNext()) {
            var q = it.next();
            it.remove();
            Player p = Bukkit.getPlayer(q.requester());
            if (p != null && capable(p)) {
                startOther(q);
            }
        }
    }

    private void send(Player p, PortraitProtocol.Packet packet) {
        if (!p.isOnline()) {
            return;
        }
        try {
            p.sendPluginMessage(plugin, PortraitProtocol.RESPONSE, PortraitProtocol.encode(packet));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[ChacaPortrait] 패킷 인코딩 실패: " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!PortraitProtocol.REQUEST.equals(channel)) {
            return;
        }
        PortraitProtocol.Packet r;
        try {
            r = PortraitProtocol.decode(bytes);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (r.op() == PortraitProtocol.C_PROMPT) {
            long now = System.currentTimeMillis();
            Long last = gate.get(p.getUniqueId());
            if (last != null && now - last < 100) {
                return;
            }
            gate.put(p.getUniqueId(), now);
        }
        switch (r.op()) {
            case PortraitProtocol.C_HELLO -> hello(p, r);
            case PortraitProtocol.C_OTHER -> other(p, r);
            case PortraitProtocol.C_PROMPT -> {
                if (!capable(p) || !PortraitProtocol.validToken(r.text())) {
                    return;
                }
                plugin.rerolls().answer(p, r.text(), r.extra(), r.flag());
            }
            default -> {
            }
        }
    }

    private void hello(Player p, PortraitProtocol.Packet r) {
        // 접속마다 한 번이면 충분 (반복 HELLO로 DB·전송을 몰아붙이지 못하게 10초 간격)
        long at = System.currentTimeMillis();
        Long last = helloAt.get(p.getUniqueId());
        if (last != null && at - last < 10_000) {
            return;
        }
        helloAt.put(p.getUniqueId(), at);
        if (r.number() != PortraitProtocol.VERSION) {
            capable.remove(p.getUniqueId());
            return;
        }
        capable.put(p.getUniqueId(), true);
        send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_HELLO_ACK, "", PortraitProtocol.VERSION, ""));
        String cached = PortraitProtocol.validSha(r.text()) ? r.text() : "";
        UUID id = p.getUniqueId();
        plugin.service().then(plugin.db().call(() -> plugin.storage().sha(id)), sha -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null || !capable(now)) {
                return;
            }
            if (sha == null) {
                sentSha.put(id, "");
                send(now, PortraitProtocol.Packet.of(PortraitProtocol.S_META, "", 0, "0"));
            } else if (sha.equals(cached)) {
                sentSha.put(id, sha);
                send(now, PortraitProtocol.Packet.of(PortraitProtocol.S_META, sha, 0, "0"));
            } else {
                plugin.service().then(plugin.db().call(() -> plugin.storage().portrait(id)), portrait -> {
                    Player again = Bukkit.getPlayer(id);
                    if (again == null || !capable(again)) {
                        return;
                    }
                    if (portrait != null) {
                        push(again, portrait.sha(), portrait.png());
                    } else {
                        // 그 사이에 삭제됨 → "없음"으로 알려 클라이언트가 계속 기다리지 않게 한다
                        sentSha.put(id, "");
                        send(again, PortraitProtocol.Packet.of(PortraitProtocol.S_META, "", 0, "0"));
                    }
                }, ex -> plugin.getLogger().warning("[ChacaPortrait] 일러스트 읽기 실패: " + ex.getMessage()));
            }
        }, ex -> plugin.getLogger().warning("[ChacaPortrait] 일러스트 조회 실패: " + ex.getMessage()));
    }

    /** 메인 스레드: 본인 일러스트 전송 (이전 전송은 취소). */
    public void push(Player p, String sha, byte[] png) {
        if (!capable(p) || png == null || png.length == 0 || png.length > PortraitProtocol.MAX_IMAGE_BYTES) {
            return;
        }
        UUID id = p.getUniqueId();
        BukkitTask old = transfers.remove(id);
        if (old != null) {
            old.cancel();
        }
        int chunks = PortraitProtocol.chunks(png.length);
        sentSha.put(id, sha);
        send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_META, sha, png.length, Integer.toString(chunks)));
        int[] next = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null || next[0] >= chunks || transfers.get(id) != self[0]) {
                self[0].cancel();
                transfers.remove(id, self[0]);
                return;
            }
            int from = next[0] * PortraitProtocol.CHUNK_BYTES;
            int to = Math.min(png.length, from + PortraitProtocol.CHUNK_BYTES);
            send(now, new PortraitProtocol.Packet(PortraitProtocol.S_CHUNK, sha, next[0], "", false, Arrays.copyOfRange(png, from, to)));
            next[0]++;
        }, 1L, 1L);
        transfers.put(id, self[0]);
    }

    /** 일러스트 삭제 알림 (/유저관리 <닉네임> 초상화 초기화). */
    public void cleared(Player p) {
        if (capable(p)) {
            sentSha.put(p.getUniqueId(), "");
            send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_META, "", 0, "0"));
        }
    }

    /**
     * 다른 서버에서 완성·삭제된 일러스트 전달 (메인 스레드, 주기 호출).
     * HELLO를 마친 접속자들의 현재 SHA를 쿼리 한 번으로 읽어, 이번 접속에서 보낸 것과 다르면 새 일러스트를 보내고 완성 안내를 한 번 한다.
     * 이 서버에서 그리는 중인 플레이어는 건너뛴다 (완료 시 이 서버가 직접 보낸다).
     */
    public void syncRemote() {
        if (syncing || sentSha.isEmpty()) {
            return;
        }
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (UUID id : sentSha.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && capable(p) && !plugin.service().busy(id) && !transfers.containsKey(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        syncing = true;
        plugin.service().then(plugin.db().call(() -> plugin.storage().shas(ids)), found -> {
            syncing = false;
            for (UUID id : ids) {
                String sent = sentSha.get(id);
                Player p = Bukkit.getPlayer(id);
                if (sent == null || p == null || !capable(p) || plugin.service().busy(id)) {
                    continue;
                }
                String current = found.get(id);
                if (current == null) {
                    if (!sent.isEmpty()) {
                        cleared(p); // 다른 서버에서 삭제됨 (/유저관리 <닉네임> 초상화 초기화)
                    }
                    continue;
                }
                if (current.equals(sent)) {
                    continue;
                }
                plugin.service().then(plugin.db().call(() -> plugin.storage().portrait(id)), portrait -> {
                    Player again = Bukkit.getPlayer(id);
                    if (again == null || portrait == null || !capable(again) || portrait.sha().equals(sentSha.get(id))) {
                        return;
                    }
                    push(again, portrait.sha(), portrait.png());
                    if (!portrait.sha().equals(sentSha.get(id))) {
                        sentSha.put(id, portrait.sha()); // 보낼 수 없는 그림 (크기 초과 등) — 20초마다 다시 읽고 안내하지 않게 한다
                        return;
                    }
                    again.sendMessage(plugin.settings().message("done"));
                }, ex -> plugin.getLogger().warning("[ChacaPortrait] 일러스트 읽기 실패: " + ex.getMessage()));
            }
        }, ex -> {
            syncing = false;
            plugin.getLogger().warning("[ChacaPortrait] 일러스트 변경 확인 실패: " + ex.getMessage());
        });
    }

    public void openPrompt(Player p, String token, String hint) {
        send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_PROMPT_OPEN, token, 0, hint));
    }

    public void closePrompt(Player p) {
        if (capable(p)) {
            send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_PROMPT_CLOSE, "", 0, ""));
        }
    }

    public void status(Player p, String text) {
        if (capable(p) && text != null && !text.isEmpty()) {
            send(p, PortraitProtocol.Packet.of(PortraitProtocol.S_STATUS, "", 0,
                    text.length() > PortraitProtocol.MAX_STATUS_CHARS ? text.substring(0, PortraitProtocol.MAX_STATUS_CHARS) : text));
        }
    }
}
