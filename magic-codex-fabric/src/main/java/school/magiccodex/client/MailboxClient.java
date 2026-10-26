package school.magiccodex.client;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.MailboxProtocol;
import school.magiccodex.protocol.MailboxProtocol.*;
public final class MailboxClient {
 private static long sequence,waiting,deadline;private static MailboxScreen owner;
 private interface Bytes{byte[] bytes();}
 public record Query(byte[] bytes)implements CustomPayload,Bytes{public static final Id<Query>ID=new Id<>(Identifier.of(MailboxProtocol.REQUEST));public static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new);public Id<Query>getId(){return ID;}}
 public record Reply(byte[] bytes)implements CustomPayload,Bytes{public static final Id<Reply>ID=new Id<>(Identifier.of(MailboxProtocol.RESPONSE));public static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new);public Id<Reply>getId(){return ID;}}
 private static <T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T>make){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>MailboxProtocol.MAX_BYTES){b.skipBytes(n);return make.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return make.apply(bytes);}public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}};}
 public static void initialize(){DeferredScreens.initialize();PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=MailboxProtocol.response(p.bytes());c.client().execute(()->{if(r.sequence()!=waiting)return;var s=owner;waiting=0;owner=null;if(s!=null&&c.client().currentScreen==s)s.receive(r);});}catch(IllegalArgumentException ignored){}});ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientCommandRegistrationCallback.EVENT.register((d,a)->d.register(ClientCommandManager.literal("우편함").executes(c->{open(null);return 1;})));ClientTickEvents.END_CLIENT_TICK.register(c->{if(waiting!=0&&Util.getMeasuringTimeMs()>deadline){var s=owner;waiting=0;owner=null;if(s!=null)s.failed("응답이 늦습니다. 새로고침으로 수령 상태를 확인해 주세요.");}});}
 private static void reset(){sequence=waiting=deadline=0;owner=null;}
 public static void open(Screen parent){DeferredScreens.open(()->new MailboxScreen(parent));}
 static boolean request(MailboxScreen s,int action,long session,String mail,int page){if(MinecraftClient.getInstance().getNetworkHandler()==null||!ClientPlayNetworking.canSend(Query.ID)){s.failed("우편함 서버 연결이 필요합니다.");return false;}if(waiting!=0)return false;long seq=++sequence;if(action!=MailboxProtocol.CLOSE){waiting=seq;deadline=Util.getMeasuringTimeMs()+12000;owner=s;}ClientPlayNetworking.send(new Query(MailboxProtocol.encode(new Request(action,seq,session,mail,page))));return true;}
 static void detach(MailboxScreen s){if(owner==s)owner=null;}
}
