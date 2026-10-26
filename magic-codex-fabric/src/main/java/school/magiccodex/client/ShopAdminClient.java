package school.magiccodex.client;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;import net.fabricmc.fabric.api.client.networking.v1.*;import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.*;import net.minecraft.network.codec.PacketCodec;import net.minecraft.network.packet.CustomPayload;import net.minecraft.util.*;
import school.magiccodex.protocol.ShopAdminProtocol;import school.magiccodex.protocol.ShopAdminProtocol.*;
public final class ShopAdminClient {
 private static long sequence,waiting,session,deadline;private static ShopAdminScreen owner;
 private interface Bytes{byte[] bytes();}
 public record Query(byte[] bytes)implements CustomPayload,Bytes{public static final Id<Query>ID=new Id<>(Identifier.of(ShopAdminProtocol.REQUEST));public static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new);public Id<Query>getId(){return ID;}}
 public record Reply(byte[] bytes)implements CustomPayload,Bytes{public static final Id<Reply>ID=new Id<>(Identifier.of(ShopAdminProtocol.RESPONSE));public static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new);public Id<Reply>getId(){return ID;}}
 private static <T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T>make){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>ShopAdminProtocol.MAX_BYTES){b.skipBytes(n);return make.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return make.apply(bytes);}public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}};}
 public static void initialize(){PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
  ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(payload,context)->{try{Response r=ShopAdminProtocol.response(payload.bytes());context.client().execute(()->{if(r.sequence()==0){waiting=0;owner=null;MagicCodexClient.dismiss();var s=new ShopAdminScreen();context.client().setScreen(s);s.receive(r);return;}if(r.sequence()!=waiting||r.session()!=session)return;var s=owner;waiting=0;owner=null;if(s!=null&&context.client().currentScreen==s)s.receive(r);});}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());ClientTickEvents.END_CLIENT_TICK.register(c->{if(waiting!=0&&Util.getMeasuringTimeMs()>deadline){waiting=0;var s=owner;owner=null;if(s!=null)s.failed("응답이 늦습니다. 새로고침으로 저장 결과를 확인하세요. 자동으로 다시 저장하지 않습니다.");}});
 }
 private static void reset(){sequence=waiting=session=deadline=0;owner=null;}
 static boolean request(ShopAdminScreen s,Request r){if(waiting!=0||!ClientPlayNetworking.canSend(Query.ID))return false;long seq=++sequence;if(r.action()!=ShopAdminProtocol.CLOSE){waiting=seq;session=r.session();owner=s;deadline=Util.getMeasuringTimeMs()+15000;}
  ClientPlayNetworking.send(new Query(ShopAdminProtocol.encode(new Request(r.action(),seq,r.session(),r.shop(),r.revision(),r.product(),r.name(),r.buy(),r.sell(),r.search(),r.offset(),r.category(),r.sort(),r.slot(),r.slotItem()))));return true;
 }
 static void detach(ShopAdminScreen s){if(owner==s)owner=null;}
}
