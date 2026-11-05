package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 곡괭이 가챠 기계 창. 가운데 3칸 슬롯(행운/효율/내구성), 왼쪽 내 곡괭이, 오른쪽 비용·코인, 빨간 돌리기 버튼.
 * 돌리기 → gacha.spin machine=N → 서버 fx(gacha_result / gacha_jackpot / gacha_destroy / gacha_protect) 로 연출.
 */
public final class GachaPanel implements Panel {
    private final Host host;
    private JsonObject data = new JsonObject();
    private JsonObject pending;           // 연출 중에 온 새 값 (연출 끝나고 반영)
    private long waitingSince;            // 돌리기 누르고 결과 기다리는 중
    private String fxId;
    private JsonObject fx;
    private long fxStart;
    private boolean pressed;
    private long pressedAt;
    private static final long REEL_MS = 1700, RESULT_MS = 2600;

    private float ox, oy, k = 1, cw0 = DW, ch0 = DH;
    private static final float DW = 1500, DH = 880;
    private record R(String key, float x, float y, float w, float h) {}
    private final java.util.List<R> hits = new java.util.ArrayList<>();

    public GachaPanel(Host host, JsonObject data) { this.host = host; this.data = data == null ? new JsonObject() : data; }

    @Override public String screen() { return "gacha"; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        if (busy(now)) pending = J.merge(pending == null ? data : pending, d);
        else data = J.merge(data, d);
    }

    private boolean busy(long now) {
        if (waitingSince > 0 && now - waitingSince > 5000) waitingSince = 0;
        return waitingSince > 0 || (fx != null && now - fxStart < life());
    }

    private long life() {
        if (fxId == null) return 0;
        return switch (fxId) { case "gacha_jackpot" -> REEL_MS + 3600; case "gacha_result" -> REEL_MS + RESULT_MS; default -> 2600; };
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        if (!id.startsWith("gacha_")) return false;
        fxId = id;
        fx = d;
        fxStart = now;
        waitingSince = 0;
        if (id.equals("gacha_destroy")) host.sound("minecraft:entity.item.break", .8f, 1);
        else if (id.equals("gacha_protect")) host.sound("minecraft:item.shield.block", 1.1f, 1);
        else host.sound("minecraft:block.note_block.chime", 1.3f, .6f);
        return true;
    }

    private String machine() { return J.str(data, "machine", "1"); }

    private String body() {
        return switch (machine()) { case "2" -> "body_purple"; case "3" -> "body_charcoal_gold"; default -> "body_green"; };
    }

    /** 본체별 칸 위치 (그림 비율 %) : 윗판 x0,y0,x1,y1 / 릴 y0,y1 / 릴 x 3개 */
    private float[] plate() {
        return switch (body()) { case "body_purple" -> new float[]{16, 7, 84, 24}; case "body_charcoal_gold" -> new float[]{15, 7, 85, 27}; default -> new float[]{15, 7, 83, 22}; };
    }

    private float[] reelY() {
        return switch (body()) { case "body_purple" -> new float[]{41, 87}; case "body_charcoal_gold" -> new float[]{39, 91}; default -> new float[]{38, 86}; };
    }

    private float[][] reelX() {
        return switch (body()) {
            case "body_purple" -> new float[][]{{9, 35}, {37, 63}, {65, 91}};
            case "body_charcoal_gold" -> new float[][]{{8, 36}, {37, 64}, {65, 92}};
            default -> new float[][]{{9, 34}, {37, 62}, {65, 90}};
        };
    }

    private static final String[] STAT = {"fortune", "efficiency", "unbreaking"};
    private static final String[] STAT_NAME = {"행운", "효율", "내구성"};
    private static final String[] STAT_ICON = {"gacha/stat_luck", "gacha/stat_efficiency", "gacha/stat_durability"};
    private static final String[] SMALL_ICON = {"gacha_machine/luck_clover", "gacha_machine/efficiency_bolt", "gacha_machine/durability_shield"};

    private boolean canSpin(long now) {
        if (busy(now)) return false;
        if (!J.bool(data, "holdingPickaxe")) return false;
        if (J.num(data, "coins") < J.num(data, "cost")) return false;
        JsonObject st = J.objOrEmpty(data, "stats");
        long max = J.num(data, "max");
        boolean allMax = max > 0;
        for (String s : STAT) if (J.num(st, s) < max) allMax = false;
        return !allMax;
    }

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W0 = c.width(), H0 = c.height();
        if (pending != null && !busy(now)) { data = J.merge(data, pending); pending = null; }
        c.fill(0, 0, W0, H0, 0, 0x88000000);
        // 디자인(1500x880)을 화면에 맞게 키운다
        k = Math.min(W0 * .94f / DW, H0 * .86f / DH);
        c.push();
        c.translate(W0 / 2, H0 / 2 + 30);
        c.scale(k, k);
        float W = DW, H = DH;
        c.translate(-DW / 2, -DH / 2);
        ox = 0;
        oy = 0;
        mx = (mx - W0 / 2) / k + DW / 2;
        my = (my - H0 / 2 - 30) / k + DH / 2;
        cw0 = W0;
        ch0 = H0;
        long max = J.num(data, "max");
        boolean holding = J.bool(data, "holdingPickaxe");

        // 깨굴이 간판 (기계마다 표정이 다름)
        String sign = switch (machine()) { case "2" -> "frog_sign_grin"; case "3" -> "frog_sign_villain"; default -> "frog_sign_gentle"; };
        float sw = 380, sh = sw * 315 / 512f;
        float bob = (float) Math.sin(now / 600.0) * 3;
        c.image("gacha_machine/" + sign, ox + DW / 2 - sw / 2, oy - 40 + bob, sw, sh, 0xFFFFFFFF);
        c.text("최대 " + max, ox + DW / 2, oy - 40 + bob + sh * .79f, 30, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 기계 본체
        float bw = 760, bh = bw * 860 / 1024f, bx = ox + DW / 2 - bw / 2, by = oy + sh - 70;
        c.image("gacha/" + body(), bx, by, bw, bh, 0xFFFFFFFF);
        float[] p = plate();
        c.textFit(J.str(data, "label", "가챠"), bx + bw * (p[0] + p[2]) / 200, by + bh * (p[1] + p[3]) / 200 + 2, 32, bw * (p[2] - p[0]) / 100 * .9f, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        JsonObject st = J.objOrEmpty(data, "stats");
        long e = fx == null ? Long.MAX_VALUE : now - fxStart;
        boolean rolling = fx != null && (fxId.equals("gacha_result") || fxId.equals("gacha_jackpot")) && e < life();
        long[] before = rolling ? J.longs(fx, "before") : null, after = rolling ? J.longs(fx, "after") : null;
        float[] ry = reelY();
        float[][] rx = reelX();
        for (int i = 0; i < 3; i++) {
            float x = bx + bw * rx[i][0] / 100, w = bw * (rx[i][1] - rx[i][0]) / 100;
            float y = by + bh * ry[0] / 100, h = bh * (ry[1] - ry[0]) / 100;
            float cx = x + w / 2;
            c.imageFit(STAT_ICON[i], cx, y + h * .2f, h * .26f, 0xFFFFFFFF);
            c.text(STAT_NAME[i], cx, y + h * .4f, 22, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            long v = J.num(st, STAT[i]);
            float numY = y + h * .7f;
            if (rolling && after != null && after.length == 3) {
                long stop = 850 + i * 420L;
                if (e < stop) {
                    // 숫자가 빠르게 바뀐다
                    long fake = 1 + ((e / 55 + i * 3) % Math.max(1, max));
                    c.text(String.valueOf(fake), cx, numY + Anim.shake(now, 3, 12), 74, 0xFF8C7764, Canvas.CENTER, Canvas.BOLD);
                    continue;
                }
                v = after[i];
                float t = Anim.t(now, fxStart + stop, 260);
                if (t < 1 && now - (fxStart + stop) < 20) host.sound("minecraft:block.note_block.hat", 1 + i * .15f, .6f);
                float s = 1 + .35f * (1 - Anim.outBack(t));
                boolean up = before != null && before.length == 3 && after[i] > before[i];
                c.push();
                c.translate(cx, numY);
                c.scale(s, s);
                c.text(String.valueOf(v), 0, 0, 74, after[i] >= max ? 0xFFE0A21B : up ? 0xFF4C9A3A : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                c.pop();
                if (after[i] >= max) c.image("gacha/reel_max_outline", x - 4, y - 4, w + 8, h + 8, Anim.white(.6f + .4f * (float) Math.sin(now / 120.0)));
            } else {
                c.text(String.valueOf(v), cx, numY, 74, v >= max && max > 0 ? 0xFFE0A21B : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                if (v >= max && max > 0) c.image("gacha/reel_max_outline", x - 4, y - 4, w + 8, h + 8, 0xC0FFFFFF);
            }
            hits.add(new R(STAT[i], x, y, w, h));
        }

        // 왼쪽: 내 곡괭이
        float lw = 300, lh = lw * 1024 / 614f, lx = ox + 30, ly = oy + 230;
        c.image("gacha_machine/panel_info", lx, ly, lw, lh, 0xFFFFFFFF);
        String pick = !holding ? "gacha_machine/pickaxe_empty" : "gacha_machine/pickaxe";
        if (fx != null && fxId.equals("gacha_destroy") && e < life()) pick = "gacha_machine/pickaxe_cracked";
        float pcx = lx + lw * .505f, pcy = ly + lh * .31f;
        c.imageFit(pick, pcx, pcy, lw * .48f, holding ? 0xFFFFFFFF : 0x99FFFFFF);
        if (!holding) c.text("곡괭이를 손에 들어 주세요", pcx, ly + lh * .47f, 17, Theme.RED, Canvas.CENTER, Canvas.BOLD);
        float[][] rows = {{55, 63}, {68, 76}, {81, 89}};
        for (int i = 0; i < 3; i++) {
            float rx0 = lx + lw * .12f, rw = lw * .78f, ryy = ly + lh * rows[i][0] / 100, rh = lh * (rows[i][1] - rows[i][0]) / 100;
            c.imageFit(SMALL_ICON[i], rx0 + 26, ryy + rh / 2, rh * .72f, 0xFFFFFFFF);
            c.text(STAT_NAME[i], rx0 + 52, ryy + rh / 2, 20, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.text(String.valueOf(J.num(st, STAT[i])), rx0 + rw - 16, ryy + rh / 2, 24, Theme.INK, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(STAT[i], rx0, ryy, rw, rh));
        }

        // 오른쪽: 비용 / 코인 / 파괴확률 / 방지권
        float qw = 300, qh = qw * 1024 / 691f, qx = ox + DW - qw - 30, qy = oy + 150;
        c.image("gacha_machine/panel_cost", qx, qy, qw, qh, 0xFFFFFFFF);
        float[][] qrows = {{21, 35}, {39, 53}, {57, 71}, {75, 89}};
        String[] qicons = {"gacha_machine/frog_coin", "gacha_machine/coin_wallet", "gacha_machine/pickaxe_cracked", "gacha_machine/protection_ticket"};
        String[] qnames = {"1회 비용", "보유 코인", "파괴 확률", "파괴 방지권"};
        String[] qkeys = {"cost", "cost", "destroy", "protect"};
        double dc = J.dbl(data, "destroyChance", 0);
        String[] qvals = {J.num(data, "cost") + "개", Fmt.num(J.num(data, "coins")) + "개",
                dc <= 0 ? "없음" : (dc == Math.floor(dc) ? String.valueOf((long) dc) : String.format("%.1f", dc)) + "%", J.num(data, "protectTickets") + "장"};
        int[] qcol = {Theme.INK, J.num(data, "coins") < J.num(data, "cost") ? Theme.RED : Theme.INK, dc > 0 ? Theme.RED : Theme.GREEN_DARK, 0xFF8E5BD0};
        for (int i = 0; i < 4; i++) {
            float rx0 = qx + qw * .11f, rw = qw * .78f, ryy = qy + qh * qrows[i][0] / 100, rh = qh * (qrows[i][1] - qrows[i][0]) / 100;
            c.imageFit(qicons[i], rx0 + 34, ryy + rh / 2, rh * .62f, 0xFFFFFFFF);
            c.text(qnames[i], rx0 + 66, ryy + rh * .32f, 19, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(qvals[i], rx0 + 66, ryy + rh * .68f, 28, rw - 76, qcol[i], Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(qkeys[i], rx0, ryy, rw, rh));
        }

        // 돌리기 버튼
        boolean can = canSpin(now);
        float bs = 200, bcx = qx + qw / 2, bcy = qy + qh + bs / 2 + 4;
        boolean hover = can && Anim.in(mx, my, bcx - bs / 2, bcy - bs / 2, bs, bs);
        boolean down = pressed && now - pressedAt < 160;
        String btn = !can ? "spin_disabled" : down ? "spin_pressed" : "spin_normal";
        float bscale = hover && !down ? 1.04f : 1;
        c.imageAt("gacha_machine/" + btn, bcx, bcy, bs, bscale, 0, 0xFFFFFFFF);
        c.text(waitingSince > 0 ? "..." : "돌리기", bcx, bcy - 4, 36, can ? Theme.WHITE : 0xFFF0E6DA, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new R("spin", bcx - bs / 2, bcy - bs / 2, bs, bs));

        // 닫기
        float cs = 64, ccx = ox + DW - 20, ccy = oy + 40;
        boolean ch = Anim.in(mx, my, ccx - cs / 2, ccy - cs / 2, cs, cs);
        c.imageFit(ch ? "gacha_machine/close_pressed" : "gacha_machine/close_normal", ccx, ccy, cs, 0xFFFFFFFF);
        hits.add(new R("close", ccx - cs / 2, ccy - cs / 2, cs, cs));

        // 결과 연출
        if (fx != null && e < life()) drawFx(c, now, e, bx, by, bw, bh, pcx, pcy, lw);

        c.pop();
        // 툴팁 (화면 좌표로)
        JsonObject tips = J.objOrEmpty(data, "tips");
        for (R r : hits) if (Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) {
            String t = J.str(tips, r.key(), "");
            if (!t.isEmpty()) Theme.tooltip(c, t, (mx - DW / 2) * k + W0 / 2, (my - DH / 2) * k + H0 / 2 + 30);
            break;
        }
    }

    private void drawFx(Canvas c, long now, long e, float bx, float by, float bw, float bh, float pcx, float pcy, float lw) {
        float W = DW, H = DH;
        switch (fxId) {
            case "gacha_jackpot" -> {
                if (e < REEL_MS) return;
                long j = e - REEL_MS;
                float a = Anim.fade(now, fxStart + REEL_MS, life() - REEL_MS, 200, 500);
                if (j < 40) host.sound("minecraft:ui.toast.challenge_complete", 1, 1);
                float[] ry = reelY();
                float y0 = by + bh * ry[0] / 100, y1 = by + bh * ry[1] / 100;
                c.image("gacha/triple_seven_outline", bx + bw * .05f, y0 - 20, bw * .9f, (y1 - y0) + 40, Anim.white(a * (.6f + .4f * (float) Math.sin(now / 90.0))));
                c.imageAt("gacha/jackpot_sunburst", W / 2, H * .42f, 620, 1, j / 30f, Anim.white(a * .7f));
                float t = Anim.outBack(Anim.t(j, 0, 500));
                c.imageAt("gacha/frog_jackpot", W / 2, H * .42f, 340 * t, 1, 0, Anim.white(a));
                float rw = 760, rh = rw * 247 / 1024f;
                c.image("gacha/jackpot_ribbon", W / 2 - rw / 2, H * .6f, rw, rh, Anim.white(a));
                c.text("잭팟!!!", W / 2, H * .6f + rh * .42f, 52, Anim.alpha(0xFFE0564C, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                FxView.confetti(c, j, a, fxStart);
            }
            case "gacha_result" -> {
                if (e < REEL_MS) return;
                float a = Anim.fade(now, fxStart + REEL_MS, RESULT_MS, 150, 500);
                long[] af = J.longs(fx, "after");
                if (af.length == 3) {
                    String s = "행운 " + af[0] + " · 효율 " + af[1] + " · 내구성 " + af[2];
                    float y = by + bh + 34;
                    float w = c.textWidth(s, 30, Canvas.BOLD) + 60;
                    c.fill(W / 2 - w / 2, y - 26, w, 52, 26, Anim.alpha(0xF2FFF8EA, a));
                    c.text(s, W / 2, y, 30, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                }
            }
            case "gacha_destroy" -> {
                float a = Anim.fade(now, fxStart, 2600, 80, 500);
                float t = Anim.t(e, 150, 900);
                c.imageAt("gacha_fx/crack_overlay", pcx, pcy, lw * .55f, 1, 0, Anim.white(a * (e < 150 ? e / 150f : 1)));
                if (e > 150) {
                    c.imageAt("gacha_fx/broken_pickaxe_head", pcx - 120 * t, pcy - 40 + 260 * t * t, lw * .3f, 1, -80 * t, Anim.white(a * (1 - t * .6f)));
                    c.imageAt("gacha_fx/broken_pickaxe_handle", pcx + 80 * t, pcy + 300 * t * t, lw * .35f, 1, 60 * t, Anim.white(a * (1 - t * .6f)));
                }
                float ft = Anim.outBack(Anim.t(e, 400, 400));
                c.imageAt("gacha_fx/frog_stifled_laugh", W / 2, H * .45f, 300 * ft, 1, Anim.shake(now, 3, 6), Anim.white(a));
                banner(c, "곡괭이가 부서졌어요...", Theme.RED, a, W, H);
            }
            case "gacha_protect" -> {
                float a = Anim.fade(now, fxStart, 2600, 80, 500);
                float t = Anim.outBack(Anim.t(e, 0, 400));
                c.imageAt("gacha_fx/purple_protection_shield", pcx, pcy, lw * .6f * t, 1, 0, Anim.white(a * .95f));
                float tt = Anim.t(e, 500, 700);
                c.imageAt("gacha_fx/torn_ticket_left", W / 2 - 60 - 90 * tt, H * .35f + 200 * tt * tt, 150, 1, -40 * tt, Anim.white(a * (1 - tt)));
                c.imageAt("gacha_fx/torn_ticket_right", W / 2 + 60 + 90 * tt, H * .35f + 220 * tt * tt, 150, 1, 40 * tt, Anim.white(a * (1 - tt)));
                float ft = Anim.outBack(Anim.t(e, 300, 400));
                c.imageAt("gacha_fx/frog_relief", W / 2, H * .5f, 300 * ft, 1, 0, Anim.white(a));
                banner(c, "파괴 방지권이 막아줬어요!", 0xFF8E5BD0, a, W, H);
            }
            default -> { }
        }
    }

    private void banner(Canvas c, String s, int color, float a, float W, float H) {
        float w = c.textWidth(s, 36, Canvas.BOLD) + 80;
        c.fill(W / 2 - w / 2, H * .72f - 34, w, 68, 34, Anim.alpha(0xF5FFF8EA, a));
        c.text(s, W / 2, H * .72f, 36, Anim.alpha(color, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - cw0 / 2) / k + DW / 2;
        my = (my - ch0 / 2 - 30) / k + DH / 2;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            if (r.key().equals("close")) { host.closePanel(this); return true; }
            if (r.key().equals("spin")) {
                if (!canSpin(now)) {
                    host.sound("minecraft:block.note_block.bass", .6f, .6f);
                    return true;
                }
                pressed = true;
                pressedAt = now;
                waitingSince = now;
                fx = null;
                host.sound("minecraft:block.stone_button.click_on", 1, 1);
                host.send("gacha.spin", "machine", machine());
                return true;
            }
        }
        return false;
    }
}
