package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 깨굴 상점 (GPT 시안 layout 1600x1024 좌표). 왼쪽 메뉴: 코인 구매 / 코인 판매(ex) / 판매 / 농작물.
 *  - 코인 구매: 다이아로 깨굴코인. shop.buy amount=N → 서버가 실패/정상/2배/3배 결정 → fx coin_buy
 *  - 판매: 아이템 1개당 다이아(소수). shop.sell id=.. amount=N / 전부 판매 shop.sellAll → fx shop_sold
 *  - 농작물(farm): 씨앗·도구 구매 shop.cropBuy id=.. amount=묶음 수 / 작물 판매 shop.cropSell · shop.cropSellAll (데이터 crop.buy · crop.sell)
 */
public final class ShopPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private long recvAt;
    private String tab;
    private int filter;                 // 0 전체 1 광물 2 작물
    private int farmFilter;             // 농작물 탭: 0 씨앗 1 도구 2 판매
    private String selected;
    private long sellAmt = -1, coinAmt = 1, exAmt = 1, buyAmt = 1;
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

    @Override public boolean textInput() { return true; }

    @Override public String screen() { return "shop"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        recvAt = now;
        if (d.has("tab")) tab = J.str(d, "tab", tab);
        waitingSince = 0;
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        if (!id.equals("coin_buy") && !id.equals("shop_sold") && !id.equals("coin_exchange")) return false;
        fxId = id;
        fx = d;
        fxStart = now;
        waitingSince = 0;
        typed = null;
        if (id.equals("coin_buy")) {
            String o = J.str(d, "outcome");
            host.sound(o.equals("fail") ? "minecraft:block.note_block.bass" : o.equals("triple") ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.player.levelup", o.equals("fail") ? .6f : 1.2f, .7f);
        } else {
            if (id.equals("shop_sold")) sellAmt = -1; else exAmt = 1;
            host.sound("minecraft:entity.experience_orb.pickup", 1.1f, .8f);
        }
        return true;
    }

    @Override public void modifiers(int m) { mods = m; }

    private long step() { return (mods & 2) != 0 ? 100 : (mods & 1) != 0 ? 10 : 1; }

    // ───────── 데이터 ─────────
    /** 목록이 있는 탭 (판매 / 농작물) */
    private boolean listTab() { return tab.equals("sell") || tab.equals("farm") || tab.equals("item"); }
    /** 구매 목록 = 농작물 탭의 씨앗·도구 / 아이템 탭(코인) */
    private boolean farmBuy() { return tab.equals("item") || tab.equals("farm") && farmFilter < 2; }
    private boolean coinTab() { return tab.equals("item"); }

    private List<JsonObject> source() {
        if (coinTab()) return J.objs(data, "coinItems");
        if (!tab.equals("farm")) return J.objs(data, "sell");
        JsonObject crop = J.objOrEmpty(data, "crop");
        return J.objs(crop, farmFilter == 2 ? "sell" : "buy");
    }

    private List<JsonObject> items() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : source()) {
            if (coinTab()) { /* 거르기 없음 */ }
            else if (tab.equals("farm")) {
                String g = J.str(o, "group");
                if (farmFilter == 0 && !g.equals("seed") || farmFilter == 1 && !g.equals("tool")) continue;
            } else {
                boolean farm = J.bool(o, "farm");
                if (filter == 1 && farm || filter == 2 && !farm) continue;
            }
            if (search != null && !search.isEmpty() && !J.str(o, "name").contains(search)) continue;
            out.add(o);
        }
        return out;
    }

    /** 구매 묶음 수 (보유 다이아·아이템 탭은 코인으로 살 수 있는 만큼, 최대 64) */
    private long buyLimit(JsonObject s) {
        long price = s == null ? 1 : Math.max(1, J.num(s, "price", 1));
        return Math.max(1, Math.min(64, buyMoney() / price));
    }
    private long buyMoney() { return coinTab() ? J.num(data, "coins") : avail(); }
    private long buyAmount() { return typedOr(Math.max(1, buyAmt), 64); }

    private JsonObject sel(List<JsonObject> all) {
        for (JsonObject o : all) if (J.str(o, "id").equals(selected)) return o;
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
    private long coinMax() { return tab.equals("ex") ? J.num(data, "coins") : Math.max(0, Math.min(J.num(data, "coinMax", 100), avail() / unit())); }

    private long typedOr(long v, long max) {
        if (typed == null) return v;
        try { return Math.min(max, Math.max(0, Long.parseLong(typed.isEmpty() ? "0" : typed))); } catch (NumberFormatException e) { return 0; }
    }

    private long sellAmount(JsonObject s) {
        long max = s == null ? 0 : J.num(s, "have");
        return typedOr(sellAmt < 0 ? max : Math.min(sellAmt, max), max);
    }

    /** 코인 구매 탭 / 코인 교환 탭(ex) 수량. 교환은 보유 코인까지 */
    private long coinLimit() { return tab.equals("ex") ? Math.max(1, J.num(data, "coins")) : Math.max(1, J.num(data, "coinMax", 100)); }
    private long coinAmount() { return typedOr(Math.max(1, tab.equals("ex") ? exAmt : coinAmt), coinLimit()); }
    private void setCoinAmt(long v) { if (tab.equals("ex")) exAmt = v; else coinAmt = v; }

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
        // v3 프레임 왼쪽 칸 장식: 흐린 클로버 x 62~100 y 198~232 · 반짝이 x 45~70 y 240~270 · 큰 반짝이 x 18~60 y 295~345 → 글자·메뉴 판은 그 오른쪽(x≥66)
        c.text("메뉴", 108, 220, 22, Theme.MUTED, Canvas.LEFT, TXT);
        // 순서: 코인 구매 / 코인 판매 / 아이템 / 판매 / 농작물
        float my2 = 262;
        menu(c, "coin", my2, "shop/coin_48", "코인 구매", mx, my); my2 += 80;
        if (J.num(data, "toDiamond") > 0) { menu(c, "ex", my2, "shop/diamond_48", "코인 판매", mx, my); my2 += 80; }
        List<JsonObject> ci = J.objs(data, "coinItems");
        if (!ci.isEmpty()) { menu(c, "item", my2, "item:" + icon(ci.get(0)), "아이템", mx, my); my2 += 80; }
        menu(c, "sell", my2, "shop/sell_tab_pickaxe", "판매", mx, my); my2 += 80;
        if (!J.objs(J.objOrEmpty(data, "crop"), "buy").isEmpty()) menu(c, "farm", my2, "item:minecraft:wheat", "농작물", mx, my);
        // 보유: 구분선(766) 아래 ~ 왼쪽 아래 모서리 장식(y≥880) 위, 반짝이(x 45~70 y 805~832)·흐린 클로버(x 55~90 y 838~875) 오른쪽(x≥100)
        c.text("보유", 100, 788, 22, Theme.MUTED, Canvas.LEFT, TXT);
        double dust = J.dbl(data, "dust", 0);
        if (dust > 0.0005) c.textFit("모은 소수점 " + dec(dust), 282, 788, 17, 140, Theme.MUTED, Canvas.RIGHT, TXT);
        c.image("shop/diamond_32", 100, 806, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(avail()), 144, 823, 28, 140, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/coin_32", 100, 844, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(J.num(data, "coins")), 144, 861, 28, 140, Theme.INK, Canvas.LEFT, TXT);

        if (listTab()) sell(c, now, mx, my); else if (tab.equals("ex")) exchange(c, now, mx, my); else coin(c, now, mx, my);
        c.pop();
    }

    private void menu(Canvas c, String key, float y, String icon, String label, float mx, float my) {
        // 메뉴 판 66~286: 왼쪽 테두리 반짝이(x ≤ 60)를 덮지 않게 — 그림 248x72 는 9조각으로 양끝 비율 그대로
        boolean on = tab.equals(key), hov = !on && Anim.in(mx, my, 66, y, 220, 72);
        if (on || hov) c.imageNine("shop/menu_selected", 66, y, 220, 72, .15f, .45f, 37, 32.4f, on ? 0xFFFFFFFF : 0x66FFFFFF);   // shop/menu_default 는 빈(투명) 그림이라 안 그림
        if (icon.startsWith("item:")) c.item(icon.substring(5), 84, y + 12, 48);
        else c.image(icon, 84, y + 12, 48, 48, 0xFFFFFFFF);
        c.textFit(label, 142, y + 37, 28, 132, on ? Theme.INK : 0xFF6B5A4C, Canvas.LEFT, TXT);
        hits.add(new R("tab:" + key, 66, y, 220, 72));
    }

    /** 오른쪽 정렬 값 + 앞에 아이콘 */
    private void value(Canvas c, String icon, String v, float y, int col) {
        float tw = c.textWidth(v, 26, TXT);
        if (tw > 150) tw = 150;
        c.textFit(v, 1488, y, 26, 150, col, Canvas.RIGHT, TXT);
        if (icon != null) c.image(icon, 1488 - tw - 40, y - 16, 32, 32, 0xFFFFFFFF);
    }

    private void stepper(Canvas c, long now, float y, long amount, float mx, float my) {
        String img = isPressed("minus", now) ? "shop/stepper_left_pressed" : isPressed("plus", now) ? "shop/stepper_right_pressed" : "shop/stepper_default";
        // 수량 칸 + 최대 버튼 묶음(376)을 거래 판 가운데(1303)에 — 칸 그림 3칸: 왼 4~60 · 가운데 62~198 · 오른 200~256 (260x56 기준)
        final float sx = 1115;
        c.image(img, sx, y, 260, 56, 0xFFFFFFFF);
        c.image("shop/minus", sx + 20, y + 16, 24, 24, 0xFFFFFFFF);
        c.image("shop/plus", sx + 216, y + 16, 24, 24, 0xFFFFFFFF);
        String shown = typed != null ? (typed.isEmpty() ? "0" : typed) : Fmt.num(amount);
        c.textFit(shown, sx + 130, y + 28, 28, 126, Theme.INK, Canvas.CENTER, TXT);
        if (typed != null && (now / 530) % 2 == 0) {
            float tw = Math.min(126, c.textWidth(shown, 28, TXT));
            c.fill(sx + 130 + tw / 2 + 3, y + 13, 3, 30, 1.5f, 0xFF7CB342);
        }
        hit("minus", sx, y, 61, 56);
        hit("input", sx + 61, y, 138, 56);
        hit("plus", sx + 199, y, 61, 56);
        float bx = sx + 284;
        boolean hMax = Anim.in(mx, my, bx, y + 1, 92, 54);
        c.image(isPressed("max", now) || hMax ? "shop/small_outline_pressed" : "shop/small_outline_default", bx, y + 1, 92, 54, 0xFFFFFFFF);
        c.text("최대", bx + 46, y + 28, 22, Theme.INK, Canvas.CENTER, TXT);
        hit("max", bx, y + 1, 92, 54);
    }

    /** 클릭 자리 (오른쪽 판을 옮겨 그릴 때는 hx 만큼 같이 옮김) */
    private void hit(String key, float x, float y, float w, float h) { hits.add(new R(key, x + hx, y, w, h)); }

    /** 오른쪽 거래 판: 프레임 오른쪽 칸(1072~1558) 안 1080~1510 — 칸 오른쪽 반짝이·클로버(x≥1508)와 아래 모서리 장식(y≥880)을 덮지 않게.
     *  9조각 모서리 = 판 그림의 위오른쪽·아래왼쪽 장식(가로 ~105 · 세로 ~80 픽셀)이 통째로 들어가게 103x82 (늘이지 않음).
     *  판 안 아래왼쪽 장식이 화면 y 808~ · x ~1198 까지라 버튼은 그 위(≤806)에서 끝남 */
    private float hx;
    private static final float RX = -8, PANEL_Y = 190, PANEL_W = 430, PANEL_H = 700;

    private float beginRight(Canvas c, float mx) {
        c.push();
        c.translate(RX, 0);
        hx = RX;
        c.imageNine("shop/transaction", 1088, PANEL_Y, PANEL_W, PANEL_H, .22f, .11f, 103.4f, 82.5f, 0xFFFFFFFF);
        return mx - RX;
    }

    private void endRight(Canvas c) { c.pop(); hx = 0; }

    private void bigButton(Canvas c, String kind, String key, float y, String label, boolean enabled, float mx, float my, int ink) {
        // 거래 판 폭(430)에 맞춰 386x62 — 양끝 다이아·클로버(그림 x 0~68 · 356~424)는 9조각으로 비율 그대로, 글자는 그 사이에
        final float bx = 1110, bw = 386, bh = 62, sc = bh / 68f;
        boolean hov = enabled && Anim.in(mx, my, bx, y, bw, bh);
        c.imageNine("shop/" + kind + (enabled ? hov ? "_hover" : "_default" : "_disabled"), bx, y, bw, bh, .16f, .45f, .16f * 424 * sc, .45f * bh, 0xFFFFFFFF);
        c.textFit(label, 1303, y + bh / 2, 30, 270, enabled ? ink : 0xFF9A8E80, Canvas.CENTER, TXT);
        hit(key, bx, y, bw, bh);
    }

    // ───────── 판매 탭 ─────────
    private void sell(Canvas c, long now, float mx, float my) {
        boolean farmTab = tab.equals("farm");
        int f = farmTab ? farmFilter : filter;
        c.text(coinTab() ? "코인 아이템" : farmTab ? "농작물" : "판매 아이템", 330, 216, 30, Theme.INK, Canvas.LEFT, TXT);
        if (!coinTab()) {
        String fimg = f == 0 ? "shop/filter_left_selected" : f == 1 ? "shop/filter_center_selected" : "shop/filter_right_selected";
        c.image(fimg, 324, 254, 420, 54, 0xFFFFFFFF);
        String[] fl = farmTab ? new String[]{"씨앗", "도구", "판매"} : new String[]{"전체", "광물", "작물"};
        for (int i = 0; i < 3; i++) {
            c.text(fl[i], 394 + i * 140, 281, 24, f == i ? Theme.GREEN_DARK : 0xFF6B5A4C, Canvas.CENTER, TXT);
            hits.add(new R("filter:" + i, 324 + i * 140, 254, 140, 54));
        }
        // 검색
        if (search != null) {
            c.imageNine("shop/filter_default", 770, 256, 218, 50, .2f, .45f, 26, 22.5f, 0xFFFFFFFF);   // 크림 금테 알약 = 입력 칸
            String q = search.isEmpty() ? "§7이름 검색" : search;
            c.textFit(q, search.isEmpty() ? 800 : 790, 281, 22, 175, Theme.INK, Canvas.LEFT, TXT);   // 빈 칸이면 커서(792) 오른쪽에 안내 글자
            if (!search.isEmpty() || (now / 530) % 2 == 0) {
                float tw = search.isEmpty() ? 0 : Math.min(180, c.textWidth(search, 22, TXT));
                if ((now / 530) % 2 == 0) c.fill(792 + tw, 267, 2.5f, 28, 1, 0xFF7CB342);
            }
        }
        boolean hS = Anim.in(mx, my, 990, 254, 56, 56);
        c.image("shop/search", 998, 262, 40, 40, hS || search != null ? 0xFFB9E39A : 0xFFFFFFFF);
        hits.add(new R("search", 990, 254, 56, 56));
        }

        List<JsonObject> all = items();
        JsonObject s = sel(all);
        int rows = (all.size() + 2) / 3;
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, rows - 2)));
        if (all.isEmpty()) c.text(search != null ? "찾는 아이템이 없어요" : coinTab() ? "살 수 있는 아이템이 없어요" : "팔 수 있는 아이템이 없어요", 688, 600, 26, Theme.MUTED, Canvas.CENTER, TXT);
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
        if (coinTab()) {
            c.text("깨굴코인으로 사요", 330, 917, 24, Theme.MUTED, Canvas.LEFT, TXT);
        } else if (farmTab) {
            c.text(farmBuy() ? "다이아로 사요 · 가격은 고정이에요" : "작물은 고정 시세로 팔아요", 330, 917, 24, Theme.MUTED, Canvas.LEFT, TXT);
        } else { // 시세 갱신
            long left = Math.max(0, J.num(data, "farmNextMs") - (now - recvAt)) / 1000;
            c.image("shop/hourglass", 328, 900, 32, 32, 0xFFFFFFFF);
            c.text("시세 갱신 " + String.format("%02d:%02d", left / 60, left % 60), 372, 917, 24, Theme.INK, Canvas.LEFT, TXT);
        }

        // 오른쪽: 선택한 아이템 (거래 판 1088~1558 을 RX 만큼 옮겨 프레임 칸 가운데로)
        mx = beginRight(c, mx);
        c.text("선택한 아이템", 1116, 220, 26, Theme.INK, Canvas.LEFT, TXT);
        if (s != null) {
            float bob = (float) Math.sin(now / 420.0) * 3;
            c.item(icon(s), 1255, 236 + bob, 96);
            c.textFit(J.str(s, "name"), 1303, 352, 30, 380, Theme.INK, Canvas.CENTER, TXT);
            if (!farmTab && !coinTab()) trend(c, s, 1303, 380);
        } else c.text("아이템을 골라 주세요", 1303, 290, 24, Theme.MUTED, Canvas.CENTER, TXT);
        c.image("shop/divider_horizontal", 1112, 400, 382, 2, 0xFFFFFFFF);
        if (farmBuy()) buyPanel(c, now, s, mx, my); else sellInfo(c, now, s, farmTab, mx, my);

        // 판매 결과: 다이아가 위로 튀어 오름 + 문구
        if ("shop_sold".equals(fxId) && now - fxStart < SELL_FX) {
            float t = Anim.t(now, fxStart, SELL_FX), a = Anim.fade(now, fxStart, SELL_FX, 120, 500);
            for (int i = 0; i < 8; i++) {
                float tt = Anim.clamp(t * 1.8f - i * .05f);
                if (tt <= 0 || tt >= 1) continue;
                double ang = i * Math.PI / 4 + .4;
                float r = 30 + 110 * Anim.outCubic(tt);
                c.imageAt("shop/diamond_48", 1303 + (float) Math.cos(ang) * r, 284 + (float) Math.sin(ang) * r * .6f, 36, 1, i * 20, Anim.white(a * (1 - tt)));
            }
            float p = Anim.outBack(Anim.t(now, fxStart, 320));
            c.push(); c.translate(1303, 284); c.scale(p, p);
            String msg = "다이아 +" + Fmt.num(J.num(fx, "got"));
            float tw = c.textWidth(msg, 30, TXT) + 56;
            pill(c, -tw / 2, -30, tw, 60, a);
            c.text(msg, 0, 0, 30, Anim.alpha(Theme.GREEN_DARK, a), Canvas.CENTER, TXT);
            c.pop();
        }
        endRight(c);
    }

    /** 판매: 보유·가격 → 수량 → 받을 다이아 → 판매 / 전부 판매 (판 아래 890 안쪽) */
    private void sellInfo(Canvas c, long now, JsonObject s, boolean farmTab, float mx, float my) {
        long amt = sellAmount(s);
        double price = s == null ? 0 : J.dbl(s, "price", 0);
        c.text("보유 수량", 1116, 426, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, null, Fmt.num(s == null ? 0 : J.num(s, "have")) + "개", 426, Theme.INK);
        c.text("개당 가격", 1116, 464, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", dec(price), 464, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 488, 382, 2, 0xFFFFFFFF);
        c.text("판매 수량", 1116, 512, 24, Theme.INK, Canvas.LEFT, TXT);
        stepper(c, now, 532, amt, mx, my);
        c.image("shop/divider_horizontal", 1112, 604, 382, 2, 0xFFFFFFFF);
        c.text("받을 다이아", 1116, 630, 24, Theme.INK, Canvas.LEFT, TXT);
        double val = price * amt;
        value(c, "shop/diamond_32", dec(val), 630, Theme.INK);
        long got = (long) Math.floor(J.dbl(data, "dust", 0) + val + 1e-9);
        if (amt > 0 && Math.abs(val - Math.rint(val)) > 1e-6)
            c.textFit("바로 +" + Fmt.num(got) + "개 · 소수점은 모아뒀다가 1개가 되면 지급", 1303, 657, 17, 380, Theme.MUTED, Canvas.CENTER, TXT);
        boolean b = busy(now);
        bigButton(c, "sales", "sell", 670, b ? "판매 중..." : "판매하기", s != null && amt > 0 && !b, mx, my, 0xFF2F4A1C);
        bigButton(c, "secondary", "sellAll", 742, farmTab ? "작물 전부 판매" : "전부 판매", !b && !source().isEmpty(), mx, my, Theme.INK);
    }

    /** 그릴 아이템: ItemsAdder 아이템은 서버가 준 icon(재료|모델), 아니면 재료 id */
    private static String icon(JsonObject o) {
        String i = J.str(o, "icon");
        return i.isEmpty() ? J.str(o, "id") : i;
    }

    /** 농작물 탭 구매: 묶음 수 고르기 → shop.cropBuy */
    private void buyPanel(Canvas c, long now, JsonObject s, float mx, float my) {
        long per = s == null ? 0 : J.num(s, "amount", 1), price = s == null ? 0 : J.num(s, "price");
        boolean ct = coinTab();
        String cur = ct ? "shop/coin_32" : "shop/diamond_32", curName = ct ? "코인" : "다이아";
        c.text("한 묶음", 1116, 426, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, null, Fmt.num(per) + "개", 426, Theme.INK);
        c.text("묶음 가격", 1116, 464, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, cur, Fmt.num(price), 464, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 488, 382, 2, 0xFFFFFFFF);
        c.text("구매 묶음 수", 1116, 512, 24, Theme.INK, Canvas.LEFT, TXT);
        long n = buyAmount();
        stepper(c, now, 532, n, mx, my);
        c.image("shop/divider_horizontal", 1112, 604, 382, 2, 0xFFFFFFFF);
        long cost = n * price, after = buyMoney() - cost;
        c.text("필요 " + curName, 1116, 630, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, cur, Fmt.num(cost), 630, Theme.INK);
        c.textFit("받는 개수 " + Fmt.num(n * per) + "개 · 구매 후 " + curName + " " + Fmt.num(after), 1303, 657, 17, 380, after < 0 ? Theme.RED : Theme.MUTED, Canvas.CENTER, TXT);
        boolean b = busy(now), ok = s != null && n > 0 && after >= 0 && !b;
        bigButton(c, "purchase", "cropBuy", 670, b ? "구매 중..." : after < 0 ? (ct ? "코인이 부족해요" : "다이아가 부족해요") : "구매하기", ok, mx, my, 0xFF5A3A12);
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
        c.item(icon(o), x + 60, y + 24 + lift, 112);
        c.textFit(J.str(o, "name"), x + 116, y + 146, 28, 200, Theme.INK, Canvas.CENTER, TXT);
        String p = dec(J.dbl(o, "price", 0));
        int t = (int) J.num(o, "trend");
        float tw = Math.min(120, c.textWidth(p, 26, TXT)), aw = J.bool(o, "farm") && t != 0 ? 30 : 0;
        float sx = x + 116 - (30 + tw + aw) / 2;
        c.image(coinTab() ? "shop/coin_32" : "shop/diamond_24", sx, y + 172, 24, 24, 0xFFFFFFFF);
        c.textFit(p, sx + 30, y + 185, 26, 120, Theme.INK, Canvas.LEFT, TXT);
        if (aw > 0) c.image(t > 0 ? "shop/up" : "shop/down", sx + 30 + tw + 6, y + 172, 24, 24, 0xFFFFFFFF);
        c.image("shop/divider_horizontal", x + 24, y + 210, 184, 1.5f, 0xFFFFFFFF);
        if (farmBuy()) c.textFit(Fmt.num(J.num(o, "amount", 1)) + "개 묶음", x + 116, y + 233, 24, 200, Theme.INK, Canvas.CENTER, TXT);
        else {
            long have = J.num(o, "have");
            c.textFit("보유 " + Fmt.num(have), x + 116, y + 233, 24, 200, have > 0 ? Theme.INK : Theme.MUTED, Canvas.CENTER, TXT);
        }
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
        // 상품 칸(v3): 위 테두리 y 313 · 왼쪽 위 장식 x ≤ 487 y ≤ 377 · 오른쪽 아래 장식 x ≥ 867 y ≥ 802 (다이아 x ≥ 934 y ≥ 817)
        c.textFit("깨굴코인", 682, 356, 46, 360, Theme.INK, Canvas.CENTER, TXT);
        c.text("다이아로 구매하는 코인", 682, 406, 26, Theme.MUTED, Canvas.CENTER, TXT);
        float bob = (float) Math.sin(now / 480.0) * 6;
        c.image("shop/coin_256", 566, 436 + bob, 232, 232, 0xFFFFFFFF);
        c.image("shop/divider_horizontal", 380, 686, 604, 2, 0xFFFFFFFF);
        c.text("기준 교환량", 682, 716, 24, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/exchange_strip", 440, 738, 488, 72, 0xFFFFFFFF);
        c.image("shop/diamond_32", 504, 758, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(unit()), 548, 775, 34, 110, Theme.INK, Canvas.LEFT, TXT);
        c.text("→", 684, 773, 34, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/coin_32", 776, 758, 32, 32, 0xFFFFFFFF);
        c.text("1", 820, 775, 34, Theme.INK, Canvas.LEFT, TXT);
        c.text("지급량은 구매 결과에 따라 달라집니다", 682, 918, 22, Theme.MUTED, Canvas.CENTER, TXT);

        // 오른쪽: 구매 정보
        float rmx = beginRight(c, mx);
        c.text("구매 정보", 1116, 220, 26, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/coin_128", 1247, 236, 112, 112, 0xFFFFFFFF);
        c.text("깨굴코인", 1303, 370, 28, Theme.INK, Canvas.CENTER, TXT);
        c.text("1회 비용", 1116, 412, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(unit()), 412, Theme.INK);
        c.text("기준 수량", 1116, 450, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/coin_32", "1", 450, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 474, 382, 2, 0xFFFFFFFF);
        c.text("구매 수량", 1116, 498, 24, Theme.INK, Canvas.LEFT, TXT);
        long n = coinAmount();
        stepper(c, now, 518, n, rmx, my);
        c.image("shop/divider_horizontal", 1112, 590, 382, 2, 0xFFFFFFFF);
        long cost = n * unit(), after = avail() - cost;
        c.text("필요 다이아", 1116, 616, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(cost), 616, Theme.INK);
        c.text("구매 후 잔액", 1116, 654, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(after), 654, after < 0 ? Theme.RED : Theme.INK);
        boolean b = busy(now), ok = n > 0 && after >= 0 && !b;
        bigButton(c, "purchase", "buy", 680, b ? "구매 중..." : after < 0 ? "다이아가 부족해요" : "구매하기", ok, rmx, my, 0xFF5A3A12);
        String last = fx != null && "coin_buy".equals(fxId) ? J.str(fx, "message") : "";
        c.textFit(last.isEmpty() ? "결과는 구매 후 표시됩니다" : last, 1303, 778, 22, 380, last.isEmpty() ? Theme.MUTED : Theme.INK, Canvas.CENTER, TXT);
        endRight(c);

        if ("coin_buy".equals(fxId) && now - fxStart < COIN_FX) coinResult(c, now);
        else if (hInfo) odds(c);
    }

    // ───────── 코인 교환 탭 (코인 → 보유 다이아, 제출용) ─────────
    private void exchange(Canvas c, long now, float mx, float my) {
        long rate = Math.max(1, J.num(data, "toDiamond", 15)), coins = J.num(data, "coins");
        c.text("코인 판매", 320, 224, 34, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/product_display", 322, 282, 720, 614, 0xFFFFFFFF);
        c.textFit("깨굴코인 → 다이아", 682, 356, 46, 360, Theme.INK, Canvas.CENTER, TXT);
        c.text("제출에 쓸 수 있는 보유 다이아로 바꿉니다", 682, 406, 26, Theme.MUTED, Canvas.CENTER, TXT);
        float bob = (float) Math.sin(now / 480.0) * 6;
        c.image("shop/coin_256", 566, 436 + bob, 232, 232, 0xFFFFFFFF);
        c.image("shop/divider_horizontal", 380, 686, 604, 2, 0xFFFFFFFF);
        c.text("판매 비율", 682, 716, 24, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/exchange_strip", 440, 738, 488, 72, 0xFFFFFFFF);
        c.image("shop/coin_32", 504, 758, 32, 32, 0xFFFFFFFF);
        c.text("1", 548, 775, 34, Theme.INK, Canvas.LEFT, TXT);
        c.text("→", 684, 773, 34, Theme.INK, Canvas.CENTER, TXT);
        c.image("shop/diamond_32", 776, 758, 32, 32, 0xFFFFFFFF);
        c.textFit(Fmt.num(rate), 820, 775, 34, 110, Theme.INK, Canvas.LEFT, TXT);
        c.text("판매한 코인은 다시 되돌릴 수 없습니다", 682, 918, 22, Theme.MUTED, Canvas.CENTER, TXT);

        float rmx = beginRight(c, mx);
        c.text("판매 정보", 1116, 220, 26, Theme.INK, Canvas.LEFT, TXT);
        c.image("shop/coin_128", 1247, 236, 112, 112, 0xFFFFFFFF);
        c.text("깨굴코인", 1303, 370, 28, Theme.INK, Canvas.CENTER, TXT);
        c.text("보유 코인", 1116, 412, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/coin_32", Fmt.num(coins), 412, Theme.INK);
        c.text("코인 1개당", 1116, 450, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(rate), 450, Theme.INK);
        c.image("shop/divider_horizontal", 1112, 474, 382, 2, 0xFFFFFFFF);
        c.text("판매 수량", 1116, 498, 24, Theme.INK, Canvas.LEFT, TXT);
        long n = coinAmount();
        stepper(c, now, 518, n, rmx, my);
        c.image("shop/divider_horizontal", 1112, 590, 382, 2, 0xFFFFFFFF);
        c.text("받을 다이아", 1116, 616, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/diamond_32", Fmt.num(n * rate), 616, Theme.INK);
        long after = coins - n;
        c.text("판매 후 코인", 1116, 654, 24, Theme.INK, Canvas.LEFT, TXT);
        value(c, "shop/coin_32", Fmt.num(after), 654, after < 0 ? Theme.RED : Theme.INK);
        boolean b = busy(now), ok = n > 0 && after >= 0 && !b;
        bigButton(c, "purchase", "exchange", 680, b ? "판매 중..." : after < 0 ? "코인이 부족해요" : "판매하기", ok, rmx, my, 0xFF5A3A12);
        endRight(c);

        if ("coin_exchange".equals(fxId) && now - fxStart < SELL_FX) {
            float a = Anim.fade(now, fxStart, SELL_FX, 120, 500), p = Anim.outBack(Anim.t(now, fxStart, 320));
            c.push(); c.translate(682, 560); c.scale(p, p);
            String msg = "다이아 +" + Fmt.num(J.num(fx, "got"));
            float tw = c.textWidth(msg, 34, TXT) + 60;
            pill(c, -tw / 2, -34, tw, 68, a);
            c.text(msg, 0, 0, 34, Anim.alpha(Theme.GREEN_DARK, a), Canvas.CENTER, TXT);
            c.pop();
        }
    }

    /** 결과 알약 (초록 필터 그림 shop/filter_selected 를 늘여서) */
    private static void pill(Canvas c, float x, float y, float w, float h, float a) {
        c.imageNine("shop/filter_selected", x, y, w, h, .2f, .45f, h * .52f, h * .45f, Anim.white(a));
    }

    private void odds(Canvas c) {
        List<JsonObject> os = J.objs(data, "outcomes");
        if (os.isEmpty()) return;
        // 상품 칸 그림(720x614)을 절반 크기 9조각으로: 모서리 = 왼쪽 위 장식(그림 x ≤ 165, y ≤ 102) · 오른쪽 아래 장식(x ≥ 545, y ≥ 519) 이 통째로 들어가는 175x110 → 화면 87.5x55
        // 판 몸통(금테)은 그림 x 12~708, y 31~592 → 화면 x+6 ~ x+w-6, y+15.5 ~ y+h-11
        float w = 390, h = 155 + (os.size() - 1) * 38, x = 1040 - w, y = 266;
        c.fill(x + 9, y + 21, w - 12, h - 26, 18, 0x33000000);
        c.imageNine("shop/product_display", x, y, w, h, .243f, .179f, 87.5f, 55, 0xFFFFFFFF);
        c.text("구매 1회당 결과 확률", x + w / 2, y + 44, 22, Theme.INK, Canvas.CENTER, TXT);
        float ry = y + 90;   // 첫 줄은 왼쪽 위 장식(화면 y+51) 아래, 마지막 줄은 오른쪽 아래 장식(화면 y+h-47) 위
        for (JsonObject o : os) {
            long m = J.num(o, "multiplier", 1);
            String name = m <= 0 ? "실패 (코인 없음)" : m == 1 ? "정상 구매" : m + "배 구매";
            int col = m <= 0 ? Theme.RED : m >= 3 ? Theme.GOLD : m == 2 ? Theme.GREEN_DARK : Theme.INK;
            c.text(name, x + 30, ry, 22, col, Canvas.LEFT, TXT);
            c.text(dec(J.dbl(o, "chance", 0)) + "%", x + w - 30, ry, 22, col, Canvas.RIGHT, TXT);
            ry += 38;
        }
    }

    /** 코인 구매 결과: 다이아 → 금화, 깨굴이 + 결과 리본 (가운데 상품 칸 위) */
    private void coinResult(Canvas c, long now) {
        String o = J.str(fx, "outcome");
        boolean fail = o.equals("fail");
        long e = now - fxStart;
        float a = Anim.fade(now, fxStart, COIN_FX, 150, 450);
        c.image("shop/product_display", 322, 282, 720, 614, Anim.white(a));   // 상품 칸을 결과 판으로 덮음
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
        c.imageAt(frog, 682, 540 + fb, 300 * s2, 1, 0, Anim.white(a));
        String banner = switch (o) { case "fail" -> "shop/banner_fail"; case "double" -> "shop/banner_double"; case "triple" -> "shop/banner_triple"; default -> "shop/banner_success"; };
        String title = switch (o) { case "fail" -> "구매 실패..."; case "double" -> "2배 구매!"; case "triple" -> "3배 구매!!"; default -> "구매 완료!"; };
        long got = J.num(fx, "got");
        c.push();
        c.translate(682, 736);   // 리본 아래끝 805: 상품 칸 오른쪽 아래 다이아·클로버(y ≥ 817)를 덮지 않게
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
            if (key.startsWith("filter:")) {
                int f = Integer.parseInt(key.substring(7));
                if (tab.equals("farm")) { farmFilter = f; selected = null; sellAmt = -1; buyAmt = 1; } else filter = f;
                scrollRow = 0; host.sound("minecraft:ui.button.click", 1.3f, .35f); return true;
            }
            if (key.startsWith("item:")) {
                String id = key.substring(5);
                if (!id.equals(selected)) { selected = id; sellAmt = -1; buyAmt = 1; host.sound("minecraft:ui.button.click", 1.5f, .35f); }
                return true;
            }
            boolean sellTab = listTab() && !farmBuy(), buyTab = farmBuy(), farm = tab.equals("farm");
            JsonObject s = listTab() ? sel(items()) : null;
            switch (key) {
                case "close" -> host.closePanel(this);
                case "search" -> { search = search == null ? "" : null; scrollRow = 0; }
                case "input" -> typed = "";
                case "minus" -> {
                    if (buyTab) buyAmt = Math.max(1, buyAmount() - step());
                    else if (sellTab) sellAmt = Math.max(0, sellAmount(s) - step()); else setCoinAmt(Math.max(1, coinAmount() - step()));
                    press("minus", now); host.sound("minecraft:ui.button.click", 1.4f, .35f);
                }
                case "plus" -> {
                    if (buyTab) buyAmt = Math.min(64, buyAmount() + step());
                    else if (sellTab) sellAmt = Math.min(s == null ? 0 : J.num(s, "have"), sellAmount(s) + step());
                    else setCoinAmt(Math.min(coinLimit(), coinAmount() + step()));
                    press("plus", now); host.sound("minecraft:ui.button.click", 1.5f, .35f);
                }
                case "max" -> {
                    if (buyTab) buyAmt = buyLimit(s);
                    else if (sellTab) sellAmt = -1; else setCoinAmt(Math.max(1, coinMax()));
                    press("max", now); host.sound("minecraft:ui.button.click", 1.2f, .35f);
                }
                case "sell" -> {
                    long a = sellAmount(s);
                    if (s == null || a <= 0 || busy(now)) return true;
                    waitingSince = now;
                    host.send(farm ? "shop.cropSell" : "shop.sell", "id", J.str(s, "id"), "amount", String.valueOf(a));
                }
                case "sellAll" -> {
                    if (busy(now)) return true;
                    waitingSince = now;
                    host.send(farm ? "shop.cropSellAll" : "shop.sellAll");
                }
                case "cropBuy" -> {
                    long n = buyAmount();
                    if (s == null || busy(now) || n <= 0 || n * J.num(s, "price") > buyMoney()) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                    waitingSince = now;
                    host.send(coinTab() ? "shop.itemBuy" : "shop.cropBuy", "id", J.str(s, "id"), "amount", String.valueOf(n));
                    host.sound("minecraft:entity.experience_orb.pickup", 1.1f, .6f);
                }
                case "buy" -> {
                    long n = coinAmount();
                    if (busy(now) || n <= 0 || n * unit() > avail()) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                    waitingSince = now;
                    host.send("shop.buy", "amount", String.valueOf(n));
                }
                case "exchange" -> {
                    long n = coinAmount();
                    if (busy(now) || n <= 0 || n > J.num(data, "coins")) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                    waitingSince = now;
                    host.send("shop.toDiamond", "amount", String.valueOf(n));
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
        if (farmBuy()) buyAmt = Math.max(1, buyAmount());
        else if (listTab()) sellAmt = sellAmount(sel(items())); else setCoinAmt(Math.max(1, coinAmount()));
        typed = null;
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        if (listTab() && Anim.in(x, y, 310, 320, 760, 570)) {
            scrollRow += amount < 0 ? 1 : -1;
            return true;
        }
        if (Anim.in(x, y, 1080, 500, 470, 140)) {
            long d = amount > 0 ? 1 : -1;
            if (farmBuy()) buyAmt = Math.max(1, Math.min(64, buyAmount() + d));
            else if (listTab()) { JsonObject s = sel(items()); sellAmt = Math.max(0, Math.min(s == null ? 0 : J.num(s, "have"), sellAmount(s) + d)); }
            else setCoinAmt(Math.max(1, Math.min(coinLimit(), coinAmount() + d)));
            typed = null;
            return true;
        }
        return false;
    }

    @Override public boolean typed(char ch, long now) {
        if (search != null && listTab() && typed == null) {
            if (Character.isISOControl(ch) || search.length() >= 10) return true;
            search += ch;
            scrollRow = 0;
            return true;
        }
        if (ch >= '0' && ch <= '9') {
            if (typed == null) typed = "";
            if (typed.length() < 6) typed = (typed.equals("0") ? "" : typed) + ch;
            // 코인 구매칸은 한 번에 살 수 있는 최대(coinMax)를 넘겨 칠 수 없음 → 넘기면 최대로 바뀌어 보임
            long lim = farmBuy() ? 64 : coinLimit();
            if ((!listTab() || farmBuy()) && Long.parseLong(typed) > lim) typed = String.valueOf(lim);
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
