package school.magiccodex.paper;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.MileageProtocol;
import school.magiccodex.protocol.MileageProtocol.*;

/**
 * 마일리지 상점 (2026-10-24, owner: "마일리지는 유저관리 명령어로 조절, 관리하기 편한 창도 하나, 구매하면 우편함으로").
 *
 * <ul>
 *   <li>Players: top menu 「마일리지샵」 or /마일리지상점 opens the window. A purchase debits mileage and the item arrives as system mail.</li>
 *   <li>Admins (OP): /상점관리 마일리지 opens the management window (add an item picked from the inventory, name/description/price/category,
 *       on sale or hidden, order, swap item, delete). /유저관리 &lt;닉네임&gt; 마일리지 보기·설정·추가·빼기·기록.</li>
 * </ul>
 * Storage is {@link MileageStore} on the shop/mailbox DB. All DB work runs on one IO thread; Bukkit calls return to the main thread.
 */
final class MileageBridge implements PluginMessageListener, Listener {
    private static final int QUEUE_LIMIT = 200;
    private final MagicCodexBridge plugin;
    private final MileageStore store;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { var t = new Thread(r, "MagicCodex-mileage-io"); t.setDaemon(true); return t; });
    /** Open windows: false = shop, true = admin. */
    private final Map<UUID, Boolean> sessions = new HashMap<>();
    private final Set<UUID> buying = new HashSet<>();
    /** Last known balance of online players (for /유저관리 보기 summary, main thread only). */
    private final Map<UUID, Long> cached = new HashMap<>();
    private final Set<String> delivering = new HashSet<>();
    /** Max quantity per purchase by product id (and by id#bytes-hash as a decode cache), main thread only. */
    private final Map<String, Integer> limit = new HashMap<>();
    private boolean closing;
    private int retryTask = -1;

    MileageBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        var dir = plugin.getDataFolder().toPath();
        try { store = io.submit(() -> new MileageStore(ShopMailboxDatabaseSettings.load(dir), dir.resolve("mileage.db"))).get(15, TimeUnit.SECONDS); }
        catch (Exception e) { io.shutdownNow(); throw e; }
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, MileageProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, MileageProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CmdKit.register(plugin, "/마일리지상점", this::playerCommands);
        CmdKit.register(plugin, "/상점관리 마일리지", this::adminCommands);
        CmdKit.register(plugin, "/유저관리 마일리지", this::userCommands);
        // Paid orders whose mail was not confirmed (crash, mailbox down): resend with the same source key, never a second mail.
        retryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> recover(null), 20 * 15, 20 * 300).getTaskId();
    }

    void close() {
        closing = true;
        if (retryTask >= 0) Bukkit.getScheduler().cancelTask(retryTask);
        sessions.clear();
        io.shutdown();
        try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); } catch (InterruptedException e) { io.shutdownNow(); Thread.currentThread().interrupt(); }
        try { store.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ async helper

    private <T> void work(Callable<T> task, Consumer<T> success, Consumer<String> fail) {
        if (closing || io.isShutdown()) { fail.accept("마일리지 상점을 사용할 수 없어요"); return; }
        if (io.getQueue().size() >= QUEUE_LIMIT) { fail.accept("마일리지 상점이 바빠요. 잠시 뒤 다시 시도해 주세요"); return; }
        try {
            io.execute(() -> {
                T value; String error = null;
                try { value = task.call(); }
                catch (IllegalStateException | IllegalArgumentException e) { value = null; error = e.getMessage() == null ? "처리하지 못했어요" : e.getMessage(); }
                catch (Exception e) { value = null; error = "마일리지 기록을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요"; plugin.getLogger().log(Level.WARNING, "Mileage DB work failed", e); }
                T v = value; String m = error;
                if (!closing) Bukkit.getScheduler().runTask(plugin, () -> { if (m == null) success.accept(v); else fail.accept(m); });
            });
        } catch (RejectedExecutionException e) { fail.accept("마일리지 상점을 사용할 수 없어요"); }
    }

    // ------------------------------------------------------------------ window

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(MileageProtocol.RESPONSE); }

    void open(Player p, boolean admin) {
        if (!modded(p)) { CmdKit.notify(p, "warning", "mileage-mod", "마일리지 상점을 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
        if (admin && !p.isOp()) return;
        sessions.put(p.getUniqueId(), admin);
        push(p, MileageProtocol.KIND_OPEN, "", "");
    }

    /** Reads catalogue + balance and sends a STATE to an open window (kind OPEN also opens it). */
    private void push(Player p, int kind, String select, String notice) {
        UUID id = p.getUniqueId();
        work(() -> new Object[]{store.products(), store.balance(id)}, v -> {
            Boolean admin = sessions.get(id);
            if (admin == null || !p.isOnline()) return;
            @SuppressWarnings("unchecked") var list = (List<MileageStore.Product>) v[0];
            long balance = (Long) v[1];
            cached.put(id, balance);
            var out = new ArrayList<Product>();
            for (var x : list) {
                if (!admin && !x.enabled()) continue;
                if (out.size() >= MileageProtocol.MAX_PRODUCTS) break;
                byte[] preview = x.bytes() != null && x.bytes().length <= MileageProtocol.MAX_PREVIEW_BYTES ? x.bytes() : new byte[0];
                limit.computeIfAbsent(x.id() + "#" + Arrays.hashCode(x.bytes()), k -> stackLimit(x.bytes()));
                limit.put(x.id(), limit.get(x.id() + "#" + Arrays.hashCode(x.bytes())));
                try { out.add(new Product(x.id(), x.category(), cut(x.name(), MileageProtocol.MAX_NAME), cut(x.description(), MileageProtocol.MAX_DESCRIPTION), x.price(), x.enabled(), preview)); }
                catch (IllegalArgumentException bad) { plugin.getLogger().warning("마일리지 상품을 보낼 수 없음: " + x.id()); }
            }
            send(p, new State(kind, admin, Math.min(balance, MileageProtocol.MAX_BALANCE), out, select, cut(notice, MileageProtocol.MAX_TEXT)));
        }, m -> { if (sessions.containsKey(id)) notify(p, m); });
    }
    private void send(Player p, Frame frame) {
        try { p.sendPluginMessage(plugin, MileageProtocol.RESPONSE, MileageProtocol.encode(frame)); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("마일리지 상점 패킷 실패: " + e.getMessage()); }
    }
    private void notify(Player p, String message) { CmdKit.notify(p, "warning", "mileage", message, "", 0); }
    private static String cut(String s, int max) { s = s == null ? "" : s.strip(); return s.length() <= max ? s : s.substring(0, max); }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (!channel.equals(MileageProtocol.REQUEST)) return;
        Request r;
        try { r = MileageProtocol.request(data); } catch (IllegalArgumentException bad) { return; }
        UUID id = p.getUniqueId();
        switch (r.action()) {
            case MileageProtocol.OPEN -> open(p, false);
            case MileageProtocol.ADMIN_OPEN -> { if (p.isOp()) open(p, true); }
            case MileageProtocol.CLOSE -> sessions.remove(id);
            case MileageProtocol.BUY -> buy(p, r);
            default -> admin(p, r);
        }
    }

    // ------------------------------------------------------------------ buying

    private void buy(Player p, Request r) {
        UUID id = p.getUniqueId();
        if (!Boolean.FALSE.equals(sessions.get(id))) return;
        if (!buying.add(id)) { send(p, new Result(r.operation(), false, cached.getOrDefault(id, 0L), "이전 구매를 처리하고 있어요")); return; }
        String key = id + ":" + r.operation();
        // stack limit: the mail holds at most 9 stacks of the product's item (known from the catalogue this window was sent)
        int max = limit.getOrDefault(r.product(), MileageProtocol.MAX_QUANTITY);
        if (r.quantity() > max) { buying.remove(id); send(p, new Result(r.operation(), false, cached.getOrDefault(id, 0L), "한 번에 " + max + "개까지 살 수 있어요")); return; }
        work(() -> store.buy(id, key, r.product(), r.quantity(), r.expected()), b -> {
            buying.remove(id);
            cached.put(id, b.balance());
            if (b.order() == null) {
                if (p.isOnline()) { send(p, new Result(r.operation(), false, b.balance(), cut(b.message(), MileageProtocol.MAX_TEXT))); push(p, MileageProtocol.KIND_STATE, "", ""); }
                return;
            }
            plugin.getLogger().info("MILEAGE BUY player=" + id + " order=" + b.order().id() + " product=" + b.order().product() + " qty=" + b.order().quantity() + " total=" + b.order().total() + " balance=" + b.balance());
            AuditLog.log(p, AuditLog.마일리지, "마일리지샵 구매 · " + b.order().title() + " x" + b.order().quantity() + " · " + MileageProtocol.amount(b.order().total()),
                    "상품", b.order().product(), "이름", b.order().title(), "수량", b.order().quantity(), "합계", b.order().total(), "남은마일리지", b.balance(), "주문", b.order().id());
            deliver(b.order(), ok -> {
                if (!p.isOnline()) return;
                send(p, new Result(r.operation(), true, b.balance(), ok ? "" : "구매는 완료됐어요. 우편이 곧 도착해요"));
            });
        }, m -> { buying.remove(id); if (p.isOnline()) send(p, new Result(r.operation(), false, cached.getOrDefault(id, 0L), cut(m, MileageProtocol.MAX_TEXT))); });
    }

    private static int stackLimit(byte[] bytes) {
        try { return Math.min(MileageProtocol.MAX_QUANTITY, 9 * Math.max(1, ItemStack.deserializeBytes(bytes).getMaxStackSize())); }
        catch (RuntimeException e) { return 9; }
    }

    private List<ItemStack> stacks(MileageStore.Order o) {
        var sample = ItemStack.deserializeBytes(o.bytes());
        sample.setAmount(1);
        var out = new ArrayList<ItemStack>();
        int left = o.quantity(), max = Math.max(1, sample.getMaxStackSize());
        while (left > 0 && out.size() < 9) { var s = sample.clone(); s.setAmount(Math.min(left, max)); left -= s.getAmount(); out.add(s); }
        return out;
    }

    /** Creates the mail (idempotent by source key) and marks the order done. done.accept(true) once the mail is saved. */
    private void deliver(MileageStore.Order o, Consumer<Boolean> done) {
        if (!delivering.add(o.id())) { done.accept(false); return; }
        MailService mail = Bukkit.getServicesManager().load(MailService.class);
        CompletableFuture<UUID> sent;
        try {
            if (mail == null) throw new IllegalStateException("mail service missing");
            String title = cut(o.title(), 70) + " 구매";
            sent = mail.sendSystem("mileage:" + o.id(), o.owner(), title,
                    "마일리지 상점에서 " + o.quantity() + "개를 구매했어요. (마일리지 " + MileageProtocol.amount(o.total()) + " 사용)", stacks(o));
        } catch (RuntimeException e) {
            delivering.remove(o.id());
            plugin.getLogger().log(Level.WARNING, "Mileage mail failed for order " + o.id() + " (will retry)", e);
            done.accept(false);
            return;
        }
        sent.whenComplete((mailId, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                delivering.remove(o.id());
                plugin.getLogger().warning("Mileage mail failed for order " + o.id() + " (will retry): " + error);
                done.accept(false);
                return;
            }
            work(() -> { store.done(o.id()); return true; }, v -> { delivering.remove(o.id()); done.accept(true); },
                    m -> { delivering.remove(o.id()); done.accept(true); });
        }));
    }

    private void recover(UUID owner) {
        if (closing) return;
        work(() -> store.paid(owner, 50), list -> {
            for (var o : list) deliver(o, ok -> { if (ok) plugin.getLogger().info("MILEAGE RECOVER order=" + o.id() + " player=" + o.owner() + " mail confirmed"); });
        }, m -> {});
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { recover(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { UUID id = e.getPlayer().getUniqueId(); sessions.remove(id); buying.remove(id); cached.remove(id); }

    // ------------------------------------------------------------------ admin window

    private void admin(Player p, Request r) {
        if (!p.isOp() || !Boolean.TRUE.equals(sessions.get(p.getUniqueId()))) return;
        String admin = p.getName();
        switch (r.action()) {
            case MileageProtocol.ADMIN_ADD -> {
                byte[] bytes = slotItem(p, r.slot());
                if (bytes == null) return;
                var e = r.edit();
                work(() -> store.add(e.category(), e.name().strip(), e.description().strip(), e.price(), e.enabled(), bytes), id -> {
                    plugin.getLogger().info("MILEAGE ADMIN add admin=" + admin + " product=" + id + " name=" + e.name() + " price=" + e.price());
                    refreshAll(p, id, "상품을 추가했어요");
                }, m -> push(p, MileageProtocol.KIND_STATE, "", m));
            }
            case MileageProtocol.ADMIN_SAVE -> {
                var e = r.edit();
                work(() -> store.save(e.id(), e.category(), e.name().strip(), e.description().strip(), e.price(), e.enabled()), ok -> {
                    plugin.getLogger().info("MILEAGE ADMIN save admin=" + admin + " product=" + e.id() + " name=" + e.name() + " price=" + e.price() + " enabled=" + e.enabled() + " category=" + e.category());
                    refreshAll(p, e.id(), ok ? "저장했어요" : "이미 삭제된 상품이에요");
                }, m -> push(p, MileageProtocol.KIND_STATE, "", m));
            }
            case MileageProtocol.ADMIN_ITEM -> {
                byte[] bytes = slotItem(p, r.slot());
                if (bytes == null) return;
                work(() -> store.replaceItem(r.product(), bytes), ok -> {
                    plugin.getLogger().info("MILEAGE ADMIN item admin=" + admin + " product=" + r.product());
                    refreshAll(p, r.product(), ok ? "아이템을 바꿨어요" : "이미 삭제된 상품이에요");
                }, m -> push(p, MileageProtocol.KIND_STATE, "", m));
            }
            case MileageProtocol.ADMIN_DELETE -> work(() -> store.delete(r.product()), ok -> {
                plugin.getLogger().info("MILEAGE ADMIN delete admin=" + admin + " product=" + r.product());
                refreshAll(p, "", ok ? "상품을 삭제했어요" : "이미 삭제된 상품이에요");
            }, m -> push(p, MileageProtocol.KIND_STATE, "", m));
            case MileageProtocol.ADMIN_MOVE -> work(() -> store.move(r.product(), r.step() == 1), ok -> refreshAll(p, r.product(), ""), m -> push(p, MileageProtocol.KIND_STATE, "", m));
            default -> {}
        }
    }
    /**
     * The item in the admin's inventory slot (0..8 hotbar, 9..35 main — owner 2026-10-24: 내 인벤토리에서 선택) as a
     * one-item template, or null (the window gets a fresh state with the reason, which also clears its busy flag).
     * The item stays in the inventory.
     */
    private byte[] slotItem(Player p, int slot) {
        var item = slot >= 0 && slot < MileageProtocol.SLOTS ? p.getInventory().getItem(slot) : null;
        if (item == null || item.getType().isAir()) { push(p, MileageProtocol.KIND_STATE, "", "고른 칸에 아이템이 없어요. 다시 골라 주세요"); return null; }
        var copy = item.clone();
        copy.setAmount(1);
        byte[] bytes = copy.serializeAsBytes();
        if (bytes.length > MileageProtocol.MAX_PREVIEW_BYTES) { push(p, MileageProtocol.KIND_STATE, "", "아이템 데이터가 너무 커요"); return null; }
        return bytes;
    }
    /** After a catalogue change: the admin gets the new state, every open shop window too. */
    private void refreshAll(Player admin, String select, String notice) {
        for (var e : new ArrayList<>(sessions.entrySet())) {
            var p = Bukkit.getPlayer(e.getKey());
            if (p == null) { sessions.remove(e.getKey()); continue; }
            if (p.equals(admin)) push(p, MileageProtocol.KIND_STATE, select, notice);
            else push(p, MileageProtocol.KIND_STATE, "", "");
        }
    }

    // ------------------------------------------------------------------ commands

    private void playerCommands() {
        ChacaApi.playerCommand(plugin, CmdKit.literal("마일리지상점").executes(CmdKit.run(ctx -> open(CmdKit.self(ctx), false))), "마일리지 상점 열기", List.of("마일리지샵"));
    }
    private void adminCommands() {
        ChacaApi.branch(plugin, ChacaApi.상점관리, CmdKit.literal("마일리지").executes(CmdKit.run(ctx -> open(CmdKit.self(ctx), true))),
                "마일리지 상점 관리 창 (인벤토리 아이템 추가·가격·분류·판매 여부·순서)", "마일리지");
    }
    private void userCommands() {
        var root = CmdKit.literal("마일리지")
                .then(CmdKit.literal("보기").executes(CmdKit.run(ctx -> {
                    UUID id = ChacaApi.target(ctx); var to = CmdKit.sender(ctx);
                    work(() -> store.balance(id), v -> { cached.computeIfPresent(id, (k, o) -> v); CmdKit.lines(to, List.of("── " + CmdKit.name(id) + " 마일리지 ──", MileageProtocol.amount(v))); },
                            m -> CmdKit.userFail(to, m));
                })))
                .then(CmdKit.literal("기록").executes(CmdKit.run(ctx -> {
                    UUID id = ChacaApi.target(ctx); var to = CmdKit.sender(ctx);
                    work(() -> store.recent(id, 10), list -> {
                        var lines = new ArrayList<String>();
                        lines.add("── " + CmdKit.name(id) + " 마일리지 구매 기록 (최근 10건) ──");
                        if (list.isEmpty()) lines.add("기록이 없어요");
                        for (var o : list) lines.add(QuestBridge.timeText(o.created()) + " | " + o.title() + " ×" + o.quantity() + " | " + "마일리지 " + MileageProtocol.amount(o.total()) + " | "
                                + (o.state().equals("done") ? "우편 보냄" : "우편 대기(자동 재전송)"));
                        CmdKit.lines(to, lines);
                    }, m -> CmdKit.userFail(to, m));
                })));
        for (String change : AdminCommandRules.CHANGES)
            root.then(CmdKit.literal(change).then(CmdKit.argument("값", LongArgumentType.longArg(0, MileageProtocol.MAX_BALANCE)).executes(CmdKit.run(ctx -> changeBalance(ctx, change)))));
        ChacaApi.branch(plugin, ChacaApi.유저관리, root, "마일리지 보기·설정·추가·빼기·구매 기록", "마일리지 보기");
        ChacaApi.summary(plugin, "마일리지", id -> { Long v = cached.get(id); return v == null ? "" : MileageProtocol.amount(v); });
    }
    /**
     * 다른 기능(별풍선 후원)의 마일리지 지급: /유저관리 &lt;닉&gt; 마일리지 추가 와 같은 저장(같은 IO 스레드·같은 표). 메인 스레드에서 부르고,
     * 결과도 메인 스레드: ok = {전, 후}, fail = 유저용 문구. 기록(운영 기록)은 부른 쪽이 남긴다.
     */
    void credit(UUID id, long value, Consumer<long[]> ok, Consumer<String> fail) {
        if (value <= 0 || value > MileageProtocol.MAX_BALANCE) { fail.accept("마일리지 값이 맞지 않아요"); return; }
        work(() -> store.change(id, "추가", value), v -> {
            plugin.getLogger().info("MILEAGE CREDIT player=" + id + " value=" + value + " before=" + v[0] + " after=" + v[1]);
            var p = Bukkit.getPlayer(id);
            if (p != null) { cached.put(id, v[1]); if (sessions.containsKey(id)) push(p, MileageProtocol.KIND_STATE, "", ""); }
            ok.accept(v);
        }, fail);
    }

    private void changeBalance(CommandContext<CommandSourceStack> ctx, String change) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        long value = LongArgumentType.getLong(ctx, "값");
        CommandSender to = CmdKit.sender(ctx);
        work(() -> store.change(id, change, value), v -> {
            plugin.getLogger().info("MILEAGE ADMIN admin=" + to.getName() + " player=" + id + " action=" + change + " value=" + value + " before=" + v[0] + " after=" + v[1]);
            long delta = v[1] - v[0];
            AuditLog.log(id, AuditLog.마일리지, "관리자 마일리지 " + change + " " + value + " (" + v[0] + " → " + v[1] + ") · " + to.getName(),
                    "관리자", to.getName(), "변경", change, "값", value, "전", v[0], "후", v[1]);
            CmdKit.ok(to, CmdKit.name(id) + " 님 마일리지 " + MileageProtocol.amount(v[1]) + " (" + (delta >= 0 ? "+" : "") + MileageProtocol.amount(delta) + ")");
            var p = Bukkit.getPlayer(id);
            if (p != null) { cached.put(id, v[1]); if (sessions.containsKey(id)) push(p, MileageProtocol.KIND_STATE, "", ""); }
        }, m -> CmdKit.userFail(to, m));
    }
}
