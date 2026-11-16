package com.diamondserver.ui;

import com.diamondserver.client.St;

/**
 * 우편함 화면. 받은 그림만 재활용: 상점 창·상품 칸·분류 버튼·구매/판매 버튼·스크롤 막대·우편함 아이콘.
 * 배치는 상점 화면과 같은 자리(받은 layout.json 좌표)를 쓴다: 왼쪽 분류 1칸, 오른쪽 우편 4열 × 3줄, 맨 아래 받기 줄.
 */
public final class MailUi {
    public interface Host {
        void claim(String id);
        void claimAll();
        void close();
        void clickSound();
    }

    private final Host host;
    private String sel;
    private int scroll;
    private String down = "";
    private double ox, oy;

    public MailUi(Host host) { this.host = host; }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int maxScroll(int count) { return Math.max(0, (count + 3) / 4 - 3); }

    private double cardX(int v) { return ox + 544 + (v % 4) * 259; }
    private double cardY(int v) { return oy + 308 + (v / 4) * 169; }

    private St.Letter selected() {
        for (St.Letter l : St.mails) if (l.id().equals(sel)) return l;
        return null;
    }

    private void icon(G g, St.Letter l, double x, double y, double size) {
        if (!l.art().isEmpty()) g.img(l.art(), x, y, size, size, 1); else g.item(l.icon(), x, y, size);
    }

    public void draw(G g, double mx, double my) {
        ox = (g.vw() - 1920) / 2;
        oy = (g.vh() - 1080) / 2;
        int n = St.mails.size();
        scroll = Math.min(scroll, maxScroll(n));

        g.img("shop/shop_window", ox + 300, oy + 85, 1320, 900, 1);
        g.img("hud/mail", ox + 336, oy + 111, 54, 54, 1);
        g.text("우편함", ox + 407, oy + 139, 37, 0, G.TEXT);
        g.img("shop/balance", ox + 1327, oy + 112, 180, 50, 1);
        g.img("hud/mail", ox + 1342, oy + 121, 29, 29, 1);
        g.fit(n + "통", ox + 1384, oy + 137, 24, 0, G.TEXT, 108);
        g.img(down.equals("close") ? "shop/close_pressed" : "shop/close_normal", ox + 1540, oy + 113, 48, 48, 1);
        g.text("받을 우편을 고른 뒤 받기를 누르세요. 인벤토리가 가득 차면 못 받은 만큼은 우편함에 남습니다.", ox + 329, oy + 236, 20, 0, G.DIM);

        g.img("shop/category_selected", ox + 329, oy + 309, 197, 60, 1);
        g.text("받은 우편", ox + 427, oy + 338, 22, 1, G.TEXT);

        if (n == 0) g.text("받은 우편이 없습니다", ox + 1058, oy + 540, 22, 1, G.DIM);
        for (int v = 0; v < 12; v++) {
            int idx = scroll * 4 + v;
            if (idx >= n) break;
            St.Letter l = St.mails.get(idx);
            double x = cardX(v), y = cardY(v);
            boolean on = l.id().equals(sel) || in(mx, my, x, y, 252, 126);
            g.img(on ? "shop/compact_card_hover" : "shop/compact_card_normal", x, y, 252, 126, 1);
            icon(g, l, x + 13, y + 29, 64);
            g.fitClip(l.title(), x + 100, y + 47, 18, 0, G.TEXT, 140);
            g.fitClip(l.text(), x + 100, y + 83, 17, 0, G.DIM, 140);
        }
        int rows = (n + 3) / 4;
        if (rows > 3) {
            g.img("shop/scroll_track", ox + 1590, oy + 305, 8, 466, 1);
            double h = 466 * 3.0 / rows;
            g.img("shop/scroll_thumb", ox + 1590 + G.THUMB_DX, oy + 305 + (466 - h) * scroll / (double) maxScroll(n), 8, h, 1);
        }

        St.Letter s = selected();
        if (s == null) g.text(n == 0 ? "" : "받을 우편을 고르세요", ox + 339, oy + 924, 24, 0, G.DIM);
        else {
            icon(g, s, ox + 339, oy + 898, 52);
            g.fit(s.title() + "  ·  " + s.text() + "  ·  보낸 곳: " + s.from(), ox + 407, oy + 924, 24, 0, G.TEXT, 760);
        }
        g.img("shop/sell" + (down.equals("all") ? "_pressed" : "_normal"), ox + 1199, oy + 899, 176, 52, n > 0 ? 1 : 0.4);
        g.text("모두 받기", ox + 1287, oy + 924, 23, 1, n > 0 ? G.DARK : G.DIM);
        g.img("shop/buy" + (down.equals("one") ? "_pressed" : "_normal"), ox + 1391, oy + 899, 176, 52, s != null ? 1 : 0.4);
        g.text("받기", ox + 1479, oy + 924, 23, 1, s != null ? G.DARK : G.DIM);
    }

    public void mouseDown(double mx, double my) {
        if (in(mx, my, ox + 1540, oy + 113, 48, 48)) { down = "close"; host.clickSound(); host.close(); return; }
        int n = St.mails.size();
        for (int v = 0; v < 12; v++) {
            int idx = scroll * 4 + v;
            if (idx >= n) break;
            if (in(mx, my, cardX(v), cardY(v), 252, 126)) { sel = St.mails.get(idx).id(); host.clickSound(); return; }
        }
        if (in(mx, my, ox + 1199, oy + 899, 176, 52) && n > 0) { down = "all"; host.clickSound(); host.claimAll(); }
        else if (in(mx, my, ox + 1391, oy + 899, 176, 52) && selected() != null) { down = "one"; host.clickSound(); host.claim(sel); }
    }

    public void mouseUp() { down = ""; }

    /** 휠: dir > 0 이면 위로 */
    public void scroll(double dir) {
        scroll = Math.max(0, Math.min(maxScroll(St.mails.size()), scroll - (int) Math.signum(dir)));
    }

    /** 검수용: 고른 우편을 정해 놓고 그린다 */
    public void preset(String sel) { this.sel = sel; }
}
