package school.magiccodex.client;

import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.*;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.world.ClientWorld;
import school.magiccodex.protocol.TamingProtocol;
import school.magiccodex.protocol.TamingProtocol.State;

/** Smooth local interpolation; only the server can decide eligibility, odds or results. */
public final class TamingClient {
    private TamingClient(){}
    private static ClientWorld stateWorld, observedWorld;
    private static TamingProtocol.Anchor serverAnchor;
    private static school.magiccodex.protocol.MonsterTargetProtocol.Target monster;
    private static State state;private static long received,nextQuery,nextLoop,lastToken;private static int lastStatus=-1,generation=-1;
    private static HudTextureCache images;private static CodexTypography text;private static PositionedSoundInstance looping;
    private static final UUID NONE=new UUID(0,0);
    private static final Identifier FONT=Identifier.of("magiccodex","hud_bold"),BODY=Identifier.of("magiccodex","label");
    private interface Bytes{byte[] bytes();}
    public record Request(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Request> ID=new Id<>(Identifier.of(TamingProtocol.REQUEST));static final PacketCodec<RegistryByteBuf,Request> CODEC=codec(Request::new);public Id<Request> getId(){return ID;}
    }
    public record Response(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Response> ID=new Id<>(Identifier.of(TamingProtocol.RESPONSE));static final PacketCodec<RegistryByteBuf,Response> CODEC=codec(Response::new);public Id<Response> getId(){return ID;}
    }
    public record AnchoredResponse(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<AnchoredResponse> ID=new Id<>(Identifier.of(TamingProtocol.ANCHORED_RESPONSE));
        static final PacketCodec<RegistryByteBuf,AnchoredResponse> CODEC=codec(AnchoredResponse::new);
        public Id<AnchoredResponse> getId(){return ID;}
    }
    public record MonsterResponse(byte[] bytes)implements CustomPayload,Bytes{static final Id<MonsterResponse>ID=new Id<>(Identifier.of(school.magiccodex.protocol.MonsterTargetProtocol.CHANNEL));static final PacketCodec<RegistryByteBuf,MonsterResponse>CODEC=codec(MonsterResponse::new);public Id<MonsterResponse>getId(){return ID;}}
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){if(b.readableBytes()>TamingProtocol.MAX_BYTES){b.skipBytes(b.readableBytes());return f.apply(new byte[0]);}byte[] a=new byte[b.readableBytes()];b.readBytes(a);return f.apply(a);}
        public void encode(RegistryByteBuf b,T value){b.writeBytes(value.bytes());}
    };}
    public static void initialize(){
        PayloadTypeRegistry.playS2C().register(MonsterResponse.ID,MonsterResponse.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(MonsterResponse.ID,(payload,context)->{try{var value=school.magiccodex.protocol.MonsterTargetProtocol.decode(payload.bytes());var world=context.client().world;context.client().execute(()->{if(world!=null&&world==context.client().world){accept(value.target().state(),value.target().anchor());monster=value;}});}catch(IllegalArgumentException ignored){}});

        PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC);PayloadTypeRegistry.playS2C().register(Response.ID,Response.CODEC);
        PayloadTypeRegistry.playS2C().register(AnchoredResponse.ID,AnchoredResponse.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID,(payload,context)->{try{var s=TamingProtocol.state(payload.bytes());var world=context.client().world;context.client().execute(()->{if(world!=null&&world==context.client().world)accept(s);});}catch(IllegalArgumentException ignored){}});
        ClientPlayNetworking.registerGlobalReceiver(AnchoredResponse.ID,(payload,context)->{try{
            var value=TamingProtocol.anchoredState(payload.bytes());var world=context.client().world;
            context.client().execute(()->{if(world!=null&&world==context.client().world)accept(value.state(),value.anchor());});
        }catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(TamingClient::tick);HudRenderCallback.EVENT.register((ctx,t)->render(ctx));
        // 2026-10-19: the taming card above the head is gone; the target HUD (TargetHudClient) shows name, HP and odds.
    }
    private static void accept(State s){accept(s,null);}
    private static void accept(State s,TamingProtocol.Anchor anchor){
        long n=Util.getMeasuringTimeMs();boolean changed=s.token()!=lastToken||s.status()!=lastStatus;
        monster=null;
        state=s;serverAnchor=anchor;stateWorld=MinecraftClient.getInstance().world;received=n;
        if(changed){stopLoop();if(s.status()==TamingProtocol.CHANNEL){sound("channel_start",.65f);nextLoop=n+450;}
            else if(s.status()==TamingProtocol.SUCCESS){sound("capture_success",.75f);PetClient.invalidateCatalog();}
            else if(s.status()==TamingProtocol.FAIL)sound("capture_fail",.65f);
        }
        lastToken=s.token();lastStatus=s.status();
    }
    private static boolean informationScreen(MinecraftClient c){
        return c.currentScreen==null || c.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen || c.currentScreen instanceof HudCursorScreen;
    }
    private static void tick(MinecraftClient c){
        long n=Util.getMeasuringTimeMs();if(c.player==null||c.world==null){reset();return;}
        if(observedWorld!=c.world){reset();observedWorld=c.world;}
        if(state!=null&&state.status()==TamingProtocol.CHANNEL&&n-received<1800){if(n>=nextLoop){stopLoop();looping=PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex","taming.channel_loop")),1,.3f);c.getSoundManager().play(looping);nextLoop=n+2400;}}
        else stopLoop();
        // 2026-10-19: no periodic QUERY any more. It only fed the removed head card; the server now pushes the
        // target HUD (magiccodex:target_hud). Casting and cancelling still use Request.
    }
    private static void sound(String name,float volume){MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex","taming."+name)),1,volume));}
    private static void stopLoop(){if(looping!=null){MinecraftClient.getInstance().getSoundManager().stop(looping);looping=null;}}
    private static void close(){if(images!=null){images.close();images=null;}if(text!=null){text.close();text=null;}}
    private static void reset(){monster=null;state=null;serverAnchor=null;stateWorld=observedWorld=null;received=nextQuery=nextLoop=lastToken=0;lastStatus=-1;stopLoop();close();}
    // The head card that covered the name tag is gone (2026-10-19), so vanilla names always show.
    public static boolean hidesVanillaName(int entityId){return false;}
    private static boolean targetRegistered(){return CastingClient.state().registered("rainbow_collar")||CastingClient.state().registered("taming");}
    public static void bindingsChanged(){

        nextQuery=0;
    }
    private static void panel(DrawContext ctx,String file,int x,int y,int w,int h,int sw,int sh){images.drawTexture(ctx,Identifier.of("magiccodex","textures/gui/taming/"+file+".png"),x,y,0,0,w,h,sw,sh,sw,sh,0xffffffff);}
    private static void label(DrawContext ctx,String value,float x,float y,float size,int color,boolean bold){text.draw(ctx,value.replaceAll("§[0-9a-fk-orA-FK-OR]",""),x,y,size,value.startsWith("§e")?0xffffff55:color,bold?FONT:BODY,true);}
    private static void render(DrawContext ctx){
        var c=MinecraftClient.getInstance();if(state==null||stateWorld!=c.world||state.status()==0||state.status()==TamingProtocol.TARGET||c.world==null||c.player==null||c.options.hudHidden||!HudPointer.hudVisibleUnder(c.currentScreen))return;
        long age=Util.getMeasuringTimeMs()-received;boolean target=state.status()==TamingProtocol.TARGET;
        if(age>(target?1200:state.status()==TamingProtocol.PENDING?15000:state.status()==TamingProtocol.CHANNEL?1800:2400))return;
        if(generation!=UiResources.generation()){close();generation=UiResources.generation();}if(images==null)images=new HudTextureCache(c,true);if(text==null)text=new CodexTypography(c);
        images.beginFrame();text.beginFrame();
        float hs=Math.max(.20f,Math.min(1f,Math.min(c.getWindow().getScaledWidth()/1100f,c.getWindow().getScaledHeight()/750f))*.78f);
        try(var customization=HudCustomization.begin(ctx,"taming",c.getWindow().getScaledWidth()/2f-240*hs,c.getWindow().getScaledHeight()*.68f,480*hs,88*hs)){ctx.getMatrices().push();
        try{
            int width=c.getWindow().getScaledWidth(),height=c.getWindow().getScaledHeight();float scale=Math.min(1f,Math.min(width/1100f,height/750f));scale=Math.max(.20f,scale*.78f);
            if(!target){ctx.getMatrices().translate(width/2f-240*scale,height*.68f,270);ctx.getMatrices().scale(scale,scale,1);panel(ctx,"channel_panel",0,0,480,88,480,88);images.drawCentered(ctx,"magiccodex:textures/gui/taming/taming_icon.png",45,44,50,0xffffffff);
                String message=state.message();float size=Math.min(21,21*360/Math.max(1,text.width(message,21,FONT)));label(ctx,message,270,27,size,0xffe2eef4,true);
                float progress=state.status()==TamingProtocol.CHANNEL?Math.clamp(1-(state.remainingMs()-age)/(float)Math.max(1,state.durationMs()),0,1):state.status()==TamingProtocol.SUCCESS||state.status()==TamingProtocol.PENDING?1:0;
                ctx.fill(88,54,448,62,0xff16313c);ctx.fill(88,54,88+(int)(360*progress),62,state.status()==TamingProtocol.FAIL?0xffb9818c:0xff79dce9);
                if(state.status()==TamingProtocol.SUCCESS){float flash=(float)Math.max(0,1-age/900f);for(int i=0;i<8;i++){double a=i*Math.PI/4;HudMesh.star(ctx,240+(float)Math.cos(a)*(70+age*.1f),44+(float)Math.sin(a)*(25+age*.05f),4+flash*5,((int)(200*flash)<<24)|0xb5faff);}}
            }
        }finally{ctx.getMatrices().pop();images.endFrame();}

        }
}
}
