package school.chacademia.gimmick;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import org.bukkit.entity.Player;

/**
 * /던전관리 … (명령어 허브 갈래). 2026-10-24 owner: 소환하고 막대로 고치는 것만.
 * <pre>
 * 소환 &lt;모델&gt; · 도구 · 목록 [검색] · 모델 [검색] · 초기화 [반경]
 * </pre>
 * Wiring needs no command: blocks that touch (or are joined by conduits) are connected (Signals).
 */
final class GimmickCommand {
    private final GimmickPlugin plugin;
    GimmickCommand(GimmickPlugin plugin) { this.plugin = plugin; }

    void register() {
        var root = ChacaLink.던전관리;
        ChacaLink.branch(plugin, root, spawn(), "내가 서 있는 칸에 기믹 설치 (보는 방향)", "소환 ");
        ChacaLink.branch(plugin, root, Commands.literal("도구").executes(this::tool), "기믹 막대 (정보·회전·크기·이동·높이·정답·삭제)", "도구");
        ChacaLink.branch(plugin, root, list(), "설치된 기믹 목록", "목록");
        ChacaLink.branch(plugin, root, models(), "모델 목록", "모델");
        ChacaLink.branch(plugin, root, reset(), "주변 기믹을 처음 상태로 (문 닫힘·레버 내림·얼음 다시 얼림·발판 제자리)", "초기화");
        ChacaLink.reload(plugin, "던전", "config·models.yml 다시 읽기 (기믹 모델 다시 그림)", () -> {
            var warnings = plugin.reloadAll();
            if (!warnings.isEmpty() && warnings.get(0).startsWith("Reload rejected")) throw new IllegalStateException(warnings.get(0));
        });
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }
    private static <T> T opt(CommandContext<CommandSourceStack> ctx, String name, Class<T> type, T fallback) {
        try { return ctx.getArgument(name, type); } catch (IllegalArgumentException missing) { return fallback; }
    }

    private LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return Commands.literal("소환").then(Commands.argument("모델", ChacaLink.word(() -> plugin.catalog().ids())).executes(ctx -> {
            Player p = self(ctx);
            String model = ctx.getArgument("모델", String.class);
            var refused = new String[1];
            Gimmick g = plugin.placer().place(p, model, p.getLocation().getBlock(), Gimmick.facingOf(p.getLocation().getYaw()), r -> refused[0] = r);
            if (g == null) throw ChacaLink.error(refused[0] == null ? "설치하지 못했어요" : refused[0]);
            ChacaLink.ok(p, g.id + " 설치 (" + plugin.catalog().get(model).name() + ")  블록 " + g.bx + ", " + g.by + ", " + g.bz
                + (g.live() ? "" : " · 모델을 그리지 못했어요 (콘솔 확인)"));
            return 1;
        }));
    }

    private int tool(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        p.getInventory().addItem(plugin.tool().create());
        ChacaLink.ok(p, "기믹 막대를 받았어요. 우클릭 +, 좌클릭 -, 웅크리고 클릭하면 모드 변경");
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> list() {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            String q = opt(ctx, "검색", String.class, null);
            var list = plugin.world().all().stream().filter(g -> !g.attached() && (q == null || g.model.contains(q) || g.id.equals(q)
                || plugin.catalog().get(g.model).name().contains(q))).toList();
            var out = new ArrayList<String>();
            out.add("── 기믹 " + list.size() + "개" + (q == null ? "" : " (" + q + ")") + " ──");
            for (Gimmick g : list.subList(0, Math.min(40, list.size())))
                out.add(String.format(Locale.ROOT, " %s %s %s %d %d %d %s", g.id, plugin.catalog().get(g.model).name(), g.world, g.bx, g.by, g.bz,
                    plugin.signals().on(g) ? "켜짐" : "꺼짐"));
            if (list.size() > 40) out.add(" … 외 " + (list.size() - 40) + "개 (검색어로 좁혀 보세요)");
            ChacaLink.lines(ctx.getSource().getSender(), out);
            return 1;
        };
        return Commands.literal("목록").executes(run).then(Commands.argument("검색", ChacaLink.word(() -> plugin.catalog().ids())).executes(run));
    }

    private LiteralArgumentBuilder<CommandSourceStack> models() {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            String q = opt(ctx, "검색", String.class, "");
            var ids = plugin.catalog().ids().stream().filter(i -> i.contains(q) || plugin.catalog().get(i).name().contains(q)).toList();
            var out = new ArrayList<String>();
            out.add("── 모델 " + ids.size() + "개" + (q.isEmpty() ? "" : " (" + q + ")") + " ──");
            for (String i : ids) { var info = plugin.catalog().get(i); out.add(" " + i + "  " + info.name() + "  " + Signals.role(info.kind()) + "  " + info.footW() + "x" + info.footD() + "칸"); }
            ChacaLink.lines(ctx.getSource().getSender(), out);
            return 1;
        };
        return Commands.literal("모델").executes(run).then(Commands.argument("검색", ChacaLink.word(() -> plugin.catalog().ids())).executes(run));
    }

    private LiteralArgumentBuilder<CommandSourceStack> reset() {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            Player p = self(ctx);
            double r = opt(ctx, "반경", Double.class, 32d);
            int n = 0;
            for (Gimmick g : plugin.world().near(p.getLocation(), r)) {
                if (g.attached() || !plugin.protection().pair(p, g.location())) continue;
                plugin.signals().reset(g);
                n++;
            }
            ChacaLink.ok(p, "반경 " + (int) r + "칸 기믹 " + n + "개를 처음 상태로 되돌렸어요");
            return 1;
        };
        return Commands.literal("초기화").executes(run).then(Commands.argument("반경", DoubleArgumentType.doubleArg(1, 256)).executes(run));
    }
}
