package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.TravelProtocol;
import school.magiccodex.protocol.TravelProtocol.*;

/**
 * 지도 이동 창 (2026-10-23, owner spec + approved PNG set in textures/gui/travel).
 *
 * A compact square frame (main_frame.png, its centre is transparent and the map is drawn behind it) with the tabs
 * 학교 / 야생1 / 야생2 (text drawn here, never baked). The place list starts folded; the toggle on the frame's right edge
 * opens it OUTSIDE the frame to the right. One scale is chosen for the open arrangement, so opening never resizes or
 * squeezes the map — the frame just slides left to keep everything on screen (TravelMapMath.place).
 *
 * Picking a point on the map or in the list selects it in both, centres it and zooms in smoothly; only 이동하기 moves.
 * Wheel / + / − zoom (around the cursor), drag pans, the house button returns to the whole map. Markers: current
 * position (arrow, turned to where the player looks), public point (glowing ring), personal point (pin), 미발견 (lock);
 * points that can't be used right now are dimmed and say why.
 *
 * Map sources: image tabs draw the configured texture (replace the PNG and travel.yml numbers, no code change); a
 * missing image or a terrain tab (not implemented yet) shows a coordinate grid with the points on it.
 */
final class TravelScreen extends Screen {
    static final String ROOT = "textures/gui/travel/";
    static final Identifier SECTION = Identifier.of("magiccodex", "section"), BODY = Identifier.of("magiccodex", "body"),
            LABEL = Identifier.of("magiccodex", "label");
    static final int WHITE = 0xFFFFFFFF, TEXT = 0xFFDCE4EE, MUTED = 0xFF9FB0C4, DIM = 0xFF6F7C8C, GOLD = 0xFFE9D3A0, WARN = 0xFFFFB0A0, LINE = 0xFFC9B48A;
    private static Identifier tex(String name) { return Identifier.of("magiccodex", ROOT + name + ".png"); }
    /** Trimmed PNG sizes (padding removed, pixels unchanged). */
    private record Art(String name, int w, int h) { Identifier id() { return tex(name); } }
    static final Art FRAME = new Art("main_frame", 1254, 1254), PANEL = new Art("drawer_panel", 621, 1809),
            EXPAND = new Art("drawer_expand", 131, 403), COLLAPSE = new Art("drawer_collapse", 92, 272), BUTTON = new Art("button_blank", 1793, 284),
            CLOSE = new Art("icon_close", 406, 407), HOME = new Art("icon_home", 528, 489), PLUS = new Art("icon_plus", 288, 283), MINUS = new Art("icon_minus", 394, 22),
            PLAYER = new Art("player_position", 378, 388), ACTIVE = new Art("waypoint_active", 339, 343), LOCKED = new Art("waypoint_locked", 468, 591),
            PERSONAL = new Art("waypoint_personal", 441, 577);

    // ---- from the server
    private State state;
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private final List<Point> points = new ArrayList<>();
    private boolean loaded;

    // ---- view
    private String tab = "";
    private final Map<String, TravelMapMath.Camera> cameras = new HashMap<>();
    private String selected, hovered;
    private boolean listOpen;
    private float list;                 // 0..1 eased slide of the list
    private int scroll;
    private long lastFrame;
    private float time;
    private TravelMapMath.Placement place;
    private double pressX, pressY, lastX, lastY;
    private boolean pressedMap, dragging, mouseDown;

    // ---- requests
    private long pendingOp, pendingAt;
    private int pendingStage = -1;
    private String notice = "";
    private long noticeAt, closeAt;
    private final UiResources.Entrance entrance = new UiResources.Entrance();

    private final long openedAt = Util.getMeasuringTimeMs();
    TravelScreen() { super(Text.literal("지도")); }

    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}
    @Override public void removed() { TravelClient.close(); }

    // ------------------------------------------------------------------ server

    void onState(State s) {
        boolean first = !loaded;
        loaded = true; state = s;
        tabs.clear(); for (Tab t : s.tabs()) tabs.put(t.id(), t);
        points.clear(); points.addAll(s.points());
        if (first || !tabs.containsKey(tab)) {
            tab = !s.player().tab().isEmpty() && tabs.containsKey(s.player().tab()) ? s.player().tab() : s.tabs().getFirst().id();
        }
        for (Tab t : s.tabs()) {
            var cam = cameras.get(t.id());
            double[] e = extent(t);
            if (cam == null) {
                cam = new TravelMapMath.Camera(); cameras.put(t.id(), cam);
                TravelMapMath.home(cam, e[0], e[1]);
                // open on the player when they stand on this tab
                if (t.id().equals(s.player().tab())) {
                    var a = affine(t);
                    cam.tu = a.u(s.player().x(), s.player().z()); cam.tv = a.v(s.player().x(), s.player().z());
                    cam.tzoom = Math.max(cam.tzoom, Math.min(cam.maxZoom, focusZoom(t)));
                    TravelMapMath.clamp(cam, e[0], e[1]);
                }
                cam.snap();
            } else {
                TravelMapMath.Camera fit = new TravelMapMath.Camera(); TravelMapMath.home(fit, e[0], e[1]);
                cam.minZoom = fit.minZoom; cam.maxZoom = fit.maxZoom;
            }
        }
        if (selected != null && point(selected) == null) selected = null;
        // minimap fallback: the key of the world the player stands in, in case its marker frame was missed
        var here = tabs.get(s.player().tab());
        if (here != null && here.map() == TravelProtocol.MAP_TERRAIN && TravelClient.markers.cache().isEmpty()) TravelTerrain.key(here.cacheKey());
        if (!s.notice().isBlank()) say(s.notice());
    }
    void onResult(Result r) {
        if (r.operation() != pendingOp) return;
        pendingStage = r.stage();
        if (!r.notice().isBlank()) say(r.notice());
        switch (r.stage()) {
            case TravelProtocol.STAGE_ACCEPTED -> pendingAt = Util.getMeasuringTimeMs();   // keep waiting: the server switch takes a while
            case TravelProtocol.STAGE_DONE -> { pendingOp = 0; closeAt = Util.getMeasuringTimeMs() + 350; sound(true); }
            default -> { pendingOp = 0; pendingStage = -1; sound(false); }
        }
    }
    private void say(String s) { notice = s; noticeAt = Util.getMeasuringTimeMs(); }
    private boolean busy() { return pendingOp != 0; }

    @Override public void tick() {
        long now = Util.getMeasuringTimeMs();
        // opened by the key before the server answered: give up if it never does (refused: no mod / no shared DB …)
        if (!loaded && now - openedAt > 5000) { close(); return; }
        if (closeAt != 0 && now >= closeAt) { closeAt = 0; close(); return; }
        // a remote move waits for the server switch; a local one answers at once
        long limit = pendingStage == TravelProtocol.STAGE_ACCEPTED ? 55000 : 10000;
        if (busy() && now - pendingAt > limit) { pendingOp = 0; pendingStage = -1; say("서버 응답이 늦어요. 다시 시도해 주세요."); TravelClient.query(); }
    }

    // ------------------------------------------------------------------ model helpers

    private Point point(String id) { for (Point p : points) if (p.id().equals(id)) return p; return null; }
    private Tab current() { return tabs.get(tab); }
    private TravelMapMath.Camera camera() { return cameras.get(tab); }

    /** The image map is used only when its PNG really is in the resource pack (else the grid, in world coordinates). */
    static boolean hasImage(Tab t) {
        if (t.map() != TravelProtocol.MAP_IMAGE || t.imageW() < 1 || t.imageH() < 1) return false;
        Identifier id = Identifier.tryParse(t.texture());
        return id != null && FishingArt.exists(id) && TravelMapMath.Affine.image(t.tlX(), t.tlZ(), t.trX(), t.trZ(), t.blX(), t.blZ(), t.imageW(), t.imageH()).valid();
    }
    static TravelMapMath.Affine affine(Tab t) {
        if (hasImage(t)) {
            var a = TravelMapMath.Affine.image(t.tlX(), t.tlZ(), t.trX(), t.trZ(), t.blX(), t.blZ(), t.imageW(), t.imageH());
            if (a.valid()) return a;
        }
        double[] b = worldBounds(t);
        return TravelMapMath.Affine.world(b[0], b[1], b[2], b[3]);
    }
    /** World tabs: the configured bounds when they are a real area, else the box around the tab's points (at least 512 blocks). */
    private static double[] worldBounds(Tab t) { return BOUNDS.getOrDefault(t.id(), new double[]{-256, -256, 256, 256}); }
    private static final Map<String, double[]> BOUNDS = new HashMap<>();
    private void computeBounds() {
        BOUNDS.clear();
        for (Tab t : tabs.values()) {
            if (t.maxX() - t.minX() <= 200_000 && t.maxZ() - t.minZ() <= 200_000) { BOUNDS.put(t.id(), new double[]{t.minX(), t.minZ(), t.maxX(), t.maxZ()}); continue; }
            double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            boolean any = false;
            for (Point p : points) if (p.tab().equals(t.id())) { any = true; x0 = Math.min(x0, p.x()); z0 = Math.min(z0, p.z()); x1 = Math.max(x1, p.x()); z1 = Math.max(z1, p.z()); }
            if (state != null && state.player().tab().equals(t.id())) { any = true; double x = state.player().x(), z = state.player().z(); x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z); }
            double[] seen = t.map() == TravelProtocol.MAP_TERRAIN ? TravelTerrain.knownBounds(t.cacheKey()) : null;   // explored minimap tiles
            if (seen != null) { any = true; x0 = Math.min(x0, seen[0]); z0 = Math.min(z0, seen[1]); x1 = Math.max(x1, seen[2]); z1 = Math.max(z1, seen[3]); }
            if (!any) { x0 = -256; z0 = -256; x1 = 256; z1 = 256; }
            double cx = (x0 + x1) / 2, cz = (z0 + z1) / 2, half = Math.max(256, Math.max(x1 - x0, z1 - z0) / 2 + 160);
            BOUNDS.put(t.id(), new double[]{cx - half, cz - half, cx + half, cz + half});
        }
    }
    /** Map-space size of a tab (image pixels, or the world bounds in blocks). */
    private double[] extent(Tab t) {
        computeBounds();
        if (hasImage(t)) return new double[]{t.imageW(), t.imageH()};
        double[] b = worldBounds(t);
        return new double[]{b[2] - b[0], b[3] - b[1]};
    }
    /** Zoom used when a point is picked: about 1.6 design px per block (or 2.5x the whole-map zoom for a small image). */
    private double focusZoom(Tab t) {
        var a = affine(t);
        double blocksPerUnit = Math.sqrt(Math.abs(a.det()));
        return Math.max(1.6 * blocksPerUnit, TravelMapMath.fitZoom(extent(t)[0], extent(t)[1]) * 2.5);
    }

    /** List order: personal point first, then tab order, usable before not, then name. */
    private List<Point> rows() {
        var order = new HashMap<String, Integer>(); int i = 0; for (String id : tabs.keySet()) order.put(id, i++);
        var out = new ArrayList<>(points);
        out.sort(Comparator.<Point>comparingInt(p -> p.kind() == TravelProtocol.POINT_PERSONAL ? 0 : 1)
                .thenComparingInt(p -> order.getOrDefault(p.tab(), 99))
                .thenComparingInt(Point::state)
                .thenComparing(Point::name));
        return out;
    }

    // ------------------------------------------------------------------ layout (design units = main_frame.png pixels)

    static final float TAB_Y = 163, TAB_GAP = 190, LEGEND_Y = 90;
    /** The footer band has its own divider line at y 1121 (like the header's at 127): info above it, controls below. */
    static final float INFO_Y = 1093, FOOT_Y = 1154, BTN_W = 300, BTN_H = BTN_W * 284 / 1793f, BTN_X = 1157 - BTN_W - 6;
    static final float ICON = 46, ZOOM_X = 128;
    static final float ROW_H = 58, LIST_TOP = 98, LIST_PAD = 26;
    private float toggleX() { return TravelMapMath.BOX_R + TravelMapMath.TOGGLE_GAP; }
    private float panelX() { return toggleX() + TravelMapMath.TOGGLE_W + TravelMapMath.PANEL_GAP; }
    private static boolean in(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private float tabX(int i) { return 627 + (i - (tabs.size() - 1) / 2f) * TAB_GAP; }
    private int visibleRows() { return Math.max(1, (int) ((TravelMapMath.PANEL_H - LIST_TOP - LIST_PAD) / ROW_H)); }
    private int maxScroll() { return Math.max(0, rows().size() + groupCount() - visibleRows()); }
    private int groupCount() { var seen = new HashSet<String>(); for (Point p : points) seen.add(p.kind() == TravelProtocol.POINT_PERSONAL ? "~" : p.tab()); return seen.size(); }

    // ------------------------------------------------------------------ input

    private double dx(double mouseX) { return place == null ? -1 : place.designX(mouseX); }
    private double dy(double mouseY) { return place == null ? -1 : place.designY(mouseY); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseDown = true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || place == null || !loaded) return super.mouseClicked(mouseX, mouseY, button);
        double mx = dx(mouseX), my = dy(mouseY);
        if (in(mx, my, 1118, 70, 44, 44)) { close(); return true; }
        // tabs
        int i = 0;
        for (String id : tabs.keySet()) {
            float tx = tabX(i++);
            if (in(mx, my, tx - 80, TAB_Y - 26, 160, 52)) { if (!id.equals(tab)) { tab = id; hovered = null; click(); } return true; }
        }
        // list toggle (on the frame's right edge) and the list itself
        if (in(mx, my, toggleX(), (float) TravelMapMath.viewCy() - TravelMapMath.TOGGLE_H / 2, TravelMapMath.TOGGLE_W, TravelMapMath.TOGGLE_H)) { listOpen = !listOpen; click(); return true; }
        if (listOpen && list > .5f && mx >= panelX()) { listClick(mx, my); return true; }
        // footer
        if (in(mx, my, ZOOM_X - ICON / 2, FOOT_Y - ICON / 2, ICON, ICON)) { zoomButton(1 / 1.5); return true; }
        if (in(mx, my, ZOOM_X + 64 - ICON / 2, FOOT_Y - ICON / 2, ICON, ICON)) { zoomButton(1.5); return true; }
        if (in(mx, my, ZOOM_X + 128 - ICON / 2, FOOT_Y - ICON / 2, ICON, ICON)) { homeButton(); return true; }
        if (in(mx, my, BTN_X, FOOT_Y - BTN_H / 2, BTN_W, BTN_H)) { travel(); return true; }
        // the map: select on release (a drag pans instead)
        if (TravelMapMath.insideMap(mx, my)) { pressedMap = true; dragging = false; pressX = lastX = mx; pressY = lastY = my; return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!pressedMap || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        double mx = dx(mouseX), my = dy(mouseY);
        if (!dragging && Math.hypot(mx - pressX, my - pressY) > 6) dragging = true;
        var cam = camera(); var t = current();
        if (dragging && cam != null && t != null) { double[] e = extent(t); TravelMapMath.pan(cam, mx - lastX, my - lastY, e[0], e[1]); }
        lastX = mx; lastY = my;
        return true;
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseDown = false;
        if (pressedMap && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressedMap = false;
            if (!dragging) {
                String hit = pick(dx(mouseX), dy(mouseY));
                if (hit != null) select(hit, false);
                else if (selected != null) { selected = null; click(); }
            }
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        double mx = dx(mouseX), my = dy(mouseY);
        if (listOpen && mx >= panelX()) { scroll = Math.clamp(scroll - (int) Math.signum(v), 0, maxScroll()); return true; }
        var cam = camera(); var t = current();
        if (cam != null && t != null && TravelMapMath.insideMap(mx, my) && v != 0) { double[] e = extent(t); TravelMapMath.zoomAt(cam, mx, my, v > 0 ? 1.25 : 1 / 1.25, e[0], e[1]); }
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && selected != null) { selected = null; return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { travel(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    private void zoomButton(double f) {
        var cam = camera(); var t = current(); if (cam == null || t == null) return;
        double[] e = extent(t); TravelMapMath.zoomAt(cam, TravelMapMath.viewCx(), TravelMapMath.viewCy(), f, e[0], e[1]); click();
    }
    private void homeButton() {
        var cam = camera(); var t = current(); if (cam == null || t == null) return;
        double[] e = extent(t); TravelMapMath.home(cam, e[0], e[1]); click();
    }

    /** Selects a point in both the map and the list: switches tab if needed, centres it and zooms in smoothly. */
    private void select(String id, boolean fromList) {
        Point p = point(id); if (p == null) return;
        selected = id; click();
        if (!p.tab().isEmpty() && tabs.containsKey(p.tab())) {
            tab = p.tab();
            var t = current(); var cam = camera();
            var a = affine(t); double[] e = extent(t);
            TravelMapMath.focus(cam, a.u(p.x(), p.z()), a.v(p.x(), p.z()), focusZoom(t), e[0], e[1]);
        }
        if (!fromList) revealInList(id);
    }
    private void revealInList(String id) {
        int index = rowIndex(id); if (index < 0) return;
        int visible = visibleRows();
        if (index < scroll) scroll = index; else if (index >= scroll + visible) scroll = index - visible + 1;
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private void listClick(double mx, double my) {
        float x = panelX(), top = TravelMapMath.BOX_T + LIST_TOP;
        if (my < top) return;
        int line = scroll + (int) ((my - top) / ROW_H);
        var lines = lines();
        if (line >= 0 && line < lines.size() && lines.get(line) instanceof Point p) select(p.id(), true);
    }

    private void travel() {
        if (busy() || selected == null) return;
        Point p = point(selected);
        if (p == null) return;
        if (!p.travelable()) { say(p.reason().isBlank() ? "지금은 이동할 수 없어요" : p.reason()); sound(false); return; }
        long op = TravelClient.travel(p.id());
        if (op == 0) { say("서버에 연결되어 있지 않아요."); return; }
        pendingOp = op; pendingAt = Util.getMeasuringTimeMs(); pendingStage = -1;
        click();
    }

    /** Point under the cursor on the map (design units), nearest within 24 px; null = none. */
    private String pick(double mx, double my) {
        var t = current(); var cam = camera();
        if (t == null || cam == null || !TravelMapMath.insideMap(mx, my)) return null;
        var a = affine(t);
        String best = null; double bestD = 24 * 24;
        for (Point p : points) {
            if (!p.tab().equals(tab)) continue;
            double x = TravelMapMath.screenX(cam, a.u(p.x(), p.z())), y = TravelMapMath.screenY(cam, a.v(p.x(), p.z())) - (p.kind() == TravelProtocol.POINT_PERSONAL ? 16 : 0);   // only the pin sits above its spot
            double d = (x - mx) * (x - mx) + (y - my) * (y - my);
            if (d < bestD) { bestD = d; best = p.id(); }
        }
        return best;
    }

    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, .25f)); }
    private void sound(boolean ok) { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ok ? SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), ok ? 1.3f : .7f, .45f)); }

    // ------------------------------------------------------------------ drawing helpers

    private static void art(DrawContext c, Art a, float x, float y, float w, float h, int tint) {
        int x0 = Math.round(x), y0 = Math.round(y), w0 = Math.round(x + w) - x0, h0 = Math.round(y + h) - y0;
        if (w0 <= 0 || h0 <= 0 || (tint >>> 24) == 0) return;
        UiResources.images().drawTexture(c, a.id(), x0, y0, 0, 0, w0, h0, a.w(), a.h(), a.w(), a.h(), tint);
    }
    /** Fits the art in a box (keeps its ratio), centred. */
    private static void icon(DrawContext c, Art a, float cx, float cy, float box, int tint) {
        float k = box / Math.max(a.w(), a.h()), w = a.w() * k, h = a.h() * k;
        art(c, a, cx - w / 2, cy - h / 2, w, h, tint);
    }
    private static void text(DrawContext c, String v, float x, float y, float size, int color, Identifier face, boolean centered) {
        if (v != null && !v.isBlank() && (color >>> 24) > 2) UiResources.text().draw(c, v, x, y, size, color, face, centered);
    }
    private static float width(String v, float size, Identifier face) { return UiResources.text().width(v, size, face); }
    private static String cut(String v, float size, Identifier face, float max) { return TravelMapMath.ellipsize(v, max, s -> width(s, size, face)); }
    private static int a(int color, float alpha) { return FishingArt.alpha(color, alpha); }
    private static int white(float alpha) { return FishingArt.argb(alpha, 0xFFFFFF); }
    private static float ease(float t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }

    // ------------------------------------------------------------------ render

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1000f, 0, .05f);
        lastFrame = now; time += dt;
        list += ((listOpen ? 1 : 0) - list) * Math.min(1, dt * 9);
        if (Math.abs(list - (listOpen ? 1 : 0)) < .002f) list = listOpen ? 1 : 0;
        place = TravelMapMath.place(width, height, ease(list));
        var cam = camera();
        if (cam != null) cam.step(dt);
        double mx = dx(mouseX), my = dy(mouseY);
        var images = UiResources.images(); var type = UiResources.text();
        images.beginFrame(); type.beginFrame();
        c.fill(0, 0, width, height, 0x9A030A16);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(place.frameX(), place.frameY(), 0); m.scale(place.scale(), place.scale(), 1);
            hovered = loaded && !dragging ? pick(mx, my) : null;
            map(c, mx, my);
            art(c, FRAME, 0, 0, TravelMapMath.FRAME_W, TravelMapMath.FRAME_H, WHITE);
            header(c, mx, my);
            footer(c, mx, my);
            drawer(c, mx, my);
            noticeLine(c);
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    // ---- the map (behind the frame, clipped to its window)

    private void map(DrawContext c, double mx, double my) {
        // DrawContext.enableScissor applies the current matrix (1.21.4), so the window is given in design units
        int sx0 = (int) TravelMapMath.MAP_L, sy0 = (int) TravelMapMath.MAP_T, sx1 = (int) TravelMapMath.MAP_R, sy1 = (int) TravelMapMath.MAP_B;
        FishingArt.rect(c, TravelMapMath.MAP_L, TravelMapMath.MAP_T, TravelMapMath.MAP_R - TravelMapMath.MAP_L, TravelMapMath.MAP_B - TravelMapMath.MAP_T, 0xFF0B1A2E);
        var t = current(); var cam = camera();
        if (!loaded || t == null || cam == null) { text(c, "불러오는 중…", (float) TravelMapMath.viewCx(), (float) TravelMapMath.viewCy(), 24, MUTED, BODY, true); return; }
        c.enableScissor(sx0, sy0, sx1, sy1);
        try {
            var a = affine(t);
            boolean image = hasImage(t);
            if (image) drawImage(c, t, cam);
            else {
                if (t.map() == TravelProtocol.MAP_TERRAIN) terrain(c, t, cam, a);
                grid(c, t, cam, a);
            }
            // points: locked first (under), then public, personal, selected last
            var order = new ArrayList<Point>();
            for (Point p : points) if (p.tab().equals(tab)) order.add(p);
            order.sort(Comparator.<Point>comparingInt(p -> p.id().equals(selected) ? 3 : p.id().equals(hovered) ? 2 : p.state() == TravelProtocol.LOCKED ? 0 : 1));
            for (Point p : order) marker(c, p, a, cam);
            // the player
            var pos = state.player();
            if (pos.tab().equals(tab)) pings(c, t, a, cam);
            if (pos.tab().equals(tab)) {
                float x = (float) TravelMapMath.screenX(cam, a.u(pos.x(), pos.z())), y = (float) TravelMapMath.screenY(cam, a.v(pos.x(), pos.z()));
                var m = c.getMatrices(); m.push();
                try {
                    m.translate(x, y, 0); m.multiply(new Quaternionf().rotateZ((float) a.heading(pos.yaw())));
                    float pulse = .9f + .1f * (float) Math.sin(time * 3);
                    FishingArt.bloom(c, 0, 0, 26, 0xCFE8FF, .18f * pulse);
                    icon(c, PLAYER, 0, 0, 34, WHITE);
                } finally { m.pop(); }
            }
            if (!image) {
                String note = t.map() == TravelProtocol.MAP_IMAGE ? "지도 그림이 아직 없어요"
                        : t.map() == TravelProtocol.MAP_TERRAIN && TravelTerrain.known(t.cacheKey()).isEmpty() ? "아직 둘러본 곳이 없어요 · 다니면 지도가 그려져요" : "";
                text(c, note, TravelMapMath.MAP_L + 16, TravelMapMath.MAP_B - 22, 17, 0x99A9BCD2, BODY, false);
            }
            if (!t.online()) {
                // e.g. 야생2 "준비 중입니다" (travel.yml servers.wild2.closed-text)
                FishingArt.rect(c, TravelMapMath.MAP_L, TravelMapMath.MAP_T, TravelMapMath.MAP_R - TravelMapMath.MAP_L, TravelMapMath.MAP_B - TravelMapMath.MAP_T, 0x8C040A14);
                String why = t.status().isBlank() ? t.label() + " 서버가 지금 닫혀 있어요" : t.status();
                text(c, cut(why, 26, SECTION, 900), (float) TravelMapMath.viewCx(), (float) TravelMapMath.viewCy(), 26, TEXT, SECTION, true);
            }
        } finally { c.disableScissor(); }
    }

    /** Minimap tiles of this tab (TravelTerrain), drawn 1 unit = 1 block around a nearby whole-block origin. */
    private void terrain(DrawContext c, Tab t, TravelMapMath.Camera cam, TravelMapMath.Affine a) {
        if (t.cacheKey().isBlank()) return;
        double u0 = TravelMapMath.mapU(cam, TravelMapMath.MAP_L), u1 = TravelMapMath.mapU(cam, TravelMapMath.MAP_R);
        double v0 = TravelMapMath.mapV(cam, TravelMapMath.MAP_T), v1 = TravelMapMath.mapV(cam, TravelMapMath.MAP_B);
        double x0 = a.worldX(u0, v0), z0 = a.worldZ(u0, v0), x1 = a.worldX(u1, v1), z1 = a.worldZ(u1, v1);
        int ox = (int) Math.floor((x0 + x1) / 2), oz = (int) Math.floor((z0 + z1) / 2);
        var m = c.getMatrices(); m.push();
        try {
            m.translate((float) TravelMapMath.screenX(cam, a.u(ox, oz)), (float) TravelMapMath.screenY(cam, a.v(ox, oz)), 0);
            m.scale((float) cam.zoom, (float) cam.zoom, 1);
            TravelTerrain.draw(c, t.cacheKey(), Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), ox, oz);
        } finally { m.pop(); }
    }

    private void drawImage(DrawContext c, Tab t, TravelMapMath.Camera cam) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate((float) TravelMapMath.screenX(cam, 0), (float) TravelMapMath.screenY(cam, 0), 0);
            m.scale((float) cam.zoom, (float) cam.zoom, 1);
            UiResources.images().drawTexture(c, Identifier.of(t.texture()), 0, 0, 0, 0, t.imageW(), t.imageH(), t.imageW(), t.imageH(), t.imageW(), t.imageH(), WHITE);
        } finally { m.pop(); }
    }

    /** Thin coordinate grid (16·2^k blocks, at least 70 px apart) with the world origin lines a little stronger. */
    private void grid(DrawContext c, Tab t, TravelMapMath.Camera cam, TravelMapMath.Affine a) {
        double blocksPerPx = Math.sqrt(Math.abs(a.det())) / cam.zoom;
        double step = 16; while (step / blocksPerPx < 70 && step < 1 << 20) step *= 2;
        // visible world range (world tabs: u = x - minX, v = z - minZ)
        double u0 = TravelMapMath.mapU(cam, TravelMapMath.MAP_L), u1 = TravelMapMath.mapU(cam, TravelMapMath.MAP_R);
        double v0 = TravelMapMath.mapV(cam, TravelMapMath.MAP_T), v1 = TravelMapMath.mapV(cam, TravelMapMath.MAP_B);
        double x0 = a.worldX(u0, v0), x1 = a.worldX(u1, v0), z0 = a.worldZ(u0, v0), z1 = a.worldZ(u0, v1);
        for (double x = Math.floor(Math.min(x0, x1) / step) * step; x <= Math.max(x0, x1); x += step) {
            float sx = (float) TravelMapMath.screenX(cam, a.u(x, z0));
            FishingArt.rect(c, sx, TravelMapMath.MAP_T, x == 0 ? 1.6f : 1, TravelMapMath.MAP_B - TravelMapMath.MAP_T, x == 0 ? 0x30C9D6E6 : 0x14C9D6E6);
            text(c, String.valueOf((long) x), sx + 5, TravelMapMath.MAP_T + 14, 13, 0x66A9BCD2, BODY, false);
        }
        for (double z = Math.floor(Math.min(z0, z1) / step) * step; z <= Math.max(z0, z1); z += step) {
            float sy = (float) TravelMapMath.screenY(cam, a.v(x0, z));
            FishingArt.rect(c, TravelMapMath.MAP_L, sy, TravelMapMath.MAP_R - TravelMapMath.MAP_L, z == 0 ? 1.6f : 1, z == 0 ? 0x30C9D6E6 : 0x14C9D6E6);
            text(c, String.valueOf((long) z), TravelMapMath.MAP_L + 6, sy - 9, 13, 0x66A9BCD2, BODY, false);
        }
    }

    /** 이동 못 하는 지점의 홀로그램 빨강 (#53). */
    static final int HOLO = 0xFFFF5A5A, HOLO_TEXT = 0xFFFF9C9C, SPARK = 0xFFF6E7B0;

    /**
     * 지도 표시 (오픈 전 메모 #53, 오너 2026-10-25): 이동할 수 있는 곳은 반짝반짝 빛나고, 이동할 수 없는 곳(미발견·사용 불가)은
     * 빨갛게 빛나는 홀로그램. 이름은 늘 위에 작게 (마우스를 올리거나 고르면 이름표).
     */
    private void marker(DrawContext c, Point p, TravelMapMath.Affine a, TravelMapMath.Camera cam) {
        float x = (float) TravelMapMath.screenX(cam, a.u(p.x(), p.z())), y = (float) TravelMapMath.screenY(cam, a.v(p.x(), p.z()));
        if (x < TravelMapMath.MAP_L - 40 || x > TravelMapMath.MAP_R + 40 || y < TravelMapMath.MAP_T - 40 || y > TravelMapMath.MAP_B + 60) return;
        boolean on = p.id().equals(selected), over = p.id().equals(hovered);
        boolean usable = p.travelable(), personal = p.kind() == TravelProtocol.POINT_PERSONAL;
        float grow = on ? 1.25f : over ? 1.12f : 1;
        float phase = (float) (time + (p.x() * 0.37 + p.z() * 0.71) % 7);
        if (on) { HudMesh.arc(c, x, y, 20 * grow, 1.4f, 0, (float) (Math.PI * 2), usable ? 0xCCE9D3A0 : 0xCCFF7A7A); }
        float iconY = personal ? y - 16 * grow : y;     // pin: its tip on the spot
        Art art = personal ? PERSONAL : ACTIVE;
        float box = 34 * grow;
        if (usable) {
            float pulse = .5f + .5f * (float) Math.sin(phase * 3.2);
            FishingArt.bloom(c, x, iconY, 30 * grow, 0xFFE6A8, .22f + .20f * pulse);
            FishingArt.bloom(c, x, iconY, 16 * grow, 0xFFFFFF, .18f + .16f * pulse);
            icon(c, art, x, iconY, box, WHITE);
            sparkles(c, x, iconY, 20 * grow, phase);
        } else hologram(c, art, x, iconY, box, phase);
        float top = iconY - (personal ? 20 : 16) * grow;
        if (on || over) label(c, p, x, top - 16);
        else {
            String name = cut(p.name(), 14, LABEL, 200);
            FishingArt.outlined(c, name, x, top - 8, 14, 0xE0000000, LABEL);   // 검은 테두리 먼저, 글씨는 위에
            text(c, name, x, top - 8, 14, usable ? 0xFFFFF4D6 : HOLO_TEXT, LABEL, true);
        }
    }

    /** 반짝이: 아이콘 둘레를 도는 별 3개, 하나씩 켜졌다 꺼짐. */
    private static void sparkles(DrawContext c, float x, float y, float r, float phase) {
        for (int i = 0; i < 3; i++) {
            float t = phase * 0.9f + i * 2.1f;
            float tw = (float) Math.max(0, Math.sin(t * 2.3));          // 반짝 (0 → 1 → 0)
            if (tw < .05f) continue;
            float ang = (float) (t * 0.6 + i * Math.PI * 2 / 3);
            float sx = x + (float) Math.cos(ang) * r, sy = y + (float) Math.sin(ang) * r * .85f;
            float size = 3.5f + 4.5f * tw;
            FishingArt.bloom(c, sx, sy, size * 1.8f, 0xFFF0C0, .35f * tw);
            HudMesh.star(c, sx, sy, size, FishingArt.argb(.95f * tw, SPARK));
            var m = c.getMatrices(); m.push();
            try {
                m.translate(sx, sy, 0); m.multiply(new Quaternionf().rotateZ((float) (Math.PI / 2)));
                HudMesh.star(c, 0, 0, size * .7f, FishingArt.argb(.8f * tw, SPARK));
            } finally { m.pop(); }
        }
    }

    /** 빨간 홀로그램: 붉은 빛 + 깜빡임 + 살짝 어긋난 잔상 + 위아래로 지나가는 주사선. */
    private static void hologram(DrawContext c, Art art, float x, float y, float box, float phase) {
        float pulse = .5f + .5f * (float) Math.sin(phase * 2.6);
        float flicker = (Math.sin(phase * 23) > .93) ? .55f : 1f;         // 가끔 지지직
        FishingArt.bloom(c, x, y, box * .95f, 0xFF3030, (.20f + .18f * pulse) * flicker);
        FishingArt.bloom(c, x, y, box * .45f, 0xFF7070, (.16f + .10f * pulse) * flicker);
        float jitter = (float) Math.sin(phase * 17) * 1.2f;
        icon(c, art, x + 1.6f + jitter, y, box, FishingArt.argb(.28f * flicker, HOLO));   // 잔상
        icon(c, art, x, y, box, FishingArt.argb((.80f + .15f * pulse) * flicker, HOLO));
        float scan = (float) ((phase * 0.8) % 1.0);                       // 주사선 0..1
        float sy = y - box / 2 + box * scan;
        FishingArt.rect(c, x - box * .42f, sy, box * .84f, 1.4f, FishingArt.argb(.55f * flicker, 0xFFB0B0));
        for (int i = 0; i < 4; i++) {
            float ly = y - box * .38f + i * box * .25f;
            FishingArt.rect(c, x - box * .36f, ly, box * .72f, 1, FishingArt.argb(.12f, 0xFF8080));
        }
    }

    /** 376 봉화 신호: party members' flare markers (MapPingClient) in the world the player stands in, with their names. */
    private void pings(DrawContext c, Tab t, TravelMapMath.Affine a, TravelMapMath.Camera cam) {
        for (var p : MapPingClient.in(t.world())) {
            float x = (float) TravelMapMath.screenX(cam, a.u(p.x(), p.z())), y = (float) TravelMapMath.screenY(cam, a.v(p.x(), p.z()));
            if (x < TravelMapMath.MAP_L - 40 || x > TravelMapMath.MAP_R + 40 || y < TravelMapMath.MAP_T - 40 || y > TravelMapMath.MAP_B + 60) continue;
            float pulse = .5f + .5f * (float) Math.sin(time * 4);
            FishingArt.bloom(c, x, y, 22 + 6 * pulse, 0xFF8A3C, .22f + .12f * pulse);
            icon(c, ACTIVE, x, y, 30, 0xFFFFB27A);
            String name = cut(p.name(), 16, LABEL, 220);
            float w = width(name, 16, LABEL) + 18;
            FishingArt.card(c, x - w / 2, y - 40, w, 24, 0xD0081830, 0x80FF9A5A, 1);
            text(c, name, x, y - 28, 16, 0xFFFFE2C8, LABEL, true);
        }
    }

    private void label(DrawContext c, Point p, float x, float y) {
        String name = cut(p.name(), 18, LABEL, 260);
        float w = width(name, 18, LABEL) + 22;
        FishingArt.card(c, x - w / 2, y - 14, w, 28, p.travelable() ? 0xD0081830 : 0xD0300810, p.travelable() ? 0x60C9B48A : 0x80FF6A6A, 1);
        text(c, name, x, y, 18, p.travelable() ? WHITE : HOLO_TEXT, LABEL, true);
    }

    // ---- header: legend (top half) and tabs (bottom half), close

    private void header(DrawContext c, double mx, double my) {
        float x = 112;
        icon(c, PLAYER, x, LEGEND_Y, 20, WHITE); text(c, "현재 위치", x + 16, LEGEND_Y + 1, 15, MUTED, BODY, false); x += 22 + width("현재 위치", 15, BODY) + 26;
        icon(c, ACTIVE, x, LEGEND_Y, 22, WHITE); text(c, "공개 지점", x + 16, LEGEND_Y + 1, 15, MUTED, BODY, false); x += 22 + width("공개 지점", 15, BODY) + 26;
        icon(c, PERSONAL, x, LEGEND_Y - 2, 20, WHITE); text(c, "개인 지점", x + 16, LEGEND_Y + 1, 15, MUTED, BODY, false); x += 22 + width("개인 지점", 15, BODY) + 26;
        icon(c, ACTIVE, x, LEGEND_Y, 22, FishingArt.argb(.9f, HOLO)); text(c, "이동 불가", x + 16, LEGEND_Y + 1, 15, MUTED, BODY, false);
        boolean overClose = in(mx, my, 1118, 70, 44, 44);
        icon(c, CLOSE, 1140, 92, 24, white(overClose ? 1 : .75f));
        int i = 0;
        for (Tab t : tabs.values()) {
            float tx = tabX(i++);
            boolean on = t.id().equals(tab), over = in(mx, my, tx - 80, TAB_Y - 26, 160, 52);
            int color = on ? WHITE : over ? TEXT : MUTED;
            text(c, t.label(), tx, TAB_Y, 26, t.online() || on ? color : a(color, .6f), SECTION, true);
            if (on) { float w = Math.min(120, width(t.label(), 26, SECTION) + 18); FishingArt.rect(c, tx - w / 2, TAB_Y + 21, w, 1.5f, LINE); }
        }
    }

    // ---- footer: zoom − + ⌂ · selected point · 이동하기

    private void footer(DrawContext c, double mx, double my) {
        String[] tips = {"축소", "확대", "전체 지도"};
        Art[] arts = {MINUS, PLUS, HOME};
        for (int k = 0; k < 3; k++) {
            float cx = ZOOM_X + 64 * k;
            boolean over = in(mx, my, cx - ICON / 2, FOOT_Y - ICON / 2, ICON, ICON);
            FishingArt.card(c, cx - ICON / 2, FOOT_Y - ICON / 2, ICON, ICON, over ? 0x4019304E : 0x200C1A30, over ? 0xB0C9B48A : 0x60C9B48A, 1);
            icon(c, arts[k], cx, FOOT_Y, k == 2 ? 24 : 20, white(over ? 1 : .82f));
            if (over) text(c, tips[k], ZOOM_X + 128 + 40, FOOT_Y + 1, 15, TEXT, BODY, false);   // beside the buttons (the band above is the info line)
        }
        Point p = selected == null ? null : point(selected);
        float infoX = 112, infoR = 1142;
        if (p == null) text(c, "지도나 목록에서 장소를 고르세요", infoX, INFO_Y, 18, DIM, BODY, false);
        else {
            Tab t = tabs.get(p.tab());
            String where = p.kind() == TravelProtocol.POINT_PERSONAL ? "개인 지점" + (t == null ? "" : " · " + t.label()) : t == null ? "지도 밖" : t.label();
            String name = cut(p.name(), 22, SECTION, 520);
            text(c, name, infoX, INFO_Y, 22, p.travelable() ? WHITE : TEXT, SECTION, false);
            float subX = infoX + width(name, 22, SECTION) + 18;
            String sub = p.travelable() ? where + (p.kind() == TravelProtocol.POINT_PERSONAL ? " · 마나 100 · 재사용 대기 60초" : "") : p.reason();
            text(c, cut(sub, 16, BODY, infoR - subX), subX, INFO_Y + 2, 16, p.travelable() ? MUTED : WARN, BODY, false);
        }
        boolean enabled = p != null && p.travelable() && !busy() && closeAt == 0;
        boolean over = enabled && in(mx, my, BTN_X, FOOT_Y - BTN_H / 2, BTN_W, BTN_H);
        art(c, BUTTON, BTN_X, FOOT_Y - BTN_H / 2, BTN_W, BTN_H, enabled ? white(over ? 1 : .92f) : white(.45f));
        String label = busy() ? pendingStage == TravelProtocol.STAGE_ACCEPTED ? "서버 이동 중…" : "이동 준비 중…" : "이동하기";
        text(c, label, BTN_X + BTN_W / 2, FOOT_Y + 1, 22, enabled || busy() ? (over ? WHITE : GOLD) : DIM, SECTION, true);
    }

    // ---- the list: toggle on the frame edge, panel outside the frame

    private void drawer(DrawContext c, double mx, double my) {
        float ty = (float) TravelMapMath.viewCy() - TravelMapMath.TOGGLE_H / 2;
        boolean overToggle = in(mx, my, toggleX(), ty, TravelMapMath.TOGGLE_W, TravelMapMath.TOGGLE_H);
        Art toggle = listOpen ? COLLAPSE : EXPAND;
        // both PNGs were cut from same-size canvases: one scale keeps their drawn sizes as designed
        float th = toggle.h() * TravelMapMath.TOGGLE_H / EXPAND.h(), tw = toggle.w() * TravelMapMath.TOGGLE_H / EXPAND.h();
        art(c, toggle, toggleX() + (TravelMapMath.TOGGLE_W - tw) / 2, (float) TravelMapMath.viewCy() - th / 2, tw, th, white(overToggle ? 1 : .85f));
        if (list <= .01f) return;
        float al = ease(list);
        float x = panelX() - 30 * (1 - al), top = TravelMapMath.BOX_T, w = TravelMapMath.PANEL_W;
        art(c, PANEL, x, top, w, TravelMapMath.PANEL_H, white(al));
        text(c, "장소 목록", x + w / 2, top + 26, 20, a(GOLD, al), SECTION, true);
        var lines = lines();
        int visible = visibleRows();
        scroll = Math.clamp(scroll, 0, maxScroll());
        float y0 = top + LIST_TOP;
        if (points.isEmpty()) text(c, "아직 등록된 장소가 없어요", x + w / 2, y0 + 40, 17, a(MUTED, al), BODY, true);
        for (int k = 0; k < visible && scroll + k < lines.size(); k++) {
            Object line = lines.get(scroll + k);
            float y = y0 + k * ROW_H;
            if (line instanceof String header) {
                text(c, header, x + 26, y + ROW_H - 18, 15, a(DIM, al), BODY, false);
                FishingArt.hline(c, x + 22, x + w - 22, y + ROW_H - 4, FishingArt.argb(.35f * al, 0xC9B48A));
                continue;
            }
            Point p = (Point) line;
            boolean on = p.id().equals(selected), over = in(mx, my, x + 14, y, w - 28, ROW_H);
            if (on || over) FishingArt.rect(c, x + 16, y + 2, w - 32, ROW_H - 4, FishingArt.argb((on ? .10f : .05f) * al, 0xFFFFFF));
            if (on) FishingArt.rect(c, x + 16, y + 10, 2, ROW_H - 20, a(LINE, al));
            Art ic = p.kind() == TravelProtocol.POINT_PERSONAL ? PERSONAL : p.state() == TravelProtocol.LOCKED ? LOCKED : ACTIVE;
            icon(c, ic, x + 42, y + ROW_H / 2, ic == LOCKED ? 18 : 24, p.travelable() ? white(al) : a(0xFF8A93A0, al));
            String status = p.state() == TravelProtocol.LOCKED ? "미발견" : p.state() == TravelProtocol.UNAVAILABLE ? "사용 불가" : "";
            float statusW = status.isEmpty() ? 0 : width(status, 14, BODY) + 10;
            String name = cut(p.name(), 19, LABEL, w - 96 - statusW);
            text(c, name, x + 64, y + ROW_H / 2 + 1, 19, a(p.travelable() ? (on ? WHITE : TEXT) : MUTED, al), LABEL, false);
            if (!status.isEmpty()) text(c, status, x + w - 26 - statusW + 10, y + ROW_H / 2 + 1, 14, a(p.state() == TravelProtocol.LOCKED ? DIM : WARN, al), BODY, false);
            FishingArt.hline(c, x + 22, x + w - 22, y + ROW_H, FishingArt.argb(.16f * al, 0xC9D6E6));
            if (over && !p.travelable() && !p.reason().isBlank() && p.state() != TravelProtocol.LOCKED) tooltip(c, p.reason(), x + w / 2, y - 4, al);
        }
        int max = maxScroll();
        if (max > 0) {
            float bar = TravelMapMath.PANEL_H - LIST_TOP - LIST_PAD, thumb = Math.max(40, bar * visible / (float) (visible + max));
            FishingArt.rect(c, x + w - 14, y0, 2, bar, FishingArt.argb(.15f * al, 0xFFFFFF));
            FishingArt.rect(c, x + w - 15, y0 + (bar - thumb) * scroll / max, 4, thumb, FishingArt.argb(.55f * al, 0xFFFFFF));
        }
    }
    private void tooltip(DrawContext c, String v, float cx, float y, float al) {
        String s = cut(v, 15, BODY, 340); float w = width(s, 15, BODY) + 20;
        FishingArt.card(c, cx - w / 2, y - 26, w, 26, FishingArt.argb(.92f * al, 0x081830), FishingArt.argb(.5f * al, 0xC9B48A), 1);
        text(c, s, cx, y - 13, 15, a(TEXT, al), BODY, true);
    }
    /** List lines: a tab header (String) before each group, then its points. Personal point group first. */
    private List<Object> lines() {
        var out = new ArrayList<Object>();
        String group = null;
        for (Point p : rows()) {
            String g = p.kind() == TravelProtocol.POINT_PERSONAL ? "개인 지점" : tabs.containsKey(p.tab()) ? tabs.get(p.tab()).label() : "지도 밖";
            if (!g.equals(group)) { out.add(g); group = g; }
            out.add(p);
        }
        return out;
    }
    private int rowIndex(String id) { var l = lines(); for (int i = 0; i < l.size(); i++) if (l.get(i) instanceof Point p && p.id().equals(id)) return i; return -1; }

    private void noticeLine(DrawContext c) {
        long age = Util.getMeasuringTimeMs() - noticeAt;
        if (notice.isBlank() || age > 4000) return;
        float al = age > 3500 ? (4000 - age) / 500f : 1;
        String s = cut(notice, 18, BODY, 900);
        float w = width(s, 18, BODY) + 40, y = TravelMapMath.MAP_B - 46;
        FishingArt.card(c, (float) TravelMapMath.viewCx() - w / 2, y - 17, w, 34, FishingArt.argb(.88f * al, 0x08182E), FishingArt.argb(.55f * al, 0xC9B48A), 1);
        text(c, s, (float) TravelMapMath.viewCx(), y, 18, a(0xFFF1F5FA, al), BODY, true);
    }
}
