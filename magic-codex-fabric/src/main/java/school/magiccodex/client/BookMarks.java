package school.magiccodex.client;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import school.magiccodex.protocol.BookMarkProtocol;

/**
 * 책 블록 표시 (오너 2026-10-26, 메모 #69). 서버가 보낸 책 블록마다, 연결할 때 관리자가 바라본 면(책장이면 앞면)
 * 바로 앞에 홀로그램 책 아이콘과 빛을 그린다 — 안 읽은 책 금빛 반짝임, 읽은 책 흐리게, 지능 부족은 빨간빛.
 * 그 블록을 바라보면 조준점 아래에 "제목 · 우클릭으로 읽기" 카드. 그리기는 16블록 안만, 서버 패킷은 바뀔 때만.
 */
final class BookMarks {
    private BookMarks() {}

    static final double RANGE = 16;
    private static final Identifier ICON = Identifier.of("magiccodex", "textures/gui/book/book_mark.png");
    private static final Identifier HALO = Identifier.of("magiccodex", "textures/gui/travel/world/halo.png");
    private static volatile BookMarkProtocol.Marks marks = BookMarkProtocol.Marks.EMPTY;

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(BookMarkProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > BookMarkProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            BookMarkProtocol.Marks next;
            try { next = BookMarkProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> marks = next);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> marks = BookMarkProtocol.Marks.EMPTY);
        WorldRenderEvents.AFTER_ENTITIES.register(BookMarks::render);
        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.attachLayerAfter(IdentifiedLayer.CROSSHAIR,
                Identifier.of("magiccodex", "book_marks"), BookMarks::hud));
    }

    private static boolean sameWorld(MinecraftClient c) {
        return c.world != null && !marks.world().isEmpty();
    }

    // ------------------------------------------------------------------ world: icon on the open face

    private static void render(WorldRenderContext ctx) {
        var c = MinecraftClient.getInstance();
        List<BookMarkProtocol.Mark> list = marks.marks();
        if (c.player == null || c.options.hudHidden || list.isEmpty() || !sameWorld(c)) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        var cam = ctx.camera().getPos();
        float t = (Util.getMeasuringTimeMs() % 3_600_000L) / 1000f;
        for (var m : list) {
            double cx = m.x() + .5, cy = m.y() + .5, cz = m.z() + .5;
            double d2 = c.player.squaredDistanceTo(cx, cy, cz);
            if (d2 > RANGE * RANGE) continue;
            float fade = (float) Math.clamp((RANGE - Math.sqrt(d2)) / 4, 0, 1);
            float pulse = .5f + .5f * (float) Math.sin(t * 2.2f + m.x() * .7f + m.z() * 1.3f);
            int glow, icon;
            float glowA, iconA;
            switch (m.state()) {
                case BookMarkProtocol.READ -> { glow = 0x9FB6CC; icon = 0xC9D6E6; glowA = .10f; iconA = .38f; }
                case BookMarkProtocol.LOCKED -> { glow = 0xFF4A4A; icon = 0xFF9C9C; glowA = .22f + .12f * pulse; iconA = .70f; }
                default -> { glow = 0xFFC75A; icon = 0xFFF0C8; glowA = .26f + .22f * pulse; iconA = .80f + .20f * pulse; }
            }
            matrices.push();
            try {
                var n = normal(m.face());
                matrices.translate(cx + n[0] * .505 - cam.x, cy + n[1] * .505 - cam.y, cz + n[2] * .505 - cam.z);
                matrices.multiply(facing(m.face()));
                Matrix4f mat = matrices.peek().getPositionMatrix();
                float bob = m.state() == BookMarkProtocol.READ ? 0 : .02f * (float) Math.sin(t * 1.6f);
                quad(out.getBuffer(RenderLayer.getEntityTranslucentEmissive(HALO)), mat, 0, 0, .004f, 1.15f, 1.15f, argb(fade * glowA, glow));
                quad(out.getBuffer(RenderLayer.getEntityTranslucentEmissive(ICON)), mat, 0, bob, .012f, .55f, .55f, argb(fade * iconA, icon));
            } finally { matrices.pop(); }
        }
    }

    /** Direction 번호 (0 아래 · 1 위 · 2 북 · 3 남 · 4 서 · 5 동) → 바깥쪽 법선. */
    static double[] normal(int face) {
        return switch (face) {
            case 0 -> new double[]{0, -1, 0}; case 1 -> new double[]{0, 1, 0}; case 2 -> new double[]{0, 0, -1};
            case 4 -> new double[]{-1, 0, 0}; case 5 -> new double[]{1, 0, 0}; default -> new double[]{0, 0, 1};
        };
    }
    /** +Z 를 보는 사각형을 그 면 바깥으로 돌리는 회전. */
    private static Quaternionf facing(int face) {
        float h = (float) (Math.PI / 2);
        return switch (face) {
            case 0 -> new Quaternionf().rotateX(h); case 1 -> new Quaternionf().rotateX(-h); case 2 -> new Quaternionf().rotateY((float) Math.PI);
            case 4 -> new Quaternionf().rotateY(-h); case 5 -> new Quaternionf().rotateY(h); default -> new Quaternionf();
        };
    }
    /** 양면 사각형 (뒤집혀도 보이게). */
    private static void quad(VertexConsumer vc, Matrix4f m, float ox, float oy, float z, float w, float h, int color) {
        if ((color >>> 24) == 0) return;
        float x0 = ox - w / 2, x1 = ox + w / 2, y0 = oy - h / 2, y1 = oy + h / 2;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        vc.vertex(m, x0, y0, z).color(color).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x1, y0, z).color(color).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x1, y1, z).color(color).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x0, y1, z).color(color).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x0, y1, z).color(color).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, -1);
        vc.vertex(m, x1, y1, z).color(color).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, -1);
        vc.vertex(m, x1, y0, z).color(color).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, -1);
        vc.vertex(m, x0, y0, z).color(color).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, -1);
    }
    private static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }

    // ------------------------------------------------------------------ HUD: card while looking at the block

    private static void hud(DrawContext c, RenderTickCounter ticks) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null || marks.marks().isEmpty() || !sameWorld(mc)) return;
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) return;
        var pos = b.getBlockPos();
        BookMarkProtocol.Mark mark = null;
        for (var m : marks.marks()) if (m.x() == pos.getX() && m.y() == pos.getY() && m.z() == pos.getZ()) { mark = m; break; }
        if (mark == null) return;
        String title = mark.title();
        String sub = switch (mark.state()) {
            case BookMarkProtocol.READ -> "✔ 읽음 · 우클릭으로 다시 읽기";
            case BookMarkProtocol.LOCKED -> "필요 지능 " + mark.required() + " · 아직 읽을 수 없어요";
            default -> "우클릭으로 읽기" + (mark.required() > 0 ? " · 필요 지능 " + mark.required() : "");
        };
        int sub_color = mark.state() == BookMarkProtocol.LOCKED ? 0xFFFF9C9C : mark.state() == BookMarkProtocol.READ ? 0xFFB7C3D2 : 0xFFE9D3A0;
        float cx = c.getScaledWindowWidth() / 2f, top = c.getScaledWindowHeight() / 2f + 16;
        float w = Math.max(FishingArt.width(title, 11, FishingArt.LABEL), FishingArt.width(sub, 9, FishingArt.BODY)) + 20;
        FishingArt.card(c, cx - w / 2, top, w, 30, 0xC8081830, mark.state() == BookMarkProtocol.LOCKED ? 0x90FF6A6A : 0x80C9B48A, 1);
        FishingArt.text(c, title, cx, top + 9, 11, 0xFFFFF2CC, FishingArt.LABEL, true);
        FishingArt.text(c, sub, cx, top + 21, 9, sub_color, FishingArt.BODY, true);
    }
}
