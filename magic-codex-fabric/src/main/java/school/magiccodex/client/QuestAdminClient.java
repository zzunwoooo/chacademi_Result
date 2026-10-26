package school.magiccodex.client;
import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.QuestAdminProtocol;
import school.magiccodex.protocol.QuestProtocol;
public final class QuestAdminClient {
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes) implements CustomPayload,Bytes{static final Id<Query>ID=new Id<>(Identifier.of(QuestAdminProtocol.REQUEST));static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new,QuestProtocol.MAX_BYTES);public Id<Query>getId(){return ID;}}
    public record Reply(byte[] bytes) implements CustomPayload,Bytes{static final Id<Reply>ID=new Id<>(Identifier.of(QuestAdminProtocol.RESPONSE));static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new,QuestAdminProtocol.MAX_RESPONSE_BYTES);public Id<Reply>getId(){return ID;}}
    private static<T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T> f,int max){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>max){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}public void encode(RegistryByteBuf b,T p){b.writeBytes(p.bytes());}};}
    public static void initialize(){PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=QuestAdminProtocol.response(p.bytes());c.client().execute(()->{if(c.client().currentScreen instanceof QuestAdminScreen s)s.receive(r);else{MagicCodexClient.dismiss();c.client().setScreen(new QuestAdminScreen(r));}});}catch(IllegalArgumentException ignored){}});}
    static boolean send(int action,String id,String revision,Map<String,String> fields){if(!ClientPlayNetworking.canSend(Query.ID))return false;ClientPlayNetworking.send(new Query(QuestAdminProtocol.encode(new QuestAdminProtocol.Request(action,id,revision,fields))));return true;}
}
