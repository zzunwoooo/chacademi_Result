package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.ChunkClaimProtocol;
import school.magiccodex.protocol.ChunkClaimProtocol.Person;
import school.magiccodex.protocol.PartyUiProtocol;
import static school.magiccodex.client.PartyUiAtlas.*;

/**
 * 내 땅 관리 창 (메모 #88). 오른쪽 위 작은 칸이 펼쳐진 모양 — 새 디자인 없이 파티 창의 "파티 초대" 패널을 그대로 쓴다
 * (INVITE_PANEL 패널 · SEARCH_ROW 줄 · 얼굴 틀 · 파랑 "허용"/분홍 "빼기" 버튼 · 확인 창은 파티 나가기 확인과 같은 TOAST).
 * 가상 좌표: 패널 0..563 × 0..H (파티 초대 패널 1062..1625 를 0 으로 옮긴 것).
 * 지금 이 땅에 들어온 사람 → 허용 넣기/빼기, 허용 명단(내 모든 땅에 적용) → 빼기, 판매(소유 해제 + 땅문서 1장 돌려받기, 확인 한 번).
 */
public final class ChunkLandScreen extends SocialScreen {
    private static final Identifier TITLE = Identifier.of("magiccodex", "section"), SERIF = Identifier.of("magiccodex", "heading");
    private static final Identifier STAR = Identifier.of("magiccodex", "textures/gui/party/house_star.png");
    private static final int TITLE_GOLD = 0xFFF0D293, SUB = 0xFFBFD3E6, GREEN = 0xFF7BE08E, OFF = 0xFF8A949C, TEXT = 0xFFE6E9EE,
            BLUE_TEXT = 0xFFDCEBFA, GOLD_TEXT = 0xFFF1D9A0, ROSE_TEXT = 0xFFF3A9B6;
    /** 파티 초대 패널과 같은 줄 크기 (C_X 1096-1062, C_W, C_H, C_STEP). */
    private static final float PANEL_W = 563, PANEL_H = 760, ROW_X = 34, ROW_W = 490, ROW_H = 102, ROW_STEP = 116, HEAD_H = 48,
            LIST_TOP = 150, LIST_H = PANEL_H - LIST_TOP - 128;
    private static final long BUSY_TIMEOUT = 4000;

    private float openT, scroll;
    private long lastFrame, busyAt;
    private boolean confirm;
    private int hot;

    public ChunkLandScreen() { super("내 땅"); }

    @Override SocialLayout.Fit fit() {
        // 오른쪽 위 작은 칸 자리에서 아래로 펼쳐진다
        float s = Math.min(width * .40f / PANEL_W, height * .92f / PANEL_H);
        float[] box = ChunkClaimClient.boxFrame(width, height);
        float right = box[0] + ChunkClaimClient.BOX_W * box[2];
        float x = Math.min(width - 6, right) - PANEL_W * s;
        float y = Math.max(4, Math.min(box[1], height - PANEL_H * s - 4));
        return new SocialLayout.Fit(x, y, s);
    }
    private static float ease(float t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }

    void landChanged() {
        if (!ChunkClaimClient.land.inside()) { close(); return; }
        scroll = Math.clamp(scroll, 0, maxScroll());
    }
    void result(boolean ok, String message) { busy = false; notice(message); if (ok) sound(1.2f, .18f); }

    // ---------------------------------------------------------------- 목록 (머리줄 + 사람 줄)

    private record Row(int kind, Person person, String text) {}   // kind 0 머리, 1 들어온 사람, 2 허용 명단, 3 빈 줄 안내
    private List<Row> rows() {
        var l = ChunkClaimClient.land;
        var out = new ArrayList<Row>();
        out.add(new Row(0, null, "지금 이 땅에 있는 사람 " + l.visitors().size() + "명"));
        for (var p : l.visitors()) out.add(new Row(1, p, ""));
        if (l.visitors().isEmpty()) out.add(new Row(3, null, "들어와 있는 사람이 없어요"));
        out.add(new Row(0, null, "허용 명단 " + l.trusted().size() + "명 · 내 모든 땅"));
        for (var p : l.trusted()) out.add(new Row(2, p, ""));
        if (l.trusted().isEmpty()) out.add(new Row(3, null, "허용한 사람이 없어요"));
        return out;
    }
    private static float height(Row r) { return r.kind() == 0 ? HEAD_H : r.kind() == 3 ? 56 : ROW_STEP; }
    private float contentHeight() { float h = 0; for (var r : rows()) h += height(r); return h; }
    private float maxScroll() { return Math.max(0, contentHeight() - LIST_H); }

    private boolean act(ChunkClaimProtocol.Action a) {
        if (busy) return false;
        if (!ChunkClaimClient.send(a)) { notice("서버에 연결되어 있지 않아요"); return false; }
        busy = true; busyAt = Util.getMeasuringTimeMs(); sound(1.13f, .2f); return true;
    }

    @Override public void tick() {
        super.tick();
        if (client.currentScreen != this) return;
        if (busy && Util.getMeasuringTimeMs() - busyAt > BUSY_TIMEOUT) busy = false;
        if (!ChunkClaimClient.land.inside()) close();
    }

    // ---------------------------------------------------------------- 그리기

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs(); float dt = lastFrame == 0 ? 0 : Math.min(.1f, (now - lastFrame) / 1000f); lastFrame = now;
        openT = Math.clamp(openT + dt / .22f, 0, 1);
        var f = fit(); double mx = f.x(mouseX), my = f.y(mouseY);
        int newHot = 0;
        start(c);
        try {
            float a = ease(openT);
            int tint = PartyUiAtlas.alpha(0xFFFFFFFF, a);
            c.getMatrices().push();
            // 작은 칸에서 아래로 펼쳐지는 느낌: 위쪽을 고정하고 세로로 늘어난다
            c.getMatrices().scale(1, .25f + .75f * a, 1);
            try {
                var land = ChunkClaimClient.land;
                stretch(images, c, INVITE_PANEL, 0, 0, PANEL_W, PANEL_H, tint);
                images.drawTexture(c, STAR, 40, 37, 0, 0, 52, 52, 128, 128, 128, 128, tint);
                type.draw(c, "내 땅", 115, 63, 34, PartyUiAtlas.alpha(TITLE_GOLD, a), TITLE, false);
                boolean overClose = !confirm && a >= 1 && near(mx, my, 516, 47, 22); if (overClose) newHot = 1;
                icon(images, c, CLOSE, 516, 47, 42, PartyUiAtlas.alpha(overClose ? 0xFFFFFFFF : 0xFFD8DCE2, a));
                String sub = "청크 " + land.cx() + ", " + land.cz() + " · 내 땅 " + land.owned() + "칸" + (land.max() > 0 ? " / " + land.max() : "");
                type.draw(c, sub, 40, 116, 22, PartyUiAtlas.alpha(SUB, a), SERIF, false);
                if (a >= .99f) { int h = list(c, mx, my); if (h != 0) newHot = h; }
                // 판매 (파티 나가기 버튼 그림)
                boolean overSell = !confirm && a >= 1 && in(mx, my, ROW_X, PANEL_H - 104, ROW_W, 58); if (overSell) newHot = 2;
                nine(images, c, LEAVE, ROW_X, PANEL_H - 104, ROW_W, 58, 58f / 121, PartyUiAtlas.alpha(overSell ? 0xFFFFFFFF : 0xFFE0E0E0, a));
                type.draw(c, "이 땅 판매 · 땅문서 1장 돌려받기", ROW_X + ROW_W / 2, PANEL_H - 75, 22, PartyUiAtlas.alpha(overSell ? 0xFFF3DDA6 : TEXT, a), BODY, true);
                if (busy) { float t = now / 180f; for (int i = 0; i < 3; i++) HudMesh.disk(c, PANEL_W / 2 - 18 + i * 18, PANEL_H - 30, 3, ((int) (100 + 155 * (.5 + .5 * Math.sin(t - i))) << 24) | 0x92E7F2); }
                if (confirm) newHot = confirmDialog(c, mx, my);
            } finally { c.getMatrices().pop(); }
            c.getMatrices().push(); c.getMatrices().translate(0, 0, 60); toast(c, 40, Math.round(PANEL_H + 10), 483); c.getMatrices().pop();
        } finally { end(c); }
        hot = newHot; hover(hot);
    }

    private int list(DrawContext c, double mx, double my) {
        var rows = rows();
        scroll = Math.clamp(scroll, 0, maxScroll());
        boolean inList = !confirm && my >= LIST_TOP && my < LIST_TOP + LIST_H;
        int hit = 0, visitor = 0, trusted = 0;
        c.enableScissor(10, Math.round(LIST_TOP), Math.round(PANEL_W - 10), Math.round(LIST_TOP + LIST_H));
        try {
            float top = LIST_TOP - scroll;
            for (var r : rows) {
                float h = height(r);
                boolean visible = top + h >= LIST_TOP && top <= LIST_TOP + LIST_H;
                switch (r.kind()) {
                    case 0 -> {
                        if (visible) {
                            type.draw(c, r.text(), ROW_X + 4, top + 26, 23, GOLD_TEXT, BOLD, false);
                            float end = ROW_X + 14 + type.width(r.text(), 23, BOLD);
                            HudMesh.line(c, end, top + 26, ROW_X + ROW_W, top + 26, 1.4f, 0x88D8BB7A);
                        }
                    }
                    case 3 -> { if (visible) type.draw(c, r.text(), ROW_X + ROW_W / 2, top + 26, 20, MUTED, BODY, true); }
                    default -> {
                        int index = r.kind() == 1 ? visitor++ : trusted++;
                        if (visible) {
                            var p = r.person();
                            nine(images, c, SEARCH_ROW, ROW_X, top, ROW_W, ROW_H, .55f, 0xFFFFFFFF);
                            avatar(c, p, ROW_X + 17, top + 15, 75, p.online());
                            icon(images, c, p.online() ? ONLINE : OFFLINE, ROW_X + 117, top + 67, 16, 0xFFFFFFFF);
                            type.draw(c, fit(p.name(), 26, 190), ROW_X + 144, top + 34, 26, 0xFFFFFFFF, BOLD, false);
                            String state = r.kind() == 1 ? (p.trusted() ? "허용됨" : "손님") : p.online() ? "온라인" : "오프라인";
                            type.draw(c, state, ROW_X + 144, top + 67, 18, r.kind() == 1 ? (p.trusted() ? GREEN : SUB) : p.online() ? GREEN : OFF, BODY, false);
                            boolean add = r.kind() == 1 && !p.trusted();
                            float bx = ROW_X + 350, by = top + 24;
                            boolean over = inList && !busy && in(mx, my, bx, by, 123, 53);
                            if (over) hit = (r.kind() == 1 ? 100 : 300) + index;
                            if (add) {
                                nine(images, c, over ? BLUE_HOVER : BLUE, bx, by, 123, 53, 53f / 93, 0xFFFFFFFF);
                                icon(images, c, PLUS, bx + 30, by + 26, 20, 0xFFDCEBFA);
                                type.draw(c, "허용", bx + 46, by + 26, 21, over ? 0xFFFFFFFF : BLUE_TEXT, BODY, false);
                            } else {
                                nine(images, c, over ? ROSE_HOVER : ROSE, bx, by, 123, 53, 53f / 93, 0xFFFFFFFF);
                                icon(images, c, KICK, bx + 30, by + 26, 22, 0xFFFFFFFF);
                                type.draw(c, "빼기", bx + 46, by + 26, 21, over ? 0xFFFFFFFF : ROSE_TEXT, BODY, false);
                            }
                        }
                    }
                }
                top += h;
            }
        } finally { c.disableScissor(); }
        float max = maxScroll();
        if (max > 0) {
            float x = PANEL_W - 26, h = LIST_H - 10, content = contentHeight();
            nine(images, c, SCROLL_TRACK, x, LIST_TOP + 5, 8, h, 8f / 35, 0xFFFFFFFF);
            float thumb = Math.max(40, h * h / Math.max(h, content)), ty = LIST_TOP + 5 + (h - thumb) * scroll / max;
            nine(images, c, SCROLL_THUMB, x + .5f, ty, 7, thumb, 7f / 31, 0xFFFFFFFF);
        }
        return hit;
    }

    private int confirmDialog(DrawContext c, double mx, double my) {
        c.getMatrices().push(); c.getMatrices().translate(0, 0, 40); int hit = 0;
        try {
            HudMesh.quad(c, 0, 0, PANEL_W, PANEL_H, 0x99050B14, 0x99050B14);
            float x = 10, y = PANEL_H / 2 - 75, w = PANEL_W - 20;
            stretch(images, c, TOAST, x, y, w, 150, 0xFFFFFFFF);
            String text = "이 땅을 팔까요? 소유가 풀리고 땅문서 1장을 돌려받아요.";
            type.draw(c, text, PANEL_W / 2, y + 47, Math.min(21, 21 * (w - 40) / Math.max(1, type.width(text, 21, BODY))), TEXT, BODY, true);
            if (button(c, "판매", PANEL_W / 2 - 156, y + 82, 150, ROSE, ROSE_HOVER, ROSE_TEXT, mx, my)) hit = 10;
            if (button(c, "취소", PANEL_W / 2 + 6, y + 82, 150, BLUE, BLUE_HOVER, BLUE_TEXT, mx, my)) hit = 11;
        } finally { c.getMatrices().pop(); }
        return hit;
    }
    /** 파티 창 확인 버튼과 같은 모양. @return 마우스가 위에 있으면 true */
    private boolean button(DrawContext c, String label, float x, float y, float w, Piece normal, Piece hover, int textColor, double mx, double my) {
        boolean over = in(mx, my, x, y, w, 52);
        nine(images, c, over ? hover : normal, x, y, w, 52, 52f / 93, 0xFFFFFFFF);
        type.draw(c, label, x + w / 2, y + 26, 21, over ? 0xFFFFFFFF : textColor, BODY, true);
        return over;
    }
    private void avatar(DrawContext c, Person p, float x, float y, float size, boolean online) {
        int inset = Math.round(size * .085f), fx = Math.round(x) + inset, fy = Math.round(y) + inset, fs = Math.round(size) - 2 * inset;
        HudMesh.quad(c, fx, fy, fs, fs, 0xFF0B1826, 0xFF0B1826);
        Identifier skin;
        try { skin = PartyUiClient.face(new PartyUiProtocol.Person(p.id(), p.name(), -1, "", false, online ? PartyUiProtocol.ONLINE : 0)); }
        catch (IllegalArgumentException bad) { skin = net.minecraft.client.util.DefaultSkinHelper.getSkinTextures(p.id()).texture(); }
        c.drawTexture(RenderLayer::getGuiTextured, skin, fx, fy, 8, 8, fs, fs, 8, 8, 64, 64);
        c.drawTexture(RenderLayer::getGuiTextured, skin, fx, fy, 40, 8, fs, fs, 8, 8, 64, 64);
        if (!online) HudMesh.quad(c, fx, fy, fs, fs, 0x88071019, 0x88071019);
        stretch(images, c, AVATAR_FRAME, x, y, size, size, 0xFFFFFFFF);
    }
    private String fit(String value, float size, float room) {
        if (type.width(value, size, BOLD) <= room) return value;
        var cps = value.codePoints().toArray();
        for (int n = cps.length - 1; n > 0; n--) { String s = new String(cps, 0, n) + "…"; if (type.width(s, size, BOLD) <= room) return s; }
        return "…";
    }
    private static boolean near(double x, double y, float cx, float cy, float r) { return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r; }

    // ---------------------------------------------------------------- 입력

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        var f = fit(); double mx = f.x(x), my = f.y(y);
        if (confirm) {
            if (hot == 10) { var l = ChunkClaimClient.land; if (act(ChunkClaimProtocol.Action.sell(l.cx(), l.cz()))) confirm = false; }
            else if (hot == 11 || !in(mx, my, 10, PANEL_H / 2 - 75, PANEL_W - 20, 150)) { confirm = false; sound(1, .15f); }
            return true;
        }
        if (hot == 1) { close(); return true; }
        if (hot == 2) { confirm = true; sound(1.1f, .2f); return true; }
        var land = ChunkClaimClient.land;
        if (hot >= 100 && hot < 300) {
            int i = hot - 100;
            if (i < land.visitors().size()) { var p = land.visitors().get(i); act(ChunkClaimProtocol.Action.trust(p.id(), !p.trusted())); }
            return true;
        }
        if (hot >= 300 && hot < 500) {
            int i = hot - 300;
            if (i < land.trusted().size()) act(ChunkClaimProtocol.Action.trust(land.trusted().get(i).id(), false));
            return true;
        }
        // 창 밖을 누르면 접힌다
        if (!in(mx, my, 0, 0, PANEL_W, PANEL_H)) { close(); return true; }
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (confirm) return true;
        var f = fit(); double mx = f.x(x), my = f.y(y);
        if (in(mx, my, 0, LIST_TOP, PANEL_W, LIST_H)) { scroll = (float) Math.clamp(scroll - vertical * 58, 0, maxScroll()); return true; }
        return false;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (confirm) { confirm = false; return true; } close(); return true; }
        if (confirm && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) { hot = 10; mouseClicked(0, 0, 0); return true; }
        if (!confirm && key == UiKeys.code(UiKeys.Action.LAND)) { close(); return true; }   // 같은 단축키로 접기
        return super.keyPressed(key, scan, mods);
    }
    @Override public void close() { sound(.95f, .18f); super.close(); }
}
