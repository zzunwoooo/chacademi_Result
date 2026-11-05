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
    private int stopMask;
    private long pressedAt;
    private static final long REEL_MS = 1700, RESULT_MS = 2600;

    private float k = 1, cw0, ch0;   // 배율, 화면 위 원점
    private static final float DW = 820, DH = 540, TOP = 70; // TOP: 위로 튀어나오는 깨굴이 자리
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
        stopMask = 0;
        waitingSince = 0;
        if (id.equals("gacha_destroy")) host.sound("minecraft:entity.item.break", .8f, 1);
        else if (id.equals("gacha_protect")) host.sound("minecraft:item.shield.block", 1.1f, 1);
        else host.sound("minecraft:block.note_block.chime", 1.3f, .6f);
        return true;
    }

    private String machine() { return J.str(data, "machine", "1"); }

    /** 기계별 색: {바깥 테, 안쪽 바탕, 옆판 바탕} — 1 연두 / 2 보라 / 3 숯+금테 */
    private int[] theme() {
        return switch (machine()) {
            case "2" -> new int[]{0xFFF3E9D6, 0xFFE9DDF3, 0xFFEFE6F7};
            case "3" -> new int[]{0xFFE3B95C, 0xFF4E4E54, 0xFF55555B};
            default -> new int[]{0xFFF3E9D6, 0xFFE0ECCE, 0xFFE6F0D8};
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
        c.fill(0, 0, W0, H0, 0, 0x66000000);
        // 레퍼런스 배치(820x460 단위)를 화면에 맞게 키운다: 왼판 · 기계 · 오른판
        k = Math.min(W0 * .92f / DW, H0 * .9f / DH);
        float ox0 = (W0 - DW * k) / 2, oy0 = (H0 - DH * k) / 2;
        c.push();
        c.translate(ox0, oy0);
        c.scale(k, k);
        c.translate(0, TOP);
        cw0 = ox0;
        ch0 = oy0 + TOP * k;
        mx = (mx - cw0) / k;
        my = (my - ch0) / k;
        int[] th = theme();
        boolean dark = machine().equals("3");
        int ink = Theme.INK;
        long max = J.num(data, "max");
        boolean holding = J.bool(data, "holdingPickaxe");
        JsonObject st = J.objOrEmpty(data, "stats");
        long e = fx == null ? Long.MAX_VALUE : now - fxStart;
        boolean live = fx != null && e < life();
        boolean rolling = live && (fxId.equals("gacha_result") || fxId.equals("gacha_jackpot"));

        // ── 왼판: 곡괭이 + 3스탯
        side(c, 0, 75, 200, 245, th);
        MenuPanel.box(c, 50, 88, 105, 90, 16, dark ? 0xFFE3B95C : 0xFFE8DBC2, 0xFFFBF5E8, 2);
        String pick = !holding ? "gacha_machine/pickaxe_empty" : live && fxId.equals("gacha_destroy") ? "gacha_machine/pickaxe_cracked" : "gacha_machine/pickaxe";
        c.imageFit(pick, 102, 133, 72, holding ? 0xFFFFFFFF : 0x88FFFFFF);
        if (!holding) c.text("곡괭이를 들어 주세요", 102, 167, 10, Theme.RED, Canvas.CENTER, Canvas.BOLD);
        for (int i = 0; i < 3; i++) {
            float y = 188 + i * 41;
            c.imageFit(SMALL_ICON[i], 45, y + 15, 28, 0xFFFFFFFF);
            MenuPanel.box(c, 72, y, 112, 30, 15, dark ? 0xFFC9A04A : 0xFFE6D9C0, 0xFFFBF5E8, 1.5f);
            c.text(STAT_NAME[i], 84, y + 15, 12, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.text(String.valueOf(J.num(st, STAT[i])), 174, y + 15, 16, ink, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(STAT[i], 30, y, 160, 30));
        }

        // ── 오른판: 비용 · 코인 · 파괴확률 · 방지권
        side(c, 620, 75, 200, 245, th);
        String[] qi = {"gacha_machine/frog_coin", "gacha_machine/coin_wallet", "gacha_machine/pickaxe_cracked", "gacha_machine/protection_ticket"};
        String[] qn = {"1회 비용", "보유 코인", "파괴 확률", "파괴 방지권"};
        String[] qk = {"cost", "cost", "destroy", "protect"};
        double dc = J.dbl(data, "destroyChance", 0);
        String[] qv = {J.num(data, "cost") + "개", Fmt.num(J.num(data, "coins")) + "개",
                dc <= 0 ? "없음" : (dc == Math.floor(dc) ? String.valueOf((long) dc) : String.format("%.1f", dc)) + "%", J.num(data, "protectTickets") + "장"};
        int[] qc = {ink, J.num(data, "coins") < J.num(data, "cost") ? Theme.RED : ink, dc > 0 ? Theme.RED : Theme.GREEN_DARK, 0xFF8E5BD0};
        for (int i = 0; i < 4; i++) {
            float y = 96 + i * 54;
            c.imageFit(qi[i], 664, y + 22, 34, 0xFFFFFFFF);
            MenuPanel.box(c, 694, y, 112, 44, 16, dark ? 0xFFC9A04A : 0xFFE6D9C0, 0xFFFBF5E8, 1.5f);
            c.textFit(qn[i], 750, y + 13, 11, 96, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(qv[i], 750, y + 30, 16, 96, qc[i], Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(qk[i], 650, y, 160, 44));
        }

        // ── 가운데 기계: 깨굴이(뒤) → 몸통 → 이름판 → 아이콘 → 릴 3칸 → 슬롯 → 돌리기
        boolean cheer = rolling && e >= REEL_MS;
        if (!cheer) {
            String sign = switch (machine()) { case "2" -> "frog_sign_grin"; case "3" -> "frog_sign_villain"; default -> "frog_sign_gentle"; };
            float sw = 175, sh = sw * 315 / 512f, bob = (float) Math.sin(now / 600.0) * 1.5f;
            c.image("gacha_machine/" + sign, 405 - sw / 2, 52 - sh * .58f + bob, sw, sh, 0xFFFFFFFF);
        }
        MenuPanel.box(c, 205, 40, 400, 290, 40, dark ? 0xFFE3B95C : 0xFFE6D6B6, th[0], 3);
        MenuPanel.box(c, 218, 52, 374, 266, 32, dark ? 0xFFC99A3E : 0xFFE9DEC8, th[1], 2);
        HudView.leaf(c, 232, 302, 14, true);
        HudView.leaf(c, 578, 302, 14, false);
        MenuPanel.box(c, 285, 58, 240, 38, 19, 0xFFE6D6B6, 0xFFFBF5E8, 2);
        c.textFit(J.str(data, "label", "가챠"), 405, 77, 17, 220, ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 닫기 (동그라미)
        boolean hc = Anim.in(mx, my, 555, 60, 40, 40);
        MenuPanel.box(c, 555, 60, 40, 40, 20, 0xFFE0CFAE, hc ? 0xFFF3E6CC : 0xFFFBF5E8, 2);
        c.push(); c.translate(575, 80); c.rotate(45);
        c.fill(-11, -2, 22, 4, 2, 0xFF9C8264); c.fill(-2, -11, 4, 22, 2, 0xFF9C8264);
        c.pop();
        hits.add(new R("close", 555, 60, 40, 40));

        long[] before = rolling ? J.longs(fx, "before") : null, after = rolling ? J.longs(fx, "after") : null;
        float[] rx = {255, 362, 469};
        for (int i = 0; i < 3; i++) {
            float x = rx[i], y = 135, w = 86, h = 106, cx = x + w / 2;
            c.imageFit(SMALL_ICON[i], cx, 116, 28, 0xFFFFFFFF);
            boolean maxed;
            MenuPanel.box(c, x, y, w, h, 14, dark ? 0xFFC99A3E : 0xFFE6D9C0, 0xFFFDF8EE, 2);
            long v = J.num(st, STAT[i]);
            if (rolling && after != null && after.length == 3) {
                long stop = 850 + i * 420L;
                if (e < stop) {
                    long fake = 1 + ((e / 55 + i * 3) % Math.max(1, max));
                    c.text(String.valueOf(fake), cx, y + h / 2 + Anim.shake(now, 2, 12), 52, 0xFFB3A391, Canvas.CENTER, Canvas.BOLD);
                    hits.add(new R(STAT[i], x, y, w, h));
                    continue;
                }
                if ((stopMask & (1 << i)) == 0) { stopMask |= 1 << i; host.sound("minecraft:block.note_block.hat", 1 + i * .15f, .6f); }
                v = after[i];
                float t = Anim.t(now, fxStart + stop, 260);
                float sc = 1 + .35f * (1 - Anim.outBack(t));
                boolean up = before != null && before.length == 3 && after[i] > before[i];
                maxed = after[i] >= max;
                c.push(); c.translate(cx, y + h / 2); c.scale(sc, sc);
                c.text(String.valueOf(v), 0, 0, 52, maxed ? 0xFFE0A21B : up ? 0xFF4C9A3A : ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                c.pop();
            } else {
                maxed = v >= max && max > 0;
                c.text(String.valueOf(v), cx, y + h / 2, 52, maxed ? 0xFFE0A21B : ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            }
            if (maxed) c.image("gacha/reel_max_outline", x - 7, y - 7, w + 14, h + 14, Anim.white(.6f + .4f * (float) Math.sin(now / 120.0)));
            hits.add(new R(STAT[i], x, y, w, h));
        }
        // 동전 넣는 곳
        c.fill(362, 249, 86, 9, 4.5f, dark ? 0xFF2C2C30 : 0xFF6E5A47);
        c.fill(364, 250, 82, 3.5f, 1.75f, 0x30FFFFFF);
        // 돌리기
        boolean can = canSpin(now);
        boolean hov = can && Anim.in(mx, my, 305, 268, 200, 38);
        boolean down = pressed && now - pressedAt < 160;
        int red = !can ? 0xFFC9B9B4 : down ? 0xFFD9524A : hov ? 0xFFF4766C : 0xFFEE6A60;
        MenuPanel.box(c, 305, 268 + (down ? 2 : 0), 200, 38, 19, 0xFFF6E7CC, red, 3);
        c.fill(318, 273 + (down ? 2 : 0), 174, 9, 4.5f, 0x30FFFFFF);
        c.text(waitingSince > 0 ? "..." : "돌리기", 405, 287 + (down ? 2 : 0), 20, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new R("spin", 305, 268, 200, 38));

        if (live) drawFx(c, now, e, mx, my);
        c.pop();

        // 툴팁
        JsonObject tips = J.objOrEmpty(data, "tips");
        for (R r : hits) if (Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) {
            String t = J.str(tips, r.key(), "");
            if (!t.isEmpty()) Theme.tooltip(c, t, mx * k + cw0, my * k + ch0);
            break;
        }
    }

    /** 옆판 (테두리 + 바탕 + 위쪽 잎) */
    private void side(Canvas c, float x, float y, float w, float h, int[] th) {
        boolean dark = machine().equals("3");
        HudView.leaf(c, x + 40, y + 2, 12, true);
        HudView.leaf(c, x + w - 40, y + 2, 12, false);
        MenuPanel.box(c, x, y, w, h, 22, dark ? 0xFFE3B95C : 0xFFE6D6B6, th[0], 3);
        MenuPanel.box(c, x + 10, y + 10, w - 20, h - 20, 16, dark ? 0xFFC99A3E : 0xFFE9DEC8, th[2], 1.5f);
    }

    /** 결과 연출 (슬롯머신 레퍼런스): 결과=깨굴이 만세+리본 / 잭팟=햇살+색종이 / 파괴=조각+웃참 / 방지=방패+안도 */
    private void drawFx(Canvas c, long now, long e, float mx, float my) {
        switch (fxId) {
            case "gacha_result", "gacha_jackpot" -> {
                if (e < REEL_MS) return;
                boolean jp = fxId.equals("gacha_jackpot");
                long j = e - REEL_MS;
                float a = Anim.fade(now, fxStart + REEL_MS, life() - REEL_MS, 150, 450);
                float t = Anim.outBack(Anim.t(j, 0, 400));
                if (jp) c.imageAt("gacha/jackpot_sunburst", 405, 20, 190, 1, j / 30f, Anim.white(a * .45f));
                rays(c, 405, 10, 70, a, now);
                c.imageAt(jp ? "gacha/frog_jackpot" : "hud/frog_yay", 405, 0 - 8 * t, 110 * t, 1, 0, Anim.white(a));
                float rw = 330, rh = rw * 247 / 1024f;
                c.image("gacha/jackpot_ribbon", 405 - rw / 2, 330, rw, rh, Anim.white(a));
                long[] af = J.longs(fx, "after");
                String s = jp ? "잭팟!!! " : "";
                if (af.length == 3) s += "행운 " + af[0] + " · 효율 " + af[1] + " · 내구 " + af[2];
                c.textFit(s, 405, 330 + rh * .44f, 16, 240, Anim.alpha(jp ? 0xFFE0564C : Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                if (jp) {
                    if (j < 40) host.sound("minecraft:ui.toast.challenge_complete", 1, 1);
                    c.push(); c.scale(1 / k, 1 / k); c.translate(-cw0, -ch0);
                    FxView.confetti(c, j, a, fxStart);
                    c.pop();
                }
            }
            case "gacha_destroy" -> {
                float a = Anim.fade(now, fxStart, 2600, 80, 500);
                float t = Anim.outCubic(Anim.t(e, 100, 700));
                c.imageAt("gacha_fx/broken_pickaxe_head", 300 - 40 * t, 365 + 20 * t, 42, 1, -30 - 30 * t, Anim.white(a));
                c.imageAt("gacha_fx/broken_pickaxe_handle", 360 + 30 * t, 378 + 15 * t, 62, 1, 40 * t, Anim.white(a));
                c.imageAt("gacha_fx/crack_overlay", 330, 370, 40, 1, 0, Anim.white(a * (1 - t * .5f)));
                float ft = Anim.outBack(Anim.t(e, 300, 400));
                c.imageAt("gacha_fx/frog_stifled_laugh", 505, 380, 110 * ft, 1, Anim.shake(now, 3, 6), Anim.white(a));
                rays(c, 505, 352, 28, a, now);
                plate(c, 405, 440, "곡괭이가 부서졌어요...", Theme.RED, a);
            }
            case "gacha_protect" -> {
                float a = Anim.fade(now, fxStart, 2600, 80, 500);
                float t = Anim.outBack(Anim.t(e, 0, 400));
                c.imageAt("gacha_fx/purple_protection_shield", 320, 375, 100 * t, 1, 0, Anim.white(a));
                c.imageAt("gacha_machine/pickaxe", 320, 372, 50 * t, 1, 0, Anim.white(a));
                rays(c, 320, 345, 30, a, now);
                float ft = Anim.outBack(Anim.t(e, 250, 400));
                c.imageAt("gacha_fx/frog_relief", 500, 378, 105 * ft, 1, 0, Anim.white(a));
                plate(c, 320, 445, "방지권 사용!", 0xFF8E5BD0, a);
                plate(c, 500, 445, "휴, 살았다...", Theme.INK, a);
            }
            default -> { }
        }
    }

    /** 반짝 선 (만화 효과) */
    private static void rays(Canvas c, float x, float y, float r, float a, long now) {
        float pulse = 1 + .08f * (float) Math.sin(now / 120.0);
        for (int i = 0; i < 6; i++) {
            float ang = -150 + i * 24;
            c.push(); c.translate(x, y); c.rotate(ang + 90);
            c.fill(-2.5f, -r * pulse - 14, 5, 14, 2.5f, Anim.alpha(0xFFF2D27A, a));
            c.pop();
        }
    }

    private static void plate(Canvas c, float cx, float cy, String s, int color, float a) {
        float w = Math.max(150, c.textWidth(s, 15, Canvas.BOLD) + 40);
        MenuPanel.box(c, cx - w / 2, cy - 18, w, 36, 10, Anim.alpha(0xFFE6D6B6, a), Anim.alpha(0xFFFBF5E8, a), 2);
        c.text(s, cx, cy, 15, Anim.alpha(color, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - cw0) / k;
        my = (my - ch0) / k;
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
