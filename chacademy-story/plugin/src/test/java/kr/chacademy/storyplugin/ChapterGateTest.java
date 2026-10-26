package kr.chacademy.storyplugin;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChapterGateTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test void chapterNumberComesFromStepIdThenChapterText() {
        assertEquals(3, ChapterGate.chapterOf("ch3-1", "1장 · 이름이 바뀜"));
        assertEquals(12, ChapterGate.chapterOf("ch12_intro", ""));
        assertEquals(1, ChapterGate.chapterOf("ch1-15r-arkeon", ""));
        assertEquals(0, ChapterGate.chapterOf("tutorial", "0장 · 튜토리얼"));
        assertEquals(3, ChapterGate.chapterOf("side-a", " 3장 · 잿빛"));
        assertEquals(-1, ChapterGate.chapterOf("tutorial", ""));
        assertEquals(-1, ChapterGate.chapterOf("magic_tutorial", null));
        assertEquals(-1, ChapterGate.chapterOf("chapter-x", "프롤로그"));
    }

    @Test void defaultsOpenThroughChapterTwoAndLockLaterChapters() {
        ChapterGate g = ChapterGate.defaults();
        long now = System.currentTimeMillis();
        assertFalse(g.locked(-1, now));
        assertFalse(g.locked(0, now));
        assertFalse(g.locked(1, now));
        assertFalse(g.locked(2, now));
        assertTrue(g.locked(3, now));
        assertTrue(g.locked(4, now));
        assertEquals(ChapterGate.DEFAULT_TITLE, g.title);
    }

    @Test void explicitOpenAndOpenAtRelease() {
        List<String> warnings = new ArrayList<>();
        Long at = ChapterGate.parseTime("2026-11-04 20:00", KST, warnings, 3);
        assertNotNull(at);
        ChapterGate g = new ChapterGate(2, Map.of(3, new ChapterGate.Release(false, at), 4, new ChapterGate.Release(true, null)), "", null);
        assertTrue(g.locked(3, at - 1));
        assertFalse(g.locked(3, at));
        assertFalse(g.locked(4, 0));
        assertTrue(g.locked(5, Long.MAX_VALUE));
        assertEquals(ChapterGate.DEFAULT_TITLE, g.title);
        assertTrue(warnings.isEmpty());
    }

    @Test void blankOrBrokenTimeKeepsChapterLocked() {
        List<String> warnings = new ArrayList<>();
        assertNull(ChapterGate.parseTime("", KST, warnings, 3));
        assertTrue(warnings.isEmpty());
        assertNull(ChapterGate.parseTime("11월 4일", KST, warnings, 3));
        assertEquals(1, warnings.size());
        ChapterGate g = new ChapterGate(2, Map.of(3, new ChapterGate.Release(false, null)), "잠김", "");
        assertTrue(g.locked(3, Long.MAX_VALUE));
    }
}
