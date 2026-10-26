package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.*;

/**
 * 낚시 networking (2026-10-20). The server decides everything; this class forwards its frames to the panel
 * (FishingScreen) and the codex (FishingCodexScreen) and sends the player's few inputs back.
 */
public final class FishingClient {
    private FishingClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(FishingProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, FishingProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(FishingProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, FishingProtocol.MAX_BYTES);
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
            try { frame = FishingProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {});
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (frame) {
            case Session s -> panel(client).onSession(s);
            case Bite b -> { if (client.currentScreen instanceof FishingScreen screen) screen.onBite(b); }
            case Result r -> { if (client.currentScreen instanceof FishingScreen screen) screen.onResult(r); }
            case Close c -> {
                if (client.currentScreen instanceof FishingScreen screen && screen.session() == c.session()) screen.closeFromServer();
                if (!c.notice().isBlank()) SharedNotifications.offer("fishing:close", "warning", "낚시", c.notice());
            }
            case GearList g -> {
                if (client.currentScreen instanceof FishingScreen screen) screen.onGear(g);
                else { var screen = new FishingScreen(); MagicCodexClient.dismiss(); client.setScreen(screen); screen.onGear(g); }
            }
            case Codex c -> {
                if (client.currentScreen instanceof FishingCodexScreen screen) screen.onCodex(c);
                else { MagicCodexClient.dismiss(); client.setScreen(new FishingCodexScreen(c)); }
            }
        }
    }

    private static FishingScreen panel(MinecraftClient client) {
        if (client.currentScreen instanceof FishingScreen screen) return screen;
        MagicCodexClient.dismiss();
        var screen = new FishingScreen();
        client.setScreen(screen);
        return screen;
    }

    static ItemStack decode(byte[] b) {
        var c = MinecraftClient.getInstance();
        if (b == null || b.length == 0 || c.world == null) return ItemStack.EMPTY;
        try {
            return ItemStack.fromNbt(c.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(262144))).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }

    // ------------------------------------------------------------------ requests

    static boolean send(FishingProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(FishingProtocol.request(r)));
        return true;
    }
    static void stop(long session, float depth) { send(FishingProtocol.Request.stop(session, Math.clamp(depth, 0, FishingProtocol.MAX_DEPTH))); }
    /** heldMask: bit i = the player was winding when bite attack i happened (that attack wears double). */
    static void result(long session, int token, int outcome, int elapsed, int heldMask) {
        send(FishingProtocol.Request.result(session, token, outcome, Math.max(0, elapsed), heldMask & 0xFF));
    }
    static void recast(long session) { send(FishingProtocol.Request.of(FishingProtocol.RECAST, session)); }
    static void cancel(long session) { send(FishingProtocol.Request.of(FishingProtocol.CANCEL, session)); }
    static void openGear() { send(FishingProtocol.Request.of(FishingProtocol.GEAR_OPEN, 0)); }
    static void equip(int kind, int slot) { send(FishingProtocol.Request.equip(kind, slot)); }
    static void openCodex() { send(FishingProtocol.Request.of(FishingProtocol.CODEX_OPEN, 0)); }
    static void claim(int tier) { send(FishingProtocol.Request.claim(tier)); }
}
