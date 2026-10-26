package dev.portablevfx.client;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.portablevfx.client.api.PortableVfxApi;
import dev.portablevfx.client.render.EffectRenderer;
import dev.portablevfx.client.definition.EffectLibrary;
import dev.portablevfx.protocol.PlayEffect;
import dev.portablevfx.protocol.VfxProtocol;
import java.util.UUID;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Explicitly debug-only local previews; normal spell selection is provided by the server. */
final class ClientCommands {
    private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
            detail -> Text.literal("PortableVFX: " + detail));

    /** Client-only debug root (was /pvfxdebug, 2026-10-20 command cleanup: Korean names only). */
    static final String ROOT = "이펙트도구";
    private static final String EFFECT = "효과", INSTANCE = "인스턴스";

    private ClientCommands() { }

    static void register(PortableVfxApi api, EffectLibrary library, EffectRenderer renderer, dev.portablevfx.client.claude.ClaudePreviewController claude) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(debugRoot(api, library, renderer, claude)));
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> debugRoot(PortableVfxApi api, EffectLibrary library,
            EffectRenderer renderer, dev.portablevfx.client.claude.ClaudePreviewController claude) {
            var root = literal(ROOT).executes(context -> {
                context.getSource().sendFeedback(Text.literal("/" + ROOT + " 재생 <효과> [크기] [틱] [요] [피치] [롤] | 증명재생 <효과> ... (seed 0) | 여기재생 <효과> ... | 좌표재생 <효과> <x> <y> <z> ... | 따라가기 <효과> | 지면발사 <효과> <끝효과> <폭> <속도> <거리> <유지초> | 폭발 <인스턴스> <효과> | 발사 <효과> [속도] | 충돌 <인스턴스> <효과> [법선x 법선y 법선z] | 마무리 <인스턴스> | 설정오류 | 상태 | 목록 | 활성 | 정지 <인스턴스> | 전부정지 | 새로고침"));
                return 1;
            });
            root.then(literal("설정오류").executes(c -> {
                c.getSource().sendFeedback(Text.literal(library.configErrors().isEmpty()?"Claude config: no errors":String.join("\n",library.configErrors())));return 1;
            }));
            root.then(literal("발사").then(effectArgument(api).executes(c -> launch(c,claude,10))
                .then(argument("속도", DoubleArgumentType.doubleArg(0,32)).executes(c -> launch(c,claude,DoubleArgumentType.getDouble(c,"속도"))))));
            root.then(literal("따라가기").then(effectArgument(api).executes(c -> {
                UUID id=claude.follow(c.getArgument(EFFECT,Identifier.class)).orElseThrow(()->INVALID.create("Entity-foot follow rejected"));
                c.getSource().sendFeedback(Text.literal("Client-only attached preview: "+id));return 1;
            })));
            root.then(literal("지면발사").then(effectArgument(api)
                .then(argument("끝효과",IdentifierArgumentType.identifier())
                .then(argument("폭",DoubleArgumentType.doubleArg(.01,64))
                .then(argument("속도",DoubleArgumentType.doubleArg(.01,32))
                .then(argument("거리",DoubleArgumentType.doubleArg(.01,128))
                .then(argument("유지초",DoubleArgumentType.doubleArg(0,10)).executes(c -> {
                    UUID id=claude.groundLaunch(c.getArgument(EFFECT,Identifier.class),c.getArgument("끝효과",Identifier.class),
                        DoubleArgumentType.getDouble(c,"폭"),DoubleArgumentType.getDouble(c,"속도"),
                        DoubleArgumentType.getDouble(c,"거리"),DoubleArgumentType.getDouble(c,"유지초"))
                        .orElseThrow(()->INVALID.create("Ground preview rejected; hold+distance/speed must be <=19 seconds"));
                    c.getSource().sendFeedback(Text.literal("Client-only horizontal preview: "+id+"; no collision, terrain or gameplay; burst/finish/stop supported"));return 1;
                }))))))));
            root.then(literal("폭발").then(argument(INSTANCE,StringArgumentType.word()).then(effectArgument(api).executes(c -> {
                try {
                    UUID id=claude.burst(UUID.fromString(StringArgumentType.getString(c,INSTANCE)),c.getArgument(EFFECT,Identifier.class))
                        .orElseThrow(()->INVALID.create("Active preview and valid burst effect required"));
                    c.getSource().sendFeedback(Text.literal("Independent impact at your feet: "+id));return 1;
                }catch(IllegalArgumentException e){throw INVALID.create(e.getMessage());}
            }))));
            root.then(literal("마무리").then(argument(INSTANCE,StringArgumentType.word()).executes(c -> {
                try { boolean ok=api.finish(UUID.fromString(StringArgumentType.getString(c,INSTANCE)));
                    c.getSource().sendFeedback(Text.literal(ok?"Claude emission stopped; detached particles drain":"Claude instance not found"));return ok?1:0;
                } catch(IllegalArgumentException e){throw INVALID.create(e.getMessage());}
            })));
            root.then(literal("충돌").then(argument(INSTANCE,StringArgumentType.word()).then(effectArgument(api)
                .executes(c -> impact(c,claude,null))
                .then(argument("법선x",DoubleArgumentType.doubleArg(-1,1))
                .then(argument("법선y",DoubleArgumentType.doubleArg(-1,1))
                .then(argument("법선z",DoubleArgumentType.doubleArg(-1,1)).executes(c -> impact(c,claude,new Vec3d(
                    DoubleArgumentType.getDouble(c,"법선x"),DoubleArgumentType.getDouble(c,"법선y"),DoubleArgumentType.getDouble(c,"법선z"))))))))));
            root.then(literal("상태").executes(context -> {
                context.getSource().sendFeedback(Text.literal(api.status() + " / worldFrames=" + renderer.worldFrames() + " / active=" + api.activeInstances().size() + " / " + renderer.prewarmStatus()));
                context.getSource().sendFeedback(Text.literal("[VFX 진단] "+renderer.readinessDiagnostic()+" / "+dev.portablevfx.client.network.VfxNetworking.diagnostic()));return 1;
            }));
            root.then(literal("목록").executes(context -> {
                context.getSource().sendFeedback(Text.literal("Effects (" + api.list().size() + "): " +
                        String.join(", ", api.list().stream().map(Object::toString).sorted().toList())));
                return api.list().size();
            }));
            root.then(literal("활성").executes(context -> {
                context.getSource().sendFeedback(Text.literal("Active (" + api.activeInstances().size() + "): " +
                        String.join(", ", api.activeInstances().stream().map(Object::toString).sorted().toList())));
                return api.activeInstances().size();
            }));
            root.then(literal("전부정지").executes(context -> {
                api.clear(); context.getSource().sendFeedback(Text.literal("PortableVFX: cleared")); return 1;
            }));
            root.then(literal("새로고침").executes(context -> {
                api.clear();
                context.getSource().getClient().reloadResources();
                context.getSource().sendFeedback(Text.literal("PortableVFX: resource reload requested (same resources as F3+T)"));
                return 1;
            }));
            root.then(literal("정지").then(argument(INSTANCE, StringArgumentType.word())
                    .suggests((context, builder) -> CommandSource.suggestMatching(api.activeInstances().stream().map(Object::toString), builder))
                    .executes(context -> {
                        try {
                            boolean stopped = api.stop(UUID.fromString(StringArgumentType.getString(context, INSTANCE)));
                            context.getSource().sendFeedback(Text.literal(stopped ? "PortableVFX: stopped" : "PortableVFX: instance not found"));
                            return stopped ? 1 : 0;
                        } catch (IllegalArgumentException ex) { throw INVALID.create("invalid UUID"); }
                    })));
            root.then(literal("재생").then(effectArgument(api).executes(context -> play(context, api, library, 0))
                    .then(options(api, library, 0))));
            root.then(literal("증명재생").then(effectArgument(api).executes(c -> play(c, api, library, 3)).then(options(api, library, 3))));
            root.then(literal("여기재생").then(effectArgument(api).executes(c -> play(c, api, library, 2)).then(options(api, library, 2))));
            root.then(literal("좌표재생").then(effectArgument(api)
                    .then(argument("x", DoubleArgumentType.doubleArg(-VfxProtocol.MAX_POSITION, VfxProtocol.MAX_POSITION))
                    .then(argument("y", DoubleArgumentType.doubleArg(-VfxProtocol.MAX_POSITION, VfxProtocol.MAX_POSITION))
                    .then(argument("z", DoubleArgumentType.doubleArg(-VfxProtocol.MAX_POSITION, VfxProtocol.MAX_POSITION))
                            .executes(context -> play(context, api, library, 1)).then(options(api, library, 1)))))));
            return root;
    }

    private static int launch(CommandContext<FabricClientCommandSource> c,dev.portablevfx.client.claude.ClaudePreviewController claude,double speed)throws CommandSyntaxException {
        UUID id=claude.launch(c.getArgument(EFFECT,Identifier.class),speed).orElseThrow(()->INVALID.create("Claude launch not accepted"));
        c.getSource().sendFeedback(Text.literal("Claude cosmetic flight: "+id+" (use impact, finish, or stop)"));return 1;
    }
    private static int impact(CommandContext<FabricClientCommandSource> c,dev.portablevfx.client.claude.ClaudePreviewController claude,Vec3d normal)throws CommandSyntaxException {
        try {
            UUID id=claude.impact(UUID.fromString(StringArgumentType.getString(c,INSTANCE)),c.getArgument(EFFECT,Identifier.class),normal)
                .orElseThrow(()->INVALID.create("Active launched Claude instance and valid impact ID required"));
            c.getSource().sendFeedback(Text.literal("Claude impact: "+id));return 1;
        } catch(IllegalArgumentException e){throw INVALID.create(e.getMessage());}
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Identifier> effectArgument(PortableVfxApi api) {
        return argument(EFFECT, IdentifierArgumentType.identifier()).suggests((context, builder) ->
                CommandSource.suggestMatching(api.list().stream().map(Object::toString), builder));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Float> options(PortableVfxApi api, EffectLibrary library, int placement) {
        return argument("크기", FloatArgumentType.floatArg(.01f, VfxProtocol.MAX_SCALE))
                .executes(c -> play(c, api, library, placement))
                .then(argument("틱", IntegerArgumentType.integer(1, VfxProtocol.MAX_DURATION_TICKS))
                        .executes(c -> play(c, api, library, placement))
                .then(argument("요", FloatArgumentType.floatArg(-360, 360)).executes(c -> play(c, api, library, placement))
                .then(argument("피치", FloatArgumentType.floatArg(-360, 360)).executes(c -> play(c, api, library, placement))
                .then(argument("롤", FloatArgumentType.floatArg(-360, 360)).executes(c -> play(c, api, library, placement))))));
    }

    private static int play(CommandContext<FabricClientCommandSource> context, PortableVfxApi api,
            EffectLibrary library, int placement) throws CommandSyntaxException {
        try {
            var source = context.getSource();
            Identifier id = context.getArgument(EFFECT, Identifier.class);
            var effect = library.get(id);
            if (effect == null) throw INVALID.create("unknown effect ID: " + id);
            Vec3d position;
            if (placement == 1) position = new Vec3d(DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"), DoubleArgumentType.getDouble(context, "z"));
            else if (placement == 2) position = source.getPlayer().getPos().add(0, .02, 0);
            else {
                Vec3d look = source.getPlayer().getRotationVec(1);
                position = source.getPlayer().getPos().add(look.x * 2, 1, look.z * 2);
            }
            UUID instance = UUID.randomUUID();
            String rgbString = optional(context, "색", String.class, "ffffff");
            if (!rgbString.matches("[a-fA-F0-9]{6}")) throw INVALID.create("RGB must be six hex digits RRGGBB (without #)");
            int rgb = Integer.parseInt(rgbString, 16);
            PlayEffect request = new PlayEffect(instance, id.toString(), source.getWorld().getRegistryKey().getValue().toString(),
                    position.x, position.y, position.z,
                    optional(context, "요", Float.class, 0f), optional(context, "피치", Float.class, 0f),
                    optional(context, "롤", Float.class, 0f), optional(context, "크기", Float.class, 1f), rgb,
                    optional(context, "불투명도", Float.class, 1f), optional(context, "틱", Integer.class, effect.durationTicks()));
            if (placement == 3) request = new PlayEffect(instance, request.effectId(), request.dimensionId(),
                    position.x, position.y, position.z, request.yaw(), request.pitch(), request.roll(), request.scale(),
                    request.rgb(), request.opacity(), request.durationTicks(), null, 0, -1,
                    dev.portablevfx.protocol.EffectAnchor.WORLD, 0, 0, 0);
            if (!api.play(request)) throw INVALID.create("not accepted: unknown ID, unsupported tint/opacity/Claude scale (must be 1), wrong world, >256 blocks, or capacity");
            source.sendFeedback(Text.literal("PortableVFX accepted (check /" + ROOT + " 상태): " + id + " → " + instance));
            return 1;
        } catch (IllegalArgumentException ex) { throw INVALID.create(ex.getMessage()); }
    }

    private static <T> T optional(CommandContext<?> context, String name, Class<T> type, T fallback) {
        try { return context.getArgument(name, type); }
        catch (IllegalArgumentException missing) { return fallback; }
    }
}
