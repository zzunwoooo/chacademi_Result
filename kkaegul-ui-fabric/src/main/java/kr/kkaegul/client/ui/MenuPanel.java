package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 메뉴 창: 내 정보 / 접속 보상 / 칭호 / 명예의 전당 */
public final class MenuPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private String tab;
    private int board;
    private float scroll, scrollMax;
    private long opened;
    private record R(String key, String arg, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private String hoverTip;

    private static final String[] TABS = {"me", "playtime", "titles", "hall"};
    private static final String[] TAB_ART = {"profile", "attendance", "titles", "ranking"};
    private static final String[] TAB_NAME = {"내 정보", "접속 보상", "칭호", "명예의 전당"};

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

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        hoverTip = null;
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x88000000);
        float fw = Math.min(1280, W - 260), fh = fw * 643 / 1024f;
        if (fh > H - 160) { fh = H - 160; fw = fh * 1024 / 643f; }
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 260));
        c.push();
        c.translate(W / 2, H / 2 + 20);
        c.scale(pop, pop);
        c.translate(-W / 2, -(H / 2 + 20));
        // 마우스 좌표도 같은 변환으로
        float k = 1 / pop;
        mx = W / 2 + (mx - W / 2) * k;
        my = (H / 2 + 20) + (my - (H / 2 + 20)) * k;

        float fx = (W - fw) / 2, fy = (H - fh) / 2 + 30;
        c.image("menu/menu_frame", fx, fy, fw, fh, 0xFFFFFFFF);
        c.imageFit("menu/frog_hanging", fx + fw / 2, fy - 36 + (float) Math.sin(now / 700.0) * 4, 170, 0xFFFFFFFF);

        // 왼쪽 탭
        float ts = 112;
        for (int i = 0; i < 4; i++) {
            boolean sel = TABS[i].equals(tab);
            float tx = fx - ts + 18, ty = fy + 56 + i * (ts + 26);
            boolean hov = Anim.in(mx, my, tx, ty, ts, ts);
            float s = sel ? 1.06f : hov ? 1.03f : 1;
            c.imageAt("menu/tab_" + TAB_ART[i] + (sel ? "_selected" : "_normal"), tx + ts / 2, ty + ts / 2, ts, s, 0, 0xFFFFFFFF);
            c.text(TAB_NAME[i], tx + ts / 2, ty + ts + 6, 17, sel ? Theme.WHITE : 0xDDFFFFFF, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            hits.add(new R("tab", TABS[i], tx, ty, ts, ts + 16));
        }
        // 닫기
        float cs = 58, ccx = fx + fw - 26, ccy = fy + 22;
        boolean ch = Anim.in(mx, my, ccx - cs / 2, ccy - cs / 2, cs, cs);
        c.imageFit(ch ? "menu/close_pressed" : "menu/close_normal", ccx, ccy, cs, 0xFFFFFFFF);
        hits.add(new R("close", "", ccx - cs / 2, ccy - cs / 2, cs, cs));

        float ix = fx + fw * .055f, iy = fy + fh * .085f, iw = fw * .89f, ih = fh * .84f;
        String title = TAB_NAME[indexOf(tab)];
        c.text(title, ix, iy + 20, 38, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        float top = iy + 66;
        switch (tab) {
            case "playtime" -> playtime(c, J.objOrEmpty(data, "playtime"), ix, top, iw, ih - 66, mx, my);
            case "titles" -> titles(c, J.objOrEmpty(data, "titles"), ix, top, iw, ih - 66, mx, my);
            case "hall" -> hall(c, J.objOrEmpty(data, "hall"), ix, top, iw, ih - 66, mx, my);
            default -> me(c, J.objOrEmpty(data, "me"), ix, top, iw, ih - 66, mx, my);
        }
        c.pop();
        if (hoverTip != null) Theme.tooltip(c, hoverTip, mx, my);
    }

    private static int indexOf(String t) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(t)) return i;
        return 0;
    }

    // ───────── 내 정보 ─────────
    private void me(Canvas c, JsonObject m, float x, float y, float w, float h, float mx, float my) {
        String name = J.str(m, "name", J.str(m, "account", ""));
        String title = J.str(m, "title");
        c.text((title.isEmpty() ? "" : title + " ") + "§r" + name, x + w, y - 46, 26, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);

        // 위: 큰 숫자 3개
        String[][] big = {{"hud/target_reticle", "남은 다이아", Fmt.num(J.num(m, "debt"))},
                {"hud/diamond", "보유 다이아", Fmt.num(J.num(m, "held"))},
                {"hud/frog_coin", "깨굴코인", Fmt.num(J.num(m, "coins"))}};
        float bw = (w - 40) / 3, bh = 92;
        for (int i = 0; i < 3; i++) {
            float bx = x + i * (bw + 20);
            c.fill(bx, y, bw, bh, 24, 0xFFF9EED8);
            c.fill(bx + 3, y + 3, bw - 6, bh - 6, 21, 0xFFFFF9EE);
            c.imageFit(big[i][0], bx + 52, y + bh / 2, 58, 0xFFFFFFFF);
            c.text(big[i][1], bx + 96, y + 30, 19, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(big[i][2], bx + 96, y + 62, 34, bw - 110, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        }
        // 아래: 누적 기록 6칸
        String[][] stats = {
                {"menu/icon_diamond", "다이아 누적 증가", Fmt.num(J.num(m, "totalIncreased"))},
                {"menu/icon_submit", "누적 제출", Fmt.num(J.num(m, "totalSubmitted"))},
                {"menu/icon_donation", "누적 기부", Fmt.num(J.num(m, "totalDonated"))},
                {"menu/icon_jail", "깜빵", J.num(m, "jailCount") + "회"},
                {"menu/icon_broken_pickaxe", "곡괭이 파괴", J.num(m, "pickaxeBreaks") + "회"},
                {"menu/icon_death", "즉사", J.num(m, "instantDeaths") + "회"}};
        float rw = (w - 30) / 2, rh = rw * 168 / 1024f, gy = y + bh + 28;
        for (int i = 0; i < 6; i++) {
            float rx = x + (i % 2) * (rw + 30), ry = gy + (i / 2) * (rh + 16);
            c.image("menu/stat_row", rx, ry, rw, rh, 0xFFFFFFFF);
            c.imageFit(stats[i][0], rx + rw * .085f, ry + rh / 2, rh * .58f, 0xFFFFFFFF);
            c.text(stats[i][1], rx + rw * .19f, ry + rh / 2, 22, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(stats[i][2], rx + rw * .95f, ry + rh / 2, 28, rw * .35f, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        }
        float ly = gy + 3 * (rh + 16) + 18;
        JsonObject g = J.objOrEmpty(m, "gauge");
        String line = "§7운빨 게이지 §r" + J.num(g, "value") + "/" + J.num(g, "max", 100) + "    §7가챠 최고 수치 §r" + J.num(m, "bestPickaxe")
                + "    §7서버 " + J.num(m, "day") + "일차";
        c.textFit(line, x + w / 2, ly, 22, w, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    // ───────── 접속 보상 ─────────
    private void playtime(Canvas c, JsonObject p, float x, float y, float w, float h, float mx, float my) {
        long today = J.num(p, "todaySeconds");
        List<JsonObject> ms = J.objs(p, "milestones");
        long maxNeed = 1;
        for (JsonObject m : ms) maxNeed = Math.max(maxNeed, J.num(m, "needSeconds"));
        c.text("오늘 접속 §2" + Fmt.time(today), x + w, y - 46, 24, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);

        // 진행 막대 + 말풍선 표시
        float tx = x + 30, tw = w - 60, ty = y + 92, th = tw * 71 / 1024f;
        float ratio = Math.min(1, today / (float) maxNeed);
        c.image("menu/progress_track", tx, ty, tw, th, 0xFFFFFFFF);
        if (ratio > 0) c.imagePart("menu/progress_fill", tx, ty - th * .2f, tw * ratio, th * 1.42f, 0, 0, ratio, 1, 0xFFFFFFFF);
        String[] markers = {"menu/progress_marker_short", "menu/progress_marker_medium", "menu/progress_marker_long"};
        for (int i = 0; i < ms.size(); i++) {
            JsonObject m = ms.get(i);
            float px = tx + tw * Math.min(1, J.num(m, "needSeconds") / (float) maxNeed);
            boolean done = J.bool(m, "claimed") || J.bool(m, "ready");
            c.imageFit(markers[Math.min(2, i * 3 / Math.max(1, ms.size()))], px, ty - 44, 74, done ? 0xFFFFFFFF : 0xC0FFFFFF);
            c.text(Fmt.time(J.num(m, "needSeconds")).trim(), px, ty - 50, 16, done ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        }

        // 보상 카드
        int n = Math.max(1, ms.size());
        float gap = 18, cw = Math.min(190, (w - gap * (n - 1)) / n), chh = cw * 512 / 499f;
        float cx0 = x + (w - (cw * n + gap * (n - 1))) / 2, cy = ty + 66;
        boolean anyReady = false;
        for (int i = 0; i < ms.size(); i++) {
            JsonObject m = ms.get(i);
            boolean ready = J.bool(m, "ready"), claimed = J.bool(m, "claimed");
            anyReady |= ready;
            String art = claimed ? "menu/reward_card_claimed" : ready ? "menu/reward_card_ready" : "menu/reward_card_locked";
            float cx = cx0 + i * (cw + gap);
            boolean hov = ready && Anim.in(mx, my, cx, cy, cw, chh);
            float bob = ready ? (float) Math.sin(System.currentTimeMillis() / 300.0 + i) * 3 : 0;
            c.image(art, cx, cy + bob - (hov ? 4 : 0), cw, chh, 0xFFFFFFFF);
            c.imageFit(FxView.icon(J.str(m, "id"), J.str(m, "label")), cx + cw / 2, cy + bob + chh * .38f, cw * .42f, claimed ? 0x90FFFFFF : 0xFFFFFFFF);
            c.textFit(J.str(m, "label"), cx + cw / 2, cy + bob + chh * .74f, 18, cw * .84f, claimed ? Theme.MUTED : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            if (ready) hits.add(new R("claim", "", cx, cy, cw, chh));
        }

        // 버튼: 모두 받기 / 꽝 종이 교환
        float by = cy + chh + 34, bw = Math.min(380, w / 2 - 30), bh = bw * 141 / 1024f;
        float b1 = x + w / 2 - bw - 20, b2 = x + w / 2 + 20;
        boolean h1 = anyReady && Anim.in(mx, my, b1, by, bw, bh);
        c.image(anyReady ? (h1 ? "menu/button_claim_pressed" : "menu/button_claim_normal") : "menu/button_claim_disabled", b1, by, bw, bh, 0xFFFFFFFF);
        c.text(anyReady ? "보상 받기" : "받을 보상 없음", b1 + bw / 2, by + bh / 2, 24, anyReady ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (anyReady) hits.add(new R("claim", "", b1, by, bw, bh));
        long papers = J.num(p, "failPapers"), need = Math.max(1, J.num(p, "exchangeNeed", 5));
        boolean ex = papers >= need;
        boolean h2 = ex && Anim.in(mx, my, b2, by, bw, bh);
        c.image(ex ? (h2 ? "menu/button_exchange_pressed" : "menu/button_exchange_normal") : "menu/button_exchange_disabled", b2, by, bw, bh, 0xFFFFFFFF);
        c.imageFit("menu/icon_losing_paper", b2 + 40, by + bh / 2, bh * .9f, 0xFFFFFFFF);
        c.text("꽝 종이 " + papers + "/" + need + " → 고급상자", b2 + bw / 2 + 16, by + bh / 2, 21, ex ? Theme.INK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (ex) hits.add(new R("exchange", "", b2, by, bw, bh));
    }

    // ───────── 칭호 ─────────
    private void titles(Canvas c, JsonObject t, float x, float y, float w, float h, float mx, float my) {
        List<JsonObject> rows = J.objs(t, "rows");
        long owned = rows.stream().filter(r -> J.bool(r, "owned")).count();
        c.text("보유 §2" + owned + "§r / " + rows.size(), x + w, y - 46, 24, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        float colW = (w - 24) / 2, rh = colW * 120 / 1024f + 14;
        int perCol = Math.max(1, (int) ((h - 10) / (rh + 10)));
        int rowsNeeded = (rows.size() + 1) / 2;
        scrollMax = Math.max(0, (rowsNeeded - perCol) * (rh + 10));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            float rx = x + (i % 2) * (colW + 24), ry = y + (i / 2) * (rh + 10) - scroll;
            if (ry < y - 4 || ry + rh > y + h + 4) continue;
            boolean own = J.bool(r, "owned"), sel = J.bool(r, "selected");
            String art = sel ? "menu/title_row_selected" : own ? "menu/title_row_owned" : "menu/title_row_locked";
            boolean hov = own && Anim.in(mx, my, rx, ry, colW, rh);
            c.image(art, rx, ry, colW, rh, hov ? 0xFFFFFFFF : own ? 0xF2FFFFFF : 0xFFFFFFFF);
            String nm = J.str(r, "name");
            c.textFit(own ? nm : Fmt.strip(nm), rx + 28, ry + rh * .36f, 24, colW * .8f, own ? Theme.INK : 0xFF9A948C, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(J.str(r, "condition"), rx + 28, ry + rh * .72f, 16, colW * .8f, Theme.MUTED, Canvas.LEFT, Canvas.LIGHT);
            if (own) hits.add(new R("title", sel ? "none" : J.str(r, "id"), rx, ry, colW, rh));
            if (Anim.in(mx, my, rx, ry, colW, rh))
                hoverTip = (own ? (sel ? "&a장착 중 &7(누르면 해제)" : "&e누르면 장착") : "&7아직 없는 칭호") + "\n&f" + J.str(r, "condition");
        }
        if (scrollMax > 0) {
            float sh = h * h / (h + scrollMax);
            c.fill(x + w + 14, y, 8, h, 4, 0x30000000);
            c.fill(x + w + 14, y + (h - sh) * scroll / scrollMax, 8, sh, 4, 0xFF8DBF63);
        }
    }

    // ───────── 명예의 전당 ─────────
    private void hall(Canvas c, JsonObject hall, float x, float y, float w, float h, float mx, float my) {
        List<JsonObject> boards = J.objs(hall, "boards");
        if (boards.isEmpty()) { c.text("아직 기록이 없어요", x + w / 2, y + h / 2, 28, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT); return; }
        board = Math.max(0, Math.min(board, boards.size() - 1));
        float sw = (w - (boards.size() - 1) * 10) / boards.size(), sh = 50;
        for (int i = 0; i < boards.size(); i++) {
            boolean sel = i == board;
            float sx = x + i * (sw + 10);
            c.image(sel ? "menu/ranking_subtab_selected" : "menu/ranking_subtab_normal", sx, y - 4, sw, sh, 0xFFFFFFFF);
            c.textFit(J.str(boards.get(i), "label"), sx + sw / 2, y - 4 + sh / 2, 18, sw * .86f, sel ? Theme.GREEN_DARK : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R("board", String.valueOf(i), sx, y - 4, sw, sh));
        }
        List<JsonObject> rows = J.objs(boards.get(board), "rows");
        float ry = y + sh + 12, rh = Math.min(56, (h - sh - 20) / Math.max(10, rows.size()) - 4);
        float rw = w;
        if (rows.isEmpty()) c.text("아직 기록이 없어요", x + w / 2, ry + 120, 26, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        String[] medal = {"menu/medal_gold", "menu/medal_silver", "menu/medal_bronze"};
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            float yy = ry + i * (rh + 4);
            c.image("menu/ranking_row", x, yy, rw, rh, 0xFFFFFFFF);
            long rank = J.num(r, "rank", i + 1);
            if (rank <= 3) c.imageFit(medal[(int) rank - 1], x + rw * .095f, yy + rh / 2, rh * .92f, 0xFFFFFFFF);
            else c.text(String.valueOf(rank), x + rw * .095f, yy + rh / 2, 24, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            String t = J.str(r, "title");
            c.textFit((t.isEmpty() ? "" : t + " ") + "§r" + J.str(r, "name"), x + rw * .19f, yy + rh / 2, 24, rw * .48f, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(Fmt.num(J.num(r, "value")), x + rw * .95f, yy + rh / 2, 26, rw * .24f, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        }
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        if (!tab.equals("titles")) return false;
        scroll = (float) Math.max(0, Math.min(scrollMax, scroll - amount * 60));
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
        // render 에서 쓴 팝업 변환과 같은 좌표계 (열림 애니 끝나면 1배)
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            switch (r.key()) {
                case "close" -> host.closePanel(this);
                case "tab" -> { if (!r.arg().equals(tab)) { tab = r.arg(); scroll = 0; host.sound("minecraft:item.book.page_turn", 1.2f, .8f); } }
                case "claim" -> { host.send("playtime.claim"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:entity.experience_orb.pickup", 1.2f, .7f); }
                case "exchange" -> { host.send("playtime.exchange"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:block.note_block.pling", 1.2f, .7f); }
                case "title" -> { host.send("title.select", "id", r.arg()); host.send("menu.open", "tab", "titles"); host.sound("minecraft:block.note_block.bell", 1.4f, .6f); }
                case "board" -> { board = Integer.parseInt(r.arg()); host.sound("minecraft:ui.button.click", 1.2f, .4f); }
                default -> { }
            }
            return true;
        }
        return false;
    }
}
