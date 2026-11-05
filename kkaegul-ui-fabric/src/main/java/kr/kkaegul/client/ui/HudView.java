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

    private static String frog(String tier) {
        return switch (tier) {
            case "low" -> "hud/frog_sad";
            case "high" -> "hud/frog_smile";
            case "jackpot" -> "hud/frog_yay";
            default -> "hud/frog_neutral";
        };
    }

    public void render(Canvas c, long now) {
        hits.clear();
        if (!has || !visible) return;
        c.push();
        c.scale(scale, scale);
        float x0 = 26, y0 = 22;

        JsonObject g = J.objOrEmpty(data, "gauge");
        long value = J.num(g, "value"), max = Math.max(1, J.num(g, "max", 100));
        String tier = J.str(g, "tier", "mid");
        // 게이지 숫자는 부드럽게 따라간다
        if (shownGauge < 0) shownGauge = value;
        shownGauge += (value - shownGauge) * 0.15f;
        if (Math.abs(value - shownGauge) < 0.05f) shownGauge = value;

        // 깨굴이 (값이 바뀌면 살짝 통통)
        float bob = (float) Math.sin(now / 520.0) * 2.5f;
        c.imageH(frog(tier), x0, y0 + bob, 118, 0xFFFFFFFF);
        hits.add(new Hit("gauge", x0, y0, 104, 118));

        // 운빨 게이지
        float gx = x0 + 114, gy = y0 + 6, gw = 430, gh = gw * 177 / 1024f;
        float ratio = Math.max(0, Math.min(1, shownGauge / max));
        float fx = gx + gw * .028f, fy = gy + gh * .30f, fw = gw * .944f, fh = gh * .58f;
        c.fill(fx, fy, fw, fh, fh / 2, 0xB0FFF6E4);
        if (ratio > 0) c.imagePart("hud/gauge_fill_jackpot", fx, fy, fw * ratio, fh, 0, 0, ratio, 1, 0xFFFFFFFF);
        if ("jackpot".equals(tier)) c.fill(fx, fy, fw * ratio, fh, fh / 2, Anim.alpha(0x55FFFFFF, .5f + .5f * (float) Math.sin(now / 160.0)));
        c.image("hud/gauge_frame", gx, gy, gw, gh, 0xFFFFFFFF);
        c.text("운빨 " + Math.round(shownGauge) + "§7 / " + max, gx + gw / 2, fy + fh / 2 + 1, 23, Theme.WHITE, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        hits.add(new Hit("gauge", gx, gy, gw, gh));

        // 남은 다이아 / 보유 다이아 / 코인
        float cx = gx, cy = gy + gh - 4, cw = gw, ch = cw * 120 / 1024f;
        c.image("hud/currency_frame", cx, cy, cw, ch, 0xFFFFFFFF);
        float bodyY = cy + ch * .25f, bodyH = ch * .72f, mid = bodyY + bodyH / 2;
        float[][] seg = {{.015f, .30f}, {.30f, .60f}, {.60f, .985f}};
        String[] icons = {"hud/target_reticle", "hud/diamond", "hud/frog_coin"};
        String[] keys = {"target", "held", "coin"};
        long[] vals = {J.num(data, "debt"), J.num(data, "held"), J.num(data, "coins")};
        for (int i = 0; i < 3; i++) {
            float sx = cx + cw * seg[i][0], sw = cw * (seg[i][1] - seg[i][0]);
            if (i == 0) {
                c.imageFit("hud/diamond", sx + 20, mid, 19, 0xFFFFFFFF);
                c.imageFit("hud/target_reticle", sx + 20, mid, 30, 0xFFFFFFFF);
            } else c.imageFit(icons[i], sx + 20, mid, 28, 0xFFFFFFFF);
            c.textFit(Fmt.num(vals[i]), sx + 38, mid, 20, sw - 44, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new Hit(keys[i], sx, bodyY, sw, bodyH));
        }
        c.imageFit("hud/expand_arrow", cx + cw * .935f, mid + 1, 16, 0xC0FFFFFF);
        lastSegX = cx + cw + 56;
        lastSegY = mid;

        // 상태 뱃지
        float bx = cx, by = cy + ch + 8;
        if (J.bool(data, "protected")) bx = badge(c, "hud/protection_shield", "보호 중", 0xFF5BA346, bx, by, "protection");
        JsonObject fev = J.objOrEmpty(data, "fever");
        long fd = J.num(fev, "diamond"), ft = J.num(fev, "triple"), fb = J.num(fev, "box");
        if (ft > 0) bx = badge(c, "fever/badge_triple|fever/lightning", "3배 " + Fmt.clock(ft), 0xFFB04FD0, bx, by, "fever");
        else if (fd > 0) bx = badge(c, "hud/fever", "피버 " + Fmt.clock(fd), 0xFFE0564C, bx, by, "fever");
        if (fb > 0) bx = badge(c, "fever/badge_chest|fever/gift", "상자 " + Fmt.clock(fb), 0xFFD9951A, bx, by, "fever");
        JsonObject jail = J.obj(data, "jail");
        if (jail != null) bx = badge(c, "hud/prison_bars", "운동 " + J.num(jail, "done") + "/" + J.num(jail, "required"), 0xFF6F6F78, bx, by, "jail");
        long mail = J.num(data, "mail");
        if (mail > 0) badge(c, "invite/envelope_closed", "우편 " + mail, 0xFF3C8FD6, bx, by, "mail");

        // 남은 다이아 변화 팝업
        pops.removeIf(p -> now - p.start() > 1600);
        for (Pop p : pops) {
            float t = (now - p.start()) / 1600f;
            float a = t < .15f ? t / .15f : 1 - Anim.clamp((t - .6f) / .4f);
            c.text(p.text(), p.x(), p.y() - 26 * Anim.outCubic(t), 30, Anim.alpha(p.color(), a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }
        c.pop();
    }

    private float badge(Canvas c, String icon, String text, int accent, float x, float y, String key) {
        float h = 36, tw = c.textWidth(text, 18, Canvas.BOLD), w = 48 + tw + 14;
        c.fill(x, y, w, h, h / 2, accent);
        c.fill(x + 2, y + 2, w - 4, h - 4, h / 2 - 2, 0xF2FFF8EA);
        String[] ic = icon.split("\\|");
        c.imageFit(ic[0], x + 22, y + h / 2, 28, 0xFFFFFFFF);
        if (ic.length > 1) c.imageFit(ic[1], x + 22, y + h / 2, 16, 0xFFFFFFFF);
        c.text(text, x + 40, y + h / 2, 18, accent, Canvas.LEFT, Canvas.BOLD);
        hits.add(new Hit(key, x, y, w, h));
        return x + w + 8;
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
