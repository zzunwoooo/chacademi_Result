package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 다이아 증감 룰렛 (숫자 4칸 슬롯머신). 서버가 결과를 정해서 보내고(rolls, total), spinMs 동안 돌다가 왼쪽부터 멈춘다.
 * 장부 반영은 서버가 spinMs 뒤에 한다.
 */
public final class RouletteView {
    private record Spin(JsonObject d, long start, long spinMs) {}
    private final Deque<Spin> queue = new ArrayDeque<>();
    private Spin cur;
    private int stopMask;
    private static final long HOLD = 2300;

    /** 본체별 안쪽 칸 위치 (그림 비율 0~100, 릴 칸은 그림에서 잰 크림 칸 바깥 테두리). {윗판 x0,y0,x1,y1}, 릴 y0,y1, 릴 4개 x 구간, {아랫판 x0,y0,x1,y1} */
    private record Frame(float[] plate, float reelY0, float reelY1, float[][] reels, float[] bottom) {}

    private static Frame frame(String body) {
        return switch (body) {
            case "body_decrease" -> new Frame(new float[]{19, 6, 81, 20}, 27.2f, 83.1f, new float[][]{{6.2f, 27.1f}, {28.5f, 49.3f}, {50.7f, 71.5f}, {72.9f, 93.8f}}, new float[]{31, 87, 71, 99});
            case "body_big_increase" -> new Frame(new float[]{23, 2, 77, 16}, 19.5f, 83.1f, new float[][]{{6.2f, 27.1f}, {28.5f, 49.2f}, {50.8f, 71.5f}, {72.9f, 93.8f}}, new float[]{34, 88, 66, 99});
            case "body_big_decrease" -> new Frame(new float[]{18, 1, 82, 17}, 22.7f, 81.7f, new float[][]{{6.7f, 27.1f}, {28.9f, 49.1f}, {50.9f, 71.2f}, {72.9f, 93.3f}}, new float[]{31, 87, 71, 99});
            default -> new Frame(new float[]{23, 1, 78, 17}, 25.5f, 82.9f, new float[][]{{6.4f, 27.2f}, {28.6f, 49.3f}, {50.7f, 71.4f}, {72.7f, 93.6f}}, new float[]{34, 87, 67, 100});
        };
    }

    /** 다른 큰 연출(퀴즈 카드·결과 개구리·괴롭히기 룰렛 등)이 떠 있으면 true → 끝날 때까지 기다렸다가 돌림 */
    public java.util.function.LongPredicate blocked = t -> false;

    public void add(JsonObject d, long now) {
        queue.addLast(new Spin(d, 0, Math.max(600, J.num(d, "spinMs", 3000))));
        if (cur == null && !blocked.test(now)) next(now);
    }

    private void next(long now) {
        Spin s = queue.pollFirst();
        // 밀리면 빨리 돌린다
        cur = s == null ? null : new Spin(s.d(), now, queue.size() >= 2 ? Math.min(s.spinMs(), 1200) : s.spinMs());
        stopMask = 0;
    }

    public boolean active() { return cur != null; }

    public void render(Canvas c, long now, Host host) {
        if (cur == null && !queue.isEmpty() && !blocked.test(now)) next(now);
        if (cur == null) return;
        long e = now - cur.start();
        long life = cur.spinMs() + HOLD;
        if (e > life) { cur = null; if (queue.isEmpty() || blocked.test(now)) return; next(now); e = 0; }
        JsonObject d = cur.d();
        boolean inc = J.bool(d, "increase");
        String kind = J.str(d, "kind");
        boolean big = kind.contains("big");
        String body = "body_" + (big ? "big_" : "") + (inc ? "increase" : "decrease");
        Frame f = frame(body);
        float W = c.width(), H = c.height();
        float w = Math.min(700, W - 260);
        float h = w * GachaPanel.bodyRatio(c, "roulette/" + body, .53f);
        float x = (W - w) / 2, y = H * .44f - h / 2;
        float a = Anim.fade(now, cur.start(), life, 160, 420);
        float drop = (1 - Anim.outBack(Anim.t(now, cur.start(), 380))) * -40;
        int tint = Anim.white(a);

        c.push();
        c.translate(0, drop);
        if (J.bool(d, "fever")) {
            // 피버 중: 위에 불꽃 금테(roulette/fever_frame) 안 빨간 알약 표시 (본체 테두리 없음)
            // 피버 = 범위 배수(2배/3배), 1+1·1+2 는 운빨 게이지 (오른쪽 위 뱃지)
            String ft = J.num(d, "mult", 2) >= 3 ? "3배 피버! 범위 x3" : "2배 피버! 범위 x2";
            float tw = c.textWidth(ft, 26, Canvas.BOLD) + 110, th = 58, pulse = 1 + .04f * (float) Math.sin(now / 90.0);
            float fk = th / 200f;   // 그림 세로 200px → th
            c.push(); c.translate(x + w / 2, y - 34); c.scale(pulse, pulse);
            c.fill(-tw / 2 + 1024 * .05f * fk, -th / 2 + th * .1f, tw - 1024 * .1f * fk, th * .8f, th * .4f, Anim.alpha(0xFFE95A50, a));
            c.imageNine("roulette/fever_frame", -tw / 2, -th / 2, tw, th, .13f, .45f, 1024 * .13f * fk, th * .45f, tint);
            c.text(ft, 0, 0, 26, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD);
            c.pop();
        }
        GachaPanel.art(c, "roulette/" + body, x, y, w, h, tint);
        // 릴 칸 바탕 (roulette/reel_background 를 칸 크기에 9조각으로)
        for (int i = 0; i < 4; i++) {
            float rx = x + w * f.reels()[i][0] / 100, rw = w * (f.reels()[i][1] - f.reels()[i][0]) / 100;
            float ry = y + h * f.reelY0() / 100, rh = h * (f.reelY1() - f.reelY0()) / 100;
            c.imageNine("roulette/reel_background", rx - 1.5f, ry - 1.5f, rw + 3, rh + 3, .2f, .1f, rw * .2f, rw * .214f, tint);
        }

        // 윗판: 룰렛 이름
        float px = x + w * f.plate()[0] / 100, py = y + h * f.plate()[1] / 100, pw = w * (f.plate()[2] - f.plate()[0]) / 100, ph = h * (f.plate()[3] - f.plate()[1]) / 100;
        c.textFit(J.str(d, "label", inc ? "증가 룰렛" : "감소 룰렛"), px + pw / 2, py + ph / 2 + 1, 26, pw * .9f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 릴 4칸
        // 4칸을 넘는 합계(피버 1+2 등)는 릴에 첫 칸 값을 보여 주고, 합계는 아래 결과판·계산식에 그대로
        long total = Math.abs(J.num(d, "total"));
        if (total > 9999) { long[] rs = J.longs(d, "rolls"); total = Math.min(9999, rs.length > 0 ? Math.abs(rs[0]) : 9999); }
        String digits = String.format("%4d", total);
        int firstDigit = 4 - String.valueOf(total).length();
        boolean allStopped = true;
        for (int i = 0; i < 4; i++) {
            float rx = x + w * f.reels()[i][0] / 100, rw = w * (f.reels()[i][1] - f.reels()[i][0]) / 100;
            float ry = y + h * f.reelY0() / 100, rh = h * (f.reelY1() - f.reelY0()) / 100;
            long stopAt = (long) (cur.spinMs() * (.42f + .58f * (i + 1) / 4f));
            int target = digits.charAt(i) == ' ' ? 0 : digits.charAt(i) - '0';
            boolean dim = i < firstDigit;
            float size = rh * .62f;
            float cxr = rx + rw / 2, cyr = ry + rh / 2;
            int ink = inc ? 0xFFD9493F : 0xFF3F8F32;
            if (e < stopAt) {
                allStopped = false;
                // 흘러가는 숫자는 크림 칸 안에서만 보이게 (칸 밖으로 삐져나오던 것)
                c.clip(rx + 3, ry + 3, rw - 6, rh - 6);
                // 도는 중: 숫자가 위로 흘러간다 (칸 밖은 흐리게)
                float speed = 0.022f * (1 - .5f * Anim.clamp(e / (float) stopAt));
                float pos = e * speed + i * 3.1f;
                int dgt = (int) Math.floor(pos) % 10;
                float frac = pos - (float) Math.floor(pos);
                for (int k = -1; k <= 1; k++) {
                    float oy = (k + frac - .5f) * rh * .45f;
                    if (Math.abs(oy) > rh * .27f) continue;
                    float vis = 1 - Math.min(1, Math.abs(oy) / (rh * .3f));
                    if (vis <= 0) continue;
                    c.text(String.valueOf(((dgt - k) % 10 + 10) % 10), cxr, cyr + oy, size, Anim.alpha(0xFF6B5A4C, a * vis * .9f), Canvas.CENTER, Canvas.BOLD);
                }
                c.unclip();
            } else {
                float st = Anim.t(now, cur.start() + stopAt, 260);
                float oy = (1 - Anim.outBack(st)) * -rh * .25f;
                if ((stopMask & (1 << i)) == 0) { stopMask |= 1 << i; host.sound("minecraft:block.note_block.hat", 1.2f + i * .1f, .5f); }
                c.text(String.valueOf(target), cxr, cyr + oy, size, Anim.alpha(dim ? 0x26000000 | (ink & 0xFFFFFF) : ink, a), Canvas.CENTER, Canvas.BOLD);
            }
        }

        // 다 멈추면: 결과
        if (allStopped) {
            float rt = Anim.t(now, cur.start() + cur.spinMs(), 300);
            float bx = x + w * f.bottom()[0] / 100, byy = y + h * f.bottom()[1] / 100, bw = w * (f.bottom()[2] - f.bottom()[0]) / 100, bh = h * (f.bottom()[3] - f.bottom()[1]) / 100;
            long[] rolls = J.longs(d, "rolls");
            String res = (inc ? "+" : "-") + Fmt.num(Math.abs(J.num(d, "total")));
            c.textFit(res, bx + bw / 2, byy + bh / 2 + 1, 26 * (.8f + .2f * Anim.outBack(rt)), bw * .86f, Anim.alpha(inc ? 0xFFD9493F : 0xFF3F8F32, a), Canvas.CENTER, Canvas.BOLD);
            if (rolls.length > 1) {
                // 1+1 / 1+2 계산식은 본체 아래 빛나는 결과 띠(roulette/result_highlight) 위에
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rolls.length; i++) sb.append(i == 0 ? "" : " + ").append(Fmt.num(rolls[i]));
                String eq = sb + " = " + res;
                float hw = Math.max(260, c.textWidth(eq, 26, Canvas.BOLD) + 90), hh = 46;
                c.imageNine("roulette/result_highlight", x + w / 2 - hw / 2 * (.7f + .3f * rt), y + h + 30 - hh / 2, hw * (.7f + .3f * rt), hh, .06f, .45f, hh * .7f, hh * .45f, Anim.white(a * rt));
                c.text(eq, x + w / 2, y + h + 30, 26, Anim.alpha(inc ? 0xFFD9493F : 0xFF3F8F32, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            }
            long extra = J.num(d, "extra");
            if (extra > 0) {
                String b = extra >= 2 ? "roulette/badge_fancy" : "roulette/badge_basic";
                float bs = 120 * Anim.outBack(rt);
                // 본체 오른쪽 위 장식(클로버·보석)을 덮지 않게 바깥 모서리에
                float bx0 = x + w + 44, by0 = y - 28;
                c.imageAt(b, bx0, by0, bs, 1, -12, tint);
                c.text("1+" + extra, bx0, by0, 30 * Anim.outBack(rt), Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            }
        }

        // 깨굴이 + 레버
        String frog = "roulette/frog_" + (big ? "big_" : "") + (inc ? "increase" : "decrease");
        float fb = (float) Math.sin(now / 140.0) * (allStopped ? 4 : 1.5f);
        // 깨굴이는 본체 왼쪽 밖에 세움 (본체 왼쪽 아래 장식과 첫 칸을 덮지 않게), 발은 본체 바닥선에
        int[] fsz = c.size(frog);
        float fk = fsz == null ? 1 : 190f / Math.max(fsz[0], fsz[1]), fw = fsz == null ? 190 : fsz[0] * fk, fh = fsz == null ? 190 : fsz[1] * fk;
        c.image(frog, x + 14 - fw, y + h * .99f - fh + fb, fw, fh, tint);
        float pull = e < 420 ? (float) Math.sin(Anim.clamp(e / 420f) * Math.PI) : 0;
        c.push();
        c.translate(x + w + 34, y + h * .78f);
        c.scale(1, 1 - .35f * pull);
        c.imageH("roulette/lever", -28, -h * .62f, h * .62f, tint);
        c.pop();
        c.pop();
    }
}
