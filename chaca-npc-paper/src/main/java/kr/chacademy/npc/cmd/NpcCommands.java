package kr.chacademy.npc.cmd;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.config.PlaceRepository;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.TextFilter;
import kr.chacademy.npc.core.TimeText;
import kr.chacademy.npc.data.Storage;
import kr.chacademy.npc.dialogue.DialogueSession;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ChacaNPC 명령어 (MagicCodexBridge 명령어 허브에 붙인다).
 * <pre>
 * /NPC관리 소환|연결|연결해제|목록|장소|경로|분위기노트
 * /유저관리 &lt;닉네임&gt; AI대화 보기|호칭|기억|대화종료|사용량
 * /테스트 NPC 대화|대화종료
 * /서버관리 AI 켜기|끄기|예산|캐시
 * /말 &lt;내용…&gt; · /말 그만 · /호칭 &lt;이름|초기화&gt; · /대화선택 (채팅 버튼 클릭용)
 * </pre>
 */
public final class NpcCommands {

    /** 호칭 칸에서 "모든 NPC 공통 호칭"을 뜻하는 값. DB에는 "*"로 저장된다. */
    private static final String ALL_NPC = "전체";
    private static final int NICK_MAX = 8;

    private final ChacaNpcPlugin plugin;

    public NpcCommands(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (!ChacaLink.ready()) {
            plugin.getLogger().warning("[ChacaNPC] MagicCodexBridge 명령어 허브가 없어 NPC 명령을 등록하지 않았어요.");
            return;
        }
        ChacaLink.branch(plugin, ChacaLink.NPC관리, Commands.literal("소환").then(Commands.argument("캐릭터", characterArg())
                .executes(ctx -> spawn(ctx))), "내 위치에 캐릭터 NPC 만들기", "소환 ");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, Commands.literal("연결").then(Commands.argument("캐릭터", characterArg())
                .executes(ctx -> link(ctx))), "가장 가까운 Citizens NPC를 캐릭터와 연결", "연결 ");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, Commands.literal("연결해제").executes(ctx -> unlink(ctx)),
                "가장 가까운 NPC의 캐릭터 연결 해제", "연결해제");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, Commands.literal("목록").executes(ctx -> list(ctx)),
                "캐릭터·연결 상태·지금 일과", "목록");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, placeBranch(), "내 위치를 NPC 일과 장소로 등록", "장소 ");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, placeAdminBranch(), "등록된 장소 목록 보기·지우기", "장소관리 목록");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, waypointBranch(), "장소로 가는 중간 지점 추가·비우기", "경로 추가 ");
        ChacaLink.branch(plugin, ChacaLink.NPC관리, vibeBranch(), "서버 분위기 노트 보기·승인·삭제", "분위기노트 보기");

        ChacaLink.branch(plugin, ChacaLink.유저관리, userBranch(), "AI NPC 대화 호칭·기억·사용량", "AI대화 보기");

        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("NPC")
                .then(Commands.literal("대화").then(Commands.argument("캐릭터", characterArg())
                        .then(Commands.argument("문장", StringArgumentType.greedyString()).executes(ctx -> test(ctx)))))
                .then(Commands.literal("대화종료").executes(ctx -> {
                    plugin.dialogue().close(self(ctx), false);
                    return 1;
                })), "AI NPC 테스트 대화 (토큰·시간 표시)", "NPC 대화 ");

        ChacaLink.branch(plugin, ChacaLink.서버관리, Commands.literal("AI")
                .then(Commands.literal("켜기").executes(ctx -> ai(ctx, true)))
                .then(Commands.literal("끄기").executes(ctx -> ai(ctx, false)))
                .then(Commands.literal("예산").executes(ctx -> budget(ctx)))
                .then(Commands.literal("캐시").executes(ctx -> {
                    plugin.dialogue().buttonCache().clear();
                    ChacaLink.ok(ctx.getSource().getSender(), "버튼 대답 캐시를 비웠어요");
                    return 1;
                })), "AI NPC 켜기·끄기·예산·버튼 캐시", "AI 예산");

        ChacaLink.playerCommand(plugin, Commands.literal("말")
                .executes(ctx -> talk(ctx, ""))
                .then(Commands.literal("그만").executes(ctx -> {
                    Player p = self(ctx);
                    if (plugin.dialogue().session(p.getUniqueId()) != null) {
                        plugin.dialogue().close(p, true);
                    }
                    return 1;
                }))
                .then(Commands.argument("내용", StringArgumentType.greedyString())
                        .executes(ctx -> talk(ctx, StringArgumentType.getString(ctx, "내용")))),
                "근처 NPC에게 말 걸기 (그만: 대화 끝내기)", List.of());
        ChacaLink.playerCommand(plugin, Commands.literal("호칭")
                .then(Commands.literal("초기화").executes(ctx -> nickname(ctx, null)))
                .then(Commands.argument("이름", StringArgumentType.greedyString())
                        .executes(ctx -> nickname(ctx, StringArgumentType.getString(ctx, "이름").trim()))),
                "NPC가 나를 부를 호칭 (초기화: 원래 이름)", List.of());
        ChacaLink.playerCommand(plugin, Commands.literal("대화선택")
                .then(Commands.literal("버튼").then(Commands.argument("버튼", ChacaLink.word(List::of)).executes(ctx -> {
                    plugin.dialogue().input(self(ctx), null, ctx.getArgument("버튼", String.class));
                    return 1;
                })))
                .then(Commands.literal("선물").then(Commands.argument("칸", ChacaLink.word(List::of)).executes(ctx -> {
                    String slot = ctx.getArgument("칸", String.class);
                    if (slot.matches("[0-9]|[12][0-9]|3[0-5]|40")) {
                        plugin.dialogue().gift(self(ctx), Integer.parseInt(slot), -1);
                    }
                    return 1;
                })))
                .then(Commands.literal("부탁")
                        .then(Commands.literal("수락").then(Commands.argument("번호", ChacaLink.word(List::of))
                                .executes(ctx -> quest(ctx, true))))
                        .then(Commands.literal("거절").then(Commands.argument("번호", ChacaLink.word(List::of))
                                .executes(ctx -> quest(ctx, false))))),
                "(내부용) NPC 대화 채팅 버튼", List.of());

        ChacaLink.reload(plugin, "NPC", "AI NPC 설정·캐릭터·장소·필터", plugin::reloadEverything);
        ChacaLink.status(plugin, "AI NPC", this::statusLine);
        ChacaLink.summary(plugin, "AI 대화", id -> {
            int calls = plugin.budget().callsToday(id.toString());
            DialogueSession s = plugin.dialogue().session(id);
            if (calls == 0 && s == null) {
                return "";
            }
            return "오늘 " + calls + "/" + plugin.settings().perPlayerDailyCalls + "회" + (s == null ? "" : " · " + s.character().name() + "와 대화 중");
        });
    }

    // ================================================================ 공통

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) {
            return p;
        }
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    private static String label(CharacterSheet c) {
        String n = c.name() == null ? "" : c.name().replaceAll("\\s+", "");
        return n.isEmpty() ? c.id() : n;
    }

    private com.mojang.brigadier.arguments.ArgumentType<String> characterArg() {
        return ChacaLink.word(() -> {
            Set<String> out = new LinkedHashSet<>();
            for (CharacterSheet c : plugin.characters().all()) {
                out.add(label(c));
            }
            return out;
        });
    }

    /** 캐릭터 칸: 한글 이름(띄어쓰기 무시) 또는 캐릭터 ID. */
    private CharacterSheet character(CommandContext<CommandSourceStack> ctx, String arg) throws CommandSyntaxException {
        String in = ctx.getArgument(arg, String.class);
        CharacterSheet c = plugin.characters().get(in);
        if (c == null) {
            for (CharacterSheet x : plugin.characters().all()) {
                if (label(x).equals(in)) {
                    c = x;
                    break;
                }
            }
        }
        if (c == null) {
            throw ChacaLink.error("'" + in + "' 캐릭터가 없어요");
        }
        return c;
    }

    private String npcName(String id) {
        if ("*".equals(id)) {
            return "모든 NPC";
        }
        CharacterSheet c = plugin.characters().get(id);
        return c == null ? id : c.name();
    }

    /** DB 작업 → 메인 스레드에서 결과 처리. 실패하면 원인은 콘솔, 운영자에게는 failMessage. */
    private <T> void db(CommandSender to, Supplier<T> task, String failMessage, Consumer<T> done) {
        plugin.database().call(task).whenComplete((v, ex) -> plugin.sync(() -> {
            if (ex != null) {
                ChacaLink.fail(to, failMessage, ex);
                return;
            }
            try {
                done.accept(v);
            } catch (RuntimeException e) {
                ChacaLink.fail(to, failMessage, e);
            }
        }));
    }

    // ================================================================ /NPC관리

    private int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        CharacterSheet c = character(ctx, "캐릭터");
        NPC npc = plugin.npcs().spawn(c, p.getLocation());
        plugin.movement().reset();
        ChacaLink.ok(p, c.name() + " NPC를 만들었어요 (Citizens #" + npc.getId() + ")");
        return 1;
    }

    private int link(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        CharacterSheet c = character(ctx, "캐릭터");
        NPC npc = plugin.npcs().nearestAny(p, 5);
        if (npc == null) {
            throw ChacaLink.error("5칸 안에 Citizens NPC가 없어요");
        }
        int cleared = plugin.npcs().link(npc, c);
        plugin.movement().reset();
        ChacaLink.ok(p, "NPC #" + npc.getId() + "을(를) " + c.name() + "와 연결했어요"
                + (cleared > 0 ? " (이전 연결 " + cleared + "개 해제)" : ""));
        return 1;
    }

    private int unlink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = plugin.npcs().nearestAny(p, 5);
        if (npc == null) {
            throw ChacaLink.error("5칸 안에 Citizens NPC가 없어요");
        }
        if (!plugin.npcs().unlink(npc)) {
            throw ChacaLink.error("캐릭터와 연결된 NPC가 아니에요");
        }
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 연결을 해제했어요");
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        List<String> out = new ArrayList<>();
        out.add("AI NPC 캐릭터 " + plugin.characters().all().size() + "명 · 대화 중 " + plugin.dialogue().sessions().size() + "명");
        for (CharacterSheet c : plugin.characters().all()) {
            Integer id = plugin.npcs().npcIdFor(c.id());
            int slot = plugin.npcs().currentSlot(c);
            String where = slot >= 0 ? c.schedule().get(slot).place() : "-";
            out.add(label(c) + " (" + c.id() + ") · " + ("main".equalsIgnoreCase(c.type()) ? "메인" : "서브")
                    + " · " + (id == null ? "연결 안 됨" : "NPC #" + id) + " · 지금 일과: " + where);
        }
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> placeBranch() {
        var name = Commands.argument("이름", ChacaLink.word(this::placeNames)).executes(ctx -> place(ctx, 4, null));
        var radius = Commands.argument("반경", DoubleArgumentType.doubleArg(0.5, 64))
                .executes(ctx -> place(ctx, DoubleArgumentType.getDouble(ctx, "반경"), null));
        radius.then(Commands.argument("표시이름", StringArgumentType.greedyString())
                .executes(ctx -> place(ctx, DoubleArgumentType.getDouble(ctx, "반경"), StringArgumentType.getString(ctx, "표시이름"))));
        return Commands.literal("장소").then(name.then(radius));
    }

    /** /NPC관리 장소관리 목록 | 삭제 <이름> | 전부삭제 확인 (오너 2026-10-26: 테스트 장소 정리). */
    private LiteralArgumentBuilder<CommandSourceStack> placeAdminBranch() {
        return Commands.literal("장소관리")
                .then(Commands.literal("목록").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    var names = plugin.places().allNames();
                    if (names.isEmpty()) { ChacaLink.ok(s, "등록된 장소가 없어요"); return 1; }
                    var rows = new ArrayList<String>();
                    for (String n : names) {
                        PlaceRepository.Place p = plugin.places().get(n);
                        rows.add(p == null ? " " + n + " (월드 없음·보류)"
                                : " " + n + (p.label == null ? "" : " · " + p.label) + " · 반경 " + p.radius + " · 중간 지점 " + p.waypoints.size());
                    }
                    s.sendMessage("── NPC 장소 " + names.size() + "곳 ──");
                    for (String r : rows) s.sendMessage(r);
                    return 1;
                }))
                .then(Commands.literal("삭제").then(Commands.argument("이름", ChacaLink.word(() -> plugin.places().allNames())).executes(ctx -> {
                    String name = ctx.getArgument("이름", String.class);
                    if (!plugin.places().removePlace(name)) throw ChacaLink.error("'" + name + "' 장소가 없어요");
                    plugin.movement().reset();
                    ChacaLink.ok(ctx.getSource().getSender(), "장소 '" + name + "'를 지웠어요 (이 장소를 일과표에 쓰던 NPC는 그 시간엔 제자리)");
                    return 1;
                })))
                .then(Commands.literal("전부삭제").then(Commands.literal("확인").executes(ctx -> {
                    int n = 0;
                    for (String name : plugin.places().allNames()) if (plugin.places().removePlace(name)) n++;
                    plugin.movement().reset();
                    ChacaLink.ok(ctx.getSource().getSender(), "장소 " + n + "곳을 모두 지웠어요");
                    return 1;
                })));
    }

    private Collection<String> placeNames() {
        Set<String> out = new TreeSet<>();
        for (PlaceRepository.Place p : plugin.places().all()) {
            out.add(p.name);
        }
        return out;
    }

    private int place(CommandContext<CommandSourceStack> ctx, double radius, String label) throws CommandSyntaxException {
        Player p = self(ctx);
        String name = ctx.getArgument("이름", String.class);
        if (!plugin.places().setPlace(name, p.getLocation(), radius)) {
            throw ChacaLink.error("장소 이름에는 마침표(.)를 쓸 수 없어요");
        }
        if (label != null && !label.isBlank()) {
            PlaceRepository.Place place = plugin.places().get(name);
            if (place != null) {
                place.label = label.trim();
                plugin.places().setPlace(name, place.location, place.radius);
            }
        }
        plugin.movement().reset();
        ChacaLink.ok(p, "장소 '" + name + "'를 등록했어요 (반경 " + radius + "칸)");
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> waypointBranch() {
        return Commands.literal("경로")
                .then(Commands.literal("추가").then(Commands.argument("장소", ChacaLink.word(this::placeNames)).executes(ctx -> {
                    Player p = self(ctx);
                    String name = ctx.getArgument("장소", String.class);
                    if (!plugin.places().addWaypoint(name, p.getLocation())) {
                        throw ChacaLink.error("'" + name + "' 장소가 없어요. 먼저 /NPC관리 장소 로 등록해 주세요");
                    }
                    PlaceRepository.Place place = plugin.places().get(name);
                    ChacaLink.ok(p, "'" + name + "'로 가는 중간 지점 " + (place == null ? "" : place.waypoints.size() + "번을 ") + "추가했어요");
                    return 1;
                })))
                .then(Commands.literal("비우기").then(Commands.argument("장소", ChacaLink.word(this::placeNames)).executes(ctx -> {
                    String name = ctx.getArgument("장소", String.class);
                    if (!plugin.places().clearWaypoints(name)) {
                        throw ChacaLink.error("'" + name + "' 장소가 없어요");
                    }
                    ChacaLink.ok(ctx.getSource().getSender(), "'" + name + "'의 중간 지점을 비웠어요");
                    return 1;
                })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> vibeBranch() {
        return Commands.literal("분위기노트")
                .then(Commands.literal("보기").executes(ctx -> vibeReview(ctx.getSource().getSender())))
                .then(Commands.literal("승인")
                        .then(Commands.literal("전체").executes(ctx -> {
                            CommandSender s = ctx.getSource().getSender();
                            db(s, () -> {
                                plugin.storage().setAllVibeStatus("candidate", "approved");
                                return null;
                            }, "분위기 노트를 승인하지 못했어요", v -> {
                                plugin.vibe().refreshCache();
                                ChacaLink.ok(s, "분위기 노트 후보를 모두 승인했어요");
                            });
                            return 1;
                        }))
                        .then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx ->
                                vibeStatus(ctx, "approved", "승인했어요"))))
                .then(Commands.literal("삭제").then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx ->
                        vibeStatus(ctx, "removed", "지웠어요"))))
                .then(Commands.literal("비우기").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    db(s, () -> {
                        plugin.storage().setAllVibeStatus("approved", "removed");
                        plugin.storage().setAllVibeStatus("candidate", "removed");
                        return null;
                    }, "분위기 노트를 비우지 못했어요", v -> {
                        plugin.vibe().refreshCache();
                        ChacaLink.ok(s, "분위기 노트를 모두 비웠어요");
                    });
                    return 1;
                }))
                .then(Commands.literal("지금생성").executes(ctx -> {
                    CommandSender s = ctx.getSource().getSender();
                    ChacaLink.ok(s, "분위기 노트를 지금 만들어요");
                    plugin.vibe().run("manual-" + System.currentTimeMillis(), m -> plugin.sync(() -> {
                        plugin.getLogger().info("[ChacaNPC] 분위기 노트(수동): " + m);
                        if (m.startsWith("AI 호출 실패") || m.startsWith("AI 응답") || m.startsWith("저장 실패") || m.startsWith("오류로 중단")) {
                            ChacaLink.fail(s, "분위기 노트를 만들지 못했어요", null);
                        } else {
                            ChacaLink.ok(s, "분위기 노트: " + m);
                        }
                    }));
                    return 1;
                }));
    }

    private int vibeReview(CommandSender s) {
        db(s, () -> List.of(plugin.storage().vibes("candidate"), plugin.storage().vibes("approved")), "분위기 노트를 불러오지 못했어요", lists -> {
            List<Storage.VibeRow> cand = lists.get(0);
            List<Storage.VibeRow> appr = lists.get(1);
            List<String> out = new ArrayList<>();
            out.add("분위기 노트 · 승인 " + appr.size() + "개 (NPC들이 알고 있음) · 후보 " + cand.size() + "개");
            for (Storage.VibeRow r : appr) {
                out.add("[승인] #" + r.id() + " " + r.note() + " (" + r.keyword() + ")");
            }
            for (Storage.VibeRow r : cand) {
                out.add("[후보] #" + r.id() + " " + r.note() + " (" + r.keyword() + ")");
            }
            if (!cand.isEmpty()) {
                out.add("승인: /NPC관리 분위기노트 승인 <번호|전체> · 삭제: /NPC관리 분위기노트 삭제 <번호>");
            }
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    private int vibeStatus(CommandContext<CommandSourceStack> ctx, String status, String done) {
        CommandSender s = ctx.getSource().getSender();
        long id = LongArgumentType.getLong(ctx, "번호");
        db(s, () -> {
            plugin.storage().setVibeStatus(id, status);
            return null;
        }, "분위기 노트를 바꾸지 못했어요", v -> {
            plugin.vibe().refreshCache();
            ChacaLink.ok(s, "분위기 노트 #" + id + "을(를) " + done);
        });
        return 1;
    }

    // ================================================================ /유저관리 <닉네임> AI대화

    private LiteralArgumentBuilder<CommandSourceStack> userBranch() {
        var npcNick = Commands.argument("NPC", nickTargetArg())
                .then(Commands.literal("설정").then(Commands.argument("이름", StringArgumentType.greedyString())
                        .executes(ctx -> userNickname(ctx, StringArgumentType.getString(ctx, "이름").trim()))))
                .then(Commands.literal("초기화").executes(ctx -> userNickname(ctx, null)));
        var memory = Commands.literal("기억")
                .then(Commands.literal("보기").executes(ctx -> memoryView(ctx, null))
                        .then(Commands.argument("NPC", characterArg()).executes(ctx -> memoryView(ctx, character(ctx, "NPC")))))
                .then(Commands.literal("삭제").then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx -> memoryDelete(ctx))))
                .then(Commands.literal("초기화").executes(ctx -> needConfirm())
                        .then(Commands.literal("확인").executes(ctx -> memoryClear(ctx, null)))
                        .then(Commands.argument("NPC", characterArg()).executes(ctx -> needConfirm())
                                .then(Commands.literal("확인").executes(ctx -> memoryClear(ctx, character(ctx, "NPC"))))));
        return Commands.literal("AI대화")
                .then(Commands.literal("보기").executes(ctx -> userView(ctx, null))
                        .then(Commands.argument("NPC", characterArg()).executes(ctx -> userView(ctx, character(ctx, "NPC")))))
                .then(Commands.literal("호칭").then(npcNick))
                .then(memory)
                .then(Commands.literal("대화종료").executes(ctx -> {
                    Player p = ChacaLink.targetOnline(ctx);
                    DialogueSession s = plugin.dialogue().session(p.getUniqueId());
                    if (s == null) {
                        throw ChacaLink.error(ChacaLink.name(p.getUniqueId()) + " 님은 AI 대화 중이 아니에요");
                    }
                    plugin.dialogue().close(p, false);
                    ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님의 " + s.character().name() + " 대화를 닫았어요");
                    return 1;
                }))
                .then(Commands.literal("사용량").executes(ctx -> usage(ctx)));
    }

    private static int needConfirm() throws CommandSyntaxException {
        throw ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요");
    }

    /** 호칭 칸: 캐릭터 + "전체"(모든 NPC 공통). */
    private com.mojang.brigadier.arguments.ArgumentType<String> nickTargetArg() {
        return ChacaLink.word(() -> {
            Set<String> out = new LinkedHashSet<>();
            out.add(ALL_NPC);
            for (CharacterSheet c : plugin.characters().all()) {
                out.add(label(c));
            }
            return out;
        });
    }

    private int userView(CommandContext<CommandSourceStack> ctx, CharacterSheet only) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String pid = id.toString();
        String who = ChacaLink.name(id);
        record View(Map<String, String> nicks, Map<String, Long> last, List<Storage.MemoRow> memos) {
        }
        db(s, () -> new View(plugin.storage().nicknames(pid), plugin.storage().lastChatTimes(pid),
                plugin.storage().memoRows(pid, only == null ? null : only.id())), "AI 대화 기록을 불러오지 못했어요", v -> {
            long now = System.currentTimeMillis();
            List<String> out = new ArrayList<>();
            DialogueSession cur = plugin.dialogue().session(id);
            if (only == null) {
                out.add(who + " 님 AI 대화");
                out.add("공통 호칭: " + v.nicks().getOrDefault("*", "없음 (원래 이름)"));
                out.add("오늘 대화 " + plugin.budget().callsToday(pid) + "회 / 하루 한도 " + plugin.settings().perPlayerDailyCalls + "회"
                        + (cur == null ? "" : " · 지금 " + cur.character().name() + "와 대화 중"));
                Set<String> npcs = new TreeSet<>();
                npcs.addAll(v.last().keySet());
                npcs.addAll(v.nicks().keySet());
                npcs.remove("*");
                for (Storage.MemoRow m : v.memos()) {
                    npcs.add(m.npc());
                }
                if (npcs.isEmpty()) {
                    out.add("NPC와 대화한 기록이 없어요");
                }
                for (String npc : npcs) {
                    long memoCount = v.memos().stream().filter(m -> m.npc().equals(npc)).count();
                    Long last = v.last().get(npc);
                    out.add(npcName(npc) + ": " + (v.nicks().containsKey(npc) ? "호칭 '" + v.nicks().get(npc) + "' · " : "")
                            + "기억 " + memoCount + "줄 · 마지막 대화 " + (last == null ? "없음" : TimeText.ago(last, now)));
                }
            } else {
                out.add(who + " 님 ↔ " + only.name());
                String nick = v.nicks().get(only.id());
                out.add("호칭: " + (nick != null ? "'" + nick + "'" : v.nicks().containsKey("*") ? "'" + v.nicks().get("*") + "' (공통)" : "없음 (원래 이름)"));
                Long last = v.last().get(only.id());
                out.add("마지막 대화: " + (last == null ? "없음" : TimeText.ago(last, now))
                        + (cur != null && cur.character().id().equals(only.id()) ? " · 지금 대화 중" : ""));
                if (v.memos().isEmpty()) {
                    out.add("기억: 없음");
                }
                for (Storage.MemoRow m : v.memos()) {
                    out.add("#" + m.id() + " " + m.memo());
                }
            }
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    private int userNickname(CommandContext<CommandSourceStack> ctx, String nickname) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String raw = ctx.getArgument("NPC", String.class);
        String npc;
        String npcLabel;
        if (raw.equals(ALL_NPC)) {
            npc = "*";
            npcLabel = "모든 NPC";
        } else {
            CharacterSheet c = character(ctx, "NPC");
            npc = c.id();
            npcLabel = c.name();
        }
        if (nickname != null) {
            if (nickname.isEmpty()) {
                throw ChacaLink.error("호칭을 적어 주세요");
            }
            if (nickname.codePointCount(0, nickname.length()) > NICK_MAX) {
                throw ChacaLink.error("호칭은 " + NICK_MAX + "글자까지 할 수 있어요");
            }
        }
        String who = ChacaLink.name(id);
        db(s, () -> {
            plugin.storage().setNickname(id.toString(), npc, nickname);
            return null;
        }, "호칭을 저장하지 못했어요", v -> ChacaLink.ok(s, nickname == null
                ? who + " 님의 " + npcLabel + " 호칭을 지웠어요 (다음 대화부터)"
                : npcLabel + "이(가) " + who + " 님을 '" + nickname + "'(이)라고 불러요 (다음 대화부터)"));
        return 1;
    }

    private int memoryView(CommandContext<CommandSourceStack> ctx, CharacterSheet only) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String who = ChacaLink.name(id);
        db(s, () -> plugin.storage().memoRows(id.toString(), only == null ? null : only.id()), "기억을 불러오지 못했어요", rows -> {
            List<String> out = new ArrayList<>();
            out.add(who + " 님에 대한 NPC 기억" + (only == null ? "" : " · " + only.name()) + " " + rows.size() + "줄");
            for (Storage.MemoRow m : rows) {
                out.add("#" + m.id() + " [" + npcName(m.npc()) + "] " + m.memo());
            }
            if (!rows.isEmpty()) {
                out.add("삭제: /유저관리 " + who + " AI대화 기억 삭제 <번호>");
            }
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    private int memoryDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        long no = LongArgumentType.getLong(ctx, "번호");
        String who = ChacaLink.name(id);
        db(s, () -> plugin.storage().deleteMemo(id.toString(), no), "기억을 지우지 못했어요", ok -> {
            if (Boolean.TRUE.equals(ok)) {
                ChacaLink.ok(s, who + " 님의 기억 #" + no + "을(를) 지웠어요");
            } else {
                ChacaLink.fail(s, who + " 님의 기억에 #" + no + "이(가) 없어요", null);
            }
        });
        return 1;
    }

    private int memoryClear(CommandContext<CommandSourceStack> ctx, CharacterSheet only) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String who = ChacaLink.name(id);
        db(s, () -> plugin.storage().clearMemory(id.toString(), only == null ? null : only.id()), "기억을 지우지 못했어요", n ->
                ChacaLink.ok(s, who + " 님의 " + (only == null ? "모든 NPC" : only.name()) + " 기억 " + n + "줄과 지난 대화를 지웠어요"));
        return 1;
    }

    private int usage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String pid = id.toString();
        String who = ChacaLink.name(id);
        db(s, () -> plugin.storage().usage(pid, 7), "사용량을 불러오지 못했어요", rows -> {
            List<String> out = new ArrayList<>();
            out.add(who + " 님 AI 대화 사용량");
            out.add("오늘 " + plugin.budget().callsToday(pid) + "회 / 하루 한도 " + plugin.settings().perPlayerDailyCalls + "회");
            if (rows.isEmpty()) {
                out.add("기록 없음");
            }
            for (Storage.UsageRow r : rows) {
                out.add(String.format("%s · %d회 · 입력 %,d(캐시 %,d) · 출력 %,d 토큰 · $%.4f",
                        r.day(), r.calls(), r.inputTokens(), r.cachedTokens(), r.outputTokens(), r.cost()));
            }
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    // ================================================================ /테스트 NPC

    private int test(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        CharacterSheet c = character(ctx, "캐릭터");
        String text = StringArgumentType.getString(ctx, "문장");
        DialogueSession cur = plugin.dialogue().session(p.getUniqueId());
        if (cur == null || !cur.character().id().equals(c.id()) || !cur.adminTest()) {
            if (cur != null) {
                plugin.dialogue().close(p, false);
            }
            plugin.dialogue().open(p, c, plugin.npcs().npcIdFor(c.id()), true);
        }
        plugin.dialogue().input(p, text, null);
        return 1;
    }

    // ================================================================ /서버관리 AI

    private int ai(CommandContext<CommandSourceStack> ctx, boolean on) {
        CommandSender s = ctx.getSource().getSender();
        plugin.budget().setAiEnabled(on);
        plugin.getLogger().info("[ChacaNPC] " + s.getName() + " 이(가) AI를 " + (on ? "켰습니다" : "껐습니다") + ".");
        ChacaLink.ok(s, on ? "AI NPC를 켰어요 (재시작해도 유지)" : "AI NPC를 껐어요. 모든 NPC가 고정 대사로 대답해요 (재시작해도 유지)");
        return 1;
    }

    private int budget(CommandContext<CommandSourceStack> ctx) {
        var b = plugin.budget();
        var st = plugin.settings();
        int day = st.dayIndex(st.today());
        String period = day < 0 ? (st.budgetLimitEnabled ? "운영 시작 전 (테스트 한도 $" + st.testDailyUsd + "/일)" : "운영 시작 전")
                : day >= st.budgetDays ? "운영 기간 종료" : "운영 " + (day + 1) + "일차 / " + st.budgetDays + "일";
        List<String> out = new ArrayList<>();
        out.add("AI NPC 예산 · " + period);
        if (st.budgetLimitEnabled) {
            out.add(String.format("오늘: $%.3f / 한도 $%.3f · 상태 %s", b.spentToday(), b.allowance(), statusName(b.status())));
            out.add(String.format("전체: $%.3f / $%.0f (진행 중 예약 $%.4f)", b.spentTotal(), st.budgetTotalUsd, b.reserved()));
        } else {
            out.add(String.format("오늘: $%.3f · 전체: $%.3f (금액 한도 없음 · 진행 중 예약 $%.4f)", b.spentToday(), b.spentTotal(), b.reserved()));
        }
        out.add("AI: " + (b.isAiEnabled() ? "켜짐" : "꺼짐") + " · MagicCodex 연결: " + (plugin.link().available() ? "연결됨" : "없음 (호감도 기본값, 퀘스트·선물 꺼짐)"));
        out.add("오늘 플레이어 대화 호출: " + b.totalCallsToday() + "회 (1인 한도 " + st.perPlayerDailyCalls + ")");
        out.add("응답 속도: " + b.latencySummary());
        out.add("AI 대기열: " + plugin.ai().queueSize() + " · 처리 중 " + plugin.ai().runningCount());
        out.add("최근 1시간 AI 실패: " + plugin.ai().failureSummary());
        out.add("AI 차단기: " + plugin.ai().circuitSummary());
        if (b.loadError() != null) {
            out.add("사용량을 불러오지 못해 AI 대화가 꺼져 있어요 (고정 대사만). 자세한 내용은 콘솔 — DB 확인 뒤 서버 재시작");
        } else if (!b.isLoaded()) {
            out.add("사용량을 아직 불러오는 중이에요 (그동안은 고정 대사)");
        }
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private static String statusName(kr.chacademy.npc.core.BudgetMath.Status status) {
        return switch (status) {
            case NORMAL -> "정상";
            case REDUCED -> "절약";
            case EXHAUSTED -> "소진";
        };
    }

    private String statusLine() {
        var b = plugin.budget();
        if (b.loadError() != null) {
            return "대기 · 사용량을 불러오지 못해 AI 꺼짐 (콘솔 확인)";
        }
        if (!b.isAiEnabled()) {
            return "꺼짐 (고정 대사)";
        }
        if (!plugin.settings().budgetLimitEnabled) {
            return String.format("AI 켜짐 · 오늘 $%.2f (금액 한도 없음) · 대화 중 %d명", b.spentToday(), plugin.dialogue().sessions().size());
        }
        return String.format("AI 켜짐 · 오늘 $%.2f / $%.2f (%s) · 대화 중 %d명", b.spentToday(), b.allowance(),
                statusName(b.status()), plugin.dialogue().sessions().size());
    }

    // ================================================================ 일반 유저

    private int talk(CommandContext<CommandSourceStack> ctx, String raw) throws CommandSyntaxException {
        Player p = self(ctx);
        String text = raw == null ? "" : raw.trim();
        DialogueSession s = plugin.dialogue().session(p.getUniqueId());
        if (s != null) {
            if (!text.isEmpty()) {
                plugin.dialogue().input(p, text, null);
            }
            return 1;
        }
        NPC npc = plugin.npcs().nearest(p, plugin.settings().talkStartDistance);
        if (npc == null) {
            throw ChacaLink.error("근처에 대화할 수 있는 NPC가 없어요");
        }
        String charId = plugin.npcs().charIdFor(npc.getId());
        CharacterSheet c = plugin.characters().get(charId);
        if (c == null) {
            plugin.getLogger().warning("[ChacaNPC] NPC #" + npc.getId() + "에 연결된 캐릭터 파일이 없습니다: " + charId);
            throw ChacaLink.error("지금은 이 NPC와 대화할 수 없어요");
        }
        int npcId = npc.getId();
        if (plugin.link().available()) {
            // 우클릭과 같은 우선순위: 열 수 있는 고정(스토리) 대화가 있으면 그쪽, NONE 일 때만 AI
            plugin.link().openStory(p, npcId, npc.getEntity()).whenComplete((res, ex) -> {
                if (ex == null && "NONE".equals(res) && p.isOnline()) {
                    plugin.sync(() -> openAndSay(p, c, npcId, text));
                }
            });
        } else if (!Bukkit.getPluginManager().isPluginEnabled("MagicCodexBridge")) {
            openAndSay(p, c, npcId, text);
        } else {
            throw ChacaLink.error("지금은 이 NPC와 대화할 수 없어요");
        }
        return 1;
    }

    private void openAndSay(Player p, CharacterSheet c, int npcId, String text) {
        plugin.dialogue().open(p, c, npcId, false);
        if (!text.isEmpty() && plugin.dialogue().session(p.getUniqueId()) != null) {
            plugin.dialogue().input(p, text, null);
        }
    }

    private int nickname(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        Player p = self(ctx);
        if (name != null) {
            if (name.isEmpty()) {
                throw ChacaLink.error("NPC가 부를 호칭을 적어 주세요");
            }
            if (name.codePointCount(0, name.length()) > NICK_MAX) {
                throw ChacaLink.error("호칭은 " + NICK_MAX + "글자까지 할 수 있어요");
            }
            TextFilter filter = plugin.content().filter();
            if (!filter.isClean(name)) {
                throw ChacaLink.error("그 호칭은 쓸 수 없어요");
            }
        }
        String pid = p.getUniqueId().toString();
        db(p, () -> {
            plugin.storage().setNickname(pid, "*", name);
            return null;
        }, "호칭을 저장하지 못했어요", v -> ChacaLink.ok(p, name == null
                ? "이제 NPC들이 원래 이름으로 불러요"
                : "이제 NPC들이 '" + name + "'(이)라고 불러요 (다음 대화부터)"));
        return 1;
    }

    private int quest(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException {
        plugin.dialogue().answerQuest(self(ctx), ctx.getArgument("번호", String.class), accept);
        return 1;
    }
}
