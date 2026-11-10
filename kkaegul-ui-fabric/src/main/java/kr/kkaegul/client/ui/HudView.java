package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 왼쪽 위 상시 HUD: 깨굴이 얼굴 + 운빨 게이지 + (남은 다이아 / 보유 다이아 / 깨굴코인) + 상태 뱃지.
 * 채팅창 등으로 커서가 보일 때 각 요소에 마우스를 올리면 서버가 보낸 tips 문구를 툴팁으로 띄운다.
 */
public final class HudView {
    private JsonObject data = new JsonObject();
    private boolean has;
    private record Pop(String text, int color, long start, float x, float y) {}
    private final List<Pop> pops = new ArrayList<>();
    private record Hit(String key, float x, float y, float w, float h) {}
    private final List<Hit> hits = new ArrayList<>();
    private long gaugeAnimFrom, gaugeAnimStart;
    private float shownGauge = -1;
    private long lastDebt = Long.MIN_VALUE;
    /** HUD 크기 배율 (설정) */
    public float scale = 1f;
    /** 운빨 게이지 막대 가운데 = 화면 가운데 + 이만큼 (오른쪽 깨굴이 자리만큼 왼쪽). 위 가운데 배너도 여기에 맞춤 */
    public static final float GAUGE_DX = -30;
    /** 드롭다운: 열림 여부와 ▼ 아래 꼬리 자리 (HUD 좌표) */
    public boolean menuOpen;
    public float menuX = 497, menuY = 62;
    private float arrowTurn;
    /** 미니게임 제목 리본이 위 가운데에 있을 때 게이지 숨김 */
    public boolean hideGauge;
    public boolean visible = true;

    public boolean has() { return has; }
    public JsonObject data() { return data; }

    public void set(JsonObject d, long now) {
        if (d == null) return;
        long debt = J.num(d, "debt", lastDebt);
        if (has && lastDebt != Long.MIN_VALUE && d.has("debt") && debt != lastDebt) popDebt(debt - lastDebt, now);
        lastDebt = debt;
        data = J.merge(data, d);
        if (d.has("fever")) feverAt = now;
        has = true;
    }

    private long feverAt;
    /** 서버가 보낸 남은 초에서 받은 뒤 지난 시간을 빼서 1초마다 줄어 보이게 */
    private long left(long sec, long now) { return sec <= 0 ? 0 : Math.max(0, sec - (now - feverAt) / 1000); }

    /** update screen=gauge */
    public void gauge(JsonObject g, long now) {
        JsonObject d = new JsonObject();
        d.add("gauge", g);
        set(d, now);
    }

    /** update screen=diamond {debt, delta, reason} */
    public void diamond(JsonObject d, long now) {
        JsonObject x = new JsonObject();
        if (d.has("debt")) x.add("debt", d.get("debt"));
        if (d.has("protected")) x.add("protected", d.get("protected"));
        set(x, now);
    }

    public void fever(JsonObject f, long now) {
        JsonObject d = new JsonObject();
        d.add("fever", f);
        set(d, now);
    }

    private void popDebt(long delta, long now) {
        // 남은 다이아가 늘면(빨강) 나쁜 것, 줄면(초록) 좋은 것
        pops.add(new Pop((delta > 0 ? "+" : "") + Fmt.num(delta), delta > 0 ? 0xFFFF6B5E : 0xFF7BE07A, now, 0, 0));
        if (pops.size() > 3) pops.remove(0);
    }

    /** 게이지 단계별 깨굴이 (연잎 위에 앉은 전신) */
    static String frog(String tier) {
        return switch (tier) {
            case "low" -> "hud/frog_sad";
            case "high" -> "hud/frog_smile";
            case "jackpot" -> "hud/frog_yay";
            default -> "hud/frog_neutral";
        };
    }

    /** 단계별 채움 색 (레퍼런스: 낮음 회녹 / 보통 연두 / 높음 노랑 / 잭팟 무지개) */
    static int fillColor(String tier) {
        return switch (tier) {
            case "low" -> 0xFF7F9683;
            case "high" -> 0xFFFFCF3A;
            default -> 0xFF9ED04A;
        };
    }

    /**
     * v3 아이콘은 예전 자른 영역(몸통) 밖에도 반짝이·테두리 장식이 있어 여백째 뽑았다. 여백 = 그림 픽셀 {왼, 위, 오른, 아래}.
     * fit/at 은 몸통이 예전과 같은 자리·크기에 오게 그리고, 여백(장식)은 바깥으로 더 그린다.
     */
    private static final java.util.Map<String, float[]> PAD = java.util.Map.ofEntries(
            java.util.Map.entry("hud/diamond", new float[]{2.9f, 0, 2.9f, 2.9f}),
            java.util.Map.entry("hud/expand_arrow", new float[]{3, 0, 0, 7}),
            java.util.Map.entry("hud/fever", new float[]{11.6f, 5.3f, 6.2f, 0}),
            java.util.Map.entry("hud/frog_coin", new float[]{6.9f, 5.5f, 0, 0}),
            java.util.Map.entry("hud/frog_neutral", new float[]{2.4f, 1.8f, 0, 0}),
            java.util.Map.entry("hud/frog_sad", new float[]{1.8f, 0, 2.4f, 0}),
            java.util.Map.entry("hud/frog_smile", new float[]{0, 0, 2.4f, 0}),
            java.util.Map.entry("hud/prison_bars", new float[]{61, 71, 30, 29}),
            java.util.Map.entry("hud/protection_shield", new float[]{16.8f, 51.9f, 13.2f, 0}),
            java.util.Map.entry("hud/target_reticle", new float[]{20.6f, 12.4f, 22, 8.9f}));

    /** imageFit 과 같되 몸통(여백 뺀 부분)의 긴 변이 box, 몸통 가운데가 (cx,cy) */
    public static void fit(Canvas c, String id, float cx, float cy, float box, int argb) {
        int[] s = c.size(id);
        float[] p = PAD.get(id);
        if (s == null || p == null) { FxView.artFit(c, id, cx, cy, box, argb); return; }   // HUD 표에 없으면 피버·가챠 쪽 여백 표로
        float bw = s[0] - p[0] - p[2], bh = s[1] - p[1] - p[3], k = box / Math.max(bw, bh);
        c.image(id, cx - (bw / 2 + p[0]) * k, cy - (bh / 2 + p[1]) * k, s[0] * k, s[1] * k, argb);
    }

    /** imageAt 과 같되 h = 몸통 높이, 몸통 가운데가 (cx,cy) */
    public static void at(Canvas c, String id, float cx, float cy, float h, float scale, float rotate, int argb) {
        int[] s = c.size(id);
        float[] p = PAD.get(id);
        if (s == null || p == null) { c.imageAt(id, cx, cy, h, scale, rotate, argb); return; }
        float bw = s[0] - p[0] - p[2], bh = s[1] - p[1] - p[3], k = h / bh;
        c.push();
        c.translate(cx, cy);
        if (rotate != 0) c.rotate(rotate);
        if (scale != 1) c.scale(scale, scale);
        c.image(id, -(bw / 2 + p[0]) * k, -(bh / 2 + p[1]) * k, s[0] * k, s[1] * k, argb);
        c.pop();
    }

    /**
     * hud/gauge_frame (v3 1024×216 = 원본 x96~2082·y132~550, 픽셀 실측). 막대 판 = 원본 x107~2068·y236~502 (높이 266).
     * 가로 경계(원본 x): 왼쪽 끝(둥근 끝+클로버·다이아) · 별 · 별 · 별 · 오른쪽 끝(클로버·다이아+둥근 끝)은 늘이지 않고,
     * 그 사이 민 금테 구간만 늘인다 → 장식·별이 찌그러지거나 잘리지 않음
     */
    private static final float[] GF_X = {96, 451, 538, 561, 1065, 1108, 1608, 1643, 1715, 2082};
    private static final boolean[] GF_FIX = {true, false, true, false, true, false, true, false, true};

    /** 가로로만 늘이는 띠: xs = 그림 가로 경계(원본 픽셀), fix 칸은 k 배 그대로, 나머지 칸이 남은 폭을 길이 비례로 나눠 가짐 */
    static void stripH(Canvas c, String id, float x, float y, float w, float h, float[] xs, boolean[] fix, float k, int argb) {
        float span = xs[xs.length - 1] - xs[0], fixed = 0, free = 0;
        for (int i = 0; i < fix.length; i++) { float n = (xs[i + 1] - xs[i]) * k; if (fix[i]) fixed += n; else free += n; }
        float f = free > 0 ? Math.max(0, w - fixed) / free : 1, xx = x;
        for (int i = 0; i < fix.length; i++) {
            float pw = (xs[i + 1] - xs[i]) * k * (fix[i] ? 1 : f);
            c.imagePart(id, xx, y, pw, h, (xs[i] - xs[0]) / span, 0, (xs[i + 1] - xs[0]) / span, 1, argb);
            xx += pw;
        }
    }

    /**
     * 운빨 게이지 막대 (게이지 상세 레퍼런스): 왼쪽 잎 · 금테 막대 · 오른쪽 잎 · 연잎 위 깨굴이.
     * x,y = 막대 왼쪽 위, w,h = 막대 크기. 오른쪽에 깨굴이 자리(h*3)만큼 더 쓴다.
     */
    public static void gaugeBar(Canvas c, float x, float y, float w, float h, float ratio, String tier, long now) { gaugeBar(c, x, y, w, h, ratio, tier, now, 1f); }

    /** fs = 깨굴이(연잎 포함) 크기 배율 */
    public static void gaugeBar(Canvas c, float x, float y, float w, float h, float ratio, String tier, long now, float fs) {
        // (예전 왼쪽 잎은 v3 테두리 그림의 클로버 장식이 대신함)
        // 안쪽 크림 홈 (테두리는 아래 hud/gauge_frame 그림이 덮음)
        c.fill(x + h * .06f, y + h * .08f, w - h * .12f, h - h * .16f, (h - h * .16f) / 2, 0xFFFDF4E2);
        float pad = h * .22f;
        float iw = (w - pad * 2) * Math.max(0, Math.min(1, ratio)), ih = h - pad * 2;
        if (iw > 1) {
            if ("jackpot".equals(tier)) {
                // 무지개 채움 그림(1024×92) 전체를 채운 길이에 9조각으로: 양 끝 둥근 금테(가로 6%)는 그대로, 가운데만 줄임 (끝을 자르지 않음)
                float jw = Math.max(ih * 1.4f, iw), jk = ih / 92f;
                c.imageNine("hud/gauge_fill_jackpot", x + pad, y + pad, jw, ih, .06f, .45f, 1024 * .06f * jk, ih * .45f, 0xFFFFFFFF);
                c.fill(x + pad, y + pad, jw, ih * .45f, ih * .22f, Anim.alpha(0x66FFFFFF, .5f + .5f * (float) Math.sin(now / 160.0)));
            } else {
                c.fill(x + pad, y + pad, Math.max(ih, iw), ih, ih / 2, fillColor(tier));
                c.fill(x + pad + ih * .3f, y + pad + ih * .12f, Math.max(ih, iw) - ih * .6f, ih * .3f, ih * .15f, 0x40FFFFFF);
            }
        }
        // 테두리 그림: 막대 판(원본 x107~2068·y236~502)이 막대 칸(x,y,w,h)에 딱 맞게, 장식 여백은 바깥으로 (GF_X 설명)
        float fk = h / 266f;
        stripH(c, "hud/gauge_frame", x - (107 - 96) * fk, y - (236 - 132) * fk, w + (107 - 96 + 2082 - 2068) * fk, 418 * fk, GF_X, GF_FIX, fk, 0xFFFFFFFF);
        // 연잎 + 깨굴이
        float lh = h * fs;
        float px = x + w + lh * 2.1f, py = y + h * .5f + lh * .85f;
        c.image("hud/lily_pad", px - lh * 1.6f, py - lh * .56f, lh * 3.2f, lh * 1.12f, 0xFFFFFFFF);
        float bob = "jackpot".equals(tier) ? Math.abs((float) Math.sin(now / 180.0)) * lh * .25f : (float) Math.sin(now / 600.0) * 1.5f;
        at(c, frog(tier), px, py - lh * 1.2f - bob, lh * 2.6f, 1, 0, 0xFFFFFFFF);
    }

    public void render(Canvas c, long now) {
        hits.clear();
        if (!has || !visible) return;
        c.push();
        c.scale(scale, scale);
        float W = c.width() / scale;

        // ── 왼쪽 위: 남은 다이아 | 보유 다이아 | 코인 | ▼
        // hud/currency_frame (v3 1024×170 = 원본 x16~2158·y169~524). 몸통(예전 자른 영역) = 원본 x75~2100·y224~461 (2025×237).
        // 몸통이 (cx,cy,cw,ch) 에 오고 왼쪽 위 클로버·오른쪽 아래 다이아 장식(원본 왼 59·위 55·오른 58·아래 63)은 바깥으로 더 그림.
        // 왼쪽 위 장식이 화면 밖으로 잘리지 않게 그만큼 안쪽으로 들여 놓음
        float cw = 520, ck = cw / 2025f, ch = 237 * ck, cx = 3 + 59 * ck, cy = 2 + 55 * ck;
        c.image("hud/currency_frame", cx - 59 * ck, cy - 55 * ck, 2142 * ck, 355 * ck, 0xFFFFFFFF);
        // 안쪽 크림 칸 (원본 픽셀 실측, 몸통 왼쪽 위 기준): 세로 76~209 → 가운데 142.5, 칸 x 30~624 / 633~1235 / 1243~1844 / 1852~2004
        float bodyY = cy + 76 * ck, bodyH = 133 * ck, mid = cy + 142.5f * ck;
        float[][] seg = {{30, 624}, {633, 1235}, {1243, 1844}};
        String[] keys = {"target", "held", "coin"};
        long[] vals = {J.num(data, "debt"), J.num(data, "held"), J.num(data, "coins")};
        for (int i = 0; i < 3; i++) {
            float sx = cx + seg[i][0] * ck, sw = (seg[i][1] - seg[i][0]) * ck;
            float ix = sx + 26;
            // 아이콘은 칸 높이(약 34) 안에: 조준틀이 금테 위로 넘쳐 왼쪽 둥근 끝을 덮지 않게
            if (i == 0) {
                fit(c, "hud/diamond", ix, mid, 21, 0xFFFFFFFF);
                fit(c, "hud/target_reticle", ix, mid, bodyH - 4, 0xFFFFFFFF);
            } else if (i == 1) fit(c, "hud/diamond", ix, mid, 28, 0xFFFFFFFF);
            else fit(c, "hud/frog_coin", ix, mid, 28, 0xFFFFFFFF);
            c.textFit(Fmt.num(vals[i]), ix + 24, mid, 22, sw - 56, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new Hit(keys[i], sx, bodyY, sw, bodyH));
        }
        arrowTurn += ((menuOpen ? 1 : 0) - arrowTurn) * .3f;
        menuX = cx + 1928 * ck;
        menuY = cy + ch - 1;
        if (arrowTurn < .01f) fit(c, "hud/expand_arrow", menuX, mid, 18, 0xFFFFFFFF);
        else {
            c.push();
            c.translate(menuX, mid);
            c.rotate(180 * Anim.inOut(arrowTurn));
            fit(c, "hud/expand_arrow", 0, 0, 18, 0xFFFFFFFF);
            c.pop();
        }
        hits.add(new Hit("menu", cx + 1852 * ck, bodyY, 152 * ck, bodyH));

        // ── 그 아래: 상태 아이콘 + 남은 시간
        float bx = cx + 6, by = cy + ch + 8;
        if (J.bool(data, "protected")) bx = status(c, "hud/protection_shield", null, "보호", bx, by, "protection");
        JsonObject fev = J.objOrEmpty(data, "fever");
        long fd = J.num(fev, "diamond"), ft = J.num(fev, "triple"), fb = J.num(fev, "box");
        ft = left(ft, now); fd = left(fd, now); fb = left(fb, now);
        if (ft > 0) bx = status(c, "fever/badge_triple", "fever/lightning", Fmt.clock(ft), bx, by, "fever");
        else if (fd > 0) bx = status(c, "hud/fever", null, Fmt.clock(fd), bx, by, "fever");
        if (fb > 0) bx = status(c, "fever/badge_chest", "fever/gift", Fmt.clock(fb), bx, by, "fever");
        JsonObject jail = J.obj(data, "jail");
        if (jail != null) bx = status(c, "hud/prison_bars", null, J.num(jail, "done") + "/" + J.num(jail, "required"), bx, by, "jail");
        long mail = J.num(data, "mail");
        if (mail > 0) status(c, "invite/envelope_closed", null, "우편 " + mail, bx, by, "mail");

        // ── 위 가운데: 운빨 게이지
        JsonObject g = J.objOrEmpty(data, "gauge");
        long value = J.num(g, "value"), max = Math.max(1, J.num(g, "max", 100));
        String tier = J.str(g, "tier", "mid");
        if (shownGauge < 0) shownGauge = value;
        shownGauge += (value - shownGauge) * 0.15f;
        if (Math.abs(value - shownGauge) < 0.05f) shownGauge = value;
        float gw = 520, gh = 40, gx = W / 2 - gw / 2 + GAUGE_DX, gy = 44;
        if (!hideGauge) gaugeBar(c, gx, gy, gw, gh, shownGauge / max, tier, now, .62f);
        // 가운데 퍼센트
        long pct = Math.round(shownGauge * 100.0 / max);
        if (!hideGauge) c.text(pct + "%", gx + gw / 2, gy + gh / 2, gh * .62f, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new Hit("gauge", gx - 20, gy - 20, gw + gh * 3 + 20, gh + 40));

        // 목표(남은) 다이아 변화 팝업: 화면 가운데 아래 액션바 근처, 다이아 아이콘과 함께
        float H = c.height() / scale;
        pops.removeIf(p -> now - p.start() > 1800);
        int n = 0;
        for (Pop p : pops) {
            float t = (now - p.start()) / 1800f;
            float a = t < .12f ? t / .12f : 1 - Anim.clamp((t - .65f) / .35f);
            float py = H - 96 - n * 34 - 22 * Anim.outCubic(t);
            float tw = c.textWidth(p.text(), 30, Canvas.BOLD | Canvas.OUTLINE);
            float px = W / 2 - (tw + 38) / 2;
            fit(c, "hud/diamond", px + 15, py, 30, Anim.white(a));
            c.text(p.text(), px + 38, py, 30, Anim.alpha(p.color(), a), Canvas.LEFT, Canvas.BOLD | Canvas.OUTLINE);
            n++;
        }
        c.pop();
    }

    /** 아이콘 + 그 아래 시간 글자 (배경 없음) */
    private float status(Canvas c, String icon, String inner, String text, float x, float y, String key) {
        float s = 44;
        float w = Math.max(s, c.textWidth(text, 19, Canvas.BOLD | Canvas.OUTLINE)) + 10;
        float cx = x + w / 2;
        fit(c, icon, cx, y + s / 2, s, 0xFFFFFFFF);
        if (inner != null) fit(c, inner, cx, y + s / 2, s * .55f, 0xFFFFFFFF);
        c.text(text, cx, y + s + 11, 19, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new Hit(key, x, y, w, s + 20));
        return x + w + 6;
    }

    /** 커서로 누른 HUD 요소 (가상 좌표), 없으면 null */
    public String hitKey(float mx, float my) {
        if (!has || !visible) return null;
        float lx = mx / scale, ly = my / scale;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Anim.in(lx, ly, h.x(), h.y(), h.w(), h.h())) return h.key();
        }
        return null;
    }

    /** 커서가 보일 때 툴팁 (가상 좌표) */
    public void tooltip(Canvas c, float mx, float my) {
        if (!has || !visible) return;
        JsonObject tips = J.objOrEmpty(data, "tips");
        float lx = mx / scale, ly = my / scale;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Anim.in(lx, ly, h.x(), h.y(), h.w(), h.h())) {
                String tip = J.str(tips, h.key(), "");
                if (tip.isEmpty() && h.key().equals("mail")) tip = "&b우편함\n&f받을 선물이 " + J.num(data, "mail") + "개 있어요\n&7/우편함";
                Theme.tooltip(c, tip, mx, my);
                return;
            }
        }
    }
}
