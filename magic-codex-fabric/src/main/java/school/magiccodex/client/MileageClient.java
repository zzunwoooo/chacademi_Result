package school.magiccodex.client;

import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.MileageProtocol;
import school.magiccodex.protocol.MileageProtocol.*;

/** 마일리지 상점 networking (2026-10-24): the server opens the window, owns balances/products and decides every purchase. */
public final class MileageClient {
    private MileageClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(MileageProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, MileageProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(MileageProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, MileageProtocol.MAX_BYTES);
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
            try { frame = MileageProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (frame) {
            case State s -> {
                if (client.currentScreen instanceof MileageScreen screen && screen.admin() == s.admin()) screen.onState(s);
                else if (s.kind() == MileageProtocol.KIND_OPEN) { MagicCodexClient.dismiss(); var screen = new MileageScreen(s.admin()); client.setScreen(screen); screen.onState(s); }
            }
            case Result r -> { if (client.currentScreen instanceof MileageScreen screen) screen.onResult(r); else MileageScreen.lateResult(r); }
        }
    }

    static boolean send(MileageProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(MileageProtocol.request(r)));
        return true;
    }
    /** Top menu 「마일리지샵」. Without the server side, falls back to the command so the server can say what is wrong. */
    static void open() {
        var c = MinecraftClient.getInstance();
        if (send(MileageProtocol.Request.of(MileageProtocol.OPEN))) return;
        if (c.getNetworkHandler() != null) { c.setScreen(null); c.getNetworkHandler().sendChatCommand("마일리지상점"); }
    }
    static void openAdmin() { send(MileageProtocol.Request.of(MileageProtocol.ADMIN_OPEN)); }
    static void close() { send(MileageProtocol.Request.of(MileageProtocol.CLOSE)); }
    /** Returns the operation id (0 = not sent). */
    static long buy(String product, int quantity, long price) {
        long op = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return send(MileageProtocol.Request.buy(product, quantity, price, op)) ? op : 0;
    }
}
