package school.chacademi.ops;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 5초마다: 서버별 상태 핑 + 공유 DB 하트비트 읽기 + 경보 계산. 1초마다: 로그 따라 읽기.
 * 모두 대시보드 프로세스 안에서만 돈다 — 게임 서버에 주는 부담은 5초에 핑 1번·DB 읽기 1번뿐.
 */
final class Monitor implements AutoCloseable {
    static final int HISTORY = 720; // 5초 × 720 = 1시간

    record Point(long at, double tps, double mspt, double cpu, double heap, int online) { }

    static final class View {
        final Config.Server server;
        final LogTail tail;
        volatile McPing.Result ping = McPing.Result.down("아직 확인 안 함");
        volatile Alarms.Heartbeat hb;
        volatile Alarms.Status status = Alarms.Status.DOWN;
        volatile List<Alarms.Alarm> alarms = List.of();
        volatile long sparkRequestedAt;
        volatile String sparkReply = "";
        final Deque<Point> history = new ArrayDeque<>();

        View(Config.Server server, Pattern spark) {
            this.server = server;
            this.tail = server.log() == null ? null : new LogTail(server.log(), spark);
        }

        synchronized List<Point> history(int max) {
            List<Point> all = new ArrayList<>(history);
            return all.size() > max ? all.subList(all.size() - max, all.size()) : all;
        }
    }

    record Host(double cpu, long memTotalMb, long memFreeMb, long diskFreeGb, long diskTotalGb) { }

    private static final Logger LOG = Logger.getLogger("ops");
    private final Config config;
    private final Db db;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> daemon(r, "ops-monitor"));
    private final ExecutorService pinger;
    private volatile Host host = new Host(-1, -1, -1, -1, -1);
    private volatile String dbStatus = "확인 전";
    private volatile Set<String> lastAlarmKeys = Set.of();
    private volatile Consumer<List<Alarms.Alarm>> onNewAlarms = a -> { };

    Monitor(Config config, Db db) {
        this.config = config;
        this.db = db;
        Pattern spark = Pattern.compile(config.sparkLinkPattern);
        for (Config.Server s : config.servers) views.put(s.id(), new View(s, spark));
        pinger = Executors.newFixedThreadPool(Math.max(2, Math.min(8, views.size())), r -> daemon(r, "ops-ping"));
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void onNewAlarms(Consumer<List<Alarms.Alarm>> listener) { this.onNewAlarms = listener; }

    void start() {
        timer.scheduleWithFixedDelay(this::safeTails, 0, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::safeCycle, 1, 5, TimeUnit.SECONDS);
    }

    private void safeTails() {
        long now = System.currentTimeMillis();
        for (View v : views.values()) if (v.tail != null) v.tail.poll(now);
    }

    private void safeCycle() {
        try { cycle(); } catch (Throwable t) { LOG.warning("모니터 주기 오류: " + t); }
    }

    private void cycle() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, Future<McPing.Result>> pings = new HashMap<>();
        for (View v : views.values()) pings.put(v.server.id(), pinger.submit(() -> McPing.ping(v.server.host(), v.server.port(), 2500)));
        Map<String, Alarms.Heartbeat> rows = readHeartbeats();
        host = readHost();
        List<Alarms.Alarm> all = new ArrayList<>();
        var t = new Alarms.Thresholds(config.alarmTps, config.alarmMspt, config.alarmHeap, config.staleMillis, config.errorsPerMinute);
        for (View v : views.values()) {
            McPing.Result ping;
            try { ping = pings.get(v.server.id()).get(4, TimeUnit.SECONDS); }
            catch (Exception e) { ping = McPing.Result.down("시간 초과"); }
            v.ping = ping;
            // DB 읽기에 실패하면(rows == null) 앞 값을 그대로 둔다 → 오래되면 "하트비트 끊김" 으로 바뀜
            if (rows != null) v.hb = rows.get(v.server.heartbeatId());
            // 하트비트 DB 가 없으면 켜짐/꺼짐은 접속 핑으로만 본다 (없는 하트비트로 늘 "응답 없음"이 뜨지 않게)
            v.status = Alarms.status(v.server.paper() && config.dbConfigured(), ping.up(), v.hb, now, config.staleMillis);
            int errors = v.tail == null ? 0 : v.tail.errorsLastMinute(now);
            v.alarms = Alarms.evaluate(v.server.id(), v.server.label(), v.server.paper(), v.status, ping.up(), v.hb, now, errors, t);
            all.addAll(v.alarms);
            Alarms.Heartbeat h = v.hb;
            boolean fresh = h != null && now - h.updatedAt() <= config.staleMillis && !h.stopped();
            Point p = new Point(now, fresh ? h.tps() : -1, fresh ? h.msptMean() : -1, fresh ? h.cpuProcess() : -1,
                    fresh ? h.heapPercent() : -1, ping.up() ? ping.online() : (fresh ? h.online() : -1));
            synchronized (v) { v.history.addLast(p); while (v.history.size() > HISTORY) v.history.removeFirst(); }
        }
        Host hst = host;
        if (hst.cpu() > config.alarmHostCpu) all.add(new Alarms.Alarm("host", "cpu", false, "호스팅 PC CPU " + Math.round(hst.cpu()) + "%"));
        if (hst.memTotalMb() > 0) {
            double used = 100.0 * (hst.memTotalMb() - hst.memFreeMb()) / hst.memTotalMb();
            if (used > config.alarmHostMem) all.add(new Alarms.Alarm("host", "mem", false, "호스팅 PC 메모리 " + Math.round(used) + "%"));
        }
        if (db == null) all.add(new Alarms.Alarm("db", "none", false, "DB 설정이 없어 하트비트·운영 기록을 못 읽음 (ops-dashboard.properties db.*)"));
        else if (!dbStatus.equals("정상")) all.add(new Alarms.Alarm("db", "error", true, "공유 DB 읽기 실패: " + dbStatus));
        java.util.Set<String> keys = new java.util.HashSet<>();
        List<Alarms.Alarm> fresh = new ArrayList<>();
        for (Alarms.Alarm a : all) {
            String key = a.server() + ":" + a.code();
            keys.add(key);
            if (!lastAlarmKeys.contains(key) && a.critical()) fresh.add(a);
        }
        lastAlarmKeys = Set.copyOf(keys);
        hostAlarms = List.copyOf(all.stream().filter(a -> !views.containsKey(a.server())).toList());
        if (!fresh.isEmpty()) try { onNewAlarms.accept(fresh); } catch (RuntimeException e) { LOG.warning("경보 처리 오류: " + e); }
    }

    private volatile List<Alarms.Alarm> hostAlarms = List.of();

    private Map<String, Alarms.Heartbeat> readHeartbeats() {
        if (db == null) { dbStatus = "설정 없음"; return Map.of(); }
        try {
            Map<String, Alarms.Heartbeat> out = db.with(c -> {
                Map<String, Alarms.Heartbeat> m = new HashMap<>();
                try (var st = c.createStatement()) {
                    st.setQueryTimeout(5);
                    try (ResultSet r = st.executeQuery("SELECT * FROM " + config.statusTable)) {
                        while (r.next()) { Alarms.Heartbeat hb = row(r); m.put(hb.serverId(), hb); }
                    }
                }
                return m;
            });
            dbStatus = "정상";
            return out;
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 표가 아직 없으면 (게임 서버가 한 번도 안 씀) 오류 대신 빈 목록
            if (msg.contains("doesn't exist") || msg.contains("1146")) { dbStatus = "정상"; return Map.of(); }
            dbStatus = msg.length() > 160 ? msg.substring(0, 160) : msg;
            return null;
        }
    }

    static Alarms.Heartbeat row(ResultSet r) throws SQLException {
        return new Alarms.Heartbeat(r.getString("server_id"), r.getString("state"), r.getLong("updated_at"), r.getLong("started_at"),
                r.getString("source"), r.getDouble("tps_10s"), r.getDouble("tps_1m"), r.getDouble("tps_5m"), r.getDouble("tps_15m"),
                r.getDouble("mspt_mean"), r.getDouble("mspt_max"), r.getDouble("mspt_p95"), r.getDouble("cpu_process"),
                r.getDouble("cpu_system"), r.getInt("heap_used_mb"), r.getInt("heap_max_mb"), r.getInt("online"),
                r.getInt("max_players"), r.getInt("worlds"), r.getInt("chunks"), r.getInt("entities"), r.getString("version"));
    }

    private Host readHost() {
        double cpu = -1;
        long total = -1, free = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double c = os.getCpuLoad();
            cpu = c < 0 ? -1 : Math.round(c * 1000) / 10.0;
            total = os.getTotalMemorySize() / (1024 * 1024);
            free = os.getFreeMemorySize() / (1024 * 1024);
        }
        File disk = new File(config.hostDiskPath);
        long df = disk.exists() ? disk.getUsableSpace() / (1L << 30) : -1;
        long dt = disk.exists() ? disk.getTotalSpace() / (1L << 30) : -1;
        return new Host(cpu, total, free, df, dt);
    }

    View view(String id) { return views.get(id); }
    List<View> views() { return List.copyOf(views.values()); }
    Host host() { return host; }
    String dbStatus() { return dbStatus; }

    List<Alarms.Alarm> alarms() {
        List<Alarms.Alarm> out = new ArrayList<>();
        for (View v : views.values()) out.addAll(v.alarms);
        out.addAll(hostAlarms);
        return out;
    }

    @Override public void close() {
        timer.shutdownNow();
        pinger.shutdownNow();
    }
}
