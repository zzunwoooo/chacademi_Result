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
    private float scroll, scrollMax, scrollShown;
    private final long opened;
    private record Hit(String key, String arg, float x, float y, float w, float h) {}
    private final List<Hit> hits = new ArrayList<>();
    private String hoverTip;
    private float k = 1, fx, fy; // 시안 단위 → 화면

    private static final String[] TABS = {"me", "playtime", "titles", "hall"};
    private static final String[] TAB_ART = {"profile", "attendance", "titles", "ranking"};
    private static final String[] TAB_NAME = {"내 정보", "접속 보상", "칭호", "명예의 전당"};

    // 시안 색
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

    /** 그림을 가로로 잘라 그리기: 텍스처 u 지점 us[i] 를 화면 x 지점 xs[i] 에 맞춤 (세로는 통째로) */
    static void slices(Canvas c, String id, float y, float h, float[] us, float[] xs, int argb) {
        for (int i = 0; i + 1 < us.length; i++)
            if (xs[i + 1] > xs[i]) c.imagePart(id, xs[i], y, xs[i + 1] - xs[i], h, us[i], 0, us[i + 1], 1, argb);
    }

    /** 가로 3조각: 양끝(uL·uR 만큼)은 그림 비율 그대로, 가운데만 늘림 — 알약 모양 줄·버튼용 */
    static void strip(Canvas c, String id, float x, float y, float w, float h, float uL, float uR, int argb) {
        int[] s = c.size(id);
        float tw = s == null ? h * 8 : s[0] * h / s[1];                  // 높이 h 로 맞췄을 때 그림 전체 너비
        float l = Math.min(w / 2, uL * tw), r = Math.min(w / 2, uR * tw);
        slices(c, id, y, h, new float[]{0, uL, 1 - uR, 1}, new float[]{x, x + l, x + w - r, x + w}, argb);
    }

    /**
     * strip 과 같지만, 그림 몸통(예전 자른 범위) 바깥에 테두리·그림자 여백이 붙은 그림용.
     * pad = 그림 픽셀 기준 왼·위·오른·아래 여백. 몸통이 (x,y,w,h) 에 오고 여백은 바깥으로 삐져나와 그려짐.
     */
    static void stripPad(Canvas c, String id, float x, float y, float w, float h, float uL, float uR, float[] pad, int argb) {
        int[] s = c.size(id);
        float s1 = s == null ? 0 : h / (s[1] - pad[1] - pad[3]);            // 그림 1픽셀 → 화면
        strip(c, id, x - pad[0] * s1, y - pad[1] * s1, w + (pad[0] + pad[2]) * s1, h + (pad[1] + pad[3]) * s1, uL, uR, argb);
    }

    // 다시 뽑은 그림의 몸통 바깥 여백 (그림 픽셀, 왼·위·오른·아래)
    private static final float[] PAD_SUBTAB_NORMAL = {14, 2, 14, 2};
    private static final float[] PAD_TITLE_LOCKED = {2.4f, 1.4f, 2.8f, .5f};
    private static final float[] PAD_TITLE_OWNED = {1.9f, 2.4f, 1.9f, 0};
    private static final float[] PAD_TITLE_SELECTED = {2, 2, 2, 3};

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

        // 프레임 (금·연두 이중 테두리 그림, 네 귀퉁이 보석·클로버는 안 늘어나게 9조각)
        c.imageNine("menu/menu_frame", 0, 0, 680, 407, .1f, .16f, 46, 46, 0xFFFFFFFF);
        // 깨굴이 간판 + 제목
        float sw = 250, sh = sw * 312 / 512f, sy = 26 - sh * .79f;
        float bob = (float) Math.sin(now / 700.0) * 1.5f;
        c.image("gacha_machine/frog_sign_gentle", 340 - sw / 2, sy + bob, sw, sh, 0xFFFFFFFF);
        c.textFit(TAB_NAME[indexOf(tab)], 340, sy + bob + sh * .748f, 30, 180, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 닫기
        boolean ch = Anim.in(mxR, myR, 615, 12, 44, 44);
        c.image(ch ? "menu/close_pressed" : "menu/close_normal", 615, 12, 44, 44, 0xFFFFFFFF);
        hits.add(new Hit("close", "", 615, 12, 44, 44));

        // 왼쪽 탭
        for (int i = 0; i < 4; i++) {
            boolean sel = TABS[i].equals(tab);
            float tx = 22, ty = 75 + i * 76.67f;                          // 4개가 오른쪽 판(70~376) 높이에 고르게
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
        // 머리를 안쪽 판에 꽉 차게 그리고 판의 둥근 모서리에 맞춰 잘라냄
        if (c.face(J.str(m, "account"), 141, 92, 152)) c.cornerMask(141, 92, 152, 152, 15, 0xFFFBF7EC);
        else c.imageFit("hud/frog_smile", 217, 169, 130, 0xFFFFFFFF);
        String title = J.str(m, "title");
        if (!title.isEmpty()) c.textFit(title, 217, 256, 13, 170, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.image("gacha/jackpot_ribbon", 132, 262, 170, 170 * 247 / 1024f, 0xFFFFFFFF);
        c.textArc(J.str(m, "name", J.str(m, "account")), 217, 262 + 170 * 247 / 1024f * Theme.RIBBON_CY, 17, Theme.ribbonR(170), 120, Theme.INK, Canvas.BOLD | Canvas.LIGHT);
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
        for (int i = 0; i < 6; i++) {
            float y = 70 + i * 51;
            // stat_row: 왼쪽 아이콘 칸(u .03~.16) + 오른쪽 긴 칸, 아이콘 칸까지는 안 늘림
            strip(c, "menu/stat_row", 328, y, 326, 44, .18f, .04f, 0xFFFFFFFF);
            c.imageFit(rows[i][0], 352.6f, y + 22, 27, 0xFFFFFFFF);
            c.text(rows[i][1], 386, y + 22, 15, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(rows[i][2], 636, y + 22, 19, 110, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        }
        // 맨 아래: 남은/보유 다이아 · 코인 · 게이지
        JsonObject g = J.objOrEmpty(m, "gauge");
        String line = "§7남은 다이아 §r" + Fmt.num(J.num(m, "debt")) + "   §7보유 §r" + Fmt.num(J.num(m, "held"))
                + "   §7코인 §r" + Fmt.num(J.num(m, "coins")) + "   §7운빨 §r" + J.num(g, "value") + "   §7" + dayText(J.num(m, "day"));
        c.textFit(line, 486, 385, 13, 296, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    /** 서버 일차: 오픈 전(0 이하)이면 "오픈 D-3" */
    static String dayText(long day) { return day >= 1 ? day + "일차" : "오픈 D-" + (1 - day); }

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
        strip(c, "menu/progress_track", bx, by, bw, bh, .035f, .035f, 0xFFFFFFFF);
        if (ratio > 0) strip(c, "menu/progress_fill", bx, by - 1, Math.max(bh, bw * ratio), bh + 2, .05f, .05f, 0xFFFFFFFF);
        // 눈금: 처음 short · 중간 medium · 마지막 long
        for (int i = 0; i < ms.size(); i++) {
            JsonObject m = ms.get(i);
            float px = bx + bw * Math.min(1, J.num(m, "needSeconds") / (float) maxNeed);
            boolean reached = today >= J.num(m, "needSeconds");
            String mk = i == ms.size() - 1 ? "long" : i == 0 ? "short" : "medium";
            c.imageAt("menu/progress_marker_" + mk, px, by + bh / 2, 24, 1, 0, reached ? 0xFFFFFFFF : 0xAAFFFFFF);
            c.text(Fmt.time(J.num(m, "needSeconds")).trim(), px, by + 30, 11, reached ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        }

        // 보상 카드 (받음/잠김/받기 그림 — 체크·자물쇠는 그림에 들어 있음)
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
            // 정사각 그림을 세로로 긴 카드에 맞춤: 아래 체크·자물쇠·띠는 귀퉁이 칸에 들어가게 9조각
            if (ready) // 받기 카드는 바깥 금테가 더 큼: 몸통은 다른 카드 자리 그대로, 바깥 테는 둘레로 삐져나오게
                c.imageNine("menu/reward_card_ready", x - cw * .0733f, cy - lift - cw * .0744f, cw * 1.1466f, ch + cw * .1488f,
                        .3257f, .3607f, cw * .3733f, cw * .4144f, 0xFFFFFFFF);
            else c.imageNine(claimed ? "menu/reward_card_claimed" : "menu/reward_card_locked",
                    x, cy - lift, cw, ch, .3f, .34f, cw * .3f, cw * .34f, 0xFFFFFFFF);
            c.imageFit(FxView.icon(J.str(m, "id"), J.str(m, "label")).equals("reward/event_diamond") ? "fever/gift" : FxView.icon(J.str(m, "id"), J.str(m, "label")),
                    x + cw / 2, cy - lift + 44, 50, claimed ? 0x99FFFFFF : 0xFFFFFFFF);
            c.textFit(J.str(m, "label"), x + cw / 2, cy - lift + 88, 15, cw - 26, claimed ? Theme.MUTED : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(claimed ? "받음" : ready ? "받을 수 있어요!" : Fmt.time(J.num(m, "needSeconds")).trim() + " 접속", x + cw / 2, cy - lift + 107, 12, cw - 26,
                    claimed ? Theme.MUTED : ready ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
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
        // 종이 아이콘은 버튼 안쪽 테두리(높이 약 28) 안에 들어가게, 둥근 왼쪽 끝을 피해 조금 안쪽으로
        c.imageFit("menu/icon_losing_paper", 433, 354, 25, 0xFFFFFFFF);
        c.textFit("꽝 종이 " + papers + "/" + need + " → 고급상자", 526, 354, 14, 150, ex ? Theme.INK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
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
        scrollShown += (scroll - scrollShown) * .25f;                     // 부드럽게 따라감
        if (Math.abs(scroll - scrollShown) < .3f) scrollShown = scroll;
        c.clip(118, 91, 541, 293);                                         // 판 안쪽만 보이게 (잘린 줄도 반만 보임)
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            float y = top + i * step - scrollShown;
            if (y + rh < 88 || y > 386) continue;
            boolean own = J.bool(r, "owned"), sel = J.bool(r, "selected");
            boolean hov = own && Anim.in(mx, my, 134, y, 510, rh);
            // 장착(체크)·잠김(자물쇠)은 그림 오른쪽 끝에 들어 있어서 오른쪽 15%는 안 늘림
            stripPad(c, sel ? "menu/title_row_selected" : own ? "menu/title_row_owned" : "menu/title_row_locked", 134, y, 510, rh, .07f, .15f,
                    sel ? PAD_TITLE_SELECTED : own ? PAD_TITLE_OWNED : PAD_TITLE_LOCKED, hov && !sel ? 0xFFEFFFE0 : 0xFFFFFFFF);
            c.image("gacha/jackpot_ribbon", 150, y + rh / 2 - 20, 160, 160 * 247 / 1024f, own ? 0xFFFFFFFF : 0xCCFFFFFF);
            String name = J.str(r, "name");
            c.textArc(own ? name : "§7" + Fmt.strip(name), 230, y + rh / 2 - 20 + 160 * 247 / 1024f * Theme.RIBBON_CY, 16, Theme.ribbonR(160), 110, Theme.INK, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(J.str(r, "condition"), 330, y + rh / 2, 15, 230, own ? Theme.INK : Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (own) { float hy = Math.max(91, y), hb = Math.min(384, y + rh); if (hb > hy) hits.add(new Hit("title", sel ? "none" : J.str(r, "id"), 134, hy, 510, hb - hy)); }
            if (Anim.in(mx, my, 134, y, 510, rh) && my > 91 && my < 384)
                hoverTip = own ? (sel ? "&a장착 중 &7(누르면 해제)" : "&e누르면 장착") : "&7아직 없는 칭호";
        }
        c.unclip();
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
            if (sel) strip(c, "menu/ranking_subtab_selected", x, y, sw, shh, .1f, .1f, 0xFFFFFFFF);
            else stripPad(c, "menu/ranking_subtab_normal", x, y, sw, shh, .1f, .1f, PAD_SUBTAB_NORMAL, hov ? 0xFFF4FFE8 : 0xFFFFFFFF);
            c.textFit(J.str(boards.get(i), "label"), x + sw / 2, y + shh / 2, 13, sw - 20, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
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
        scrollShown += (scroll - scrollShown) * .25f;
        if (Math.abs(scroll - scrollShown) < .3f) scrollShown = scroll;
        c.clip(118, top - 2, 541, viewBottom - top + 4);
        int[] medal = {0xFFFFF0C4, 0xFFF0F2FA, 0xFFFFE8DA};                 // 1~3등 줄은 메달 빛으로 살짝 물들임
        String[] art = {"menu/medal_gold", "menu/medal_silver", "menu/medal_bronze"};
        float y = top - scrollShown;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            boolean big = i < 3;
            float h = big ? 58 : 38;
            if (y + h >= top - 2 && y <= viewBottom + 2) {
                long rank = J.num(r, "rank", i + 1);
                // ranking_row: 칸막이 두 줄(u .174 / .694)을 순위칸·이름칸 끝(212 / 502)에 맞춤
                float cap = .05f * 1024 * h / 112;
                slices(c, "menu/ranking_row", y, h, new float[]{0, .05f, .172f, .178f, .692f, .697f, .95f, 1},
                        new float[]{135, 135 + cap, 211, 213, 501, 503, 648 - cap, 648}, rank <= 3 ? medal[(int) rank - 1] : 0xFFFFFFFF);
                if (rank <= 3) c.imageFit(art[(int) rank - 1], 180, y + h / 2, h * .95f, 0xFFFFFFFF);
                else c.text(String.valueOf(rank), 180, y + h / 2, 16, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                String t = J.str(r, "title");
                c.textFit((t.isEmpty() ? "" : t + " ") + "§r" + J.str(r, "name"), 222, y + h / 2, big ? 19 : 16, 270, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
                c.textFit(Fmt.num(J.num(r, "value")), 624, y + h / 2, big ? 20 : 16, 112, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            }
            y += h + 10;
        }
        c.unclip();
        scrollbar(c, 652, top, viewBottom - top);
    }

    private void scrollbar(Canvas c, float x, float y, float h) {
        if (scrollMax <= 0) return;
        float th = h * h / (h + scrollMax);
        c.fill(x, y, 5, h, 2.5f, 0x22000000);
        c.fill(x, y + (h - th) * Math.max(0, Math.min(1, scrollShown / scrollMax)), 5, th, 2.5f, 0xFF9FCB72);
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
                case "tab" -> { if (!r.arg().equals(tab)) { tab = r.arg(); scroll = 0; scrollShown = 0; host.sound("minecraft:item.book.page_turn", 1.2f, .8f); } }
                case "claim" -> { host.send("playtime.claim"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:entity.experience_orb.pickup", 1.2f, .7f); }
                case "exchange" -> { host.send("playtime.exchange"); host.send("menu.open", "tab", "playtime"); host.sound("minecraft:block.note_block.pling", 1.2f, .7f); }
                case "title" -> { host.send("title.select", "id", r.arg()); host.send("menu.open", "tab", "titles"); host.sound("minecraft:block.note_block.bell", 1.4f, .6f); }
                case "board" -> { board = Integer.parseInt(r.arg()); scroll = 0; scrollShown = 0; host.sound("minecraft:ui.button.click", 1.2f, .4f); }
                default -> { }
            }
            return true;
        }
        return false;
    }
}
