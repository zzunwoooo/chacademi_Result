package school.magiccodex.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * Item icons drawn at their exact on-screen pixel size (owner 2026-10-24: "16px로 줄였다 키우는 게 아니고, 저 위치에
 * 새로 그려서 픽셀 깨짐 없이").
 *
 * For a high-resolution icon (the 128~256px item art, see {@link ItemIconTextures}) at a given place, the device
 * pixel size is computed from the current GUI matrix and the window scale; the original PNG is resampled once on
 * a worker thread straight to that size (Lanczos-3 on premultiplied colour, the same quality as shrinking it in an
 * image editor) and uploaded. It is then drawn 1:1 on the device pixel grid with nearest sampling, so the GPU never
 * resamples it again. Until that copy is ready, and for vanilla 16px / 3D block items, the caller falls back to the
 * normal item draw (trilinear for high-res icons, vanilla models for the rest).
 *
 * Copies are keyed by (icon, pixel width, pixel height); a GUI scale or window change makes a new size. At most
 * {@link #MAX} copies are kept (least recently used dropped). Render thread only.
 */
public final class ExactItemIcons {
    private ExactItemIcons() {}

    private static final int MAX = 384, UPLOADS_PER_TICK = 6, MAX_PX = 512;
    private record Key(Identifier icon, int w, int h) {}
    private record Pixels(int w, int h, int[] premultiplied) {}
    private record Sized(Identifier id, RenderLayer layer) {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "magiccodex-exact-icons"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private static final LinkedHashMap<Key, Optional<Sized>> READY = new LinkedHashMap<>(64, 0.75f, true);
    private static AsyncUiLoader<Key, Optional<Pixels>> pending;
    private static final ItemRenderState SCRATCH = new ItemRenderState();
    private static int serial, generation;
    private static boolean failed;

    static void initialize() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> pump());
    }

    /**
     * Draws the stack's high-resolution icon filling the square (x, y, size) in the current matrix space.
     * @return false when the caller must draw the item the normal way this frame.
     */
    public static boolean draw(DrawContext ctx, ItemStack stack, float x, float y, float size) {
        if (failed || stack == null || stack.isEmpty() || size <= 0) return false;
        Identifier icon = iconKey(stack);
        if (icon == null) return false;
        var aspect = ItemIconTextures.aspect(icon);
        if (aspect == null) return false;
        var m = ctx.getMatrices().peek().getPositionMatrix();
        float scale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        float sx = m.m00(), sy = m.m11();
        if (Math.abs(m.m01()) > 1e-4f || Math.abs(m.m10()) > 1e-4f || sx <= 0 || sy <= 0) return false; // rotated: not ours
        // Fit non-square art inside the square, same as the 16x16 path.
        float fw = aspect[0], fh = aspect[1];
        float lx = x + (1 - fw) * size / 2, ly = y + (1 - fh) * size / 2, lw = size * fw, lh = size * fh;
        // Device pixels of that rectangle, snapped to whole pixels.
        int pw = Math.round(lw * sx * scale), ph = Math.round(lh * sy * scale);
        if (pw < 2 || ph < 2 || pw > MAX_PX || ph > MAX_PX) return false;
        int px0 = Math.round((m.m30() + lx * sx) * scale), py0 = Math.round((m.m31() + ly * sy) * scale);
        var key = new Key(icon, pw, ph);
        var sized = READY.get(key);
        if (sized == null) { request(key); return false; }
        if (sized.isEmpty()) return false;
        // Back to local coordinates for exactly those pixels.
        float x0 = (px0 / scale - m.m30()) / sx, y0 = (py0 / scale - m.m31()) / sy;
        float x1 = ((px0 + pw) / scale - m.m30()) / sx, y1 = ((py0 + ph) / scale - m.m31()) / sy;
        var stackM = ctx.getMatrices();
        stackM.push();
        try {
            stackM.translate(0, 0, 150);
            var pos = stackM.peek().getPositionMatrix();
            var t = sized.get();
            ctx.draw(p -> {
                var v = p.getBuffer(t.layer());
                v.vertex(pos, x0, y0, 0).texture(0, 0).color(0xFFFFFFFF);
                v.vertex(pos, x0, y1, 0).texture(0, 1).color(0xFFFFFFFF);
                v.vertex(pos, x1, y1, 0).texture(1, 1).color(0xFFFFFFFF);
                v.vertex(pos, x1, y0, 0).texture(1, 0).color(0xFFFFFFFF);
            });
            ctx.draw();
            if (stack.hasGlint()) {
                stackM.push();
                stackM.translate(x0, y0, 0);
                stackM.scale((x1 - x0) / 16f, (y1 - y0) / 16f, 1);
                ItemIconTextures.glint(ctx, stackM.peek().getPositionMatrix());
                stackM.pop();
            }
        } catch (RuntimeException e) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Exact-size item icons disabled", e);
            return false;
        } finally { stackM.pop(); }
        return true;
    }

    /** The high-res icon key for this stack (items/ JSON path first, then the resolved GUI model, e.g. ItemsAdder). */
    static Identifier iconKey(ItemStack stack) {
        Identifier model = stack.get(DataComponentTypes.ITEM_MODEL);
        if (model != null && ItemIconTextures.present(model)) return model;
        var client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        try {
            client.getItemModelManager().update(SCRATCH, stack, ModelTransformationMode.GUI, client.world, client.player, 0);
            Identifier key = ((HiResItemState) SCRATCH).magiccodex$hiResModel();
            return key != null && ItemIconTextures.present(key) ? key : null;
        } catch (RuntimeException e) { return null; }
        finally { SCRATCH.clear(); }
    }

    // ---------------------------------------------------------------- loading

    private static void request(Key key) {
        if (pending == null) {
            var manager = MinecraftClient.getInstance().getResourceManager();
            int gen = generation;
            pending = new AsyncUiLoader<>(WORKER, 12, k -> {
                if (gen != generation) return Optional.empty();
                var decoded = ItemIconTextures.decode(manager, k.icon());
                if (decoded.isEmpty()) return Optional.empty();
                var d = decoded.get();
                return Optional.of(new Pixels(k.w(), k.h(), IconResampler.resample(d.argb(), d.width(), d.height(), k.w(), k.h())));
            }, p -> {});
        }
        pending.request(key);
    }

    static void pump() {
        if (pending == null) return;
        for (int i = 0; i < UPLOADS_PER_TICK; i++) {
            var ready = pending.takeReady();
            if (ready == null) return;
            var result = ready.getValue();
            if (READY.size() >= MAX) evictOldest();
            READY.put(ready.getKey(), result.error() == null ? result.value().flatMap(ExactItemIcons::upload) : Optional.empty());
        }
    }

    private static void evictOldest() {
        var it = READY.entrySet().iterator();
        if (!it.hasNext()) return;
        var oldest = it.next();
        oldest.getValue().ifPresent(s -> MinecraftClient.getInstance().getTextureManager().destroyTexture(s.id()));
        it.remove();
    }

    /** Resource reload / disconnect (called from ItemIconTextures.reset on the render thread). */
    static void reset() {
        if (pending != null) { pending.close(); pending = null; }
        var textures = MinecraftClient.getInstance().getTextureManager();
        for (var s : READY.values()) s.ifPresent(t -> textures.destroyTexture(t.id()));
        READY.clear();
        generation++;
        failed = false;
    }

    private static Optional<Sized> upload(Pixels p) {
        RenderSystem.assertOnRenderThread();
        NativeImage image = new NativeImage(p.w(), p.h(), false);
        NativeImageBackedTexture texture = null;
        try {
            for (int y = 0; y < p.h(); y++) for (int x = 0; x < p.w(); x++) image.setColorArgb(x, y, p.premultiplied()[y * p.w() + x]);
            texture = new NativeImageBackedTexture(image); image = null;
            texture.setFilter(false, false);   // drawn 1:1: never filtered again
            texture.setClamp(true);
            var id = Identifier.of("magiccodex", "runtime_exact_icon/" + generation + "/" + serial++);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            texture = null;
            return Optional.of(new Sized(id, new ExactLayer(id)));
        } catch (Exception e) {
            if (texture != null) texture.close();
            if (image != null) image.close();
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Exact item icon upload failed", e);
            return Optional.empty();
        }
    }

    private static final class ExactLayer extends RenderLayer {
        ExactLayer(Identifier id) {
            super("magiccodex_exact_item_icon", VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS, 256, false, false,
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                        RenderSystem.disableCull();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL11.GL_LEQUAL);
                        RenderSystem.depthMask(true);
                        RenderSystem.setShader(ItemIconTextures.PROGRAM);
                        RenderSystem.setShaderTexture(0, id);
                        var tex = MinecraftClient.getInstance().getTextureManager().getTexture(id);
                        tex.setFilter(false, false);
                    }, () -> {
                        RenderSystem.enableCull();
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
        }
    }
}
