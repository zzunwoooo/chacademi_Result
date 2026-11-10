package kr.kkaegul.client.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import kr.kkaegul.client.ui.TextRaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI 그림/글자 GPU 캐시.
 * 원본 PNG 는 그대로 두고, 알파를 곱한(premultiplied) 사본에 밉맵을 만들어 올린다 → 크게 줄여도 테두리가 깨끗하다 (차카데미 방식).
 * 그림은 처음 쓸 때 백그라운드에서 읽고 다음 프레임에 올린다. 한동안 안 쓴 그림은 내린다(VRAM 절약).
 */
public final class Gfx {
    private static final Logger LOG = LoggerFactory.getLogger("kkaegul-ui");
    private static final ShaderProgramKey PROGRAM = new ShaderProgramKey(Identifier.of("kkaegul", "core/ui_premultiplied"), VertexFormats.POSITION_TEXTURE_COLOR, Defines.EMPTY);
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kkaegul-ui-images");
        t.setDaemon(true);
        return t;
    });

    public record Tex(Identifier id, int width, int height, RenderLayer layer) {}
    /** 글자 텍스처 + 래스터 기준점 (픽셀) */
    public record TextTex(Tex tex, float left, float baseline, float advance) {}

    private final MinecraftClient client;
    private final Map<String, Tex> images = new HashMap<>();
    private final Map<String, Long> used = new HashMap<>();
    private final Map<String, int[]> sizes = new ConcurrentHashMap<>();
    private final Map<String, NativeImage> decoded = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> missing = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, TextTex> texts = new LinkedHashMap<>(256, .75f, true);
    private long textBytes;
    private int serial;
    private long frame;
    private boolean fontsTried, preloaded;
    /** 미리 읽어두고 내리지 않는 그림 (메뉴·HUD: 처음 열 때 렉 방지) */
    private final java.util.Set<String> pinned = ConcurrentHashMap.newKeySet();
    private static final String[] PRELOAD = {"menu/", "hud/", "notice/", "gacha_machine/", "gacha/", "shop/", "dropdown/", "wardrobe/"};

    public Gfx(MinecraftClient client) { this.client = client; }

    public void beginFrame() {
        frame++;
        if (!fontsTried) {
            fontsTried = true;
            loadFonts(client.getResourceManager());
        }
        if (!preloaded && frame > 40) {
            preloaded = true;
            try {
                var found = client.getResourceManager().findResources("textures/ui", id -> id.getNamespace().equals("kkaegul") && id.getPath().endsWith(".png"));
                for (Identifier rid : found.keySet()) {
                    String name = rid.getPath().substring("textures/ui/".length(), rid.getPath().length() - 4);
                    for (String pre : PRELOAD) if (name.startsWith(pre)) { pinned.add(name); image(name); break; }
                }
            } catch (RuntimeException e) { LOG.debug("미리 읽기 실패", e); }
        }
        // 백그라운드에서 읽어둔 그림을 프레임당 몇 장씩 올린다
        int n = 0;
        for (Iterator<Map.Entry<String, NativeImage>> it = decoded.entrySet().iterator(); it.hasNext() && n < 6; n++) {
            var e = it.next();
            it.remove();
            images.put(e.getKey(), upload(e.getValue(), true, "img"));
            pending.remove(e.getKey());
        }
        // 20초 넘게 안 쓴 그림 내리기
        if (frame % 600 == 0) {
            long now = System.currentTimeMillis();
            used.entrySet().removeIf(e -> {
                if (now - e.getValue() < 20_000 || pinned.contains(e.getKey())) return false;
                Tex t = images.remove(e.getKey());
                if (t != null) client.getTextureManager().destroyTexture(t.id());
                return true;
            });
        }
        // 글자 캐시 상한
        Iterator<Map.Entry<String, TextTex>> it = texts.entrySet().iterator();
        while ((texts.size() > 600 || textBytes > 24L << 20) && it.hasNext()) {
            Tex t = it.next().getValue().tex();
            textBytes -= (long) t.width() * t.height() * 4;
            client.getTextureManager().destroyTexture(t.id());
            it.remove();
        }
    }

    public static void loadFonts(ResourceManager rm) {
        if (TextRaster.ready()) return;
        try (var c = rm.getResourceOrThrow(Identifier.of("kkaegul", "font/jua.ttf")).getInputStream();
             var a = rm.getResourceOrThrow(Identifier.of("kkaegul", "font/pretendard_semibold.ttf")).getInputStream();
             var b = rm.getResourceOrThrow(Identifier.of("kkaegul", "font/pretendard_extrabold.ttf")).getInputStream()) {
            TextRaster.load(c, a, b);
        } catch (Exception e) {
            LOG.warn("UI 글꼴을 못 읽었어요", e);
        }
    }

    private static Identifier path(String id) { return Identifier.of("kkaegul", "textures/ui/" + id + ".png"); }

    /** 그림 (없거나 아직 읽는 중이면 null) */
    public Tex image(String id) {
        Tex t = images.get(id);
        used.put(id, System.currentTimeMillis());
        if (t != null) return t;
        if (missing.containsKey(id)) return null;
        NativeImage ready = decoded.remove(id);
        if (ready != null) {
            t = upload(ready, true, "img");
            images.put(id, t);
            pending.remove(id);
            return t;
        }
        if (pending.putIfAbsent(id, Boolean.TRUE) == null) {
            ResourceManager rm = client.getResourceManager();
            DECODER.execute(() -> {
                try {
                    var res = rm.getResource(path(id));
                    if (res.isEmpty()) { missing.put(id, Boolean.TRUE); pending.remove(id); return; }
                    NativeImage img;
                    try (var in = res.get().getInputStream()) { img = NativeImage.read(in); }
                    sizes.put(id, new int[]{img.getWidth(), img.getHeight()});
                    img.apply(Gfx::premultiply);
                    decoded.put(id, img);
                } catch (Exception e) {
                    LOG.warn("UI 그림 실패 {}", id, e);
                    missing.put(id, Boolean.TRUE);
                    pending.remove(id);
                }
            });
        }
        return null;
    }

    /** 원본 크기 (그림을 아직 못 읽었으면 null) */
    public int[] size(String id) {
        Tex t = images.get(id);
        if (t != null) return new int[]{t.width(), t.height()};
        int[] s = sizes.get(id);
        if (s == null) image(id);
        return s;
    }

    public boolean missing(String id) { return missing.containsKey(id); }

    /** 미리 읽어두기 */
    public void prefetch(String... ids) { for (String id : ids) image(id); }

    static int premultiply(int pixel) {
        int a = pixel >>> 24;
        int c0 = ((pixel & 255) * a + 127) / 255, c1 = (((pixel >>> 8) & 255) * a + 127) / 255, c2 = (((pixel >>> 16) & 255) * a + 127) / 255;
        return a << 24 | c2 << 16 | c1 << 8 | c0;
    }

    /** 최종 픽셀 크기로 래스터화한 글자 */
    public TextTex text(String s, float px, int rgb, int style, String key) {
        TextTex cached = texts.get(key);
        if (cached != null) return cached;
        TextRaster.Raster r = TextRaster.raster(s, px, rgb, style);
        if (r == null) return null;
        BufferedImage bi = r.image();
        NativeImage img = new NativeImage(bi.getWidth(), bi.getHeight(), false);
        for (int y = 0; y < bi.getHeight(); y++)
            for (int x = 0; x < bi.getWidth(); x++) img.setColorArgb(x, y, premultiplyArgb(bi.getRGB(x, y)));
        Tex t = upload(img, false, "txt");
        TextTex tt = new TextTex(t, r.left(), r.baseline(), r.advance());
        texts.put(key, tt);
        textBytes += (long) t.width() * t.height() * 4;
        return tt;
    }

    private static int premultiplyArgb(int argb) {
        int a = argb >>> 24;
        int r = ((argb >> 16) & 255) * a / 255, g = ((argb >> 8) & 255) * a / 255, b = (argb & 255) * a / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** 이미 알파를 곱한 이미지를 그대로 올리기 */
    public Tex uploadRaw(NativeImage image) { return upload(image, false, "raw"); }

    private Tex upload(NativeImage image, boolean mip, String kind) {
        int w = image.getWidth(), h = image.getHeight();
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.bindTexture();
        if (mip) {
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(w, h));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, levels);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        }
        texture.setClamp(true);
        texture.setFilter(true, mip);
        Identifier id = Identifier.of("kkaegul", "runtime/" + kind + "/" + serial++);
        client.getTextureManager().registerTexture(id, texture);
        return new Tex(id, w, h, new UiLayer(client, id, mip));
    }

    public void close() {
        images.values().forEach(t -> client.getTextureManager().destroyTexture(t.id()));
        texts.values().forEach(t -> client.getTextureManager().destroyTexture(t.tex().id()));
        images.clear();
        texts.clear();
        used.clear();
        textBytes = 0;
    }

    /** 알파 곱한 텍스처용 블렌드(ONE, ONE_MINUS_SRC_ALPHA) */
    private static final class UiLayer extends RenderLayer {
        UiLayer(MinecraftClient client, Identifier id, boolean mip) {
            super("kkaegul_ui", VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS, 1536, false, false,
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                        RenderSystem.disableCull();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL11.GL_LEQUAL);
                        RenderSystem.depthMask(false);
                        RenderSystem.setShader(PROGRAM);
                        RenderSystem.setShaderTexture(0, id);
                        client.getTextureManager().getTexture(id).setFilter(true, mip);
                    }, () -> {
                        RenderSystem.depthMask(true);
                        RenderSystem.enableCull();
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
        }
    }
}
