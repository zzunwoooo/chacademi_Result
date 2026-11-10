package kr.kkaegul.client.ui;

/**
 * 화면 그리기 추상화. 마인크래프트(McCanvas)와 미리보기(AwtCanvas)가 같은 화면 코드를 그린다.
 * 좌표는 가상 해상도(세로 1080 기준) 단위. 이미지 id 는 "hud/gauge_frame" 처럼 textures/ui 아래 경로.
 */
public interface Canvas {
    int LEFT = 0, CENTER = 1, RIGHT = 2;
    /** LIGHT: 밝은 패널 위 글자 → 색코드를 진한 색으로 */
    int BOLD = 1, OUTLINE = 2, SHADOW = 4, LIGHT = 8;
    /** 잉크(실제 글자 모양) 가운데 맞춤을 끄고 예전 베이스라인 방식으로 (글자를 하나씩 그릴 때) */
    int NOINK = 16;

    /** 가상 화면 크기 */
    float width();
    float height();

    /** 이미지 전체를 x,y,w,h 에. argb 는 색조+투명도(0xFFFFFFFF = 그대로) */
    void image(String id, float x, float y, float w, float h, int argb);

    /** 이미지 일부(u0..u1, v0..v1 은 0~1 비율)를 x,y,w,h 에 */
    void imagePart(String id, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb);

    /** 원본 픽셀 크기. 없으면 null */
    int[] size(String id);

    /** 글자. y 는 글자 줄의 세로 중앙. 색코드(&,§) 지원. style = BOLD|OUTLINE|SHADOW */
    void text(String s, float x, float y, float size, int argb, int align, int style);

    float textWidth(String s, float size, int style);

    /** 둥근 사각형 채우기 (radius 0 이면 직사각형) */
    void fill(float x, float y, float w, float h, float radius, int argb);

    void push();
    void pop();
    void translate(float x, float y);
    void scale(float sx, float sy);
    /** 도 단위, 현재 원점 기준 */
    void rotate(float degrees);

    /** 접속자 얼굴(스킨 머리). 그렸으면 true. account 가 빈 문자열이면 나 */
    default boolean face(String account, float x, float y, float size) { return false; }

    /**
     * 둥근 모서리 가리개: (x,y,w,h) 사각형의 네 모서리에서 반지름 r 바깥쪽을 argb 색으로 덮는다.
     * 네모난 그림(얼굴 등)을 그린 뒤 이걸 그리면 둥글게 잘린 것처럼 보인다.
     */
    default void cornerMask(float x, float y, float w, float h, float r, int argb) {}

    /**
     * 휘어진 리본 위 글씨: 가운데 (cx,cy)를 지나는 반지름 r 원호(위로 볼록) 위에 한 글자씩 기울여 놓는다.
     * 리본의 가운데 선과 같은 곡선이면 글씨가 리본을 따라 휜다. maxW 보다 넓으면 줄여서 맞춤.
     */
    default void textArc(String s, float cx, float cy, float size, float r, float maxW, int argb, int style) {
        if (s == null || s.isEmpty()) return;
        float w = textWidth(s, size, style);
        if (maxW > 0 && w > maxW) { size *= maxW / w; w = maxW; }
        if (r <= 0 || r > 100000) { text(s, cx, cy, size, argb, CENTER, style); return; }
        TextRaster.Ink ink = TextRaster.ink(s, size, style);
        float baseY = ink != null ? -ink.cy() - size * TextRaster.CENTER_TO_BASELINE : 0;
        float pos = -w / 2;
        for (Fmt.Run run : Fmt.parse(s, argb & 0xFFFFFF, (style & LIGHT) != 0)) {
            String t = run.text();
            int col = (argb & 0xFF000000) | (run.rgb() & 0xFFFFFF);
            int i = 0;
            while (i < t.length()) {
                int cp = t.codePointAt(i), n = Character.charCount(cp);
                String ch = t.substring(i, i + n);
                boolean space = Character.isWhitespace(cp);
                float cw = space ? textWidth("가 가", size, style) - textWidth("가가", size, style) : textWidth(ch, size, style);
                if (space) { pos += cw; i += n; continue; }
                double th = (pos + cw / 2) / r;
                push();
                translate(cx + (float) (r * Math.sin(th)), cy + (float) (r * (1 - Math.cos(th))));
                rotate((float) Math.toDegrees(th));
                text(ch, -cw / 2, baseY, size, col, LEFT, style | NOINK);
                pop();
                pos += cw;
                i += n;
            }
        }
    }

    /** 이 사각형 밖은 그리지 않기 (스크롤 목록용). 꼭 unclip 으로 짝을 맞춘다 */
    default void clip(float x, float y, float w, float h) {}
    default void unclip() {}

    /** 마인크래프트 아이템 그림 (id 예: "WHEAT", "minecraft:wheat"). 정사각형 size. 그렸으면 true */
    default boolean item(String id, float x, float y, float size) { return false; }

    // ───────── 편의 ─────────

    /** 이미지를 가로 w 에 맞추고 비율 유지. 그린 높이를 돌려줌 */
    default float imageW(String id, float x, float y, float w, int argb) {
        int[] s = size(id);
        float h = s == null ? w : w * s[1] / (float) s[0];
        image(id, x, y, w, h, argb);
        return h;
    }

    /** 이미지를 세로 h 에 맞추고 비율 유지. 그린 너비를 돌려줌 */
    default float imageH(String id, float x, float y, float h, int argb) {
        int[] s = size(id);
        float w = s == null ? h : h * s[0] / (float) s[1];
        image(id, x, y, w, h, argb);
        return w;
    }

    /** 중심(cx,cy) 기준, 긴 변을 box 에 맞춰 그리기 */
    default void imageFit(String id, float cx, float cy, float box, int argb) {
        int[] s = size(id);
        float w = box, h = box;
        if (s != null) {
            float k = box / Math.max(s[0], s[1]);
            w = s[0] * k;
            h = s[1] * k;
        }
        image(id, cx - w / 2, cy - h / 2, w, h, argb);
    }

    /** 중심 기준 회전/확대 그리기 */
    default void imageAt(String id, float cx, float cy, float h, float scale, float rotate, int argb) {
        int[] s = size(id);
        float w = s == null ? h : h * s[0] / (float) s[1];
        push();
        translate(cx, cy);
        if (rotate != 0) rotate(rotate);
        if (scale != 1) scale(scale, scale);
        image(id, -w / 2, -h / 2, w, h, argb);
        pop();
    }

    /**
     * 9조각 늘이기: 그림의 가장자리(가로 bu, 세로 bv = 그림 크기 대비 0~0.5)는 늘이지 않고 화면에서 cw×ch 크기로,
     * 변은 한 방향으로만, 가운데는 양쪽으로 늘려서 그림. 테두리 장식이 있는 판을 아무 크기로 쓸 때.
     */
    default void imageNine(String id, float x, float y, float w, float h, float bu, float bv, float cw, float ch, int argb) {
        cw = Math.min(cw, w / 2); ch = Math.min(ch, h / 2);
        float[] xs = {x, x + cw, x + w - cw, x + w}, us = {0, bu, 1 - bu, 1};
        float[] ys = {y, y + ch, y + h - ch, y + h}, vs = {0, bv, 1 - bv, 1};
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
            float ww = xs[i + 1] - xs[i], hh = ys[j + 1] - ys[j];
            if (ww <= 0 || hh <= 0) continue;
            imagePart(id, xs[i], ys[j], ww, hh, us[i], vs[j], us[i + 1], vs[j + 1], argb);
        }
    }

    /** 폭을 넘으면 글자 크기를 줄여서 그리기 */
    default void textFit(String s, float x, float y, float size, float maxW, int argb, int align, int style) {
        float w = textWidth(s, size, style);
        if (w > maxW && w > 0) size = Math.max(size * 0.5f, size * maxW / w);
        text(s, x, y, size, argb, align, style);
    }
}
