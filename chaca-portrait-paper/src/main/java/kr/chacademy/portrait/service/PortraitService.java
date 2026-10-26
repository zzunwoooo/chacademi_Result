package kr.chacademy.portrait.service;

import kr.chacademy.portrait.ChacaPortraitPlugin;
import kr.chacademy.portrait.ai.OpenAiImageClient;
import kr.chacademy.portrait.core.AutoBackoff;
import kr.chacademy.portrait.core.CircuitBreaker;
import kr.chacademy.portrait.core.CostModel;
import kr.chacademy.portrait.core.JobOrder;
import kr.chacademy.portrait.core.PortraitSettings;
import kr.chacademy.portrait.core.PromptBuilder;
import kr.chacademy.portrait.data.PortraitStorage;
import kr.chacademy.portrait.skin.SkinFetcher;
import kr.chacademy.portrait.skin.SkinRenderer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 일러스트 생성 작업. 스킨 → 앞/뒤 그림 → (선택) 외형 정리 → 이미지 모델(레퍼런스 + 스킨 그림) → 검사 → 저장 → 전송.
 * 모든 OpenAI·DB 작업은 작업 스레드에서, 플레이어 메시지·아이템은 메인 스레드에서 처리한다.
 *
 * <p>대기열은 우선순위 큐다: 관리자 → 다시 그리기 → 자동, 같은 등급 안에서는 들어온 순서.
 * <p>실패 분류: 일시적(429·5xx·시간 초과·연결) / 설정(401·403·404·단가 없음 등) / 내용(400 거부·결과 검사 실패).
 * 자동 생성은 일시적 실패를 횟수에 넣지 않고 30초 → 2분 → 10분 간격으로 최대 3번 다시 대기열에 넣는다 (접속 중일 때만).
 * 다시 그리기는 일시적 실패를 작업 안에서 1번 더 시도한 뒤에도 실패하면 아이템을 소모 처리한다.
 * 일시적 실패가 연속 5번이면 차단기가 열려 60초(두 배씩, 최대 10분) 동안 새 작업을 쉰다.
 */
public final class PortraitService {

    public enum Kind { AUTO, REROLL, ADMIN }

    /** 작업 하나. 모델은 기본값 또는 명시한 한 개만 사용한다. */
    public record Job(Kind kind, UUID player, String name, SkinFetcher.SkinRef skin, String request,
                      String rerollToken, List<String> models, CommandSender reporter) {
    }

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long LOCK_STALE_MS = 20 * 60 * 1000L;
    private static final int MAX_RESULT_BYTES = 8 * 1024 * 1024;
    /** 자동 생성 일시적 실패 뒤 다시 넣기까지의 간격 (초). 길이 = 작업당 최대 재시도 횟수. */
    private static final int[] AUTO_RETRY_SECONDS = {30, 120, 600};
    /** 다시 그리기 일시적 실패 뒤 작업 안에서 한 번 더 시도하기 전 대기 (ms). */
    private static final long REROLL_RETRY_WAIT_MS = 10_000L;
    /** 작업 안 재시도가 생성 잠금을 쥔 채 기다릴 수 있는 최대 시간 (처음 쉬는 시간 + 차단기 대기 합계). LOCK_STALE_MS 안에 끝나야 한다. */
    private static final long REROLL_RETRY_MAX_WAIT_MS = 120_000L;
    /** 대기 시간 안내용: 한 장에 걸리는 시간 추정 (초). */
    private static final int SECONDS_PER_JOB = 90;

    /** 대기열 항목. 순서는 {@link JobOrder} (관리자 → 다시 그리기 → 자동, 같은 등급은 먼저 온 순서). */
    static final class QueuedJob implements Runnable, Comparable<QueuedJob> {
        final JobOrder order;
        final UUID player;
        private final Runnable body;

        QueuedJob(Kind kind, long seq, UUID player, Runnable body) {
            this.order = new JobOrder(rank(kind), seq);
            this.player = player;
            this.body = body;
        }

        static int rank(Kind kind) {
            return kind == Kind.ADMIN ? JobOrder.ADMIN : kind == Kind.REROLL ? JobOrder.REROLL : JobOrder.AUTO;
        }

        @Override
        public int compareTo(QueuedJob o) {
            return order.compareTo(o.order);
        }

        @Override
        public void run() {
            body.run();
        }
    }

    /** 작업 한 번의 진행 상태 (작업 스레드 전용). */
    private static final class Run {
        /** 자동 생성 재시도 순번 (0 = 첫 시도). */
        final int attempt;
        /** 다시 그리기: "생성 시작" 경계를 넘었음 (이후 실패는 아이템 소모). */
        boolean started;
        /** 다시 그리기: 경계 기록을 시도했지만 결과를 확인하지 못함 (유료 호출은 하지 않았음). */
        boolean startUncertain;
        /** 다시 그리기: 일시적 실패 재시도를 이미 썼음. */
        boolean rerollRetried;

        Run(int attempt) {
            this.attempt = attempt;
        }
    }

    private final ChacaPortraitPlugin plugin;
    private final OpenAiImageClient ai = new OpenAiImageClient();
    private final SkinFetcher skins = new SkinFetcher();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** 이 서버에 접속 중인 플레이어 (메인 스레드에서 갱신, 작업 스레드에서 읽기). */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    /** 자동 생성 재시도 예약이 걸려 있는 플레이어 (접속 시 중복으로 넣지 않게). */
    private final Set<UUID> retryPending = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<Runnable> waiting = new PriorityBlockingQueue<>(64);
    private final AtomicInteger threadNo = new AtomicInteger();
    private final AtomicLong jobSeq = new AtomicLong();
    private volatile ThreadPoolExecutor pool;
    private volatile ThreadPoolExecutor aux;
    private final kr.chacademy.portrait.core.GenerationGate gate = new kr.chacademy.portrait.core.GenerationGate();
    /** 일시적 실패 연속 5번 → 60초 쉼 (두 배씩, 최대 10분). */
    private final CircuitBreaker breaker = new CircuitBreaker(5, 60_000L, 600_000L);
    /** 횟수에 넣지 않는 자동 생성 실패: 30분에 한 번, 하루 5번까지만 다시 시도 (메모리, 재시작 시 초기화). */
    private final AutoBackoff backoff = new AutoBackoff(30 * 60 * 1000L, 5);
    private volatile List<byte[]> references = List.of();
    private volatile String referenceError = "레퍼런스를 아직 읽지 않았습니다";

    public PortraitService(ChacaPortraitPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PortraitSettings s = plugin.settings();
        pool = new ThreadPoolExecutor(s.workers, s.workers, 30, TimeUnit.SECONDS, waiting, r -> {
            Thread t = new Thread(r, "ChacaPortrait-Job-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // 문장 검사·레퍼런스 읽기처럼 짧은 보조 작업용 (공용 ForkJoin 풀을 쓰지 않는다)
        ThreadPoolExecutor a = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "ChacaPortrait-Aux-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        a.allowCoreThreadTimeOut(true);
        aux = a;
        runAux(this::loadReferences);
    }

    /** 짧은 보조 작업용 실행기 (문장 검사 등). 플러그인 종료 시 함께 닫힌다. */
    public java.util.concurrent.Executor aux() {
        return this::runAux;
    }

    private void runAux(Runnable r) {
        ThreadPoolExecutor a = aux;
        if (a == null) {
            throw new java.util.concurrent.RejectedExecutionException("stopped");
        }
        a.execute(r);
    }

    /** /서버관리 새로고침 초상화: 작업 수·레퍼런스 다시 읽기. */
    public void reload() {
        PortraitSettings s = plugin.settings();
        ThreadPoolExecutor p = pool;
        if (p != null) {
            if (s.workers > p.getMaximumPoolSize()) {
                p.setMaximumPoolSize(s.workers);
                p.setCorePoolSize(s.workers);
            } else {
                p.setCorePoolSize(s.workers);
                p.setMaximumPoolSize(s.workers);
            }
        }
        try {
            runAux(this::loadReferences);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            plugin.getLogger().warning("[ChacaPortrait] 레퍼런스를 다시 읽지 못했습니다 (보조 작업이 밀려 있음). 잠시 뒤 다시 reload 하세요.");
        }
    }

    public void stop() {
        gate.stop();
        ThreadPoolExecutor p = pool;
        if (p != null) {
            p.shutdownNow();
            try {
                p.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ThreadPoolExecutor a = aux;
        aux = null;
        if (a != null) {
            a.shutdownNow();
        }
        ai.close();
        skins.close();
    }

    private void loadReferences() {
        PortraitSettings s = plugin.settings();
        List<byte[]> out = new ArrayList<>();
        String error = null;
        for (String rel : s.references) {
            Path base = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path file = base.resolve(rel).normalize();
            if (!file.startsWith(base)) {
                error = "레퍼런스 경로가 플러그인 폴더 밖입니다: " + rel;
                break;
            }
            try {
                byte[] b = Files.readAllBytes(file);
                checkPng(b, 4096);
                out.add(b);
            } catch (IOException e) {
                error = "레퍼런스를 읽지 못했습니다: " + rel + " (" + e.getMessage() + ")";
                break;
            }
        }
        if (error == null && out.isEmpty()) {
            error = "레퍼런스가 설정되지 않았습니다";
        }
        if (error != null) {
            references = List.of();
            referenceError = error;
            plugin.getLogger().warning("[ChacaPortrait] " + error);
        } else {
            references = List.copyOf(out);
            referenceError = null;
            plugin.getLogger().info("[ChacaPortrait] 레퍼런스 " + out.size() + "장 준비됨");
        }
    }

    public String referenceError() {
        return referenceError;
    }

    public boolean busy(UUID id) {
        return active.contains(id);
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int runningCount() {
        ThreadPoolExecutor p = pool;
        return p == null ? 0 : p.getActiveCount();
    }

    /** 접속·퇴장 기록 (메인 스레드). 작업 스레드가 Bukkit API 없이 접속 여부를 보기 위한 것. */
    public void online(UUID id, boolean here) {
        if (here) {
            online.add(id);
        } else {
            online.remove(id);
        }
    }

    /** OpenAI 장애로 새 작업을 쉬는 중인지 (차단기 열림). */
    public boolean paused() {
        return breaker.paused(System.currentTimeMillis());
    }

    /**
     * 유료 호출 없이 미리 알 수 있는 준비 상태 (설정·키·레퍼런스·모델·단가). 문제 없으면 null.
     * 플레이어에게 "그리는 중" 안내를 하거나 대기열에 넣기 전에 확인한다.
     */
    public String staticProblem(PortraitSettings s, List<String> models) {
        if (plugin.generationBlocked()) {
            return "server-id가 비어 있습니다 (공유 DB에서는 필수) — config.yml에 서버마다 다른 값을 넣고 재시작하세요";
        }
        if (!s.enabled) {
            return "기능 꺼짐";
        }
        if (!s.hasKey()) {
            return "OpenAI 키 없음 (환경변수 CHACAPORTRAIT_OPENAI_KEY 또는 CHACANPC_OPENAI_KEY, 혹은 이 플러그인의 openai.api-key)";
        }
        String refs = referenceError;
        if (refs != null) {
            return refs;
        }
        List<String> ms = models == null || models.isEmpty() ? List.of(s.imageModel) : models;
        if (ms.size() != 1) {
            return "Exactly one image model is required";
        }
        String m = ms.get(0);
        if (!PortraitSettings.supportedImageModel(m)) {
            return "지원하지 않는 이미지 모델: " + m;
        }
        if (!PortraitSettings.supportsQuality(m, s.quality)) {
            return "Unsupported model quality";
        }
        CostModel cost = new CostModel(s.prices, s.estimate, s.estimate25);
        if (!cost.hasPrices(m)) {
            return "단가표(prices)에 " + m + " 없음 — 예산을 지킬 수 없어 중단";
        }
        if (!s.describeEnabled || s.describeModel == null || s.describeModel.isBlank()) {
            return "Skin preparation is required; enable describe";
        }
        if (!cost.hasPrices(s.describeModel)) {
            return "Skin preparation price missing";
        }
        return null;
    }

    /** 기본 모델 기준의 준비 상태. 문제 없으면 null. */
    public String staticProblem() {
        return staticProblem(plugin.settings(), List.of());
    }

    /** DB 스레드에서 호출: 남은 예산이 "외형 정리 + 이미지 한 장" 예약액 이상인지. */
    public boolean budgetAvailable(PortraitSettings s) throws java.sql.SQLException {
        CostModel cost = new CostModel(s.prices, s.estimate, s.estimate25);
        PortraitStorage.BudgetState b = plugin.storage().budget();
        long need = cost.reserveDescribe(s.describeModel, s.describeMaxTokens) + cost.reserveImage(s.imageModel, s.quality);
        return b.spent() + b.reserved() + need <= budgetCap(s);
    }

    /**
     * 자동 생성을 지금 넣어도 되는지 (메인 스레드). 준비 상태·차단기·재시도 예약·실패 간격 제한을 본다.
     * 예산은 DB 조회가 필요하므로 호출 측이 {@link #budgetAvailable}로 따로 확인한다.
     */
    public boolean autoEligible(UUID id) {
        return staticProblem() == null && !paused() && !retryPending.contains(id)
                && backoff.allowed(id, System.currentTimeMillis(), LocalDate.now(ZONE).toString());
    }

    /** 관리자 초기화(/유저관리 <닉네임> 초상화 초기화) 시: 자동 생성 간격 제한을 푼다. */
    public void clearBackoff(UUID id) {
        backoff.clear(id);
    }

    /** 이 플레이어 작업 앞에 대기 중인 작업 수. 대기열에 없으면(바로 그리기 시작했으면) -1. */
    public int ahead(UUID player) {
        Object[] all = waiting.toArray();
        QueuedJob mine = null;
        for (Object o : all) {
            if (o instanceof QueuedJob q && q.player.equals(player)) {
                mine = q;
            }
        }
        if (mine == null) {
            return -1;
        }
        int n = 0;
        for (Object o : all) {
            if (o instanceof QueuedJob q && q != mine && q.order.before(mine.order)) {
                n++;
            }
        }
        return n;
    }

    /** 앞에 ahead개가 있을 때 대략의 완성 시간 (분). */
    public int roughMinutes(int ahead) {
        return JobOrder.roughMinutes(ahead, plugin.settings().workers, SECONDS_PER_JOB);
    }

    /**
     * 작업 넣기 (메인 스레드). 같은 플레이어 작업이 이미 있으면 false.
     * @param onRejected 대기열이 가득 차는 등으로 못 넣었을 때 (메인 스레드에서 호출)
     */
    public boolean submit(Job job, Runnable onRejected) {
        return submit(job, onRejected, 0);
    }

    private boolean submit(Job job, Runnable onRejected, int attempt) {
        ThreadPoolExecutor p = pool;
        if (!gate.running() || p == null || p.isShutdown() || plugin.generationBlocked()) {
            return false;
        }
        if (!active.add(job.player())) {
            return false;
        }
        if (waiting.size() >= plugin.settings().maxWaiting) {
            active.remove(job.player());
            return false;
        }
        Run ctx = new Run(attempt);
        try {
            p.execute(new QueuedJob(job.kind(), jobSeq.incrementAndGet(), job.player(), () -> {
                try {
                    run(job, ctx);
                } catch (InterruptedException t) {
                    Thread.currentThread().interrupt();
                    finishFailure(job, new JobFailure("서버 종료로 중단", false, true), ctx);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "[ChacaPortrait] 작업 오류: " + t, t);
                    finishFailure(job, new JobFailure("내부 오류: " + t.getClass().getSimpleName(), false, true), ctx);
                } finally {
                    active.remove(job.player());
                }
            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            active.remove(job.player());
            if (onRejected != null) {
                onRejected.run();
            }
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 작업 본체 (작업 스레드)

    /**
     * 작업 실패 (포장용).
     * countAttempt = 자동 생성 시도 횟수에 넣을지 (내용 문제만 넣는다. 일시적 장애·설정 문제·예산은 넣지 않음).
     * quiet = 플레이어에게 실패 안내를 하지 않음. retryable = 일시적 실패 (다시 시도할 가치가 있음). budget = 예산 부족.
     */
    private static final class JobFailure extends Exception {
        final boolean countAttempt;
        final boolean quiet;
        final boolean retryable;
        final boolean budget;

        JobFailure(String message, boolean countAttempt, boolean quiet) {
            this(message, countAttempt, quiet, false, false);
        }

        JobFailure(String message, boolean countAttempt, boolean quiet, boolean retryable, boolean budget) {
            super(message);
            this.countAttempt = countAttempt;
            this.quiet = quiet;
            this.retryable = retryable;
            this.budget = budget;
        }
    }

    /** DB 작업을 기다린다. 쓰기는 시간 제한 없이 끝까지 기다린다 (시간 초과 후 뒤늦게 커밋되는 일 방지). */
    private <T> T await(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ex ? ex : new RuntimeException(c);
        }
    }

    private void stopCheck() throws InterruptedException {
        if (!gate.running() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Portrait generation stopped");
        }
    }

    /** 차단기가 열려 있으면 닫힐 때까지 이 작업 스레드를 쉬게 한다 (새 작업을 가져가지 않는 효과). */
    private void awaitBreaker() throws InterruptedException {
        long wait;
        while ((wait = breaker.waitMs(System.currentTimeMillis())) > 0) {
            stopCheck();
            Thread.sleep(Math.min(wait, 1000L));
        }
    }

    private void run(Job job, Run ctx) throws Exception {
        PortraitSettings s = plugin.settings();
        PortraitStorage st = plugin.storage();
        String server = s.serverId;
        long generation = 0;
        try {
            awaitBreaker();
            if (!gate.running()) return;
            if (job.kind() == Kind.AUTO && !online.contains(job.player())) {
                // 차례가 왔을 때 이미 나간 플레이어: API를 부르지 않는다 (다음 접속 때 다시 대기열에)
                plugin.getLogger().info("[ChacaPortrait] " + job.name() + " 자동 생성 건너뜀 (접속 종료)");
                return;
            }
            generation = await(plugin.db().call(() -> st.claimGeneration(job.player(), server, LOCK_STALE_MS)));
            if (generation == 0) {
                throw new JobFailure("다른 서버에서 이미 그리는 중", false, true);
            }
            if (!gate.running()) return;
            if (job.kind() == Kind.AUTO && (!s.automaticGenerationAllowed()
                    || await(plugin.db().call(() -> st.sha(job.player()) != null
                        || st.autoAttempts(job.player()) >= s.autoMaxAttempts)))) return;
            Committed done = produce(job, ctx, s, st, generation);
            // ---- 여기부터는 이미 확정됨: 실패해도 반환·재시도하지 않는다 ----
            try {
                backoff.clear(job.player());
                plugin.getLogger().info("[ChacaPortrait] " + job.name() + " 일러스트 완료 (" + done.model + ", "
                        + CostModel.usd(done.cost) + ", " + done.millis / 1000 + "초)");
                plugin.main(() -> {
                    Player p = Bukkit.getPlayer(job.player());
                    if (p != null) {
                        plugin.channel().push(p, done.sha, done.png);
                        p.sendMessage(s.message("done"));
                    }
                    if (job.reporter() != null && !(job.reporter() instanceof Player rp && rp.getUniqueId().equals(job.player()))) {
                        kr.chacademy.portrait.cmd.PortraitCommands.report(job.reporter(), true, job.name() + " 님 일러스트 완성 · " + done.model + ", " + CostModel.usd(done.cost));
                    }
                });
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[ChacaPortrait] 완료 알림 실패: " + e);
            }
        } catch (JobFailure f) {
            finishFailure(job, f, ctx);
        } catch (DiscardedResult d) {
            // 결과를 확정하지 못함 (종료 중이거나 잠금·기록이 이미 다른 상태). 다시 그리기는 이미 생성이 시작됐으므로
            // 실패 경로로 보내 소모 처리·안내까지 끝낸다. 자동·관리자는 잠금만 풀고 끝낸다.
            finishFailure(job, new JobFailure("결과 폐기 (확정하지 못함 — 종료 중이거나 기록이 이미 다른 상태)", false, true), ctx);
        } finally {
            if (generation != 0) {
                final long ownedGeneration = generation;
                try {
                    await(plugin.db().call(() -> {
                        st.releaseGeneration(job.player(), server, ownedGeneration);
                        return null;
                    }));
                } catch (Exception e) {
                    plugin.getLogger().warning("[ChacaPortrait] 잠금 해제 실패: " + e.getMessage());
                }
            }
        }
    }

    private static final class DiscardedResult extends Exception {
    }

    private record Committed(String sha, byte[] png, String model, long cost, long millis) {
    }

    /** 생성부터 확정까지. 실패는 JobFailure, 확정 전 내부 오류는 그대로 던짐(→ 실패 처리). */
    private Committed produce(Job job, Run ctx, PortraitSettings s, PortraitStorage st, long generation) throws Exception {
        String server = s.serverId;
        stopCheck();
        // 0) 유료 호출 없이 알 수 있는 문제는 여기서 끝낸다 (횟수에 넣지 않음)
        String problem = staticProblem(s, job.models());
        if (problem != null) {
            throw new JobFailure(problem, false, true);
        }
        List<byte[]> refs = references;
        if (refs.isEmpty()) {
            throw new JobFailure(String.valueOf(referenceError), false, true);
        }
        String model = job.models() == null || job.models().isEmpty() ? s.imageModel : job.models().get(0);
        CostModel cost = new CostModel(s.prices, s.estimate, s.estimate25);

        // 1) 스킨
        if (PortraitSettings.needsLocalMatte(model)) {
            try { BackgroundMatte.ensureAvailable(); }
            catch (IOException unavailable) { throw new JobFailure("Local background removal unavailable; no API request sent", false, true); }
        }
        byte[] render;
        try {
            byte[] skinPng = skins.download(job.skin());
            render = SkinRenderer.renderPng(skinPng, job.skin().slim());
        } catch (IOException e) {
            throw new JobFailure("스킨: " + e.getMessage(), false, true);
        }
        String user = s.sendUserHash ? userHash(job.player()) : null;

        // 2) 예산: 외형 정리 + 이미지 예약을 첫 유료 호출 전에 한꺼번에 잡는다 (외형 정리만 내고 이미지에서 예산이 막히는 일 방지)
        String describeRid = newId(), imageRid = newId();
        long describeReserve = cost.reserveDescribe(s.describeModel, s.describeMaxTokens);
        long imageReserve = cost.reserveImage(model, s.quality);
        long cap = budgetCap(s);
        boolean reserved = await(plugin.db().call(() -> {
            if (!st.reserve(describeRid, server, describeReserve, cap)) {
                return false;
            }
            if (!st.reserve(imageRid, server, imageReserve, cap)) {
                st.settle(describeRid, 0);
                return false;
            }
            return true;
        }));
        if (!reserved) {
            notifyBudget(job);
            throw new JobFailure("예산 소진", false, true, false, true);
        }
        boolean describeOpen = true, imageOpen = true; // 아직 정산하지 않은 예약
        try {
            // 3) 다시 그리기: "생성 시작" 경계. 이 전이가 커밋된 뒤의 실패는 아이템을 돌려주지 않는다.
            //    종료와 겹치지 않도록 종료 관문(gate) 안에서 기록한다 (종료가 먼저면 false → 유료 호출 없이 반환 경로).
            if (job.rerollToken() != null) {
                String day = LocalDate.now(ZONE).toString();
                boolean started;
                try {
                    started = await(plugin.db().call(() -> gate.commit(() ->
                            st.markRerollStarted(job.rerollToken(), job.player(), day))));
                } catch (Exception e) {
                    ctx.startUncertain = true; // 기록됐는지 모름. 유료 호출은 하지 않았으므로 반환 대상
                    throw e;
                }
                if (!started) {
                    throw new JobFailure("다시 그리기를 시작하지 못함 (종료 중이거나 기록이 대기 상태가 아님)", false, true);
                }
                ctx.started = true;
            }

            // 4) 외형 정리 (필수, 실패하면 이미지 호출 중단)
            String appearance;
            describeOpen = false; // describeOnce가 어떤 경우에도 정산한다
            try {
                appearance = describeOnce(job, s, cost, describeRid, describeReserve, render, user);
            } catch (JobFailure f) {
                if (!rerollRetry(job, ctx, f)) {
                    throw f;
                }
                String rid = newId();
                if (!await(plugin.db().call(() -> st.reserve(rid, server, describeReserve, cap)))) {
                    throw new JobFailure("예산 소진 (재시도)", false, true, false, true);
                }
                appearance = describeOnce(job, s, cost, rid, describeReserve, render, user);
            }
            PromptBuilder.ImageRequest imageRequest = PromptBuilder.imageRequest(refs, render,
                    s.promptBase, s.promptAppearance, s.promptRequest, appearance, job.request());
            String prompt = imageRequest.prompt();
            List<byte[]> inputs = imageRequest.images();

            // 5) 이미지
            Generated g;
            imageOpen = false; // imageOnce가 어떤 경우에도 정산한다
            try {
                g = imageOnce(job, s, cost, model, imageRid, imageReserve, inputs, prompt, user);
            } catch (JobFailure f) {
                if (!rerollRetry(job, ctx, f)) {
                    throw f;
                }
                String rid = newId();
                if (!await(plugin.db().call(() -> st.reserve(rid, server, imageReserve, cap)))) {
                    throw new JobFailure("예산 소진 (재시도)", false, true, false, true);
                }
                g = imageOnce(job, s, cost, model, rid, imageReserve, inputs, prompt, user);
            }
            String sha = sha256(g.png);
            String source = job.kind() == Kind.REROLL ? "reroll" : job.kind() == Kind.ADMIN ? "admin" : "auto";
            String day = LocalDate.now(ZONE).toString();
            final Generated result = g;
            boolean committed = await(plugin.db().call(() -> gate.commit(() -> st.commitResult(job.player(), job.rerollToken(), sha, result.png, model,
                    source, job.request(), result.cost, day, server, generation))));
            if (!committed) {
                throw new DiscardedResult();
            }
            return new Committed(sha, g.png, model, g.cost, g.millis);
        } finally {
            if (describeOpen) {
                releaseReservation(describeRid);
            }
            if (imageOpen) {
                releaseReservation(imageRid);
            }
        }
    }

    /**
     * 다시 그리기의 일시적 실패: 작업당 한 번만, 잠시 쉬었다가 다시 시도한다. 다시 시도해도 되면 true.
     * 생성 잠금을 쥔 채 기다리므로 차단기 대기는 합계 REROLL_RETRY_MAX_WAIT_MS 까지만 한다. 그때까지 차단기가
     * 열려 있으면 false (호출한 쪽이 원래 실패를 던져 일반 실패 경로로 끝난다 — 이미 시작된 다시 그리기는 소모 처리).
     */
    private boolean rerollRetry(Job job, Run ctx, JobFailure f) throws InterruptedException {
        if (job.kind() != Kind.REROLL || !f.retryable || ctx.rerollRetried) {
            return false;
        }
        ctx.rerollRetried = true;
        plugin.getLogger().info("[ChacaPortrait] " + job.name() + " 다시 그리기 일시적 실패 → 1회 재시도: " + f.getMessage());
        long begin = System.currentTimeMillis();
        long until = begin + REROLL_RETRY_WAIT_MS, deadline = begin + REROLL_RETRY_MAX_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            stopCheck();
            Thread.sleep(500L);
        }
        long wait;
        while ((wait = breaker.waitMs(System.currentTimeMillis())) > 0) {
            stopCheck();
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                plugin.getLogger().info("[ChacaPortrait] " + job.name() + " 다시 그리기 재시도 포기: OpenAI 차단기가 "
                        + REROLL_RETRY_MAX_WAIT_MS / 1000 + "초 안에 닫히지 않음");
                return false;
            }
            Thread.sleep(Math.min(Math.min(wait, left), 1000L));
        }
        return true;
    }

    /** API 실패 → 작업 실패로 분류 (차단기 기록 포함). */
    private JobFailure apiFailure(String prefix, OpenAiImageClient.ApiException e) {
        String message = prefix + e.getMessage();
        if (e.retryable()) {
            breakerFailure();
            return new JobFailure(message, false, false, true, false);
        }
        breakerOk(); // 서버는 정상적으로 응답했다
        if (e.type == OpenAiImageClient.Type.CONFIG) {
            return new JobFailure(message, false, true);
        }
        return new JobFailure(message, true, false);
    }

    private void breakerFailure() {
        if (breaker.failure(System.currentTimeMillis())) {
            plugin.getLogger().warning("[ChacaPortrait] OpenAI 일시적 실패가 연속으로 발생 — 새 작업을 " + breaker.coolMs() / 1000
                    + "초 쉽니다 (계속 실패하면 최대 10분까지 늘어남). 대기 중인 작업은 유지됩니다.");
        }
    }

    private void breakerOk() {
        if (breaker.success()) {
            plugin.getLogger().warning("[ChacaPortrait] OpenAI 응답이 정상으로 돌아왔습니다 — 작업을 다시 진행합니다.");
        }
    }

    /** 외형 정리 1회: 호출 → 검사 → 정산 (정산은 어떤 경우에도 한 번). 실패는 JobFailure. */
    private String describeOnce(Job job, PortraitSettings s, CostModel cost, String rid, long reserve, byte[] render,
                                String user) throws Exception {
        long settle = 0; // 아직 요청을 보내지 않음 (종료로 중단되면 과금 없음)
        long in = -1, out = -1;
        boolean ok = false;
        try {
            stopCheck();
            settle = reserve; // 여기부터는 요청이 나갔을 수 있으므로 예약액 (누락 방지)
            OpenAiImageClient.TextResult r = ai.describe(s, render, user, job.request());
            // 여기서는 차단기를 닫지 않는다: 외형 정리(Responses)만 되고 이미지 API만 장애인 경우, 작업마다 연속 실패 횟수가
            // 초기화되어 차단기가 영영 열리지 않는다. 닫는 것은 이미지 호출이 응답했을 때(imageOnce)와 일시적이 아닌 API 응답뿐.
            in = r.inputTokens();
            out = r.outputTokens();
            settle = cost.settleDescribe(s.describeModel, in, out, reserve);
            String appearance;
            try { appearance = kr.chacademy.portrait.core.SkinAnalysis.validatedNotes(r.text()); }
            catch (RuntimeException invalid) { throw new JobFailure("Invalid skin preparation; image generation blocked", true, false); }
            if (appearance == null || appearance.isBlank()) {
                throw new JobFailure("Skin preparation empty; image generation blocked", true, false);
            }
            ok = true;
            return appearance;
        } catch (OpenAiImageClient.ApiException e) {
            settle = e.billedUnknown ? reserve : 0;
            throw apiFailure("Skin preparation failed; image generation blocked: ", e);
        } finally {
            settleQuietly(job, rid, settle, "describe", s.describeModel, 0, Math.max(0, in), Math.max(0, out), ok);
        }
    }

    private record Generated(byte[] png, long cost, long millis, CostModel.ImageUsage usage) {
    }

    /** 이미지 1회: 호출 → 검사 → 정산 (정산은 어떤 경우에도 한 번). 예약(rid)은 호출 측이 미리 잡아 둔다. 실패는 JobFailure. */
    private Generated imageOnce(Job job, PortraitSettings s, CostModel cost, String model, String rid, long reserve,
                                List<byte[]> inputs, String prompt, String user) throws Exception {
        long started = System.currentTimeMillis();
        long settle = 0; // 아직 요청을 보내지 않음 (종료로 중단되면 과금 없음)
        CostModel.ImageUsage usage = null;
        byte[] png = null;
        JobFailure failure = null;
        try {
            stopCheck();
            settle = reserve; // 여기부터는 요청이 나갔을 수 있으므로 예약액
            OpenAiImageClient.ImageResult r = ai.edit(s, model, inputs,
                    kr.chacademy.portrait.core.PromptBuilder.forModel(prompt, model), user);
            breakerOk();
            usage = r.usage();
            settle = cost.settleImage(model, usage, reserve);
            String invalid = validateResult(r.png(), false);
            byte[] processed = null;
            if (invalid == null) {
                try {
                    processed = PortraitSettings.needsLocalMatte(model) ? BackgroundMatte.remove(r.png()) : r.png();
                    invalid = validateResult(processed, s.requireTransparent || PortraitSettings.needsLocalMatte(model) || PortraitSettings.nativeTransparent(model));
                } catch (java.io.IOException failed) { invalid = "Background segmentation failed; previous portrait retained"; }
            }
            if (invalid != null) {
                failure = new JobFailure(invalid, true, false); // 결과 내용 문제 → 횟수에 넣음
            } else {
                png = processed;
            }
        } catch (OpenAiImageClient.ApiException e) {
            // 과금되지 않은 것이 확실하면 0, 요청이 닿았을 수 있으면(시간 초과·5xx) 예약액으로 보수 정산
            settle = e.billedUnknown ? reserve : 0;
            failure = apiFailure("", e);
        } finally {
            CostModel.ImageUsage u = usage;
            settleQuietly(job, rid, settle, job.kind().name().toLowerCase(java.util.Locale.ROOT), model,
                    u == null ? 0 : Math.max(0, u.textInput()), u == null ? 0 : Math.max(0, u.imageInput()),
                    u == null ? 0 : Math.max(0, u.output()), png != null);
        }
        if (failure != null) {
            throw failure;
        }
        if (png == null) {
            throw new JobFailure("알 수 없는 오류", true, false);
        }
        return new Generated(png, settle, System.currentTimeMillis() - started, usage);
    }

    /** 정산 + 사용 기록. 실패해도 예외를 던지지 않는다 (남은 예약은 다음 시작 때 예약액으로 정산됨). */
    private void settleQuietly(Job job, String rid, long amount, String kind, String model, long textIn, long imageIn,
                               long output, boolean ok) {
        try {
            await(plugin.db().call(() -> {
                plugin.storage().settle(rid, amount);
                plugin.storage().logUsage(job.player(), kind, model, textIn, imageIn, output, amount, ok);
                return null;
            }));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[ChacaPortrait] 정산 중 중단 (" + rid + ") — 다음 시작 때 예약액으로 정산됩니다");
        } catch (Exception e) {
            plugin.getLogger().warning("[ChacaPortrait] 정산 실패 (" + rid + "): " + e.getMessage());
        }
    }

    /** 쓰지 않은 예약 풀기 (유료 호출 전 중단). 실패해도 예외를 던지지 않는다. */
    private void releaseReservation(String rid) {
        try {
            await(plugin.db().call(() -> plugin.storage().settle(rid, 0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().warning("[ChacaPortrait] 예약 해제 실패 (" + rid + "): " + e.getMessage());
        }
    }

    private long budgetCap(PortraitSettings s) {
        return (long) Math.floor(s.budgetUsd * 1_000_000L);
    }

    private void notifyBudget(Job job) {
        plugin.main(() -> {
            if (job.reporter() != null) {
                kr.chacademy.portrait.cmd.PortraitCommands.report(job.reporter(), false, "일러스트 예산이 부족해요 (/서버관리 초상화 예산보기)");
            }
        });
    }

    /**
     * 확정 전 실패 처리 (어느 스레드든).
     * 다시 그리기: "생성 시작" 전이면 아이템 반환, 시작 후면 소모 처리 (반환 없음).
     * 자동: 일시적 실패는 횟수에 넣지 않고 간격을 두고 다시 대기열에 넣는다. 내용 문제만 횟수에 넣는다.
     * 횟수에 넣지 않는 그 밖의 실패는 {@link AutoBackoff}로 접속할 때마다 반복되지 않게 한다.
     */
    private void finishFailure(Job job, JobFailure f, Run ctx) {
        PortraitSettings s = plugin.settings();
        String error = f.getMessage();
        boolean auto = job.kind() == Kind.AUTO;
        boolean retry = auto && f.retryable && ctx.attempt < AUTO_RETRY_SECONDS.length && gate.running();
        plugin.getLogger().info("[ChacaPortrait] " + job.name() + " " + job.kind() + " 실패: " + error
                + (retry ? " (" + AUTO_RETRY_SECONDS[ctx.attempt] + "초 뒤 재시도 " + (ctx.attempt + 1) + "/" + AUTO_RETRY_SECONDS.length + ")" : ""));
        plugin.db().call(() -> {
            plugin.storage().recordAttempt(job.player(), auto && f.countAttempt, error);
            return null;
        });
        if (job.rerollToken() != null) {
            if (ctx.started) {
                plugin.rerolls().consumed(job.player(), job.rerollToken(), error);
            } else {
                plugin.rerolls().failed(job.player(), job.rerollToken(), error, f.budget, ctx.startUncertain);
            }
        }
        if (retry) {
            scheduleAutoRetry(job, ctx.attempt + 1);
        } else if (auto && !f.countAttempt) {
            backoff.failed(job.player(), System.currentTimeMillis(), LocalDate.now(ZONE).toString());
        }
        plugin.main(() -> {
            if (job.reporter() != null) {
                kr.chacademy.portrait.cmd.PortraitCommands.report(job.reporter(), false, job.name() + " 님 일러스트를 그리지 못했어요 (사유는 콘솔)");
            }
            if (auto && !f.quiet && !retry) {
                Player p = Bukkit.getPlayer(job.player());
                if (p != null) {
                    p.sendMessage(s.message("failed"));
                }
            }
        });
    }

    /** 자동 생성 재시도 예약. 그때 접속 중이 아니면 넣지 않는다 (다음 접속 때 처음부터 다시 확인). */
    private void scheduleAutoRetry(Job job, int attempt) {
        UUID id = job.player();
        retryPending.add(id);
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                retryPending.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p == null || !gate.running()) {
                    return;
                }
                Job next = jobFor(Kind.AUTO, p, job.request(), null, job.models(), null);
                if (next.skin().url() == null) {
                    return;
                }
                submit(next, null, attempt);
            }, AUTO_RETRY_SECONDS[attempt - 1] * 20L);
        } catch (RuntimeException e) {
            retryPending.remove(id); // 종료 중
        }
    }

    // ------------------------------------------------------------------ 검사·도구

    /** PNG인지, 크기 상한, (선택) 투명 배경인지. 문제 없으면 null. */
    static String validateResult(byte[] png, boolean requireTransparent) {
        if (png == null || png.length < 64 || png.length > MAX_RESULT_BYTES) {
            return "결과 이미지 크기 오류";
        }
        try {
            BufferedImage img = checkPng(png, 2048);
            if (requireTransparent && !img.getColorModel().hasAlpha()) {
                return "투명 배경이 아님";
            }
            if (requireTransparent && !transparentFrame(img)) {
                return "투명 배경이 아님";
            }
            return null;
        } catch (IOException e) {
            return "결과 이미지 오류: " + e.getMessage();
        }
    }

    /**
     * 배경이 지워졌는지 가장자리로 판단한다. 상반신 그림은 허리 아래가 잘려 아래쪽 가장자리·아래 귀퉁이에 몸이 닿는 것이 정상이므로
     * 아래쪽은 보지 않는다. 조건: 위쪽 두 귀퉁이가 투명, 맨 윗줄의 90% 이상이 투명, 양옆 세로줄의 위쪽 절반 중 60% 이상이 투명.
     * (투명 = 알파 16 이하)
     */
    static boolean transparentFrame(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (clearAt(img, 0, 0) == 0 || clearAt(img, w - 1, 0) == 0) {
            return false;
        }
        int top = 0;
        for (int x = 0; x < w; x++) {
            top += clearAt(img, x, 0);
        }
        if (top * 10L < w * 9L) {
            return false;
        }
        int half = Math.max(1, h / 2), left = 0, right = 0;
        for (int y = 0; y < half; y++) {
            left += clearAt(img, 0, y);
            right += clearAt(img, w - 1, y);
        }
        return left * 10L >= half * 6L && right * 10L >= half * 6L;
    }

    private static int clearAt(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) <= 16 ? 1 : 0;
    }

    /** PNG 헤더·크기 확인 후 디코드. 너무 큰 이미지는 디코드 전에 거부. */
    static BufferedImage checkPng(byte[] png, int maxSide) throws IOException {
        if (png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new IOException("PNG가 아님");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("읽을 수 없는 이미지");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > maxSide || h > maxSide) {
                    throw new IOException("이미지 크기 " + w + "x" + h);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String userHash(UUID id) {
        return sha256(("chacademi-portrait:" + id).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 메인 스레드: 플레이어 스킨을 읽어 작업을 만든다. */
    public static Job jobFor(Kind kind, Player p, String request, String token, List<String> models, CommandSender reporter) {
        return new Job(kind, p.getUniqueId(), nickname(p), SkinFetcher.refOf(p), request, token, models, reporter);
    }

    /** 메인 스레드: 완성·실패 알림에 쓸 한글 닉네임 (MagicCodexBridge.nicknameOf). 브리지가 없거나 낮으면 계정 이름 (#94). */
    private static String nickname(Player p) {
        var bridge = org.bukkit.Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge != null && bridge.isEnabled()) {
            try {
                if (bridge.getClass().getMethod("nicknameOf", org.bukkit.OfflinePlayer.class).invoke(bridge, p) instanceof String n && !n.isBlank()) return n;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { }
        }
        return p.getName();
    }

    /** 비동기 완료 후 메인 스레드에서 이어서. */
    public <T> void then(CompletableFuture<T> f, Consumer<T> onMain, Consumer<Throwable> onError) {
        f.whenComplete((v, ex) -> plugin.main(() -> {
            if (ex != null) {
                if (onError != null) {
                    onError.accept(ex);
                }
            } else {
                onMain.accept(v);
            }
        }));
    }
}
