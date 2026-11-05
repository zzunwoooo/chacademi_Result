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

    /** 본체별 안쪽 칸 위치 (그림 비율 0~100). {윗판 x0,y0,x1,y1}, 릴 y0,y1, 릴 4개 x 구간, {아랫판 x0,y0,x1,y1} */
    private record Frame(float[] plate, float reelY0, float reelY1, float[][] reels, float[] bottom) {}

    private static Frame frame(String body) {
        return switch (body) {
            case "body_decrease" -> new Frame(new float[]{19, 6, 81, 20}, 30, 82, new float[][]{{7, 27}, {29, 49}, {51, 71}, {73, 93}}, new float[]{31, 87, 71, 99});
            case "body_big_increase" -> new Frame(new float[]{23, 2, 77, 16}, 21, 86, new float[][]{{6, 27}, {28, 49}, {50, 71}, {72, 94}}, new float[]{34, 88, 66, 99});
            case "body_big_decrease" -> new Frame(new float[]{18, 1, 82, 17}, 23, 81, new float[][]{{7, 27}, {29, 49}, {51, 71}, {73, 94}}, new float[]{31, 87, 71, 99});
            default -> new Frame(new float[]{23, 1, 78, 17}, 28, 82, new float[][]{{7, 27}, {29, 49}, {51, 71}, {73, 94}}, new float[]{34, 87, 67, 100});
        };
    }

    public void add(JsonObject d, long now) {
        queue.addLast(new Spin(d, 0, Math.max(600, J.num(d, "spinMs", 3000))));
        if (cur == null) next(now);
    }

    private void next(long now) {
        Spin s = queue.pollFirst();
        // 밀리면 빨리 돌린다
        cur = s == null ? null : new Spin(s.d(), now, queue.size() >= 2 ? Math.min(s.spinMs(), 1200) : s.spinMs());
        stopMask = 0;
    }

    public boolean active() { return cur != null; }

    public void render(Canvas c, long now, Host host) {
        if (cur == null) return;
        long e = now - cur.start();
        long life = cur.spinMs() + HOLD;
        if (e > life) { next(now); if (cur == null) return; e = 0; }
        JsonObject d = cur.d();
        boolean inc = J.bool(d, "increase");
        String kind = J.str(d, "kind");
        boolean big = kind.contains("big");
        String body = "body_" + (big ? "big_" : "") + (inc ? "increase" : "decrease");
        Frame f = frame(body);
        float W = c.width(), H = c.height();
        float w = Math.min(700, W - 260);
        int[] sz = c.size("roulette/" + body);
        float h = sz == null ? w * .53f : w * sz[1] / sz[0];
        float x = (W - w) / 2, y = H * .44f - h / 2;
        float a = Anim.fade(now, cur.start(), life, 160, 420);
        float drop = (1 - Anim.outBack(Anim.t(now, cur.start(), 380))) * -40;
        int tint = Anim.white(a);

        c.push();
        c.translate(0, drop);
        if (J.bool(d, "fever")) {
            // 피버 중: 위에 빨간 알약 표시만 (테두리 없음)
            String ft = J.num(d, "extra") >= 2 ? "3배 피버! 1+2" : "다이아 피버! 1+1";
            float tw = c.textWidth(ft, 26, Canvas.BOLD) + 50, pulse = 1 + .04f * (float) Math.sin(now / 90.0);
            c.push(); c.translate(x + w / 2, y - 34); c.scale(pulse, pulse);
            MenuPanel.box(c, -tw / 2, -22, tw, 44, 22, Anim.alpha(0xFFFFF4E6, a), Anim.alpha(0xFFE95A50, a), 3);
            c.text(ft, 0, 0, 26, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD);
            c.pop();
        }
        c.image("roulette/" + body, x, y, w, h, tint);

        // 윗판: 룰렛 이름
        float px = x + w * f.plate()[0] / 100, py = y + h * f.plate()[1] / 100, pw = w * (f.plate()[2] - f.plate()[0]) / 100, ph = h * (f.plate()[3] - f.plate()[1]) / 100;
        c.textFit(J.str(d, "label", inc ? "증가 룰렛" : "감소 룰렛"), px + pw / 2, py + ph / 2 + 1, 26, pw * .9f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);

        // 릴 4칸
        long total = Math.min(9999, Math.abs(J.num(d, "total")));
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
                // 1+1 / 1+2 계산식은 본체 아래에
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rolls.length; i++) sb.append(i == 0 ? "" : " + ").append(Fmt.num(rolls[i]));
                c.text(sb + " = " + res, x + w / 2, y + h + 30, 26, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            }
            long extra = J.num(d, "extra");
            if (extra > 0) {
                String b = extra >= 2 ? "roulette/badge_fancy" : "roulette/badge_basic";
                float bs = 120 * Anim.outBack(rt);
                c.imageAt(b, x + w - 10, y + 6, bs, 1, -12, tint);
                c.text("1+" + extra, x + w - 10, y + 8, 30 * Anim.outBack(rt), Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            }
        }

        // 깨굴이 + 레버
        String frog = "roulette/frog_" + (big ? "big_" : "") + (inc ? "increase" : "decrease");
        float fb = (float) Math.sin(now / 140.0) * (allStopped ? 4 : 1.5f);
        c.imageFit(frog, x - 30, y + h * .72f + fb, 190, tint);
        float pull = e < 420 ? (float) Math.sin(Anim.clamp(e / 420f) * Math.PI) : 0;
        c.push();
        c.translate(x + w + 34, y + h * .78f);
        c.scale(1, 1 - .35f * pull);
        c.imageH("roulette/lever", -28, -h * .62f, h * .62f, tint);
        c.pop();
        c.pop();
    }
}
