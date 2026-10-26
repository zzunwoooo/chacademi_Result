package school.magiccodex.client;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;import net.minecraft.network.codec.PacketCodec;import net.minecraft.network.packet.CustomPayload;import net.minecraft.util.Identifier;
import school.magiccodex.protocol.EnhancementProtocol;import school.magiccodex.protocol.EnhancementProtocol.*;
final class EnhancementClient {
 private static Response pending;private static long closed;
 private interface Bytes{byte[] bytes();}
 record Query(byte[] bytes) implements CustomPayload,Bytes{static final Id<Query> ID=new Id<>(Identifier.of(EnhancementProtocol.REQUEST));public Id<Query> getId(){return ID;}}
 record Reply(byte[] bytes) implements CustomPayload,Bytes{static final Id<Reply> ID=new Id<>(Identifier.of(EnhancementProtocol.RESPONSE));public Id<Reply> getId(){return ID;}}
 private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<1||n>EnhancementProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] data=new byte[n];b.readBytes(data);return f.apply(data);}public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}};}
 static void send(long token,int action,int node){if(ClientPlayNetworking.canSend(Query.ID))ClientPlayNetworking.send(new Query(EnhancementProtocol.request(new Request(token,action,node))));}
 static void closed(long token){closed=token;send(token,EnhancementProtocol.CLOSE,0);}
 static void initialize(){
  PayloadTypeRegistry.playC2S().register(Query.ID,codec(Query::new));PayloadTypeRegistry.playS2C().register(Reply.ID,codec(Reply::new));
  ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,ctx)->{try{var r=EnhancementProtocol.response(p.bytes());ctx.client().execute(()->{if(r.token()==closed)return;if(ctx.client().currentScreen instanceof EnhancementScreen s)s.receive(r);else pending=r;});}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.DISCONNECT.register((h,c)->{pending=null;closed=0;});
  ClientTickEvents.END_CLIENT_TICK.register(c->{if(pending!=null&&c.player!=null&&c.world!=null&&c.getOverlay()==null){var r=pending;pending=null;MagicCodexClient.dismiss();c.setScreen(new EnhancementScreen(r));}});
 }
}
