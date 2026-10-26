package school.magiccodex.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import school.magiccodex.protocol.FishingProtocol;

/**
 * 낚시 UI art (GPT's approved PNGs in textures/gui/fishing, 2026-10-20) and the small drawing helpers shared by the
 * panel, the gear window and the codex. Everything is drawn in the 1672x941 reference space of the mock-ups.
 * Glow, rays and rings are soft white textures drawn additively and tinted: that is the panel's bloom.
 */
final class FishingArt {
    private FishingArt() {}

    static final Identifier TITLE = Identifier.of("magiccodex", "title"), BOLD = Identifier.of("magiccodex", "hud_bold"),
            LABEL = Identifier.of("magiccodex", "label"), BODY = Identifier.of("magiccodex", "body");
    static final int GOLD = 0xFFE7C77E, TITLE_GOLD = 0xFFF2D9A0, IVORY = 0xFFF3E9D3, TEXT = 0xFFE4E9EF, MUTED = 0xFF9AA8BB,
            DIM = 0xFF6B7A90, WARN = 0xFFFF9E9E, CYAN = 0xFF7FE3FF, NAVY = 0xFF0C1A33, PANEL = 0xF20B1730, CARD = 0xE6101F3B;
    /** 일반 고급 유일 전설 (same as the artifact codex). */
    static final int[] GRADE = {0xFFDCE3EA, 0xFF71E5F2, 0xFFD2A8FF, 0xFFFFD582};

    static Identifier tex(String path) { return Identifier.of("magiccodex", "textures/gui/fishing/" + path + ".png"); }

    // ---- art with their pixel sizes
    record Art(Identifier id, int w, int h) {}
    static Art art(String path, int w, int h) { return new Art(tex(path), w, h); }
    static final Art FRAME_PANEL = art("frames/fishing_frame_full", 1614, 1802), FRAME_GEAR = art("frames/equipment_frame_full", 1892, 1162),
            FRAME_CODEX = art("frames/codex_frame_full", 2812, 1698), DIAMOND = art("frames/codex_diamond_top", 48, 50),
            BUTTON_GOLD = art("buttons/button_gold_filled", 546, 106), BUTTON_LINE = art("buttons/button_gold_outline", 544, 102),
            LOCK = art("icons/lock", 256, 256), MOUSE = art("icons/mouse_default", 256, 256), MOUSE_LEFT = art("icons/mouse_left_red", 256, 256),
            SEARCH = art("icons/search_magnifier", 256, 256), BOBBER = art("underwater/floating_bobber", 128, 128), HOOK = art("underwater/hook", 128, 128),
            GLOW = art("fx/glow", 128, 128), RAY = art("fx/ray", 64, 512), RING = art("fx/ring", 128, 128);
    static final Art[] BACKGROUND = {art("bg/freshwater", 724, 2172), art("bg/ocean", 724, 2172), art("bg/ice", 724, 2172), art("bg/cave", 724, 2172)};
    static final Art[] SHAPES = {art("silhouettes/fish_generic", 512, 512), art("silhouettes/shark_large", 512, 512), art("silhouettes/eel_long", 512, 512),
            art("silhouettes/ray_flat", 512, 512), art("silhouettes/squid_jelly", 512, 512), art("chests/chest_common_closed", 512, 512)};
    static final Art[] DEFAULT_ICONS = {art("icons/default_rod", 256, 256), art("icons/default_bobber", 256, 256),
            art("icons/default_line_spool", 256, 256), art("icons/default_bait_earthworm", 256, 256)};
    static final String[] GRADE_FILES = {"common", "advanced", "unique", "legendary"};
    static Art chest(int grade, boolean open) { return art("chests/chest_" + GRADE_FILES[Math.clamp(grade, 0, 3)] + (open ? "_open" : "_closed"), 512, 512); }
    static Art reward(int tier) { return art("chests/reward_" + new int[]{25, 50, 75, 100}[tier], 256, 256); }
    static Art gear(int kind, String id) { return art("equipment/" + FishingProtocol.KIND_FOLDERS[kind] + "/" + id, 128, 128); }

    private static final Map<Identifier, Boolean> EXISTS = new HashMap<>();
    private static int existsGeneration = -1;
    /** Fish artwork when it has been added to the mod (textures/gui/fishing/fish/<id>.png, 256x256), else null. */
    static Art fish(String id) {
        if (id == null || id.isEmpty()) return null;
        var a = art("fish/" + id, 256, 256);
        return exists(a.id()) ? a : null;
    }
    static boolean exists(Identifier id) {
        if (existsGeneration != UiResources.generation()) { EXISTS.clear(); existsGeneration = UiResources.generation(); }
        return EXISTS.computeIfAbsent(id, k -> MinecraftClient.getInstance().getResourceManager().getResource(k).isPresent());
    }

    // ---- drawing

    static void draw(DrawContext c, Art a, float x, float y, float w, float h, int tint) { draw(c, a, x, y, w, h, tint, false); }
    static void glow(DrawContext c, Art a, float x, float y, float w, float h, int tint) { draw(c, a, x, y, w, h, tint, true); }
    static void draw(DrawContext c, Art a, float x, float y, float w, float h, int tint, boolean additive) {
        if (w <= 0 || h <= 0 || (tint >>> 24) == 0) return;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(x, y, 0); m.scale(w / a.w(), h / a.h(), 1);
            var images = UiResources.images();
            if (additive) images.drawTextureAdditive(c, a.id(), 0, 0, 0, 0, a.w(), a.h(), a.w(), a.h(), a.w(), a.h(), tint);
            else images.drawTexture(c, a.id(), 0, 0, 0, 0, a.w(), a.h(), a.w(), a.h(), a.w(), a.h(), tint);
        } finally { m.pop(); }
    }
    /** Art centred on (cx, cy), optionally mirrored and rotated (radians). */
    static void drawCentered(DrawContext c, Art a, float cx, float cy, float w, float h, int tint, boolean mirror, float rotation, boolean additive) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(cx, cy, 0);
            if (rotation != 0) m.multiply(new Quaternionf().rotateZ(rotation));
            if (mirror) m.scale(-1, 1, 1);
            draw(c, a, -w / 2, -h / 2, w, h, tint, additive);
        } finally { m.pop(); }
    }
    /**
     * A fish picture fitted into a box (centre cx, cy) by its visible silhouette (FishArtBounds), keeping its ratio.
     * Without art the silhouette shape is drawn the same way. Returns false when nothing could be drawn.
     */
    static boolean fishFit(DrawContext c, String fishId, int fallbackShape, float cx, float cy, float boxW, float boxH, int tint, boolean mirror) {
        Art a = fish(fishId);
        int[] b = a == null ? null : FishArtBounds.of(fishId);
        if (a == null) { a = SHAPES[Math.clamp(fallbackShape, 0, SHAPES.length - 1)]; b = new int[]{24, 64, 488, 448}; }
        if (b == null) b = new int[]{0, 0, a.w(), a.h()};
        float bw = b[2] - b[0], bh = b[3] - b[1];
        if (bw <= 0 || bh <= 0) return false;
        float k = Math.min(boxW / bw, boxH / bh);
        float w = a.w() * k, h = a.h() * k;
        // centre of the silhouette, not of the canvas
        float ox = ((b[0] + b[2]) / 2f - a.w() / 2f) * k, oy = ((b[1] + b[3]) / 2f - a.h() / 2f) * k;
        drawCentered(c, a, cx - (mirror ? -ox : ox), cy - oy, w, h, tint, mirror, 0, false);
        return true;
    }

    /** Soft round bloom. */
    static void bloom(DrawContext c, float cx, float cy, float radius, int rgb, float alpha) {
        if (alpha <= .004f) return;
        glow(c, GLOW, cx - radius, cy - radius, radius * 2, radius * 2, argb(alpha, rgb));
    }

    static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }
    static int alpha(int color, float a) { return argb(((color >>> 24) / 255f) * a, color); }
    static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    static void text(DrawContext c, String value, float x, float y, float size, int color, Identifier face, boolean centered) {
        if (value != null && !value.isBlank() && (color >>> 24) > 3) UiResources.text().draw(c, value, x, y, size, color, face, centered);
    }
    static void outlined(DrawContext c, String value, float x, float y, float size, int color, Identifier face) {
        if (value != null && !value.isBlank()) UiResources.text().drawOutline(c, value, x, y, size, color, face, true);
    }
    static void right(DrawContext c, String value, float right, float y, float size, int color, Identifier face) {
        if (value == null || value.isBlank()) return;
        text(c, value, right - UiResources.text().width(value, size, face), y, size, color, face, false);
    }
    static float width(String value, float size, Identifier face) { return UiResources.text().width(value, size, face); }
    static float fit(String value, float size, Identifier face, float max) {
        float w = width(value, size, face);
        return w <= max ? size : Math.max(10, size * max / w);
    }

    /** Rounded card: fill + 1.6px border (code-drawn, owner 2026-10-19: 코드로 그릴 수 있는 건 코드로). */
    static void card(DrawContext c, float x, float y, float w, float h, int fill, int border, float thickness) {
        float r = 6;
        HudMesh.quad(c, x + r, y, w - 2 * r, h, fill, fill);
        HudMesh.quad(c, x, y + r, r, h - 2 * r, fill, fill);
        HudMesh.quad(c, x + w - r, y + r, r, h - 2 * r, fill, fill);
        HudMesh.disk(c, x + r, y + r, r, fill); HudMesh.disk(c, x + w - r, y + r, r, fill);
        HudMesh.disk(c, x + r, y + h - r, r, fill); HudMesh.disk(c, x + w - r, y + h - r, r, fill);
        if ((border >>> 24) == 0) return;
        HudMesh.line(c, x + r, y, x + w - r, y, thickness, border);
        HudMesh.line(c, x + r, y + h, x + w - r, y + h, thickness, border);
        HudMesh.line(c, x, y + r, x, y + h - r, thickness, border);
        HudMesh.line(c, x + w, y + r, x + w, y + h - r, thickness, border);
        float q = (float) (Math.PI / 2);
        HudMesh.arc(c, x + r, y + r, r, thickness, (float) Math.PI, q, border);
        HudMesh.arc(c, x + w - r, y + r, r, thickness, -q, q, border);
        HudMesh.arc(c, x + w - r, y + h - r, r, thickness, 0, q, border);
        HudMesh.arc(c, x + r, y + h - r, r, thickness, q, q, border);
    }
    static void rect(DrawContext c, float x, float y, float w, float h, int color) { HudMesh.quad(c, x, y, w, h, color, color); }
    /** Horizontal gradient (left colour -> right colour, alpha included). */
    static void hgrad(DrawContext c, float x, float y, float w, float h, int left, int right) { HudMesh.quad(c, x, y, w, h, left, right); }
    /** Vertical gradient top -> bottom (alpha included), drawn as thin bands since the mesh quad only blends sideways. */
    static void vgrad(DrawContext c, float x, float y, float w, float h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        int bands = Math.clamp(Math.round(h / 6), 2, 48);
        float bh = h / bands;
        for (int i = 0; i < bands; i++) {
            int col = lerpArgb(top, bottom, (i + .5f) / bands);
            if ((col >>> 24) == 0) continue;
            HudMesh.quad(c, x, y + i * bh, w, bh + .35f, col, col);
        }
    }
    /** Like mix but alpha is interpolated too. */
    static int lerpArgb(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int aa = a >>> 24, ba = b >>> 24;
        return (Math.round(aa + (ba - aa) * t) << 24) | (mix(a, b, t) & 0xFFFFFF);
    }
    static void hline(DrawContext c, float x0, float x1, float y, int color) { HudMesh.line(c, x0, y, x1, y, 1.4f, color); }
    static void vline(DrawContext c, float x, float y0, float y1, int color) { HudMesh.line(c, x, y0, x, y1, 1.4f, color); }

    /** Bracket corners around a box (result frame). */
    static void brackets(DrawContext c, float x, float y, float w, float h, float len, int color) {
        float t = 2.4f;
        HudMesh.line(c, x, y, x + len, y, t, color); HudMesh.line(c, x, y, x, y + len, t, color);
        HudMesh.line(c, x + w - len, y, x + w, y, t, color); HudMesh.line(c, x + w, y, x + w, y + len, t, color);
        HudMesh.line(c, x, y + h, x + len, y + h, t, color); HudMesh.line(c, x, y + h - len, x, y + h, t, color);
        HudMesh.line(c, x + w - len, y + h, x + w, y + h, t, color); HudMesh.line(c, x + w, y + h - len, x + w, y + h, t, color);
    }
    /** Small diamond (title ornaments). */
    static void diamond(DrawContext c, float cx, float cy, float r, int color) { HudMesh.star(c, cx, cy, r, color); }
    /** Gold button: art stretched to the box, label centred. */
    static void button(DrawContext c, float x, float y, float w, float h, String label, boolean filled, boolean hover, boolean enabled) {
        var a = filled ? BUTTON_GOLD : BUTTON_LINE;
        int tint = !enabled ? 0xFF7A8090 : hover ? 0xFFFFFFFF : 0xFFE4E0D4;
        draw(c, a, x, y, w, h, tint);
        if (hover && enabled) bloom(c, x + w / 2, y + h / 2, w * .45f, 0xE7C77E, .10f);
        text(c, label, x + w / 2, y + h / 2 + 1, Math.min(22, h * .42f), enabled ? (filled ? 0xFFFFF4DC : IVORY) : DIM, BOLD, true);
    }
    static boolean inside(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    static String grade(int g) { return FishingProtocol.GRADE_LABELS[Math.clamp(g, 0, 3)]; }
    static String cm(float v) { return String.format(java.util.Locale.ROOT, "%.1f", v) + " cm"; }
}
