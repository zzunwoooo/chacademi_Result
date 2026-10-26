package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.AscensionProtocol;
import school.magiccodex.protocol.AscensionProtocol.Response;

/** Four cached PNGs + bounded meshes. All movement and front/back layering is local. */
public final class AscensionScreen extends Screen {
    private static final Identifier AVATAR=asset("mana_avatar"),ORB=asset("crystal_orb"),CIRCLE=asset("arcane_circle"),STAR=asset("star_node");
    private static final Identifier FONT=Identifier.of("magiccodex","hud_bold"),BODY=Identifier.of("magiccodex","label");
    private final boolean preview;
    private Response offer;
    private HudTextureCache images;
    private CodexTypography typography;
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    private long start=-1,waitingAt=-1,closingAt=-1;
    private boolean playing,arrivalSound,hovered;
    private String notice="";
    private double visualTime=-1;
    private float scale,left,top;
    public AscensionScreen(Response response,boolean preview){super(Text.literal("클래스 승급"));this.offer=response;this.preview=preview;playing=preview||response.action()==AscensionProtocol.SUCCESS;}
    public static AscensionScreen preview(int to){if(to<2||to>9)throw new IllegalArgumentException();return new AscensionScreen(new Response(AscensionProtocol.OFFER,0,to-1,to,true,""),true);}
    public long token(){return offer.token();}
    public void receive(Response r){
        waitingAt=-1;offer=r;
        if(r.action()==AscensionProtocol.SUCCESS){playing=true;start=-1;arrivalSound=false;notice="";}
        else notice=r.message();
    }
    @Override protected void init(){images=UiResources.images();typography=UiResources.text();}
    private static Identifier asset(String name){return Identifier.of("magiccodex","textures/gui/ascension/"+name+".png");}
    private void layout(){scale=Math.min(width/1440f,height/810f)*.94f;left=(width-1440*scale)/2;top=(height-810*scale)/2;}
    private static int alpha(int rgb,float a){return Math.round(Math.clamp(a,0,1)*255)<<24|(rgb&0xFFFFFF);}
    private void sprite(DrawContext c,Identifier id,float x,float y,int w,int h,float rotation,float opacity){
        c.getMatrices().push();c.getMatrices().translate(x,y,0);c.getMatrices().multiply(new Quaternionf().rotateZ(rotation));
        int tw=id.equals(AVATAR)?1024:1254,th=id.equals(AVATAR)?1536:1254;
        images.drawTexture(c,id,-w/2,-h/2,0,0,w,h,tw,th,tw,th,alpha(0xFFFFFF,opacity));c.getMatrices().pop();
    }
    private void layer(DrawContext c,int z){c.draw();c.getMatrices().translate(0,0,z);}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        images=UiResources.images();typography=UiResources.text();layout();
        images.beginFrame();typography.beginFrame();long now=Util.getMeasuringTimeMs();
        if(entrance.ready()&&playing&&start<0){start=now;sound("light.ritual",.4f,.82f);}
        double age=visualTime>=0?visualTime:start<0?0:(now-start)/1000.0;
        double time=playing?age:now/1000.0;
        if(playing&&age>=AscensionMotion.ARRIVAL&&!arrivalSound&&entrance.ready()){arrivalSound=true;sound("spell_discovered",.55f,.9f);}
        float fade=closingAt<0?1:1-AscensionMotion.smooth((now-closingAt)/280.0);
        if(closingAt>=0&&now-closingAt>=280){super.close();return;}
        if(waitingAt>=0&&now-waitingAt>8000){waitingAt=-1;notice="서버 응답이 지연되고 있습니다. 닫은 뒤 /클래스승급으로 다시 확인해 주세요.";}
        c.fill(0,0,width,height,alpha(0x050C19,.83f*fade));
        c.getMatrices().push();
        try{
            c.getMatrices().translate(left,top,50);c.getMatrices().scale(scale,scale,1);
            AscensionVeil.draw(c,time,offer.to(),fade,false);
            sprite(c,CIRCLE,438,376,704,704,(float)(time*.027),.26f*fade);
            sprite(c,CIRCLE,438,376,584,584,(float)(-time*.043),.13f*fade);
            layer(c,1);stars(c,time,age,false,fade);
            sprite(c,AVATAR,413,405,508,762,0,.91f*fade);
            layer(c,1);AscensionVeil.draw(c,time,offer.to(),fade*.48f,true);
            // Elliptical orbit is a separate texture and can rotate independently of the robe.
            c.getMatrices().push();c.getMatrices().translate(470,351,0);c.getMatrices().multiply(new Quaternionf().rotateZ(-.20f));c.getMatrices().scale(1,.31f,1);
            sprite(c,CIRCLE,0,0,422,422,(float)(time*.10),.24f*fade);c.getMatrices().pop();
            float pulse=(float)(.92+.06*Math.sin(time*2.3));
            sprite(c,ORB,470,351,112,112,0,pulse*fade);
            sprite(c,ORB,646+(float)Math.sin(time*.33)*9,207+(float)Math.sin(time*.52)*12,108,108,(float)(Math.sin(time*.23)*.1),.85f*fade);
            layer(c,1);stars(c,time,age,true,fade);
            if(playing){
                float burst=AscensionMotion.burst(age)*fade;
                sprite(c,STAR,470,351,Math.round(180+170*burst),Math.round(180+170*burst),0,burst*.8f);
                if(age>3.2&&age<4.8){float p=AscensionMotion.smooth((age-3.2)/1.6);HudMesh.arc(c,470,351,80+p*190,1.8f,0,(float)(Math.PI*2),alpha(0xA7EAEC,(1-p)*.65f*fade));}
            }
            layer(c,1);
            label(c,"클래스 승급",1070,260,27,0xE1CCA2,fade,BODY);
            boolean completed=playing&&age>=AscensionMotion.COMPLETE;
            label(c,offer.from()==9?"클래스 9":"클래스 "+offer.from()+"  →  클래스 "+offer.to(),1070,350,55,0xF1D79D,fade,FONT);
            HudMesh.line(c,892,406,1248,406,1,alpha(0xB49C6C,.45f*fade));
            String subtitle=playing?(completed?"새로운 별이 마나 클래스에 합류했습니다.":age<1.25?"마력이 심장부로 모이고 있습니다.":"새로운 별이 깨어나고 있습니다."):offer.message();
            label(c,subtitle,1070,445,22,0xBDCDDC,fade,BODY);
            double mx=(mouseX-left)/scale,my=(mouseY-top)/scale;
            boolean buttonVisible=!playing||completed;
            boolean hover=buttonVisible&&mx>=943&&mx<=1197&&my>=520&&my<=580;
            if(hover&&!hovered)sound("light.chime",.07f,1.5f);hovered=hover;
            if(buttonVisible){
                HudMesh.capsule(c,943,520,254,60,alpha(hover?0x72CCD9:0x9C926D,.65f*fade),alpha(hover?0x72CCD9:0x9C926D,.65f*fade));
                HudMesh.capsule(c,945,522,250,56,alpha(hover?0x20495D:0x122E40,.95f*fade),alpha(0x152B3A,.95f*fade));
                label(c,waitingAt>=0?"확인 중…":playing?"확인":offer.allowed()&&notice.isEmpty()?"승급하기":"닫기",1070,550,25,0xECF0EE,fade,FONT);
            }
            if(!notice.isEmpty())drawNotice(c,notice,fade);
            label(c,preview?"연출 미리보기 · 실제 클래스는 변경되지 않습니다":"ESC 닫기",1070,650,18,0x839BAE,fade,BODY);
            c.draw();
        }finally{c.getMatrices().pop();images.endFrame();}
        entrance.draw(c,width,height);
    }
    private void drawNotice(DrawContext c,String value,float fade){
        // Bounded two-line notice; fixed strings, no server-provided layout or fonts.
        int mid=value.length()>32?value.lastIndexOf(' ',32):-1;
        if(mid>0){label(c,value.substring(0,mid),1070,604,18,0xD7BDA6,fade,BODY);label(c,value.substring(mid+1),1070,627,18,0xD7BDA6,fade,BODY);}
        else label(c,value,1070,615,18,0xD7BDA6,fade,BODY);
    }
    private void stars(DrawContext c,double time,double age,boolean front,float fade){
        for(int i=0;i<offer.to();i++){
            boolean incoming=i>=offer.from();
            if(incoming&&!playing)continue;
            float progress=incoming?AscensionMotion.reveal(age):1;
            if(incoming&&age<1.25)continue;
            float z=AscensionMotion.depth(i,time);
            if((z>=0)!=front)continue;
            float x=AscensionMotion.x(i,time),y=AscensionMotion.y(i,time);
            if(incoming){x=730+(x-730)*progress;y=140+(y-140)*progress-(float)Math.sin(progress*Math.PI)*88;}
            int size=Math.round((63+z*12)*(incoming?1.15f:1));
            if(incoming&&progress<1){
                for(int j=1;j<=9;j++){float p=AscensionMotion.reveal(age-j*.035);float tx=730+(AscensionMotion.x(i,time)-730)*p,ty=140+(AscensionMotion.y(i,time)-140)*p-(float)Math.sin(p*Math.PI)*88;HudMesh.disk(c,tx,ty,3-j*.22f,alpha(0xBAE8F4,(1-j/10f)*.28f*fade));}
            }
            sprite(c,STAR,x,y,size,size,0,fade*(.7f+.3f*(z+1)/2)*Math.min(1,progress*3));
        }
    }
    private void label(DrawContext c,String s,float x,float y,float size,int rgb,float opacity,Identifier font){
        float fit=Math.min(size,size*520/Math.max(1,typography.width(s,size,font)));typography.draw(c,s,x,y,fit,alpha(rgb,opacity),font,true);
    }
    private void sound(String name,float volume,float pitch){if(visualTime<0)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex",name)),pitch,volume));}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0||closingAt>=0)return false;layout();double mx=(x-left)/scale,my=(y-top)/scale;
        double age=start<0?0:(Util.getMeasuringTimeMs()-start)/1000.0;
        if(mx>=943&&mx<=1197&&my>=520&&my<=580&&(!playing||age>=AscensionMotion.COMPLETE)&&entrance.ready()){
            if(playing||!offer.allowed()||!notice.isEmpty())close();
            else if(waitingAt<0){waitingAt=Util.getMeasuringTimeMs();AscensionClient.claim(offer.token());}
            return true;
        }return false;
    }
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}return super.keyPressed(key,scan,mods);}
    @Override public void close(){if(closingAt<0)closingAt=Util.getMeasuringTimeMs();}
    @Override public void tick(){if(visualTime<0&&(client.player==null||client.world==null||!client.player.isAlive()))super.close();}
    @Override public boolean shouldPause(){return false;}
    /** Deterministic screenshot fixture only; no server state is modified. */
    public void visualTime(double value){visualTime=value;}
    public boolean ready(){return entrance.ready();}
    public void verifyRenderer(){images.verifyGpuState();}
}
