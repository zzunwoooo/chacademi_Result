package school.magiccodex.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingProtocol.*;
import school.magiccodex.protocol.HousingRules.Def;

/**
 * 하우징 (기숙사 내 방 꾸미기) 연결. 게임 서버의 ChacaHousing 플러그인과 {@code chacademi:housing} 으로 주고받는다.
 * 창은 서버가 연다 (기숙사실 문 우클릭 → ROOM + 열기 표시). 방 안의 모든 동작은 서버가 다시 검사한다.
 */
public final class HousingClient {
    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID=new Id<>(Identifier.of(HousingProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf,Payload> CODEC=new PacketCodec<>(){
            public Payload decode(RegistryByteBuf b){int size=b.readableBytes();if(size>HousingProtocol.MAX_BYTES){b.skipBytes(size);return new Payload(new byte[0]);}byte[] data=new byte[size];b.readBytes(data);return new Payload(data);}
            public void encode(RegistryByteBuf b,Payload value){b.writeBytes(value.bytes());}
        };
        public Id<Payload> getId(){return ID;}
    }

    private static Map<String,Def> defs=Map.of();
    private static List<Def> ordered=List.of();
    private static Room room;
    private static Viewers viewers;
    private static Guestbook guestbook;
    private static List<Friend> friends;
    private record SkinKey(UUID id,String hash,boolean slim){}
    private static final Map<SkinKey,Supplier<SkinTextures>> skins=new LinkedHashMap<>(64,.75f,true);
    private HousingClient(){}

    static void initialize(){
        HousingSearchClient.initialize();
        HousingRankClient.initialize(); // #51 하우징 랭킹 채널
        PayloadTypeRegistry.playS2C().register(Payload.ID,Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payload.ID,Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID,(payload,context)->{
            try{var message=HousingProtocol.decode(payload.bytes());context.client().execute(()->receive(message));}
            catch(IllegalArgumentException ignored){/* 잘못된 패킷은 통째로 버린다. */}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->client.execute(HousingClient::reset));
        ClientTickEvents.END_CLIENT_TICK.register(HousingClient::tick);
    }
    private static void reset(){defs=Map.of();ordered=List.of();room=null;viewers=null;guestbook=null;friends=null;skins.clear();suspended=null;child=null;HousingShowcase.reset();}

    // ---- #108 구경 화면을 방 위에 잠깐 (아티팩트 진열장 → 도감 구경 모드): 그 창을 닫으면 방으로 돌아온다, 그동안 방 세션은 유지
    private static HousingScreen suspended;
    private static Screen child;
    static void suspend(HousingScreen room,Screen over){suspended=room;child=over;}
    static boolean suspended(HousingScreen room){return suspended==room;}
    /** 방으로 돌아왔으면 풀고, 다른 창으로 가 버렸으면 그때 방을 닫은 것으로 처리 (구경 중 명단에서 빠지게). 참조 비교 두 번뿐. */
    private static void tick(MinecraftClient c){
        if(suspended==null)return;
        var now=c.currentScreen;
        if(now==suspended){suspended=null;child=null;}
        else if(now!=child){suspended=null;child=null;closed();}
    }

    static Def def(String id){return defs.get(id);}
    static List<Def> catalog(){return ordered;}
    static Room room(){return room;}
    /** 지금 보고 있는 방의 구경 중 명단 (다른 방 것이면 null). */
    static Viewers viewers(){return room!=null&&viewers!=null&&viewers.owner().equals(room.owner().id())?viewers:null;}
    static Guestbook guestbook(){return room!=null&&guestbook!=null&&guestbook.owner().equals(room.owner().id())?guestbook:null;}
    static List<Friend> friends(){return friends;}

    private static void receive(Message m){
        var c=MinecraftClient.getInstance();
        switch(m){
            case Catalog cat->{
                var map=new LinkedHashMap<String,Def>();for(var d:cat.defs())map.put(d.id(),d);
                defs=Collections.unmodifiableMap(map);ordered=List.copyOf(cat.defs());
                if(c.currentScreen instanceof HousingScreen screen)screen.catalogChanged();
            }
            case RoomMessage rm->{
                var previous=room;room=rm.room();
                if(previous==null||!previous.owner().id().equals(room.owner().id())){viewers=null;guestbook=null;HousingShowcase.reset();}
                if(c.currentScreen instanceof HousingScreen screen)screen.roomChanged(previous,room);
                else if(room.open()&&(c.currentScreen==null||c.currentScreen instanceof ChatScreen||c.currentScreen instanceof HudCursorScreen)){
                    MagicCodexClient.dismiss();c.setScreen(new HousingScreen());
                }else if(room.open()){
                    // 다른 창이 열려 있으면 닫힌 것으로 처리 (구경 중 명단에서 빠지게)
                    send(new Close());
                }
            }
            case Viewers v->viewers=v;
            case Showcase s->{if(room!=null&&room.owner().id().equals(s.owner()))HousingShowcase.accept(s);}
            case Guestbook g->{guestbook=g;if(c.currentScreen instanceof HousingScreen screen)screen.guestbookChanged();}
            case FriendList f->{friends=f.friends();if(c.currentScreen instanceof HousingScreen screen)screen.friendsChanged();}
            case Result r->{
                if(c.currentScreen instanceof HousingScreen screen)screen.result(r.ok(),r.message());
                else if(!r.message().isEmpty())SharedNotifications.offer("housing:result","system","내 방",r.message());
            }
        }
    }

    static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Payload.ID);}
    /** @return 보냈으면 true */
    static boolean send(Request r){
        if(!supported())return false;
        ClientPlayNetworking.send(new Payload(HousingProtocol.encode(r)));return true;
    }
    /** 창을 닫을 때: 지금 방 정보는 지운다 (다음에 열 때 서버가 다시 보낸다). */
    static void closed(){HousingSearchClient.invalidate();send(new Close());room=null;viewers=null;guestbook=null;friends=null;HousingShowcase.reset();}

    /** 얼굴 텍스처 (파티 창과 같은 방식: 같은 서버면 탭 목록 스킨, 아니면 스킨 해시로 내려받기, 없으면 기본 스킨). */
    static Identifier face(Person p){
        var c=MinecraftClient.getInstance();
        var entry=c.getNetworkHandler()==null?null:c.getNetworkHandler().getPlayerListEntry(p.id());
        if(entry!=null)return entry.getSkinTextures().texture();
        if(p.skin().isEmpty())return DefaultSkinHelper.getSkinTextures(p.id()).texture();
        var key=new SkinKey(p.id(),p.skin(),p.slim());
        var supplier=skins.get(key);
        if(supplier==null){
            while(skins.size()>=128)skins.remove(skins.keySet().iterator().next());
            String json="{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"+p.skin()+"\""+(p.slim()?",\"metadata\":{\"model\":\"slim\"}":"")+"}}}";
            var profile=new GameProfile(p.id(),"HousingFace");
            profile.getProperties().put("textures",new Property("textures",Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
            supplier=c.getSkinProvider().getSkinTexturesSupplier(profile);skins.put(key,supplier);
        }
        return supplier.get().texture();
    }
}
