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
    public boolean visible = true;

    public boolean has() { return has; }
    public JsonObject data() { return data; }

    public void set(JsonObject d, long now) {
        if (d == null) return;
        long debt = J.num(d, "debt", lastDebt);
        if (has && lastDebt != Long.MIN_VALUE && d.has("debt") && debt != lastDebt) popDebt(debt - lastDebt, now);
        lastDebt = debt;
        data = J.merge(data, d);
        has = true;
    }

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

    private float lastSegX = 160, lastSegY = 120;

    private void popDebt(long delta, long now) {
        // 남은 다이아가 늘면(빨강) 나쁜 것, 줄면(초록) 좋은 것
        pops.add(new Pop((delta > 0 ? "+" : "") + Fmt.num(delta), delta > 0 ? 0xFFFF6B5E : 0xFF7BE07A, now, lastSegX, lastSegY));
        if (pops.size() > 6) pops.remove(0);
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

    /** 잎사귀 두 장 (줄기 끝 장식) */
    /**
     * 잎 두 장 (hud/leaf.png: 줄기 끝이 오른쪽 아래, 잎은 왼쪽 위).
     * (x,y) = 줄기 끝. flip=true 면 그대로(잎이 왼쪽), false 면 좌우 반전(잎이 오른쪽).
     */
    static void leaf(Canvas c, float x, float y, float size, boolean flip) {
        float h = size * 1.6f, w = h * 160 / 120f;
        c.push();
        c.translate(x, y);
        if (!flip) c.scale(-1, 1);
        c.image("hud/leaf", -w * 150 / 160f, -h * 104 / 120f, w, h, 0xFFFFFFFF);
        c.pop();
    }

    /**
     * 운빨 게이지 막대 (게이지 상세 레퍼런스): 왼쪽 잎 · 금테 막대 · 오른쪽 잎 · 연잎 위 깨굴이.
     * x,y = 막대 왼쪽 위, w,h = 막대 크기. 오른쪽에 깨굴이 자리(h*3)만큼 더 쓴다.
     */
    public static void gaugeBar(Canvas c, float x, float y, float w, float h, float ratio, String tier, long now) {
        leaf(c, x + h * .2f, y + h * .55f, h * 1.3f, true);
        MenuPanel.box(c, x, y, w, h, h / 2, 0xFF9C6B22, 0xFFE9B54C, Math.max(1.5f, h * .06f));
        MenuPanel.box(c, x + h * .14f, y + h * .14f, w - h * .28f, h - h * .28f, (h - h * .28f) / 2, 0xFFD9A441, 0xFFFDF4E2, 1);
        float pad = h * .22f;
        float iw = (w - pad * 2) * Math.max(0, Math.min(1, ratio)), ih = h - pad * 2;
        if (iw > 1) {
            if ("jackpot".equals(tier)) {
                c.imagePart("hud/gauge_fill_jackpot", x + pad, y + pad, iw, ih, 0.02f, .1f, .02f + .96f * Math.max(.05f, ratio), .9f, 0xFFFFFFFF);
                c.fill(x + pad, y + pad, iw, ih * .45f, ih * .22f, Anim.alpha(0x66FFFFFF, .5f + .5f * (float) Math.sin(now / 160.0)));
            } else {
                c.fill(x + pad, y + pad, Math.max(ih, iw), ih, ih / 2, fillColor(tier));
                c.fill(x + pad + ih * .3f, y + pad + ih * .12f, Math.max(ih, iw) - ih * .6f, ih * .3f, ih * .15f, 0x40FFFFFF);
            }
        }
        leaf(c, x + w - h * .2f, y + h * .55f, h * 1.3f, false);
        // 연잎 + 깨굴이
        float px = x + w + h * 2.1f, py = y + h * 1.35f;
        c.image("hud/lily_pad", px - h * 1.6f, py - h * .56f, h * 3.2f, h * 1.12f, 0xFFFFFFFF);
        float bob = "jackpot".equals(tier) ? Math.abs((float) Math.sin(now / 180.0)) * h * .25f : (float) Math.sin(now / 600.0) * 1.5f;
        c.imageAt(frog(tier), px, py - h * 1.2f - bob, h * 2.6f, 1, 0, 0xFFFFFFFF);
    }

    public void render(Canvas c, long now) {
        hits.clear();
        if (!has || !visible) return;
        c.push();
        c.scale(scale, scale);
        float W = c.width() / scale;

        // ── 왼쪽 위: 남은 다이아 | 보유 다이아 | 코인 | ▼
        float cx = 24, cy = 14, cw = 520, ch = cw * 120 / 1024f;
        c.image("hud/currency_frame", cx, cy, cw, ch, 0xFFFFFFFF);
        float bodyY = cy + ch * .25f, bodyH = ch * .72f, mid = bodyY + bodyH / 2;
        float[][] seg = {{.015f, .30f}, {.30f, .60f}, {.60f, .90f}};
        String[] keys = {"target", "held", "coin"};
        long[] vals = {J.num(data, "debt"), J.num(data, "held"), J.num(data, "coins")};
        for (int i = 0; i < 3; i++) {
            float sx = cx + cw * seg[i][0], sw = cw * (seg[i][1] - seg[i][0]);
            float ix = sx + 26;
            if (i == 0) {
                c.imageFit("hud/diamond", ix, mid, 22, 0xFFFFFFFF);
                c.imageFit("hud/target_reticle", ix, mid, 36, 0xFFFFFFFF);
            } else if (i == 1) c.imageFit("hud/diamond", ix, mid, 28, 0xFFFFFFFF);
            else c.imageFit("hud/frog_coin", ix, mid, 30, 0xFFFFFFFF);
            c.textFit(Fmt.num(vals[i]), ix + 22, mid + 1, 22, sw - 54, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new Hit(keys[i], sx, bodyY, sw, bodyH));
        }
        c.imageFit("hud/expand_arrow", cx + cw * .945f, mid + 1, 18, 0xFFFFFFFF);
        lastSegX = cx + cw + 46;
        lastSegY = mid;

        // ── 그 아래: 상태 아이콘 + 남은 시간
        float bx = cx + 6, by = cy + ch + 8;
        if (J.bool(data, "protected")) bx = status(c, "hud/protection_shield", null, "보호", bx, by, "protection");
        JsonObject fev = J.objOrEmpty(data, "fever");
        long fd = J.num(fev, "diamond"), ft = J.num(fev, "triple"), fb = J.num(fev, "box");
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
        float gw = 330, gh = 24, gx = W / 2 - gw / 2 - 30, gy = 28;
        gaugeBar(c, gx, gy, gw, gh, shownGauge / max, tier, now);
        hits.add(new Hit("gauge", gx - 20, gy - 20, gw + gh * 4 + 20, gh + 40));

        // 남은 다이아 변화 팝업
        pops.removeIf(p -> now - p.start() > 1600);
        for (Pop p : pops) {
            float t = (now - p.start()) / 1600f;
            float a = t < .15f ? t / .15f : 1 - Anim.clamp((t - .6f) / .4f);
            c.text(p.text(), p.x(), p.y() - 26 * Anim.outCubic(t), 30, Anim.alpha(p.color(), a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }
        c.pop();
    }

    /** 아이콘 + 그 아래 시간 글자 (배경 없음) */
    private float status(Canvas c, String icon, String inner, String text, float x, float y, String key) {
        float s = 44;
        float w = Math.max(s, c.textWidth(text, 19, Canvas.BOLD | Canvas.OUTLINE)) + 10;
        float cx = x + w / 2;
        c.imageFit(icon, cx, y + s / 2, s, 0xFFFFFFFF);
        if (inner != null) c.imageFit(inner, cx, y + s / 2, s * .55f, 0xFFFFFFFF);
        c.text(text, cx, y + s + 11, 19, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new Hit(key, x, y, w, s + 20));
        return x + w + 6;
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
