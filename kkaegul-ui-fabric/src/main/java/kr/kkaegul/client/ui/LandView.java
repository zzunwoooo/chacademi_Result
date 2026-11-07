package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 땅 (서버 메시지 type "land"). 월드 경계 벽은 모드 쪽 LandWorld 가 같은 상태로 그린다.
 * <ul>
 *   <li>땅문서를 든 동안: 화면 위 가운데(게이지 바로 아래) 작은 상태 이름표 — 소유 가능(연두) / 주인(하늘) / 제한(빨강) / 내 땅(노랑).</li>
 *   <li>내 땅 안: 오른쪽 위 작은 땅 카드. 누르면 관리 창 (land.open).</li>
 * </ul>
 * 이름표·카드 그림은 묶음 6(land_ui) 이 오면 바꿔 끼울 자리 — 지금은 코드로 그린 임시 판.
 */
public final class LandView {
    public volatile boolean holding;
    public volatile String status = "", label = "";
    public volatile int cx, cz, radius;
    public volatile String[] cells = new String[0];
    /** 내 땅 안이면 카드 정보, 아니면 null */
    public volatile JsonObject mine;
    private long changedAt, mineAt;
    private float cardX, cardY, cardW, cardH, hover;

    public void set(JsonObject m, long now) {
        boolean h = J.bool(m, "holding");
        String st = J.str(m, "status");
        if (h != holding || !st.equals(status)) changedAt = now;
        holding = h;
        status = st;
        label = J.str(m, "label");
        cx = (int) J.num(m, "cx");
        cz = (int) J.num(m, "cz");
        radius = (int) J.num(m, "radius", 1);
        JsonArray a = m.has("cells") && m.get("cells").isJsonArray() ? m.getAsJsonArray("cells") : new JsonArray();
        String[] c = new String[a.size()];
        for (int i = 0; i < c.length; i++) c[i] = a.get(i).getAsString();
        cells = c;
        JsonObject mi = J.obj(m, "mine");
        if ((mi == null) != (mine == null)) mineAt = now;
        mine = mi;
    }

    public void reset() { holding = false; mine = null; cells = new String[0]; }

    /** 상태 → 색 (RGB) */
    public static int rgb(String st) {
        return switch (st) {
            case "free" -> 0x7BE08E;
            case "mine" -> 0xF7D35C;
            case "trusted" -> 0x9AD7FF;
            case "owned" -> 0x8CC8FF;
            case "blocked" -> 0xF26B6B;
            default -> 0xFFFFFF;
        };
    }

    /** 이 칸 (dx,dz: -r..r) 상태 */
    public String cell(int dx, int dz) {
        int r = radius, side = 2 * r + 1, i = (dz + r) * side + (dx + r);
        String[] c = cells;
        return i >= 0 && i < c.length ? c[i] : "";
    }

    public void render(Canvas c, long now, float scale, boolean cursor, float mx, float my) {
        c.push();
        c.scale(scale, scale);
        float W = c.width() / scale;
        if (holding && !label.isEmpty()) {
            float t = Anim.outCubic(Anim.t(now, changedAt, 180));
            int col = rgb(status);
            float tw = c.textWidth(label, 21, Canvas.BOLD);
            float w = Math.max(200, tw + 70), h = 40, x = W / 2 - 30 - w / 2, y = 98 - (1 - t) * 6;
            c.fill(x, y, w, h, h / 2, Anim.alpha(0xE6FFF8EC, t));
            c.fill(x + 2, y + 2, w - 4, h - 4, h / 2 - 2, Anim.alpha(0xFF000000 | col, t * .28f));
            c.fill(x + 12, y + h / 2 - 8, 16, 16, 8, Anim.alpha(0xFF000000 | col, t));
            c.textFit(label, x + 38, y + h / 2, 21, w - 50, Anim.alpha(0xFF4A2D16, t), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        }
        JsonObject mi = mine;
        cardW = 0;
        if (mi != null) {
            float t = Anim.outBack(Anim.t(now, mineAt, 260));
            float w = 300, h = 96, x = W - w - 16 + (1 - t) * 40, y = 16;
            boolean over = cursor && Anim.in(mx / scale, my / scale, x, y, w, h);
            hover += ((over ? 1 : 0) - hover) * .3f;
            float a = Anim.clamp(t);
            c.fill(x, y, w, h, 22, Anim.alpha(0xFF6B4A2B, a));
            c.fill(x + 3, y + 3, w - 6, h - 6, 19, Anim.alpha(hover > .5f ? 0xFFFFF4DE : 0xFFFBEBD0, a));
            c.imageFit("dropdown/icon_lobby", x + 50, y + h / 2 - 2 * hover, 64 + 4 * hover, Anim.white(a));
            c.textFit("내 땅", x + 92, y + 34, 26, w - 104, Anim.alpha(0xFF4A2D16, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit("눌러서 관리 · " + J.num(mi, "count") + "칸", x + 92, y + 66, 18, w - 104, Anim.alpha(0xFF8A6A4A, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            cardX = x; cardY = y; cardW = w; cardH = h;
        }
        c.pop();
    }

    /** 카드를 눌렀는지 (가상 좌표, scale = HUD 배율) */
    public boolean hit(float mx, float my, float scale) {
        if (mine == null || cardW <= 0) return false;
        return Anim.in(mx / scale, my / scale, cardX, cardY, cardW, cardH);
    }
}
