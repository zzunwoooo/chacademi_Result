package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import school.magiccodex.database.DatabaseSettings;

/**
 * 인기도 (메모 #87, 오너 2026-10-26). 정보창(StatsBridge)의 올리기·내리기 버튼과 관리 명령.
 * <ul>
 *   <li>같은 상대에게 하루 1번 (상대마다 따로). 하루 기준 = NpcSocialService.today() (Asia/Seoul 자정).</li>
 *   <li>오늘 이미 조절한 상대에게 한 번 더 → 인기도 제한 해제권(ItemsAdder, popularity.yml unlock-item) 1개를 쓰고 한 번 더.
 *       해제권은 DB 거래 전에 메인 스레드에서 떼어 두고, DB 가 실패하거나 해제권이 필요 없었으면 돌려준다 (잃지 않게).</li>
 *   <li>점수·오늘치 기록은 공유 DB (PopularityStore). DB 는 전용 IO 스레드, 결과는 메인 스레드.</li>
 *   <li>관리: /유저관리 &lt;닉&gt; 인기도 보기|설정 &lt;값&gt;|초기화 · /유저관리 &lt;닉&gt; 일일제한 보기|초기화 인기도</li>
 * </ul>
 */
final class PopularityBridge implements Listener, AutoCloseable {
    /** 버튼 결과. score = 새 점수(모르면 null) · voted = 오늘 이 상대 조절했는지 · tickets = 남은 해제권. */
    record VoteResult(String message, Double score, boolean voted, int tickets) {}
    private record Voted(String day, Set<UUID> targets) {}
    private record Settings(String unlockItem, ZoneId zone, int keepDays, Map<String, String> messages, String notifyUp, String notifyDown) {
        String message(String key, String fallback) { return messages.getOrDefault(key, fallback); }
    }

    private final MagicCodexBridge plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "MagicCodex-popularity-io"); t.setDaemon(true); return t; });
    private final PopularityStore store;
    private final ItemsAdderItems items;
    private final Settings settings;
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Voted> voted = new HashMap<>();
    private final Set<UUID> loadingScore = new HashSet<>(), loadingVoted = new HashSet<>(), busy = new HashSet<>();
    private volatile boolean closing;

    PopularityBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "popularity.yml");
        if (!file.exists()) plugin.saveResource("popularity.yml", false);
        settings = load(YamlConfiguration.loadConfiguration(file));
        items = new ItemsAdderItems(plugin.getLogger());
        var db = DatabaseSettings.load(plugin.getDataFolder().toPath().resolve("database.properties"));
        Path sqlite = plugin.getDataFolder().toPath().resolve("popularity.db");
        try {
            store = io.submit(() -> new PopularityStore(db, sqlite)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) { io.shutdownNow(); throw e; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) { loadScore(p.getUniqueId()); loadVoted(p.getUniqueId()); }
        String oldest = LocalDate.parse(today()).minusDays(settings.keepDays()).toString();
        work(() -> store.prune(oldest), n -> { if (n > 0) plugin.getLogger().info("인기도 지난 기록 " + n + "줄 정리 (" + settings.keepDays() + "일 보관)"); }, e -> {});
        registerCommands();
    }

    private static Settings load(YamlConfiguration y) {
        var messages = new HashMap<String, String>();
        var section = y.getConfigurationSection("messages");
        if (section != null) for (String k : section.getKeys(false)) messages.put(k, section.getString(k, ""));
        ZoneId zone;
        try { zone = ZoneId.of(y.getString("timezone", "Asia/Seoul")); } catch (RuntimeException e) { zone = ZoneId.of("Asia/Seoul"); }
        return new Settings(y.getString("unlock-item", "item:popularity_reset").trim(), zone, Math.max(1, y.getInt("keep-days", 30)),
                Map.copyOf(messages), y.getString("notify.up", "{player}님이 인기도를 올렸어요"), y.getString("notify.down", "누군가 인기도를 내렸어요"));
    }

    /** 하루 기준: NPC 일일 제한과 같은 NpcSocialService.today(). 없으면 popularity.yml timezone. */
    String today() {
        var social = plugin.npcSocial();
        return social != null ? social.today() : LocalDate.now(settings.zone()).toString();
    }

    // ------------------------------------------------------------------ 정보창에서 읽는 값 (메인 스레드, DB 없음)

    /** 아는 점수, 모르면 null (불러오기를 걸어 둔다 → 다음 새로고침에 보인다). */
    Double score(UUID player) {
        Integer v = scores.get(player);
        if (v == null) loadScore(player);
        return v == null ? null : v.doubleValue();
    }

    /** 정보창을 열 때: 다른 서버의 관리 설정 등으로 바뀌었을 수 있어 한 번 다시 읽는다. */
    void refresh(UUID player) { loadScore(player); }

    /** 행위자가 오늘 이 상대를 조절했는지 (모르면 false, 불러오기를 걸어 둔다). */
    boolean voted(UUID actor, UUID target) {
        var v = voted.get(actor);
        if (v == null || !v.day().equals(today())) { loadVoted(actor); return false; }
        return v.targets().contains(target);
    }

    /** 인벤토리의 인기도 제한 해제권 수. */
    int tickets(Player p) {
        if (settings.unlockItem().isEmpty() || !items.available()) return 0;
        int n = 0;
        for (var item : p.getInventory().getStorageContents()) if (settings.unlockItem().equals(items.idOf(item))) n += item.getAmount();
        return Math.min(n, school.magiccodex.protocol.StatsProtocol.MAX_TICKETS);
    }

    private void loadScore(UUID player) {
        if (closing || !loadingScore.add(player)) return;
        work(() -> store.score(player), v -> { loadingScore.remove(player); scores.put(player, v); }, e -> loadingScore.remove(player));
    }

    private void loadVoted(UUID actor) {
        if (closing || !loadingVoted.add(actor)) return;
        String day = today();
        work(() -> store.today(actor, day), rows -> {
            loadingVoted.remove(actor);
            var set = new HashSet<UUID>();
            for (var r : rows) set.add(r.target());
            voted.put(actor, new Voted(day, set));
        }, e -> loadingVoted.remove(actor));
    }

    private void markVoted(UUID actor, UUID target, String day) {
        var v = voted.get(actor);
        if (v == null || !v.day().equals(day)) { v = new Voted(day, new HashSet<>()); voted.put(actor, v); }
        v.targets().add(target);
    }

    // ------------------------------------------------------------------ 올리기·내리기

    /** 메인 스레드. 결과도 메인 스레드로. target 은 같은 서버 접속자 (정보창 세션이 확인함). */
    void vote(Player actor, Player target, boolean up, Consumer<VoteResult> done) {
        UUID a = actor.getUniqueId(), t = target.getUniqueId();
        if (a.equals(t)) { done.accept(result(actor, t, settings.message("self", "자신의 인기도는 조절할 수 없어요"))); return; }
        if (closing || !busy.add(a)) { done.accept(result(actor, t, settings.message("busy", "처리 중이에요. 잠시 후 다시 눌러 주세요"))); return; }
        String day = today();
        long now = System.currentTimeMillis();
        work(() -> store.vote(a, t, day, up, false, now), first -> {
            if (first.outcome() != PopularityRules.Outcome.LIMIT) { finish(actor, target, up, day, first, false, done); return; }
            scores.put(t, first.score());
            if (!actor.isOnline()) { busy.remove(a); return; }
            // 오늘 이미 조절함 → 해제권 1개를 떼어 두고 한 번 더 (DB 실패·불필요하면 돌려준다)
            if (tickets(actor) <= 0 || !takeTicket(actor)) {
                busy.remove(a); markVoted(a, t, day);
                done.accept(result(actor, t, settings.message("limit", "오늘은 이미 이 사람의 인기도를 조절했어요. 인기도 제한 해제권이 있으면 한 번 더 할 수 있어요")));
                return;
            }
            work(() -> store.vote(a, t, day, up, true, System.currentTimeMillis()), second -> {
                if (second.outcome() != PopularityRules.Outcome.TICKET) giveTicketBack(actor); // 그 사이 관리자가 오늘 기록을 지움 → 해제권 필요 없음
                finish(actor, target, up, day, second, second.outcome() == PopularityRules.Outcome.TICKET, done);
            }, e -> {
                giveTicketBack(actor);
                failed(actor, t, e, done);
            });
        }, e -> failed(actor, t, e, done));
    }

    private void failed(Player actor, UUID target, Exception e, Consumer<VoteResult> done) {
        busy.remove(actor.getUniqueId());
        plugin.getLogger().warning("인기도 저장 실패: " + e);
        done.accept(result(actor, target, settings.message("failed", "인기도를 저장하지 못했어요. 잠시 후 다시 시도해 주세요")));
    }

    private void finish(Player actor, Player target, boolean up, String day, PopularityStore.Result r, boolean ticket, Consumer<VoteResult> done) {
        UUID a = actor.getUniqueId(), t = target.getUniqueId();
        busy.remove(a);
        scores.put(t, r.score());
        String message;
        if (r.outcome() == PopularityRules.Outcome.SELF) message = settings.message("self", "자신의 인기도는 조절할 수 없어요");
        else if (r.outcome() == PopularityRules.Outcome.LIMIT) { markVoted(a, t, day); message = settings.message("limit", "오늘은 이미 이 사람의 인기도를 조절했어요"); }
        else {
            markVoted(a, t, day);
            message = settings.message(up ? "up" : "down", up ? "인기도를 올렸어요" : "인기도를 내렸어요");
            if (ticket) message = message + " · " + settings.message("ticket-used", "인기도 제한 해제권 1개를 썼어요");
            if (target.isOnline()) {
                String title = PopularityRules.format(up ? settings.notifyUp() : settings.notifyDown(), plugin.names() == null ? actor.getName() : plugin.names().name(actor));
                CmdKit.notify(target, "system", "popularity:" + a + ":" + System.nanoTime(), title, "", 0);
            }
        }
        if (done != null && actor.isOnline()) done.accept(new VoteResult(message, (double) r.score(), voted(a, t), tickets(actor)));
    }

    private VoteResult result(Player actor, UUID target, String message) {
        Integer s = scores.get(target);
        return new VoteResult(message, s == null ? null : s.doubleValue(), voted(actor.getUniqueId(), target), tickets(actor));
    }

    /** 해제권 1개를 인벤토리에서 뗀다 (ArtifactBridge.takeKeys 와 같은 방식). */
    private boolean takeTicket(Player p) {
        var inv = p.getInventory(); var contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            var item = contents[i];
            if (!settings.unlockItem().equals(items.idOf(item))) continue;
            if (item.getAmount() <= 1) inv.setItem(i, null); else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    /** 떼어 둔 해제권 돌려주기: 인벤토리 → 넘치면 발밑 → 접속이 끊겼으면 우편. */
    private void giveTicketBack(Player p) {
        ItemStack item = items.create(settings.unlockItem(), 1);
        if (item == null) { plugin.getLogger().severe("인기도 제한 해제권을 돌려주지 못했어요 (ItemsAdder " + settings.unlockItem() + " 없음): " + p.getName() + " 에게 1개"); return; }
        // 공통 지급 함수 (2026-10-26): 넣을 수 있는 칸(핫바 + 잠금 해제된 지금 쪽) → 남으면 우편 (발밑 떨어뜨림 대신).
        var delivery = RewardDelivery.get();
        if (delivery != null) { delivery.give(p.getUniqueId(), List.of(item), "popularity-refund:" + p.getUniqueId() + ":" + UUID.randomUUID(), "인기도 제한 해제권 반환", "처리하지 못해 돌려드려요", null); return; }
        if (p.isOnline()) {
            for (var left : p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            return;
        }
        var mail = Bukkit.getServicesManager().load(MailService.class);
        if (mail == null) { plugin.getLogger().severe("인기도 제한 해제권을 돌려주지 못했어요 (우편 없음): " + p.getUniqueId() + " 에게 1개"); return; }
        mail.sendSystem("popularity-refund:" + p.getUniqueId() + ":" + UUID.randomUUID(), p.getUniqueId(), "인기도 제한 해제권 반환",
                "처리하지 못해 돌려드려요", List.of(item)).whenComplete((id, e) -> {
            if (e != null) plugin.getLogger().severe("인기도 제한 해제권 반환 우편 실패: " + p.getUniqueId() + " " + e);
        });
    }

    // ------------------------------------------------------------------ 관리 명령 · 일일 제한

    private void registerCommands() {
        DailyLimits.register(plugin, new DailyLimits.Source("인기도", "인기도 올리기·내리기",
                (id, day) -> future(() -> store.today(id, day)).thenApply(rows -> {
                    var out = new ArrayList<String>();
                    for (var r : rows) out.add(ChacaApi.name(r.target()) + " " + (r.delta() > 0 ? "올림" : "내림") + (r.ticket() ? "(해제권)" : ""));
                    return out;
                }),
                (id, day) -> future(() -> store.resetDay(id, day)).thenApply(n -> { voted.remove(id); loadVoted(id); return n; })));
        if (!CodexCommands.ready(plugin, "인기도")) return;
        var node = Commands.literal("인기도");
        node.then(Commands.literal("보기").executes(ctx -> {
            UUID id = ChacaApi.target(ctx); var sender = CodexCommands.sender(ctx); String day = today();
            work(() -> new Object[]{store.score(id), store.today(id, day).size()}, v -> {
                scores.put(id, (Integer) v[0]);
                ChacaApi.ok(sender, ChacaApi.name(id) + " 님 인기도 " + v[0] + " · 오늘 다른 사람을 조절한 횟수 " + v[1]);
            }, e -> ChacaApi.fail(sender, "인기도를 읽지 못했어요", e));
            return Command.SINGLE_SUCCESS;
        }));
        node.then(Commands.literal("설정").then(Commands.argument("값", IntegerArgumentType.integer(PopularityRules.MIN_SCORE, PopularityRules.MAX_SCORE)).executes(ctx -> {
            UUID id = ChacaApi.target(ctx); var sender = CodexCommands.sender(ctx); int value = IntegerArgumentType.getInteger(ctx, "값");
            set(sender, id, value);
            return Command.SINGLE_SUCCESS;
        })));
        node.then(Commands.literal("초기화").executes(ctx -> {
            set(CodexCommands.sender(ctx), ChacaApi.target(ctx), 0);
            return Command.SINGLE_SUCCESS;
        }));
        ChacaApi.branch(plugin, ChacaApi.유저관리, node, "인기도 보기·설정·초기화", "인기도 보기");
        ChacaApi.summary(plugin, "인기도", id -> { Integer v = scores.get(id); return v == null ? "" : String.valueOf(v); });
    }

    private void set(org.bukkit.command.CommandSender sender, UUID id, int value) {
        work(() -> { store.setScore(id, value, System.currentTimeMillis()); return store.score(id); }, v -> {
            scores.put(id, v);
            ChacaApi.ok(sender, ChacaApi.name(id) + " 님 인기도를 " + v + "(으)로 바꿨어요");
        }, e -> ChacaApi.fail(sender, "인기도를 바꾸지 못했어요", e));
    }

    // ------------------------------------------------------------------ 비동기

    private interface Job<T> { T run() throws Exception; }

    private <T> void work(Job<T> job, Consumer<T> ok, Consumer<Exception> fail) {
        if (closing) { fail.accept(new IllegalStateException("closing")); return; }
        try {
            io.execute(() -> {
                T v; Exception error = null;
                try { v = job.run(); } catch (Exception e) { v = null; error = e; }
                T out = v; Exception err = error;
                if (!plugin.isEnabled()) return;
                try { Bukkit.getScheduler().runTask(plugin, () -> { if (err == null) ok.accept(out); else fail.accept(err); }); }
                catch (RuntimeException disabling) { /* 꺼지는 중 */ }
            });
        } catch (RejectedExecutionException e) { fail.accept(e); }
    }

    /** DailyLimits 용: 메인 스레드에서 완료되는 future. */
    private <T> CompletableFuture<T> future(Job<T> job) {
        var f = new CompletableFuture<T>();
        work(job, f::complete, f::completeExceptionally);
        return f;
    }

    @EventHandler public void join(PlayerJoinEvent e) { UUID id = e.getPlayer().getUniqueId(); loadScore(id); loadVoted(id); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        scores.remove(id); voted.remove(id);
    }

    @Override public void close() {
        closing = true;
        HandlerList.unregisterAll(this);
        io.execute(() -> { try { store.close(); } catch (Exception ignored) {} });
        io.shutdown();
        try { if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("인기도 DB 종료 대기 초과"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        scores.clear(); voted.clear(); busy.clear();
    }
}
