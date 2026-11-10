package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/**
 * 전체 이벤트 배너 (type "banner" {text, color:gold|blue|purple}, 받은 묶음 5).
 * 배너 900×150 (2배 그림), 화면 위 가운데 게이지 아래로 내려왔다가 올라감. 하나씩 차례로.
 * 판 안쪽 세로 가운데 81.5 (그림에서 잼), 느낌표 아이콘 가운데 (112,82) 60 (v3 왼쪽 다이아·덩굴 장식을 덮지 않게 안쪽으로), 글자 x 145~795.
 */
public final class BannerView {
    private static final long LIFE = 4200, IN = 380, OUT = 380;
    private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
    private JsonObject cur;
    private long at;

    public void add(JsonObject d, long now) {
        queue.addLast(d);
        if (queue.size() > 4) queue.pollFirst();
        if (cur == null) next(now);
    }

    private void next(long now) { cur = queue.pollFirst(); at = now; }

    public void render(Canvas c, long now) {
        if (cur == null) return;
        long e = now - at;
        long life = queue.isEmpty() ? LIFE : 2600;     // 밀려 있으면 빨리 넘김
        if (e > life) { next(now); if (cur == null) return; e = 0; }
        float W = c.width(), H = c.height();
        float k = Math.min(W / 1920f, H / 1080f) * .62f;
        float slide = e < IN ? 1 - Anim.outBack(e / (float) IN) : e > life - OUT ? Anim.inCubic((e - (life - OUT)) / (float) OUT) : 0;
        float a = e < IN ? Anim.clamp(e / 200f) : e > life - OUT ? Anim.clamp((life - e) / (float) OUT) : 1;
        float bw = 900 * k, bh = 150 * k;
        float x = W / 2 + HudView.GAUGE_DX - bw / 2, y = H * .105f - slide * (bh + H * .1f);   // 게이지 막대와 같은 가운데
        String color = J.str(cur, "color", "blue");
        if (!color.equals("gold") && !color.equals("purple")) color = "blue";
        c.push();
        c.translate(x, y);
        c.scale(k, k);
        c.image("banner/" + color, 0, 0, 900, 150, Anim.white(a));
        float pulse = 1 + .08f * (float) Math.max(0, Math.sin(Math.min(1, e / 600f) * Math.PI));
        c.push();
        c.translate(112, 82);
        c.scale(pulse, pulse);
        c.image("banner/icon", -30, -30, 60, 60, Anim.white(a));
        c.pop();
        String sub = J.str(cur, "sub");
        int ink = 0xFF4A2D16;
        if (sub.isEmpty()) c.textFit(J.str(cur, "text"), 145, 81.5f, 44, 650, Anim.alpha(ink, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        else {
            c.textFit(J.str(cur, "text"), 145, 61, 38, 650, Anim.alpha(ink, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(sub, 145, 100, 26, 650, Anim.alpha(0xFF7A6A58, a), Canvas.LEFT, Canvas.LIGHT);
        }
        c.pop();
    }
}
