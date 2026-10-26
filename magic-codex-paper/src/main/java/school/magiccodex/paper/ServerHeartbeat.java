package school.magiccodex.paper;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 운영 대시보드용 하트비트 (메모 #92). 이 서버의 상태 한 줄을 공유 DB {@code codex_server_status} 에 덮어쓴다.
 *
 * <p>렉 방지: 메인 스레드에서는 숫자 몇 개(TPS 배열·평균 틱·접속자·월드별 청크/엔티티 수)만 읽고,
 * spark 조회·CPU·DB 쓰기는 전용 스레드 하나에서 한다. 앞 쓰기가 아직 안 끝났으면 이번 회차는 건너뛴다
 * (DB 가 느려도 작업이 쌓이지 않음). DB 연결도 그 스레드에서 처음 연다.
 */
final class ServerHeartbeat implements AutoCloseable {
    static final String TABLE = "codex_server_status";

    /** 메인 스레드에서 모은 값. -1 = 모름. */
    record Sample(long at, double[] bukkitTps, double bukkitMspt, int online, int maxPlayers,
                  int worlds, int chunks, int entities) { }

    /** DB 에 쓰는 한 줄. */
    record Row(String serverId, String state, long updatedAt, long startedAt, String source,
               double tps10s, double tps1m, double tps5m, double tps15m,
               double msptMean, double msptMax, double msptP95,
               double cpuProcess, double cpuSystem, int heapUsedMb, int heapMaxMb,
               int online, int maxPlayers, int worlds, int chunks, int entities, String version) { }

    private final JavaPlugin plugin;
    private final DatabaseSettings settings;
    private final String serverId;
    private final boolean worldCounts;
    private final long startedAt = System.currentTimeMillis();
    private final String version;
    private final ExecutorService io;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final SparkProbe spark;
    private volatile ConnectionHolder holder;
    private volatile String lastError = "";
    private volatile long lastWrite;
    private volatile String lastSource = "-";
    private BukkitTask task;
    private boolean closed;

    private ServerHeartbeat(JavaPlugin plugin, DatabaseSettings settings, String serverId, int intervalSeconds, boolean worldCounts) {
        this.plugin = plugin;
        this.settings = settings;
        this.serverId = serverId;
        this.worldCounts = worldCounts;
        this.version = clip(Bukkit.getName() + " " + Bukkit.getMinecraftVersion(), 96);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MagicCodex-Heartbeat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.spark = new SparkProbe(plugin);
        long period = intervalSeconds * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 100L, period);
    }

    /** 설정을 읽고 켠다. 꺼져 있거나 MariaDB 가 아니면 null (로그 한 줄). */
    static ServerHeartbeat start(JavaPlugin plugin) throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        if (!Files.exists(folder.resolve("server-heartbeat.yml"))) plugin.saveResource("server-heartbeat.yml", false);
        var y = YamlConfiguration.loadConfiguration(folder.resolve("server-heartbeat.yml").toFile());
        if (!y.getBoolean("enabled", true)) { plugin.getLogger().info("운영 하트비트: 꺼짐 (server-heartbeat.yml enabled: false)"); return null; }
        // 하트비트 전용 DB 설정 (2026-10-27): database.properties 는 다른 기능의 저장 위치도 바꾸므로 따로 둔다
        var settings = DatabaseSettings.load(folder.resolve("heartbeat-database.properties"));
        if (!settings.mariaDb()) { plugin.getLogger().info("운영 하트비트: heartbeat-database.properties 가 MariaDB 가 아니라 끔"); return null; }
        String id = serverId(y.getString("server-id", ""), plugin.getServer().getWorldContainer().getAbsoluteFile().toPath());
        int interval = clampInterval(y.getInt("interval-seconds", 10));
        var hb = new ServerHeartbeat(plugin, settings, id, interval, y.getBoolean("world-counts", true));
        plugin.getLogger().info("운영 하트비트: " + TABLE + " server_id=" + id + ", " + interval + "초마다");
        return hb;
    }

    static int clampInterval(int seconds) { return Math.max(5, Math.min(60, seconds)); }

    /** 설정값 → 없으면 서버 폴더 이름. 영문 소문자·숫자·_- 1~48자로 정리. */
    static String serverId(String configured, Path serverRoot) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty() && serverRoot != null) {
            Path name = serverRoot.normalize().getFileName();
            raw = name == null ? "" : name.toString();
            if (raw.equals(".")) raw = "";
        }
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (id.length() > 48) id = id.substring(0, 48);
        return id.isEmpty() ? "server" : id;
    }

    String describe() {
        if (lastWrite == 0) return "server_id=" + serverId + " · 아직 안 씀" + (lastError.isEmpty() ? "" : " · 오류: " + lastError);
        long ago = (System.currentTimeMillis() - lastWrite) / 1000;
        return "server_id=" + serverId + " · " + ago + "초 전 기록 (" + lastSource + ")" + (lastError.isEmpty() ? "" : " · 최근 오류: " + lastError);
    }

    /** 메인 스레드. 값 몇 개만 읽고 바로 넘긴다. */
    private void tick() {
        if (closed || !busy.compareAndSet(false, true)) return;
        Sample sample;
        try { sample = sampleMain(); }
        catch (RuntimeException | LinkageError error) { busy.set(false); lastError = clip(String.valueOf(error), 160); return; }
        try {
            io.execute(() -> {
                try { write(build(sample, "running")); lastError = ""; }
                catch (Exception | LinkageError error) { lastError = clip(error.getClass().getSimpleName() + ": " + error.getMessage(), 160); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException rejected) { busy.set(false); }
    }

    private Sample sampleMain() {
        var server = plugin.getServer();
        double[] tps = server.getTPS();
        double mspt = server.getAverageTickTime();
        int worlds = 0, chunks = 0, entities = 0;
        if (worldCounts) {
            for (World w : server.getWorlds()) { worlds++; chunks += w.getChunkCount(); entities += w.getEntityCount(); }
        } else { worlds = -1; chunks = -1; entities = -1; }
        return new Sample(System.currentTimeMillis(), tps == null ? new double[0] : tps.clone(), mspt,
                server.getOnlinePlayers().size(), server.getMaxPlayers(), worlds, chunks, entities);
    }

    /** IO 스레드: spark 값이 있으면 spark, 없으면 Bukkit 값 + JVM CPU. */
    private Row build(Sample s, String state) {
        SparkProbe.Values sv = spark.poll();
        double[] t = s.bukkitTps();
        double b1 = t.length > 0 ? t[0] : -1, b5 = t.length > 1 ? t[1] : -1, b15 = t.length > 2 ? t[2] : -1;
        Runtime rt = Runtime.getRuntime();
        int used = (int) ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        int max = (int) (rt.maxMemory() / (1024 * 1024));
        double cpuP = -1, cpuS = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpuP = percent(os.getProcessCpuLoad());
            cpuS = percent(os.getCpuLoad());
        }
        Row row;
        if (sv != null) {
            row = new Row(serverId, state, s.at(), startedAt, "spark",
                    pick(sv.tps10s(), b1), pick(sv.tps1m(), b1), pick(sv.tps5m(), b5), pick(sv.tps15m(), b15),
                    pick(sv.msptMean(), s.bukkitMspt()), sv.msptMax(), sv.msptP95(),
                    pick(percent(sv.cpuProcess()), cpuP), pick(percent(sv.cpuSystem()), cpuS),
                    used, max, s.online(), s.maxPlayers(), s.worlds(), s.chunks(), s.entities(), version);
        } else {
            row = new Row(serverId, state, s.at(), startedAt, "bukkit", b1, b1, b5, b15, s.bukkitMspt(), -1, -1,
                    cpuP, cpuS, used, max, s.online(), s.maxPlayers(), s.worlds(), s.chunks(), s.entities(), version);
        }
        lastSource = row.source();
        return row;
    }

    /** 0..1 → 0..100 (음수·NaN 은 -1). */
    static double percent(double fraction) {
        if (Double.isNaN(fraction) || fraction < 0) return -1;
        return Math.round(Math.min(1.0, fraction) * 1000.0) / 10.0;
    }

    static double pick(double preferred, double fallback) {
        return Double.isNaN(preferred) || preferred < 0 ? fallback : preferred;
    }

    private ConnectionHolder holder() throws Exception {
        ConnectionHolder h = holder;
        if (h != null) return h;
        h = settings.holder(plugin.getDataFolder().toPath().resolve("unused-heartbeat.db"));
        try (var st = h.get().createStatement()) { st.execute(createSql()); }
        holder = h;
        return h;
    }

    static String createSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " (server_id VARCHAR(48) NOT NULL PRIMARY KEY, state VARCHAR(12) NOT NULL,"
                + " updated_at BIGINT NOT NULL, started_at BIGINT NOT NULL, source VARCHAR(8) NOT NULL,"
                + " tps_10s DOUBLE NOT NULL, tps_1m DOUBLE NOT NULL, tps_5m DOUBLE NOT NULL, tps_15m DOUBLE NOT NULL,"
                + " mspt_mean DOUBLE NOT NULL, mspt_max DOUBLE NOT NULL, mspt_p95 DOUBLE NOT NULL,"
                + " cpu_process DOUBLE NOT NULL, cpu_system DOUBLE NOT NULL, heap_used_mb INT NOT NULL, heap_max_mb INT NOT NULL,"
                + " online INT NOT NULL, max_players INT NOT NULL, worlds INT NOT NULL, chunks INT NOT NULL, entities INT NOT NULL,"
                + " version VARCHAR(96) NOT NULL DEFAULT '') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    static final String UPSERT = "INSERT INTO " + TABLE + " (server_id,state,updated_at,started_at,source,tps_10s,tps_1m,tps_5m,tps_15m,"
            + "mspt_mean,mspt_max,mspt_p95,cpu_process,cpu_system,heap_used_mb,heap_max_mb,online,max_players,worlds,chunks,entities,version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),updated_at=VALUES(updated_at),"
            + "started_at=VALUES(started_at),source=VALUES(source),tps_10s=VALUES(tps_10s),tps_1m=VALUES(tps_1m),tps_5m=VALUES(tps_5m),"
            + "tps_15m=VALUES(tps_15m),mspt_mean=VALUES(mspt_mean),mspt_max=VALUES(mspt_max),mspt_p95=VALUES(mspt_p95),"
            + "cpu_process=VALUES(cpu_process),cpu_system=VALUES(cpu_system),heap_used_mb=VALUES(heap_used_mb),heap_max_mb=VALUES(heap_max_mb),"
            + "online=VALUES(online),max_players=VALUES(max_players),worlds=VALUES(worlds),chunks=VALUES(chunks),entities=VALUES(entities),"
            + "version=VALUES(version)";

    private void write(Row r) throws Exception {
        var db = holder().get();
        try (var p = db.prepareStatement(UPSERT)) {
            p.setString(1, r.serverId()); p.setString(2, r.state()); p.setLong(3, r.updatedAt()); p.setLong(4, r.startedAt());
            p.setString(5, r.source());
            p.setDouble(6, round(r.tps10s())); p.setDouble(7, round(r.tps1m())); p.setDouble(8, round(r.tps5m())); p.setDouble(9, round(r.tps15m()));
            p.setDouble(10, round(r.msptMean())); p.setDouble(11, round(r.msptMax())); p.setDouble(12, round(r.msptP95()));
            p.setDouble(13, r.cpuProcess()); p.setDouble(14, r.cpuSystem());
            p.setInt(15, r.heapUsedMb()); p.setInt(16, r.heapMaxMb()); p.setInt(17, r.online()); p.setInt(18, r.maxPlayers());
            p.setInt(19, r.worlds()); p.setInt(20, r.chunks()); p.setInt(21, r.entities()); p.setString(22, r.version());
            p.executeUpdate();
        }
        lastWrite = System.currentTimeMillis();
    }

    static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        return Math.round(v * 100.0) / 100.0;
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 끌 때: 마지막으로 state=stopped 를 한 번 쓰고(최대 2초) 스레드를 닫는다. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (task != null) { task.cancel(); task = null; }
        try {
            var server = plugin.getServer();
            Sample s = new Sample(System.currentTimeMillis(), new double[0], -1, server.getOnlinePlayers().size(), server.getMaxPlayers(), -1, -1, -1);
            io.submit(() -> {
                try { write(build(s, "stopped")); } catch (Exception | LinkageError ignored) { }
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        io.shutdownNow();
        ConnectionHolder h = holder;
        if (h != null) try { h.close(); } catch (SQLException ignored) { }
    }
}
