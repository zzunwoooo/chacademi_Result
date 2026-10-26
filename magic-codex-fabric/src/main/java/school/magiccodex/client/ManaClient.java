package school.magiccodex.client;

import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.text.Text;
import school.magiccodex.protocol.ManaHeatProtocol;
import school.magiccodex.protocol.ManaProtocol;

public final class ManaClient {
    private static ManaProtocol.Snapshot snapshot;
    private static long nextRequest,received,sequence;
    private record Pending(String spell,long until){}
    private static final Map<Long,Pending> pending=new HashMap<>();
    private ManaClient(){}
    public static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Request.ID);}
    public static double current(){return snapshot==null?0:snapshot.current();}
    public static double maximum(){return snapshot==null?0:snapshot.maximum();}
    public static double regeneration(){return snapshot==null?0:snapshot.regeneration();}
    public static double haste(){return snapshot==null?0:snapshot.haste();}
    public static boolean available(){return snapshot!=null;}
    /** 거꾸로 된 마나병 (아티팩트): the mana bar is an overheat gauge (heat = maximum - current). */
    private static boolean heat;private static long heatLockUntil;
    public static boolean heat(){return heat&&snapshot!=null;}
    /** Milliseconds of the overheat lock left (0 = not locked). */
    public static long heatLockRemaining(){return heat?Math.max(0,heatLockUntil-Util.getMeasuringTimeMs()):0;}
    public record Heat(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Heat> ID=new Id<>(Identifier.of(ManaHeatProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf,Heat> CODEC=codec(Heat::new,6,6);
        public Id<Heat> getId(){return ID;}
    }
    private interface Bytes{byte[] bytes();}
    public record Request(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Request> ID=new Id<>(Identifier.of(ManaProtocol.REQUEST));
        public static final PacketCodec<RegistryByteBuf,Request> CODEC=codec(Request::new,4,76);
        public Id<Request> getId(){return ID;}
    }
    public record Response(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Response> ID=new Id<>(Identifier.of(ManaProtocol.RESPONSE));
        public static final PacketCodec<RegistryByteBuf,Response> CODEC=codec(Response::new,41,49);
        public Id<Response> getId(){return ID;}
    }
    public record Reset(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Reset> ID=new Id<>(Identifier.of(ManaProtocol.COOLDOWN_RESET));
        public static final PacketCodec<RegistryByteBuf,Reset> CODEC=codec(Reset::new,1,64);
        public Id<Reset> getId(){return ID;}
    }
    public record Slots(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Slots> ID=new Id<>(Identifier.of("chacademia:spell_slots"));
        public static final PacketCodec<RegistryByteBuf,Slots> CODEC=codec(Slots::new,0,1024);
        public Id<Slots> getId(){return ID;}
    }
    private static String sentSlots;
    private static long nextSlots;
    private static void reportSlots(long now) {
        if(now<nextSlots||!ClientPlayNetworking.canSend(Slots.ID))return;
        nextSlots=now+1000;
        String value=String.join(",",CastingClient.state().bindings().stream().filter(b->!b.empty()).map(SpellKeySettings.Binding::spellId).distinct().limit(6).toList());
        if(!value.equals(sentSlots)){ClientPlayNetworking.send(new Slots(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));sentSlots=value;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> factory,int min,int max){
        return new PacketCodec<>(){
            public T decode(RegistryByteBuf b){int size=b.readableBytes();if(size<min||size>max){b.skipBytes(size);return factory.apply(new byte[0]);}byte[] data=new byte[size];b.readBytes(data);return factory.apply(data);}
            public void encode(RegistryByteBuf b,T value){if(value.bytes().length<min||value.bytes().length>max)throw new IllegalArgumentException("Invalid mana payload");b.writeBytes(value.bytes());}
        };
    }
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC);
        PayloadTypeRegistry.playC2S().register(Slots.ID,Slots.CODEC);
        PayloadTypeRegistry.playS2C().register(Reset.ID,Reset.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reset.ID,(payload,context)->{
            try{String id=ManaProtocol.decodeCooldownReset(payload.bytes());context.client().execute(()->CastingClient.state().clearCooldown(id));}
            catch(IllegalArgumentException ignored){}
        });
        PayloadTypeRegistry.playS2C().register(Response.ID,Response.CODEC);
        PayloadTypeRegistry.playS2C().register(Heat.ID,Heat.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Heat.ID,(payload,context)->{
            try{var h=ManaHeatProtocol.decode(payload.bytes());context.client().execute(()->{heat=h.on();heatLockUntil=Util.getMeasuringTimeMs()+h.lockMillis();});}
            catch(IllegalArgumentException ignored){}
        });
        ClientPlayNetworking.registerGlobalReceiver(Response.ID,(payload,context)->{
            try{var response=ManaProtocol.decode(payload.bytes());context.client().execute(()->receive(response));}
            catch(IllegalArgumentException ignored){}
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
    }
    private static void reset(){heat=false;heatLockUntil=0;sentSlots=null;nextSlots=0;snapshot=null;nextRequest=received=sequence=0;pending.clear();}
    public static void cast(CodexData.Spell spell){
        if(!supported())throw new IllegalStateException("Mana service unavailable");
        if(pending.size()>=6)return;
        long seq=++sequence;ClientPlayNetworking.send(new Request(ManaProtocol.cast(seq,spell.id())));
        pending.put(seq,new Pending(spell.id(),Util.getMeasuringTimeMs()+3000));
    }
    private static void receive(ManaProtocol.Response response){
        snapshot=response.mana();received=Util.getMeasuringTimeMs();
        if(response.status()==ManaProtocol.UNAVAILABLE){
            if(response.sequence()!=0){var rejected=pending.remove(response.sequence());if(rejected!=null)CastingClient.state().serverResult(rejected.spell(),0,received);}
            SharedNotifications.unavailable();
            return; // Preserve the client's existing cooldown; no error/actionbar duplicate.
        }
        if(response.sequence()==0)return;
        var request=pending.remove(response.sequence());if(request==null)return;
        CastingClient.state().serverResult(request.spell(),response.cooldownMillis(),received);
        if(response.status()==ManaProtocol.OK)ScreenVfxClient.onCast(request.spell());
        else {
            if(request.spell().equals("taming"))return; // Capture HUD already displays the server's precise rejection.
            String text=switch(response.status()){
                case ManaProtocol.EMPTY->heat?"과열 중이에요. 식을 때까지 마법을 쓸 수 없어요.":"마나가 부족합니다.";case ManaProtocol.COOLDOWN->"아직 재사용 대기 중입니다.";
                case ManaProtocol.LOCKED->"이 마법을 사용할 수 없습니다.";case ManaProtocol.UNKNOWN->"서버에 등록되지 않았거나 비활성화된 마법입니다.";
                default->"마법 명령이 연결되지 않았거나 실행에 실패했습니다.";
            };
            // Notification card + 삐빅 (no actionbar). Same id while the card is visible = no spam on repeated presses.
            SharedNotifications.offer("mana:fail:"+response.status(),"error","마법을 사용하지 못했어요",text);
        }
    }
    public static void tick(MinecraftClient client){
        if(client.player==null||client.world==null)return;
        long now=Util.getMeasuringTimeMs();reportSlots(now);pending.entrySet().removeIf(e->e.getValue().until()<now);
        if(!supported()){snapshot=null;nextRequest=received=0;pending.clear();return;}
        if(received!=0&&now-received>65000){snapshot=null;received=0;}
        if(now>=nextRequest){ClientPlayNetworking.send(new Request(ManaProtocol.subscribe()));nextRequest=now+20000;}
    }
}
