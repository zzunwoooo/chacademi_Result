package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.*;
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
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.AlchemyAdminProtocol;
import school.magiccodex.protocol.AlchemyAdminProtocol.Frame;
import school.magiccodex.protocol.AlchemyAdminProtocol.Held;
import school.magiccodex.protocol.AlchemyAdminProtocol.Result;
import school.magiccodex.protocol.AlchemyAdminProtocol.Row;
import school.magiccodex.protocol.AlchemyAdminProtocol.State;
import school.magiccodex.protocol.AlchemyProtocol.Item;

/** Operator recipe editor data (MagicCodexBridge AlchemyEditor). The server opens the screen with /연금술관리. */
public final class AlchemyAdminClient {
    private AlchemyAdminClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(AlchemyAdminProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, AlchemyAdminProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(AlchemyAdminProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, AlchemyAdminProtocol.MAX_BYTES);
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

    private static State state;
    private static final Map<String, ItemStack> icons = new HashMap<>();
    private static long sequence, version;

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            Frame frame;
            try { frame = AlchemyAdminProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { state = null; icons.clear(); version++; });
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        switch (frame) {
            case State s -> {
                state = s; version++;
                icons.clear();
                for (Item i : s.items()) icons.put(i.key(), decode(i.preview()));
                if (s.kind() == AlchemyAdminProtocol.KIND_OPEN && client.player != null && !(client.currentScreen instanceof AlchemyAdminScreen)) {
                    MagicCodexClient.dismiss();
                    client.setScreen(new AlchemyAdminScreen());
                }
            }
            case Result r -> { if (client.currentScreen instanceof AlchemyAdminScreen screen) screen.onResult(r); }
        }
    }

    private static ItemStack decode(byte[] b) {
        var c = MinecraftClient.getInstance();
        if (b.length == 0 || c.world == null) return ItemStack.EMPTY;
        try { return ItemStack.fromNbt(c.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(262144))).orElse(ItemStack.EMPTY); }
        catch (Exception e) { return ItemStack.EMPTY; }
    }

    static State state() { return state; }
    static long version() { return version; }
    static int slots() { return state == null ? 6 : state.slots(); }
    static List<Row> rows() { return state == null ? List.of() : state.rows(); }
    static List<Held> inventory() { return state == null ? List.of() : state.inventory(); }
    static ItemStack icon(String key) { return icons.getOrDefault(key, ItemStack.EMPTY); }
    static String name(String key) { ItemStack s = icon(key); return s.isEmpty() ? key : s.getName().getString(); }
    static Held held(String key) { for (Held h : inventory()) if (h.key().equals(key)) return h; return null; }

    private static long send(int action, Row row, String id) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return 0;
        long seq = ++sequence;
        ClientPlayNetworking.send(new Request(AlchemyAdminProtocol.request(new AlchemyAdminProtocol.Request(action, seq, row, id))));
        return seq;
    }
    static void query() { send(AlchemyAdminProtocol.QUERY, null, ""); }
    static long save(Row row) { return send(AlchemyAdminProtocol.SAVE, row, ""); }
    static long delete(String id) { return send(AlchemyAdminProtocol.DELETE, null, id); }
    static void closed() { send(AlchemyAdminProtocol.CLOSE, null, ""); }
}
