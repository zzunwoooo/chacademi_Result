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
            if (search.isEmpty() || label(i).toLowerCase().contains(search.toLowerCase()) || n.get(i).toLowerCase().contains(search.toLowerCase())) out.add(i);
        }
        return out;
    }

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x88000000);
        float fh = Math.min(820, H - 120), fw = fh * 676 / 1024f;
        float fx = (W - fw) / 2, fy = (H - fh) / 2 + 10;
        float rise = (1 - Anim.outCubic(Anim.t(now, opened, 280))) * 40;
        fy += rise;
        c.image("user_select/window_frame", fx, fy, fw, fh, 0xFFFFFFFF);
        c.imageFit("user_select/frog_portal", fx - 120, fy + fh * .62f, 300, 0xFFFFFFFF);
        c.imageAt("user_select/teleport_ticket", fx + fw - 30, fy + 40, 90, 1, 14, 0xFFFFFFFF);
        c.textFit("유저 텔레포트", fx + fw * .485f, fy + fh * .045f, 28, fw * .6f, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        float ix = fx + fw * .08f, iw = fw * .84f, iy = fy + fh * .11f;
        c.text(search.isEmpty() ? "누구에게 갈까요?" : "검색: " + search, ix + 4, iy + 10, 20, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);

        List<Integer> vis = visible();
        float listY = iy + 36, listH = fh * .7f, rh = iw * 158 / 1024f, gap = 8;
        scrollMax = Math.max(0, vis.size() * (rh + gap) - listH);
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (vis.isEmpty()) c.text(names().isEmpty() ? "접속한 다른 유저가 없어요" : "검색 결과가 없어요", fx + fw / 2, listY + 120, 22, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        for (int k = 0; k < vis.size(); k++) {
            int i = vis.get(k);
            float ry = listY + k * (rh + gap) - scroll;
            if (ry < listY - 2 || ry + rh > listY + listH + 2) continue;
            boolean hov = Anim.in(mx, my, ix, ry, iw - 22, rh);
            String art = i == selected ? "user_row_pressed" : hov ? "user_row_hover" : "user_row_normal";
            float ah = i == selected ? iw * 237 / 1024f * .66f : rh;
            c.image("user_select/" + art, ix, ry + (rh - ah) / 2, iw - 22, ah, 0xFFFFFFFF);
            String nm = label(i);
            c.text(nm.isEmpty() ? "?" : nm.substring(0, 1), ix + (iw - 22) * .075f, ry + rh / 2, 22, Theme.BLUE, Canvas.CENTER, Canvas.BOLD);
            c.textFit(nm, ix + (iw - 22) * .16f, ry + rh / 2 - (nm.equals(names().get(i)) ? 0 : 8), 22, (iw - 22) * .78f, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (!nm.equals(names().get(i))) c.text(names().get(i), ix + (iw - 22) * .16f, ry + rh / 2 + 13, 14, Theme.MUTED, Canvas.LEFT, Canvas.LIGHT);
            hits.add(new R("row", i, ix, ry, iw - 22, rh));
        }
        if (scrollMax > 0) {
            float tx = ix + iw - 12, th = listH * listH / (listH + scrollMax);
            c.image("user_select/scroll_track", tx, listY, 10, listH, 0xFFFFFFFF);
            c.image("user_select/scroll_thumb", tx - 3, listY + (listH - th) * scroll / scrollMax, 16, th, 0xFFFFFFFF);
        }
        // 버튼
        float bw = fw * .38f, bh = bw * 119 / 512f, by = fy + fh * .885f;
        float cx1 = fx + fw * .27f - bw / 2, cx2 = fx + fw * .71f - bw / 2;
        boolean canMove = selected >= 0;
        boolean hc = Anim.in(mx, my, cx1, by, bw, bh), hm = canMove && Anim.in(mx, my, cx2, by, bw, bh);
        c.image(hc ? "user_select/cancel_pressed" : "user_select/cancel_normal", cx1, by, bw, bh, 0xFFFFFFFF);
        c.text("취소", cx1 + bw / 2, by + bh / 2, 22, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.image(!canMove ? "user_select/move_disabled" : hm ? "user_select/move_pressed" : "user_select/move_normal", cx2, by, bw, bh, 0xFFFFFFFF);
        c.text("이동", cx2 + bw / 2, by + bh / 2, 22, canMove ? Theme.WHITE : 0xFFB8C4CC, Canvas.CENTER, Canvas.BOLD | (canMove ? Canvas.OUTLINE : 0));
        hits.add(new R("cancel", -1, cx1, by, bw, bh));
        hits.add(new R("move", -1, cx2, by, bw, bh));
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        scroll = (float) Math.max(0, Math.min(scrollMax, scroll - amount * 50));
        return true;
    }

    @Override public boolean click(float mx, float my, long now) {
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
        if (search.length() < 16) search += ch;
        selected = -1;
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        if (key == 259 && !search.isEmpty()) { search = search.substring(0, search.length() - 1); return true; } // BACKSPACE
        if ((key == 257 || key == 335) && selected >= 0) { move(); return true; } // ENTER
        return false;
    }
}
