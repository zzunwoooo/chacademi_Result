package school.magiccodex.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.TarotProtocol;

/**
 * 마법 힌트권 「운명의 타로」 (§41): 서버가 정한 카드(별·그림·힌트)를 받아 TarotScreen 을 연다. 서버로 답은 보내지 않는다.
 * 힌트는 창이 닫힐 때(확인·Esc·다른 창에 밀림·연결 끊김 전) 채팅에 한 줄 남긴다 — 고르기 전에 닫아도 잃지 않음.
 * 창을 못 열면(죽어 있음 등) 2초 기다린 뒤 채팅 한 줄만.
 */
public final class TarotClient {
    private TarotClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(TarotProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > TarotProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    /** 받았지만 아직 창이 안 열린 카드 (창이 열리면 창이 채팅을 맡음). */
    private static TarotProtocol.Card waiting;
    private static int waitingTicks;

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            TarotProtocol.Card card;
            try { card = TarotProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> {
                if (context.client().player == null) return;
                if (waiting != null) chat(waiting);   // 앞의 카드가 아직 안 열렸으면 그건 채팅으로
                waiting = card; waitingTicks = 0;
            });
        });
        // 다음 틱 끝에 연다 (DeferredScreens 와 같은 때). 살아 있지 않으면 최대 2초 기다린 뒤 채팅만.
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (waiting == null) return;
            if (c.player != null && c.world != null && c.player.isAlive()) {
                var card = waiting; waiting = null;
                MagicCodexClient.dismiss();
                c.setScreen(new TarotScreen(card));   // 이미 열린 타로 창이 있으면 그 창은 닫히며 자기 힌트를 채팅에 남김
            } else if (++waitingTicks > 40) { var card = waiting; waiting = null; chat(card); }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> waiting = null));
    }

    /** 채팅 한 줄 (서버의 예전 형식 그대로): [마법 힌트 ★★☆] 문장 */
    static void chat(TarotProtocol.Card card) {
        var client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;
        String stars = "★".repeat(card.stars()) + "☆".repeat(3 - card.stars());
        client.inGameHud.getChatHud().addMessage(Text.literal("[마법 힌트 " + stars + "] ").formatted(Formatting.YELLOW)
                .append(Text.literal(card.line()).formatted(Formatting.WHITE)));
    }
}
