package kr.chacademy.portrait.cmd;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademy.portrait.ChacaPortraitPlugin;
import kr.chacademy.portrait.core.CostModel;
import kr.chacademy.portrait.core.PortraitSettings;
import kr.chacademy.portrait.data.Database;
import kr.chacademy.portrait.data.PortraitStorage;
import kr.chacademy.portrait.service.PortraitService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ChacaPortrait 관리 명령 (MagicCodexBridge 명령어 허브에 붙인다).
 * <pre>
 * /유저관리 &lt;닉네임&gt; 초상화 보기|다시그리기 [모델]|초기화 확인|재생성권 보기|환불
 * /서버관리 초상화 예산보기|예산조정 &lt;달러&gt;|모델 [모델]
 * /서버관리 새로고침 초상화
 * </pre>
 */
public final class PortraitCommands {

    private static final List<String> MODELS = PortraitSettings.IMAGE_MODELS;
    /** 한 번에 보정할 수 있는 최대 금액 (USD). 오타로 장부가 크게 틀어지는 일 방지. */
    private static final double MAX_ADJUST_USD = 100.0;

    private final ChacaPortraitPlugin plugin;

    public PortraitCommands(ChacaPortraitPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (!ChacaLink.ready()) {
            plugin.getLogger().warning("[ChacaPortrait] MagicCodexBridge 명령어 허브가 없어 초상화 명령을 등록하지 않았어요.");
            return;
        }
        ChacaLink.branch(plugin, ChacaLink.유저관리, userBranch(), "플레이어 일러스트 보기·다시 그리기·초기화·재생성권·환불", "초상화 보기");
        ChacaLink.branch(plugin, ChacaLink.서버관리, serverBranch(), "일러스트 예산·이미지 모델", "초상화 예산보기");
        ChacaLink.reload(plugin, "초상화", "일러스트 설정·레퍼런스 그림", () -> {
            plugin.reloadSettings();
            String ref = plugin.service().referenceError();
            if (ref != null) {
                throw new IllegalStateException(ref);
            }
        });
        ChacaLink.status(plugin, "초상화", this::statusLine);
    }

    // ---------------------------------------------------------------- 알림 (PortraitService가 작업 결과를 알릴 때도 씀)

    /** 관리자가 요청한 작업의 결과 알림. ok=false면 실패 알림(원인은 이미 콘솔에 남김). */
    public static void report(CommandSender to, boolean ok, String message) {
        if (to == null) {
            return;
        }
        if (to instanceof Player p && !p.isOnline()) {
            return;
        }
        if (ok) {
            ChacaLink.ok(to, message);
        } else {
            ChacaLink.fail(to, message, null);
        }
    }

    // ---------------------------------------------------------------- 공통

    private <T> void db(CommandSender to, Database.SqlCall<T> task, String failMessage, Consumer<T> done) {
        plugin.service().then(plugin.db().call(task), v -> {
            try {
                done.accept(v);
            } catch (RuntimeException e) {
                ChacaLink.fail(to, failMessage, e);
            }
        }, ex -> ChacaLink.fail(to, failMessage, ex));
    }

    private static int needConfirm() throws CommandSyntaxException {
        throw ChacaLink.error("되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요");
    }

    private String model(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String m = ctx.getArgument("모델", String.class);
        if (!PortraitSettings.supportedImageModel(m)) {
            throw ChacaLink.error("지원하지 않는 모델이에요 (" + String.join(", ", MODELS) + ")");
        }
        return m;
    }

    /** 생성에 필요한 준비가 안 됐으면 유저용 실패. 자세한 이유는 콘솔에. */
    private void requireReady(List<String> models) throws CommandSyntaxException {
        String problem = plugin.service().staticProblem(plugin.settings(), models);
        if (problem != null) {
            plugin.getLogger().warning("[ChacaPortrait] 일러스트를 그릴 수 없음: " + problem);
            throw ChacaLink.error("지금은 일러스트를 그릴 수 없어요 (자세한 내용은 콘솔)");
        }
    }

    // ---------------------------------------------------------------- /유저관리 <닉네임> 초상화

    private LiteralArgumentBuilder<CommandSourceStack> userBranch() {
        return Commands.literal("초상화")
                .then(Commands.literal("보기").executes(this::view))
                .then(Commands.literal("다시그리기").executes(ctx -> regen(ctx, List.of()))
                        .then(Commands.argument("모델", ChacaLink.word(() -> MODELS)).executes(ctx -> regen(ctx, List.of(model(ctx))))))
                .then(Commands.literal("초기화").executes(ctx -> needConfirm())
                        .then(Commands.literal("확인").executes(this::reset)))
                .then(Commands.literal("재생성권").then(Commands.literal("보기").executes(this::rerolls)))
                .then(Commands.literal("환불").executes(this::refund));
    }

    private int view(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String who = ChacaLink.name(id);
        record View(PortraitStorage.Portrait portrait, String state) {
        }
        db(s, () -> new View(plugin.storage().portrait(id), plugin.storage().lastError(id)), "일러스트 정보를 불러오지 못했어요", v -> {
            List<String> out = new ArrayList<>();
            out.add(who + " 님 일러스트");
            if (v.portrait() == null) {
                out.add("일러스트 없음");
            } else {
                DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(PortraitService.ZONE);
                out.add("일러스트 있음 · " + v.portrait().model() + " · " + sourceLabel(v.portrait().source())
                        + " · " + time.format(Instant.ofEpochMilli(v.portrait().createdAt())));
            }
            if (v.state() != null) {
                out.add(v.state());
            }
            if (plugin.service().busy(id)) {
                out.add("이 서버에서 그리는 중");
            }
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    private static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "auto", "AUTO" -> "자동";
            case "admin", "ADMIN" -> "관리자";
            case "reroll", "REROLL" -> "다시 그리기";
            default -> source == null ? "-" : source;
        };
    }

    private int regen(CommandContext<CommandSourceStack> ctx, List<String> models) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        Player p = ChacaLink.targetOnline(ctx); // 스킨을 읽어야 하므로 접속 중이어야 함
        requireReady(models);
        PortraitService.Job job = PortraitService.jobFor(PortraitService.Kind.ADMIN, p, "", null, models, s);
        if (job.skin().url() == null) {
            throw ChacaLink.error(ChacaLink.name(p.getUniqueId()) + " 님의 스킨을 읽지 못했어요");
        }
        if (!plugin.service().submit(job, null)) {
            throw ChacaLink.error("이미 그리는 중이거나 대기열이 가득 찼어요");
        }
        ChacaLink.ok(s, ChacaLink.name(p.getUniqueId()) + " 님 일러스트를 다시 그려요");
        return 1;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String who = ChacaLink.name(id);
        db(s, () -> {
            boolean had = plugin.storage().deletePortrait(id);
            plugin.storage().resetState(id);
            return had;
        }, "일러스트를 초기화하지 못했어요", had -> {
            plugin.service().clearBackoff(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.channel().cleared(p);
            }
            ChacaLink.ok(s, Boolean.TRUE.equals(had)
                    ? who + " 님 일러스트를 지웠어요 (자동 생성이 켜진 서버면 다음 접속 때 다시 그려요)"
                    : who + " 님은 일러스트가 없어요 (자동 시도 횟수만 초기화)");
        });
        return 1;
    }

    private static String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case PortraitStorage.R_PREPARED -> "차감 준비";
            case PortraitStorage.R_CANCELLED -> "취소(차감 없음)";
            case PortraitStorage.R_PENDING -> "대기 중";
            case PortraitStorage.R_STARTED -> "생성 중";
            case PortraitStorage.R_DONE -> "성공";
            case PortraitStorage.R_CONSUMED -> "실패(아이템 소모)";
            case PortraitStorage.R_REFUND -> "반환 예정";
            case PortraitStorage.R_REFUNDED -> "반환 완료";
            default -> String.valueOf(state);
        };
    }

    /** 최근 다시 그리기(재생성권 사용) 기록 (모든 서버, 최신순 10건)과 오늘 사용 횟수. 문의 대응용. */
    private int rerolls(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        String who = ChacaLink.name(id);
        String today = java.time.LocalDate.now(PortraitService.ZONE).toString();
        record View(List<PortraitStorage.Reroll> rows, long[] usage) {
        }
        db(s, () -> new View(plugin.storage().recentRerolls(id, 10), plugin.storage().rerollUsage(id, today)),
                "다시 그리기 기록을 불러오지 못했어요", v -> {
                    PortraitSettings st = plugin.settings();
                    List<String> out = new ArrayList<>();
                    out.add(who + " 님 다시 그리기(재생성권) 기록 " + v.rows().size() + "건 · 오늘 " + v.usage()[1] + "회"
                            + (st.rerollDailyLimit > 0 ? " / 하루 " + st.rerollDailyLimit + "회" : ""));
                    DateTimeFormatter time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(PortraitService.ZONE);
                    for (PortraitStorage.Reroll r : v.rows()) {
                        String prompt = r.prompt() == null || r.prompt().isEmpty() ? "(기본)" : r.prompt();
                        out.add(time.format(Instant.ofEpochMilli(r.createdAt())) + " [" + r.server() + "] " + stateLabel(r.state())
                                + " · 요청: " + (prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt)
                                + (r.error() == null || r.error().isEmpty() ? "" : " · 사유: "
                                + (r.error().length() > 160 ? r.error().substring(0, 160) + "…" : r.error())));
                    }
                    ChacaLink.lines(s, out);
                });
        return 1;
    }

    /**
     * 소모 처리된(생성 시작 후 실패) 다시 그리기 1건을 반환 대상으로 되돌린다. 실제 지급은 기존 반환 경로가 한다
     * (플레이어 데이터의 차감 영수증 확인 → 원래 아이템 1개 지급 → 반환 영수증). 이 서버에서 차감한 기록만 대상이다.
     */
    private int refund(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        UUID id = ChacaLink.target(ctx);
        if (plugin.generationBlocked()) {
            plugin.getLogger().warning("[ChacaPortrait] server-id가 비어 있어 반환을 처리할 수 없습니다 (config.yml 설정 후 재시작).");
            throw ChacaLink.error("지금은 반환을 처리할 수 없어요 (자세한 내용은 콘솔)");
        }
        String who = ChacaLink.name(id);
        String server = plugin.settings().serverId;
        String admin = s.getName();
        db(s, () -> plugin.storage().refundConsumed(id, server), "반환을 처리하지 못했어요", token -> {
            if (token == null) {
                ChacaLink.fail(s, who + " 님은 이 서버(" + server + ")에서 소모된 다시 그리기 기록이 없어요", null);
                return;
            }
            plugin.getLogger().warning("[ChacaPortrait] 관리자 반환: " + admin + " → " + who + " (" + id + "), 기록 " + token);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.rerolls().deliverRefunds(p, false);
                ChacaLink.ok(s, who + " 님에게 다시 그리기 아이템 1개를 돌려줬어요 (인벤토리가 가득하면 빈 칸이 생길 때 지급)");
            } else {
                ChacaLink.ok(s, who + " 님 반환을 예약했어요 (이 서버에 다음 접속할 때 지급)");
            }
        });
        return 1;
    }

    // ---------------------------------------------------------------- /서버관리 초상화

    private LiteralArgumentBuilder<CommandSourceStack> serverBranch() {
        return Commands.literal("초상화")
                .then(Commands.literal("예산보기").executes(this::budget))
                .then(Commands.literal("예산조정").then(Commands.argument("달러", DoubleArgumentType.doubleArg(-MAX_ADJUST_USD, MAX_ADJUST_USD))
                        .executes(this::budgetAdjust)))
                .then(Commands.literal("모델").executes(ctx -> {
                    PortraitSettings st = plugin.settings();
                    ChacaLink.lines(ctx.getSource().getSender(), List.of("일러스트 모델 " + st.imageModel + " · 품질 " + st.quality,
                            "바꾸기: /서버관리 초상화 모델 <" + String.join("|", MODELS) + ">"));
                    return 1;
                }).then(Commands.argument("모델", ChacaLink.word(() -> MODELS)).executes(this::setModel)));
    }

    private int budget(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        PortraitSettings st = plugin.settings();
        db(s, () -> plugin.storage().budget(), "예산을 불러오지 못했어요", b -> {
            long cap = (long) Math.floor(st.budgetUsd * 1_000_000L);
            List<String> out = new ArrayList<>();
            out.add("일러스트 예산 " + CostModel.usd(cap) + " · 사용 " + CostModel.usd(b.spent()) + " · 예약 중 "
                    + CostModel.usd(b.reserved()) + " · 남음 " + CostModel.usd(Math.max(0, cap - b.spent() - b.reserved())));
            CostModel cm = new CostModel(st.prices, st.estimate, st.estimate25);
            for (String m : MODELS) {
                out.add(m + " 1장 추정 예약액(" + st.quality + "): " + CostModel.usd(cm.reserveImage(m, st.quality)));
            }
            out.add("(예약액은 비용 상한이 아니에요. 실제 사용량으로 정산)");
            ChacaLink.lines(s, out);
        });
        return 1;
    }

    /**
     * 사용액 장부 보정. 시간 초과·5xx처럼 과금 여부를 몰라 예약액으로 잡힌 금액을 OpenAI 청구서와 맞출 때 쓴다.
     * +는 사용액을 늘리고 -는 줄인다 (0 아래로는 내려가지 않음). 한 번에 ±100 USD까지. 콘솔 로그에 남는다.
     */
    private int budgetAdjust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        double usd = DoubleArgumentType.getDouble(ctx, "달러");
        long delta = Math.round(usd * 1_000_000L);
        if (delta == 0) {
            throw ChacaLink.error("0이 아닌 금액을 적어 주세요");
        }
        String who = s.getName();
        db(s, () -> {
            long before = plugin.storage().budget().spent();
            long after = plugin.storage().adjustSpent(delta).spent();
            return new long[]{before, after};
        }, "예산을 조정하지 못했어요", r -> {
            plugin.getLogger().warning("[ChacaPortrait] 예산 사용액 보정: " + who + " 이(가) " + CostModel.usd(r[0]) + " → "
                    + CostModel.usd(r[1]) + " (요청 " + (delta > 0 ? "+" : "-") + CostModel.usd(Math.abs(delta)) + ")");
            ChacaLink.ok(s, "일러스트 사용액 " + CostModel.usd(r[0]) + " → " + CostModel.usd(r[1]) + " (기록 남김)");
        });
        return 1;
    }

    private int setModel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String m = model(ctx);
        if (!PortraitSettings.supportsQuality(m, plugin.settings().quality)) {
            throw ChacaLink.error("지금 품질 설정(image.quality)은 이 모델에서 쓸 수 없어요");
        }
        plugin.getConfig().set("image.model", m);
        plugin.saveConfig();
        plugin.reloadSettings();
        ChacaLink.ok(ctx.getSource().getSender(), "일러스트 모델을 " + m + "(으)로 바꿨어요 (다음 생성부터)");
        return 1;
    }

    // ---------------------------------------------------------------- /서버관리 상태

    private String statusLine() {
        PortraitService svc = plugin.service();
        if (svc == null) {
            return "꺼짐";
        }
        String problem = svc.staticProblem();
        if (problem != null) {
            return "대기 · 생성 불가 (" + problem + ")";
        }
        PortraitSettings st = plugin.settings();
        return (svc.paused() ? "대기 · OpenAI 실패가 이어져 잠시 쉬는 중" : "정상") + " · " + st.imageModel
                + " · 진행 " + svc.runningCount() + " · 대기 " + svc.waitingCount()
                + " · 자동 생성 " + (st.automaticGenerationAllowed() ? "켜짐" : "꺼짐");
    }
}
