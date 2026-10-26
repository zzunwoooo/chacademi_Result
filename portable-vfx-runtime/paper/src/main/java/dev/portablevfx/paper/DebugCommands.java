package dev.portablevfx.paper;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.portablevfx.paper.api.EffectRequest;
import dev.portablevfx.paper.api.PlayResult;
import dev.portablevfx.protocol.VfxProtocol;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * PortableVFX operator commands on the MagicCodexBridge command hub (OP only; replaces /pvfxserverdebug).
 * <pre>
 * /테스트 마법 이펙트 &lt;마법&gt; [단계]
 * /개발 이펙트 재생 &lt;효과&gt; &lt;월드&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [크기] [틱] [요] [피치] [롤] [색] [불투명도] [반경]
 * /개발 이펙트 따라가기 &lt;효과&gt; &lt;대상&gt; [크기] [틱] [기준점] [x오프셋] [y오프셋] [z오프셋]
 * /개발 이펙트 마무리 &lt;핸들&gt; · 정지 &lt;핸들&gt; · 전부정지 · 준비상태 &lt;닉네임&gt; · 상태
 * /서버관리 새로고침 이펙트 · /서버관리 상태 (이펙트)
 * </pre>
 * Raw relay diagnostics ([PortableVFX] …) are shown only here, in the operator's chat.
 */
final class DebugCommands {
    private static final List<String> ANCHORS = List.of("world", "entity", "head", "left_shoulder", "right_shoulder");
    private final PortableVfxPlugin plugin;

    DebugCommands(PortableVfxPlugin plugin) { this.plugin = plugin; }

    static boolean hubReady() { return ChacaLink.ready(); }

    void register() {
        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("마법").then(spellEffect()),
            "마나 없이 마법 이펙트(카탈로그 연출)만 재생", "마법 이펙트 ");
        ChacaLink.branch(plugin, ChacaLink.개발, Commands.literal("이펙트")
                .then(play()).then(follow())
                .then(Commands.literal("마무리").then(Commands.argument("핸들", ChacaLink.word(List::of)).executes(ctx -> {
                    UUID handle = uuid(ctx.getArgument("핸들", String.class));
                    return debug(ctx, () -> List.of("[PortableVFX debug] finish=" + plugin.debugFinish(handle)));
                })))
                .then(Commands.literal("정지").then(Commands.argument("핸들", ChacaLink.word(List::of)).executes(ctx -> {
                    UUID handle = uuid(ctx.getArgument("핸들", String.class));
                    return debug(ctx, () -> List.of(plugin.debugStop(handle) ? "[PortableVFX] 중지 요청: " + handle : "[PortableVFX] 활성 핸들이 없습니다: " + handle));
                })))
                .then(Commands.literal("전부정지").executes(ctx -> debug(ctx, () -> List.of("[PortableVFX] " + plugin.debugClear() + "개 핸들을 정리했습니다."))))
                .then(Commands.literal("준비상태").then(Commands.argument(ChacaLink.TARGET, ChacaLink.player()).executes(ctx -> {
                    Player target = ChacaLink.resolveOnline(ctx, ChacaLink.TARGET);
                    return debug(ctx, () -> List.of("[PortableVFX readiness] " + ChacaLink.name(target.getUniqueId()) + "(" + target.getName() + "): " + plugin.readiness(target.getUniqueId())));
                })))
                .then(Commands.literal("상태").executes(ctx -> debug(ctx, () -> plugin.debugStatus(ctx.getSource().getSender() instanceof Player p ? p : null)))),
            "PortableVFX 저수준 재생·핸들·준비 상태 디버그", "이펙트 상태");
        ChacaLink.reload(plugin, "이펙트", "PortableVFX config.yml·spell-catalog.yml·spell-bindings.yml (실패하면 이전 카탈로그 유지)", plugin::reloadAll);
        ChacaLink.status(plugin, "이펙트", plugin::statusLine);
    }

    // ------------------------------------------------------------------ /테스트 마법 이펙트

    private LiteralArgumentBuilder<CommandSourceStack> spellEffect() {
        return Commands.literal("이펙트").then(Commands.argument("마법", ChacaLink.word(plugin::spellSuggestions))
            .executes(ctx -> spellEffect(ctx, null))
            .then(Commands.argument("단계", ChacaLink.word(List::of)).suggests((ctx, b) -> {
                String id = plugin.resolveSpell(ctx.getArgument("마법", String.class));
                if (id != null) for (String e : plugin.spellEvents(id)) if (e.startsWith(b.getRemaining())) b.suggest(e);
                return b.buildFuture();
            }).executes(ctx -> spellEffect(ctx, ctx.getArgument("단계", String.class)))));
    }

    private int spellEffect(CommandContext<CommandSourceStack> ctx, String event) throws CommandSyntaxException {
        if (!(ctx.getSource().getSender() instanceof Player player)) throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
        String typed = ctx.getArgument("마법", String.class);
        String id = plugin.resolveSpell(typed);
        if (id == null) throw ChacaLink.error("카탈로그에 없는 마법이에요: " + typed);
        String name = plugin.spellName(id);
        if (event == null && plugin.mechanicOwned(id)) throw ChacaLink.error(name + "의 이펙트는 미몹 스킬이 직접 내요. /테스트 마법 시전 으로 확인해 주세요");
        Boolean result = event == null ? plugin.debugCatalogCast(player, id) : plugin.debugPreviewEvent(player, id, event);
        if (result == null) throw ChacaLink.error(name + "에는 이펙트 연출이 없어요");
        if (!result) throw ChacaLink.error(name + " 이펙트를 시작하지 못했어요 (원인: /개발 이펙트 상태)");
        ChacaLink.ok(player, name + " 이펙트" + (event == null ? "" : " (" + event + ")") + "를 재생했어요");
        return 1;
    }

    // ------------------------------------------------------------------ /개발 이펙트 재생 · 따라가기

    private LiteralArgumentBuilder<CommandSourceStack> play() {
        var radius = Commands.argument("반경", DoubleArgumentType.doubleArg(0, VfxProtocol.MAX_POSITION)).executes(this::play);
        var opacity = Commands.argument("불투명도", FloatArgumentType.floatArg(0, 1)).executes(this::play).then(radius);
        var color = Commands.argument("색", ChacaLink.word(() -> List.of("ffffff"))).executes(this::play).then(opacity);
        var roll = Commands.argument("롤", FloatArgumentType.floatArg(-360, 360)).executes(this::play).then(color);
        var pitch = Commands.argument("피치", FloatArgumentType.floatArg(-360, 360)).executes(this::play).then(roll);
        var yaw = Commands.argument("요", FloatArgumentType.floatArg(-360, 360)).executes(this::play).then(pitch);
        var ticks = Commands.argument("틱", IntegerArgumentType.integer(1)).executes(this::play).then(yaw);
        var scale = Commands.argument("크기", FloatArgumentType.floatArg(0.01f)).executes(this::play).then(ticks);
        var z = Commands.argument("z", DoubleArgumentType.doubleArg()).executes(this::play).then(scale);
        return Commands.literal("재생").then(effectArg()
            .then(Commands.argument("월드", ChacaLink.word(() -> Bukkit.getWorlds().stream().map(World::getName).toList()))
                .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("y", DoubleArgumentType.doubleArg()).then(z)))));
    }

    private int play(CommandContext<CommandSourceStack> ctx) {
        return debug(ctx, () -> {
            var request = new EffectRequest(effect(ctx), ctx.getArgument("월드", String.class),
                DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"),
                opt(ctx, "크기", Float.class, 1f), opt(ctx, "틱", Integer.class, Math.min(40, plugin.maxDurationTicks())),
                opt(ctx, "요", Float.class, 0f), opt(ctx, "피치", Float.class, 0f), opt(ctx, "롤", Float.class, 0f),
                color(opt(ctx, "색", String.class, "ffffff")), opt(ctx, "불투명도", Float.class, 1f), opt(ctx, "반경", Double.class, plugin.defaultRadius()));
            PlayResult result = plugin.debugPlay(request);
            var out = new ArrayList<String>();
            out.add("[PortableVFX] handle=" + result.handle() + ", 전송=" + result.recipients()
                + ", 다음 tick 재시도=" + result.deferred() + ", 예산 제외=" + result.skippedRateLimited() + ", 반경=" + result.effectiveRadius());
            if (result.recipients() == 0 && result.deferred() == 0)
                out.add("[PortableVFX] 활성 핸들이 생성되지 않았습니다. 같은 월드·반경 내 호환 클라이언트와 채널 등록을 확인하세요.");
            return out;
        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> follow() {
        var oz = Commands.argument("z오프셋", FloatArgumentType.floatArg()).executes(this::follow);
        var oy = Commands.argument("y오프셋", FloatArgumentType.floatArg()).executes(this::follow).then(oz);
        var ox = Commands.argument("x오프셋", FloatArgumentType.floatArg()).executes(this::follow).then(oy);
        var anchor = Commands.argument("기준점", ChacaLink.word(() -> ANCHORS)).executes(this::follow).then(ox);
        var ticks = Commands.argument("틱", IntegerArgumentType.integer(1)).executes(this::follow).then(anchor);
        var scale = Commands.argument("크기", FloatArgumentType.floatArg(0.01f)).executes(this::follow).then(ticks);
        return Commands.literal("따라가기").then(effectArg()
            .then(Commands.argument("대상", ChacaLink.word(() -> List.of("나"))).executes(this::follow).then(scale)));
    }

    private int follow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String who = ctx.getArgument("대상", String.class);
        Entity entity;
        if (who.equals("나")) {
            if (!(ctx.getSource().getSender() instanceof Player p)) throw ChacaLink.error("콘솔에서는 엔티티 UUID를 적어 주세요");
            entity = p;
        } else entity = Bukkit.getEntity(uuid(who));
        if (entity == null || !entity.isValid() || entity.isDead()) throw ChacaLink.error("살아 있고 로드된 엔티티가 아니에요");
        Entity target = entity;
        return debug(ctx, () -> {
            PlayResult result = plugin.debugFollow(effect(ctx), target, opt(ctx, "크기", Float.class, 1f), opt(ctx, "틱", Integer.class, 100),
                opt(ctx, "기준점", String.class, null), opt(ctx, "x오프셋", Float.class, 0f), opt(ctx, "y오프셋", Float.class, 0f), opt(ctx, "z오프셋", Float.class, 0f));
            return List.of("[PortableVFX] follow handle=" + result.handle() + ", recipients=" + result.recipients());
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Effect IDs look like claude:pack/system: a resource-key argument, so the client does not mark ':' as an error. */
    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.kyori.adventure.key.Key> effectArg() {
        return Commands.argument("효과", io.papermc.paper.command.brigadier.argument.ArgumentTypes.key()).suggests((ctx, b) -> {
            String typed = b.getRemaining();
            for (String id : plugin.effectIds()) if (id.startsWith(typed)) b.suggest(id);
            return b.buildFuture();
        });
    }

    private static String effect(CommandContext<CommandSourceStack> ctx) { return ctx.getArgument("효과", net.kyori.adventure.key.Key.class).asString(); }

    @FunctionalInterface private interface Debug { List<String> run(); }

    /** OP debug output (raw relay text) in chat; relay rejections are shown as "[PortableVFX] 거부: …" there too. */
    private static int debug(CommandContext<CommandSourceStack> ctx, Debug body) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            ChacaLink.lines(sender, body.run());
            return 1;
        } catch (IllegalArgumentException | RejectedExecutionException e) {
            ChacaLink.lines(sender, List.of("[PortableVFX] 거부: " + e.getMessage()));
            return 0;
        }
    }

    private static UUID uuid(String raw) throws CommandSyntaxException {
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { throw ChacaLink.error("UUID 형식이 아니에요: " + raw); }
    }

    private static int color(String input) {
        String hex = input.startsWith("#") ? input.substring(1) : input;
        if (!hex.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("색은 RRGGBB 16진수 6자리로 적어 주세요");
        return Integer.parseInt(hex, 16);
    }

    private static <T> T opt(CommandContext<CommandSourceStack> ctx, String name, Class<T> type, T fallback) {
        try { return ctx.getArgument(name, type); } catch (IllegalArgumentException missing) { return fallback; }
    }
}
