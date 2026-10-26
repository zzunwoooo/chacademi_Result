package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import school.magiccodex.protocol.ChunkClaimProtocol;
import school.magiccodex.protocol.ChunkClaimProtocol.*;

/**
 * 야생 청크 점유 (땅문서, 메모 #88, 오너 2026-10-26). 서버가 보낸 것만 그린다 (판정·저장은 전부 서버).
 * <ul>
 *   <li>땅문서를 든 동안 (서버 VIEW): 주변 청크 경계에 반투명 홀로그램 벽 (큐디치 경기장 경계 그림 재사용) +
 *       청크 한가운데 내 눈높이에 글씨 (주인 한글 닉 · 소유 가능 · 제한, 웨이포인트 글씨와 같은 글자 규칙).</li>
 *   <li>내 땅 안 (서버 LAND): 오른쪽 위 작은 칸 (파티 UI 알림 카드 그림). 단축키(기본 H) 또는 Ctrl·채팅창 커서로 누르면
 *       {@link ChunkLandScreen} (파티 초대 창 그림·줄·버튼 재사용)이 펼쳐진다.</li>
 * </ul>
 * 매 틱 서버에 묻지 않는다. 서버는 바뀐 일이 있을 때만 보낸다.
 */
public final class ChunkClaimClient {
    private ChunkClaimClient() {}

    private interface Bytes { byte[] bytes(); }
    public record State(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<State> ID = new Id<>(Identifier.of(ChunkClaimProtocol.STATE));
        static final PacketCodec<RegistryByteBuf, State> CODEC = codec(State::new, ChunkClaimProtocol.MAX_BYTES);
        public Id<State> getId() { return ID; }
    }
    public record Action(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Action> ID = new Id<>(Identifier.of(ChunkClaimProtocol.ACTION));
        static final PacketCodec<RegistryByteBuf, Action> CODEC = codec(Action::new, ChunkClaimProtocol.MAX_ACTION_BYTES);
        public Id<Action> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> f, int max) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > max) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return f.apply(a);
            }
            public void encode(RegistryByteBuf b, T value) { b.writeBytes(value.bytes()); }
        };
    }

    static View view = View.HIDDEN;
    static Land land = Land.OUTSIDE;

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
        PayloadTypeRegistry.playC2S().register(Action.ID, Action.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(State.ID, (payload, context) -> {
            Frame frame;
            try { frame = ChunkClaimProtocol.decode(payload.bytes()); } catch (IllegalArgumentException bad) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(ChunkClaimClient::reset));
        HudRenderCallback.EVENT.register((ctx, ticks) -> renderBox(ctx));
        WorldRenderEvents.START.register(ctx -> { if (worldText != null) worldText.beginFrame(); });
        WorldRenderEvents.AFTER_ENTITIES.register(ChunkClaimClient::renderWorld);
    }
    private static void reset() { view = View.HIDDEN; land = Land.OUTSIDE; boxHit = null; }

    private static void accept(Frame frame) {
        var c = MinecraftClient.getInstance();
        switch (frame) {
            case View v -> view = v;
            case Land l -> {
                land = l;
                if (c.currentScreen instanceof ChunkLandScreen s) s.landChanged();
            }
            case Result r -> { if (c.currentScreen instanceof ChunkLandScreen s) s.result(r.ok(), r.message()); }
        }
    }

    static boolean send(ChunkClaimProtocol.Action a) {
        if (!ClientPlayNetworking.canSend(Action.ID)) return false;
        ClientPlayNetworking.send(new Action(ChunkClaimProtocol.encode(a)));
        return true;
    }

    /** 내 땅 단축키 (UiKeys LAND, 기본 H). 내 땅 안이 아니면 아무것도 하지 않는다. */
    static void open() {
        var c = MinecraftClient.getInstance();
        if (!land.inside() || c.player == null) return;
        if (c.currentScreen instanceof ChunkLandScreen) return;
        MagicCodexClient.dismiss();
        c.setScreen(new ChunkLandScreen());
    }

    // ================================================================== 오른쪽 위 작은 칸 (내 땅 안)

    private static final Identifier STAR = Identifier.of("magiccodex", "textures/gui/party/house_star.png");
    private static final Identifier TITLE = Identifier.of("magiccodex", "section"), BODY = Identifier.of("magiccodex", "label"), BOLD = Identifier.of("magiccodex", "hud_bold");
    /** 칸 크기 (가상 px, 1672×941 기준 · 큐디치 대기 카드와 같은 배율). */
    static final float BOX_W = 330, BOX_H = 104;
    private record Box(float x, float y, float w, float h) { boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; } }
    private static Box boxHit;
    private static HudTextureCache images; private static CodexTypography text; private static int generation = -1;

    /** 화면에서 칸이 놓이는 곳 (GUI px). 펼친 창도 이 자리에서 펼쳐진다. */
    static float[] boxFrame(float w, float h) {
        float s = KyudichiHud.scale(w, h) * .8f;
        float top = KyudichiHud.rightTop();
        int q = KyudichiQueueClient.state();
        if (q == school.magiccodex.protocol.KyudichiQueueProtocol.S_SEARCHING || q == school.magiccodex.protocol.KyudichiQueueProtocol.S_MOVING) top += (313 + 14) * s;   // 큐디치 매칭 카드 아래
        return new float[]{w - (BOX_W + 21) * s, top, s};
    }

    private static void renderBox(DrawContext ctx) {
        boxHit = null;
        var c = MinecraftClient.getInstance();
        if (!land.inside() || c.player == null || c.options.hudHidden || c.getOverlay() != null || !HudPointer.hudVisibleUnder(c.currentScreen)) return;
        if (KyudichiClient.active()) return;
        if (generation != UiResources.generation()) { close(); generation = UiResources.generation(); }
        if (images == null) images = new HudTextureCache(c);
        if (text == null) text = new CodexTypography(c);
        images.beginFrame(); text.beginFrame();
        float w = c.getWindow().getScaledWidth(), h = c.getWindow().getScaledHeight();
        float[] f = boxFrame(w, h);
        float x = f[0], y = f[1], s = f[2];
        try (var customization = HudCustomization.begin(ctx, "chunk_land", x, y, BOX_W * s, BOX_H * s)) {
            boolean pointer = HudPointer.active();
            double[] pt = HudCustomization.pointer("chunk_land", HudPointer.x(), HudPointer.y());
            boolean hover = pointer && pt[0] >= x && pt[0] < x + BOX_W * s && pt[1] >= y && pt[1] < y + BOX_H * s;
            ctx.getMatrices().push();
            try {
                ctx.getMatrices().translate(x, y, 150); ctx.getMatrices().scale(s, s, 1);
                PartyUiAtlas.nine(images, ctx, PartyUiAtlas.TOAST, 0, 0, BOX_W, BOX_H, BOX_H / 156f, hover ? 0xFFFFFFFF : 0xF2E6E6E6);
                images.drawTexture(ctx, STAR, 22, 26, 0, 0, 52, 52, 128, 128, 128, 128, 0xFFFFFFFF);
                text.draw(ctx, "내 땅", 90, 40, 30, 0xFFF0D293, TITLE, false);
                String key = KeySettingsLayout.name(UiKeys.code(UiKeys.Action.LAND));
                String sub = "청크 " + land.cx() + ", " + land.cz() + " · " + (UiKeys.code(UiKeys.Action.LAND) < 0 ? "눌러서 관리" : key + " 관리");
                float size = Math.min(19, 19 * (BOX_W - 104) / Math.max(1, text.width(sub, 19, BODY)));
                text.draw(ctx, sub, 91, 74, size, 0xFFBFD3E6, BODY, false);
            } finally { ctx.getMatrices().pop(); images.endFrame(); }
            boxHit = new Box(x, y, BOX_W * s, BOX_H * s);
        }
    }
    private static void close() { if (images != null) { images.close(); images = null; } if (text != null) { text.close(); text = null; } }

    /** Ctrl 커서 화면·채팅창 클릭: 칸을 누르면 관리 창. */
    static boolean click(double x, double y, int button) {
        if (button != 0 || boxHit == null || !land.inside() || HudCustomization.editingGesture()) return false;
        double[] p = HudCustomization.pointer("chunk_land", x, y);
        if (!boxHit.contains(p[0], p[1])) return false;
        open();
        return true;
    }

    // ================================================================== 월드: 경계 홀로그램 + 청크 가운데 글씨 (땅문서를 든 동안)

    static final Identifier WALL = Identifier.of("magiccodex", "textures/kyudichi/boundary.png");
    private static final Identifier HALO = Identifier.of("magiccodex", "textures/gui/travel/world/halo.png");
    /** 벽 높이 (눈높이 아래·위), 보이는 거리, 칸 크기. */
    static final double BELOW = 3, ABOVE = 3, FADE = 26, CELL = 2, LABEL_DIST = 44;
    private static CodexTypography worldText;

    /** 상태별 색 (파티 UI 글자 색과 같은 계열). */
    static int rgb(int status) {
        return switch (status) {
            case ChunkClaimProtocol.MINE -> 0x7BE08E;
            case ChunkClaimProtocol.TRUSTED -> 0x90C5FF;
            case ChunkClaimProtocol.OTHER -> 0xF1D685;
            case ChunkClaimProtocol.RESTRICTED -> 0xF3A9B6;
            default -> 0x8FE6FF;
        };
    }
    private static int priority(int status) {
        return switch (status) { case ChunkClaimProtocol.RESTRICTED, ChunkClaimProtocol.OTHER -> 3; case ChunkClaimProtocol.MINE, ChunkClaimProtocol.TRUSTED -> 2; default -> 1; };
    }
    static String label(View v, Cell cell) {
        return switch (cell.status()) {
            case ChunkClaimProtocol.FREE -> v.freeLabel();
            case ChunkClaimProtocol.RESTRICTED -> v.restrictedLabel();
            default -> cell.owner();
        };
    }

    private static void renderWorld(WorldRenderContext ctx) {
        var v = view;
        var c = MinecraftClient.getInstance();
        if (!v.holding() || c.world == null || c.player == null || c.options.hudHidden) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        var cam = ctx.camera().getPos();
        float pulse = .88f + .12f * (float) Math.sin(Util.getMeasuringTimeMs() / 300.0);
        int r = v.radius(), side = 2 * r + 1, x0 = v.centerX() - r, z0 = v.centerZ() - r;
        double y0 = cam.y - BELOW, y1 = cam.y + ABOVE;
        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f m = matrices.peek().getPositionMatrix();
            VertexConsumer vc = out.getBuffer(RenderLayer.getEntityTranslucentEmissive(WALL));
            // x 방향 선 (x = 고정): 칸 i-1 과 i 사이
            for (int i = 0; i <= side; i++) for (int j = 0; j < side; j++) {
                int left = i - 1, right = i;
                edge(vc, m, cam.x, cam.z, true, (x0 + i) * 16.0, (z0 + j) * 16.0, y0, y1, v, left, j, right, j, r, pulse);
            }
            // z 방향 선 (z = 고정)
            for (int j = 0; j <= side; j++) for (int i = 0; i < side; i++) {
                edge(vc, m, cam.x, cam.z, false, (z0 + j) * 16.0, (x0 + i) * 16.0, y0, y1, v, i, j - 1, i, j, r, pulse);
            }
        } finally { matrices.pop(); }
        labels(ctx, v, cam.x, cam.y, cam.z);
    }

    /** 한 칸 가장자리 16블록 (fixedX 면 x = at, 아니면 z = at). 양쪽 칸 중 하나라도 가운데면 진하게. */
    private static void edge(VertexConsumer vc, Matrix4f m, double camX, double camZ, boolean fixedX, double at, double start, double y0, double y1,
                             View v, int ax, int az, int bx, int bz, int r, float pulse) {
        int side = 2 * r + 1;
        Cell a = ax >= 0 && ax < side && az >= 0 && az < side ? v.cell(ax - r, az - r) : null;
        Cell b = bx >= 0 && bx < side && bz >= 0 && bz < side ? v.cell(bx - r, bz - r) : null;
        boolean aCenter = ax == r && az == r, bCenter = bx == r && bz == r;
        Cell pick = aCenter ? a : bCenter ? b : a == null ? b : b == null ? a : priority(a.status()) >= priority(b.status()) ? a : b;
        if (pick == null) return;
        float strength = aCenter || bCenter ? .62f : .30f;
        int color = rgb(pick.status());
        for (double u = start; u < start + 16 - 1e-6; u += CELL) {
            double mid = u + CELL / 2;
            double d = fixedX ? Math.hypot(at - camX, mid - camZ) : Math.hypot(mid - camX, at - camZ);
            float fade = (float) Math.clamp(1 - d / FADE, 0, 1);
            if (fade <= 0) continue;
            for (double y = y0; y < y1 - 1e-6; y += CELL) {
                double top = Math.min(y1, y + CELL);
                // 위아래 끝은 흐리게
                double ym = (y + top) / 2, edgeFade = 1 - Math.abs(ym - (y0 + y1) / 2) / ((y1 - y0) / 2);
                float alpha = strength * fade * fade * (float) Math.clamp(.35 + .65 * edgeFade, 0, 1) * pulse;
                int argb = (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | color;
                if ((argb >>> 24) == 0) continue;
                float[][] q = fixedX
                        ? new float[][]{{(float) at, (float) y, (float) u}, {(float) at, (float) y, (float) (u + CELL)}, {(float) at, (float) top, (float) (u + CELL)}, {(float) at, (float) top, (float) u}}
                        : new float[][]{{(float) u, (float) y, (float) at}, {(float) (u + CELL), (float) y, (float) at}, {(float) (u + CELL), (float) top, (float) at}, {(float) u, (float) top, (float) at}};
                float sv = (float) ((top - y) / CELL);
                float[][] uv = {{0, sv}, {1, sv}, {1, 0}, {0, 0}};
                float nx = fixedX ? 1 : 0, nz = fixedX ? 0 : 1;
                // 양면: 안·밖 어느 쪽에서 봐도 보이게 두 번 (반대 방향으로) 그린다
                for (int k = 0; k < 4; k++) emit(vc, m, q[k], uv[k], argb, nx, nz);
                for (int k = 3; k >= 0; k--) emit(vc, m, q[k], uv[k], argb, -nx, -nz);
            }
        }
    }
    private static void emit(VertexConsumer vc, Matrix4f m, float[] p, float[] uv, int color, float nx, float nz) {
        vc.vertex(m, p[0], p[1], p[2]).color(color).texture(uv[0], uv[1]).overlay(OverlayTexture.DEFAULT_UV)
          .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(nx, 0, nz);
    }

    /** 청크 가운데, 내 눈높이 글씨. 가까이(2블록 안) 오면 흐려져 얼굴을 가리지 않는다. */
    private static void labels(WorldRenderContext ctx, View v, double camX, double camY, double camZ) {
        var c = MinecraftClient.getInstance();
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (worldText == null) { worldText = new CodexTypography(c); worldText.beginFrame(); }
        int r = v.radius();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            Cell cell = v.cell(dx, dz);
            String value = label(v, cell);
            if (value == null || value.isBlank()) continue;
            double x = (v.centerX() + dx) * 16 + 8 - camX, z = (v.centerZ() + dz) * 16 + 8 - camZ, y = 0;
            double dist = Math.sqrt(x * x + z * z);
            if (dist > LABEL_DIST) continue;
            float alpha = (float) (Math.clamp((LABEL_DIST - dist) / 10, 0, 1) * Math.clamp((dist - 1.2) / 2.5, 0, 1));
            if (alpha <= .02f) continue;
            float scale = (float) (1.5 + Math.min(dist, 32) * .045);
            int rgb = FishingArt.mix(0xFF000000 | rgb(cell.status()), 0xFFFFF2CC, .45f) & 0xFFFFFF;
            matrices.push();
            try {
                matrices.translate(x, y, z);
                matrices.multiply(ctx.camera().getRotation());
                matrices.scale(scale, scale, scale);
                halo(out.getBuffer(RenderLayer.getEntityTranslucentEmissive(HALO)), matrices.peek().getPositionMatrix(), .95f, .34f, argb(alpha * .22f, rgb(cell.status())));
                int outline = argb(alpha * .85f, 0x0A0A0F);
                float o = 9;
                float[][] offs = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o * .7f, -o * .7f}, {o * .7f, -o * .7f}, {-o * .7f, o * .7f}, {o * .7f, o * .7f}};
                for (float[] off : offs) worldText.drawWorld(matrices, out, value, off[0], off[1], 250, 1600, outline);
                worldText.drawWorld(matrices, out, value, 0, 0, 250, 1600, argb(alpha, rgb));
            } finally { matrices.pop(); }
        }
    }
    private static void halo(VertexConsumer vc, Matrix4f m, float w, float h, int color) {
        if ((color >>> 24) == 0) return;
        float x = w / 2, y = h / 2;
        vc.vertex(m, -x, -y, -.01f).color(color).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 0, 1);
        vc.vertex(m, x, -y, -.01f).color(color).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 0, 1);
        vc.vertex(m, x, y, -.01f).color(color).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 0, 1);
        vc.vertex(m, -x, y, -.01f).color(color).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 0, 1);
    }
    private static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }
}
