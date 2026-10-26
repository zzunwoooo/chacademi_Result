package school.magiccodex.client;

import java.util.*;
import java.util.function.*;
import java.io.ByteArrayInputStream;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.StatsProtocol;
import school.magiccodex.protocol.StatsProtocol.*;

/**
 * 서버가 보내는 정보창 스냅샷 (상대 정보 · v3 서버면 내 정보도). ManaClient·권한 목록에는 쓰지 않는다.
 * v2 채널은 옛 서버용, v3 채널(정보창 v4)은 칭호·하우징·퀘스트·장신구·인기도 상태를 더 받는다.
 * 새 클라는 서버가 v3 요청 채널을 열었을 때만 v3 로 요청한다 ({@link #v3()}).
 */
public final class RemoteStatsClient {
    private static View active;
    private static Response pendingOpen;
    private static Extra pendingExtra;
    private static boolean pendingThree;
    private interface Bytes {byte[] bytes();}
    public record Query(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Query> ID=new Id<>(Identifier.of(StatsProtocol.REQUEST));
        public static final PacketCodec<RegistryByteBuf,Query> CODEC=codec(Query::new,StatsProtocol.MAX_BYTES);
        public Id<Query> getId(){return ID;}
    }
    public record Reply(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Reply> ID=new Id<>(Identifier.of(StatsProtocol.RESPONSE));
        public static final PacketCodec<RegistryByteBuf,Reply> CODEC=codec(Reply::new,StatsProtocol.MAX_BYTES);
        public Id<Reply> getId(){return ID;}
    }
    public record Query3(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Query3> ID=new Id<>(Identifier.of(StatsProtocol.REQUEST3));
        public static final PacketCodec<RegistryByteBuf,Query3> CODEC=codec(Query3::new,StatsProtocol.MAX_BYTES);
        public Id<Query3> getId(){return ID;}
    }
    public record Reply3(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Reply3> ID=new Id<>(Identifier.of(StatsProtocol.RESPONSE3));
        public static final PacketCodec<RegistryByteBuf,Reply3> CODEC=codec(Reply3::new,StatsProtocol.MAX_BYTES3);
        public Id<Reply3> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> factory,int max){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){int size=b.readableBytes();if(size<4||size>max){b.skipBytes(size);return factory.apply(new byte[0]);}byte[] bytes=new byte[size];b.readBytes(bytes);return factory.apply(bytes);}
        public void encode(RegistryByteBuf b,T value){if(value.bytes().length>max)throw new IllegalArgumentException("Large stats payload");b.writeBytes(value.bytes());}
    };}
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        PayloadTypeRegistry.playC2S().register(Query3.ID,Query3.CODEC);PayloadTypeRegistry.playS2C().register(Reply3.ID,Reply3.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(payload,context)->{
            try{var response=StatsProtocol.decodeResponse(payload.bytes());context.client().execute(()->receive(response,null,null,false));}catch(IllegalArgumentException ignored){}
        });
        ClientPlayNetworking.registerGlobalReceiver(Reply3.ID,(payload,context)->{
            try{var r=StatsProtocol.decodeResponse3(payload.bytes());context.client().execute(()->receive(r.base(),r.extra(),r.vote(),true));}catch(IllegalArgumentException ignored){}
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,c)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.player==null||c.world==null){reset();return;}
            if(pendingOpen!=null&&c.getOverlay()==null){var r=pendingOpen;var extra=pendingExtra;boolean three=pendingThree;pendingOpen=null;pendingExtra=null;
                var view=new View(r,extra,three);
                // 내 정보: 단축키로 이미 열려 있는 내 정보창에 서버 값을 붙인다 (창을 다시 열지 않음)
                if(view.self()&&c.currentScreen instanceof StatsScreen s&&s.isLive()&&s.attach(view)){active=view;}
                else{MagicCodexClient.dismiss();var screen=view.self()?new StatsScreen():new StatsScreen(view);if(view.self())screen.attach(view);c.setScreen(screen);active=view;}
            }
            if(active!=null)active.tick(c);
        });
    }
    private static void reset(){active=null;pendingOpen=null;pendingExtra=null;StatsRanks.reset();}
    /** 서버가 v3 (정보창 v4) 를 아는지: 새 요청 채널을 열었으면 true. */
    static boolean v3(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Query3.ID);}
    /** 내 정보창을 열 때: v3 서버면 내 스냅샷(칭호·퀘스트·장비·인기도)을 요청한다. 옛 서버면 아무것도 안 함. */
    static void requestSelf(){
        var c=MinecraftClient.getInstance();if(c.player==null||!v3())return;
        ClientPlayNetworking.send(new Query3(StatsProtocol.encodeRequest3(new Request(StatsProtocol.SELF,1,c.player.getUuid()))));
    }
    private static void receive(Response r,Extra extra,Vote vote,boolean three){
        if(r.kind()==StatsProtocol.OPEN){pendingOpen=r;pendingExtra=extra;pendingThree=three;return;}
        // A newer open can arrive in the same tick; keep its latest snapshot.
        if(pendingOpen!=null&&pendingOpen.session()==r.session()&&pendingOpen.target().equals(r.target())){
            if(r.kind()==StatsProtocol.GONE)pendingOpen=null;
            else if(r.kind()==StatsProtocol.SNAPSHOT){pendingOpen=new Response(StatsProtocol.OPEN,r.session(),r.target(),r.message(),r.profile());pendingExtra=extra;}
            return;
        }
        if(active==null||active.session!=r.session()||!active.id().equals(r.target()))return;
        var c=MinecraftClient.getInstance();if(!(c.currentScreen instanceof StatsScreen s)||!s.displays(active))return;
        if(r.kind()==StatsProtocol.SNAPSHOT)active.update(r.profile(),extra);
        else if(r.kind()==StatsProtocol.VOTED){active.waiting=0;if(vote!=null)active.vote(vote);s.showNotice(r.message());}
        else {active.waiting=0;s.showNotice(r.message());if(r.kind()==StatsProtocol.GONE)active.online=false;}
    }
    private static void send(int action,View view){
        if(view.three&&ClientPlayNetworking.canSend(Query3.ID))ClientPlayNetworking.send(new Query3(StatsProtocol.encodeRequest3(new Request(action,view.session,view.id()))));
        else if(action<=StatsProtocol.POPULARITY&&ClientPlayNetworking.canSend(Query.ID))ClientPlayNetworking.send(new Query(StatsProtocol.encodeRequest(new Request(action,view.session,view.id()))));
    }
    public static final class View {
        private final long session;
        private final boolean three;
        private Profile profile;
        private Extra extra;
        private OtherClientPlayerEntity avatar;
        private final ItemStack[] armor={ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY};
        private final ItemStack[] accessories={ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY};
        private Double popularity;
        private boolean voted;
        private int tickets;
        private long received,nextRefresh,nextAction,waiting;
        private boolean online=true,closed;
        private View(Response response,Extra extra,boolean three){session=response.session();this.three=three;update(response.profile(),extra);}
        public UUID id(){return profile.id();}
        /** 이 스냅샷이 내 것인지 (내 정보창). */
        public boolean self(){var p=MinecraftClient.getInstance().player;return p!=null&&p.getUuid().equals(id());}
        /** 서버가 v3 값(칭호·퀘스트·장신구·인기도 상태)을 보냈는지. false 면 그 칸은 모름(—). */
        public boolean extended(){return three&&extra!=null;}
        public Profile profile(){return profile;}
        public Extra extra(){return extra;}
        public Double popularity(){return popularity;}
        public boolean voted(){return voted;}
        public int tickets(){return tickets;}
        /** 머리·상의·하의·신발 */
        public ItemStack armor(int i){return armor[i];}
        /** 반지·귀걸이·목걸이 */
        public ItemStack accessory(int i){return accessories[i];}
        public StatsValues values(){var p=profile;return new StatsValues(p.name(),p.circle(),p.dormitory(),p.power(),p.health(),p.mana(),p.maximum(),p.regeneration(),p.armor(),p.learned(),p.total(),popularity,p.haste());}
        public OtherClientPlayerEntity avatar(){return avatar;}
        private void vote(Vote v){popularity=v.popularity();voted=v.voted();tickets=v.tickets();}
        private void update(Profile value,Extra more){
            boolean changed=profile==null||!profile.texture().equals(value.texture())||!profile.signature().equals(value.signature());
            profile=value;if(more!=null)extra=more;popularity=value.popularity();
            if(extra!=null){voted=extra.voted();tickets=extra.tickets();}
            received=Util.getMeasuringTimeMs();nextRefresh=received+5000;online=true;
            var c=MinecraftClient.getInstance();if(c.world==null)return;
            if(changed||avatar==null){
                GameProfile gameProfile=new GameProfile(profile.id(),"CodexPlayer");
                if(!profile.texture().isEmpty())gameProfile.getProperties().put("textures",new Property("textures",profile.texture(),profile.signature().isEmpty()?null:profile.signature()));
                Supplier<SkinTextures> skins=c.getSkinProvider().getSkinTexturesSupplier(gameProfile);
                avatar=new OtherClientPlayerEntity(c.world,gameProfile){
                    @Override public SkinTextures getSkinTextures(){return skins.get();}
                    @Override public boolean isPartVisible(PlayerModelPart part){return true;}
                };
            }
            EquipmentSlot[] slots={EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.FEET,EquipmentSlot.LEGS,EquipmentSlot.CHEST,EquipmentSlot.HEAD};
            for(int i=0;i<slots.length;i++){
                ItemStack item=item(c,profile.equipment().get(i));
                avatar.equipStack(slots[i],item);
                if(i>=2)armor[5-i]=item; // FEET(2)→신발(3) … HEAD(5)→머리(0)
            }
            if(extra!=null)for(int i=0;i<accessories.length;i++)accessories[i]=item(c,extra.accessories().get(i));
        }
        private static ItemStack item(MinecraftClient c,byte[] bytes){
            if(bytes.length==0||c.world==null)return ItemStack.EMPTY;
            try{var nbt=NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtSizeTracker.of(262144));return ItemStack.fromNbt(c.world.getRegistryManager(),nbt).orElse(ItemStack.EMPTY);}catch(Exception ignored){return ItemStack.EMPTY;}
        }
        /** 버튼 요청 (친구 추가 · 인기도 올리기 · 내리기). 반환 = 바로 보여 줄 안내 문구. */
        public String action(StatsSocialActions.Action action){
            long now=Util.getMeasuringTimeMs();var c=MinecraftClient.getInstance();
            if(closed||!online||now-received>15000)return "상대방 정보를 다시 열어 주세요.";
            if(c.player!=null&&c.player.getUuid().equals(id()))return "자신에게는 사용할 수 없습니다.";
            if(action==StatsSocialActions.Action.POPULARITY_DOWN&&!(three&&ClientPlayNetworking.canSend(Query3.ID)))return "서버 업데이트 후 사용할 수 있어요.";
            if(!(three?ClientPlayNetworking.canSend(Query3.ID):ClientPlayNetworking.canSend(Query.ID)))return "상대방 정보를 다시 열어 주세요.";
            if(now<nextAction||waiting!=0)return "요청을 확인하고 있습니다.";
            nextAction=now+1000;waiting=now;
            send(switch(action){case FRIEND->StatsProtocol.FRIEND;case POPULARITY->StatsProtocol.POPULARITY;case POPULARITY_DOWN->StatsProtocol.DOWN;},this);
            return "요청을 확인하고 있습니다.";
        }
        private void tick(MinecraftClient c){
            if(!(c.currentScreen instanceof StatsScreen screen)||!screen.displays(this)){close();return;}
            long now=Util.getMeasuringTimeMs();
            if(waiting!=0&&now-waiting>4000){waiting=0;screen.showNotice("응답이 없습니다. 잠시 후 다시 시도해 주세요.");}
            if(online&&now>=nextRefresh){nextRefresh=now+5000;send(StatsProtocol.REFRESH,this);}
            if(online&&now-received>15000){online=false;if(!self())screen.showNotice("연결이 끊겼습니다. 정보창을 다시 열어 주세요.");}
        }
        public void close(){if(closed)return;closed=true;send(StatsProtocol.CLOSE,this);avatar=null;if(active==this)active=null;}
    }
}
