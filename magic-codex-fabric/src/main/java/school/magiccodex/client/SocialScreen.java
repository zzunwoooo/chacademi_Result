package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.mixin.TextFieldAccessor;

/** Shared PNG filtering, typography, field editing and control drawing. */
abstract class SocialScreen extends Screen {
    static final int WHITE=0xFFF0F3F5,GOLD=0xFFE7CE91,MUTED=0xFFA5B8C8,CYAN=0xFF92E7F2;
    static final Identifier BODY=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    CodexTypography type;HudTextureCache images;boolean busy;
    String notice="";long noticeAt;private int lastHover;
    private int resourceGeneration=-1;
    final UiResources.Entrance entrance=new UiResources.Entrance();
    SocialScreen(String name){super(Text.literal(name));}
    abstract SocialLayout.Fit fit();
    @Override protected void init(){attachResources();}
    private void attachResources(){type=UiResources.text();images=UiResources.images();resourceGeneration=UiResources.generation();}
    void notice(String value){if(!value.isEmpty()){notice=value;noticeAt=Util.getMeasuringTimeMs();}}
    void start(DrawContext c){if(type==null||images==null||resourceGeneration!=UiResources.generation())attachResources();type.beginFrame();images.beginFrame();var f=fit();c.getMatrices().push();c.getMatrices().translate(f.x(),f.y(),0);c.getMatrices().scale(f.scale(),f.scale(),1);}
    void end(DrawContext c){c.getMatrices().pop();images.endFrame();entrance.draw(c,width,height);}
    void label(DrawContext c,String s,float x,float y,float size,int color,boolean bold){type.draw(c,s,x,y,size,color,bold?BOLD:BODY,false);}
    void center(DrawContext c,String s,float x,float y,float size,int color){type.draw(c,s,x,y,size,color,BODY,true);}
    void fitted(DrawContext c,String s,float x,float y,float size,float max,int color,boolean bold){var font=bold?BOLD:BODY;type.draw(c,s,x,y,Math.min(size,size*max/Math.max(1,type.width(s,size,font))),color,font,false);}
    void asset(DrawContext c,String file,int x,int y,int w,int h,int sx,int sy,int sw,int sh,int tint){images.drawTexture(c,Identifier.of("magiccodex","textures/gui/social/"+file+".png"),x,y,sx,sy,w,h,sw,sh,sw+sx,sh+sy,tint);}
    static boolean in(double x,double y,float rx,float ry,float w,float h){return x>=rx&&x<rx+w&&y>=ry&&y<ry+h;}
    boolean refinedPanels(){return this instanceof ShopScreen||this instanceof MailboxScreen||this instanceof ShopAdminScreen||this instanceof LootBoxAdminScreen;}
    void box(DrawContext c,int x,int y,int w,int h,boolean hover){if(!refinedPanels()){HudMesh.capsule(c,x,y,w,h,hover?0xB74BADC2:0x886A839A,hover?0xB74BADC2:0x886A839A);HudMesh.capsule(c,x+1,y+1,w-2,h-2,hover?0xEF16394B:0xE00C1F32,hover?0xEF16394B:0xE00C1F32);return;}chamfer(c,x,y,w,h,Math.min(10,h/4f),hover?0xFFC3CBA9:0xFF758A9C);chamfer(c,x+2,y+2,w-4,h-4,Math.min(8,h/4f),hover?0xF01B3B50:0xF0112436);}
    void chamfer(DrawContext c,float x,float y,float w,float h,float r,int color){
        float[] xs={x+r,x+w-r,x+w,x+w,x+w-r,x+r,x,x},ys={y,y,y+r,y+h-r,y+h,y+h,y+h-r,y+r};var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{var v=p.getBuffer(net.minecraft.client.render.RenderLayer.getGui());for(int i=0;i<8;i++){int j=(i+1)%8;v.vertex(m,x+w/2,y+h/2,0).color(color);v.vertex(m,xs[j],ys[j],0).color(color);v.vertex(m,xs[i],ys[i],0).color(color);v.vertex(m,x+w/2,y+h/2,0).color(color);}});
    }
    void button(DrawContext c,String name,int x,int y,int w,int h,boolean hover){box(c,x,y,w,h,hover);center(c,name,x+w/2f,y+h/2f,21,hover?CYAN:GOLD);}
    void hover(int id){if(id!=0&&id!=lastHover)sound(1.4f,.07f);lastHover=id;}
    void sound(float pitch,float volume){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),pitch,volume));}
    void toast(DrawContext c,int x,int y,int w){long age=Util.getMeasuringTimeMs()-noticeAt;if(notice.isEmpty()||age>5000)return;int h=refinedPanels()?54:42;box(c,x,y,w,h,false);fitted(c,notice,x+18,y+h/2f,refinedPanels()?26:21,w-36,WHITE,refinedPanels());}
    TextFieldWidget field(String name,int max,String value){var f=new TextFieldWidget(textRenderer,0,0,800,30,Text.literal(name));f.setMaxLength(max);f.setText(value);return f;}
    /** Vanilla editing/clipboard, with Pretendard rendering and horizontal cursor tracking. */
    final class Input {
        TextFieldWidget widget;int offset;float x,y,w,h;
        Input(String name,int max,String text){widget=field(name,max,text);}
        String text(){return widget.getText();}
        void focus(boolean v){widget.setFocused(v);}
        boolean focus(){return widget.isFocused();}
        void draw(DrawContext c,float x,float y,float w,float h,String hint){
            this.x=x;this.y=y;this.w=w;this.h=h;String s=text();int cursor=widget.getCursor();offset=Math.min(offset,cursor);
            while(offset<cursor&&type.width(s.substring(offset,cursor),23,BODY)>w-28)offset++;
            int end=s.length();while(end>offset&&type.width(s.substring(offset,end),23,BODY)>w-24)end--;
            if(s.isEmpty())label(c,hint,x+12,y+h/2,22,MUTED,false);
            else {
                int anchor=((TextFieldAccessor)widget).magiccodex$selectionEnd();int lo=Math.max(offset,Math.min(cursor,anchor)),hi=Math.min(end,Math.max(cursor,anchor));
                if(focus()&&lo<hi){float a=type.width(s.substring(offset,lo),23,BODY),b=type.width(s.substring(offset,hi),23,BODY);HudMesh.quad(c,x+12+a,y+7,b-a,h-14,0x8865A5C5,0x8865A5C5);}
                label(c,s.substring(offset,end),x+12,y+h/2,23,WHITE,false);
            }
            if(focus()&&Util.getMeasuringTimeMs()/500%2==0){float cx=x+12+type.width(s.substring(offset,cursor),23,BODY);HudMesh.line(c,cx,y+10,cx,y+h-10,1.4f,CYAN);}
        }
        void drawCentered(DrawContext c,float x,float y,float w,float h,String hint,int color){
            this.x=x;this.y=y;this.w=w;this.h=h;String value=text();offset=0;
            float size=Math.min(29,29*(w-24)/Math.max(1,type.width(value,29,BOLD)));
            float left=x+(w-type.width(value,size,BOLD))/2;
            type.draw(c,value.isEmpty()?hint:value,x+w/2,y+h/2,size,color,BOLD,true);
            if(focus()&&Util.getMeasuringTimeMs()/500%2==0){float cursor=left+type.width(value.substring(0,widget.getCursor()),size,BOLD);HudMesh.line(c,cursor,y+9,cursor,y+h-9,1.5f,CYAN);}
        }
        boolean click(double mx,double my){boolean hit=in(mx,my,x,y,w,h);focus(hit);if(hit){int at=offset;String s=text();while(at<s.length()&&type.width(s.substring(offset,at+1),23,BODY)<mx-x-12)at++;widget.setCursor(at,false);}return hit;}
        boolean key(int key,int scan,int mods){return widget.keyPressed(key,scan,mods);}
        boolean typed(char c,int mods){return widget.charTyped(c,mods);}
    }
    @Override public boolean shouldPause(){return false;}
    @Override public void tick(){if(client.player==null||client.world==null||!client.player.isAlive())close();}
    void release(){type=null;images=null;}
    @Override public void removed(){release();super.removed();}
    public void verifyRenderer(){images.verifyGpuState();}
}
