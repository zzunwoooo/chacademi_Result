package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import school.magiccodex.protocol.CastAimProtocol;

/**
 * C04 aim (2026-10-21): right before an aimed cast the client tells the server which point/target the preview chose.
 * The server checks range, sight and direction against its own eyes and falls back to its ray when the check fails.
 * Servers without the channel simply never receive it (old behaviour: look packet only).
 */
final class CastAimClient {
    private CastAimClient() {}
    record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(CastAimProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int size = b.readableBytes(); byte[] data = new byte[Math.min(size, CastAimProtocol.MAX_BYTES)]; b.readBytes(data); b.skipBytes(b.readableBytes()); return new Payload(data); }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static void initialize() { PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC); }

    /** Sends the chosen point (and the target entity, when the point is on one). Silently skipped on old servers. */
    static void send(String spell, Vec3d point, Entity target, boolean air) {
        if (point == null || !ClientPlayNetworking.canSend(Payload.ID)) return;
        try {
            ClientPlayNetworking.send(new Payload(CastAimProtocol.encode(new CastAimProtocol.Aim(spell, point.x, point.y, point.z,
                target == null ? null : target.getUuid(), air))));
        } catch (IllegalArgumentException ignored) { /* invalid id/point: the server uses its own ray */ }
    }
}
