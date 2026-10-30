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
        // [기능:파티던전 시작]
        ChacaLink.branch(plugin, root, newDungeon(), "새 던전 등록 (기본값은 첫 던전에서 복사, 칸 3개)", "새던전 crypt 지하 묘지");
        ChacaLink.branch(plugin, root, dungeonInfo(), "던전 정보 바꾸기: 이름·부제·월드·칸수·인원·권한·잠금문구·입장권·입장권수·첫입장무료·체력회복·포탈이름·배경", "던전정보 crypt 인원 3");
        ChacaLink.branch(plugin, root, placeHere("입구", true), "지금 선 자리를 그 던전의 입장 포탈로 (반경 선택)", "입구 crypt 2.5");
        ChacaLink.branch(plugin, root, placeHere("퇴장위치", false), "지금 선 자리를 그 던전에서 나왔을 때 서는 곳으로", "퇴장위치 crypt");
        ChacaLink.branch(plugin, root, Commands.literal("던전목록").executes(ctx -> {
            var out = new ArrayList<String>();
            out.add("── 던전 " + plugin.service().dungeons.size() + "개 ──");
            for (var d : plugin.service().dungeons.values())
                out.add(" " + d.id + " · " + d.name + " · " + (d.partySize > 1 ? d.partySize + "인 파티" : "1인") + " · 칸 " + d.instances + " · 월드 " + d.world
                        + (d.portal == null ? " · 입구 없음" : "") + (d.exit == null ? " · 퇴장위치 없음" : ""));
            ChacaLink.lines(ctx.getSource().getSender(), out);
            return 1;
        }), "등록된 던전 목록", "던전목록");
        // [기능:파티던전 끝]
        ChacaLink.reload(plugin, "던전입장", "config·dungeons.yml 다시 읽기", plugin::reload);
    }

    // [기능:파티던전 시작]
    /** 던전 목록에 하나 더 넣고 저장 (진행 중인 판은 그대로). */
    private void putDungeon(Defs.Dungeon d) {
        var next = new java.util.LinkedHashMap<>(plugin.service().dungeons);
        next.put(d.id, d);
        plugin.service().apply(next);
        plugin.saveDungeons();
    }

    private LiteralArgumentBuilder<CommandSourceStack> newDungeon() {
        return Commands.literal("새던전").then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                .then(Commands.argument("이름", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx -> {
                    String id = ctx.getArgument("id", String.class);
                    String name = ctx.getArgument("이름", String.class).trim();
                    if (!id.matches("[a-z0-9_]{1,32}")) throw ChacaLink.error("id 는 영문 소문자·숫자·_ 로 32자까지예요 (예: crypt)");
                    if (plugin.service().dungeons.containsKey(id)) throw ChacaLink.error("이미 있는 id 예요: " + id);
                    if (!Defs.validName(name)) throw ChacaLink.error("이름은 한글·영문·숫자·공백 32자까지예요");
                    var d = new Defs.Dungeon(id);
                    d.name = name;
                    d.portalLabel = name;
                    d.lockMessage = "아직 들어갈 수 없어요.";
                    var template = plugin.service().dungeons.values().stream().findFirst().orElse(null);
                    if (template != null) {
                        d.world = template.world;
                        d.ticketItem = template.ticketItem;
                        d.ticketAmount = template.ticketAmount;
                        d.freeUntilFirstClear = template.freeUntilFirstClear;
                    } else if (ctx.getSource().getSender() instanceof Player p) d.world = p.getWorld().getName();
                    d.instances = 3;
                    for (int no = 1; no <= d.instances; no++) d.slot(no);
                    putDungeon(d);
                    ChacaLink.ok(ctx.getSource().getSender(), "새 던전 등록: " + id + " (" + name + ") · 월드 " + d.world + " · 칸 3 · 1인. "
                            + "다음: /던전관리 입구 " + id + " · 퇴장위치 " + id + " · 던전정보 " + id + " 인원 3 · 설정 도구로 칸 설정");
                    return 1;
                })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> dungeonInfo() {
        var keys = List.of("이름", "부제", "월드", "칸수", "인원", "권한", "잠금문구", "입장권", "입장권수", "첫입장무료", "체력회복", "포탈이름", "배경");
        return Commands.literal("던전정보").then(Commands.argument("던전", ChacaLink.word(() -> plugin.service().dungeons.keySet()))
                .then(Commands.argument("항목", ChacaLink.word(() -> keys))
                        .then(Commands.argument("값", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx -> {
                            var d = plugin.service().dungeons.get(ctx.getArgument("던전", String.class));
                            if (d == null) throw ChacaLink.error("그런 던전이 없어요");
                            String key = ctx.getArgument("항목", String.class), v = ctx.getArgument("값", String.class).trim();
                            String shown = v;
                            switch (key) {
                                case "이름" -> { if (!Defs.validName(v)) throw ChacaLink.error("이름은 한글·영문·숫자·공백 32자까지예요"); d.name = v; }
                                case "부제" -> d.subtitle = v;
                                case "월드" -> { if (org.bukkit.Bukkit.getWorld(v) == null) throw ChacaLink.error("그런 월드가 없어요: " + v); d.world = v; }
                                case "칸수" -> { d.instances = Math.max(1, Math.min(16, number(v))); for (int no = 1; no <= d.instances; no++) d.slot(no); shown = String.valueOf(d.instances); }
                                case "인원" -> {
                                    int n = number(v);
                                    if (n < 1 || n > 5) throw ChacaLink.error("인원은 1(1인 던전)~5 예요");
                                    d.partySize = n; shown = n == 1 ? "1인 던전" : n + "인 파티 던전";
                                }
                                case "권한" -> d.permission = v.equals("없음") ? "" : v;
                                case "잠금문구" -> d.lockMessage = v;
                                case "입장권" -> d.ticketItem = v.equals("없음") ? "" : v;
                                case "입장권수" -> { d.ticketAmount = Math.max(0, number(v)); shown = String.valueOf(d.ticketAmount); }
                                case "첫입장무료" -> d.freeUntilFirstClear = bool(v);
                                case "체력회복" -> d.naturalRegen = bool(v);
                                case "포탈이름" -> d.portalLabel = v;
                                case "배경" -> { if (!v.matches("[a-z0-9_]{1,32}|없음")) throw ChacaLink.error("배경 이름은 영문 소문자·숫자·_ (예: halloween, lich)"); d.art = v.equals("없음") ? "" : v; }
                                default -> throw ChacaLink.error("항목: " + String.join(" · ", keys));
                            }
                            putDungeon(d);
                            ChacaLink.ok(ctx.getSource().getSender(), d.id + " " + key + " = " + shown);
                            return 1;
                        }))));
    }

    private static int number(String v) throws CommandSyntaxException {
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { throw ChacaLink.error("숫자를 적어 주세요: " + v); }
    }

    private static boolean bool(String v) throws CommandSyntaxException {
        return switch (v.trim()) {
            case "켜기", "예", "true", "on" -> true;
            case "끄기", "아니오", "false", "off" -> false;
            default -> throw ChacaLink.error("켜기 또는 끄기로 적어 주세요");
        };
    }

    /** 입구(포탈) 또는 퇴장 위치를 지금 선 자리로. */
    private LiteralArgumentBuilder<CommandSourceStack> placeHere(String word, boolean portal) {
        com.mojang.brigadier.Command<CommandSourceStack> run = ctx -> {
            Player p = self(ctx);
            var d = plugin.service().dungeons.get(ctx.getArgument("던전", String.class));
            if (d == null) throw ChacaLink.error("그런 던전이 없어요");
            var at = Defs.Loc.of(p.getLocation());
            if (portal) {
                d.portal = at;
                try { d.portalRadius = Math.max(0.5, ctx.getArgument("반경", Double.class)); } catch (IllegalArgumentException none) { }
            } else d.exit = at;
            putDungeon(d);
            ChacaLink.ok(p, d.name + " " + (portal ? "입구 포탈 = " + at.shortText() + " (반경 " + d.portalRadius + ")" : "퇴장 위치 = " + at.shortText()));
            return 1;
        };
        var arg = Commands.argument("던전", ChacaLink.word(() -> plugin.service().dungeons.keySet())).executes(run);
        if (portal) arg.then(Commands.argument("반경", DoubleArgumentType.doubleArg(0.5, 10)).executes(run));
        return Commands.literal(word).then(arg);
    }
    // [기능:파티던전 끝]

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
                String ia = RewardKeep.ia(plugin.service().items.idOf(hand), hand); // [기능:던전보상능력치유지]
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
