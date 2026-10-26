package kr.chacademy.motionplugin.broom;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademy.motionplugin.ChacaLink;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 빗자루 명령 (명령어 허브 MagicCodexBridge 에 붙인다).
 * <pre>
 *   /빗자루 [정보|목록|장착 &lt;빗자루&gt;]                       누구나
 *   /유저관리 &lt;닉네임&gt; 빗자루 보기                             OP
 *   /유저관리 &lt;닉네임&gt; 빗자루 지정 &lt;빗자루&gt; | 해제
 *   /유저관리 &lt;닉네임&gt; 빗자루 숙련도 &lt;0~100&gt;                 (오프라인도 됨)
 *   /유저관리 &lt;닉네임&gt; 빗자루 주기|회수 &lt;빗자루|전체&gt;        보유 = LuckPerms chacademy.broom.own.&lt;ID&gt;
 *   /테스트 빗자루 타기 [닉네임]                              마법 없이 시험 비행 켜기/끄기
 * </pre>
 */
public final class BroomCommand {
    static final String OWN = "chacademy.broom.own.";
    private static final String ARG = "빗자루";

    private final Plugin plugin;
    private final BroomService service;

    public BroomCommand(Plugin plugin, BroomService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 허브에 갈래·유저 명령·요약 줄을 등록한다. 허브가 켜져 있을 때만 부른다. */
    public void register() {
        ChacaLink.playerCommand(plugin, userCommand(), "내 빗자루 보기·목록·장착", List.of());
        ChacaLink.branch(plugin, ChacaLink.유저관리, adminBranch(), "빗자루 보유·장착·숙련도", "빗자루 보기");
        ChacaLink.branch(plugin, ChacaLink.테스트, testBranch(), "마법 없이 시험 비행 켜기/끄기", "빗자루 타기 ");
        ChacaLink.summary(plugin, "빗자루", this::summaryLine);
    }

    // ------------------------------------------------------------------ 이름 ↔ 빗자루

    /** 자동완성용: 한글 이름(띄어쓰기는 _), 그다음 ID. */
    private static List<String> labels(Iterable<BroomType> types) {
        var out = new LinkedHashSet<String>();
        var ids = new ArrayList<String>();
        for (var t : types) {
            if (t.name() != null && !t.name().isBlank() && !t.name().equals(t.id())) out.add(t.name().strip().replace(' ', '_'));
            ids.add(t.id());
        }
        out.addAll(ids);
        return new ArrayList<>(out);
    }

    private BroomType find(String input) {
        if (input == null) return null;
        var byId = service.brooms().get(input.toLowerCase(Locale.ROOT));
        if (byId != null) return byId;
        String key = squash(input);
        for (var t : service.brooms().values()) if (squash(t.name()).equals(key)) return t;
        return null;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private BroomType broomArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String raw = ctx.getArgument(ARG, String.class);
        var type = find(raw);
        if (type == null) throw ChacaLink.error("없는 빗자루예요: " + raw);
        return type;
    }

    // ------------------------------------------------------------------ /빗자루

    private LiteralArgumentBuilder<CommandSourceStack> userCommand() {
        return Commands.literal("빗자루")
                .executes(ctx -> { info(ctx.getSource().getSender()); return 1; })
                .then(Commands.literal("정보").executes(ctx -> { info(ctx.getSource().getSender()); return 1; }))
                .then(Commands.literal("목록").executes(ctx -> { list(ctx.getSource().getSender()); return 1; }))
                .then(Commands.literal("장착").then(Commands.argument(ARG, ChacaLink.word(() -> labels(service.brooms().values())))
                        .suggests(this::suggestOwned)
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player p)) throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
                            var type = broomArg(ctx);
                            if (!service.owns(p, type)) throw ChacaLink.error("가지고 있지 않은 빗자루예요");
                            service.equip(p, type);
                            ChacaLink.ok(p, type.name() + " 빗자루를 장착했어요");
                            return 1;
                        })));
    }

    private CompletableFuture<Suggestions> suggestOwned(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (!(ctx.getSource().getSender() instanceof Player p)) return builder.buildFuture();
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String s : labels(service.ownedBrooms(p))) {
            if (s.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(s);
        }
        return builder.buildFuture();
    }

    private void info(CommandSender sender) throws CommandSyntaxException {
        if (!(sender instanceof Player p)) throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
        var lines = new ArrayList<String>();
        lines.add("[빗자루] " + ChacaLink.name(p.getUniqueId()));
        var owned = service.ownedBroom(p);
        lines.add("장착 빗자루: " + (owned == null ? "없음 (비행 마법 = 연습용 빗자루)" : owned.name()));
        var all = service.ownedBrooms(p);
        lines.add("보유: " + (all.isEmpty() ? "없음" : String.join(", ", all.stream().map(BroomType::name).toList())));
        lines.add("비행 숙련도: " + masteryText(p.getUniqueId()));
        var riding = service.ridingType(p);
        if (riding != null) lines.add("지금 타는 중: " + riding.name());
        ChacaLink.lines(sender, lines);
    }

    private void list(CommandSender sender) {
        var lines = new ArrayList<String>();
        lines.add("[빗자루 목록] 속도 = 초당 블록, 회전·안정은 클수록 좋음");
        for (var t : service.brooms().values()) {
            lines.add(String.format("%s [%s] 속도 %.0f · 가속 %.0f · 회전 %.1f · 안정 %.2f",
                    t.name(), t.grade(), t.maxSpeed() * 20, t.accel() * 1000, t.turn(), t.stability()));
        }
        ChacaLink.lines(sender, lines);
    }

    private String masteryText(UUID id) {
        float m = service.mastery(id);
        return Math.round(m * 100) + "% (" + BroomMastery.level(m) + ")";
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 빗자루

    private LiteralArgumentBuilder<CommandSourceStack> adminBranch() {
        return Commands.literal("빗자루")
                .then(Commands.literal("보기").executes(ctx -> { adminView(ctx.getSource().getSender(), ChacaLink.target(ctx)); return 1; }))
                .then(Commands.literal("지정").then(Commands.argument(ARG, ChacaLink.word(() -> labels(service.brooms().values())))
                        .executes(ctx -> { assign(ctx.getSource().getSender(), ChacaLink.target(ctx), broomArg(ctx)); return 1; })))
                .then(Commands.literal("해제").executes(ctx -> { unassign(ctx.getSource().getSender(), ChacaLink.target(ctx)); return 1; }))
                .then(Commands.literal("숙련도").then(Commands.argument("값", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> {
                            UUID id = ChacaLink.target(ctx);
                            int pct = IntegerArgumentType.getInteger(ctx, "값");
                            service.store().setSeconds(id, BroomMastery.secondsForPercent(pct, service.masteryFullSeconds()));
                            ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(id) + " 님의 비행 숙련도를 " + masteryText(id) + "로 설정했어요 (다음 탑승부터)");
                            return 1;
                        })))
                .then(Commands.literal("주기")
                        .then(Commands.literal("전체").executes(ctx -> { give(ctx.getSource().getSender(), ChacaLink.target(ctx), null); return 1; }))
                        .then(Commands.argument(ARG, ChacaLink.word(() -> labels(service.brooms().values())))
                                .executes(ctx -> { give(ctx.getSource().getSender(), ChacaLink.target(ctx), broomArg(ctx)); return 1; })))
                .then(Commands.literal("회수")
                        .then(Commands.literal("전체").executes(ctx -> { take(ctx.getSource().getSender(), ChacaLink.target(ctx), null); return 1; }))
                        .then(Commands.argument(ARG, ChacaLink.word(() -> labels(service.brooms().values())))
                                .executes(ctx -> { take(ctx.getSource().getSender(), ChacaLink.target(ctx), broomArg(ctx)); return 1; })));
    }

    private void adminView(CommandSender sender, UUID id) {
        String who = ChacaLink.name(id);
        Player online = Bukkit.getPlayer(id);
        var equippedRaw = service.brooms().get(service.store().broom(id));
        String mastery = masteryText(id);
        if (online != null) {
            var lines = new ArrayList<String>();
            lines.add("[빗자루] " + who + " (접속 중)");
            var eff = service.ownedBroom(online);
            lines.add("장착 빗자루: " + (eff == null ? "없음 (비행 마법 = 연습용 빗자루)" : eff.name() + " (" + eff.id() + ")"));
            if (equippedRaw != null && (eff == null || !eff.id().equals(equippedRaw.id()))) {
                lines.add("지정해 둔 빗자루: " + equippedRaw.name() + " (보유하지 않아 적용 안 됨)");
            }
            var all = service.ownedBrooms(online);
            lines.add("보유 (" + all.size() + "종): " + (all.isEmpty() ? "없음" : String.join(", ", all.stream().map(BroomType::name).toList())));
            lines.add("비행 숙련도: " + mastery);
            var riding = service.ridingType(online);
            if (riding != null) lines.add("지금 타는 중: " + riding.name());
            ChacaLink.lines(sender, lines);
            return;
        }
        ChacaLink.permissions(id, OWN).whenComplete((nodes, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                ChacaLink.fail(sender, "빗자루 보유 정보를 읽지 못했어요", err);
                return;
            }
            var lines = new ArrayList<String>();
            lines.add("[빗자루] " + who + " (접속 안 함)");
            lines.add("지정해 둔 빗자루: " + (equippedRaw == null ? "없음" : equippedRaw.name() + " (" + equippedRaw.id() + ")"));
            String owned;
            if (nodes.contains(OWN + "*")) owned = "전체";
            else {
                var names = new ArrayList<String>();
                for (var t : service.brooms().values()) if (nodes.contains(OWN + t.id())) names.add(t.name());
                owned = names.isEmpty() ? "없음 (개인 권한 기준, 그룹 권한은 접속 시 반영)" : names.size() + "종: " + String.join(", ", names);
            }
            lines.add("보유: " + owned);
            lines.add("비행 숙련도: " + mastery);
            ChacaLink.lines(sender, lines);
        }));
    }

    private void assign(CommandSender sender, UUID id, BroomType type) {
        String who = ChacaLink.name(id);
        Player online = Bukkit.getPlayer(id);
        if (online != null && service.owns(online, type)) {
            service.equip(online, type);
            ChacaLink.ok(sender, who + " 님의 장착 빗자루를 " + type.name() + "(으)로 바꿨어요");
            return;
        }
        service.store().setBroom(id, type.id());
        if (online != null) service.sendList(online);
        ChacaLink.ok(sender, who + " 님에게 " + type.name() + "을(를) 지정했어요 (보유하면 그때부터 적용)");
    }

    private void unassign(CommandSender sender, UUID id) {
        service.store().setBroom(id, "");
        Player online = Bukkit.getPlayer(id);
        String after = "";
        if (online != null) {
            service.sendList(online);
            var fallback = service.ownedBroom(online);
            after = fallback == null ? " (이제 연습용 빗자루)" : " (이제 가진 것 중 가장 좋은 " + fallback.name() + ")";
        }
        ChacaLink.ok(sender, ChacaLink.name(id) + " 님의 장착 빗자루를 해제했어요" + after);
    }

    /** type == null 이면 전체. */
    private void give(CommandSender sender, UUID id, BroomType type) {
        String who = ChacaLink.name(id);
        String node = OWN + (type == null ? "*" : type.id());
        String what = type == null ? "빗자루 전체" : type.name();
        ChacaLink.permission(id, node, true).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                ChacaLink.fail(sender, "빗자루를 주지 못했어요", err);
                return;
            }
            refreshLater(id);
            ChacaLink.ok(sender, who + " 님에게 " + what + "을(를) 주었어요");
        }));
    }

    /** type == null 이면 전체(개인 권한에 있는 빗자루 노드 전부). */
    private void take(CommandSender sender, UUID id, BroomType type) {
        String who = ChacaLink.name(id);
        if (type != null) {
            ChacaLink.permission(id, OWN + type.id(), false).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    ChacaLink.fail(sender, "빗자루를 회수하지 못했어요", err);
                    return;
                }
                refreshLater(id);
                ChacaLink.ok(sender, who + " 님에게서 " + type.name() + "을(를) 회수했어요");
            }));
            return;
        }
        ChacaLink.permissions(id, OWN).thenCompose(nodes -> {
            var all = new ArrayList<CompletableFuture<Void>>();
            for (String n : nodes) all.add(ChacaLink.permission(id, n, false));
            return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(v -> nodes.size());
        }).whenComplete((count, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                ChacaLink.fail(sender, "빗자루를 회수하지 못했어요", err);
                return;
            }
            refreshLater(id);
            ChacaLink.ok(sender, count == 0 ? who + " 님은 개인으로 받은 빗자루가 없어요"
                    : who + " 님에게서 빗자루 " + count + "개 권한을 회수했어요");
        }));
    }

    /** 권한이 반영된 뒤 장비창 목록을 다시 보낸다. */
    private void refreshLater(UUID id) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) service.sendList(p);
        }, 10L);
    }

    // ------------------------------------------------------------------ /테스트 빗자루 타기 [닉네임]

    private LiteralArgumentBuilder<CommandSourceStack> testBranch() {
        return Commands.literal("빗자루")
                .then(Commands.literal("타기")
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player p)) throw ChacaLink.error("닉네임을 적어 주세요");
                            ride(ctx.getSource().getSender(), p);
                            return 1;
                        })
                        .then(Commands.argument(ChacaLink.TARGET, ChacaLink.player())
                                .executes(ctx -> { ride(ctx.getSource().getSender(), ChacaLink.resolveOnline(ctx, ChacaLink.TARGET)); return 1; })));
    }

    private void ride(CommandSender sender, Player target) throws CommandSyntaxException {
        String who = ChacaLink.name(target.getUniqueId());
        if (service.isRiding(target)) {
            service.stop(target);
            ChacaLink.ok(sender, who + " 님이 빗자루에서 내렸어요");
            return;
        }
        if (!service.hasMod(target)) throw ChacaLink.error(who + " 님은 차카데미 모션 모드가 없어서 빗자루를 탈 수 없어요");
        var type = service.ownedBroom(target);
        if (type == null) type = service.brooms().get(service.practiceBroomId());
        if (type == null || !service.start(target, type, 0)) {
            throw ChacaLink.error("지금은 탈 수 없어요 (탈것 탑승·관전 모드·금지 월드 등)");
        }
        ChacaLink.ok(sender, who + " 님이 " + type.name() + "에 탔어요");
    }

    // ------------------------------------------------------------------ 요약 줄

    private String summaryLine(UUID id) {
        Player online = Bukkit.getPlayer(id);
        BroomType eq = online != null ? service.ownedBroom(online) : service.brooms().get(service.store().broom(id));
        return "장착 " + (eq == null ? "없음" : eq.name()) + " · 숙련도 " + masteryText(id);
    }
}
