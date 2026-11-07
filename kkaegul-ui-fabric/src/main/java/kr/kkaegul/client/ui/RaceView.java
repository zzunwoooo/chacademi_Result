package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/** 경주 중 화면 오른쪽 위 작은 레인 (창을 안 열어도 보임) + 결과 트로피 */
public final class RaceView {
    private JsonObject hud;
    private long hudAt;
    private JsonObject result;
    private long resultAt;

    public void hud(JsonObject d, long now) {
        hud = d;
        hudAt = now;
        String st = J.str(d, "state");
        if (!st.equals("racing")) hud = null;   // 결승선 들어오면 오른쪽 위 레인은 바로 끔
    }

    public void result(JsonObject d, long now) { result = d; resultAt = now; }

    public void render(Canvas c, long now, boolean panelOpen) {
        if (panelOpen) return;
        float W = c.width(), H = c.height();
        if (hud != null && now - hudAt < 15000) {
            int n = Math.max(1, Math.min(6, J.objs(hud, "frogs").size()));
            float k = Math.min(W * .36f / 1460, H * .36f / (112 * n + 20));
            float x = W - 1460 * k - 14, y = 14;
            c.push();
            c.translate(x, y);
            c.scale(k, k);
            MenuPanel.box(c, 0, 0, 1460, 112 * n + 20, 40, 0xCC6B4A2B, 0xE6FBF3E6, 4);
            c.translate(-70, -275);
            RacePanel.track(c, hud, now, 0, "", false);
            c.pop();
        }
        if (result != null) {
            long e = now - resultAt;
            if (e > 9000) { result = null; return; }
            float k = Math.min(W / 1600f, H / 1010f) * .8f;
            c.push();
            c.translate(W / 2 - 800 * k, H / 2 - 505 * k);
            c.scale(k, k);
            RacePanel.resultOverlay(c, result, e, 800);
            c.pop();
        }
    }
}
