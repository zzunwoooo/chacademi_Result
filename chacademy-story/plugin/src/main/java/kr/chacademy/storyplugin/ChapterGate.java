package kr.chacademy.storyplugin;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스토리 장(챕터) 공개 제한 (config.yml chapter-release). Bukkit 에 의존하지 않는다 (테스트용).
 * <ul>
 *   <li>open-through 이하 번호의 장은 항상 공개 (기본 2 = 0장 튜토리얼·1장·2장).</li>
 *   <li>그보다 높은 장은 chapters.&lt;번호&gt;.open: true 이거나 open-at 시각이 지났을 때만 공개. 적지 않은 장은 잠김.</li>
 *   <li>장 번호는 단계 ID 의 "ch&lt;번호&gt;-" 로 정한다 (장 표시 이름이 바뀌어도 그대로). 없으면 장 이름 앞의 "&lt;번호&gt;장".
 *       둘 다 없으면 장이 없는 단계 = 항상 공개.</li>
 * </ul>
 * 잠긴 장의 단계는 호출(포탈·NPC·자동·컷신·관리자 강제 진행)과 시작명령이 실행되지 않는다 (QuestEngine).
 */
final class ChapterGate {
    static final String DEFAULT_TITLE = "아직 열리지 않은 이야기입니다";
    static final String DEFAULT_BODY = "다음 이야기가 공개되면 이어서 진행할 수 있어요.";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern STEP_ID = Pattern.compile("^ch(\\d{1,3})(?:[-_].*)?$");
    private static final Pattern CHAPTER_TEXT = Pattern.compile("^\\s*(\\d{1,3})\\s*장");

    /** openAt = 공개 시각 (epoch ms), null = 시각 없음. */
    record Release(boolean open, Long openAt) {}

    final int openThrough;
    final Map<Integer, Release> chapters;
    final String title, body;

    ChapterGate(int openThrough, Map<Integer, Release> chapters, String title, String body) {
        this.openThrough = openThrough;
        this.chapters = chapters == null ? Map.of() : Map.copyOf(new TreeMap<>(chapters));
        this.title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        this.body = body == null ? "" : body;
    }

    /** 설정이 없을 때: 2장까지 공개, 3장부터 잠금. */
    static ChapterGate defaults() { return new ChapterGate(2, Map.of(), DEFAULT_TITLE, DEFAULT_BODY); }

    /** 단계의 장 번호. -1 = 장 없음 (항상 공개). */
    static int chapterOf(String stepId, String chapterText) {
        if (stepId != null) {
            Matcher m = STEP_ID.matcher(stepId);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        if (chapterText != null) {
            Matcher m = CHAPTER_TEXT.matcher(chapterText);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    boolean locked(int chapter, long nowMs) {
        if (chapter < 0 || chapter <= openThrough) return false;
        Release r = chapters.get(chapter);
        if (r == null) return true;
        if (r.open()) return false;
        return r.openAt() == null || nowMs < r.openAt();
    }

    /** "yyyy-MM-dd HH:mm" (zone 기준) → epoch ms. 비었으면 null, 형식이 틀리면 warnings 에 남기고 null (= 시각 없음, 잠김 유지). */
    static Long parseTime(String raw, ZoneId zone, List<String> warnings, int chapter) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.strip(), TIME).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            if (warnings != null) warnings.add(chapter + "장 open-at 형식 오류 (\"2026-11-04 20:00\" 처럼): " + raw + " — 시각 없이 잠금 유지");
            return null;
        }
    }

    /** 운영자용 한 줄 설명. */
    String describe(int chapter, long nowMs, ZoneId zone) {
        if (chapter <= openThrough) return chapter + "장: 공개 (open-through " + openThrough + " 이하)";
        Release r = chapters.get(chapter);
        String at = r == null || r.openAt() == null ? "" : " · 공개 시각 " + TIME.format(java.time.Instant.ofEpochMilli(r.openAt()).atZone(zone));
        return chapter + "장: " + (locked(chapter, nowMs) ? "잠김" : "공개") + at;
    }
}
