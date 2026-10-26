package school.magiccodex.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import school.magiccodex.protocol.TravelProtocol;

/**
 * 웨이포인트 월드 표시 (2026-10-24, owner: WaypointGlobe VFX + 아직 등록 안 한 유저에게는 "웨이포인트" 글씨).
 *
 * <ul>
 *   <li>The approved PortableVFX pack WaypointGlobe (portable-vfx-runtime/assets/WaypointGlobe): {@code idle} loops on
 *       every public waypoint of the world the player is in, played locally by this client only while the player is
 *       within {@value #START} blocks (stopped past {@value #STOP}) — nothing plays for far points and the server sends
 *       nothing per tick. {@code activate} plays once on the spot when the server records the player's registration.</li>
 *   <li>Waypoints this player has not registered yet show "웨이포인트" above the globe like the story's
 *       "퀘스트 목표 지점" label (MagicCodex font, dark outline, soft glow), within {@value #LABEL_DIST} blocks.</li>
 * </ul>
 * PortableVFX is reached by reflection (its client API, same contract the broom trails use); without it only the label shows.
 */
final class TravelWorldMarks {
    private TravelWorldMarks() {}

    static final double START = 48, STOP = 56, LABEL_DIST = 32;
    static final String IDLE = "claude:waypointglobe/idle", ACTIVATE = "claude:waypointglobe/activate";
    /** The pack's loop default is 12000 ticks; restart a little before it ends. */
    static final long LOOP_MS = 9 * 60_000;
    private static final Identifier HALO = Identifier.of("magiccodex", "textures/gui/travel/world/halo.png");
    /** Label height above the waypoint floor: the globe centre is 1.85 m, the globe about 0.5 m across. */
    private static final double LABEL_Y = 2.95;
    private static final String LABEL = "웨이포인트";

    private record Playing(UUID handle, long since) {}
    private static final Map<String, Playing> playing = new HashMap<>();
    private static String world = "";
    private static CodexTypography text;

    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> stopAll());
        WorldRenderEvents.START.register(ctx -> { if (text != null) text.beginFrame(); });
        WorldRenderEvents.AFTER_ENTITIES.register(TravelWorldMarks::render);
    }

    /** New marker list from the server: a point that just became registered plays {@code activate} once. */
    static void changed(TravelProtocol.Markers before, TravelProtocol.Markers after) {
        if (!before.world().equals(after.world())) { stopAll(); return; }
        var was = new HashMap<String, Boolean>();
        before.markers().forEach(m -> was.put(m.id(), m.unlocked()));
        var c = MinecraftClient.getInstance();
        for (var m : after.markers())
            if (m.unlocked() && Boolean.FALSE.equals(was.get(m.id())) && c.player != null && c.player.squaredDistanceTo(m.x(), m.y(), m.z()) < STOP * STOP)
                Vfx.play(ACTIVATE, new Vec3d(m.x(), m.y(), m.z()), 0);
        var ids = new HashSet<String>(); after.markers().forEach(m -> ids.add(m.id()));
        for (var it = playing.entrySet().iterator(); it.hasNext(); ) { var e = it.next(); if (!ids.contains(e.getKey())) { Vfx.finish(e.getValue().handle()); it.remove(); } }
    }

    private static void stopAll() {
        playing.values().forEach(p -> Vfx.stop(p.handle()));
        playing.clear();
    }

    /** Starts / stops the idle loops by distance (client tick, a handful of points at most). */
    private static void tick() {
        var c = MinecraftClient.getInstance();
        var data = TravelClient.markers;
        if (c.world == null || c.player == null) { if (!playing.isEmpty()) stopAll(); return; }
        if (!data.world().equals(world)) { stopAll(); world = data.world(); }
        if (!Vfx.ready()) return;
        long now = Util.getMeasuringTimeMs();
        var active = Vfx.active();
        for (var m : data.markers()) {
            double d2 = c.player.squaredDistanceTo(m.x(), m.y(), m.z());
            Playing p = playing.get(m.id());
            if (d2 > STOP * STOP) { if (p != null) { Vfx.finish(p.handle()); playing.remove(m.id()); } continue; }
            if (d2 > START * START && p == null) continue;
            if (p != null && active.contains(p.handle()) && now - p.since() < LOOP_MS) continue;
            if (p != null) Vfx.stop(p.handle());
            UUID handle = Vfx.play(IDLE, new Vec3d(m.x(), m.y(), m.z()), 0);
            if (handle != null) playing.put(m.id(), new Playing(handle, now)); else playing.remove(m.id());
        }
    }

    // ------------------------------------------------------------------ "웨이포인트" label (not registered yet)

    private static void render(WorldRenderContext ctx) {
        var c = MinecraftClient.getInstance();
        var data = TravelClient.markers;
        if (c.world == null || c.player == null || c.options.hudHidden || data.markers().isEmpty()) return;
        var matrices = ctx.matrixStack(); var out = ctx.consumers();
        if (matrices == null || out == null) return;
        if (text == null) { text = new CodexTypography(c); text.beginFrame(); }
        var cam = ctx.camera().getPos();
        float t = (Util.getMeasuringTimeMs() % 3_600_000L) / 1000f;
        for (var m : data.markers()) {
            if (m.unlocked()) continue;
            double dx = m.x() - cam.x, dy = m.y() + LABEL_Y + Math.sin(t * 1.6f) * .05 - cam.y, dz = m.z() - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > LABEL_DIST) continue;
            float alpha = (float) Math.clamp((LABEL_DIST - dist) / 8, 0, 1);
            float hum = .5f + .5f * (float) Math.sin(t * 1.25f);
            matrices.push();
            try {
                matrices.translate(dx, dy, dz);
                matrices.multiply(ctx.camera().getRotation());
                // soft glow behind (bloom), then dark outline copies, then the warm ivory fill (차카데미 글자 규칙)
                halo(out.getBuffer(RenderLayer.getEntityTranslucentEmissive(HALO)), matrices.peek().getPositionMatrix(), .85f, .34f, argb(alpha * (.22f + .12f * hum), 0x80D1FF));
                int outline = argb(alpha * .85f, 0x0A0A0F);
                float o = 9;
                float[][] offs = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o * .7f, -o * .7f}, {o * .7f, -o * .7f}, {-o * .7f, o * .7f}, {o * .7f, o * .7f}};
                for (float[] off : offs) text.drawWorld(matrices, out, LABEL, off[0], off[1], 250, 1600, outline);
                text.drawWorld(matrices, out, LABEL, 0, 0, 250, 1600, argb(alpha, 0xFFF2CC));
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

    // ------------------------------------------------------------------ PortableVFX client API (reflection, optional)

    /**
     * Contract (PortableVFX client dev.portablevfx.client.api.PortableVfxApi, same as chacademy-motion's broom trails):
     * PortableVfxClient.api(); play(Identifier, Vec3d, float scale, int durationTicks) → Optional&lt;UUID&gt; (0 = pack
     * default, loop systems 12000 ticks); finish(UUID) / stop(UUID) → boolean; activeInstances() → Set&lt;UUID&gt;.
     * Client thread only. A broken contract turns the globe off (one log line), the label stays.
     */
    private static final class Vfx {
        private enum State { UNRESOLVED, READY, UNAVAILABLE }
        private static State state = State.UNRESOLVED;
        private static Object api;
        private static Method play, finish, stop, active;

        static boolean ready() { if (state == State.UNRESOLVED) resolve(); return state == State.READY; }

        private static void resolve() {
            if (!FabricLoader.getInstance().isModLoaded("portablevfx")) { off("PortableVFX 모드 없음 → 웨이포인트 VFX 끔", null); return; }
            try {
                Class<?> client = Class.forName("dev.portablevfx.client.PortableVfxClient");
                Object a;
                try { a = client.getMethod("api").invoke(null); } catch (InvocationTargetException notYet) { return; }   // not initialised yet: next tick
                Method p = null, f = null, s = null;
                for (Method m : a.getClass().getMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (m.getName().equals("play") && t.length == 4 && t[2] == float.class && t[3] == int.class && Optional.class.isAssignableFrom(m.getReturnType())) p = m;
                    if (m.getName().equals("finish") && t.length == 1 && t[0] == UUID.class) f = m;
                    if (m.getName().equals("stop") && t.length == 1 && t[0] == UUID.class) s = m;
                }
                Method ac = a.getClass().getMethod("activeInstances");
                if (p == null || f == null || s == null) { off("PortableVFX API 형식이 다름 → 웨이포인트 VFX 끔", null); return; }
                api = a; play = p; finish = f; stop = s; active = ac; state = State.READY;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { off("PortableVFX 연결 실패 → 웨이포인트 VFX 끔", e); }
        }
        private static void off(String why, Throwable e) {
            state = State.UNAVAILABLE; api = null;
            if (e == null) org.slf4j.LoggerFactory.getLogger("magiccodex").info(why);
            else org.slf4j.LoggerFactory.getLogger("magiccodex").warn("{} ({})", why, e.toString());
        }
        private static Object call(Method m, Object... args) {
            try { return m.invoke(api, args); }
            catch (ReflectiveOperationException | RuntimeException e) { off("PortableVFX 호출 오류 → 웨이포인트 VFX 끔", e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e); return null; }
        }
        static UUID play(String effect, Vec3d at, int ticks) {
            if (!ready()) return null;
            Object r = call(play, Identifier.of(effect), at, 1f, ticks);
            return r instanceof Optional<?> o && o.isPresent() && o.get() instanceof UUID id ? id : null;
        }
        static void finish(UUID id) { if (id != null && ready()) call(finish, id); }
        static void stop(UUID id) { if (id != null && ready()) call(stop, id); }
        @SuppressWarnings("unchecked")
        static Set<UUID> active() { if (!ready()) return Set.of(); Object r = call(active); return r instanceof Set<?> s ? (Set<UUID>) s : Set.of(); }
    }
}
