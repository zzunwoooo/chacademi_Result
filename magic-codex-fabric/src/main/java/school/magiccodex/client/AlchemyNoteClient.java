package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.AlchemyNoteProtocol;

// [기능:alchemy-overhaul 시작] 연금 수첩 통신 (2026-10-31, 새 파일)
/**
 * 연금 수첩 (MagicCodexBridge AlchemyPlus). 수첩 버튼(연금술 창) → 서버에 요청 → 받은 내용으로 수첩 창을 연다.
 * 연금 쪽지를 우클릭하면 서버가 먼저 보내고(focus = 방금 읽은 조합식) 수첩 창이 그 쪽지를 펼친 채로 열린다.
 */
public final class AlchemyNoteClient {
    private AlchemyNoteClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(AlchemyNoteProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, 64);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(AlchemyNoteProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, AlchemyNoteProtocol.MAX_BYTES);
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

    private static AlchemyNoteProtocol.State state;
    private static Screen waitingParent;
    private static boolean waiting;

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            AlchemyNoteProtocol.State s;
            try { s = AlchemyNoteProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(s));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { state = null; waiting = false; waitingParent = null; });
    }

    private static void accept(AlchemyNoteProtocol.State s) {
        state = s;
        var client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.currentScreen instanceof AlchemyNoteScreen screen) { screen.update(s); waiting = false; return; }
        if (waiting || !s.focus().isEmpty()) {
            Screen parent = waiting ? waitingParent : client.currentScreen;
            waiting = false; waitingParent = null;
            client.setScreen(new AlchemyNoteScreen(parent, s));
        }
    }

    /** 수첩 버튼: 서버에 보내 달라고 하고, 오면 연다. */
    static boolean open(Screen parent) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        waiting = true; waitingParent = parent;
        ClientPlayNetworking.send(new Request(AlchemyNoteProtocol.request(AlchemyNoteProtocol.OPEN)));
        return true;
    }

    static AlchemyNoteProtocol.State state() { return state; }
}
// [기능:alchemy-overhaul 끝]
