package school.magiccodex.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-21: 창시자 연출은 미리 정한 유저만 (C11), 권한 소속은 다시 분류하지 않음 (D-13), 소속 초기화 (D-12). */
class SchoolHouseFixTest {
    @TempDir Path dir;

    @Test void onlyAdminPresetUsersAreFounders() throws Exception {
        try (var s = new SchoolStore(dir.resolve("preset.db"))) {
            UUID preset = UUID.randomUUID(), sorted = UUID.randomUUID(), fresh = UUID.randomUUID();
            s.presetHouse(preset, 1);
            var a = s.assignHouse(sorted, 3, 3, 2);
            assertTrue(a.created());
            assertEquals(new SchoolStore.Start(1, true), s.sortingStart(preset));
            assertEquals(new SchoolStore.Start(a.house(), false), s.sortingStart(sorted), "분류로 배정된 일반 유저는 창시자가 아니다");
            assertEquals(new SchoolStore.Start(null, false), s.sortingStart(fresh));
        }
    }

    @Test void presetSurvivesRestart() throws Exception {
        Path file = dir.resolve("restart.db");
        UUID id = UUID.randomUUID();
        try (var s = new SchoolStore(file)) { s.presetHouse(id, 2); s.presetHouse(id, 2); }
        try (var s = new SchoolStore(file)) { assertEquals(new SchoolStore.Start(2, true), s.sortingStart(id)); }
    }

    @Test void permissionHouseIsAdoptedNotResorted() throws Exception {
        try (var s = new SchoolStore(dir.resolve("adopt.db"))) {
            UUID id = UUID.randomUUID();
            var first = s.adoptHouse(id, 2);
            assertEquals(2, first.house());
            assertTrue(first.created());
            var again = s.adoptHouse(id, 0);
            assertEquals(2, again.house(), "이미 DB 소속이 있으면 그대로");
            assertFalse(again.created());
            assertFalse(s.assignHouse(id, 3, 3, 2).created(), "결정이 다시 와도 새로 배정하지 않는다");
            assertEquals(new SchoolStore.Start(2, false), s.sortingStart(id));
        }
    }

    @Test void clearHouseRemovesAssignmentAndPresetFromCounts() throws Exception {
        try (var s = new SchoolStore(dir.resolve("clear.db"))) {
            UUID id = UUID.randomUUID();
            s.presetHouse(id, 0);
            assertEquals(1, s.houseCounts()[0]);
            assertTrue(s.clearHouse(id));
            assertEquals(0, s.houseCounts()[0]);
            assertEquals(new SchoolStore.Start(null, false), s.sortingStart(id));
            assertFalse(s.clearHouse(id));
            s.assignHouse(id, 0, 0, 2);
            assertFalse(s.sortingStart(id).preset(), "초기화 뒤 다시 분류하면 창시자 표시가 남지 않는다");
        }
    }
}
