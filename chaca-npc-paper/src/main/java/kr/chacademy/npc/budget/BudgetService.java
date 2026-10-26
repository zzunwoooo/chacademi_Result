package kr.chacademy.npc.budget;

import kr.chacademy.npc.ai.OpenAiClient;
import kr.chacademy.npc.config.Settings;
import kr.chacademy.npc.core.BudgetMath;
import kr.chacademy.npc.data.Storage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 3주 총예산·하루 한도 관리. 모든 AI 호출(플레이어 대화, 버튼 미리 생성, NPC끼리 잡담, 분위기 요약)은
 * 요청 전에 최대 비용을 예약하고, 끝나면 실제 사용량으로 정산한다.
 * 사용량을 모르는 경우(스트림 끊김)는 예약 금액 그대로 정산해 누락을 막는다.
 *
 * <p>OpenAI 프로젝트 지출 한도는 별개의 마지막 안전장치다 (반영 지연으로 소폭 초과할 수 있음).
 *
 * <p>2026-10-21 (오너 결정 6): 금액 한도는 budget.limit-enabled=false 로 꺼져 있다 (예약·정산·사용량 기록은 그대로).
 * 대신 플레이어 1인 하루 대화 횟수를 공유 DB 의 한 행(cnpc_usage)에 조건부 UPDATE 로 원자적으로 센다 → 같은 DB를 쓰는 서버끼리 합산.
 */
public final class BudgetService {

    public static final String SYSTEM = "*system*";

    /** 예약 한 건. settle()은 한 번만 효과가 있다. */
    public final class Reservation {
        private final String player;
        private final double amount;
        private final String day;
        private boolean settled;

        private Reservation(String player, double amount, String day) {
            this.player = player;
            this.amount = amount;
            this.day = day;
        }

        public double amount() {
            return amount;
        }
    }

    private final Supplier<Settings> settings;
    private final Storage storage;

    private volatile String today = "";
    private double spentBeforeToday;
    private double spentToday;
    private double reserved;          // 아직 정산 안 된 예약 합계 (전체)
    private final Map<String, Integer> callsToday = new ConcurrentHashMap<>();
    private volatile boolean aiEnabled = true;
    private volatile boolean aiSwitchTouched; // 불러오기 전에 관리자가 /서버관리 AI 를 썼으면 그 값을 따른다
    private volatile boolean loaded;
    private volatile String loadError;

    /** cnpc_meta 키: /서버관리 AI 끄기 상태를 재시작 뒤에도 유지한다. 값 "0" = 꺼짐. */
    private static final String META_AI = "ai_enabled";

    private long latencyCount;
    private long firstChunkSum;
    private long totalSum;
    private long slowCount;

    public BudgetService(Supplier<Settings> settings, Storage storage) {
        this.settings = settings;
        this.storage = storage;
    }

    /** 시작할 때 DB에서 사용량을 읽는다 (DB 스레드). 읽기 전에는 AI를 부르지 않는다. */
    public void loadFromDb() {
        String day = settings.get().today().toString();
        Storage.UsageSnapshot snap = storage.usageSnapshot(day);
        String aiSwitch = storage.meta(META_AI);
        if (!aiSwitchTouched && "0".equals(aiSwitch)) {
            aiEnabled = false;
        }
        synchronized (this) {
            today = day;
            spentBeforeToday = snap.spentBeforeToday();
            spentToday = snap.spentToday();
            callsToday.clear();
            callsToday.putAll(snap.callsToday());
            loaded = true;
            loadError = null;
        }
    }

    /** 테이블 준비·사용량 불러오기가 실패했다: AI는 계속 꺼진 상태(예약 불가)이고 /서버관리 AI 예산 에 이유를 보여준다. */
    public void markLoadFailed(String reason) {
        loadError = reason == null || reason.isBlank() ? "알 수 없는 오류" : reason;
    }

    /** 불러오기 실패 이유, 정상이면 null. */
    public String loadError() {
        return loadError;
    }

    public boolean isLoaded() {
        return loaded;
    }

    private synchronized void rollDay() {
        String now = settings.get().today().toString();
        if (!now.equals(today)) {
            spentBeforeToday += spentToday;
            spentToday = 0;
            callsToday.clear();
            latencyCount = firstChunkSum = totalSum = slowCount = 0;
            today = now;
        }
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    /** /서버관리 AI 켜기|끄기. DB에 저장해서 재시작해도 유지된다. */
    public void setAiEnabled(boolean enabled) {
        this.aiSwitchTouched = true;
        this.aiEnabled = enabled;
        storage.db().run(() -> storage.setMeta(META_AI, enabled ? "1" : "0"));
    }

    /** 그날 금액 한도. 금액 한도를 끈 상태(limit-enabled=false)면 무한대. */
    public synchronized double allowance() {
        rollDay();
        Settings s = settings.get();
        if (!s.budgetLimitEnabled) {
            return Double.POSITIVE_INFINITY;
        }
        return BudgetMath.dailyAllowance(s.budgetTotalUsd, spentBeforeToday, s.budgetDays,
                s.dayIndex(s.today()), s.testDailyUsd);
    }

    /** 예약 포함 상태. */
    public synchronized BudgetMath.Status status() {
        double a = allowance();
        return BudgetMath.status(spentToday + reserved, a, settings.get().reduceAtPercent);
    }

    /** 요청 전 비용 계산용 예상치 (캐시 할인 없이 보수적으로). */
    public double estimate(int inputChars, int maxOutputTokens) {
        Settings s = settings.get();
        long inTokens = (long) (inputChars * 1.1) + 64;
        return BudgetMath.cost(inTokens, 0, maxOutputTokens, s.priceInput, s.priceCached, s.priceOutput);
    }

    /**
     * 예약. 오늘 한도와 총예산 모두에 여유가 있어야 한다. 실패하면 null (AI를 부르지 말 것).
     */
    public synchronized Reservation reserve(String player, double amount) {
        if (!loaded || !aiEnabled) {
            return null;
        }
        rollDay();
        Settings s = settings.get();
        if (!s.budgetLimitEnabled) {
            reserved += amount;
            return new Reservation(player, amount, today);
        }
        double total = spentBeforeToday + spentToday + reserved;
        if (total + amount > s.budgetTotalUsd) {
            return null;
        }
        if (spentToday + reserved + amount > allowance()) {
            return null;
        }
        reserved += amount;
        return new Reservation(player, amount, today);
    }

    /**
     * 정산. usageKnown=false 거나 usage가 없으면 예약 금액으로 정산한다. 반환 = 기록한 비용.
     */
    public double settle(Reservation r, OpenAiClient.Usage usage, boolean usageKnown) {
        if (r == null) {
            return 0;
        }
        Settings s = settings.get();
        double cost;
        String day;
        synchronized (this) {
            if (r.settled) {
                return 0;
            }
            r.settled = true;
            reserved = Math.max(0, reserved - r.amount);
            cost = usageKnown && usage != null
                    ? BudgetMath.cost(usage.input(), usage.cached(), usage.output(), s.priceInput, s.priceCached, s.priceOutput)
                    : r.amount;
            rollDay();
            // 예약한 날과 정산하는 날이 다르면 (자정을 넘김) 오늘 사용으로 기록
            spentToday += cost;
            day = today;
        }
        long in = usage == null ? 0 : usage.input();
        long cached = usage == null ? 0 : usage.cached();
        long out = usage == null ? 0 : usage.output();
        storage.db().run(() -> storage.addUsage(day, r.player, SYSTEM.equals(r.player) ? 1 : 0, in, cached, out, cost));
        return cost;
    }

    /**
     * 이 플레이어가 오늘 더 부를 수 있는지. 부를 수 있으면 횟수를 1 올린다 (DB 스레드에서 원자적으로, 서버 간 공유).
     * 완료 값: true = 허용(횟수 올림), false = 오늘 한도 도달, null = DB 오류(부르지 말 것).
     */
    public CompletableFuture<Boolean> tryUseCall(String player) {
        rollDay();
        String day = today;
        int limit = settings.get().perPlayerDailyCalls;
        return storage.db().async(() -> storage.tryAddCall(day, player, limit)).thenApply(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                callsToday.merge(player, 1, Integer::sum); // 표시용 (/서버관리 AI)
            }
            return ok;
        });
    }

    /** AI를 부르지 못했거나 과금 없이 실패한 호출 되돌리기 (예약 실패·대화가 그새 닫힘·대기열 가득·API 오류). */
    public void refundCall(String player) {
        boolean[] done = {false};
        callsToday.computeIfPresent(player, (k, v) -> {
            if (v <= 0) {
                return v;
            }
            done[0] = true;
            return v - 1;
        });
        if (!done[0]) {
            return; // 날짜가 바뀌었거나 이미 0: DB에 음수 줄이 생기지 않게 한다
        }
        String day = today;
        storage.db().run(() -> storage.refundCall(day, player));
    }

    public int callsToday(String player) {
        rollDay();
        return callsToday.getOrDefault(player, 0);
    }

    public synchronized void recordLatency(long firstChunkMs, long totalMs) {
        if (firstChunkMs < 0) {
            return;
        }
        latencyCount++;
        firstChunkSum += firstChunkMs;
        totalSum += totalMs;
        if (firstChunkMs > 3000) {
            slowCount++;
        }
    }

    public synchronized double spentToday() {
        rollDay();
        return spentToday;
    }

    public synchronized double spentTotal() {
        rollDay();
        return spentBeforeToday + spentToday;
    }

    public synchronized double reserved() {
        return reserved;
    }

    public synchronized String latencySummary() {
        if (latencyCount == 0) {
            return "기록 없음";
        }
        return String.format("첫 글자 평균 %.2f초, 전체 평균 %.2f초, 3초 초과 %d/%d회",
                firstChunkSum / 1000.0 / latencyCount, totalSum / 1000.0 / latencyCount, slowCount, latencyCount);
    }

    public int totalCallsToday() {
        rollDay();
        int sum = 0;
        for (Map.Entry<String, Integer> e : callsToday.entrySet()) {
            if (!SYSTEM.equals(e.getKey())) {
                sum += e.getValue();
            }
        }
        return sum;
    }
}
