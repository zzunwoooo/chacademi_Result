package school.chacademia.housing;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.SchoolProtocol;

/**
 * 명령어 허브(MagicCodexBridge)에 붙이는 하우징 명령.
 * <ul>
 *   <li>/하우징관리 문 &lt;등록 [기숙사|모두]|삭제|목록&gt; · 가구목록</li>
 *   <li>/유저관리 &lt;닉네임&gt; 방 … · 가구 …</li>
 *   <li>/테스트 하우징 내방</li>
 *   <li>/방구경 &lt;닉네임&gt; (일반 유저)</li>
 *   <li>/하우징랭킹 (일반 유저, #51)</li>
 * </ul>
 */
final class HousingCommands {
    private static final String CONFIRM = "확인";
    private static final String ALL = "전체";
    private static final String PREFIX = "chacademi.housing.furniture.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final UUID CONSOLE = new UUID(0, 0);

    private final HousingPlugin plugin;
    HousingCommands(HousingPlugin plugin) { this.plugin = plugin; }

    void register() {
        if (!ChacaLink.ready()) { plugin.getLogger().warning("MagicCodexBridge 명령어 허브가 없어 하우징 명령을 등록하지 않았어요."); return; }
        ChacaLink.branch(plugin, ChacaLink.하우징관리, doorBranch(), "기숙사실 문 등록·삭제·목록 (바라보는 블록)", "문 목록");
        ChacaLink.branch(plugin, ChacaLink.하우징관리, Commands.literal("가구목록").executes(ctx -> { furnitureList(ctx.getSource().getSender()); return 1; }),
                "가구 ID·이름·최대 개수", "가구목록");
        // #51 (2026-10-25): 누적 방문 순위 (서버 집계·조회만, 유저 화면은 레퍼런스 나온 뒤)
        ChacaLink.branch(plugin, ChacaLink.하우징관리, Commands.literal("방문순위")
                        .executes(ctx -> { visitRanking(ctx.getSource().getSender(), 10); return 1; })
                        .then(Commands.argument("개수", IntegerArgumentType.integer(1, 100)).executes(ctx -> { visitRanking(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "개수")); return 1; })),
                "누적 방문 많은 방 순위 (기본 10개)", "방문순위 10");
        ChacaLink.branch(plugin, ChacaLink.유저관리, roomBranch(), "방 크기·구경·배치·방명록·좋아요", "방 구경");
        ChacaLink.branch(plugin, ChacaLink.유저관리, furnitureBranch(), "가구 보유 보기·주기·회수", "가구 보기");
        ChacaLink.branch(plugin, ChacaLink.테스트, Commands.literal("하우징").then(Commands.literal("내방").executes(ctx -> {
            Player p = player(ctx);
            plugin.rooms().open(p, p.getUniqueId(), true);
            return 1;
        })), "어디서나 내 방 꾸미기 열기", "하우징 내방");
        ChacaLink.playerCommand(plugin, Commands.literal("방구경").then(Commands.argument("닉네임", ChacaLink.player()).executes(ctx -> {
            Player p = player(ctx);
            // owner rule (2026-10-19, A05 2026-10-21): only friends may visit — visit-command never skips the friend check; own room · OP always ok
            String input = ctx.getArgument("닉네임", String.class);
            find(input, id -> {
                if (id == null) { deny(p, "'" + input + "' 학생의 방을 찾을 수 없어요"); return; }
                if (id.equals(p.getUniqueId()) || p.isOp()) { if (p.isOnline()) plugin.rooms().open(p, id, false); return; }
                plugin.store().isFriend(p.getUniqueId(), id, friend -> {
                    if (!p.isOnline()) return;
                    if (Boolean.TRUE.equals(friend)) plugin.rooms().open(p, id, false);
                    else if (friend == null) {
                        // the friend table could not be read (SQLite housing.db has none: housing must share MagicCodexBridge's MariaDB)
                        plugin.getLogger().warning("방구경 친구 확인 실패: 친구 정보를 읽지 못했어요 (하우징 database.properties 가 MagicCodexBridge 와 같은 MariaDB인지 확인)");
                        deny(p, "지금은 친구 정보를 확인할 수 없어요. 잠시 후 다시 시도해 주세요");
                    }
                    else deny(p, "다른 학생의 방은 친구가 되면 구경할 수 있어요");
                });
            });
            return 1;
        })), "다른 학생의 방 구경하기", List.of());
        // #51 (2026-10-25): 하우징 랭킹 창 (일일 방문·누적 방문·하트). 화면은 차카데미 모드.
        ChacaLink.playerCommand(plugin, Commands.literal("하우징랭킹").executes(ctx -> {
            Player p = player(ctx);
            if (plugin.rank() == null || !plugin.rank().open(p)) deny(p, "하우징 랭킹은 차카데미 클라이언트에서만 볼 수 있어요");
            return 1;
        }), "하우징 랭킹 보기 (일일 방문·누적 방문·하트)", List.of("방랭킹"));
        ChacaLink.reload(plugin, "하우징", "config.yml · furniture.yml · doors.yml", plugin::reload);
        ChacaLink.summary(plugin, "방 크기", id -> { Integer s = plugin.rooms().size(id); return s == null ? "" : s + "×" + s; });
    }

    // ------------------------------------------------------------ /하우징관리
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> doorBranch() {
        var houses = new ArrayList<>(SchoolProtocol.HOUSES); houses.add("모두");
        return Commands.literal("문")
                .then(Commands.literal("등록")
                        .executes(ctx -> addDoor(ctx, "모두"))
                        .then(Commands.argument("기숙사", ChacaLink.word(() -> houses)).executes(ctx -> addDoor(ctx, ctx.getArgument("기숙사", String.class)))))
                .then(Commands.literal("삭제").executes(ctx -> {
                    Player p = player(ctx);
                    var block = target(p);
                    if (!plugin.doors().remove(block)) throw ChacaLink.error("등록된 문이 아니에요");
                    ChacaLink.ok(p, "기숙사실 문 등록을 지웠어요");
                    return 1;
                }))
                .then(Commands.literal("목록").executes(ctx -> {
                    var all = plugin.doors().all();
                    var lines = new ArrayList<String>();
                    lines.add("기숙사실 문 " + all.size() + "개");
                    for (var d : all) lines.add(" - " + d.describe());
                    ChacaLink.lines(ctx.getSource().getSender(), lines);
                    return 1;
                }));
    }

    private int addDoor(CommandContext<CommandSourceStack> ctx, String houseName) throws CommandSyntaxException {
        Player p = player(ctx);
        int house = -1;
        if (!houseName.equals("모두")) {
            house = houseIndex(houseName);
            if (house < 0) throw ChacaLink.error("기숙사는 " + String.join("·", SchoolProtocol.HOUSES) + " 또는 모두");
        }
        var block = target(p);
        plugin.doors().add(block, house);
        ChacaLink.ok(p, "기숙사실 문으로 등록했어요 · " + (house < 0 ? "모든 기숙사" : SchoolProtocol.HOUSES.get(house)));
        return 1;
    }

    private static int houseIndex(String name) {
        int i = SchoolProtocol.HOUSES.indexOf(name);
        if (i >= 0) return i;
        return Rooms.HOUSE_PERMISSIONS.indexOf(name.toLowerCase(Locale.ROOT)); // 내부 ID 값(arkeon 등)도 받음
    }

    private static org.bukkit.block.Block target(Player p) throws CommandSyntaxException {
        var block = p.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) throw ChacaLink.error("6칸 안의 블록을 바라봐 주세요");
        return block;
    }

    private void visitRanking(CommandSender s, int limit) {
        plugin.store().topVisited(limit, rows -> {
            if (rows == null) { dbFail(s, "방문 순위를 읽지 못했어요"); return; }
            var lines = new ArrayList<String>();
            lines.add("방 누적 방문 순위 (상위 " + rows.size() + "개 · " + (plugin.settings().visitOnceEver() ? "방문자별 한 번" : "방문자별 하루 한 번") + " 집계)");
            if (rows.isEmpty()) lines.add(" 아직 방문 기록이 없어요");
            for (int i = 0; i < rows.size(); i++) { var r = rows.get(i); lines.add(" " + (i + 1) + ". " + r.name() + " · 방문 " + r.visits() + " · 좋아요 " + r.likes()); }
            ChacaLink.lines(s, lines);
        });
    }

    private void furnitureList(CommandSender s) {
        var lines = new ArrayList<String>();
        lines.add("가구 " + plugin.catalog().all().size() + "개 (furniture.yml)");
        for (var d : plugin.catalog().all()) lines.add(" - " + d.name() + " (" + d.id() + ") · " + category(d) + " · 최대 " + d.max() + "개");
        ChacaLink.lines(s, lines);
    }

    private static String category(Def d) {
        return switch (d.category()) {
            case HousingRules.CAT_FURNITURE -> "가구 " + d.w() + "x" + d.d();
            case HousingRules.CAT_PROP -> "소품 " + d.w() + "x" + d.d();
            case HousingRules.CAT_WALL -> "벽걸이 " + d.w() + "x" + d.wallCells();
            default -> d.sub() == HousingRules.SURFACE_WALLPAPER ? "벽지" : "바닥재";
        };
    }

    // ------------------------------------------------------------ /유저관리 <닉네임> 방
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> roomBranch() {
        return Commands.literal("방")
                .then(Commands.literal("크기").then(Commands.argument("값", IntegerArgumentType.integer(HousingRules.MIN_SIZE, HousingRules.MAX_SIZE)).executes(ctx -> {
                    UUID id = ChacaLink.target(ctx);
                    int size = IntegerArgumentType.getInteger(ctx, "값");
                    CommandSender s = ctx.getSource().getSender();
                    plugin.store().setSize(id, size, plugin.catalog()::get, plugin.catalog().everything(), ok -> {
                        if (ok == null) { dbFail(s, "방 크기를 바꾸지 못했어요"); return; }
                        if (!ok) { deny(s, noRoom(id)); return; }
                        plugin.rooms().size(id, size);
                        plugin.rooms().refresh(id);
                        ChacaLink.ok(s, ChacaLink.name(id) + " 님의 방 크기를 " + size + "×" + size + "(으)로 설정했어요");
                    });
                    return 1;
                })))
                // A03 (2026-10-21): 방 확장 결제 기록 · 결과를 모르는(uncertain) 거래 처리
                .then(Commands.literal("확장기록").executes(this::expandHistory))
                .then(Commands.literal("확장처리").then(Commands.argument("번호", IntegerArgumentType.integer(1, 50))
                        .then(Commands.literal("환불").executes(ctx -> { throw confirm(); }).then(Commands.literal(CONFIRM).executes(ctx -> resolveExpand(ctx, true))))
                        .then(Commands.literal("완료").executes(ctx -> { throw confirm(); }).then(Commands.literal(CONFIRM).executes(ctx -> resolveExpand(ctx, false))))))
                .then(Commands.literal("구경").executes(ctx -> {
                    UUID id = ChacaLink.target(ctx);
                    Player p = player(ctx);
                    plugin.rooms().open(p, id, false);
                    return 1;
                }))
                .then(Commands.literal("배치초기화")
                        .executes(ctx -> { throw confirm(); })
                        .then(Commands.literal(CONFIRM).executes(ctx -> resetLayouts(ctx, -1)))
                        .then(Commands.argument("슬롯", IntegerArgumentType.integer(1, HousingRules.SLOTS))
                                .executes(ctx -> { throw confirm(); })
                                .then(Commands.literal(CONFIRM).executes(ctx -> resetLayouts(ctx, IntegerArgumentType.getInteger(ctx, "슬롯") - 1)))))
                .then(Commands.literal("방명록")
                        .then(Commands.literal("보기").executes(this::guestbook))
                        .then(Commands.literal("삭제").then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx -> {
                            UUID id = ChacaLink.target(ctx);
                            long entry = LongArgumentType.getLong(ctx, "번호");
                            CommandSender s = ctx.getSource().getSender();
                            UUID requester = s instanceof Player p ? p.getUniqueId() : CONSOLE;
                            plugin.store().deleteEntry(id, entry, requester, true, why -> {
                                if (why != null) { deny(s, why.endsWith(".") ? why.substring(0, why.length() - 1) : why); return; }
                                plugin.rooms().refresh(id);
                                ChacaLink.ok(s, ChacaLink.name(id) + " 님의 방명록 " + entry + "번 글을 지웠어요");
                            });
                            return 1;
                        }))))
                .then(Commands.literal("좋아요초기화")
                        .executes(ctx -> { throw confirm(); })
                        .then(Commands.literal(CONFIRM).executes(ctx -> {
                            UUID id = ChacaLink.target(ctx);
                            CommandSender s = ctx.getSource().getSender();
                            plugin.store().resetLikes(id, n -> {
                                if (n == null) { dbFail(s, "좋아요를 초기화하지 못했어요"); return; }
                                if (n < 0) { deny(s, noRoom(id)); return; }
                                plugin.rooms().refresh(id);
                                ChacaLink.ok(s, ChacaLink.name(id) + " 님의 방 좋아요 " + n + "개를 초기화했어요");
                            });
                            return 1;
                        })));
    }

    /** 관리자가 방금 본 확장 거래 (관리자 이름|대상 → 거래 ID). */
    private final Map<String, List<String>> expandListed = new HashMap<>();

    private int expandHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = ctx.getSource().getSender();
        plugin.store().expandList(id, 20, rows -> {
            if (rows == null) { dbFail(s, "방 확장 기록을 읽지 못했어요"); return; }
            var ids = new ArrayList<String>();
            var lines = new ArrayList<String>();
            lines.add(ChacaLink.name(id) + " 님의 방 확장 결제 기록 (최근 " + rows.size() + "건)");
            for (var t : rows) {
                ids.add(t.id());
                lines.add(" " + ids.size() + ". " + DATE.format(Instant.ofEpochMilli(t.created())) + " · " + t.from() + "→" + t.to() + " · " + String.format("%,d", t.cost())
                        + " · " + stateLabel(t.state()) + " · " + t.note() + " · 서버 " + t.origin() + " · 거래 " + t.id().substring(0, 8));
            }
            if (rows.isEmpty()) lines.add(" 기록이 없어요");
            else lines.add(" 확인 필요(uncertain)만 처리: 방 확장처리 <번호> 환불 확인 (돈 돌려주기) | 완료 확인 (확인 끝, 돈 이동 없음)");
            expandListed.put(s.getName() + "|" + id, ids);
            ChacaLink.lines(s, lines);
        });
        return 1;
    }

    private static String stateLabel(String state) {
        return switch (state) {
            case ExpandLedger.DONE -> "끝남"; case ExpandLedger.CANCELLED -> "취소(돈 안 빠짐)"; case ExpandLedger.UNCERTAIN -> "확인 필요";
            case ExpandLedger.PREPARED -> "출금 전"; case ExpandLedger.WITHDRAWN -> "출금됨"; case ExpandLedger.APPLIED -> "넓힘"; case ExpandLedger.REFUND_PENDING -> "환불 중";
            default -> state;
        };
    }

    private int resolveExpand(CommandContext<CommandSourceStack> ctx, boolean refund) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = ctx.getSource().getSender();
        int number = IntegerArgumentType.getInteger(ctx, "번호");
        var ids = expandListed.get(s.getName() + "|" + id);
        if (ids == null) throw ChacaLink.error("먼저 방 확장기록 으로 번호를 확인해 주세요");
        if (number > ids.size()) throw ChacaLink.error("그 번호의 거래가 없어요 (방 확장기록 으로 확인)");
        String tx = ids.get(number - 1), admin = s.getName();
        var log = plugin.getLogger();
        plugin.store().expandGet(tx, found -> {
            if (found == null) { dbFail(s, "거래를 읽지 못했어요"); return; }
            var t = found.orElse(null);
            if (t == null || !ExpandLedger.UNCERTAIN.equals(t.state())) { deny(s, "확인 필요(uncertain) 상태인 거래만 처리할 수 있어요"); return; }
            if (!refund) {
                plugin.store().expandMove(tx, ExpandLedger.UNCERTAIN, ExpandLedger.DONE, "관리자 완료 " + admin, ok -> {
                    log.warning("HOUSING EXPAND ADMIN resolve tx=" + tx + " admin=" + admin + " player=" + id + " action=완료 ok=" + ok);
                    if (Boolean.TRUE.equals(ok)) ChacaLink.ok(s, "거래 " + tx.substring(0, 8) + " 를 완료로 닫았어요 (돈 이동 없음)");
                    else dbFail(s, "거래 상태를 바꾸지 못했어요 (이미 처리됐을 수 있어요)");
                });
                return;
            }
            // 환불: 먼저 refund_pending 으로 옮겨 두 번 환불되지 않게 한 뒤 입금한다.
            plugin.store().expandMove(tx, ExpandLedger.UNCERTAIN, ExpandLedger.REFUND_PENDING, "관리자 환불 중 " + admin, moved -> {
                if (!Boolean.TRUE.equals(moved)) { dbFail(s, "거래 상태를 바꾸지 못했어요 (이미 처리됐을 수 있어요)"); return; }
                var pay = Economy.depositResult(Bukkit.getOfflinePlayer(id), t.cost());
                log.warning("HOUSING EXPAND ADMIN resolve tx=" + tx + " admin=" + admin + " player=" + id + " action=환불 cost=" + t.cost() + " deposit=" + pay);
                boolean paid = pay == Economy.Pay.OK;
                plugin.store().expandMove(tx, ExpandLedger.REFUND_PENDING, paid ? ExpandLedger.DONE : ExpandLedger.UNCERTAIN, paid ? "관리자 환불 " + admin : "관리자 환불 실패(" + pay + ")", null);
                if (paid) ChacaLink.ok(s, ChacaLink.name(id) + " 님에게 " + String.format("%,d", t.cost()) + " 를 돌려줬어요 (거래 " + tx.substring(0, 8) + ")");
                else dbFail(s, "환불하지 못했어요 (" + (pay == Economy.Pay.REFUSED ? "거절됨" : "결과 모름 — 잔액을 확인한 뒤 처리해 주세요") + ")");
            });
        });
        return 1;
    }

    private int resetLayouts(CommandContext<CommandSourceStack> ctx, int slot) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = ctx.getSource().getSender();
        plugin.store().resetLayouts(id, slot, n -> {
            if (n == null) { dbFail(s, "배치를 초기화하지 못했어요"); return; }
            if (n < 0) { deny(s, noRoom(id)); return; }
            plugin.rooms().refresh(id);
            ChacaLink.ok(s, ChacaLink.name(id) + " 님의 방 " + (slot < 0 ? "배치 전체" : "레이아웃 " + (slot + 1)) + "를 초기화했어요");
        });
        return 1;
    }

    private int guestbook(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = ctx.getSource().getSender();
        plugin.store().row(id, row -> {
            if (row == null) { deny(s, noRoom(id)); return; }
            plugin.store().guestbook(id, HousingProtocol.MAX_GUESTBOOK, entries -> {
                if (entries == null) { dbFail(s, "방명록을 읽지 못했어요"); return; }
                var lines = new ArrayList<String>();
                lines.add(ChacaLink.name(id) + " 님의 방 · " + row.size() + "×" + row.size() + " · 좋아요 " + row.likes() + " · 방문 " + row.visits());
                if (entries.isEmpty()) lines.add(" 방명록이 비어 있어요");
                else {
                    lines.add(" 방명록 최근 " + entries.size() + "개 (번호로 삭제)");
                    for (var e : entries) lines.add(" " + e.id() + "번 · " + e.author().name() + " · " + DATE.format(Instant.ofEpochMilli(e.time())) + " · " + e.text());
                }
                ChacaLink.lines(s, lines);
            });
        });
        return 1;
    }

    // ------------------------------------------------------------ /유저관리 <닉네임> 가구
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> furnitureBranch() {
        return Commands.literal("가구")
                .then(Commands.literal("보기").executes(ctx -> {
                    UUID id = ChacaLink.target(ctx);
                    CommandSender s = ctx.getSource().getSender();
                    after(s, ChacaLink.permissions(id, PREFIX), "가구 보유를 읽지 못했어요", nodes -> {
                        var lines = new ArrayList<String>();
                        boolean everything = nodes.contains(PREFIX + "*");
                        var owned = new ArrayList<String>();
                        var unknown = new ArrayList<String>();
                        for (String node : nodes) {
                            String fid = node.substring(PREFIX.length());
                            if (fid.equals("*")) continue;
                            Def d = plugin.catalog().get(fid);
                            if (d == null) unknown.add(fid); else owned.add(d.name() + "(" + fid + ")");
                        }
                        lines.add(ChacaLink.name(id) + " 님의 가구 " + (everything ? "전체 보유" : owned.size() + "개 / " + plugin.catalog().all().size() + "개"));
                        if (!owned.isEmpty()) lines.add(" " + String.join(", ", owned));
                        if (!unknown.isEmpty()) lines.add(" 목록에 없는 가구: " + String.join(", ", unknown));
                        if (!everything && owned.isEmpty() && unknown.isEmpty()) lines.add(" 가진 가구가 없어요");
                        ChacaLink.lines(s, lines);
                    });
                    return 1;
                }))
                .then(Commands.literal("주기").then(Commands.argument("가구", ChacaLink.word(this::furnitureSuggestions)).executes(ctx -> grant(ctx, true))))
                .then(Commands.literal("회수").then(Commands.argument("가구", ChacaLink.word(this::furnitureSuggestions)).executes(ctx -> grant(ctx, false))));
    }

    private Collection<String> furnitureSuggestions() {
        var out = new LinkedHashSet<String>();
        out.add(ALL);
        for (Def d : plugin.catalog().all()) out.add(compact(d.name()));
        for (Def d : plugin.catalog().all()) out.add(d.id());
        return out;
    }

    private static String compact(String name) { return name.replace(" ", ""); }

    private Def furniture(String input) throws CommandSyntaxException {
        Def d = plugin.catalog().get(input.toLowerCase(Locale.ROOT));
        if (d != null) return d;
        for (Def def : plugin.catalog().all()) if (compact(def.name()).equals(compact(input))) return def;
        throw ChacaLink.error("'" + input + "' 가구를 찾을 수 없어요");
    }

    private int grant(CommandContext<CommandSourceStack> ctx, boolean give) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender s = ctx.getSource().getSender();
        String input = ctx.getArgument("가구", String.class);
        String who = ChacaLink.name(id);
        if (!input.equals(ALL)) {
            Def d = furniture(input);
            after(s, ChacaLink.permission(id, HousingRules.permission(d.id()), give), "가구 보유를 바꾸지 못했어요",
                    v -> ChacaLink.ok(s, who + (give ? " 님에게 " : " 님에게서 ") + d.name() + " 가구를 " + (give ? "주었어요" : "회수했어요")));
            return 1;
        }
        if (give) {
            var futures = plugin.catalog().all().stream().map(d -> ChacaLink.permission(id, HousingRules.permission(d.id()), true)).toArray(CompletableFuture[]::new);
            int n = futures.length;
            after(s, CompletableFuture.allOf(futures), "가구 보유를 바꾸지 못했어요", v -> ChacaLink.ok(s, who + " 님에게 가구 " + n + "개를 모두 주었어요"));
        } else {
            after(s, ChacaLink.permissions(id, PREFIX).thenCompose(nodes ->
                    CompletableFuture.allOf(nodes.stream().map(node -> ChacaLink.permission(id, node, false)).toArray(CompletableFuture[]::new)).thenApply(v -> nodes.size())),
                    "가구 보유를 바꾸지 못했어요", n -> ChacaLink.ok(s, who + " 님에게서 가구 " + n + "개를 모두 회수했어요"));
        }
        return 1;
    }

    // ------------------------------------------------------------ 공통
    private static CommandSyntaxException confirm() { return ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요"); }

    private static Player player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    private static String noRoom(UUID id) { return ChacaLink.name(id) + " 님의 방이 아직 없어요 (한 번 접속해야 생겨요)"; }

    /** 비동기 작업 뒤 유저가 고칠 수 있는 실패: 알림 카드(+삐빅), 콘솔 로그 없음. */
    private static void deny(CommandSender s, String message) {
        if (s instanceof Player p) ChacaLink.notify(p, "error", "cmd-fail", message, "", 0);
        else s.sendMessage(message);
    }

    /** DB 오류: 원인은 HousingStore 가 이미 콘솔에 남김. */
    private static void dbFail(CommandSender s, String message) {
        ChacaLink.fail(s, message, new IllegalStateException("하우징 DB 작업 실패 (위 콘솔 경고 참고)"));
    }

    /** LuckPerms 작업 결과를 메인 스레드에서 처리. */
    private <T> void after(CommandSender s, CompletableFuture<T> future, String failMessage, Consumer<T> done) {
        future.whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) ChacaLink.fail(s, failMessage, err);
            else done.accept(v);
        }));
    }

    /** 닉네임 → UUID: 허브(닉네임·계정명·UUID) → 하우징 DB(닉네임 테이블·방 이름). 없으면 null. */
    private void find(String input, Consumer<UUID> done) {
        UUID id = null;
        try { id = ChacaLink.find(input); } catch (RuntimeException ignored) { /* 허브 없음 */ }
        if (id != null) { done.accept(id); return; }
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.rooms().person(p).name().equalsIgnoreCase(input)) { done.accept(p.getUniqueId()); return; }
        plugin.store().find(input, done);
    }
}
