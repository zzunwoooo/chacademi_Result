package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 땅 관리 창 (screen "land") — 우편함 창 그림을 그대로 재활용.
 * 창 (560,160) 800×740, 닫기 (1290,170) 60, 줄 (610, 290+100i) 700×84 [얼굴 (12,10) 64, 이름 x 112, 버튼 (596,15) 88×55 = 인출 버튼 그림],
 * 아래 버튼 (760,800) 400×70 = "이 땅 회수" (누르면 인출 팝업 그림으로 확인).
 * 줄: 지금 내 땅에 들어온 사람(허용) + 허용 명단(빼기)을 한 목록으로.
 */
public final class LandPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16, MUTED = 0xFF9A8A78;
    private final Host host;
    private JsonObject data;
    private final long opened;
    private long closing = -1, confirmAt = -1, pressedAt;
    private boolean confirm;
    private float k = 1, ox, oy, scroll, shown;

    private record Row(String uuid, String name, String account, boolean inside, boolean trusted) {}

    public LandPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "land"; }
    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    private List<Row> rows() {
        Map<String, Row> m = new LinkedHashMap<>();
        for (JsonObject o : J.objs(data, "inside"))
            m.put(J.str(o, "uuid"), new Row(J.str(o, "uuid"), J.str(o, "name"), J.str(o, "account"), true, J.bool(o, "trusted")));
        for (JsonObject o : J.objs(data, "trusted")) {
            String u = J.str(o, "uuid");
            Row r = m.get(u);
            if (r == null) m.put(u, new Row(u, J.str(o, "name"), J.str(o, "account"), false, true));
        }
        return new ArrayList<>(m.values());
    }

    private static final float HW = 220, HH = 55, HX = 960 - HW / 2, HY = 796;

    private float maxScroll(int n) { return Math.max(0, n * 100 - 484); }

    private void close(long now) { if (closing < 0) closing = now; }

    @Override public void render(Canvas c, long now, float mx, float my) {
        if (closing >= 0 && now - closing > 150) { host.closePanel(this); return; }
        float t = closing >= 0 ? 1 - Anim.t(now, closing, 150) : Anim.t(now, opened, 260);
        float a = Anim.clamp(t * 1.6f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x66000000, a));
        k = Math.min(W / 1920f, H / 1080f);
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2 + (1 - Anim.outCubic(t)) * 30;
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        boolean live = closing < 0 && !confirm;
        List<Row> rows = rows();
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.image("mail/mail_panel", 560, 160, 800, 740, Anim.white(a));
        c.textFit("내 땅 관리 · " + J.num(data, "count") + "칸", 960, 160 + 99.75f, 34, 280, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        boolean hc = live && Anim.in(lx, ly, 1290, 170, 60, 60);
        c.image("mail/mail_close", 1290, 170 - (hc ? 2 : 0), 60, 60, Anim.white(a));

        scroll = Math.max(0, Math.min(scroll, maxScroll(rows.size())));
        shown += (scroll - shown) * .25f;
        if (Math.abs(scroll - shown) < .3f) shown = scroll;
        c.clip(600, 284, 716, 496);
        for (int i = 0; i < rows.size(); i++) {
            float y = 290 + 100 * i - shown;
            if (y > 790 || y + 84 < 280) continue;
            Row r = rows.get(i);
            c.image("mail/mail_row_normal", 610, y, 700, 84, Anim.white(a));
            // v3 줄 그림: 알약 몸통 y 17~81 (가운데 49)
            if (!c.face(r.account(), 626, y + 21, 56)) HudView.fit(c, "dropdown/icon_profile", 654, y + 49, 52, Anim.white(a));
            c.textFit(r.name(), 610 + 112, y + 38, 24, 330, Anim.alpha(INK, a), Canvas.LEFT, TXT);
            String tag = r.inside() ? (r.trusted() ? "내 땅에 있음 · 허용됨" : "내 땅에 있음") : "허용 명단";
            c.textFit(tag, 610 + 112, y + 63, 16, 330, Anim.alpha(MUTED, a), Canvas.LEFT, TXT);
            boolean add = !r.trusted();
            float bx = 610 + 596, by = y + 21.5f;
            boolean hb = live && ly > 284 && ly < 780 && Anim.in(lx, ly, bx, by, 88, 55);
            c.image(hb ? "dropdown/withdraw_button_hover" : "dropdown/withdraw_button_normal", bx, by - (hb ? 2 : 0), 88, 55, Anim.white(a));
            c.textFit(add ? "허용" : "빼기", bx + 44, by + 27.5f - (hb ? 2 : 0), 22, 70, Anim.alpha(add ? 0xFF3E8E2F : 0xFFB8322B, a), Canvas.CENTER, TXT);
        }
        if (rows.isEmpty()) {
            c.text("내 땅에 들어온 사람이 없어요", 960, 500, 26, Anim.alpha(MUTED, a), Canvas.CENTER, TXT);
            c.text("같이 쓸 사람을 내 땅에 데려오면 허용할 수 있어요", 960, 545, 19, Anim.alpha(MUTED, a), Canvas.CENTER, TXT);
        }
        c.unclip();
        if (maxScroll(rows.size()) > 0) {
            c.image("mail/mail_scroll_track", 1320, 290, 16, 480, Anim.white(a));
            float th = Math.max(40, 480 * 484f / (rows.size() * 100));
            c.image("mail/mail_scroll_thumb", 1320, 290 + (480 - th) * shown / maxScroll(rows.size()), 16, th, Anim.white(a));
        }
        // 집 위치 버튼: 지금 서 있는 자리를 집으로 (이동 > 마을 때 여기로)
        boolean hh = live && Anim.in(lx, ly, HX, HY, HW, HH);
        c.imageNine(hh ? "dropdown/withdraw_button_hover" : "dropdown/withdraw_button_normal", HX, HY - (hh ? 2 : 0), HW, HH, .3f, .4f, 26, 22, Anim.white(a));
        c.textFit("여기를 집으로", HX + HW / 2, HY + HH / 2 - (hh ? 2 : 0), 22, HW - 30, Anim.alpha(0xFF3E8E2F, a), Canvas.CENTER, TXT);
        String here = J.str(data, "here");
        c.textFit("이동 > 마을 을 누르면 집으로 와요 · 허용한 사람은 내 땅을 같이 써요" + (here.isEmpty() ? "" : "  ·  지금 청크 " + here), 960, 868, 17, 700, Anim.alpha(MUTED, a), Canvas.CENTER, TXT);
        c.pop();

        if (confirm) renderConfirm(c, now, mx, my);
    }

    /** 회수 확인: 인출 팝업 그림 재활용 (440×230, 제목 (220,65.5), 버튼 88×55, 닫기 (413,71)) */
    private void renderConfirm(Canvas c, long now, float mx, float my) {
        float t = Anim.t(now, confirmAt, 200), e = Anim.outBack(t), a = Anim.clamp(t * 1.8f);
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, Anim.alpha(0x44000000, a));
        float px = (mx - ox) / k - 740, py = (my - oy) / k - 425;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.translate(960, 540);
        float s = .88f + .12f * e;
        c.scale(s, s);
        c.translate(-220, -115);
        c.image("dropdown/withdraw_panel", 0, 0, 440, 230, Anim.white(a));
        c.textFit("땅 회수", 220, 65.5f, 26, 160, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.textFit("땅문서를 돌려받고 이 청크를 놓을까요?", 220, 120, 21, 380, Anim.alpha(Theme.INK, a), Canvas.CENTER, TXT);
        boolean hy = Anim.in(px, py, 110, 155, 88, 55), hn = Anim.in(px, py, 242, 155, 88, 55);
        c.image(hy ? "dropdown/withdraw_button_hover" : "dropdown/withdraw_button_normal", 110, 155 - (hy ? 2 : 0), 88, 55, Anim.white(a));
        c.textFit("회수", 154, 182.5f - (hy ? 2 : 0), 22, 70, Anim.alpha(0xFFB8322B, a), Canvas.CENTER, TXT);
        c.image(hn ? "dropdown/withdraw_button_hover" : "dropdown/withdraw_button_normal", 242, 155 - (hn ? 2 : 0), 88, 55, Anim.white(a));
        c.textFit("취소", 286, 182.5f - (hn ? 2 : 0), 22, 70, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.image("dropdown/close_button", 401, 59, 24, 24, Anim.white(a));
        c.pop();
    }

    @Override public boolean scroll(float mx, float my, double amount) { scroll -= (float) amount * 60; return true; }

    @Override public boolean click(float mx, float my, long now) {
        if (closing >= 0) return true;
        if (confirm) {
            float px = (mx - ox) / k - 740, py = (my - oy) / k - 425;
            if (Anim.in(px, py, 110, 155, 88, 55)) { host.send("land.release"); host.sound("minecraft:ui.button.click", 1f, .4f); confirm = false; close(now); }
            else { confirm = false; host.sound("minecraft:ui.button.click", .8f, .3f); }
            return true;
        }
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        if (Anim.in(lx, ly, 1290, 170, 60, 60) || !Anim.in(lx, ly, 560, 160, 800, 740)) { close(now); return true; }
        if (Anim.in(lx, ly, HX, HY, HW, HH)) { host.send("land.sethome"); host.sound("minecraft:block.note_block.bell", 1.2f, .5f); return true; }
        if (ly > 284 && ly < 780) {
            List<Row> rows = rows();
            for (int i = 0; i < rows.size(); i++) {
                float y = 290 + 100 * i - shown;
                if (!Anim.in(lx, ly, 610 + 596, y + 15, 88, 55)) continue;
                Row r = rows.get(i);
                host.send(r.trusted() ? "land.untrust" : "land.trust", "uuid", r.uuid());
                host.sound(r.trusted() ? "minecraft:ui.button.click" : "minecraft:block.note_block.bell", r.trusted() ? 1f : 1.4f, .5f);
                return true;
            }
        }
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (key == 256 && confirm) { confirm = false; return true; }
        return false;
    }
}
