package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 기부 랭킹 (screen "donation_board", 받은 묶음 3): 판 (560,140) 800×800, 제목 판 가운데 (400,99) — 그림에서 잼.
 * 닫기 (710,84) 60, 총 기부 띠 (50,170) 700×80 (글자 x 115~655, y 40),
 * 1~3등 줄 (50,270+100i) 700×88 (메달 숫자 (85,44), 이름 x 180, 수 오른쪽 676),
 * 4등부터 (50,574+66j) 700×56 (등수 (76,28), 이름 x 150, 수 오른쪽 670) — 3줄 보이고 나머지는 스크롤.
 */
public final class DonationBoardPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private long closing = -1;
    private float k = 1, ox, oy, scroll, shown;

    public DonationBoardPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "donation_board"; }
    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    @Override public void render(Canvas c, long now, float mx, float my) {
        if (closing >= 0 && now - closing > 150) { host.closePanel(this); return; }
        float t = closing >= 0 ? 1 - Anim.t(now, closing, 150) : Anim.t(now, opened, 260);
        float e = closing >= 0 ? t : Anim.outBack(t), a = Anim.clamp(t * 1.8f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x66000000, a));
        k = Math.min(W / 1920f, H / 1080f);
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2;
        float lx = (mx - ox) / k - 560, ly = (my - oy) / k - 140;
        List<JsonObject> rows = J.objs(data, "rows");
        int extra = Math.max(0, rows.size() - 3);
        float maxScroll = Math.max(0, extra * 66 - 198);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        shown += (scroll - shown) * .25f;

        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(960, 540);
        float s = .9f + .1f * e;
        c.scale(s, s);
        c.translate(-400, -400);
        c.image("board/board", 0, 0, 800, 800, Anim.white(a));
        c.textFit("기부 랭킹", 400, 99, 32, 270, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        boolean hc = Anim.in(lx, ly, 710, 84, 60, 60);
        c.image(hc ? "board/close_hover" : "board/close_normal", 710, 84, 60, 60, Anim.white(a));

        // 총 기부
        c.image("board/total_strip", 50, 170, 700, 80, Anim.white(a));
        c.text("서버 총 기부", 165, 210, 24, Anim.alpha(INK, a), Canvas.LEFT, TXT);
        c.text(Fmt.num(J.num(data, "total")) + "개", 705, 210, 28, Anim.alpha(0xFF2F7FB0, a), Canvas.RIGHT, TXT);

        String[] medal = {"gold", "silver", "bronze"};
        for (int i = 0; i < 3; i++) {
            float ry = 270 + 100 * i, rt = Anim.outCubic(Anim.t(now, opened + 80 + 50L * i, 240));
            float ra = a * rt, slide = (1 - rt) * 20;
            c.image("board/" + medal[i], 50 + slide, ry, 700, 88, Anim.white(ra));
            c.text(String.valueOf(i + 1), 50 + 85 + slide, ry + 44, 24, Anim.alpha(0xFFFFFFFF, ra), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            if (i < rows.size()) {
                JsonObject r = rows.get(i);
                c.textFit(J.str(r, "name"), 50 + 180 + slide, ry + 44, 26, 320, Anim.alpha(INK, ra), Canvas.LEFT, TXT);
                c.text(Fmt.num(J.num(r, "amount")) + "개", 50 + 676 + slide, ry + 44, 26, Anim.alpha(INK, ra), Canvas.RIGHT, TXT);
            } else c.text("-", 50 + 180 + slide, ry + 44, 24, Anim.alpha(0xFF9A8470, ra), Canvas.LEFT, TXT);
        }

        c.clip(40, 570, 720, 200);
        for (int j = 0; j < extra; j++) {
            float ry = 574 + 66 * j - shown;
            if (ry > 780 || ry + 56 < 560) continue;
            float rt = Anim.outCubic(Anim.t(now, opened + 230 + 40L * Math.min(j, 3), 240)), ra = a * rt;
            JsonObject r = rows.get(3 + j);
            c.image("board/regular", 50, ry, 700, 56, Anim.white(ra));
            c.text(String.valueOf(J.num(r, "rank", 4 + j)), 50 + 76, ry + 28, 20, Anim.alpha(INK, ra), Canvas.CENTER, TXT);
            c.textFit(J.str(r, "name"), 50 + 150, ry + 28, 22, 330, Anim.alpha(INK, ra), Canvas.LEFT, TXT);
            c.text(Fmt.num(J.num(r, "amount")) + "개", 50 + 670, ry + 28, 22, Anim.alpha(INK, ra), Canvas.RIGHT, TXT);
        }
        c.unclip();
        if (rows.isEmpty()) c.text("아직 기부한 사람이 없어요", 400, 660, 22, Anim.alpha(0xFF9A8470, a), Canvas.CENTER, TXT);
        c.pop();
    }

    @Override public boolean scroll(float mx, float my, double amount) { scroll -= (float) amount * 40; return true; }

    @Override public boolean click(float mx, float my, long now) {
        float lx = (mx - ox) / k - 560, ly = (my - oy) / k - 140;
        if (Anim.in(lx, ly, 710, 84, 60, 60) || !Anim.in(lx, ly, 0, 0, 800, 800)) { if (closing < 0) closing = now; return true; }
        return false;
    }
}
