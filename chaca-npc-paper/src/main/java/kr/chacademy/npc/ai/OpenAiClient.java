package kr.chacademy.npc.ai;

import kr.chacademy.npc.core.Json;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * OpenAI Responses API 호출 (스트리밍). Bukkit과 무관하게 백그라운드 스레드에서만 돈다.
 *
 * <p>대기열은 길이가 정해져 있다. 플레이어 대화({@link #player})와 백그라운드 작업({@link #background}: 버튼 미리 생성·
 * NPC 잡담·분위기 요약)은 스레드와 대기열이 따로이고, 플레이어 쪽이 붐비면 백그라운드 작업부터 버린다.
 * 요청은 대기열에서 꺼내 실제로 보내기 직전에 "아직 필요한지·기한이 남았는지"를 다시 확인하고,
 * 아니면 보내지 않는다 (API 호출·과금 없음).
 */
public final class OpenAiClient {

    public record Request(String instructions, List<Map<String, Object>> input, Map<String, Object> schema,
                          String schemaName, int maxOutputTokens, String cacheKey) {
    }

    public record Usage(long input, long cached, long output) {
        public static final Usage ZERO = new Usage(0, 0, 0);
    }

    /**
     * REJECTED = 대기열이 가득 찼거나 차단기가 열려 있어 받지 않음, SKIPPED = 대기 중 기한이 지났거나 더 이상 필요 없어 보내지 않음.
     * 둘 다 API를 부르지 않았다 (과금 없음).
     */
    public enum Status { OK, TIMEOUT, ERROR, NO_KEY, REJECTED, SKIPPED }

    static final String ERR_CIRCUIT = "circuit open";
    static final String ERR_SHED = "background shed";
    static final String ERR_QUEUE = "queue full";

    /**
     * usage = 이번 요청(재시도 포함)에서 확인된 사용량 합계. usageKnown=false 면 스트림이 끊겨 사용량을 모름
     * (호출자는 예약 금액으로 정산해야 한다). http = 마지막 HTTP 상태 코드 (응답을 못 받았으면 0).
     */
    public record Result(Status status, String text, Usage usage, String error, long firstChunkMs, long totalMs,
                         boolean usageKnown, int http) {

        /** API에 보내지 않은 결과 (키 없음·대기열 가득·건너뜀): 과금 없음 = 사용량 0으로 확정. */
        static Result notSent(Status status, String error) {
            return new Result(status, "", Usage.ZERO, error, -1, 0, true, 0);
        }

        Result withUsage(Usage u, boolean known) {
            return new Result(status, text, u, error, firstChunkMs, totalMs, known, http);
        }

        public boolean ok() {
            return status == Status.OK;
        }

        /** 실패했고 과금이 없었던 것이 확실함 (보내지 않았거나 200 응답 전에 실패). 플레이어 하루 횟수를 돌려줘도 된다. */
        public boolean notBilled() {
            return status != Status.OK && usageKnown && Usage.ZERO.equals(usage) && (text == null || text.isEmpty());
        }

        /** 실패 종류 (집계·로그용). */
        public String category() {
            return switch (status) {
                case OK -> "ok";
                case NO_KEY -> "no_key";
                case TIMEOUT -> "timeout";
                case SKIPPED -> "skipped";
                case REJECTED -> ERR_CIRCUIT.equals(error) ? "circuit_open" : ERR_SHED.equals(error) ? "background_shed" : "queue_full";
                case ERROR -> http == 401 || http == 403 ? "auth" : http == 429 ? "rate_limit" : http >= 500 ? "server"
                        : http >= 400 ? "http_" + http : http == 200 ? "stream" : "network";
            };
        }
    }

    public static final class Config {
        public String apiKey;
        public String baseUrl;
        public String model;
        public String reasoningEffort;
        /** 요청 하나가 대기열에 들어간 순간부터 쓸 수 있는 최대 시간 (백그라운드 작업 기준, 플레이어 대화는 더 짧게 끊는다). */
        public int hardTimeoutSeconds;
        public int maxConcurrent;
        /** 플레이어 대화 대기열 길이 (넘으면 바로 거절 → 고정 대사). */
        public int maxQueue;
        /** 연속 실패가 이만큼이면 차단기를 연다 (0 = 끔). */
        public int circuitFailures;
        public int circuitCooloffSeconds;
        public String moderationModel;
    }

    /** 플레이어 화면이 포기한 뒤에도 사용량을 받으려고 스트림을 더 붙잡아 두는 시간. 넘으면 끊고 예약 금액으로 정산한다. */
    static final long LATE_GRACE_MS = 7_000L;
    /** 백그라운드 작업이 대기열에서 이보다 오래 기다렸으면 보내지 않는다. */
    static final long BACKGROUND_MAX_WAIT_MS = 20_000L;
    private static final int BACKGROUND_QUEUE = 16;

    private final Logger log;
    private volatile Config config;
    private final HttpClient http;
    private final ThreadPoolExecutor playerPool;
    private final ThreadPoolExecutor backgroundPool;
    private final ScheduledExecutorService watchdog;
    private final AtomicInteger running = new AtomicInteger();
    private final AiHealth health;
    private volatile boolean shutdown;
    private volatile boolean sendReasoning = true;
    private volatile boolean sendCacheKey = true;

    public OpenAiClient(Config config, Logger log) {
        this.config = config;
        this.log = log;
        this.health = new AiHealth(config.circuitFailures, config.circuitCooloffSeconds * 1000L);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_2)
                .build();
        int threads = Math.max(1, config.maxConcurrent);
        this.playerPool = pool(threads, Math.max(1, config.maxQueue), "ChacaNPC-AI");
        // 백그라운드 작업은 적은 스레드로 따로 돌려 플레이어 대화 자리를 차지하지 않게 한다
        this.backgroundPool = pool(Math.max(1, Math.min(3, threads / 5)), BACKGROUND_QUEUE, "ChacaNPC-AI-bg");
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChacaNPC-AI-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /** reload 때 키·모델 등 교체 (동시 요청 수·대기열 길이는 재시작해야 바뀜). */
    public void updateConfig(Config c) {
        this.config = c;
        this.sendReasoning = true;
        this.sendCacheKey = true;
        this.health.configure(c.circuitFailures, c.circuitCooloffSeconds * 1000L);
    }

    public int queueSize() {
        return playerPool.getQueue().size() + backgroundPool.getQueue().size();
    }

    public int runningCount() {
        return running.get();
    }

    /** 차단기가 열려 지금은 호출을 받지 않는지 (상태는 바꾸지 않는다). */
    public boolean isCircuitBlocked() {
        return health.blocked(System.currentTimeMillis());
    }

    /** /서버관리 AI 예산 용: 최근 1시간 종류별 실패 수. */
    public String failureSummary() {
        return AiHealth.format(health.lastHour(System.currentTimeMillis()));
    }

    /** /서버관리 AI 예산 용: 차단기 상태. */
    public String circuitSummary() {
        return health.isOpen() ? "열림 (AI 호출 쉬는 중, 연속 실패 " + health.consecutiveFailures() + "회)"
                : "닫힘 (연속 실패 " + health.consecutiveFailures() + "회)";
    }

    /** 진행 중 요청을 끊고 최대 3초 기다린다 (정산 콜백이 DB 종료 전에 돌 수 있게). */
    public void shutdown() {
        shutdown = true;
        List<Runnable> dropped = new ArrayList<>(playerPool.shutdownNow());
        dropped.addAll(backgroundPool.shutdownNow());
        watchdog.shutdownNow();
        // 대기열에 남아 있던 요청도 "보내지 않음"으로 끝낸다 (기다리던 쪽의 예약 정산 콜백이 돌 수 있게)
        for (Runnable queued : dropped) {
            try {
                queued.run();
            } catch (RuntimeException ignored) {
                // 꺼지는 중
            }
        }
        try {
            if (playerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                backgroundPool.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 플레이어 대화 요청.
     *
     * @param deadlineAt  화면이 이 요청을 포기하는 시각(ms). 대기열에서 이 시각을 넘기면 보내지 않는다.
     * @param stillWanted 보내기 직전에 AI 스레드에서 확인: false 면 보내지 않는다 (창을 닫았거나 다른 요청으로 넘어감).
     */
    public CompletableFuture<Result> player(Request req, long deadlineAt, BooleanSupplier stillWanted) {
        return submit(req, false, deadlineAt, stillWanted);
    }

    /** 백그라운드 요청 (버튼 미리 생성·NPC 잡담·분위기 요약). 플레이어 대화가 붐비면 받지 않는다. */
    public CompletableFuture<Result> background(Request req) {
        return submit(req, true, 0L, null);
    }

    private CompletableFuture<Result> submit(Request req, boolean background, long deadlineAt, BooleanSupplier stillWanted) {
        long enqueuedAt = System.currentTimeMillis();
        Config c = config;
        if (c.apiKey == null || c.apiKey.isBlank()) {
            return done(Result.notSent(Status.NO_KEY, "API 키가 없습니다"));
        }
        if (shutdown) {
            return CompletableFuture.completedFuture(Result.notSent(Status.SKIPPED, "shutdown"));
        }
        if (!health.allow(enqueuedAt)) {
            return done(Result.notSent(Status.REJECTED, ERR_CIRCUIT));
        }
        if (background && (!playerPool.getQueue().isEmpty() || playerPool.getActiveCount() >= playerPool.getCorePoolSize())) {
            return done(Result.notSent(Status.REJECTED, ERR_SHED));
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        Runnable task = () -> {
            Result r;
            try {
                r = run(req, background, enqueuedAt, deadlineAt, stillWanted);
            } catch (Throwable t) {
                r = new Result(Status.ERROR, "", Usage.ZERO, "internal " + t, -1,
                        System.currentTimeMillis() - enqueuedAt, true, 0);
            }
            record(r);
            future.complete(r);
        };
        try {
            (background ? backgroundPool : playerPool).execute(task);
        } catch (RejectedExecutionException ex) {
            // 백그라운드 대기열이 찬 것은 "버림"으로만 센다 (경고 로그·실패 집계 대상이 아님)
            return done(Result.notSent(shutdown ? Status.SKIPPED : Status.REJECTED,
                    shutdown ? "shutdown" : background ? ERR_SHED : ERR_QUEUE));
        }
        return future;
    }

    private CompletableFuture<Result> done(Result r) {
        record(r);
        return CompletableFuture.completedFuture(r);
    }

    private static boolean wanted(BooleanSupplier stillWanted) {
        try {
            return stillWanted == null || stillWanted.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 대기열에서 꺼낸 뒤 (AI 스레드). 보내기 직전에 기한·필요 여부를 다시 확인한다. */
    private Result run(Request req, boolean background, long enqueuedAt, long deadlineAt, BooleanSupplier stillWanted) {
        long now = System.currentTimeMillis();
        if (shutdown) {
            return Result.notSent(Status.SKIPPED, "shutdown");
        }
        if (deadlineAt > 0 && now >= deadlineAt) {
            return Result.notSent(Status.SKIPPED, "대기 중 기한이 지남");
        }
        if (background && now - enqueuedAt > BACKGROUND_MAX_WAIT_MS) {
            return Result.notSent(Status.SKIPPED, "대기 시간이 너무 김");
        }
        if (!wanted(stillWanted)) {
            return Result.notSent(Status.SKIPPED, "더 이상 필요 없음");
        }
        // 대기 시간도 기한에 포함: 대기열에 들어간 순간부터 hard-timeout, 플레이어 대화는 화면 기한 + 여유까지만
        long limitAt = enqueuedAt + Math.max(5, config.hardTimeoutSeconds) * 1000L;
        if (deadlineAt > 0) {
            limitAt = Math.min(limitAt, deadlineAt + LATE_GRACE_MS);
        }
        running.incrementAndGet();
        try {
            Result r = doStream(req, enqueuedAt, limitAt);
            long in = r.usage().input(), cached = r.usage().cached(), out = r.usage().output();
            boolean unknown = !r.usageKnown();
            // 다시 보내기는 한 번만, 200 응답을 받기 전(과금 없음)에 실패했고 다시 해 볼 만한 경우에만
            boolean paramRetry = r.notBilled() && r.error() != null && r.error().startsWith("RETRY_PARAM");
            boolean retry = paramRetry || (r.notBilled() && r.status() == Status.ERROR && retryable(r));
            if (retry && !shutdown) {
                if (!paramRetry) {
                    sleep(250 + ThreadLocalRandom.current().nextInt(400)); // 살짝 흩어서 다시
                }
                long t = System.currentTimeMillis();
                boolean inTime = deadlineAt > 0 ? t < deadlineAt - 1000 : t < limitAt - 3000;
                if (inTime && !shutdown && wanted(stillWanted)) {
                    r = doStream(req, enqueuedAt, limitAt);
                    in += r.usage().input(); cached += r.usage().cached(); out += r.usage().output();
                    unknown |= !r.usageKnown();
                }
            }
            return r.withUsage(new Usage(in, cached, out), !unknown);
        } finally {
            running.decrementAndGet();
        }
    }

    /** 다시 보내 볼 만한 실패: 429·5xx, 또는 응답을 받기 전 연결 실패. 4xx(잘못된 키·모델·요청)는 다시 보내지 않는다. */
    static boolean retryable(Result r) {
        return r.http() == 429 || r.http() >= 500 || r.http() == 0;
    }

    /** 요청 하나가 끝날 때마다: 실패 집계·차단기·로그 (debug 설정과 무관하게 항상). */
    private void record(Result r) {
        if (shutdown) {
            return; // 꺼지면서 끊긴 요청은 실패로 세지 않는다
        }
        long now = System.currentTimeMillis();
        if (r.ok()) {
            if (health.onSuccess() == AiHealth.Transition.CLOSED) {
                log.info("[ChacaNPC] AI 응답이 돌아왔습니다 — AI 호출을 다시 엽니다.");
            }
            return;
        }
        String cat = r.category();
        if (cat.equals("skipped") || cat.equals("background_shed")) {
            return; // 보내지 않기로 한 것 — 실패가 아님
        }
        boolean local = cat.equals("circuit_open") || cat.equals("queue_full") || cat.equals("no_key");
        boolean first = health.recordFailure(cat, now, !cat.equals("circuit_open"));
        if (!local && health.onFailure(now) == AiHealth.Transition.OPENED) {
            log.warning("[ChacaNPC] AI 호출이 연속 " + health.consecutiveFailures() + "회 실패 — "
                    + config.circuitCooloffSeconds + "초 동안 AI를 부르지 않고 고정 대사로 대답합니다. 마지막 오류: "
                    + cat + " " + shortError(r));
        }
        if (cat.equals("circuit_open")) {
            return;
        }
        if (first) {
            log.warning("[ChacaNPC] AI 실패 (" + cat + "): " + describe(cat) + shortError(r));
            return;
        }
        String summary = health.summaryIfDue(now);
        if (summary != null) {
            log.warning("[ChacaNPC] 최근 AI 실패: " + summary + " — /서버관리 AI 예산 에서 최근 1시간 집계를 볼 수 있어요.");
        }
    }

    private static String describe(String cat) {
        return switch (cat) {
            case "no_key" -> "OpenAI API 키가 없습니다. 환경변수 CHACANPC_OPENAI_KEY 를 설정하세요. ";
            case "auth" -> "OpenAI API 키가 잘못됐거나 권한이 없습니다. 키와 프로젝트 권한을 확인하세요. ";
            case "queue_full" -> "AI 대기열이 가득 찼습니다 (openai.max-concurrent / max-queue). ";
            case "rate_limit" -> "OpenAI 요청 한도(429)에 걸렸습니다. ";
            case "timeout" -> "기한 안에 응답이 오지 않았습니다. ";
            case "http_400", "http_404" -> "요청이 거절됐습니다 — config.yml 의 openai.model / base-url 을 확인하세요. ";
            default -> "";
        };
    }

    private static String shortError(Result r) {
        String e = r.error() == null ? "" : r.error().replaceAll("\\s+", " ");
        return (r.http() > 0 ? "HTTP " + r.http() + " " : "") + (e.length() > 200 ? e.substring(0, 200) : e);
    }

    /** start = 대기열에 들어간 시각 (걸린 시간 계산 기준), limitAt = 이 시각이 되면 스트림을 끊는다. */
    private Result doStream(Request req, long start, long limitAt) {
        Config c = config;
        if (c.apiKey == null || c.apiKey.isBlank()) {
            return Result.notSent(Status.NO_KEY, "API 키가 없습니다");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", c.model);
        body.put("instructions", req.instructions());
        body.put("input", req.input());
        body.put("max_output_tokens", req.maxOutputTokens());
        body.put("stream", Boolean.TRUE);
        body.put("store", Boolean.FALSE);
        if (sendReasoning && c.reasoningEffort != null && !c.reasoningEffort.isBlank()) {
            body.put("reasoning", Json.map("effort", c.reasoningEffort));
        }
        if (sendCacheKey && req.cacheKey() != null) {
            body.put("prompt_cache_key", req.cacheKey());
        }
        if (req.schema() != null) {
            body.put("text", Json.map("format", Json.map(
                    "type", "json_schema",
                    "name", req.schemaName() == null ? "reply" : req.schemaName(),
                    "schema", req.schema(),
                    "strict", Boolean.TRUE)));
        }

        long hardMs = Math.max(1000, limitAt - System.currentTimeMillis());
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(c.baseUrl + "/responses"))
                .timeout(Duration.ofMillis(hardMs))
                .header("Authorization", "Bearer " + c.apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();

        AtomicReference<InputStream> streamRef = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> totalGuard = watchdog.schedule(() -> {
            timedOut.set(true);
            closeQuietly(streamRef.get());
            worker.interrupt();
        }, hardMs, TimeUnit.MILLISECONDS);

        StringBuilder text = new StringBuilder();
        Usage usage = Usage.ZERO;
        long firstMs = -1;
        // 200 응답을 받은 뒤에는 response.completed 가 오기 전까지 사용량을 "모름"으로 본다 (과금됐을 수 있음)
        boolean got200 = false;
        try {
            HttpResponse<InputStream> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            streamRef.set(resp.body());
            if (timedOut.get()) {
                closeQuietly(resp.body());
                return new Result(Status.TIMEOUT, "", Usage.ZERO, "timeout", -1, System.currentTimeMillis() - start, false,
                        resp.statusCode());
            }
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                return handleHttpError(resp.statusCode(), err, start);
            }
            got200 = true;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) {
                        continue;
                    }
                    Map<String, Object> ev;
                    try {
                        ev = Json.parseObject(data);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    String type = Json.str(ev, "type");
                    if (type == null) {
                        continue;
                    }
                    switch (type) {
                        case "response.output_text.delta" -> {
                            String delta = Json.str(ev, "delta");
                            if (delta != null && !delta.isEmpty()) {
                                if (firstMs < 0) {
                                    firstMs = System.currentTimeMillis() - start;
                                }
                                text.append(delta);
                            }
                        }
                        case "response.completed", "response.incomplete" -> {
                            usage = readUsage(Json.obj(ev.get("response")));
                            if ("response.incomplete".equals(type)) {
                                Map<String, Object> det = Json.obj(Json.obj(ev.get("response")).get("incomplete_details"));
                                log.fine("[ChacaNPC] 응답이 잘림: " + Json.str(det, "reason")
                                        + " — max-output-tokens를 늘려 보세요");
                            }
                            return new Result(Status.OK, text.toString(), usage, null, firstMs,
                                    System.currentTimeMillis() - start, true, 200);
                        }
                        case "response.failed", "error" -> {
                            String msg = data.length() > 300 ? data.substring(0, 300) : data;
                            return new Result(Status.ERROR, text.toString(), usage, "API: " + msg, firstMs,
                                    System.currentTimeMillis() - start, false, 200);
                        }
                        default -> {
                            // 다른 이벤트는 무시
                        }
                    }
                }
            }
            if (timedOut.get()) {
                return new Result(Status.TIMEOUT, text.toString(), usage, "timeout", firstMs,
                        System.currentTimeMillis() - start, false, 200);
            }
            // completed 이벤트 없이 끝남: 받은 만큼이라도 돌려주되 사용량은 모름
            return new Result(text.length() > 0 ? Status.OK : Status.ERROR, text.toString(), usage,
                    text.length() > 0 ? null : "stream ended", firstMs, System.currentTimeMillis() - start, false, 200);
        } catch (Exception ex) {
            if (timedOut.get() || ex instanceof java.net.http.HttpTimeoutException) {
                return new Result(Status.TIMEOUT, text.toString(), usage, "timeout", firstMs,
                        System.currentTimeMillis() - start, !got200, got200 ? 200 : 0);
            }
            return new Result(Status.ERROR, text.toString(), usage, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    firstMs, System.currentTimeMillis() - start, !got200, got200 ? 200 : 0);
        } finally {
            totalGuard.cancel(false);
            Thread.interrupted(); // 워치독이 건 인터럽트 정리
        }
    }

    private Result handleHttpError(int code, String body, long start) {
        String lower = body.toLowerCase();
        long ms = System.currentTimeMillis() - start;
        if (code == 400 && sendReasoning && lower.contains("reasoning")) {
            sendReasoning = false;
            log.warning("[ChacaNPC] 이 모델은 reasoning 설정을 받지 않아 빼고 다시 보냅니다.");
            return new Result(Status.ERROR, "", Usage.ZERO, "RETRY_PARAM reasoning", -1, ms, true, code);
        }
        if (code == 400 && sendCacheKey && lower.contains("prompt_cache_key")) {
            sendCacheKey = false;
            log.warning("[ChacaNPC] prompt_cache_key를 받지 않아 빼고 다시 보냅니다.");
            return new Result(Status.ERROR, "", Usage.ZERO, "RETRY_PARAM prompt_cache_key", -1, ms, true, code);
        }
        // 로그는 record() 가 종류별로 묶어서 남긴다 (401 이 플레이어 한마디마다 찍히지 않게)
        String shortBody = body.length() > 400 ? body.substring(0, 400) : body;
        return new Result(Status.ERROR, "", Usage.ZERO, shortBody, -1, ms, true, code);
    }

    private static Usage readUsage(Map<String, Object> response) {
        Map<String, Object> u = response == null ? null : Json.obj(response.get("usage"));
        if (u == null) {
            return Usage.ZERO;
        }
        long in = Json.num(u, "input_tokens", 0);
        long out = Json.num(u, "output_tokens", 0);
        long cached = Json.num(Json.obj(u.get("input_tokens_details")), "cached_tokens", 0);
        return new Usage(in, cached, out);
    }

    /** 입력 검사 (moderation). 실패하면 통과로 본다. */
    public CompletableFuture<Boolean> moderate(String text) {
        Config c = config;
        if (c.apiKey == null || c.apiKey.isBlank() || c.moderationModel == null || c.moderationModel.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        Map<String, Object> body = Json.map("model", c.moderationModel, "input", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.baseUrl + "/moderations"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + c.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        return false;
                    }
                    List<Object> results = Json.arr(Json.parseObject(resp.body()).get("results"));
                    if (results == null || results.isEmpty()) {
                        return false;
                    }
                    return Boolean.TRUE.equals(Json.obj(results.get(0)).get("flagged"));
                })
                .exceptionally(ex -> false);
    }

    /** 메시지 하나 만들기 도우미. */
    public static Map<String, Object> message(String role, String content) {
        return Json.map("role", role, "content", content);
    }

    public static List<Map<String, Object>> messages() {
        return new ArrayList<>();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // 무시
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
