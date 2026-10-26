package school.magiccodex.client;

import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.StatusProtocol;

/** Status effects per entity id, as told by the server (MagicMythic). Display only. */
public final class StatusClient {
    private StatusClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(StatusProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > StatusProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    /** One icon: client clock start/end (end = Long.MAX_VALUE without end). Order = server order (oldest first). */
    public record Line(StatusProtocol.Status status, long start, long end) {
        boolean infinite() { return end == Long.MAX_VALUE; }
        float fraction(long now) { return infinite() ? 1 : Math.clamp((end - now) / (float) Math.max(1, end - start), 0, 1); }
        /** Last second: blink like the element marks. */
        float blink(long now) { return !infinite() && end - now < 1000 ? .45f + .55f * (float) Math.abs(Math.sin(now / 110.0)) : 1f; }
    }

    private static final Map<Integer, List<Line>> lines = new HashMap<>();
    private static ClientWorld world;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            StatusProtocol.Frame frame;
            try { frame = StatusProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> lines.clear());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> lines.clear());
    }

    private static void accept(StatusProtocol.Frame frame) {
        sync();
        long now = Util.getMeasuringTimeMs();
        switch (frame) {
            case StatusProtocol.Clear c -> lines.remove(c.entityId());
            case StatusProtocol.Set s -> {
                if (s.entries().isEmpty()) { lines.remove(s.entityId()); return; }
                var old = lines.getOrDefault(s.entityId(), List.of());
                var list = new ArrayList<Line>(s.entries().size());
                for (var e : s.entries()) {
                    long end = e.infinite() ? Long.MAX_VALUE : now + e.ticksLeft() * 50L;
                    long start = e.infinite() ? now : end - Math.max(e.ticksLeft(), e.totalTicks()) * 50L;
                    // Keep the ring smooth when a periodic resend only confirms the same end.
                    for (Line o : old) if (o.status() == e.status() && Math.abs(o.end() - end) < 260 && o.infinite() == e.infinite()) { start = o.start(); end = o.end(); break; }
                    list.add(new Line(e.status(), start, end));
                }
                lines.put(s.entityId(), List.copyOf(list));
            }
        }
    }

    private static void sync() {
        var c = MinecraftClient.getInstance();
        if (c.world != world) { lines.clear(); world = c.world; }
    }

    /** Current icons of an entity, expired ones dropped. Never null. */
    public static List<Line> of(int entityId) {
        sync();
        var list = lines.get(entityId);
        if (list == null) return List.of();
        long now = Util.getMeasuringTimeMs();
        boolean expired = false;
        for (Line l : list) if (!l.infinite() && l.end() <= now) { expired = true; break; }
        if (!expired) return list;
        var keep = new ArrayList<Line>(list.size());
        for (Line l : list) if (l.infinite() || l.end() > now) keep.add(l);
        if (keep.isEmpty()) { lines.remove(entityId); return List.of(); }
        var copy = List.copyOf(keep);
        lines.put(entityId, copy);
        return copy;
    }

    static Set<Integer> entities() { sync(); return lines.keySet(); }

    /** Test preview (/차카hud 미리보기): fake icons on an entity for a few seconds. */
    static void preview(int entityId, List<Line> fake) { sync(); lines.put(entityId, List.copyOf(fake)); }
}
