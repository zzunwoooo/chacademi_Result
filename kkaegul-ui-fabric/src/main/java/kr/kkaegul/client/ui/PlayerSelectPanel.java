package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 유저 텔포권: 접속자 목록에서 골라 이동 → ticket.userTp target=계정명 */
public final class PlayerSelectPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private int selected = -1;
    private float scroll, scrollMax;
    private long opened;
    private String search = "";
    private record R(String key, int idx, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();

    public PlayerSelectPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public String screen() { return "player_select"; }

    @Override public void update(JsonObject d, long now) { if (d != null) data = J.merge(data, d); }

    private List<String> names() { return J.strs(data, "players"); }

    private String label(int i) {
        List<String> l = J.strs(data, "labels");
        return i < l.size() && !l.get(i).isBlank() ? l.get(i) : names().get(i);
    }

    private List<Integer> visible() {
        List<Integer> out = new ArrayList<>();
        List<String> n = names();
        for (int i = 0; i < n.size(); i++) {
            String q = HangulInput.compose(search).toLowerCase();
            if (q.isEmpty() || label(i).toLowerCase().contains(q) || n.get(i).toLowerCase().contains(q)) out.add(i);
        }
        return out;
    }

    private float k = 1, ox, oy;

    /** 유저 텔포 창 (레퍼런스 1024x1536 좌표 그대로) */
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x66000000);
        k = Math.min(H * .9f / 1240f, W * .9f / 900f);
        float rise = (1 - Anim.outCubic(Anim.t(now, opened, 280))) * 30;
        ox = W / 2 - 475 * k;
        oy = H / 2 - 790 * k + rise;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;

        // 창 그림 (676x1024 비율 그대로): 금테 · 하늘색 띠 · 크림 안쪽 + 위 이름판·잎
        c.image("user_select/window_frame", FX, FY, FW, FH, 0xFFFFFFFF);
        c.textFit("유저 텔레포트", 475, FY + (.043f + .117f) / 2 * FH, 40, .56f * FW, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 티켓 + 안내
        c.imageAt("user_select/teleport_ticket", 215, 410, 54, 1, -14, 0xFFFFFFFF);
        c.textFit(search.isEmpty() ? "누구에게 갈까요?" : "검색: " + HangulInput.compose(search), 280, 412, 30, 480, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.fill(170, 452, 610, 3, 1.5f, 0xFFE8DCC6);

        // 목록 (줄 그림 1024x158 비율 그대로)
        List<Integer> vis = visible();
        float listY = LIST_Y, listH = LIST_H, step = ROW_H + 12, rh = ROW_H;
        scrollMax = Math.max(0, vis.size() * step - (listH + (step - rh)));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (vis.isEmpty()) c.text(names().isEmpty() ? "접속한 다른 유저가 없어요" : "검색 결과가 없어요", 475, 760, 34, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        for (int n = 0; n < vis.size(); n++) {
            int i = vis.get(n);
            float ry = listY + n * step - scroll;
            if (ry < listY - 4 || ry + rh > listY + listH + 4) continue;
            boolean sel = i == selected, hov = Anim.in(mx, my, ROW_X, ry, ROW_W, rh);
            // 얼굴 칸: 그림마다 자리가 조금씩 달라서 그림 대비 비율로 {x0, x1, y0, y1}
            float[] slot;
            if (sel) {
                // 고른 줄: 잎 달린 하늘색 그림(1024x237) — 몸통(금테)이 그림 높이의 14.5~96% (픽셀 34~227) 라 그만큼 위아래로 키워 9조각으로 폭만 늘림
                float ph = rh / .815f, py = ry - .145f * ph, s = ph / 237f;
                c.imageNine("user_select/user_row_pressed", ROW_X - 4, py, ROW_W + 8, ph, .2f, .45f, .2f * 1024 * s, .45f * ph, 0xFFFFFFFF);
                float ax0 = ROW_X - 4 + .052f * 1024 * s, ax1 = ROW_X - 4 + .183f * 1024 * s;   // 얼굴 칸 픽셀 x 53~187, y 64~194
                slot = new float[]{ax0, ax1, py + .27f * ph, py + .819f * ph};
            } else if (hov) {
                c.image("user_select/user_row_hover", ROW_X, ry, ROW_W, rh, 0xFFFFFFFF);
                slot = new float[]{ROW_X + .037f * ROW_W, ROW_X + .142f * ROW_W, ry + .17f * rh, ry + .887f * rh};   // 픽셀 x 38~145, y 27~141
            } else {
                c.image("user_select/user_row_normal", ROW_X, ry, ROW_W, rh, 0xFFFFFFFF);
                slot = new float[]{ROW_X + .024f * ROW_W, ROW_X + .137f * ROW_W, ry + .139f * rh, ry + .854f * rh};   // 픽셀 x 25~140, y 22~135
            }
            float fcx = (slot[0] + slot[1]) / 2, fcy = (slot[2] + slot[3]) / 2, fs = Math.min(slot[1] - slot[0], slot[3] - slot[2]) * .8f;
            if (!c.face(names().get(i), fcx - fs / 2, fcy - fs / 2, fs)) c.text(label(i).isEmpty() ? "?" : label(i).substring(0, 1), fcx, fcy, fs * .62f, Theme.BLUE, Canvas.CENTER, Canvas.BOLD);
            String nm = label(i);
            boolean nick = !nm.equals(names().get(i));
            float tx = ROW_X + .17f * ROW_W, tw = ROW_X + ROW_W - 28 - tx;
            c.textFit(nm, tx, ry + rh / 2 - (nick ? 12 : 0), 34, tw, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (nick) c.textFit(names().get(i), tx, ry + rh / 2 + 22, 21, tw, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R("row", i, ROW_X, ry, ROW_W, rh));
        }
        // 스크롤 막대 (가는 길 + 하늘색 손잡이)
        float sx = 770, sw = 16;
        nine(c, "user_select/scroll_track", sx, listY - 4, sw, listH + 8, .45f, .06f);
        float th = scrollMax > 0 ? Math.max(90, listH * listH / (listH + scrollMax)) : listH;
        float ty = scrollMax > 0 ? listY + (listH - th) * scroll / scrollMax : listY;
        nine(c, "user_select/scroll_thumb", sx - 5, ty, sw + 10, th, .45f, .3f);
        c.fill(170, 1137, 610, 3, 1.5f, 0xFFE8DCC6);

        // 버튼: 이동(파랑) · 취소(크림) — 누르는 중(마우스 올림)은 눌린 그림
        boolean canMove = selected >= 0;
        float by = 1158, bh = 76, bw = 280, mxB = 185, cxB = 485;
        boolean hm = canMove && Anim.in(mx, my, mxB, by, bw, bh), hc = Anim.in(mx, my, cxB, by, bw, bh);
        nine(c, !canMove ? "user_select/move_disabled" : hm ? "user_select/move_pressed" : "user_select/move_normal", mxB, by + (hm ? 2 : 0), bw, bh, .25f, .45f);
        c.text("이동", mxB + bw / 2, by + bh / 2 + (hm ? 2 : 0), 36, canMove ? Theme.WHITE : 0xFF9AA4AC, Canvas.CENTER, Canvas.BOLD | (canMove ? Canvas.OUTLINE : Canvas.LIGHT));
        nine(c, hc ? "user_select/cancel_pressed" : "user_select/cancel_normal", cxB, by + (hc ? 2 : 0), bw, bh, .25f, .45f);
        c.text("취소", cxB + bw / 2, by + bh / 2 + (hc ? 2 : 0), 36, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("move", -1, mxB, by, bw, bh));
        hits.add(new R("cancel", -1, cxB, by, bw, bh));

        // 오른쪽 아래 깨굴이 + 포탈
        float jump = Math.abs((float) Math.sin(now / 380.0)) * 18;
        c.imageAt("user_select/frog_portal", 870, 1255 - jump * .3f, 290, 1, 0, 0xFFFFFFFF);
        c.pop();
    }

    // 창 그림 자리 (676x1024) · 목록 줄 자리 (줄 그림 1024x158 비율)
    private static final float FX = 110, FW = 730, FH = FW * 1024 / 676f, FY = 200;
    private static final float ROW_X = 168, ROW_W = 590, ROW_H = ROW_W * 158 / 1024f, LIST_Y = 476, LIST_H = 646;

    /** 9조각 그림: 가장자리는 그림 비율 그대로 (가로·세로 중 작은 배율), 가운데만 늘림 */
    private static void nine(Canvas c, String id, float x, float y, float w, float h, float bu, float bv) {
        int[] s = c.size(id);
        if (s == null) { c.image(id, x, y, w, h, 0xFFFFFFFF); return; }
        float sc = Math.min(w / s[0], h / s[1]);
        c.imageNine(id, x, y, w, h, bu, bv, bu * s[0] * sc, bv * s[1] * sc, 0xFFFFFFFF);
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        scroll = (float) Math.max(0, Math.min(scrollMax, scroll - amount * (ROW_H + 12)));
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            switch (r.key()) {
                case "row" -> {
                    if (selected == r.idx() && now - lastClick < 350) { move(); return true; }
                    selected = r.idx();
                    lastClick = now;
                    host.sound("minecraft:ui.button.click", 1.3f, .4f);
                }
                case "cancel" -> host.closePanel(this);
                case "move" -> move();
                default -> { }
            }
            return true;
        }
        return false;
    }

    private long lastClick;

    private void move() {
        if (selected < 0 || selected >= names().size()) return;
        host.send("ticket.userTp", "target", names().get(selected));
        host.sound("minecraft:entity.enderman.teleport", 1.2f, .6f);
        host.closePanel(this);
    }

    @Override public boolean typed(char ch, long now) {
        if (Character.isISOControl(ch)) return false;
        if (search.length() < 32) search += ch;   // 자모가 따로 와도 보여줄 때·찾을 때 합침
        selected = -1;
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (key == 259 && !search.isEmpty()) { search = search.substring(0, search.length() - 1); return true; } // BACKSPACE
        if ((key == 257 || key == 335) && selected >= 0) { move(); return true; } // ENTER
        return false;
    }
}
