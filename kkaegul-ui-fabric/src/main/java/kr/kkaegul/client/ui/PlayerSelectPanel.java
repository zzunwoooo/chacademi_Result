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

        // 창 그림: 몸통(원본 92~932 x 139~1411)이 FX,FY,FW,FH 자리 — 그림은 원본 82~944 x 136~1413 (v3 장식 여백 L10/T3/R12/B2 원본 픽셀)
        float q = FW / 840f;
        c.image("user_select/window_frame", FX - 10 * q, FY - 3 * q, 862 * q, 1277 * q, 0xFFFFFFFF);
        // 이름판: 원본 x 245~780, y 195~305 → 가운데 (512, 250)
        c.textFit("유저 텔레포트", FX + (512 - 92) * q, FY + (250 - 139) * q, 40, 440, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 티켓 + 안내
        c.imageAt("user_select/teleport_ticket", 226, 414, 54, 1, -14, 0xFFFFFFFF);   // 창 왼쪽 위 반짝이·흐린 클로버(x ≤ 180, y ≤ 431) 오른쪽
        c.textFit(search.isEmpty() ? "누구에게 갈까요?" : "검색: " + HangulInput.compose(search), 292, 414, 30, 460, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.fill(ROW_X, 452, 600, 3, 1.5f, 0xFFE8DCC6);

        // 목록 (줄 그림 1024x158 비율 그대로)
        List<Integer> vis = visible();
        float listY = LIST_Y, listH = LIST_H, step = ROW_H + 12, rh = ROW_H;
        scrollMax = Math.max(0, vis.size() * step - (listH + (step - rh)));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (vis.isEmpty()) c.text(names().isEmpty() ? "접속한 다른 유저가 없어요" : "검색 결과가 없어요", 475, LIST_Y + LIST_H / 2, 34, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        for (int n = 0; n < vis.size(); n++) {
            int i = vis.get(n);
            float ry = listY + n * step - scroll;
            if (ry < listY - 4 || ry + rh > listY + listH + 4) continue;
            boolean sel = i == selected, hov = Anim.in(mx, my, ROW_X, ry, ROW_W, rh);
            // 얼굴 칸: 그림마다 자리가 조금씩 달라서 그림 대비 비율로 {x0, x1, y0, y1}
            float[] slot;
            String nm = label(i);
            boolean nick = !nm.equals(names().get(i));
            if (sel) {
                // 고른 줄 (v3 1024x237, 몸통 = 그림 전체): 높이는 줄과 같게, 얼굴 칸까지 든 양끝 22% 는 비율 그대로 · 가운데 파란 부분만 가로로 늘림
                float s = rh / 237f;
                c.imageNine("user_select/user_row_pressed", ROW_X, ry, ROW_W, rh, .22f, .45f, .22f * 1024 * s, .45f * rh, 0xFFFFFFFF);
                slot = new float[]{ROW_X + 66 * s, ROW_X + 194 * s, ry + .283f * rh, ry + .802f * rh};   // 얼굴 칸 픽셀 x 66~194, y 67~190
            } else if (hov) {
                c.image("user_select/user_row_hover", ROW_X, ry, ROW_W, rh, 0xFFFFFFFF);
                slot = new float[]{ROW_X + .051f * ROW_W, ROW_X + .148f * ROW_W, ry + .27f * rh, ry + .818f * rh};   // 픽셀 x 52~152, y 43~130
            } else {
                c.image("user_select/user_row_normal", ROW_X, ry, ROW_W, rh, 0xFFFFFFFF);
                slot = new float[]{ROW_X + .032f * ROW_W, ROW_X + .141f * ROW_W, ry + .222f * rh, ry + .861f * rh};   // 픽셀 x 33~144, y 35~136
            }
            float fcx = (slot[0] + slot[1]) / 2, fcy = (slot[2] + slot[3]) / 2, fs = Math.min(slot[1] - slot[0], slot[3] - slot[2]) * .8f;
            if (!c.face(names().get(i), fcx - fs / 2, fcy - fs / 2, fs)) c.text(nm.isEmpty() ? "?" : nm.substring(0, 1), fcx, fcy, fs * .62f, Theme.BLUE, Canvas.CENTER, Canvas.BOLD);
            if (hov && !sel) {
                // 마우스 올린 줄: 흰 이름 칸(픽셀 x 178~985, y 50~122) 안에 한 줄로 — 별명 왼쪽, 계정명 오른쪽 작게
                float fx0 = ROW_X + 196f / 1024 * ROW_W, fx1 = ROW_X + 968f / 1024 * ROW_W, fy = ry + 86f / 159 * rh;
                float aw = nick ? Math.min(150, c.textWidth(names().get(i), 20, Canvas.BOLD | Canvas.LIGHT)) : 0;
                c.textFit(nm, fx0, fy, 30, fx1 - fx0 - aw - (nick ? 12 : 0), Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
                if (nick) c.textFit(names().get(i), fx1, fy, 20, 150, Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
            } else {
                float tx = ROW_X + 220f / 1024 * ROW_W, tw = ROW_X + .94f * ROW_W - tx;
                // 고른 줄은 파란 속(그림 y 48~200 = 줄 높이 20~84%) 안에 두 줄이 들어가게 조금 작게
                float fs1 = sel ? 30 : 34, up = nick ? (sel ? 10 : 12) : 0, dn = sel ? 19 : 22;
                c.textFit(nm, tx, ry + rh * (sel ? .52f : .5f) - up, fs1, tw, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
                if (nick) c.textFit(names().get(i), tx, ry + rh * (sel ? .52f : .5f) + dn, sel ? 19 : 21, tw, sel ? 0xFF2F5F8A : Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            }
            hits.add(new R("row", i, ROW_X, ry, ROW_W, rh));
        }
        // 스크롤 막대 (가는 길 + 하늘색 손잡이)
        // 창 오른쪽 반짝이(x 773~)에 안 닿게 목록 오른쪽 742 다음 750~
        float sx = 752, sw = 14;
        nine(c, "user_select/scroll_track", sx, listY - 4, sw, listH + 8, .45f, .06f);
        float th = scrollMax > 0 ? Math.max(90, listH * listH / (listH + scrollMax)) : listH;
        float ty = scrollMax > 0 ? listY + (listH - th) * scroll / scrollMax : listY;
        nine(c, "user_select/scroll_thumb", sx - 4, ty, sw + 8, th, .45f, .3f);
        c.fill(ROW_X, LIST_Y + LIST_H + 12, 600, 3, 1.5f, 0xFFE8DCC6);

        // 버튼: 이동(파랑) · 취소(크림) — 누르는 중(마우스 올림)은 눌린 그림
        // v3 창 아래 두 모서리 장식(왼 y≥1117 · 오른 x≥517 y≥1092)보다 위에
        boolean canMove = selected >= 0;
        float by = LIST_Y + LIST_H + 26, bh = 70, bw = 262, mxB = 188, cxB = 500;
        boolean hm = canMove && Anim.in(mx, my, mxB, by, bw, bh), hc = Anim.in(mx, my, cxB, by, bw, bh);
        nine(c, !canMove ? "user_select/move_disabled" : hm ? "user_select/move_pressed" : "user_select/move_normal", mxB, by + (hm ? 2 : 0), bw, bh, .25f, .45f);
        c.text("이동", mxB + bw / 2, by + bh / 2 + (hm ? 2 : 0), 36, canMove ? Theme.WHITE : 0xFF9AA4AC, Canvas.CENTER, Canvas.BOLD | (canMove ? Canvas.OUTLINE : Canvas.LIGHT));
        nine(c, hc ? "user_select/cancel_pressed" : "user_select/cancel_normal", cxB, by + (hc ? 2 : 0), bw, bh, .25f, .45f);
        c.text("취소", cxB + bw / 2, by + bh / 2 + (hc ? 2 : 0), 36, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("move", -1, mxB, by, bw, bh));
        hits.add(new R("cancel", -1, cxB, by, bw, bh));

        // 오른쪽 아래 깨굴이 + 포탈
        float jump = Math.abs((float) Math.sin(now / 380.0)) * 18;
        // 창 오른쪽 아래 클로버·다이아(x ≤ 834)를 덮지 않게 창 바깥 오른쪽으로
        c.imageAt("user_select/frog_portal", 952, 1235 - jump * .3f, 290, 1, 0, 0xFFFFFFFF);
        c.pop();
    }

    // 창 몸통 자리 (원본 840x1272 영역) · 목록 줄 자리 (줄 그림 1024x158 비율)
    private static final float FX = 110, FW = 730, FH = FW * 1272 / 840f, FY = 200;
    private static final float ROW_X = 166, ROW_W = 576, ROW_H = ROW_W * 158 / 1024f, LIST_Y = 476, LIST_H = 506;

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
