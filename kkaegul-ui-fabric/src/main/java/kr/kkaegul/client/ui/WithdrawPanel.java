package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 인출 팝업 (받은 묶음 4): 판 440×230 을 화면 가운데에. 제목 판 안쪽 가운데 (220, 65.5) — 그림에서 잼.
 * 다이아 아이콘 (48,103) 34, 보유 글자 x 95 / 가운데 y 120, 버튼 88×55 (20+102i, 155), 닫기 (401,59) 24.
 */
public final class WithdrawPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final String[] AMT = {"1", "10", "64", "all"};
    private static final String[] LABEL = {"1", "10", "64", "전부"};
    private static final float PW = 440, PH = 230;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private long closing = -1, last, pressAt = -1;
    private int pressed = -1;
    private final float[] hov = new float[4];
    private float k = 1, ox, oy, shownHeld = -1;

    public WithdrawPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
        this.last = now;
    }

    @Override public String screen() { return "withdraw"; }
    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    private void close(long now) { if (closing < 0) closing = now; }

    @Override public void render(Canvas c, long now, float mx, float my) {
        float dt = Math.min(100, now - last);
        last = now;
        if (closing >= 0 && now - closing >= 140) { host.closePanel(this); return; }
        float t = closing >= 0 ? 1 - Anim.t(now, closing, 140) : Anim.t(now, opened, 240);
        float e = closing >= 0 ? t : Anim.outBack(t), a = Anim.clamp(t * 1.8f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x55000000, a));
        k = Math.min(W / 1920f, H / 1080f);
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2;
        float lx = (mx - ox) / k - 740, ly = (my - oy) / k - 425;
        long held = J.num(data, "held");
        if (shownHeld < 0) shownHeld = held;
        shownHeld += (held - shownHeld) * (1 - (float) Math.exp(-dt / 70f));
        if (Math.abs(held - shownHeld) < .5f) shownHeld = held;

        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(740 + PW / 2, 425 + PH / 2);
        float s = .88f + .12f * e;
        c.scale(s, s);
        c.translate(-PW / 2, -PH / 2);
        c.image("dropdown/withdraw_panel", 0, 0, PW, PH, Anim.white(a));
        c.textFit("인출", 220, 65.5f, 26, 160, Anim.alpha(0xFF4A2D16, a), Canvas.CENTER, TXT);
        HudView.fit(c, "hud/diamond", 65, 120, 34, Anim.white(a));
        c.textFit("보유 다이아 " + Fmt.num(Math.round(shownHeld)), 95, 120, 25, 300, Anim.alpha(Theme.INK, a), Canvas.LEFT, TXT);
        for (int i = 0; i < 4; i++) {
            float bx = 23 + 102 * i, by = 155;   // 판 안쪽 좌우 여백 같게 (23 / 23)
            boolean off = i == 3 ? held <= 0 : held < Long.parseLong(AMT[i]);
            boolean over = !off && closing < 0 && Anim.in(lx, ly, bx, by, 88, 55);
            hov[i] += ((over ? 1 : 0) - hov[i]) * (1 - (float) Math.exp(-dt / 45f));
            float press = pressed == i ? 1 - Anim.t(now, pressAt, 160) : 0;
            float lift = -2 * hov[i] + 2 * press;
            String skin = off ? "dropdown/withdraw_button_disabled" : over ? "dropdown/withdraw_button_hover" : "dropdown/withdraw_button_normal";
            c.image(skin, bx, by + lift, 88, 55, Anim.white(a));
            c.textFit(LABEL[i], bx + 44, by + 27.5f + lift, 24, 68, Anim.alpha(off ? 0xFF9A9A9A : Theme.INK, a), Canvas.CENTER, TXT);
        }
        boolean hc = closing < 0 && Anim.in(lx, ly, 389, 59, 32, 32);
        float cs = 24 * (hc ? 1.15f : 1);
        c.image("dropdown/close_button", 405 - cs / 2, 75 - cs / 2, cs, cs, Anim.white(a));   // 오른쪽 위 클로버 장식에 안 겹치게
        c.pop();
    }

    @Override public boolean click(float mx, float my, long now) {
        if (closing >= 0) return true;
        float lx = (mx - ox) / k - 740, ly = (my - oy) / k - 425;
        if (Anim.in(lx, ly, 389, 59, 32, 32)) { close(now); return true; }
        long held = J.num(data, "held");
        for (int i = 0; i < 4; i++) {
            if (!Anim.in(lx, ly, 23 + 102 * i, 155, 88, 55)) continue;
            boolean off = i == 3 ? held <= 0 : held < Long.parseLong(AMT[i]);
            if (off) { host.sound("minecraft:block.note_block.didgeridoo", 2f, .5f); return true; }
            pressed = i;
            pressAt = now;
            host.send("withdraw", "amount", AMT[i]);
            host.sound("minecraft:block.amethyst_block.hit", 1.4f, .8f);
            return true;
        }
        if (!Anim.in(lx, ly, 0, 0, PW, PH)) { close(now); return true; }
        return false;
    }
}
