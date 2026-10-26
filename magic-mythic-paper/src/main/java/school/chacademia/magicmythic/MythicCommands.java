package school.chacademia.magicmythic;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * 마법 미몹 명령 (명령어 허브 갈래).
 * <pre>
 * /유저관리 &lt;닉네임&gt; 마법슬롯 [마법…|비우기]
 * /테스트 마법 시전 &lt;마법&gt;
 * /테스트 몹 예고 &lt;종류&gt; [초]
 * /테스트 몹 예고효과 &lt;효과&gt; &lt;모양&gt; &lt;크기&gt; [각도·폭] [틱] [선행] [배율]
 * /테스트 몹 상태이상 &lt;종류|해제|목록&gt; [초] [대상] [세기]
 * /컨텐츠관리 연습장 허수아비 &lt;설치|제거|목록&gt;
 * /서버관리 새로고침 몹마법 · /서버관리 상태 (몹마법)
 * </pre>
 */
final class MythicCommands {
    private final MagicMythicPlugin plugin;

    MythicCommands(MagicMythicPlugin plugin) { this.plugin = plugin; }

    void register() {
        ChacaLink.branch(plugin, ChacaLink.유저관리, slots(), "마법 슬롯 장착 보기·강제 지정 (패시브 시험용)", "마법슬롯 ");
        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("마법").then(cast()),
            "마나·쿨타임 없이 마법의 미몹 스킬만 실행", "마법 시전 ");
        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("몹").then(telegraph()).then(telegraphFx()).then(status()),
            "몹 예고 장판·예고 효과·상태이상 시험", "몹 예고 전부");
        if (plugin.dummies() != null)
            ChacaLink.branch(plugin, ChacaLink.컨텐츠관리, plugin.dummies().command(), "마법 연습장 훈련 허수아비 설치(서 있는 곳)·제거(3칸 안)·목록", "연습장 허수아비 설치");
        ChacaLink.reload(plugin, "몹마법", "config.yml (대상·원소 반응·상태이상·예고 장판). 스킬 YAML은 /mm reload", plugin::reloadAll);
        ChacaLink.status(plugin, "몹마법", plugin::statusLine);
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 마법슬롯

    private LiteralArgumentBuilder<CommandSourceStack> slots() {
        return Commands.literal("마법슬롯")
            .executes(ctx -> {
                Player p = ChacaLink.targetOnline(ctx);
                var s = plugin.slots();
                ChacaLink.lines(ctx.getSource().getSender(), List.of(
                    "── " + ChacaLink.name(p.getUniqueId()) + " 마법 슬롯 ──",
                    " 장착: " + names(s.list(p)),
                    " 모드: " + (s.mode().equals("learned") ? "배운 마법 전체" : "클라이언트 보고") + " · 보고 " + (s.known(p) ? "받음" : "없음")));
                return 1;
            })
            .then(Commands.literal("비우기").executes(ctx -> {
                Player p = ChacaLink.targetOnline(ctx);
                plugin.slots().force(p, List.of());
                ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님의 강제 슬롯을 비웠어요");
                return 1;
            }))
            .then(Commands.argument("마법", StringArgumentType.greedyString())
                .suggests((ctx, b) -> {
                    String all = b.getRemaining();
                    int cut = all.lastIndexOf(' ') + 1;
                    String typed = all.substring(cut);
                    var sub = b.createOffset(b.getStart() + cut);
                    for (String s : plugin.spellNames().suggestions()) if (s.startsWith(typed)) sub.suggest(s);
                    return sub.buildFuture();
                })
                .executes(ctx -> {
                    Player p = ChacaLink.targetOnline(ctx);
                    var ids = new ArrayList<String>();
                    for (String part : StringArgumentType.getString(ctx, "마법").trim().split("[\\s,]+")) {
                        if (part.isEmpty()) continue;
                        String id = plugin.spellNames().resolve(part);
                        if (!id.matches("[a-z0-9_-]{1,64}")) throw ChacaLink.error("모르는 마법이에요: " + part);
                        if (!ids.contains(id)) ids.add(id);
                    }
                    if (ids.size() > SpellSlots.LIMIT) throw ChacaLink.error("마법 슬롯은 " + SpellSlots.LIMIT + "칸까지예요");
                    plugin.slots().force(p, ids);
                    ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님 마법 슬롯 강제 지정: " + names(plugin.slots().list(p)));
                    return 1;
                }));
    }

    private String names(List<String> ids) {
        if (ids.isEmpty()) return "(없음)";
        var out = new ArrayList<String>();
        for (String id : ids) out.add(plugin.spellNames().label(id));
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ /테스트 마법 시전

    private LiteralArgumentBuilder<CommandSourceStack> cast() {
        return Commands.literal("시전")
            .then(Commands.argument("마법", ChacaLink.word(() -> plugin.spellNames().suggestions()))
                .executes(ctx -> {
                    ChacaLink.ok(ctx.getSource().getSender(), TestCast.cast(plugin, self(ctx), ctx.getArgument("마법", String.class)));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ /테스트 몹 예고 · 예고효과 · 상태이상

    private LiteralArgumentBuilder<CommandSourceStack> telegraph() {
        var root = Commands.literal("예고");
        for (String kind : TelegraphCommand.KINDS) {
            root.then(Commands.literal(kind)
                .executes(ctx -> { ChacaLink.ok(ctx.getSource().getSender(), TelegraphCommand.run(plugin, self(ctx), kind, null)); return 1; })
                .then(Commands.argument("초", DoubleArgumentType.doubleArg(0.1, 30))
                    .executes(ctx -> {
                        ChacaLink.ok(ctx.getSource().getSender(), TelegraphCommand.run(plugin, self(ctx), kind, DoubleArgumentType.getDouble(ctx, "초")));
                        return 1;
                    })));
        }
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> telegraphFx() {
        var effect = Commands.argument("효과", ChacaLink.word(() -> List.of("earthquake/quake_club", "dragonbreath/breath_nether", "sealcircle/seal", "frostlaser/laser")));
        for (String shape : TelegraphCommand.SHAPES) {
            var size = Commands.argument("크기", FloatArgumentType.floatArg(0.1f, 64f)).executes(ctx -> fx(ctx, shape));
            var extra = Commands.argument("각도·폭", FloatArgumentType.floatArg(0f, 360f)).executes(ctx -> fx(ctx, shape));
            var ticks = Commands.argument("틱", IntegerArgumentType.integer(1, 600)).executes(ctx -> fx(ctx, shape));
            var lead = Commands.argument("선행", IntegerArgumentType.integer(0, 600)).executes(ctx -> fx(ctx, shape));
            var scale = Commands.argument("배율", FloatArgumentType.floatArg(0.25f, 4f)).executes(ctx -> fx(ctx, shape));
            effect.then(Commands.literal(shape).then(size.then(extra.then(ticks.then(lead.then(scale))))));
        }
        return Commands.literal("예고효과").then(effect);
    }

    private int fx(CommandContext<CommandSourceStack> ctx, String shape) throws CommandSyntaxException {
        String result = TelegraphCommand.fx(plugin, self(ctx), ctx.getArgument("효과", String.class), shape,
            FloatArgumentType.getFloat(ctx, "크기"), opt(ctx, "각도·폭", Float.class, 0f), opt(ctx, "틱", Integer.class, 24),
            opt(ctx, "선행", Integer.class, 2), opt(ctx, "배율", Float.class, 1f));
        ChacaLink.ok(ctx.getSource().getSender(), result);
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> status() {
        var root = Commands.literal("상태이상");
        root.then(Commands.literal("목록").executes(ctx -> { ChacaLink.lines(ctx.getSource().getSender(), StatusCommand.list()); return 1; }));
        root.then(Commands.literal("해제")
            .executes(ctx -> { ChacaLink.ok(ctx.getSource().getSender(), StatusCommand.clear(plugin, ctx.getSource().getSender(), "바라봄")); return 1; })
            .then(Commands.argument("대상", ChacaLink.word(StatusCommand::targets))
                .executes(ctx -> {
                    ChacaLink.ok(ctx.getSource().getSender(), StatusCommand.clear(plugin, ctx.getSource().getSender(), ctx.getArgument("대상", String.class)));
                    return 1;
                })));
        root.then(Commands.argument("종류", ChacaLink.word(StatusCommand::labels))
            .executes(ctx -> applyStatus(ctx))
            .then(Commands.argument("초", DoubleArgumentType.doubleArg(0.05, 600)).executes(this::applyStatus)
                .then(Commands.argument("대상", ChacaLink.word(StatusCommand::targets)).executes(this::applyStatus)
                    .then(Commands.argument("세기", DoubleArgumentType.doubleArg(0, 100)).executes(this::applyStatus)))));
        return root;
    }

    private int applyStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var sender = ctx.getSource().getSender();
        String result = StatusCommand.apply(plugin, sender, ctx.getArgument("종류", String.class), opt(ctx, "초", Double.class, 5d),
            opt(ctx, "대상", String.class, "바라봄"), opt(ctx, "세기", Double.class, 0d));
        ChacaLink.ok(sender, result);
        return 1;
    }

    private static <T> T opt(CommandContext<CommandSourceStack> ctx, String name, Class<T> type, T fallback) {
        try { return ctx.getArgument(name, type); } catch (IllegalArgumentException missing) { return fallback; }
    }
}
