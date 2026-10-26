package school.chacademi.ops;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * ops-dashboard.properties (UTF-8). 비밀값(비밀번호·키)은 환경변수가 먼저, 없으면 파일 값.
 * 파일 예시는 ops-dashboard/ops-dashboard.properties.example.
 */
final class Config {
    enum Kind { PAPER, PROXY }
    enum Console { RCON, SCRIPT, NONE }

    record Server(String id, String label, Kind kind, String host, int port, Path log, String heartbeatId,
                  Console console, int rconPort, String rconPassword, String consoleScript,
                  String start, String stop, String restart, String sparkCommand, String manageUrl) {
        boolean paper() { return kind == Kind.PAPER; }
    }

    final Path file;
    final String bind;
    final int port;
    final String passwordSha256;
    final long sessionMillis;
    final int maxFailures;
    final long lockMillis;
    final boolean trustForwardedFor;
    final Path tlsKeystore;
    final String tlsPassword;
    final Path dataDir;
    final Path dbPropertiesFile;
    final String dbHost, dbName, dbUser, dbPassword;
    final int dbPort;
    final String statusTable;
    final List<Server> servers;
    final String hostDiskPath;
    // 경보
    final double alarmTps, alarmMspt, alarmHeap, alarmHostCpu, alarmHostMem;
    final long staleMillis;
    final int errorsPerMinute;
    // 조작
    final long controlTimeoutMillis, stopWaitMillis, startWaitMillis;
    final String shell;
    final java.nio.charset.Charset outputCharset;
    // AI
    final boolean aiEnabled, aiAuto;
    final String aiKey, aiBaseUrl, aiModel, aiReasoning;
    final int aiMaxOutput, aiMaxPerDay;
    final long aiAutoIntervalMillis;
    // 운영 기록
    final String auditTable;
    final Map<String, String> auditColumns;
    final String auditTimeUnit;
    final int auditMaxRows;
    final String zone;
    final String sparkLinkPattern;

    private Config(Path file, Properties p, Function<String, String> env) throws IOException {
        this.file = file;
        bind = str(p, "http.bind", "0.0.0.0");
        port = integer(p, "http.port", 8470, 1, 65535);
        String pw = secret(p, env, "password", "CHACA_OPS_PASSWORD");
        String hash = str(p, "password.sha256", "").toLowerCase(Locale.ROOT);
        if (!pw.isEmpty()) hash = Auth.sha256Hex(pw);
        if (!hash.matches("[0-9a-f]{64}"))
            throw new IOException("비밀번호가 없습니다: 환경변수 CHACA_OPS_PASSWORD 또는 password= / password.sha256= 를 넣으세요");
        if (!pw.isEmpty() && pw.length() < 8) throw new IOException("비밀번호는 8자 이상");
        passwordSha256 = hash;
        sessionMillis = integer(p, "session.hours", 12, 1, 24 * 30) * 3_600_000L;
        maxFailures = integer(p, "login.max-failures", 5, 1, 100);
        lockMillis = integer(p, "login.lock-minutes", 15, 1, 24 * 60) * 60_000L;
        trustForwardedFor = bool(p, "http.trust-forwarded-for", false);
        String ks = str(p, "tls.keystore", "");
        tlsKeystore = ks.isEmpty() ? null : Path.of(ks);
        tlsPassword = secret(p, env, "tls.password", "CHACA_OPS_TLS_PASSWORD");
        dataDir = Path.of(str(p, "data-dir", "data"));
        String dbFile = str(p, "db.properties-file", "");
        dbPropertiesFile = dbFile.isEmpty() ? null : Path.of(dbFile);
        dbHost = str(p, "db.host", "127.0.0.1");
        dbPort = integer(p, "db.port", 3306, 1, 65535);
        dbName = str(p, "db.name", "");
        dbUser = str(p, "db.user", "");
        dbPassword = secret(p, env, "db.password", "CHACA_OPS_DB_PASSWORD");
        statusTable = ident(str(p, "db.status-table", "codex_server_status"), "db.status-table");
        hostDiskPath = str(p, "host.disk-path", "C:/Chacademi");
        alarmTps = dbl(p, "alarm.tps-below", 15);
        alarmMspt = dbl(p, "alarm.mspt-above", 50);
        alarmHeap = dbl(p, "alarm.heap-above-percent", 90);
        alarmHostCpu = dbl(p, "alarm.host-cpu-above-percent", 95);
        alarmHostMem = dbl(p, "alarm.host-memory-above-percent", 95);
        staleMillis = integer(p, "alarm.heartbeat-stale-seconds", 30, 10, 3600) * 1000L;
        errorsPerMinute = integer(p, "alarm.errors-per-minute", 10, 1, 100000);
        controlTimeoutMillis = integer(p, "control.script-timeout-seconds", 180, 5, 3600) * 1000L;
        stopWaitMillis = integer(p, "control.stop-wait-seconds", 180, 5, 3600) * 1000L;
        startWaitMillis = integer(p, "control.start-wait-seconds", 300, 5, 3600) * 1000L;
        shell = str(p, "control.shell", "");
        String cs = str(p, "control.output-charset", "");
        if (cs.isEmpty()) cs = System.getProperty("native.encoding", "UTF-8");
        try { outputCharset = java.nio.charset.Charset.forName(cs); }
        catch (RuntimeException e) { throw new IOException("control.output-charset: " + cs); }
        aiEnabled = bool(p, "ai.enabled", true);
        aiAuto = bool(p, "ai.auto-on-alarm", false);
        String key = secret(p, env, "ai.api-key", "CHACA_OPS_OPENAI_KEY");
        if (key.isEmpty()) key = nz(env.apply("CHACANPC_OPENAI_KEY"));
        aiKey = key;
        aiBaseUrl = str(p, "ai.base-url", "https://api.openai.com/v1").replaceAll("/+$", "");
        aiModel = str(p, "ai.model", "gpt-6-luna");
        aiReasoning = str(p, "ai.reasoning-effort", "low");
        aiMaxOutput = integer(p, "ai.max-output-tokens", 900, 100, 8000);
        aiMaxPerDay = integer(p, "ai.max-per-day", 40, 0, 10000);
        aiAutoIntervalMillis = integer(p, "ai.auto-min-interval-minutes", 15, 1, 24 * 60) * 60_000L;
        auditTable = ident(str(p, "audit.table", "codex_audit_log"), "audit.table");
        Map<String, String> cols = new LinkedHashMap<>();
        for (String[] c : new String[][]{{"time", "at_ms"}, {"server", "server"}, {"uuid", "player"},
                {"name", "player_name"}, {"kind", "kind"}, {"message", "summary"}, {"detail", "detail"}}) {
            String v = str(p, "audit.col." + c[0], c[1]);
            cols.put(c[0], v.isEmpty() ? "" : ident(v, "audit.col." + c[0]));
        }
        if (cols.get("time").isEmpty()) throw new IOException("audit.col.time 은 비울 수 없음");
        auditColumns = Map.copyOf(cols);
        auditTimeUnit = str(p, "audit.time-unit", "auto").toLowerCase(Locale.ROOT);
        if (!auditTimeUnit.matches("auto|millis|seconds|datetime")) throw new IOException("audit.time-unit: auto|millis|seconds|datetime");
        auditMaxRows = integer(p, "audit.max-rows", 300, 10, 5000);
        zone = str(p, "timezone", "Asia/Seoul");
        sparkLinkPattern = str(p, "spark.link-pattern", "https://spark\\.lucko\\.me/[A-Za-z0-9]+");
        servers = List.copyOf(servers(p, env));
        if (servers.isEmpty()) throw new IOException("servers= 에 서버를 하나 이상 넣으세요");
    }

    static Config load(Path file) throws IOException {
        return load(file, System::getenv);
    }

    static Config load(Path file, Function<String, String> env) throws IOException {
        var p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        return new Config(file, p, env);
    }

    static Config of(Properties p, Function<String, String> env) throws IOException {
        return new Config(Path.of("memory.properties"), p, env);
    }

    private static List<Server> servers(Properties p, Function<String, String> env) throws IOException {
        List<Server> out = new ArrayList<>();
        for (String raw : str(p, "servers", "").split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            if (!id.matches("[a-z0-9_-]{1,48}")) throw new IOException("서버 이름은 영문 소문자·숫자·_- : " + id);
            String k = "server." + id + ".";
            Kind kind = str(p, k + "type", id.equals("proxy") || id.equals("velocity") ? "proxy" : "paper").equalsIgnoreCase("proxy") ? Kind.PROXY : Kind.PAPER;
            String log = str(p, k + "log", "");
            Console console;
            try { console = Console.valueOf(str(p, k + "console", kind == Kind.PAPER ? "rcon" : "none").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IOException(k + "console: rcon|script|none"); }
            String envName = "CHACA_OPS_RCON_" + id.toUpperCase(Locale.ROOT).replace('-', '_');
            String spark = str(p, k + "spark-command", kind == Kind.PAPER ? "spark profiler start --timeout 60" : "sparkv profiler start --timeout 60");
            // 다른 PC 의 대시보드가 관리하는 서버: 카드에 "관리 열기" 버튼 (새 탭)
            String manage = str(p, k + "manage-url", "");
            if (!manage.isEmpty() && !manage.matches("https?://[^\\s\"'<>]{1,200}")) throw new IOException(k + "manage-url: http(s):// 주소");
            out.add(new Server(id, str(p, k + "label", id), kind, str(p, k + "host", "127.0.0.1"),
                    integer(p, k + "port", 25565, 1, 65535), log.isEmpty() ? null : Path.of(log),
                    str(p, k + "heartbeat-id", id), console, integer(p, k + "rcon.port", 25575, 1, 65535),
                    secret(p, env, k + "rcon.password", envName), str(p, k + "console.script", ""),
                    str(p, k + "start", ""), str(p, k + "stop", ""), str(p, k + "restart", ""), spark, manage));
        }
        return out;
    }

    Server server(String id) {
        for (Server s : servers) if (s.id().equals(id)) return s;
        return null;
    }

    boolean dbConfigured() {
        return dbPropertiesFile != null || (!dbName.isEmpty() && !dbUser.isEmpty());
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null ? def : v.trim();
    }

    private static String secret(Properties p, Function<String, String> env, String key, String envName) {
        String e = nz(env.apply(envName));
        return e.isEmpty() ? str(p, key, "") : e;
    }

    private static int integer(Properties p, String key, int def, int min, int max) throws IOException {
        String v = str(p, key, "");
        if (v.isEmpty()) return def;
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) throw new IOException(key + " 범위 " + min + "~" + max);
            return n;
        } catch (NumberFormatException e) { throw new IOException(key + " 는 숫자: " + v); }
    }

    private static double dbl(Properties p, String key, double def) throws IOException {
        String v = str(p, key, "");
        if (v.isEmpty()) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { throw new IOException(key + " 는 숫자: " + v); }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = str(p, key, "");
        return v.isEmpty() ? def : v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    static String ident(String v, String key) throws IOException {
        if (!v.matches("[A-Za-z0-9_]{1,64}")) throw new IOException(key + " 는 영문·숫자·_ 만: " + v);
        return v;
    }
}
