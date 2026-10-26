package school.chacademi.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * logs/latest.log 끝부분 따라 읽기. 매번 열고 바로 닫는다 (log4j 가 시작할 때 latest.log 를 이름 바꿀 수 있게 — NIO 는 Windows 에서
 * 삭제·이름 바꾸기 공유를 허용). 파일이 줄거나 새로 만들어지면 처음부터 다시 읽는다. 게임 서버 프로세스와 무관하다.
 */
final class LogTail {
    enum Level { INFO, WARN, ERROR }

    record Line(long seq, long at, Level level, String text) { }

    static final int KEEP = 1500;
    static final int KEEP_PROBLEMS = 300;
    private static final int FIRST_READ = 128 * 1024;
    private static final int MAX_READ = 1024 * 1024;
    private static final int MAX_LINE = 2000;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private final Path file;
    private final Pattern sparkLink;
    private final Deque<Line> lines = new ArrayDeque<>();
    private final Deque<Line> problems = new ArrayDeque<>();
    private final Deque<Long> errorTimes = new ArrayDeque<>();
    private long seq;
    private long offset = -1;
    private Object fileIdentity;
    private String lastSparkLink = "";
    private long lastSparkAt;
    private String error = "";

    LogTail(Path file, Pattern sparkLink) {
        this.file = file;
        this.sparkLink = sparkLink;
    }

    /** 1초마다 모니터 스레드에서. */
    synchronized void poll(long now) {
        if (file == null) return;
        try {
            if (!Files.exists(file)) { error = "로그 파일 없음: " + file; return; }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            Object identity = attrs.fileKey() != null ? attrs.fileKey() : attrs.creationTime();
            long size = attrs.size();
            if (offset < 0 || !identity.equals(fileIdentity) || size < offset) {
                if (offset >= 0) add(now, "── 로그 파일이 새로 시작됨 (서버 재시작?) ──");
                fileIdentity = identity;
                offset = Math.max(0, size - FIRST_READ);
                if (offset > 0) offset = skipPartial(offset, size);
            }
            if (size == offset) { error = ""; return; }
            long from = offset;
            if (size - from > MAX_READ) { add(now, "── 로그가 너무 빨리 늘어 " + ((size - from - MAX_READ) / 1024) + "KB 건너뜀 ──"); from = size - MAX_READ; }
            byte[] data = read(from, (int) (size - from));
            int last = lastNewline(data);
            if (last < 0) { offset = from; return; } // 아직 줄 끝 없음
            String text = new String(data, 0, last + 1, StandardCharsets.UTF_8);
            for (String raw : text.split("\r?\n")) if (!raw.isEmpty()) add(now, raw);
            offset = from + last + 1;
            error = "";
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private long skipPartial(long start, long size) throws IOException {
        byte[] head = read(start, (int) Math.min(8192, size - start));
        for (int i = 0; i < head.length; i++) if (head[i] == '\n') return start + i + 1;
        return start;
    }

    private byte[] read(long from, int length) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            ch.position(from);
            while (buf.hasRemaining()) { if (ch.read(buf) < 0) break; }
            byte[] out = new byte[buf.position()];
            buf.flip();
            buf.get(out);
            return out;
        }
    }

    private static int lastNewline(byte[] data) {
        for (int i = data.length - 1; i >= 0; i--) if (data[i] == '\n') return i;
        return -1;
    }

    /** 테스트·내부용: 한 줄 추가. */
    synchronized void add(long now, String raw) {
        String text = ANSI.matcher(raw).replaceAll("");
        text = Rcon.stripColors(text);
        if (text.length() > MAX_LINE) text = text.substring(0, MAX_LINE) + "…";
        Level level = level(text);
        Line line = new Line(++seq, now, level, text);
        lines.addLast(line);
        while (lines.size() > KEEP) lines.removeFirst();
        if (level != Level.INFO) {
            problems.addLast(line);
            while (problems.size() > KEEP_PROBLEMS) problems.removeFirst();
        }
        if (level == Level.ERROR) errorTimes.addLast(now);
        Matcher m = sparkLink.matcher(text);
        if (m.find()) { lastSparkLink = m.group(); lastSparkAt = now; }
    }

    /** Paper 형식 "[12:00:00] [Server thread/ERROR]: ..." 와 Velocity "[12:00:00 ERROR]: ..." 둘 다. */
    static Level level(String text) {
        int end = Math.min(text.length(), 80);
        String head = text.substring(0, end);
        if (head.contains("/ERROR]") || head.contains(" ERROR]") || head.contains("/FATAL]") || head.contains("[SEVERE]") || head.contains("/SEVERE]"))
            return Level.ERROR;
        if (head.contains("/WARN]") || head.contains(" WARN]") || head.contains("[WARNING]") || head.contains("/WARNING]"))
            return Level.WARN;
        // 예외 스택은 앞 줄이 ERROR 여도 따로 표시되도록
        if (text.startsWith("\tat ") || text.startsWith("Caused by: ")) return Level.WARN;
        return Level.INFO;
    }

    synchronized List<Line> since(long after, int max) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (l.seq() > after) out.add(l);
        if (out.size() > max) out = new ArrayList<>(out.subList(out.size() - max, out.size()));
        return out;
    }

    synchronized List<Line> problems(int max) {
        List<Line> out = new ArrayList<>(problems);
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    /** 최근 60초 ERROR 줄 수. */
    synchronized int errorsLastMinute(long now) {
        while (!errorTimes.isEmpty() && errorTimes.peekFirst() < now - 60_000) errorTimes.removeFirst();
        return errorTimes.size();
    }

    synchronized long seq() { return seq; }
    synchronized String sparkLink() { return lastSparkLink; }
    synchronized long sparkAt() { return lastSparkAt; }
    synchronized String error() { return error; }
}
