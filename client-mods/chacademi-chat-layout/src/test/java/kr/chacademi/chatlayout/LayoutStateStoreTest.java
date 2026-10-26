package kr.chacademi.chatlayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LayoutStateStoreTest {
    @TempDir Path directory;

    @Test
    void missingAndCorruptFilesReturnEmptyStateWithoutWriting() throws Exception {
        Path path = directory.resolve("layout.json");
        LayoutStateStore store = new LayoutStateStore(path);
        assertTrue(store.load().windows.isEmpty());
        assertFalse(Files.exists(path));
        Files.writeString(path, "{ definitely invalid");
        assertTrue(store.load().windows.isEmpty());
        Files.writeString(path, "null");
        assertTrue(store.load().windows.isEmpty());
        Files.writeString(path, "{\"windows\":\"invalid\"}");
        assertTrue(store.load().windows.isEmpty());
    }

    @Test
    void roundTripPreservesIndependentWindowsAndOpacity() throws Exception {
        Path path = directory.resolve("nested/layout.json");
        LayoutStateStore store = new LayoutStateStore(path);
        LayoutState state = new LayoutState();
        WindowState first = new WindowState(), second = new WindowState();
        first.signature = LayoutState.signature(List.of("global", "party"));
        first.anchor(.2, .1);
        first.resize(.6, .4);
        first.maximize();
        first.close();
        second.signature = LayoutState.signature(List.of("system"));
        second.systemDefault = true;
        second.anchor(.4, .55);
        second.resize(.3, .12);
        second.minimize();
        second.toggleLock();
        state.windows.add(first);
        state.windows.add(second);
        state.backgroundOpacity = .25;
        state.borderOpacity = .75;
        store.save(state);
        LayoutState restored = store.load();
        assertEquals(2, restored.windows.size());
        WindowState a = restored.windows.get(0), b = restored.windows.get(1);
        assertEquals(first.id, a.id);
        assertEquals(first.signature, a.signature);
        assertEquals(.2, a.x);
        assertEquals(.1, a.y);
        assertEquals(.6, a.width);
        assertEquals(.4, a.height);
        assertFalse(a.locked);
        assertEquals(UiState.Mode.CLOSED, a.mode);
        assertEquals(UiState.Mode.NORMAL, a.restore);
        assertEquals(second.id, b.id);
        assertEquals(.4, b.x);
        assertEquals(.55, b.y);
        assertEquals(.3, b.width);
        assertEquals(.12, b.height);
        assertTrue(b.locked);
        assertTrue(b.systemDefault);
        assertEquals(UiState.Mode.NORMAL, b.mode);
        assertEquals(UiState.Mode.NORMAL, b.restore);
        assertEquals(.25, restored.backgroundOpacity);
        assertEquals(.75, restored.borderOpacity);
        assertFalse(Files.exists(path.resolveSibling("layout.json.tmp")));
        assertFalse(Files.readString(path).contains("\"messages\""));
    }

    @Test
    void legacyMigrationPreservesAllTwoWindowFields() throws Exception {
        Path path = directory.resolve("legacy.json");
        String legacy = """
                {
                  "upperX": 0.2, "upperY": 0.1, "upperWidth": 0.6, "upperHeight": 0.4,
                  "lowerX": 0.4, "lowerY": 0.55, "lowerWidth": 0.3, "lowerHeight": 0.12,
                  "upperLocked": true, "lowerLocked": false,
                  "upper": "MINIMIZED", "upperRestore": "MAXIMIZED",
                  "lower": "CLOSED", "lowerRestore": "MAXIMIZED"
                }
                """;
        Files.writeString(path, legacy);
        LayoutState migrated = new LayoutStateStore(path).load();
        assertEquals(legacy, Files.readString(path));
        assertEquals(3, migrated.schemaVersion);
        assertEquals(2, migrated.windows.size());
        WindowState upper = migrated.windows.get(0), lower = migrated.windows.get(1);
        assertEquals(LayoutState.signature(List.of("전체", "기숙사", "파티", "귓말")), upper.signature);
        assertEquals(LayoutState.signature(List.of("시스템")), lower.signature);
        assertEquals(.2, upper.x);
        assertEquals(.1, upper.y);
        assertEquals(.6, upper.width);
        assertEquals(.4, upper.height);
        assertTrue(upper.locked);
        assertEquals(UiState.Mode.NORMAL, upper.mode);
        assertEquals(UiState.Mode.NORMAL, upper.restore);
        assertFalse(upper.systemDefault);
        assertEquals(.4, lower.x);
        assertEquals(.55, lower.y);
        assertEquals(.3, lower.width);
        assertEquals(.12, lower.height);
        assertFalse(lower.locked);
        assertEquals(UiState.Mode.CLOSED, lower.mode);
        assertEquals(UiState.Mode.NORMAL, lower.restore);
        assertTrue(lower.systemDefault);
        assertEquals(.50, migrated.backgroundOpacity);
        assertEquals(.85, migrated.borderOpacity);
    }

    @Test
    void legacyUninitializedSystemCoordinatesKeepSentinelAcrossSave() throws Exception {
        Path path = directory.resolve("legacy-uninitialized.json");
        Files.writeString(path, "{\"upperX\":0.2,\"upperY\":0.1,\"lowerLocked\":true}");
        LayoutStateStore store = new LayoutStateStore(path);
        LayoutState migrated = store.load();
        WindowState lower = migrated.windows.get(1);
        assertEquals(.2, lower.x);
        assertEquals(-1, lower.y);
        assertNull(lower.width);
        assertNull(lower.height);
        assertTrue(lower.locked);
        assertTrue(lower.systemDefault);
        store.save(migrated);
        assertEquals(-1, store.load().windows.get(1).y);
    }
}
