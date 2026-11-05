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

        // 창: 크림 테 · 하늘색 띠 · 크림 안쪽
        MenuPanel.box(c, 110, 225, 730, 1140, 90, 0xFFE9DCC4, 0xFFFBF4E6, 5);
        MenuPanel.box(c, 128, 243, 694, 1104, 76, 0xFF9FCDEB, 0xFFC4E3F6, 4);
        MenuPanel.box(c, 146, 261, 658, 1068, 62, 0xFFE6D8BF, 0xFFFAF3E6, 3);
        // 위 이름판 + 잎
        HudView.leaf(c, 230, 215, 44, true);
        HudView.leaf(c, 720, 215, 44, false);
        MenuPanel.box(c, 228, 183, 500, 109, 54, 0xFFE3D3B6, 0xFFFBF4E6, 5);
        MenuPanel.box(c, 244, 197, 468, 81, 40, 0xFFEADFC9, 0xFFF9F1E2, 2);
        c.text("유저 텔레포트", 478, 237, 44, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 티켓 + 안내
        c.imageAt("user_select/teleport_ticket", 220, 347, 70, 1, -14, 0xFFFFFFFF);
        c.text(search.isEmpty() ? "누구에게 갈까요?" : "검색: " + search, 300, 347, 30, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.fill(157, 400, 633, 3, 1.5f, 0xFFE3D6BF);

        // 목록
        List<Integer> vis = visible();
        float listY = 435, listH = 720, step = 147, rh = 130;
        scrollMax = Math.max(0, vis.size() * step - (listH + (step - rh)));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (vis.isEmpty()) c.text(names().isEmpty() ? "접속한 다른 유저가 없어요" : "검색 결과가 없어요", 455, 700, 34, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        for (int n = 0; n < vis.size(); n++) {
            int i = vis.get(n);
            float ry = listY + n * step - scroll;
            if (ry < listY - 4 || ry + rh > listY + listH + 4) continue;
            boolean sel = i == selected, hov = Anim.in(mx, my, 157, ry, 593, rh);
            MenuPanel.box(c, 157, ry, 593, rh, 30, sel ? 0xFF8CC4EA : hov ? 0xFFC9B999 : 0xFFE3D6BF, sel ? 0xFFC3E3F8 : hov ? 0xFFFBF2E0 : 0xFFF8F0E2, 3);
            MenuPanel.box(c, 180, ry + 15, 100, 100, 22, 0xFFDCCDB1, 0xFFFBF2E2, 3);
            if (!c.face(names().get(i), 195, ry + 30, 70)) c.text(label(i).isEmpty() ? "?" : label(i).substring(0, 1), 230, ry + 65, 44, Theme.BLUE, Canvas.CENTER, Canvas.BOLD);
            String nm = label(i);
            boolean nick = !nm.equals(names().get(i));
            c.textFit(nm, 310, ry + rh / 2 - (nick ? 14 : 0), 40, 410, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            if (nick) c.text(names().get(i), 310, ry + rh / 2 + 28, 24, Theme.MUTED, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            hits.add(new R("row", i, 157, ry, 593, rh));
        }
        // 스크롤 막대
        MenuPanel.box(c, 768, 432, 22, 720, 11, 0xFFE3D6BF, 0xFFFBF4E8, 2);
        float th = scrollMax > 0 ? Math.max(80, 720 * 720 / (720 + scrollMax)) : 720;
        float ty = scrollMax > 0 ? 432 + (720 - th) * scroll / scrollMax : 432;
        MenuPanel.box(c, 768, ty, 22, th, 11, 0xFF7FB8E4, 0xFFA8D4F2, 2);
        c.fill(157, 1185, 633, 3, 1.5f, 0xFFE3D6BF);

        // 버튼: 이동(파랑) · 취소(크림)
        boolean canMove = selected >= 0;
        boolean hm = canMove && Anim.in(mx, my, 170, 1215, 278, 85), hc = Anim.in(mx, my, 470, 1215, 278, 85);
        MenuPanel.box(c, 170, 1215, 278, 85, 42, canMove ? 0xFF6FB3E3 : 0xFFC9D6DE, canMove ? (hm ? 0xFF8FCBF2 : 0xFFA5D8F7) : 0xFFDDE6EC, 4);
        c.text("이동", 309, 1257, 38, canMove ? Theme.WHITE : 0xFFF2F6F8, Canvas.CENTER, Canvas.BOLD | (canMove ? Canvas.OUTLINE : 0));
        MenuPanel.box(c, 470, 1215, 278, 85, 42, hc ? 0xFFC9B999 : 0xFFE3D3B6, 0xFFFBF1DD, 4);
        c.text("취소", 609, 1257, 38, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("move", -1, 170, 1215, 278, 85));
        hits.add(new R("cancel", -1, 470, 1215, 278, 85));

        // 오른쪽 아래 깨굴이 + 포탈
        float jump = Math.abs((float) Math.sin(now / 380.0)) * 18;
        c.imageAt("user_select/frog_portal", 870, 1255 - jump * .3f, 290, 1, 0, 0xFFFFFFFF);
        c.pop();
    }

    @Override public boolean scroll(float mx, float my, double amount) {
        scroll = (float) Math.max(0, Math.min(scrollMax, scroll - amount * 147));
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
