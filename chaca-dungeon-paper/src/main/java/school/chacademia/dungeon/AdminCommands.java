package school.chacademia.dungeon;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /던전관리 … 인게임 설정 (명령어 허브 갈래, 기믹 플러그인의 /던전관리 와 같은 뿌리).
 * 먼저 "/던전관리 선택 lich_lab 1" 로 칸을 고르고, 그 칸에서 서서 위치를 찍는다. 칸마다 따로 설정한다.
 * <pre>
 * 선택 &lt;던전&gt; &lt;칸&gt; · 시작점 · 구역 1|2 · 방 &lt;이름&gt; 1|2|가두기|안가두기|지우기 · 방몹 &lt;방&gt; &lt;몹&gt; [수] · 방몹비우기 &lt;방&gt;
 * 보스 &lt;이름&gt; &lt;몹&gt; · 지점 &lt;이름&gt; · 기믹 &lt;이름&gt; · 포탈 [반경] · 출구 · 보상 첫클리어|반복 추가|목록|비우기
 * 설정확인 · 현황 · 칸초기화 &lt;던전&gt; &lt;칸&gt; · 강제퇴장 &lt;닉네임&gt;
 * </pre>
 */
final class AdminCommands {
    private final DungeonPlugin plugin;
    private record Sel(String dungeon, int slot) {}
    private final Map<UUID, Sel> selected = new HashMap<>();

    AdminCommands(DungeonPlugin plugin) { this.plugin = plugin; }

    void register() {
        String root = ChacaLink.던전관리;
        ChacaLink.branch(plugin, root, select(), "설정할 던전 칸 고르기", "선택 lich_lab 1");
        ChacaLink.branch(plugin, root, Commands.literal("설정도구").executes(ctx -> { Player p = self(ctx); p.getInventory().addItem(plugin.editor().tool()); ChacaLink.ok(p, "던전 설정 도구를 받았어요. 허공 우클릭 = 설정 창"); return 1; }), "던전 설정 도구 (좌·우클릭 구역, 허공 우클릭 설정 창)", "설정도구");
        ChacaLink.branch(plugin, root, rewards(), "보상(기본·첫클리어·확률): 손에 든 아이템 추가 / 목록 / 빼기 / 비우기", "보상 확률 추가 12.5");
        ChacaLink.branch(plugin, root, Commands.literal("설정확인").executes(this::check), "고른 던전의 칸별 빠진 설정", "설정확인");
        ChacaLink.branch(plugin, root, Commands.literal("현황").executes(ctx -> { ChacaLink.lines(ctx.getSource().getSender(), plugin.service().status()); return 1; }), "칸 사용·대기 현황", "현황");
        ChacaLink.branch(plugin, root, forceReset(), "칸 강제 정리 (진행 중이면 실패 처리)", "칸초기화 lich_lab 1");
        ChacaLink.branch(plugin, root, Commands.literal("강제퇴장").then(Commands.argument(ChacaLink.TARGET, ChacaLink.player()).executes(ctx -> {
            UUID id = ChacaLink.target(ctx);
            if (!plugin.service().kick(id)) throw ChacaLink.error("던전 안에 있지 않아요");
            ChacaLink.ok(ctx.getSource().getSender(), "던전에서 내보냈어요 (실패 처리)");
            return 1;
        })), "던전에서 내보내기", "강제퇴장 닉네임");
        ChacaLink.reload(plugin, "던전입장", "config·dungeons.yml 다시 읽기", plugin::reload);
    }

    // ------------------------------------------------------------ 도우미

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    private Defs.Dungeon dungeon(Player p) throws CommandSyntaxException {
        Sel s = selected.get(p.getUniqueId());
        var d = s == null ? null : plugin.service().dungeons.get(s.dungeon());
        if (d == null) throw ChacaLink.error("먼저 /던전관리 선택 <던전> <칸> 으로 골라 주세요");
        return d;
    }

    private Defs.Slot slot(Player p, Defs.Dungeon d) throws CommandSyntaxException {
        Sel s = selected.get(p.getUniqueId());
        if (!p.getWorld().getName().equals(d.world)) throw ChacaLink.error(d.world + " 월드 안에서 설정해 주세요");
        return d.slot(s.slot());
    }

    private String tag(Player p) { Sel s = selected.get(p.getUniqueId()); return s == null ? "" : "[" + s.dungeon() + " " + s.slot() + "번] "; }

    private void saved(Player p, String message) {
        plugin.saveDungeons();
        ChacaLink.ok(p, tag(p) + message);
    }

    private interface SlotEdit { String apply(Player p, Defs.Dungeon d, Defs.Slot s) throws CommandSyntaxException; }
    private interface NamedEdit { String apply(Player p, Defs.Dungeon d, Defs.Slot s, String name) throws CommandSyntaxException; }
    private interface DungeonEdit { String apply(Player p, Defs.Dungeon d) throws CommandSyntaxException; }

    private static int[] feet(Player p) {
        var b = p.getLocation().getBlock();
        return new int[]{b.getX(), b.getY(), b.getZ()};
    }

    // ------------------------------------------------------------ 명령

    private LiteralArgumentBuilder<CommandSourceStack> select() {
        return Commands.literal("선택").then(Commands.argument("던전", ChacaLink.word(() -> plugin.service().dungeons.keySet()))
                .then(Commands.argument("칸", IntegerArgumentType.integer(1, 16)).executes(ctx -> {
                    Player p = self(ctx);
                    String id = ctx.getArgument("던전", String.class);
                    int no = ctx.getArgument("칸", Integer.class);
                    var d = plugin.service().dungeons.get(id);
                    if (d == null) throw ChacaLink.error("그런 던전이 없어요: " + id);
                    if (no > d.instances) throw ChacaLink.error("이 던전은 " + d.instances + "칸이에요");
                    selected.put(p.getUniqueId(), new Sel(id, no));
                    ChacaLink.ok(p, d.name + " " + no + "번 칸을 설정해요. 칸마다 시작점·구역·방·보스를 따로 찍어 주세요.");
                    return 1;
                })));
    }

    /** 보상 묶음 이름 → 목록. 반복 = 기본 (예전 이름). */
    private static List<Defs.Reward> group(Defs.Dungeon d, String kind) {
        return switch (kind) { case "첫클리어" -> d.firstRewards; case "확률" -> d.randomRewards; default -> d.baseRewards; };
    }

    private LiteralArgumentBuilder<CommandSourceStack> rewards() {
        var node = Commands.literal("보상");
        for (String kind : List.of("기본", "반복", "첫클리어", "확률")) {
            var k = Commands.literal(kind);
            com.mojang.brigadier.Command<CommandSourceStack> add = ctx -> {
                Player p = self(ctx); var d = dungeon(p);
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) throw ChacaLink.error("넣을 아이템을 손에 들어 주세요");
                double chance = 0;
                if (kind.equals("확률")) {
                    try { chance = ctx.getArgument("확률", Double.class); }
                    catch (IllegalArgumentException missing) { throw ChacaLink.error("확률(%)을 적어 주세요: /던전관리 보상 확률 추가 12.5"); }
                }
                String ia = plugin.service().items.idOf(hand);
                Defs.Reward r;
                if (ia != null) r = new Defs.Reward(ia, hand.getAmount(), "", chance);
                else if (!hand.hasItemMeta() || hand.isSimilar(new ItemStack(hand.getType()))) r = new Defs.Reward(hand.getType().getKey().toString(), hand.getAmount(), "", chance);
                else { var one = hand.clone(); one.setAmount(1); r = new Defs.Reward("", hand.getAmount(), Base64.getEncoder().encodeToString(one.serializeAsBytes()), chance); }
                group(d, kind).add(r);
                saved(p, label(kind) + " 추가: " + (r.item().isBlank() ? hand.getType().getKey() + " (아이템 그대로)" : r.item()) + " ×" + r.amount()
                        + (kind.equals("확률") ? " · " + chance + "%" : ""));
                return 1;
            };
            if (kind.equals("확률")) k.then(Commands.literal("추가").executes(add).then(Commands.argument("확률", DoubleArgumentType.doubleArg(0.01, 100)).executes(add)));
            else k.then(Commands.literal("추가").executes(add));
            k.then(Commands.literal("목록").executes(ctx -> {
                var p = self(ctx); var d = dungeon(p);
                var list = group(d, kind);
                var out = new ArrayList<String>();
                out.add("── " + d.name + " " + label(kind) + " " + list.size() + "줄 ──");
                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i);
                    out.add(" " + (i + 1) + ". " + (r.item().isBlank() ? "(손에 들었던 아이템)" : r.item()) + " ×" + r.amount() + (r.chance() > 0 ? " · " + r.chance() + "%" : ""));
                }
                ChacaLink.lines(p, out);
                return 1;
            }));
            k.then(Commands.literal("빼기").then(Commands.argument("번호", IntegerArgumentType.integer(1, 99)).executes(ctx -> {
                var p = self(ctx); var d = dungeon(p);
                var list = group(d, kind);
                int i = ctx.getArgument("번호", Integer.class);
                if (i > list.size()) throw ChacaLink.error(label(kind) + "은 " + list.size() + "줄이에요");
                var r = list.remove(i - 1);
                saved(p, label(kind) + " " + i + "번 뺌: " + (r.item().isBlank() ? "(손에 들었던 아이템)" : r.item()));
                return 1;
            })));
            k.then(Commands.literal("비우기").executes(ctx -> {
                var p = self(ctx); var d = dungeon(p);
                group(d, kind).clear();
                saved(p, label(kind) + "을 비웠어요");
                return 1;
            }));
            node.then(k);
        }
        return node;
    }

    private static String label(String kind) {
        return switch (kind) { case "첫클리어" -> "첫 클리어 보상"; case "확률" -> "확률 보상"; default -> "기본 보상"; };
    }

    private int check(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        var d = dungeon(p);
        var out = new ArrayList<String>();
        out.add("── " + d.name + " 설정 확인 ──");
        out.addAll(plugin.editor().check(d));
        ChacaLink.lines(p, out);
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> forceReset() {
        return Commands.literal("칸초기화").then(Commands.argument("던전", ChacaLink.word(() -> plugin.service().dungeons.keySet()))
                .then(Commands.argument("칸", IntegerArgumentType.integer(1, 16)).executes(ctx -> {
                    String id = ctx.getArgument("던전", String.class);
                    int no = ctx.getArgument("칸", Integer.class);
                    boolean ok = plugin.service().forceReset(id, no);
                    ChacaLink.ok(ctx.getSource().getSender(), id + " " + no + "번 칸 " + (ok ? "정리 완료 (배정 가능)" : "정리 실패 — 콘솔 확인"));
                    return 1;
                })));
    }
}
