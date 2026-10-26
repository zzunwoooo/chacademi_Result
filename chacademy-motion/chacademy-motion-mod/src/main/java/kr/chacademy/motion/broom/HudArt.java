package kr.chacademy.motion.broom;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * High-quality HUD drawing for the flight UI, same approach as MagicCodex's HUD renderer:
 * images get a full mip chain with trilinear filtering (vanilla GUI blits sample nearest, which shimmers when a large
 * painted texture is shown small), quads take float coordinates and per-corner colours (soft gauge edges), and text is
 * rasterised from the MagicCodex Pretendard font at the real screen pixel size instead of the vanilla bitmap font.
 */
final class HudArt {
    record Tex(ResourceLocation id, int width, int height, RenderType layer) {}
    private record TextKey(String value, int pixelSize64) {}
    private record Sprite(Tex tex) {}

    private static final Map<String, Optional<Tex>> IMAGES = new HashMap<>();
    private static final Map<TextKey, Sprite> TEXT = new LinkedHashMap<>(64, .75f, true);
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private static final int PADDING = 2;
    private static Font font;
    private static boolean fontTried;
    private static int serial;

    private HudArt() {}

    /** Drops every GPU copy (resource reload / world leave). */
    static void reset() {
        var textures = Minecraft.getInstance().getTextureManager();
        IMAGES.values().forEach(t -> t.ifPresent(v -> textures.release(v.id())));
        IMAGES.clear();
        TEXT.values().forEach(s -> textures.release(s.tex().id()));
        TEXT.clear();
        font = null; fontTried = false;
    }

    /** {@code textures/gui/hud/<name>.png} of this mod, mipmapped. Empty when it cannot be loaded. */
    static Optional<Tex> image(String name) {
        return IMAGES.computeIfAbsent(name, HudArt::load);
    }

    private static Optional<Tex> load(String name) {
        var mc = Minecraft.getInstance();
        var source = ResourceLocation.fromNamespaceAndPath("chacademy_motion", "textures/gui/hud/" + name + ".png");
        try (var stream = mc.getResourceManager().getResourceOrThrow(source).open()) {
            return Optional.of(upload(NativeImage.read(stream), true));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger("chacademy_motion").warn("Flight HUD texture failed: {}", source, error);
            return Optional.empty();
        }
    }

    private static Tex upload(NativeImage image, boolean mipmap) {
        int width = image.getWidth(), height = image.getHeight();
        var texture = new DynamicTexture(image);
        var id = ResourceLocation.fromNamespaceAndPath("chacademy_motion", "runtime_hud/" + serial++);
        texture.bind();
        if (mipmap) {
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(width, height));
            // DynamicTexture allocates a single level; open the whole chain and let the driver build it.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, levels);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        }
        texture.setClamp(true);
        texture.setFilter(true, mipmap);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new Tex(id, width, height, new Layer(id, mipmap));
    }

    /** Axis-aligned textured quad with float corners and per-corner colours (top-left, bottom-left, bottom-right, top-right). */
    static void quad(GuiGraphics g, Tex t, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                     int tl, int bl, int br, int tr) {
        Matrix4f m = g.pose().last().pose();
        g.drawSpecial(buffers -> {
            var vc = buffers.getBuffer(t.layer());
            vc.addVertex(m, x0, y0, 0).setUv(u0, v0).setColor(tl);
            vc.addVertex(m, x0, y1, 0).setUv(u0, v1).setColor(bl);
            vc.addVertex(m, x1, y1, 0).setUv(u1, v1).setColor(br);
            vc.addVertex(m, x1, y0, 0).setUv(u1, v0).setColor(tr);
        });
    }

    static void image(GuiGraphics g, String name, float x, float y, float w, float h, int color) {
        image(name).ifPresent(t -> quad(g, t, x, y, x + w, y + h, 0, 0, 1, 1, color, color, color, color));
    }

    /** Triangle (as a degenerate quad) with UVs taken from positions inside the image rectangle. */
    static void triangle(GuiGraphics g, Tex t, float rx, float ry, float rw, float rh, float[] a, float[] b, float[] c, int ca, int cb, int cc) {
        Matrix4f m = g.pose().last().pose();
        g.drawSpecial(buffers -> {
            var vc = buffers.getBuffer(t.layer());
            vc.addVertex(m, a[0], a[1], 0).setUv((a[0] - rx) / rw, (a[1] - ry) / rh).setColor(ca);
            vc.addVertex(m, b[0], b[1], 0).setUv((b[0] - rx) / rw, (b[1] - ry) / rh).setColor(cb);
            vc.addVertex(m, c[0], c[1], 0).setUv((c[0] - rx) / rw, (c[1] - ry) / rh).setColor(cc);
            vc.addVertex(m, c[0], c[1], 0).setUv((c[0] - rx) / rw, (c[1] - ry) / rh).setColor(cc);
        });
    }

    // ---- text ----

    private static Font font() {
        if (!fontTried) {
            fontTried = true;
            // The Pretendard face ships with MagicCodex (always installed with this mod on 차카데미).
            var id = ResourceLocation.fromNamespaceAndPath("magiccodex", "font/pretendard_bold.ttf");
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
                font = Font.createFont(Font.TRUETYPE_FONT, stream);
            } catch (Exception missing) {
                font = null;
            }
        }
        return font;
    }

    /** Device pixels per GUI unit of the current pose (vertical). */
    private static float pixelsPerUnit(GuiGraphics g) {
        return Math.abs(g.pose().last().pose().m11()) * (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Text width in current units for a cap size of {@code size} units. */
    static float width(GuiGraphics g, String value, float size) {
        var f = font();
        if (f == null) return Minecraft.getInstance().font.width(value) * size / 9f;
        return value.isEmpty() ? 0 : new TextLayout(value, f.deriveFont(size), CONTEXT).getAdvance();
    }

    /**
     * Centred text, {@code size} = font size in current units (Pretendard em). A soft dark shadow keeps it readable
     * now that the HUD has no backing panel. Falls back to the vanilla font when MagicCodex is not installed.
     */
    static void text(GuiGraphics g, String value, float cx, float cy, float size, int color) {
        if (value == null || value.isBlank()) return;
        var f = font();
        if (f == null) {
            var mc = Minecraft.getInstance();
            var pose = g.pose(); pose.pushPose();
            try { pose.translate(cx, cy, 0); pose.scale(size / 9f, size / 9f, 1); g.drawCenteredString(mc.font, value, 0, -4, color); }
            finally { pose.popPose(); }
            return;
        }
        float ppu = pixelsPerUnit(g);
        int px64 = Math.max(64, Math.round(size * ppu * 64));
        var sprite = TEXT.computeIfAbsent(new TextKey(value, px64), key -> bake(f, key));
        if (TEXT.size() > 96) {
            var it = TEXT.entrySet().iterator();
            var oldest = it.next();
            if (oldest.getValue() != sprite) { Minecraft.getInstance().getTextureManager().release(oldest.getValue().tex().id()); it.remove(); }
        }
        var t = sprite.tex();
        float w = t.width() / ppu, h = t.height() / ppu, x = cx - w / 2, y = cy - h / 2;
        float off = Math.max(1f, ppu * .08f) / ppu;
        int shadow = ((int) (((color >>> 24) & 0xFF) * .55f) << 24) | 0x05080C;
        quad(g, t, x + off, y + off * 1.4f, x + w + off, y + h + off * 1.4f, 0, 0, 1, 1, shadow, shadow, shadow, shadow);
        quad(g, t, x, y, x + w, y + h, 0, 0, 1, 1, color, color, color, color);
    }

    private static Sprite bake(Font f, TextKey key) {
        float pixelSize = key.pixelSize64() / 64f;
        var layout = new TextLayout(key.value(), f.deriveFont(pixelSize), CONTEXT);
        var bounds = layout.getPixelBounds(CONTEXT, 0, 0);
        var image = new BufferedImage(Math.max(1, bounds.width) + PADDING * 2, Math.max(1, bounds.height) + PADDING * 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(Color.WHITE);
            layout.draw(graphics, PADDING - bounds.x, PADDING - bounds.y);
        } finally { graphics.dispose(); }
        var pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                pixels.setPixel(x, y, (image.getRGB(x, y) & 0xFF000000) | 0x00FFFFFF);
        return new Sprite(upload(pixels, false));
    }

    /** Straight-alpha textured GUI layer that keeps the texture's own (trilinear) filter instead of forcing nearest. */
    private static final class Layer extends RenderType {
        Layer(ResourceLocation id, boolean mipmap) {
            super("chacademy_motion_hud", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536, false, false,
                () -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    RenderSystem.disableCull();
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    RenderSystem.depthMask(false);
                    RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                    RenderSystem.setShaderTexture(0, id);
                    var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
                    texture.setFilter(true, mipmap);
                },
                () -> {
                    RenderSystem.depthMask(true);
                    RenderSystem.enableCull();
                    RenderSystem.disableBlend();
                    RenderSystem.defaultBlendFunc();
                });
        }
    }
}
