package school.magiccodex.client;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.TravelProtocol;
import school.magiccodex.protocol.TravelProtocol.*;

/** 지도 이동 networking (2026-10-23): the server opens the window (/지도) and decides every move. */
public final class TravelClient {
    private TravelClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(TravelProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, TravelProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(TravelProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, TravelProtocol.MAX_BYTES);
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

    /** Portal marks of the world the player is in (server sends them; drawn by TravelWorldMarks). */
    static volatile Markers markers = new Markers("", "", List.of());

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            Frame frame;
            try { frame = TravelProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { markers = new Markers("", "", List.of()); TravelTerrain.key(""); });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> { markers = new Markers("", "", List.of()); TravelTerrain.key(""); });
        TravelWorldMarks.initialize();
        TravelTerrain.initialize();
        MapPingClient.initialize();   // 376 봉화 신호 party markers on this map
    }

    private static void accept(Frame frame) {
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (frame) {
            case State s -> {
                if (client.currentScreen instanceof TravelScreen screen) screen.onState(s);
                else if (s.kind() == TravelProtocol.KIND_OPEN) { MagicCodexClient.dismiss(); var screen = new TravelScreen(); client.setScreen(screen); screen.onState(s); }
            }
            case Result r -> { if (client.currentScreen instanceof TravelScreen screen) screen.onResult(r); }
            case Markers m -> { TravelWorldMarks.changed(markers, m); markers = m; TravelTerrain.key(m.cache()); }
        }
    }

    private static boolean send(TravelProtocol.Request r) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(TravelProtocol.request(r)));
        return true;
    }
    /** The map key (UI 단축키, default J): asks the server to open the window (same as /지도). */
    /**
     * Opens the window at once (it shows "불러오는 중…" for the moment the server needs) and asks the server for the
     * map; the server still decides — if it refuses, the window says so and closes (owner 2026-10-24: J 가 1초 늦게 열림).
     */
    static void open() {
        var c = MinecraftClient.getInstance();
        if (!send(TravelProtocol.Request.of(TravelProtocol.OPEN))) { c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("지도를 열 수 없어요 (서버에 연결되어 있지 않아요)")); return; }
        if (c.currentScreen instanceof TravelScreen) return;
        MagicCodexClient.dismiss();
        c.setScreen(new TravelScreen());
    }
    static void query() { send(TravelProtocol.Request.of(TravelProtocol.QUERY)); }
    static void close() { send(TravelProtocol.Request.of(TravelProtocol.CLOSE)); }
    /** Returns the operation id (0 = not sent). */
    static long travel(String pointId) {
        long op = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return send(TravelProtocol.Request.travel(pointId, op)) ? op : 0;
    }
}
