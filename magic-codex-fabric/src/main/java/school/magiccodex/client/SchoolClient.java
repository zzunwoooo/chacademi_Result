package school.magiccodex.client;

import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.SchoolProtocol;
import school.magiccodex.protocol.SchoolProtocol.*;

public final class SchoolClient {
    private static long sequence,revision=-1,nextSend,nextIdentity;
    private static String nickname="",dormitory="";
    private static boolean open;
    private record Cached(Donation donation,long until){}
    private record Pending(Request request,Screen owner,long until){}
    private static final Map<String,Cached> donors=new HashMap<>();
    private static final Map<Long,Pending> pending=new HashMap<>();
    private static final Map<Integer,Response> pages=new HashMap<>();
    private static List<Long> scores=List.of(0L,0L,0L,0L);
    private static int total;
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Query> ID=new Id<>(Identifier.of(SchoolProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf,Query> CODEC=codec(Query::new);
        public Id<Query> getId(){return ID;}
    }
    public record Reply(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Reply> ID=new Id<>(Identifier.of(SchoolProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf,Reply> CODEC=codec(Reply::new);
        public Id<Reply> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>SchoolProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] v=new byte[n];b.readBytes(v);return f.apply(v);}
        public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}
    };}
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{Response r=SchoolProtocol.response(p.bytes());c.client().execute(()->receive(r));}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.player==null||c.world==null)return;
            if(open&&c.getOverlay()==null){open=false;MagicCodexClient.dismiss();c.setScreen(new SchoolScreen());}
            long now=Util.getMeasuringTimeMs();var it=pending.entrySet().iterator();
            while(it.hasNext()){var e=it.next();if(now>=e.getValue().until()){var p=e.getValue();it.remove();notice(p.owner(),"서버 응답이 늦습니다. 잠시 후 다시 시도해 주세요.");}}
            if(now>=nextIdentity&&supported()&&pending.isEmpty()&&!(c.currentScreen instanceof CodexScreen)&&!(c.currentScreen instanceof SchoolScreen)){
                if(request(SchoolProtocol.IDENTITY,0,"",null))nextIdentity=now+10000;
            }
        });
    }
    private static void reset(){sequence=nextSend=nextIdentity=0;revision=-1;nickname=dormitory="";open=false;donors.clear();pending.clear();pages.clear();scores=List.of(0L,0L,0L,0L);total=0;}
    public static void open(){open=true;}
    public static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Query.ID);}
    public static String nickname(String fallback){return NicknameClient.display(nickname.isBlank()?fallback:nickname);}
    public static String dormitory(String fallback){return dormitory.isBlank()?fallback:dormitory;}
    static List<Long> scores(){return scores;}
    static int total(){return total;}
    static Response page(int page){return pages.get(page);}
    static boolean waiting(int action,String spell){return pending.values().stream().anyMatch(p->p.request().action()==action&&p.request().spell().equals(spell));}
    static boolean request(int action,int page,String spell,Screen owner){
        long now=Util.getMeasuringTimeMs();if(!supported()||now<nextSend||pending.size()>=8||waiting(action,spell))return false;
        var r=new Request(action,++sequence,page,spell);pending.put(sequence,new Pending(r,owner,now+8000));nextSend=now+150;
        ClientPlayNetworking.send(new Query(SchoolProtocol.encode(r)));return true;
    }
    static void lookup(String spell,Screen owner){Cached c=donors.get(spell);if(c==null||c.until()<Util.getMeasuringTimeMs())request(SchoolProtocol.LOOKUP,0,spell,owner);}
    static String donor(String spell){var c=donors.get(spell);if(c==null)return supported()?"확인 중":"서버 연결 필요";return c.donation()==null?"기록 없음":c.donation().nickname();}
    static void donate(CodexData.Spell spell,CodexScreen owner){
        if(spell==null){owner.schoolNotice("마법을 선택해 주세요.");return;}
        if(!supported()){owner.schoolNotice("학교 기증 서버 연결이 필요합니다.");return;}
        // Server checks possession and the globally unique claim; local state cannot grant donations.
        if(request(SchoolProtocol.DONATE,0,spell.id(),owner))owner.schoolNotice("기증을 확인하고 있습니다.");else owner.schoolNotice("잠시 후 다시 시도해 주세요.");
    }
    private static void notice(Screen owner,String text){if(MinecraftClient.getInstance().currentScreen!=owner)return;if(owner instanceof CodexScreen s)s.schoolNotice(text);if(owner instanceof SchoolScreen s)s.notice(text);}
    private static void receive(Response r){
        Pending p=pending.remove(r.sequence());if(r.sequence()!=0&&p==null)return;
        nickname=r.nickname();dormitory=r.dormitory();scores=r.scores();total=r.total();
        if(r.revision()!=revision){revision=r.revision();donors.clear();pages.clear();}
        if(r.action()==SchoolProtocol.LIST){pages.put(r.page(),r);if(r.sequence()==0&&r.message().equals("open"))open();}
        if(r.action()==SchoolProtocol.LOOKUP||r.action()==SchoolProtocol.DONATE)donors.put(r.spell(),new Cached(r.records().isEmpty()?null:r.records().getFirst(),Util.getMeasuringTimeMs()+10000));
        if(p!=null&&!r.message().isBlank())notice(p.owner(),r.message());
    }
}
