package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 개구리 점프 창 (GPT 시안 layout.json, 1600x1024). 글자 위치는 그림의 안쪽 판을 재서 맞춤.
 * 서버 "frog_jump": {available, minBet, maxBet, steps:[{chance,multiplier}], playing, bet, step, multiplier, cashout, nextChance, nextMultiplier}
 * 연출 fx: jump_start / jump_ok{step} / jump_fail{step,bet} / jump_cashout{step,multiplier,got,bet}
 */
public final class FrogJumpPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private long amount = 1;
    private String typed;
    private int mods;
    private long waitingSince, opened;
    // 연출 상태
    private int fromStep, toStep;          // 개구리가 뛰는 칸 (0 = 출발 둑)
    private long animStart;
    private String anim = "";              // "", "ok", "fail", "cash", "start"
    private JsonObject result;             // 끝난 판 결과 (다음 판 시작 전까지 표시)
    private String resultKind = "";
    private long resultAt;
    private record R(String key, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private float k = 1, ox, oy;
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;
    /** 연잎 위치 (왼쪽 위 90×68). 연못 물 안쪽 (그림 x 110~950px → 창 150~990)에 10칸이 다 들어가게 83.8 간격 (예전 94 간격은 10번 연잎·×18 꼬리표가 연못 밖으로 나감) */
    private static final float[][] PAD = {{146, 576}, {229.8f, 558}, {313.6f, 566}, {397.3f, 576}, {481.1f, 552}, {564.9f, 578}, {648.7f, 552}, {732.4f, 569}, {816.2f, 579}, {900, 558}};
    private static final float START_X = 92, START_Y = 716;   // 출발 둑 (개구리 발 위치)
    private static final long HOP = 460, LAND = 650;

    public FrogJumpPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
        int st = (int) J.num(this.data, "step");
        fromStep = toStep = J.bool(this.data, "playing") ? st : 0;
        amount = Math.max(1, J.num(this.data, "bet", J.num(this.data, "minBet", 1)));
    }

    @Override public boolean textInput() { return true; }

    @Override public String screen() { return "frog_jump"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        waitingSince = 0;
        if (J.bool(data, "playing") && anim.isEmpty()) fromStep = toStep = (int) J.num(data, "step");
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        switch (id) {
            case "jump_start" -> { result = null; resultKind = ""; fromStep = toStep = 0; anim = "start"; animStart = now; host.sound("minecraft:entity.frog.ambient", 1.2f, .8f); }
            case "jump_ok" -> { fromStep = toStep; toStep = (int) J.num(d, "step"); anim = "ok"; animStart = now; }
            case "jump_fail" -> { fromStep = toStep; toStep = (int) J.num(d, "step"); anim = "fail"; animStart = now; result = d; resultKind = "fail"; resultAt = now + HOP; }
            case "jump_cashout" -> {
                anim = "cash"; animStart = now; result = d;
                resultKind = J.num(d, "step") >= steps().size() ? "rainbow" : "gold";
                resultAt = now + (J.num(d, "step") > toStep ? HOP : 0);
                if (J.num(d, "step") > toStep) { fromStep = toStep; toStep = (int) J.num(d, "step"); }
            }
            default -> { return false; }
        }
        waitingSince = 0;
        return true;
    }

    @Override public void modifiers(int m) { mods = m; }
    private long step() { return (mods & 2) != 0 ? 100 : (mods & 1) != 0 ? 10 : 1; }

    private List<JsonObject> steps() { return J.objs(data, "steps"); }
    private boolean playing() { return J.bool(data, "playing"); }
    private long maxBet() { return Math.max(1, Math.min(J.num(data, "maxBet", 500), J.num(data, "available"))); }
    private long amt() {
        if (typed == null) return Math.max(1, Math.min(amount, Math.max(1, maxBet())));
        try { return Math.max(0, Math.min(Long.parseLong(typed.isEmpty() ? "0" : typed), Math.max(1, maxBet()))); } catch (NumberFormatException e) { return 0; }
    }

    private static float[] padFoot(int step) {
        if (step <= 0) return new float[]{START_X, START_Y};
        float[] p = PAD[Math.min(9, step - 1)];
        return new float[]{p[0] + 45, p[1] + 38};
    }

    /** 성공 확률에 따른 위험도: 0 낮음 / 1 보통 / 2 높음 */
    private static int risk(double chance) { return chance >= 75 ? 0 : chance >= 60 ? 1 : 2; }

    // ───────── 그리기 ─────────
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x77000000);
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .9f / 1600, H * .94f / 990) * pop;
        ox = W / 2 - 800 * k;
        oy = H / 2 - 500 * k;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        if (waitingSince > 0 && now - waitingSince > 5000) waitingSince = 0;
        if (!anim.isEmpty() && now - animStart > HOP + LAND) anim = "";

        c.image("jump/window_frame", 0, 0, 1600, 1024, 0xFFFFFFFF);
        c.textFit("깨굴이", 800, 126, 40, 330, INK, Canvas.CENTER, TXT);
        boolean hc = Anim.in(mx, my, 1352, 112, 52, 52);   // 닫기: 오른쪽 위 모서리 보석·덩굴 장식(창 1414~1550) 왼쪽 빈 곳 — 예전엔 보석 위를 덮었음
        c.image("shop/close", 1358, 118, 40, 40, hc ? 0xFFFFB9A8 : 0xFFFFFFFF);
        hits.add(new R("close", 1352, 112, 52, 52));

        // 왼쪽: 제목 + 현재 칸
        c.text("개구리 점프", 72, 236, 56, INK, Canvas.LEFT, TXT);
        c.text("어디까지 뛸 수 있을까?", 76, 295, 28, 0xFF6B5A4C, Canvas.LEFT, TXT);
        int shown = anim.equals("ok") || anim.equals("fail") ? (now - animStart > HOP ? toStep : fromStep) : toStep;
        int total = Math.max(10, steps().size());
        // 진행 게이지 (jump/gauge_empty 안에 gauge_fill 을 간 칸만큼) + 가운데 글자
        float gx = 740, gy = 231, gw = 318, gh = 50;
        c.imageNine("jump/gauge_empty", gx, gy, gw, gh, .07f, .45f, gh * .55f, gh * .45f, 0xFFFFFFFF);
        float fr = Anim.clamp(shown / (float) total), fw = (gw - 18) * fr;
        if (fw > 2) c.imageNine("jump/gauge_fill", gx + 9, gy + 8, Math.max(gh - 16, fw), gh - 16, .06f, .45f, (gh - 16) * .6f, (gh - 16) * .45f, 0xFFFFFFFF);
        c.textFit("현재 " + shown + " / " + total + "칸", gx + gw / 2, 256, 28, 260, INK, Canvas.CENTER, TXT);

        pond(c, now);

        // 아래 범례
        c.imageNine("jump/probability_panel", 112, 852, 906, 54, .06f, .3f, 24, 16, 0xFFFFFFFF);
        c.text("위험도", 150, 879, 26, INK, Canvas.LEFT, TXT);
        c.fill(262, 862, 2, 34, 1, 0x55A08B72);
        String[] rl = {"낮음", "보통", "높음"};
        int[] rc = {0xFF6FBF3C, 0xFFF2C230, 0xFFE9584F};
        for (int i = 0; i < 3; i++) {
            float x = 296 + i * 116;
            c.fill(x - 13, 866, 26, 26, 13, 0xFF4A2D16);
            c.fill(x - 11, 868, 22, 22, 11, rc[i]);
            c.text(rl[i], x + 22, 879, 26, INK, Canvas.LEFT, TXT);
        }
        c.fill(650, 862, 2, 34, 1, 0x55A08B72);
        c.textFit("성공할수록 배수가 올라가요", 830, 879, 26, 330, INK, Canvas.CENTER, TXT);

        info(c, now, mx, my);
        c.pop();
    }

    private void pond(Canvas c, long now) {
        c.image("jump/pond_empty", 40, 330, 1040, 500, 0xFFFFFFFF);
        // 출발 팻말
        c.fill(87, 560, 10, 40, 3, 0xFF8A5A2B);
        MenuPanel.box(c, 46, 520, 92, 48, 10, 0xFF6B4A2B, 0xFFF3C77A, 3);
        c.text("출발", 92, 544, 26, INK, Canvas.CENTER, TXT);

        List<JsonObject> st = steps();
        int n = Math.min(10, Math.max(10, st.size()));
        int stand = anim.isEmpty() || now - animStart > HOP ? toStep : fromStep;
        // 점선 길
        for (int i = 0; i < n - 1; i++) {
            float[] a = padFoot(i + 1), b = padFoot(i + 2);
            for (int d = 1; d < 6; d++) {
                float t = d / 6f;
                float x = a[0] + (b[0] - a[0]) * t, y = a[1] - 14 + (b[1] - a[1]) * t - (float) Math.sin(t * Math.PI) * 26;
                c.fill(x - 4, y - 2, 8, 4, 2, 0xCCFFFFFF);
            }
        }
        for (int i = 0; i < n; i++) {
            float[] p = PAD[i];
            int s = i + 1;
            String img = s < stand ? "jump/lily_pad_passed" : s == stand ? "jump/lily_pad_current" : "jump/lily_pad_default";
            float bob = (float) Math.sin(now / 520.0 + i) * 1.5f;
            c.image(img, p[0], p[1] + bob, 90, 68, 0xFFFFFFFF);
            c.text(String.valueOf(s), p[0] + 45, p[1] + 83, 30, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            if (i < st.size()) {
                JsonObject o = st.get(i);
                String tag = switch (risk(J.dbl(o, "chance", 50))) { case 0 -> "jump/tag_safe_green"; case 1 -> "jump/tag_medium_orange"; default -> "jump/tag_high_red"; };
                float ty = p[1] - 72;
                c.image(tag, p[0] + 4, ty, 82, 36, 0xFFFFFFFF);   // 꼬리표 간격(83.8)보다 좁게 → 옆 꼬리표와 안 겹침
                c.textFit("×" + ShopPanel.dec(J.dbl(o, "multiplier", 1)), p[0] + 45, ty + 17.5f, 21, 70, INK, Canvas.CENTER, TXT);
            }
        }
        // 개구리
        float[] a = padFoot(fromStep), b = padFoot(toStep);
        long e = now - animStart;
        String sprite = "jump/frog_idle";
        float fx = b[0], fy = b[1];
        boolean airborne = (anim.equals("ok") || anim.equals("fail") || anim.equals("cash") && fromStep != toStep) && e < HOP;
        if (airborne) {
            float t = Anim.clamp(e / (float) HOP), ease = t * t * (3 - 2 * t);
            fx = a[0] + (b[0] - a[0]) * ease;
            fy = a[1] + (b[1] - a[1]) * ease - (float) Math.sin(t * Math.PI) * 70;
            sprite = "jump/frog_jump";
        } else if (resultKind.equals("fail")) {
            sprite = "jump/frog_splash";
        } else if (anim.equals("ok") || anim.equals("cash") || resultKind.equals("gold") || resultKind.equals("rainbow")) {
            sprite = anim.isEmpty() && result == null ? "jump/frog_idle" : "jump/frog_land_cheer";
        }
        if (!playing() && result == null && anim.isEmpty()) { fx = START_X; fy = START_Y; }
        float breathe = sprite.equals("jump/frog_idle") ? (float) Math.sin(now / 400.0) * 2 : 0;
        c.image(sprite, fx - 80, fy - 120 + breathe, 160, 140, 0xFFFFFFFF);
        // 효과
        if (!airborne && e < HOP + LAND && (anim.equals("ok") || anim.equals("cash"))) {
            float t = Anim.clamp((e - HOP) / (float) LAND);
            c.image("jump/landing_sparkle", b[0] - 90, b[1] - 75 - t * 10, 180, 110, Anim.white(1 - t));
        }
        if (resultKind.equals("fail") && now >= resultAt) {
            float t = Anim.clamp((now - resultAt) / 700f);
            c.push();
            c.translate(b[0], b[1] - 20);
            c.scale(.7f + .3f * t, .7f + .3f * t);
            c.image("jump/splash_effect", -90, -55, 180, 110, Anim.white(1 - t * .5f));
            c.pop();
        }
        // 결과 리본
        if (result != null && now >= resultAt) {
            float s = Anim.outBack(Anim.t(now, resultAt, 360));
            String rib = switch (resultKind) { case "fail" -> "jump/result_ribbon_gray"; case "rainbow" -> "jump/result_ribbon_rainbow"; default -> "jump/result_ribbon_gold"; };
            String title = switch (resultKind) { case "fail" -> "풍덩!"; case "rainbow" -> "끝까지 완주!"; default -> J.num(result, "step") + "칸에서 멈춤!"; };
            String sub = resultKind.equals("fail") ? "투자 코인 " + Fmt.num(J.num(result, "bet")) + "개를 잃었어요" : "코인 +" + Fmt.num(J.num(result, "got")) + " (×" + ShopPanel.dec(J.dbl(result, "multiplier", 1)) + ")";
            c.push();
            c.translate(560, 752);   // 연못 아래쪽 물 위 (제목·연잎을 가리지 않게)
            c.scale(s, s);
            c.image(rib, -320, -74, 640, 160, 0xFFFFFFFF);
            int ink = resultKind.equals("fail") ? 0xFF5E5E5E : INK;
            c.textFit(title, -2, -21, 46, 420, ink, Canvas.CENTER, TXT);
            c.textFit(sub, -2, 23, 30, 420, resultKind.equals("fail") ? 0xFF7A7A7A : 0xFF3F7F31, Canvas.CENTER, TXT);
            c.pop();
        }
    }

    private void info(Canvas c, long now, float mx, float my) {
        c.image("jump/information_panel", 1100, 190, 440, 750, 0xFFFFFFFF);
        c.text("진행 정보", 1148, 242, 34, INK, Canvas.LEFT, TXT);
        boolean pl = playing();
        long bet = pl ? J.num(data, "bet") : amt();
        double mult = pl ? J.dbl(data, "multiplier", 1) : 1;
        long pay = pl ? J.num(data, "cashout") : bet;
        String[] lab = {"투자 코인", "현재 배수", "수령 코인"};
        float[] ry = {298, 352, 406};
        for (int i = 0; i < 3; i++) {
            c.text(lab[i], 1148, ry[i], 26, INK, Canvas.LEFT, TXT);
            if (i < 2) c.fill(1146, ry[i] + 25, 348, 2, 1, 0x33A08B72);
        }
        valueWithDiamond(c, Fmt.num(bet), 298);
        c.textFit("×" + ShopPanel.dec(mult), 1492, 352, 28, 150, INK, Canvas.RIGHT, TXT);
        valueWithDiamond(c, Fmt.num(pay), 406);
        c.fill(1146, 433, 348, 2, 1, 0x33A08B72);

        // 다음 성공 확률
        c.image("jump/probability_panel", 1120, 445, 400, 90, 0xFFFFFFFF);
        List<JsonObject> st = steps();
        int next = pl ? (int) J.num(data, "step") : 0;
        double ch = next < st.size() ? J.dbl(st.get(next), "chance", 0) : 0;
        c.text("다음 성공 확률", 1164, 489, 26, INK, Canvas.LEFT, TXT);
        c.textFit(ShopPanel.dec(ch) + "%", 1476, 489, 42, 140, INK, Canvas.RIGHT, TXT);
        // 수량 (시작 전에만) — 정보판 안쪽(1130~1510)에 들어가게 작게
        float dim = pl ? .45f : 1;
        float sy = 560, sh = 52;
        c.image("jump/stepper", 1136, sy, 236, sh, Anim.white(dim));
        c.text("−", 1166, sy + sh / 2, 30, Anim.alpha(INK, dim), Canvas.CENTER, TXT);
        c.text("+", 1342, sy + sh / 2, 30, Anim.alpha(INK, dim), Canvas.CENTER, TXT);
        String q = typed != null ? (typed.isEmpty() ? "0" : typed) : Fmt.num(amt());
        c.textFit(q, 1254, sy + sh / 2, 28, 104, Anim.alpha(INK, dim), Canvas.CENTER, TXT);
        if (typed != null && (now / 530) % 2 == 0) c.fill(1254 + Math.min(104, c.textWidth(q, 28, TXT)) / 2 + 3, sy + 13, 3, 26, 1.5f, 0xFF7CB342);
        boolean hm = !pl && Anim.in(mx, my, 1384, sy, 96, sh);
        c.image("jump/max_button", 1384, sy + (hm ? -2 : 0), 96, sh, Anim.white(dim));
        c.text("최대", 1432, sy + sh / 2 + (hm ? -2 : 0), 24, Anim.alpha(INK, dim), Canvas.CENTER, TXT);
        if (!pl) {
            hits.add(new R("minus", 1136, sy, 60, sh));
            hits.add(new R("input", 1196, sy, 116, sh));
            hits.add(new R("plus", 1312, sy, 60, sh));
            hits.add(new R("max", 1384, sy, 96, sh));
        }

        // 버튼
        boolean busy = waitingSince > 0 || (!anim.isEmpty() && now - animStart < HOP);
        boolean canJump = !busy && (pl ? next < st.size() : amt() > 0 && amt() <= J.num(data, "available"));
        float jy = 640, cy = 742;
        boolean hj = canJump && Anim.in(mx, my, 1120, jy, 400, 90);
        c.image(canJump ? hj ? "jump/jump_hover" : "jump/jump_default" : "jump/jump_disabled", 1120, jy, 400, 90, 0xFFFFFFFF);
        String jl = pl ? "점프!" : J.num(data, "available") <= 0 ? "코인이 없어요" : "투자하고 시작";
        c.textFit(busy ? "..." : jl, 1320, jy + 43, 38, 340, canJump ? 0xFF2F4A1C : 0xFF8A8A80, Canvas.CENTER, TXT);
        hits.add(new R("jump", 1120, jy, 400, 90));
        boolean canCash = pl && !busy && J.num(data, "step") > 0;
        boolean hh = canCash && Anim.in(mx, my, 1120, cy, 400, 90);
        c.image(canCash ? hh ? "jump/cashout_hover" : "jump/cashout_default" : "jump/cashout_disabled", 1120, cy, 400, 90, 0xFFFFFFFF);
        String cl = "정산";
        float cw = c.textWidth(cl, 36, TXT), vw = c.textWidth(Fmt.num(pay), 36, TXT);
        float gx = 1320 - (cw + 20 + 34 + 8 + vw) / 2;
        int cc = canCash ? 0xFF5A3A12 : 0xFF8A8A80;
        c.text(cl, gx, cy + 44, 36, cc, Canvas.LEFT, TXT);
        c.image("shop/coin_32", gx + cw + 20, cy + 28, 34, 34, canCash ? 0xFFFFFFFF : 0x88FFFFFF);
        c.text(Fmt.num(pay), gx + cw + 62, cy + 44, 36, cc, Canvas.LEFT, TXT);
        hits.add(new R("cashout", 1120, cy, 400, 90));
        c.textFit(pl && J.num(data, "step") == 0 ? "한 칸 이상 뛰어야 정산할 수 있어요" : "실패하면 투자 코인을 잃어요", 1320, 868, 22, 370, 0xFF6B5A4C, Canvas.CENTER, TXT);
    }

    private static void valueWithDiamond(Canvas c, String v, float y) {
        float tw = Math.min(150, c.textWidth(v, 28, TXT));
        c.textFit(v, 1492, y, 28, 150, INK, Canvas.RIGHT, TXT);
        c.image("shop/coin_32", 1492 - tw - 44, y - 17, 34, 34, 0xFFFFFFFF);
    }

    // ───────── 입력 ─────────
    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            String key = r.key();
            if (!key.equals("input") && typed != null) { amount = amt(); typed = null; }
            switch (key) {
                case "close" -> host.closePanel(this);
                case "input" -> typed = "";
                case "minus" -> { amount = Math.max(1, amt() - step()); host.sound("minecraft:ui.button.click", 1.3f, .3f); }
                case "plus" -> { amount = Math.min(Math.max(1, maxBet()), amt() + step()); host.sound("minecraft:ui.button.click", 1.5f, .3f); }
                case "max" -> { amount = Math.max(1, maxBet()); host.sound("minecraft:ui.button.click", 1.2f, .3f); }
                case "jump" -> {
                    if (waitingSince > 0 || (!anim.isEmpty() && now - animStart < HOP)) return true;
                    if (playing()) { waitingSince = now; host.send("jump.jump"); }
                    else {
                        long a = amt();
                        if (a <= 0 || a > J.num(data, "available")) { host.sound("minecraft:block.note_block.bass", .7f, .5f); return true; }
                        waitingSince = now;
                        result = null; resultKind = "";
                        host.send("jump.start", "amount", String.valueOf(a));
                    }
                }
                case "cashout" -> {
                    if (!playing() || waitingSince > 0 || J.num(data, "step") <= 0) return true;
                    waitingSince = now;
                    host.send("jump.cashout");
                }
                default -> { }
            }
            return true;
        }
        return false;
    }

    @Override public boolean scroll(float mx, float my, double w) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        if (!playing() && Anim.in(x, y, 1120, 545, 400, 80)) { amount = Math.max(1, Math.min(Math.max(1, maxBet()), amt() + (w > 0 ? 1 : -1))); typed = null; return true; }
        return false;
    }

    @Override public boolean typed(char ch, long now) {
        if (playing()) {
            if (ch == ' ') { click(ox + 1320 * k, oy + 685 * k, now); return true; }
            return false;
        }
        if (ch >= '0' && ch <= '9') {
            if (typed == null) typed = "";
            if (typed.length() < 6) typed = (typed.equals("0") ? "" : typed) + ch;
            return true;
        }
        return false;
    }

    @Override public boolean key(int key, int m, long now) {
        if (key == 259 && typed != null) { if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1); return true; }
        if ((key == 257 || key == 335) && typed != null) { amount = amt(); typed = null; return true; }
        return false;
    }
}
