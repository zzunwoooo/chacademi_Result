package school.magiccodex.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * GUI-only renderer for flat item icons whose texture is larger than 32px (the 128x128 icons of
 * the resource pack). Vanilla shrinks those with nearest sampling, so edges break into stray
 * pixels and gradients alias. Matching items are instead drawn smoothly, the same way as other
 * magiccodex UI images: a private GPU copy with premultiplied alpha, a full mip chain and
 * trilinear filtering, so the artwork looks like a scaled picture rather than a pixel grid.
 * Hand-held, third-person, dropped and framed items use {@link #renderWorld}: one flat, smoothly
 * filtered quad instead of vanilla's per-pixel extruded {@code item/generated} mesh (which, at 128px+,
 * shows a stair-stepped silhouette up close and a fringe of hairline side faces).
 * Non-square textures keep their aspect ratio (fitted and centred in the 16x16 item square).
 *
 * Only simple definitions qualify: {@code items/<id>.json} of type {@code minecraft:model}
 * without tints, whose model chain ends in {@code item/generated} with a single {@code layer0},
 * no custom GUI display transform and no animation. Anything else and icons still loading fall
 * back to vanilla for that frame. Enchanted icons get the vanilla glint layer over the icon pixels.
 */
public final class ItemIconTextures {
    private ItemIconTextures() {}

    static final int MIN_SIZE = 33;
    private static final int MAX_SIZE = 1024, MAX_TEXTURES = 256, UPLOADS_PER_TICK = 4;
    static final ShaderProgramKey PROGRAM = new ShaderProgramKey(
            Identifier.of("magiccodex", "core/item_icon"), VertexFormats.POSITION_TEXTURE_COLOR, Defines.EMPTY);
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "magiccodex-item-icons"); t.setDaemon(true); return t;
    });

    record Decoded(Identifier texture, int width, int height, int[] argb, int[] worldArgb) {}
    /** {@code id}: premultiplied GUI copy. {@code worldId}: straight-alpha copy for vanilla entity shaders. */
    private record Loaded(Identifier id, int width, int height, RenderLayer layer, Identifier worldId, RenderLayer worldLayer) {}

    /** Empty = this item model stays vanilla until the next resource reload. */
    private static final Map<Identifier, Optional<Loaded>> ICONS = new HashMap<>();
    private static AsyncUiLoader<Identifier, Optional<Decoded>> pending;
    private static int serial, generation;
    private static boolean announced;

    static void initialize() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> pump());
        ExactItemIcons.initialize();
    }

    /** True when this icon key has an uploaded high-res texture (requests it otherwise). Render thread. */
    static boolean present(Identifier key) {
        var known = ICONS.get(key);
        if (known == null) { request(key); return false; }
        return known.isPresent();
    }

    /** {fitW, fitH} of a present icon (non-square art keeps its ratio), or null. */
    static float[] aspect(Identifier key) {
        var known = ICONS.get(key);
        if (known == null || known.isEmpty()) return null;
        return new float[]{fitW(known.get()), fitH(known.get())};
    }

    /** Vanilla glint over a 16x16 square in the given matrix (used by ExactItemIcons). */
    static void glint(DrawContext ctx, org.joml.Matrix4f matrix) { drawGlint(ctx, matrix, 0, 0, 16, 16); }

    /**
     * GUI replacement after ItemModelManager.update: use the sprite vanilla actually selected,
     * including range_dispatch/custom_model_data. Draw into the caller's current framebuffer;
     * 16 is a coordinate extent, never an intermediate texture resolution. The projection and
     * matrix retain the caller's GUI scale, drag depth and model GUI display transform.
     */
    public static boolean drawResolved(DrawContext ctx, ItemStack stack, int x, int y, int z,
                                       net.minecraft.client.render.item.ItemRenderState state) {
        if (stack.isEmpty()) return false;
        var model = ((HiResItemState) state).magiccodex$singleFlatModel();
        Identifier key = flatSpriteKey(model);
        if (key == null) return false;
        var known = ICONS.get(key);
        if (known == null) { request(key); return false; }
        if (known.isEmpty()) return false;
        var t = known.get();
        float fw = fitW(t), fh = fitH(t);
        float x0 = (1 - fw) / 2, x1 = x0 + fw, y0 = (1 - fh) / 2, y1 = y0 + fh;
        ctx.draw();
        var m = ctx.getMatrices();
        m.push();
        try {
            // Same transform/depth sequence as DrawContext.drawItem in Minecraft 1.21.4.
            m.translate(x + 8, y + 8, 150 + (state.hasDepth() ? z : 0));
            m.scale(16, -16, 16);
            state.getTransformation().apply(false, m);
            m.translate(-0.5f, -0.5f, -0.5f);
            var matrix = m.peek().getPositionMatrix();
            ctx.draw(provider -> {
                var v = provider.getBuffer(t.layer());
                v.vertex(matrix, x0, y0, 0.5f).texture(0, 1).color(0xFFFFFFFF);
                v.vertex(matrix, x1, y0, 0.5f).texture(1, 1).color(0xFFFFFFFF);
                v.vertex(matrix, x1, y1, 0.5f).texture(1, 0).color(0xFFFFFFFF);
                v.vertex(matrix, x0, y1, 0.5f).texture(0, 0).color(0xFFFFFFFF);
            });
            ctx.draw();
            if (stack.hasGlint()) drawGlint(ctx, matrix, x0, y0, x1, y1);
        } finally { m.pop(); }
        announce(key);
        return true;
    }
    private static void announce(Identifier key) {
        if (!announced) { announced = true; org.slf4j.LoggerFactory.getLogger("magiccodex").info("High-res item icons active ({})", key); }
    }

    private static float fitW(Loaded t) { return t.width() >= t.height() ? 1f : (float) t.width() / t.height(); }
    private static float fitH(Loaded t) { return t.height() >= t.width() ? 1f : (float) t.height() / t.width(); }

    // ---------------------------------------------------------------- hand / third person / dropped / frame

    /**
     * Called from the ItemModelManager mixin after vanilla filled an ItemRenderState.
     * 1) items/&lt;id&gt;.json path for non-GUI item rendering.
     * 2) Otherwise the model vanilla actually resolved: one layer, flat item/generated-like quads,
     *    one texture larger than 32px. This also catches range_dispatch / custom_model_data
     *    definitions (ItemsAdder) that the JSON path cannot follow. GUI uses drawResolved instead.
     * @return icon key to draw as a smooth flat card, or null to keep vanilla.
     */
    public static Identifier stateKey(ItemStack stack, net.minecraft.item.ModelTransformationMode mode,
                                      net.minecraft.client.render.model.BakedModel resolved) {
        if (stack == null || stack.isEmpty() || stack.hasGlint()) return null;
        // GUI is owned exclusively by DrawContext's resolved-model hook. Never route it
        // through the entity/world shader (including during asynchronous icon loading).
        boolean gui = mode == net.minecraft.item.ModelTransformationMode.GUI;
        if (gui) return null;
        Identifier model = stack.get(DataComponentTypes.ITEM_MODEL);
        if (model != null && !gui) {
            var known = ICONS.get(model);
            if (known == null) request(model);
            else if (known.isPresent()) return model;
        }
        Identifier sprite = flatSpriteKey(resolved);
        if (sprite == null) return null;
        var known = ICONS.get(sprite);
        if (known == null) { request(sprite); return null; } // vanilla until the upload lands
        return known.isPresent() ? sprite : null;
    }

    /** Namespace of icon keys that point straight at an atlas sprite's PNG rather than an items/ JSON. */
    static final String SPRITE_NS = "magiccodex-sprite";
    private static final Map<net.minecraft.client.render.model.BakedModel, Identifier> FLAT = new IdentityHashMap<>();
    private static final Identifier NOT_FLAT = Identifier.of("magiccodex", "not_flat");
    private static final net.minecraft.util.math.random.Random QUAD_RANDOM = net.minecraft.util.math.random.Random.create(42);

    /** Cached per baked model (models are rebuilt on resource reload, when the cache is cleared). */
    static Identifier flatSpriteKey(net.minecraft.client.render.model.BakedModel model) {
        if (model == null) return null;
        var cached = FLAT.get(model);
        if (cached == null) {
            cached = NOT_FLAT;
            try { var k = computeFlatSpriteKey(model); if (k != null) cached = k; }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger("magiccodex").debug("Flat item check failed", error); }
            FLAT.put(model, cached);
        }
        return cached == NOT_FLAT ? null : cached;
    }

    private static Identifier computeFlatSpriteKey(net.minecraft.client.render.model.BakedModel model) {
        var sprite = model.getParticleSprite();
        if (sprite == null) return null;
        var contents = sprite.getContents();
        if (Math.max(contents.getWidth(), contents.getHeight()) < MIN_SIZE) return null; // vanilla 16px stays pixel art
        int quads = 0;
        for (int d = 0; d <= 6; d++) {
            var face = d < 6 ? net.minecraft.util.math.Direction.values()[d] : null;
            for (var quad : model.getQuads(null, face, QUAD_RANDOM)) {
                if (quad.getSprite() != sprite || quad.hasTint()) return null;
                if (!flatZ(quad.getVertexData())) return null; // 3D element model (furniture etc.)
                quads++;
            }
        }
        if (quads == 0) return null;
        var id = contents.getId();
        return Identifier.of(SPRITE_NS, id.getNamespace() + "/" + id.getPath());
    }

    /** item/generated: every vertex lies between z = 7.5/16 and 8.5/16. Vertex stride 8 ints, xyz first. */
    static boolean flatZ(int[] data) {
        for (int i = 2; i < data.length; i += 8) {
            float z = Float.intBitsToFloat(data[i]);
            if (z < 7.4f / 16 || z > 8.6f / 16) return false;
        }
        return true;
    }

    /**
     * Draws the icon as one double-sided quad on the item plane (z = 8/16), using the model's own
     * display transform, so hand, head, third-person, ground and frame placement match vanilla.
     * @return false when the icon is not ready (caller renders vanilla this frame).
     */
    public static boolean renderWorld(Identifier model, net.minecraft.client.util.math.MatrixStack matrices,
                                      net.minecraft.client.render.VertexConsumerProvider consumers, int light, int overlay,
                                      net.minecraft.client.render.model.json.Transformation transform, boolean leftHand) {
        var known = ICONS.get(model);
        if (known == null || known.isEmpty() || worldFailed) return false;
        var t = known.get();
        float fw = fitW(t), fh = fitH(t);
        float x0 = (1 - fw) / 2, x1 = x0 + fw, y0 = (1 - fh) / 2, y1 = y0 + fh;
        matrices.push();
        try {
            transform.apply(leftHand, matrices);
            matrices.translate(-0.5f, -0.5f, -0.5f);
            var entry = matrices.peek();
            var pos = entry.getPositionMatrix();
            var v = consumers.getBuffer(t.worldLayer());
            float zf = 0.5f + WORLD_HALF_GAP, zb = 0.5f - WORLD_HALF_GAP;
            // Front (+Z, south): texture reads normally. v=0 is the top row of the PNG.
            vertex(v, pos, entry, x0, y0, zf, 0, 1, light, overlay, 1);
            vertex(v, pos, entry, x1, y0, zf, 1, 1, light, overlay, 1);
            vertex(v, pos, entry, x1, y1, zf, 1, 0, light, overlay, 1);
            vertex(v, pos, entry, x0, y1, zf, 0, 0, light, overlay, 1);
            // Back (-Z, north): same texels, so it reads mirrored from behind like a vanilla item.
            vertex(v, pos, entry, x0, y1, zb, 0, 0, light, overlay, -1);
            vertex(v, pos, entry, x1, y1, zb, 1, 0, light, overlay, -1);
            vertex(v, pos, entry, x1, y0, zb, 1, 1, light, overlay, -1);
            vertex(v, pos, entry, x0, y0, zb, 0, 1, light, overlay, -1);
        } catch (RuntimeException error) {
            worldFailed = true; // GUI icons stay; world items fall back to vanilla
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("High-res held/world items disabled", error);
            return false;
        } finally { matrices.pop(); }
        announce(model);
        return true;
    }
    /** Two faces 1/1000 block apart: no z-fighting, visually a paper-thin card. */
    static final float WORLD_HALF_GAP = 0.0005f;
    private static boolean worldFailed;
    private static void vertex(net.minecraft.client.render.VertexConsumer v, org.joml.Matrix4f pos,
                               net.minecraft.client.util.math.MatrixStack.Entry entry, float x, float y, float z,
                               float u, float tv, int light, int overlay, float nz) {
        v.vertex(pos, x, y, z).color(0xFFFFFFFF).texture(u, tv).overlay(overlay).light(light).normal(entry, 0, 0, nz);
    }

    /** Glint texture span per icon; the vanilla glint texturing multiplies UVs by 8. */
    static final float GLINT_UV = 0.03f;
    private static boolean glintFailed;

    /**
     * Vanilla enchantment shimmer on top of the high-resolution icon. The icon pass writes depth only
     * where its texels are visible (item_icon.fsh discards clear texels), and the vanilla glint layer
     * tests depth EQUAL, so the shimmer follows the icon's own silhouette like on a vanilla item.
     */
    private static void drawGlint(DrawContext ctx, org.joml.Matrix4f matrix, float x0, float y0, float x1, float y1) {
        if (glintFailed) return;
        try {
            ctx.draw(provider -> {
                var v = provider.getBuffer(RenderLayer.getGlint());
                v.vertex(matrix, x0, y0, 0.5f).texture(0, GLINT_UV);
                v.vertex(matrix, x1, y0, 0.5f).texture(GLINT_UV, GLINT_UV);
                v.vertex(matrix, x1, y1, 0.5f).texture(GLINT_UV, 0);
                v.vertex(matrix, x0, y1, 0.5f).texture(0, 0);
            });
            ctx.draw();
        } catch (RuntimeException error) {
            glintFailed = true; // keep icons; only the shimmer is lost
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("High-res item glint disabled", error);
        }
    }

    private static final java.util.Map<Integer,net.minecraft.client.font.BakedGlyph> CHAT_GLYPHS=new java.util.HashMap<>();
    public static net.minecraft.client.font.BakedGlyph chatGlyph(int codepoint){
        String name=switch(codepoint){case 0xE110->"global";case 0xE111->"dormitory";case 0xE112->"party";case 0xE113->"system";case 0xE114->"school";case 0xE115->"wild";default->null;};
        if(name==null)return null;
        var glyph=CHAT_GLYPHS.get(codepoint);if(glyph!=null)return glyph;
        var model=Identifier.of("magiccodex","chat_"+name);var known=ICONS.get(model);
        if(known==null){request(model);return null;}if(known.isEmpty())return null;
        var t=known.get();var layers=new net.minecraft.client.font.TextRenderLayerSet(t.layer(),t.layer(),t.layer());
        glyph=new ChatImageGlyph(layers);
        CHAT_GLYPHS.put(codepoint,glyph);return glyph;
    }
    private static final Identifier WHISPER=Identifier.of("magiccodex","whisper_envelope");
    private static net.minecraft.client.font.BakedGlyph whisperGlyph;
    /** Uses the same premultiplied mip chain as high-resolution item icons, within normal text layout. */
    public static net.minecraft.client.font.BakedGlyph whisperGlyph(){
        if(whisperGlyph!=null)return whisperGlyph;
        var known=ICONS.get(WHISPER);
        if(known==null){request(WHISPER);return null;}
        if(known.isEmpty())return null;
        var t=known.get();
        var layers=new net.minecraft.client.font.TextRenderLayerSet(t.layer(),t.layer(),t.layer());
        whisperGlyph=new net.minecraft.client.font.BakedGlyph(layers,0,1,0,1,0,8f*t.width()/t.height(),0,8);
        return whisperGlyph;
    }

    private static void request(Identifier model) {
        if (pending == null) {
            ResourceManager manager = MinecraftClient.getInstance().getResourceManager();
            pending = new AsyncUiLoader<>(DECODER, 8, id -> decode(manager, id), d -> {});
        }
        pending.request(model);
    }

    /** Render thread: upload a few finished decodes per tick. */
    static void pump() {
        if (pending == null) return;
        for (int i = 0; i < UPLOADS_PER_TICK; i++) {
            var ready = pending.takeReady();
            if (ready == null) return;
            var result = ready.getValue();
            if (result.error() != null)
                org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Item icon failed: {}", ready.getKey(), result.error());
            if (ICONS.size() >= MAX_TEXTURES) clearIcons();
            ICONS.put(ready.getKey(), result.error() == null ? result.value().flatMap(ItemIconTextures::upload) : Optional.empty());
        }
    }

    /** Resource reload / disconnect / shutdown. Runs on the render thread. */
    public static void reset() {
        if (!RenderSystem.isOnRenderThread()) { MinecraftClient.getInstance().execute(ItemIconTextures::reset); return; }
        if (pending != null) { pending.close(); pending = null; }
        clearIcons();
        ExactItemIcons.reset();
        generation++;
        announced = false;
        worldFailed = false;
    }
    private static void clearIcons() {
        var textures = MinecraftClient.getInstance().getTextureManager();
        for (var icon : ICONS.values()) icon.ifPresent(t -> { textures.destroyTexture(t.id()); textures.destroyTexture(t.worldId()); });
        ICONS.clear();FLAT.clear();whisperGlyph=null;CHAT_GLYPHS.clear();
    }

    // ---------------------------------------------------------------- worker thread

    static Optional<Decoded> decode(ResourceManager manager, Identifier model) throws Exception {
        var texture = model.getNamespace().equals(SPRITE_NS) ? spriteTexture(model) : resolveTexture(manager, model);
        if (texture.isEmpty()) return Optional.empty();
        Identifier png = Identifier.of(texture.get().getNamespace(), "textures/" + texture.get().getPath() + ".png");
        if (manager.getResource(Identifier.of(png.getNamespace(), png.getPath() + ".mcmeta")).isPresent()) return Optional.empty();
        var resource = manager.getResource(png);
        if (resource.isEmpty()) return Optional.empty();
        java.awt.image.BufferedImage image;
        try (InputStream in = resource.get().getInputStream()) { image = javax.imageio.ImageIO.read(in); }
        if (image == null) return Optional.empty();
        int w = image.getWidth(), h = image.getHeight();
        if (Math.max(w, h) < MIN_SIZE || Math.max(w, h) > MAX_SIZE) return Optional.empty();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        return Optional.of(new Decoded(png, w, h, argb, bleed(argb, w, h)));
    }

    /**
     * Straight-alpha copy whose fully clear texels take the colour of the nearest visible texel.
     * Vanilla entity shaders blend non-premultiplied colour; without this, bilinear/mip sampling at
     * the silhouette mixes in the PNG's black transparent RGB and draws a dark rim.
     */
    static int[] bleed(int[] argb, int w, int h) {
        int[] out = argb.clone();
        boolean[] done = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int i = 0; i < out.length; i++) if ((out[i] >>> 24) != 0) { done[i] = true; queue[tail++] = i; }
        if (tail == 0) return out;
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w, rgb = out[i] & 0xFFFFFF;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = ny * w + nx;
                if (done[n]) continue;
                done[n] = true; out[n] = rgb; queue[tail++] = n; // alpha stays 0
            }
        }
        return out;
    }

    /** magiccodex-sprite:&lt;ns&gt;/&lt;path&gt; → &lt;ns&gt;:&lt;path&gt; (the atlas sprite id, e.g. minecraft:item/x). */
    static Optional<Identifier> spriteTexture(Identifier key) {
        String p = key.getPath();
        int slash = p.indexOf('/');
        if (slash <= 0 || slash == p.length() - 1) return Optional.empty();
        return Optional.ofNullable(Identifier.tryParse(p.substring(0, slash), p.substring(slash + 1)));
    }

    /** items/&lt;id&gt;.json → model chain → layer0, only for flat single-layer icons. */
    static Optional<Identifier> resolveTexture(ResourceManager manager, Identifier model) throws Exception {
        var definition = json(manager, Identifier.of(model.getNamespace(), "items/" + model.getPath() + ".json"));
        if (definition == null || !(definition.get("model") instanceof JsonObject root)) return Optional.empty();
        String type = string(root, "type");
        if (type == null || !(type.equals("minecraft:model") || type.equals("model"))) return Optional.empty();
        if (root.has("tints") && !root.getAsJsonArray("tints").isEmpty()) return Optional.empty();
        String next = string(root, "model");
        Map<String, String> textures = new HashMap<>();
        for (int depth = 0; next != null && depth < 8; depth++) {
            Identifier id = Identifier.of(next);
            if (isGenerated(id)) return flatLayer(textures);
            var json = json(manager, Identifier.of(id.getNamespace(), "models/" + id.getPath() + ".json"));
            if (json == null || json.has("elements")) return Optional.empty();
            if (json.get("display") instanceof JsonObject display && display.has("gui")) return Optional.empty();
            if (json.get("textures") instanceof JsonObject map)
                for (var entry : map.entrySet()) if (entry.getValue().isJsonPrimitive()) textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
            next = string(json, "parent");
        }
        return Optional.empty();
    }

    private static boolean isGenerated(Identifier id) {
        return id.getNamespace().equals("minecraft") && (id.getPath().equals("item/generated") || id.getPath().equals("builtin/generated")
                || id.getPath().equals("item/handheld"));
    }
    private static Optional<Identifier> flatLayer(Map<String, String> textures) {
        // A sparse layer2+ is still a multi-layer model and must remain vanilla.
        if (textures.keySet().stream().anyMatch(key -> key.matches("layer[0-9]+") && !key.equals("layer0")))
            return Optional.empty();
        String layer = textures.get("layer0");
        for (int i = 0; layer != null && layer.startsWith("#") && i < 8; i++) layer = textures.get(layer.substring(1));
        if (layer == null || layer.startsWith("#")) return Optional.empty();
        return Optional.ofNullable(Identifier.tryParse(layer));
    }
    private static JsonObject json(ResourceManager manager, Identifier id) throws Exception {
        var resource = manager.getResource(id);
        if (resource.isEmpty()) return null;
        try (var reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(reader);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        }
    }
    private static String string(JsonObject o, String key) {
        var e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // ---------------------------------------------------------------- render thread

    /** Same treatment as magiccodex UI images: premultiplied alpha, full GPU mip chain, trilinear. */
    private static Optional<Loaded> upload(Decoded d) {
        RenderSystem.assertOnRenderThread();
        var gui = texture(d, true);
        if (gui.isEmpty()) return Optional.empty();
        var world = texture(d, false);
        if (world.isEmpty()) { MinecraftClient.getInstance().getTextureManager().destroyTexture(gui.get()); return Optional.empty(); }
        return Optional.of(new Loaded(gui.get(), d.width(), d.height(), new IconLayer(gui.get()),
                world.get(), RenderLayer.getItemEntityTranslucentCull(world.get())));
    }

    private static Optional<Identifier> texture(Decoded d, boolean premultiplied) {
        NativeImage base = new NativeImage(d.width(), d.height(), false);
        NativeImageBackedTexture texture = null;
        try {
            int[] argb = premultiplied ? d.argb() : d.worldArgb();
            for (int y = 0; y < d.height(); y++) for (int x = 0; x < d.width(); x++) {
                int c = argb[y * d.width() + x];
                base.setColorArgb(x, y, premultiplied ? PremultipliedAlpha.pixel(c) : c);
            }
            texture = new NativeImageBackedTexture(base); base = null;
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(d.width(), d.height()));
            texture.bindTexture();
            // NativeImage's base allocation sets MAX_LEVEL and MAX_LOD to zero.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, levels);
            // GUI copy: averaging premultiplied texels keeps edges fringe-free.
            // World copy: straight alpha, but clear texels carry bled edge colour (see bleed()).
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            texture.setClamp(true);
            applyFilter(texture);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, levels, GL11.GL_TEXTURE_WIDTH) != 1)
                throw new IllegalStateException("Incomplete item icon mip chain");
            var id = Identifier.of("magiccodex", RUNTIME_PREFIX + generation + "/" + serial++);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            texture = null;
            return Optional.of(id);
        } catch (Exception error) {
            if (texture != null) texture.close();
            if (base != null) base.close();
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Item icon upload failed: {}", d.texture(), error);
            return Optional.empty();
        }
    }

    /** Texture path prefix; CodexTextureFilterMixin also forces mipmapped sampling for it in vanilla layers. */
    public static final String RUNTIME_PREFIX = "runtime_item_icon/";

    /** Smooth like other UI images: trilinear when shrinking, bilinear when enlarged. */
    private static void applyFilter(net.minecraft.client.texture.AbstractTexture texture) {
        texture.setFilter(true, true);
    }

    private static final class IconLayer extends RenderLayer {
        IconLayer(Identifier id) {
            super("magiccodex_item_icon", VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS, 1536, false, false,
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                        RenderSystem.disableCull();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL11.GL_LEQUAL);
                        RenderSystem.depthMask(true);
                        RenderSystem.setShader(PROGRAM);
                        RenderSystem.setShaderTexture(0, id);
                        applyFilter(MinecraftClient.getInstance().getTextureManager().getTexture(id));
                    }, () -> {
                        RenderSystem.enableCull();
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
        }
    }
}
