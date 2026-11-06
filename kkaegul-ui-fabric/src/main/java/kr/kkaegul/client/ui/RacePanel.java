package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 개구리 경주 창 (GPT 시안 layout 1600x1024).
 *  - 배팅 중: 출전표(번호·얼굴·이름·컨디션★·배당·내 다이아) + 오른쪽 배팅 패널 → race.bet no=.. amount=..
 *  - 경주 중/결과: 같은 창에서 6줄 레인 경주 화면 + 우승 트로피
 * 서버 데이터 screen "race": {state, endsInMs, round, frogs:[{no,name,colorId,form,odds,myBet,pool,progress}], available, minBet, maxBet, myTotal, winner}
 */
public final class RacePanel implements Panel {
    private final Host host;
    private JsonObject data;
    private long recvAt;
    private int selected = 1;
    private long amount = 1;
    private String typed;
    private int mods;
    private long waitingSince, opened;
    private JsonObject result;
    private long resultAt;
    private record R(String key, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private float k = 1, ox, oy;
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    static final String[] COL = {"coral_red", "orange", "golden_yellow", "leaf_green", "cyan_blue", "lavender_purple"};
    static final String[] LANE = {"red", "orange", "yellow", "green", "blue", "purple"};
    static final int[] TINT = {0xFFE9584F, 0xFFF29B3A, 0xFFF2C230, 0xFF7CC24A, 0xFF3FB8E8, 0xFFA77BE0};

    public RacePanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.recvAt = now;
        this.opened = now;
        for (JsonObject f : J.objs(this.data, "frogs")) if (J.num(f, "myBet") > 0) { selected = (int) J.num(f, "no"); break; }
    }

    @Override public boolean textInput() { return true; }

    @Override public String screen() { return "race"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        String before = J.str(data, "state");
        data = J.merge(data, d);
        recvAt = now;
        waitingSince = 0;
        if (!J.str(data, "state").equals(before) && J.str(data, "state").equals("betting")) result = null;
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        switch (id) {
            case "race_result" -> {
                result = d; resultAt = now;
                host.sound(J.num(d, "got") > 0 ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.villager.no", 1f, .7f);
                return true;
            }
            case "race_bet" -> { waitingSince = 0; host.sound("minecraft:entity.experience_orb.pickup", 1.3f, .6f); return true; }
            default -> { return false; }
        }
    }

    @Override public void modifiers(int m) { mods = m; }
    private long step() { return (mods & 2) != 0 ? 100 : (mods & 1) != 0 ? 10 : 1; }

    private String state() { return J.str(data, "state", "idle"); }
    private long left(long now) { return Math.max(0, J.num(data, "endsInMs") - (now - recvAt)); }
    private long room() { return Math.max(0, Math.min(J.num(data, "available"), J.num(data, "maxBet", 300) - J.num(data, "myTotal"))); }
    private long amt() {
        if (typed == null) return Math.max(1, Math.min(amount, Math.max(1, room())));
        try { return Math.max(0, Math.min(Long.parseLong(typed.isEmpty() ? "0" : typed), Math.max(1, room()))); } catch (NumberFormatException e) { return 0; }
    }

    private JsonObject frog(int no) {
        for (JsonObject f : J.objs(data, "frogs")) if (J.num(f, "no") == no) return f;
        return null;
    }

    static int ci(JsonObject f) { return (int) Math.floorMod(J.num(f, "colorId", J.num(f, "no", 1) - 1), 6); }

    // ───────── 그리기 ─────────
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x77000000);
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .9f / 1600, H * .94f / 1010) * pop;
        ox = W / 2 - 800 * k;
        oy = H / 2 - 505 * k;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;

        c.image("race/main_window", 28, 100, 1543, 904, 0xFFFFFFFF);
        c.image("race/frog_header_ribbon", 540, 8, 520, 188, 0xFFFFFFFF);
        c.textFit("개구리 경주", 800, 136, 50, 380, Theme.INK, Canvas.CENTER, TXT);
        boolean hc = Anim.in(mx, my, 1485, 129, 52, 52);
        c.image("race/close", 1495, 139, 32, 32, hc ? 0xFFFFB9A8 : 0xFFFFFFFF);
        hits.add(new R("close", 1485, 129, 52, 52));

        String st = state();
        if (st.equals("racing") || st.equals("result")) {
            track(c, data, now, J.num(data, "round"), st.equals("result") ? "결승!" : "경주 중", true);
            if (result != null && now - resultAt < 9000) resultOverlay(c, result, now - resultAt, 800);
        } else betting(c, now, mx, my);
        c.pop();
    }

    /** 예상 배당: 아무도 안 건 번호는 "-" */
    static String oddsText(double o) { return o <= 0 ? "-" : "x" + ShopPanel.dec(o); }

    /** 3조각으로 늘리는 둥근 칸 (모서리 안 찌그러지게) */
    static void field(Canvas c, float x, float y, float w, float h) {
        float cap = Math.min(w / 2, h * .55f), u = 26f / 280f;
        c.imagePart("race/value_field", x, y, cap, h, 0, 0, u, 1, 0xFFFFFFFF);
        c.imagePart("race/value_field", x + cap, y, w - cap * 2, h, u, 0, 1 - u, 1, 0xFFFFFFFF);
        c.imagePart("race/value_field", x + w - cap, y, cap, h, 1 - u, 0, 1, 1, 0xFFFFFFFF);
    }

    private void betting(Canvas c, long now, float mx, float my) {
        c.image("race/contestant_list_panel", 50, 265, 959, 665, 0xFFFFFFFF);
        c.image("race/betting_panel", 1070, 265, 467, 665, 0xFFFFFFFF);
        // 머리줄
        c.image("race/crossed_flags", 90, 284, 66, 46, 0xFFFFFFFF);
        c.image("race/frog_headband_leaf_green", 182, 280, 60, 50, 0xFFFFFFFF);
        c.text("이름", 383, 308, 24, Theme.INK, Canvas.CENTER, TXT);
        c.image("race/star_empty", 525, 292, 32, 32, 0xFFFFFFFF);
        c.text("컨디션", 563, 308, 22, Theme.INK, Canvas.LEFT, TXT);
        c.text("예상 배당", 705, 308, 22, Theme.INK, Canvas.CENTER, TXT);
        c.image("race/diamond", 800, 289, 40, 35, 0xFFFFFFFF);
        c.text("내 다이아", 846, 308, 22, Theme.INK, Canvas.LEFT, TXT);

        boolean open = state().equals("betting");
        List<JsonObject> fl = J.objs(data, "frogs");
        for (int i = 0; i < 6; i++) {
            float y = 338 + 94 * i;
            if (i >= fl.size()) continue;
            JsonObject f = fl.get(i);
            int no = (int) J.num(f, "no"), col = ci(f);
            boolean sel = no == selected, hov = !sel && Anim.in(mx, my, 69, y, 920, 94);
            c.image(sel ? "race/row_selected" : hov ? "race/row_hover" : "race/row_default", 69, y, 920, 94, 0xFFFFFFFF);
            c.image("race/badge_" + COL[col], 90, y + 17, 60, 60, 0xFFFFFFFF);
            if (sel) c.image("race/check", 96, y + 25, 48, 38, 0xFFFFFFFF);
            else c.text(String.valueOf(no), 120, y + 48, 32, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            float bob = sel ? (float) Math.sin(now / 160.0) * 3 : 0;
            c.image("race/frog_headband_" + COL[col], 160, y + 7 + bob, 96, 80, 0xFFFFFFFF);
            field(c, 275, y + 21, 215, 51);
            c.textFit(J.str(f, "name"), 383, y + 47, 26, 195, Theme.INK, Canvas.CENTER, TXT);
            int form = (int) J.num(f, "form", 1);
            for (int s = 0; s < 3; s++) c.image(s < form ? "race/star_filled" : "race/star_empty", 510 + 39 * s, y + 29, 34, 34, 0xFFFFFFFF);
            field(c, 640, y + 21, 130, 51);
            c.textFit(oddsText(J.dbl(f, "odds", 0)), 705, y + 47, 26, 115, 0xFF3F7F31, Canvas.CENTER, TXT);
            field(c, 790, y + 21, 175, 51);
            c.image("race/diamond", 800, y + 31, 36, 31, 0xFFFFFFFF);
            long mine = J.num(f, "myBet");
            c.textFit(mine > 0 ? Fmt.num(mine) : "-", 900, y + 47, 26, 110, mine > 0 ? Theme.INK : Theme.MUTED, Canvas.CENTER, TXT);
            hits.add(new R("frog:" + no, 69, y, 920, 94));
        }

        // 오른쪽 배팅 패널
        c.image("race/clock", 1118, 295, 56, 58, 0xFFFFFFFF);
        field(c, 1190, 297, 300, 51);
        long l = left(now) / 1000;
        long nx = Math.max(0, J.num(data, "nextInMs") - (now - recvAt)) / 1000;
        String t = open ? "마감까지 " + l / 60 + ":" + String.format("%02d", l % 60) : state().equals("idle") ? "다음 경주까지 " + nx / 60 + ":" + String.format("%02d", nx % 60) : "다음 경주 대기 중";
        c.textFit(t, 1340, 323, 26, 270, open && l <= 10 ? Theme.RED : Theme.INK, Canvas.CENTER, TXT);
        long carry = J.num(data, "carry");
        c.textFit("상금 " + Fmt.num(J.num(data, "pot")) + (carry > 0 ? " · 이월 " + Fmt.num(carry) : "") + " · 수수료 " + J.num(data, "feePercent", 10) + "%", 1304, 366, 20, 400, Theme.MUTED, Canvas.CENTER, TXT);
        JsonObject s = frog(selected);
        if (s == null && !fl.isEmpty()) { s = fl.get(0); selected = (int) J.num(s, "no"); }
        if (s != null) {
            int col = ci(s);
            float bob = (float) Math.sin(now / 300.0) * 4;
            c.image("race/frog_headband_" + COL[col], 1235, 385 + bob, 120, 100, 0xFFFFFFFF);
            c.image("race/badge_" + COL[col], 1375, 400, 60, 60, 0xFFFFFFFF);
            c.text(String.valueOf(J.num(s, "no")), 1405, 431, 32, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            field(c, 1160, 489, 280, 51);
            c.textFit(J.str(s, "name") + " · " + oddsText(J.dbl(s, "odds", 0)), 1300, 515, 26, 255, Theme.INK, Canvas.CENTER, TXT);
        }
        c.image("race/diamond", 1110, 568, 60, 52, 0xFFFFFFFF);
        field(c, 1198, 566, 280, 51);
        c.textFit("보유 " + Fmt.num(J.num(data, "available")), 1338, 592, 26, 255, Theme.INK, Canvas.CENTER, TXT);
        long a = amt();
        // 건 사람끼리 나눠 갖기: 내가 더 걸면 그 번호 몫이 커지므로 그것까지 넣어서 예상
        long win = 0;
        if (s != null) {
            double pot = J.num(data, "pot") + a * (1 - J.num(data, "feePercent", 10) / 100.0), on = J.num(s, "pool") + a;
            win = on > 0 ? (long) Math.floor(pot * a / on) : 0;
        }
        c.textFit(s == null ? "" : "지금 걸면 맞혔을 때 약 " + Fmt.num(win) + "개 (마감 때 확정)", 1290, 642, 20, 390, Theme.MUTED, Canvas.CENTER, TXT);
        // 수량
        c.image("race/amount_stepper", 1100, 665, 280, 61, 0xFFFFFFFF);
        c.image("race/minus", 1120, 690, 32, 10, 0xFFFFFFFF);
        c.image("race/plus", 1327, 679, 32, 32, 0xFFFFFFFF);
        String shown = typed != null ? (typed.isEmpty() ? "0" : typed) : Fmt.num(a);
        c.textFit(shown, 1240, 696, 28, 120, Theme.INK, Canvas.CENTER, TXT);
        if (typed != null && (now / 530) % 2 == 0) c.fill(1240 + Math.min(120, c.textWidth(shown, 28, TXT)) / 2 + 3, 681, 3, 30, 1.5f, 0xFF7CB342);
        hits.add(new R("minus", 1100, 665, 66, 61));
        hits.add(new R("input", 1166, 665, 148, 61));
        hits.add(new R("plus", 1314, 665, 66, 61));
        boolean hm = Anim.in(mx, my, 1404, 660, 80, 70);
        c.image("race/max_icon_button", 1404, 660 + (hm ? -2 : 0), 80, 70, 0xFFFFFFFF);
        c.text("최대", 1444, 744, 16, Theme.INK, Canvas.CENTER, TXT);
        hits.add(new R("max", 1404, 660, 80, 70));
        // 걸기
        if (waitingSince > 0 && now - waitingSince > 5000) waitingSince = 0;
        boolean can = open && s != null && a > 0 && a <= room() && waitingSince == 0;
        boolean hb = can && Anim.in(mx, my, 1099, 775, 410, 82);
        c.image(can ? hb ? "race/bet_hover" : "race/bet_default" : "race/bet_disabled", 1099, 775, 410, 82, 0xFFFFFFFF);
        c.image("race/check", 1180, 792, 60, 48, can ? 0xFFFFFFFF : 0x88FFFFFF);
        String bl = !open ? "배팅 마감" : room() <= 0 ? (J.num(data, "available") <= 0 ? "다이아가 없어요" : "최대까지 걸었어요") : waitingSince > 0 ? "거는 중..." : "걸기";
        c.textFit(bl, 1340, 816, 32, 240, can ? 0xFF5A3A12 : 0xFF9A8E80, Canvas.CENTER, TXT);
        hits.add(new R("bet", 1099, 775, 410, 82));
        c.textFit("이번 경주에 건 다이아 " + Fmt.num(J.num(data, "myTotal")) + " / " + Fmt.num(J.num(data, "maxBet", 300)), 1304, 882, 22, 390, Theme.INK, Canvas.CENTER, TXT);
    }

    /** 경주 화면 (창 안 레인 6줄). overlay=false 면 테두리 없이 레인만 */
    /** 서버 진행값은 0.2초마다 오므로, 그 사이를 속도로 이어서 부드럽게 (key → {보이는 값, 마지막 목표, 받은 시각, 속도, 마지막 프레임}) */
    private static final java.util.Map<String, float[]> SMOOTH = new java.util.HashMap<>();

    static float smooth(String key, float target, long now) {
        float t = (float) (now % 1_000_000L);
        float[] s = SMOOTH.get(key);
        if (s == null || target < s[1] - .05f || Math.abs(t - s[4]) > 1500) {      // 새 경주이거나 오래 안 그렸으면 바로 맞춤
            s = new float[]{target, target, t, 0, t};
            SMOOTH.put(key, s);
        }
        if (target != s[1]) {
            float dt = Math.max(1, t - s[2]);
            if (dt < 2000) s[3] = s[3] * .5f + (target - s[1]) / dt * .5f;   // 1ms 당 진행 (조금 평균)
            s[1] = target;
            s[2] = t;
        }
        float est = target >= 1 ? 1 : Math.min(1, target + s[3] * Math.min(250, Math.max(0, t - s[2])));
        float frame = Math.max(1, Math.min(100, t - s[4]));
        s[0] += (est - s[0]) * (1 - (float) Math.exp(-frame / 60f));
        if (s[0] > est + .02f) s[0] = est;
        s[4] = t;
        return Math.max(0, Math.min(1, s[0]));
    }

    static void track(Canvas c, JsonObject d, long now, long round, String status, boolean header) {
        if (header) {
            c.image("race/crossed_flags", 93, 207, 66, 46, 0xFFFFFFFF);
            field(c, 185, 209, 280, 51);
            c.textFit(round > 0 ? "제 " + round + "경주" : "개구리 경주", 325, 235, 26, 255, Theme.INK, Canvas.CENTER, TXT);
            c.image("race/clock", 1050, 184, 80, 82, 0xFFFFFFFF);
            field(c, 1145, 209, 230, 51);
            c.textFit(status, 1260, 235, 26, 210, Theme.INK, Canvas.CENTER, TXT);
        }
        List<JsonObject> fl = J.objs(d, "frogs");
        int win = (int) J.num(d, "winner", -1);
        for (int i = 0; i < Math.min(6, fl.size()); i++) {
            JsonObject f = fl.get(i);
            int col = ci(f);
            float y = 285 + 112 * i;
            c.image("race/lane_" + LANE[col], 80, y, 1440, 112, 0xFFFFFFFF);
            c.image("race/badge_" + COL[col], 103, y + 26, 60, 60, 0xFFFFFFFF);
            c.text(String.valueOf(J.num(f, "no")), 133, y + 57, 32, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            c.image("race/frog_headband_" + COL[col], 176, y + 16, 84, 70, 0xFFFFFFFF);
            double p = smooth((header ? "p" : "v") + J.num(f, "no"), (float) Math.max(0, Math.min(1, J.dbl(f, "progress", 0))), now);
            float front = 290 + (float) p * (1430 - 290);
            boolean done = p >= 1;
            String fr = done ? "02" : ((now / 125 + i) % 2 == 0 ? "01" : "02");
            float hop = done ? 0 : (float) Math.abs(Math.sin((now + i * 90) / 125.0 * Math.PI / 2)) * -6;
            c.image("race/frog_" + COL[col] + "_run_" + fr, front - 174, y - 8 + hop, 180, 120, 0xFFFFFFFF);
            if (J.num(f, "myBet") > 0) {
                float bob = (float) Math.sin(now / 200.0) * 4;
                c.image("race/selected_diamond", front - 81, y - 43 + bob, 64, 72, 0xFFFFFFFF);
            }
            if (win == J.num(f, "no")) c.image("race/check", front - 40, y + 30, 50, 40, 0xFFFFFFFF);
        }
        c.image("race/finish_strip", 1435, 285, 70, Math.min(672, 112 * Math.max(1, fl.size())), 0xFFFFFFFF);
        if (header) c.image("race/finish_flag", 1395, 150, 140, 140, 0xFFFFFFFF);
    }

    /** 우승 트로피 + 리본 (가운데 cx 기준, 시안 result_overlay) */
    static void resultOverlay(Canvas c, JsonObject r, long e, float cx) {
        float a = Math.min(1, e / 200f) * (e > 8000 ? Math.max(0, 1 - (e - 8000) / 1000f) : 1);
        float s = Anim.outBack(Math.min(1, e / 380f));
        int col = (int) Math.floorMod(J.num(r, "colorId", J.num(r, "winner", 1) - 1), 6);
        c.fill(cx - 740, 190, 1480, 800, 36, Anim.alpha(0x99000000, a));
        c.push();
        c.translate(cx, 380);
        c.scale(s, s);
        c.image("race/trophy_frog_base", -150, -160, 300, 320, Anim.white(a));
        c.image("race/trophy_frog_vest_gray", -150, -160, 300, 320, Anim.alpha(TINT[col], a));
        c.pop();
        long got = J.num(r, "got"), bet = J.num(r, "bet"), spent = J.num(r, "spent");
        boolean won = got > 0;
        c.push();
        c.translate(cx, 645);
        c.scale(s, s);
        c.image(won || spent == 0 ? "race/ribbon_win_blank" : "race/ribbon_lose_blank", -260, -60, 520, 120, Anim.white(a));
        c.textFit(J.num(r, "winner") + "번 " + J.str(r, "name") + " 우승!", 0, -4, 32, 390, Anim.alpha(won || spent == 0 ? 0xFF6B4A00 : 0xFF5E5E5E, a), Canvas.CENTER, TXT);
        c.pop();
        double od = J.dbl(r, "odds", 0);
        String sub = won ? "맞혔어요! 다이아 +" + Fmt.num(got) + " (x" + ShopPanel.dec(od) + ")"
                : od <= 0 ? "맞힌 사람이 없어 상금 " + Fmt.num(J.num(r, "pot")) + "개는 다음 경주로 이월!"
                : spent > 0 ? "아쉬워요... 건 다이아 " + Fmt.num(spent) + "개" : "배당 x" + ShopPanel.dec(od);
        c.textFit(sub, cx, 740, 32, 760, Anim.alpha(won ? 0xFFFFE27A : Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        if (won) for (int i = 0; i < 10; i++) {
            float t = ((e + i * 170) % 1700) / 1700f;
            double ang = i * .63;
            c.image("race/diamond", cx + (float) Math.cos(ang) * (190 + 90 * t) - 20, 380 + (float) Math.sin(ang) * (150 + 70 * t) - 17, 40, 35, Anim.white(a * (1 - t)));
        }
    }

    // ───────── 입력 ─────────
    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            String key = r.key();
            if (!key.equals("input") && typed != null) { amount = amt(); typed = null; }
            if (key.startsWith("frog:")) { selected = Integer.parseInt(key.substring(5)); host.sound("minecraft:ui.button.click", 1.4f, .35f); return true; }
            switch (key) {
                case "close" -> host.closePanel(this);
                case "input" -> typed = "";
                case "minus" -> { amount = Math.max(1, amt() - step()); host.sound("minecraft:ui.button.click", 1.3f, .3f); }
                case "plus" -> { amount = Math.min(Math.max(1, room()), amt() + step()); host.sound("minecraft:ui.button.click", 1.5f, .3f); }
                case "max" -> { amount = Math.max(1, room()); host.sound("minecraft:ui.button.click", 1.2f, .3f); }
                case "bet" -> {
                    long a = amt();
                    if (!state().equals("betting") || a <= 0 || a > room() || waitingSince > 0) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                    waitingSince = now;
                    host.send("race.bet", "no", String.valueOf(selected), "amount", String.valueOf(a));
                }
                default -> { }
            }
            return true;
        }
        return false;
    }

    @Override public boolean scroll(float mx, float my, double amountWheel) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        if (Anim.in(x, y, 1090, 640, 450, 120)) { amount = Math.max(1, Math.min(Math.max(1, room()), amt() + (amountWheel > 0 ? 1 : -1))); typed = null; return true; }
        return false;
    }

    @Override public boolean typed(char ch, long now) {
        if (ch >= '0' && ch <= '9') {
            if (typed == null) typed = "";
            if (typed.length() < 6) typed = (typed.equals("0") ? "" : typed) + ch;
            return true;
        }
        if (ch >= '1' && ch <= '6' && typed == null) { selected = ch - '0'; return true; }
        return false;
    }

    @Override public boolean key(int key, int m, long now) {
        if (key == 259 && typed != null) { if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1); return true; }
        if ((key == 257 || key == 335) && typed != null) { amount = amt(); typed = null; return true; }
        return false;
    }
}
