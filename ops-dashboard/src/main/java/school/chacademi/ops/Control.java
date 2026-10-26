package school.chacademi.ops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 콘솔 명령 보내기(RCON 또는 스크립트)와 시작·정지·재시작(설정한 Windows 명령 실행).
 * 서버마다 조작은 한 번에 하나만. 정지·재시작·위험 명령은 화면에서 한 번 더 확인 + 서버도 확인 값을 검사한다.
 */
final class Control implements AutoCloseable {
    /** 서버 하나의 진행 중/마지막 조작. */
    static final class Job {
        final String server, action, ip;
        final long startedAt = System.currentTimeMillis();
        volatile String state = "진행 중";
        volatile boolean done;
        volatile boolean ok;
        final List<String> output = new ArrayList<>();
        Job(String server, String action, String ip) { this.server = server; this.action = action; this.ip = ip; }
        synchronized void out(String line) { if (output.size() < 300) output.add(line); }
        synchronized List<String> output() { return List.copyOf(output); }
    }

    static final Pattern DANGEROUS = Pattern.compile(
            "^(?:minecraft:)?(stop|end|restart|reload|rl|shutdown|kill|op|deop|ban|ban-ip|pardon|pardon-ip|whitelist|save-off|"
                    + "lp|luckperms|perm|perms|kick|velocity|greload|plugman|execute|fill|clone)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private final Config config;
    private final Monitor monitor;
    private final ActionLog log;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> Monitor.daemon(r, "ops-control"));

    Control(Config config, Monitor monitor, ActionLog log) {
        this.config = config;
        this.monitor = monitor;
        this.log = log;
    }

    static boolean dangerous(String command) {
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        return DANGEROUS.matcher(c).find();
    }

    Job job(String server) { return jobs.get(server); }

    /** 콘솔 명령. RCON 이면 응답 글을 돌려준다. 스크립트면 실행 결과 줄. */
    String command(Config.Server s, String command, String ip) throws IOException {
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isEmpty() || c.length() > 1000 || c.contains("\n") || c.contains("\r")) throw new IOException("명령이 비었거나 너무 김");
        String reply;
        try {
            reply = switch (s.console()) {
                case RCON -> Rcon.exec(s.host(), s.rconPort(), s.rconPassword(), c, 8000);
                case SCRIPT -> runScript(s.consoleScript(), Map.of("server", s.id(), "command", c), 30_000, null);
                case NONE -> throw new IOException(s.label() + " 은(는) 명령 보내기를 설정하지 않음 (server." + s.id() + ".console)");
            };
        } catch (IOException e) {
            log.write(ip, "명령", s.id(), c, "실패: " + why(e));
            throw e;
        }
        log.write(ip, "명령", s.id(), c, reply.isBlank() ? "보냄" : reply);
        return reply;
    }

    /** spark 프로파일러 60초. 결과 링크는 로그에서 잡는다 (LogTail spark.link-pattern). */
    String spark(Config.Server s, String ip) throws IOException {
        Monitor.View v = monitor.view(s.id());
        String reply = command(s, s.sparkCommand(), ip);
        if (v != null) { v.sparkRequestedAt = System.currentTimeMillis(); v.sparkReply = reply; }
        return reply;
    }

    /** 시작·정지·재시작. 이미 진행 중이면 예외. */
    Job control(Config.Server s, String action, String ip) throws IOException {
        if (!action.matches("start|stop|restart")) throw new IOException("start|stop|restart");
        Job running = jobs.get(s.id());
        if (running != null && !running.done) throw new IOException(s.label() + " 은(는) 이미 '" + running.action + "' 진행 중");
        String startCmd = s.start(), stopCmd = s.stop(), restartCmd = s.restart();
        if (action.equals("start") && startCmd.isEmpty()) throw new IOException("server." + s.id() + ".start 가 비어 있음");
        if (action.equals("stop") && stopCmd.isEmpty()) throw new IOException("server." + s.id() + ".stop 이 비어 있음");
        if (action.equals("restart") && restartCmd.isEmpty() && (startCmd.isEmpty() || stopCmd.isEmpty()))
            throw new IOException("server." + s.id() + ".restart 또는 start·stop 둘 다 필요");
        Job job = new Job(s.id(), action, ip);
        jobs.put(s.id(), job);
        log.write(ip, label(action), s.id(), "", "시작");
        workers.execute(() -> {
            try {
                switch (action) {
                    case "start" -> { step(job, startCmd, s); waitFor(job, s, true, config.startWaitMillis); }
                    case "stop" -> { step(job, stopCmd, s); waitFor(job, s, false, config.stopWaitMillis); }
                    default -> {
                        if (!restartCmd.isEmpty()) { step(job, restartCmd, s); }
                        else {
                            step(job, stopCmd, s);
                            if (!waitFor(job, s, false, config.stopWaitMillis)) throw new IOException("정지를 확인하지 못해 시작하지 않음");
                            Thread.sleep(3000);
                            step(job, startCmd, s);
                        }
                        waitFor(job, s, true, config.startWaitMillis);
                    }
                }
                job.ok = !job.state.startsWith("확인 못 함");
                if (job.ok) job.state = "완료";
            } catch (Exception e) {
                job.ok = false;
                job.state = "실패: " + why(e);
            } finally {
                job.done = true;
                log.write(ip, label(action), s.id(), "", job.state);
            }
        });
        return job;
    }

    static String why(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    static String label(String action) {
        return switch (action) { case "start" -> "시작"; case "stop" -> "정지"; default -> "재시작"; };
    }

    private void step(Job job, String commandLine, Config.Server s) throws IOException, InterruptedException {
        job.state = "명령 실행 중";
        job.out("> " + commandLine);
        String out = runScript(commandLine, Map.of("server", s.id()), config.controlTimeoutMillis, job);
        if (!out.isBlank()) job.out(out.length() > 2000 ? out.substring(0, 2000) : out);
    }

    /** 핑(+하트비트)이 원하는 상태가 될 때까지. */
    private boolean waitFor(Job job, Config.Server s, boolean up, long timeoutMs) throws InterruptedException {
        job.state = up ? "켜지기 기다리는 중" : "꺼지기 기다리는 중";
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            boolean pingUp = McPing.ping(s.host(), s.port(), 2000).up();
            if (up && pingUp) { job.out("켜짐 확인"); return true; }
            if (!up && !pingUp) { job.out("꺼짐 확인"); return true; }
            Thread.sleep(3000);
        }
        job.state = "확인 못 함 (" + timeoutMs / 1000 + "초 안에 " + (up ? "켜지지" : "꺼지지") + " 않음)";
        job.out(job.state);
        return false;
    }

    /** 명령줄 실행. {server} {command} 는 인자 하나로 바뀐다. control.shell 이 있으면 그 앞에 붙인다. */
    String runScript(String commandLine, Map<String, String> vars, long timeoutMs, Job job) throws IOException {
        if (commandLine == null || commandLine.isBlank()) throw new IOException("실행할 명령이 설정되지 않음");
        List<String> args = new ArrayList<>();
        if (!config.shell.isBlank()) args.addAll(tokenize(config.shell));
        for (String token : tokenize(commandLine)) args.add(substitute(token, vars));
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        if (config.file != null && config.file.toAbsolutePath().getParent() != null)
            pb.directory(config.file.toAbsolutePath().getParent().toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), config.outputCharset))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) { if (sb.length() < 20_000) sb.append(line).append('\n'); }
                }
            } catch (IOException ignored) { }
        }, "ops-script-out");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException("명령이 " + timeoutMs / 1000 + "초 안에 끝나지 않아 중단");
            }
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("중단됨");
        }
        String out;
        synchronized (sb) { out = sb.toString().strip(); }
        if (p.exitValue() != 0) throw new IOException("종료 코드 " + p.exitValue() + (out.isEmpty() ? "" : ": " + tail(out, 400)));
        return out;
    }

    static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    static String substitute(String token, Map<String, String> vars) {
        String out = token;
        for (var e : vars.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    /** 큰따옴표로 묶은 부분은 한 인자 ("C:\Program Files\..." 그대로). 백슬래시는 그대로 둔다 (Windows 경로). */
    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { quoted = !quoted; any = true; continue; }
            if (!quoted && Character.isWhitespace(c)) {
                if (any || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); any = false; }
                continue;
            }
            cur.append(c);
        }
        if (any || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    @Override public void close() { workers.shutdownNow(); }
}
