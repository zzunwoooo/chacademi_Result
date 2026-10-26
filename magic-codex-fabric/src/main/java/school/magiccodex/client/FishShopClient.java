package school.magiccodex.client;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.FishShopProtocol;
import school.magiccodex.protocol.FishShopProtocol.*;

/** 물고기 판매소 networking (2026-10-20): the server opens the window and decides every sale. */
public final class FishShopClient {
    private FishShopClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(FishShopProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, FishShopProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(FishShopProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, FishShopProtocol.MAX_BYTES);
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
            Frame frame;
            try { frame = FishShopProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (frame) {
            case State s -> {
                if (client.currentScreen instanceof FishShopScreen screen) screen.onState(s);
                else if (s.kind() == FishShopProtocol.KIND_OPEN) { MagicCodexClient.dismiss(); var screen = new FishShopScreen(); client.setScreen(screen); screen.onState(s); }
            }
            case Result r -> { if (client.currentScreen instanceof FishShopScreen screen) screen.onResult(r); }
        }
    }

    private static boolean send(FishShopProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(FishShopProtocol.request(r)));
        return true;
    }
    static void query() { send(FishShopProtocol.Request.of(FishShopProtocol.QUERY)); }
    static void close() { send(FishShopProtocol.Request.of(FishShopProtocol.CLOSE)); }
    static boolean locks(Collection<String> ids) { return send(FishShopProtocol.Request.locks(ids)); }
    /** Returns the operation id (0 = not sent). */
    static long sell(int mask, long expected) {
        long op = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return send(FishShopProtocol.Request.sell(mask, expected, op)) ? op : 0;
    }
}
