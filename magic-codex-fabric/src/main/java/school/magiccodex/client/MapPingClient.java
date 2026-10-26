package school.magiccodex.client;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * Temporary party markers on the travel map (2026-10-25, 376 봉화 신호: 30 s the caster's position is shown to its party).
 * S2C chacademia:map_ping, UTF-8 {@code <uuid>;<name>;<world>;<x>;<z>;<ttl ticks>} (ttl 0 = remove). The server
 * (magic-mythic-paper) refreshes it every second while it lasts; a marker it stops refreshing disappears by itself.
 * {@link TravelScreen} draws the markers of the world the player stands in on the player's own tab.
 */
final class MapPingClient {
    static final String CHANNEL = "chacademia:map_ping";
    private MapPingClient() {}

    record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int size = b.readableBytes(); byte[] data = new byte[Math.min(size, 512)]; b.readBytes(data); b.skipBytes(b.readableBytes()); return new Payload(data); }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    record Ping(UUID id, String name, String world, double x, double z, long until) {}
    private static final Map<UUID, Ping> PINGS = new LinkedHashMap<>();

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> context.client().execute(() -> accept(payload.bytes())));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(PINGS::clear));
    }

    private static void accept(byte[] bytes) {
        String[] f = new String(bytes, StandardCharsets.UTF_8).split(";", -1);
        if (f.length != 6 || f[1].length() > 32 || !school.magiccodex.protocol.TravelProtocol.validWorld(f[2])) return;
        try {
            UUID id = UUID.fromString(f[0]);
            double x = Double.parseDouble(f[3]), z = Double.parseDouble(f[4]);
            int ttl = Integer.parseInt(f[5]);
            if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7) return;
            if (ttl <= 0) { PINGS.remove(id); return; }
            if (PINGS.size() >= 32 && !PINGS.containsKey(id)) return;
            PINGS.put(id, new Ping(id, f[1], f[2], x, z, Util.getMeasuringTimeMs() + Math.min(ttl, 20 * 60) * 50L));
        } catch (IllegalArgumentException ignored) {}
    }

    /** Live markers in this world (expired ones are dropped). */
    static List<Ping> in(String world) {
        if (PINGS.isEmpty()) return List.of();
        long now = Util.getMeasuringTimeMs();
        PINGS.values().removeIf(p -> p.until() <= now);
        var out = new ArrayList<Ping>();
        for (Ping p : PINGS.values()) if (p.world().equals(world)) out.add(p);
        return out;
    }
}
