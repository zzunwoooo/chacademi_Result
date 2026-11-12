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
    // [기능:파티던전 시작]
    /** 글꼴: BOLD = Pretendard Bold (기본), MEDIUM = Pretendard Medium (항목 이름), SERIF = 마루부리 Bold (제목·기록 시간·큰 버튼). */
    enum Face {
        BOLD("pretendard_bold"), MEDIUM("pretendard_medium"), SERIF("maruburi_bold");
        final String file;
        Face(String file) { this.file = file; }
    }
    private record TextKey(String value, int pixelSize64, Face face) {
        TextKey(String value, int pixelSize64) { this(value, pixelSize64, Face.BOLD); }
    }
    // [기능:파티던전 끝]

    private static final Map<String, Optional<Tex>> IMAGES = new HashMap<>();
    private static final Map<TextKey, Tex> TEXT = new LinkedHashMap<>(64, .75f, true);
    private static final Map<String, Optional<Tex>> WORLD_TEXT = new HashMap<>();
    private static final Map<String, RenderType> WORLD_LAYERS = new HashMap<>();
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private static final int PADDING = 2;
    // [기능:파티던전 시작] 글꼴 여러 개 (Face 별로 한 번만 읽는다)
    private static final Map<Face, Optional<Font>> FONTS = new java.util.EnumMap<>(Face.class);
    // [기능:파티던전 끝]
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
        FONTS.clear();
        SpellIcons.reset();   // [기능:탑아이콘]
    }

    /** 흰 4×4 그림 (색만 입혀 칠할 때: 문 홀로그램 면). */
    static Optional<Tex> white() {
        return IMAGES.computeIfAbsent("white", k -> {
            var img = new NativeImage(4, 4, false);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setPixel(x, y, 0xFFFFFFFF);
            return Optional.of(upload(img, false));
        });
    }

    /** 이 모드 그림: textures/gui/&lt;path&gt;.png (예: "panels/modal-panel"). */
    static Optional<Tex> ui(String path) { return IMAGES.computeIfAbsent("ui:" + path, k -> load("chacademi_dungeon", "textures/gui/" + path + ".png", false)); }

    /** 스토리 모드 퀘스트 그림 (line·book·check·portal_ring …). silver=true 면 은색으로 바꾼 사본. */
    static Optional<Tex> quest(String name, boolean silver) {
        return IMAGES.computeIfAbsent((silver ? "silver:" : "quest:") + name, k -> load("chaca_story", "textures/quest/" + name + ".png", silver));
    }

    // [기능:파티던전 시작]
    /** MagicCodex 그림 (예: "textures/gui/kyudichi/ping/ally.png" = 큐디치 핑). 없으면 빈 값. */
    static Optional<Tex> mc(String path) { return IMAGES.computeIfAbsent("mc:" + path, k -> load("magiccodex", path, false)); }

    // [기능:탑아이콘 시작]
    /** MagicCodex 그림을 긴 변 max 픽셀 이하로 줄여서 (마법 아이콘 원본 1254px — 그대로 올리면 장당 수 MB). */
    static Optional<Tex> mcSmall(String path, int max) { return IMAGES.computeIfAbsent("mcs" + max + ":" + path, k -> loadSmall("magiccodex", path, max)); }

    private static Optional<Tex> loadSmall(String namespace, String path, int max) {
        var source = ResourceLocation.fromNamespaceAndPath(namespace, path);
        try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(source).open(); var src = NativeImage.read(stream)) {
            int sw = src.getWidth(), sh = src.getHeight();
            float k = Math.min(1f, max / (float) Math.max(sw, sh));
            int dw = Math.max(1, Math.round(sw * k)), dh = Math.max(1, Math.round(sh * k));
            var dst = new NativeImage(dw, dh, false);
            // 상자 평균 (투명도 가중) — 크게 줄여도 테두리가 거칠지 않게
            for (int y = 0; y < dh; y++) {
                int y0 = y * sh / dh, y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
                for (int x = 0; x < dw; x++) {
                    int x0 = x * sw / dw, x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                    long a = 0, r = 0, gg = 0, b = 0; int n = 0;
                    for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                        int c = src.getPixel(xx, yy), ca = c >>> 24;
                        a += ca; r += (long) ((c >> 16) & 0xFF) * ca; gg += (long) ((c >> 8) & 0xFF) * ca; b += (long) (c & 0xFF) * ca; n++;
                    }
                    int oa = (int) (a / n);
                    dst.setPixel(x, y, a == 0 ? 0 : (oa << 24) | ((int) (r / a) << 16) | ((int) (gg / a) << 8) | (int) (b / a));
                }
            }
            return Optional.of(upload(dst, true));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("MagicCodex icon failed: {}", source, error);
            return Optional.empty();
        }
    }
    // [기능:탑아이콘 끝]

    /** 단색 사각형 (실수 좌표). */
    static void rect(GuiGraphics g, float x, float y, float w, float h, int color) { white().ifPresent(t -> quad(g, t, x, y, x + w, y + h, 0, 0, 1, 1, color)); }

    /** 128px 아이콘을 가운데 (cx, cy) 기준으로. */
    static void icon(GuiGraphics g, String path, float cx, float cy, float size, int color) { ui(g, path, cx - size / 2, cy - size / 2, size, size, color); }
    // [기능:파티던전 끝]

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

    private static Font font() { return font(Face.BOLD); }

    // [기능:파티던전 시작]
    private static Font font(Face face) {
        return FONTS.computeIfAbsent(face, k -> {
            var id = ResourceLocation.fromNamespaceAndPath("magiccodex", "font/" + k.file + ".ttf");
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
                return Optional.of(Font.createFont(Font.TRUETYPE_FONT, stream));
            } catch (Exception missing) {
                return k == Face.BOLD ? Optional.empty() : Optional.ofNullable(font(Face.BOLD));
            }
        }).orElse(null);
    }
    // [기능:파티던전 끝]

    private static float pixelsPerUnit(GuiGraphics g) {
        return Math.abs(g.pose().last().pose().m11()) * (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    static float width(GuiGraphics g, String value, float size) { return width(g, Face.BOLD, value, size); }

    static float width(GuiGraphics g, Face face, String value, float size) {
        var f = font(face);
        if (f == null) return Minecraft.getInstance().font.width(value) * size / 9f;
        return value.isEmpty() ? 0 : new TextLayout(value, f.deriveFont(size), CONTEXT).getAdvance();
    }

    /** 왼쪽 정렬 글자 (세로 가운데 = cy). 그린 폭을 돌려준다. */
    static float text(GuiGraphics g, String value, float x, float cy, float size, int color) { return text(g, Face.BOLD, value, x, cy, size, color); }

    static float text(GuiGraphics g, Face face, String value, float x, float cy, float size, int color) {
        if (value == null || value.isBlank()) return 0;
        var f = font(face);
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
        var t = TEXT.computeIfAbsent(new TextKey(value, px64, face), key -> bake(f, key, false));
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

    static void textCenter(GuiGraphics g, String value, float cx, float cy, float size, int color) { textCenter(g, Face.BOLD, value, cx, cy, size, color); }

    static void textRight(GuiGraphics g, String value, float right, float cy, float size, int color) { textRight(g, Face.BOLD, value, right, cy, size, color); }

    static void textCenter(GuiGraphics g, Face face, String value, float cx, float cy, float size, int color) {
        text(g, face, value, cx - width(g, face, value, size) / 2, cy, size, color);
    }

    static void textRight(GuiGraphics g, Face face, String value, float right, float cy, float size, int color) {
        text(g, face, value, right - width(g, face, value, size), cy, size, color);
    }

    /** 폭에 맞게 자른 글자 (넘치면 …). */
    static String fit(GuiGraphics g, String value, float size, float maxW) { return fit(g, Face.BOLD, value, size, maxW); }

    static String fit(GuiGraphics g, Face face, String value, float size, float maxW) {
        if (value == null) return "";
        if (width(g, face, value, size) <= maxW) return value;
        String s = value;
        while (s.length() > 1 && width(g, face, s + "…", size) > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // [기능:파티던전 시작]
    /** 폭에 맞게 줄인 글자 크기 (최소 min). */
    static float shrink(GuiGraphics g, Face face, String value, float size, float maxW, float min) {
        float w = width(g, face, value, size);
        return w <= maxW ? size : Math.max(min, size * maxW / w);
    }
    // [기능:파티던전 끝]

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
