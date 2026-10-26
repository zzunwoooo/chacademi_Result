package school.magiccodex.client;

import java.util.List;
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
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import school.magiccodex.protocol.HouseBoardProtocol;
import school.magiccodex.protocol.SchoolProtocol;

/**
 * 기숙사 점수 홀로그램 (오픈 전 메모 #106, 오너 2026-10-26 "게시판 없이 단순하게 홀로그램 느낌으로").
 * 서버가 보낸 자리마다 떠 있는 글씨판을 그린다: 제목 "기숙사 점수" + 순위대로 4줄 (순위 · 기숙사 이름 · 점수).
 * <ul>
 *   <li>스타일은 기존 것 재사용: 웨이포인트·청크 점유 글씨 규칙 (MagicCodex 굵은 글꼴, 검은 외곽선 8장 + 채움, 은은한 halo 빛,
 *       위아래로 살짝 떠다님), 책 블록 표시의 halo 텍스처 (textures/gui/travel/world/halo.png). 새 그림 없음.</li>
 *   <li>판은 동서남북 중 한 방향을 90도 단위로 고정해서 바라본다 (오너 수정: 빌보드 아님). 앞면 쪽에서만 그리고,
 *       뒤나 옆 끝에서는 흐려지다 안 보인다 (뒤집힌 글씨가 보이지 않게).</li>
 *   <li>기숙사 색 = 학교 창({@code SchoolScreen}) 막대 색. 1위 줄은 금빛 띠 + 금색 숫자.</li>
 *   <li>점수가 바뀌면 숫자는 0.7초 동안 바뀌며 겹쳐 사라지고, 순위가 바뀐 줄은 새 자리로 미끄러진다
 *       (글자 그림은 바뀐 숫자만 한 번 새로 굽는다 — 매 프레임 새 글자를 만들지 않음).</li>
 *   <li>벽 뒤 규칙은 웨이포인트 글씨와 같다 (깊이 검사 그대로 = 벽에 가려짐). 그리기는 {@code range}(기본 48) 블록 안만.</li>
 * </ul>
 * 서버 패킷은 바뀔 때만 온다. 매 틱 서버에 묻지 않는다.
 */
final class HouseBoardClient {
    private HouseBoardClient() {}

    private static final Identifier HALO = Identifier.of("magiccodex", "textures/gui/travel/world/halo.png");
    private static final Identifier FACE = Identifier.of("magiccodex", "hud_bold");
    /** CodexTypography.drawWorld 의 글자 1px = 이만큼 블록. */
    static final float PX = NameplatePolicy.WORLD_WIDTH / 2048f;
    /** 판 전체 크기 배율 (48블록에서도 읽히게 웨이포인트 글씨보다 조금 크게). */
    static final float SCALE = 1.5f;
    // 판 안 배치 (글자 px 단위, 위가 +)
    static final float TITLE_SIZE = 270, ROW_SIZE = 215;
    static final float TITLE_Y = 600, DIVIDER_Y = 420, ROW_Y0 = 230, ROW_GAP = 290;
    static final float RANK_X = -1150, NAME_LEFT = -900, SCORE_RIGHT = 1250;
    /** 판 = halo 3장을 가로로 겹쳐 납작한 타원 빛 (가운데 하나만 늘리면 둥근 얼룩이 됨). */
    static final float PANEL_W = 3200, PANEL_H = 3200, PANEL_Y = 0, PANEL_STEP = 1000;
    static final long CHANGE_MS = 700;
    static final String TITLE = "기숙사 점수";
    /** SchoolScreen.COLORS 와 같은 기숙사 색 (아르케온·루미나·베스티아즈·노크세르). */
    static final int[] HOUSE_RGB = {0xED737B, 0x79B6F0, 0x77C69D, 0xE4C86C};
    static final int IVORY = 0xFFF2CC, NAVY = 0x0B1A3A, BLUE = 0x3C7BD6, GOLD = 0xFFC75A, GOLD_TEXT = 0xFFE3A0, RANK_GOLD = 0xFFD27A,
            MUTED = 0xC9D6E6, WHITE = 0xF1EEE3, OUTLINE = 0x0A0A0F;

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(HouseBoardProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > HouseBoardProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    private static HouseBoardProtocol.State state = HouseBoardProtocol.State.EMPTY;
    /** 바뀌기 전 점수 (숫자 겹쳐 바뀜·줄 미끄러짐용). */
    private static List<Long> before = HouseBoardProtocol.State.EMPTY.scores();
    private static long changedAt;
    private static CodexTypography text;

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            HouseBoardProtocol.State next;
            try { next = HouseBoardProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(next));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(HouseBoardClient::reset));
        WorldRenderEvents.START.register(ctx -> { if (text != null) text.beginFrame(); });
        WorldRenderEvents.AFTER_ENTITIES.register(HouseBoardClient::render);
    }

    private static void reset() { state = HouseBoardProtocol.State.EMPTY; before = state.scores(); changedAt = 0; }

    private static void accept(HouseBoardProtocol.State next) {
        var old = state;
        boolean animate = !old.boards().isEmpty() && old.world().equals(next.world()) && !old.scores().equals(next.scores());
        // 바뀌는 중에 또 바뀌면 직전 값(이미 거의 다 바뀐 쪽)에서 다시 시작
        if (animate) { before = old.scores(); changedAt = Util.getMeasuringTimeMs(); }
        else { before = next.scores(); changedAt = 0; }
        state = next;
    }

    // ------------------------------------------------------------------ world

    private static void render(WorldRenderContext ctx) {
        var s = state;
        var c = MinecraftClient.getInstance();
        if (s.boards().isEmpty() || c.world == null || c.player == null || c.options.hudHidden) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        if (text == null) { text = new CodexTypography(c); text.beginFrame(); }
        var cam = ctx.camera().getPos();
        long now = Util.getMeasuringTimeMs();
        float t = (now % 3_600_000L) / 1000f;
        float k = changedAt == 0 ? 1 : Math.clamp((now - changedAt) / (float) CHANGE_MS, 0f, 1f);
        float ease = k * k * (3 - 2 * k);
        if (k >= 1 && changedAt != 0) { changedAt = 0; before = s.scores(); }
        int[] slotNew = HouseBoardProtocol.slots(s.scores()), slotOld = HouseBoardProtocol.slots(before);
        int[] rank = HouseBoardProtocol.ranks(s.scores());
        double range = s.range();
        for (var b : s.boards()) {
            double bob = Math.sin(t * 1.6f + b.x() * .37 + b.z() * .53) * .04;
            double dx = b.x() - cam.x, dy = b.y() + bob - cam.y, dz = b.z() - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > range) continue;
            // 앞면 쪽에서만: 판에서 카메라로 가는 방향과 앞면 방향의 cos (수평)
            int[] n = HouseBoardProtocol.normal(b.facing());
            double flat = Math.sqrt(dx * dx + dz * dz);
            double front = flat < 1e-3 ? 0 : (-dx * n[0] - dz * n[1]) / flat;
            if (front <= .08) continue;
            float alpha = (float) (Math.clamp((range - dist) / 8, 0, 1) * Math.clamp((dist - .8) / 1.5, 0, 1) * Math.clamp((front - .08) / .3, 0, 1));
            if (alpha <= .02f) continue;
            matrices.push();
            try {
                matrices.translate(dx, dy, dz);
                matrices.multiply(new Quaternionf().rotateY(-b.facing() * (float) (Math.PI / 2)));
                matrices.scale(SCALE, SCALE, SCALE);
                board(matrices, out, s.scores(), rank, slotOld, slotNew, ease, alpha, t);
            } finally { matrices.pop(); }
        }
    }

    private static void board(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider out,
                              List<Long> scores, int[] rank, int[] slotOld, int[] slotNew, float ease, float alpha, float t) {
        float hum = .5f + .5f * (float) Math.sin(t * 1.25f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumer halo = out.getBuffer(RenderLayer.getEntityTranslucentEmissive(HALO));
        // 반투명 남색 판 + 푸른 테두리 빛 (책 블록·웨이포인트 halo 그림)
        quad(halo, m, 0, PANEL_Y * PX, -.03f, (PANEL_W + 2 * PANEL_STEP) * 1.2f * PX, PANEL_H * 1.2f * PX, argb(alpha * (.16f + .06f * hum), BLUE));
        for (int i = -1; i <= 1; i++) quad(halo, m, i * PANEL_STEP * PX, PANEL_Y * PX, -.02f, PANEL_W * PX, PANEL_H * PX, argb(alpha * .42f, NAVY));
        // 제목 아래 금빛 가는 줄
        quad(halo, m, 0, DIVIDER_Y * PX, -.01f, 2400 * PX, 40 * PX, argb(alpha * .55f, GOLD));
        // 1위 줄 금빛 띠 (같은 점수 1위가 여럿이면 모두)
        for (int h = 0; h < HouseBoardProtocol.HOUSES; h++) if (rank[h] == 1) {
            float y = rowY(slotOld[h], slotNew[h], ease);
            for (int i = -1; i <= 1; i++) quad(halo, m, i * 900 * PX, y * PX, -.015f, 1800 * PX, 260 * PX, argb(alpha * (.18f + .07f * hum), GOLD));
        }
        outlined(matrices, out, TITLE, 0, TITLE_Y, TITLE_SIZE, alpha, IVORY);
        for (int h = 0; h < HouseBoardProtocol.HOUSES; h++) {
            float y = rowY(slotOld[h], slotNew[h], ease);
            boolean first = rank[h] == 1;
            outlined(matrices, out, rank[h] + "위", RANK_X, y, ROW_SIZE, alpha, first ? RANK_GOLD : MUTED);
            String name = SchoolProtocol.HOUSES.get(h);
            float nw = text.width(name, ROW_SIZE, FACE);
            outlined(matrices, out, name, NAME_LEFT + nw / 2, y, ROW_SIZE, alpha, FishingArt.mix(0xFF000000 | HOUSE_RGB[h], 0xFF000000 | IVORY, .25f) & 0xFFFFFF);
            String now = score(scores.get(h)), was = score(before.get(h));
            int color = first ? GOLD_TEXT : WHITE;
            if (now.equals(was) || ease >= 1) outlined(matrices, out, now, SCORE_RIGHT - text.width(now, ROW_SIZE, FACE) / 2, y, ROW_SIZE, alpha, color);
            else {
                outlined(matrices, out, was, SCORE_RIGHT - text.width(was, ROW_SIZE, FACE) / 2, y + 60 * ease, ROW_SIZE, alpha * (1 - ease), color);
                outlined(matrices, out, now, SCORE_RIGHT - text.width(now, ROW_SIZE, FACE) / 2, y - 60 * (1 - ease), ROW_SIZE, alpha * ease, color);
            }
        }
    }

    static float rowY(int from, int to, float ease) { return ROW_Y0 - (from + (to - from) * ease) * ROW_GAP; }
    static String score(long v) { return String.format(java.util.Locale.ROOT, "%,d점", v); }

    /** 차카데미 글자 규칙 (웨이포인트·청크 점유 글씨와 같음): 검은 외곽선 8장 + 채움. */
    private static void outlined(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider out,
                                 String value, float cx, float cy, float size, float alpha, int rgb) {
        if (alpha <= .02f) return;
        int outline = argb(alpha * .85f, OUTLINE);
        float o = 9;
        float[][] offs = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o * .7f, -o * .7f}, {o * .7f, -o * .7f}, {-o * .7f, o * .7f}, {o * .7f, o * .7f}};
        for (float[] off : offs) text.drawWorld(matrices, out, value, cx + off[0], cy + off[1], size, 1600, outline);
        text.drawWorld(matrices, out, value, cx, cy, size, 1600, argb(alpha, rgb));
    }

    /** 앞면(+Z)만 보이는 사각형. */
    private static void quad(VertexConsumer vc, Matrix4f m, float ox, float oy, float z, float w, float h, int color) {
        if ((color >>> 24) == 0) return;
        float x0 = ox - w / 2, x1 = ox + w / 2, y0 = oy - h / 2, y1 = oy + h / 2;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        vc.vertex(m, x0, y0, z).color(color).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x1, y0, z).color(color).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x1, y1, z).color(color).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
        vc.vertex(m, x0, y1, z).color(color).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1);
    }
    private static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }
}
