package kr.chacademy.npc.appearance;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import kr.chacademi.npcmodel.protocol.NpcModelProtocol;
import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.npc.NpcManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.File;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Citizens NPC 외형(3D 모델) 동기화. 서버는 "어떤 모델·크기·동작 상태"만 관리하고, 뼈대 계산·렌더링은 하지 않는다.
 *
 * <ul>
 *   <li>보내는 때: NPC 추적 시작(다음 틱), 모델 변경, 대화 시작/끝, 인사, 설정 새로고침 — 그 NPC를 보고 있는 모드 사용자에게만</li>
 *   <li>매 틱 위치 패킷 없음: 위치·회전은 바닐라 엔티티 동기화(Citizens 이동) 그대로</li>
 *   <li>엔티티는 (번호, UUID, revision) 으로 묶는다. Citizens가 NPC를 다시 만들면 새 엔티티 → 추적 시작 때 새로 보냄</li>
 *   <li>투명화 패킷을 쓰지 않는다: 모델을 못 그리는 클라이언트(모드 없음·파일 없음·로드 실패)는 원래 플레이어 외형 그대로</li>
 * </ul>
 */
public final class AppearanceService implements Listener, PluginMessageListener {

    private final ChacaNpcPlugin plugin;
    private final AppearanceConfig config;
    /** 서버가 켜질 때마다 새 번호. 클라이언트는 이 값이 바뀌면 받은 매핑을 버린다. */
    private final long session = new SecureRandom().nextLong();
    /** HELLO 버전이 맞은 플레이어. */
    private final Set<UUID> capable = ConcurrentHashMap.newKeySet();
    private final Set<UUID> warnedVersion = ConcurrentHashMap.newKeySet();
    /** Citizens 번호 → 외형 변경 번호 (클라이언트가 낮은 번호 패킷을 버리게). */
    private final Map<Integer, Integer> revision = new HashMap<>();
    private final Map<Integer, Integer> greetSeq = new HashMap<>();
    private Set<Integer> talking = new HashSet<>();
    /** 관리자 동작 테스트로 켠 대화 상태 (실제 대화가 없어도 유지, '동작 대기'로 끔). */
    private final Set<Integer> testTalking = new HashSet<>();
    private boolean enabled = true;

    // 측정 (/NPC관리 외형 통계)
    private final LongAdder packets = new LongAdder(), bytes = new LongAdder(), trackEvents = new LongAdder(), trackNanos = new LongAdder();
    private final long startedAt = System.currentTimeMillis();

    public AppearanceService(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
        this.config = new AppearanceConfig(new File(plugin.getDataFolder(), "npc-models.yml"), plugin.getLogger());
    }

    public AppearanceConfig config() {
        return config;
    }

    public void register() {
        config.load();
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, NpcModelProtocol.CHANNEL_S2C);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, NpcModelProtocol.CHANNEL_C2S, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 리로드 등으로 이미 접속해 있는 플레이어: 채널을 듣고 있으면 인사부터
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getListeningPluginChannels().contains(NpcModelProtocol.CHANNEL_S2C)) {
                send(p, new NpcModelProtocol.HelloAck(session));
            }
        }
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, NpcModelProtocol.CHANNEL_S2C);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, NpcModelProtocol.CHANNEL_C2S, this);
        capable.clear();
    }

    // ================================================================ 연결 확인 (HELLO)

    /** 모드가 S2C 채널을 등록함 → 세션 번호를 알려 주고 HELLO(버전)를 기다린다. */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent e) {
        if (NpcModelProtocol.CHANNEL_S2C.equals(e.getChannel())) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    send(p, new NpcModelProtocol.HelloAck(session));
                }
            });
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (!NpcModelProtocol.CHANNEL_C2S.equals(channel)) {
            return;
        }
        NpcModelProtocol.Hello h = NpcModelProtocol.decodeHello(data);
        if (h == null) {
            return;
        }
        if (h.clientVersion() != NpcModelProtocol.VERSION) {
            capable.remove(p.getUniqueId());
            if (warnedVersion.add(p.getUniqueId())) {
                plugin.getLogger().warning("[ChacaNPC] " + p.getName() + " 의 NPC 모델 모드 프로토콜 v" + h.clientVersion()
                        + " ≠ 서버 v" + NpcModelProtocol.VERSION + " → 이 플레이어에게는 원래 외형만 보입니다 (모드/플러그인 버전 맞추기)");
            }
            return;
        }
        if (!capable.add(p.getUniqueId())) {
            return; // 이미 연결됨: 다시 답하지 않는다 (HELLO/HELLO_ACK 무한 왕복 방지)
        }
        send(p, new NpcModelProtocol.HelloAck(session)); // 채널 등록 전에 온 HELLO 대비: 세션을 먼저 알려 준 뒤
        if (enabled) {
            sendAllTrackedTo(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        capable.remove(e.getPlayer().getUniqueId());
        warnedVersion.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================ 추적 (보는 사람에게만)

    /** 플레이어가 엔티티를 보기 시작함. 아주 자주 불리므로 가장 싼 검사부터. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent e) {
        Entity ent = e.getEntity();
        if (ent.getType() != EntityType.PLAYER || !enabled) {
            return;
        }
        Player viewer = e.getPlayer();
        if (!capable.contains(viewer.getUniqueId())) {
            return;
        }
        long t0 = System.nanoTime();
        trackEvents.increment();
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(ent);
        if (npc != null && resolve(npc) != null) {
            // 스폰 패킷보다 뒤에 도착하게 다음 틱 (클라이언트는 순서가 바뀌어도 잠시 보관했다가 붙인다)
            int id = ent.getEntityId();
            UUID uuid = ent.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline() && ent.isValid() && ent.getEntityId() == id && ent.getTrackedBy().contains(viewer)) {
                    sendAppear(viewer, npc, ent, uuid);
                }
            });
        }
        trackNanos.add(System.nanoTime() - t0);
    }

    /** NPC가 사라질 때: 클라이언트는 엔티티 제거로 스스로 정리하지만, 같은 번호 재사용 대비로 revision 만 올린다. */
    @EventHandler
    public void onDespawn(NPCDespawnEvent e) {
        bump(e.getNPC().getId());
    }

    @EventHandler
    public void onRemove(NPCRemoveEvent e) {
        int id = e.getNPC().getId();
        revision.remove(id);
        greetSeq.remove(id);
        talking.remove(id);
        testTalking.remove(id);
    }

    private void sendAllTrackedTo(Player p) {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            Entity ent = npc.isSpawned() ? npc.getEntity() : null;
            if (ent != null && ent.getType() == EntityType.PLAYER && resolve(npc) != null && ent.getTrackedBy().contains(p)) {
                sendAppear(p, npc, ent, ent.getUniqueId());
            }
        }
    }

    // ================================================================ 상태 변경

    /** 모델·크기 설정이 바뀐 NPC: 보는 사람에게 새 외형(또는 해제)을 보낸다. */
    public void refresh(NPC npc) {
        int rev = bump(npc.getId());
        Entity ent = npc.isSpawned() ? npc.getEntity() : null;
        if (ent == null) {
            return;
        }
        AppearanceConfig.Spec spec = resolve(npc);
        for (Player p : ent.getTrackedBy()) {
            if (!capable.contains(p.getUniqueId())) {
                continue;
            }
            if (spec == null) {
                send(p, new NpcModelProtocol.Clear(ent.getEntityId(), ent.getUniqueId(), rev));
            } else {
                sendAppear(p, npc, ent, ent.getUniqueId());
            }
        }
    }

    /** 설정 새로고침 뒤: 모드 사용자 전원에게 RESET 후 보이는 NPC를 다시 보낸다. */
    public void refreshAll() {
        revision.replaceAll((id, rev) -> rev + 1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (capable.contains(p.getUniqueId())) {
                send(p, new NpcModelProtocol.Reset());
                if (enabled) {
                    sendAllTrackedTo(p);
                }
            }
        }
    }

    public void reload() {
        config.load();
        refreshAll();
    }

    /** 대화창이 열릴 때 (AI 대화·스토리 대화 모두): 인사 한 번. */
    public void greet(Integer citizensId) {
        if (citizensId == null) {
            return;
        }
        plugin.sync(() -> { // 스토리 결과 콜백 등 다른 스레드에서 와도 메인 스레드에서 보냄
            greetSeq.merge(citizensId, 1, Integer::sum);
            broadcastState(citizensId);
        });
    }

    /** 1초마다: AI 대화 중인 NPC 목록이 바뀌었으면 동작 상태를 보낸다. */
    public void tick(Set<Integer> realTalking) {
        Set<Integer> nowTalking = new HashSet<>(realTalking);
        nowTalking.addAll(testTalking);
        if (nowTalking.equals(talking)) {
            return;
        }
        Set<Integer> changed = new HashSet<>(nowTalking);
        changed.addAll(talking);
        Set<Integer> same = new HashSet<>(nowTalking);
        same.retainAll(talking);
        changed.removeAll(same);
        talking = new HashSet<>(nowTalking);
        for (int id : changed) {
            broadcastState(id);
        }
    }

    /** 관리자 테스트: 상태를 강제로 보낸다. */
    public void testState(NPC npc, boolean talk, boolean greetOnce) {
        if (talk) {
            testTalking.add(npc.getId());
            talking.add(npc.getId());
        } else {
            testTalking.remove(npc.getId());
            talking.remove(npc.getId());
        }
        if (greetOnce) {
            greetSeq.merge(npc.getId(), 1, Integer::sum);
        }
        broadcastState(npc.getId());
    }

    private void broadcastState(int citizensId) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(citizensId);
        if (npc == null || !npc.isSpawned() || resolve(npc) == null || !enabled) {
            return;
        }
        Entity ent = npc.getEntity();
        int rev = revision.computeIfAbsent(citizensId, k -> 1);
        NpcModelProtocol.State st = new NpcModelProtocol.State(ent.getEntityId(), ent.getUniqueId(), rev,
                talking.contains(citizensId) ? NpcModelProtocol.STATE_TALK : NpcModelProtocol.STATE_IDLE,
                greetSeq.getOrDefault(citizensId, 0));
        for (Player p : ent.getTrackedBy()) {
            if (capable.contains(p.getUniqueId())) {
                send(p, st);
            }
        }
    }

    // ================================================================ 내부

    public AppearanceConfig.Spec resolve(NPC npc) {
        Object c = npc.data().has(NpcManager.DATA_KEY) ? npc.data().get(NpcManager.DATA_KEY) : null;
        return config.resolve(npc.getId(), c == null ? null : c.toString());
    }

    private void sendAppear(Player p, NPC npc, Entity ent, UUID uuid) {
        AppearanceConfig.Spec spec = resolve(npc);
        if (spec == null) {
            return;
        }
        int rev = revision.computeIfAbsent(npc.getId(), k -> 1);
        Float yaw = config.facing(npc.getId());
        send(p, new NpcModelProtocol.Appear(ent.getEntityId(), uuid, rev, spec.model(), spec.scale(),
                yaw == null ? Float.NaN : yaw,
                talking.contains(npc.getId()) ? NpcModelProtocol.STATE_TALK : NpcModelProtocol.STATE_IDLE,
                greetSeq.getOrDefault(npc.getId(), 0)));
    }

    private int bump(int citizensId) {
        return revision.merge(citizensId, 1, Integer::sum);
    }

    private void send(Player p, NpcModelProtocol.Message m) {
        byte[] b = NpcModelProtocol.encode(m);
        p.sendPluginMessage(plugin, NpcModelProtocol.CHANNEL_S2C, b);
        packets.increment();
        bytes.add(b.length);
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
        refreshAll();
    }

    public boolean enabled() {
        return enabled;
    }

    public int capableCount() {
        return capable.size();
    }

    public boolean isCapable(Player p) {
        return capable.contains(p.getUniqueId());
    }

    /** 서버 추가 부하 측정값 (켜진 뒤 누적). */
    public String stats() {
        double minutes = Math.max(1e-3, (System.currentTimeMillis() - startedAt) / 60000.0);
        long ev = trackEvents.sum();
        return String.format("모드 사용자 %d명 · 보낸 패킷 %d개(%.1f/분, %d바이트) · NPC 추적 검사 %d회, 평균 %.1fµs",
                capable.size(), packets.sum(), packets.sum() / minutes, bytes.sum(), ev, ev == 0 ? 0 : trackNanos.sum() / 1000.0 / ev);
    }
}
