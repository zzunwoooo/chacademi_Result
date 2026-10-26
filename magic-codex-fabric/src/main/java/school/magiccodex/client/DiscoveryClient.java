package school.magiccodex.client;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.resource.*;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.*;
import school.magiccodex.protocol.DiscoveryProtocol;
import school.magiccodex.protocol.DiscoveryProtocol.Notice;

/** One common texture and one OGG, independent of the number of spells. */
public final class DiscoveryClient {
    private static final DiscoveryQueue QUEUE=new DiscoveryQueue();
    private static Notice current;private static long started,nextHello,previewToken=-1,pausedAt;
    private static HudTextureCache images;private static CodexTypography text;
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/discovery_toast.png"),FONT=Identifier.of("magiccodex","hud_bold"),BODY=Identifier.of("magiccodex","label");
    private interface Bytes{byte[] bytes();}
    public record Ack(byte[] bytes) implements CustomPayload,Bytes{
        public static final Id<Ack> ID=new Id<>(Identifier.of(DiscoveryProtocol.REQUEST));public static final PacketCodec<RegistryByteBuf,Ack> CODEC=codec(Ack::new,12);public Id<Ack> getId(){return ID;}
    }
    public record Notification(byte[] bytes) implements CustomPayload,Bytes{
        public static final Id<Notification> ID=new Id<>(Identifier.of(DiscoveryProtocol.RESPONSE));public static final PacketCodec<RegistryByteBuf,Notification> CODEC=codec(Notification::new,700);public Id<Notification> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f,int max){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n>max||n<4){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}
        public void encode(RegistryByteBuf b,T v){b.writeBytes(v.bytes());}
    };}
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Ack.ID,Ack.CODEC);PayloadTypeRegistry.playS2C().register(Notification.ID,Notification.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Notification.ID,(payload,ctx)->{try{var notice=DiscoveryProtocol.decode(payload.bytes());ctx.client().execute(()->{
            var result=QUEUE.offer(notice);if(result==DiscoveryQueue.Result.FINISHED)ack(notice.token());
            if(result==DiscoveryQueue.Result.ADDED)PermissionClient.reset();
        });}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((handler,sender,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((handler,c)->reset());
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){public Identifier getFabricId(){return Identifier.of("magiccodex","discovery_toast");}public void reload(ResourceManager manager){close();}});
        ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.player==null||c.world==null)return;long now=Util.getMeasuringTimeMs();if(now>=nextHello&&ClientPlayNetworking.canSend(Ack.ID)){ack(0);nextHello=now+15000;}});
    }
    public static int preview(String id){var s=CodexCatalog.spells().stream().filter(v->v.id().equals(id)||v.name().equals(id)).findFirst().orElse(null);
        if(s==null&&!id.equals("harvest_wind"))return 0;QUEUE.offer(new Notice(previewToken--,s==null?id:s.id(),s==null?"수확의 바람":s.name(),s==null?"magiccodex:textures/spells/novice/harvest_wind.png":s.icon(),false));return 1;}
    private static void ack(long token){if(token>=0&&ClientPlayNetworking.canSend(Ack.ID))ClientPlayNetworking.send(new Ack(DiscoveryProtocol.request(token)));}
    private static void reset(){QUEUE.clear();current=null;nextHello=started=pausedAt=0;close();}
    private static void close(){if(images!=null){images.close();images=null;}if(text!=null){text.close();text=null;}}
    public static boolean active(){return current!=null;}
    public static void render(DrawContext ctx){
        var c=MinecraftClient.getInstance();long now=Util.getMeasuringTimeMs();
        boolean visible=c.player!=null&&c.world!=null&&c.player.isAlive()&&!c.options.hudHidden&&(c.currentScreen==null||c.currentScreen instanceof HudCursorScreen);
        if(!visible){if(current!=null&&pausedAt==0)pausedAt=now;return;}
        if(pausedAt!=0){started+=now-pausedAt;pausedAt=0;}
        if(current==null){current=QUEUE.poll();if(current==null)return;started=now;c.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex","spell_discovered")),1f,.62f));}
        long age=now-started;if(age>=4500){QUEUE.finish(current.token());ack(current.token());current=null;return;}
        if(images==null)images=new HudTextureCache(c);if(text==null)text=new CodexTypography(c);
        float alpha=Math.min(1,Math.min(age/240f,(4500-age)/600f));int tint=((int)(255*alpha)<<24)|0xFFFFFF;
        int width=c.getWindow().getScaledWidth(),height=c.getWindow().getScaledHeight();float scale=Math.min(width/1920f,height/1080f)*.38f;
        var customization=HudCustomization.begin(ctx,"discovery",width/2f-1040*scale,height*.025f,2048*scale,623*scale);
        images.beginFrame();text.beginFrame();ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(width/2f-1040*scale,height*.025f-145*scale-(1-alpha)*12*scale,350);ctx.getMatrices().scale(scale,scale,1);
            images.drawTexture(ctx,PANEL,0,0,0,0,2048,768,2048,768,2048,768,tint);
            images.drawCentered(ctx,GimmickSpellIcons.resolve(current.spell(),MobSpellIcons.resolve(current.spell(),current.icon())),618,382,305,tint);
            String name=current.name();float size=Math.min(92,92*770/Math.max(1,text.width(name,92,FONT)));
            text.draw(ctx,current.first()?"최초로 발견한 마법":"새로운 마법 발견",1235,308,40,((int)(255*alpha)<<24)|0xEBD083,BODY,true);
            text.draw(ctx,name,1235,405,size,tint,FONT,true);
            text.draw(ctx,"마법 도감에 기록되었습니다",1235,488,35,((int)(255*alpha)<<24)|0xBED5E8,BODY,true);
            for(int i=0;i<4;i++){double a=age*.0007+i*Math.PI/2;float x=618+(float)Math.cos(a)*206,y=382+(float)Math.sin(a)*206;HudMesh.star(ctx,x,y,6+2*(float)Math.sin(age*.006+i),((int)(170*alpha)<<24)|0xBDF8F3);}
        }finally{ctx.getMatrices().pop();images.endFrame();customization.close();}
    }
}
