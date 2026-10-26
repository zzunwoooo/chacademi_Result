package school.magiccodex.paper;

import static school.magiccodex.paper.CmdKit.*;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import school.magiccodex.protocol.ManaProtocol;

/**
 * 관리자 명령 (2026-10-20 명령어 정리). 모든 변경은 그 상태를 가진 하위 시스템에 맡긴다.
 * <ul>
 *   <li>/유저관리 &lt;닉네임&gt; 스탯 · 마법 · 마법조건 · 퀘스트 · 호감도</li>
 *   <li>/아이템관리 마법봉 · 마력코어</li>
 * </ul>
 * OP 검사는 허브가 대단원 단위로 한다.
 */
final class AdminCommandBridge {
    private static final List<String> SPELL_ACTIONS = List.of("배우기", "잊기");
    private final MagicCodexBridge plugin;
    private final ManaBridge mana;
    private final EnhancementBridge enhancement;
    private final AppraisalBridge appraisal;
    private final NpcSocialService social;

    AdminCommandBridge(MagicCodexBridge plugin, ManaBridge mana, SchoolBridge school, EnhancementBridge enhancement, AppraisalBridge appraisal, TitleBridge titles, NpcSocialService social) {
        this.plugin = plugin; this.mana = mana; this.enhancement = enhancement; this.appraisal = appraisal; this.social = social;
        register(plugin, "/유저관리 스탯·마법·퀘스트·호감도", () -> {
            ChacaApi.branch(plugin, ChacaApi.유저관리, stats(), "마나·마법 가속 기본값 바꾸기", "스탯 최대마나 설정 500");
            ChacaApi.branch(plugin, ChacaApi.유저관리, spells(), "마법 배우게 하기·잊게 하기", "마법 배우기 ");
            ChacaApi.branch(plugin, ChacaApi.유저관리, conditions(), "마법 발견 수동 조건 보기·주기·회수·초기화", "마법조건 보기 ");
            ChacaApi.branch(plugin, ChacaApi.유저관리, quests(), "메인·일일 퀘스트 기록 보기·주기·진행·완료·초기화", "퀘스트 메인 보기");
            ChacaApi.branch(plugin, ChacaApi.유저관리, affinity(), "NPC 호감도·하트·호칭", "호감도 ");
        });
        register(plugin, "/아이템관리 마법봉·마력코어", () -> {
            ChacaApi.branch(plugin, ChacaApi.아이템관리, wand(), "마법봉 주기(등급별)·만들기·등록·원본설정", "마법봉 주기 고급");
            ChacaApi.branch(plugin, ChacaApi.아이템관리, core(), "마력코어 주기·만들기", "마력코어 주기");
        });
        var operator = new OperatorCommands(plugin); // #95 게임모드·찾아가기·불러오기·좌표이동·비행·회복
        // #95 한글 편의 명령 (/겜모 /텔포 /소환 /공지 /도움 /뒤로 /스폰 /쓰레기통 /메모리). 실패해도 나머지는 계속.
        try { new ConvenienceCommands(plugin, operator); } catch (Exception | LinkageError error) { plugin.getLogger().severe("편의 명령 초기화 실패 (convenience-commands.yml 확인): " + error); }
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 스탯

    private LiteralArgumentBuilder<CommandSourceStack> stats() {
        var root = literal("스탯");
        for (String stat : AdminCommandRules.STATS) {
            var node = literal(stat);
            for (String change : AdminCommandRules.CHANGES)
                node.then(literal(change).then(argument("값", DoubleArgumentType.doubleArg(0, 1_000_000)).executes(run(ctx -> stat(ctx, stat, change)))));
            root.then(node);
        }
        return root;
    }

    private void stat(CommandContext<CommandSourceStack> ctx, String stat, String change) throws CommandSyntaxException {
        Player target = ChacaApi.targetOnline(ctx);
        if (!plugin.playerStateReady(target)) throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        double value = DoubleArgumentType.getDouble(ctx, "값");
        plugin.refreshEquipment(target);
        UUID id = target.getUniqueId();
        ManaAccount account = mana.mana.account(id);
        double before = switch (stat) { case "현재마나" -> account.snapshot().current(); case "최대마나" -> account.baseMaximum; case "마나회복" -> account.baseRegen; default -> account.baseHaste; };
        double next = user(() -> AdminCommandRules.changed(before, change, value, 1_000_000));
        switch (stat) {
            case "현재마나" -> mana.mana.setCurrent(id, next);
            case "최대마나" -> mana.mana.setBaseMaximum(id, next);
            case "마나회복" -> mana.mana.setBaseRegeneration(id, next);
            default -> mana.mana.setBaseHaste(id, next);
        }
        mana.mana.save(target); plugin.savePlayerState(target);
        var snapshot = mana.mana.account(id).snapshot();
        if (target.getListeningPluginChannels().contains(ManaProtocol.RESPONSE))
            target.sendPluginMessage(plugin, ManaProtocol.RESPONSE, ManaProtocol.encode(new ManaProtocol.Response(0, ManaProtocol.SNAPSHOT, 0, snapshot)));
        double actual = stat.equals("현재마나") ? snapshot.current() : next;
        ok(sender(ctx), plugin.names().name(target) + " 님 " + stat + " " + AdminCommandRules.number(actual) + (stat.equals("현재마나") ? "" : " (장비·버프 제외 기본값)"));
    }

    // ------------------------------------------------------------------ 마법 · 마법조건 (MagicDiscovery)

    private LiteralArgumentBuilder<CommandSourceStack> spells() {
        var root = literal("마법");
        for (String action : SPELL_ACTIONS)
            root.then(literal(action).then(word("마법", () -> AdminCommandRules.catalogChoices(DiscoveryLink.registeredSpells())).executes(run(ctx -> {
                UUID target = ChacaApi.target(ctx);
                Map<String, String> catalog = DiscoveryLink.registeredSpells();
                if (catalog.isEmpty()) throw ChacaApi.error("마법 발견 플러그인이 준비되지 않았어요");
                String id = pick(catalog, text(ctx, "마법"), "마법");
                var sender = sender(ctx);
                String who = CmdKit.name(target);
                DiscoveryLink.adminSetLearned(target, id, action.equals("배우기"), error -> {
                    if (error == null) ok(sender, who + " 님이 " + catalog.get(id) + " 마법을 " + (action.equals("배우기") ? "배웠어요" : "잊었어요"));
                    else userFail(sender, error);
                });
            }))));
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> conditions() {
        var root = literal("마법조건");
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("보기", "조건조회"); actions.put("주기", "조건지급"); actions.put("회수", "조건회수"); actions.put("초기화", "조건초기화");
        actions.forEach((verb, legacy) -> {
            var spell = word("마법", () -> AdminCommandRules.catalogChoices(DiscoveryLink.manualConditions()));
            CmdKit.Body body = ctx -> {
                UUID target = ChacaApi.target(ctx);
                Map<String, String> catalog = DiscoveryLink.manualConditions();
                if (catalog.isEmpty()) throw ChacaApi.error("마법 발견 플러그인이 준비되지 않았어요");
                String id = pick(catalog, text(ctx, "마법"), "수동 조건 마법");
                var sender = sender(ctx);
                DiscoveryLink.adminCondition(target, id, legacy, text -> {
                    if (verb.equals("보기")) lines(sender, List.of("── " + CmdKit.name(target) + " · " + catalog.get(id) + " 수동 조건 ──", text));
                    else if (text != null && text.startsWith(legacy + " 완료")) ok(sender, CmdKit.name(target) + " 님 " + catalog.get(id) + " 조건 " + verb + " 완료");
                    else userFail(sender, text == null ? "조건을 처리하지 못했어요" : text);
                });
            };
            if (verb.equals("초기화")) root.then(literal(verb).then(confirm(spell, body)));
            else root.then(literal(verb).then(spell.executes(run(body))));
        });
        return root;
    }

    // ------------------------------------------------------------------ 퀘스트

    private LiteralArgumentBuilder<CommandSourceStack> quests() {
        var root = literal("퀘스트");
        for (String category : List.of("메인", "일일")) {
            var node = literal(category);
            for (String operation : JournalBridge.OPERATIONS) {
                var op = literal(operation);
                var quest = wordIn("퀘스트", ctx -> {
                    UUID id = targetOf(ctx);
                    Player target = id == null ? null : org.bukkit.Bukkit.getPlayer(id);
                    return target == null ? List.of() : plugin.journalBridge().choices(target, category, operation);
                });
                CmdKit.Body body = ctx -> plugin.journalBridge().categoryCommand(sender(ctx), ChacaApi.targetOnline(ctx), category, operation, text(ctx, "퀘스트"));
                if (operation.equals("보기")) op.executes(run(ctx -> plugin.journalBridge().categoryCommand(sender(ctx), ChacaApi.targetOnline(ctx), category, operation, "")));
                if (operation.equals("초기화")) op.then(confirm(quest, body));
                else op.then(quest.executes(run(body)));
                node.then(op);
            }
            root.then(node);
        }
        return root;
    }

    // ------------------------------------------------------------------ 호감도

    private LiteralArgumentBuilder<CommandSourceStack> affinity() {
        var npc = word("NPC", () -> social == null ? List.of() : AdminCommandRules.catalogChoices(social.registeredNpcs()));
        npc.then(literal("보기").executes(run(ctx -> affinityChange(ctx, "보기", 0))));
        for (String change : List.of("설정", "추가", "빼기"))
            npc.then(literal(change).then(argument("값", IntegerArgumentType.integer(0, 100)).executes(run(ctx -> affinityChange(ctx, change, IntegerArgumentType.getInteger(ctx, "값"))))));
        npc.then(literal("하트")
                .then(literal("보기").executes(run(ctx -> affinityChange(ctx, "보기", 0))))
                .then(literal("설정").then(argument("단계", IntegerArgumentType.integer(0, 4)).executes(run(this::heart)))));
        npc.then(literal("호칭")
                .then(literal("보기").executes(run(ctx -> affinityChange(ctx, "보기", 0))))
                .then(literal("설정").then(word("호칭", List::of).executes(run(ctx -> nickname(ctx, text(ctx, "호칭"))))))
                .then(literal("초기화").executes(run(ctx -> nickname(ctx, "")))));
        // A08 (2026-10-21): 끝나지 않은 NPC 선물 보기·처리 (옛 기록·다른 서버 기록 포함)
        return literal("호감도").then(npc)
                .then(literal("선물기록").executes(run(this::giftPending)))
                .then(literal("선물처리").then(argument("번호", IntegerArgumentType.integer(1, 100))
                        .then(confirm(literal("확정"), ctx -> resolveGift(ctx, true)))
                        .then(confirm(literal("취소"), ctx -> resolveGift(ctx, false)))));
    }

    /** 관리자가 방금 본 선물 목록 (관리자 이름|대상 → 토큰). */
    private final Map<String, List<String>> giftListed = new HashMap<>();

    private void giftPending(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (social == null) throw ChacaApi.error("NPC 호감도 기능이 준비되지 않았어요");
        UUID target = ChacaApi.target(ctx);
        var sender = sender(ctx);
        String who = CmdKit.name(target);
        social.adminPendingGifts(target).whenComplete((rows, error) -> {
            if (error != null) { fail(sender, "선물 기록을 읽지 못했어요", error); return; }
            var out = new ArrayList<String>();
            var tokens = new ArrayList<String>();
            out.add("── " + who + " 끝나지 않은 NPC 선물 " + rows.size() + "건 ──");
            for (var g : rows) {
                tokens.add(g.token());
                String where = g.origin().isEmpty() ? "옛 기록(서버 정보 없음)" : g.origin().equals(social.origin()) ? "이 서버" : "다른 서버";
                out.add(tokens.size() + ". " + g.npc() + " · +" + g.delta() + " · " + QuestBridge.timeText(g.created()) + " · " + where + " · " + g.token().substring(0, 8));
            }
            if (rows.isEmpty()) out.add("(없음)");
            else out.add("시작한 서버에 유저가 접속하면 영수증으로 자동 처리돼요. 옛 기록은 직접: 선물처리 <번호> 확정(아이템 빠짐, 점수 반영) | 취소(아이템 그대로, 횟수 돌려줌) 확인");
            giftListed.put(sender.getName() + "|" + target, List.copyOf(tokens));
            lines(sender, out);
        });
    }

    private void resolveGift(CommandContext<CommandSourceStack> ctx, boolean commit) throws CommandSyntaxException {
        if (social == null) throw ChacaApi.error("NPC 호감도 기능이 준비되지 않았어요");
        UUID target = ChacaApi.target(ctx);
        var sender = sender(ctx);
        int number = IntegerArgumentType.getInteger(ctx, "번호");
        var tokens = giftListed.get(sender.getName() + "|" + target);
        if (tokens == null) throw ChacaApi.error("먼저 호감도 선물기록 으로 번호를 확인해 주세요");
        if (number > tokens.size()) throw ChacaApi.error("그 번호의 선물이 없어요 (선물기록 으로 확인)");
        String token = tokens.get(number - 1), admin = sender.getName();
        social.adminResolveGift(token, commit).whenComplete((done, error) -> {
            plugin.getLogger().warning("NPC GIFT ADMIN admin=" + admin + " player=" + target + " token=" + token + " action=" + (commit ? "확정" : "취소") + " ok=" + done + (error == null ? "" : " error=" + error));
            if (error != null) { fail(sender, "선물을 처리하지 못했어요", error); return; }
            giftListed.remove(admin + "|" + target);
            if (Boolean.TRUE.equals(done)) ok(sender, "선물 " + token.substring(0, 8) + " 를 " + (commit ? "확정했어요 (점수 반영)" : "취소했어요 (하루 횟수 돌려줌)"));
            else userFail(sender, "이미 처리된 선물이에요. 선물기록 으로 다시 확인해 주세요");
        });
    }

    private String npc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (social == null) throw ChacaApi.error("NPC 호감도 기능이 준비되지 않았어요");
        Map<String, String> npcs = social.registeredNpcs();
        if (npcs.isEmpty()) throw ChacaApi.error("등록된 NPC가 없어요 (ChacaNPC 상태 확인)");
        return pick(npcs, text(ctx, "NPC"), "NPC");
    }

    private static List<String> affinityLines(String who, String npc, AffinityStore.Row row) {
        return List.of("── " + who + " · " + npc + " 호감도 ──",
                "점수 " + row.score() + " / 상한 " + AffinityStore.gate(row.heart()),
                "하트 " + row.heart() + "단계",
                "호칭 " + (row.nickname().isEmpty() ? "(없음)" : row.nickname()));
    }

    private void affinityChange(CommandContext<CommandSourceStack> ctx, String action, int value) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx);
        String id = npc(ctx);
        var sender = sender(ctx);
        String who = CmdKit.name(target);
        social.adminChange(target, id, action, value).whenComplete((row, error) -> {
            if (error != null) { fail(sender, "호감도를 처리하지 못했어요", error); return; }
            if (action.equals("보기")) lines(sender, affinityLines(who, id, row));
            else ok(sender, who + " 님 " + id + " 호감도 " + row.score() + " (상한 " + AffinityStore.gate(row.heart()) + ")");
        });
    }

    private void heart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx);
        String id = npc(ctx);
        int level = IntegerArgumentType.getInteger(ctx, "단계");
        var sender = sender(ctx);
        String who = CmdKit.name(target);
        social.adminHeart(target, id, level).whenComplete((row, error) -> {
            if (error != null) fail(sender, "하트 단계를 바꾸지 못했어요", error);
            else ok(sender, who + " 님 " + id + " 하트 " + row.heart() + "단계 (호감도 " + row.score() + ")");
        });
    }

    private void nickname(CommandContext<CommandSourceStack> ctx, String nickname) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx);
        String id = npc(ctx);
        if (nickname.codePointCount(0, nickname.length()) > 8) throw ChacaApi.error("호칭은 8글자까지예요");
        var sender = sender(ctx);
        String who = CmdKit.name(target);
        social.setNickname(target, id, nickname).whenComplete((row, error) -> {
            if (error != null) fail(sender, "호칭을 바꾸지 못했어요", error);
            else ok(sender, who + " 님 " + id + " 호칭 " + (row.nickname().isEmpty() ? "초기화" : "= " + row.nickname()));
        });
    }

    // ------------------------------------------------------------------ /아이템관리 마법봉 · 마력코어

    private static final String[] WAND_STATS = {"지능", "추가마나", "마법가속"};

    private LiteralArgumentBuilder<CommandSourceStack> wand() {
        var give = literal("주기").executes(run(ctx -> giveWand(ctx, self(ctx))))
                .then(player("닉네임").executes(run(ctx -> giveWand(ctx, ChacaApi.resolveOnline(ctx, "닉네임")))));
        // 전투 스펙 2026-10-26: /아이템관리 마법봉 주기 <등급> [닉네임] — 등급·수치는 enhancement.yml wand-grades (명령 등록 시점의 등급 목록).
        for (String grade : enhancement.wandGradeNames())
            give.then(literal(grade).executes(run(ctx -> giveGradeWand(ctx, self(ctx), grade)))
                    .then(player("닉네임").executes(run(ctx -> giveGradeWand(ctx, ChacaApi.resolveOnline(ctx, "닉네임"), grade)))));
        var make = literal("만들기").then(wandStats(run(ctx -> makeWand(ctx, self(ctx))), run(ctx -> makeWand(ctx, ChacaApi.resolveOnline(ctx, "닉네임")))));
        var register = literal("등록").then(wandStats(run(this::registerWand), null));
        var base = literal("원본설정").then(wandStats(run(this::wandBase), null));
        return literal("마법봉").then(give).then(make).then(register).then(base);
    }

    /** &lt;지능&gt; &lt;추가마나&gt; &lt;마법가속&gt; [닉네임] */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> wandStats(com.mojang.brigadier.Command<CommandSourceStack> done, com.mojang.brigadier.Command<CommandSourceStack> withPlayer) {
        var last = argument(WAND_STATS[2], DoubleArgumentType.doubleArg(0, 100_000)).executes(done);
        if (withPlayer != null) last.then(player("닉네임").executes(withPlayer));
        return argument(WAND_STATS[0], DoubleArgumentType.doubleArg(0, 100_000)).then(argument(WAND_STATS[1], DoubleArgumentType.doubleArg(0, 100_000)).then(last));
    }

    private static double[] wandValues(CommandContext<CommandSourceStack> ctx) {
        return new double[]{DoubleArgumentType.getDouble(ctx, WAND_STATS[0]), DoubleArgumentType.getDouble(ctx, WAND_STATS[1]), DoubleArgumentType.getDouble(ctx, WAND_STATS[2])};
    }

    private void deliver(CommandContext<CommandSourceStack> ctx, Player target, List<ItemStack> items, String what) throws CommandSyntaxException {
        if (!plugin.playerStateReady(target)) throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        // 공통 지급 칸 판정 (RewardDelivery, 2026-10-26): 핫바 + 잠금 해제된 지금 쪽 27칸만. 다 들어갈 때만 준다(예전처럼 우편 없음).
        var next = RewardDelivery.copy(target.getInventory().getStorageContents());
        if (!RewardDelivery.place(next, RewardDelivery.usable(plugin, target), items, false).isEmpty()) throw ChacaApi.error(plugin.names().name(target) + " 님 인벤토리에 빈칸이 " + items.size() + "개 필요해요");
        target.getInventory().setStorageContents(next);
        target.saveData(); plugin.savePlayerState(target); plugin.refreshEquipment(target);
        ok(sender(ctx), plugin.names().name(target) + " 님에게 " + what + "을(를) 주었어요");
    }

    private void giveWand(CommandContext<CommandSourceStack> ctx, Player target) throws CommandSyntaxException {
        deliver(ctx, target, List.of(enhancement.apprenticeWand()), "수습 마법봉");
    }

    private void giveGradeWand(CommandContext<CommandSourceStack> ctx, Player target, String grade) throws CommandSyntaxException {
        ItemStack item = user(() -> enhancement.gradeWand(grade));
        deliver(ctx, target, List.of(item), grade + " 마법봉");
    }

    private void makeWand(CommandContext<CommandSourceStack> ctx, Player target) throws CommandSyntaxException {
        double[] v = wandValues(ctx);
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        var meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("마법봉").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        user(() -> { enhancement.register(item, v[0], v[1], v[2]); return null; });
        deliver(ctx, target, List.of(item), "마법봉");
    }

    private void registerWand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        double[] v = wandValues(ctx);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || hand.getAmount() != 1) throw ChacaApi.error("등록할 아이템 한 개를 주 손에 들어 주세요");
        if (enhancement.isWand(hand)) throw ChacaApi.error("이미 마법봉으로 등록된 아이템이에요");
        user(() -> { enhancement.register(hand, v[0], v[1], v[2]); return null; });
        p.saveData(); plugin.refreshEquipment(p);
        ok(sender(ctx), "손에 든 아이템을 마법봉으로 등록했어요");
    }

    private void wandBase(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        double[] v = wandValues(ctx);
        user(() -> { enhancement.setBase(p, v); return null; });
        ok(sender(ctx), "손에 든 마법봉의 강화 전 원본 수치를 정했어요");
    }

    private LiteralArgumentBuilder<CommandSourceStack> core() {
        var give = literal("주기").executes(run(ctx -> giveCores(ctx, self(ctx), 1)))
                .then(player("닉네임").executes(run(ctx -> giveCores(ctx, ChacaApi.resolveOnline(ctx, "닉네임"), 1)))
                        .then(argument("개수", IntegerArgumentType.integer(1, 36)).executes(run(ctx -> giveCores(ctx, ChacaApi.resolveOnline(ctx, "닉네임"), IntegerArgumentType.getInteger(ctx, "개수"))))));
        var tendency = word("성향", () -> List.of("지능", "마나", "마법가속"))
                .executes(run(ctx -> makeCore(ctx, self(ctx))))
                .then(player("닉네임").executes(run(ctx -> makeCore(ctx, ChacaApi.resolveOnline(ctx, "닉네임")))));
        var make = literal("만들기").then(argument("코어수", IntegerArgumentType.integer(1, 10))
                .then(argument("성공률", DoubleArgumentType.doubleArg(0, 100))
                        .then(argument("별잡기보너스", DoubleArgumentType.doubleArg(0, 100))
                                .then(argument("상승배율", DoubleArgumentType.doubleArg(0, 10_000)).then(tendency)))));
        return literal("마력코어").then(give).then(make);
    }

    private void giveCores(CommandContext<CommandSourceStack> ctx, Player target, int count) throws CommandSyntaxException {
        var items = new ArrayList<ItemStack>();
        for (int i = 0; i < count; i++) items.add(appraisal.unappraisedCore());
        deliver(ctx, target, items, "미감정 마력코어 " + count + "개");
    }

    private void makeCore(CommandContext<CommandSourceStack> ctx, Player target) throws CommandSyntaxException {
        int nodes = IntegerArgumentType.getInteger(ctx, "코어수");
        double success = DoubleArgumentType.getDouble(ctx, "성공률"), bonus = DoubleArgumentType.getDouble(ctx, "별잡기보너스"), gain = DoubleArgumentType.getDouble(ctx, "상승배율");
        int tendency = user(() -> AdminCommandRules.affinity(text(ctx, "성향")));
        ItemStack item = user(() -> appraisal.adminCore(nodes, success, bonus, gain, tendency));
        deliver(ctx, target, List.of(item), "마력코어");
    }
}
