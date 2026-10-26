package school.magiccodex.client;

import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.QuestProtocol;

public final class QuestClient {
    static QuestProtocol.Response data=new QuestProtocol.Response(false,0,0,"",List.of());
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes)implements CustomPayload,Bytes{static final Id<Query>ID=new Id<>(Identifier.of(QuestProtocol.REQUEST));static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new);public Id<Query>getId(){return ID;}}
    public record Reply(byte[] bytes)implements CustomPayload,Bytes{static final Id<Reply>ID=new Id<>(Identifier.of(QuestProtocol.RESPONSE));static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new);public Id<Reply>getId(){return ID;}}
    private static<T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T>f){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>QuestProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}public void encode(RegistryByteBuf b,T p){b.writeBytes(p.bytes());}};}
    public static void initialize(){PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=QuestProtocol.response(p.bytes());c.client().execute(()->{data=r;if(r.open()){MagicCodexClient.dismiss();c.client().setScreen(new QuestScreen());}if(c.client().currentScreen instanceof QuestScreen s)s.received(r.message());});}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
    }
    private static void reset(){data=new QuestProtocol.Response(false,0,0,"",List.of());}
    public static void open(){MagicCodexClient.dismiss();MinecraftClient.getInstance().setScreen(new QuestScreen());}
    static boolean send(int action,String id,int page){return send(action,id,page,0);}
    static boolean send(int action,String id,int page,int tab){if(MinecraftClient.getInstance().getNetworkHandler()==null||!ClientPlayNetworking.canSend(Query.ID))return false;ClientPlayNetworking.send(new Query(QuestProtocol.encode(new QuestProtocol.Request(action,page,id,tab))));return true;}
}
