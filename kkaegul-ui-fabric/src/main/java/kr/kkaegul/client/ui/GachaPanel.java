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
        // 돌리기를 눌렀는데 연출(fx) 없이 갱신만 왔다 = 서버가 거절함 (코인 부족 등) → 바로 다시 누를 수 있게
        if (waitingSince > 0 && fx == null) waitingSince = 0;
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

    /** 기계별 몸통 그림: 1 연두 / 2 보라 / 3 숯+금테 */
    private String body() {
        return switch (machine()) { case "2" -> "gacha/body_purple"; case "3" -> "gacha/body_charcoal_gold"; default -> "gacha/body_green"; };
    }

    /** 몸통 그림 안 크림 창 세로 범위 (몸통 대비 비율, v3 그림에서 잰 값) — 기계마다 다름 */
    private float[] winY() {
        return switch (machine()) { case "2" -> new float[]{.376f, .854f}; case "3" -> new float[]{.397f, .867f}; default -> new float[]{.392f, .843f}; };
    }

    /** 크림 창 3개 가로 범위 (몸통 대비 비율, v3 그림에서 잰 값) — 보라 기계는 창이 바깥쪽으로 벌어져 있음 */
    private float[][] winX() {
        return switch (machine()) {
            case "2" -> new float[][]{{.069f, .325f}, {.375f, .628f}, {.678f, .934f}};
            case "3" -> new float[][]{{.084f, .337f}, {.374f, .627f}, {.664f, .916f}};
            default -> new float[][]{{.099f, .341f}, {.381f, .619f}, {.660f, .902f}};
        };
    }

    // 몸통 그림 자리 (1024x860 비율 그대로)
    private static final float BX = 200, BY = 22, BW = 410, BH = BW * 860 / 1024f;
    // 왼판(panel_info 614x1024) · 오른판(panel_cost 691x1024): 높이 300 기준
    private static final float PY = 44, PH = 300, LX = 10, LW = PH * 614 / 1024f, RW = PH * 691 / 1024f, RX = 820 - RW;
    private static final float SPIN = 78, SPIN_CY = BY + BH + 16;

    private static final String[] STAT = {"fortune", "efficiency", "unbreaking"};
    private static final String[] STAT_NAME = {"행운", "효율", "내구성"};
    private static final String[] STAT_ICON = {"gacha/stat_luck", "gacha/stat_efficiency", "gacha/stat_durability"};
    private static final String[] SMALL_ICON = {"gacha_machine/luck_clover", "gacha_machine/efficiency_bolt", "gacha_machine/durability_shield"};

    private boolean canSpin(long now) { return !busy(now) && blockReason().isEmpty(); }

    /** 못 돌리는 이유 (없으면 ""). 예전엔 세 칸이 모두 기계 max "이상"이면 막아서, 9/15/9 처럼 max 를 넘는 곡괭이는 영영 못 돌렸다. */
    private String blockReason() {
        if (!J.bool(data, "holdingPickaxe")) return "곡괭이를 손에 들어 주세요";
        if (J.num(data, "coins") < J.num(data, "cost")) return "깨굴코인이 부족해요";
        if (allExactMax()) return "이미 이 기계 최대예요 · 다른 기계를 써 주세요";
        return "";
    }

    private boolean allExactMax() {
        JsonObject st = J.objOrEmpty(data, "stats");
        long max = J.num(data, "max");
        if (max <= 0) return false;
        for (String s : STAT) if (J.num(st, s) != max) return false;
        return true;
    }

    /** 이 기계 max 보다 높은 칸이 있으면 돌렸을 때 내려갈 수 있다 */
    private boolean overMax() {
        JsonObject st = J.objOrEmpty(data, "stats");
        long max = J.num(data, "max");
        if (max <= 0) return false;
        for (String s : STAT) if (J.num(st, s) > max) return true;
        return false;
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
        int ink = Theme.INK;
        long max = J.num(data, "max");
        boolean holding = J.bool(data, "holdingPickaxe");
        JsonObject st = J.objOrEmpty(data, "stats");
        long e = fx == null ? Long.MAX_VALUE : now - fxStart;
        boolean live = fx != null && e < life();
        boolean rolling = live && (fxId.equals("gacha_result") || fxId.equals("gacha_jackpot"));

        // ── 왼판 (panel_info): 그림 칸 = 곡괭이, 크림 줄 3개 = 3스탯
        art(c, "gacha_machine/panel_info", LX, PY, LW, PH, 0xFFFFFFFF);
        float picX0 = LX + .217f * LW, picX1 = LX + .787f * LW, picY0 = PY + .157f * PH, picY1 = PY + .484f * PH;
        float picCx = (picX0 + picX1) / 2;
        String pick = !holding ? "gacha_machine/pickaxe_empty" : live && fxId.equals("gacha_destroy") ? "gacha_machine/pickaxe_cracked" : "gacha_machine/pickaxe";
        artFit(c, pick, picCx, (picY0 + picY1) / 2 - (holding ? 0 : 6), holding ? 78 : 62, holding ? 0xFFFFFFFF : 0x88FFFFFF);
        if (!holding) c.textFit("곡괭이를 들어 주세요", picCx, picY1 - 11, 10, picX1 - picX0 - 8, Theme.RED, Canvas.CENTER, Canvas.BOLD);
        float[][] infoY = {{.563f, .646f}, {.691f, .773f}, {.819f, .901f}};
        float rowX0 = LX + .119f * LW, rowX1 = LX + .884f * LW;
        for (int i = 0; i < 3; i++) {
            float y0 = PY + infoY[i][0] * PH, y1 = PY + infoY[i][1] * PH, cy = (y0 + y1) / 2;
            artFit(c, SMALL_ICON[i], rowX0 + 14, cy, 19, 0xFFFFFFFF);
            c.text(STAT_NAME[i], rowX0 + 29, cy, 11, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.text(String.valueOf(J.num(st, STAT[i])), rowX1 - 10, cy, 14, ink, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(STAT[i], rowX0, y0, rowX1 - rowX0, y1 - y0));
        }

        // ── 오른판 (panel_cost): 크림 줄 4개 = 비용 · 코인 · 파괴확률 · 방지권
        art(c, "gacha_machine/panel_cost", RX, PY, RW, PH, 0xFFFFFFFF);
        String[] qi = {"gacha_machine/frog_coin", "gacha_machine/coin_wallet", "gacha_machine/pickaxe_cracked", "gacha_machine/protection_ticket"};
        String[] qn = {"1회 비용", "보유 코인", "파괴 확률", "파괴 방지권"};
        String[] qk = {"cost", "cost", "destroy", "protect"};
        double dc = J.dbl(data, "destroyChance", 0);
        String[] qv = {J.num(data, "cost") + "개", Fmt.num(J.num(data, "coins")) + "개",
                dc <= 0 ? "없음" : (dc == Math.floor(dc) ? String.valueOf((long) dc) : String.format("%.1f", dc)) + "%", J.num(data, "protectTickets") + "장"};
        int[] qc = {ink, J.num(data, "coins") < J.num(data, "cost") ? Theme.RED : ink, dc > 0 ? Theme.RED : Theme.GREEN_DARK, 0xFF8E5BD0};
        float[][] costY = {{.221f, .339f}, {.395f, .518f}, {.576f, .699f}, {.756f, .879f}};
        float qx0 = RX + .101f * RW, qx1 = RX + .895f * RW, qtx = (qx0 + 34 + qx1) / 2;
        for (int i = 0; i < 4; i++) {
            float y0 = PY + costY[i][0] * PH, y1 = PY + costY[i][1] * PH, cy = (y0 + y1) / 2;
            artFit(c, qi[i], qx0 + 19, cy, 25, 0xFFFFFFFF);
            c.textFit(qn[i], qtx, cy - 7.5f, 10, qx1 - qx0 - 44, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(qv[i], qtx, cy + 7.5f, 14, qx1 - qx0 - 44, qc[i], Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R(qk[i], qx0, y0, qx1 - qx0, y1 - y0));
        }

        // ── 가운데 기계: 깨굴이(뒤) → 몸통 그림 → 이름판 글자 → 릴 3칸(그림 속 크림 창) → 동전 구멍 → 돌리기
        boolean cheer = rolling && e >= REEL_MS;
        if (!cheer) {
            String sign = switch (machine()) { case "2" -> "frog_sign_grin"; case "3" -> "frog_sign_villain"; default -> "frog_sign_gentle"; };
            float sw = 170, sh = sw * bodyRatio(c, "gacha_machine/" + sign, 315 / 512f), bob = (float) Math.sin(now / 600.0) * 1.5f;
            art(c, "gacha_machine/" + sign, 405 - sw / 2, BY + .06f * BH - sh * .62f + bob, sw, sh, 0xFFFFFFFF);
        }
        // 잭팟 햇살은 기계 뒤에 (앞에 그리면 이름판 글자를 덮음)
        if (cheer && fxId.equals("gacha_jackpot")) {
            float ja = Anim.fade(now, fxStart + REEL_MS, life() - REEL_MS, 150, 450);
            c.imageAt("gacha/jackpot_sunburst", 405, 20, 190, 1, (e - REEL_MS) / 30f, Anim.white(ja * .45f));
        }
        art(c, body(), BX, BY, BW, BH, 0xFFFFFFFF);
        float plX0 = BX + .178f * BW, plX1 = BX + .823f * BW, plCy = BY + (.088f + .215f) / 2 * BH;
        c.textFit(J.str(data, "label", "가챠"), 405, plCy, 17, plX1 - plX0 - 24, ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 닫기 (오른판 위 오른쪽)
        float cs = 36, cx0 = 820 - cs + 4, cy0 = PY - cs + 2;
        boolean hc = Anim.in(mx, my, cx0, cy0, cs, cs);
        c.image(hc ? "gacha_machine/close_pressed" : "gacha_machine/close_normal", cx0, cy0 + (hc ? 1 : 0), cs, cs, 0xFFFFFFFF);
        hits.add(new R("close", cx0, cy0, cs, cs));

        long[] before = rolling ? J.longs(fx, "before") : null, after = rolling ? J.longs(fx, "after") : null;
        float[] wy = winY();
        float[][] wx = winX();
        float ry0 = BY + wy[0] * BH, ry1 = BY + wy[1] * BH, numCy = (ry0 + ry1) / 2 + 12;
        int maxedCount = 0;
        for (int i = 0; i < 3; i++) {
            float x = BX + wx[i][0] * BW, w = (wx[i][1] - wx[i][0]) * BW, y = ry0, h = ry1 - ry0, cx = x + w / 2;
            artFit(c, SMALL_ICON[i], cx, y + 22, 28, 0xFFFFFFFF);
            boolean maxed;
            long v = J.num(st, STAT[i]);
            if (rolling && after != null && after.length == 3) {
                long stop = 850 + i * 420L;
                if (e < stop) {
                    long fake = 1 + ((e / 55 + i * 3) % Math.max(1, max));
                    c.text(String.valueOf(fake), cx, numCy + Anim.shake(now, 2, 12), 52, 0xFFB3A391, Canvas.CENTER, Canvas.BOLD);
                    hits.add(new R(STAT[i], x, y, w, h));
                    continue;
                }
                if ((stopMask & (1 << i)) == 0) { stopMask |= 1 << i; host.sound("minecraft:block.note_block.hat", 1 + i * .15f, .6f); }
                v = after[i];
                float t = Anim.t(now, fxStart + stop, 260);
                float sc = 1 + .35f * (1 - Anim.outBack(t));
                boolean up = before != null && before.length == 3 && after[i] > before[i];
                maxed = after[i] >= max;
                c.push(); c.translate(cx, numCy); c.scale(sc, sc);
                c.text(String.valueOf(v), 0, 0, 52, maxed ? 0xFFE0A21B : up ? 0xFF4C9A3A : ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                c.pop();
            } else {
                maxed = v >= max && max > 0;
                c.text(String.valueOf(v), cx, numCy, 52, maxed ? 0xFFE0A21B : ink, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            }
            if (maxed) {
                maxedCount++;
                // 금테 반짝이 (가장자리 비율 유지하며 창 크기에 맞춤)
                float ow = w + 10, oc = ow * .3f;
                c.imageNine("gacha/reel_max_outline", x - 5, y - 5, ow, h + 10, .3f, .12f, oc, oc * .12f * 1024 / (.3f * 382), Anim.white(.6f + .4f * (float) Math.sin(now / 120.0)));
            }
            hits.add(new R(STAT[i], x, y, w, h));
        }
        // 세 칸 모두 최대 (잭팟 포함) → 숫자 줄에 777 띠
        if (maxedCount == 3) {
            float tw = (wx[2][1] - wx[0][0]) * BW + 26, th2 = tw * 190 / 1024f;
            c.image("gacha/triple_seven_outline", 405 - tw / 2, numCy - th2 / 2, tw, th2, Anim.white(.75f + .25f * (float) Math.sin(now / 150.0)));
        }
        // 동전 넣는 곳 (창 아래 띠)
        float slW = 92, slH = slW * bodyRatio(c, "gacha_machine/coin_slot", 96 / 512f);
        // v3 는 구멍 둘레에 크림 테가 붙어 커졌음 → 구멍 112→92 로 줄이고 테까지 포함한 전체를 창 아래 틈 가운데에 (창 테두리를 덮지 않게)
        float[] sp = PAD.get("gacha_machine/coin_slot");
        float slGap = ry1 + (BY + .935f * BH - ry1) / 2;
        art(c, "gacha_machine/coin_slot", 405 - slW / 2, slGap - slH / 2 + (sp[1] - sp[3]) / 2 * slH + 2, slW, slH, 0xFFFFFFFF);
        // 돌리기 (주황 동그라미, 몸통 아래쪽에 걸침)
        boolean can = canSpin(now);
        float spx = 405 - SPIN / 2, spy = SPIN_CY - SPIN / 2;
        boolean hov = can && Anim.in(mx, my, spx, spy, SPIN, SPIN);
        boolean down = pressed && now - pressedAt < 160;
        String spinTex = !can ? "gacha_machine/spin_disabled" : down ? "gacha_machine/spin_pressed" : "gacha_machine/spin_normal";
        float grow = hov && !down ? 3 : 0;
        if (!cheer) {   // 결과 리본이 버튼 자리를 덮으므로 그동안은 버튼을 숨김
            art(c, spinTex, spx - grow / 2, spy - grow / 2 + (down ? 2 : 0), SPIN + grow, SPIN + grow, 0xFFFFFFFF);
            c.text(waitingSince > 0 ? "..." : "돌리기", 405, SPIN_CY + (down ? 2 : 0), 17, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }
        hits.add(new R("spin", spx, spy, SPIN, SPIN));
        if (!live && waitingSince == 0) {
            String why = blockReason();
            float wyT = SPIN_CY + SPIN / 2 + 14;
            if (!why.isEmpty()) c.textFit(why, 405, wyT, 12, 400, 0xFFFF8A80, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            else if (overMax()) c.textFit("이 기계는 최대 " + max + " · 지금보다 낮아질 수 있어요", 405, wyT, 12, 400, 0xFFFFC266, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }

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

    /** 결과 연출 (슬롯머신 레퍼런스): 결과=깨굴이 만세+리본 / 잭팟=햇살+색종이 / 파괴=조각+웃참 / 방지=방패+안도 */
    private void drawFx(Canvas c, long now, long e, float mx, float my) {
        switch (fxId) {
            case "gacha_result", "gacha_jackpot" -> {
                if (e < REEL_MS) return;
                boolean jp = fxId.equals("gacha_jackpot");
                long j = e - REEL_MS;
                float a = Anim.fade(now, fxStart + REEL_MS, life() - REEL_MS, 150, 450);
                float t = Anim.outBack(Anim.t(j, 0, 400));
                rays(c, 405, 10, 70, a, now);
                artAt(c, jp ? "gacha/frog_jackpot" : "hud/frog_yay", 405, 0 - 8 * t, 110 * t, 1, 0, Anim.white(a));
                float rw = 330, rh = rw * 247 / 1024f;
                c.image("gacha/jackpot_ribbon", 405 - rw / 2, 330, rw, rh, Anim.white(a));
                long[] af = J.longs(fx, "after");
                String s = jp ? "잭팟!!! " : "";
                long[] dl = J.longs(fx, "delta");
                if (af.length == 3 && dl.length == 3) s += "행운 " + af[0] + d(dl[0]) + " · 효율 " + af[1] + d(dl[1]) + " · 내구 " + af[2] + d(dl[2]);
                else if (af.length == 3) s += "행운 " + af[0] + " · 효율 " + af[1] + " · 내구 " + af[2];
                c.textArc(s, 405, 330 + rh * Theme.RIBBON_CY, 16, Theme.ribbonR(rw), 240, Anim.alpha(jp ? 0xFFE0564C : Theme.INK, a), Canvas.BOLD | Canvas.LIGHT);
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
                c.imageAt("gacha_fx/broken_pickaxe_head", 290 - 40 * t, 370 + 20 * t, 42, 1, -30 - 30 * t, Anim.white(a));
                c.imageAt("gacha_fx/broken_pickaxe_handle", 320 + 6 * t, 385 + 15 * t, 62, 1, 40 * t, Anim.white(a));
                c.imageAt("gacha_fx/crack_overlay", 310, 375, 40, 1, 0, Anim.white(a * (1 - t * .5f)));
                float ft = Anim.outBack(Anim.t(e, 300, 400));
                c.imageAt("gacha_fx/frog_stifled_laugh", 512, 380, 110 * ft, 1, Anim.shake(now, 3, 6), Anim.white(a));
                rays(c, 512, 352, 28, a, now);
                plate(c, 405, 452, "곡괭이가 부서졌어요...", Theme.RED, a);
            }
            case "gacha_protect" -> {
                float a = Anim.fade(now, fxStart, 2600, 80, 500);
                float t = Anim.outBack(Anim.t(e, 0, 400));
                c.imageAt("gacha_fx/purple_protection_shield", 300, 378, 100 * t, 1, 0, Anim.white(a));
                artAt(c, "gacha_machine/pickaxe", 300, 375, 50 * t, 1, 0, Anim.white(a));
                rays(c, 300, 348, 30, a, now);
                float ft = Anim.outBack(Anim.t(e, 250, 400));
                artAt(c, "gacha_fx/frog_relief", 512, 378, 105 * ft, 1, 0, Anim.white(a));
                plate(c, 300, 452, "방지권 사용!", 0xFF8E5BD0, a);
                plate(c, 512, 452, "휴, 살았다...", Theme.INK, a);
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
    /** 이번에 오르내린 값 "(+3)" / "(-2)" */
    private static String d(long v) { return v > 0 ? "(+" + v + ")" : "(" + v + ")"; }

    /**
     * 끝이 잘려 있던 그림을 원본에서 다시 뽑으면서 늘어난 바깥 여백 (예전 그림 크기 대비 비율: 왼, 위, 오른, 아래).
     * 코드는 예전 자리(몸통)를 그대로 쓰고, art()/artAt() 이 여백만큼 바깥으로 넓혀 그려서 잘렸던 장식 끝까지 보이게 한다.
     */
    private static final java.util.Map<String, float[]> PAD = java.util.Map.ofEntries(
            // v3 그림 (다이아·클로버·광택 장식이 예전 자리 밖까지 나옴) — 몸통은 예전 자리 그대로
            java.util.Map.entry("gacha/body_green", new float[]{.0101f, .0160f, .0126f, .0010f}),
            java.util.Map.entry("gacha/body_purple", new float[]{.0372f, .0324f, .0391f, .0417f}),
            java.util.Map.entry("gacha/body_charcoal_gold", new float[]{.0106f, .0047f, .0106f, .0047f}),
            java.util.Map.entry("gacha/frog_jackpot", new float[]{.0051f, .0292f, .0041f, .0071f}),
            java.util.Map.entry("gacha/stat_luck", new float[]{.1557f, .1591f, .1439f, .1470f}),
            java.util.Map.entry("gacha/stat_efficiency", new float[]{.0915f, .0810f, .0936f, .0778f}),
            java.util.Map.entry("gacha_fx/frog_relief", new float[]{.0048f, .0079f, .0079f, .0105f}),
            java.util.Map.entry("gacha_machine/panel_info", new float[]{.0377f, .0092f, .0419f, .0059f}),
            java.util.Map.entry("gacha_machine/panel_cost", new float[]{.0159f, 0, .0159f, .0078f}),
            java.util.Map.entry("gacha_machine/spin_normal", new float[]{.0035f, 0, .0046f, 0}),
            java.util.Map.entry("gacha_machine/spin_pressed", new float[]{.0151f, .0171f, .0116f, .0091f}),
            java.util.Map.entry("gacha_machine/spin_disabled", new float[]{.0035f, 0, 0, 0}),
            java.util.Map.entry("gacha_machine/coin_slot", new float[]{.2539f, .2442f, .2539f, .1935f}),
            java.util.Map.entry("gacha_machine/coin_wallet", new float[]{.0062f, .0030f, 0, 0}),
            java.util.Map.entry("gacha_machine/frog_coin", new float[]{0, .0033f, .0046f, 0}),
            java.util.Map.entry("gacha_machine/protection_ticket", new float[]{.0040f, .1431f, .0030f, .0820f}),
            java.util.Map.entry("gacha_machine/durability_shield", new float[]{.0022f, .0074f, 0, .0148f}),
            java.util.Map.entry("gacha_machine/efficiency_bolt", new float[]{.0075f, 0, .0015f, .0022f}),
            java.util.Map.entry("gacha_machine/luck_clover", new float[]{.0035f, 0, .0035f, 0}),
            java.util.Map.entry("gacha_machine/pickaxe", new float[]{.0110f, 0, 0, 0}),
            java.util.Map.entry("gacha_machine/pickaxe_empty", new float[]{.0036f, .0011f, .0071f, .0079f}),
            java.util.Map.entry("gacha_machine/frog_sign_grin", new float[]{.0289f, .0335f, .0306f, .0134f}),
            java.util.Map.entry("roulette/body_increase", new float[]{.0062f, .0179f, .0055f, .0239f}),
            java.util.Map.entry("roulette/body_decrease", new float[]{.0025f, .0180f, .0008f, .0014f}),
            java.util.Map.entry("roulette/body_big_increase", new float[]{.0023f, 0, .0023f, 0}),
            java.util.Map.entry("roulette/body_big_decrease", new float[]{.0029f, 0, .0029f, 0}),
            java.util.Map.entry("roulette/frog_decrease", new float[]{.0012f, .0023f, 0, .0045f}),
            java.util.Map.entry("roulette/frog_big_decrease", new float[]{0, .0040f, .0019f, .0013f}));

    /** (x,y,w,h) = 그림 몸통 자리. 여백이 있는 그림은 그만큼 넓혀 그림 */
    static void art(Canvas c, String id, float x, float y, float w, float h, int argb) {
        float[] p = PAD.get(id);
        if (p == null) { c.image(id, x, y, w, h, argb); return; }
        c.image(id, x - p[0] * w, y - p[1] * h, w * (1 + p[0] + p[2]), h * (1 + p[1] + p[3]), argb);
    }

    /** imageFit 과 같지만 몸통(여백 빼고)을 box 안에 맞춤 (여백 장식은 바깥으로) */
    static void artFit(Canvas c, String id, float cx, float cy, float box, int argb) {
        float r = bodyRatio(c, id, 1), w = r > 1 ? box / r : box, h = r > 1 ? box : box * r;
        art(c, id, cx - w / 2, cy - h / 2, w, h, argb);
    }

    /** 몸통 세로/가로 비율 (여백 빼고) */
    static float bodyRatio(Canvas c, String id, float fallback) {
        int[] s = c.size(id);
        if (s == null) return fallback;
        float[] p = PAD.getOrDefault(id, new float[4]);
        return s[1] / (1 + p[1] + p[3]) / (s[0] / (1 + p[0] + p[2]));
    }

    /** imageAt 과 같지만 h 는 몸통 높이 (여백은 바깥으로) */
    static void artAt(Canvas c, String id, float cx, float cy, float h, float scale, float rotate, int argb) {
        float w = h / bodyRatio(c, id, 1);
        float[] p = PAD.getOrDefault(id, new float[4]);
        c.push();
        c.translate(cx, cy);
        if (rotate != 0) c.rotate(rotate);
        if (scale != 1) c.scale(scale, scale);
        c.image(id, -w / 2 - p[0] * w, -h / 2 - p[1] * h, w * (1 + p[0] + p[2]), h * (1 + p[1] + p[3]), argb);
        c.pop();
    }
}
