package school.magiccodex.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import school.magiccodex.protocol.ArtifactFxProtocol;

/**
 * 아티팩트 동행 연출 (그룹 D, 2026-10-26). 서버는 장착 상태(바뀔 때만)와 짧은 사건만 보내고, 모양은 전부 여기서 그린다.
 * <ul>
 *   <li>화염 정령: PortableVFX {@code claude:artifactcompanions/fire_spirit} 를 그 플레이어에 붙여(playAttached) 재생.
 *       불덩이는 {@code spirit_bolt} 를 매 틱 move 로 정령 → 대상까지 5틱에 옮기고 {@code spirit_hit}.</li>
 *   <li>거울 쌍둥이: 그 플레이어의 자세를 매 틱 기록해 0.5초(10틱) 늦은 자세로 스킨을 반투명 하늘색으로 한 번 더 그린다.
 *       따라 쓰기 순간 대상에 {@code mirror_echo}.</li>
 *   <li>수다 앵무새: 어깨 앵무새는 바닐라 어깨 앵무새 렌더러로 ({@code ArtifactParrotMixin}), 말풍선은 parrot_bubble.png 3조각 + MagicCodex 글꼴.</li>
 * </ul>
 * PortableVFX 는 리플렉션(TravelWorldMarks 와 같은 계약 + playAttached/move). 없으면 정령·불덩이·메아리만 안 보이고 나머지는 그대로.
 */
public final class ArtifactFxClient {
    private ArtifactFxClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID = new Id<>(Identifier.of(ArtifactFxProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n > ArtifactFxProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); } byte[] v = new byte[n]; b.readBytes(v); return new Payload(v); }
            public void encode(RegistryByteBuf b, Payload p) { b.writeBytes(p.bytes); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static final String SPIRIT = "claude:artifactcompanions/fire_spirit", BOLT = "claude:artifactcompanions/spirit_bolt",
            HIT = "claude:artifactcompanions/spirit_hit", ECHO = "claude:artifactcompanions/mirror_echo";
    /** 정령 위치 (vfx.json fire_spirit 와 같은 값): 오른쪽 0.42, 높이 1.78, 뒤 0.38. */
    static final double SP_RIGHT = 0.42, SP_UP = 1.78, SP_BACK = 0.38;
    static final int TWIN_DELAY = 10, BOLT_TICKS = 5;
    static final double SPIRIT_RANGE = 64, TWIN_RANGE = 48;
    static final long SPIRIT_LOOP_MS = 9 * 60_000, BUBBLE_MS = 4000;
    private static final Identifier BUBBLE = Identifier.of("magiccodex", "textures/gui/artifact_fx/parrot_bubble.png");

    private static final Map<UUID, Integer> flags = new HashMap<>();
    private record Playing(UUID handle, int entity, long since) {}
    private static final Map<UUID, Playing> spirits = new HashMap<>();
    private record Bolt(UUID handle, Vec3d from, int target, int[] age) {}
    private static final List<Bolt> bolts = new ArrayList<>();
    private record Pose(double x, double y, double z, float body, float head, float pitch, float limbPos, float limbSpeed, float swing, boolean sneak) {}
    private static final Map<UUID, ArrayDeque<Pose>> trails = new HashMap<>();
    private record Bubble(String text, long until) {}
    private static final Map<UUID, Bubble> bubbles = new HashMap<>();
    private static CodexTypography text;
    private static Object world;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (p, c) -> {
            ArtifactFxProtocol.Message m;
            try { m = ArtifactFxProtocol.decode(p.bytes()); } catch (IllegalArgumentException bad) { return; }
            c.client().execute(() -> receive(m));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
        WorldRenderEvents.START.register(ctx -> { if (text != null) text.beginFrame(); });
        WorldRenderEvents.AFTER_ENTITIES.register(ArtifactFxClient::render);
    }

    /** ArtifactParrotMixin: 이 플레이어 어깨에 앵무새를 그릴지. */
    public static boolean parrot(UUID player) { return (flags.getOrDefault(player, 0) & ArtifactFxProtocol.PARROT) != 0; }

    private static void reset() {
        spirits.values().forEach(s -> Vfx.stop(s.handle())); spirits.clear();
        bolts.forEach(b -> Vfx.stop(b.handle())); bolts.clear();
        flags.clear(); trails.clear(); bubbles.clear(); world = null;
        if (text != null) { text.close(); text = null; }
    }

    private static void receive(ArtifactFxProtocol.Message m) {
        var c = MinecraftClient.getInstance();
        if (c.world == null) return;
        switch (m) {
            case ArtifactFxProtocol.States s -> {
                if (s.replace()) flags.clear();
                s.flags().forEach((id, f) -> { if (f == 0) flags.remove(id); else flags.put(id, f); });
            }
            case ArtifactFxProtocol.Shot s -> {
                PlayerEntity caster = c.world.getPlayerByUuid(s.caster());
                Entity target = c.world.getEntityById(s.target());
                if (caster == null || target == null) return;
                if (s.kind() == ArtifactFxProtocol.SPIRIT_SHOT) {
                    Vec3d from = spiritPos(caster, 1);
                    UUID h = Vfx.play(BOLT, from, 1f, 40);
                    if (h != null) bolts.add(new Bolt(h, from, s.target(), new int[]{0}));
                } else {
                    Vfx.play(ECHO, target.getPos(), (float) Math.clamp(target.getHeight() / 1.8, 0.6, 2.2), 0);
                }
            }
            case ArtifactFxProtocol.Bubble b -> bubbles.put(b.speaker(), new Bubble(b.text(), Util.getMeasuringTimeMs() + BUBBLE_MS));
        }
    }

    /** 정령 자리 (몸 방향 기준, vfx.json 과 같은 오프셋). */
    static Vec3d spiritPos(PlayerEntity p, float delta) {
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(delta, p.prevBodyYaw, p.bodyYaw));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        Vec3d feet = p.getLerpedPos(delta);
        return feet.add(rx * SP_RIGHT - fx * SP_BACK, SP_UP, rz * SP_RIGHT - fz * SP_BACK);
    }

    // ------------------------------------------------------------------ tick: 정령 VFX · 불덩이 · 쌍둥이 기록

    private static void tick() {
        var c = MinecraftClient.getInstance();
        if (c.world == null || c.player == null) { if (!flags.isEmpty() || !spirits.isEmpty()) reset(); return; }
        if (c.world != world) { spirits.values().forEach(s -> Vfx.stop(s.handle())); spirits.clear(); trails.clear(); bubbles.clear(); world = c.world; }
        long now = Util.getMeasuringTimeMs();
        // 화염 정령
        if (Vfx.ready()) {
            var active = Vfx.active();
            for (var it = spirits.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next(); PlayerEntity p = c.world.getPlayerByUuid(e.getKey());
                boolean keep = (flags.getOrDefault(e.getKey(), 0) & ArtifactFxProtocol.SPIRIT) != 0 && p != null && p.getId() == e.getValue().entity()
                        && !p.isInvisible() && !p.isSpectator() && p.squaredDistanceTo(c.player) < SPIRIT_RANGE * SPIRIT_RANGE
                        && active.contains(e.getValue().handle()) && now - e.getValue().since() < SPIRIT_LOOP_MS;
                if (!keep) { Vfx.finish(e.getValue().handle()); it.remove(); }
            }
            for (var e : flags.entrySet()) {
                if ((e.getValue() & ArtifactFxProtocol.SPIRIT) == 0 || spirits.containsKey(e.getKey())) continue;
                PlayerEntity p = c.world.getPlayerByUuid(e.getKey());
                if (p == null || p.isInvisible() || p.isSpectator() || p.squaredDistanceTo(c.player) >= SPIRIT_RANGE * SPIRIT_RANGE) continue;
                UUID h = Vfx.playAttached(SPIRIT, p, 12000);
                if (h != null) spirits.put(e.getKey(), new Playing(h, p.getId(), now));
            }
        }
        // 불덩이: 5틱 동안 정령 → 대상 가슴
        for (var it = bolts.iterator(); it.hasNext(); ) {
            var b = it.next(); Entity t = c.world.getEntityById(b.target());
            int age = ++b.age()[0];
            if (t == null) { Vfx.finish(b.handle()); it.remove(); continue; }
            Vec3d to = t.getPos().add(0, t.getHeight() * 0.55, 0);
            double k = Math.min(1, age / (double) BOLT_TICKS), arc = Math.sin(k * Math.PI) * 0.35;
            Vfx.move(b.handle(), b.from().lerp(to, k).add(0, arc, 0));
            if (age >= BOLT_TICKS) { Vfx.finish(b.handle()); Vfx.play(HIT, to, 1f, 0); it.remove(); }
        }
        // 거울 쌍둥이 자세 기록
        trails.keySet().removeIf(id -> (flags.getOrDefault(id, 0) & ArtifactFxProtocol.TWIN) == 0);
        for (var e : flags.entrySet()) {
            if ((e.getValue() & ArtifactFxProtocol.TWIN) == 0) continue;
            PlayerEntity p = c.world.getPlayerByUuid(e.getKey());
            if (p == null || p.squaredDistanceTo(c.player) > TWIN_RANGE * TWIN_RANGE) { trails.remove(e.getKey()); continue; }
            var d = trails.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
            d.addLast(new Pose(p.getX(), p.getY(), p.getZ(), p.bodyYaw, p.headYaw, p.getPitch(), p.limbAnimator.getPos(), p.limbAnimator.getSpeed(),
                    p.getHandSwingProgress(1f), p.isInSneakingPose()));
            while (d.size() > TWIN_DELAY + 2) d.removeFirst();
        }
        bubbles.values().removeIf(b -> b.until() < now);
    }

    // ------------------------------------------------------------------ render: 거울 쌍둥이 · 말풍선

    private static void render(WorldRenderContext ctx) {
        var c = MinecraftClient.getInstance();
        if (c.world == null || c.player == null) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        float delta = ctx.tickCounter().getTickDelta(false);
        Vec3d cam = ctx.camera().getPos();
        for (var e : trails.entrySet()) {
            var d = e.getValue();
            if (d.size() < TWIN_DELAY + 2) continue;
            PlayerEntity p = c.world.getPlayerByUuid(e.getKey());
            if (!(p instanceof AbstractClientPlayerEntity player) || p.isInvisible() || p.isSpectator()) continue;
            var list = new ArrayList<>(d);
            Pose a = list.get(list.size() - TWIN_DELAY - 2), b = list.get(list.size() - TWIN_DELAY - 1);
            try { twin(c, ctx, player, a, b, delta, cam); }
            catch (RuntimeException | LinkageError err) { trails.clear(); flags.replaceAll((k, v) -> v & ~ArtifactFxProtocol.TWIN); org.slf4j.LoggerFactory.getLogger("magiccodex").warn("거울 쌍둥이 그리기 오류 → 끔 ({})", err.toString()); return; }
        }
        if (!bubbles.isEmpty() && !c.options.hudHidden) {
            if (text == null) { text = new CodexTypography(c); text.beginFrame(); }
            long now = Util.getMeasuringTimeMs();
            for (var e : bubbles.entrySet()) {
                PlayerEntity p = c.world.getPlayerByUuid(e.getKey());
                if (p == null || p.isInvisible() || p.isSpectator() || (p == c.player && c.options.getPerspective().isFirstPerson())) continue;
                if (p.squaredDistanceTo(cam) > 32 * 32) continue;
                bubble(ctx, p, e.getValue(), delta, cam, now);
            }
        }
    }

    /** 0.5초 늦은 자세로 그 플레이어 스킨을 반투명 하늘색으로 한 번 더 그린다. */
    private static void twin(MinecraftClient c, WorldRenderContext ctx, AbstractClientPlayerEntity player, Pose a, Pose b, float delta, Vec3d cam) {
        double x = MathHelper.lerp(delta, a.x(), b.x()), y = MathHelper.lerp(delta, a.y(), b.y()), z = MathHelper.lerp(delta, a.z(), b.z());
        // 내 분신이 1인칭 카메라와 겹치면(가만히 서 있을 때) 그리지 않는다
        if (player == c.player && c.options.getPerspective().isFirstPerson() && cam.squaredDistanceTo(x, y + 1.6, z) < 1.0) return;
        if (!(c.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerEntityRenderer renderer)) return;
        var state = renderer.getAndUpdateRenderState(player, delta);
        float body = MathHelper.lerpAngleDegrees(delta, a.body(), b.body()), head = MathHelper.lerpAngleDegrees(delta, a.head(), b.head());
        state.bodyYaw = body;
        state.yawDegrees = MathHelper.wrapDegrees(head - body);
        state.pitch = MathHelper.lerp(delta, a.pitch(), b.pitch());
        state.limbFrequency = MathHelper.lerp(delta, a.limbPos(), b.limbPos());
        state.limbAmplitudeMultiplier = MathHelper.lerp(delta, a.limbSpeed(), b.limbSpeed());
        state.handSwingProgress = MathHelper.lerp(delta, a.swing(), b.swing());
        state.isInSneakingPose = b.sneak();
        var model = renderer.getModel();
        var matrices = ctx.matrixStack();
        matrices.push();
        try {
            matrices.translate(x - cam.x, y - cam.y + (b.sneak() ? -0.125 : 0), z - cam.z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - body));
            matrices.scale(-1f, -1f, 1f);
            matrices.scale(0.9375f, 0.9375f, 0.9375f);
            matrices.translate(0f, -1.501f, 0f);
            model.setAngles(state);
            VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getEntityTranslucent(state.skinTextures.texture()));
            model.render(matrices, vc, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, 0x80BFEFFF);
        } finally { matrices.pop(); }
    }

    /** 앵무새(오른쪽 어깨) 머리 위에 꼬리가 오도록, 3조각 말풍선 + 글자. */
    private static void bubble(WorldRenderContext ctx, PlayerEntity p, Bubble b, float delta, Vec3d cam, long now) {
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(delta, p.prevBodyYaw, p.bodyYaw));
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        Vec3d tip = p.getLerpedPos(delta).add(rx * 0.40, (p.isInSneakingPose() ? 1.62 : 1.86), rz * 0.40);
        float alpha = (float) Math.clamp((b.until() - now) / 400.0, 0, 1);
        if (alpha <= 0) return;
        float size = 170, factor = NameplatePolicy.WORLD_WIDTH / 2048f;
        float textW = text.width(b.text(), size, Identifier.of("magiccodex", "hud_bold")) * factor;
        float h = 0.34f, px = h / 64f;                       // 텍스처 64 px 높이
        float cap = 32 * px, mid = Math.max(0, textW + 22 * px - 2 * cap + 24 * px);
        float left = -6 * px;                                // 꼬리 끝(텍스처 x≈6)이 tip 에 오도록
        var matrices = ctx.matrixStack();
        matrices.push();
        try {
            matrices.translate(tip.x - cam.x, tip.y - cam.y, tip.z - cam.z);
            matrices.multiply(ctx.camera().getRotation());
            Matrix4f m = matrices.peek().getPositionMatrix();
            int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
            VertexConsumer v = ctx.consumers().getBuffer(RenderLayer.getText(BUBBLE));
            float x0 = left, x1 = x0 + cap, x2 = x1 + mid, x3 = x2 + cap;
            quad(v, m, x0, x1, 0, h, 0f, 1f / 3, color);
            quad(v, m, x1, x2, 0, h, 1f / 3, 2f / 3, color);
            quad(v, m, x2, x3, 0, h, 2f / 3, 1f, color);
            // 글자: 몸통(텍스처 y 0..48) 가운데, 진한 갈색
            float cx = (x0 + x3) / 2 / factor, cy = (h - 24 * px - 1 * px) / factor;
            text.drawWorld(matrices, ctx.consumers(), b.text(), cx, cy, size, (x3 - x0) / factor, ((int) (alpha * 255) << 24) | 0x3A2A22);
        } finally { matrices.pop(); }
    }
    private static void quad(VertexConsumer v, Matrix4f m, float x0, float x1, float y0, float y1, float u0, float u1, int color) {
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        v.vertex(m, x0, y1, 0).color(color).texture(u0, 0).light(light);
        v.vertex(m, x0, y0, 0).color(color).texture(u0, 1).light(light);
        v.vertex(m, x1, y0, 0).color(color).texture(u1, 1).light(light);
        v.vertex(m, x1, y1, 0).color(color).texture(u1, 0).light(light);
    }

    // ------------------------------------------------------------------ PortableVFX client API (리플렉션, 선택)

    /**
     * 계약: dev.portablevfx.client.PortableVfxClient.api() → PortableVfxApi: play(Identifier, Vec3d, float, int) → Optional&lt;UUID&gt;,
     * playAttached(Identifier, Entity, EffectAnchor, Vec3d, float, int, long) → Optional&lt;UUID&gt;, move(UUID, Vec3d), finish(UUID),
     * stop(UUID), activeInstances(). 클라 스레드 전용. 계약이 깨지면 이 기능만 끈다 (로그 한 줄).
     */
    private static final class Vfx {
        private enum State { UNRESOLVED, READY, UNAVAILABLE }
        private static State state = State.UNRESOLVED;
        private static Object api, entityAnchor;
        private static Method play, attached, move, finish, stop, active;

        static boolean ready() { if (state == State.UNRESOLVED) resolve(); return state == State.READY; }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void resolve() {
            if (!FabricLoader.getInstance().isModLoaded("portablevfx")) { off("PortableVFX 모드 없음 → 아티팩트 동행 VFX 끔", null); return; }
            try {
                Class<?> client = Class.forName("dev.portablevfx.client.PortableVfxClient");
                Object a;
                try { a = client.getMethod("api").invoke(null); } catch (InvocationTargetException notYet) { return; }
                Method p = null, at = null, mv = null, f = null, s = null;
                for (Method m : a.getClass().getMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (m.getName().equals("play") && t.length == 4 && t[2] == float.class && t[3] == int.class) p = m;
                    if (m.getName().equals("playAttached") && t.length == 7) at = m;
                    if (m.getName().equals("move") && t.length == 2 && t[0] == UUID.class) mv = m;
                    if (m.getName().equals("finish") && t.length == 1 && t[0] == UUID.class) f = m;
                    if (m.getName().equals("stop") && t.length == 1 && t[0] == UUID.class) s = m;
                }
                if (p == null || at == null || mv == null || f == null || s == null) { off("PortableVFX API 형식이 다름 → 아티팩트 동행 VFX 끔", null); return; }
                entityAnchor = Enum.valueOf((Class) at.getParameterTypes()[2], "ENTITY");
                api = a; play = p; attached = at; move = mv; finish = f; stop = s; active = a.getClass().getMethod("activeInstances");
                state = State.READY;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { off("PortableVFX 연결 실패 → 아티팩트 동행 VFX 끔", e); }
        }
        private static void off(String why, Throwable e) {
            state = State.UNAVAILABLE; api = null;
            if (e == null) org.slf4j.LoggerFactory.getLogger("magiccodex").info(why);
            else org.slf4j.LoggerFactory.getLogger("magiccodex").warn("{} ({})", why, e.toString());
        }
        private static Object call(Method m, Object... args) {
            try { return m.invoke(api, args); }
            catch (ReflectiveOperationException | RuntimeException e) { off("PortableVFX 호출 오류 → 아티팩트 동행 VFX 끔", e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e); return null; }
        }
        private static UUID id(Object r) { return r instanceof Optional<?> o && o.isPresent() && o.get() instanceof UUID u ? u : null; }
        static UUID play(String effect, Vec3d at, float scale, int ticks) { return ready() ? id(call(play, Identifier.of(effect), at, scale, ticks)) : null; }
        static UUID playAttached(String effect, Entity target, int ticks) {
            return ready() ? id(call(attached, Identifier.of(effect), target, entityAnchor, Vec3d.ZERO, 1f, ticks, (long) target.getId())) : null;
        }
        static void move(UUID id, Vec3d at) { if (id != null && ready()) call(move, id, at); }
        static void finish(UUID id) { if (id != null && ready()) call(finish, id); }
        static void stop(UUID id) { if (id != null && ready()) call(stop, id); }
        @SuppressWarnings("unchecked")
        static Set<UUID> active() { if (!ready()) return Set.of(); Object r = call(active); return r instanceof Set<?> s ? (Set<UUID>) s : Set.of(); }
    }
}
