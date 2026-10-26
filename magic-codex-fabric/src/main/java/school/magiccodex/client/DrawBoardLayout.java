package school.magiccodex.client;

/**
 * 「행운의 뽑기판」 창 (오너 디자인 납품 2026-10-26, §44) 의 배치·시간. Minecraft 없이 계산만 (DrawBoardLayoutTest,
 * docs/design/draw100-v1/preview.py 가 같은 값을 씀).
 *
 * <p>좌표 = 패널 PNG(panel.png) 원본 픽셀 1254 × 1254 (정사각형). 화면에는 한 배율 s 로만 줄이고 늘린다 (가로·세로 따로 늘리지 않음)
 * → 모든 칸이 언제나 정확히 같은 크기의 정사각형. 화면이 작으면 전체가 같은 비율로 작아진다.</p>
 *
 * <p>칸 PNG 3종(숨김·호버·공개)과 반짝임 PNG 는 1254 × 1254 캔버스 안에서 보이는 테두리 위치가 서로 다르다. 그래서 납품
 * SPRITE_LAYOUT.json 의 테두리 상자(frameRectPixels)를 <b>같은 정사각형 칸</b>에 맞춰 그린다: 테두리 상자 → 칸 (TILE × TILE),
 * 상자 밖(빛·반짝임)은 그 비율 그대로 칸 밖으로 넘친다. 누르는 자리는 그림 알파가 아니라 칸 정사각형으로 판정한다.</p>
 */
final class DrawBoardLayout {
    private DrawBoardLayout() {}

    static final int PANEL = 1254, TEX = 1254;
    static final float CX = PANEL / 2f;
    /** 칸 한 변 · 칸 사이 · 판 한 변 (10칸 + 9틈). 납품 예(64/6) 와 같은 비율에 가깝게. */
    static final float TILE = 80, GAP = 8, GRID = 10 * TILE + 9 * GAP;
    static final float GRID_X = CX - GRID / 2, GRID_Y = 266;
    /** 제목·안내 (패널 위 장식선 y≈122 아래). */
    static final float TITLE_Y = 172, TITLE_SIZE = 46, GUIDE_Y = 224, GUIDE_SIZE = 25;
    /** 닫기 X: 가운데·한 변 (패널 오른쪽 위, 제목 줄 높이). */
    static final float CLOSE_X = 1118, CLOSE_Y = 172, CLOSE_SIZE = 40;

    /** 납품 SPRITE_LAYOUT.json frameRectPixels {x, y, 너비, 높이} (PNG 알파로 확인: 숨김 154+947=1101 ≈ 보이는 끝 1099). */
    static final int[] HIDDEN_FRAME = {154, 158, 947, 897}, HOVER_FRAME = {140, 150, 975, 940},
            REVEALED_FRAME = {82, 88, 1091, 1052}, FX_FRAME = {206, 235, 838, 788};
    /** 공개 칸 안 아이템 크기 (칸 한 변 대비). 16px 아이템을 이 크기로. */
    static final float ITEM_FRACTION = .6f;

    /** 뒤집기 · 반짝임 · 호버 · 새 판 물결(칸마다 늦춤) 시간 (ms). */
    static final long FLIP_MS = 260, FX_MS = 420, HOVER_MS = 110, WAVE_STEP_MS = 14;

    /** 화면(GUI 단위) → 패널 배율 하나. 가로 92% · 세로 94% 안에 정사각형 패널 전체. */
    static float scale(int width, int height) { return Math.max(.02f, Math.min(width * .92f / PANEL, height * .94f / PANEL)); }

    static float cellX(int cell) { return GRID_X + (cell % 10) * (TILE + GAP); }
    static float cellY(int cell) { return GRID_Y + (cell / 10) * (TILE + GAP); }

    /** 패널 좌표 → 칸 번호 (칸 정사각형 안만, 틈은 -1). */
    static int cellAt(float x, float y) {
        float dx = x - GRID_X, dy = y - GRID_Y;
        if (dx < 0 || dy < 0 || dx >= GRID || dy >= GRID) return -1;
        int col = (int) (dx / (TILE + GAP)), row = (int) (dy / (TILE + GAP));
        if (col > 9 || row > 9 || dx - col * (TILE + GAP) >= TILE || dy - row * (TILE + GAP) >= TILE) return -1;
        return row * 10 + col;
    }

    static boolean closeHit(float x, float y) { return Math.abs(x - CLOSE_X) <= CLOSE_SIZE / 2 + 6 && Math.abs(y - CLOSE_Y) <= CLOSE_SIZE / 2 + 6; }

    /**
     * 테두리 상자 frame 을 (x, y, size, size) 칸에 맞추는 변환: 캔버스 원점 위치(tx, ty)와 축 배율(sx, sy).
     * 캔버스 전체(TEX × TEX)를 이 변환으로 그리면 frame 이 칸과 정확히 겹친다 (상태가 달라도 칸 자리·크기는 같음).
     */
    static float[] fit(int[] frame, float x, float y, float size) {
        float sx = size / frame[2], sy = size / frame[3];
        return new float[]{x - frame[0] * sx, y - frame[1] * sy, sx, sy};
    }

    /** 뒤집기 진행 0~1 → 가로 배율 |cos| (0.5 에서 0, 그때 면이 바뀜). */
    static float flipWidth(float t) { return (float) Math.abs(Math.cos(Math.PI * Math.max(0, Math.min(1, t)))); }
    static boolean secondHalf(float t) { return t >= .5f; }
}
