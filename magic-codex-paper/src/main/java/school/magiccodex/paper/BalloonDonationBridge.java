package school.magiccodex.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 별풍선(SOOP) 후원 → 게임 보상 (오너 2026-10-26, FINAL_PATCHES §43). 규칙은 {@link BalloonRules}, 설정은 donation.yml.
 *
 * <p><b>받는 길</b>: 외부 플러그인 "donation"(bot.colla, deployment/donation/) 의 {@code bot.colla.donation.event.DonationEvent}.
 * 컴파일 때 그 jar 를 쓰지 않으려고 리플렉션으로 등록한다 (플러그인이 있을 때만). 이벤트의 getPlayer() = 후원을 받은 방송인
 * (클라이언트 모드가 후원을 받은 마인크래프트 플레이어), getCount() = 별풍선 개수, getNickname() = 후원자.
 * 처리한 뒤 이벤트를 <b>취소</b>한다: 취소하지 않으면 donation 플러그인이 콘솔 명령 {@code api <플레이어> <종류번호> <금액> <후원자>} 을
 * 실행하므로, 예전 Skript {@code api} 명령이 남아 있으면 두 번 지급된다.
 *
 * <p><b>보상</b>: 표의 개수와 정확히 같으면 상자를 우편함으로(source 키 = 후원마다 고유), 즉사 개수(기본 10000)면 그 자리 사망
 * ({@link InventorySaveBridge#forcedDeath}: 인벤세이브 안 씀, 인벤토리·아공간 배낭 모든 쪽이 그 자리에) + 모든 서버 가운데 공지
 * ({@link ConvenienceCommands#announce}, /공지 와 같은 Velocity BungeeCord Forward) + 지금 접속 중인 전원에게 기념 상자 우편.
 * 그 밖의 개수는 마일리지 += 개수 ({@link MileageBridge#credit}, /유저관리 &lt;닉&gt; 마일리지 추가 와 같은 저장).
 *
 * <p><b>전원 상자</b>: (1) 이 서버 실제 접속자, (2) 공유 DB codex_travel_presence 의 다른 서버 줄(45초 안에 쓴 줄만, IO 스레드에서 읽음),
 * (3) 가운데 공지를 받은 다른 서버가 자기 접속자에게 (announceHook). 셋 다 우편 source 가 "balloon-all:&lt;처리번호&gt;:&lt;uuid&gt;" 로 같아서
 * 겹쳐도 한 사람 한 통 (우편 표 source_key UNIQUE). (3) 은 travel.yml 에 없는 서버(아레나 — presence 줄을 쓰지 않음)를 채운다.
 *
 * <p>게임 스레드는 이벤트 처리·아이템 만들기만. 우편·마일리지·접속자 읽기는 모두 각 기능의 IO 스레드.
 */
final class BalloonDonationBridge implements Listener {
    static final String FILE = "donation.yml";
    static final String EVENT_CLASS = "bot.colla.donation.event.DonationEvent";
    private static final String ALL_PREFIX = "balloon:";

    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final File file;
    private volatile BalloonRules.Settings settings = BalloonRules.DEFAULT;
    /** 후원을 받는 서버 (travel.yml server-id). 오너 2026-10-26: 큐디치(아레나) 서버에서는 아예 안 받음 → 목록에 없는 서버는 이벤트를 취소만 하고 보상 없음. */
    private volatile Set<String> servers = Set.of("school", "wild1", "wild2");
    private boolean hooked;
    private Method getType, getNickname, getAmount, getCount;
    /** 이 서버에서 이미 전원 상자를 보낸 (처리번호|uuid). 오래된 것부터 지운다. */
    private final LinkedHashSet<String> granted = new LinkedHashSet<>();

    BalloonDonationBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        loadAll(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        hook();
        ConvenienceCommands.announceHook = this::remoteAnnounce;
        CmdKit.register(plugin, "/후원관리", this::commands);
        if (ChacaApi.ready()) {
            ChacaApi.reload(plugin, "후원", "donation.yml (별풍선 후원 보상)", () -> loadAll(file));
            ChacaApi.status(plugin, "별풍선 후원", () -> (settings.enabled() ? "켜짐" : "꺼짐 (donation 플러그인의 api 명령이 그대로 실행됨)")
                    + " · donation 플러그인 " + (hooked ? "연결됨" : "없음"));
        }
        plugin.getLogger().info("별풍선 후원 보상 준비 (donation 플러그인 " + (hooked ? "연결" : "없음 — /후원관리 만 동작") + ")");
    }

    // ------------------------------------------------------------------ donation.yml

    private void loadAll(File file) {
        var y = YamlConfiguration.loadConfiguration(file);
        settings = parse(y);
        servers = TravelItemRules.ids(y.isList("servers") ? y.getStringList("servers") : null, Set.of("school", "wild1", "wild2"));
    }

    /** 이 서버가 후원을 받는 서버인가 (travel.yml server-id 가 servers 에 있음). server-id 를 모르면 받지 않음. */
    private boolean here() {
        String self;
        try { self = plugin.travel() == null || plugin.travel().config() == null ? "" : plugin.travel().config().self(); } catch (RuntimeException e) { self = ""; }
        return !self.isEmpty() && servers.contains(self);
    }

    static BalloonRules.Settings parse(YamlConfiguration y) {
        var d = BalloonRules.DEFAULT;
        var dm = d.messages();
        var tiers = new LinkedHashMap<Long, BalloonRules.Box>();
        var section = y.getConfigurationSection("tiers");
        if (section == null) tiers.putAll(d.tiers());
        else for (String key : section.getKeys(false)) {
            var t = section.getConfigurationSection(key);
            String where = "tiers." + key;
            if (t == null) throw new IllegalArgumentException(where + " 는 item·amount·name 묶음이어야 해요");
            long count = BalloonRules.count(key, where);
            if (tiers.put(count, BalloonRules.box(t.getString("item"), t.getInt("amount", 1), t.getString("name"), where)) != null)
                throw new IllegalArgumentException(where + " 개수가 두 번 있어요");
        }
        long deathCount = y.getLong("death.count", d.deathCount());
        var deathBox = BalloonRules.box(y.getString("death.item", d.deathBox().item()), y.getInt("death.amount", d.deathBox().amount()),
                y.getString("death.name", d.deathBox().name()), "death");
        var m = new BalloonRules.Messages(
                y.getString("messages.card-title", dm.cardTitle()), y.getString("messages.box", dm.box()), y.getString("messages.mileage", dm.mileage()),
                y.getString("messages.death", dm.death()), y.getString("messages.failed", dm.failed()),
                y.getString("messages.mail-title", dm.mailTitle()), y.getString("messages.mail-body", dm.mailBody()),
                y.getString("death.title", dm.deathTitle()), y.getString("death.announce", dm.announce()), y.getString("death.card-title", dm.deathCardTitle()),
                y.getString("death.mail-title", dm.deathMailTitle()), y.getString("death.mail-body", dm.deathMailBody()));
        return BalloonRules.settings(y.getBoolean("enabled", true), tiers, deathCount, deathBox, y.getBoolean("mileage-fallback", true), m);
    }

    // ------------------------------------------------------------------ donation 플러그인 연결 (리플렉션)

    @EventHandler public void enabled(PluginEnableEvent e) { if (!hooked && "donation".equals(e.getPlugin().getName())) hook(); }

    private void hook() {
        Plugin donation = Bukkit.getPluginManager().getPlugin("donation");
        if (hooked || donation == null || !donation.isEnabled()) return;
        try {
            Class<? extends Event> type = Class.forName(EVENT_CLASS, true, donation.getClass().getClassLoader()).asSubclass(Event.class);
            if (!PlayerEvent.class.isAssignableFrom(type) || !Cancellable.class.isAssignableFrom(type)) throw new IllegalStateException("DonationEvent 모양이 달라요");
            getType = type.getMethod("getType");
            getNickname = type.getMethod("getNickname");
            getAmount = type.getMethod("getAmount");
            getCount = type.getMethod("getCount");
            // HIGHEST: 다른 플러그인이 먼저 취소한 후원은 건드리지 않는다 (ignoreCancelled).
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> { if (type.isInstance(event)) donation(event); }, plugin, true);
            hooked = true;
            plugin.getLogger().info("donation 플러그인 " + donation.getPluginMeta().getVersion() + " 후원 이벤트 연결");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            plugin.getLogger().severe("donation 플러그인 후원 이벤트 연결 실패 (보상 없음, 그 플러그인의 api 명령은 그대로 실행됨): " + error);
        }
    }

    private void donation(Event event) {
        var s = settings;
        if (!s.enabled()) return;   // 끄면 취소하지 않는다 → donation 플러그인의 api 콘솔 명령이 예전처럼 실행
        Player streamer = ((PlayerEvent) event).getPlayer();
        String type, donor;
        long count, amount;
        try {
            type = String.valueOf(getType.invoke(event));
            donor = String.valueOf(getNickname.invoke(event));
            count = ((Number) getCount.invoke(event)).longValue();
            amount = ((Number) getAmount.invoke(event)).longValue();
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().severe("후원 이벤트를 읽지 못했어요 (취소 안 함): " + error);
            return;
        }
        ((Cancellable) event).setCancelled(true);   // 처리했으니 api 콘솔 명령은 실행되지 않게 (두 번 지급 방지)
        if (!here()) { plugin.getLogger().info("후원 무시 (이 서버는 donation.yml servers 에 없음 — 큐디치·아레나): 후원자=" + donor + " 개수=" + count); return; }
        if (streamer == null || !streamer.isOnline()) { plugin.getLogger().warning("후원 받은 플레이어가 접속 중이 아님: 후원자=" + donor + " 개수=" + count); return; }
        handle(streamer, donor, type, count, amount, "donation");
    }

    // ------------------------------------------------------------------ /후원관리 <닉네임> <개수>

    private void commands() {
        ChacaApi.rootArgument(plugin, ChacaApi.후원관리, CmdKit.player(ChacaApi.TARGET)
                .then(CmdKit.argument("개수", IntegerArgumentType.integer(1, (int) BalloonRules.MAX_MILEAGE)).executes(CmdKit.run(ctx -> {
                    if (!settings.enabled()) throw ChacaApi.error("donation.yml 의 enabled 가 false 예요 (켠 뒤 /서버관리 새로고침 후원)");
                    if (!here()) throw ChacaApi.error("이 서버에서는 후원을 받지 않아요 (donation.yml servers — 큐디치·아레나 제외)");
                    Player target = ChacaApi.resolveOnline(ctx, ChacaApi.TARGET);   // 이 서버에 접속 중이어야 함 (아니면 거절 알림)
                    int count = IntegerArgumentType.getInteger(ctx, "개수");
                    var reward = BalloonRules.reward(settings, count);
                    handle(target, "관리자", "ADMIN", count, 0, "관리자 " + CmdKit.sender(ctx).getName());
                    CmdKit.ok(CmdKit.sender(ctx), CmdKit.name(target.getUniqueId()) + " 님에게 별풍선 " + count + "개 후원 처리 · " + reward.text());
                }))),
                "실제 후원과 같은 처리 (후원자 = 관리자, 접속 중인 유저만)", "");
    }

    // ------------------------------------------------------------------ 처리 (메인 스레드)

    /** 후원 하나 처리. via = "donation" 또는 "관리자 &lt;이름&gt;" (기록용). */
    void handle(Player streamer, String donor, String type, long count, long amount, String via) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        var s = settings;
        UUID id = streamer.getUniqueId();
        String op = UUID.randomUUID().toString().replace("-", "");
        var reward = BalloonRules.reward(s, count);
        String nick = CmdKit.name(id);
        var values = new HashMap<String, String>();
        values.put("count", String.valueOf(count));
        values.put("donor", clean(donor, 40));
        values.put("nickname", nick);
        values.put("reward", reward.box() != null ? reward.box().text() : reward.kind() == BalloonRules.Kind.MILEAGE ? "마일리지 " + reward.mileage() : "없음");
        plugin.getLogger().info("BALLOON op=" + op + " player=" + streamer.getName() + " donor=" + donor + " type=" + type + " count=" + count + " amount=" + amount + " reward=" + reward.text() + " via=" + via);
        audit(id, "별풍선 " + count + "개 후원 · " + reward.text(), op, donor, type, count, amount, reward, via, "");
        ChacaApi.stat(id, "후원받음", 1);
        ChacaApi.stat(id, "후원별풍선", count);
        switch (reward.kind()) {
            case BOX -> {
                var items = items(reward.box());
                if (items == null) { failed(id, op, values, "아이템 없음: " + reward.box().item(), s); return; }
                mail(id, "balloon:" + op, BalloonRules.fill(s.messages().mailTitle(), values), BalloonRules.fill(s.messages().mailBody(), values), items, ok -> {
                    if (ok) tell(id, op, s.messages().cardTitle(), BalloonRules.fill(s.messages().box(), values));
                    else failed(id, op, values, "우편 저장 실패", s);
                });
            }
            case MILEAGE -> {
                var mileage = plugin.mileage();
                if (mileage == null) { failed(id, op, values, "마일리지 기능 없음", s); return; }
                mileage.credit(id, reward.mileage(), v -> {
                    AuditLog.log(id, AuditLog.마일리지, "별풍선 후원 마일리지 +" + reward.mileage() + " (" + v[0] + " → " + v[1] + ")", "처리번호", op, "전", v[0], "후", v[1]);
                    tell(id, op, s.messages().cardTitle(), BalloonRules.fill(s.messages().mileage(), values));
                }, m -> failed(id, op, values, "마일리지 저장 실패: " + m, s));
            }
            case DEATH -> death(streamer, op, values, s);
            case NONE -> plugin.getLogger().info("BALLOON op=" + op + " 보상 없음 (개수 " + count + ", mileage-fallback 꺼짐 또는 범위 밖)");
        }
    }

    private void death(Player streamer, String op, Map<String, String> values, BalloonRules.Settings s) {
        UUID id = streamer.getUniqueId();
        boolean died = InventorySaveBridge.forcedDeath(streamer);
        if (!died) plugin.getLogger().warning("BALLOON op=" + op + " 즉사 못 함 (이미 사망·접속 끊김·사망 이벤트 취소) — 공지·전원 상자는 그대로");
        var m = s.messages();
        String line = BalloonRules.fill(m.announce(), values);
        values.put("announce", line);
        if (!ConvenienceCommands.announce(ALL_PREFIX + op, BalloonRules.fill(m.deathCardTitle(), values), BalloonRules.fill(m.deathTitle(), values), line)) {
            // 편의 명령이 없는 서버: 이 서버에만
            var title = net.kyori.adventure.title.Title.title(Component.text(BalloonRules.fill(m.deathTitle(), values), NamedTextColor.RED), Component.text(line, NamedTextColor.GOLD));
            for (Player p : Bukkit.getOnlinePlayers()) { p.showTitle(title); CmdKit.notify(p, "warning", "balloon-death-" + op, m.deathCardTitle(), line, 0); }
            plugin.getLogger().warning("BALLOON op=" + op + " 편의 명령(/공지)이 없어 이 서버에만 공지");
        }
        tell(id, op, m.cardTitle(), BalloonRules.fill(m.death(), values));
        grantAll(op, line, s);
    }

    // ------------------------------------------------------------------ 전원 기념 상자

    private void grantAll(String op, String announce, BalloonRules.Settings s) {
        for (Player p : Bukkit.getOnlinePlayers()) grantOne(op, p.getUniqueId(), announce, s);
        var travel = plugin.travel();
        if (travel == null || !travel.storeReady() || travel.config() == null) {
            plugin.getLogger().warning("BALLOON op=" + op + " 공유 접속자 목록(codex_travel_presence)을 못 써요 — 다른 서버는 가운데 공지를 받은 서버가 직접 지급");
            return;
        }
        String self = travel.config().self();
        travel.presenceAll(rows -> {   // IO 스레드에서 읽고 메인 스레드로 돌아옴
            if (rows == null) { plugin.getLogger().warning("BALLOON op=" + op + " 공유 접속자 목록 읽기 실패 — 다른 서버는 가운데 공지를 받은 서버가 직접 지급"); return; }
            long now = System.currentTimeMillis();
            int n = 0;
            for (var row : rows) {
                if (row.server().equals(self) || !row.alive(now)) continue;
                for (var o : row.players()) if (grantOne(op, o.id(), announce, s)) n++;
            }
            plugin.getLogger().info("BALLOON op=" + op + " 다른 서버 접속자(공유 목록) " + n + "명에게 기념 상자 우편");
        });
    }

    /** 다른 서버의 후원 사망 공지를 받음: 이 서버 접속자에게 기념 상자 (보낸 서버가 같은 source 로 보냈어도 한 통). */
    private void remoteAnnounce(ConvenienceRules.Wire w) {
        if (!w.id().startsWith(ALL_PREFIX)) return;
        String op = w.id().substring(ALL_PREFIX.length());
        var s = settings;
        if (!op.matches("[0-9a-f]{32}") || !s.enabled() || s.deathCount() <= 0) return;
        if (Math.abs(System.currentTimeMillis() - w.time()) > 10 * 60_000L) return;   // 아주 늦게 온 메시지는 무시
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (grantOne(op, p.getUniqueId(), w.text(), s)) n++;
        plugin.getLogger().info("BALLOON op=" + op + " (서버 " + w.server() + " 의 후원 사망) 이 서버 접속자 " + n + "명에게 기념 상자 우편");
    }

    private boolean grantOne(String op, UUID who, String announce, BalloonRules.Settings s) {
        if (!granted.add(op + "|" + who)) return false;
        while (granted.size() > 5000) { var it = granted.iterator(); it.next(); it.remove(); }
        var items = items(s.deathBox());
        if (items == null) { plugin.getLogger().severe("BALLOON op=" + op + " 기념 상자 아이템 없음: " + s.deathBox().item()); return false; }
        var values = new HashMap<String, String>();
        values.put("announce", announce);
        values.put("reward", s.deathBox().text());
        mail(who, "balloon-all:" + op + ":" + who, BalloonRules.fill(s.messages().deathMailTitle(), values), BalloonRules.fill(s.messages().deathMailBody(), values), items,
                ok -> { if (!ok) plugin.getLogger().warning("BALLOON op=" + op + " 기념 상자 우편 실패(또는 다른 서버가 먼저 보냄) player=" + who); });
        return true;
    }

    // ------------------------------------------------------------------ 공통

    /** 상자 아이템 (최대 스택 단위로 나눔). ItemsAdder 에 없으면 null. */
    private List<ItemStack> items(BalloonRules.Box box) {
        ItemStack one = itemsAdder.create(box.item(), 1);
        if (one == null) return null;
        var out = new ArrayList<ItemStack>();
        for (int n : BalloonRules.stacks(box.amount(), one.getMaxStackSize())) { var c = one.clone(); c.setAmount(n); out.add(c); }
        return out;
    }

    /** 우편 9칸씩 나눠 보낸다 (source = base:번호). done 은 메인 스레드, 모두 저장됐을 때 true. */
    private void mail(UUID to, String base, String title, String body, List<ItemStack> items, Consumer<Boolean> done) {
        var service = Bukkit.getServicesManager().load(MailService.class);
        if (service == null) { plugin.getLogger().severe("BALLOON 우편 기능 없음: " + base); done.accept(false); return; }
        var parts = new ArrayList<List<ItemStack>>();
        for (int i = 0; i < items.size(); i += 9) parts.add(items.subList(i, Math.min(items.size(), i + 9)));
        int[] left = {parts.size()};
        boolean[] all = {true};
        String t = clean(title, 80), b = body == null ? "" : body.length() > 2000 ? body.substring(0, 2000) : body;
        for (int i = 0; i < parts.size(); i++) {
            String source = base + ":" + i;
            CompletableFuture<UUID> sent;
            try { sent = service.sendSystem(source, to, t.isEmpty() ? "별풍선 후원 보상" : t, b, List.copyOf(parts.get(i))); }
            catch (RuntimeException e) { sent = CompletableFuture.failedFuture(e); }
            sent.whenComplete((mailId, error) -> {
                if (error != null) plugin.getLogger().warning("BALLOON 우편 실패 source=" + source + " player=" + to + " (" + error + ")");
                else plugin.getLogger().info("BALLOON 우편 source=" + source + " player=" + to + " mail=" + mailId);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> { all[0] &= error == null; if (--left[0] == 0) done.accept(all[0]); });
            });
        }
    }

    /** 알림 카드 + 채팅 한 줄 (접속 중일 때). */
    private void tell(UUID id, String op, String card, String line) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        CmdKit.notify(p, "system", "balloon-" + op, card, line, 0);
        p.sendMessage(Component.text(line, NamedTextColor.GOLD));
    }

    private void failed(UUID id, String op, Map<String, String> values, String why, BalloonRules.Settings s) {
        plugin.getLogger().severe("BALLOON op=" + op + " 지급 실패 player=" + id + " (" + why + ") — 직접 지급 필요: " + values.get("reward"));
        AuditLog.log(id, AuditLog.후원, "별풍선 " + values.get("count") + "개 후원 · 지급 실패 (" + why + ")", "처리번호", op, "보상", values.get("reward"), "이유", why);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            String line = BalloonRules.fill(s.messages().failed(), values);
            CmdKit.notify(p, "warning", "balloon-fail-" + op, s.messages().cardTitle(), line, 0);
            p.sendMessage(Component.text(line, NamedTextColor.RED));
        }
    }

    private static void audit(UUID id, String summary, String op, String donor, String type, long count, long amount, BalloonRules.Reward reward, String via, String note) {
        var d = new LinkedHashMap<String, String>();
        d.put("처리번호", op);
        d.put("후원자", clean(donor, 40));
        d.put("종류", type);
        d.put("개수", String.valueOf(count));
        d.put("금액", String.valueOf(amount));
        d.put("보상", reward.text());
        d.put("경로", via);
        if (!note.isEmpty()) d.put("메모", note);
        ChacaApi.audit(id, AuditLog.후원, summary, d);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
