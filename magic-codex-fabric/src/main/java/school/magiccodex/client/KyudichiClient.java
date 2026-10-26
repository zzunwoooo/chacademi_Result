package school.magiccodex.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import school.magiccodex.protocol.KyudichiProtocol;

/**
 * 큐디치 client: state from the arena server (chacademi:kyudichi), the F-key reservation, and Ctrl pings.
 * Drawing lives in {@link KyudichiHud}. The F action itself goes through vanilla swap-hands (the server listens to it),
 * so the key works even without this mod; the mod only keeps a spell bound to F from eating it during a match.
 */
public final class KyudichiClient {
    private KyudichiClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID = new Id<>(Identifier.of(KyudichiProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n > KyudichiProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); } byte[] d = new byte[n]; b.readBytes(d); return new Payload(d); }
            public void encode(RegistryByteBuf b, Payload v) { b.writeBytes(v.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    // ------------------------------------------------------------------ state (render thread)
    static KyudichiProtocol.Match match; static long matchAt;
    /** The last match seen was a 1:1 대전 (the result message has no mode; the result screen reads this). */
    static boolean lastDuel;
    static KyudichiProtocol.Key key = new KyudichiProtocol.Key(0, 0, 0); static long keyAt, keyLitAt;
    static KyudichiProtocol.Balls balls, prevBalls; static long ballsAt, prevBallsAt;
    static List<KyudichiProtocol.Shrine> shrines = List.of();
    record FeedLine(KyudichiProtocol.Feed feed, long at) {}
    static final List<FeedLine> feed = new ArrayList<>();
    record PingMark(KyudichiProtocol.Ping ping, long at) {}
    static final List<PingMark> pings = new ArrayList<>();
    static KyudichiProtocol.Result result; static long resultAt;
    static final Map<UUID, Integer> teams = new HashMap<>();
    /** ROSTER: house, broom and rating per player (preparation window). */
    static final Map<UUID, KyudichiProtocol.Entry> roster = new HashMap<>();

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            KyudichiProtocol.Message m;
            try { m = KyudichiProtocol.decode(payload.bytes()); } catch (IllegalArgumentException bad) { return; }
            context.client().execute(() -> { KyudichiPreview.realArena(); receive(m); });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
        KyudichiBoundary.initialize();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(KyudichiClient::tick);
        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.attachLayerAfter(IdentifiedLayer.CROSSHAIR,
                Identifier.of("magiccodex", "kyudichi"), KyudichiHud::render));
    }

    static void reset() {
        match = null; balls = prevBalls = null; shrines = List.of(); feed.clear(); pings.clear(); result = null; teams.clear(); roster.clear(); gesture = null;
        key = new KyudichiProtocol.Key(0, 0, 0);
        KyudichiHud.close();
    }

    static void receive(KyudichiProtocol.Message m) {
        long now = Util.getMeasuringTimeMs();
        switch (m) {
            case KyudichiProtocol.Match x -> {
                match = x; matchAt = now; lastDuel = (x.flags() & 16) != 0;
                teams.clear(); for (var p : x.players()) teams.put(p.id(), p.team());
                if (x.phase() == 0 && result != null && now - resultAt > 3000) result = null;
            }
            case KyudichiProtocol.Key x -> {
                if (x.action() != 0 && x.action() != key.action()) keyLitAt = now;
                key = x; keyAt = now;
            }
            case KyudichiProtocol.Balls x -> { prevBalls = balls; prevBallsAt = ballsAt; balls = x; ballsAt = now; }
            case KyudichiProtocol.Shrines x -> shrines = x.shrines();
            case KyudichiProtocol.Feed x -> { feed.add(new FeedLine(x, now)); while (feed.size() > 4) feed.remove(0); }
            case KyudichiProtocol.Ping x -> {
                pings.removeIf(p -> p.ping().sender().equals(x.sender()) && now - p.at() < 400);
                pings.add(new PingMark(x, now)); while (pings.size() > 12) pings.remove(0);
                var c = MinecraftClient.getInstance();
                c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), x.kind() == 3 ? 0.8f : 1.4f, 0.35f));
            }
            case KyudichiProtocol.Roster x -> { roster.clear(); for (var e : x.entries()) roster.put(e.id(), e); }
            case KyudichiProtocol.Bounds x -> KyudichiBoundary.set(x);
            case KyudichiProtocol.Result x -> {
                result = x; resultAt = now; match = null; balls = prevBalls = null; shrines = List.of(); pings.clear(); KyudichiBoundary.clear();
                var c = MinecraftClient.getInstance();
                if (c.currentScreen == null || HudPointer.hudVisibleUnder(c.currentScreen) || c.currentScreen instanceof KyudichiPrepareScreen
                        || c.currentScreen instanceof KyudichiSpellPickScreen) c.setScreen(new KyudichiResultScreen(x));
            }
        }
    }

    /** A match HUD is up (packets arrive every second; 4 s silence = gone, e.g. server switch). */
    static boolean active() { return match != null && match.phase() != 5 && Util.getMeasuringTimeMs() - matchAt < 4000; }
    static boolean live() { return active() && match.phase() == 2; }
    static boolean spectator() { return active() && match.myTeam() == 2; }
    /** Playing (not spectating): the match loadout replaces the bottom-right six, and aimed spells may stay in the air. */
    /**
     * Mixin (Entity.shouldRender): during a match other players, the ball models (ModelEngine bones are display entities)
     * and their armor-stand bases are drawn at any distance.
     */
    public static boolean renderFar(net.minecraft.entity.Entity e) {
        if (match == null || !active()) return false;
        return e instanceof net.minecraft.entity.player.PlayerEntity || e instanceof net.minecraft.entity.decoration.DisplayEntity
                || e instanceof net.minecraft.entity.decoration.ArmorStandEntity;
    }

    /** Match flag 16: a 1:1 대전 (no balls, no F key, ground only). */
    static boolean duel() { return match != null && (match.flags() & 16) != 0 && Util.getMeasuringTimeMs() - matchAt < 4000; }
    static boolean playing() { return active() && match.myTeam() != 2; }
    public static boolean airAim() { return playing(); }
    /** In a match (playing or spectating): the match HUD knows every player's team. */
    static boolean inMatch() { return active(); }
    /** C03: same team as me in the current match (match teams replace the party during a match). */
    static boolean teammate(UUID id) {
        if (!playing() || id == null) return false;
        Integer t = teams.get(id);
        return t != null && t == match.myTeam();
    }

    private static int lastPhase = -1;
    /** Every client tick: switch the magic bar to the match loadout and open the setup window when a match's preparation starts. */
    /** Mixin (F3+B): no hitboxes while a match (or spectating one) is going on. */
    public static boolean hitboxesLocked() { return active(); }

    private static void tick(MinecraftClient c) {
        if (active() && c.getEntityRenderDispatcher().shouldRenderHitboxes()) c.getEntityRenderDispatcher().setRenderHitboxes(false);
        boolean on = playing();
        CastingClient.setMatchLoadout(on);
        int phase = on ? match.phase() : -1;
        if (phase == 0 && lastPhase != 0 && c.currentScreen == null && c.player != null) c.setScreen(new KyudichiPrepareScreen());
        lastPhase = phase;
    }
    static void openSetup() {
        var c = MinecraftClient.getInstance();
        if (playing() && match.phase() == 0) c.setScreen(new KyudichiPrepareScreen());
    }

    /**
     * CodexKeyboardMixin, before casting: during the preparation the 큐디치 key (F) opens the preparation window again
     * (it has nothing else to do before the start).
     */
    public static boolean handleKey(MinecraftClient c, int key, int scan, int action) {
        if (action != 1 || c.currentScreen != null || !playing() || !c.options.swapHandsKey.matchesKey(key, scan)) return false;
        if (match.phase() == 0) { openSetup(); return true; }
        // owner 2026-10-17: F throws the quaffle / hits a bludger toward the crosshair (not the body's facing). The point goes
        // first; vanilla then sends swap-hands (the server's F) on its next tick.
        if (live() && !duel()) sendAim(crosshairPoint(c));
        return false;
    }

    /** World point under the screen centre (the crosshair), from the camera (shoulder view aware); 120 blocks if nothing is hit. */
    static Vec3d crosshairPoint(MinecraftClient c) {
        var p = c.player; var w = c.world;
        if (p == null || w == null) return null;
        Vec3d from, dir;
        if (AimView.valid()) {
            var win = c.getWindow();
            from = AimView.camera(); dir = AimView.ray(win.getScaledWidth() / 2f, win.getScaledHeight() / 2f).normalize();
        } else { from = p.getCameraPosVec(1f); dir = p.getRotationVec(1f); }
        Vec3d end = from.add(dir.multiply(120));
        var hit = w.raycast(new RaycastContext(from, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d at = hit.getType() == HitResult.Type.MISS ? end : hit.getPos();
        // too close (aiming at a wall right behind the shoulder camera): use the far point along the same ray
        return at.squaredDistanceTo(p.getEyePos()) < 4 ? end : at;
    }

    /** Right before an aimed cast whose point is in the air: the server uses this point once (MagicAimPointEvent). */
    static void sendAim(Vec3d p) {
        if (p != null && ClientPlayNetworking.canSend(Payload.ID))
            ClientPlayNetworking.send(new Payload(KyudichiProtocol.encode(new KyudichiProtocol.Aim((float) p.x, (float) p.y, (float) p.z))));
    }

    /**
     * CodexKeyboardMixin: during a match the swap-hands key (F) belongs to 큐디치, so a spell bound to the same key must not
     * consume it. Returning true makes the mixin skip CastingClient for this key; vanilla then sends swap-hands to the server.
     */
    public static boolean reservesKey(MinecraftClient c, int key, int scan) {
        // 1:1 대전 has no F action after the preparation: F stays a normal key (a spell bound to it works)
        return active() && !spectator() && !(duel() && match.phase() != 0) && c.options.swapHandsKey.matchesKey(key, scan);
    }

    static String keyLabel() { return MinecraftClient.getInstance().options.swapHandsKey.getBoundKeyLocalizedText().getString(); }

    // ------------------------------------------------------------------ ball interpolation
    /** Interpolated position of ball (kind,index) or null. */
    static Vec3d ball(KyudichiProtocol.Ball b) {
        if (prevBalls != null) for (var p : prevBalls.balls()) if (p.kind() == b.kind() && p.index() == b.index()) {
            long span = Math.max(1, ballsAt - prevBallsAt);
            double t = Math.clamp((Util.getMeasuringTimeMs() - ballsAt) / (double) span, 0, 1);
            return new Vec3d(p.x() + (b.x() - p.x()) * t, p.y() + (b.y() - p.y()) * t, p.z() + (b.z() - p.z()) * t);
        }
        return new Vec3d(b.x(), b.y(), b.z());
    }

    static Entity player(UUID id) {
        var w = MinecraftClient.getInstance().world;
        return w == null ? null : w.getPlayerByUuid(id);
    }

    // ------------------------------------------------------------------ Ctrl pings
    /** Ctrl + click = magnet ping; Ctrl + drag = direction menu (위 경고 · 오른쪽 가는 중 · 아래 도움 · 왼쪽 힐 줘). */
    record Gesture(double x, double y, long at) {}
    static Gesture gesture;
    static final int WHEEL_RADIUS = 18;   // GUI px of drag before the menu takes over

    /** Chat / cursor screen click on the spectator "나가기" button (KyudichiHud.leave): asks the arena server to end spectating. */
    public static boolean leaveClick(double x, double y, int button) {
        if (!active() || !spectator() || button != 0 || !KyudichiHud.leaveHit(x, y)) return false;
        if (ClientPlayNetworking.canSend(Payload.ID)) ClientPlayNetworking.send(new Payload(KyudichiProtocol.encode(new KyudichiProtocol.SpectateLeave())));
        var c = MinecraftClient.getInstance();
        c.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 1f, .4f));
        if (c.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) c.setScreen(null);
        return true;
    }

    /** HudCursorScreen.mouseClicked, after the other HUD widgets. */
    public static boolean cursorPress(double x, double y, int button) {
        if(HudCustomization.editingGesture())return false;
        if (!active() || spectator() || button != 0) return false;
        if (KyudichiHud.forfeitHit(x, y)) {
            if (ClientPlayNetworking.canSend(Payload.ID)) ClientPlayNetworking.send(new Payload(KyudichiProtocol.encode(new KyudichiProtocol.Forfeit(true))));
            return true;
        }
        gesture = new Gesture(x, y, Util.getMeasuringTimeMs());
        return true;
    }

    /** HudCursorScreen.mouseReleased. */
    public static boolean cursorRelease(double x, double y, int button) {
        var g = gesture; gesture = null;
        if (g == null || button != 0 || !active()) return false;
        int dir = direction(x - g.x(), y - g.y());
        if (dir >= 0) sendPing(dir, null, worldPoint(g.x(), g.y()));
        else {
            var target = Magnet.find(g.x(), g.y());
            if (target != null) sendPing(target.kind(), target.player(), target.pos());
            else sendPing(0, null, worldPoint(g.x(), g.y()));
        }
        return true;
    }

    /** Wire ping kind for a drag, or -1 for a click. */
    static int direction(double dx, double dy) {
        if (dx * dx + dy * dy < WHEEL_RADIUS * WHEEL_RADIUS) return -1;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 4 : 6;   // 가는 중 · 힐 줘
        return dy < 0 ? 3 : 5;                                      // 경고 · 도움
    }

    private static void sendPing(int kind, UUID target, Vec3d pos) {
        if (pos == null || !ClientPlayNetworking.canSend(Payload.ID)) return;
        ClientPlayNetworking.send(new Payload(KyudichiProtocol.encode(new KyudichiProtocol.PingRequest(kind, (float) pos.x, (float) pos.y, (float) pos.z, target))));
    }

    /** Where the cursor points in the world (blocks up to 160, else 48 blocks out in the air). */
    static Vec3d worldPoint(double sx, double sy) {
        var c = MinecraftClient.getInstance();
        if (c.world == null || c.player == null || !AimView.valid()) return null;
        Vec3d from = AimView.camera(), dir = AimView.ray((float) sx, (float) sy).normalize();
        var hit = c.world.raycast(new RaycastContext(from, from.add(dir.multiply(160)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, c.player));
        return hit.getType() == HitResult.Type.MISS ? from.add(dir.multiply(48)) : hit.getPos();
    }

    /** Snaps a cursor point to the nearest ball / player / shrine on screen (60 px at 1080p). */
    static final class Magnet {
        record Target(int kind, UUID player, Vec3d pos, float sx, float sy) {}
        static Target find(double sx, double sy) {
            var c = MinecraftClient.getInstance();
            if (c.player == null || !AimView.valid()) return null;
            float radius = 60f * c.getWindow().getScaledHeight() / 1080f;
            Target best = null; double bestD = radius * radius; int bestPri = 9;
            var cands = new ArrayList<Target>();
            if (balls != null) for (var b : balls.balls()) { Vec3d p = ball(b); cands.add(new Target(7 + b.kind(), null, p, 0, 0)); }
            for (var e : teams.entrySet()) {
                if (e.getKey().equals(c.player.getUuid())) continue;
                Entity pe = player(e.getKey());
                if (!(pe instanceof PlayerEntity pl) || pl.isSpectator()) continue;
                boolean ally = match != null && e.getValue() == match.myTeam();
                cands.add(new Target(ally ? 1 : 2, e.getKey(), pe.getPos().add(0, pe.getHeight() * 0.6, 0), 0, 0));
            }
            for (var s : shrines) cands.add(new Target(s.type() == 3 ? 14 : 10 + s.type(), null, new Vec3d(s.x(), s.y(), s.z()), 0, 0));
            for (var t : cands) {
                float[] p = AimView.toScreen(t.pos());
                if (p == null) continue;
                double d = (p[0] - sx) * (p[0] - sx) + (p[1] - sy) * (p[1] - sy);
                int pri = t.kind() >= 7 && t.kind() <= 9 ? 0 : t.kind() <= 2 ? 1 : 2;   // 공 > 선수 > 성소
                if (d <= radius * radius && (pri < bestPri || pri == bestPri && d < bestD)) { best = new Target(t.kind(), t.player(), t.pos(), p[0], p[1]); bestD = d; bestPri = pri; }
            }
            return best;
        }
    }
}
