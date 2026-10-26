package kr.chacademi.npcmodel.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import kr.chacademi.npcmodel.NpcModelClient;
import kr.chacademi.npcmodel.core.ModelRegistry;
import kr.chacademi.npcmodel.core.Proportion;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * 내 화면에서만 NPC 모델 비율·키를 바꿔 보는 명령 (서버·다른 사람에게는 영향 없음). 마음에 들면 '저장'으로 models.json 에 기록.
 *
 * <pre>
 * /npc모델 정보 [모델]
 * /npc모델 비율 &lt;모델&gt; &lt;다리|몸통|팔&gt; &lt;0.5~1.5&gt;
 * /npc모델 키 &lt;모델&gt; &lt;블록 0.5~25 | 0 = models.json scale 사용&gt;
 * /npc모델 저장 &lt;모델&gt;      지금 값을 models.json 에 기록 (런처가 다시 덮어쓰면 사라지니 결과를 배포본에도 반영)
 * /npc모델 되돌리기          models.json 다시 읽기
 * </pre>
 */
public final class TuneCommands {
    private TuneCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("npc모델")
                        .then(literal("정보").executes(ctx -> info(ctx, null))
                                .then(argument("모델", StringArgumentType.word()).suggests(TuneCommands::models)
                                        .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "모델")))))
                        .then(literal("비율").then(argument("모델", StringArgumentType.word()).suggests(TuneCommands::models)
                                .then(part("다리")).then(part("몸통")).then(part("팔"))))
                        .then(literal("키").then(argument("모델", StringArgumentType.word()).suggests(TuneCommands::models)
                                .then(argument("블록", FloatArgumentType.floatArg(0, 25)).executes(TuneCommands::height))))
                        .then(literal("저장").then(argument("모델", StringArgumentType.word()).suggests(TuneCommands::models)
                                .executes(TuneCommands::save)))
                        .then(literal("되돌리기").executes(ctx -> {
                            NpcModelClient.models().reloadRegistry();
                            NpcModelClient.bindings().clearInstances();
                            ok(ctx, "models.json 을 다시 읽었어요");
                            return 1;
                        }))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> part(String name) {
        return literal(name).then(argument("배율", FloatArgumentType.floatArg(0.5f, 1.5f)).executes(ctx -> proportion(ctx, name)));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> models(
            CommandContext<FabricClientCommandSource> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(NpcModelClient.models().registry().models.keySet(), b);
    }

    private static ModelRegistry.Entry entry(CommandContext<FabricClientCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "모델");
        ModelRegistry.Entry e = NpcModelClient.models().registry().models.get(id);
        if (e == null) {
            ctx.getSource().sendError(Component.literal("'" + id + "' 모델이 models.json 에 없어요"));
        }
        return e;
    }

    private static int proportion(CommandContext<FabricClientCommandSource> ctx, String part) {
        ModelRegistry.Entry e = entry(ctx);
        if (e == null) {
            return 0;
        }
        float v = FloatArgumentType.getFloat(ctx, "배율");
        Proportion.Factors f = e.proportion();
        Proportion.Factors nf = switch (part) {
            case "다리" -> new Proportion.Factors(v, f.torso(), f.arms());
            case "몸통" -> new Proportion.Factors(f.legs(), v, f.arms());
            default -> new Proportion.Factors(f.legs(), f.torso(), v);
        };
        apply(ctx, e, nf, e.height());
        return 1;
    }

    private static int height(CommandContext<FabricClientCommandSource> ctx) {
        ModelRegistry.Entry e = entry(ctx);
        if (e == null) {
            return 0;
        }
        float h = FloatArgumentType.getFloat(ctx, "블록");
        if (h != 0 && h < 0.5f) {
            ctx.getSource().sendError(Component.literal("키는 0.5~25 블록이에요 (0 = models.json 의 scale 사용)"));
            return 0;
        }
        apply(ctx, e, e.proportion(), h);
        return 1;
    }

    private static void apply(CommandContext<FabricClientCommandSource> ctx, ModelRegistry.Entry e, Proportion.Factors f, float h) {
        NpcModelClient.models().override(e.id(), f, h);
        NpcModelClient.bindings().reloadModel(e.id());
        ok(ctx, String.format("%s → 다리 %.2f · 몸통 %.2f · 팔 %.2f · 키 %s (내 화면만, 저장: /npc모델 저장 %s)",
                e.id(), f.legs(), f.torso(), f.arms(), h > 0 ? String.format("%.2f", h) : "scale " + e.scale(), e.id()));
    }

    private static int save(CommandContext<FabricClientCommandSource> ctx) {
        ModelRegistry.Entry e = entry(ctx);
        if (e == null) {
            return 0;
        }
        try {
            NpcModelClient.models().registry().save(e);
            ok(ctx, e.id() + " 값을 models.json 에 저장했어요. 다른 사람에게도 적용하려면 이 models.json 을 런처 배포본에 넣어 주세요");
        } catch (Exception ex) {
            NpcModelClient.LOG.warn("[npc-models] models.json 저장 실패", ex);
            ctx.getSource().sendError(Component.literal("저장 실패: " + ex.getMessage()));
        }
        return 1;
    }

    private static int info(CommandContext<FabricClientCommandSource> ctx, String id) {
        for (ModelRegistry.Entry e : NpcModelClient.models().registry().models.values()) {
            if (id != null && !id.equals(e.id())) {
                continue;
            }
            Proportion.Factors f = e.proportion();
            ok(ctx, String.format("%s: 다리 %.2f · 몸통 %.2f · 팔 %.2f · 키 %s", e.id(), f.legs(), f.torso(), f.arms(),
                    e.height() > 0 ? String.format("%.2f", e.height()) : "scale " + e.scale()));
        }
        return 1;
    }

    private static void ok(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg));
    }
}
