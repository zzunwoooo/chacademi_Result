package school.chacademi.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AI 없이 바로 띄우는 규칙 경보 (메모 #92): TPS<15, MSPT>50, 메모리>90%, 하트비트 끊김, ERROR 급증, 꺼짐. */
final class Alarms {
    enum Status { UP, NO_HEARTBEAT, DOWN }

    record Heartbeat(String serverId, String state, long updatedAt, long startedAt, String source,
                     double tps10s, double tps1m, double tps5m, double tps15m,
                     double msptMean, double msptMax, double msptP95, double cpuProcess, double cpuSystem,
                     int heapUsedMb, int heapMaxMb, int online, int maxPlayers, int worlds, int chunks, int entities, String version) {
        double tps() { return tps10s >= 0 ? tps10s : tps1m; }
        double heapPercent() { return heapMaxMb > 0 ? 100.0 * heapUsedMb / heapMaxMb : -1; }
        boolean stopped() { return "stopped".equals(state); }
    }

    record Alarm(String server, String code, boolean critical, String message) { }

    record Thresholds(double tps, double mspt, double heap, long staleMillis, int errorsPerMinute) { }

    private Alarms() { }

    /** 핑 결과 + 하트비트로 상태를 정한다. */
    static Status status(boolean paper, boolean pingUp, Heartbeat hb, long now, long staleMillis) {
        if (!paper) return pingUp ? Status.UP : Status.DOWN;
        boolean fresh = hb != null && !hb.stopped() && now - hb.updatedAt() <= staleMillis;
        if (pingUp && fresh) return Status.UP;
        if (!pingUp && (hb == null || hb.stopped() || !fresh)) return Status.DOWN;
        return Status.NO_HEARTBEAT; // 포트는 살아 있는데 하트비트가 끊김(메인 스레드 멈춤 의심) 또는 그 반대
    }

    static List<Alarm> evaluate(String id, String label, boolean paper, Status status, boolean pingUp, Heartbeat hb,
                                long now, int errorsLastMinute, Thresholds t) {
        List<Alarm> out = new ArrayList<>();
        if (status == Status.DOWN) {
            out.add(new Alarm(id, "down", true, label + ": 꺼져 있음 (접속 포트 응답 없음)"));
        } else if (status == Status.NO_HEARTBEAT) {
            if (pingUp) {
                String age = hb == null ? "기록 없음 — MagicCodexBridge·DB 확인" : ((now - hb.updatedAt()) / 1000) + "초째 끊김";
                out.add(new Alarm(id, "heartbeat", true, label + ": 응답 없음 — 하트비트 " + age + " (서버가 멈췄을 수 있음)"));
            } else {
                out.add(new Alarm(id, "ping", true, label + ": 접속 포트가 응답하지 않음 (하트비트는 들어옴)"));
            }
        }
        if (paper && hb != null && status != Status.DOWN && !hb.stopped() && now - hb.updatedAt() <= t.staleMillis()) {
            double tps = hb.tps();
            if (tps >= 0 && tps < t.tps()) out.add(new Alarm(id, "tps", true, label + ": TPS 낮음 " + fmt(tps)));
            if (hb.msptMean() > t.mspt()) out.add(new Alarm(id, "mspt", true, label + ": MSPT 높음 " + fmt(hb.msptMean()) + "ms"));
            double heap = hb.heapPercent();
            if (heap > t.heap()) out.add(new Alarm(id, "heap", true, label + ": 메모리 " + Math.round(heap) + "% 사용"));
        }
        if (errorsLastMinute >= t.errorsPerMinute())
            out.add(new Alarm(id, "errors", true, label + ": 오류 로그 급증 (최근 1분 " + errorsLastMinute + "줄)"));
        return out;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
