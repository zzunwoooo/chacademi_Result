package school.chacademia.wildlife;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 * 야생몹 명령 (명령어 허브 갈래). 예전 /야생관리.
 * <pre>
 * /월드관리 야생몹 &lt;상태|진단 [닉네임]|확률 [닉네임]|확인 &lt;몹&gt;|구조물&gt;
 * /테스트 몹 작물몹 &lt;작물&gt; [변색종]
 * /서버관리 새로고침 야생 · /서버관리 상태 (야생몹)
 * </pre>
 */
final class WildlifeCommands {
    private final WildlifePlugin plugin;
    private final CropAwakening crops;

    WildlifeCommands(WildlifePlugin plugin, CropAwakening crops) { this.plugin = plugin; this.crops = crops; }

    void register() {
        ChacaLink.branch(plugin, ChacaLink.월드관리, Commands.literal("야생몹")
            .then(Commands.literal("상태").executes(ctx -> {
                var out = new ArrayList<String>();
                out.add("── 야생몹 상태 ──");
                out.add(" " + plugin.statusLine());
                out.addAll(plugin.globalDiagnostics());
                ChacaLink.lines(ctx.getSource().getSender(), out);
                return 1;
            }))
            .then(Commands.literal("진단")
                .executes(ctx -> diagnose(ctx, self(ctx)))
                .then(Commands.argument(ChacaLink.TARGET, ChacaLink.player())
                    .executes(ctx -> diagnose(ctx, ChacaLink.resolveOnline(ctx, ChacaLink.TARGET)))))
            .then(Commands.literal("확률")
                .executes(ctx -> chance(ctx, self(ctx)))
                .then(Commands.argument(ChacaLink.TARGET, ChacaLink.player())
                    .executes(ctx -> chance(ctx, ChacaLink.resolveOnline(ctx, ChacaLink.TARGET)))))
            .then(Commands.literal("확인").then(Commands.argument("몹", ChacaLink.word(plugin::ids)).executes(ctx -> {
                Player p = self(ctx);
                String id = ctx.getArgument("몹", String.class);
                if (!plugin.ids().contains(id)) throw ChacaLink.error("habitats.yml에 없는 몹이에요: " + id);
                String result = plugin.check(id, p.getLocation(), false);
                ChacaLink.lines(p, java.util.List.of("── " + id + " 출현 조건 (내 위치) ──",
                    " 결과: " + (result.equals("OK") ? "나올 수 있음" : result),
                    " 바이옴: " + p.getWorld().getBiome(p.getLocation()).getKey()));
                return 1;
            })))
            .then(Commands.literal("구조물").executes(ctx -> {
                Player p = self(ctx);
                var sites = plugin.structureIndex().nearby(p.getLocation(), Set.of(), 96);
                var out = new ArrayList<String>();
                out.add("── 근처 구조물 (96칸, 로드된 청크) " + sites.size() + "개 ──");
                for (var s : sites) out.add(" " + s.id() + "  " + (int) s.box().getCenterX() + ", " + (int) s.box().getMinY() + ", " + (int) s.box().getCenterZ());
                ChacaLink.lines(p, out);
                return 1;
            })),
            "야생 몹 스폰 상태·진단·출현 조건 확인", "야생몹 상태");

        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("몹").then(Commands.literal("작물몹")
            .then(Commands.argument("작물", ChacaLink.word(crops::ids))
                .executes(ctx -> crop(ctx, false))
                .then(Commands.literal("변색종").executes(ctx -> crop(ctx, true))))),
            "살아나는 작물 몹을 내 앞에 소환", "몹 작물몹 ");

        ChacaLink.reload(plugin, "야생", "config.yml·habitats.yml·biome-compatibility.yml·ranged-skills.yml", plugin::reloadAll);
        ChacaLink.status(plugin, "야생몹", plugin::statusLine);
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요 (콘솔은 닉네임을 적어 주세요)");
    }

    private int chance(CommandContext<CommandSourceStack> ctx, Player target) {
        var out = new ArrayList<String>();
        out.add("── 야생몹 스폰 확률: " + ChacaLink.name(target.getUniqueId()) + " 위치 ──");
        out.addAll(plugin.chanceAt(target));
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int diagnose(CommandContext<CommandSourceStack> ctx, Player target) {
        var out = new ArrayList<String>();
        out.add("── 야생몹 진단: " + ChacaLink.name(target.getUniqueId()) + " ──");
        out.addAll(plugin.diagnose(target));
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int crop(CommandContext<CommandSourceStack> ctx, boolean shiny) throws CommandSyntaxException {
        Player p = self(ctx);
        String id = ctx.getArgument("작물", String.class);
        if (!crops.ids().contains(id)) throw ChacaLink.error("등록되지 않은 작물이에요: " + id);
        var direction = p.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1.0E-4) direction.setZ(1);
        var at = p.getLocation().add(direction.normalize().multiply(3));
        at.setY(p.getLocation().getBlockY()); at.setX(at.getBlockX() + .5); at.setZ(at.getBlockZ() + .5);
        String r = crops.spawn(p, id, shiny, at);
        if (r.startsWith("미몹 없음")) throw new IllegalStateException("작물 몹 소환 실패: " + r);
        if (!r.equals("OK")) throw ChacaLink.error(r.equals("동시 작물 개체 제한") ? "동시에 소환할 수 있는 작물 몹 수를 넘었어요" : "작물 몹을 소환하지 못했어요");
        ChacaLink.ok(p, "작물 몹 소환: " + id + (shiny ? " (변색종)" : ""));
        return 1;
    }
}
