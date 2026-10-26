package school.chacademi.ops;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 페이지(index.html) + JSON API. 로그인 말고는 전부 세션 쿠키 필요.
 * POST 는 헤더 X-Ops: 1 이 있어야 한다 (다른 사이트가 몰래 버튼을 누르게 하는 요청 차단 — SameSite=Strict 쿠키와 같이).
 */
final class Web implements HttpHandler {
    private static final String COOKIE = "chaca_ops";
    private final Config config;
    private final Auth auth;
    private final Monitor monitor;
    private final Control control;
    private final AuditSearch audit;
    private final AiAnalyst ai;
    private final ActionLog log;
    private final byte[] page;

    Web(Config config, Auth auth, Monitor monitor, Control control, AuditSearch audit, AiAnalyst ai, ActionLog log) throws IOException {
        this.config = config;
        this.auth = auth;
        this.monitor = monitor;
        this.control = control;
        this.audit = audit;
        this.ai = ai;
        this.log = log;
        try (InputStream in = Web.class.getResourceAsStream("/ops/index.html")) {
            if (in == null) throw new IOException("index.html 이 jar 에 없음");
            page = in.readAllBytes();
        }
    }

    @Override public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (HttpError e) {
            json(ex, e.code, Json.map("error", e.getMessage()));
        } catch (Exception e) {
            json(ex, 500, Json.map("error", "서버 오류: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())));
        } finally {
            ex.close();
        }
    }

    static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String message) { super(message); this.code = code; }
    }

    private void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        securityHeaders(ex);
        if (path.equals("/") || path.equals("/index.html")) {
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(page); }
            return;
        }
        if (path.equals("/healthz")) { text(ex, 200, "ok"); return; }
        if (!path.startsWith("/api/")) { text(ex, 404, "없음"); return; }
        boolean post = method.equals("POST");
        if (post && !"1".equals(ex.getRequestHeaders().getFirst("X-Ops"))) throw new HttpError(403, "잘못된 요청");
        if (!post && !method.equals("GET")) throw new HttpError(405, "GET/POST 만");
        String ip = ip(ex);
        if (path.equals("/api/login") && post) { login(ex, ip); return; }
        String token = cookie(ex, COOKIE);
        if (!auth.valid(token)) throw new HttpError(401, "로그인이 필요합니다");
        Map<String, String> q = query(ex);
        switch (path) {
            case "/api/logout" -> { auth.logout(token); clearCookie(ex); log.write(ip, "로그아웃", "", "", ""); json(ex, 200, Json.map("ok", true)); }
            case "/api/status" -> json(ex, 200, status());
            case "/api/console" -> json(ex, 200, console(q));
            case "/api/command" -> { need(post); command(ex, ip); }
            case "/api/control" -> { need(post); control(ex, ip); }
            case "/api/spark" -> { need(post); spark(ex, ip); }
            case "/api/ai" -> { need(post); aiRequest(ex, ip); }
            case "/api/audit" -> json(ex, 200, auditSearch(q, ip));
            case "/api/audit/kinds" -> json(ex, 200, Json.map("kinds", audit.kinds()));
            case "/api/actions" -> json(ex, 200, actions());
            default -> throw new HttpError(404, "없음");
        }
    }

    private static void need(boolean post) throws HttpError { if (!post) throw new HttpError(405, "POST 만"); }

    // ------------------------------------------------------------ 로그인

    private void login(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = body(ex);
        String[] token = new String[1];
        Auth.Result r = auth.login(ip, Json.str(body, "password"), token);
        switch (r) {
            case OK -> {
                String flags = "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + config.sessionMillis / 1000 + (config.tlsKeystore != null ? "; Secure" : "");
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token[0] + flags);
                log.write(ip, "로그인", "", "", "성공");
                json(ex, 200, Json.map("ok", true));
            }
            case WRONG -> {
                log.write(ip, "로그인", "", "", "비밀번호 틀림");
                Thread.sleep(600);
                throw new HttpError(401, "비밀번호가 틀렸습니다");
            }
            default -> {
                log.write(ip, "로그인", "", "", "잠김");
                throw new HttpError(429, "여러 번 틀려 잠겼습니다. " + Math.max(1, auth.lockedSeconds(ip) / 60) + "분 뒤에 다시 해 주세요");
            }
        }
    }

    private void clearCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    // ------------------------------------------------------------ 상태

    private Map<String, Object> status() {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", now);
        out.put("db", monitor.dbStatus());
        Monitor.Host h = monitor.host();
        out.put("host", Json.map("cpu", h.cpu(), "memTotal", h.memTotalMb(), "memFree", h.memFreeMb(), "diskFree", h.diskFreeGb(), "diskTotal", h.diskTotalGb()));
        List<Object> alarms = new ArrayList<>();
        for (Alarms.Alarm a : monitor.alarms()) alarms.add(Json.map("server", a.server(), "code", a.code(), "critical", a.critical(), "message", a.message()));
        out.put("alarms", alarms);
        AiAnalyst.Analysis last = ai.last();
        out.put("ai", Json.map("available", ai.available(), "running", ai.running(), "used", ai.usedToday(), "max", config.aiMaxPerDay,
                "auto", config.aiAuto,
                "last", last == null ? null : Json.map("at", last.at(), "trigger", last.trigger(), "text", last.text(), "ok", last.ok())));
        List<Object> servers = new ArrayList<>();
        for (Monitor.View v : monitor.views()) servers.add(server(v, now));
        out.put("servers", servers);
        return out;
    }

    private Map<String, Object> server(Monitor.View v, long now) {
        Config.Server s = v.server;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("label", s.label());
        m.put("kind", s.paper() ? "paper" : "proxy");
        m.put("status", v.status.name());
        McPing.Result p = v.ping;
        m.put("ping", Json.map("up", p.up(), "online", p.online(), "max", p.max(), "version", p.version(), "latency", p.latencyMs(), "error", p.error()));
        Alarms.Heartbeat hb = v.hb;
        if (hb != null) {
            m.put("hb", Json.map("age", (now - hb.updatedAt()) / 1000, "state", hb.state(), "source", hb.source(), "startedAt", hb.startedAt(),
                    "tps10s", hb.tps10s(), "tps1m", hb.tps1m(), "tps5m", hb.tps5m(), "tps15m", hb.tps15m(),
                    "mspt", hb.msptMean(), "msptMax", hb.msptMax(), "msptP95", hb.msptP95(),
                    "cpu", hb.cpuProcess(), "cpuSystem", hb.cpuSystem(), "heapUsed", hb.heapUsedMb(), "heapMax", hb.heapMaxMb(),
                    "online", hb.online(), "maxPlayers", hb.maxPlayers(), "chunks", hb.chunks(), "entities", hb.entities(), "version", hb.version()));
        }
        List<Object> t = new ArrayList<>(), ms = new ArrayList<>(), o = new ArrayList<>();
        for (Monitor.Point pt : v.history(120)) { t.add(pt.tps()); ms.add(pt.mspt()); o.add(pt.online()); }
        m.put("history", Json.map("tps", t, "mspt", ms, "online", o));
        m.put("console", s.console().name().toLowerCase());
        m.put("manageUrl", s.manageUrl());
        m.put("canStart", !s.start().isEmpty());
        m.put("canStop", !s.stop().isEmpty());
        m.put("canRestart", !s.restart().isEmpty() || (!s.start().isEmpty() && !s.stop().isEmpty()));
        if (v.tail != null) {
            m.put("log", Json.map("error", v.tail.error(), "errorsPerMin", v.tail.errorsLastMinute(now)));
            m.put("spark", Json.map("link", v.tail.sparkLink(), "at", v.tail.sparkAt(), "requestedAt", v.sparkRequestedAt, "reply", v.sparkReply));
        } else {
            m.put("log", Json.map("error", "로그 파일 설정 없음 (server." + s.id() + ".log)", "errorsPerMin", 0));
            m.put("spark", Json.map("link", "", "at", 0, "requestedAt", v.sparkRequestedAt, "reply", v.sparkReply));
        }
        Control.Job job = control.job(s.id());
        if (job != null && (!job.done || now - job.startedAt < 30 * 60_000L))
            m.put("job", Json.map("action", Control.label(job.action), "state", job.state, "done", job.done, "ok", job.ok,
                    "startedAt", job.startedAt, "ip", job.ip, "output", job.output()));
        List<Object> alarms = new ArrayList<>();
        for (Alarms.Alarm a : v.alarms) alarms.add(a.message());
        m.put("alarms", alarms);
        return m;
    }

    // ------------------------------------------------------------ 콘솔

    private Map<String, Object> console(Map<String, String> q) throws HttpError {
        Monitor.View v = view(q.get("server"));
        if (v.tail == null) return Json.map("seq", 0, "lines", List.of(), "error", "로그 파일 설정 없음");
        long since = parseLong(q.get("since"), 0);
        List<Object> lines = new ArrayList<>();
        for (LogTail.Line l : v.tail.since(since, since == 0 ? 400 : 1000))
            lines.add(Json.map("s", l.seq(), "t", l.at(), "l", l.level().name(), "x", l.text()));
        return Json.map("seq", v.tail.seq(), "lines", lines, "error", v.tail.error());
    }

    private void command(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = body(ex);
        Monitor.View v = view(Json.str(body, "server"));
        String cmd = Json.str(body, "command");
        if (cmd == null || cmd.isBlank()) throw new HttpError(400, "명령을 적어 주세요");
        if (Control.dangerous(cmd) && !Boolean.TRUE.equals(body.get("confirm")))
            throw new HttpError(409, "위험한 명령입니다. 한 번 더 확인해 주세요");
        try { json(ex, 200, Json.map("ok", true, "reply", control.command(v.server, cmd, ip))); }
        catch (IOException e) { throw new HttpError(502, "명령 보내기 실패: " + Control.why(e)); }
    }

    private void control(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = body(ex);
        Monitor.View v = view(Json.str(body, "server"));
        String action = String.valueOf(body.get("action"));
        // 정지·재시작은 서버 이름을 그대로 다시 적어야 한다 (화면의 두 번째 확인)
        if (!action.equals("start") && !v.server.id().equals(Json.str(body, "confirm")))
            throw new HttpError(409, "확인 칸에 서버 이름(" + v.server.id() + ")을 정확히 적어 주세요");
        try {
            Control.Job job = control.control(v.server, action, ip);
            json(ex, 200, Json.map("ok", true, "state", job.state));
        } catch (IOException e) { throw new HttpError(409, e.getMessage()); }
    }

    private void spark(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = body(ex);
        Monitor.View v = view(Json.str(body, "server"));
        if (System.currentTimeMillis() - v.sparkRequestedAt < 70_000) throw new HttpError(409, "이미 측정 중입니다 (60초)");
        try { json(ex, 200, Json.map("ok", true, "reply", control.spark(v.server, ip))); }
        catch (IOException e) { throw new HttpError(502, "spark 명령 실패: " + Control.why(e)); }
    }

    private void aiRequest(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = body(ex);
        String focus = Json.str(body, "server");
        if (focus != null && !focus.isBlank()) view(focus);
        String refused = ai.requestManual(ip, focus);
        if (refused != null) throw new HttpError(409, refused);
        json(ex, 200, Json.map("ok", true));
    }

    // ------------------------------------------------------------ 기록

    private Map<String, Object> auditSearch(Map<String, String> q, String ip) throws HttpError {
        var f = new AuditSearch.Filter(q.get("name"), q.get("kind"), q.get("server"), q.get("from"), q.get("to"), q.get("q"),
                (int) parseLong(q.get("limit"), 100));
        try {
            var rows = audit.search(f);
            log.write(ip, "기록 검색", "", "닉=" + nz(f.name()) + " 종류=" + nz(f.kind()) + " 글자=" + nz(f.text()), rows.size() + "건");
            return Json.map("rows", rows, "max", config.auditMaxRows);
        } catch (SQLException e) {
            throw new HttpError(502, "검색 실패: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new HttpError(400, "검색 조건 형식: " + e.getMessage());
        }
    }

    private Map<String, Object> actions() {
        List<Object> rows = new ArrayList<>();
        for (String[] r : log.recent(300)) {
            rows.add(Json.map("time", r.length > 0 ? r[0] : "", "ip", r.length > 1 ? r[1] : "", "action", r.length > 2 ? r[2] : "",
                    "target", r.length > 3 ? r[3] : "", "detail", r.length > 4 ? r[4] : "", "result", r.length > 5 ? r[5] : ""));
        }
        return Json.map("rows", rows);
    }

    // ------------------------------------------------------------ 도우미

    private Monitor.View view(String id) throws HttpError {
        Monitor.View v = id == null ? null : monitor.view(id);
        if (v == null) throw new HttpError(404, "그런 서버가 없습니다: " + id);
        return v;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long parseLong(String s, long def) {
        try { return s == null || s.isBlank() ? def : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private String ip(HttpExchange ex) {
        if (config.trustForwardedFor) {
            String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (f != null && !f.isBlank()) return f.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) for (String part : h.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, Object> body(HttpExchange ex) throws HttpError, IOException {
        byte[] data = ex.getRequestBody().readNBytes(64 * 1024 + 1);
        if (data.length > 64 * 1024) throw new HttpError(413, "요청이 너무 큼");
        try { return Json.parseObject(new String(data, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new HttpError(400, "JSON 형식"); }
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
    }

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] data = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(data); }
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(data); }
    }
}
