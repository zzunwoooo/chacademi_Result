package kr.kkaegul.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/** 화면 위쪽 가운데 멘트 배너 + 아래쪽 토스트 */
public final class NoticeView {
    private record Notice(String text, String sub, String kind, long start, long life) {}
    private record Toast(String text, long start) {}

    private Notice cur;
    private final Deque<Toast> toasts = new ArrayDeque<>();

    public void center(String text, String sub, long ms, long now) {
        String k = kind(text + " " + sub);
        long life = Math.max(1200, ms) + 300;
        // 같은 멘트가 연달아 오면 시간만 연장
        if (cur != null && cur.text().equals(text) && cur.sub().equals(sub) && now - cur.start() < cur.life())
            cur = new Notice(text, sub, k, cur.start(), now - cur.start() + life);
        else cur = new Notice(text, sub, k, now, life);
    }

    public void toast(String text, long now) {
        if (!toasts.isEmpty() && toasts.peekLast().text().equals(text) && now - toasts.peekLast().start() < 2000) {
            toasts.pollLast();
        }
        toasts.addLast(new Toast(text, now));
        while (toasts.size() > 3) toasts.pollFirst();
    }

    /** 색코드/단어로 배너 종류 고르기 */
    static String kind(String s) {
        String plain = Fmt.strip(s);
        if (plain.contains("잭팟") || plain.contains("대박") || plain.contains("JACKPOT") || plain.contains("졸업")) return "jackpot";
        String a = Fmt.amp(s);
        int i = a.indexOf('§');
        char code = 0;
        while (i >= 0 && i + 1 < a.length()) {
            char k = Character.toLowerCase(a.charAt(i + 1));
            if ("0123456789abcdef".indexOf(k) >= 0) { code = k; break; }
            i = a.indexOf('§', i + 2);
        }
        if (code == 'c' || code == '4') return "failure";
        if (code == 'a' || code == '2') return "success";
        return "neutral";
    }

    public void render(Canvas c, long now) {
        float W = c.width(), H = c.height();
        if (cur != null) {
            long e = now - cur.start();
            if (e > cur.life()) cur = null;
            else {
                float a = Anim.fade(now, cur.start(), cur.life(), 120, 350);
                float s = e < 260 ? .82f + .18f * Anim.outBack(e / 260f) : 1;
                float w = Math.min(860, W - 80), h = w * 236 / 1024f;
                float cy = H * .27f;
                c.push();
                c.translate(W / 2, cy);
                c.scale(s, s);
                c.image("notice/banner_" + cur.kind(), -w / 2, -h / 2, w, h, Anim.white(a));
                float body = -h / 2 + h * .30f, bodyH = h * .66f;
                boolean sub = !cur.sub().isBlank();
                float ty = body + bodyH / 2 - (sub ? 15 : 0);
                c.textFit(cur.text(), 0, ty, 38, w * .84f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
                if (sub) c.textFit(cur.sub(), 0, ty + 38, 23, w * .84f, Anim.alpha(Theme.MUTED, a), Canvas.CENTER, Canvas.LIGHT);
                c.pop();
            }
        }
        // 토스트: 아래쪽, 최근 것이 맨 아래
        toasts.removeIf(t -> now - t.start() > 2800);
        float y = H - 230;
        Toast[] arr = toasts.toArray(new Toast[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            Toast t = arr[i];
            float a = Anim.fade(now, t.start(), 2800, 140, 450);
            float rise = 14 * (1 - Anim.outCubic((now - t.start()) / 220f));
            float w = Math.min(600, W - 60), h = w * 160 / 1024f;
            float x = (W - w) / 2, ty = y + rise;
            c.image("notice/toast_cream", x, ty - h / 2, w, h, Anim.white(a));
            c.imageFit("notice/face_neutral", x + w * .12f, ty, h * .52f, Anim.white(a));
            c.textFit(t.text(), x + w * .2f, ty + 1, 22, w * .7f, Anim.alpha(Theme.INK, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            y -= h * .78f;
        }
    }
}
