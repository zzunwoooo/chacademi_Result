package kr.kkaegul.client.ui;

/** 공통 색 / 툴팁 */
public final class Theme {
    /** gacha/jackpot_ribbon: 글씨 판 가운데 선 = 높이의 0.344 (x 가운데), 양쪽으로 휘는 반지름 = 그림 너비 1024 당 약 2650 (재서 맞춤) */
    public static final float RIBBON_CY = .344f;
    public static float ribbonR(float drawnWidth) { return 2650f * drawnWidth / 1024f; }

    private Theme() {}

    public static final int INK = 0xFF4A3426;      // 크림 패널 위 기본 글자 (진한 갈색)
    public static final int MUTED = 0xFF8C7764;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int CREAM = 0xFFFBF3E2;
    public static final int GREEN = 0xFF5BA346;
    public static final int GREEN_DARK = 0xFF3F7F31;
    public static final int RED = 0xFFE0564C;
    public static final int BLUE = 0xFF3C8FD6;
    public static final int GOLD = 0xFFD9951A;
    public static final int PURPLE = 0xFF8E5BD0;
    public static final int DIM = 0x99000000;

    /** 마우스 위치 옆에 툴팁 상자 (색코드, \n 줄바꿈) */
    public static void tooltip(Canvas c, String text, float mx, float my) {
        if (text == null || text.isBlank()) return;
        float size = 22, lh = 30, padX = 18, padY = 14;
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String l : text.split("\n")) lines.addAll(TextRaster.wrap(l, 460, size, Canvas.LIGHT));
        float w = 0;
        for (String l : lines) w = Math.max(w, c.textWidth(l, size, Canvas.LIGHT));
        w += padX * 2;
        float h = lines.size() * lh + padY * 2 - (lh - size) * .2f;
        float x = mx + 22, y = my + 18;
        if (x + w > c.width() - 8) x = mx - 16 - w;
        if (y + h > c.height() - 8) y = c.height() - 8 - h;
        c.push();
        c.translate(0, 0);
        c.fill(x + 2, y + 4, w, h, 14, 0x40000000);
        c.fill(x, y, w, h, 14, 0xFF8DBF63);
        c.fill(x + 3, y + 3, w - 6, h - 6, 11, 0xFFFFF8EA);
        float ty = y + padY + size * .55f;
        boolean first = true;
        for (String l : lines) {
            c.text(l, x + padX, ty, size, INK, Canvas.LEFT, Canvas.LIGHT | (first ? Canvas.BOLD : 0));
            ty += lh;
            first = false;
        }
        c.pop();
    }

    /** 크림색 버튼 대체(그림 없는 곳용) */
    public static void pill(Canvas c, float x, float y, float w, float h, int argb) { c.fill(x, y, w, h, h / 2, argb); }
}
