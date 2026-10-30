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
        ChacaLink.branch(plugin, root, here("시작점", (p, d, s) -> { s.start = Defs.Loc.of(p.getLocation()); return "시작 지점 = 지금 자리 (보는 방향 포함)"; }), "이 칸의 시작 지점 = 지금 자리", "시작점");
        ChacaLink.branch(plugin, root, region(), "이 칸 구역 모서리 (정리·몹 집계 범위)", "구역 1");
        ChacaLink.branch(plugin, root, room(), "방 모서리·가두기 설정", "방 entrance 1");
        ChacaLink.branch(plugin, root, roomMob(), "방에 들어가면 지금 자리에 나올 몹", "방몹 entrance CA_Mob_ArchlichThrall 3");
        ChacaLink.branch(plugin, root, roomMobClear(), "방 몹 목록 비우기", "방몹비우기 entrance");
        ChacaLink.branch(plugin, root, boss(), "보스 자리 = 지금 자리", "보스 lich CA_Boss_Archlich");
        ChacaLink.branch(plugin, root, named("지점", (p, d, s, name) -> { s.points.put(name, Defs.Loc.of(p.getLocation())); return "지점 " + name + " = 지금 자리"; }), "도착 단계용 지점", "지점 altar");
        ChacaLink.branch(plugin, root, named("기믹", (p, d, s, name) -> {
            var found = plugin.service().hooks.nearestGimmick(p.getLocation(), 6);
            if (found == null) throw ChacaLink.error("6칸 안에 기믹이 없어요");
            s.gimmicks.put(name, found.block());
            return "기믹 " + name + " = " + found.model() + " (" + Defs.block(found.block()) + ")";
        }), "기믹 단계용: 가장 가까운 기믹 연결", "기믹 door1");
        ChacaLink.branch(plugin, root, portal(), "입장 포탈 = 지금 자리 (던전 공통)", "포탈 2.5");
        ChacaLink.branch(plugin, root, dungeonHere("출구", (p, d) -> { d.exit = Defs.Loc.of(p.getLocation()); return "나가는 곳 = 지금 자리"; }), "끝나면 돌아갈 곳 (던전 공통)", "출구");
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

    private LiteralArgumentBuilder<CommandSourceStack> here(String literal, SlotEdit edit) {
        return Commands.literal(literal).executes(ctx -> {
            Player p = self(ctx); var d = dungeon(p);
            saved(p, edit.apply(p, d, slot(p, d)));
            return 1;
        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> named(String literal, NamedEdit edit) {
        return Commands.literal(literal).then(Commands.argument("이름", ChacaLink.word(List::of)).executes(ctx -> {
            Player p = self(ctx); var d = dungeon(p);
            String name = ctx.getArgument("이름", String.class);
            if (!name.matches("[A-Za-z0-9_가-힣]{1,32}")) throw ChacaLink.error("이름은 한글·영문·숫자·_ 32자까지");
            saved(p, edit.apply(p, d, slot(p, d), name));
            return 1;
        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> dungeonHere(String literal, DungeonEdit edit) {
        return Commands.literal(literal).executes(ctx -> {
            Player p = self(ctx);
            saved(p, edit.apply(p, dungeon(p)));
            return 1;
        });
    }

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

    private LiteralArgumentBuilder<CommandSourceStack> region() {
        var node = Commands.literal("구역");
        for (String which : List.of("1", "2"))
            node.then(Commands.literal(which).executes(ctx -> {
                Player p = self(ctx); var d = dungeon(p); var s = slot(p, d);
                if (which.equals("1")) s.corner1 = feet(p); else s.corner2 = feet(p);
                var box = s.region();
                saved(p, "구역 모서리 " + which + " = " + Defs.block(feet(p)) + (box == null ? " (다른 모서리도 찍어 주세요)" : " → 구역 " + box.text()));
                return 1;
            }));
        return node;
    }

    private LiteralArgumentBuilder<CommandSourceStack> room() {
        var name = Commands.argument("방", ChacaLink.word(List::of));
        for (String action : List.of("1", "2", "가두기", "안가두기", "지우기"))
            name.then(Commands.literal(action).executes(ctx -> {
                Player p = self(ctx); var d = dungeon(p); var s = slot(p, d);
                String rn = ctx.getArgument("방", String.class);
                if (!rn.matches("[A-Za-z0-9_가-힣]{1,32}")) throw ChacaLink.error("방 이름은 한글·영문·숫자·_ 32자까지");
                if (action.equals("지우기")) { s.rooms.remove(rn); saved(p, "방 " + rn + " 지움"); return 1; }
                var room = s.rooms.computeIfAbsent(rn, Defs.Room::new);
                String msg;
                switch (action) {
                    case "1" -> { room.corner1 = feet(p); msg = "방 " + rn + " 모서리 1"; }
                    case "2" -> { room.corner2 = feet(p); msg = "방 " + rn + " 모서리 2"; }
                    case "가두기" -> { room.lock = true; msg = "방 " + rn + ": 들어가면 몹을 다 잡을 때까지 못 나감"; }
                    default -> { room.lock = false; msg = "방 " + rn + ": 가두지 않음"; }
                }
                var box = room.box();
                saved(p, msg + (box == null ? "" : " → " + box.text()));
                return 1;
            }));
        return Commands.literal("방").then(name);
    }

    private LiteralArgumentBuilder<CommandSourceStack> roomMob() {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            Player p = self(ctx); var d = dungeon(p); var s = slot(p, d);
            String rn = ctx.getArgument("방", String.class);
            var room = s.rooms.get(rn);
            if (room == null) throw ChacaLink.error("방 " + rn + " 이 없어요. 먼저 /던전관리 방 " + rn + " 1·2 로 모서리를 찍어 주세요");
            String mob = ctx.getArgument("몹", String.class);
            int count;
            try { count = ctx.getArgument("수", Integer.class); } catch (IllegalArgumentException missing) { count = 1; }
            room.spawns.add(new Defs.Spawn(mob, count, Defs.Loc.of(p.getLocation())));
            saved(p, "방 " + rn + " 몹 " + mob + " ×" + count + " (지금 자리) · 이 방 몹 자리 " + room.spawns.size() + "곳");
            return 1;
        };
        return Commands.literal("방몹").then(Commands.argument("방", ChacaLink.word(List::of)).then(Commands.argument("몹", ChacaLink.word(List::of)).executes(run)
                .then(Commands.argument("수", IntegerArgumentType.integer(1, 50)).executes(run))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> roomMobClear() {
        return Commands.literal("방몹비우기").then(Commands.argument("방", ChacaLink.word(List::of)).executes(ctx -> {
            Player p = self(ctx); var d = dungeon(p); var s = slot(p, d);
            var room = s.rooms.get(ctx.getArgument("방", String.class));
            if (room == null) throw ChacaLink.error("그런 방이 없어요");
            room.spawns.clear();
            saved(p, "방 " + room.name + " 몹 목록을 비웠어요");
            return 1;
        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> boss() {
        return Commands.literal("보스").then(Commands.argument("이름", ChacaLink.word(List::of)).then(Commands.argument("몹", ChacaLink.word(List::of)).executes(ctx -> {
            Player p = self(ctx); var d = dungeon(p); var s = slot(p, d);
            String name = ctx.getArgument("이름", String.class), mob = ctx.getArgument("몹", String.class);
            s.bosses.put(name, new Defs.Boss(mob, Defs.Loc.of(p.getLocation())));
            saved(p, "보스 " + name + " = " + mob + " (지금 자리)");
            return 1;
        })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> portal() {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            Player p = self(ctx); var d = dungeon(p);
            double r;
            try { r = ctx.getArgument("반경", Double.class); } catch (IllegalArgumentException missing) { r = d.portalRadius; }
            d.portal = Defs.Loc.of(p.getLocation().getBlock().getLocation().add(.5, 0, .5));
            d.portalRadius = r;
            if (d.exit == null) {
                var front = p.getLocation().clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(-(r + 1.5)));
                front.setYaw(p.getLocation().getYaw());
                d.exit = Defs.Loc.of(front);
            }
            plugin.service().apply(plugin.service().dungeons);
            saved(p, "포탈 = 지금 자리, 반경 " + r + "칸 (나가는 곳이 비어 있으면 포탈 앞으로)");
            return 1;
        };
        return Commands.literal("포탈").executes(run).then(Commands.argument("반경", DoubleArgumentType.doubleArg(0.5, 16)).executes(run));
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
        out.add(" 포탈 " + (d.portal == null ? "✘ 없음" : d.portal.shortText() + " 반경 " + d.portalRadius) + " · 나가는 곳 " + (d.exit == null ? "포탈" : d.exit.shortText()));
        out.add(" 입장권 " + (d.ticketItem.isBlank() ? "없음" : d.ticketItem + " ×" + d.ticketAmount + (d.freeUntilFirstClear ? " (첫 클리어 전 무료)" : "")) + " · 권한 " + (d.permission.isBlank() ? "누구나" : d.permission));
        out.add(" 단계 " + d.stages.size() + "개 · 보상 기본 " + d.baseRewards.size() + "줄 / 첫 클리어 " + d.firstRewards.size() + "줄 / 확률 " + d.randomRewards.size() + "줄 · 체력 자동 회복 " + (d.naturalRegen ? "있음" : "없음"));
        for (int no = 1; no <= d.instances; no++) {
            var s = d.slots.get(no);
            var miss = new ArrayList<String>();
            if (s == null || s.start == null) miss.add("시작점");
            if (s == null || s.region() == null) miss.add("구역");
            if (s != null) for (var st : d.stages) {
                switch (st.type) {
                    case "room" -> { var r = s.rooms.get(st.target); if (r == null || r.box() == null) miss.add("방 " + st.target); else if (r.spawns.isEmpty()) miss.add("방 " + st.target + " 몹"); }
                    case "boss" -> { if (!s.bosses.containsKey(st.target)) miss.add("보스 " + st.target); }
                    case "gimmick" -> { if (!s.gimmicks.containsKey(st.target)) miss.add("기믹 " + st.target); }
                    case "reach" -> { if (!s.points.containsKey(st.target)) miss.add("지점 " + st.target); }
                    default -> { }
                }
            }
            out.add(" " + no + "번 칸: " + (miss.isEmpty() ? "✔ 준비됨" : "✘ " + String.join(", ", miss)));
        }
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
