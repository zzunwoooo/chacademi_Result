package kr.chacademi.chatlayout;

import org.junit.jupiter.api.Test;
import kr.chacademi.chatlayout.LayoutMath.Panel;
import kr.chacademi.chatlayout.LayoutMath.Rect;
import static org.junit.jupiter.api.Assertions.*;

class InputLayoutTest {
    @Test void systemBodyKeepsBothLinesAndTabsFollowInput() {
        WindowState state = new WindowState();
        state.systemDefault = true;
        Panel original = LayoutMath.window(480, 270, state, 18, 130);
        Panel attached = InputLayout.attach(original);
        assertSame(original.body(), attached.body());
        assertSame(original.header(), attached.header());
        assertEquals(18, attached.body().height());
        assertEquals(original.tabs(), attached.tabs());
        assertEquals(original.frame().height() + 20, attached.frame().height());
        Rect input = InputLayout.field(attached);
        assertEquals(attached.body().x() + 10, input.x());
        assertEquals(attached.body().bottom() + 5, input.top());
        assertEquals(attached.body().width() - 20, input.width());
        assertEquals(12, input.height());
        assertEquals(attached.body().bottom() + 21,
                attached.body().bottom() + 1 + InputLayout.tabOffset(attached));
        assertTrue(input.bottom() < attached.body().bottom() + 21);
    }

    @Test void ordinaryAndMaximizedWindowsStayWithinInputReserve() {
        for (int[] viewport : new int[][]{{320, 180}, {480, 270}, {960, 540}}) {
            for (UiState.Mode mode : new UiState.Mode[]{UiState.Mode.NORMAL, UiState.Mode.MAXIMIZED}) {
                WindowState state = new WindowState();
                state.x = 1;
                state.y = 1;
                state.mode = mode;
                Panel original = LayoutMath.window(viewport[0], viewport[1], state, 18, 240);
                Panel attached = InputLayout.attach(original);
                assertTrue(original.frame().bottom() <= viewport[1] - 22);
                assertTrue(attached.frame().bottom() <= viewport[1] - 2);
                assertSame(original.body(), attached.body());
                assertEquals(20, InputLayout.tabOffset(attached));
                assertNotNull(InputLayout.field(attached));
            }
        }
    }

    @Test void minimizedAndClosedPanelsHaveNoInputSpace() {
        for (UiState.Mode mode : new UiState.Mode[]{UiState.Mode.MINIMIZED, UiState.Mode.CLOSED}) {
            WindowState state = new WindowState();
            state.mode = mode;
            Panel original = LayoutMath.window(480, 270, state, 18, 240);
            assertSame(original, InputLayout.attach(original));
            assertNull(InputLayout.field(original));
            assertEquals(0, InputLayout.tabOffset(original));
        }
    }

    @Test void fieldWidthNeverBecomesEmpty() {
        Panel tiny = new Panel(new Rect(5, 6, 4, 60), new Rect(5, 6, 4, 12),
                new Rect(5, 18, 4, 18), 15, UiState.Mode.NORMAL);
        assertEquals(1, InputLayout.field(InputLayout.attach(tiny)).width());
    }

    @Test void popupsClampBothEdgesWithoutChangingAnInBoundsPopup() {
        Rect interior = new Rect(20, 30, 80, 40);
        assertEquals(interior, InputLayout.popup(interior, 320, 180));
        assertEquals(new Rect(2, 2, 80, 40),
                InputLayout.popup(new Rect(-10, -10, 80, 40), 320, 180));
        assertEquals(new Rect(238, 138, 80, 40),
                InputLayout.popup(new Rect(400, 250, 80, 40), 320, 180));
    }

    @Test void oversizedAndInvalidPopupSizesFitViewportMargins() {
        assertEquals(new Rect(2, 2, 316, 176),
                InputLayout.popup(new Rect(99, 99, 999, 999), 320, 180));
        assertEquals(new Rect(2, 2, 1, 1),
                InputLayout.popup(new Rect(-10, -10, 0, -5), 320, 180));
        assertEquals(new Rect(2, 2, 2, 2),
                InputLayout.popup(new Rect(100, 100, 100, 100), 6, 6));
    }

    @Test void nativeSuggestionRowsFitEachViewportAndNeverBecomeEmpty() {
        assertEquals(14, InputLayout.suggestionRows(15, 180));
        assertEquals(15, InputLayout.suggestionRows(15, 270));
        assertEquals(15, InputLayout.suggestionRows(15, 540));
        assertEquals(7, InputLayout.suggestionRows(15, 90));
        assertEquals(10, InputLayout.suggestionRows(10, 180));
        assertEquals(1, InputLayout.suggestionRows(0, 180));
        assertEquals(1, InputLayout.suggestionRows(-5, 180));
        assertEquals(1, InputLayout.suggestionRows(15, 6));
        assertEquals(1, InputLayout.suggestionRows(15, Integer.MIN_VALUE));
    }

    @Test void invalidViewportAndNullArgumentsFailClearly() {
        assertThrows(IllegalArgumentException.class,
                () -> InputLayout.popup(new Rect(0, 0, 1, 1), 5, 180));
        assertThrows(IllegalArgumentException.class,
                () -> InputLayout.popup(new Rect(0, 0, 1, 1), 320, 5));
        assertThrows(NullPointerException.class, () -> InputLayout.attach(null));
        assertThrows(NullPointerException.class, () -> InputLayout.field(null));
        assertThrows(NullPointerException.class, () -> InputLayout.tabOffset(null));
        assertThrows(NullPointerException.class, () -> InputLayout.popup(null, 320, 180));
    }
    @Test void detachedPaneInputDrawsAboveEveryNativePaneBackground(){
        for(int count:new int[]{1,2,5,50,1000}){
            float nativeFront=(count-1)*0.1f;
            assertTrue(InputLayout.overlayDepth(count)>nativeFront);
        }
        assertTrue(InputLayout.overlayDepth(2)>InputLayout.overlayDepth(1));
    }
    @Test void glyphsAndCaretHaveVerticalRoomInsideTheInputBorder(){
        WindowState state=new WindowState();
        for(int[] viewport:new int[][]{{320,180},{480,270},{960,540}}){
            Panel panel=InputLayout.attach(LayoutMath.window(viewport[0],viewport[1],state,18,130));
            Rect field=InputLayout.field(panel),box=InputLayout.background(field);
            // Borderless EditBox renders text at Y; tall glyphs can reach slightly above that origin.
            assertTrue(field.top()-3>box.top());
            assertTrue(field.top()+10<box.bottom());
            assertTrue(box.top()>panel.body().bottom());
            assertTrue(box.bottom()<panel.body().bottom()+InputLayout.tabOffset(panel));
            assertTrue(box.x()>=panel.frame().x());
            assertTrue(box.right()<=panel.frame().right());
        }
    }
}
