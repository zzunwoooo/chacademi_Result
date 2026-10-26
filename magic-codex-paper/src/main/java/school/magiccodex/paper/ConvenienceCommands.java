package school.magiccodex.paper;

import static school.magiccodex.paper.CmdKit.*;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 한글 편의 명령 (오픈 전 메모 #95, 오너 2026-10-26). Essentials 에서 자주 쓰던 것만, 한글로.
 *
 * <p>관리자(OP) 전용: /겜모 · /gm 0|1 · /텔포 &lt;닉네임&gt; · /소환 &lt;닉네임&gt; · /공지 &lt;할말&gt; · /뒤로 · /back · /스폰 · /spawn ·
 * /메모리 · /도움기록. 유저: /도움 &lt;할말&gt; · /쓰레기통 (권한 magiccodex.convenience.help · .trash, 기본 켜짐).
 * /홈·/셋홈은 만들지 않는다 (오너 결정).
 *
 * <p>이동: /텔포·/소환은 /유저관리 찾아가기·불러오기(OperatorCommands)를 그대로 쓴다 (다른 서버에 있으면 HuskHomes).
 * /뒤로·/스폰은 HuskHomes 의 back·spawn 명령이 있으면 그것에 맡기고(서버 간 이동·사망 위치는 HuskHomes 설정), 없으면 이 서버 안에서만
 * (관리자의 마지막 순간이동 전 위치·죽은 위치 / 첫 월드 스폰). 관리자 전용이라 전투 중 이동 막기(#91)는 해당 없음 (관리자 예외).
 *
 * <p>서버 간 공지·도움: Velocity 의 BungeeCord 호환 채널(Forward ALL)로 다른 서버에 전한다. 프록시 쪽 플러그인 변경 없음.
 * 받는 서버에 접속자가 있어야 도착한다 (접속자가 없는 서버는 받을 사람도 없음).
 *
 * <p>부담: 매 틱 일 없음. 순간이동·사망 이벤트에서 관리자 위치 하나만 적고, 나머지는 명령을 칠 때만 일한다.
 */
final class ConvenienceCommands implements Listener, PluginMessageListener {
    static final String FILE = "convenience-commands.yml";
    static final String CHANNEL = "BungeeCord";
    static final String PERM_HELP = "magiccodex.convenience.help", PERM_TRASH = "magiccodex.convenience.trash";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 도움 요청 한 건 (이 서버가 받은 것). */
    record Help(String id, String server, String who, String account, String text, long time) {}

    private final MagicCodexBridge plugin;
    private final OperatorCommands operator;
    private final File file;
    private final File logFile;
    private ConvenienceRules.Settings settings = ConvenienceRules.Settings.DEFAULT;
    /** 이번 실행 토큰 — 서버 간 메시지에서 내가 보낸 것을 거른다. */
    private final String origin = UUID.randomUUID().toString();

    /** HuskHomes 가 없을 때 /뒤로 대신: 관리자의 이전 위치 (순간이동 전·죽은 곳). */
    private final Map<UUID, Location> back = new HashMap<>();
    /** 도움 쿨다운: 마지막으로 보낸 시각. */
    private final Map<UUID, Long> lastHelp = new HashMap<>();
    /** 다른 서버 운영자의 확인을 기다리는 내 도움 요청: id → 보낸 사람. */
    private final Map<String, UUID> pending = new HashMap<>();
    /** 받을 운영자가 없었던 요청 — 이 서버에 운영자가 들어오면 보여 주고 비운다. */
    private final Deque<Help> waiting = new ArrayDeque<>();
    /** 이 서버가 받은 최근 도움 요청 (/도움기록). */
    private final Deque<Help> recent = new ArrayDeque<>();
    /** 이미 처리한 서버 간 메시지 id. */
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    /** 다른 기능(별풍선 후원)이 모든 서버 가운데 공지를 띄울 때 ({@link #announce}). 없으면 null. */
    private static ConvenienceCommands instance;

    ConvenienceCommands(MagicCodexBridge plugin, OperatorCommands operator) throws Exception {
        this.plugin = plugin;
        instance = this;
        this.operator = operator;
        this.file = new File(plugin.getDataFolder(), FILE);
        this.logFile = new File(plugin.getDataFolder(), "help-requests.log");
        if (!file.exists()) plugin.saveResource(FILE, false);
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
        register(plugin, "편의 명령 (/겜모 /텔포 /소환 /공지 /도움 /뒤로 /스폰 /쓰레기통 /메모리)", this::registerCommands);
        if (ChacaApi.ready()) ChacaApi.reload(plugin, "편의명령", FILE + " (공지·도움·쓰레기통 문구·쿨다운·소리)", this::reload);
    }

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        var d = ConvenienceRules.Settings.DEFAULT;
        settings = ConvenienceRules.settings(
                y.getString("notice.title", d.noticeTitle()), sound(y, "notice.sound", d.noticeSound()), y.getBoolean("cross-server", d.crossServer()),
                y.getDouble("help.cooldown-seconds", d.helpCooldownMs() / 1000.0), y.getInt("help.max-length", d.helpMaxLength()),
                sound(y, "help.sound", d.helpSound()), y.getLong("help.ack-wait-ticks", d.helpAckWaitTicks()), y.getInt("help.keep", d.helpKeep()),
                y.getString("help.sent", d.helpSent()), y.getString("help.no-operator", d.helpNoOperator()), y.getString("help.cooldown", d.helpCooldown()),
                y.getString("trash.title", d.trashTitle()), y.getInt("trash.rows", d.trashRows()));
    }

    private static ConvenienceRules.Sound sound(YamlConfiguration y, String path, ConvenienceRules.Sound fallback) {
        if (!y.isConfigurationSection(path)) return fallback;
        return ConvenienceRules.sound(y.getString(path + ".key", fallback.key()), y.getDouble(path + ".volume", fallback.volume()), y.getDouble(path + ".pitch", fallback.pitch()));
    }

    // ------------------------------------------------------------------ 등록

    private static boolean admin(CommandSourceStack source) {
        CommandSender s = source.getSender();
        return !(s instanceof Player) || s.isOp();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminLiteral(String name) { return literal(name).requires(ConvenienceCommands::admin); }

    private void registerCommands() {
        // 게임모드: /겜모 = 서바이벌 ↔ 크리에이티브, /겜모 0|1 · /gm 0|1
        for (String name : List.of("겜모", "gm")) {
            ChacaApi.playerCommand(plugin, adminLiteral(name).executes(run(ctx -> toggleMode(self(ctx))))
                    .then(literal("0").executes(run(ctx -> setMode(self(ctx), GameMode.SURVIVAL))))
                    .then(literal("1").executes(run(ctx -> setMode(self(ctx), GameMode.CREATIVE)))),
                    "(관리자) 게임모드 서바이벌 ↔ 크리에이티브", List.of());
        }
        // 순간이동: /유저관리 찾아가기·불러오기와 같은 동작 (칸 이름도 같은 "닉네임")
        ChacaApi.playerCommand(plugin, adminLiteral("텔포").then(player(ChacaApi.TARGET).executes(run(operator::goTo))),
                "(관리자) 그 유저에게 순간이동", List.of());
        ChacaApi.playerCommand(plugin, adminLiteral("소환").then(player(ChacaApi.TARGET).executes(run(operator::bring))),
                "(관리자) 그 유저를 내 위치로", List.of());
        ChacaApi.playerCommand(plugin, adminLiteral("공지").executes(ctx -> { throw ChacaApi.error("공지할 말을 적어 주세요 · /공지 <할말>"); })
                        .then(argument("할말", StringArgumentType.greedyString()).executes(run(ctx -> notice(sender(ctx), StringArgumentType.getString(ctx, "할말"))))),
                "(관리자) 모든 서버 접속자에게 공지", List.of());
        for (String name : List.of("뒤로", "back"))
            ChacaApi.playerCommand(plugin, adminLiteral(name).executes(run(ctx -> back(self(ctx)))), "(관리자) 이전 위치로 (순간이동 전·죽은 곳)", List.of());
        for (String name : List.of("스폰", "spawn"))
            ChacaApi.playerCommand(plugin, adminLiteral(name).executes(run(ctx -> spawn(self(ctx)))), "(관리자) 스폰으로", List.of());
        ChacaApi.playerCommand(plugin, adminLiteral("메모리").executes(run(ctx -> memory(sender(ctx)))), "(관리자) 메모리·TPS 보기", List.of());
        ChacaApi.playerCommand(plugin, adminLiteral("도움기록").executes(run(ctx -> history(sender(ctx)))), "(관리자) 최근 도움 요청 보기", List.of());

        // 유저
        ChacaApi.playerCommand(plugin, literal("도움").requires(s -> s.getSender().hasPermission(PERM_HELP))
                        .executes(ctx -> { throw ChacaApi.error("운영자에게 보낼 말을 적어 주세요 · /도움 <할말>"); })
                        .then(argument("할말", StringArgumentType.greedyString()).executes(run(ctx -> help(self(ctx), StringArgumentType.getString(ctx, "할말"))))),
                "운영자에게 도움 요청 보내기", List.of());
        ChacaApi.playerCommand(plugin, literal("쓰레기통").requires(s -> s.getSender().hasPermission(PERM_TRASH)).executes(run(ctx -> trash(self(ctx)))),
                "쓰레기통 열기 (닫으면 넣은 아이템이 사라져요)", List.of());
    }

    // ------------------------------------------------------------------ 게임모드

    private void toggleMode(Player p) throws CommandSyntaxException {
        setMode(p, p.getGameMode() == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE);
    }

    private void setMode(Player p, GameMode mode) {
        String name = OperatorCommands.gameModeName(mode);
        if (p.getGameMode() == mode) { ok(p, "이미 " + name + " 모드예요"); return; }
        p.setGameMode(mode);
        plugin.getLogger().info("OPERATOR gamemode " + mode + " admin=" + p.getName());
        ok(p, name + " 모드로 바꿨어요");
    }

    // ------------------------------------------------------------------ /뒤로 · /스폰

    private static boolean hasCommand(String label) {
        try { return Bukkit.getCommandMap().getCommand(label) != null; }
        catch (RuntimeException | LinkageError e) { return false; }
    }

    private void back(Player p) throws CommandSyntaxException {
        if (hasCommand("huskhomes:back")) { delegate(p, "huskhomes:back"); return; }
        Location to = back.get(p.getUniqueId());
        if (to == null || to.getWorld() == null || Bukkit.getWorld(to.getWorld().getUID()) == null) throw ChacaApi.error("돌아갈 이전 위치가 없어요");
        move(p, to.clone(), "이전 위치로 돌아왔어요");
    }

    private void spawn(Player p) throws CommandSyntaxException {
        if (hasCommand("huskhomes:spawn")) { delegate(p, "huskhomes:spawn"); return; }
        World w = Bukkit.getWorlds().isEmpty() ? p.getWorld() : Bukkit.getWorlds().get(0);
        move(p, w.getSpawnLocation().clone().add(.5, 0, .5), "스폰으로 왔어요");
    }

    /** HuskHomes 에 맡긴다 (결과 알림은 HuskHomes 가 직접). */
    private void delegate(Player p, String command) throws CommandSyntaxException {
        stopBroom(p);
        plugin.getLogger().info("OPERATOR " + p.getName() + " → /" + command);
        if (!p.performCommand(command)) throw ChacaApi.error("HuskHomes 명령을 실행하지 못했어요");
    }

    private void move(Player p, Location to, String done) {
        stopBroom(p);
        p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((moved, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (error != null) { fail(p, "순간이동하지 못했어요", error); return; }
            if (!Boolean.TRUE.equals(moved)) { userFail(p, "순간이동이 막혔어요 (경기 중이거나 보호 구역일 수 있어요)"); return; }
            p.setFallDistance(0);
            ok(p, done);
        }));
    }

    /** 관리자만, 명령·플러그인 순간이동과 죽은 곳을 적는다 (HuskHomes 가 없을 때 /뒤로 가 씀). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent e) {
        var cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        Player p = e.getPlayer();
        if (!p.isOp()) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to != null && from.getWorld() == to.getWorld() && from.distanceSquared(to) < 4) return;
        back.put(p.getUniqueId(), from.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.isOp()) back.put(p.getUniqueId(), p.getLocation().clone());
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        back.remove(id);
        if (lastHelp.size() > 512) { long now = System.currentTimeMillis(); lastHelp.values().removeIf(at -> ConvenienceRules.cooldownLeft(at, now, settings.helpCooldownMs()) <= 0); }
    }

    // ------------------------------------------------------------------ /공지

    private void notice(CommandSender from, String raw) throws CommandSyntaxException {
        String text = ConvenienceRules.clean(raw, 300);
        if (text.isEmpty()) throw ChacaApi.error("공지할 말을 적어 주세요");
        String who = from instanceof Player p ? plugin.names().name(p) : "콘솔";
        var w = new ConvenienceRules.Wire(ConvenienceRules.Kind.NOTICE, origin, UUID.randomUUID().toString(), serverId(), who, from.getName(), text, System.currentTimeMillis());
        seen(w.id());
        showNotice(w);
        plugin.getLogger().info("NOTICE admin=" + from.getName() + " : " + text);
        if (!settings.crossServer()) { ok(from, "이 서버에 공지했어요"); return; }
        ok(from, forward(from instanceof Player p ? p : null, w) ? "모든 서버에 공지했어요" : "이 서버에 공지했어요 (다른 서버로 보낼 접속자가 없어요)");
    }

    /**
     * 모든 서버 접속자에게 화면 가운데 큰 글씨(title·line) + 알림 카드(card·line) + 공지 소리. /공지 와 같은 서버 간 경로(Forward ALL).
     * 메인 스레드. 이 서버에 보이면 true. cross-server: false 면 이 서버만. 편의 명령이 없으면(초기화 실패) false — 그때는 부른 쪽이 이 서버에만 띄운다.
     * id = 메시지 ID (64자 이하, 받은 서버의 {@link #announceHook} 가 이것으로 무슨 공지인지 안다). 비우면 무작위.
     */
    static boolean announce(String id, String card, String title, String line) {
        var self = instance;
        if (self == null) return false;
        var w = new ConvenienceRules.Wire(ConvenienceRules.Kind.ANNOUNCE, self.origin, id == null || id.isEmpty() || id.length() > 64 ? UUID.randomUUID().toString() : id,
                self.serverId(), ConvenienceRules.clean(card, 80), ConvenienceRules.clean(title, 40), ConvenienceRules.clean(line, 300), System.currentTimeMillis());
        self.seen(w.id());
        self.showAnnounce(w);
        if (self.settings.crossServer() && !self.forward(null, w)) self.plugin.getLogger().warning("가운데 공지를 다른 서버로 보내지 못했어요 (이 서버 접속자 없음): " + w.text());
        return true;
    }

    /**
     * 다른 서버에서 온 가운데 공지(ANNOUNCE)를 받은 뒤 부르는 곳 (메인 스레드, 처음 보는 id 만). 별풍선 후원 사망이 쓴다:
     * id 가 "balloon:" 로 시작하면 이 서버 접속자에게 기념 상자를 보낸다 (BalloonDonationBridge). null = 없음.
     */
    static java.util.function.Consumer<ConvenienceRules.Wire> announceHook;

    private void showAnnounce(ConvenienceRules.Wire w) {
        var title = net.kyori.adventure.title.Title.title(Component.text(w.account(), NamedTextColor.RED), Component.text(w.text(), NamedTextColor.GOLD),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(800)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            CmdKit.notify(p, "warning", "announce-" + w.id(), w.who().isEmpty() ? settings.noticeTitle() : w.who(), w.text(), 0);
            play(p, settings.noticeSound());
        }
    }

    private void showNotice(ConvenienceRules.Wire w) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            CmdKit.notify(p, "system", "notice-" + w.id(), settings.noticeTitle(), w.text(), 0);
            play(p, settings.noticeSound());
        }
    }

    // ------------------------------------------------------------------ /도움

    private void help(Player p, String raw) throws CommandSyntaxException {
        String text = ConvenienceRules.clean(raw, settings.helpMaxLength());
        if (text.isEmpty()) throw ChacaApi.error("운영자에게 보낼 말을 적어 주세요");
        long now = System.currentTimeMillis();
        long left = ConvenienceRules.cooldownLeft(lastHelp.getOrDefault(p.getUniqueId(), -1L), now, settings.helpCooldownMs());
        if (left > 0) throw ChacaApi.error(ConvenienceRules.cooldownMessage(settings.helpCooldown(), left));
        lastHelp.put(p.getUniqueId(), now);
        var w = new ConvenienceRules.Wire(ConvenienceRules.Kind.HELP, origin, UUID.randomUUID().toString(), serverId(), plugin.names().name(p), p.getName(), text, now);
        seen(w.id());
        var help = new Help(w.id(), w.server(), w.who(), w.account(), text, now);
        int here = deliverHelp(help);
        writeLog(help);
        boolean forwarded = settings.crossServer() && forward(p, w);
        if (here > 0) { ok(p, settings.helpSent()); return; }
        if (!forwarded) { keepWaiting(help); CmdKit.notify(p, "warning", "help-none", settings.helpNoOperator(), "", 0); return; }
        // 다른 서버 운영자의 확인을 잠깐 기다린다 (확인이 오면 그때 "보냈어요")
        pending.put(w.id(), p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID who = pending.remove(w.id());
            if (who == null) return;
            keepWaiting(help);
            Player still = Bukkit.getPlayer(who);
            if (still != null) CmdKit.notify(still, "warning", "help-none", settings.helpNoOperator(), "", 0);
        }, settings.helpAckWaitTicks());
    }

    /** 이 서버 운영자에게 보여 준다. 보여 준 운영자 수. */
    private int deliverHelp(Help h) {
        remember(recent, h);
        int count = 0;
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (!op.isOp()) continue;
            count++;
            String where = h.server().isEmpty() || h.server().equals(serverId()) ? "" : " · " + h.server() + " 서버";
            CmdKit.notify(op, "warning", "help-" + h.id(), "도움 요청 · " + h.who() + where, h.text(), 0);
            op.sendMessage(helpLine(h));
            play(op, settings.helpSound());
        }
        return count;
    }

    private Component helpLine(Help h) {
        String where = h.server().isEmpty() ? "" : " [" + h.server() + "]";
        return Component.text("[도움] ", NamedTextColor.GOLD)
                .append(Component.text(h.who() + where + ": ", NamedTextColor.YELLOW))
                .append(Component.text(h.text(), NamedTextColor.WHITE))
                .append(Component.text("  " + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(h.time()), java.time.ZoneId.systemDefault()).format(TIME), NamedTextColor.DARK_GRAY))
                .clickEvent(ClickEvent.suggestCommand("/텔포 " + h.who()))
                .hoverEvent(HoverEvent.showText(Component.text("눌러서 /텔포 " + h.who() + " 입력")));
    }

    private void keepWaiting(Help h) { remember(waiting, h); }

    private void remember(Deque<Help> list, Help h) {
        list.addFirst(h);
        while (list.size() > settings.helpKeep()) list.removeLast();
    }

    /** 운영자가 들어오면 받을 사람이 없던 요청을 보여 주고 비운다. */
    @EventHandler public void joined(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.isOp() || waiting.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || waiting.isEmpty()) return;
            CmdKit.notify(p, "warning", "help-waiting", "운영자가 없을 때 온 도움 요청 " + waiting.size() + "개", "채팅창을 확인해 주세요", 0);
            p.sendMessage(Component.text("── 운영자가 없을 때 온 도움 요청 ──", NamedTextColor.GOLD));
            for (var it = waiting.descendingIterator(); it.hasNext(); ) p.sendMessage(helpLine(it.next()));
            play(p, settings.helpSound());
            waiting.clear();
        }, 60L);
    }

    private void history(CommandSender to) throws CommandSyntaxException {
        if (recent.isEmpty()) throw ChacaApi.error("이 서버가 받은 도움 요청이 아직 없어요");
        to.sendMessage(Component.text("── 최근 도움 요청 (이 서버가 받은 것, 최신순) ──", NamedTextColor.GOLD));
        int n = 0;
        for (Help h : recent) { to.sendMessage(helpLine(h)); if (++n >= 20) break; }
    }

    /** 모든 요청을 파일에 한 줄씩 (비동기). 서버를 껐다 켜도 남는 기록. */
    private void writeLog(Help h) {
        String line = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(h.time()), java.time.ZoneId.systemDefault()) + "\t" + h.server() + "\t" + h.who() + "(" + h.account() + ")\t" + h.text() + System.lineSeparator();
        var path = logFile.toPath();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (ConvenienceCommands.class) {
                try { Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                catch (Exception error) { plugin.getLogger().warning("도움 요청 기록 실패: " + error); }
            }
        });
    }

    // ------------------------------------------------------------------ /쓰레기통

    /** 쓰레기통 창 표시. */
    static final class Trash implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private void trash(Player p) {
        var holder = new Trash();
        holder.inventory = Bukkit.createInventory(holder, settings.trashRows() * 9, Component.text(settings.trashTitle()));
        p.openInventory(holder.inventory);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Trash)) return;
        int count = 0;
        for (ItemStack item : e.getInventory().getContents()) if (item != null && !item.getType().isAir()) count += item.getAmount();
        e.getInventory().clear();
        if (count <= 0 || !(e.getPlayer() instanceof Player p)) return;
        plugin.getLogger().info("TRASH player=" + p.getName() + " items=" + count);
        if (p.isOnline()) CmdKit.notify(p, "system", "trash", "아이템 " + count + "개를 버렸어요", "", 0);
    }

    // ------------------------------------------------------------------ /메모리

    private void memory(CommandSender to) {
        var rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        double[] tps = Bukkit.getServer().getTPS();
        double mspt = Bukkit.getServer().getAverageTickTime();
        int chunks = 0, entities = 0;
        for (World w : Bukkit.getWorlds()) { chunks += w.getLoadedChunks().length; entities += w.getEntities().size(); }
        var lines = new ArrayList<String>();
        lines.add("── 서버 상태" + (serverId().isEmpty() ? "" : " · " + serverId()) + " ──");
        lines.add("메모리: " + ConvenienceRules.megabytes(used) + " 사용 / " + ConvenienceRules.megabytes(rt.maxMemory()) + " 최대 (잡아 둔 것 " + ConvenienceRules.megabytes(rt.totalMemory()) + ")");
        lines.add("TPS: " + ConvenienceRules.tps(tps.length > 0 ? tps[0] : Double.NaN) + " (1분) · " + ConvenienceRules.tps(tps.length > 1 ? tps[1] : Double.NaN) + " (5분) · "
                + ConvenienceRules.tps(tps.length > 2 ? tps[2] : Double.NaN) + " (15분)");
        lines.add("MSPT: " + String.format(Locale.ROOT, "%.1f", mspt) + "ms (50 넘으면 렉)");
        lines.add("접속자: " + Bukkit.getOnlinePlayers().size() + "명 · 로드된 청크: " + chunks + " · 엔티티: " + entities);
        CmdKit.lines(to, lines);
    }

    // ------------------------------------------------------------------ 서버 간 메시지

    /** 다른 서버로 보낸다. 실어 보낼 접속자가 없으면 false. */
    private boolean forward(Player carrier, ConvenienceRules.Wire w) {
        Player via = carrier != null && carrier.isOnline() ? carrier : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (via == null) return false;
        try { via.sendPluginMessage(plugin, CHANNEL, ConvenienceRules.forwardAll(ConvenienceRules.encode(w))); return true; }
        catch (RuntimeException error) { plugin.getLogger().warning("서버 간 공지·도움 전달 실패: " + error); return false; }
    }

    @Override public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        var w = ConvenienceRules.decode(ConvenienceRules.unwrapForward(message));
        if (w == null || origin.equals(w.origin())) return;
        switch (w.kind()) {
            case NOTICE -> { if (seen(w.id())) showNotice(w); }
            case ANNOUNCE -> {
                if (!seen(w.id())) return;
                showAnnounce(w);
                var hook = announceHook;   // 별풍선 후원 사망: 이 서버 접속자 기념 상자 (우편 source 가 같아 보낸 서버와 겹쳐도 한 통)
                if (hook != null) try { hook.accept(w); } catch (RuntimeException error) { plugin.getLogger().warning("가운데 공지 후속 처리 실패: " + error); }
            }
            case HELP -> {
                if (!seen(w.id())) return;
                String text = ConvenienceRules.clean(w.text(), 500);
                int ops = deliverHelp(new Help(w.id(), w.server(), w.who(), w.account(), text, w.time()));
                if (ops > 0) forward(null, new ConvenienceRules.Wire(ConvenienceRules.Kind.HELP_ACK, origin, w.id(), serverId(), "", "", "", System.currentTimeMillis()));
            }
            case HELP_ACK -> {
                UUID who = pending.remove(w.id());
                Player p = who == null ? null : Bukkit.getPlayer(who);
                if (p != null) ok(p, settings.helpSent());
            }
        }
    }

    /** 처음 보는 id 면 true (최근 256개 기억). */
    private boolean seen(String id) {
        if (!seen.add(id)) return false;
        if (seen.size() > 256) { var it = seen.iterator(); it.next(); it.remove(); }
        return true;
    }

    // ------------------------------------------------------------------ 도움

    private String serverId() {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); }
        catch (RuntimeException e) { return ""; }
    }

    private static void play(Player p, ConvenienceRules.Sound s) {
        if (!s.enabled()) return;
        try { p.playSound(p.getLocation(), s.key(), SoundCategory.MASTER, s.volume(), s.pitch()); }
        catch (RuntimeException ignored) {}
    }

    /** 순간이동 전 빗자루에서 내리게 한다 (OperatorCommands·TravelBridge 와 같은 방식). */
    private static void stopBroom(Player p) {
        var motion = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (motion == null || !motion.isEnabled()) return;
        try {
            if (Boolean.TRUE.equals(motion.getClass().getMethod("isRidingBroom", Player.class).invoke(motion, p)))
                motion.getClass().getMethod("stopBroom", Player.class).invoke(motion, p);
        } catch (ReflectiveOperationException | LinkageError ignored) {}
    }
}
