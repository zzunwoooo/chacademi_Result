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

    /**
     * stripPad 에 가운데 장식(왕관 등, 그림 u c0~c1)까지 안 늘어나게 한 5조각.
     * 장식은 제 비율로 그림에서와 같은 상대 위치에 두고, 그 양옆 민무늬 칸만 늘이거나 줄임.
     */
    static void stripMid(Canvas c, String id, float x, float y, float w, float h, float uL, float c0, float c1, float uR, float[] pad, int argb) {
        int[] s = c.size(id);
        if (s == null) { c.image(id, x, y, w, h, argb); return; }
        float k1 = h / (s[1] - pad[1] - pad[3]);
        float X = x - pad[0] * k1, Y = y - pad[1] * k1, Wt = w + (pad[0] + pad[2]) * k1, Ht = h + (pad[1] + pad[3]) * k1;
        float tw = s[0] * k1;
        float l = Math.min(Wt / 2, uL * tw), r = Math.min(Wt / 2, uR * tw);
        float mw = (c1 - c0) * tw, mc = X + Wt * (c0 + c1) / 2;
        float m0 = Math.max(X + l, mc - mw / 2), m1 = Math.min(X + Wt - r, mc + mw / 2);
        slices(c, id, Y, Ht, new float[]{0, uL, c0, c1, 1 - uR, 1}, new float[]{X, X + l, m0, m1, X + Wt - r, X + Wt}, argb);
    }

    /** slices 의 여백 붙은 그림판: us 는 몸통 기준 u, 몸통 바깥 여백은 양끝·위아래로 제 비율로 삐져나옴 */
    static void slicesPad(Canvas c, String id, float y, float h, float[] us, float[] xs, float[] pad, int argb) {
        int[] s = c.size(id);
        if (s == null) { slices(c, id, y, h, us, xs, argb); return; }
        float k1 = h / (s[1] - pad[1] - pad[3]), bw = s[0] - pad[0] - pad[2];
        int n = us.length;
        float[] u2 = new float[n + 2], x2 = new float[n + 2];
        u2[0] = 0; x2[0] = xs[0] - pad[0] * k1;
        for (int i = 0; i < n; i++) { u2[i + 1] = (pad[0] + us[i] * bw) / s[0]; x2[i + 1] = xs[i]; }
        u2[n + 1] = 1; x2[n + 1] = xs[n - 1] + pad[2] * k1;
        slices(c, id, y - pad[1] * k1, h + (pad[1] + pad[3]) * k1, u2, x2, argb);
    }

    /** 몸통(예전 자른 범위)을 (x,y,w,h) 에, 여백은 바깥으로 */
    static void imagePad(Canvas c, String id, float x, float y, float w, float h, float[] pad, int argb) {
        int[] s = c.size(id);
        if (s == null) { c.image(id, x, y, w, h, argb); return; }
        float sx = w / (s[0] - pad[0] - pad[2]), sy = h / (s[1] - pad[1] - pad[3]);
        c.image(id, x - pad[0] * sx, y - pad[1] * sy, w + (pad[0] + pad[2]) * sx, h + (pad[1] + pad[3]) * sy, argb);
    }

    // 다시 뽑은 그림(v3)의 몸통 바깥 여백 (그림 픽셀, 왼·위·오른·아래)
    private static final float[] PAD_FRAME = {3.4f, 25.5f, 6.0f, 1.3f};                // 위 = 가운데 왕관
    private static final float[] PAD_CLOSE = {1.4f, 1.4f, 1.4f, 9.9f};                 // 아래 = 클로버·보석
    private static final float[] PAD_CLAIM = {2.2f, 1.1f, 2.2f, 1.1f};
    private static final float[] PAD_TAB_TITLES_SEL = {2.5f, .8f, .8f, .8f};
    private static final float[] PAD_STAT = {1, 31.5f, 1, 1};                          // 위 = 가운데 왕관·양끝 보석
    private static final float[] PAD_RANK_ROW = {3.9f, 20.9f, 2.4f, 3.9f};             // 위 = 왼쪽 보석·클로버
    private static final float[] PAD_SUBTAB_NORMAL = {3, 49, 4, 10};                   // 몸통 = 알약 전체, 위 = 왕관
    private static final float[] PAD_SUBTAB_SELECTED = {1, 3.6f, 1.5f, 1};
    private static final float[] PAD_TITLE_LOCKED = {2.8f, 3.3f, 2.8f, 1.4f};
    private static final float[] PAD_TITLE_OWNED = {2.9f, 5.2f, 2.9f, 1.4f};
    private static final float[] PAD_TITLE_SELECTED = {3, 4, 3.5f, 4};

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

        // 프레임: v3 는 귀퉁이 장식이 커서 9조각이면 장식이 잘리거나 늘어남 → 통째로 (가로세로 비율 차 5% 뿐), 위 왕관은 여백으로 삐져나옴
        imagePad(c, "menu/menu_frame", 0, 0, 680, 407, PAD_FRAME, 0xFFFFFFFF);
        // 오른쪽 판 (탭마다 모양만 다름)
        if (tab.equals("playtime") || tab.equals("titles") || tab.equals("hall")) box(c, 117, 70, 543, 316, 18, PANEL_BORDER, PANEL_FILL, 2);
        else box(c, 117, 70, 195, 306, 16, 0xFFDCEAC8, 0xFFEEF5E2, 2);
        // 프레임 네 귀퉁이 장식(크림 바탕 뺀 그림)을 판 위에 한 번 더: 판이 장식을 덮지 않게. 탭·내용은 그 위
        imagePad(c, "menu/menu_frame_deco", 0, 0, 680, 407, PAD_FRAME, 0xFFFFFFFF);
        // 깨굴이 간판 + 제목
        float sw = 250, sh = sw * 312 / 512f, sy = 26 - sh * .79f;
        float bob = (float) Math.sin(now / 700.0) * 1.5f;
        c.image("gacha_machine/frog_sign_gentle", 340 - sw / 2, sy + bob, sw, sh, 0xFFFFFFFF);
        c.textFit(TAB_NAME[indexOf(tab)], 340, sy + bob + sh * .748f, 30, 180, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 닫기: 오른쪽 위 클로버·보석 장식 바로 왼쪽
        float cbx = 552, cby = 14;
        boolean ch = Anim.in(mxR, myR, cbx, cby, 44, 44);
        if (ch) c.image("menu/close_pressed", cbx, cby, 44, 44, 0xFFFFFFFF);
        else imagePad(c, "menu/close_normal", cbx, cby, 44, 44, PAD_CLOSE, 0xFFFFFFFF);
        hits.add(new Hit("close", "", cbx, cby, 44, 44));

        // 왼쪽 탭
        for (int i = 0; i < 4; i++) {
            boolean sel = TABS[i].equals(tab);
            float tx = 22, ty = 75 + i * 76.67f;                          // 4개가 오른쪽 판(70~376) 높이에 고르게
            boolean hov = Anim.in(mxR, myR, tx, ty, 80, 66);
            String tid = "menu/tab_" + TAB_ART[i] + (sel ? "_selected" : "_normal");
            float th = 76, tcx = tx + 40, tcy = ty + 33;
            int[] ts = tid.equals("menu/tab_titles_selected") ? c.size(tid) : null;
            if (ts != null) { // 이 그림만 v3 장식이 몸통 밖으로 나와 여백을 붙여 뽑음: 몸통이 다른 탭과 같은 크기·자리에
                float[] p = PAD_TAB_TITLES_SEL;
                float k1 = th / (ts[1] - p[1] - p[3]);
                tcx += (p[0] - p[2]) / 2 * k1; tcy += (p[1] - p[3]) / 2 * k1; th = ts[1] * k1;
            }
            c.imageAt(tid, tcx, tcy, th, hov && !sel ? 1.04f : 1, 0, 0xFFFFFFFF);
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
            // 줄 높이 41·간격 47: 맨 아래 한 줄 글자가 v3 프레임 아래 금테·오른쪽 아래 장식에 안 걸리게 위로 당김
            float y = 70 + i * 47, rh = 41;
            // stat_row: 왼쪽 아이콘 칸(u .03~.16) + 오른쪽 긴 칸, 아이콘 칸까지는 안 늘림
            // v3: 가운데 위 왕관(u .358~.640)은 늘이지 않고, 위로 삐져나온 왕관·보석은 여백으로
            stripMid(c, "menu/stat_row", 328, y, 326, rh, .18f, .358f, .640f, .04f, PAD_STAT, 0xFFFFFFFF);
            c.imageFit(rows[i][0], 351.7f, y + rh / 2, 25, 0xFFFFFFFF);
            c.text(rows[i][1], 384, y + rh / 2, 15, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(rows[i][2], 636, y + rh / 2, 19, 110, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        }
        // 맨 아래: 남은/보유 다이아 · 코인 · 게이지 (오른쪽 아래 보석·클로버(x 574~) 왼쪽, 프레임 금테 위)
        JsonObject g = J.objOrEmpty(m, "gauge");
        String line = "§7남은 다이아 §r" + Fmt.num(J.num(m, "debt")) + "   §7보유 §r" + Fmt.num(J.num(m, "held"))
                + "   §7코인 §r" + Fmt.num(J.num(m, "coins")) + "   §7운빨 §r" + J.num(g, "value") + "   §7" + dayText(J.num(m, "day"));
        c.textFit(line, 448, 361, 13, 236, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    /** 서버 일차: 오픈 전(0 이하)이면 "오픈 D-3" */
    static String dayText(long day) { return day >= 1 ? day + "일차" : "오픈 D-" + (1 - day); }

    // ───────── 접속 보상 ─────────
    private void playtime(Canvas c, JsonObject p, float mx, float my, long now) {
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
        if (anyReady && !h1) imagePad(c, "menu/button_claim_normal", 175, 335, 200, 38, PAD_CLAIM, 0xFFFFFFFF);
        else c.image(anyReady ? "menu/button_claim_pressed" : "menu/button_claim_disabled", 175, 335, 200, 38, 0xFFFFFFFF);
        c.text(anyReady ? "보상 받기" : "받을 보상 없음", 275, 354, 16, anyReady ? Theme.GREEN_DARK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (anyReady) hits.add(new Hit("claim", "", 175, 335, 200, 38));
        long papers = J.num(p, "failPapers"), need = Math.max(1, J.num(p, "exchangeNeed", 5));
        boolean ex = papers >= need;
        boolean h2 = ex && Anim.in(mx, my, 404, 335, 210, 38);
        c.image(ex ? (h2 ? "menu/button_exchange_pressed" : "menu/button_exchange_normal") : "menu/button_exchange_disabled", 404, 335, 210, 38, 0xFFFFFFFF);
        // 종이 아이콘은 버튼 안쪽 테두리(높이 약 28) 안에 들어가게, 둥근 왼쪽 끝을 피해 조금 안쪽으로
        c.imageFit("menu/icon_losing_paper", 440, 354, 25, 0xFFFFFFFF);
        c.textFit("꽝 종이 " + papers + "/" + need + " → 고급상자", 531, 354, 14, 140, ex ? Theme.INK : Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (ex) hits.add(new Hit("exchange", "", 404, 335, 210, 38));
    }

    // ───────── 칭호 ─────────
    private void titles(Canvas c, JsonObject t, float mx, float my) {
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
            // v3: 왼쪽 보석·클로버(14%)도 안 늘림 → 리본·글자는 그 장식 오른쪽부터
            stripPad(c, sel ? "menu/title_row_selected" : own ? "menu/title_row_owned" : "menu/title_row_locked", 134, y, 510, rh, .14f, .18f,
                    sel ? PAD_TITLE_SELECTED : own ? PAD_TITLE_OWNED : PAD_TITLE_LOCKED, hov && !sel ? 0xFFEFFFE0 : 0xFFFFFFFF);
            float rbw = 140, rbh = rbw * 247 / 1024f, rbx = 222, rby = y + rh / 2 - rbh * Theme.RIBBON_CY;
            c.image("gacha/jackpot_ribbon", rbx, rby, rbw, rbh, own ? 0xFFFFFFFF : 0xCCFFFFFF);
            String name = J.str(r, "name");
            c.textArc(own ? name : "§7" + Fmt.strip(name), rbx + rbw / 2, y + rh / 2, 15, Theme.ribbonR(rbw), 96, Theme.INK, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(J.str(r, "condition"), 374, y + rh / 2, 15, 148, own ? Theme.INK : Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (own) { float hy = Math.max(91, y), hb = Math.min(384, y + rh); if (hb > hy) hits.add(new Hit("title", sel ? "none" : J.str(r, "id"), 134, hy, 510, hb - hy)); }
            if (Anim.in(mx, my, 134, y, 510, rh) && my > 91 && my < 384)
                hoverTip = own ? (sel ? "&a장착 중 &7(누르면 해제)" : "&e누르면 장착") : "&7아직 없는 칭호";
        }
        c.unclip();
        scrollbar(c, 650, 92, 284);
    }

    // ───────── 명예의 전당 ─────────
    private void hall(Canvas c, JsonObject hall, float mx, float my) {
        List<JsonObject> boards = J.objs(hall, "boards");
        if (boards.isEmpty()) { c.text("아직 기록이 없어요", 388, 228, 20, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT); return; }
        board = Math.max(0, Math.min(board, boards.size() - 1));
        // 소분류 버튼 (3개씩 줄)
        int per = 3;
        int lines = (boards.size() + per - 1) / per;
        float sw = 145, shh = lines > 1 ? 27 : 33, gap = 15;
        for (int i = 0; i < boards.size(); i++) {
            int row = i / per, colI = i % per;
            float x = 160 + colI * (sw + gap), y = 82 + row * (shh + 10);            // 줄 사이 10: 아랫줄 왕관이 윗줄을 안 덮게
            boolean sel = i == board, hov = Anim.in(mx, my, x, y, sw, shh);
            // 둥근 양끝(보석·클로버 포함)은 통째로 안 늘림. 보통 버튼은 위 왕관도 제 비율로, 알약 전체가 (x,y,sw,shh) 에 꼭 맞음
            if (sel) stripPad(c, "menu/ranking_subtab_selected", x, y, sw, shh, .15f, .15f, PAD_SUBTAB_SELECTED, 0xFFFFFFFF);
            else stripMid(c, "menu/ranking_subtab_normal", x, y, sw, shh, .17f, .315f, .685f, .17f, PAD_SUBTAB_NORMAL, hov ? 0xFFF4FFE8 : 0xFFFFFFFF);
            c.textFit(J.str(boards.get(i), "label"), x + sw / 2, y + shh / 2, 13, sw - 56, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new Hit("board", String.valueOf(i), x, y, sw, shh));
        }
        float top = 82 + lines * (shh + 10) + 8;
        List<JsonObject> rows = J.objs(boards.get(board), "rows");
        if (rows.isEmpty()) { c.text("아직 기록이 없어요", 388, top + 90, 18, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT); return; }
        float viewBottom = 380;
        float total = 0;
        for (int i = 0; i < rows.size(); i++) total += (i < 3 ? 58 : 38) + 12;
        scrollMax = Math.max(0, total - (viewBottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        scrollShown += (scroll - scrollShown) * .25f;
        if (Math.abs(scroll - scrollShown) < .3f) scrollShown = scroll;
        c.clip(118, top - 13, 541, viewBottom - top + 15);                 // 위 13: 첫 줄 왼쪽 위 보석·클로버가 안 잘리게
        int[] medal = {0xFFFFF0C4, 0xFFF0F2FA, 0xFFFFE8DA};                 // 1~3등 줄은 메달 빛으로 살짝 물들임
        String[] art = {"menu/medal_gold", "menu/medal_silver", "menu/medal_bronze"};
        float y = top - scrollShown;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            boolean big = i < 3;
            float h = big ? 58 : 38;
            if (y + h >= top - 13 && y <= viewBottom + 2) {
                long rank = J.num(r, "rank", i + 1);
                // ranking_row: 칸막이 두 줄(u .174 / .694)을 순위칸·이름칸 끝(212 / 502)에 맞춤
                // v3: 왼쪽 끝 보석·클로버(몸통 u 0~.125)·오른쪽 끝 보석·클로버(u .925~1)는 안 늘림, 위로 삐져나온 장식은 여백
                float bodyK = h / 111.2f * 1017.7f;                           // 몸통 전체 너비 (높이 h 기준 제 비율)
                float capL = Math.min(70, .125f * bodyK), capR = .075f * bodyK;
                slicesPad(c, "menu/ranking_row", y, h, new float[]{0, .125f, .172f, .178f, .692f, .697f, .925f, 1},
                        new float[]{135, 135 + capL, 211, 213, 501, 503, 648 - capR, 648}, PAD_RANK_ROW, rank <= 3 ? medal[(int) rank - 1] : 0xFFFFFFFF);
                if (rank <= 3) c.imageFit(art[(int) rank - 1], 187, y + h / 2, h * .8f, 0xFFFFFFFF);   // 왼쪽 끝 클로버(~160) 와 칸막이(211) 사이
                else c.text(String.valueOf(rank), 180, y + h / 2, 16, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                String t = J.str(r, "title");
                c.textFit((t.isEmpty() ? "" : t + " ") + "§r" + J.str(r, "name"), 222, y + h / 2, big ? 19 : 16, 270, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
                c.textFit(Fmt.num(J.num(r, "value")), 642 - capR, y + h / 2, big ? 20 : 16, 130 - capR, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            }
            y += h + 12;
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
