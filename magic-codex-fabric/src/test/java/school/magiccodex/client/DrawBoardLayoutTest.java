package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.DrawBoardProtocol;
import static school.magiccodex.client.DrawBoardLayout.*;

class DrawBoardLayoutTest {
    private static final float EPS = 1e-3f;

    @Test void everyCellIsTheSameExactSquareAndCellsDoNotOverlap() {
        for (int i = 0; i < 100; i++) {
            float x = cellX(i), y = cellY(i);
            // 가운데·네 모서리 안쪽은 이 칸, 한 변 = TILE (가로 = 세로)
            assertEquals(i, cellAt(x + TILE / 2, y + TILE / 2));
            assertEquals(i, cellAt(x + .01f, y + .01f));
            assertEquals(i, cellAt(x + TILE - .01f, y + TILE - .01f));
            assertEquals(-1, cellAt(x + TILE + GAP / 2, y + TILE / 2), "칸 사이 틈은 누르지 않음");
            if (i % 10 < 9) assertEquals(TILE + GAP, cellX(i + 1) - x, EPS);
            if (i < 90) assertEquals(TILE + GAP, cellY(i + 10) - y, EPS);
        }
        assertEquals(-1, cellAt(GRID_X - 1, GRID_Y + 5));
        assertEquals(-1, cellAt(GRID_X + TILE + GAP / 2, GRID_Y + 5), "틈은 칸이 아님");
        assertEquals(GRID, cellX(9) + TILE - GRID_X, EPS);
        assertEquals(GRID, cellY(90) + TILE - GRID_Y, EPS);
    }

    @Test void gridAndTitleFitInsidePanelFrame() {
        // 패널 PNG 안쪽 금테: 대략 x 70~1180, y 74~1176. 판·제목·닫기가 그 안.
        assertTrue(GRID_X > 90 && GRID_X + GRID < 1165);
        assertTrue(GRID_Y > GUIDE_Y + 20 && GRID_Y + GRID < 1160);
        assertEquals(CX, GRID_X + GRID / 2, EPS);
        assertTrue(CLOSE_X + CLOSE_SIZE / 2 < 1165 && CLOSE_Y - CLOSE_SIZE / 2 > 110);
        assertTrue(closeHit(CLOSE_X, CLOSE_Y));
        assertFalse(closeHit(CX, GRID_Y));
    }

    @Test void oneUniformScaleForEveryScreen() {
        int[][] screens = {{854, 480}, {1920, 1080}, {427, 240}, {320, 640}, {3840, 2160}, {640, 480}};
        for (var sc : screens) {
            float s = scale(sc[0], sc[1]);
            assertTrue(PANEL * s <= sc[0] + EPS && PANEL * s <= sc[1] + EPS, "패널이 화면 안 " + Arrays.toString(sc));
            // 화면이 커도 가로·세로 중 좁은 쪽에 맞춘 배율 하나 (칸은 화면에서도 TILE*s 정사각형)
            assertTrue(s > 0 && (Math.abs(PANEL * s - sc[0] * .92f) < 1 || Math.abs(PANEL * s - sc[1] * .94f) < 1));
        }
    }

    @Test void allStatesMapTheirFrameToTheSameSquare() {
        int[][] frames = {HIDDEN_FRAME, HOVER_FRAME, REVEALED_FRAME, FX_FRAME};
        for (int cell : new int[]{0, 9, 45, 99}) {
            float x = cellX(cell), y = cellY(cell);
            for (var fr : frames) {
                float[] f = fit(fr, x, y, TILE);
                // frame (x, y, w, h) 의 네 모서리가 칸 네 모서리로
                assertEquals(x, f[0] + fr[0] * f[2], EPS);
                assertEquals(y, f[1] + fr[1] * f[3], EPS);
                assertEquals(x + TILE, f[0] + (fr[0] + fr[2]) * f[2], EPS);
                assertEquals(y + TILE, f[1] + (fr[1] + fr[3]) * f[3], EPS);
                // 납품 테두리 상자는 거의 정사각형 → 축 배율 차이 10% 안
                assertTrue(Math.abs(f[2] / f[3] - 1) < .1f, Arrays.toString(fr));
                assertTrue(fr[0] + fr[2] <= TEX && fr[1] + fr[3] <= TEX);
            }
        }
    }

    @Test void flipCurve() {
        assertEquals(1, flipWidth(0), EPS);
        assertEquals(0, flipWidth(.5f), EPS);
        assertEquals(1, flipWidth(1), EPS);
        assertFalse(secondHalf(.49f));
        assertTrue(secondHalf(.5f));
    }

    @Test void protocolRoundTrips() {
        var cells = List.of(new DrawBoardProtocol.Cell(0, 5), new DrawBoardProtocol.Cell(99, 0));
        var s = new DrawBoardProtocol.State(1, 7, 3, 12, 4, 9, 100, true, false, cells, "새 판");
        var back = DrawBoardProtocol.state(DrawBoardProtocol.encode(s));
        assertEquals(s.revealed(), back.revealed());
        assertEquals(s.round(), back.round());
        assertEquals(DrawBoardProtocol.KIND_STATE, DrawBoardProtocol.kind(DrawBoardProtocol.encode(s)));
        // 100칸 모두 공개된 상태도 한 패킷
        var all = new ArrayList<DrawBoardProtocol.Cell>();
        for (int i = 0; i < 100; i++) all.add(new DrawBoardProtocol.Cell(i, 99 - i));
        assertEquals(100, DrawBoardProtocol.state(DrawBoardProtocol.encode(new DrawBoardProtocol.State(0, 7, 0, 13, 4, 9, 100, true, true, all, ""))).revealed().size());
        // 같은 칸 두 번 → 거절
        assertThrows(IllegalArgumentException.class, () -> DrawBoardProtocol.state(DrawBoardProtocol.encode(
                new DrawBoardProtocol.State(0, 7, 0, 1, 1, 1, 100, true, false, List.of(new DrawBoardProtocol.Cell(3, 1), new DrawBoardProtocol.Cell(3, 2)), ""))));

        var a = new DrawBoardProtocol.Action(DrawBoardProtocol.FLIP, 7, 1, 4, 37, true, -1, "", "");
        assertEquals(a, DrawBoardProtocol.action(DrawBoardProtocol.encode(a)));
        assertThrows(IllegalArgumentException.class, () -> DrawBoardProtocol.action(DrawBoardProtocol.encode(new DrawBoardProtocol.Action(DrawBoardProtocol.FLIP, 7, 1, 4, -1, true, -1, "", ""))));
        assertThrows(IllegalArgumentException.class, () -> DrawBoardProtocol.action(DrawBoardProtocol.encode(new DrawBoardProtocol.Action(DrawBoardProtocol.REMOVE, 7, 1, 0, -1, false, -1, "", "x y"))));

        // 목록 100개 × 큰 미리보기도 패킷 상한 안의 조각들로
        var entries = new ArrayList<DrawBoardProtocol.Entry>();
        for (int i = 0; i < 100; i++) entries.add(new DrawBoardProtocol.Entry(UUID.randomUUID().toString(), new byte[DrawBoardProtocol.MAX_ITEM]));
        var parts = DrawBoardProtocol.split(7, 9, entries, DrawBoardProtocol.CATALOG_BUDGET);
        int seen = 0;
        for (var p : parts) {
            byte[] b = DrawBoardProtocol.encode(p);
            assertTrue(b.length <= DrawBoardProtocol.MAX_BYTES);
            assertEquals(DrawBoardProtocol.KIND_CATALOG, DrawBoardProtocol.kind(b));
            var c = DrawBoardProtocol.catalog(b);
            assertEquals(seen, c.start());
            seen += c.entries().size();
        }
        assertEquals(100, seen);
        assertEquals(1, DrawBoardProtocol.split(7, 9, List.of(), 1000).size(), "빈 목록도 한 조각(전체 0개)");
    }
}
