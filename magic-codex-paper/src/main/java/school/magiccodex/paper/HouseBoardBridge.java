package school.magiccodex.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.HouseBoardProtocol;

/**
 * 기숙사 점수 홀로그램 (오픈 전 메모 #106, 오너 2026-10-26 "게시판 없이 단순하게 홀로그램 느낌으로").
 * house-board.yml 의 servers(travel.yml server-id, 기본 학교 school)에서만 켜진다. 다른 서버는 채널·이벤트·작업을 하나도 열지 않는다.
 *
 * <ul>
 *   <li>관리자가 정한 자리(여러 곳) + 네 기숙사 점수를 그 세계에 있는 플레이어의 클라에 보낸다. 그리기는 전부 클라
 *       ({@code HouseBoardClient}) — 엔티티·블록·게시판 없음.</li>
 *   <li>점수는 {@link SchoolBridge#data()} (학교 기록 메모리 캐시, 공유 DB 면 SchoolBridge 가 이미 2초마다 갱신) 만 읽는다.
 *       이 기능은 DB 를 치지 않는다.</li>
 *   <li>보내기: 채널 등록(접속)·세계 이동·자리 추가/삭제/새로고침 때, 그리고 {@code refresh-seconds}(기본 20초)마다 훑어서
 *       지난번에 보낸 내용과 다른 사람에게만 (세계별로 한 번만 만든다).</li>
 * </ul>
 */
final class HouseBoardBridge implements Listener, AutoCloseable {
    static final String FILE = "house-board.yml";
    static final int MAX_TOTAL = 64;

    record Board(String name, String world, double x, double y, double z, int facing) {}
    private record Settings(boolean enabled, Set<String> servers, int range, int refreshSeconds, double above, Map<String, Board> boards) {
        boolean activeOn(String server) { return enabled && (servers.isEmpty() || servers.contains(server)); }
    }

    private final MagicCodexBridge plugin;
    private final File file;
    private final String serverId;
    private final boolean active;
    private Settings settings;
    private final Map<UUID, Integer> sent = new HashMap<>();
    private BukkitTask task;

    HouseBoardBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        settings = parse();
        serverId = serverId(plugin);
        active = settings.activeOn(serverId);
        if (active) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, HouseBoardProtocol.CHANNEL);
            Bukkit.getPluginManager().registerEvents(this, plugin);
            schedule();
            plugin.getLogger().info("기숙사 점수 홀로그램: 켜짐 · 서버 " + serverId + " · 자리 " + settings.boards().size() + "곳 · 점수 확인 " + settings.refreshSeconds() + "초");
        } else plugin.getLogger().info("기숙사 점수 홀로그램: 이 서버(" + (serverId.isEmpty() ? "server-id 없음" : serverId) + ")에서는 꺼짐 (" + FILE + " servers " + settings.servers() + ")");
        CmdKit.register(plugin, "/컨텐츠관리 기숙사판", this::commands);
    }

    private static String serverId(MagicCodexBridge plugin) {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); }
        catch (RuntimeException e) { return ""; }
    }

    private void schedule() {
        if (task != null) task.cancel();
        long period = settings.refreshSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pass, period, period);
    }

    @Override public void close() {
        if (task != null) { task.cancel(); task = null; }
        sent.clear();
    }

    // ------------------------------------------------------------------ 설정

    private Settings parse() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        int range = y.getInt("view-distance", 48), refresh = y.getInt("refresh-seconds", 20);
        if (range < HouseBoardProtocol.MIN_RANGE || range > HouseBoardProtocol.MAX_RANGE) throw new IllegalArgumentException("view-distance 는 8~96 이에요 (" + range + ")");
        if (refresh < 5 || refresh > 300) throw new IllegalArgumentException("refresh-seconds 는 5~300 이에요 (" + refresh + ")");
        double above = y.getDouble("height-above-eyes", 0.9);
        if (!Double.isFinite(above) || above < -2 || above > 5) throw new IllegalArgumentException("height-above-eyes 는 -2~5 예요 (" + above + ")");
        var boards = new LinkedHashMap<String, Board>();
        ConfigurationSection root = y.getConfigurationSection("boards");
        if (root != null) for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null || !s.isString("world") || !s.isSet("x") || !s.isSet("y") || !s.isSet("z")) throw new IllegalArgumentException("boards." + name + ": world·x·y·z 가 필요해요");
            int facing = HouseBoardProtocol.facingOf(s.getString("facing", "남"));
            if (facing < 0) throw new IllegalArgumentException("boards." + name + ": facing 은 동·서·남·북 중 하나예요");
            var b = new Board(name, s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), facing);
            if (!Double.isFinite(b.x()) || !Double.isFinite(b.y()) || !Double.isFinite(b.z())) throw new IllegalArgumentException("boards." + name + ": 좌표가 숫자가 아니에요");
            boards.put(name, b);
        }
        if (boards.size() > MAX_TOTAL) throw new IllegalArgumentException("자리는 최대 " + MAX_TOTAL + "곳이에요");
        return new Settings(y.getBoolean("enabled", true), y.isList("servers") ? new HashSet<>(y.getStringList("servers")) : Set.of("school"),
                range, refresh, above, Collections.unmodifiableMap(boards));
    }

    private void reload() throws Exception {
        settings = parse();
        if (active) { schedule(); resendAll(); }
    }

    /** boards 칸만 바꿔 저장 (다른 설정·주석은 그대로). 실패하면 메모리도 그대로. */
    private void saveBoards(Map<String, Board> next) throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        y.set("boards", null);
        var root = y.createSection("boards");
        for (var b : next.values()) {
            var s = root.createSection(b.name());
            s.set("world", b.world());
            s.set("x", round(b.x())); s.set("y", round(b.y())); s.set("z", round(b.z())); s.set("facing", HouseBoardProtocol.FACING_NAMES.get(b.facing()));
        }
        y.save(file);
        settings = new Settings(settings.enabled(), settings.servers(), settings.range(), settings.refreshSeconds(), settings.above(), Collections.unmodifiableMap(new LinkedHashMap<>(next)));
    }
    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    // ------------------------------------------------------------------ 보내기

    @EventHandler public void onRegister(PlayerRegisterChannelEvent e) {
        if (e.getChannel().equals(HouseBoardProtocol.CHANNEL)) send(e.getPlayer(), null);
    }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { send(e.getPlayer(), null); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { sent.remove(e.getPlayer().getUniqueId()); }

    private void pass() {
        var cache = new HashMap<String, byte[]>();
        for (Player p : Bukkit.getOnlinePlayers()) send(p, cache);
    }
    private void resendAll() { pass(); }

    /** 이 플레이어가 있는 세계의 자리 + 점수. 지난번과 같으면 보내지 않는다. 자리 없는 세계에 처음 들어온 사람에게는 보내지 않는다. */
    private void send(Player p, Map<String, byte[]> cache) {
        if (!active || !p.isOnline() || !p.getListeningPluginChannels().contains(HouseBoardProtocol.CHANNEL)) return;
        String world = p.getWorld().getName();
        byte[] bytes = cache == null ? frame(world) : cache.computeIfAbsent(world, this::frame);
        if (bytes == null) return;
        int hash = Arrays.hashCode(bytes);
        Integer before = sent.put(p.getUniqueId(), hash);
        if (before != null && before == hash) return;
        if (before == null && !hasBoards(world)) return;
        p.sendPluginMessage(plugin, HouseBoardProtocol.CHANNEL, bytes);
    }

    private boolean hasBoards(String world) {
        for (var b : settings.boards().values()) if (b.world().equals(world)) return true;
        return false;
    }

    /** null = 학교 기록이 없어 점수를 모름 (아무것도 보내지 않음). */
    private byte[] frame(String world) {
        var school = plugin.schoolBridge();
        var data = school == null ? null : school.data();
        if (data == null || data.scores().size() != HouseBoardProtocol.HOUSES) return null;
        var list = new ArrayList<HouseBoardProtocol.Board>();
        for (var b : settings.boards().values())
            if (b.world().equals(world) && list.size() < HouseBoardProtocol.MAX_BOARDS) list.add(new HouseBoardProtocol.Board(b.x(), b.y(), b.z(), b.facing()));
        try { return HouseBoardProtocol.encode(new HouseBoardProtocol.State(world, settings.range(), data.scores(), list)); }
        catch (IllegalArgumentException bad) { plugin.getLogger().warning("기숙사 점수 홀로그램을 보낼 수 없어요 (" + world + "): " + bad.getMessage()); return null; }
    }

    // ------------------------------------------------------------------ 명령

    private Collection<String> names() { return settings.boards().keySet(); }

    private void commands() {
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, CmdKit.literal("기숙사판")
                .then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> list(ctx))))
                .then(CmdKit.literal("추가").then(CmdKit.word("이름", List::of).executes(CmdKit.run(ctx -> add(ctx, null)))
                        .then(CmdKit.word("방향", () -> List.of("동", "서", "남", "북")).executes(CmdKit.run(ctx -> add(ctx, CmdKit.text(ctx, "방향")))))))
                .then(CmdKit.literal("삭제").then(CmdKit.word("이름", this::names).executes(CmdKit.run(this::remove)))),
                "기숙사 점수 홀로그램 자리 추가(서 있는 곳, 동·서·남·북 중 한 방향 고정)·삭제·목록", "기숙사판 추가 광장 남");
        ChacaApi.reload(plugin, "기숙사판", FILE + " (기숙사 점수 홀로그램 자리·거리·주기)", this::reload);
        ChacaApi.status(plugin, "기숙사 점수 홀로그램", () -> !active ? "꺼짐 (이 서버 " + (serverId.isEmpty() ? "?" : serverId) + ")" : "자리 " + settings.boards().size() + "곳");
    }

    private void list(CommandContext<CommandSourceStack> ctx) {
        var out = new ArrayList<String>();
        out.add("── 기숙사 점수 홀로그램 " + settings.boards().size() + "곳" + (active ? "" : " · 이 서버에서는 꺼짐") + " ──");
        for (var b : settings.boards().values())
            out.add(" " + b.name() + " · " + b.world() + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", b.x(), b.y(), b.z()) + " · " + HouseBoardProtocol.FACING_NAMES.get(b.facing()) + "쪽을 봄");
        if (settings.boards().isEmpty()) out.add(" (없음 · 둘 곳에 서서 /컨텐츠관리 기숙사판 추가 <이름> [동|서|남|북])");
        CmdKit.lines(CmdKit.sender(ctx), out);
    }

    /** 방향을 안 적으면 내가 바라보는 방향을 가장 가까운 90도로 (그쪽에 선 사람이 읽음). */
    private void add(CommandContext<CommandSourceStack> ctx, String direction) throws CommandSyntaxException {
        if (!active) throw ChacaApi.error("이 서버에서는 기숙사 점수 홀로그램이 꺼져 있어요 (" + FILE + " servers)");
        Player p = CmdKit.self(ctx);
        String name = CmdKit.text(ctx, "이름").strip();
        if (!name.matches("[0-9A-Za-z가-힣_-]{1,24}")) throw ChacaApi.error("이름은 한글·영문·숫자·_- 1~24자예요");
        var next = new LinkedHashMap<>(settings.boards());
        boolean moved = next.containsKey(name);
        if (!moved && next.size() >= MAX_TOTAL) throw ChacaApi.error("자리는 최대 " + MAX_TOTAL + "곳이에요");
        Location l = p.getLocation();
        String world = l.getWorld().getName();
        if (!moved && next.values().stream().filter(b -> b.world().equals(world)).count() >= HouseBoardProtocol.MAX_BOARDS)
            throw ChacaApi.error("한 세계에는 최대 " + HouseBoardProtocol.MAX_BOARDS + "곳이에요");
        int facing = direction == null ? HouseBoardProtocol.facingOf(l.getYaw()) : HouseBoardProtocol.facingOf(direction);
        if (facing < 0) throw ChacaApi.error("방향은 동·서·남·북 중 하나예요");
        var b = new Board(name, world, l.getX(), l.getY() + p.getEyeHeight() + settings.above(), l.getZ(), facing);
        next.put(name, b);
        try { saveBoards(next); } catch (Exception e) { CmdKit.fail(CmdKit.sender(ctx), FILE + " 저장 실패", e); return; }
        resendAll();
        CmdKit.ok(CmdKit.sender(ctx), "기숙사 점수 홀로그램 '" + name + "' 을 " + (moved ? "여기로 옮겼어요" : "여기에 띄웠어요") + " · " + HouseBoardProtocol.FACING_NAMES.get(facing) + "쪽을 봐요 (" + world + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", b.x(), b.y(), b.z()) + ")");
    }

    private void remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = CmdKit.text(ctx, "이름").strip();
        var next = new LinkedHashMap<>(settings.boards());
        if (next.remove(name) == null) throw ChacaApi.error("그 이름의 홀로그램이 없어요 (/컨텐츠관리 기숙사판 목록)");
        try { saveBoards(next); } catch (Exception e) { CmdKit.fail(CmdKit.sender(ctx), FILE + " 저장 실패", e); return; }
        resendAll();
        CmdKit.ok(CmdKit.sender(ctx), "기숙사 점수 홀로그램 '" + name + "' 을 지웠어요");
    }
}
