package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 「운명의 타로」 창 (마법 힌트권, 오너 디자인 납품 2026-10-26, §41) 의 배치·시간표·줄바꿈. Minecraft 없이 계산만 (TarotLayoutTest,
 * docs/design/tarot-v1/preview 그림이 같은 값을 씀).
 *
 * 좌표: 메인 패널 PNG(main-panel-v2.png) 원본 픽셀 = 1585 × 992. 화면에는 가로 약 70% 로 가운데 (작은 화면은 축소).
 * 카드 PNG 4종(뒷면·빈 프레임 3종)은 1024 × 1536 안에서 보이는 부분 위치가 조금씩 달라서, 각자 보이는 상자(알파 64 초과)를
 * 같은 카드 칸 높이에 맞춰 그린다 → 뒤집을 때 뒷면과 앞면 크기가 같다. 그림(1024 × 1536, 불투명)은 프레임마다 정한 안쪽 칸
 * (투명한 안쪽 + 테두리 밑으로 조금)에 cover 로 잘라 넣고 그 위에 프레임을 그린다 (완성형 앞면은 쓰지 않음 — 이중 테두리 방지).
 */
final class TarotLayout {
    private TarotLayout() {}

    static final int PANEL_W = 1585, PANEL_H = 992;
    static final float CX = PANEL_W / 2f;
    /** 카드 PNG 크기 (뒷면·프레임·그림 모두). */
    static final int CARD_TEX_W = 1024, CARD_TEX_H = 1536;

    /** 카드 PNG 안의 보이는 상자 {x0, y0, x1, y1} (알파 64 초과, 납품 PNG 에서 잰 값). */
    static final int[] BACK_BOX = {107, 101, 918, 1398};
    static final int[][] FRAME_BOX = {{96, 91, 928, 1409}, {63, 40, 961, 1464}, {59, 17, 965, 1474}};
    /**
     * 프레임마다 그림을 넣을 칸 (프레임 PNG 픽셀). 투명한 안쪽(1성 149,144–876,1360 · 2성 137,117–888,1395 · 3성 127,119–897,1383)을
     * 테두리 밑으로 16/22/24 px 넓힌 것 — 바깥 투명 영역에는 닿지 않음(잰 여유 32/44/49 px 의 절반).
     */
    static final int[][] ART_RECT = {{133, 128, 892, 1376}, {115, 95, 910, 1417}, {103, 95, 921, 1407}};

    // ------------------------------------------------------------------ 패널 안 배치 (패널 픽셀)

    /** 제목 장식(title-ornament.png 2172 × 724, 선은 y≈360): 그릴 너비와 선의 y. */
    static final float ORNAMENT_W = 680, ORNAMENT_LINE_Y = 104;
    static final float TITLE_Y = 170, TITLE_SIZE = 50;
    static final float GUIDE_Y = 860, GUIDE_SIZE = 34;

    /** 고르기: 카드 칸 높이, 세 장의 가운데 x·y, 기울기(도), 마우스를 올리면 들리는 높이. */
    static final float SELECT_H = 500, SELECT_SPACING = 286, SELECT_Y = 532, SIDE_DROP = 18, SIDE_TILT = 8, LIFT = 24;
    /** 결과: 카드 칸 높이·가운데, 별 줄. */
    static final float RESULT_H = 380, RESULT_Y = 456;
    static final float STAR_Y = 230, STAR_SIZE = 48, STAR_GAP = 64;

    /** 힌트 바탕(hint-panel-v3.png 2172 × 724, 보이는 부분 26,234–2145,488): 보이는 너비, 위쪽 y, 위·아래 고정 띠(원본 px). */
    static final int HINT_SRC_X0 = 26, HINT_SRC_Y0 = 234, HINT_SRC_X1 = 2145, HINT_SRC_Y1 = 488, HINT_SLICE_TOP = 335, HINT_SLICE_BOTTOM = 387;
    static final float HINT_W = 1150, HINT_TOP = 666, HINT_TEXT_W = 1010, HINT_PAD = 18;
    /** 확인 버튼(confirm-*.png 2172 × 724, 보이는 부분 125,192–2047,532): 보이는 너비, 기본 가운데 y. */
    static final int BUTTON_SRC_X0 = 125, BUTTON_SRC_Y0 = 192, BUTTON_SRC_X1 = 2047, BUTTON_SRC_Y1 = 532;
    static final float BUTTON_W = 330, BUTTON_Y = 870, BUTTON_TEXT = 31;

    /** 힌트 글자: 먼저 30px 3줄까지, 넘으면 26px 4줄까지, 그래도 넘으면 4줄 끝을 "…" (전문은 닫을 때 채팅에 남음). */
    static final float HINT_SIZE = 30, HINT_SIZE_SMALL = 26, LINE_GAP = 1.32f;

    /** 화면(가로·세로, GUI 단위) → 패널 배율. 보통 가로 70%, 좁은 화면은 94%, 세로는 94% 안. */
    static float scale(int width, int height) {
        float fraction = width < 560 ? .94f : .70f;
        return Math.max(.05f, Math.min(width * fraction / PANEL_W, height * .94f / PANEL_H));
    }

    /** 카드 한 장 그리기 위치: PNG 를 (x, y) 에 PNG 픽셀 배율 s 로 그리면 보이는 상자가 가운데 (cx, cy), 높이 h 가 된다. */
    record Placement(float x, float y, float scale) {}
    static Placement place(int[] box, float cx, float cy, float h) {
        float s = h / (box[3] - box[1]);
        return new Placement(cx - (box[0] + box[2]) / 2f * s, cy - (box[1] + box[3]) / 2f * s, s);
    }

    /** cover: 그림(CARD_TEX) 에서 rect 비율로 가운데를 잘라낼 부분 {u, v, w, h} (그림 픽셀). */
    static float[] cover(int[] rect) {
        float rw = rect[2] - rect[0], rh = rect[3] - rect[1], want = rw / rh, have = (float) CARD_TEX_W / CARD_TEX_H;
        if (have > want) { float w = CARD_TEX_H * want; return new float[]{(CARD_TEX_W - w) / 2, 0, w, CARD_TEX_H}; }
        float h = CARD_TEX_W / want; return new float[]{0, (CARD_TEX_H - h) / 2, CARD_TEX_W, h};
    }

    /** 고르기 화면 카드 i(0~2) 의 가운데 x, y, 기울기(도). */
    static float selectX(int i) { return CX + (i - 1) * SELECT_SPACING; }
    static float selectY(int i) { return SELECT_Y + (i == 1 ? 0 : SIDE_DROP); }
    static float selectTilt(int i) { return (i - 1) * SIDE_TILT; }

    /** 마우스가 카드 i 위인가 (기울기 무시한 상자, 가운데 카드가 위에 그려지므로 가운데 먼저). -1 = 없음. */
    static int cardAt(float mx, float my) {
        float h = SELECT_H, w = h * (BACK_BOX[2] - BACK_BOX[0]) / (BACK_BOX[3] - BACK_BOX[1]);
        for (int i : new int[]{1, 0, 2}) if (Math.abs(mx - selectX(i)) <= w / 2 && Math.abs(my - selectY(i)) <= h / 2) return i;
        return -1;
    }

    // ------------------------------------------------------------------ 시간표 (ms, 고른 순간 = 0)

    static final long MOVE_MS = 860, FADE_MS = 320, FLIP_START = 300, FLIP_MS = 560, FLIP_HALF = FLIP_START + FLIP_MS / 2,
            RESULT_AT = FLIP_START + FLIP_MS, STAR_STEP = 140, STAR_MS = 240, TEXT_AT = RESULT_AT + 120, TEXT_MS = 300,
            BUTTON_AT = RESULT_AT + 360, BUTTON_MS = 260, CONFIRM_AT = BUTTON_AT;

    static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static float smooth(float t) { t = clamp01(t); return t * t * (3 - 2 * t); }
    static float progress(long t, long start, long length) { return clamp01((t - start) / (float) length); }
    /** 뒤집기 각도(0~180도). 90도를 넘으면 앞면. */
    static float flipDegrees(long t) { return 180 * smooth(progress(t, FLIP_START, FLIP_MS)); }
    /** 가로 배율 = |cos|: 음수(거울상)로 그리지 않는다. */
    static float flipWidth(float degrees) { return Math.abs((float) Math.cos(Math.toRadians(degrees))); }
    static boolean frontVisible(float degrees) { return degrees > 90; }
    /** 별 i(0~2) 의 나타남 0~1. */
    static float star(long t, int i) { return smooth(progress(t, RESULT_AT + i * STAR_STEP, STAR_MS)); }

    // ------------------------------------------------------------------ 힌트 줄바꿈

    interface Measure { float width(String text, float size); }
    record Wrapped(List<String> lines, float size) {
        float lineHeight() { return size * LINE_GAP; }
        /** 힌트 바탕 보이는 높이 (패널 px): 기본 높이보다 작아지지 않음. */
        float panelHeight() { return Math.max(naturalHintHeight(), lines.size() * lineHeight() + 2 * HINT_PAD); }
    }
    static float naturalHintHeight() { return HINT_W * (HINT_SRC_Y1 - HINT_SRC_Y0) / (HINT_SRC_X1 - HINT_SRC_X0); }

    static Wrapped fitHint(String text, Measure m) {
        var lines = wrap(text, HINT_TEXT_W, s -> m.width(s, HINT_SIZE));
        if (lines.size() <= 3) return new Wrapped(lines, HINT_SIZE);
        lines = wrap(text, HINT_TEXT_W, s -> m.width(s, HINT_SIZE_SMALL));
        if (lines.size() <= 4) return new Wrapped(lines, HINT_SIZE_SMALL);
        var cut = new ArrayList<>(lines.subList(0, 4));
        String last = cut.get(3);
        while (!last.isEmpty() && m.width(last + "…", HINT_SIZE_SMALL) > HINT_TEXT_W) last = last.substring(0, last.length() - 1);
        cut.set(3, last.stripTrailing() + "…");
        return new Wrapped(List.copyOf(cut), HINT_SIZE_SMALL);
    }

    interface Width { float of(String text); }
    /** 글자 폭 기준 줄바꿈: 띄어쓰기에서 끊고, 한 낱말이 한 줄보다 길면 글자 단위로 끊는다. */
    static List<String> wrap(String text, float max, Width width) {
        var out = new ArrayList<String>();
        String line = "";
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width.of(joined) <= max) { line = joined; continue; }
            if (!line.isEmpty()) { out.add(line); line = ""; }
            // 낱말 하나가 줄보다 김: 글자 단위
            while (width.of(word) > max) {
                int n = 1;
                while (n < word.length() && width.of(word.substring(0, n + 1)) <= max) n++;
                if (Character.isHighSurrogate(word.charAt(n - 1)) && n < word.length()) n++;
                out.add(word.substring(0, n)); word = word.substring(n);
            }
            line = word;
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    /** 힌트 바탕 아래 + 여백으로 버튼이 밀린 가운데 y (패널 안에 머무름). */
    static float buttonY(Wrapped w) { return Math.min(Math.max(BUTTON_Y, HINT_TOP + w.panelHeight() + 44), 884); }
    static float buttonH() { return BUTTON_W * (BUTTON_SRC_Y1 - BUTTON_SRC_Y0) / (BUTTON_SRC_X1 - BUTTON_SRC_X0); }
}
