package school.magiccodex.client;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.resource.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.PartyHudProtocol;
import school.magiccodex.protocol.PartyHudProtocol.Member;
import static school.magiccodex.client.PartyHudLayout.*;

/**
 * 파티 위젯. Velocity 파티 플러그인이 보내는 실제 파티(한글 닉네임·기숙사·체력·마나)를 그린다.
 * Ctrl(HUD 커서)를 누른 동안: 끌어서 이동, 오른쪽 아래 모서리를 끌어 크기 조절, 우클릭으로 초기화,
 * 체력·마나 막대에 마우스를 올리면 수치 팝업. 위치·크기는 config/magiccodex/party-hud.json 에 저장.
 */
public final class PartyHudClient {
    private static final Identifier BODY_FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("magiccodex/party-hud.json");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final long STALE_MILLIS=12_000;
    private record Bar(Identifier id,int width,int height){}
    private static final Bar TRACK=bar("bar_track",725),HEALTH_BAR=bar("health_bar",886),MANA_BAR=bar("mana_bar",768);
    private static Bar bar(String name,int width){return new Bar(Identifier.of("magiccodex","textures/gui/party/"+name+".png"),width,36);}

    /** 저장되는 배치. */
    static final class Settings { float x=DEFAULT_X,y=DEFAULT_Y,scale=DEFAULT_SCALE;boolean enabled=true;boolean defaultAnchor=true; }
    private static Settings settings;
    private static List<Member> members=List.of();
    private static long received;
    /** 막대가 부드럽게 따라가는 표시 값과, 맞았을 때 잠깐 남는 잔상. */
    private static final class Anim { float health=-1,mana=-1,trail=-1;long trailHold; }
    private static final Map<UUID,Anim> anims=new HashMap<>();
    private static long lastFrame;
    private record Gesture(boolean resize,double mouseX,double mouseY,float x,float y,float scale,float ox,float oy){}
    private static Gesture gesture;
    private static HudTextureCache images;private static CodexTypography text;private static int generation=-1;

    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID=new Id<>(Identifier.of(PartyHudProtocol.CHANNEL));
        public static final PacketCodec<RegistryByteBuf,Payload> CODEC=new PacketCodec<>(){
            public Payload decode(RegistryByteBuf b){int size=b.readableBytes();if(size>PartyHudProtocol.MAX_BYTES){b.skipBytes(size);return new Payload(new byte[0]);}byte[] data=new byte[size];b.readBytes(data);return new Payload(data);}
            public void encode(RegistryByteBuf b,Payload value){b.writeBytes(value.bytes());}
        };
        public Id<Payload> getId(){return ID;}
    }
    private PartyHudClient(){}

    static void initialize(){
        PayloadTypeRegistry.playS2C().register(Payload.ID,Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID,(payload,context)->{
            try{var decoded=PartyHudProtocol.decode(payload.bytes());context.client().execute(()->receive(decoded));}
            catch(IllegalArgumentException ignored){/* 잘못된 패킷은 통째로 버린다. */}
        });
        // 서버 이동 때 접속이 다시 맺어져도 Velocity가 1초 뒤 처음부터 다시 보낸다.
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
        HudRenderCallback.EVENT.register((ctx,ticks)->render(ctx));
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){
            public Identifier getFabricId(){return Identifier.of("magiccodex","party_hud");}
            public void reload(ResourceManager manager){close();}
        });
    }
    /** C03: the player is in my party (last roster from Velocity, not stale). Aim assist skips party members. */
    static boolean member(java.util.UUID id){
        if(id==null||members.isEmpty()||received!=0&&Util.getMeasuringTimeMs()-received>STALE_MILLIS)return false;
        for(var m:members)if(id.equals(m.id()))return true;
        return false;
    }
    private static void receive(List<Member> next){members=next;received=Util.getMeasuringTimeMs();anims.keySet().retainAll(next.stream().map(Member::id).toList());}
    private static void reset(){members=List.of();received=0;anims.clear();gesture=null;close();}
    private static void close(){if(images!=null){images.close();images=null;}if(text!=null){text.close();text=null;}}

    // ---------- 저장 ----------
    static Settings settings(){
        if(settings==null){
            settings=new Settings();
            try{if(Files.exists(FILE)){var loaded=JSON.fromJson(Files.readString(FILE,StandardCharsets.UTF_8),Settings.class);if(loaded!=null){settings=loaded;var raw=JsonParser.parseString(Files.readString(FILE,StandardCharsets.UTF_8)).getAsJsonObject();if(!raw.has("defaultAnchor"))settings.defaultAnchor=false;}}}
            catch(Exception malformed){settings=new Settings();}
            settings.scale=clampScale(settings.scale);
            if(!Float.isFinite(settings.x)||settings.x<0||settings.x>1)settings.x=DEFAULT_X;
            if(!Float.isFinite(settings.y)||settings.y<0||settings.y>1)settings.y=DEFAULT_Y;
        }
        return settings;
    }
    private static void save(){
        try{
            Files.createDirectories(FILE.getParent());var tmp=FILE.resolveSibling(FILE.getFileName()+".tmp");
            Files.writeString(tmp,JSON.toJson(settings()),StandardCharsets.UTF_8);
            try{Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Party HUD layout save failed",e);}
    }

    /** /화면표시 파티 켜기|끄기|초기화 */
    static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> command(){
        var root=ClientCommandManager.literal("파티");
        root.executes(ctx->{ctx.getSource().sendFeedback(Text.literal("/화면표시 파티 켜기 · 끄기 · 초기화  ("+UiKeys.cursorHint()+" 끌어서 이동, 오른쪽 아래 모서리로 크기 조절)"));return 1;});
        root.then(ClientCommandManager.literal("켜기").executes(ctx->{settings().enabled=true;save();ctx.getSource().sendFeedback(Text.literal("파티 창을 켰습니다."));return 1;}));
        root.then(ClientCommandManager.literal("끄기").executes(ctx->{settings().enabled=false;gesture=null;save();ctx.getSource().sendFeedback(Text.literal("파티 창을 껐습니다."));return 1;}));
        root.then(ClientCommandManager.literal("초기화").executes(ctx->{resetLayout();ctx.getSource().sendFeedback(Text.literal("파티 창 위치와 크기를 처음대로 돌렸습니다."));return 1;}));
        return root;
    }
    private static void resetLayout(){var s=settings();s.x=DEFAULT_X;s.y=DEFAULT_Y;s.scale=DEFAULT_SCALE;s.defaultAnchor=true;gesture=null;save();}

    // ---------- 화면 계산 ----------
    private record Frame(float screenW,float screenH,float scale,float ox,float oy,int count){
        float lx(double mx){return (float)((mx-ox)/scale);}
        float ly(double my){return (float)((my-oy)/scale);}
    }
    private static boolean visible(MinecraftClient c){
        if(!settings().enabled||c.player==null||c.world==null||c.options.hudHidden||c.getOverlay()!=null||members.isEmpty())return false;
        if(received!=0&&Util.getMeasuringTimeMs()-received>STALE_MILLIS)return false;
        return c.currentScreen==null||c.currentScreen instanceof HudCursorScreen||c.currentScreen instanceof ChatScreen;
    }
    private static Frame frame(MinecraftClient c){
        var w=c.getWindow();float sw=w.getScaledWidth(),sh=w.getScaledHeight();var s=settings();
        float scale=PartyHudLayout.fittedScale(sw,sh,s.scale,members.size());int count=members.size();
        return new Frame(sw,sh,scale,s.defaultAnchor?defaultOriginX(sw,scale):originX(s.x,sw,scale),s.defaultAnchor?defaultOriginY(sh,scale,count):originY(s.y,sh,scale,count),count);
    }
    private static double mouseX(MinecraftClient c){var w=c.getWindow();return c.mouse.getX()*w.getScaledWidth()/w.getWidth();}
    private static double mouseY(MinecraftClient c){var w=c.getWindow();return c.mouse.getY()*w.getScaledHeight()/w.getHeight();}

    // ---------- Ctrl 커서 조작 (HudCursorScreen 에서 호출) ----------
    static boolean click(double x,double y,int button){return false;}
    static boolean release(double x,double y,int button){
        if(gesture==null||button!=0)return false;
        apply(MinecraftClient.getInstance(),x,y);finish();return true;
    }
    /** Ctrl을 놓아 커서 화면이 닫힐 때. */
    static void cursorClosed(){finish();}
    private static void finish(){if(gesture!=null){gesture=null;save();}}
    private static void apply(MinecraftClient c,double mx,double my){
        var g=gesture;if(g==null)return;var s=settings();var w=c.getWindow();float sw=w.getScaledWidth(),sh=w.getScaledHeight();
        if(g.resize()){s.scale=resized(g.scale(),g.ox(),g.oy(),g.mouseX(),g.mouseY(),mx,my);return;}
        float scale=PartyHudLayout.fittedScale(sw,sh,s.scale,members.size());
        // 저장 값도 화면 안쪽으로 맞춰 둔다 (해상도가 바뀌어도 위젯이 사라지지 않게).
        s.x=Math.clamp(g.x()+(float)((mx-g.mouseX())/sw),0,Math.max(0,1-WIDTH*scale/sw));
        s.y=Math.clamp(g.y()+(float)((my-g.mouseY())/sh),0,Math.max(0,1-height(members.size())*scale/sh));
    }
    private static void sound(MinecraftClient c){c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.15f,.23f));}

    // ---------- 그리기 ----------
    private static void render(DrawContext ctx){
        var c=MinecraftClient.getInstance();long now=Util.getMeasuringTimeMs();
        float dt=lastFrame==0?0:Math.min(.1f,(now-lastFrame)/1000f);lastFrame=now;
        if(!visible(c)){if(gesture!=null)finish();return;}
        boolean cursor=c.currentScreen instanceof HudCursorScreen,pointer=cursor||c.currentScreen instanceof ChatScreen;
        if(gesture!=null){
            if(!cursor||GLFW.glfwGetMouseButton(c.getWindow().getHandle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)!=GLFW.GLFW_PRESS)finish();
            else apply(c,mouseX(c),mouseY(c));
        }
        if(UiResources.generation()!=generation){close();generation=UiResources.generation();}
        if(images==null)images=new HudTextureCache(c);if(text==null)text=new CodexTypography(c);
        images.beginFrame();text.beginFrame();
        var f=frame(c);
        try(var customization=HudCustomization.begin(ctx,"party",f.ox()-8*f.scale(),f.oy()-6*f.scale(),(WIDTH+16)*f.scale(),(height(f.count())+12)*f.scale())){
        double mx=HudPointer.x(),my=HudPointer.y();float lx=f.lx(mx),ly=f.ly(my);
        int hot=pointer?hit(lx,ly,f.count()):NONE;
        var self=c.player.getUuid();
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(f.ox(),f.oy(),150);ctx.getMatrices().scale(f.scale(),f.scale(),1);
            float h=height(f.count());
            if(cursor){
                // Ctrl 모드: 잡을 수 있는 영역과 크기 조절 모서리를 보여 준다.
                boolean active=gesture!=null||hot!=NONE;
                HudMesh.quad(ctx,-8,-6,WIDTH+16,h+12,active?0x3A0C1F32:0x260C1F32,active?0x3A0C1F32:0x260C1F32);
                outline(ctx,-8,-6,WIDTH+16,h+12,active?0xCCE7CE91:0x66E7CE91);
                int grip=gesture!=null&&gesture.resize()||hot==GRIP_HIT?0xFFE7CE91:0x99E7CE91;
                for(int i=0;i<3;i++)HudMesh.line(ctx,WIDTH+4-i*6,h+2,WIDTH+4,h+2-i*6,1.6f,grip);
            }
            header(ctx);
            for(int i=0;i<f.count();i++)row(ctx,members.get(i),rowTop(i),self,dt,now);
            if(pointer&&(hot==HEALTH||hot==MANA)&&gesture==null){
                int r=PartyHudLayout.row(ly,f.count());if(r>=0)tooltip(ctx,f,members.get(r),hot==HEALTH,lx,ly);
            }else if(cursor&&gesture==null&&hot!=NONE){
                text.drawOutline(ctx,"끌어서 이동 · 모서리로 크기 · 우클릭 초기화",0,h+20,15,0xCC000000,BODY_FONT,false);
                text.draw(ctx,"끌어서 이동 · 모서리로 크기 · 우클릭 초기화",0,h+20,15,0xFFE7CE91,BODY_FONT,false);
            }
        }finally{ctx.getMatrices().pop();images.endFrame();}
        }
    }
    private static void header(DrawContext ctx){
        images.drawTexture(ctx,Identifier.of("magiccodex","textures/gui/party/house_star.png"),0,9,0,0,26,26,128,128,128,128,0xFFFFFFFF);
        label(ctx,"파티",34,22,24,0xFFFFFFFF,BOLD);
        float end=34+text.width("파티",24,BOLD)+10;
        HudMesh.line(ctx,end,22,WIDTH-60,22,1.6f,0xFFD8BB7A);
        HudMesh.line(ctx,WIDTH-65,22,WIDTH-60,17,1.6f,0xFFD8BB7A);HudMesh.line(ctx,WIDTH-60,17,WIDTH-55,22,1.6f,0xFFD8BB7A);
        HudMesh.line(ctx,WIDTH-55,22,WIDTH-60,27,1.6f,0xFFD8BB7A);HudMesh.line(ctx,WIDTH-60,27,WIDTH-65,22,1.6f,0xFFD8BB7A);
    }
    private static void row(DrawContext ctx,Member m,float top,UUID self,float dt,long now){
        boolean live=m.online()&&m.known();int alpha=m.online()?0xFF:0x77;int tint=(alpha<<24)|0xFFFFFF;
        String icon=PartyHudLayout.icon(m.house());
        if(icon!=null)images.drawTexture(ctx,Identifier.of("magiccodex","textures/gui/party/"+icon+".png"),(int)(26-ICON/2),(int)(top+38-ICON/2),0,0,(int)ICON,(int)ICON,128,128,128,128,tint);
        else HudMesh.star(ctx,26,top+38,14,(alpha<<24)|0xE7CE91);
        // 이름: 길면 줄인다. 파티장은 이름 뒤 작은 별, 나는 금색.
        float room=barWidth()-(m.leader()?24:0)-(m.online()?0:70);
        String name=fit(m.nickname(),21,room);
        int nameColor=!m.online()?0xFF9AA3AB:m.id().equals(self)?0xFFF6E3B0:0xFFFFFFFF;
        label(ctx,name,BAR_X,top+12,21,nameColor,BOLD);
        float after=BAR_X+text.width(name,21,BOLD)+4;
        if(m.leader())HudMesh.star(ctx,after+9,top+12,7,(alpha<<24)|0xE7CE91);
        if(!m.online()){text.draw(ctx,"오프라인",WIDTH-text.width("오프라인",15,BODY_FONT),top+13,15,0xFF8A949C,BODY_FONT,false);}
        var a=anims.computeIfAbsent(m.id(),k->new Anim());
        float hp=live?fraction(m.health(),m.maxHealth()):0,mp=live&&m.manaKnown()?fraction(m.mana(),m.maxMana()):0;
        if(a.health<0){a.health=hp;a.trail=hp;a.mana=mp;}
        float k=Math.min(1,dt*12);a.health+=(hp-a.health)*k;a.mana+=(mp-a.mana)*k;
        // 맞으면 잔상이 0.45초 머물다 줄어든다. 회복은 바로 따라간다.
        if(hp>=a.trail){a.trail=hp;a.trailHold=now+450;}else if(now>a.trailHold)a.trail=Math.max(hp,a.trail-dt*.9f);
        int w=(int)barWidth();
        nine(ctx,TRACK,(int)BAR_X,(int)(top+HEALTH_Y),w,(int)HEALTH_H,1f,tint);
        if(live&&a.trail>a.health+.002f)nine(ctx,HEALTH_BAR,(int)BAR_X,(int)(top+HEALTH_Y),w,(int)HEALTH_H,a.trail,0x80FFFFFF);
        if(live)nine(ctx,HEALTH_BAR,(int)BAR_X,(int)(top+HEALTH_Y),w,(int)HEALTH_H,a.health,0xFFFFFFFF);
        nine(ctx,TRACK,(int)BAR_X,(int)(top+MANA_Y),w,(int)MANA_H,1f,tint);
        if(live&&m.manaKnown())nine(ctx,MANA_BAR,(int)BAR_X,(int)(top+MANA_Y),w,(int)MANA_H,a.mana,0xFFFFFFFF);
        if(m.online()&&!m.known())text.draw(ctx,"정보 받는 중",BAR_X+w/2f,top+HEALTH_Y+HEALTH_H/2,12,0xCCA5B8C8,BODY_FONT,true);
    }
    private static void tooltip(DrawContext ctx,Frame f,Member m,boolean health,float lx,float ly){
        String value;
        if(!m.online())value="오프라인";
        else if(!m.known())value="정보 받는 중";
        else if(health)value="체력  "+number(m.health())+" / "+number(m.maxHealth());
        else value=m.manaKnown()?"마나  "+number(m.mana())+" / "+number(m.maxMana()):"마나 정보 없음";
        float size=17,tw=text.width(value,size,BODY_FONT)+24,th=34;
        float x=lx+14,y=ly-th-6;
        if(f.ox()+(x+tw)*f.scale()>f.screenW())x=lx-14-tw;
        if(f.oy()+y*f.scale()<0)y=ly+18;
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(0,0,20);
            int edge=health?0xFFE36571:0xFF3FD0E8;
            HudMesh.capsule(ctx,x,y,tw,th,edge,edge);
            HudMesh.capsule(ctx,x+1.5f,y+1.5f,tw-3,th-3,0xF2102536,0xF20C1F32);
            text.draw(ctx,value,x+tw/2,y+th/2,size,0xFFF0F3F5,BODY_FONT,true);
        }finally{ctx.getMatrices().pop();}
    }
    /** 9조각 막대: 양 끝 둥근 부분은 그대로, 가운데만 늘린다. fraction<1 이면 왼쪽부터 그 비율만. */
    private static void nine(DrawContext ctx,Bar bar,int x,int y,int w,int h,float fraction,int tint){
        int fill=Math.round(w*Math.clamp(fraction,0,1));if(fill<=0)return;
        int cap=Math.max(1,h/2),src=bar.height()/2;
        if(fill<=cap){
            // 아주 적을 때: 왼쪽 끝 조각만 잘라 쓴다 (잘린 폭은 정수 단계라 캐시가 늘지 않는다).
            int sw=Math.max(1,Math.round(src*fill/(float)cap));
            images.drawTexture(ctx,bar.id(),x,y,0,0,fill,h,sw,bar.height(),bar.width(),bar.height(),tint);return;
        }
        images.drawTexture(ctx,bar.id(),x,y,0,0,cap,h,src,bar.height(),bar.width(),bar.height(),tint);
        int middle=fill-2*cap;
        if(middle>0)images.drawTexture(ctx,bar.id(),x+cap,y,bar.width()/2-8,0,middle,h,16,bar.height(),bar.width(),bar.height(),tint);
        // 채움이 끝 조각 두 개보다 짧으면 오른쪽 끝을 겹쳐 그려 둥근 끝을 유지한다.
        images.drawTexture(ctx,bar.id(),x+fill-cap,y,bar.width()-src,0,cap,h,src,bar.height(),bar.width(),bar.height(),tint);
    }
    private static void label(DrawContext ctx,String value,float x,float y,float size,int color,Identifier face){
        text.drawOutline(ctx,value,x,y,size,(color&0xFF000000)|0x14161C,face,false);
        text.draw(ctx,value,x,y,size,color,face,false);
    }
    private static String fit(String value,float size,float room){
        if(text.width(value,size,BOLD)<=room)return value;
        var cps=value.codePoints().toArray();
        for(int n=cps.length-1;n>0;n--){String s=new String(cps,0,n)+"…";if(text.width(s,size,BOLD)<=room)return s;}
        return "…";
    }
    private static void outline(DrawContext ctx,float x,float y,float w,float h,int color){
        HudMesh.line(ctx,x,y,x+w,y,1.2f,color);HudMesh.line(ctx,x+w,y,x+w,y+h,1.2f,color);
        HudMesh.line(ctx,x+w,y+h,x,y+h,1.2f,color);HudMesh.line(ctx,x,y+h,x,y,1.2f,color);
    }
}
