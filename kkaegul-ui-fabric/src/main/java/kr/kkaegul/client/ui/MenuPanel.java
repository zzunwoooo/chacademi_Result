package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 메뉴 창: 내 정보 / 접속 보상 / 칭호 / 명예의 전당.
 * GPT 레퍼런스(2026-11-06 메뉴 시안) 배치 그대로. 좌표는 시안 기준 680x407 프레임 단위(R)로 쓰고 화면 크기에 맞게 키운다.
 */
public final class MenuPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private String tab;
    private int board;
    private float scroll, scrollMax;
    private final long opened;
    private record Hit(String key, String arg, float x, float y, float w, float h) {}
    private final List<Hit> hits = new ArrayList<>();
    private String hoverTip;
    private float k = 1, fx, fy; // 시안 단위 → 화면

    private static final String[] TABS = {"me", "playtime", "titles", "hall"};
    private static final String[] TAB_ART = {"profile", "attendance", "titles", "ranking"};
    private static final String[] TAB_NAME = {"내 정보", "접속 보상", "칭호", "명예의 전당"};

    // 시안 색
    private static final int FRAME_BORDER = 0xFFCFE6A8, FRAME_FILL = 0xFFFDF8EC;
    private static final int PANEL_BORDER = 0xFFE9DFCB, PANEL_FILL = 0xFFF9F2E3;

    public MenuPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.tab = J.str(this.data, "tab", "me");
        this.opened = now;
    }

    @Override public String screen() { return "menu"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        if (d.has("tab")) tab = J.str(d, "tab", tab);
    }

    /** 테두리 있는 둥근 상자 */
    static void box(Canvas c, float x, float y, float w, float h, float r, int border, int fill, float bw) {
        c.fill(x, y, w, h, r, border);
        c.fill(x + bw, y + bw, w - bw * 2, h - bw * 2, Math.max(0, r - bw), fill);
    }

    private float lx(float mx) { return (mx - fx) / k; }
    private float ly(float my) { return (my - fy) / k; }

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        hoverTip = null;
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x88000000);
        // 680x407 프레임 + 위로 튀어나온 깨굴이 간판을 화면 안에 맞춘다
        k = Math.min((W - 120) / 680f, (H - 100) / 520f);
        k = Math.min(k, 2.1f);
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 260));
        fx = W / 2 - 340 * k;
        fy = H / 2 - 160 * k;
        float mxR = lx(mx), myR = ly(my);
        c.push();
        c.translate(W / 2, H / 2);
        c.scale(pop, pop);
        c.translate(-W / 2, -H / 2);
        c.translate(fx, fy);
        c.scale(k, k);

        // 프레임
        box(c, 0, 0, 680, 407, 26, FRAME_BORDER, FRAME_FILL, 4);
        c.fill(4, 4, 672, 399, 22, 0x10FFFFFF);
        HudView.leaf(c, 30, 398, 16, true);
        HudView.leaf(c, 650, 398, 16, false);

        // 깨굴이 간판 + 제목
        float sw = 250, sh = sw * 312 / 512f, sy = 26 - sh * .79f;
        float bob = (float) Math.sin(now / 700.0) * 1.5f;
        c.image("gacha_machine/frog_sign_gentle", 340 - sw / 2, sy + bob, sw, sh, 0xFFFFFFFF);
        c.textFit(TAB_NAME[indexOf(tab)], 340, sy + bob + sh * .79f, 20, 130, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 닫기
        boolean ch = Anim.in(mxR, myR, 615, 12, 44, 44);
        c.image(ch ? "gacha_machine/close_pressed" : "gacha_machine/close_normal", 615, 12, 44, 44, 0xFFFFFFFF);
        hits.add(new Hit("close", "", 615, 12, 44, 44));

        // 왼쪽 탭
        for (int i = 0; i < 4; i++) {
            boolean sel = TABS[i].equals(tab);
            float tx = 22, ty = 67 + i * 73;
            boolean hov = Anim.in(mxR, myR, tx, ty, 80, 66);
            c.imageAt("menu/tab_" + TAB_ART[i] + (sel ? "_selected" : "_normal"), tx + 40, ty + 33, 76, hov && !sel ? 1.04f : 1, 0, 0xFFFFFFFF);
            hits.add(new Hit("tab", TABS[i], tx, ty, 80, 66));
            if (hov) hoverTip = "&f" + TAB_NAME[i];
        }

        switch (tab) {
            case "playtime" -> playtime(c, J.objOrEmpty(data, "playtime"), mxR, myR, now);
            case "titles" -> titles(c, J.objOrEmpty(data, "titles"), mxR, myR);
            case "hall" -> hall(c, J.objOrEmpty(data, "hall"), mxR, myR);
            default -> me(c, J.objOrEmpty(data, "me"), mxR, myR);
        }
        c.pop();
        if (hoverTip != null) Theme.tooltip(c, hoverTip, mx, my);
    }

    /** 프레임 아래 모서리 잎사귀 (그림 대신 간단히) */
    private static void leaves(Canvas c, float x, float y, boolean flip) {
        c.push();
        c.translate(x, y);
        if (flip) c.scale(-1, 1);
        c.push(); c.rotate(-40); c.fill(-4, -14, 9, 16, 4.5f, 0xFF9CCB6A); c.pop();
        c.push(); c.translate(8, -2); c.rotate(30); c.fill(-4, -13, 8, 14, 4, 0xFFB5DA87); c.pop();
        c.pop();
    }

    private static int indexOf(String t) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(t)) return i;
        return 0;
    }

    // ───────── 내 정보 ─────────
    private void me(Canvas c, JsonObject m, float mx, float my) {
        // 왼쪽 카드: 얼굴 · 이름 리본 · 곡괭이 3스탯
        box(c, 117, 70, 195, 306, 16, 0xFFDCEAC8, 0xFFEEF5E2, 2);
        box(c, 137, 88, 160, 162, 18, 0xFFC9DDB0, 0xFFFBF7EC, 3);
        if (!c.face(J.str(m, "account"), 167, 119, 100)) c.imageFit("hud/frog_smile", 217, 169, 120, 0xFFFFFFFF);
        String title = J.str(m, "title");
        if (!title.isEmpty()) c.textFit(title, 217, 256, 13, 170, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.image("gacha/jackpot_ribbon", 132, 262, 170, 170 * 247 / 1024f, 0xFFFFFFFF);
        c.textFit(J.str(m, "name", J.str(m, "account")), 217, 282, 17, 110, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        JsonObject pk = J.objOrEmpty(m, "pickaxe");
        String[] ic = {"gacha_machine/luck_clover", "gacha_machine/efficiency_bolt", "gacha_machine/durability_shield"};
        String[] key = {"fortune", "efficiency", "unbreaking"};
        String[] nm = {"행운", "효율", "내구성"};
        for (int i = 0; i < 3; i++) {
            float cx = 160 + i * 55;
            c.imageFit(ic[i], cx, 330, 38, 0xFFFFFFFF);
            c.text(String.valueOf(J.num(pk, key[i])), cx, 360, 14, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            if (Anim.in(mx, my, cx - 22, 308, 44, 62)) hoverTip = "&f손에 든 곡괭이 " + nm[i] + " " + J.num(pk, key[i]) + "\n&7가챠 최고 수치 " + J.num(m, "bestPickaxe");
        }

        // 오른쪽 기록 6줄
        String[][] rows = {
                {"menu/icon_diamond", "다이아 누적 증가", Fmt.num(J.num(m, "totalIncreased"))},
                {"menu/icon_submit", "누적 제출", Fmt.num(J.num(m, "totalSubmitted"))},
                {"menu/icon_donation", "누적 기부", Fmt.num(J.num(m, "totalDonated"))},
                {"menu/icon_jail", "깜빵", J.num(m, "jailCount") + "회"},
                {"menu/icon_broken_pickaxe", "곡괭이 파괴", J.num(m, "pickaxeBreaks") + "회"},
                {"menu/icon_death", "즉사", J.num(m, "instantDeaths") + "회"}};
        int[][] col = {{0xFFC7DBEF, 0xFFE4EEF8}, {0xFFE6D6BF, 0xFFF7EEDF}, {0xFFF0C6C6, 0xFFFCE3E3},
                {0xFFD4CAEA, 0xFFEDE7F7}, {0xFFEFD5C0, 0xFFFBEDE1}, {0xFFD3D3D3, 0xFFEBEBEB}};
        for (int i = 0; i < 6; i++) {
            float y = 70 + i * 51;
            box(c, 328, y, 326, 44, 22, col[i][0], col[i][1], 2);
            box(c, 330, y + 2, 64, 40, 20, 0x00000000, 0xB0FFFFFF, 0);
            c.fill(394, y + 8, 1.5f, 28, 0, col[i][0]);
            c.imageFit(rows[i][0], 362, y + 22, 30, 0xFFFFFFFF);
            c.text(rows[i][1], 408, y + 22, 15, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(rows[i][2], 640, y + 22, 19, 110, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        }
        // 맨 아래: 남은/보유 다이아 · 코인 · 게이지
        JsonObject g = J.objOrEmpty(m, "gauge");
        String line = "§7남은 다이아 §r" + Fmt.num(J.num(m, "debt")) + "   §7보유 §r" + Fmt.num(J.num(m, "held"))
                + "   §7코인 §r" + Fmt.num(J.num(m, "coins")) + "   §7운빨 §r" + J.num(g, "value") + "   §7" + J.num(m, "day") + "일차";
        c.textFit(line, 491, 388, 13, 326, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    // ───────── 접속 보상 ─────────
    private void playtime(Canvas c, JsonObject p, float mx, float my, long now) {
        box(c, 117, 70, 543, 316, 18, PANEL_BORDER, PANEL_FILL, 2);
        long today = J.num(p, "todaySeconds");
        List<JsonObject> ms = J.objs(p, "milestones");
        long maxNeed = 1;
        for (JsonObject m : ms) maxNeed = Math.max(maxNeed, J.num(m, "needSeconds"));
        c.text("오늘 접속 §2" + Fmt.time(today), 640, 86, 13, Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);

        // 진행 막대 + 별 표시
        float bx = 152, bw = 476, by = 105, bh = 16;
        float ratio = Math.min(1, today / (float) maxNeed);
        box(c, bx, by, bw, bh, 8, 0xFFE3D8C2, 0xFFFFFBF2, 1.5f);
        if (ratio > 0) box(c, bx, by, Math.max(bh, bw * ratio), bh, 8, 0xFF9FCB72, 0xFFB5DB8A, 1.5f);
        for (JsonObject m : ms) {
            float px = bx + bw * Math.min(1, J.num(m, "needSeconds") / (float) maxNeed);
            boolean reached = today >= J.num(m, "needSeconds");
            c.imageAt("gacha/confetti_star", px, by + bh / 2, 30, 1, 0, reached ? 0xFFFFFFFF : 0x88FFFFFF);
            c.text(Fmt.time(J.num(m, "needSeconds")).trim(), px, by + 30, 11, reached ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        }

        // 보상 카드 (초록/보라/분홍 반복)
        int[][] col = {{0xFFCFE3B4, 0xFFEAF3DD}, {0xFFD9CCEE, 0xFFF0EAF8}, {0xFFF2CACA, 0xFFFCE8E6}};
        int n = Math.max(1, ms.size());
        float gap = 17, cw = Math.min(160, (510 - gap * (n - 1)) / n), ch = 155;
        float cx0 = 135 + (510 - (cw * n + gap * (n - 1))) / 2, cy = 158;
        boolean anyReady = false;
        for (int i = 0; i < ms.size(); i++) {
            JsonObject m = ms.get(i);
            boolean ready = J.bool(m, "ready"), claimed = J.bool(m, "claimed");
            anyReady |= ready;
            float x = cx0 + i * (cw + gap);
            boolean hov = ready && Anim.in(mx, my, x, cy, cw, ch);
            float lift = hov ? 3 : ready ? (float) Math.abs(Math.sin(now / 350.0 + i)) * 2 : 0;
            int[] cc = col[i % 3];
            box(c, x, cy - lift, cw, ch, 16, ready ? 0xFF8FC25E : cc[0], cc[1], ready ? 3 : 2);
            c.imageFit(FxView.icon(J.str(m, "id"), J.str(m, "label")).equals("reward/event_diamond") ? "fever/gift" : FxView.icon(J.str(m, "id"), J.str(m, "label")),
                    x + cw / 2, cy - lift + 38, 56, claimed ? 0x99FFFFFF : 0xFFFFFFFF);
            box(c, x + 10, cy - lift + 70, cw - 20, 75, 12, cc[0], 0xFFFFFCF5, 1.5f);
            c.textFit(J.str(m, "label"), x + cw / 2, cy - lift + 96, 15, cw - 30, claimed ? Theme.MUTED : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.text(claimed ? "받음" : ready ? "받을 수 있어요!" : Fmt.time(J.num(m, "needSeconds")).trim() + " 접속", x + cw / 2, cy - lift + 122, 12,
                    claimed ? Theme.MUTED : ready ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            if (claimed) c.image("menu/check_badge", x + cw - 36, cy - lift + 6, 30, 30, 0xFFFFFFFF);
            else if (!ready) c.image("menu/lock", x + cw - 34, cy - lift + 6, 26, 26, 0xFFFFFFFF);
            if (ready) hits.add(new Hit("claim", "", x, cy, cw, ch));
        }

        // 버튼
        boolean h1 = anyReady && Anim.in(mx, my, 175, 335, 200, 38);
        c.image(anyReady ? (h1 ? "menu/button_claim_pressed" : "menu/button_claim_normal") : "menu/button_claim_disabled", 175, 335, 200, 38, 0xFFFFFFFF);
        c.text(anyReady ? "보상 받기" : "받을 보상 없음", 275, 354, 16, anyReady ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (anyReady) hits.add(new Hit("claim", "", 175, 335, 200, 38));
        long papers = J.num(p, "failPapers"), need = Math.max(1, J.num(p, "exchangeNeed", 5));
        boolean ex = papers >= need;
        boolean h2 = ex && Anim.in(mx, my, 404, 335, 210, 38);
        c.image(ex ? (h2 ? "menu/button_exchange_pressed" : "menu/button_exchange_normal") : "menu/button_exchange_disabled", 404, 335, 210, 38, 0xFFFFFFFF);
        c.imageFit("menu/icon_losing_paper", 428, 354, 32, 0xFFFFFFFF);
        c.text("꽝 종이 " + papers + "/" + need + " → 고급상자", 519, 354, 14, ex ? Theme.INK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (ex) hits.add(new Hit("exchange", "", 404, 335, 210, 38));
    }

    // ───────── 칭호 ─────────
    private void titles(Canvas c, JsonObject t, float mx, float my) {
        box(c, 117, 70, 543, 316, 18, PANEL_BORDER, PANEL_FILL, 2);
        List<JsonObject> rows = J.objs(t, "rows");
        long owned = rows.stream().filter(r -> J.bool(r, "owned")).count();
        c.text("보유 §2" + owned + "§r / " + rows.size(), 640, 84, 13, Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        float top = 97, viewH = 280, step = 90, rh = 72;
        scrollMax = Math.max(0, rows.size() * step - (viewH + (step - rh)));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            float y = top + i * step - scroll;
            if (y < 88 || y + rh > 384) continue;
            boolean own = J.bool(r, "owned"), sel = J.bool(r, "selected");
            int border = sel ? 0xFFC3DCA4 : own ? 0xFFE6D8C0 : 0xFFD8CCEC, fill = sel ? 0xFFE6F1D8 : own ? 0xFFFAF2E4 : 0xFFEEE8F7;
            boolean hov = own && Anim.in(mx, my, 134, y, 510, rh);
            box(c, 134, y, 510, rh, 14, hov ? 0xFF9FCB72 : border, fill, 2);
            c.image("gacha/jackpot_ribbon", 150, y + rh / 2 - 24, 160, 160 * 247 / 1024f, own ? 0xFFFFFFFF : 0xCCFFFFFF);
            String name = J.str(r, "name");
            c.textFit(own ? name : "§7" + Fmt.strip(name), 230, y + rh / 2 + 1, 17, 100, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(J.str(r, "condition"), 330, y + rh / 2, 15, 230, own ? Theme.INK : Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (sel) c.image("menu/check_badge", 598, y + rh / 2 - 19, 38, 38, 0xFFFFFFFF);
            else if (!own) c.image("menu/lock", 600, y + rh / 2 - 17, 34, 34, 0xFFFFFFFF);
            if (own) hits.add(new Hit("title", sel ? "none" : J.str(r, "id"), 134, y, 510, rh));
            if (Anim.in(mx, my, 134, y, 510, rh))
                hoverTip = own ? (sel ? "&a장착 중 &7(누르면 해제)" : "&e누르면 장착") : "&7아직 없는 칭호";
        }
        scrollbar(c, 650, 92, 284);
    }

    // ───────── 명예의 전당 ─────────
    private void hall(Canvas c, JsonObject hall, float mx, float my) {
        box(c, 117, 70, 543, 316, 18, PANEL_BORDER, PANEL_FILL, 2);
        List<JsonObject> boards = J.objs(hall, "boards");
        if (boards.isEmpty()) { c.text("아직 기록이 없어요", 388, 228, 20, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT); return; }
        board = Math.max(0, Math.min(board, boards.size() - 1));
        // 소분류 버튼 (3개씩 줄)
        int per = 3;
        int lines = (boards.size() + per - 1) / per;
        float sw = 145, shh = lines > 1 ? 27 : 33, gap = 15;
        for (int i = 0; i < boards.size(); i++) {
            int row = i / per, colI = i % per;
            float x = 160 + colI * (sw + gap), y = 82 + row * (shh + 6);
            boolean sel = i == board, hov = Anim.in(mx, my, x, y, sw, shh);
            box(c, x, y, sw, shh, shh / 2, sel ? 0xFF9FCB72 : hov ? 0xFFC9B99C : 0xFFDCCFB8, sel ? 0xFFCDE8A8 : 0xFFF5EEDF, 2);
            c.textFit(J.str(boards.get(i), "label"), x + sw / 2, y + shh / 2, 13, sw - 16, sel ? Theme.GREEN_DARK : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new Hit("board", String.valueOf(i), x, y, sw, shh));
        }
        float top = 82 + lines * (shh + 6) + 10;
        List<JsonObject> rows = J.objs(boards.get(board), "rows");
        if (rows.isEmpty()) { c.text("아직 기록이 없어요", 388, top + 90, 18, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT); return; }
        float viewBottom = 380;
        float total = 0;
        for (int i = 0; i < rows.size(); i++) total += (i < 3 ? 58 : 38) + 10;
        scrollMax = Math.max(0, total - (viewBottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int[][] medal = {{0xFFEBD48C, 0xFFFDF5DA}, {0xFFC7CCE0, 0xFFEDEFF6}, {0xFFE6C3A2, 0xFFFAEBDD}};
        String[] art = {"menu/medal_gold", "menu/medal_silver", "menu/medal_bronze"};
        float y = top - scroll;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            boolean big = i < 3;
            float h = big ? 58 : 38;
            if (y >= top - 2 && y + h <= viewBottom + 2) {
                long rank = J.num(r, "rank", i + 1);
                int[] cc = rank <= 3 ? medal[(int) rank - 1] : new int[]{0xFFE6DCC8, 0xFFFBF6EC};
                box(c, 135, y, 513, h, 12, cc[0], cc[1], 2);
                if (rank <= 3) c.imageFit(art[(int) rank - 1], 180, y + h / 2, h * .95f, 0xFFFFFFFF);
                else c.text(String.valueOf(rank), 180, y + h / 2, 16, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                String t = J.str(r, "title");
                c.textFit((t.isEmpty() ? "" : t + " ") + "§r" + J.str(r, "name"), 222, y + h / 2, big ? 19 : 16, 280, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
                c.textFit(Fmt.num(J.num(r, "value")), 632, y + h / 2, big ? 20 : 16, 120, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            }
            y += h + 10;
        }
        scrollbar(c, 652, top, viewBottom - top);
    }

    private void scrollbar(Canvas c, float x, float y, float h) {
        if (scrollMax <= 0) return;
        float th = h * h / (h + scrollMax);
        c.fill(x, y, 5, h, 2.5f, 0x22000000);
        c.fill(x, y + (h - th) * scroll / scrollMax, 5, th, 2.5f, 0xFF9FCB72);
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        if (!tab.equals("titles") && !tab.equals("hall")) return false;
        scroll = (float) Math.max(0, Math.min(scrollMax, scroll - amount * 40));
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
        float x = lx(mx), y = ly(my);
        for (Hit r : hits) {
            if (!Anim.in(x, y, r.x(), r.y(), r.w(), r.h())) continue;
            switch (r.key()) {
                case "close" -> host.closePanel(this);
                case "tab" -> { if (!r.arg().equals(tab)) { tab = r.arg(); scroll = 0; host.sound("minecraft:item.book.page_turn", 1.2f, .8f); } }
                case "claim" -> { host.send("playtime.claim"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:entity.experience_orb.pickup", 1.2f, .7f); }
                case "exchange" -> { host.send("playtime.exchange"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:block.note_block.pling", 1.2f, .7f); }
                case "title" -> { host.send("title.select", "id", r.arg()); host.send("menu.open", "tab", "titles"); host.sound("minecraft:block.note_block.bell", 1.4f, .6f); }
                case "board" -> { board = Integer.parseInt(r.arg()); scroll = 0; host.sound("minecraft:ui.button.click", 1.2f, .4f); }
                default -> { }
            }
            return true;
        }
        return false;
    }
}
