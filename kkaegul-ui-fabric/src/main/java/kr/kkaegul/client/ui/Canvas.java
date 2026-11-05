package kr.kkaegul.client.ui;

/**
 * 화면 그리기 추상화. 마인크래프트(McCanvas)와 미리보기(AwtCanvas)가 같은 화면 코드를 그린다.
 * 좌표는 가상 해상도(세로 1080 기준) 단위. 이미지 id 는 "hud/gauge_frame" 처럼 textures/ui 아래 경로.
 */
public interface Canvas {
    int LEFT = 0, CENTER = 1, RIGHT = 2;
    /** LIGHT: 밝은 패널 위 글자 → 색코드를 진한 색으로 */
    int BOLD = 1, OUTLINE = 2, SHADOW = 4, LIGHT = 8;

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

    /** 폭을 넘으면 글자 크기를 줄여서 그리기 */
    default void textFit(String s, float x, float y, float size, float maxW, int argb, int align, int style) {
        float w = textWidth(s, size, style);
        if (w > maxW && w > 0) size = Math.max(size * 0.5f, size * maxW / w);
        text(s, x, y, size, argb, align, style);
    }
}
