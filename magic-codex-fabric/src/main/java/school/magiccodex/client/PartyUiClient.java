package school.magiccodex.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.PartyUiProtocol;
import school.magiccodex.protocol.PartyUiProtocol.Person;
import school.magiccodex.protocol.PartyUiProtocol.State;

/**
 * 파티 창 연결. Velocity 파티 플러그인과 {@code chacademi:party_ui} 로 주고받는다.
 * /파티 (인수 없음) → 프록시가 열기 표시가 붙은 상태를 보내면 창을 연다. 초대 알림은 FriendRequestToast 가 그린다.
 */
public final class PartyUiClient {
    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID=new Id<>(Identifier.of(PartyUiProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf,Payload> CODEC=new PacketCodec<>(){
            public Payload decode(RegistryByteBuf b){int size=b.readableBytes();if(size>PartyUiProtocol.MAX_BYTES){b.skipBytes(size);return new Payload(new byte[0]);}byte[] data=new byte[size];b.readBytes(data);return new Payload(data);}
            public void encode(RegistryByteBuf b,Payload value){b.writeBytes(value.bytes());}
        };
        public Id<Payload> getId(){return ID;}
    }
    private static State state=State.EMPTY;
    private static List<Person> candidates=List.of();
    private static long candidatesAt;
    private record SkinKey(UUID id,String hash,boolean slim){}
    private static final Map<SkinKey,Supplier<SkinTextures>> skins=new LinkedHashMap<>(64,.75f,true);
    private PartyUiClient(){}

    static void initialize(){
        PayloadTypeRegistry.playS2C().register(Payload.ID,Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payload.ID,Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID,(payload,context)->{
            try{var message=PartyUiProtocol.decode(payload.bytes());context.client().execute(()->receive(message));}
            catch(IllegalArgumentException ignored){/* 잘못된 패킷은 통째로 버린다. */}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->client.execute(PartyUiClient::reset));
    }
    private static void reset(){state=State.EMPTY;candidates=List.of();candidatesAt=0;skins.clear();}

    public static State state(){return state;}
    public static List<Person> candidates(){return candidates;}
    public static long candidatesAt(){return candidatesAt;}

    private static void receive(PartyUiProtocol.Message m){
        var c=MinecraftClient.getInstance();
        switch(m.kind()){
            case PartyUiProtocol.K_STATE->{
                state=m.state();
                FriendRequestToast.syncParty(state.invites());
                if(c.currentScreen instanceof PartyScreen screen)screen.stateChanged();
                else if(state.open()&&(c.currentScreen==null||c.currentScreen instanceof ChatScreen||c.currentScreen instanceof HudCursorScreen)){
                    MagicCodexClient.dismiss();c.setScreen(new PartyScreen());
                }
            }
            case PartyUiProtocol.K_CANDIDATES->{candidates=m.people();candidatesAt=Util.getMeasuringTimeMs();if(c.currentScreen instanceof PartyScreen screen)screen.stateChanged();}
            case PartyUiProtocol.K_INVITE->FriendRequestToast.offerParty(m.person());
            case PartyUiProtocol.K_RESULT->{
                if(c.currentScreen instanceof PartyScreen screen)screen.result(m.ok(),m.message());
                else if(!m.message().isEmpty())SharedNotifications.offer("party:result","system","파티",m.message());
            }
            default->{}
        }
    }

    public static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Payload.ID);}
    /** @return 보냈으면 true (연결이 없거나 프록시가 채널을 알리지 않았으면 false) */
    public static boolean send(int action,UUID target){
        if(!supported())return false;
        ClientPlayNetworking.send(new Payload(PartyUiProtocol.request(action,target)));return true;
    }
    /** 초대 알림 카드의 수락/거절. */
    static boolean respond(UUID inviter,boolean accept){return send(accept?PartyUiProtocol.ACCEPT:PartyUiProtocol.DENY,inviter);}

    /**
     * 얼굴 텍스처. 같은 서버에 있으면 탭 목록의 스킨을, 아니면 프록시가 알려 준 스킨 해시로 내려받는다(표시 전용, 서명 없음).
     * 내려받는 동안과 해시가 없을 때는 UUID 기본 스킨.
     */
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
            var profile=new GameProfile(p.id(),"PartyMember");
            profile.getProperties().put("textures",new Property("textures",Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
            supplier=c.getSkinProvider().getSkinTexturesSupplier(profile);skins.put(key,supplier);
        }
        return supplier.get().texture();
    }
}
