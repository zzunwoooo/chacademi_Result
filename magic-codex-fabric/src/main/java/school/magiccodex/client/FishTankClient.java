package school.magiccodex.client;

import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.FishTankProtocol;
import school.magiccodex.protocol.FishTankProtocol.*;

/** 아공간 수조 networking (2026-10-20): the server opens the window (아공간 수조 spell) and decides every action. */
public final class FishTankClient {
    private FishTankClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(FishTankProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, FishTankProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(FishTankProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, FishTankProtocol.MAX_BYTES);
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
            try { frame = FishTankProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (frame) {
            case State s -> {
                if (client.currentScreen instanceof FishTankScreen screen) screen.onState(s);
                else if (s.kind() == FishTankProtocol.KIND_OPEN) { MagicCodexClient.dismiss(); var screen = new FishTankScreen(); client.setScreen(screen); screen.onState(s); }
            }
            case Result r -> { if (client.currentScreen instanceof FishTankScreen screen) screen.onResult(r); }
        }
    }

    private static boolean send(FishTankProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(FishTankProtocol.request(r)));
        return true;
    }
    static void query() { send(FishTankProtocol.Request.of(FishTankProtocol.QUERY)); }
    static void close() { send(FishTankProtocol.Request.of(FishTankProtocol.CLOSE)); }
    private static long op() { return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE); }
    /** Returns the operation id (0 = not sent). */
    static long fish(int action, String tankId) { long op = op(); return send(FishTankProtocol.Request.fish(action, tankId, op)) ? op : 0; }
    static long sell(String tankId, long expected) { long op = op(); return send(FishTankProtocol.Request.sell(tankId, expected, op)) ? op : 0; }
    static long insert(int slot, String tankId) { long op = op(); return send(FishTankProtocol.Request.insert(slot, tankId, op)) ? op : 0; }
}
