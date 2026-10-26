package school.magiccodex.client;

import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import school.magiccodex.protocol.TelegraphProtocol;

/**
 * Enemy attack warnings on the ground (예고 장판, {@link TelegraphProtocol}): translucent red fill + bright red
 * outline, as in the owner's reference sheet (원형 / 직선 / 부채꼴). Shown for the warning time, then removed.
 * The server hits exactly this area when the warning ends.
 */
public final class TelegraphClient {
    private TelegraphClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(TelegraphProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > TelegraphProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    private record Live(TelegraphProtocol.Show show, TelegraphGeometry.Mesh mesh, long start, long end) {}

    static final int MAX_LIVE = 96;
    /** Fill / outline / outline glow alphas and widths (blocks). */
    static final float FILL_ALPHA = .30f, LINE_ALPHA = 1f, GLOW_ALPHA = .38f;
    static final double LINE_W = .14, GLOW_W = .55, LIFT = .03;
    private static final Identifier BAND = AimRenderer.BAND, GLOW = AimRenderer.GLOW;

    private static final Map<Integer, Live> live = new LinkedHashMap<>();
    private static ClientWorld world;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            TelegraphProtocol.Frame frame;
            try { frame = TelegraphProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> live.clear());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> live.clear());
        WorldRenderEvents.AFTER_ENTITIES.register(TelegraphClient::render);
    }

    private static void accept(TelegraphProtocol.Frame frame) {
        sync();
        switch (frame) {
            case TelegraphProtocol.Clear c -> live.remove(c.id());
            case TelegraphProtocol.Show s -> {
                if (live.size() >= MAX_LIVE) live.remove(live.keySet().iterator().next());
                long now = Util.getMeasuringTimeMs();
                live.put(s.id(), new Live(s, TelegraphGeometry.of(s), now, now + s.ticks() * 50L));
            }
        }
    }

    private static void sync() {
        var c = MinecraftClient.getInstance();
        if (c.world != world) { live.clear(); world = c.world; }
    }

    private static void render(WorldRenderContext context) {
        if (live.isEmpty()) return;
        sync();
        var c = MinecraftClient.getInstance();
        var consumers = context.consumers();
        if (consumers == null || context.matrixStack() == null || c.world == null || c.options.hudHidden) return;
        long now = Util.getMeasuringTimeMs();
        var cam = context.camera().getPos();
        var matrices = context.matrixStack();
        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f m = matrices.peek().getPositionMatrix();
            // one buffer per layer, each fetched once and used to the end: switching layers on the immediate
            // provider ends the previous buffer. Glow first (under), then fill + outline in the band layer.
            var shown = new ArrayList<Live>(live.size());
            var alphas = new ArrayList<Float>(live.size());
            for (var it = live.values().iterator(); it.hasNext(); ) {
                Live l = it.next();
                float a = TelegraphGeometry.alpha(l.start(), l.end(), now);
                if (a < 0) { it.remove(); continue; }
                shown.add(l); alphas.add(a);
            }
            if (shown.isEmpty()) return;
            VertexConsumer glow = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(GLOW));
            for (int i = 0; i < shown.size(); i++) {
                Live l = shown.get(i);
                outline(glow, m, l.mesh().outline(), (float) (l.show().y() + LIFT) + .002f, GLOW_W, argb(l.show().argb(), GLOW_ALPHA * alphas.get(i)));
            }
            VertexConsumer band = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(BAND));
            for (int i = 0; i < shown.size(); i++) {
                Live l = shown.get(i);
                int rgb = l.show().argb() & 0xFFFFFF;
                float y = (float) (l.show().y() + LIFT), a = alphas.get(i);
                fill(band, m, l.mesh().fill(), y, argb(rgb, FILL_ALPHA * a));
                outline(band, m, l.mesh().outline(), y + .004f, LINE_W, argb(lighter(rgb), LINE_ALPHA * a));
            }
        } finally { matrices.pop(); }
    }

    private static void fill(VertexConsumer vc, Matrix4f m, List<TelegraphGeometry.P> tris, float y, int color) {
        for (int i = 0; i + 2 < tris.size(); i += 3) {
            var a = tris.get(i); var b = tris.get(i + 1); var d = tris.get(i + 2);
            quad(vc, m, new float[][]{{(float) a.x(), y, (float) a.z(), .5f, .5f}, {(float) b.x(), y, (float) b.z(), .5f, .5f},
                {(float) d.x(), y, (float) d.z(), .5f, .5f}, {(float) d.x(), y, (float) d.z(), .5f, .5f}}, color);
        }
    }

    /** Closed polyline as a flat band; v runs across (band/glow textures have their profile across v). */
    private static void outline(VertexConsumer vc, Matrix4f m, List<TelegraphGeometry.P> pts, float y, double width, int color) {
        double h = width / 2;
        for (int i = 0; i + 1 < pts.size(); i++) {
            var a = pts.get(i); var b = pts.get(i + 1);
            double tx = b.x() - a.x(), tz = b.z() - a.z(), len = Math.hypot(tx, tz);
            if (len < 1e-6) continue;
            double ux = tx / len * h, uz = tz / len * h, sx = -uz, sz = ux;
            // at sharp corners (line/cone) extend by half the width so the corner is closed; smooth arcs are not
            // extended, otherwise the translucent glow doubles up into beads at every joint
            double ea = TelegraphGeometry.sharp(pts, i) ? 1 : 0, eb = TelegraphGeometry.sharp(pts, i + 1) ? 1 : 0;
            double ax = a.x() - ux * ea, az = a.z() - uz * ea, bx = b.x() + ux * eb, bz = b.z() + uz * eb;
            quad(vc, m, new float[][]{{(float) (ax + sx), y, (float) (az + sz), 0, 0}, {(float) (ax - sx), y, (float) (az - sz), 0, 1},
                {(float) (bx - sx), y, (float) (bz - sz), 1, 1}, {(float) (bx + sx), y, (float) (bz + sz), 1, 0}}, color);
        }
    }

    /** Front face up whatever order it was built in (same rule as AimRenderer.emit). */
    private static void quad(VertexConsumer vc, Matrix4f m, float[][] q, int color) {
        float cross = (q[1][0] - q[0][0]) * (q[2][2] - q[0][2]) - (q[1][2] - q[0][2]) * (q[2][0] - q[0][0]);
        int[] order = cross <= 0 ? new int[]{0, 1, 2, 3} : new int[]{3, 2, 1, 0};
        for (int i : order) {
            float[] v = q[i];
            vc.vertex(m, v[0], v[1], v[2]).color(color).texture(v[3], v[4]).overlay(OverlayTexture.DEFAULT_UV)
              .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 1, 0);
        }
    }

    private static int argb(int rgb, float alpha) { return (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF); }
    /** Outline = the same red, lifted toward white a little so it reads brighter than the fill. */
    static int lighter(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (Math.min(255, r + (255 - r) / 3) << 16) | (Math.min(255, g + (255 - g) / 4) << 8) | Math.min(255, b + (255 - b) / 4);
    }
}
