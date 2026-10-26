package school.magiccodex.client;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import school.magiccodex.npctalk.NpcTalkProtocol;

/**
 * AI NPC 대화 채널 (ChacaNPC 서버 플러그인). 서버 채널이 열리면 HELLO(버전)를 보내고, HELLO_ACK를 받은 뒤에만
 * NpcTalkScreen을 연다. 서버는 handshake가 끝난 클라이언트에게만 HUD 대화를 보낸다.
 */
public final class NpcTalkClient {
    private interface Bytes { byte[] bytes(); }

    public record Query(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Query> ID = new Id<>(Identifier.of(NpcTalkProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Query> CODEC = codec(Query::new);
        public Id<Query> getId() { return ID; }
    }

    public record Reply(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Reply> ID = new Id<>(Identifier.of(NpcTalkProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Reply> CODEC = codec(Reply::new);
        public Id<Reply> getId() { return ID; }
    }

    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(Function<byte[], T> f) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n < 4 || n > NpcTalkProtocol.MAX_BYTES) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] bytes = new byte[n];
                b.readBytes(bytes);
                return f.apply(bytes);
            }
            public void encode(RegistryByteBuf b, T p) { b.writeBytes(p.bytes()); }
        };
    }

    private static boolean helloSent;
    private static boolean ready;

    static boolean ready() { return ready; }

    private static void reset() { helloSent = false; ready = false; }

    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(Query.ID, Query.CODEC);
        PayloadTypeRegistry.playS2C().register(Reply.ID, Reply.CODEC);
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            reset();
            c.execute(() -> { if (c.currentScreen instanceof NpcTalkScreen screen) screen.serverClose(""); });
        });
        // 서버가 채널을 등록하면(canSend) 한 번 버전 handshake
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (!helloSent && c.getNetworkHandler() != null && ClientPlayNetworking.canSend(Query.ID)) {
                helloSent = true;
                ClientPlayNetworking.send(new Query(NpcTalkProtocol.encode(
                        new NpcTalkProtocol.Request(NpcTalkProtocol.C_HELLO, "", NpcTalkProtocol.VERSION, "magic-codex-ui", false))));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID, (payload, context) -> {
            NpcTalkProtocol.Response r;
            try { r = NpcTalkProtocol.response(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> receive(context.client(), r));
        });
    }

    private static void receive(MinecraftClient client, NpcTalkProtocol.Response r) {
        NpcUiTrace.event("reply-op-"+r.op()+"-ready-"+ready+"-session-match-"+(client.currentScreen instanceof NpcTalkScreen active && active.session().equals(r.session())),r.seq(),false,0,false);
        if (r.op() == NpcTalkProtocol.S_HELLO_ACK) {
            ready = r.seq() == NpcTalkProtocol.VERSION;
            return;
        }
        if (!ready) return;
        if (r.op() == NpcTalkProtocol.S_OPEN) {
            if (client.currentScreen instanceof NpcTalkScreen screen && screen.session().equals(r.session())) {
                screen.reopen(r);
                return;
            }
            MagicCodexClient.dismiss();
            client.setScreen(new NpcTalkScreen(r, client.currentScreen instanceof NpcTalkScreen ? null : client.currentScreen));
            return;
        }
        if (client.currentScreen instanceof NpcTalkScreen screen && screen.session().equals(r.session())) {
            screen.receive(r);
        } else if (r.op() == NpcTalkProtocol.S_CLOSE && !r.text().isEmpty() && client.player != null) {
            // 창이 이미 닫혔으면 작별 인사만 채팅으로
            client.player.sendMessage(Text.literal(r.text()), false);
        }
        // 다른 세션(창 닫기·NPC 전환 이후)에 대한 응답은 버린다
    }

    static boolean send(String session, int op, int seq, String text, boolean accept) {
        if (!ready || MinecraftClient.getInstance().getNetworkHandler() == null || !ClientPlayNetworking.canSend(Query.ID)) { NpcUiTrace.event("send-blocked-ready-"+ready+"-connected-"+(MinecraftClient.getInstance().getNetworkHandler()!=null)+"-channel-"+ClientPlayNetworking.canSend(Query.ID),seq,false,0,false);return false; }
        try {
            ClientPlayNetworking.send(new Query(NpcTalkProtocol.encode(new NpcTalkProtocol.Request(op, session, seq, text, accept))));
            return true;
        } catch (RuntimeException e) { NpcUiTrace.event("encode-or-send-failed",seq,false,0,false);
            return false;
        }
    }
}
