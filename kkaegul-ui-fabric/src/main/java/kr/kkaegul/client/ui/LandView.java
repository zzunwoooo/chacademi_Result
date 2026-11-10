package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 땅 (서버 메시지 type "land"). 월드 경계 벽은 모드 쪽 LandWorld 가 같은 상태로 그린다.
 * <ul>
 *   <li>땅문서를 든 동안: 화면 위 가운데(게이지 바로 아래) 작은 상태 이름표 — 소유 가능(연두) / 주인(하늘) / 제한(빨강) / 내 땅(노랑).</li>
 *   <li>내 땅 안: 오른쪽 위 작은 땅 카드. 누르면 관리 창 (land.open).</li>
 * </ul>
 * 그림은 기존 알림 카드(notice/toast_cream)와 드롭다운 아이콘(깨굴이 집)을 재활용.
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
        if (h != holding || !st.equals(status) || !J.str(m, "label").equals(label)) changedAt = now;
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
        if (!label.isEmpty()) {   // 들고 있을 때 + 남의 땅에 들어왔을 때
            // 상태 이름표: 알림 카드(toast_cream) 재활용 + 상태 색 점
            float t = Anim.outCubic(Anim.t(now, changedAt, 180));
            float tw = c.textWidth(label, 20, Canvas.BOLD);
            float h = 50, k = h / 160f, w = Math.max(240, tw + 291 * k + 24), x = W / 2 - 30 - w / 2, y = 96 - (1 - t) * 6;
            pill(c, x, y, w, h, Anim.white(t));
            // 상태 점은 왼쪽 동그라미 가운데, 글자는 오른쪽 글자 판 안
            c.fill(x + 136.5f * k - 7, y + 79.5f * k - 7, 14, 14, 7, Anim.alpha(0xFF000000 | rgb(status), t));
            c.textFit(label, x + 198 * k + 8, y + h / 2, 20, w - 198 * k - 93 * k - 16, Anim.alpha(0xFF4A2D16, t), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        }
        JsonObject mi = mine;
        cardW = 0;
        if (mi != null) {
            // 내 땅 카드: 알림 카드 + 깨굴이 집 아이콘 재활용
            float t = Anim.outBack(Anim.t(now, mineAt, 260));
            float w = 340, h = 64, x = W - w - 14 + (1 - t) * 40, y = 14;
            boolean over = cursor && Anim.in(mx / scale, my / scale, x, y, w, h);
            hover += ((over ? 1 : 0) - hover) * .3f;
            float a = Anim.clamp(t);
            float k = h / 160f, dy = over ? -2 : 0;
            pill(c, x, y + dy, w, h, Anim.white(a));
            // 집 아이콘은 왼쪽 동그라미(안지름 96) 가운데, 글자는 글자 판(x198~931) 안
            c.imageFit("dropdown/icon_lobby", x + 136.5f * k, y + dy + 79.5f * k - 2 * hover, 96 * k * (1 + .06f * hover), Anim.white(a));
            float tx = x + 198 * k + 10;
            c.textFit("내 땅", tx, y + dy + h / 2, 23, 70, Anim.alpha(0xFF4A2D16, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(J.num(mi, "count") + "칸 · 눌러서 관리", tx + 66, y + dy + h / 2, 17, x + 931 * k - 10 - (tx + 66), Anim.alpha(0xFF8A6A4A, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            cardX = x; cardY = y; cardW = w; cardH = h;
        }
        c.pop();
    }

    /**
     * 알약 카드 그림(notice/toast_cream 1024×160)을 아무 폭으로: 높이에 맞춘 배율로 양 끝 20%(왼쪽 동그라미·오른쪽 잎 장식)는
     * 그대로 두고 가운데 판만 가로로 늘림 (통째로 늘이면 동그라미가 찌그러짐).
     */
    private static void pill(Canvas c, float x, float y, float w, float h, int argb) {
        float k = h / 160f;
        c.imageNine("notice/toast_cream", x, y, w, h, .2f, .45f, 1024 * .2f * k, h * .45f, argb);
    }

    /** 카드를 눌렀는지 (가상 좌표, scale = HUD 배율) */
    public boolean hit(float mx, float my, float scale) {
        if (mine == null || cardW <= 0) return false;
        return Anim.in(mx / scale, my / scale, cardX, cardY, cardW, cardH);
    }
}
