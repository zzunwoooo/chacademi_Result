package com.diamondserver.ui;

/**
 * 화면 코드가 쓰는 그리기 도구. 좌표는 가로 1920 × 세로 1080 기준(받은 layout.json 과 같은 단위)이고,
 * 실제 창 크기에 맞추는 일은 이 도구를 구현한 쪽이 한다.
 *  - 게임 안: client/McG
 *  - 검수용 미리보기: tools/ui-review/AwtG (같은 화면 코드를 그림 파일로 뽑는다)
 * 그림 이름은 textures/ui/ 아래 경로(확장자 없이). 예: "hud/mini_hud"
 */
public interface G {
    // 다시 그린 묶음(11/16)에서는 "채움" 그림이 파일 안에서 틀의 빈 칸과 다른 자리에 그려져 있다 (그림은 그대로 두고 그리는 자리만 옮긴다).
    // 잰 값 (파일 픽셀): 목표 게이지 틀의 빈 칸 y5~18 · 채움 y12~22 / 시간 막대 빈 칸 y2~6 · 채움 y5~8 / 스크롤 틀 빈 칸 x2~9 · 손잡이 x6~11 (12픽셀 그림을 8로 그림)
    double GOAL_FILL_DY = -5.5, TIME_FILL_DY = -2.5, THUMB_DX = -2;

    /** 지금 화면의 가로·세로 (기준 좌표). 16:9 면 1920×1080, 더 넓거나 높은 창이면 그만큼 크다 */
    double vw();
    double vh();

    /** 그림을 그 자리·크기로 (자르지 않고 크기만 맞춘다) */
    void img(String name, double x, double y, double w, double h, double alpha);

    /** 그림의 가로 일부만 보이게 (from~to 는 0~1, 진행 막대 채움용) */
    void imgClip(String name, double x, double y, double w, double h, double from, double to);

    /** 가운데(cx, cy)를 축으로 deg 도 돌려서 (룰렛 원판) */
    void imgRot(String name, double cx, double cy, double w, double h, double deg);

    void rect(double x, double y, double w, double h, int argb);

    double textW(String s, double px);

    /** 글자. anchor: 0 왼쪽, 1 가운데, 2 오른쪽. 세로는 y 가 글자의 가운데 */
    void text(String s, double x, double y, double px, int anchor, int argb);

    /** 게임 아이템 그림 (예: "minecraft:iron_pickaxe") */
    void item(String mcId, double x, double y, double size);

    /** 플레이어 얼굴 (계정 UUID 글자) */
    void face(String uuid, double x, double y, double size);

    int TEXT = 0xFFF3EDE2, DIM = 0xFFA9A398, DARK = 0xFF1E2A24, OFF = 0xFF7A766E, WARN = 0xFFE89A8E;

    /** 글자가 maxW 보다 길면 크기를 줄여서(최대 6) 그린다 */
    default void fit(String s, double x, double y, double px, int anchor, int argb, double maxW) {
        double p = px;
        while (p > px - 6 && textW(s, p) > maxW) p -= 1;
        text(s, x, y, p, anchor, argb);
    }

    /** 글자가 길면 크기를 조금(최대 3) 줄이고, 그래도 넘치면 끝을 "…" 로 줄여서 그린다 (전체 글은 다른 곳에서 볼 수 있을 때) */
    default void fitClip(String s, double x, double y, double px, int anchor, int argb, double maxW) {
        double p = px;
        while (p > px - 3 && textW(s, p) > maxW) p -= 1;
        if (textW(s, p) > maxW) {
            String t = s;
            while (t.length() > 1 && textW(t + "…", p) > maxW) t = t.substring(0, t.length() - 1);
            s = t.stripTrailing() + "…";
        }
        text(s, x, y, p, anchor, argb);
    }

    /** 게임 화면 위에 바로 쓰는 글자 (그림자 있음) */
    default void shadow(String s, double x, double y, double px, int anchor, int argb) {
        // 네 방향으로 어두운 테두리를 두르고 그 위에 글자 (한쪽으로만 그림자를 주면 겹쳐 보여서 읽기 어렵다)
        double o = Math.max(1, px / 18);
        int dark = 0xC0101010;
        text(s, x - o, y, px, anchor, dark);
        text(s, x + o, y, px, anchor, dark);
        text(s, x, y - o, px, anchor, dark);
        text(s, x, y + o, px, anchor, dark);
        text(s, x, y, px, anchor, argb);
    }

    static String n(long v) { return String.format("%,d", v); }
}
