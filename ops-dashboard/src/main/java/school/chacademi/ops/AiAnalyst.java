package school.chacademi.ops;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 현재 상태(하트비트 추이·경보·최근 경고/오류 로그·spark 값과 링크)를 OpenAI 에 보내 쉬운 한국어 분석을 받는다.
 * 비용 절약: 버튼을 누를 때만 (ai.auto-on-alarm=true 면 새 빨간 경보가 생길 때도, 최소 간격 지켜서). 하루 횟수 상한.
 * 요청 형식은 ChacaNPC 와 같은 Responses API (/responses).
 */
final class AiAnalyst implements AutoCloseable {
    record Analysis(long at, String trigger, String text, boolean ok, String ip) { }

    static final String INSTRUCTIONS = String.join("\n",
            "너는 마인크래프트 서버(차카데미, Paper 1.21.4 서버 여러 개 + Velocity 프록시, Windows PC 한 대) 운영 도우미다.",
            "운영자는 개발자가 아니다. 휴대폰으로 급히 본다. 쉬운 한국어, 짧게.",
            "형식:",
            "1) 첫 줄: 결론 한 문장. 예) \"야생 서버에서 문제가 발생했습니다: 몹이 너무 많아 렉이 걸리고 있습니다.\" 문제가 없으면 \"지금은 큰 문제가 없습니다.\"",
            "2) '근거' 2~4줄: 어떤 숫자/로그 줄을 보고 그렇게 판단했는지.",
            "3) '지금 할 일' 1~3개: 쉬운 행동. 재시작·정지처럼 접속자가 끊기는 일은 '(접속자 끊김 주의)' 를 붙인다.",
            "모르는 것은 추측이라고 말한다. spark 링크는 직접 열 수 없으니 '링크를 열어 확인' 이라고만 한다. 비밀번호·키를 묻지 않는다.",
            "기준: TPS 20 이 정상, 18 아래는 느려짐, 15 아래는 렉. MSPT 50ms 넘으면 렉. 힙 메모리 90% 넘게 오래 차 있으면 위험.");

    private final Config config;
    private final Monitor monitor;
    private final ActionLog log;
    private final ZoneId zone;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> Monitor.daemon(r, "ops-ai"));
    private volatile Analysis last;
    private volatile boolean running;
    private volatile long lastAutoAt;
    private volatile long lastManualAt;
    private LocalDate countDay;
    private int countToday;
    private boolean sendReasoning = true;

    AiAnalyst(Config config, Monitor monitor, ActionLog log) {
        this.config = config;
        this.monitor = monitor;
        this.log = log;
        this.zone = ZoneId.of(config.zone);
    }

    boolean available() { return config.aiEnabled && !config.aiKey.isBlank(); }
    Analysis last() { return last; }
    boolean running() { return running; }

    synchronized int usedToday() {
        LocalDate today = LocalDate.now(zone);
        if (!today.equals(countDay)) { countDay = today; countToday = 0; }
        return countToday;
    }

    private synchronized boolean reserve() {
        if (usedToday() >= config.aiMaxPerDay) return false;
        countToday++;
        return true;
    }

    /** 버튼. 이미 도는 중이거나 한도면 이유를 돌려준다 (null = 시작함). */
    String requestManual(String ip, String focus) {
        if (!available()) return "AI 키가 설정되지 않았습니다 (ai.api-key 또는 환경변수 CHACA_OPS_OPENAI_KEY / CHACANPC_OPENAI_KEY)";
        if (running) return "이미 분석 중입니다";
        if (System.currentTimeMillis() - lastManualAt < 20_000) return "20초 뒤에 다시 눌러 주세요";
        if (!reserve()) return "오늘 AI 분석 횟수(" + config.aiMaxPerDay + "번)를 다 썼습니다";
        lastManualAt = System.currentTimeMillis();
        start("버튼", ip, focus);
        return null;
    }

    /** 새 빨간 경보 (ai.auto-on-alarm=true 일 때만). */
    void onAlarms(List<Alarms.Alarm> fresh) {
        if (!config.aiAuto || !available() || running) return;
        if (System.currentTimeMillis() - lastAutoAt < config.aiAutoIntervalMillis) return;
        if (!reserve()) return;
        lastAutoAt = System.currentTimeMillis();
        StringBuilder why = new StringBuilder("자동(경보: ");
        for (int i = 0; i < fresh.size() && i < 3; i++) why.append(i == 0 ? "" : ", ").append(fresh.get(i).message());
        start(why.append(")").toString(), "auto", "");
    }

    private void start(String trigger, String ip, String focus) {
        running = true;
        String context = context(monitor, focus, zone);
        worker.execute(() -> {
            Analysis a;
            try {
                a = new Analysis(System.currentTimeMillis(), trigger, call(context), true, ip);
            } catch (Exception e) {
                a = new Analysis(System.currentTimeMillis(), trigger, "AI 분석 실패: " + e.getMessage(), false, ip);
            } finally {
                running = false;
            }
            last = a;
            log.write(ip, "AI 분석", focus == null || focus.isBlank() ? "전체" : focus, trigger, a.ok() ? "완료" : a.text());
        });
    }

    /** 순수에 가까운 함수: 모니터 상태 → AI 에 보낼 글. (테스트에서는 build 를 직접 부름) */
    static String context(Monitor monitor, String focus, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        sb.append("지금 시각: ").append(LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        if (focus != null && !focus.isBlank()) sb.append("운영자가 특히 보고 싶은 서버: ").append(focus).append('\n');
        Monitor.Host h = monitor.host();
        sb.append("호스팅 PC: CPU ").append(n(h.cpu())).append("%, 메모리 ").append(h.memTotalMb() - h.memFreeMb()).append('/')
                .append(h.memTotalMb()).append("MB, 디스크 남은 ").append(h.diskFreeGb()).append("GB\n");
        sb.append("공유 DB: ").append(monitor.dbStatus()).append('\n');
        List<Alarms.Alarm> alarms = monitor.alarms();
        sb.append("\n[규칙 경보] ").append(alarms.isEmpty() ? "없음" : "").append('\n');
        for (Alarms.Alarm a : alarms) sb.append("- ").append(a.message()).append('\n');
        for (Monitor.View v : monitor.views()) {
            sb.append("\n[서버 ").append(v.server.id()).append(" / ").append(v.server.label()).append("] 상태=").append(v.status);
            McPing.Result p = v.ping;
            sb.append(", 접속포트=").append(p.up() ? "응답(" + p.latencyMs() + "ms, 접속 " + p.online() + "/" + p.max() + ")" : "응답 없음(" + p.error() + ")").append('\n');
            Alarms.Heartbeat hb = v.hb;
            if (hb != null) {
                sb.append("  하트비트(").append(hb.source()).append(", ").append((now - hb.updatedAt()) / 1000).append("초 전, state=").append(hb.state())
                        .append("): TPS 10초 ").append(n(hb.tps10s())).append(" / 1분 ").append(n(hb.tps1m())).append(" / 5분 ").append(n(hb.tps5m()))
                        .append(" / 15분 ").append(n(hb.tps15m())).append(", MSPT 평균 ").append(n(hb.msptMean())).append(" 최대 ").append(n(hb.msptMax()))
                        .append(" p95 ").append(n(hb.msptP95())).append(", CPU 프로세스 ").append(n(hb.cpuProcess())).append("% 시스템 ").append(n(hb.cpuSystem()))
                        .append("%, 힙 ").append(hb.heapUsedMb()).append('/').append(hb.heapMaxMb()).append("MB, 접속 ").append(hb.online())
                        .append(", 청크 ").append(hb.chunks()).append(", 엔티티 ").append(hb.entities())
                        .append(", 켜진 지 ").append(hb.startedAt() > 0 ? (now - hb.startedAt()) / 60000 + "분" : "?").append('\n');
            } else if (v.server.paper()) sb.append("  하트비트: 기록 없음\n");
            sb.append("  최근 1시간 추이: ").append(trend(v.history(Monitor.HISTORY))).append('\n');
            if (v.tail != null) {
                if (!v.tail.sparkLink().isEmpty()) sb.append("  최근 spark 결과 링크: ").append(v.tail.sparkLink()).append('\n');
                List<LogTail.Line> problems = v.tail.problems(isFocus(focus, v) ? 40 : 15);
                sb.append("  최근 1분 ERROR 줄 수: ").append(v.tail.errorsLastMinute(now)).append('\n');
                if (!problems.isEmpty()) {
                    sb.append("  최근 경고/오류 로그 (오래된 것부터):\n");
                    for (LogTail.Line l : problems) sb.append("    ").append(clip(l.text(), 300)).append('\n');
                }
            }
        }
        String s = sb.toString();
        return s.length() > 24_000 ? s.substring(0, 24_000) + "\n(길어서 잘림)" : s;
    }

    private static boolean isFocus(String focus, Monitor.View v) {
        return focus != null && focus.equals(v.server.id());
    }

    /** 1시간 기록 → "TPS 최저 12.1 평균 19.3, MSPT 최고 80 …" 한 줄. */
    static String trend(List<Monitor.Point> pts) {
        double tMin = Double.MAX_VALUE, tSum = 0, mMax = -1, cMax = -1, hMax = -1;
        int tN = 0, oMax = -1, down = 0;
        for (Monitor.Point p : pts) {
            if (p.tps() >= 0) { tMin = Math.min(tMin, p.tps()); tSum += p.tps(); tN++; }
            mMax = Math.max(mMax, p.mspt());
            cMax = Math.max(cMax, p.cpu());
            hMax = Math.max(hMax, p.heap());
            oMax = Math.max(oMax, p.online());
            if (p.tps() < 0 && p.online() < 0) down++;
        }
        if (pts.isEmpty()) return "기록 없음";
        StringBuilder sb = new StringBuilder();
        if (tN > 0) sb.append("TPS 최저 ").append(n(tMin)).append(" 평균 ").append(n(tSum / tN)).append(", ");
        sb.append("MSPT 최고 ").append(n(mMax)).append(", CPU 최고 ").append(n(cMax)).append("%, 힙 최고 ").append(n(hMax))
                .append("%, 접속 최고 ").append(oMax).append(", 응답 없던 표본 ").append(down).append('/').append(pts.size());
        return sb.toString();
    }

    static String n(double v) {
        return v < 0 ? "?" : String.format(Locale.ROOT, "%.1f", v);
    }

    static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }

    private String call(String context) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.aiModel);
            body.put("instructions", INSTRUCTIONS);
            body.put("input", context);
            body.put("max_output_tokens", config.aiMaxOutput);
            body.put("store", Boolean.FALSE);
            if (sendReasoning && !config.aiReasoning.isBlank()) body.put("reasoning", Json.map("effort", config.aiReasoning));
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.aiBaseUrl + "/responses"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + config.aiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 400 && sendReasoning && resp.body().toLowerCase(Locale.ROOT).contains("reasoning")) {
                sendReasoning = false; // 이 모델은 reasoning 을 안 받음 → 빼고 한 번 더
                continue;
            }
            if (resp.statusCode() == 401) throw new IOException("API 키가 잘못됨 (HTTP 401)");
            if (resp.statusCode() == 429) throw new IOException("OpenAI 요청 한도 (HTTP 429)");
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " " + clip(resp.body().replaceAll("\\s+", " "), 200));
            String text = outputText(resp.body());
            if (text.isBlank()) throw new IOException("빈 응답");
            return text.strip();
        }
        throw new IOException("요청 실패");
    }

    /** Responses API 응답에서 output[].content[].text (type=output_text) 를 이어 붙인다. */
    static String outputText(String json) {
        Map<String, Object> root = Json.parseObject(json);
        String direct = Json.str(root, "output_text");
        if (direct != null) return direct;
        StringBuilder sb = new StringBuilder();
        List<Object> output = Json.arr(root.get("output"));
        if (output == null) return "";
        for (Object o : output) {
            Map<String, Object> item = Json.obj(o);
            List<Object> content = item == null ? null : Json.arr(item.get("content"));
            if (content == null) continue;
            for (Object c : content) {
                Map<String, Object> part = Json.obj(c);
                if (part != null && "output_text".equals(Json.str(part, "type"))) {
                    String t = Json.str(part, "text");
                    if (t != null) sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    @Override public void close() { worker.shutdownNow(); }
}
