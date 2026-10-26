package school.magiccodex.client;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.TitleProtocol;

/** Client-only presentation. No local title ownership, database or authoritative mutation. */
public final class TitleClient {
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes)implements CustomPayload,Bytes{static final Id<Query> ID=new Id<>(Identifier.of(TitleProtocol.REQUEST));static final PacketCodec<RegistryByteBuf,Query> CODEC=codec(Query::new,TitleProtocol.MAX_REQUEST_BYTES);public Id<Query> getId(){return ID;}}
    public record Reply(byte[] bytes)implements CustomPayload,Bytes{static final Id<Reply> ID=new Id<>(Identifier.of(TitleProtocol.RESPONSE));static final PacketCodec<RegistryByteBuf,Reply> CODEC=codec(Reply::new,TitleProtocol.MAX_BYTES);public Id<Reply> getId(){return ID;}}
    private static <T extends Bytes>PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f,int max){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>max){b.skipBytes(n);return f.apply(new byte[0]);}byte[] v=new byte[n];b.readBytes(v);return f.apply(v);}public void encode(RegistryByteBuf b,T value){int n=value.bytes().length;if(n<4||n>max)throw new IllegalArgumentException("Title payload size");b.writeBytes(value.bytes());}};}
    private static long sequence,pending,until,lastSend;private static int action;private static TitleScreen owner;private static TitleProtocol.Response data;
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=TitleProtocol.response(p.bytes());c.client().execute(()->receive(r));}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(c->{if(pending!=0&&Util.getMeasuringTimeMs()>until){pending=0;if(c.currentScreen==owner){owner.notice("응답이 늦습니다. 다시 시도해 주세요.");owner.retry();}}});
    }
    private static void reset(){sequence=pending=until=lastSend=0;owner=null;data=null;}
    static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Query.ID);}
    static boolean waiting(){return pending!=0;}
    public static void open(){var c=MinecraftClient.getInstance();if(c.player==null||c.world==null)return;MagicCodexClient.dismiss();c.setScreen(new TitleScreen());}
    static boolean request(TitleScreen screen,int next,String prefix,String suffix){
        long now=Util.getMeasuringTimeMs();if(!supported()||pending!=0||now-lastSend<220)return false;
        if(next==TitleProtocol.APPLY&&data==null)return false;
        var r=new TitleProtocol.Request(next,++sequence,data==null?"":data.token(),data==null?0:data.revision(),prefix,suffix);pending=sequence;until=now+8000;lastSend=now;owner=screen;action=next;
        ClientPlayNetworking.send(new Query(TitleProtocol.encode(r)));return true;
    }
    private static void receive(TitleProtocol.Response r){
        var c=MinecraftClient.getInstance();if(r.sequence()!=0&&r.sequence()!=pending)return;boolean applied=r.sequence()!=0&&action==TitleProtocol.APPLY;
        if(r.sequence()!=0)pending=0;if(data!=null&&r.revision()<data.revision())return;data=r;
        if(r.open()&&c.player!=null&&c.world!=null){MagicCodexClient.dismiss();c.setScreen(new TitleScreen());}
        data=r; // Changing screens can invoke removed() on the previous owner.
        if(c.currentScreen instanceof TitleScreen s){s.receive(r,applied);owner=s;}
    }
    static void closed(TitleScreen screen){if(owner!=screen)return;owner=null;pending=0;if(data!=null&&supported())ClientPlayNetworking.send(new Query(TitleProtocol.encode(new TitleProtocol.Request(TitleProtocol.CLOSE,++sequence,data.token(),data.revision(),"",""))));data=null;}
    private TitleClient(){}
}
