package kr.chacademi.npcmodel;

import kr.chacademi.npcmodel.client.Bindings;
import kr.chacademi.npcmodel.client.ModelManager;
import kr.chacademi.npcmodel.client.NpcModelRenderer;
import kr.chacademi.npcmodel.protocol.NpcModelProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 차카데미 NPC 3D 외형 클라이언트 모드.
 *
 * <p>서버(ChacaNPC)가 "이 엔티티는 모델 X"만 알려 주고, 모델 파일은 런처가 미리 깔아 둔
 * {@code <게임폴더>/chacademi/npc-models/} 에서만 읽는다. 준비가 안 됐거나 실패하면 그 NPC는 원래 플레이어 외형 그대로.</p>
 */
public final class NpcModelClient implements ClientModInitializer {
    public static final String MOD_ID = "chacademi_npc_model";
    public static final Logger LOG = LoggerFactory.getLogger("ChacademiNpcModel");

    private static ModelManager models;
    private static Bindings bindings;

    @Override
    public void onInitializeClient() {
        models = new ModelManager(FabricLoader.getInstance().getGameDir().resolve("chacademi").resolve("npc-models"));
        bindings = new Bindings(models);

        PayloadTypeRegistry.playS2C().register(S2C.TYPE, S2C.CODEC);
        PayloadTypeRegistry.playC2S().register(C2S.TYPE, C2S.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(S2C.TYPE, (payload, context) -> {
            NpcModelProtocol.Message m = NpcModelProtocol.decodeS2C(payload.data());
            if (m == null) {
                bindings.noteRejectedPacket();
                return; // 버전 불일치·깨진 패킷: 아무것도 바꾸지 않음 (원래 외형 유지)
            }
            context.client().execute(() -> {
                if (m instanceof NpcModelProtocol.HelloAck ack) {
                    if (bindings.onSession(ack.session())) {
                        sendHello();
                    }
                } else {
                    bindings.onMessage(m);
                }
            });
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            bindings.resetSession();
            models.reloadRegistry();
            sendHello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            bindings.resetSession();
            models.scheduleIdleUnload();
        }));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> bindings.onEntityUnload(entity));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            models.detectGpu();
            bindings.tick(client);
            models.tick();
        });
        WorldRenderEvents.START.register(ctx -> NpcModelRenderer.beginFrame());
        kr.chacademi.npcmodel.client.TuneCommands.register();
    }

    private static void sendHello() {
        if (ClientPlayNetworking.canSend(C2S.TYPE)) {
            ClientPlayNetworking.send(new C2S(NpcModelProtocol.encode(new NpcModelProtocol.Hello(NpcModelProtocol.VERSION))));
        }
    }

    public static Bindings bindings() {
        return bindings;
    }

    public static ModelManager models() {
        return models;
    }

    /**
     * 다른 모드(스토리 머리 위 징표 등)용: 이 엔티티가 3D 모델로 그려지고 있으면 발바닥 기준 모델 꼭대기 높이(블록), 아니면 -1.
     * 리플렉션으로 불러도 되게 정적·원시 타입만 쓴다.
     */
    public static double modelTopHeight(Entity entity) {
        return bindings == null ? -1 : bindings.topHeight(entity);
    }

    // ---------------------------------------------------------------- 패킷 (본문은 NpcModelProtocol 바이트 그대로)

    public record S2C(byte[] data) implements CustomPacketPayload {
        public static final Type<S2C> TYPE = new Type<>(ResourceLocation.parse(NpcModelProtocol.CHANNEL_S2C));
        public static final StreamCodec<FriendlyByteBuf, S2C> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()), buf -> {
            int n = buf.readableBytes();
            byte[] b = new byte[Math.min(n, NpcModelProtocol.MAX_MESSAGE + 1)];
            buf.readBytes(b);
            buf.skipBytes(buf.readableBytes());
            return new S2C(b);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final Type<C2S> TYPE = new Type<>(ResourceLocation.parse(NpcModelProtocol.CHANNEL_C2S));
        public static final StreamCodec<FriendlyByteBuf, C2S> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()), buf -> {
            byte[] b = new byte[Math.min(buf.readableBytes(), NpcModelProtocol.MAX_MESSAGE)];
            buf.readBytes(b);
            buf.skipBytes(buf.readableBytes());
            return new C2S(b);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
