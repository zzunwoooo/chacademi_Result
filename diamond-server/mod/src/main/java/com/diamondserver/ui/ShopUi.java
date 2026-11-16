package com.diamondserver.ui;

import com.diamondserver.client.St;

import java.util.ArrayList;
import java.util.List;

/**
 * 상점 화면: 구매 · 판매 · 제출 세 탭.
 * 구매 탭 좌표는 받은 layout.json 그대로. 판매·제출 탭은 같은 그림으로 오너가 승인한 배치.
 */
public final class ShopUi {
    /** 게임 쪽이 해 주는 일 */
    public interface Host {
        /** 내 인벤토리에 그 아이템이 몇 개 있는지 (예: "minecraft:coal") */
        int count(String mcId);
        void buy(String id, int qty);
        /** mat = 아이템 id 또는 "all" */
        void sell(String mat);
        void submit(long qty);
        void close();
        void clickSound();
    }

    private static final String[] TABS = {"구매", "판매", "제출"};
    private final Host host;
    private int tab;
    private String cat, sel;
    private int qty = 1, scroll;
    private long submitQty = -1;       // -1 = 아직 안 건드림(낼 수 있는 최대로)
    private String down = "";          // 누르고 있는 버튼
    private double ox, oy;

    public ShopUi(Host host) { this.host = host; }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private List<St.Item> shown() {
        List<St.Item> out = new ArrayList<>();
        for (St.Item i : St.items) if (i.cat().equals(cat)) out.add(i);
        return out;
    }

    private St.Item selected() {
        for (St.Item i : St.items) if (i.id().equals(sel)) return i;
        return null;
    }

    /** 판매 탭 한 칸: 내 인벤토리에 있는 광물 */
    private record Holding(St.Sell sell, int count, double value) { }

    private List<Holding> holdings() {
        List<Holding> out = new ArrayList<>();
        for (St.Sell s : St.sells) {
            int n = host.count(s.mat()) + 9 * (s.block().isEmpty() ? 0 : host.count(s.block()));
            if (n > 0) out.add(new Holding(s, n, n * s.price()));
        }
        return out;
    }

    private static int maxScroll(int count) { return Math.max(0, (count + 3) / 4 - 3); }

    private long submitMax() { return Math.max(0, Math.min(St.held, St.goal)); }

    private long submitNow() {
        long max = submitMax();
        return submitQty < 0 ? max : Math.max(Math.min(1, max), Math.min(max, submitQty));
    }

    // ── 그리기 ─────────────────────────────────────────────
    public void draw(G g, double mx, double my) {
        ox = (g.vw() - 1920) / 2;
        oy = (g.vh() - 1080) / 2;
        if (cat == null && !St.cats.isEmpty()) cat = St.cats.get(0).id();

        g.img("shop/shop_window", ox + 300, oy + 85, 1320, 900, 1);
        g.img("hud/shop", ox + 336, oy + 111, 54, 54, 1);
        g.text("상점", ox + 407, oy + 139, 37, 0, G.TEXT);
        g.img("shop/balance", ox + 1327, oy + 112, 180, 50, 1);
        g.img("icons/diamond", ox + 1342, oy + 121, 29, 29, 1);
        g.fit(G.n(St.held), ox + 1384, oy + 137, 24, 0, G.TEXT, 108);
        g.img(down.equals("close") ? "shop/close_pressed" : "shop/close_normal", ox + 1540, oy + 113, 48, 48, 1);
        for (int i = 0; i < TABS.length; i++) {
            double x = ox + 329 + i * 162;
            g.img(i == tab ? "shop/tab_selected" : "shop/tab_normal", x, oy + 209, 150, 56, 1);
            g.text(TABS[i], x + 75, oy + 236, 23, 1, G.TEXT);
        }
        if (tab == 0) drawBuy(g, mx, my);
        else if (tab == 1) drawSell(g, mx, my);
        else drawSubmit(g);
    }

    private void category(G g, int i, String label, boolean on) {
        double y = oy + 309 + i * 73;
        g.img(on ? "shop/category_selected" : "shop/category_normal", ox + 329, y, 197, 60, 1);
        g.fit(label, ox + 427, y + 29, 22, 1, G.TEXT, 170);
    }

    private double cardX(int v) { return ox + 544 + (v % 4) * 259; }
    private double cardY(int v) { return oy + 308 + (v / 4) * 169; }

    /** 상품·품목 한 칸 (252x126): 왼쪽 그림 64, 오른쪽 윗줄 이름, 아랫줄 다이아 아이콘 + 값 */
    private void card(G g, int v, String state, String art, String mcId, String name, String price) {
        double x = cardX(v), y = cardY(v);
        g.img("shop/compact_card_" + state, x, y, 252, 126, 1);
        if (!art.isEmpty()) g.img(art, x + 13, y + 29, 64, 64, 1); else g.item(mcId, x + 13, y + 29, 64);
        int c = state.equals("unavailable") ? G.OFF : G.TEXT;
        g.fit(name, x + 100, y + 47, 18, 0, c, 140);
        g.img("icons/diamond", x + 100, y + 72, 22, 22, 1);
        g.fit(price, x + 130, y + 83, 18, 0, c, 110);
    }

    private void scrollbar(G g, int count) {
        int rows = (count + 3) / 4;
        if (rows <= 3) return;
        g.img("shop/scroll_track", ox + 1590, oy + 305, 8, 466, 1);
        double h = 466 * 3.0 / rows;
        g.img("shop/scroll_thumb", ox + 1590, oy + 305 + (466 - h) * scroll / (double) maxScroll(count), 8, h, 1);
    }

    private void button(G g, String kind, String id, String label, boolean enabled) {
        g.img("shop/" + kind + (down.equals(id) ? "_pressed" : "_normal"), ox + 1391, oy + 899, 176, 52, enabled ? 1 : 0.4);
        g.text(label, ox + 1479, oy + 924, 23, 1, enabled ? G.DARK : G.DIM);
    }

    private void stepper(G g, String number) {
        g.img(down.equals("minus") ? "shop/minus_pressed" : "shop/minus_normal", ox + 1096, oy + 899, 54, 52, 1);
        g.fit(number, ox + 1180, oy + 923, 22, 1, G.TEXT, 50);
        g.img(down.equals("plus") ? "shop/plus_pressed" : "shop/plus_normal", ox + 1210, oy + 899, 54, 52, 1);
    }

    private void drawBuy(G g, double mx, double my) {
        for (int i = 0; i < St.cats.size() && i < 7; i++) category(g, i, St.cats.get(i).label(), St.cats.get(i).id().equals(cat));
        List<St.Item> list = shown();
        scroll = Math.min(scroll, maxScroll(list.size()));
        if (list.isEmpty()) g.text("상품이 없습니다", ox + 1058, oy + 540, 22, 1, G.DIM);
        for (int v = 0; v < 12; v++) {
            int idx = scroll * 4 + v;
            if (idx >= list.size()) break;
            St.Item it = list.get(idx);
            boolean on = it.id().equals(sel) || in(mx, my, cardX(v), cardY(v), 252, 126);
            String state = St.held < it.price() ? "unavailable" : on ? "hover" : "normal";
            if (it.id().equals(sel) && St.held < it.price()) state = "hover";       // 고른 칸은 못 사는 것이어도 고른 표시
            card(g, v, state, it.art(), it.icon(), it.amount() > 1 ? it.name() + " ×" + it.amount() : it.name(), G.n(it.price()));
        }
        scrollbar(g, list.size());

        St.Item s = selected();
        if (s == null) {
            g.text("살 물건을 고르세요", ox + 339, oy + 924, 24, 0, G.DIM);
            button(g, "buy", "buy", "구매", false);
        } else {
            if (!s.art().isEmpty()) g.img(s.art(), ox + 339, oy + 898, 52, 52, 1); else g.item(s.icon(), ox + 339, oy + 898, 52);
            long total = s.price() * qty;
            g.fit((s.amount() > 1 ? s.name() + " ×" + s.amount() : s.name()) + "  ·  합계 " + G.n(total), ox + 407, oy + 924, 24, 0,
                    St.held >= total ? G.TEXT : G.WARN, 660);
            stepper(g, String.valueOf(qty));
            button(g, "buy", "buy", "구매", St.held >= total);
        }
    }

    private void drawSell(G g, double mx, double my) {
        category(g, 0, "광물", true);
        List<Holding> list = holdings();
        scroll = Math.min(scroll, maxScroll(list.size()));
        if (list.isEmpty()) {
            g.text("인벤토리에 팔 수 있는 광물이 없습니다", ox + 1058, oy + 520, 22, 1, G.DIM);
            g.text("석탄 · 철 · 구리 · 금 · 레드스톤 · 청금석 · 에메랄드", ox + 1058, oy + 560, 18, 1, G.OFF);
            button(g, "sell", "sellall", "전부 판매", false);
            return;
        }
        double sum = 0;
        for (Holding h : list) sum += h.value();
        for (int v = 0; v < 12; v++) {
            int idx = scroll * 4 + v;
            if (idx >= list.size()) break;
            Holding h = list.get(idx);
            card(g, v, in(mx, my, cardX(v), cardY(v), 252, 126) ? "hover" : "normal", "", h.sell().mat(),
                    h.sell().name() + " ×" + h.count(), "+" + String.format("%.2f", h.value()));
        }
        scrollbar(g, list.size());
        g.img("icons/diamond", ox + 339, oy + 906, 36, 36, 1);
        g.fit("예상 +" + String.format("%.2f", sum) + " · 칸을 누르면 한 종류만 판매", ox + 392, oy + 924, 22, 0, G.TEXT, 900);
        button(g, "sell", "sellall", "전부 판매", true);
    }

    private void drawSubmit(G g) {
        g.img("hud/goal_wallet_panel", ox + 750, oy + 330, 420, 120, 1);
        g.text("남은 목표", ox + 857, oy + 372, 20, 1, G.DIM);
        g.fit(St.done ? "달성" : G.n(St.goal), ox + 857, oy + 410, 30, 1, G.TEXT, 170);
        g.text("보유 다이아", ox + 1063, oy + 372, 20, 1, G.DIM);
        g.fit(G.n(St.held), ox + 1063, oy + 410, 30, 1, G.TEXT, 170);
        g.img("hud/goal_progress_empty", ox + 770, oy + 480, 380, 24, 1);
        g.imgClip("hud/goal_progress_fill", ox + 770, oy + 480, 380, 24, 0, St.goal <= 0 ? 1 : Math.min(1, St.held / (double) St.goal));
        long max = submitMax();
        String msg = St.goal <= 0 ? "이미 목표를 모두 채웠습니다" : St.held <= 0 ? "제출할 보유 다이아가 없습니다" : "보유 다이아를 내면 남은 목표가 줄어듭니다";
        g.text(msg, ox + 960, oy + 550, 22, 1, G.DIM);
        g.img("icons/diamond", ox + 339, oy + 898, 52, 52, 1);
        g.text("제출할 다이아", ox + 407, oy + 924, 24, 0, G.TEXT);
        stepper(g, G.n(submitNow()));
        button(g, "buy", "submit", "제출", max > 0);
    }

    // ── 입력 ───────────────────────────────────────────────
    /** 마우스를 눌렀을 때. shift = 수량을 10씩 */
    public void mouseDown(double mx, double my, boolean shift) {
        if (in(mx, my, ox + 1540, oy + 113, 48, 48)) { down = "close"; host.clickSound(); host.close(); return; }
        for (int i = 0; i < TABS.length; i++)
            if (in(mx, my, ox + 329 + i * 162, oy + 209, 150, 56)) { tab = i; scroll = 0; host.clickSound(); return; }
        int step = shift ? 10 : 1;
        boolean minus = in(mx, my, ox + 1096, oy + 899, 54, 52), plus = in(mx, my, ox + 1210, oy + 899, 54, 52);
        boolean action = in(mx, my, ox + 1391, oy + 899, 176, 52);

        if (tab == 0) {
            for (int i = 0; i < St.cats.size() && i < 7; i++)
                if (in(mx, my, ox + 329, oy + 309 + i * 73, 197, 60)) { cat = St.cats.get(i).id(); scroll = 0; host.clickSound(); return; }
            List<St.Item> list = shown();
            for (int v = 0; v < 12; v++) {
                int idx = scroll * 4 + v;
                if (idx >= list.size()) break;
                if (in(mx, my, cardX(v), cardY(v), 252, 126)) {
                    String id = list.get(idx).id();
                    if (!id.equals(sel)) { sel = id; qty = 1; }
                    host.clickSound();
                    return;
                }
            }
            St.Item s = selected();
            if (s == null) return;
            if (minus) { down = "minus"; qty = Math.max(1, qty - step); host.clickSound(); }
            else if (plus) { down = "plus"; qty = Math.min(64, qty + step); host.clickSound(); }
            else if (action) { down = "buy"; host.clickSound(); host.buy(s.id(), qty); }
        } else if (tab == 1) {
            List<Holding> list = holdings();
            if (action && !list.isEmpty()) { down = "sellall"; host.clickSound(); host.sell("all"); return; }
            for (int v = 0; v < 12; v++) {
                int idx = scroll * 4 + v;
                if (idx >= list.size()) break;
                if (in(mx, my, cardX(v), cardY(v), 252, 126)) { host.clickSound(); host.sell(list.get(idx).sell().mat()); return; }
            }
        } else {
            long max = submitMax();
            if (minus) { down = "minus"; submitQty = Math.max(Math.min(1, max), submitNow() - step); host.clickSound(); }
            else if (plus) { down = "plus"; submitQty = Math.min(max, submitNow() + step); host.clickSound(); }
            else if (action && max > 0) { down = "submit"; host.clickSound(); host.submit(submitNow()); submitQty = -1; }
        }
    }

    public void mouseUp() { down = ""; }

    /** 휠: dir > 0 이면 위로 */
    public void scroll(double dir) {
        int count = tab == 0 ? shown().size() : tab == 1 ? holdings().size() : 0;
        scroll = Math.max(0, Math.min(maxScroll(count), scroll - (int) Math.signum(dir)));
    }

    // ── 검수용 (미리보기에서 화면 상태를 정해 놓고 그린다) ─────────────────────
    public void preset(int tab, String cat, String sel, int qty) { this.tab = tab; this.cat = cat; this.sel = sel; this.qty = qty; }
}
