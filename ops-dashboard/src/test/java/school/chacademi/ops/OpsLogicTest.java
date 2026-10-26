package school.chacademi.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 운영 대시보드 순수 로직 (메모 #92). 네트워크·DB 없이. */
class OpsLogicTest {
    private static final Pattern SPARK = Pattern.compile("https://spark\\.lucko\\.me/[A-Za-z0-9]+");

    // ---------------------------------------------------------------- 로그인

    @Test void loginLocksAfterFailures() {
        AtomicLong now = new AtomicLong(1_000_000);
        Auth auth = new Auth(Auth.sha256Hex("correct-horse"), 3_600_000, 3, 600_000, now::get);
        String[] t = new String[1];
        assertEquals(Auth.Result.WRONG, auth.login("1.2.3.4", "x", t));
        assertEquals(Auth.Result.WRONG, auth.login("1.2.3.4", "y", t));
        assertEquals(Auth.Result.LOCKED, auth.login("1.2.3.4", "z", t));
        assertEquals(Auth.Result.LOCKED, auth.login("1.2.3.4", "correct-horse", t)); // 잠긴 동안은 맞아도 거부
        assertEquals(Auth.Result.OK, auth.login("5.6.7.8", "correct-horse", t)); // 다른 IP 는 됨
        assertTrue(auth.valid(t[0]));
        now.addAndGet(600_001);
        assertEquals(Auth.Result.OK, auth.login("1.2.3.4", "correct-horse", t));
    }

    @Test void sessionExpiresAndLogout() {
        AtomicLong now = new AtomicLong(0);
        Auth auth = new Auth(Auth.sha256Hex("pw-12345678"), 1000, 5, 1000, now::get);
        String[] t = new String[1];
        assertEquals(Auth.Result.OK, auth.login("ip", "pw-12345678", t));
        assertTrue(auth.valid(t[0]));
        assertFalse(auth.valid("zz"));
        assertFalse(auth.valid(null));
        now.set(1001);
        assertFalse(auth.valid(t[0]));
        now.set(0);
        auth.login("ip", "pw-12345678", t);
        auth.logout(t[0]);
        assertFalse(auth.valid(t[0]));
    }

    // ---------------------------------------------------------------- 조작

    @Test void tokenizeKeepsWindowsPaths() {
        assertEquals(List.of("C:/Program Files/PowerShell/7/pwsh.exe", "-NoProfile", "-File", "C:\\x\\Stop-Network.ps1", "-Server", "{server}"),
                Control.tokenize("\"C:/Program Files/PowerShell/7/pwsh.exe\" -NoProfile -File C:\\x\\Stop-Network.ps1 -Server {server}"));
        assertEquals(List.of("a", "", "b c"), Control.tokenize("a \"\" \"b c\""));
        assertEquals("say 안녕 하세요", Control.substitute("{command}", Map.of("command", "say 안녕 하세요")));
        assertEquals("-Server=wild", Control.substitute("-Server={server}", Map.of("server", "wild")));
    }

    @Test void dangerousCommandsNeedConfirm() {
        for (String c : new String[]{"stop", "/stop", "minecraft:stop", "op 준우", "lp user x permission set *", "ban x", "whitelist off", "reload confirm"})
            assertTrue(Control.dangerous(c), c);
        for (String c : new String[]{"list", "tps", "spark health", "say 안녕", "stopwatch", "mspt"})
            assertFalse(Control.dangerous(c), c);
    }

    // ---------------------------------------------------------------- 경보

    private static Alarms.Heartbeat hb(long updated, double tps, double mspt, int heapUsed, int heapMax, String state) {
        return new Alarms.Heartbeat("school", state, updated, 0, "spark", tps, tps, tps, tps, mspt, mspt, mspt, 10, 20,
                heapUsed, heapMax, 3, 100, 3, 500, 1000, "Paper");
    }

    @Test void statusFromPingAndHeartbeat() {
        long now = 100_000;
        assertEquals(Alarms.Status.UP, Alarms.status(true, true, hb(now - 5000, 20, 10, 1, 10, "running"), now, 30_000));
        assertEquals(Alarms.Status.NO_HEARTBEAT, Alarms.status(true, true, hb(now - 40_000, 20, 10, 1, 10, "running"), now, 30_000));
        assertEquals(Alarms.Status.NO_HEARTBEAT, Alarms.status(true, true, null, now, 30_000));
        assertEquals(Alarms.Status.DOWN, Alarms.status(true, false, hb(now - 1000, 20, 10, 1, 10, "stopped"), now, 30_000));
        assertEquals(Alarms.Status.DOWN, Alarms.status(true, false, null, now, 30_000));
        assertEquals(Alarms.Status.NO_HEARTBEAT, Alarms.status(true, false, hb(now - 1000, 20, 10, 1, 10, "running"), now, 30_000));
        assertEquals(Alarms.Status.UP, Alarms.status(false, true, null, now, 30_000));
        assertEquals(Alarms.Status.DOWN, Alarms.status(false, false, null, now, 30_000));
    }

    @Test void ruleAlarms() {
        long now = 100_000;
        var t = new Alarms.Thresholds(15, 50, 90, 30_000, 10);
        var ok = Alarms.evaluate("school", "학교", true, Alarms.Status.UP, true, hb(now - 1000, 19.9, 20, 5, 10, "running"), now, 0, t);
        assertTrue(ok.isEmpty());
        var bad = Alarms.evaluate("school", "학교", true, Alarms.Status.UP, true, hb(now - 1000, 12, 80, 95, 100, "running"), now, 12, t);
        assertEquals(List.of("tps", "mspt", "heap", "errors"), bad.stream().map(Alarms.Alarm::code).toList());
        var down = Alarms.evaluate("wild", "야생", true, Alarms.Status.DOWN, false, null, now, 0, t);
        assertEquals("down", down.get(0).code());
        var hung = Alarms.evaluate("wild", "야생", true, Alarms.Status.NO_HEARTBEAT, true, hb(now - 45_000, 20, 10, 1, 10, "running"), now, 0, t);
        assertEquals("heartbeat", hung.get(0).code());
        assertTrue(hung.get(0).message().contains("45초"));
        assertEquals(1, hung.size()); // 오래된 하트비트 숫자로 TPS 경보를 만들지 않음
    }

    // ---------------------------------------------------------------- 운영 기록 검색

    private static Map<String, String> cols() {
        Map<String, String> c = new HashMap<>();
        c.put("time", "created_at"); c.put("server", "server_id"); c.put("uuid", "player_uuid"); c.put("name", "player_name");
        c.put("kind", "kind"); c.put("message", "summary"); c.put("detail", "");
        return c;
    }

    @Test void auditQueryBuildsParameters() {
        var f = new AuditSearch.Filter("준우", "shop_buy", "", "2026-10-26T00:00", "", "100%", 50);
        var q = AuditSearch.build("codex_audit_log", cols(), f, AuditSearch.TimeUnitKind.MILLIS, ZoneId.of("Asia/Seoul"), 300);
        assertTrue(q.sql().startsWith("SELECT created_at AS c_time, server_id AS c_server"));
        assertTrue(q.sql().contains("NULL AS c_detail"));
        assertTrue(q.sql().contains("player_name LIKE ?"));
        assertTrue(q.sql().contains("kind = ?"));
        assertTrue(q.sql().contains("created_at >= ?"));
        assertTrue(q.sql().contains("(summary LIKE ? OR player_name LIKE ?)"));
        assertTrue(q.sql().endsWith("ORDER BY created_at DESC LIMIT 50"));
        assertEquals("%준우%", q.params().get(0));
        assertEquals("shop_buy", q.params().get(1));
        assertEquals(1_792_940_400_000L, q.params().get(2)); // 2026-10-26 00:00 KST
        assertEquals("%100\\%%", q.params().get(3));
    }

    @Test void auditUuidAndLimits() {
        var f = new AuditSearch.Filter("123e4567-e89b-12d3-a456-426614174000", "", "", "", "2026-10-26", "", 99999);
        var q = AuditSearch.build("t", cols(), f, AuditSearch.TimeUnitKind.SECONDS, ZoneId.of("Asia/Seoul"), 300);
        assertTrue(q.sql().contains("player_uuid = ?"));
        assertTrue(q.sql().endsWith("LIMIT 300"));
        assertEquals(1_792_940_400L, q.params().get(1));
    }

    // ---------------------------------------------------------------- 로그

    @Test void logLevelsAndSparkLink() {
        assertEquals(LogTail.Level.ERROR, LogTail.level("[12:00:00] [Server thread/ERROR]: x"));
        assertEquals(LogTail.Level.WARN, LogTail.level("[12:00:00] [Server thread/WARN]: x"));
        assertEquals(LogTail.Level.ERROR, LogTail.level("[12:00:00 ERROR]: velocity"));
        assertEquals(LogTail.Level.WARN, LogTail.level("\tat school.Foo.bar(Foo.java:1)"));
        assertEquals(LogTail.Level.INFO, LogTail.level("[12:00:00] [Server thread/INFO]: ERROR in chat text later" + " ".repeat(90) + "/ERROR]"));
        LogTail t = new LogTail(null, SPARK);
        t.add(1000, "[12:00:00] [Server thread/INFO]: §aProfiler results: https://spark.lucko.me/abcDEF123");
        t.add(1000, "\u001B[31m[12:00:01] [Server thread/ERROR]: boom\u001B[0m");
        assertEquals("https://spark.lucko.me/abcDEF123", t.sparkLink());
        assertEquals(1, t.errorsLastMinute(2000));
        assertEquals(0, t.errorsLastMinute(70_000));
        assertEquals("[12:00:01] [Server thread/ERROR]: boom", t.since(1, 10).get(0).text());
        assertEquals(1, t.problems(10).size());
    }

    @Test void logTailFollowsFileAndRestart() throws IOException {
        Path dir = Files.createTempDirectory("opslog");
        Path f = dir.resolve("latest.log");
        Files.writeString(f, "old line\n", StandardCharsets.UTF_8);
        LogTail t = new LogTail(f, SPARK);
        t.poll(1);
        assertEquals(1, t.seq());
        Files.writeString(f, "old line\n한글 줄\n반쪽", StandardCharsets.UTF_8);
        t.poll(2);
        assertEquals(2, t.seq()); // 줄 끝이 없는 "반쪽" 은 아직 안 읽음
        assertEquals("한글 줄", t.since(1, 10).get(0).text());
        Files.writeString(f, "new\n", StandardCharsets.UTF_8); // 서버 재시작으로 파일이 작아짐
        t.poll(3);
        List<LogTail.Line> after = t.since(2, 10);
        assertEquals("new", after.get(after.size() - 1).text());
    }

    // ---------------------------------------------------------------- 프로토콜

    @Test void rconPacketRoundTrip() throws IOException {
        byte[] b = Rcon.encode(new Rcon.Packet(7, Rcon.EXEC, "say 안녕"));
        assertEquals(4 + 4 + 4 + "say 안녕".getBytes(StandardCharsets.UTF_8).length + 2, b.length);
        Rcon.Packet p = Rcon.decode(b);
        assertEquals(7, p.id());
        assertEquals(Rcon.EXEC, p.type());
        assertEquals("say 안녕", p.body());
        assertEquals("There are 3", Rcon.stripColors("§aThere are §c3"));
    }

    @Test void pingParseAndVarInt() throws IOException {
        McPing.Result r = McPing.parse("{\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},\"players\":{\"max\":100,\"online\":12}}", 3);
        assertTrue(r.up());
        assertEquals(12, r.online());
        assertEquals(100, r.max());
        assertEquals("Paper 1.21.4", r.version());
        for (int v : new int[]{0, 1, 127, 128, 25565, 2097151, Integer.MAX_VALUE, -1}) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            McPing.writeVarInt(o, v);
            assertEquals(v, McPing.readVarInt(new java.io.ByteArrayInputStream(o.toByteArray())));
        }
    }

    // ---------------------------------------------------------------- AI

    @Test void responsesOutputText() {
        String json = "{\"id\":\"r\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]},{\"type\":\"message\",\"content\":"
                + "[{\"type\":\"output_text\",\"text\":\"야생 서버에서 문제가 발생했습니다.\"},{\"type\":\"output_text\",\"text\":\"\\n근거\"}]}]}";
        assertEquals("야생 서버에서 문제가 발생했습니다.\n근거", AiAnalyst.outputText(json));
        assertEquals("", AiAnalyst.outputText("{\"output\":[]}"));
    }

    @Test void trendLine() {
        var pts = List.of(new Monitor.Point(0, 20, 10, 30, 50, 5), new Monitor.Point(1, 12, 80, 90, 70, 9), new Monitor.Point(2, -1, -1, -1, -1, -1));
        String s = AiAnalyst.trend(pts);
        assertTrue(s.contains("TPS 최저 12.0 평균 16.0"), s);
        assertTrue(s.contains("MSPT 최고 80.0"), s);
        assertTrue(s.contains("응답 없던 표본 1/3"), s);
        assertEquals("기록 없음", AiAnalyst.trend(List.of()));
    }

    // ---------------------------------------------------------------- 설정

    @Test void configReadsServersAndSecretsFromEnv() throws IOException {
        Properties p = new Properties();
        p.setProperty("servers", "proxy, school");
        p.setProperty("server.school.label", "학교");
        p.setProperty("server.school.port", "25566");
        p.setProperty("server.school.stop", "\"C:/Program Files/PowerShell/7/pwsh.exe\" -File C:/x/Stop-Network.ps1 -Server {server}");
        p.setProperty("audit.col.detail", "");
        Map<String, String> env = Map.of("CHACA_OPS_PASSWORD", "long-enough-pw", "CHACA_OPS_RCON_SCHOOL", "rconpw", "CHACANPC_OPENAI_KEY", "sk-test");
        Config c = Config.of(p, env::get);
        assertEquals(Auth.sha256Hex("long-enough-pw"), c.passwordSha256);
        assertEquals(2, c.servers.size());
        assertEquals(Config.Kind.PROXY, c.server("proxy").kind());
        assertEquals(Config.Console.NONE, c.server("proxy").console());
        Config.Server s = c.server("school");
        assertEquals(Config.Kind.PAPER, s.kind());
        assertEquals(Config.Console.RCON, s.console());
        assertEquals("rconpw", s.rconPassword());
        assertEquals("spark profiler start --timeout 60", s.sparkCommand());
        assertEquals("sk-test", c.aiKey);
        assertEquals("", c.auditColumns.get("detail"));
        assertEquals(8470, c.port);
    }

    @Test void configRejectsMissingPasswordAndBadNames() {
        Properties p = new Properties();
        p.setProperty("servers", "school");
        assertThrows(IOException.class, () -> Config.of(p, k -> null));
        p.setProperty("password", "short");
        assertThrows(IOException.class, () -> Config.of(p, k -> null));
        p.setProperty("password", "long-enough-pw");
        p.setProperty("audit.table", "codex_audit_log; DROP TABLE x");
        assertThrows(IOException.class, () -> Config.of(p, k -> null));
        p.setProperty("audit.table", "codex_audit_log");
        p.setProperty("servers", "School!");
        assertThrows(IOException.class, () -> Config.of(p, k -> null));
    }
}
