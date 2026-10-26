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
import school.magiccodex.protocol.AlchemyProtocol;
import school.magiccodex.protocol.AlchemyProtocol.Frame;
import school.magiccodex.protocol.AlchemyProtocol.Item;
import school.magiccodex.protocol.AlchemyProtocol.Material;
import school.magiccodex.protocol.AlchemyProtocol.Result;
import school.magiccodex.protocol.AlchemyProtocol.Stack;
import school.magiccodex.protocol.AlchemyProtocol.State;

/**
 * Alchemy (연금술) data from the server (MagicCodexBridge AlchemyBridge). The server opens the window (/연금술), owns
 * the recipes and decides every craft; this class keeps the last state, decodes item icons and forwards results.
 */
public final class AlchemyClient {
    private AlchemyClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(AlchemyProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, AlchemyProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(AlchemyProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, AlchemyProtocol.MAX_BYTES);
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
    private static final Map<String, Material> materials = new HashMap<>();
    private static long sequence, version;

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            Frame frame;
            try { frame = AlchemyProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        switch (frame) {
            case State s -> {
                state = s; version++;
                icons.clear();
                for (Item i : s.items()) icons.put(i.key(), decode(i.preview()));
                materials.clear();
                for (Material m : s.materials()) materials.put(m.key(), m);
                if (s.kind() == AlchemyProtocol.KIND_OPEN && !(client.currentScreen instanceof AlchemyScreen) && client.player != null) {
                    MagicCodexClient.dismiss();
                    client.setScreen(new AlchemyScreen());
                }
            }
            case Result r -> { if (client.currentScreen instanceof AlchemyScreen screen) screen.onResult(r); }
        }
    }

    private static void reset() { state = null; icons.clear(); materials.clear(); sequence = 0; version++; }

    private static ItemStack decode(byte[] b) {
        var c = MinecraftClient.getInstance();
        if (b.length == 0 || c.world == null) return ItemStack.EMPTY;
        try {
            return ItemStack.fromNbt(c.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(262144))).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }

    // ------------------------------------------------------------------ view

    static State state() { return state; }
    static long version() { return version; }
    static int slotCount() { return state == null ? 6 : state.slots(); }
    static List<AlchemyProtocol.Recipe> recipes() { return state == null ? List.of() : state.recipes(); }
    static int locked() { return state == null ? 0 : state.locked(); }
    static ItemStack icon(String key) { return icons.getOrDefault(key, ItemStack.EMPTY); }
    static int owned(String key) { var m = materials.get(key); return m == null ? 0 : m.owned(); }
    /** Category 재료 items the player carries, in inventory order (the slot picker). */
    static List<Material> selectable() {
        if (state == null) return List.of();
        var list = new ArrayList<Material>();
        for (Material m : state.materials()) if (m.selectable() && m.owned() > 0) list.add(m);
        return list;
    }
    static String name(String key) {
        ItemStack s = icon(key);
        return s.isEmpty() ? key : s.getName().getString();
    }
    static AlchemyProtocol.Recipe recipe(String id) {
        for (var r : recipes()) if (r.id().equals(id)) return r;
        return null;
    }

    // ------------------------------------------------------------------ requests

    private static boolean send(int action, long seq, List<Stack> slots) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(AlchemyProtocol.request(new AlchemyProtocol.Request(action, seq, slots))));
        return true;
    }
    static boolean query() { return send(AlchemyProtocol.QUERY, 0, List.of()); }
    static void closed() { send(AlchemyProtocol.CLOSE, 0, List.of()); }
    /** Returns the request's sequence (0 when it could not be sent). */
    static long craft(List<Stack> slots) { long seq = ++sequence; return send(AlchemyProtocol.CRAFT, seq, slots) ? seq : 0; }
}
