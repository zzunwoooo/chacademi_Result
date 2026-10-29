package kr.chacademi.dungeon;

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
 * 그림·글자 그리기 (스토리 모드 QuestArt 와 같은 방식의 이 모드 사본): 밉맵+선형 필터 그림, 실수 좌표 사각형,
 * MagicCodex Pretendard 글꼴을 실제 화면 픽셀 크기로 구워서 그림 (차카데미 글자 규칙: 채운 색 + 검은 외곽선).
 * 추가: 9-slice(모서리 보존), 가로 3-slice(버튼), 스토리 퀘스트 그림을 은색으로 바꾼 사본 (던전 단계창).
 */
final class Art {
    record Tex(ResourceLocation id, int width, int height, RenderType layer) {}
    private record TextKey(String value, int pixelSize64) {}

    private static final Map<String, Optional<Tex>> IMAGES = new HashMap<>();
    private static final Map<TextKey, Tex> TEXT = new LinkedHashMap<>(64, .75f, true);
    private static final Map<String, Optional<Tex>> WORLD_TEXT = new HashMap<>();
    private static final Map<String, RenderType> WORLD_LAYERS = new HashMap<>();
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private static final int PADDING = 2;
    private static Font font;
    private static boolean fontTried;
    private static int serial;

    private Art() {}

    static void reset() {
        var textures = Minecraft.getInstance().getTextureManager();
        IMAGES.values().forEach(t -> t.ifPresent(v -> textures.release(v.id())));
        IMAGES.clear();
        TEXT.values().forEach(t -> textures.release(t.id()));
        TEXT.clear();
        WORLD_TEXT.values().forEach(t -> t.ifPresent(v -> textures.release(v.id())));
        WORLD_TEXT.clear();
        WORLD_LAYERS.clear();
        font = null; fontTried = false;
    }

    /** 이 모드 그림: textures/gui/&lt;path&gt;.png (예: "panels/modal-panel"). */
    static Optional<Tex> ui(String path) { return IMAGES.computeIfAbsent("ui:" + path, k -> load("chacademi_dungeon", "textures/gui/" + path + ".png", false)); }

    /** 스토리 모드 퀘스트 그림 (line·book·check·portal_ring …). silver=true 면 은색으로 바꾼 사본. */
    static Optional<Tex> quest(String name, boolean silver) {
        return IMAGES.computeIfAbsent((silver ? "silver:" : "quest:") + name, k -> load("chaca_story", "textures/quest/" + name + ".png", silver));
    }

    private static Optional<Tex> load(String namespace, String path, boolean silver) {
        var mc = Minecraft.getInstance();
        var source = ResourceLocation.fromNamespaceAndPath(namespace, path);
        try (var stream = mc.getResourceManager().getResourceOrThrow(source).open()) {
            var image = NativeImage.read(stream);
            if (silver) toSilver(image);
            return Optional.of(upload(image, true));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("Dungeon UI texture failed: {}", source, error);
            return Optional.empty();
        }
    }

    /** 금색 장식 → 은색: 밝기만 남기고 살짝 푸른 은빛 (투명도 그대로). */
    private static void toSilver(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int c = image.getPixel(x, y); // ARGB
                int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                float lum = (0.30f * r + 0.59f * g + 0.11f * b) / 255f;
                float l = Math.min(1f, lum * 1.12f + 0.04f);
                int nr = Math.round(l * 228), ng = Math.round(l * 234), nb = Math.min(255, Math.round(l * 246) + 6);
                image.setPixel(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
            }
    }

    private static Tex upload(NativeImage image, boolean mipmap) {
        int width = image.getWidth(), height = image.getHeight();
        var texture = new DynamicTexture(image);
        var id = ResourceLocation.fromNamespaceAndPath("chacademi_dungeon", "runtime/" + serial++);
        texture.bind();
        if (mipmap) {
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(width, height));
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

    static void quad(GuiGraphics g, Tex t, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int color) {
        Matrix4f m = g.pose().last().pose();
        g.drawSpecial(buffers -> {
            var vc = buffers.getBuffer(t.layer());
            vc.addVertex(m, x0, y0, 0).setUv(u0, v0).setColor(color);
            vc.addVertex(m, x0, y1, 0).setUv(u0, v1).setColor(color);
            vc.addVertex(m, x1, y1, 0).setUv(u1, v1).setColor(color);
            vc.addVertex(m, x1, y0, 0).setUv(u1, v0).setColor(color);
        });
    }

    static void image(GuiGraphics g, Optional<Tex> tex, float x, float y, float w, float h, int color) {
        tex.ifPresent(t -> quad(g, t, x, y, x + w, y + h, 0, 0, 1, 1, color));
    }

    static void ui(GuiGraphics g, String path, float x, float y, float w, float h, int color) { image(g, ui(path), x, y, w, h, color); }

    /** 그림을 상자에 가득 (비율 유지, 넘치는 쪽을 잘라냄 = CSS cover). */
    static void cover(GuiGraphics g, String path, float x, float y, float w, float h, int color) {
        ui(path).ifPresent(t -> {
            float ia = t.width() / (float) t.height(), ba = w / h;
            float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
            if (ia > ba) { float keep = ba / ia; u0 = (1 - keep) / 2; u1 = u0 + keep; }
            else { float keep = ia / ba; v0 = (1 - keep) / 2; v1 = v0 + keep; }
            quad(g, t, x, y, x + w, y + h, u0, v0, u1, v1, color);
        });
    }

    /**
     * 9-slice: 그림 모서리 src 픽셀을 화면 corner 크기로 그대로 두고 변과 가운데만 늘린다 (테두리 장식이 늘어나지 않게).
     */
    static void nine(GuiGraphics g, String path, float x, float y, float w, float h, int src, float corner, int color) {
        ui(path).ifPresent(t -> {
            float c = Math.min(corner, Math.min(w, h) / 2);
            float su = src / (float) t.width(), sv = src / (float) t.height();
            float[] xs = {x, x + c, x + w - c, x + w}, ys = {y, y + c, y + h - c, y + h};
            float[] us = {0, su, 1 - su, 1}, vs = {0, sv, 1 - sv, 1};
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (xs[i + 1] > xs[i] && ys[j + 1] > ys[j]) quad(g, t, xs[i], ys[j], xs[i + 1], ys[j + 1], us[i], vs[j], us[i + 1], vs[j + 1], color);
        });
    }

    /** 가로 3-slice (버튼): 좌우 src 픽셀 보존, 높이는 상자에 맞춰 비율대로. */
    static void hslice(GuiGraphics g, String path, float x, float y, float w, float h, int src, int color) {
        ui(path).ifPresent(t -> {
            float side = src * h / t.height();
            side = Math.min(side, w / 2);
            float su = src / (float) t.width();
            quad(g, t, x, y, x + side, y + h, 0, 0, su, 1, color);
            if (w > side * 2) quad(g, t, x + side, y, x + w - side, y + h, su, 0, 1 - su, 1, color);
            quad(g, t, x + w - side, y, x + w, y + h, 1 - su, 0, 1, 1, color);
        });
    }

    // ---- 글자

    private static Font font() {
        if (!fontTried) {
            fontTried = true;
            var id = ResourceLocation.fromNamespaceAndPath("magiccodex", "font/pretendard_bold.ttf");
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
                font = Font.createFont(Font.TRUETYPE_FONT, stream);
            } catch (Exception missing) {
                font = null;
            }
        }
        return font;
    }

    private static float pixelsPerUnit(GuiGraphics g) {
        return Math.abs(g.pose().last().pose().m11()) * (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    static float width(GuiGraphics g, String value, float size) {
        var f = font();
        if (f == null) return Minecraft.getInstance().font.width(value) * size / 9f;
        return value.isEmpty() ? 0 : new TextLayout(value, f.deriveFont(size), CONTEXT).getAdvance();
    }

    /** 왼쪽 정렬 글자 (세로 가운데 = cy). 그린 폭을 돌려준다. */
    static float text(GuiGraphics g, String value, float x, float cy, float size, int color) {
        if (value == null || value.isBlank()) return 0;
        var f = font();
        int alpha = (color >>> 24) & 0xFF;
        int outline = ((int) (alpha * .9f) << 24) | 0x0A0A0E;
        if (f == null) {
            var mc = Minecraft.getInstance();
            var pose = g.pose(); pose.pushPose();
            try {
                pose.translate(x, cy, 0); pose.scale(size / 9f, size / 9f, 1);
                for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) g.drawString(mc.font, value, o[0], -4 + o[1], outline, false);
                g.drawString(mc.font, value, 0, -4, color, false);
            } finally { pose.popPose(); }
            return mc.font.width(value) * size / 9f;
        }
        float ppu = pixelsPerUnit(g);
        int px64 = Math.max(64, Math.round(size * ppu * 64));
        var t = TEXT.computeIfAbsent(new TextKey(value, px64), key -> bake(f, key, false));
        if (TEXT.size() > 200) {
            var it = TEXT.entrySet().iterator();
            var oldest = it.next();
            if (oldest.getValue() != t) { Minecraft.getInstance().getTextureManager().release(oldest.getValue().id()); it.remove(); }
        }
        float w = t.width() / ppu, h = t.height() / ppu, y = cy - h / 2;
        float o = Math.max(1f, ppu * .09f) / ppu;
        float d = o * .72f;
        float[][] offs = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-d, -d}, {d, -d}, {-d, d}, {d, d}};
        for (float[] off : offs) quad(g, t, x + off[0], y + off[1], x + w + off[0], y + h + off[1], 0, 0, 1, 1, outline);
        quad(g, t, x, y, x + w, y + h, 0, 0, 1, 1, color);
        return w - PADDING * 2 / ppu;
    }

    static void textCenter(GuiGraphics g, String value, float cx, float cy, float size, int color) {
        text(g, value, cx - width(g, value, size) / 2, cy, size, color);
    }

    static void textRight(GuiGraphics g, String value, float right, float cy, float size, int color) {
        text(g, value, right - width(g, value, size), cy, size, color);
    }

    /** 폭에 맞게 자른 글자 (넘치면 …). */
    static String fit(GuiGraphics g, String value, float size, float maxW) {
        if (value == null) return "";
        if (width(g, value, size) <= maxW) return value;
        String s = value;
        while (s.length() > 1 && width(g, s + "…", size) > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static Optional<Tex> worldText(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return WORLD_TEXT.computeIfAbsent(value, v -> {
            var f = font();
            return f == null ? Optional.empty() : Optional.of(bake(f, new TextKey(v, 72 * 64), true));
        });
    }

    private static Tex bake(Font f, TextKey key, boolean mipmap) {
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
        return upload(pixels, mipmap);
    }

    // ---- 월드 (포탈 홀로그램)

    static RenderType worldLayer(Tex t, boolean additive) {
        if (shaderPack()) return RenderType.entityTranslucentEmissive(t.id());
        return WORLD_LAYERS.computeIfAbsent(t.id() + (additive ? "+" : ""), k -> new WorldLayer(t.id(), additive));
    }

    private static java.lang.reflect.Method irisInUse;
    private static Object irisApi;
    private static boolean irisTried;

    static boolean shaderPack() {
        if (!irisTried) {
            irisTried = true;
            try {
                var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) { irisApi = null; irisInUse = null; }
        }
        if (irisInUse == null) return false;
        try { return Boolean.TRUE.equals(irisInUse.invoke(irisApi)); } catch (Throwable e) { return false; }
    }

    private static final class WorldLayer extends RenderType {
        WorldLayer(ResourceLocation id, boolean additive) {
            super(additive ? "chacademi_dungeon_world_add" : "chacademi_dungeon_world", DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS, 1536, false, false,
                () -> {
                    RenderSystem.enableBlend();
                    if (additive) RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                    else RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    RenderSystem.disableCull();
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    RenderSystem.depthMask(false);
                    RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                    RenderSystem.setShaderTexture(0, id);
                    Minecraft.getInstance().getTextureManager().getTexture(id).setFilter(true, true);
                },
                () -> {
                    RenderSystem.depthMask(true);
                    RenderSystem.enableCull();
                    RenderSystem.disableBlend();
                    RenderSystem.defaultBlendFunc();
                });
        }
    }

    private static final class Layer extends RenderType {
        Layer(ResourceLocation id, boolean mipmap) {
            super("chacademi_dungeon_ui", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536, false, false,
                () -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    RenderSystem.disableCull();
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    RenderSystem.depthMask(false);
                    RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                    RenderSystem.setShaderTexture(0, id);
                    Minecraft.getInstance().getTextureManager().getTexture(id).setFilter(true, mipmap);
                },
                () -> {
                    RenderSystem.depthMask(true);
                    RenderSystem.enableCull();
                    RenderSystem.disableBlend();
                    RenderSystem.defaultBlendFunc();
                });
        }
    }

    static int argb(int a, int rgb) { return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF); }
    static int argb(float a, float r, float g, float b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }
    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }
}
