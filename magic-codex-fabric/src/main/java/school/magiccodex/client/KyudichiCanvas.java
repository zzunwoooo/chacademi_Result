package school.magiccodex.client;

import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import school.magiccodex.client.kui.Canvas;
import school.magiccodex.client.kui.Parts;

/** {@link Canvas} on the game's DrawContext (the 큐디치 views; same coordinates as the Java2D previews). */
final class KyudichiCanvas implements Canvas {
    private final DrawContext ctx;
    private final HudTextureCache images;
    private final CodexTypography type;
    private int depth;

    KyudichiCanvas(DrawContext ctx, HudTextureCache images, CodexTypography type) { this.ctx = ctx; this.images = images; this.type = type; }

    static Identifier face(Face f) {
        return Identifier.of("magiccodex", switch (f) { case TITLE -> "section"; case SERIF -> "heading"; case SANS -> "label"; case SANS_BOLD -> "hud_bold"; });
    }
    private static int alpha(float a) { return (Math.round(Math.clamp(a, 0, 1) * 255) << 24) | 0xFFFFFF; }

    @Override public void image(String part, float x, float y, float w, float h, float a) {
        int[] s = Parts.size(part);
        images.drawTexture(ctx, Identifier.of("magiccodex", Parts.ROOT + part + ".png"), Math.round(x), Math.round(y), 0, 0,
                Math.round(x + w) - Math.round(x), Math.round(y + h) - Math.round(y), s[0], s[1], s[0], s[1], alpha(a));
    }
    @Override public void imageRotated(String part, float cx, float cy, float w, float h, float degrees, float a) {
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(cx, cy, 0);
            ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(degrees));
            image(part, -w / 2, -h / 2, w, h, a);
        } finally { ctx.getMatrices().pop(); }
    }
    @Override public void texture(String resource, float x, float y, float w, float h, float a) {
        if (resource == null || resource.isBlank()) return;
        images.drawCentered(ctx, resource, x + w / 2, y + h / 2, Math.round(Math.max(w, h)), alpha(a));
    }
    @Override public void head(UUID id, String skin, float x, float y, float size) {
        Identifier tex = KyudichiFaces.face(id, skin);
        int s = Math.round(size), ix = Math.round(x), iy = Math.round(y);
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, ix, iy, 8, 8, s, s, 8, 8, 64, 64);
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, ix, iy, 40, 8, s, s, 8, 8, 64, 64);
    }
    @Override public void avatar(UUID id, String skin, float cx, float cy, float radius) {
        // owner 2026-10-19: always the Minecraft skin face (not the illustration)
        Identifier tex = KyudichiFaces.face(id, skin);
        // face (8,8)-(16,16) then the hat layer (40,8)-(48,16) of the 64×64 skin
        HudMesh.texturedDisk(ctx, RenderLayer.getGuiTextured(tex), cx, cy, radius, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, 0xFFFFFFFF);
        HudMesh.texturedDisk(ctx, RenderLayer.getGuiTextured(tex), cx, cy, radius, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, 0xFFFFFFFF);
    }
    @Override public boolean broom(String id, float cx, float cy, float w, float h) {
        // the model lies along the view: a side view, slightly tilted like the reference picture
        return BroomUiLink.render(ctx, id, cx, cy, w, 90, 15, -18);
    }
    @Override public void text(String s, float x, float y, float size, int argb, Face face, int align) {
        if (s == null || s.isBlank()) return;
        Identifier f = face(face);
        float left = align == RIGHT ? x - type.width(s, size, f) : x;
        type.draw(ctx, s, left, y, size, argb, f, align == CENTER);
    }
    @Override public float width(String s, float size, Face face) { return s == null || s.isEmpty() ? 0 : type.width(s, size, face(face)); }
    @Override public void fill(float x, float y, float w, float h, int argb) {
        // thin lines in scaled groups (e.g. the 70 % 마법 세팅) must stay at least one screen pixel or they vanish
        var m = ctx.getMatrices().peek().getPositionMatrix();
        float sx = Math.abs(m.m00()), sy = Math.abs(m.m11());
        if (sx > 0 && w * sx < 1) { x -= (1 / sx - w) / 2; w = 1 / sx; }
        if (sy > 0 && h * sy < 1) { y -= (1 / sy - h) / 2; h = 1 / sy; }
        HudMesh.quad(ctx, x, y, w, h, argb, argb);
    }
    @Override public void arc(float cx, float cy, float inner, float outer, float start, float end, int argb) {
        if (end <= start) return;
        float radius = (inner + outer) / 2, thickness = outer - inner;
        double a0 = -Math.PI / 2 + start * Math.PI * 2;
        HudMesh.arc(ctx, cx, cy, radius, thickness, (float) a0, (float) ((end - start) * Math.PI * 2), argb);
    }
    @Override public void push(float tx, float ty, float scale) {
        ctx.getMatrices().push(); depth++;
        ctx.getMatrices().translate(tx, ty, 0); ctx.getMatrices().scale(scale, scale, 1);
    }
    @Override public void pop() { if (depth > 0) { ctx.getMatrices().pop(); depth--; } }
    @Override public void clip(float x, float y, float w, float h) {
        var m = ctx.getMatrices().peek().getPositionMatrix();
        float x0 = m.m00() * x + m.m30(), y0 = m.m11() * y + m.m31(), x1 = m.m00() * (x + w) + m.m30(), y1 = m.m11() * (y + h) + m.m31();
        ctx.enableScissor(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
    }
    @Override public void unclip() { ctx.disableScissor(); }

    /** For screens: pointer in design pixels for a view drawn with {@code push(ox, oy, s)}. */
    static float design(double screen, float origin, float scale) { return (float) ((screen - origin) / scale); }
    static MinecraftClient mc() { return MinecraftClient.getInstance(); }
}
