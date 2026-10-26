package school.magiccodex.client;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.AppraisalProtocol;
import school.magiccodex.protocol.AppraisalProtocol.*;
final class AppraisalClient {
 private static Response pending;
 private interface Bytes{byte[] bytes();}
 record Query(byte[] bytes) implements CustomPayload,Bytes{static final Id<Query> ID=new Id<>(Identifier.of(AppraisalProtocol.REQUEST));public Id<Query> getId(){return ID;}}
 record Reply(byte[] bytes) implements CustomPayload,Bytes{static final Id<Reply> ID=new Id<>(Identifier.of(AppraisalProtocol.RESPONSE));public Id<Reply> getId(){return ID;}}
 private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<1||n>AppraisalProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] data=new byte[n];b.readBytes(data);return f.apply(data);}public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}};}
 static void claim(long token){if(ClientPlayNetworking.canSend(Query.ID))ClientPlayNetworking.send(new Query(AppraisalProtocol.encodeRequest(new Request(token))));}
 static void initialize(){
  PayloadTypeRegistry.playC2S().register(Query.ID,codec(Query::new));PayloadTypeRegistry.playS2C().register(Reply.ID,codec(Reply::new));
  ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,ctx)->{try{var r=AppraisalProtocol.decodeResponse(p.bytes());ctx.client().execute(()->{if(ctx.client().currentScreen instanceof AppraisalScreen s&&s.token()==r.token())s.receive(r);else pending=r;});}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.DISCONNECT.register((h,c)->pending=null);
  ClientTickEvents.END_CLIENT_TICK.register(c->{if(pending!=null&&c.player!=null&&c.world!=null&&c.getOverlay()==null){var r=pending;pending=null;MagicCodexClient.dismiss();c.setScreen(new AppraisalScreen(r));}});
 }
}
