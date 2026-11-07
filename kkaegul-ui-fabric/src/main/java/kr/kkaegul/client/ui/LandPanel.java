package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 땅 관리 창 (screen "land"). 배치는 묶음 6 배치도 3·4 그대로 (판 800×700, 1920×1080 기준 (560,180)).
 * 왼쪽: 지금 내 땅에 들어온 사람 + 허용 / 오른쪽: 허용 명단 + 빼기 / 아래: 내 땅 수, 땅 회수(확인 팝업).
 * 그림이 오기 전까지는 코드로 그린 임시 판.
 */
public final class LandPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final float PW = 800, PH = 700, ROW = 66, LIST_Y = 170, VISIBLE = 5;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private long closing = -1, confirmAt = -1;
    private boolean confirm;
    private float k = 1, ox, oy, scrollL, scrollR;

    public LandPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "land"; }
    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    private void close(long now) { if (closing < 0) closing = now; }

    private float lx(float mx) { return (mx - ox) / k - 560; }
    private float ly(float my) { return (my - oy) / k - 190; }

    @Override public void render(Canvas c, long now, float mx, float my) {
        if (closing >= 0 && now - closing > 150) { host.closePanel(this); return; }
        float t = closing >= 0 ? 1 - Anim.t(now, closing, 150) : Anim.t(now, opened, 240);
        float e = closing >= 0 ? t : Anim.outBack(t), a = Anim.clamp(t * 1.8f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x55000000, a));
        k = Math.min(W / 1920f, H / 1080f);
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2;
        float lx = lx(mx), ly = ly(my);
        boolean live = closing < 0 && !confirm;

        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(960, 540);
        float s = .9f + .1f * e;
        c.scale(s, s);
        c.translate(-PW / 2, -PH / 2);
        // 판
        c.fill(0, 0, PW, PH, 34, Anim.alpha(0xFF6B4A2B, a));
        c.fill(5, 5, PW - 10, PH - 10, 30, Anim.alpha(Theme.CREAM, a));
        c.fill(PW / 2 - 170, -14, 340, 64, 32, Anim.alpha(0xFF8BC56A, a));
        c.textFit("내 땅 관리", PW / 2, 18, 30, 300, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        String here = J.str(data, "here");
        if (!here.isEmpty()) c.text("지금 청크 " + here, PW / 2, 70, 18, Anim.alpha(Theme.MUTED, a), Canvas.CENTER, TXT);
        // 닫기
        boolean hc = live && Anim.in(lx, ly, PW - 70, 10, 60, 60);
        c.fill(PW - 66, 14, 52, 52, 26, Anim.alpha(hc ? 0xFFE0564C : 0xFFC9A98A, a));
        c.text("X", PW - 40, 40, 26, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD);

        // 소제목
        sub(c, 40, 100, "지금 들어온 사람", a);
        sub(c, 420, 100, "허용 명단", a);
        List<JsonObject> inside = J.objs(data, "inside"), trusted = J.objs(data, "trusted");
        scrollL = clampScroll(scrollL, inside.size());
        scrollR = clampScroll(scrollR, trusted.size());
        list(c, 40, inside, scrollL, true, lx, ly, live, a);
        list(c, 420, trusted, scrollR, false, lx, ly, live, a);

        // 아래: 내 땅 수 + 회수
        c.fill(40, 520, 340, 50, 25, Anim.alpha(0xFFF1E2C6, a));
        c.text("내 땅 " + J.num(data, "count") + "칸", 210, 545, 22, Anim.alpha(Theme.INK, a), Canvas.CENTER, TXT);
        boolean hr = live && Anim.in(lx, ly, 280, 600, 240, 60);
        c.fill(280, 600 - (hr ? 2 : 0), 240, 60, 30, Anim.alpha(hr ? 0xFFE8675C : 0xFFD9534A, a));
        c.text("이 땅 회수", 400, 630 - (hr ? 2 : 0), 24, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        c.pop();

        if (confirm) renderConfirm(c, now, mx, my, a);
    }

    private void sub(Canvas c, float x, float y, String s, float a) {
        c.fill(x, y, 340, 50, 25, Anim.alpha(0xFFE9D5F7, a));
        c.text(s, x + 170, y + 25, 22, Anim.alpha(Theme.INK, a), Canvas.CENTER, TXT);
    }

    private static float clampScroll(float s, int n) { return Math.max(0, Math.min(s, Math.max(0, (n - VISIBLE) * ROW))); }

    private void list(Canvas c, float x, List<JsonObject> rows, float scroll, boolean left, float lx, float ly, boolean live, float a) {
        if (rows.isEmpty()) {
            c.text(left ? "아무도 없어요" : "허용한 사람이 없어요", x + 170, LIST_Y + 80, 20, Anim.alpha(Theme.MUTED, a), Canvas.CENTER, TXT);
            return;
        }
        c.clip(x - 4, LIST_Y - 4, 348, VISIBLE * ROW + 4);
        for (int i = 0; i < rows.size(); i++) {
            float y = LIST_Y + i * ROW - scroll;
            if (y + ROW < LIST_Y - 10 || y > LIST_Y + VISIBLE * ROW) continue;
            JsonObject r = rows.get(i);
            boolean on = live && Anim.in(lx, ly, x, y, 340, 56) && ly >= LIST_Y && ly < LIST_Y + VISIBLE * ROW;
            c.fill(x, y, 340, 56, 28, Anim.alpha(on ? 0xFFD8F0FA : 0xFFC9E9F5, a));
            if (!c.face(J.str(r, "account"), x + 10, y + 8, 40)) c.fill(x + 10, y + 8, 40, 40, 8, Anim.alpha(0xFFB9D7E3, a));
            c.textFit(J.str(r, "name"), x + 60, y + 28, 20, left ? 170 : 160, Anim.alpha(Theme.INK, a), Canvas.LEFT, TXT);
            float bx = x + 240, by = y + 8;
            boolean already = left && J.bool(r, "trusted");
            boolean hb = live && !already && Anim.in(lx, ly, bx, by, 90, 40);
            int col = left ? (already ? 0xFFB8C4B0 : hb ? 0xFF6FBF57 : 0xFF5BA346) : (hb ? 0xFFE8675C : 0xFFD9534A);
            c.fill(bx, by - (hb ? 2 : 0), 90, 40, 20, Anim.alpha(col, a));
            c.text(left ? (already ? "허용됨" : "허용") : "빼기", bx + 45, by + 20 - (hb ? 2 : 0), 19, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD);
        }
        c.unclip();
    }

    private void renderConfirm(Canvas c, long now, float mx, float my, float a0) {
        float t = Anim.outBack(Anim.t(now, confirmAt, 200)), a = Anim.clamp(Anim.t(now, confirmAt, 120)) * a0;
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x44000000, a));
        float px = (mx - ox) / k - 740, py = (my - oy) / k - 425;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(960, 540);
        float s = .9f + .1f * t;
        c.scale(s, s);
        c.translate(-220, -115);
        c.fill(0, 0, 440, 230, 28, Anim.alpha(0xFF6B4A2B, a));
        c.fill(4, 4, 432, 222, 24, Anim.alpha(Theme.CREAM, a));
        c.text("땅 회수", 220, 40, 26, Anim.alpha(Theme.INK, a), Canvas.CENTER, TXT);
        c.textFit("이 청크를 회수하고 땅문서를 돌려받을까요?", 220, 105, 20, 380, Anim.alpha(Theme.INK, a), Canvas.CENTER, TXT);
        boolean hy = Anim.in(px, py, 40, 155, 160, 55), hn = Anim.in(px, py, 240, 155, 160, 55);
        c.fill(40, 155, 160, 55, 27, Anim.alpha(hy ? 0xFFE8675C : 0xFFD9534A, a));
        c.text("회수", 120, 182, 22, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD);
        c.fill(240, 155, 160, 55, 27, Anim.alpha(hn ? 0xFFB3A99E : 0xFF9C9288, a));
        c.text("취소", 320, 182, 22, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD);
        c.pop();
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        float lx = lx(mx);
        if (lx < 400) scrollL -= (float) amount * 40; else scrollR -= (float) amount * 40;
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
        if (closing >= 0) return true;
        if (confirm) {
            float px = (mx - ox) / k - 740, py = (my - oy) / k - 425;
            if (Anim.in(px, py, 40, 155, 160, 55)) { host.send("land.release"); host.sound("minecraft:ui.button.click", 1f, .4f); confirm = false; close(now); }
            else { confirm = false; host.sound("minecraft:ui.button.click", .8f, .3f); }
            return true;
        }
        float lx = lx(mx), ly = ly(my);
        if (Anim.in(lx, ly, PW - 70, 10, 60, 60) || !Anim.in(lx, ly, 0, -20, PW, PH + 20)) { close(now); return true; }
        if (Anim.in(lx, ly, 280, 600, 240, 60)) { confirm = true; confirmAt = now; host.sound("minecraft:ui.button.click", 1.1f, .4f); return true; }
        if (ly >= LIST_Y && ly < LIST_Y + VISIBLE * ROW) {
            boolean left = lx < 400;
            List<JsonObject> rows = J.objs(data, left ? "inside" : "trusted");
            float x = left ? 40 : 420, scroll = left ? scrollL : scrollR;
            int i = (int) Math.floor((ly - LIST_Y + scroll) / ROW);
            if (i >= 0 && i < rows.size() && Anim.in(lx, ly, x + 240, LIST_Y + i * ROW - scroll + 8, 90, 40)) {
                JsonObject r = rows.get(i);
                if (left && J.bool(r, "trusted")) return true;
                host.send(left ? "land.trust" : "land.untrust", "uuid", J.str(r, "uuid"));
                host.sound(left ? "minecraft:block.note_block.bell" : "minecraft:ui.button.click", left ? 1.4f : 1f, .5f);
            }
        }
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (key == 256 && confirm) { confirm = false; return true; }
        return false;
    }
}
