package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.EquipmentProtocol;
import school.magiccodex.protocol.EquipmentProtocol.*;

public final class EquipmentClient {
    private static net.minecraft.client.option.KeyBinding key;
    private static final CodexShortcut shortcut=new CodexShortcut();
    static net.minecraft.client.option.KeyBinding binding(){return key;}
    public static boolean handleKey(MinecraftClient c,int code,int scan,int action){
        boolean ready=c.world!=null&&c.player!=null&&c.player.isAlive()&&(c.currentScreen==null||c.currentScreen instanceof EquipmentScreen)&&c.getOverlay()==null&&c.isWindowFocused();
        var result=shortcut.handle(code,action,key!=null&&key.matchesKey(code,scan),ready);
        if(result==CodexShortcut.Result.OPEN){if(c.currentScreen instanceof EquipmentScreen s)s.close();else open();}
        return result!=CodexShortcut.Result.PASS;
    }
    private static long sequence,pending,deadline,nextRefresh,nextSend;
    private static boolean open;
    static Response data;
    static List<ItemStack> equipped=List.of(),candidates=List.of();
    static String notice="";
    private interface Bytes{byte[] bytes();}
    record Query(byte[] bytes) implements CustomPayload,Bytes{static final Id<Query> ID=new Id<>(Identifier.of(EquipmentProtocol.REQUEST));public Id<Query> getId(){return ID;}}
    record Reply(byte[] bytes) implements CustomPayload,Bytes{static final Id<Reply> ID=new Id<>(Identifier.of(EquipmentProtocol.RESPONSE));public Id<Reply> getId(){return ID;}}
    private static <T extends Bytes>PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n>EquipmentProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}
        public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}
    };}
    static void initialize(){
        key=net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(new net.minecraft.client.option.KeyBinding("key.magiccodex.equipment",org.lwjgl.glfw.GLFW.GLFW_KEY_O,"category.magiccodex"));
        KeySettingsLayout.equipmentKey(code->key.matchesKey(code,0));
        PayloadTypeRegistry.playC2S().register(Query.ID,codec(Query::new));PayloadTypeRegistry.playS2C().register(Reply.ID,codec(Reply::new));
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=EquipmentProtocol.response(p.bytes());c.client().execute(()->{
            if(r.sequence()!=pending||pending==0)return;pending=0;data=r;nextRefresh=Util.getMeasuringTimeMs()+5000;
            equipped=r.equipped().stream().map(EquipmentClient::decode).toList();candidates=r.candidates().stream().map(i->decode(i.preview())).toList();
            if(!r.message().isBlank())notice=r.message();
        });}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientCommandRegistrationCallback.EVENT.register((d,a)->d.register(ClientCommandManager.literal("장비").executes(c->{open();return 1;})));
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            while(key.wasPressed()){}
            if(!c.isWindowFocused())shortcut.reset();
            if(c.world==null||c.player==null)return;
            if(open&&c.getOverlay()==null){open=false;MagicCodexClient.dismiss();c.setScreen(new EquipmentScreen());nextRefresh=0;notice="";}
            long now=Util.getMeasuringTimeMs();if(pending!=0&&now>deadline){pending=0;notice="서버 응답이 늦습니다. 다시 시도해 주세요.";nextRefresh=now+2000;}
            if((c.currentScreen instanceof EquipmentScreen||c.currentScreen instanceof StatsScreen s&&s.isLive())&&now>=nextRefresh&&pending==0){nextRefresh=now+5000;request(EquipmentProtocol.VIEW,0,0);}
        });
    }
    private static void reset(){shortcut.reset();data=null;equipped=candidates=List.of();pending=sequence=deadline=nextRefresh=nextSend=0;open=false;notice="";}
    private static ItemStack decode(byte[] b){var c=MinecraftClient.getInstance();if(b.length==0||c.world==null)return ItemStack.EMPTY;try{return ItemStack.fromNbt(c.world.getRegistryManager(),NbtIo.readCompressed(new ByteArrayInputStream(b),NbtSizeTracker.of(262144))).orElse(ItemStack.EMPTY);}catch(Exception e){return ItemStack.EMPTY;}}
    static boolean supported(){return MinecraftClient.getInstance().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Query.ID);}
    static boolean busy(){return pending!=0;}
    static void open(){open=true;}
    static void closed(){if(supported())ClientPlayNetworking.send(new Query(EquipmentProtocol.request(new Request(EquipmentProtocol.CLOSE,++sequence,0,0,0))));pending=0;nextRefresh=0;}
    static boolean request(int action,int slot,int inventory){
        if(!supported()){notice="장비 연동 플러그인 0.16.0 이상이 필요합니다.";return false;}if(pending!=0)return false;
        long now=Util.getMeasuringTimeMs();if(now<nextSend)return false;nextSend=now+150;
        pending=++sequence;deadline=Util.getMeasuringTimeMs()+5000;notice=action==EquipmentProtocol.VIEW?notice:"장비를 변경하고 있습니다.";
        ClientPlayNetworking.send(new Query(EquipmentProtocol.request(new Request(action,pending,data==null?0:data.revision(),slot,inventory))));return true;
    }
    static Double power(Double base){if(data==null)return base;if(base!=null)return base+data.power();if(data.power()==0)return null;return data.power();}
}
