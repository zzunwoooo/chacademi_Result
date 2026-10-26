package school.magiccodex.client;

import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.TeleportPickProtocol;

/**
 * 텔포권 (오너 2026-10-26): 서버가 보낸 접속자 목록으로 고르기 창(TeleportPickScreen, 친구 목록 창 모양)을 열고,
 * 고른 사람(PICK) 또는 닫기(CANCEL)를 답한다. 서버가 이동을 시작하면 CLOSE 로 창을 닫는다.
 */
public final class TeleportPickClient {
    private TeleportPickClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(TeleportPickProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, TeleportPickProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(TeleportPickProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, TeleportPickProtocol.MAX_BYTES);
        public Id<Response> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> f, int max) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > max) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return f.apply(a);
            }
            public void encode(RegistryByteBuf b, T value) { b.writeBytes(value.bytes()); }
        };
    }

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            TeleportPickProtocol.Frame frame;
            try { frame = TeleportPickProtocol.frame(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            var connection = context.client().getNetworkHandler();
            context.client().execute(() -> {
                if (connection != context.client().getNetworkHandler() || context.client().player == null) return;
                if (frame.kind() == TeleportPickProtocol.OPEN) { var o = frame.open(); DeferredScreens.open(() -> new TeleportPickScreen(o.token(), o.entries())); }
                else if (frame.kind() == TeleportPickProtocol.CLOSE && context.client().currentScreen instanceof TeleportPickScreen s && s.token() == frame.closeToken()) s.serverClosed();
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> { if (c.currentScreen instanceof TeleportPickScreen s) s.serverClosed(); }));
    }

    /** 고르기(target != null) 또는 닫기. 연결이 없으면 false. */
    static boolean answer(long token, UUID target) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        try {
            ClientPlayNetworking.send(new Request(TeleportPickProtocol.request(new TeleportPickProtocol.Request(
                    target == null ? TeleportPickProtocol.CANCEL : TeleportPickProtocol.PICK, token, target))));
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }
}
