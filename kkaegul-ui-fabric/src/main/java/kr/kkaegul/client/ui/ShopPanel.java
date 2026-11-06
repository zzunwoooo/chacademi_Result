package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 깨굴 상점 (GPT 시안 layout 1600x1024 좌표). 왼쪽 메뉴: 코인 구매 / 판매.
 *  - 코인 구매: 다이아로 깨굴코인. shop.buy amount=N → 서버가 실패/정상/2배/3배 결정 → fx coin_buy
 *  - 판매: 아이템 1개당 다이아(소수). shop.sell id=.. amount=N / 전부 판매 shop.sellAll → fx shop_sold
 */
public final class ShopPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private long recvAt;
    private String tab;
    private int filter;                 // 0 전체 1 광물 2 작물
    private String selected;
    private long sellAmt = -1, coinAmt = 1;
    private String typed;               // 수량 칸에 직접 치는 중
    private String search;              // null = 검색 꺼짐
    private int scrollRow;
    private String pressed;
    private long pressedAt;
    private int mods;
    private long waitingSince;
    private String fxId;
    private JsonObject fx;
    private long fxStart;
    private final long opened;
    private record R(String key, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private float k = 1, ox, oy;
    private static final float DW = 1600, DH = 1000;
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final long COIN_FX = 3000, SELL_FX = 2200;

    public ShopPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.tab = J.str(this.data, "tab", "coin");
        this.opened = now;
        this.recvAt = now;
    }

    @Override public String screen() { return "shop"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        recvAt = now;
        if (d.has("tab")) tab = J.str(d, "tab", tab);
        waitingSince = 0;
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        if (!id.equals("coin_buy") && !id.equals("shop_sold")) return false;
        fxId = id;
        fx = d;
        fxStart = now;
        waitingSince = 0;
        typed = null;
        if (id.equals("coin_buy")) {
            String o = J.str(d, "outcome");
            host.sound(o.equals("fail") ? "minecraft:block.note_block.bass" : o.equals("triple") ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.player.levelup", o.equals("fail") ? .6f : 1.2f, .7f);
        } else {
            sellAmt = -1;
            host.sound("minecraft:entity.experience_orb.pickup", 1.1f, .8f);
        }
        return true;
    }

    @Override public void modifiers(int m) { mods = m; }

    private long step() { return (mods & 2) != 0 ? 100 : (mods & 1) != 0 ? 10 : 1; }

    // ───────── 데이터 ─────────
    private List<JsonObject> items() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : J.objs(data, "sell")) {
            boolean farm = J.bool(o, "farm");
            if (filter == 1 && farm || filter == 2 && !farm) continue;
            if (search != null && !search.isEmpty() && !J.str(o, "name").contains(search)) continue;
            out.add(o);
        }
        return out;
    }

    private JsonObject sel(List<JsonObject> all) {
        for (JsonObject o : J.objs(data, "sell")) if (J.str(o, "id").equals(selected)) return o;
        JsonObject first = null;
        for (JsonObject o : all) {
            if (first == null) first = o;
            if (J.num(o, "have") > 0) { selected = J.str(o, "id"); return o; }
        }
        if (first != null) selected = J.str(first, "id");
        return first;
    }

    private long avail() { return J.num(data, "available", J.num(data, "held")); }
    private long unit() { return Math.max(1, J.num(data, "coinPrice", 1)); }
    private long coinMax() { return Math.max(0, Math.min(J.num(data, "coinMax", 100), avail() / unit())); }

    private long typedOr(long v, long max) {
        if (typed == null) return v;
        try { return Math.min(max, Math.max(0, Long.parseLong(typed.isEmpty() ? "0" : typed))); } catch (NumberFormatException e) { return 0; }
    }

    private long sellAmount(JsonObject s) {
        long max = s == null ? 0 : J.num(s, "have");
        return typedOr(sellAmt < 0 ? max : Math.min(sellAmt, max), max);
    }

    private long coinAmount() { return typedOr(Math.max(1, coinAmt), Math.max(1, J.num(data, "coinMax", 100))); }

    /** 0.08 / 1.5 / 12 */
    static String dec(double v) {
        java.math.BigDecimal b = new java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        String s = b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
        int dot = s.indexOf('.');
        String ip = dot < 0 ? s : s.substring(0, dot);
        try { ip = Fmt.num(Long.parseLong(ip)); } catch (NumberFormatException ignored) { }
        return dot < 0 ? ip : ip + s.substring(dot);
    }

    private boolean busy(long now) {
        if (waitingSince > 0 && now - waitingSince > 5000) waitingSince = 0;
        return waitingSince > 0 || fxId != null && fxId.equals("coin_buy") && now - fxStart < COIN_FX;
    }

    private void press(String key, long now) { pressed = key; pressedAt = now; }
    private boolean isPressed(String key, long now) { return key.equals(pressed) && now - pressedAt < 160; }

    // ───────── 그리기 ─────────
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x77000000);
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .9f / DW, H * .94f / DH) * pop;
        ox = W / 2 - 800 * k;
        oy = H / 2 - 490 * k;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;

        c.image("shop/window_frame", 0, 0, 1600, 1024, 0xFFFFFFFF);
        c.image("shop/header_frog_ribbon", 544, 0, 512, 176, 0xFFFFFFFF);
        c.textFit("깨굴 상점", 800, 119, 46, 360, Theme.INK, Canvas.CENTER, TXT);
        boolean hClose = Anim.in(mx, my, 1493, 110, 60, 60);
        c.image("shop/close", 1503, 120, 40, 40, hClose ? 0xFFFFB9A8 : 0xFFFFFFFF);
        hits.add(new R("close", 1493, 110, 60, 60));

        // 왼쪽 메뉴
        c.text("메뉴", 64, 220, 22, Theme.MUTED, Canvas.LEFT, TXT);
        menu(c, "coin", 262, "shop/coin_48", "코인 구매", mx, my);
        menu(c, "sell", 342, "shop/sell_tab_pickaxe", "판매", mx, my);
        c.text("보유", 64, 800, 22, Theme.MUTED, Canvas.LEFT, TXT);
        c.image("shop/diamond_32", 64, 828, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(avail()), 110, 845, 28, 170, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/coin_32", 64, 876, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(J.num(data, "coins")), 110, 893, 28, 170, Theme.INK, Canvas.LEFT, TXT);
        double dust = J.dbl(data, "dust", 0);
        if (dust > 0.0005) c.textFit("모은 소수점 " + dec(dust), 64, 930, 17, 210, Theme.MUTED, Canvas.LEFT, TXT);

        if (tab.equals("sell")) sell(c, now, mx, my); else coin(c, now, mx, my);
        c.pop();
    }

    private void menu(Canvas c, String key, float y, String icon, String label, float mx, float my) {
        boolean on = tab.equals(key), hov = !on && Anim.in(mx, my, 42, y, 248, 72);
        if (on || hov) c.image("shop/menu_selected", 42, y, 248, 72, on ? 0xFFFFFFFF : 0x66FFFFFF);
        c.image(icon, 64, y + 10, 48, 48, 0xFFFFFFFF);
        c.textFit(label, 126, y + 35, 28, 150, on ? Theme.INK : 0xFF6B5A4C, Canvas.LEFT, TXT);
        hits.add(new R("tab:" + key, 42, y, 248, 72));
    }

    /** 오른쪽 정렬 값 + 앞에 아이콘 */
    private void value(Canvas c, String icon, String v, float y, int col) {
        float tw = c.textWidth(v, 26, TXT);
        if (tw > 150) tw = 150;
        c.textFit(v, 1526, y, 26, 150, col, Canvas.RIGHT, TXT);
        if (icon != null) c.image(icon, 1526 - tw - 40, y - 16, 32, 32, 0xFFFFFFFF);
    }

    private void stepper(Canvas c, long now, float y, long amount, float mx, float my) {
        String img = isPressed("minus", now) ? "shop/stepper_left_pressed" : isPressed("plus", now) ? "shop/stepper_right_pressed" : "shop/stepper_default";
        c.image(img, 1150, y, 260, 56, 0xFFFFFFFF);
        c.image("shop/minus", 1167, y + 15, 24, 24, 0xFFFFFFFF);
        c.image("shop/plus", 1370, y + 15, 24, 24, 0xFFFFFFFF);
        String shown = typed != null ? (typed.isEmpty() ? "0" : typed) : Fmt.num(amount);
        c.textFit(shown, 1280, y + 28, 28, 130, Theme.INK, Canvas.CENTER, TXT);
        if (typed != null && (now / 530) % 2 == 0) {
            float tw = Math.min(130, c.textWidth(shown, 28, TXT));
            c.fill(1280 + tw / 2 + 3, y + 13, 3, 30, 1.5f, 0xFF7CB342);
        }
        hits.add(new R("minus", 1150, y, 58, 56));
        hits.add(new R("input", 1208, y, 144, 56));
        hits.add(new R("plus", 1352, y, 58, 56));
        boolean hMax = Anim.in(mx, my, 1434, y, 92, 54);
        c.image(isPressed("max", now) || hMax ? "shop/small_outline_pressed" : "shop/small_outline_default", 1434, y, 92, 54, 0xFFFFFFFF);
        c.text("최대", 1480, y + 27, 22, Theme.INK, Canvas.CENTER, TXT);
        hits.add(new R("max", 1434, y, 92, 54));
    }

    private void bigButton(Canvas c, String kind, String key, float y, String label, boolean enabled, float mx, float my, int ink) {
        boolean hov = enabled && Anim.in(mx, my, 1112, y, 424, 68);
        c.image("shop/" + kind + (enabled ? hov ? "_hover" : "_default" : "_disabled"), 1112, y, 424, 68, 0xFFFFFFFF);
        c.textFit(label, 1324, y + 34, 30, 380, enabled ? ink : 0xFF9A8E80, Canvas.CENTER, TXT);
        hits.add(new R(key, 1112, y, 424, 68));
    }

    // ───────── 판매 탭 ─────────
    private void sell(Canvas c, long now, float mx, float my) {
        c.text("판매 아이템", 330, 216, 30, Theme.INK, Canvas.LEFT, TXT);
        String fimg = filter == 0 ? "shop/filter_left_selected" : filter == 1 ? "shop/filter_center_selected" : "shop/filter_right_selected";
        c.image(fimg, 324, 254, 420, 54, 0xFFFFFFFF);
        String[] fl = {"전체", "광물", "작물"};
        for (int i = 0; i < 3; i++) {
            c.text(fl[i], 394 + i * 140, 281, 24, filter == i ? Theme.GREEN_DARK : 0xFF6B5A4C, Canvas.CENTER, TXT);
            hits.add(new R("filter:" + i, 324 + i * 140, 254, 140, 54));
        }
        // 검색
        if (search != null) {
            MenuPanel.box(c, 770, 256, 218, 50, 25, 0xFFC9B79F, 0xFFFFFDF7, 2);
            String q = search.isEmpty() ? "§7이름 검색" : search;
            c.textFit(q, 790, 281, 22, 180, Theme.INK, Canvas.LEFT, TXT);
            if (!search.isEmpty() || (now / 530) % 2 == 0) {
                float tw = search.isEmpty() ? 0 : Math.min(180, c.textWidth(search, 22, TXT));
                if ((now / 530) % 2 == 0) c.fill(792 + tw, 267, 2.5f, 28, 1, 0xFF7CB342);
            }
        }
        boolean hS = Anim.in(mx, my, 990, 254, 56, 56);
        c.image("shop/search", 998, 262, 40, 40, hS || search != null ? 0xFFB9E39A : 0xFFFFFFFF);
        hits.add(new R("search", 990, 254, 56, 56));

        List<JsonObject> all = items();
        JsonObject s = sel(all);
        int rows = (all.size() + 2) / 3;
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, rows - 2)));
        if (all.isEmpty()) c.text(search != null ? "찾는 아이템이 없어요" : "팔 수 있는 아이템이 없어요", 688, 600, 26, Theme.MUTED, Canvas.CENTER, TXT);
        for (int r = 0; r < 2; r++)
            for (int col = 0; col < 3; col++) {
                int i = (scrollRow + r) * 3 + col;
                if (i >= all.size()) continue;
                card(c, all.get(i), new float[]{324, 574, 820}[col], 334 + r * 278, mx, my);
            }
        if (rows > 2) { // 스크롤 막대
            float th = 538f * 2 / rows, ty = 334 + (538 - th) * scrollRow / (rows - 2);
            c.fill(1056, 334, 6, 538, 3, 0x22000000);
            c.fill(1056, ty, 6, th, 3, 0xFF9CCB6C);
        }
        // 시세 갱신
        long left = Math.max(0, J.num(data, "farmNextMs") - (now - recvAt)) / 1000;
        c.image("shop/hourglass", 328, 900, 32, 32, 0xFFFFFFFF);
        c.text("시세 갱신 " + String.format("%02d:%02d", left / 60, left % 60), 372, 917, 24, Theme.INK, Canvas.LEFT, TXT);

        // 오른쪽: 선택한 아이템
        c.image("shop/transaction", 1088, 196, 470, 750, 0xFFFFFFFF);
        c.text("선택한 아이템", 1116, 228, 26, Theme.INK, Canvas.LEFT, TXT);
        long amt = sellAmount(s);
        double price = s == null ? 0 : J.dbl(s, "price", 0);
        if (s != null) {
            float bob = (float) Math.sin(now / 420.0) * 3;
            c.item(J.str(s, "id"), 1250, 250 + bob, 128);
            c.textFit(J.str(s, "name"), 1314, 398, 30, 400, Theme.INK, Canvas.CENTER, TXT);
            trend(c, s, 1314, 431);
        } else c.text("아이템을 골라 주세요", 1314, 330, 24, Theme.MUTED, Canvas.CENTER, TXT);
        c.image("shop/divider_horizontal", 1112, 460, 416, 2, 0xFFFFFFFF);
        c.text("보유 수량", 1116, 488, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, null, Fmt.num(s == null ? 0 : J.num(s, "have")) + "개", 488, Theme.INK);
        c.text("개당 가격", 1116, 533, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", dec(price), 533, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 564, 416, 2, 0xFFFFFFFF);
        c.text("판매 수량", 1116, 592, 24, Theme.INK, Canvas.LEFT, TXT);
        stepper(c, now, 620, amt, mx, my);
        c.image("shop/divider_horizontal", 1112, 700, 416, 2, 0xFFFFFFFF);
        c.text("받을 다이아", 1116, 731, 24, Theme.INK, Canvas.LEFT, TXT);
        double val = price * amt;
        value(c, "shop/diamond_32", dec(val), 731, Theme.INK);
        long got = (long) Math.floor(J.dbl(data, "dust", 0) + val + 1e-9);
        if (amt > 0 && Math.abs(val - Math.rint(val)) > 1e-6)
            c.textFit("바로 +" + Fmt.num(got) + "개 · 소수점은 모아뒀다가 1개가 되면 지급", 1324, 762, 17, 410, Theme.MUTED, Canvas.CENTER, TXT);
        boolean b = busy(now);
        bigButton(c, "sales", "sell", 782, b ? "판매 중..." : "판매하기", s != null && amt > 0 && !b, mx, my, 0xFF2F4A1C);
        bigButton(c, "secondary", "sellAll", 862, "전부 판매", !b && !J.objs(data, "sell").isEmpty(), mx, my, Theme.INK);

        // 판매 결과: 다이아가 위로 튀어 오름 + 문구
        if ("shop_sold".equals(fxId) && now - fxStart < SELL_FX) {
            float t = Anim.t(now, fxStart, SELL_FX), a = Anim.fade(now, fxStart, SELL_FX, 120, 500);
            for (int i = 0; i < 8; i++) {
                float tt = Anim.clamp(t * 1.8f - i * .05f);
                if (tt <= 0 || tt >= 1) continue;
                double ang = i * Math.PI / 4 + .4;
                float r = 30 + 110 * Anim.outCubic(tt);
                c.imageAt("shop/diamond_48", 1314 + (float) Math.cos(ang) * r, 330 + (float) Math.sin(ang) * r * .6f, 36, 1, i * 20, Anim.white(a * (1 - tt)));
            }
            float p = Anim.outBack(Anim.t(now, fxStart, 320));
            c.push(); c.translate(1324, 330); c.scale(p, p);
            String msg = "다이아 +" + Fmt.num(J.num(fx, "got"));
            float tw = c.textWidth(msg, 30, TXT) + 56;
            MenuPanel.box(c, -tw / 2, -30, tw, 60, 30, Anim.alpha(0xFF6FAF3F, a), Anim.alpha(0xFFF4FBE8, a), 3);
            c.text(msg, 0, 0, 30, Anim.alpha(Theme.GREEN_DARK, a), Canvas.CENTER, TXT);
            c.pop();
        }
    }

    private void trend(Canvas c, JsonObject s, float cx, float y) {
        int t = (int) J.num(s, "trend");
        boolean farm = J.bool(s, "farm");
        String label = !farm ? "고정 시세" : t > 0 ? "상승" : t < 0 ? "하락" : "변동 없음";
        int col = !farm || t == 0 ? Theme.MUTED : t > 0 ? Theme.GREEN_DARK : Theme.RED;
        float tw = c.textWidth(label, 24, TXT), iw = farm && t != 0 ? 30 : 0;
        float x0 = cx - (tw + iw) / 2;
        if (iw > 0) c.image(t > 0 ? "shop/up" : "shop/down", x0, y - 12, 24, 24, 0xFFFFFFFF);
        c.text(label, x0 + iw, y, 24, col, Canvas.LEFT, TXT);
    }

    private void card(Canvas c, JsonObject o, float x, float y, float mx, float my) {
        String id = J.str(o, "id");
        boolean on = id.equals(selected), hov = !on && Anim.in(mx, my, x, y, 232, 260);
        c.image(on ? "shop/item_selected" : hov ? "shop/item_hover" : "shop/item_default", x, y, 232, 260, 0xFFFFFFFF);
        if (on) c.image("shop/check", x + 186, y + 4, 36, 36, 0xFFFFFFFF);
        float lift = hov ? -3 : 0;
        c.item(id, x + 60, y + 24 + lift, 112);
        c.textFit(J.str(o, "name"), x + 116, y + 146, 28, 200, Theme.INK, Canvas.CENTER, TXT);
        String p = dec(J.dbl(o, "price", 0));
        int t = (int) J.num(o, "trend");
        float tw = Math.min(120, c.textWidth(p, 26, TXT)), aw = J.bool(o, "farm") && t != 0 ? 30 : 0;
        float sx = x + 116 - (30 + tw + aw) / 2;
        c.image("shop/diamond_24", sx, y + 172, 24, 24, 0xFFFFFFFF);
        c.textFit(p, sx + 30, y + 185, 26, 120, Theme.INK, Canvas.LEFT, TXT);
        if (aw > 0) c.image(t > 0 ? "shop/up" : "shop/down", sx + 30 + tw + 6, y + 172, 24, 24, 0xFFFFFFFF);
        c.image("shop/divider_horizontal", x + 24, y + 210, 184, 1.5f, 0xFFFFFFFF);
        long have = J.num(o, "have");
        c.textFit("보유 " + Fmt.num(have), x + 116, y + 233, 24, 200, have > 0 ? Theme.INK : Theme.MUTED, Canvas.CENTER, TXT);
        hits.add(new R("item:" + id, x, y, 232, 260));
    }

    // ───────── 코인 구매 탭 ─────────
    private void coin(Canvas c, long now, float mx, float my) {
        c.text("코인 구매", 320, 224, 34, Theme.INK, Canvas.LEFT, TXT);
        boolean hInfo = Anim.in(mx, my, 868, 206, 172, 50);
        c.image(hInfo ? "shop/info_pressed" : "shop/info_default", 868, 206, 172, 50, 0xFFFFFFFF);
        c.image("shop/info", 884, 215, 32, 32, 0xFFFFFFFF);
        c.text("확률 안내", 922, 231, 22, Theme.INK, Canvas.LEFT, TXT);

        c.image("shop/product_display", 322, 282, 720, 614, 0xFFFFFFFF);
        c.text("깨굴코인", 682, 340, 46, Theme.INK, Canvas.CENTER, TXT);
        c.text("다이아로 구매하는 코인", 682, 396, 26, Theme.MUTED, Canvas.CENTER, TXT);
        float bob = (float) Math.sin(now / 480.0) * 6;
        c.image("shop/coin_256", 552, 438 + bob, 256, 256, 0xFFFFFFFF);
        c.image("shop/divider_horizontal", 380, 716, 604, 2, 0xFFFFFFFF);
        c.text("기준 교환량", 682, 760, 24, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/exchange_strip", 440, 790, 488, 72, 0xFFFFFFFF);
        c.image("shop/diamond_32", 504, 810, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(unit()), 548, 827, 34, 110, Theme.INK, Canvas.LEFT, TXT);
        c.text("→", 684, 825, 34, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/coin_32", 776, 810, 32, 32, 0xFFFFFFFF);
        c.text("1", 820, 827, 34, Theme.INK, Canvas.LEFT, TXT);
        c.text("지급량은 구매 결과에 따라 달라집니다", 682, 918, 22, Theme.MUTED, Canvas.CENTER, TXT);

        // 오른쪽: 구매 정보
        c.image("shop/transaction", 1088, 196, 470, 750, 0xFFFFFFFF);
        c.text("구매 정보", 1116, 228, 26, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/coin_128", 1250, 258, 128, 128, 0xFFFFFFFF);
        c.text("깨굴코인", 1314, 405, 28, Theme.INK, Canvas.CENTER, TXT);
        c.text("1회 비용", 1116, 455, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(unit()), 455, Theme.INK);
        c.text("기준 수량", 1116, 501, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/coin_32", "1", 501, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 530, 416, 2, 0xFFFFFFFF);
        c.text("구매 수량", 1116, 559, 24, Theme.INK, Canvas.LEFT, TXT);
        long n = coinAmount();
        stepper(c, now, 588, n, mx, my);
        c.image("shop/divider_horizontal", 1112, 660, 416, 2, 0xFFFFFFFF);
        long cost = n * unit(), after = avail() - cost;
        c.text("필요 다이아", 1116, 690, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(cost), 690, Theme.INK);
        c.text("구매 후 잔액", 1116, 737, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(after), 737, after < 0 ? Theme.RED : Theme.INK);
        boolean b = busy(now), ok = n > 0 && after >= 0 && !b;
        bigButton(c, "purchase", "buy", 782, b ? "구매 중..." : after < 0 ? "다이아가 부족해요" : "구매하기", ok, mx, my, 0xFF5A3A12);
        String last = fx != null && "coin_buy".equals(fxId) ? J.str(fx, "message") : "";
        c.textFit(last.isEmpty() ? "결과는 구매 후 표시됩니다" : last, 1324, 882, 22, 410, last.isEmpty() ? Theme.MUTED : Theme.INK, Canvas.CENTER, TXT);

        if ("coin_buy".equals(fxId) && now - fxStart < COIN_FX) coinResult(c, now);
        else if (hInfo) odds(c);
    }

    private void odds(Canvas c) {
        List<JsonObject> os = J.objs(data, "outcomes");
        if (os.isEmpty()) return;
        float w = 330, h = 62 + os.size() * 40, x = 1040 - w, y = 266;
        c.fill(x + 3, y + 5, w, h, 16, 0x33000000);
        MenuPanel.box(c, x, y, w, h, 16, 0xFFC9B79F, 0xFFFFFCF4, 3);
        c.text("구매 1회당 결과 확률", x + 22, y + 30, 22, Theme.INK, Canvas.LEFT, TXT);
        float ry = y + 72;
        for (JsonObject o : os) {
            long m = J.num(o, "multiplier", 1);
            String name = m <= 0 ? "실패 (코인 없음)" : m == 1 ? "정상 구매" : m + "배 구매";
            int col = m <= 0 ? Theme.RED : m >= 3 ? Theme.GOLD : m == 2 ? Theme.GREEN_DARK : Theme.INK;
            c.text(name, x + 22, ry, 22, col, Canvas.LEFT, TXT);
            c.text(dec(J.dbl(o, "chance", 0)) + "%", x + w - 22, ry, 22, col, Canvas.RIGHT, TXT);
            ry += 40;
        }
    }

    /** 코인 구매 결과: 다이아 → 금화, 깨굴이 + 결과 리본 (가운데 상품 칸 위) */
    private void coinResult(Canvas c, long now) {
        String o = J.str(fx, "outcome");
        boolean fail = o.equals("fail");
        long e = now - fxStart;
        float a = Anim.fade(now, fxStart, COIN_FX, 150, 450);
        c.fill(330, 290, 704, 598, 26, Anim.alpha(0xFFFFF8EC, a));
        // 1) 다이아 → 금화 (0~700ms)
        if (e < 900) {
            float t = Anim.t(now, fxStart, 700);
            float s = .5f + .5f * Anim.outBack(t);
            c.imageAt(fail ? "shop/diamond_48" : "shop/diamond_to_coin", 682, 560 - 30 * t, fail ? 90 : 256, s, fail ? Anim.shake(e, 8, 12) : 0, Anim.white(a * (1 - Anim.t(now, fxStart + 600, 300))));
        }
        // 2) 깨굴이 + 리본
        float t2 = Anim.t(now, fxStart + 500, 380);
        if (t2 <= 0) return;
        float s2 = Anim.outBack(t2);
        String frog = fail ? "shop/frog_sheepish" : "shop/frog_pouch";
        float fb = (float) Math.sin(now / 160.0) * 4;
        c.imageAt(frog, 682, 560 + fb, 300 * s2, 1, 0, Anim.white(a));
        String banner = switch (o) { case "fail" -> "shop/banner_fail"; case "double" -> "shop/banner_double"; case "triple" -> "shop/banner_triple"; default -> "shop/banner_success"; };
        String title = switch (o) { case "fail" -> "구매 실패..."; case "double" -> "2배 구매!"; case "triple" -> "3배 구매!!"; default -> "구매 완료!"; };
        long got = J.num(fx, "got");
        c.push();
        c.translate(682, 770);
        c.scale(.86f * s2, .86f * s2);
        c.image(banner, -350, -80, 700, 160, Anim.white(a));
        int ink = fail ? 0xFF6B6B6B : o.equals("triple") ? 0xFF8A5A00 : o.equals("double") ? 0xFF3F7F31 : Theme.INK;
        c.textFit(title, 0, -30, 48, 520, Anim.alpha(ink, a), Canvas.CENTER, TXT);
        c.textFit(fail ? "다이아만 날아갔어요" : "깨굴코인 +" + Fmt.num(got), 0, 22, 32, 520, Anim.alpha(fail ? Theme.MUTED : Theme.INK, a), Canvas.CENTER, TXT);
        c.pop();
        if (o.equals("triple") || o.equals("double")) { // 반짝이 금화
            for (int i = 0; i < 8; i++) {
                float tt = Anim.clamp((e - 600 - i * 70) / 900f);
                if (tt <= 0 || tt >= 1) continue;
                double ang = i * 0.785 + .3;
                float r = 120 + 160 * Anim.outCubic(tt);
                c.imageAt("shop/coin_48", 682 + (float) Math.cos(ang) * r, 540 + (float) Math.sin(ang) * r * .7f, 44, 1, i * 25, Anim.white(a * (1 - tt)));
            }
        }
    }

    // ───────── 입력 ─────────
    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        if ("coin_buy".equals(fxId) && now - fxStart < COIN_FX && now - fxStart > 600 && Anim.in(mx, my, 322, 282, 720, 614)) {
            fxStart = now - COIN_FX; // 클릭하면 결과 넘기기
            return true;
        }
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            String key = r.key();
            if (!key.equals("input")) commitTyped();
            if (key.startsWith("tab:")) {
                String t = key.substring(4);
                if (!t.equals(tab)) { tab = t; typed = null; search = null; host.sound("minecraft:ui.button.click", 1.2f, .4f); }
                return true;
            }
            if (key.startsWith("filter:")) { filter = Integer.parseInt(key.substring(7)); scrollRow = 0; host.sound("minecraft:ui.button.click", 1.3f, .35f); return true; }
            if (key.startsWith("item:")) {
                String id = key.substring(5);
                if (!id.equals(selected)) { selected = id; sellAmt = -1; host.sound("minecraft:ui.button.click", 1.5f, .35f); }
                return true;
            }
            boolean sellTab = tab.equals("sell");
            JsonObject s = sellTab ? sel(items()) : null;
            switch (key) {
                case "close" -> host.closePanel(this);
                case "search" -> { search = search == null ? "" : null; scrollRow = 0; }
                case "input" -> typed = "";
                case "minus" -> {
                    if (sellTab) sellAmt = Math.max(0, sellAmount(s) - step()); else coinAmt = Math.max(1, coinAmount() - step());
                    press("minus", now); host.sound("minecraft:ui.button.click", 1.4f, .35f);
                }
                case "plus" -> {
                    if (sellTab) sellAmt = Math.min(s == null ? 0 : J.num(s, "have"), sellAmount(s) + step());
                    else coinAmt = Math.min(Math.max(1, J.num(data, "coinMax", 100)), coinAmount() + step());
                    press("plus", now); host.sound("minecraft:ui.button.click", 1.5f, .35f);
                }
                case "max" -> {
                    if (sellTab) sellAmt = -1; else coinAmt = Math.max(1, coinMax());
                    press("max", now); host.sound("minecraft:ui.button.click", 1.2f, .35f);
                }
                case "sell" -> {
                    long a = sellAmount(s);
                    if (s == null || a <= 0 || busy(now)) return true;
                    waitingSince = now;
                    host.send("shop.sell", "id", J.str(s, "id"), "amount", String.valueOf(a));
                }
                case "sellAll" -> {
                    if (busy(now)) return true;
                    waitingSince = now;
                    host.send("shop.sellAll");
                }
                case "buy" -> {
                    long n = coinAmount();
                    if (busy(now) || n <= 0 || n * unit() > avail()) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                    waitingSince = now;
                    host.send("shop.buy", "amount", String.valueOf(n));
                }
                default -> { }
            }
            return true;
        }
        commitTyped();
        return false;
    }

    private void commitTyped() {
        if (typed == null) return;
        if (tab.equals("sell")) sellAmt = sellAmount(sel(items())); else coinAmt = Math.max(1, coinAmount());
        typed = null;
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        if (tab.equals("sell") && Anim.in(x, y, 310, 320, 760, 570)) {
            scrollRow += amount < 0 ? 1 : -1;
            return true;
        }
        if (Anim.in(x, y, 1100, 560, 450, 140)) {
            long d = amount > 0 ? 1 : -1;
            if (tab.equals("sell")) { JsonObject s = sel(items()); sellAmt = Math.max(0, Math.min(s == null ? 0 : J.num(s, "have"), sellAmount(s) + d)); }
            else coinAmt = Math.max(1, Math.min(Math.max(1, J.num(data, "coinMax", 100)), coinAmount() + d));
            typed = null;
            return true;
        }
        return false;
    }

    @Override public boolean typed(char ch, long now) {
        if (search != null && tab.equals("sell") && typed == null) {
            if (Character.isISOControl(ch) || search.length() >= 10) return true;
            search += ch;
            scrollRow = 0;
            return true;
        }
        if (ch >= '0' && ch <= '9') {
            if (typed == null) typed = "";
            if (typed.length() < 6) typed = (typed.equals("0") ? "" : typed) + ch;
            return true;
        }
        return false;
    }

    @Override public boolean key(int key, int mods, long now) {
        switch (key) {
            case 259 -> { // BACKSPACE
                if (typed != null) { if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1); return true; }
                if (search != null && !search.isEmpty()) { search = search.substring(0, search.length() - 1); return true; }
                return false;
            }
            case 257, 335 -> { commitTyped(); return true; } // ENTER
            default -> { return false; }
        }
    }
}
