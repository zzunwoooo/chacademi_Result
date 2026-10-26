package school.chacademia.kyudichi;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * 큐디치 명령 (2026-10-20 명령어 정리, MagicCodexBridge 명령어 허브에 붙는다).
 * <ul>
 *   <li>/큐디치관리 … 경기장 편집·경기·시즌 (보내는 사람별 "편집 중 경기장")</li>
 *   <li>/유저관리 &lt;닉네임&gt; 큐디치 … · 결투 … · 파티 …</li>
 *   <li>/테스트 큐디치 … · /테스트 결투 시작 &lt;경기장&gt;</li>
 * </ul>
 * 유저 명령 /큐디치 · /결투 는 Velocity 프록시가 로비 창을 연다 (이 서버에는 없음).
 */
final class KyudichiCommands {
    private static final String ROOT = "/큐디치관리 ";
    private final KyudichiPlugin plugin;
    /** sender key → arena being edited. */
    private final Map<String, String> editing = new HashMap<>();

    KyudichiCommands(KyudichiPlugin plugin) { this.plugin = plugin; }

    // ================================================================== registration
    void register() {
        manage("목록", lit("목록").executes(ctx -> { arenaList(ctx.getSource().getSender()); return 1; }), "경기장 목록", "목록");
        manage("생성", lit("생성").then(arg("이름", ChacaLink.word(List::of))
                        .executes(ctx -> { create(ctx, ctx.getArgument("이름", String.class), false); return 1; })
                        .then(lit("3대3").executes(ctx -> { create(ctx, ctx.getArgument("이름", String.class), false); return 1; }))
                        .then(lit("1대1").executes(ctx -> { create(ctx, ctx.getArgument("이름", String.class), true); return 1; }))),
                "경기장 만들기 (만들면 바로 편집 상태)", "생성 ");
        manage("편집", lit("편집").then(arg("경기장", arenaWord()).executes(ctx -> {
            Arena ar = arena(ctx.getArgument("경기장", String.class));
            editing.put(key(ctx), ar.name);
            ChacaLink.ok(sender(ctx), ar.name + " 편집을 시작했어요");
            return 1;
        })), "경기장 편집 시작 (이후 경기장 이름 생략)", "편집 ");
        manage("편집끝", lit("편집끝").executes(ctx -> {
            String was = editing.remove(key(ctx));
            if (was == null) throw ChacaLink.error("편집 중인 경기장이 없어요");
            ChacaLink.ok(sender(ctx), was + " 편집을 끝냈어요");
            return 1;
        }), "경기장 편집 끝내기", "편집끝");
        manage("정보", lit("정보").executes(ctx -> { describe(sender(ctx), editing(ctx)); return 1; }), "편집 중 경기장 정보 보기", "정보");

        manage("선택도구", lit("선택도구").executes(ctx -> {
            player(ctx).getInventory().addItem(plugin.selections().wand());
            ChacaLink.ok(sender(ctx), "선택도구를 주었어요 (좌클릭 지점1 · 우클릭 지점2)");
            return 1;
        }), "영역 선택 막대 받기", "선택도구");
        manage("지점1", lit("지점1").executes(ctx -> { plugin.selections().set(player(ctx), 0, player(ctx).getLocation()); return 1; }), "발밑을 지점1로", "지점1");
        manage("지점2", lit("지점2").executes(ctx -> { plugin.selections().set(player(ctx), 1, player(ctx).getLocation()); return 1; }), "발밑을 지점2로", "지점2");
        var expand = lit("확장");
        for (String dir : List.of("위", "아래", "사방"))
            expand.then(lit(dir).then(arg("칸", IntegerArgumentType.integer(1, 512)).executes(ctx -> {
                String r = plugin.selections().expand(player(ctx), dir, IntegerArgumentType.getInteger(ctx, "칸"));
                if (r == null) throw ChacaLink.error("먼저 지점1·지점2를 정하세요");
                ChacaLink.ok(sender(ctx), r);
                return 1;
            })));
        manage("선택", lit("선택").then(expand).then(lit("해제").executes(ctx -> {
            plugin.selections().clear(player(ctx)); ChacaLink.ok(sender(ctx), "선택을 지웠어요"); return 1;
        })), "선택 영역 확장·해제", "선택 확장 위 10");

        manage("경계", lit("경계").executes(ctx -> { Arena a = editing(ctx); a.bounds = selection(ctx); changed(ctx, a, "경계 " + a.bounds.size()); return 1; }), "선택 영역을 경계로", "경계");
        manage("공영역", lit("공영역").executes(ctx -> { Arena a = editing(ctx); a.ballArea = selection(ctx); changed(ctx, a, "공영역 " + a.ballArea.size()); return 1; }), "선택 영역을 공영역으로", "공영역");
        manage("스니치영역", lit("스니치영역").executes(ctx -> { Arena a = editing(ctx); a.snitchArea = selection(ctx); changed(ctx, a, "스니치영역 " + a.snitchArea.size()); return 1; }), "선택 영역을 스니치영역으로", "스니치영역");
        manage("골대", teams(lit("골대"), (ctx, t) -> {
            Arena a = editing(ctx); Box b = selection(ctx); a.goals.get(t).add(b);
            changed(ctx, a, t.word + " 골대 " + a.goals.get(t).size() + " 추가 (" + b.size() + ")");
        }), "선택 영역을 골대로 추가", "골대 파랑");
        manage("금지영역", lit("금지영역").executes(ctx -> {
            Arena a = editing(ctx); Box b = selection(ctx); a.forbidden.add(b);
            changed(ctx, a, "금지영역 " + a.forbidden.size() + " 추가 (" + b.size() + ")"); return 1;
        }), "선택 영역을 금지영역으로 추가", "금지영역");
        manage("시작위치", teams(lit("시작위치"), (ctx, t) -> slotPoint(ctx, editing(ctx).starts.get(t), t.word + " 시작위치", t.word + "시작위치")),
                "지금 위치를 시작위치로 (비어 있는 1→2→3 칸)", "시작위치 파랑");
        manage("부활위치", teams(lit("부활위치"), (ctx, t) -> slotPoint(ctx, editing(ctx).respawns.get(t), t.word + " 부활위치", t.word + "부활위치")),
                "지금 위치를 부활위치로 (비어 있는 1→2→3 칸)", "부활위치 파랑");
        manage("준비위치", teams(lit("준비위치"), (ctx, t) -> {
            Arena a = editing(ctx); a.prepare.put(t, here(ctx)); changed(ctx, a, t.word + " 준비위치를 저장했어요");
        }), "지금 위치를 준비위치로", "준비위치 파랑");
        manage("퀘이플위치", lit("퀘이플위치").executes(ctx -> { Arena a = editing(ctx); a.quaffle = here(ctx); changed(ctx, a, "퀘이플위치를 저장했어요"); return 1; }),
                "지금 위치를 퀘이플위치로", "퀘이플위치");
        manage("블러저위치", lit("블러저위치").executes(ctx -> { slotPoint(ctx, editing(ctx).bludgers, "블러저위치", "블러저위치"); return 1; }),
                "지금 위치를 블러저위치로 (비어 있는 1→2 칸)", "블러저위치");
        manage("스니치위치", lit("스니치위치").executes(ctx -> {
            Arena a = editing(ctx); a.snitchSpawns.add(here(ctx)); changed(ctx, a, "스니치위치 " + a.snitchSpawns.size() + " 추가"); return 1;
        }), "지금 위치를 스니치 출현 위치로 추가", "스니치위치");
        var shrine = lit("성소");
        for (ShrineType t : ShrineType.values()) shrine.then(lit(t.label).executes(ctx -> { shrine(ctx, List.of(t)); return 1; }));
        shrine.then(lit("전부").executes(ctx -> { shrine(ctx, Arrays.asList(ShrineType.values())); return 1; }));
        manage("성소", shrine, "지금 위치를 성소 후보로 추가", "성소 체력");
        manage("삭제", deleteNode(), "편집 중 경기장의 항목 지우기", "삭제 ");
        manage("검사", lit("검사").executes(ctx -> { check(sender(ctx), editing(ctx)); return 1; }), "남은 항목을 순서대로 보기 (누르면 명령 입력)", "검사");
        manage("테두리보기", lit("테두리보기").executes(ctx -> {
            Arena a = editing(ctx);
            ChacaLink.ok(sender(ctx), plugin.selections().toggleShow(player(ctx), a.name) ? "테두리 표시를 켰어요" : "테두리 표시를 껐어요");
            return 1;
        }), "경기장 테두리 입자 표시 켜기/끄기", "테두리보기");
        manage("이동", lit("이동").executes(ctx -> {
            Arena a = editing(ctx);
            if (a.bounds == null) throw ChacaLink.error("경계를 먼저 정하세요");
            var w = Bukkit.getWorld(a.bounds.world());
            if (w == null) throw ChacaLink.error("경기장 월드 " + a.bounds.world() + "이(가) 없어요");
            var c = a.bounds.center(); player(ctx).teleport(new Location(w, c.getX(), c.getY(), c.getZ()));
            return 1;
        }), "경기장 가운데로 이동", "이동");
        manage("켜기", lit("켜기").executes(ctx -> {
            Arena a = editing(ctx);
            var p = a.problems();
            if (!p.isEmpty()) throw ChacaLink.error("빠진 항목이 있어 켤 수 없어요: " + String.join(", ", p.subList(0, Math.min(4, p.size()))) + (p.size() > 4 ? " …" : ""));
            a.enabled = true; plugin.arenas().save(); ChacaLink.ok(sender(ctx), a.name + " 경기장을 켰어요"); return 1;
        }), "경기장 사용 켜기", "켜기");
        manage("끄기", lit("끄기").executes(ctx -> { Arena a = editing(ctx); a.enabled = false; plugin.arenas().save(); ChacaLink.ok(sender(ctx), a.name + " 경기장을 껐어요"); return 1; }),
                "경기장 사용 끄기", "끄기");
        manage("연습장", lit("연습장")
                .then(lit("켜기").executes(ctx -> { Arena a = editing(ctx); a.practice = true; plugin.arenas().save(); ChacaLink.ok(sender(ctx), a.name + " 연습장으로 썼어요"); return 1; }))
                .then(lit("끄기").executes(ctx -> { Arena a = editing(ctx); a.practice = false; plugin.arenas().save(); ChacaLink.ok(sender(ctx), a.name + " 연습장 사용을 껐어요"); return 1; })),
                "연습장으로 쓰기 켜기/끄기", "연습장 켜기");
        manage("경기장삭제", lit("경기장삭제").then(arg("이름", arenaWord())
                .executes(ctx -> { arena(ctx.getArgument("이름", String.class)); throw confirm(); })
                .then(lit("확인").executes(ctx -> {
                    Arena a = arena(ctx.getArgument("이름", String.class));
                    if (plugin.matches().inArena(a.name) != null) throw ChacaLink.error("경기 중인 경기장은 지울 수 없어요");
                    plugin.arenas().remove(a.name);
                    editing.values().removeIf(a.name::equals);
                    ChacaLink.ok(sender(ctx), a.name + " 경기장을 지웠어요");
                    return 1;
                }))), "경기장 지우기 (되돌릴 수 없음)", "경기장삭제 ");

        manage("경기", lit("경기")
                .then(lit("목록").executes(ctx -> { matchList(sender(ctx)); return 1; }))
                .then(lit("시작").then(arg("경기장", arenaWord()).then(arg("파랑팀", ChacaLink.word(this::onlineNames)).suggests(this::teamSuggest)
                        .then(arg("빨강팀", ChacaLink.word(this::onlineNames)).suggests(this::teamSuggest).executes(this::matchStart)))))
                .then(lit("종료").executes(ctx -> { end(ctx, null); return 1; })
                        .then(arg("경기장", matchWord()).executes(ctx -> { end(ctx, ctx.getArgument("경기장", String.class)); return 1; })))
                .then(customNode()),
                "진행 중인 경기 목록·시작·종료·커스텀 (커스텀·시작 = 레이팅 미반영, 관전 인원 제한 없음)", "경기 커스텀 큐디치 ");
        var season = lit("시즌");
        var start = lit("시작"); var reset = lit("초기화");
        for (String[] m : new String[][]{{"큐디치", RatingStore.MODE}, {"결투", RatingStore.DUEL}}) {
            start.then(lit(m[0]).executes(ctx -> { seasonStart(ctx, m[1], plugin.getConfig().getDouble("season.length-days", 7)); return 1; })
                    .then(arg("일수", DoubleArgumentType.doubleArg(0.01, 365)).executes(ctx -> { seasonStart(ctx, m[1], DoubleArgumentType.getDouble(ctx, "일수")); return 1; })));
            reset.then(lit(m[0]).executes(ctx -> { throw confirm(); }).then(lit("확인").executes(ctx -> {
                CommandSender s = sender(ctx);
                plugin.store().resetSeason(m[1], done -> {
                    if (done == null) ChacaLink.fail(s, "시즌을 초기화하지 못했어요", null);
                    else ChacaLink.ok(s, label(m[1]) + " 레이팅·전적을 모두 초기화했어요");
                });
                return 1;
            })));
        }
        manage("시즌", season.then(start).then(reset), "시즌 시작·초기화", "시즌 시작 큐디치 7");

        // ---------------------------------------------------------------- /유저관리 <닉네임> 큐디치|결투|파티
        ChacaLink.branch(plugin, ChacaLink.유저관리, user("큐디치", RatingStore.MODE), "큐디치 레이팅·전적·금지·대기열", "큐디치 보기");
        ChacaLink.branch(plugin, ChacaLink.유저관리, user("결투", RatingStore.DUEL), "1:1 결투 레이팅·전적·금지·대기열", "결투 보기");
        ChacaLink.branch(plugin, ChacaLink.유저관리, lit("파티")
                .then(lit("보기").executes(ctx -> proxy(ctx, KyudichiQueueProtocol.ADMIN_PARTY_VIEW, 0)))
                .then(lit("빼기").executes(ctx -> proxy(ctx, KyudichiQueueProtocol.ADMIN_PARTY_REMOVE, 0)))
                .then(lit("해산").executes(ctx -> { throw confirm(); })
                        .then(lit("확인").executes(ctx -> proxy(ctx, KyudichiQueueProtocol.ADMIN_PARTY_DISBAND, 0)))),
                "파티 보기·빼기·해산", "파티 보기");

        // ---------------------------------------------------------------- /테스트 큐디치 · 결투
        var joinArena = arg("경기장", matchWord());
        for (Team t : Team.values())
            joinArena.then(lit(t.word).executes(ctx -> { testJoin(ctx, t, player(ctx)); return 1; })
                    .then(arg("닉네임", ChacaLink.player()).executes(ctx -> { testJoin(ctx, t, ChacaLink.resolveOnline(ctx, "닉네임")); return 1; })));
        ChacaLink.branch(plugin, ChacaLink.테스트, lit("큐디치")
                .then(lit("시작").then(arg("경기장", arenaWord()).executes(ctx -> { testStart(ctx, ""); return 1; })
                        .then(arg("옵션", StringArgumentType.greedyString()).suggests(TestCommandSuggestions::suggest)
                                .executes(ctx -> { testStart(ctx, StringArgumentType.getString(ctx, "옵션")); return 1; }))))
                .then(lit("공").then(arg("경기장", arenaWord()).executes(ctx -> { testStart(ctx, "준비:0 스니치:0 무적"); return 1; })))
                .then(lit("참가").then(joinArena))
                .then(lit("종료").executes(ctx -> { end(ctx, null); return 1; })),
                "큐디치 테스트 경기 (레이팅 미반영)", "큐디치 시작 ");
        ChacaLink.branch(plugin, ChacaLink.테스트, lit("결투")
                .then(lit("시작").then(arg("경기장", ChacaLink.word(() -> names(true))).executes(ctx -> { duelTest(ctx); return 1; }))),
                "1:1 결투장 혼자 테스트", "결투 시작 ");
    }

    // ================================================================== small helpers
    private static LiteralArgumentBuilder<CommandSourceStack> lit(String name) { return Commands.literal(name); }
    private static <T> com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return Commands.argument(name, type);
    }
    private void manage(String name, LiteralArgumentBuilder<CommandSourceStack> node, String description, String example) {
        ChacaLink.branch(plugin, ChacaLink.큐디치관리, node, description, example);
    }
    private interface TeamAction { void run(CommandContext<CommandSourceStack> ctx, Team t) throws CommandSyntaxException; }
    private static LiteralArgumentBuilder<CommandSourceStack> teams(LiteralArgumentBuilder<CommandSourceStack> node, TeamAction action) {
        for (Team t : Team.values()) node.then(lit(t.word).executes(ctx -> { action.run(ctx, t); return 1; }));
        return node;
    }
    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) { return ctx.getSource().getSender(); }
    private static Player player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (sender(ctx) instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }
    private static String key(CommandContext<CommandSourceStack> ctx) {
        return sender(ctx) instanceof Player p ? p.getUniqueId().toString() : "#" + sender(ctx).getName();
    }
    private static CommandSyntaxException confirm() { return ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요"); }
    private static String label(String mode) { return mode.equals(RatingStore.DUEL) ? "결투" : "큐디치"; }

    private com.mojang.brigadier.arguments.ArgumentType<String> arenaWord() { return ChacaLink.word(() -> names(null)); }
    private com.mojang.brigadier.arguments.ArgumentType<String> matchWord() {
        return ChacaLink.word(() -> plugin.matches().all().stream().map(m -> m.arena.name).toList());
    }
    /** Arena names; duel = null for every kind. */
    private Collection<String> names(Boolean duel) {
        return plugin.arenas().all().stream().filter(a -> duel == null || a.duel == duel).map(a -> a.name).toList();
    }
    /** 닉+닉+닉 칸: 마지막 + 뒤 글자로 접속자 한글 닉네임을 제안한다. (쉼표는 클라이언트 입력 칸이 단어 끝으로 보므로 + 를 쓴다. 서버는 쉼표도 받는다) */
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> teamSuggest(CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        String typed = b.getRemaining();
        int comma = Math.max(typed.lastIndexOf('+'), typed.lastIndexOf(','));
        var sub = b.createOffset(b.getStart() + comma + 1);
        String last = typed.substring(comma + 1).toLowerCase(Locale.ROOT);
        var used = new HashSet<>(Arrays.asList(typed.substring(0, comma + 1).split("[+,]")));
        for (String n : onlineNames()) if (n.indexOf(' ') < 0 && !used.contains(n) && n.toLowerCase(Locale.ROOT).startsWith(last)) sub.suggest(n);
        return sub.buildFuture();
    }

    private Collection<String> onlineNames() {
        var out = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(ChacaLink.name(p.getUniqueId()));
        return out;
    }

    private Arena arena(String name) throws CommandSyntaxException {
        Arena a = plugin.arenas().get(name);
        if (a == null) throw ChacaLink.error(name + " 경기장이 없어요");
        return a;
    }
    private Arena editing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = editing.get(key(ctx));
        Arena a = name == null ? null : plugin.arenas().get(name);
        if (a == null) { editing.remove(key(ctx)); throw ChacaLink.error("먼저 /큐디치관리 편집 <경기장>"); }
        return a;
    }
    private Box selection(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Box b = plugin.selections().box(player(ctx));
        if (b == null) throw ChacaLink.error("먼저 선택도구로 지점1·지점2를 찍으세요");
        return b;
    }
    private static Point here(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return Point.of(player(ctx).getLocation()); }
    private static String fmt(Point p) { return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", p.x(), p.y(), p.z()); }

    /** Saves, and turns the arena off when a required item went missing. */
    private void changed(CommandContext<CommandSourceStack> ctx, Arena a, String message) {
        String extra = "";
        if (a.enabled && !a.problems().isEmpty()) { a.enabled = false; extra = " · 필수 항목이 빠져 경기장이 꺼졌어요"; }
        plugin.arenas().save();
        ChacaLink.ok(sender(ctx), message + extra);
    }

    /** First empty slot (1→2→3); a 1:1 arena only uses slot 1 of starts/respawns. */
    private void slotPoint(CommandContext<CommandSourceStack> ctx, Point[] slots, String what, String deleteItem) throws CommandSyntaxException {
        Arena a = editing(ctx);
        int max = a.duel && slots.length == 3 ? 1 : slots.length;
        int i = 0;
        while (i < max && slots[i] != null) i++;
        if (i >= max) throw ChacaLink.error(what + " " + max + "칸이 모두 찼어요. /큐디치관리 삭제 " + deleteItem + " <번호> 후 다시 해 주세요");
        slots[i] = here(ctx);
        changed(ctx, a, what + " " + (i + 1) + "을(를) 저장했어요");
    }

    private void shrine(CommandContext<CommandSourceStack> ctx, List<ShrineType> types) throws CommandSyntaxException {
        Arena a = editing(ctx);
        Point p = here(ctx);
        var added = new ArrayList<String>();
        for (ShrineType t : types) {
            if (a.duel && (t == ShrineType.STABILITY || t == ShrineType.HASTE)) {
                if (types.size() == 1) throw ChacaLink.error("1:1 결투장에는 체력·마나 성소만 있어요");
                continue;
            }
            a.shrines.get(t).add(p); added.add(t.label + " " + a.shrines.get(t).size());
        }
        changed(ctx, a, "성소 후보 추가: " + String.join(" · ", added));
    }

    // ================================================================== create / list / describe / check
    private void create(CommandContext<CommandSourceStack> ctx, String name, boolean duel) throws CommandSyntaxException {
        if (!ArenaStore.valid(name)) throw ChacaLink.error("경기장 이름은 한글·영문·숫자·_- 32자까지예요");
        if (plugin.arenas().get(name) != null) throw ChacaLink.error("이미 있는 경기장이에요");
        var a = plugin.arenas().create(name);
        if (duel) { a.duel = true; plugin.arenas().save(); }
        editing.put(key(ctx), name);
        ChacaLink.ok(sender(ctx), (duel ? "1:1 결투장 " : "경기장 ") + name + "을(를) 만들고 편집을 시작했어요 · /큐디치관리 검사로 남은 항목 보기");
    }

    private void arenaList(CommandSender s) {
        var lines = new ArrayList<String>();
        lines.add("── 큐디치 경기장 " + plugin.arenas().all().size() + "개 ──");
        if (plugin.arenas().all().isEmpty()) lines.add("경기장이 없어요. /큐디치관리 생성 <이름> [3대3|1대1]");
        for (Arena ar : plugin.arenas().all())
            lines.add(ar.name + (ar.duel ? " [1:1]" : " [3:3]") + (ar.enabled ? " [켜짐]" : " [꺼짐]") + (ar.practice ? " [연습장]" : "")
                    + (plugin.matches().inArena(ar.name) != null ? " [경기 중]" : "") + (ar.problems().isEmpty() ? "" : " · 빠진 항목 " + ar.problems().size()));
        ChacaLink.lines(s, lines);
    }

    private void describe(CommandSender s, Arena ar) {
        var l = new ArrayList<String>();
        l.add("── 경기장 " + ar.name + (ar.duel ? " [1:1]" : " [3:3]") + (ar.enabled ? " [켜짐]" : " [꺼짐]") + (ar.practice ? " [연습장]" : "") + " ──");
        l.add("경계 " + (ar.bounds == null ? "없음" : ar.bounds.size()) + " · 공영역 " + (ar.ballArea == null ? "(경계)" : ar.ballArea.size())
                + " · 스니치영역 " + (ar.snitchArea == null ? "(공영역)" : ar.snitchArea.size()) + " · 금지영역 " + ar.forbidden.size());
        for (Team t : Team.values()) {
            long starts = Arrays.stream(ar.starts.get(t)).filter(Objects::nonNull).count();
            long res = Arrays.stream(ar.respawns.get(t)).filter(Objects::nonNull).count();
            l.add(t.label + " 골대 " + ar.goals.get(t).size() + " · 시작위치 " + starts + "/" + (ar.duel ? 1 : 3) + " · 부활위치 " + res + " · 준비위치 " + (ar.prepare.get(t) == null ? "없음" : "있음"));
        }
        l.add("퀘이플위치 " + (ar.quaffle == null ? "없음" : "있음") + " · 블러저위치 " + Arrays.stream(ar.bludgers).filter(Objects::nonNull).count() + "/2 · 스니치위치 " + ar.snitchSpawns.size());
        var shr = new StringBuilder();
        for (ShrineType t : ShrineType.values()) shr.append(t.label).append(' ').append(ar.shrines.get(t).size()).append("  ");
        l.add("성소 후보: " + shr.toString().strip());
        var p = ar.problems();
        l.add(p.isEmpty() ? "필수 항목을 모두 채웠어요" : "빠진 항목 " + p.size() + "개 · /큐디치관리 검사");
        ChacaLink.lines(s, l);
    }

    /** 남은 항목을 순서대로, 줄을 누르면 고치는 명령이 채팅창에 들어간다. */
    private void check(CommandSender s, Arena ar) {
        var fixes = ar.fixes();
        if (fixes.isEmpty()) { ChacaLink.ok(s, ar.name + " 필수 항목을 모두 채웠어요" + (ar.enabled ? "" : " · /큐디치관리 켜기")); return; }
        s.sendMessage(Component.text("── " + ar.name + " 남은 항목 " + fixes.size() + "개 ──", NamedTextColor.GOLD));
        int i = 1;
        for (var e : fixes.entrySet()) {
            s.sendMessage(Component.text(" " + i++ + ". " + e.getKey(), NamedTextColor.WHITE)
                    .append(Component.text("  " + e.getValue(), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand(e.getValue() + " "))
                    .hoverEvent(HoverEvent.showText(Component.text("누르면 채팅창에 입력돼요"))));
        }
        s.sendMessage(Component.text(" 선택이 필요한 항목은 선택도구로 지점1·지점2를 먼저 찍으세요.", NamedTextColor.DARK_GRAY));
    }

    // ================================================================== 삭제 <항목> [번호]
    private interface Remover { String remove(Arena a, Integer number) throws CommandSyntaxException; }

    private LiteralArgumentBuilder<CommandSourceStack> deleteNode() {
        var node = lit("삭제");
        item(node, "경계", (a, n) -> { a.bounds = null; return "경계를 지웠어요"; });
        item(node, "공영역", (a, n) -> { a.ballArea = null; return "공영역을 지웠어요"; });
        item(node, "스니치영역", (a, n) -> { a.snitchArea = null; return "스니치영역을 지웠어요"; });
        for (Team t : Team.values()) {
            item(node, t.word + "골대", (a, n) -> fromList(a.goals.get(t), n, t.word + " 골대", Box::size));
            item(node, t.word + "시작위치", (a, n) -> fromSlots(a.starts.get(t), n, t.word + " 시작위치"));
            item(node, t.word + "부활위치", (a, n) -> fromSlots(a.respawns.get(t), n, t.word + " 부활위치"));
            item(node, t.word + "준비위치", (a, n) -> { if (a.prepare.remove(t) == null) throw ChacaLink.error(t.word + " 준비위치가 없어요"); return t.word + " 준비위치를 지웠어요"; });
        }
        item(node, "금지영역", (a, n) -> fromList(a.forbidden, n, "금지영역", Box::size));
        item(node, "퀘이플위치", (a, n) -> { a.quaffle = null; return "퀘이플위치를 지웠어요"; });
        item(node, "블러저위치", (a, n) -> fromSlots(a.bludgers, n, "블러저위치"));
        item(node, "스니치위치", (a, n) -> fromList(a.snitchSpawns, n, "스니치위치", KyudichiCommands::fmt));
        for (ShrineType t : ShrineType.values()) item(node, t.label + "성소", (a, n) -> fromList(a.shrines.get(t), n, t.label + " 성소", KyudichiCommands::fmt));
        return node;
    }

    private void item(LiteralArgumentBuilder<CommandSourceStack> node, String name, Remover r) {
        node.then(lit(name).executes(ctx -> { Arena a = editing(ctx); changed(ctx, a, r.remove(a, null)); return 1; })
                .then(arg("번호", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    Arena a = editing(ctx); changed(ctx, a, r.remove(a, IntegerArgumentType.getInteger(ctx, "번호"))); return 1;
                })));
    }

    private static <T> String fromList(List<T> list, Integer n, String what, java.util.function.Function<T, String> show) throws CommandSyntaxException {
        if (list.isEmpty()) throw ChacaLink.error(what + "이(가) 없어요");
        if (n == null) {
            if (list.size() > 1) {
                var names = new ArrayList<String>();
                for (int i = 0; i < list.size() && i < 8; i++) names.add((i + 1) + "." + show.apply(list.get(i)));
                throw ChacaLink.error(what + "이(가) " + list.size() + "개예요. 번호를 붙여 주세요: " + String.join("  ", names));
            }
            n = 1;
        }
        if (n > list.size()) throw ChacaLink.error("번호는 1~" + list.size() + "이에요");
        list.remove(n - 1);
        return what + " " + n + "을(를) 지웠어요";
    }

    private static String fromSlots(Point[] slots, Integer n, String what) throws CommandSyntaxException {
        if (n == null) {
            int last = -1;
            for (int i = 0; i < slots.length; i++) if (slots[i] != null) last = i;
            if (last < 0) throw ChacaLink.error(what + "이(가) 없어요");
            n = last + 1;
        }
        if (n > slots.length) throw ChacaLink.error("번호는 1~" + slots.length + "이에요");
        if (slots[n - 1] == null) throw ChacaLink.error(what + " " + n + "이(가) 비어 있어요");
        slots[n - 1] = null;
        return what + " " + n + "을(를) 지웠어요";
    }

    // ================================================================== matches
    private void matchList(CommandSender s) {
        var lines = new ArrayList<String>();
        lines.add("── 진행 중인 경기 " + plugin.matches().all().size() + "개 ──");
        if (plugin.matches().all().isEmpty()) lines.add("진행 중인 경기가 없어요.");
        for (Match m : plugin.matches().all()) {
            var names = new EnumMap<Team, List<String>>(Team.class);
            for (var mp : m.players.values()) names.computeIfAbsent(mp.team, k -> new ArrayList<>()).add(mp.nick);
            lines.add(m.arena.name + " · " + m.phase + " · " + m.score[0] + ":" + m.score[1] + " · " + names.getOrDefault(Team.A, List.of()) + " vs "
                    + names.getOrDefault(Team.B, List.of()) + (m.opt.test() ? " [테스트]" : m.opt.rated() ? "" : " [커스텀]") + " · 관전 " + m.spectators.size() + (m.custom() ? "" : "/" + Spectate.LIMIT));
        }
        ChacaLink.lines(s, lines);
    }

    private Arena ready(String name) throws CommandSyntaxException {
        Arena ar = arena(name);
        var p = ar.problems();
        if (!p.isEmpty()) throw ChacaLink.error(ar.name + " 설정이 덜 됐어요 (/큐디치관리 편집 " + ar.name + " 후 검사)");
        return ar;
    }

    private List<Player> team(String list) throws CommandSyntaxException {
        var out = new ArrayList<Player>();
        for (String n : list.split("[+,]")) {
            if (n.isBlank()) continue;
            UUID id = ChacaLink.find(n.strip());
            if (id == null) throw ChacaLink.error(n.strip() + " 님을 찾을 수 없어요");
            Player p = Bukkit.getPlayer(id);
            if (p == null) throw ChacaLink.error(ChacaLink.name(id) + " 님이 이 서버에 접속 중이 아니에요");
            if (out.contains(p)) throw ChacaLink.error(ChacaLink.name(id) + " 님을 두 번 적었어요");
            out.add(p);
        }
        if (out.isEmpty() || out.size() > 3) throw ChacaLink.error("팀은 1~3명이에요 (닉+닉+닉)");
        return out;
    }

    private int matchStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Arena ar = ready(ctx.getArgument("경기장", String.class));
        var a = team(ctx.getArgument("파랑팀", String.class)); var b = team(ctx.getArgument("빨강팀", String.class));
        for (Player p : a) if (b.contains(p)) throw ChacaLink.error(plugin.hooks().nick(p) + " 님이 두 팀에 모두 있어요");
        if (ar.duel && (a.size() != 1 || b.size() != 1)) throw ChacaLink.error("1:1 결투장은 팀마다 한 명이에요");
        var teams = new EnumMap<Team, List<Player>>(Team.class);
        teams.put(Team.A, a); teams.put(Team.B, b);
        Rules r = plugin.rules();
        String e = plugin.matches().start(ar, teams, ar.duel
                ? new Match.Options(false, false, r.duelPrepareTicks, r.duelRoundTicks, r.snitchAtTicks, false, Set.of())
                : new Match.Options(false, false, r.prepareTicks, r.playTicks, r.snitchAtTicks, true, Set.of()));
        if (e != null) throw ChacaLink.error(e);
        ChacaLink.ok(sender(ctx), ar.name + " 커스텀 경기를 시작했어요 (레이팅 미반영 · 관전 인원 제한 없음)");
        return 1;
    }

    /**
     * 커스텀 경기 (#62, 2026-10-25): 경기 커스텀 &lt;큐디치|결투&gt; &lt;파랑팀&gt; &lt;빨강팀&gt; — players anywhere on the network (school·wild…).
     * The proxy checks them, asks everyone to accept (same 10 s popup as matchmaking) and sends them here; rating unchanged,
     * no spectator limit, back to their own server afterwards. Players already on this server: 경기 시작 (no accept step).
     */
    private LiteralArgumentBuilder<CommandSourceStack> customNode() {
        var node = lit("커스텀");
        for (String[] m : new String[][]{{"큐디치", "0"}, {"결투", "1"}}) {
            int mode = Integer.parseInt(m[1]);
            node.then(lit(m[0]).then(arg("파랑팀", ChacaLink.word(this::onlineNames)).suggests(this::teamSuggest)
                    .then(arg("빨강팀", ChacaLink.word(this::onlineNames)).suggests(this::teamSuggest).executes(ctx -> customStart(ctx, mode)))));
        }
        return node;
    }

    private int customStart(CommandContext<CommandSourceStack> ctx, int mode) throws CommandSyntaxException {
        boolean duel = mode == KyudichiQueueProtocol.MODE_DUEL;
        var a = networkTeam(ctx.getArgument("파랑팀", String.class)); var b = networkTeam(ctx.getArgument("빨강팀", String.class));
        for (UUID id : a) if (b.contains(id)) throw ChacaLink.error(ChacaLink.name(id) + " 님이 두 팀에 모두 있어요");
        if (duel && (a.size() != 1 || b.size() != 1)) throw ChacaLink.error("1:1 커스텀 경기는 팀마다 한 명이에요");
        for (UUID id : a) if (plugin.matches().of(id) != null) throw ChacaLink.error(ChacaLink.name(id) + " 님은 이미 경기 중이에요");
        for (UUID id : b) if (plugin.matches().of(id) != null) throw ChacaLink.error(ChacaLink.name(id) + " 님은 이미 경기 중이에요");
        plugin.link().custom(sender(ctx), mode, a, b);
        return 1;
    }

    /** 닉+닉+닉 → UUIDs (anyone with a known nickname; whether they are online on the network is checked by the proxy). */
    private List<UUID> networkTeam(String list) throws CommandSyntaxException {
        var out = new ArrayList<UUID>();
        for (String n : list.split("[+,]")) {
            if (n.isBlank()) continue;
            UUID id = ChacaLink.find(n.strip());
            if (id == null) throw ChacaLink.error(n.strip() + " 님을 찾을 수 없어요");
            if (out.contains(id)) throw ChacaLink.error(ChacaLink.name(id) + " 님을 두 번 적었어요");
            out.add(id);
        }
        if (out.isEmpty() || out.size() > 3) throw ChacaLink.error("팀은 1~3명이에요 (닉+닉+닉)");
        return out;
    }

    /** 경기 종료 [경기장] · 테스트 큐디치 종료: test and real matches alike (a real one ends void: no rating, no record). */
    private void end(CommandContext<CommandSourceStack> ctx, String arena) throws CommandSyntaxException {
        CommandSender s = sender(ctx);
        Match m;
        if (arena != null) {
            m = plugin.matches().inArena(arena);
            if (m == null) throw ChacaLink.error(arena + "에서 진행 중인 경기가 없어요");
        } else {
            m = s instanceof Player p ? plugin.matches().of(p.getUniqueId()) : null;
            if (m == null && s instanceof Player p) m = plugin.matches().spectating(p.getUniqueId());
            if (m == null) throw ChacaLink.error("끝낼 경기가 없어요. 경기장 이름을 붙여 주세요");
        }
        boolean rated = m.opt.rated();
        m.end(null, m.opt.test() ? Match.EndReason.ADMIN : Match.EndReason.VOID);
        ChacaLink.ok(s, m.arena.name + " 경기를 끝냈어요" + (rated ? " (무효: 레이팅·기록 미반영)" : ""));
    }

    private void seasonStart(CommandContext<CommandSourceStack> ctx, String mode, double days) throws CommandSyntaxException {
        if (!(days > 0 && days <= 365)) throw ChacaLink.error("일수는 0보다 크고 365 이하예요");
        CommandSender s = sender(ctx);
        plugin.store().startSeason(mode, days, n -> {
            if (n == null) ChacaLink.fail(s, "시즌을 시작하지 못했어요", null);
            else ChacaLink.ok(s, label(mode) + " 시즌 " + n.number() + " 시작 · " + new java.text.SimpleDateFormat("M월 d일 HH:mm").format(new java.util.Date(n.endsAt())) + "까지");
        });
    }

    // ================================================================== tests
    private void testStart(CommandContext<CommandSourceStack> ctx, String options) throws CommandSyntaxException {
        Player p = player(ctx);
        Arena ar = ready(ctx.getArgument("경기장", String.class));
        Rules r = plugin.rules();
        int prep = r.prepareTicks, play = r.playTicks, snitch = r.snitchAtTicks; boolean bl = true; Set<UUID> inv = new HashSet<>();
        for (String o : options.strip().split("\\s+")) {
            if (o.isEmpty()) continue;
            if (o.startsWith("준비:")) prep = 20 * number(o, 3, 0, 600);
            else if (o.startsWith("시간:")) play = 1200 * number(o, 3, 1, 120);
            else if (o.startsWith("스니치:")) snitch = 20 * number(o, 4, 0, 7200);
            else if (o.equals("무적")) inv.add(p.getUniqueId());
            else if (o.equals("블러저끔") || o.equals("블러저:끔")) bl = false;
            else throw ChacaLink.error("모르는 옵션: " + o + " (준비:초 시간:분 스니치:초 무적 블러저끔)");
        }
        var teams = new EnumMap<Team, List<Player>>(Team.class);
        teams.put(Team.A, List.of(p)); teams.put(Team.B, List.of());
        String e = plugin.matches().start(ar, teams, new Match.Options(false, true, prep, Math.max(200, play), snitch, bl, inv));
        if (e != null) throw ChacaLink.error(e);
        ChacaLink.ok(sender(ctx), ar.name + " 테스트 경기를 시작했어요 · 다른 사람: /테스트 큐디치 참가 " + ar.name + " 빨강");
    }

    private static int number(String option, int from, int min, int max) throws CommandSyntaxException {
        try {
            int v = Integer.parseInt(option.substring(from));
            if (v < min || v > max) throw ChacaLink.error(option.substring(0, from) + " 값은 " + min + "~" + max + "이에요");
            return v;
        } catch (NumberFormatException e) { throw ChacaLink.error(option.substring(0, from) + " 뒤에는 숫자를 입력해 주세요"); }
    }

    private void testJoin(CommandContext<CommandSourceStack> ctx, Team t, Player p) throws CommandSyntaxException {
        String arena = ctx.getArgument("경기장", String.class);
        Match m = plugin.matches().inArena(arena);
        if (m == null || !m.opt.test()) throw ChacaLink.error(arena + "에서 진행 중인 테스트 경기가 없어요");
        if (plugin.matches().of(p.getUniqueId()) != null) throw ChacaLink.error(plugin.hooks().nick(p) + " 님은 이미 경기 중이에요");
        if (plugin.spectate().spectating(p.getUniqueId())) plugin.spectate().leave(p);
        var mp = m.add(p, t, plugin.store().cached(p.getUniqueId(), plugin.hooks().nick(p), m.duel ? RatingStore.DUEL : RatingStore.MODE).rating());
        m.rejoinAs(p, mp);
        ChacaLink.ok(sender(ctx), plugin.hooks().nick(p) + " 님이 " + t.label + "으로 참가했어요");
    }

    private void duelTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        Arena ar = ready(ctx.getArgument("경기장", String.class));
        if (!ar.duel) throw ChacaLink.error(ar.name + "은(는) 1:1 결투장이 아니에요");
        var teams = new EnumMap<Team, List<Player>>(Team.class);
        teams.put(Team.A, List.of(p)); teams.put(Team.B, List.of());
        Rules r = plugin.rules();
        String e = plugin.matches().start(ar, teams, new Match.Options(false, true, r.duelPrepareTicks, r.duelRoundTicks, 0, false, Set.of()));
        if (e != null) throw ChacaLink.error(e);
        ChacaLink.ok(sender(ctx), ar.name + " 결투 테스트를 시작했어요 · 상대: /테스트 큐디치 참가 " + ar.name + " 빨강");
    }

    // ================================================================== /유저관리 <닉네임> 큐디치|결투
    private LiteralArgumentBuilder<CommandSourceStack> user(String name, String mode) {
        int wire = mode.equals(RatingStore.DUEL) ? KyudichiQueueProtocol.MODE_DUEL : KyudichiQueueProtocol.MODE_KYUDICHI;
        return lit(name)
                .then(lit("보기").executes(ctx -> { view(ctx, mode); return 1; }))
                .then(lit("레이팅설정").then(arg("값", IntegerArgumentType.integer(0, 100_000)).executes(ctx -> {
                    int v = IntegerArgumentType.getInteger(ctx, "값");
                    return edit(ctx, mode, r -> with(r, v, r.bannedUntil()), "레이팅을 " + v + "(으)로 바꿨어요");
                })))
                .then(lit("레이팅추가").then(arg("값", IntegerArgumentType.integer(-100_000, 100_000)).executes(ctx -> {
                    int v = IntegerArgumentType.getInteger(ctx, "값");
                    return edit(ctx, mode, r -> with(r, Math.max(0, r.rating() + v), r.bannedUntil()), "레이팅을 " + (v >= 0 ? "+" : "") + v + " 했어요");
                })))
                .then(lit("레이팅초기화").executes(ctx -> edit(ctx, mode, r -> with(r, (int) plugin.rules().rating.start(), r.bannedUntil()), "레이팅을 초기화했어요")))
                .then(lit("전적초기화").executes(ctx -> { throw confirm(); }).then(lit("확인").executes(ctx -> edit(ctx, mode,
                        r -> new RatingStore.Row(r.id(), r.name(), r.mode(), r.rating(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, r.reachedAt(), r.bannedUntil(), 0),
                        "전적을 초기화했어요"))))
                .then(lit("경기에서빼기").executes(ctx -> { kick(ctx, mode); return 1; }))
                .then(lit("대기열에서빼기").executes(ctx -> proxy(ctx, KyudichiQueueProtocol.ADMIN_QUEUE_REMOVE, wire)))
                .then(lit("매칭상태초기화").executes(ctx -> proxy(ctx, KyudichiQueueProtocol.ADMIN_MATCH_RESET, wire)))
                .then(lit("금지").then(arg("기간", ChacaLink.word(() -> List.of("30분", "1시간", "1일", "7일", "영구"))).executes(ctx -> {
                    String text = ctx.getArgument("기간", String.class);
                    long until;
                    if (text.equals("영구")) until = Long.MAX_VALUE;
                    else {
                        try { until = System.currentTimeMillis() + duration(text); }
                        catch (IllegalArgumentException e) { throw ChacaLink.error("기간 예: 30분 · 1시간 · 1일 · 2일3시간 · 영구"); }
                    }
                    long u = until;
                    return edit(ctx, mode, r -> with(r, r.rating(), u), label(mode) + " 신청을 " + (u == Long.MAX_VALUE ? "영구히" : text + " 동안") + " 막았어요");
                })))
                .then(lit("금지해제").executes(ctx -> edit(ctx, mode, r -> with(r, r.rating(), 0), label(mode) + " 신청 금지를 풀었어요")));
    }

    private void view(CommandContext<CommandSourceStack> ctx, String mode) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = sender(ctx);
        String nick = ChacaLink.name(id);
        plugin.store().load(id, nick, mode, row -> {
            if (row == null) { ChacaLink.fail(s, "기록을 불러오지 못했어요", null); return; }
            var store = plugin.store();
            var tier = plugin.rules().rating.tier(row.rating(), store.rank(id, mode));
            var l = new ArrayList<String>();
            l.add("── " + nick + " " + label(mode) + " ──");
            l.add("레이팅 " + row.rating() + " (" + tier.label + ") · " + row.games() + "경기 " + row.wins() + "승 " + row.losses() + "패 " + row.draws() + "무 · 탈주 " + row.leaves());
            l.add((mode.equals(RatingStore.DUEL) ? "" : "골 " + row.goals() + " · ") + "처치 " + row.kills() + " · 데스 " + row.deaths() + " · 딜 " + Math.round(row.damage())
                    + " · 힐 " + Math.round(row.heal()) + (mode.equals(RatingStore.DUEL) ? "" : " · 스니치 " + row.snitch() + " · 블러저 명중 " + row.deflectHits()) + " · MVP " + row.mvp());
            if (row.bannedUntil() > System.currentTimeMillis())
                l.add("신청 금지 중: " + (row.bannedUntil() == Long.MAX_VALUE ? "영구" : new java.text.SimpleDateFormat("M월 d일 HH:mm").format(new java.util.Date(row.bannedUntil())) + "까지"));
            var season = store.season(mode);
            if (season != null) l.add("시즌 " + season.number() + " · " + new java.text.SimpleDateFormat("M월 d일 HH:mm").format(new java.util.Date(season.endsAt())) + "까지");
            Match m = plugin.matches().of(id);
            l.add(m == null ? "이 서버 경기: 없음" : "이 서버 경기: " + m.arena.name + (m.duel ? " (1:1)" : " (큐디치)"));
            ChacaLink.lines(s, l);
        });
    }

    private int edit(CommandContext<CommandSourceStack> ctx, String mode, UnaryOperator<RatingStore.Row> f, String done) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = sender(ctx);
        String nick = ChacaLink.name(id);
        plugin.store().update(id, nick, mode, f, n -> {
            if (n == null) ChacaLink.fail(s, "저장하지 못했어요", null);
            else ChacaLink.ok(s, nick + " 님 " + label(mode) + " " + done + " (레이팅 " + n.rating() + ")");
        });
        return 1;
    }

    private static RatingStore.Row with(RatingStore.Row r, int rating, long bannedUntil) {
        return new RatingStore.Row(r.id(), r.name(), r.mode(), rating, r.games(), r.wins(), r.losses(), r.draws(), r.goals(), r.kills(), r.deaths(), r.assists(),
                r.damage(), r.heal(), r.snitch(), r.deflectHits(), r.mvp(), rating != r.rating() ? System.currentTimeMillis() : r.reachedAt(), bannedUntil, r.leaves());
    }

    private void kick(CommandContext<CommandSourceStack> ctx, String mode) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        Match m = plugin.matches().of(id);
        boolean duel = mode.equals(RatingStore.DUEL);
        if (m == null || m.duel != duel) throw ChacaLink.error(ChacaLink.name(id) + " 님은 이 서버에서 " + label(mode) + " 경기 중이 아니에요");
        Player p = Bukkit.getPlayer(id);
        if (p == null) throw ChacaLink.error(ChacaLink.name(id) + " 님이 접속 중이 아니에요 (60초 안에 돌아오지 않으면 자동으로 빠져요)");
        m.kick(p);
        ChacaLink.ok(sender(ctx), ChacaLink.name(id) + " 님을 경기에서 뺐어요 (탈주 처리 없음)");
    }

    /** Queue / match state / party live on the Velocity proxy: ask it over the link channel. */
    private int proxy(CommandContext<CommandSourceStack> ctx, int action, int mode) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        plugin.link().admin(sender(ctx), action, id, mode);
        return 1;
    }

    /** 기간: 30분 · 1시간 · 1일 · 2일3시간 (초/분/시간/시/일), 영문 30m · 1h · 1d · 10s 도 받는다. */
    static long duration(String s) {
        var m = java.util.regex.Pattern.compile("(\\d+)(시간|초|분|시|일|s|m|h|d)").matcher(s);
        long total = 0; int end = 0;
        while (m.find()) {
            if (m.start() != end) break;
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) { case "s", "초" -> 1000L; case "m", "분" -> 60_000L; case "h", "시", "시간" -> 3_600_000L; default -> 86_400_000L; };
            end = m.end();
        }
        if (end != s.length() || total <= 0) throw new IllegalArgumentException("기간 예: 30분, 1시간, 1일, 2일3시간");
        return total;
    }
}
