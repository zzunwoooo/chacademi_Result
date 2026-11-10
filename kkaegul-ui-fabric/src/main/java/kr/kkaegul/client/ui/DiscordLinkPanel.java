package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 디스코드 연동 코드 팝업 (screen "discord_link" {code, minutes}, 받은 묶음 5).
 * 판 480×300 을 화면 가운데에. 숫자 6개: 가운데 x 79.75+64i, y 172.
 * 제목 (240,100) / 설명 (240,124) / 초록 버튼 (80,214) 320×32 = 명령어 복사 / 닫기 (405,28) 60.
 * 판 안쪽은 y 62~267, 아래 가운데 클로버 장식이 y 257 부터라 버튼은 그 위(아래 끝 246)에 둬서 장식을 안 가림.
 */
public final class DiscordLinkPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;
    private static final float PW = 480, PH = 300, PX = 720, PY = 390;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private long closing = -1, copiedAt = -1;
    private float k = 1, ox, oy;

    public DiscordLinkPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "discord_link"; }
    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    @Override public void render(Canvas c, long now, float mx, float my) {
        if (closing >= 0 && now - closing > 150) { host.closePanel(this); return; }
        float t = closing >= 0 ? 1 - Anim.t(now, closing, 150) : Anim.t(now, opened, 260);
        float e = closing >= 0 ? t : Anim.outBack(t), a = Anim.clamp(t * 1.8f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x55000000, a));
        k = Math.min(W / 1920f, H / 1080f);
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2;
        float lx = (mx - ox) / k - PX, ly = (my - oy) / k - PY;
        String code = J.str(data, "code");
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(PX + PW / 2, PY + PH / 2);
        float s = .9f + .1f * e;
        c.scale(s, s);
        c.translate(-PW / 2, -PH / 2);
        c.image("discord/popup", 0, 0, PW, PH, Anim.white(a));
        c.textFit("디스코드 연동", 240, 100, 22, 300, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.textFit("디스코드 서버에서 /연동 뒤에 이 숫자를 입력하세요 (" + J.num(data, "minutes", 10) + "분 안에)", 240, 124, 13, 376, Anim.alpha(0xFF7A6A58, a), Canvas.CENTER, TXT);
        for (int i = 0; i < 6; i++) {
            float cx = 79.75f + 64 * i;
            float pop = Anim.outBack(Anim.t(now, opened + 120 + 50L * i, 260));
            String ch = i < code.length() ? String.valueOf(code.charAt(i)) : "";
            c.push();
            c.translate(cx, 172);
            c.scale(pop, pop);
            c.text(ch, 0, 0, 40, Anim.alpha(0xFF5B3FA8, a), Canvas.CENTER, TXT);
            c.pop();
        }
        boolean hb = closing < 0 && Anim.in(lx, ly, 80, 214, 320, 32);
        c.image(hb ? "discord/connect_hover" : "discord/connect_normal", 80, 214, 320, 32, Anim.white(a));
        boolean copied = copiedAt > 0 && now - copiedAt < 2000;
        c.textFit(copied ? "복사했어요! 디스코드 채팅창에 붙여넣기" : "/연동 " + code + "  복사하기", 240, 230, 17, 270, Anim.alpha(0xFF2F5A1C, a), Canvas.CENTER, TXT);
        boolean hc = closing < 0 && Anim.in(lx, ly, 405, 28, 60, 60);
        c.image(hc ? "discord/close_hover" : "discord/close_normal", 405, 28, 60, 60, Anim.white(a));
        c.pop();
    }

    @Override public boolean click(float mx, float my, long now) {
        if (closing >= 0) return true;
        float lx = (mx - ox) / k - PX, ly = (my - oy) / k - PY;
        if (Anim.in(lx, ly, 405, 28, 60, 60) || !Anim.in(lx, ly, 0, 0, PW, PH)) { closing = now; return true; }
        if (Anim.in(lx, ly, 80, 214, 320, 32)) {
            host.copy("/연동 " + J.str(data, "code"));
            copiedAt = now;
            host.sound("minecraft:block.note_block.bell", 1.6f, .6f);
            return true;
        }
        return false;
    }
}
