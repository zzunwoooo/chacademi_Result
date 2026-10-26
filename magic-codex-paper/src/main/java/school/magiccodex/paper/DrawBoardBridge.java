package school.magiccodex.paper;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.DrawBoardProtocol;
import school.magiccodex.protocol.DrawBoardProtocol.*;

/**
 * 운영자 전용 「행운의 뽑기판」 100칸 (오너 디자인 납품 2026-10-26, FINAL_PATCHES §44). MagicCodexBridge 안의 기능 하나.
 *
 * <ul>
 *   <li>/뽑기판 = 판 창 열기, /뽑기판 관리 = 등록 창(인벤토리에서 등록·삭제), /뽑기판 초기화 = 새로 섞기, 목록·등록·삭제·비우기.
 *       OP(또는 {@value #PERMISSION})만 — 일반 유저는 자동완성에도 안 보인다. 창 안의 모든 요청도 서버가 권한을 다시 본다.</li>
 *   <li>판(등록 100개·섞인 배치·공개 칸·회차)은 공유 DB({@link DrawBoardStore}) → 학교·야생·경기장 어디서 열어도 같은 판.
 *       DB 는 전용 작업 스레드 하나에서만. 게임 스레드는 패킷 만들기와 아이템 미리보기(목록이 바뀔 때만)만.</li>
 *   <li>창을 연 사람이 이 서버에 있으면 1초마다 판 줄 하나(SELECT 1줄)를 읽어 바뀐 경우에만 보낸다 (다른 서버의 변경 반영).
 *       이 서버에서 바뀐 것은 바로 보낸다. 창을 연 사람이 없으면 아무것도 하지 않는다.</li>
 *   <li>100칸이 모두 공개되면(누른 횟수가 아니라 지금 공개된 칸 수) {@link DrawBoardProtocol#HOLD_MS} 동안 그대로 보여 준 뒤
 *       같은 100개를 다시 섞어 다음 회차로. 넘기기는 revision 비교라 여러 서버가 동시에 해도 정확히 한 번.</li>
 *   <li>아이템 지급·소모·비용·확률은 없다 (보여 주기만).</li>
 * </ul>
 */
final class DrawBoardBridge implements Listener, PluginMessageListener {
    static final String PERMISSION = "magiccodex.drawboard.admin";
    private static final int ACTIONS_PER_SECOND = 15;

    private final MagicCodexBridge plugin;
    private final DrawBoardStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-draw-board"); t.setDaemon(true); return t; });
    private final BukkitTask poller;
    private volatile boolean closed;

    private record Viewer(Player player, long session, long catalogRevision, long sentRevision) {}
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, long[]> rate = new HashMap<>();

    /** 마지막으로 읽은 판과 등록 목록 (게임 스레드). */
    private DrawBoardRules.Board board;
    private List<DrawBoardStore.Item> items = List.of();
    private long itemsRevision = -1;
    private Map<String, Integer> index = Map.of();
    private List<Entry> previews = List.of();
    private volatile long knownItemsRevision = -1;
    private boolean polling;
    private long finishScheduledFor = -1;

    private record Snapshot(DrawBoardRules.Board board, List<DrawBoardStore.Item> items) {}

    DrawBoardBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        var settings = GenericDatabaseFile.load(plugin.getDataFolder().toPath());
        try {
            store = io.submit(() -> new DrawBoardStore(settings, plugin.getDataFolder().toPath().resolve("draw-board.db"), new SplittableRandom())).get(15, TimeUnit.SECONDS);
        } catch (Exception e) { io.shutdownNow(); throw e; }
        var m = Bukkit.getMessenger();
        m.registerOutgoingPluginChannel(plugin, DrawBoardProtocol.STATE);
        m.registerIncomingPluginChannel(plugin, DrawBoardProtocol.ACTION, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        poller = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20, 20);
        CmdKit.register(plugin, "/뽑기판", this::commands);
        plugin.getLogger().info("행운의 뽑기판: " + (store.shared() ? "공유 DB" : "SQLite draw-board.db (이 서버)"));
    }

    @EventHandler public void onDisable(PluginDisableEvent event) { if (event.getPlugin() == plugin) close(); }

    void close() {
        if (closed) return;
        closed = true;
        poller.cancel();
        viewers.clear();
        io.execute(() -> { try { store.close(); } catch (Exception e) { plugin.getLogger().warning("뽑기판 저장소 종료 실패: " + e); } });
        io.shutdown();
        try { if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("뽑기판 DB 종료 대기 초과"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean allowed(CommandSender s) { return !(s instanceof Player p) || p.isOp() || p.hasPermission(PERMISSION); }
    private static boolean admin(CommandSourceStack source) { return allowed(source.getSender()); }

    // ------------------------------------------------------------------ DB 작업 (작업 스레드) → 게임 스레드

    private interface Work<T> { T run() throws Exception; }

    /** 작업 스레드에서 work, 끝나면 게임 스레드에서 done(결과) 또는 failed(오류). */
    private <T> void async(Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
        if (closed) return;
        try {
            io.execute(() -> {
                T value; Exception error = null;
                try { value = work.run(); } catch (Exception e) { value = null; error = e; }
                if (closed || !plugin.isEnabled()) return;
                final T v = value; final Exception err = error;
                Bukkit.getScheduler().runTask(plugin, () -> { if (err == null) done.accept(v); else failed.accept(err); });
            });
        } catch (RejectedExecutionException e) { failed.accept(e); }
    }

    /** 판 + (목록이 바뀌었으면) 목록. 목록은 판 줄의 items_revision 이 같은 동안 읽은 것만 쓴다. */
    private Snapshot read(boolean forceItems) throws Exception {
        for (int attempt = 0; ; attempt++) {
            var b = store.board();
            if (!forceItems && b.itemsRevision() == knownItemsRevision) return new Snapshot(b, null);
            var list = store.items();
            var again = store.board();
            if (again.itemsRevision() == b.itemsRevision() || attempt >= 3) return new Snapshot(again, list);
        }
    }

    /** 읽은 것을 게임 스레드 상태에 반영 (오래된 판은 무시), 미리보기는 목록이 바뀔 때만 다시 만든다. */
    private void apply(Snapshot s) {
        if (board != null && s.board().revision() < board.revision()) return;   // 더 오래된 읽기
        board = s.board();
        if (s.items() != null) {
            items = List.copyOf(s.items());
            itemsRevision = board.itemsRevision();
            var map = new HashMap<String, Integer>();
            var list = new ArrayList<Entry>();
            for (int i = 0; i < items.size(); i++) {
                var it = items.get(i);
                map.put(it.id(), i);
                list.add(new Entry(it.id(), preview(it.bytes())));
            }
            index = Map.copyOf(map);
            previews = List.copyOf(list);
        }
        knownItemsRevision = board.itemsRevision() == itemsRevision ? itemsRevision : -1;   // 목록이 더 새로우면 다음 읽기에서 목록도
        scheduleFinish();
    }

    private void apply(DrawBoardRules.Board b) { apply(new Snapshot(b, null)); }

    /** 100칸 공개 → HOLD_MS 뒤 다음 판 (이 서버가 한 번만 예약; 다른 서버가 먼저 넘겨도 revision 비교로 아무 일 없음). */
    private void scheduleFinish() {
        if (board == null || !board.complete() || finishScheduledFor == board.revision()) return;
        long expect = board.revision();
        finishScheduledFor = expect;
        long wait = Math.max(0, DrawBoardProtocol.HOLD_MS - Math.max(0, System.currentTimeMillis() - board.completedAt()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> async(() -> store.finishRound(expect, "server", System.currentTimeMillis()),
                b -> { apply(b); push(null, 0, ""); }, e -> plugin.getLogger().warning("뽑기판 다음 판 넘기기 실패: " + e)), Math.max(1, wait / 50));
    }

    /** 1초마다: 창을 연 사람이 있을 때만 판 줄 하나를 읽어 바뀐 경우에만 보낸다. */
    private void poll() {
        viewers.values().removeIf(v -> !v.player().isOnline() || Bukkit.getPlayer(v.player().getUniqueId()) != v.player() || !allowed(v.player()));
        if (viewers.isEmpty() || polling || closed) return;
        polling = true;
        async(() -> read(false), s -> { polling = false; apply(s); push(null, 0, ""); },
                e -> { polling = false; plugin.getLogger().warning("뽑기판 읽기 실패: " + e); });
    }

    // ------------------------------------------------------------------ 보내기

    private byte[] preview(byte[] raw) {
        try {
            var it = ItemStack.deserializeBytes(raw);
            byte[] b = plugin.coreAppearance(it.clone()).serializeAsBytes();
            if (b.length > DrawBoardProtocol.MAX_ITEM) b = new ItemStack(it.getType(), it.getAmount()).serializeAsBytes();
            return b.length > DrawBoardProtocol.MAX_ITEM ? new byte[0] : b;
        } catch (RuntimeException e) { return new byte[0]; }
    }

    private State state(Viewer v, int screen, long sequence, String message) {
        var b = board;
        boolean ready = b != null && b.ready() && b.itemsRevision() == itemsRevision;
        var cells = ready ? DrawBoardRules.visible(b, index) : List.<Cell>of();
        String text = message.length() > 240 ? message.substring(0, 237) + "…" : message;
        return new State(screen, v.session(), sequence, b == null ? 0 : b.revision(), b == null ? 0 : b.round(), itemsRevision, Math.min(DrawBoardProtocol.CELLS, items.size()),
                ready, ready && b.complete(), cells, text);
    }

    /** 한 사람(only) 또는 창을 연 모두에게: 목록이 바뀌었으면 목록 조각들, 판이 바뀌었거나 답장이면 상태. */
    private void push(Player only, long sequence, String message) { push(only, 0, sequence, message, false); }

    private void push(Player only, int screen, long sequence, String message, boolean force) {
        if (board == null) return;
        for (var e : new ArrayList<>(viewers.entrySet())) {
            var v = e.getValue();
            boolean target = only != null && v.player() == only;
            boolean stale = v.sentRevision() != board.revision() || v.catalogRevision() != itemsRevision;
            if (!target && !stale) continue;
            try {
                if (v.catalogRevision() != itemsRevision || (target && force))
                    for (var c : DrawBoardProtocol.split(v.session(), itemsRevision, previews, DrawBoardProtocol.CATALOG_BUDGET))
                        v.player().sendPluginMessage(plugin, DrawBoardProtocol.STATE, DrawBoardProtocol.encode(c));
                v.player().sendPluginMessage(plugin, DrawBoardProtocol.STATE, DrawBoardProtocol.encode(state(v, target ? screen : 0, target ? sequence : 0, target ? message : "")));
                e.setValue(new Viewer(v.player(), v.session(), itemsRevision, board.revision()));
            } catch (IllegalArgumentException ex) { plugin.getLogger().warning("뽑기판 패킷 실패: " + ex.getMessage()); }
        }
    }

    // ------------------------------------------------------------------ 창 열기 · 요청

    private void open(Player p, int screen) throws CommandSyntaxException {
        if (!p.getListeningPluginChannels().contains(DrawBoardProtocol.STATE)) throw ChacaApi.error("뽑기판 창이 있는 차카데미 모드가 필요해요");
        long session = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        viewers.put(p.getUniqueId(), new Viewer(p, session, -1, -1));
        async(() -> read(board == null), s -> {
            apply(s);
            var v = viewers.get(p.getUniqueId());
            if (v != null && v.session() == session) push(p, screen, 0, "", true);
        }, e -> { viewers.remove(p.getUniqueId()); CmdKit.fail(p, "뽑기판을 불러오지 못했어요 (DB 연결 확인)", e); });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { viewers.remove(event.getPlayer().getUniqueId()); rate.remove(event.getPlayer().getUniqueId()); }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (closed || !DrawBoardProtocol.ACTION.equals(channel)) return;
        Action a;
        try { a = DrawBoardProtocol.action(bytes); } catch (IllegalArgumentException e) { return; }
        var v = viewers.get(p.getUniqueId());
        if (v == null || v.player() != p || v.session() != a.session()) return;
        if (!allowed(p)) { viewers.remove(p.getUniqueId()); return; }
        if (a.action() == DrawBoardProtocol.CLOSE) { viewers.remove(p.getUniqueId()); return; }
        long now = System.currentTimeMillis();
        long[] r = rate.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        if (now - r[0] >= 1000) { r[0] = now; r[1] = 0; }
        if (++r[1] > ACTIONS_PER_SECOND) return;
        String by = p.getName();
        Consumer<Exception> fail = e -> { plugin.getLogger().warning("뽑기판 요청 실패 (" + p.getName() + "): " + e); reply(p, a.sequence(), "처리하지 못했어요 (DB 연결 확인). 잠시 뒤 다시 해 주세요"); };
        switch (a.action()) {
            case DrawBoardProtocol.FLIP -> async(() -> store.flip(a.round(), a.cell(), a.reveal(), by, now), f -> {
                apply(f.board());
                String message = switch (f.flip().outcome()) {
                    case STALE_ROUND -> "새 판이 시작됐어요";
                    case HOLDING -> "모두 공개됐어요 · 곧 새 판";
                    case NOT_READY -> "등록된 아이템이 100개가 아니에요";
                    default -> "";
                };
                push(null, 0, "");
                reply(p, a.sequence(), message);
            }, fail);
            case DrawBoardProtocol.ADD -> {
                byte[] raw;
                try { raw = picked(p, a).serializeAsBytes(); }
                catch (IllegalArgumentException e) { reply(p, a.sequence(), e.getMessage()); return; }
                async(() -> { var added = store.add(raw, by, now); return new Object[]{added, read(true)}; }, o -> {
                    var added = (DrawBoardStore.Added) o[0];
                    apply((Snapshot) o[1]);
                    String message = added.result() == DrawBoardStore.AddResult.FULL ? "이미 100개가 등록돼 있어요"
                            : items.size() == DrawBoardProtocol.CELLS ? "등록했어요 · 100개가 모여 새 판을 섞었어요" : "등록했어요 (" + items.size() + "/100)";
                    if (added.result() == DrawBoardStore.AddResult.ADDED) AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 아이템 등록 (" + items.size() + "/100)");
                    push(null, 0, ""); reply(p, a.sequence(), message);
                }, fail);
            }
            case DrawBoardProtocol.REMOVE -> async(() -> { store.remove(a.id(), by, now); return read(true); }, s -> {
                apply(s);
                AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 아이템 삭제 (" + items.size() + "/100)");
                push(null, 0, ""); reply(p, a.sequence(), "지웠어요 (" + items.size() + "/100)" + (items.size() < DrawBoardProtocol.CELLS ? " · 100개가 될 때까지 판은 멈춰요" : ""));
            }, fail);
            case DrawBoardProtocol.RESHUFFLE -> async(() -> { store.reshuffle(by, now); return read(true); }, s -> {
                apply(s);
                AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 새로 섞기");
                push(null, 0, ""); reply(p, a.sequence(), board.ready() ? "새로 섞었어요 · " + board.round() + "회차" : "등록된 아이템이 100개가 아니라 섞지 못했어요 (" + items.size() + "/100)");
            }, fail);
            case DrawBoardProtocol.REFRESH -> async(() -> read(true), s -> { apply(s); push(p, 0, a.sequence(), "", true); }, fail);
            default -> { }
        }
    }

    /** 요청한 사람에게 답장(상태 + 한 줄). 창이 이미 닫혔으면 아무것도 안 함. */
    private void reply(Player p, long sequence, String message) {
        var v = viewers.get(p.getUniqueId());
        if (v == null || board == null) return;
        try {
            p.sendPluginMessage(plugin, DrawBoardProtocol.STATE, DrawBoardProtocol.encode(state(v, 0, sequence, message == null ? "" : message)));
            viewers.put(p.getUniqueId(), new Viewer(p, v.session(), v.catalogRevision(), board.revision()));
        } catch (IllegalArgumentException e) { plugin.getLogger().warning("뽑기판 패킷 실패: " + e.getMessage()); }
    }

    /** 고른 내 인벤토리 칸(서버의 실제 칸). 고른 뒤 그 칸이 바뀌었으면 거절 (상자·상점 관리 창과 같은 방식). */
    private static ItemStack picked(Player p, Action a) {
        var item = p.getInventory().getItem(a.slot());
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("고른 칸이 비어 있어요. 다시 골라 주세요");
        if (!item.getType().getKey().toString().equals(a.slotItem())) throw new IllegalArgumentException("고른 칸의 아이템이 바뀌었어요. 다시 골라 주세요");
        return item.clone();
    }

    // ------------------------------------------------------------------ 명령어 /뽑기판

    private static String name(byte[] raw) {
        try {
            var it = ItemStack.deserializeBytes(raw);
            String s = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.displayName());
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
            return (s.length() > 60 ? s.substring(0, 60) : s) + (it.getAmount() > 1 ? " ×" + it.getAmount() : "");
        } catch (RuntimeException e) { return "(읽을 수 없는 아이템)"; }
    }

    private Collection<String> numbers() {
        var out = new ArrayList<String>();
        for (int i = 1; i <= items.size(); i++) out.add(Integer.toString(i));
        return out;
    }

    private void done(CommandSender to, Snapshot s, String message) {
        apply(s);
        push(null, 0, "");
        CmdKit.ok(to, message);
    }

    private void commands() {
        var root = CmdKit.literal("뽑기판").requires(DrawBoardBridge::admin)
                .executes(CmdKit.run(ctx -> open(CmdKit.self(ctx), DrawBoardProtocol.SCREEN_BOARD)));
        root.then(CmdKit.literal("관리").executes(CmdKit.run(ctx -> open(CmdKit.self(ctx), DrawBoardProtocol.SCREEN_ADMIN))));
        root.then(CmdKit.literal("초기화").executes(CmdKit.run(ctx -> {
            var sender = CmdKit.sender(ctx);
            String by = sender.getName();
            async(() -> { store.reshuffle(by, System.currentTimeMillis()); return read(true); }, s -> {
                if (sender instanceof Player p) AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 새로 섞기 (명령)");
                done(sender, s, board.ready() ? "뽑기판을 새로 섞었어요 · " + board.round() + "회차 (모두 가림)" : "등록된 아이템이 " + items.size() + "/100개라 판을 만들 수 없어요 · /뽑기판 관리");
            }, e -> CmdKit.fail(sender, "뽑기판을 섞지 못했어요 (DB 연결 확인)", e));
        })));
        root.then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> {
            var sender = CmdKit.sender(ctx);
            async(() -> read(true), s -> {
                apply(s);
                var out = new ArrayList<String>();
                out.add("── 행운의 뽑기판 등록 " + items.size() + "/100 · " + (board.ready() ? board.round() + "회차 · 공개 " + board.revealedCount() + "칸" : "판 없음 (100개가 되면 시작)") + " ──");
                for (int i = 0; i < items.size(); i++) out.add(" " + (i + 1) + ". " + name(items.get(i).bytes()));
                if (items.isEmpty()) out.add(" (없음) /뽑기판 관리 → 인벤토리에서 등록, 또는 손에 들고 /뽑기판 등록");
                CmdKit.lines(sender, out);
            }, e -> CmdKit.fail(sender, "뽑기판 목록을 읽지 못했어요 (DB 연결 확인)", e));
        })));
        root.then(CmdKit.literal("등록").executes(CmdKit.run(ctx -> {
            var p = CmdKit.self(ctx);
            var held = p.getInventory().getItemInMainHand();
            if (held.getType().isAir()) throw ChacaApi.error("등록할 아이템을 주 손에 들어 주세요");
            byte[] raw = held.clone().serializeAsBytes();
            async(() -> { var added = store.add(raw, p.getName(), System.currentTimeMillis()); return new Object[]{added, read(true)}; }, o -> {
                var added = (DrawBoardStore.Added) o[0];
                if (added.result() == DrawBoardStore.AddResult.FULL) { apply((Snapshot) o[1]); CmdKit.userFail(p, "이미 100개가 등록돼 있어요 · /뽑기판 삭제 <번호>"); return; }
                AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 아이템 등록 (명령)");
                done(p, (Snapshot) o[1], "등록했어요 (" + ((Snapshot) o[1]).items().size() + "/100)");
            }, e -> CmdKit.fail(p, "등록하지 못했어요 (DB 연결 확인)", e));
        })));
        root.then(CmdKit.literal("삭제").then(CmdKit.word("번호", this::numbers).executes(CmdKit.run(ctx -> {
            var sender = CmdKit.sender(ctx);
            int n;
            try { n = Integer.parseInt(CmdKit.text(ctx, "번호")); } catch (NumberFormatException e) { throw ChacaApi.error("번호는 숫자로 (/뽑기판 목록)"); }
            async(() -> {
                var list = store.items();
                if (n < 1 || n > list.size()) return null;
                store.remove(list.get(n - 1).id(), sender.getName(), System.currentTimeMillis());
                return read(true);
            }, s -> {
                if (s == null) { CmdKit.userFail(sender, "그 번호가 없어요 (/뽑기판 목록)"); return; }
                if (sender instanceof Player p) AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 아이템 삭제 (명령) " + n + "번");
                done(sender, s, n + "번을 지웠어요 (" + s.items().size() + "/100)");
            }, e -> CmdKit.fail(sender, "지우지 못했어요 (DB 연결 확인)", e));
        }))));
        root.then(CmdKit.confirm(CmdKit.literal("비우기"), ctx -> {
            var sender = CmdKit.sender(ctx);
            async(() -> { store.clear(sender.getName(), System.currentTimeMillis()); return read(true); }, s -> {
                if (sender instanceof Player p) AuditLog.log(p, AuditLog.관리자, "행운의 뽑기판 등록 전부 삭제");
                done(sender, s, "등록 아이템을 모두 지웠어요");
            }, e -> CmdKit.fail(sender, "지우지 못했어요 (DB 연결 확인)", e));
        }));
        ChacaApi.playerCommand(plugin, root, "(관리자) 행운의 뽑기판 100칸 · 관리·초기화·목록·등록·삭제·비우기", List.of());
    }
}
