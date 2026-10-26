package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.TeleportPickProtocol.Entry;

/**
 * 텔포권 유저 고르기 창 (오너 2026-10-26). 친구 목록 창(FriendsScreen)의 판·행·검색칸·스크롤을 그대로 쓰고, 행 오른쪽의
 * 버튼 세 개 대신 "이동" 버튼 하나와 그 유저가 있는 서버 이름을 보여 준다. 목록은 서버가 보낸 것만 (학교·야생 접속자,
 * 아레나 접속자는 없음). 고르면 서버가 다시 검사하고 이동을 시작한다 (텔포권 1개 사용, 실패하면 돌려받음).
 */
public final class TeleportPickScreen extends SocialScreen {
    private final long token;
    private final List<Entry> entries;
    private Input search;
    private boolean dragging, scrollbar, answered;
    private float scroll;
    private double dragY;
    private long pickedAt;

    TeleportPickScreen(long token, List<Entry> entries) {
        super("텔포권");
        this.token = token;
        this.entries = List.copyOf(entries);
    }
    long token() { return token; }
    @Override SocialLayout.Fit fit() { return SocialLayout.friends(width, height); }
    @Override protected void init() { String q = search == null ? "" : search.text(); super.init(); search = new Input("유저 검색", 16, q); }

    private List<Entry> visible() {
        String q = search == null ? "" : search.text().toLowerCase(Locale.ROOT);
        return entries.stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    /** 서버가 창을 닫음 (이동 시작·연결 끊김). 답은 이미 갔거나 필요 없음. */
    void serverClosed() { answered = true; if (client != null && client.currentScreen == this) super.close(); }

    @Override public void tick() {
        super.tick();
        if (busy && Util.getMeasuringTimeMs() - pickedAt > 5000) serverClosed();   // 서버 답이 없으면 그냥 닫는다 (결과는 알림창)
    }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var f = fit(); double mx = f.x(mouseX), my = f.y(mouseY);
        start(c);
        try {
            asset(c, "friends_panel", 0, 0, 1000, 870, 0, 0, 1340, 1174, 0xEEFFFFFF);
            // 친구 목록과 같은 두 사람 머리글 그림
            HudMesh.disk(c, 81, 77, 8, GOLD); HudMesh.disk(c, 104, 72, 9, GOLD); HudMesh.capsule(c, 69, 91, 24, 12, GOLD, GOLD); HudMesh.quad(c, 69, 97, 24, 8, GOLD, GOLD);
            HudMesh.capsule(c, 92, 87, 25, 12, GOLD, GOLD); HudMesh.quad(c, 92, 93, 25, 12, GOLD, GOLD);
            label(c, "텔포권 · 갈 유저 고르기 (" + entries.size() + "명 접속 중)", 140, 84, 34, WHITE, true);
            boolean close = in(mx, my, 915, 62, 42, 42) && !busy; FriendsScreen.cross(c, 936, 82, close ? CYAN : WHITE);
            box(c, 55, 145, 879, 48, search.focus()); search.draw(c, 63, 147, 863, 44, "유저 검색");
            var list = visible();
            scroll = Math.clamp(scroll, 0, SocialLayout.maxScroll(list.size()));
            c.enableScissor(52, 211, 938, 755);
            int hot = 0;
            try {
                for (int i = 0; i < list.size(); i++) {
                    int y = Math.round(215 + i * 90 - scroll); if (y + 82 < 211 || y > 755) continue;
                    Entry e = list.get(i); base(c, e, y);
                    HudMesh.disk(c, 571, y + 41, 6, 0xFF52D4A1); fitted(c, e.server().isEmpty() ? "접속 중" : e.server(), 590, y + 41, 20, 170, WHITE, false);
                    boolean over = !busy && in(mx, my, 790, y + 19, 130, 44) && my >= 215 && my < 755;
                    if (over) hot = 1 + i;
                    button(c, "이동", 790, y + 19, 130, 44, over);
                }
            } finally { c.disableScissor(); }
            int count = list.size();
            float max = SocialLayout.maxScroll(count), thumb = count <= 6 ? 540 : Math.max(45, 540 * 6f / count), ty = 215 + (max == 0 ? 0 : (540 - thumb) * scroll / max);
            HudMesh.quad(c, 947, 215, 7, 540, 0x55425665, 0x55425665); HudMesh.quad(c, 947, ty, 7, thumb, 0xFFDCC17F, 0xFFAE986C);
            if (list.isEmpty()) center(c, entries.isEmpty() ? "지금 갈 수 있는 유저가 없어요" : "검색 결과가 없습니다", 500, 475, 25, MUTED);
            center(c, busy ? "이동을 준비하고 있어요…" : "고르면 그 유저 곁으로 이동해요 · 텔포권 1개 사용", 500, 778, 20, MUTED);
            if (busy) { float t = Util.getMeasuringTimeMs() / 180f; for (int i = 0; i < 3; i++) HudMesh.disk(c, 482 + i * 18, 804, 3, ((int) (100 + 155 * (.5 + .5 * Math.sin(t - i))) << 24) | 0x92E7F2); }
            hover(hot != 0 ? hot : close ? 1000 : 0);
            c.getMatrices().push(); c.getMatrices().translate(0, 0, 60); toast(c, 120, 814, 760); c.getMatrices().pop();
        } finally { end(c); }
    }

    /** 행 배경·얼굴·이름·기숙사 (FriendsScreen 행과 같은 모양). */
    private void base(DrawContext c, Entry e, int y) {
        int d = SocialLayout.dorm(e.dormitory());
        if (d == 0) box(c, 55, y, 879, 82, false);
        else {
            String file = switch (d) { case 1 -> "row_arkeon"; case 2 -> "row_lumina"; case 3 -> "row_bestiaz"; default -> "row_noxer"; };
            int[] b = switch (d) { case 1 -> new int[]{42, 253, 2089, 208}; case 2 -> new int[]{38, 242, 2096, 216}; case 3 -> new int[]{37, 242, 2098, 218}; default -> new int[]{41, 247, 2091, 215}; };
            asset(c, file, 55, y, 879, 82, b[0], b[1], b[2], b[3], 0xCCFFFFFF);
        }
        var p = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(e.id());
        box(c, 72, y + 12, 58, 58, false);
        if (p != null) { var skin = p.getSkinTextures().texture(); c.drawTexture(RenderLayer::getGuiTextured, skin, 78, y + 18, 8, 8, 46, 46, 8, 8, 64, 64); c.drawTexture(RenderLayer::getGuiTextured, skin, 78, y + 18, 40, 8, 46, 46, 8, 8, 64, 64); }
        else center(c, e.name().isEmpty() ? "?" : e.name().substring(0, 1), 101, y + 41, 25, MUTED);
        fitted(c, e.name(), 236, y + 29, 26, 273, SocialLayout.color(e.dormitory()), true);
        label(c, e.dormitory().isEmpty() ? "기숙사 미정" : e.dormitory(), 236, y + 58, 19, SocialLayout.color(e.dormitory()), false);
    }

    private void pick(Entry e) {
        if (busy || answered) return;
        sound(1.13f, .2f);
        if (!TeleportPickClient.answer(token, e.id())) { notice("서버에 보내지 못했어요. 다시 해 주세요."); return; }
        answered = true; busy = true; pickedAt = Util.getMeasuringTimeMs();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        var f = fit(); double mx = f.x(x), my = f.y(y);
        if (busy) return true;
        if (in(mx, my, 915, 62, 42, 42)) { close(); return true; }
        if (search.click(mx, my)) return true;
        if (in(mx, my, 940, 215, 20, 540)) { scrollbar = dragging = true; dragY = my; setScrollbar(my); return true; }
        if (in(mx, my, 55, 215, 879, 540)) {
            var list = visible(); int row = (int) ((my - 215 + scroll) / 90);
            if (row >= 0 && row < list.size() && in(mx, my, 790, 215 + row * 90 - scroll + 19, 130, 44)) { pick(list.get(row)); return true; }
            dragging = true; scrollbar = false; dragY = my; return true;
        }
        return true;
    }
    private void setScrollbar(double my) { float max = SocialLayout.maxScroll(visible().size()); scroll = (float) Math.clamp((my - 215) / 540 * max, 0, max); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) { double my = fit().y(y); if (scrollbar) setScrollbar(my); else scroll = (float) Math.clamp(scroll + dragY - my, 0, SocialLayout.maxScroll(visible().size())); dragY = my; return true; }
        return false;
    }
    @Override public boolean mouseReleased(double x, double y, int button) { dragging = false; scrollbar = false; return super.mouseReleased(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (in(fit().x(x), fit().y(y), 55, 215, 902, 540)) { scroll = (float) Math.clamp(scroll - vertical * 55, 0, SocialLayout.maxScroll(visible().size())); return true; }
        return false;
    }
    @Override public boolean charTyped(char ch, int mods) { if (busy) return true; if (search.focus()) { scroll = 0; return search.typed(ch, mods); } return super.charTyped(ch, mods); }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (!busy) close(); return true; }
        if (busy) return true;
        if (search.focus()) { scroll = 0; return search.key(key, scan, mods); }
        return super.keyPressed(key, scan, mods);
    }
    @Override public void close() { sound(.95f, .18f); super.close(); }
    /** 고르지 않고 닫히면(Esc·X·다른 창) 취소를 알린다 (소모 없음). */
    @Override public void removed() { if (!answered) { answered = true; TeleportPickClient.answer(token, null); } super.removed(); }
}
