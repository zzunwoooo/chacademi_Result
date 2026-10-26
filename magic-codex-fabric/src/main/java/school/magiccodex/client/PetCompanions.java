package school.magiccodex.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import school.magiccodex.protocol.PetCompanionProtocol;

/**
 * 동행 펫 (오너 2026-10-26, 메모 #81). 서버가 채널 {@value PetCompanionProtocol#CHANNEL} 로 "이 세계에서 누가 어떤 펫을 꺼냈는지"를
 * 보내면, 모든 주인(나 포함 다른 사람도)의 펫을 펫 창과 같은 bbmodel 로 작게 그려 주인 주변을 걷거나 날게 한다.
 * <ul>
 *   <li>움직임은 클라 전용({@link PetCompanionMotion}, 틱마다), 그리기는 틱 사이를 섞어 부드럽게.</li>
 *   <li>모델: config/magiccodex/pets/models/&lt;펫ID&gt;.bbmodel (+aliases.json) — 비동기로 읽고 24개까지 들고 있음.</li>
 *   <li>안 그림: 주인이 안 보임(관전자·투명)·64블록 밖·세계가 다름. 접속 끊김·세계 바뀜이면 목록 비움.</li>
 *   <li>flags 에 FLAG_CAST 가 켜진 펫은 발밑에 PortableVFX {@value #CAST} (변색종 _shiny 는 {@value #CAST_SHINY}) 를 한 번.
 *       PortableVFX 모드·팩이 없으면 조용히 생략.</li>
 * </ul>
 */
final class PetCompanions {
    private PetCompanions() {}

    static final double RANGE = 64;
    static final String CAST = "claude:petpaw/circle", CAST_SHINY = "claude:petpaw/circle_shiny";
    static final int CACHE = 24;

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(PetCompanionProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > PetCompanionProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    /** 주인 한 명의 펫과 그 움직임. */
    private static final class Companion {
        final PetCompanionProtocol.Pet pet; final PetCompanionMotion motion;
        Companion(PetCompanionProtocol.Pet pet, PetCompanionMotion motion) { this.pet = pet; this.motion = motion; }
    }
    private static final Map<Integer, Companion> companions = new LinkedHashMap<>();
    private static String worldName = "";
    /** 목록을 받았을 때의 클라 세계 — 바뀌면(차원 이동·재접속) 목록을 버린다. */
    private static ClientWorld listWorld;
    private static PetModelRenderer models;
    private static int modelGeneration = -1;
    private static final Quaternionf turn = new Quaternionf();
    private static final BlockPos.Mutable lightPos = new BlockPos.Mutable();
    private static final PetCompanionMotion.Ground GROUND = (x, fromY, z) -> {
        var w = MinecraftClient.getInstance().world;
        if (w == null) return Double.NaN;
        Double top = AimTargeting.Ground.top(w, x, fromY, z, 6);
        return top == null ? Double.NaN : top;
    };

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            PetCompanionProtocol.Pets next;
            try { next = PetCompanionProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(next));
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { clear(); closeModels(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> closeModels());
        ClientTickEvents.END_CLIENT_TICK.register(PetCompanions::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(PetCompanions::render);
    }

    private static void clear() { companions.clear(); worldName = ""; listWorld = null; }
    private static void closeModels() { if (models != null) { models.close(); models = null; } }
    /** /펫도감 새로고침: 모델 파일 다시 읽기. */
    static void reloadModels() { closeModels(); }
    private static PetModelRenderer models() {
        if (models == null || modelGeneration != UiResources.generation()) {
            if (models != null) models.close();
            models = new PetModelRenderer(CACHE); modelGeneration = UiResources.generation();
        }
        return models;
    }

    private static void accept(PetCompanionProtocol.Pets next) {
        var c = MinecraftClient.getInstance();
        if (c.world == null) return;
        if (!next.world().equals(worldName) || c.world != listWorld) companions.clear();
        worldName = next.world(); listWorld = c.world;
        var old = new HashMap<>(companions);
        companions.clear();
        for (var pet : next.pets()) {
            Companion was = old.get(pet.owner());
            // 같은 펫이면 움직임을 이어서, 다른 펫으로 바뀌었으면 새로 (주인 옆에 나타남)
            var motion = was != null && was.pet.id().equals(pet.id()) ? was.motion : new PetCompanionMotion(31L * pet.owner() + pet.id().hashCode());
            var companion = new Companion(pet, motion);
            companions.put(pet.owner(), companion);
            if ((pet.flags() & PetCompanionProtocol.FLAG_CAST) != 0) cast(c, companion);
        }
    }

    /** 능력 발동: 펫 발밑에 원 VFX 한 번 (변색종은 _shiny). */
    private static void cast(MinecraftClient c, Companion companion) {
        if (c.player == null || c.world == null) return;
        Entity owner = c.world.getEntityById(companion.pet.owner());
        if (owner == null || c.player.squaredDistanceTo(owner) > RANGE * RANGE) return;
        var m = companion.motion;
        Vec3d at = m.placed ? new Vec3d(m.x, m.y, m.z) : new Vec3d(owner.getX(), owner.getY(), owner.getZ());
        Vfx.play(companion.pet.id().endsWith("_shiny") ? CAST_SHINY : CAST, at);
    }

    private static void tick(MinecraftClient c) {
        if (c.world == null || c.player == null) { if (!companions.isEmpty()) clear(); return; }
        if (companions.isEmpty()) return;
        if (c.world != listWorld) { clear(); return; }
        for (var companion : companions.values()) {
            Entity owner = c.world.getEntityById(companion.pet.owner());
            if (owner == null || c.player.squaredDistanceTo(owner) > RANGE * RANGE) { companion.motion.unplace(); continue; }
            companion.motion.tick(companion.pet.motion(), owner.getX(), owner.getY(), owner.getZ(), owner.isTouchingWater(), GROUND);
        }
    }

    private static void render(WorldRenderContext ctx) {
        var c = MinecraftClient.getInstance();
        if (c.world == null || c.player == null || companions.isEmpty() || c.world != listWorld) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        float delta = ctx.tickCounter().getTickDelta(false);
        Vec3d cam = ctx.camera().getPos();
        PetModelRenderer renderer = null;
        for (var companion : companions.values()) {
            var m = companion.motion;
            if (!m.placed) continue;
            Entity owner = c.world.getEntityById(companion.pet.owner());
            if (owner == null || owner.isSpectator() || owner.isInvisibleTo(c.player) || !owner.isAlive()) continue;
            double x = m.x(delta), y = m.y(delta), z = m.z(delta);
            double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
            if (renderer == null) renderer = models();
            int light = WorldRenderer.getLightmapCoordinates(c.world, lightPos.set(MathHelper.floor(x), MathHelper.floor(y + .25), MathHelper.floor(z)));
            matrices.push();
            try {
                matrices.translate(dx, dy, dz);
                matrices.multiply(turn.identity().rotateY(m.heading(delta)));
                renderer.drawWorld(matrices.peek().getPositionMatrix(), out, companion.pet.id(), m.anim, m.animTime(delta), companion.pet.scale(), light);
            } catch (RuntimeException e) {
                // 모델 하나가 망가져도 다른 펫·게임은 계속 (그 펫만 이번 목록에서 뺀다)
                org.slf4j.LoggerFactory.getLogger("magiccodex").warn("동행 펫 그리기 오류 {} ({})", companion.pet.id(), e.toString());
                m.unplace();
            } finally { matrices.pop(); }
        }
    }

    // ------------------------------------------------------------------ PortableVFX client API (리플렉션, 선택)

    /** TravelWorldMarks 와 같은 계약: PortableVfxClient.api().play(Identifier, Vec3d, float, int) → Optional&lt;UUID&gt;. 없으면 조용히 생략. */
    private static final class Vfx {
        private enum State { UNRESOLVED, READY, UNAVAILABLE }
        private static State state = State.UNRESOLVED;
        private static Object api;
        private static Method play;

        private static boolean ready() { if (state == State.UNRESOLVED) resolve(); return state == State.READY; }
        private static void resolve() {
            if (!FabricLoader.getInstance().isModLoaded("portablevfx")) { off("PortableVFX 모드 없음 → 동행 펫 발동 VFX 끔", null); return; }
            try {
                Class<?> client = Class.forName("dev.portablevfx.client.PortableVfxClient");
                Object a;
                try { a = client.getMethod("api").invoke(null); } catch (InvocationTargetException notYet) { return; }
                Method p = null;
                for (Method m : a.getClass().getMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (m.getName().equals("play") && t.length == 4 && t[2] == float.class && t[3] == int.class && Optional.class.isAssignableFrom(m.getReturnType())) p = m;
                }
                if (p == null) { off("PortableVFX API 형식이 다름 → 동행 펫 발동 VFX 끔", null); return; }
                api = a; play = p; state = State.READY;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { off("PortableVFX 연결 실패 → 동행 펫 발동 VFX 끔", e); }
        }
        private static void off(String why, Throwable e) {
            state = State.UNAVAILABLE; api = null;
            if (e == null) org.slf4j.LoggerFactory.getLogger("magiccodex").info(why);
            else org.slf4j.LoggerFactory.getLogger("magiccodex").warn("{} ({})", why, e.toString());
        }
        /** 팩에 그 효과가 없으면 PortableVFX 가 빈 Optional 을 돌려줄 뿐 — 그대로 둔다. */
        static void play(String effect, Vec3d at) {
            if (!ready()) return;
            try { play.invoke(api, Identifier.of(effect), at, 1f, 0); }
            catch (ReflectiveOperationException | RuntimeException e) { off("PortableVFX 호출 오류 → 동행 펫 발동 VFX 끔", e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e); }
        }
    }
}
