package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 깜빵 출구 선택 창 (받은 묶음 02_prison_exit 좌표): 창 (560,220) 800×600 제목칸 (225,92.5) 350×50,
 * 로비 카드 (620,380) / 복귀 카드 (980,380) 320×340, 글자 판 가운데 y 262 / 270 (그림에서 잼), 닫기 (1290,240) 60
 */
public final class JailExitPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private float k = 1, ox, oy;

    public JailExitPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "jail_exit"; }

    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    @Override public void render(Canvas c, long now, float mx, float my) {
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x66000000);
        k = Math.min(W / 1920f, H / 1080f);
        float rise = (1 - Anim.outCubic(Anim.t(now, opened, 260))) * 30;
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2 + rise;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        c.image("jail/exit_window", 560, 220, 800, 600, 0xFFFFFFFF);
        c.textFit("깜빵 탈출", 560 + 225 + 175, 220 + 92.5f + 25, 38, 350, 0xFF4A2D16, Canvas.CENTER, TXT);
        boolean hs = Anim.in(lx, ly, 620, 380, 320, 340), hr = Anim.in(lx, ly, 980, 380, 320, 340);
        c.image(hs ? "jail/exit_safe_hover" : "jail/exit_safe_normal", 620, 380 - (hs ? 4 : 0), 320, 340, 0xFFFFFFFF);
        c.image(hr ? "jail/exit_risk_hover" : "jail/exit_risk_normal", 980, 380 - (hr ? 4 : 0), 320, 340, 0xFFFFFFFF);
        c.textFit("로비로 나가기", 620 + 35 + 125, 380 + 262 - (hs ? 4 : 0), 30, 250, 0xFF2F5A1C, Canvas.CENTER, TXT);
        c.textFit("원래 자리로 (50%)", 980 + 35 + 125, 380 + 270 - (hr ? 4 : 0), 30, 250, 0xFF7A4A00, Canvas.CENTER, TXT);
        c.text("실패하면 로비로 가요", 1140, 745, 20, 0xFF7A6A58, Canvas.CENTER, TXT);
        boolean hc = Anim.in(lx, ly, 1290, 240, 60, 60);
        c.image(hc ? "jail/close_hover" : "jail/close_normal", 1290, 240, 60, 60, 0xFFFFFFFF);
        c.pop();
    }

    @Override public boolean click(float mx, float my, long now) {
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        if (Anim.in(lx, ly, 1290, 240, 60, 60)) { host.closePanel(this); return true; }
        if (Anim.in(lx, ly, 620, 380, 320, 340)) { host.send("jail.exit", "choice", "lobby"); host.closePanel(this); return true; }
        if (Anim.in(lx, ly, 980, 380, 320, 340)) { host.send("jail.exit", "choice", "return"); host.closePanel(this); return true; }
        return false;
    }
}
