package kr.chacademi.chatlayout;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowStateTest {
    @Test
    void locksAndSizesAreIndependentForEachWindow() {
        WindowState first = new WindowState(), second = new WindowState();
        first.toggleLock();
        first.resize(.4, .3);
        first.maximize();
        second.resize(.5, .2);
        second.maximize();
        assertNull(first.width);
        assertEquals(UiState.Mode.NORMAL, first.mode);
        assertEquals(.5, second.width);
        assertEquals(.2, second.height);
        assertEquals(UiState.Mode.MAXIMIZED, second.mode);
        first.toggleLock();
        first.resize(.4, .3);
        first.maximize();
        assertEquals(.4, first.width);
        assertEquals(UiState.Mode.MAXIMIZED, first.mode);
        assertNotEquals(first.id, second.id);
    }

    @Test
    void minimizeCloseAndOpenPreserveTheLastRestorableMode() {
        WindowState window = new WindowState();
        window.maximize();
        window.minimize();
        assertEquals(UiState.Mode.MINIMIZED, window.mode);
        assertEquals(UiState.Mode.MAXIMIZED, window.restore);
        window.close();
        window.open();
        assertEquals(UiState.Mode.MAXIMIZED, window.mode);
        window.minimize();
        window.minimize();
        assertEquals(UiState.Mode.MAXIMIZED, window.mode);
    }

    @Test
    void invalidGeometryAndRestoreStateAreSanitized() {
        WindowState window = new WindowState();
        window.id = null;
        window.signature = null;
        window.x = Double.POSITIVE_INFINITY;
        window.y = -2;
        window.width = Double.NaN;
        window.height = 2.0;
        window.mode = null;
        window.restore = UiState.Mode.CLOSED;
        window.sanitize();
        assertNotNull(window.id);
        assertFalse(window.id.isBlank());
        assertEquals("", window.signature);
        assertEquals(.0125, window.x);
        assertEquals(0, window.y);
        assertNull(window.width);
        assertEquals(1, window.height);
        assertEquals(UiState.Mode.NORMAL, window.mode);
        assertEquals(UiState.Mode.NORMAL, window.restore);
    }

    @Test
    void onlyUninitializedSystemWindowsKeepTheDockingSentinel() {
        WindowState system = new WindowState();
        system.systemDefault = true;
        system.y = -1;
        system.sanitize();
        assertEquals(-1, system.y);
        system.anchor(.2, .4);
        system.sanitize();
        assertEquals(.4, system.y);

        WindowState regular = new WindowState();
        regular.y = -1;
        regular.sanitize();
        assertEquals(0, regular.y);
    }
}
