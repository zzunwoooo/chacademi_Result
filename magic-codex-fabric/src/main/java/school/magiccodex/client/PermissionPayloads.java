package school.magiccodex.client;

import java.util.function.Function;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.PermissionProtocol;

/** Raw bytes match Bukkit plugin messaging; no extra VarInt array length is added. */
public final class PermissionPayloads {
    private PermissionPayloads() {}
    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<Request> ID = new Id<>(Identifier.of(PermissionProtocol.REQUEST));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new);
        @Override public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<Response> ID = new Id<>(Identifier.of(PermissionProtocol.RESPONSE));
        public static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new);
        @Override public Id<Response> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(Function<byte[], T> factory) {
        return new PacketCodec<>() {
            @Override public T decode(RegistryByteBuf buf) {
                int size = buf.readableBytes();
                if (size > PermissionProtocol.MAX_BYTES) {
                    buf.skipBytes(size);
                    return factory.apply(new byte[0]);
                }
                byte[] bytes = new byte[size]; buf.readBytes(bytes); return factory.apply(bytes);
            }
            @Override public void encode(RegistryByteBuf buf, T payload) {
                if (payload.bytes().length > PermissionProtocol.MAX_BYTES) throw new IllegalArgumentException("Payload too large");
                buf.writeBytes(payload.bytes());
            }
        };
    }
}
