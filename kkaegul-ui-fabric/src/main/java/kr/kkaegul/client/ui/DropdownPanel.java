package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * HUD ▼ 드롭다운 (받은 묶음 4 좌표, HUD 좌표계에서 그림).
 * 판 230×442 (그림 230×384 의 줄 하나를 한 번 더 이어 붙여 7줄), 꼬리 끝 (193,2) 을 HUD ▼ 아래에 맞춤. 줄 58 (첫 줄 y 24), 아이콘 (12,30+58i) 46, 글자 x 70 / 가운데 y 53+58i.
 * 이동 하위 목록 200×232 은 판 기준 (245,190), 줄 54 (아이콘 (10,12+54j), 글자 x 66 / 가운데 y 35+54j).
 * 펼침: 꼬리 끝을 축으로 세로로 내려오며 커지고, 줄은 위에서부터 차례로 나타남. 닫힘은 거꾸로 짧게.
 */
public final class DropdownPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final String[] ROW_ID = {"profile", "wardrobe", "mailbox", "withdraw", "move", "ranking", "trash", "discord_link"};
    private static final String[] ROW_NAME = {"내 정보", "옷장", "우편함", "인출", "이동", "랭킹", "쓰레기통", "디코 연동"};
    private static final String[] SUB_ID = {"lobby", "village", "gacha_shop", "wild", "hall_of_fame"};
    private static final String[] SUB_NAME = {"로비", "마을", "가챠샵", "야생", "명예의 전당"};
    private static final float PW = 230, PH = 500, TIPX = 193, TIPY = 2, SUBX = 245, SUBY = 248, SW = 200, SH = 232 + 54;   // 그림은 4줄(232), 셋째 줄 칸을 한 번 더 이어 5줄
    private static final int ROWS = ROW_ID.length;
    /** 이동 줄 (하위 목록이 이 줄 옆에 펼쳐짐: SUBY = 24 + 58 × MOVE - 8) */
    private static final int MOVE = 4;
    private static final long OPEN = 260, CLOSE = 150;

    private final Host host;
    private final HudView hud;
    private final long opened;
    private long closing = -1, last;
    private final float[] hov = new float[ROWS], subHov = new float[SUB_ID.length];
    private float sub, subPin;      // 하위 목록 펼침 정도
    private float px, py, hs = 1;   // 판 왼쪽 위 (HUD 좌표), HUD 배율

    public DropdownPanel(Host host, HudView hud, long now) {
        this.host = host;
        this.hud = hud;
        this.opened = now;
        this.last = now;
        hud.menuOpen = true;
    }

    @Override public String screen() { return "dropdown"; }
    @Override public void update(JsonObject data, long now) { }
    @Override public void closed() { hud.menuOpen = false; }

    /** 펼친 정도 0..1 */
    private float openT(long now) {
        if (closing >= 0) return 1 - Anim.t(now, closing, CLOSE);
        return Anim.t(now, opened, OPEN);
    }

    public void close(long now) {
        if (closing >= 0) return;
        closing = now;
        hud.menuOpen = false;
        host.sound("minecraft:ui.button.click", 0.9f, .25f);
    }

    private static float approach(float cur, float target, float dt, float tau) {
        return cur + (target - cur) * (1 - (float) Math.exp(-dt / tau));
    }

    @Override public void render(Canvas c, long now, float mx, float my) {
        float dt = Math.min(100, now - last);
        last = now;
        if (closing >= 0 && now - closing >= CLOSE) { host.closePanel(this); return; }
        hs = hud.scale;
        px = hud.menuX - TIPX;
        py = hud.menuY - TIPY;
        float lx = mx / hs - px, ly = my / hs - py;   // 판 기준 마우스
        float t = openT(now);
        float e = closing >= 0 ? Anim.inOut(t) : Anim.outCubic(t);
        float a = Anim.clamp(closing >= 0 ? t * 1.4f : t * 2.2f);

        // 하위 목록: 이동 줄이나 하위 목록 위에 있으면 펼침
        boolean onMove = closing < 0 && Anim.in(lx, ly, 8, 24 + 58 * MOVE, 214, 58);
        boolean onSub = closing < 0 && sub > .3f && Anim.in(lx, ly, SUBX - 8, SUBY, SW + 8, SH);
        float subTarget = closing >= 0 ? 0 : (onMove || onSub || subPin > 0 ? 1 : 0);
        sub = approach(sub, subTarget, dt, subTarget > sub ? 55 : 70);
        if (!onMove && !onSub && Anim.in(lx, ly, 0, 0, PW, PH)) subPin = 0;   // 다른 줄로 가면 고정 풀기

        c.push();
        c.scale(hs, hs);
        c.translate(px, py);

        // 판: 꼬리 끝을 축으로 세로로 펼침 (살짝 덜 커진 상태에서 시작)
        float sx = .92f + .08f * e, sy = .35f + .65f * e;
        c.push();
        c.translate(TIPX, TIPY);
        c.scale(sx, sy);
        c.translate(-TIPX, -TIPY);
        // 그림(384 높이, 6줄)의 셋째 줄 칸(141~199)을 모자란 줄 수만큼 더 넣어 늘림
        float g = 384f;
        int extra = ROWS - 6;
        c.imagePart("dropdown/dropdown_panel", 0, 0, PW, 199, 0, 0, 1, 199 / g, Anim.white(a));
        for (int r = 0; r < extra; r++)
            c.imagePart("dropdown/dropdown_panel", 0, 199 + 58 * r, PW, 58, 0, 141 / g, 1, 199 / g, Anim.white(a));
        c.imagePart("dropdown/dropdown_panel", 0, 199 + 58 * extra, PW, PH - 199 - 58 * extra, 0, 199 / g, 1, 1, Anim.white(a));
        c.pop();

        // 줄: 위에서부터 차례로 (줄마다 35ms 늦게), 살짝 위에서 내려오며 나타남
        for (int i = 0; i < ROWS; i++) {
            float rt = closing >= 0 ? t : Anim.t(now, opened + 60 + 35L * i, 200);
            float re = Anim.outCubic(rt), ra = Anim.clamp(rt * 1.6f) * a;
            if (ra <= 0.01f) continue;
            boolean over = closing < 0 && Anim.in(lx, ly, 8, 24 + 58 * i, 214, 58);
            hov[i] = approach(hov[i], over || (i == MOVE && sub > .5f) ? 1 : 0, dt, 45);
            float ry = 24 + 58 * i;
            float yy = TIPY + (ry - TIPY) * sy - (1 - re) * 8;      // 판이 펼쳐지는 만큼 따라 내려옴
            if (hov[i] > .01f) c.image("dropdown/row_hover", 8, yy + 3, 214, 52, Anim.white(hov[i] * ra));
            float ic = 46 * (1 + .1f * hov[i]);
            // 옷장 줄은 아이콘 없음 (오너 지시: dropdown/icon_wardrobe 그림이 있어도 지금은 안 씀 — 쓰려면 이 조건만 지우면 됨)
            String icon = "dropdown/icon_" + ROW_ID[i];
            if (!ROW_ID[i].equals("wardrobe"))
                c.image(icon, 35 - ic / 2, yy + 29 - ic / 2 - 2 * hov[i], ic, ic, Anim.white(ra));
            c.text(ROW_NAME[i], 70 + 3 * hov[i], yy + 29, 22, Anim.alpha(Theme.INK, ra), Canvas.LEFT, TXT);
            if (i == MOVE) c.image("dropdown/arrow_right", 190 + 4 * hov[i], yy + 17, 24, 24, Anim.white(ra));
        }

        // 이동 하위 목록: 오른쪽으로 미끄러지며 나타남
        if (sub > .02f) {
            float se = Anim.outCubic(sub), sa = Anim.clamp(sub * 1.5f) * a;
            float ox = SUBX - 16 * (1 - se);
            c.push();
            c.translate(ox, SUBY);
            // 그림(232, 4줄)의 셋째 줄 칸(116~170, 아래 줄선 포함)을 모자란 줄 수만큼 더 넣어 늘림
            float sg = 232f;
            int sx2 = SUB_ID.length - 4;
            c.imagePart("dropdown/submenu_panel", 0, 0, SW, 170, 0, 0, 1, 170 / sg, Anim.white(sa));
            for (int r = 0; r < sx2; r++)
                c.imagePart("dropdown/submenu_panel", 0, 170 + 54 * r, SW, 54, 0, 116 / sg, 1, 170 / sg, Anim.white(sa));
            c.imagePart("dropdown/submenu_panel", 0, 170 + 54 * sx2, SW, SH - 170 - 54 * sx2, 0, 170 / sg, 1, 1, Anim.white(sa));
            for (int j = 0; j < SUB_ID.length; j++) {
                float jt = Anim.clamp(sub * 1.6f - j * .15f);
                float ja = Anim.outCubic(jt) * sa;
                boolean over = closing < 0 && Anim.in(lx - ox, ly - SUBY, 8, 8 + 54 * j, 184, 54);
                subHov[j] = approach(subHov[j], over ? 1 : 0, dt, 45);
                float yy = 8 + 54 * j + (1 - Anim.outCubic(jt)) * -6;
                if (subHov[j] > .01f) c.image("dropdown/row_hover", 8, yy + 2, 184, 50, Anim.white(subHov[j] * ja));
                float ic = 46 * (1 + .1f * subHov[j]);
                c.image("dropdown/icon_" + SUB_ID[j], 33 - ic / 2, yy + 27 - ic / 2 - 2 * subHov[j], ic, ic, Anim.white(ja));
                c.text(SUB_NAME[j], 66 + 3 * subHov[j], yy + 27, 21, Anim.alpha(Theme.INK, ja), Canvas.LEFT, TXT);
            }
            c.pop();
        }
        c.pop();
    }

    @Override public boolean click(float mx, float my, long now) {
        if (closing >= 0) return true;
        float lx = mx / hs - px, ly = my / hs - py;
        // 하위 목록
        if (sub > .5f) {
            for (int j = 0; j < SUB_ID.length; j++) {
                if (Anim.in(lx, ly, SUBX + 8, SUBY + 8 + 54 * j, 184, 54)) {
                    host.send("move", "dest", SUB_ID[j]);
                    host.sound("minecraft:ui.button.click", 1.2f, .4f);
                    close(now);
                    return true;
                }
            }
        }
        for (int i = 0; i < ROWS; i++) {
            if (!Anim.in(lx, ly, 8, 24 + 58 * i, 214, 58)) continue;
            host.sound("minecraft:ui.button.click", 1.2f, .4f);
            switch (ROW_ID[i]) {
                case "profile" -> host.send("menu.open", "tab", "me");
                case "wardrobe" -> host.send("wardrobe.open");
                case "mailbox" -> host.send("mail.open");
                case "withdraw" -> host.send("withdraw.open");
                case "move" -> { subPin = subPin > 0 ? 0 : 1; return true; }
                case "ranking" -> host.send("menu.open", "tab", "hall");
                case "trash" -> { host.send("trash.open"); close(now); return true; }
                case "discord_link" -> { host.send("discord.link"); close(now); return true; }
                default -> { }
            }
            return true;    // 서버가 새 창을 열면 이 창은 그 창으로 바뀜
        }
        // 바깥 (▼ 포함) 누르면 접기
        close(now);
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (key == 77) { close(now); return true; }    // M
        return false;
    }
}
