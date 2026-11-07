package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 놀리는 한마디 (서버 type "taunt" {text, sub, ms}): 화면 가운데 위쪽에 크게 통통 튀며 나타났다 사라짐.
 * 예) "꽝이쥬~?", "와 개똥손ㅋ", "못돌아가쥬~"
 */
public final class TauntView {
    private String text = "", sub = "";
    private long at, life;

    public void show(JsonObject m, long now) {
        text = J.str(m, "text");
        sub = J.str(m, "sub");
        life = Math.max(1600, J.num(m, "ms", 2600));
        at = now;
    }

    public boolean busy(long now) { return !text.isEmpty() && now - at < life; }

    public void render(Canvas c, long now) {
        if (text.isEmpty()) return;
        long e = now - at;
        if (e > life) { text = ""; return; }
        float W = c.width(), H = c.height(), k = Math.min(W / 1920f, H / 1080f);
        float in = Anim.outBack(Anim.t(now, at, 320)), out = e > life - 300 ? (life - e) / 300f : 1;
        float a = Anim.clamp(Math.min(Anim.t(now, at, 140), out));
        float wob = (float) Math.sin(e / 90.0) * Math.max(0, 1 - e / 900f) * 6;   // 처음에 살짝 흔들림
        c.push();
        c.translate(W / 2, H * 0.36f);
        c.scale(k, k);
        float s = 0.6f + 0.4f * in;
        c.scale(s, s);
        c.rotate(wob * 0.5f);
        c.textFit(text, 0, 0, 72, 1500, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        if (!sub.isEmpty()) c.textFit(sub, 0, 70, 28, 1200, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        c.pop();
    }
}
