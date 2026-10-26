package school.magiccodex.paper;

import static school.magiccodex.paper.CmdKit.*;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 운영자 기본 편의 명령 (오픈 전 메모 #95, 2026-10-25). Essentials가 하던 것 중 가장 기본적인 것만, 한글 닉네임으로.
 * <ul>
 *   <li>/유저관리 &lt;닉네임&gt; 게임모드 &lt;서바이벌|크리에이티브|모험|관전&gt;</li>
 *   <li>/유저관리 &lt;닉네임&gt; 찾아가기 — 내가 그 유저에게 간다</li>
 *   <li>/유저관리 &lt;닉네임&gt; 불러오기 — 그 유저를 내 위치로 데려온다</li>
 *   <li>/유저관리 &lt;닉네임&gt; 좌표이동 &lt;x&gt; &lt;y&gt; &lt;z&gt; [월드]</li>
 *   <li>/유저관리 &lt;닉네임&gt; 비행 &lt;켜기|끄기&gt;</li>
 *   <li>/유저관리 &lt;닉네임&gt; 회복 — 체력·배고픔·불 끄기</li>
 * </ul>
 * 자기 자신에게 쓰려면 내 닉네임을 적는다. OP 검사는 허브가 대단원 단위로 한다.
 * 찾아가기·불러오기 대상이 이 서버에 없으면 HuskHomes가 켜져 있을 때만 HuskHomes 명령(/huskhomes:tp, /huskhomes:tphere)에
 * 맡긴다 (소프트 의존, API 직접 호출 없음). 이동 전 빗자루에서 내리게 하는 것은 지도 이동(TravelBridge)과 같다.
 */
final class OperatorCommands {
    /** 게임모드 한글 이름 (자동완성 순서). */
    static final Map<String, GameMode> GAME_MODES;
    static {
        var m = new LinkedHashMap<String, GameMode>();
        m.put("서바이벌", GameMode.SURVIVAL); m.put("크리에이티브", GameMode.CREATIVE); m.put("모험", GameMode.ADVENTURE); m.put("관전", GameMode.SPECTATOR);
        GAME_MODES = Collections.unmodifiableMap(m);
    }
    static final String HUSKHOMES = "HuskHomes";
    private static final double MAX_XZ = 29_999_984;

    private final MagicCodexBridge plugin;

    OperatorCommands(MagicCodexBridge plugin) {
        this.plugin = plugin;
        register(plugin, "/유저관리 게임모드·찾아가기·불러오기·좌표이동·비행·회복", () -> {
            ChacaApi.branch(plugin, ChacaApi.유저관리, gameMode(), "게임모드 바꾸기 (서바이벌·크리에이티브·모험·관전)", "게임모드 서바이벌");
            ChacaApi.branch(plugin, ChacaApi.유저관리, literal("찾아가기").executes(run(this::goTo)), "내가 그 유저 옆으로 순간이동", "찾아가기");
            ChacaApi.branch(plugin, ChacaApi.유저관리, literal("불러오기").executes(run(this::bring)), "그 유저를 내 옆으로 데려오기", "불러오기");
            ChacaApi.branch(plugin, ChacaApi.유저관리, coordinates(), "그 유저를 좌표로 순간이동 (월드 생략 = 지금 월드)", "좌표이동 0 80 0");
            ChacaApi.branch(plugin, ChacaApi.유저관리, flight(), "날기 켜기·끄기 (빗자루와 별개)", "비행 켜기");
            ChacaApi.branch(plugin, ChacaApi.유저관리, literal("회복").executes(run(this::heal)), "체력·배고픔 가득, 불 끄기", "회복");
        });
    }

    /** 한글 게임모드 이름 → GameMode. 없으면 IllegalArgumentException(한글 문구). */
    static GameMode gameMode(String name) {
        GameMode mode = name == null ? null : GAME_MODES.get(name.strip());
        if (mode == null) throw new IllegalArgumentException("게임모드는 " + String.join("·", GAME_MODES.keySet()) + " 중 하나예요");
        return mode;
    }

    static String gameModeName(GameMode mode) {
        for (var e : GAME_MODES.entrySet()) if (e.getValue() == mode) return e.getKey();
        return String.valueOf(mode);
    }

    // ------------------------------------------------------------------ 게임모드

    private LiteralArgumentBuilder<CommandSourceStack> gameMode() {
        var root = literal("게임모드");
        for (String name : GAME_MODES.keySet()) root.then(literal(name).executes(run(ctx -> {
            Player target = ChacaApi.targetOnline(ctx);
            GameMode mode = user(() -> gameMode(name));
            if (target.getGameMode() == mode) { ok(sender(ctx), plugin.names().name(target) + " 님은 이미 " + name + " 모드예요"); return; }
            if (mode == GameMode.SPECTATOR) stopBroom(target);
            target.setGameMode(mode);
            log(ctx, "gamemode " + mode);
            ok(sender(ctx), plugin.names().name(target) + " 님을 " + name + " 모드로 바꿨어요");
        })));
        return root;
    }

    // ------------------------------------------------------------------ 순간이동

    /** 편의 명령 /텔포 · /소환 도 같이 쓴다 (칸 이름 "닉네임"). */
    void goTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player me = self(ctx);
        UUID id = ChacaApi.target(ctx);
        if (id.equals(me.getUniqueId())) throw ChacaApi.error("자기 자신에게는 갈 수 없어요");
        Player target = Bukkit.getPlayer(id);
        if (target == null) { viaHuskHomes(me, id, "tp"); return; }
        if (target.isDead()) throw ChacaApi.error(plugin.names().name(target) + " 님이 쓰러져 있어요. 부활한 뒤 다시 해 주세요");
        teleport(ctx, me, target.getLocation(), plugin.names().name(target) + " 님 옆으로 왔어요");
    }

    /** 편의 명령 /텔포 · /소환 도 같이 쓴다 (칸 이름 "닉네임"). */
    void bring(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player me = self(ctx);
        UUID id = ChacaApi.target(ctx);
        if (id.equals(me.getUniqueId())) throw ChacaApi.error("자기 자신은 불러올 수 없어요");
        Player target = Bukkit.getPlayer(id);
        if (target == null) { viaHuskHomes(me, id, "tphere"); return; }
        if (target.isDead()) throw ChacaApi.error(plugin.names().name(target) + " 님이 쓰러져 있어요. 부활한 뒤 다시 해 주세요");
        teleport(ctx, target, me.getLocation(), plugin.names().name(target) + " 님을 불러왔어요");
    }

    private LiteralArgumentBuilder<CommandSourceStack> coordinates() {
        var z = argument("z", DoubleArgumentType.doubleArg(-MAX_XZ, MAX_XZ)).executes(run(ctx -> toCoordinates(ctx, null)))
                .then(word("월드", () -> Bukkit.getWorlds().stream().map(World::getName).toList()).executes(run(ctx -> toCoordinates(ctx, text(ctx, "월드")))));
        return literal("좌표이동").then(argument("x", DoubleArgumentType.doubleArg(-MAX_XZ, MAX_XZ))
                .then(argument("y", DoubleArgumentType.doubleArg(-2048, 2048)).then(z)));
    }

    private void toCoordinates(CommandContext<CommandSourceStack> ctx, String worldName) throws CommandSyntaxException {
        Player target = ChacaApi.targetOnline(ctx);
        World world = worldName == null ? target.getWorld() : Bukkit.getWorld(worldName);
        if (world == null) throw ChacaApi.error("그런 월드가 없어요: " + worldName);
        double x = DoubleArgumentType.getDouble(ctx, "x"), y = DoubleArgumentType.getDouble(ctx, "y"), zz = DoubleArgumentType.getDouble(ctx, "z");
        if (y < world.getMinHeight() || y > world.getMaxHeight() + 64)
            throw ChacaApi.error("높이(y)는 " + world.getMinHeight() + " ~ " + (world.getMaxHeight() + 64) + " 사이로 적어 주세요");
        Location here = target.getLocation();
        // 정수 좌표는 블록 가운데로 (Essentials /tppos 와 같은 느낌)
        Location to = new Location(world, x == Math.rint(x) ? x + .5 : x, y, zz == Math.rint(zz) ? zz + .5 : zz, here.getYaw(), here.getPitch());
        if (target.isDead()) throw ChacaApi.error(plugin.names().name(target) + " 님이 쓰러져 있어요. 부활한 뒤 다시 해 주세요");
        teleport(ctx, target, to, plugin.names().name(target) + " 님을 " + world.getName() + " " + (long) Math.floor(x) + ", " + (long) Math.floor(y) + ", " + (long) Math.floor(zz) + " 로 보냈어요");
    }

    /** 이 서버 안 순간이동. 다른 플러그인(큐디치 경기 보호 등)이 막으면 그 결과를 알린다. */
    private void teleport(CommandContext<CommandSourceStack> ctx, Player who, Location to, String done) {
        CommandSender sender = sender(ctx);
        stopBroom(who);
        String admin = sender.getName(), input = ctx.getInput();
        who.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((moved, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { fail(sender, "순간이동하지 못했어요", error); return; }
            if (!Boolean.TRUE.equals(moved)) { userFail(sender, "순간이동이 막혔어요 (경기 중이거나 보호 구역일 수 있어요)"); return; }
            who.setFallDistance(0);
            plugin.getLogger().info("OPERATOR TELEPORT admin=" + admin + " player=" + who.getName() + " to=" + to.getWorld().getName() + "," + to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ() + " /" + input);
            ok(sender, done);
        }));
    }

    /** 대상이 이 서버에 없을 때: HuskHomes(서버 간 이동)에 맡긴다. 없으면 유저가 고칠 수 있는 실패. */
    private void viaHuskHomes(Player me, UUID target, String command) throws CommandSyntaxException {
        String who = CmdKit.name(target);
        var husk = Bukkit.getPluginManager().getPlugin(HUSKHOMES);
        if (husk == null || !husk.isEnabled()) throw ChacaApi.error(who + " 님이 이 서버에 없어요 (서버를 넘는 이동은 HuskHomes가 있어야 해요)");
        String account = Bukkit.getOfflinePlayer(target).getName();
        if (account == null || !account.matches("[A-Za-z0-9_]{1,16}")) throw ChacaApi.error(who + " 님의 계정 이름을 이 서버에서 찾지 못했어요");
        stopBroom(me);
        plugin.getLogger().info("OPERATOR TELEPORT admin=" + me.getName() + " → HuskHomes /" + command + " " + account);
        // HuskHomes가 결과(성공·상대가 오프라인 등)를 직접 알린다.
        if (!me.performCommand("huskhomes:" + command + " " + account)) throw ChacaApi.error("HuskHomes 명령을 실행하지 못했어요");
    }

    // ------------------------------------------------------------------ 비행 · 회복

    private LiteralArgumentBuilder<CommandSourceStack> flight() {
        var root = literal("비행");
        for (String state : List.of("켜기", "끄기")) root.then(literal(state).executes(run(ctx -> {
            Player target = ChacaApi.targetOnline(ctx);
            boolean on = state.equals("켜기");
            if (riding(target)) throw ChacaApi.error(plugin.names().name(target) + " 님이 빗자루를 타고 있어요. 내린 뒤 다시 해 주세요");
            GameMode mode = target.getGameMode();
            if (!on && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR))
                throw ChacaApi.error(gameModeName(mode) + " 모드에서는 비행을 끌 수 없어요. 먼저 게임모드를 바꿔 주세요");
            target.setAllowFlight(on);
            if (!on) { target.setFlying(false); target.setFallDistance(0); }
            log(ctx, "flight " + on);
            ok(sender(ctx), plugin.names().name(target) + " 님 비행을 " + (on ? "켰어요" : "껐어요"));
        })));
        return root;
    }

    private void heal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = ChacaApi.targetOnline(ctx);
        if (target.isDead()) throw ChacaApi.error(plugin.names().name(target) + " 님이 쓰러져 있어요. 부활한 뒤 다시 해 주세요");
        var max = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(max == null ? 20 : max.getValue());
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);
        log(ctx, "heal");
        ok(sender(ctx), plugin.names().name(target) + " 님 체력·배고픔을 채웠어요");
    }

    // ------------------------------------------------------------------ 도움

    private void log(CommandContext<CommandSourceStack> ctx, String what) {
        plugin.getLogger().info("OPERATOR " + what + " admin=" + sender(ctx).getName() + " /" + ctx.getInput());
    }

    /** ChacademyMotion 빗자루 (선택). 없으면 false. */
    private static boolean riding(Player p) {
        var motion = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (motion == null || !motion.isEnabled()) return false;
        try { return Boolean.TRUE.equals(motion.getClass().getMethod("isRidingBroom", Player.class).invoke(motion, p)); }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    /** 순간이동 전 빗자루에서 내리게 한다 (TravelBridge.stopBroom 과 같은 방식). */
    private static void stopBroom(Player p) {
        var motion = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (motion == null || !motion.isEnabled()) return;
        try {
            if (Boolean.TRUE.equals(motion.getClass().getMethod("isRidingBroom", Player.class).invoke(motion, p)))
                motion.getClass().getMethod("stopBroom", Player.class).invoke(motion, p);
        } catch (ReflectiveOperationException | LinkageError ignored) {}
    }
}
