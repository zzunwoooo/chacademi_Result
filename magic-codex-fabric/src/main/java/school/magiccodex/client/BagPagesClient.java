package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.BagProtocol;

/**
 * 아공간 배낭 pages (2026-10-24). The server owns the pages; this keeps its last state for the inventory screen.
 * Without the server channel (old bridge, other servers) the bag simply has one page.
 */
public final class BagPagesClient {
    private BagPagesClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(BagProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(BagProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new);
        public Id<Response> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> f) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > BagProtocol.MAX_BYTES) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return f.apply(a);
            }
            public void encode(RegistryByteBuf b, T value) { b.writeBytes(value.bytes()); }
        };
    }

    /** Last state from the server; null until the server spoke (one page only). */
    private static volatile BagProtocol.State state;
    /** Page the player asked for and has not been answered yet (0 = none). */
    private static int pending;
    private static long pendingSince;

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            BagProtocol.State s;
            try { s = BagProtocol.decodeState(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(s));
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> { reset(); hello(); });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
    }

    private static void reset() { state = null; pending = 0; }

    private static void accept(BagProtocol.State s) {
        state = s;
        pending = 0;   // server messages arrive as notification cards, not on the bag
    }

    private static boolean send(BagProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(BagProtocol.encode(r)));
        return true;
    }

    private static long lastHello;

    /** Asks for the current state (join). */
    static void hello() { lastHello = Util.getMeasuringTimeMs(); send(new BagProtocol.Request(BagProtocol.HELLO, 0)); }

    /**
     * Inventory opened (#101, 2026-10-26). The screen never waits for this: it draws from the last state at once and
     * follows whatever arrives. The server already pushes every change (ready / blocked / unlock / page switch / admin),
     * so a ready state is only re-checked every 30 s; no state or not ready yet asks right away. The server answers
     * from memory only (BagPagesBridge entries), never from the database.
     */
    static void helloIfNeeded() {
        var s = state;
        if (s != null && s.ready() && Util.getMeasuringTimeMs() - lastHello < 30_000) return;
        hello();
    }

    /** Pages the screen shows in its pager (1 when the server has no bag pages). */
    static int pages() { return state == null ? 1 : BagProtocol.PAGES; }
    static int unlocked() { var s = state; return s == null ? 1 : s.unlocked(); }
    static int active() { var s = state; return s == null ? 1 : s.active(); }
    static boolean ready() { var s = state; return s != null && s.ready(); }

    /** A switch is on its way (the pager waits; gives up after 3 s so a lost answer never locks the pager). */
    static boolean switching() {
        if (pending != 0 && Util.getMeasuringTimeMs() - pendingSince > 3000) pending = 0;
        return pending != 0;
    }

    /** Ask the server to bring page into the inventory. False when it cannot be asked now. */
    static boolean requestSwitch(int page) {
        if (state == null || !state.ready() || switching() || page < 1 || page > state.unlocked() || page == state.active()) return false;
        if (!send(new BagProtocol.Request(BagProtocol.SWITCH, page))) return false;
        pending = page;
        pendingSince = Util.getMeasuringTimeMs();
        return true;
    }
}
