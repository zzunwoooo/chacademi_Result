package school.chacademi.ops;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 대시보드 자체 작업 기록 (누가·언제·무슨 버튼). 비밀번호가 하나라 "누가" = 접속 IP.
 * data-dir/ops-actions.log, 한 줄 = 시각 \t IP \t 작업 \t 대상 \t 내용 \t 결과 (탭·줄바꿈은 공백으로).
 */
final class ActionLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path file;
    private final ZoneId zone;

    ActionLog(Path dir, ZoneId zone) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("ops-actions.log");
        this.zone = zone;
    }

    synchronized void write(String ip, String action, String target, String detail, String result) {
        String line = String.join("\t", ZonedDateTime.now(zone).format(TIME), clean(ip), clean(action), clean(target),
                clean(detail), clean(result)) + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("작업 기록 쓰기 실패: " + e.getMessage());
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > 500 ? t.substring(0, 500) + "…" : t;
    }

    /** 최근 max 줄 (새것이 먼저). 파일 끝 256KB 만 읽는다. */
    synchronized List<String[]> recent(int max) {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - 256 * 1024);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = lines.length - 1; i >= 0 && out.size() < max; i--) {
                if (i == 0 && from > 0) break; // 잘린 첫 줄
                if (lines[i].isBlank()) continue;
                out.add(lines[i].split("\t", -1));
            }
        } catch (IOException e) {
            out.add(new String[]{"", "", "기록 읽기 실패", "", e.getMessage(), ""});
        }
        return out;
    }
}
