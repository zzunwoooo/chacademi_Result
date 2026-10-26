package school.magiccodex.paper;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 공통 지급 함수 (2026-10-26, 뽑기 상자 패치 때 기존 "인벤토리 → 넘치면 우편" 경로를 하나로 묶음).
 *
 * <h3>어느 칸에 넣나 ({@link #usable})</h3>
 * 아공간 배낭은 "지금 쪽 27칸(9~35) = 인벤토리 본칸, 나머지 쪽 = 공유 DB 의 lease/CAS 줄" 구조다(BagPagesBridge·BagHuskCoordinator).
 * <ul>
 *   <li>핫바 0~8: 언제나 쓴다.</li>
 *   <li>지금 쪽 27칸: 서버 실제 상태로 그 쪽이 잠금 해제돼 있고(active ≤ unlocked) 배낭 동기화가 READY 일 때만 쓴다.
 *       쪽을 넘기는 중(sharedBlocked)·동기화 중·재조정 필요·관리자가 해금 수를 줄여 잠긴 쪽을 보고 있는 경우에는 한 칸도 쓰지 않는다
 *       (넘기는 중에 27칸에 넣으면 넘김이 끝날 때 덮여 사라진다). 아공간 배낭이 없는 서버(HuskSync 없음)는 바닐라 인벤토리라 그대로 쓴다.</li>
 *   <li>지금 보이지 않는 다른 쪽(비활성 쪽): 쓰지 않는다. 그 쪽은 공유 DB 줄을 lease/CAS·WAL 로만 바꿀 수 있고, CAS 와 플레이어 저장
 *       사이에서 서버가 멈추면 지급 영수증과 묶을 방법이 없어 자동 재지급(복제) 또는 수동 재조정이 생긴다. 복제 방지를 약화시키지 않기 위해
 *       비활성 쪽에는 넣지 않고, 넘친 것은 우편으로 보낸다 (문서: CLAUDE_OPENING_FINAL_PATCHES_20261026.md).</li>
 * </ul>
 * <h3>배치 순서</h3> {@link RewardPlan} — 기존 우편 수령과 같은 규칙(같은 아이템 칸 먼저 합치고, 남으면 빈 칸, 칸 번호 순).
 * <h3>{@link #give}</h3> 넣을 수 있는 만큼 넣고 남은 것만 우편. 남은 것은 우편 대기 파일(FishMailOutbox 형식)에 먼저 적고, 같은 틱에
 * 인벤토리 변경 + 영수증(PDC)을 saveData 하고, 우편 저장이 확인되면 대기 파일과 영수증을 지운다. 재시작·재접속 때 영수증이 있으면
 * 다시 보내고(우편 source UNIQUE 라 한 통), 없으면 지급 전에 멈춘 것이므로 버린다. 낚시 보상 우편 대기열과 같은 방식.
 */
final class RewardDelivery implements Listener, AutoCloseable {
    enum Given { INVENTORY, MAIL, DROPPED }

    static final RewardPlan.Stacks<ItemStack> STACKS = new RewardPlan.Stacks<>() {
        public boolean empty(ItemStack s) { return s == null || s.getType().isAir() || s.getAmount() <= 0; }
        public boolean similar(ItemStack a, ItemStack b) { return a.isSimilar(b); }
        public int amount(ItemStack s) { return s.getAmount(); }
        public int max(ItemStack s) { return s.getMaxStackSize(); }
        public ItemStack copy(ItemStack s, int amount) { var c = s.clone(); c.setAmount(amount); return c; }
    };

    private static RewardDelivery instance;
    private final MagicCodexBridge plugin;
    private final FishMailOutbox outbox;
    private final Map<String, FishMailOutbox.Entry> pending = new LinkedHashMap<>();
    private final Set<String> sending = new HashSet<>();
    private final Map<String, Integer> retries = new HashMap<>();
    private boolean closing;
    /** 이 서버 표시(mailbox-origin.txt 앞 8자리): 영수증 이름에 넣어 다른 서버가 이 서버의 영수증을 지우지 않게 한다. */
    private final String origin;

    RewardDelivery(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.origin = NpcSocialService.serverOrigin(plugin.getDataFolder().toPath()).replace("-", "").substring(0, 8);
        this.outbox = new FishMailOutbox(plugin.getDataFolder().toPath().resolve("reward-mail-outbox"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Bukkit.getScheduler().runTask(plugin, this::recover);
    }

    /** 공통 지급 함수 (없으면 null — 그때는 각 경로가 예전처럼 동작). */
    static RewardDelivery get() { return instance; }

    // ------------------------------------------------------------------ 칸 판정 (모든 지급 경로 공통)

    /** 지금 쪽 27칸(9~35)을 써도 되는지 — 서버 실제 배낭 상태. 메인 스레드. */
    static boolean mainUsable(MagicCodexBridge plugin, Player p) {
        var bag = plugin == null ? null : plugin.bagPagesBridge();
        return bag == null || bag.mainSlotsUsable(p);
    }

    /** 보관 칸 36개 중 넣을 수 있는 칸. */
    static boolean[] usable(MagicCodexBridge plugin, Player p) { return RewardPlan.usable(mainUsable(plugin, p)); }

    /**
     * storage(보관 칸 36개 사본)에 items 를 넣는다. 반환: 못 넣은 것. whole = 아이템 하나가 다 들어갈 때만 넣기(우편 첨부 수령).
     * storage 배열 자체를 고친다.
     */
    static List<ItemStack> place(ItemStack[] storage, boolean[] usable, List<ItemStack> items, boolean whole) {
        var slots = new ArrayList<ItemStack>(Arrays.asList(storage));
        var left = RewardPlan.placeAll(slots, usable, items, STACKS, whole);
        for (int i = 0; i < storage.length; i++) storage[i] = slots.get(i);
        return left;
    }

    /** 한 덩어리(우편 첨부 하나)를 통째로 넣거나 아무것도 바꾸지 않는다. */
    static boolean insertWhole(ItemStack[] storage, boolean[] usable, ItemStack item) { return place(storage, usable, List.of(item), true).isEmpty(); }

    static ItemStack[] copy(ItemStack[] source) { return Arrays.stream(source).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }

    // ------------------------------------------------------------------ 지급: 넣을 만큼 넣고 남은 것만 우편

    /**
     * 메인 스레드. 접속 중이면 넣을 수 있는 칸에 넣고 남은 것만 우편, 접속 중이 아니면 전부 우편.
     * source 는 우편 source_key 앞부분(경로마다 고유: 예 "popularity-refund:&lt;uuid&gt;:&lt;무작위&gt;"). mailed = 우편 저장 결과(우편이 있을 때만).
     */
    Given give(UUID owner, List<ItemStack> items, String source, String title, String body, Consumer<Boolean> mailed) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        var list = items.stream().filter(i -> i != null && !i.getType().isAir() && i.getAmount() > 0).map(ItemStack::clone).toList();
        if (list.isEmpty()) return Given.INVENTORY;
        Player p = Bukkit.getPlayer(owner);
        List<ItemStack> left;
        ItemStack[] next = null;
        if (p != null && p.isOnline() && !p.isDead() && plugin.playerStateReady(p)) {
            next = copy(p.getInventory().getStorageContents());
            left = place(next, usable(plugin, p), list, false);
            if (left.isEmpty()) {
                p.getInventory().setStorageContents(next);
                try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("REWARD saveData failed player=" + owner + " source=" + source + " (" + e + "); 다음 저장 때 함께 저장됨"); }
                return Given.INVENTORY;
            }
        } else left = list;
        boolean online = p != null && p.isOnline();
        // 오프라인 지급은 플레이어 데이터에 영수증을 남길 수 없다: 처음부터 우편만이므로 "영수증 없이 항상 보냄" 표시(reward_offline_)로 남긴다.
        String hash = UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
        String receipt = online ? "reward_mail_" + origin + "_" + hash : "reward_offline_" + hash;
        var entries = new ArrayList<FishMailOutbox.Entry>();
        var parts = RewardPlan.mailParts(left, STACKS);
        try {
            for (int part = 0; part < parts.size(); part++) {
                var bytes = new ArrayList<byte[]>();
                for (ItemStack it : parts.get(part)) bytes.add(it.serializeAsBytes());
                entries.add(outbox.save(source + ":" + part, owner, receipt, title, body, bytes, "", ""));
            }
        } catch (java.io.IOException | RuntimeException e) {
            for (var en : entries) try { outbox.acknowledge(en); } catch (java.io.IOException ignored) {}
            plugin.getLogger().severe("REWARD OUTBOX write failed player=" + owner + " source=" + source + " (" + e + ")");
            if (!online) { plugin.getLogger().severe("REWARD 지급 실패 (오프라인·우편 대기 저장 실패) — 직접 지급 필요: " + left); return Given.DROPPED; }
            // 대기 파일을 못 남겼다: 넣을 수 있는 만큼은 넣고 나머지는 발밑에 (사라지지 않게).
            if (next != null) p.getInventory().setStorageContents(next);
            for (ItemStack it : left) p.getWorld().dropItemNaturally(p.getLocation(), it);
            CmdKit.notify(p, "warning", "reward-drop", "보상 일부를 발밑에 떨어뜨렸어요", "우편함에 넣지 못했어요. 바로 주워 주세요.", 0);
            return Given.DROPPED;
        }
        if (online) {
            // 인벤토리 변화와 영수증을 같은 플레이어 데이터 스냅샷에. 영수증이 디스크에 없으면 이 지급은 일어나지 않은 것이다.
            if (next != null) p.getInventory().setStorageContents(next);
            p.getPersistentDataContainer().set(key(receipt), PersistentDataType.BYTE, (byte) 1);
            try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("REWARD saveData failed player=" + owner + " source=" + source + " (" + e + "); 다음 저장 때 함께 저장됨"); }
        }
        int[] remaining = {entries.size()}; boolean[] all = {true};
        for (var en : entries) {
            pending.put(en.source(), en);
            send(en, ok -> { all[0] &= ok; if (--remaining[0] == 0 && mailed != null) mailed.accept(all[0]); }, !online);
        }
        plugin.getLogger().info("REWARD OUTBOX queued player=" + owner + " source=" + source + " parts=" + entries.size());
        return Given.MAIL;
    }

    private NamespacedKey key(String receipt) { return new NamespacedKey(plugin, receipt); }

    /** offline = 영수증 없이 보낸 오프라인 지급(처음부터 우편만): 영수증 확인 없이 보낸다. */
    private void send(FishMailOutbox.Entry e, Consumer<Boolean> done, boolean offline) {
        if (!pending.containsKey(e.source()) || !sending.add(e.source())) { done.accept(false); return; }
        var service = Bukkit.getServicesManager().load(MailService.class);
        var stacks = new ArrayList<ItemStack>();
        try { for (byte[] b : e.items()) stacks.add(ItemStack.deserializeBytes(b)); }
        catch (RuntimeException bad) { sending.remove(e.source()); plugin.getLogger().severe("REWARD OUTBOX unreadable item; kept for admin source=" + e.source() + " (" + bad + ")"); done.accept(false); return; }
        java.util.concurrent.CompletableFuture<UUID> sent;
        try {
            if (service == null) throw new IllegalStateException("mail service unavailable");
            sent = service.sendSystem(e.source(), e.recipient(), e.title(), e.body(), stacks);
        } catch (RuntimeException failed) { sending.remove(e.source()); failed(e, failed, offline); done.accept(false); return; }
        sent.whenComplete((id, error) -> {
            boolean acked = false;
            if (error == null) try { outbox.acknowledge(e); acked = true; } catch (java.io.IOException io) { plugin.getLogger().warning("REWARD OUTBOX acknowledge failed source=" + e.source() + " (" + io + "); 다시 보내도 같은 우편으로 처리됨"); }
            boolean removed = acked;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                sending.remove(e.source());
                if (error != null) { failed(e, error, offline); done.accept(false); return; }
                if (removed) { pending.remove(e.source()); retries.remove(e.source()); }
                Player online = Bukkit.getPlayer(e.recipient());
                if (online != null && pending.values().stream().noneMatch(x -> x.receipt().equals(e.receipt()))) online.getPersistentDataContainer().remove(key(e.receipt()));
                plugin.getLogger().info("REWARD OUTBOX delivered player=" + e.recipient() + " source=" + e.source() + " mail=" + id);
                done.accept(true);
            });
        });
    }

    private void failed(FishMailOutbox.Entry e, Throwable error, boolean offline) {
        int n = retries.merge(e.source(), 1, Integer::sum);
        plugin.getLogger().warning("REWARD OUTBOX send failed (kept) player=" + e.recipient() + " source=" + e.source() + " try=" + n + " (" + error + ")");
        Player online = Bukkit.getPlayer(e.recipient());
        if (online != null && n == 1) CmdKit.notify(online, "warning", "reward-mail-wait", "보상 우편이 늦어지고 있어요", "보상은 안전하게 보관 중이고 자동으로 다시 보내요.", 3000);
        if (n <= 3 && plugin.isEnabled() && !closing) Bukkit.getScheduler().runTaskLater(plugin, () -> { var cur = pending.get(e.source()); if (cur != null) send(cur, ok -> {}, offline); }, 1200L);
    }

    // ------------------------------------------------------------------ 복구 (시작 · 접속)

    private static Path playerFile(UUID id) { return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("playerdata").resolve(id + ".dat"); }

    private void recover() {
        var broken = new ArrayList<Path>();
        try { for (var e : outbox.pending(broken)) pending.putIfAbsent(e.source(), e); }
        catch (java.io.IOException e) { plugin.getLogger().severe("REWARD OUTBOX read failed (" + e + "); records kept"); return; }
        if (!broken.isEmpty()) plugin.getLogger().severe("REWARD OUTBOX 읽을 수 없는 대기 파일 " + broken.size() + "개 (자동 처리 안 함, 관리자 확인 필요): " + broken);
        if (!pending.isEmpty()) plugin.getLogger().warning("REWARD OUTBOX 대기 중인 보상 우편 " + pending.size() + "건 다시 확인");
        for (var e : List.copyOf(pending.values())) {
            if (e.receipt().startsWith("reward_offline_")) { send(e, ok -> {}, true); continue; }
            if (Bukkit.getPlayer(e.recipient()) != null) continue;
            boolean saved;
            try { saved = DeliveryReceipt.saved(playerFile(e.recipient()), key(e.receipt()).toString()); }
            catch (java.io.IOException | RuntimeException x) { plugin.getLogger().warning("REWARD OUTBOX receipt check failed source=" + e.source() + " (" + x + "); 접속 때 다시 확인"); continue; }
            if (saved) send(e, ok -> {}, false);
        }
        for (Player p : Bukkit.getOnlinePlayers()) resolve(p);
    }

    /** 접속: 영수증이 있으면 다시 보내고, 없으면 지급 전에 멈춘 것이므로 대기 파일을 버린다. 남은 영수증은 지운다. */
    private void resolve(Player p) {
        var pdc = p.getPersistentDataContainer();
        for (var e : List.copyOf(pending.values())) {
            if (!e.recipient().equals(p.getUniqueId()) || sending.contains(e.source())) continue;
            if (e.receipt().startsWith("reward_offline_") || pdc.has(key(e.receipt()), PersistentDataType.BYTE)) { send(e, ok -> {}, false); continue; }
            try { outbox.acknowledge(e); pending.remove(e.source()); plugin.getLogger().warning("REWARD OUTBOX discarded (영수증 없음: 지급 전에 멈춤) player=" + p.getUniqueId() + " source=" + e.source()); }
            catch (java.io.IOException x) { plugin.getLogger().warning("REWARD OUTBOX discard failed source=" + e.source() + " (" + x + ")"); }
        }
        String ns = plugin.getName().toLowerCase(Locale.ROOT);
        for (var k : List.copyOf(pdc.getKeys()))
            if (k.getNamespace().equals(ns) && k.getKey().startsWith("reward_mail_" + origin + "_") && pending.values().stream().noneMatch(x -> x.receipt().equals(k.getKey()))) pdc.remove(k);
    }

    @EventHandler public void join(PlayerJoinEvent e) { resolve(e.getPlayer()); }

    @Override public void close() { closing = true; if (instance == this) instance = null; }
}
