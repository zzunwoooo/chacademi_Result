package school.magiccodex.client;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import school.magiccodex.protocol.TrainingDummyProtocol;
import school.magiccodex.protocol.TrainingDummyProtocol.Kind;

/**
 * 마법 연습장 훈련 허수아비 머리 위 표시 (오픈 전 메모 #107, 2026-10-26, {@link TrainingDummyProtocol}).
 * <ul>
 *   <li>누적: 머리 바로 위 "누적 1,234" + 그 아래 작은 "초당 56.7". 마지막 타에서 resetTicks(기본 10초) 지나면 0.5초 동안 흐려지며 사라짐.</li>
 *   <li>타격 숫자(메이플 데미지 스킨 식): 누적 줄 위에서 맞을 때마다 숫자가 톡 커졌다가 1.1초 동안 위로 천천히 올라가며 투명해짐.
 *       0.35초 안에 연달아 맞으면 위로 한 칸씩 쌓고 좌우로 번갈아 흩는다. 큰 피해 = 크고 빨강, 원소 반응 = 하늘색, 지속 피해 = 작고 보라.</li>
 * </ul>
 * 숫자 글씨는 MagicCodex 폰트(잠실 엑스트라볼드)를 코드로 한 번만 굵은 테두리·위아래 그라데이션 글자판(텍스처 1장)으로 구워 두고,
 * 월드 공간 빌보드 사각형으로 이어 붙인다 — 피해 숫자마다 글자를 새로 굽지 않는다. 멀수록 작아진다(원근 그대로).
 * 부하: 서버는 맞을 때만 보낸다. 그릴 것이 없으면 렌더 콜백은 바로 끝난다.
 */
public final class TrainingDummyClient {
    private TrainingDummyClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(TrainingDummyProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > TrainingDummyProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    /** 떠오르는 숫자 하나. dx/dy = 겹치지 않게 흩은 자리(블록). */
    private record Pop(String text, Kind kind, long start, float dx, float dy) {}

    private static final class Dummy {
        double x, y, z; float total, dps; long lastHit, resetMs = 10_000;
        final ArrayDeque<Pop> pops = new ArrayDeque<>();
    }

    static final double SHOW_DIST = 48;
    static final int MAX_DUMMIES = 32, MAX_POPS = 24;
    static final long POP_MS = 1100, FADE_FROM_MS = 550, BURST_MS = 80, STACK_MS = 350, CLEAR_FADE_MS = 500;
    /** 기준점(모델 머리 꼭대기) 위 높이(블록). */
    static final float DPS_Y = .10f, TOTAL_Y = .36f, POP_Y = .75f, RISE = .55f, STACK_STEP = .30f;
    /** 글자 높이(블록) = 숫자 "0"의 실제 높이 기준. */
    static final float TOTAL_H = .20f, LABEL_H = .16f, DPS_H = .12f;
    static final int TOTAL_RGB = 0xFFF2CC, LABEL_RGB = 0xE8D6A8, DPS_RGB = 0xC9C2B4;

    private static final Map<Integer, Dummy> dummies = new LinkedHashMap<>();
    private static ClientWorld world;
    private static Atlas atlas;
    private static boolean atlasFailed;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            TrainingDummyProtocol.Hit hit;
            try { hit = TrainingDummyProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(hit));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> dummies.clear());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> dummies.clear());
        WorldRenderEvents.AFTER_ENTITIES.register(TrainingDummyClient::render);
    }

    // ------------------------------------------------------------------ 받기

    private static void accept(TrainingDummyProtocol.Hit hit) {
        sync();
        long now = Util.getMeasuringTimeMs();
        Dummy d = dummies.get(hit.id());
        if (d == null) {
            if (dummies.size() >= MAX_DUMMIES) dummies.remove(dummies.keySet().iterator().next());
            d = new Dummy(); dummies.put(hit.id(), d);
        }
        d.x = hit.x(); d.y = hit.y(); d.z = hit.z();
        d.total = hit.total(); d.dps = hit.dps(); d.lastHit = now; d.resetMs = hit.resetTicks() * 50L;
        int recent = 0;
        for (Pop p : d.pops) if (now - p.start() < STACK_MS) recent++;
        float[] at = stack(recent, hit.id() * 31L + now);
        d.pops.addLast(new Pop(TrainingDummyProtocol.format(hit.amount()), hit.kind(), now, at[0], at[1]));
        while (d.pops.size() > MAX_POPS) d.pops.removeFirst();
    }

    /**
     * Pure: n번째로 연달아 들어온 숫자의 자리 {dx, dy}. 0번은 가운데, 그다음부터 위로 한 칸(0.30)씩(4칸에서 다시 아래부터),
     * 좌우로 번갈아 0.14~0.26 블록, 약간의 흔들림(seed).
     */
    static float[] stack(int n, long seed) {
        if (n <= 0) return new float[]{0, 0};
        float side = (n % 2 == 1) ? 1 : -1;
        float jitter = (float) (((seed * 0x9E3779B97F4A7C15L) >>> 40) % 1000) / 1000f * .06f - .03f;
        return new float[]{side * (.14f + .06f * ((n / 2) % 3)) + jitter, (n % 4) * STACK_STEP};
    }

    private static void sync() {
        var c = MinecraftClient.getInstance();
        if (c.world != world) { dummies.clear(); world = c.world; }
    }

    // ------------------------------------------------------------------ 그리기

    private static void render(WorldRenderContext ctx) {
        if (dummies.isEmpty()) return;
        sync();
        var c = MinecraftClient.getInstance();
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (c.world == null || c.player == null || c.options.hudHidden || matrices == null || out == null) return;
        long now = Util.getMeasuringTimeMs();
        // 다 사라진 허수아비 정리
        for (var it = dummies.values().iterator(); it.hasNext(); ) {
            Dummy d = it.next();
            while (!d.pops.isEmpty() && now - d.pops.peekFirst().start() > POP_MS) d.pops.removeFirst();
            if (d.pops.isEmpty() && now - d.lastHit > d.resetMs) it.remove();
        }
        if (dummies.isEmpty()) return;
        Atlas a = atlas(c);
        if (a == null) return;
        var cam = ctx.camera().getPos();
        VertexConsumer v = out.getBuffer(RenderLayer.getText(a.id));
        for (Dummy d : dummies.values()) {
            double dx = d.x - cam.x, dy = d.y - cam.y, dz = d.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > SHOW_DIST * SHOW_DIST) continue;
            matrices.push();
            try {
                matrices.translate(dx, dy, dz);
                matrices.multiply(ctx.camera().getRotation());
                Matrix4f m = matrices.peek().getPositionMatrix();
                long since = now - d.lastHit;
                if (since <= d.resetMs && d.total > 0) {
                    float alpha = (float) Math.clamp((d.resetMs - since) / (double) CLEAR_FADE_MS, 0, 1);
                    // 누적 줄: 작은 "누적" + 큰 숫자, 그 아래 더 작은 "초당 n"
                    a.line(v, m, 0, TOTAL_Y, new Run[]{new Run("누적", LABEL_H, argb(alpha, LABEL_RGB)),
                        new Run(TrainingDummyProtocol.format(d.total), TOTAL_H, argb(alpha, TOTAL_RGB))});
                    a.line(v, m, 0, DPS_Y, new Run[]{new Run("초당", DPS_H, argb(alpha * .9f, DPS_RGB)),
                        new Run(TrainingDummyProtocol.format(d.dps), DPS_H, argb(alpha * .9f, DPS_RGB))});
                }
                for (Pop p : d.pops) {
                    float t = (now - p.start()) / (float) POP_MS;
                    if (t < 0 || t > 1) continue;
                    long ms = now - p.start();
                    float burst = ms < BURST_MS ? 1.45f - .45f * (ms / (float) BURST_MS) : 1f;
                    float rise = RISE * (1 - (float) Math.pow(1 - t, 3));
                    float alpha = ms < FADE_FROM_MS ? 1 : 1 - (ms - FADE_FROM_MS) / (float) (POP_MS - FADE_FROM_MS);
                    a.line(v, m, p.dx(), POP_Y + p.dy() + rise, new Run[]{new Run(p.text(), height(p.kind()) * burst, argb(alpha, color(p.kind())))});
                }
            } finally { matrices.pop(); }
        }
    }

    static float height(Kind k) {
        return switch (k) { case HEAVY -> .42f; case REACTION -> .34f; case DOT -> .20f; default -> .28f; };
    }
    /** 보통 = 주황, 큰 피해 = 빨강, 원소 반응 = 하늘색, 지속 피해 = 보라. 글자판의 위아래 밝기 차이에 곱해진다. */
    static int color(Kind k) {
        return switch (k) { case HEAVY -> 0xFF4A3D; case REACTION -> 0x6FE0FF; case DOT -> 0xC29BFF; default -> 0xFFB238; };
    }
    private static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }

    private record Run(String text, float height, int color) {}

    // ------------------------------------------------------------------ 글자판 (숫자·쉼표·점·"누적"·"초당")

    private static Atlas atlas(MinecraftClient c) {
        if (atlas != null || atlasFailed) return atlas;
        try { atlas = Atlas.bake(c); }
        catch (RuntimeException | LinkageError e) { atlasFailed = true; System.err.println("[MagicCodex] 허수아비 피해 숫자 글자판 실패: " + e); }
        return atlas;
    }

    private static final class Atlas {
        static final String[] KEYS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ",", ".", "누적", "초당"};
        static final float PX = 72, STROKE = 9, PAD = 12;
        /** 한 글자 칸: 그림 u0~u1, 칸 폭(px), 글자 진행 폭(px). */
        record G(float u0, float u1, int width, float advance) {}
        /** cellH = 칸 높이(px), baseline = 칸 위에서 바닥선까지(px), cap = 숫자 "0"의 높이(px). */
        final Identifier id; final Map<String, G> glyphs; final int cellH; final float baseline, cap;
        Atlas(Identifier id, Map<String, G> glyphs, int cellH, float baseline, float cap) {
            this.id = id; this.glyphs = glyphs; this.cellH = cellH; this.baseline = baseline; this.cap = cap;
        }

        static Atlas bake(MinecraftClient c) {
            var typography = new CodexTypography(c);
            Font base = typography.font("ui_extra_bold");
            if (base == null) base = typography.font("hud_bold");
            Font font = base.deriveFont(Font.PLAIN, PX);
            var frc = new FontRenderContext(null, true, true);
            var lm = font.getLineMetrics("0누", frc);
            int cellH = (int) Math.ceil(lm.getAscent() + lm.getDescent() + PAD * 2);
            float baseline = PAD + lm.getAscent();
            var layouts = new ArrayList<TextLayout>(); var widths = new int[KEYS.length]; int total = 0;
            for (int i = 0; i < KEYS.length; i++) {
                var l = new TextLayout(KEYS[i], font, frc);
                layouts.add(l);
                widths[i] = (int) Math.ceil(l.getAdvance() + PAD * 2);
                total += widths[i] + 2;
            }
            var img = new BufferedImage(total, cellH, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            var glyphs = new HashMap<String, G>();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int x = 0;
                for (int i = 0; i < KEYS.length; i++) {
                    var shape = layouts.get(i).getOutline(AffineTransform.getTranslateInstance(x + PAD, baseline));
                    // 굵은 어두운 테두리 → 위는 흰색, 아래로 갈수록 살짝 어두운 그라데이션 글자 (색은 그릴 때 곱한다)
                    g.setColor(new Color(0x1C, 0x12, 0x10));
                    g.setStroke(new BasicStroke(STROKE * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                    g.setPaint(new GradientPaint(0, baseline - lm.getAscent() * .8f, Color.WHITE, 0, baseline, new Color(176, 176, 176)));
                    g.fill(shape);
                    glyphs.put(KEYS[i], new G(x / (float) total, (x + widths[i]) / (float) total, widths[i], layouts.get(i).getAdvance()));
                    x += widths[i] + 2;
                }
            } finally { g.dispose(); }
            var nativeImage = new NativeImage(img.getWidth(), img.getHeight(), false);
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                // 투명한 칸도 테두리 색을 남겨 두어 가장자리에 흰 테가 생기지 않게
                nativeImage.setColorArgb(x, y, (argb >>> 24) == 0 ? 0x001C1210 : argb);
            }
            var id = Identifier.of("magiccodex", "runtime_text/training_dummy_digits");
            var texture = new NativeImageBackedTexture(nativeImage);
            texture.setFilter(true, false);
            c.getTextureManager().registerTexture(id, texture);
            float cap = (float) layouts.get(0).getBounds().getHeight();
            return new Atlas(id, Map.copyOf(glyphs), cellH, baseline, Math.max(1, cap));
        }

        /** 여러 조각(크기·색이 다른 글자열)을 한 줄로 가운데 맞춰 그린다. cx, cy = 줄 가운데(블록, 빌보드 평면). */
        void line(VertexConsumer v, Matrix4f m, float cx, float cy, Run[] runs) {
            float gapBetweenRuns = .06f, width = 0, tallest = 0;
            for (int r = 0; r < runs.length; r++) {
                width += advance(runs[r]);
                if (r > 0) width += gapBetweenRuns * runs[r].height() / TOTAL_H;
                tallest = Math.max(tallest, runs[r].height());
            }
            // 크기가 다른 조각도 같은 바닥선에 앉힌다 (가장 큰 글자가 줄 가운데에 오게)
            float line = cy - tallest / 2;
            float x = cx - width / 2;
            for (int r = 0; r < runs.length; r++) {
                Run run = runs[r];
                if (r > 0) x += gapBetweenRuns * run.height() / TOTAL_H;
                float s = run.height() / cap;
                float top = line + baseline * s, bottom = line - (cellH - baseline) * s;
                for (String key : keys(run.text())) {
                    G gl = glyphs.get(key);
                    if (gl == null) continue;
                    float left = x - PAD * s, right = left + gl.width() * s;
                    v.vertex(m, left, top, 0).color(run.color()).texture(gl.u0(), 0).light(0xF000F0);
                    v.vertex(m, left, bottom, 0).color(run.color()).texture(gl.u0(), 1).light(0xF000F0);
                    v.vertex(m, right, bottom, 0).color(run.color()).texture(gl.u1(), 1).light(0xF000F0);
                    v.vertex(m, right, top, 0).color(run.color()).texture(gl.u1(), 0).light(0xF000F0);
                    x += gl.advance() * s * .94f;
                }
            }
        }

        private float advance(Run run) {
            float s = run.height() / cap, w = 0;
            for (String key : keys(run.text())) { G gl = glyphs.get(key); if (gl != null) w += gl.advance() * s * .94f; }
            return w;
        }

        /** "누적"·"초당"은 한 덩어리, 나머지는 한 글자씩. */
        static List<String> keys(String text) {
            if (text.equals("누적") || text.equals("초당")) return List.of(text);
            var out = new ArrayList<String>(text.length());
            for (int i = 0; i < text.length(); i++) out.add(String.valueOf(text.charAt(i)));
            return out;
        }
    }
}
