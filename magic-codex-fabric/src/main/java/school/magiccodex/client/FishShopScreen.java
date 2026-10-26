package school.magiccodex.client;

import java.io.InputStream;
import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.FishShopProtocol;
import school.magiccodex.protocol.FishShopProtocol.*;

/**
 * 물고기 판매소 window (2026-10-20, GPT's approved sale / lock references and PNG set textures/gui/fish_shop).
 * Geometry is the reference's guides/layout_1672x941.json. Left: the NPC portrait (shop portrait setting, like the
 * normal shop) and the nameplate. Right: the frame with two tabs.
 *  판매     four grade rows with check boxes; the footer sells the checked grades (전체 판매 / 선택 판매).
 *  잠금 설정 all species as cards; clicking toggles the lock, 적용 saves (locked species are never sold).
 * Text is drawn with the MagicCodex fonts, never baked into the PNGs.
 */
final class FishShopScreen extends Screen {
    static final String ROOT = "textures/gui/fish_shop/";
    static final Identifier SECTION = Identifier.of("magiccodex", "section"), BODY = Identifier.of("magiccodex", "body"),
            BOLD = Identifier.of("magiccodex", "hud_bold"), LABEL = Identifier.of("magiccodex", "label"),
            EXTRA = Identifier.of("magiccodex", "ui_extra_bold");   // jamsil_extrabold: the boldest registered face
    static final int CREAM = 0xFFF4E7C6, GOLD = 0xFFE9C878, TEXT = 0xFFE4E9EF, MUTED = 0xFFA9B6C8, DIM = 0xFF6E7D93, DARK = 0xFF3A2A10, WARN = 0xFFFF9E9E;
    static final int[] GRADE = FishingArt.GRADE;
    static final String[] GRADES = {"일반", "고급", "유일", "전설"};
    private static final Map<Identifier, float[]> SIZES = new HashMap<>();

    // ---- layout: measured on the approved references (sale / lock screens, 1671x941), not the preview guide
    static final float FRAME_X = 594, FRAME_Y = 31, FRAME_W = 1034, FRAME_H = 877;
    static final float TAB_Y = 192, TAB_W = 170, TAB_H = 44, TAB_SALE_X = 632, TAB_LOCK_X = 818;
    static final float CLOSE_X = 1550, CLOSE_Y = 101, CLOSE_S = 40;
    // title between the frame's laurels (measured on shop_frame_full.png at 594,31,1034,877: inner edges x 936 / 1287,
    // leaves y 118..195, mirror axis x 1111.5)
    static final float TITLE_X = 1111.5f, TITLE_Y = 154, TITLE_MAX_W = 300, TITLE_SIZE = 44;
    static final float BAL_X = 1116, BAL_W = 474;
    static final float ALL_X = 1213, ALL_W = 175, CLEAR_X = 1406, CLEAR_W = 178, BTN_Y = 253, BTN_H = 39;
    static final float ROW_X = 628, ROW_Y = 322, ROW_W = 957, ROW_H = 96, ROW_PITCH = 123;
    static final float FOOT_X = 628, FOOT_Y = 821, FOOT_W = 961, FOOT_H = 72;
    static final float SELL_X = 1322, SELL_Y = 830, SELL_W = 250, SELL_H = 54;
    static final float SEARCH_X = 632, SEARCH_Y = 254, SEARCH_W = 706, SEARCH_H = 46, FILTER_X = 1360, FILTER_W = 226;
    static final float GRID_X = 628, GRID_Y = 352, CARD_W = 220, CARD_H = 126, PITCH_X = 243, PITCH_Y = 151;
    static final int COLS = 4, ROWS = 3;
    // between the grid (right edge 1577) and the frame's inner double line (x 1596..1607)
    static final float BAR_X = 1587, BAR_Y = 345, BAR_H = 445;
    static final float COUNT_Y = 330, COUNT_RIGHT = 1577;
    static final float CLOSE_BTN_X = 1196, APPLY_X = 1393, FOOT_BTN_Y = 833, FOOT_BTN_W = 177, FOOT_BTN_H = 49;
    static final float PLATE_X = 86, PLATE_Y = 735, PLATE_W = 412, PLATE_H = 412f * 794 / 1707;

    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private State state;
    private List<Species> species = List.of();
    private boolean lockTab, lockedOnly, busy;
    private final boolean[] checked = {true, true, true, true};
    private final Set<String> pending = new HashSet<>();     // lock tab edits until 적용
    private boolean dirty;
    /** Lock set sent with 적용 and not yet confirmed by the server's state (null = nothing in flight). */
    private Set<String> savingLocks;
    private long savingAt, savedAt;
    private int scroll;
    private TextFieldWidget search;
    private String query = "", notice = "";
    private long noticeAt, pendingOp, pendingAt;
    private boolean mouseDown;

    FishShopScreen() { super(Text.literal("물고기 판매소")); }

    @Override protected void init() {
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 20, Text.literal("물고기 이름 검색"));
        search.setMaxLength(16); search.setText(query); search.setDrawsBackground(false);
        search.setChangedListener(v -> { query = v; scroll = 0; });
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}
    @Override public void removed() { FishShopClient.close(); }

    void onState(State s) {
        state = s;
        if (!s.species().isEmpty()) species = s.species();
        if (savingLocks != null) {
            if (new HashSet<>(s.locks()).equals(savingLocks)) {
                // server ack: the stored locks are exactly what we sent ("저장됨" only now)
                boolean editedSince = !pending.equals(savingLocks);
                savingLocks = null; dirty = editedSince; if (!editedSince) savedAt = Util.getMeasuringTimeMs();
            } else if (!s.notice().isBlank()) savingLocks = null; // failure notice: keep the edits, still unsaved
        }
        if (!dirty && savingLocks == null) { pending.clear(); pending.addAll(s.locks()); }
        if (!s.notice().isBlank()) say(s.notice());
    }
    void onResult(Result r) {
        if (r.operation() != pendingOp) return;
        busy = false; pendingOp = 0;
        say(r.notice());
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(r.ok() ? SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), r.ok() ? 1.2f : .7f, .5f));
    }
    private void say(String s) { notice = s; noticeAt = Util.getMeasuringTimeMs(); }

    @Override public void tick() {
        if (busy && Util.getMeasuringTimeMs() - pendingAt > 10000) { busy = false; pendingOp = 0; say("서버 응답이 늦어요. 다시 시도해 주세요."); FishShopClient.query(); }
        if (savingLocks != null && Util.getMeasuringTimeMs() - savingAt > 10000) { savingLocks = null; say("잠금 저장 응답이 늦어요. 「적용」을 다시 눌러 주세요."); FishShopClient.query(); }
    }

    // ------------------------------------------------------------------ model

    private int selectedCount() { int n = 0; if (state != null) for (int g = 0; g < 4; g++) if (checked[g]) n += state.grades().get(g).count(); return n; }
    private long selectedTotal() { long t = 0; if (state != null) for (int g = 0; g < 4; g++) if (checked[g]) t += state.grades().get(g).total(); return t; }
    private int mask() { int m = 0; for (int g = 0; g < 4; g++) if (checked[g]) m |= 1 << g; return m; }
    private boolean allChecked() { return checked[0] && checked[1] && checked[2] && checked[3]; }

    private List<Species> cards() {
        String q = query.strip();
        var out = new ArrayList<Species>();
        for (var s : species) {
            if (!q.isEmpty() && !s.name().contains(q)) continue;
            if (lockedOnly && !pending.contains(s.id())) continue;
            out.add(s);
        }
        return out;
    }
    private int maxScroll() { return Math.max(0, (cards().size() + COLS - 1) / COLS - ROWS); }

    static String money(long v) { return String.format(Locale.ROOT, "%,d", v); }
    static String money(String raw) {
        try { return String.format(Locale.ROOT, "%,d", new java.math.BigDecimal(raw).setScale(0, java.math.RoundingMode.DOWN).longValueExact()); }
        catch (Exception e) { return raw; }
    }

    // ------------------------------------------------------------------ input

    private CodexLayout layout() { return CodexLayout.fit(width, height); }
    private static boolean in(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, .25f)); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseDown = true;
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button);
        if (in(mx, my, CLOSE_X, CLOSE_Y, CLOSE_S, CLOSE_S)) { close(); return true; }
        if (in(mx, my, TAB_SALE_X, TAB_Y, TAB_W, TAB_H) && lockTab) {
            // unsaved lock edits are applied automatically; selling waits for the server to confirm them
            if (dirty && savingLocks == null && applyLocks()) say("바뀐 잠금을 저장하고 있어요.");
            lockTab = false; search.setFocused(false); click(); return true;
        }
        if (in(mx, my, TAB_LOCK_X, TAB_Y, TAB_W, TAB_H) && !lockTab) { lockTab = true; scroll = 0; click(); return true; }
        if (!lockTab) {
            if (in(mx, my, ALL_X, BTN_Y, ALL_W, BTN_H)) { Arrays.fill(checked, true); click(); return true; }
            if (in(mx, my, CLEAR_X, BTN_Y, CLEAR_W, BTN_H)) { Arrays.fill(checked, false); click(); return true; }
            for (int g = 0; g < 4; g++) if (in(mx, my, ROW_X, ROW_Y + g * ROW_PITCH, ROW_W, ROW_H)) { checked[g] = !checked[g]; click(); return true; }
            if (in(mx, my, SELL_X, SELL_Y, SELL_W, SELL_H)) { sell(); return true; }
        } else {
            boolean searchHit = in(mx, my, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H);
            search.setFocused(searchHit);
            if (searchHit) return true;
            if (in(mx, my, FILTER_X, SEARCH_Y, FILTER_W, SEARCH_H)) { lockedOnly = !lockedOnly; scroll = 0; click(); return true; }
            var list = cards();
            for (int i = 0; i < COLS * ROWS; i++) {
                int index = scroll * COLS + i;
                if (index >= list.size()) break;
                float x = GRID_X + (i % COLS) * PITCH_X, y = GRID_Y + (i / COLS) * PITCH_Y;
                if (in(mx, my, x, y, CARD_W, CARD_H)) {
                    String id = list.get(index).id();
                    if (!pending.remove(id)) pending.add(id);
                    dirty = true; click(); return true;
                }
            }
            if (in(mx, my, BAR_X - 10, BAR_Y, 22, BAR_H)) { scroll = Math.round((float) ((my - BAR_Y) / BAR_H) * maxScroll()); return true; }
            if (in(mx, my, CLOSE_BTN_X, FOOT_BTN_Y, FOOT_BTN_W, FOOT_BTN_H)) { close(); return true; }
            if (in(mx, my, APPLY_X, FOOT_BTN_Y, FOOT_BTN_W, FOOT_BTN_H)) {
                if (dirty && savingLocks == null && applyLocks()) click();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { mouseDown = false; return super.mouseReleased(mouseX, mouseY, button); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (lockTab) scroll = Math.clamp(scroll - (int) Math.signum(v), 0, maxScroll());
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, mods);
        if (search.isFocused()) { search.setFocused(false); return true; }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char chr, int mods) { return search.isFocused() ? search.charTyped(chr, mods) : super.charTyped(chr, mods); }

    /** Sends the whole lock set; it counts as saved only when a state with exactly these locks comes back. */
    private boolean applyLocks() {
        if (!FishShopClient.locks(pending)) { say("서버에 연결되어 있지 않아요."); return false; }
        savingLocks = Set.copyOf(pending); savingAt = Util.getMeasuringTimeMs();
        return true;
    }
    private void sell() {
        if (busy || state == null) return;
        if (savingLocks != null) { say("잠금을 저장하는 중이에요. 저장된 뒤 판매해 주세요."); return; }
        if (dirty) { say("바뀐 잠금이 아직 저장되지 않았어요. 잠금 설정에서 「적용」을 눌러 주세요."); return; }
        if (selectedCount() == 0) { say("판매할 물고기가 없어요."); return; }
        long op = FishShopClient.sell(mask(), selectedTotal());
        if (op == 0) { say("서버에 연결되어 있지 않아요."); return; }
        busy = true; pendingOp = op; pendingAt = Util.getMeasuringTimeMs();
        click();
    }

    // ------------------------------------------------------------------ drawing helpers

    private void image(DrawContext c, String name, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint) {
        int x0 = Math.round(x), y0 = Math.round(y);
        int w0 = Math.round(x + w) - x0, h0 = Math.round(y + h) - y0;
        if (w0 <= 0 || h0 <= 0 || sw <= 0 || sh <= 0) return;
        UiResources.images().drawTexture(c, Identifier.of("magiccodex", ROOT + name + ".png"), x0, y0, u, v, w0, h0, sw, sh, tw, th, tint);
    }
    private void whole(DrawContext c, String name, int tw, int th, float x, float y, float w, float h, int tint) {
        image(c, name, x, y, w, h, 0, 0, tw, th, tw, th, tint);
    }
    /** Nine-slice: source insets [l,t,r,b] drawn at scale min(w/tw, h/th) so ornaments keep their shape. */
    private void nine(DrawContext c, String name, int tw, int th, int[] in, float x, float y, float w, float h, int tint) {
        float k = Math.min(w / tw, h / th);
        float l = in[0] * k, t = in[1] * k, r = in[2] * k, b = in[3] * k;
        int[] sx = {0, in[0], tw - in[2], tw}; int[] sy = {0, in[1], th - in[3], th};
        float[] dx = {x, x + l, x + w - r, x + w}; float[] dy = {y, y + t, y + h - b, y + h};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            image(c, name, dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row], sx[col], sy[row], sx[col + 1] - sx[col], sy[row + 1] - sy[row], tw, th, tint);
    }
    private static void text(DrawContext c, String v, float x, float y, float size, int color, Identifier face, boolean centered) {
        if (v != null && !v.isBlank()) UiResources.text().draw(c, v, x, y, size, color, face, centered);
    }
    private static float width(String v, float size, Identifier face) { return UiResources.text().width(v, size, face); }
    private static void right(DrawContext c, String v, float right, float y, float size, int color, Identifier face) {
        text(c, v, right - width(v, size, face), y, size, color, face, false);
    }
    private static float fit(String v, float size, Identifier face, float max) { float w = width(v, size, face); return w <= max ? size : Math.max(10, size * max / w); }
    private static int[] i(int... v) { return v; }

    private String hover(double mx, double my, float x, float y, float w, float h) {
        return in(mx, my, x, y, w, h) ? mouseDown ? "_pressed" : "_hover" : "";
    }
    private void goldButton(DrawContext c, double mx, double my, float x, float y, float w, float h, String label, boolean enabled) {
        String s = enabled ? hover(mx, my, x, y, w, h) : "_disabled";
        nine(c, "gold_button" + s, 1793, 468, i(180, 85, 180, 85), x, y, w, h, 0xFFFFFFFF);
        text(c, label, x + w / 2, y + h / 2 + 1, fit(label, 21, SECTION, w - 60), enabled ? DARK : 0xFF6A5A40, SECTION, true);
    }
    private void navyButton(DrawContext c, double mx, double my, float x, float y, float w, float h, String label) {
        nine(c, "navy_button" + hover(mx, my, x, y, w, h), 712, 672, i(85, 85, 85, 85), x, y, w, h, 0xFFFFFFFF);
        text(c, label, x + w / 2, y + h / 2 + 1, fit(label, 21, SECTION, w - 30), CREAM, SECTION, true);
    }

    // ------------------------------------------------------------------ render

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var type = UiResources.text();
        images.beginFrame(); type.beginFrame();
        c.fill(0, 0, width, height, 0x66050A12);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            npc(c, (float) l.localY(height));
            whole(c, "shop_frame_full", 1318, 1118, FRAME_X, FRAME_Y, FRAME_W, FRAME_H, 0xFFFFFFFF);
            String title = state == null || state.title().isBlank() ? "물고기 판매소" : state.title();
            text(c, title, TITLE_X, TITLE_Y, fit(title, TITLE_SIZE, EXTRA, TITLE_MAX_W), CREAM, EXTRA, true);
            whole(c, "close_button" + hover(mx, my, CLOSE_X, CLOSE_Y, CLOSE_S, CLOSE_S), 39, 39, CLOSE_X, CLOSE_Y, CLOSE_S, CLOSE_S, 0xFFFFFFFF);
            tab(c, mx, my, TAB_SALE_X, "판매", !lockTab);
            tab(c, mx, my, TAB_LOCK_X, "잠금 설정", lockTab);
            if (state == null) text(c, "불러오는 중…", 1105, 520, 22, MUTED, BODY, true);
            else if (lockTab) lockTab(c, mx, my);
            else saleTab(c, mx, my);
            long age = Util.getMeasuringTimeMs() - noticeAt;
            if (!notice.isBlank() && age < 4500) {
                float a = age > 4000 ? (4500 - age) / 500f : 1;
                float s = fit(notice, 18, BODY, 900), w = width(notice, s, BODY) + 40;
                // between the last row / grid line and the footer
                FishingArt.card(c, 1105 - w / 2, 789, w, 30, FishingArt.argb(.88f * a, 0x0A1428), FishingArt.argb(.7f * a, 0xE9C878), 1.2f);
                text(c, notice, 1105, 804, s, FishingArt.argb(a, 0xF4E7C6), BODY, true);
            }
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    private void tab(DrawContext c, double mx, double my, float x, String label, boolean selected) {
        String s = hover(mx, my, x, TAB_Y, TAB_W, TAB_H);
        if (selected) nine(c, "tab_selected" + s, 190, 64, i(21, 21, 21, 21), x, TAB_Y, TAB_W, TAB_H, 0xFFFFFFFF);
        else nine(c, "tab_idle" + s, 180, 62, i(20, 20, 20, 20), x, TAB_Y, TAB_W, TAB_H, 0xFFFFFFFF);
        text(c, label, x + TAB_W / 2, TAB_Y + TAB_H / 2 + 1, 23, selected ? DARK : CREAM, SECTION, true);
    }

    private void npc(DrawContext c, float screenBottom) {
        Identifier id = portrait();
        if (id != null) {
            float[] s = size(id);
            float aspect = s[0] / s[1];
            float h = Math.min(1000, 640 / Math.max(.1f, aspect)), w = h * aspect;
            float bottom = Math.max(941, screenBottom);
            int x0 = Math.round(300 - w / 2), y0 = Math.round(bottom - h);
            UiResources.images().drawTexture(c, id, x0, y0, 0, 0, Math.round(w), Math.round(h), (int) s[0], (int) s[1], (int) s[0], (int) s[1], 0xFFFFFFFF);
        }
        whole(c, "npc_nameplate", 1707, 794, PLATE_X, PLATE_Y, PLATE_W, PLATE_H, 0xFFFFFFFF);
        float cx = PLATE_X + PLATE_W / 2;
        String role = state == null ? "물고기 매입상" : state.role(), name = state == null ? "" : state.npcName();
        // reference: role above the divider line, the name below it
        text(c, role, cx, PLATE_Y + 73, fit(role, 18, LABEL, 220), GOLD, LABEL, true);
        text(c, name, cx, PLATE_Y + 121, fit(name, 40, SECTION, 260), 0xFFFFFFFF, SECTION, true);
    }
    private Identifier portrait() {
        if (state == null || state.portrait().isBlank() || client == null) return null;
        var id = Identifier.tryParse("magiccodex:textures/gui/dialogue/" + state.portrait() + ".png");
        return id != null && client.getResourceManager().getResource(id).isPresent() ? id : null;
    }
    private float[] size(Identifier id) {
        return SIZES.computeIfAbsent(id, k -> {
            try (InputStream in = client.getResourceManager().getResourceOrThrow(k).getInputStream()) {
                byte[] h = in.readNBytes(24);
                if (h.length < 24 || h[12] != 'I' || h[13] != 'H' || h[14] != 'D' || h[15] != 'R') return new float[]{1024, 1536};
                int w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
                int t = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
                return w > 0 && t > 0 ? new float[]{w, t} : new float[]{1024, 1536};
            } catch (Exception e) { return new float[]{1024, 1536}; }
        });
    }

    // ---- 판매

    private void saleTab(DrawContext c, double mx, double my) {
        float bcy = TAB_Y + TAB_H / 2 + 1;
        nine(c, "balance_field", 1958, 262, i(90, 55, 90, 55), BAL_X, TAB_Y, BAL_W, TAB_H, 0xFFFFFFFF);
        whole(c, "coin", 75, 77, BAL_X + 20, bcy - 14, 27, 28, 0xFFFFFFFF);
        text(c, "보유 골드", BAL_X + 57, bcy, 19, CREAM, BODY, false);
        right(c, state.balance().isBlank() ? "-" : money(state.balance()) + " G", BAL_X + BAL_W - 22, bcy, 21, CREAM, BODY);
        int locks = state.locks().size();
        if (locks > 0) {   // left end of the button row, clear of every line / border
            whole(c, "lock_closed_gold", 128, 160, 640, 262, 17, 21, 0xFFFFFFFF);
            text(c, "잠금 " + locks + "종 제외", 664, 273, 17, 0xFFD9C8A0, BODY, false);
        }
        goldButton(c, mx, my, ALL_X, BTN_Y, ALL_W, BTN_H, "전체 선택", true);
        navyButton(c, mx, my, CLEAR_X, BTN_Y, CLEAR_W, BTN_H, "선택 해제");
        for (int g = 0; g < 4; g++) row(c, mx, my, g);
        // footer
        nine(c, "footer_panel", 1328, 154, i(60, 45, 60, 45), FOOT_X, FOOT_Y, FOOT_W, FOOT_H, 0xFFFFFFFF);
        float cy = FOOT_Y + FOOT_H / 2 + 1;
        text(c, "선택", 660, cy, 19, TEXT, BODY, false);
        text(c, selectedCount() + "마리", 718, cy, fit(selectedCount() + "마리", 30, SECTION, 132), CREAM, SECTION, false);
        FishingArt.vline(c, 862, cy - 21, cy + 21, 0x70E9C878);
        text(c, "예상 판매금", 877, cy, 19, TEXT, BODY, false);
        whole(c, "coin", 75, 77, 1040, cy - 15, 28, 29, 0xFFFFFFFF);
        String total = money(selectedTotal()) + " G";
        text(c, total, 1085, cy, fit(total, 30, SECTION, 220), CREAM, SECTION, false);
        int count = selectedCount();
        goldButton(c, mx, my, SELL_X, SELL_Y, SELL_W, SELL_H, busy ? "판매 중…" : allChecked() ? "전체 판매" : "선택 판매", count > 0 && !busy);
    }

    private void row(DrawContext c, double mx, double my, int g) {
        float y = ROW_Y + g * ROW_PITCH, cy = y + ROW_H / 2;
        var grade = state.grades().get(g);
        boolean over = in(mx, my, ROW_X, y, ROW_W, ROW_H);
        String panel = checked[g] ? "item_row_selected" : over ? "item_row_hover" : "item_row";
        nine(c, panel, 2080, 306, i(65, 60, 65, 60), ROW_X, y, ROW_W, ROW_H, 0xFFFFFFFF);
        // the most valuable fish of this grade, fitted by its silhouette inside the row's inner border (y 8..88) with margin
        if (grade.sample().isEmpty()) FishingArt.fishFit(c, "", 0, ROW_X + 143, cy, 170, 52, 0x40000000, false);
        else FishingArt.fishFit(c, grade.sample(), 0, ROW_X + 143, cy, 190, 60, 0xFFFFFFFF, false);
        text(c, GRADES[g], ROW_X + 305, y + 28, 28, GRADE[g], SECTION, false);
        text(c, "판매 가능 " + grade.count() + "마리", ROW_X + 307, y + 68, 18, TEXT, BODY, false);
        boolean any = grade.count() > 0;
        whole(c, "coin", 75, 77, ROW_X + 623, cy - 14, 27, 28, any ? 0xFFFFFFFF : 0xFF8890A0);
        right(c, money(grade.total()) + " G", ROW_X + 854, cy + 4, 25, any ? CREAM : DIM, BODY);
        float bx = ROW_X + 896, by = cy - 21;
        whole(c, checked[g] ? "checkbox_frame_selected" : "checkbox_frame_empty", 56, 58, bx, by, 42, 43, 0xFFFFFFFF);
        if (checked[g]) whole(c, "check_selected_gold", 160, 144, bx + 5, by + 6, 32, 29, 0xFFFFFFFF);
    }

    // ---- 잠금 설정

    private void lockTab(DrawContext c, double mx, double my) {
        float scy = SEARCH_Y + SEARCH_H / 2 + 1;
        nine(c, "search_field", 2039, 266, i(75, 55, 75, 55), SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H, 0xFFFFFFFF);
        whole(c, "search", 128, 128, SEARCH_X + 17, scy - 12, 24, 24, 0xFFFFFFFF);
        boolean focused = search.isFocused();
        String shown = query.isEmpty() && !focused ? "물고기 이름 검색" : query + (focused && Util.getMeasuringTimeMs() / 500 % 2 == 0 ? "|" : "");
        text(c, shown, SEARCH_X + 58, scy, 20, query.isEmpty() && !focused ? 0xFF8E9BB0 : TEXT, BODY, false);
        nine(c, "search_field", 2039, 266, i(75, 55, 75, 55), FILTER_X, SEARCH_Y, FILTER_W, SEARCH_H, 0xFFFFFFFF);
        float tx = FILTER_X + 12, ty = scy - 16;
        whole(c, lockedOnly ? "filter_toggle_track_on" : "filter_toggle_track_off", 78, 40, tx, ty, 63, 32, 0xFFFFFFFF);
        whole(c, "filter_toggle_knob", 40, 40, lockedOnly ? tx + 31 : tx, ty, 32, 32, 0xFFFFFFFF);
        text(c, "잠금만 보기", FILTER_X + 87, scy, 20, CREAM, BODY, false);
        var list = cards();
        // "잡은 물고기 N종 ───── 🔒 잠금 n종" (the list only holds caught species)
        String count = (lockedOnly ? "잠금 " : "잡은 물고기 ") + list.size() + "종", locks = "잠금 " + pending.size() + "종";
        text(c, count, 636, COUNT_Y, 18, TEXT, BODY, false);
        float lw = width(locks, 18, LABEL), lx = COUNT_RIGHT - lw;
        text(c, locks, lx, COUNT_Y, 18, GOLD, LABEL, false);
        whole(c, "lock_closed_gold", 128, 160, lx - 20, COUNT_Y - 9, 14, 17, 0xFFFFFFFF);
        FishingArt.hline(c, 636 + width(count, 18, BODY) + 16, lx - 32, COUNT_Y, 0x40E9C878);
        scroll = Math.clamp(scroll, 0, maxScroll());
        for (int k = 0; k < COLS * ROWS; k++) {
            int index = scroll * COLS + k;
            if (index >= list.size()) break;
            card(c, mx, my, list.get(index), GRID_X + (k % COLS) * PITCH_X, GRID_Y + (k / COLS) * PITCH_Y);
        }
        if (list.isEmpty()) text(c, species.isEmpty() ? "아직 잡은 물고기가 없어요." : lockedOnly ? "잠긴 물고기가 없어요." : "검색 결과가 없어요.", 1105, 560, 20, MUTED, BODY, true);
        // scrollbar (code-drawn, thin like the reference)
        FishingArt.rect(c, BAR_X - 1, BAR_Y, 2, BAR_H, 0x40E9C878);
        int max = maxScroll();
        float thumb = Math.max(60, BAR_H * ROWS / Math.max(ROWS, (list.size() + COLS - 1) / COLS));
        float ty0 = BAR_Y + (max == 0 ? 0 : (BAR_H - thumb) * scroll / max);
        FishingArt.rect(c, BAR_X - 2.5f, ty0, 5, thumb, 0xC0E9C878);
        // footer
        nine(c, "footer_panel", 1328, 154, i(60, 45, 60, 45), FOOT_X, FOOT_Y, FOOT_W, FOOT_H, 0xFFFFFFFF);
        float cy = FOOT_Y + FOOT_H / 2 + 1;
        whole(c, "lock_closed_gold", 128, 160, 642, cy - 13, 21, 27, 0xFFFFFFFF);
        boolean saving = savingLocks != null, saved = !dirty && !saving && savedAt != 0 && Util.getMeasuringTimeMs() - savedAt < 4000;
        String footer = saving ? "잠금을 저장하는 중…" : dirty ? "바뀐 잠금은 「적용」을 눌러야 저장돼요" : saved ? "저장됨 · 잠긴 종류는 판매에서 제외됩니다" : "잠긴 종류는 판매에서 제외됩니다";
        text(c, footer, 682, cy, 19, saving || dirty ? GOLD : TEXT, BODY, false);
        navyButton(c, mx, my, CLOSE_BTN_X, FOOT_BTN_Y, FOOT_BTN_W, FOOT_BTN_H, "닫기");
        goldButton(c, mx, my, APPLY_X, FOOT_BTN_Y, FOOT_BTN_W, FOOT_BTN_H, saving ? "저장 중" : "적용", dirty && !saving);
    }

    private void card(DrawContext c, double mx, double my, Species s, float x, float y) {
        boolean locked = pending.contains(s.id()), over = in(mx, my, x, y, CARD_W, CARD_H);
        nine(c, locked ? "species_card_locked" : over ? "species_card_hover" : "species_card_default", 960, 492, i(72, 72, 72, 72), x, y, CARD_W, CARD_H, 0xFFFFFFFF);
        // card art border: x 7..213, y 11..117 (nine-slice of species_card_*); everything stays inside it
        FishingArt.fishFit(c, s.id(), 0, x + CARD_W / 2, y + 44, 150, 50, 0xFFFFFFFF, false);
        text(c, s.name(), x + 16, y + 84, fit(s.name(), 18, LABEL, CARD_W - 112), TEXT, LABEL, false);
        text(c, GRADES[s.grade()], x + 17, y + 103, 12, s.grade() == 0 ? MUTED : GRADE[s.grade()], BODY, false);
        if (locked) {
            float bw = 76, bh = 30, bx = x + 205 - bw, by = y + 111 - bh;
            nine(c, "lock_button_frame_gold", 94, 54, i(9, 9, 9, 9), bx, by, bw, bh, 0xFFFFFFFF);
            whole(c, "lock_closed_gold", 128, 160, bx + 10, by + 5, 16, 20, 0xFFFFFFFF);
            text(c, "잠김", bx + 32, by + bh / 2 + 1, 16, GOLD, BODY, false);
        } else {
            float b = 31, bx = x + 205 - b, by = y + 111 - b;
            nine(c, "lock_button_frame_neutral", 52, 54, i(9, 9, 9, 9), bx, by, b, b, 0xFFFFFFFF);
            whole(c, "lock_open_blue", 128, 160, bx + 8, by + 5, 15, 19, 0xFFFFFFFF);
        }
    }
}
