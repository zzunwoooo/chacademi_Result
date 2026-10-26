package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Approved atlas + dynamic font, one coordinate system for rendering and interaction.
 * Half the previous on-screen size; each slider shows its value in a framed box after the label that can be typed into.
 */
public final class SettingsScreen extends Screen {
    enum Page { HOME, EFFECT, CAMERA, GUI }
    /** Share of the screen the 1000x730 panel may use (was 0.9). */
    static final float SCREEN_SHARE = .46f;
    /** Slider geometry in panel units: the knob centre runs over the track's inner channel only. */
    static final float TRACK_X = SettingsSlider.TRACK_X, TRACK_W = SettingsSlider.TRACK_W;
    private final Screen parent;
    private final Page page;
    private final AimSettings camera;
    private float own=1,other=1,bloom=BLOOM_DEFAULT;
    /** Bloom row only when the installed PortableVFX exposes it (older builds keep the two opacity rows). */
    private boolean bloomAvailable;
    static final float BLOOM_DEFAULT=.4f;
    private String notice="";
    private int dragging=-1, editing=-1;
    private String buffer="";
    private long lastTick;
    private int lastStep=Integer.MIN_VALUE;
    private float scale,ox,oy;
    private double mx,my;
    private static final Identifier FONT=Identifier.of("magiccodex","hud_bold"), TITLE=Identifier.of("magiccodex","ui_extra_bold");
    public SettingsScreen(Screen parent){this(parent,Page.HOME);}
    private SettingsScreen(Screen parent,Page page){super(Text.literal("설정"));this.parent=parent;this.page=page;camera=AimSettings.get().copy();
        if(page==Page.EFFECT)try{float[] v=(float[])Class.forName("dev.portablevfx.client.api.VfxPreferences").getMethod("values").invoke(null);own=v[0];other=v[1];}
        catch(ReflectiveOperationException|LinkageError e){notice="스킬 이펙트 모드를 확인해 주세요.";}
        if(page==Page.EFFECT)try{bloom=(Float)Class.forName("dev.portablevfx.client.api.VfxPreferences").getMethod("bloom").invoke(null);bloomAvailable=true;}
        catch(ReflectiveOperationException|LinkageError|ClassCastException e){bloomAvailable=false;}
    }

    /** Knob centre (panel units) for a 0..1 position, and the inverse used by dragging. */
    static float knobX(float t){return SettingsSlider.knobX(t);}
    static float fraction(double x){return SettingsSlider.fraction(x);}

    private void fit(double x,double y){scale=Math.min(width*SCREEN_SHARE/1000,height*SCREEN_SHARE/730);ox=(width-1000*scale)/2;oy=(height-730*scale)/2;mx=(x-ox)/scale;my=(y-oy)/scale;}
    private boolean hit(float x,float y,float w,float h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    private void sprite(DrawContext c,String name,int x,int y,int w,int h,int sw,int sh){UiResources.images().drawTexture(c,Identifier.of("magiccodex","textures/gui/followup/"+name+".png"),x,y,0,0,w,h,sw,sh,sw,sh,0xFFFFFFFF);}
    private void text(DrawContext c,String value,float x,float y,float size,boolean title,boolean center){UiResources.text().draw(c,value,x,y,size,0xFFF5E7CA,title?TITLE:FONT,center);}
    private void button(DrawContext c,String label,int x,int y,int w,boolean primary){sprite(c,hit(x,y,w,56)?"button_hover":primary?"button_primary":"button",x,y,w,56,337,primary?105:96);text(c,label,x+w/2f,y+28,23,false,true);}
    private void click(){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1f,.25f));}

    // ---------- rows ----------
    private int rows(){return page==Page.GUI?1:page==Page.CAMERA?3:page==Page.EFFECT?(bloomAvailable?3:2):0;}
    /** Slider rows stay above the panel art's centre divider (~y386): 3 rows at the old 91 spacing put the third label/track on it. */
    private int rowY(int row){return rowY(row,rows());}
    static int rowY(int row,int count){return SettingsSlider.rowY(row,count);}
    private String label(int row){return page==Page.GUI?"핫바 크기":page==Page.EFFECT?(row==0?"내 스킬 이펙트":row==1?"다른 사람 스킬 이펙트":"이펙트 빛 번짐"):switch(row){case 0->"어깨 좌우 거리";case 1->"카메라 높이";default->"카메라 거리";};}
    private float min(int row){return (page==Page.EFFECT||page==Page.GUI)?0:switch(row){case 0->0;case 1->-.5f;default->AimSettings.MIN_DISTANCE;};}
    private float max(int row){return (page==Page.EFFECT||page==Page.GUI)?1:switch(row){case 0->AimSettings.MAX_SIDE;case 1->1.5f;default->AimSettings.MAX_DISTANCE;};}
    private float value(int row){return page==Page.GUI?PlayerHudSettings.get().percent()/100f:page==Page.EFFECT?(row==0?own:row==1?other:bloom):switch(row){case 0->camera.sideOffset;case 1->camera.height;default->camera.distance;};}
    private void set(int row,float v){v=Math.clamp(v,min(row),max(row));if(page==Page.GUI){if(!PlayerHudSettings.get().update(Math.round(v*100)))notice="설정 저장에 실패했습니다. 크기는 현재 화면에 적용됩니다.";return;}if(page==Page.EFFECT){if(row==0)own=v;else if(row==1)other=v;else bloom=v;}else switch(row){case 0->camera.sideOffset=v;case 1->camera.height=v;default->camera.distance=v;}}
    private String format(int row,float v){return (page==Page.EFFECT||page==Page.GUI)?Math.round(v*100)+"%":String.format(java.util.Locale.ROOT,"%.1f",v);}
    /** Framed value box right after the label (panel units). */
    private float boxX(int row){return TRACK_X+UiResources.text().width(label(row),22,FONT)+18;}
    static final float BOX_W=96,BOX_H=36;

    @Override public void render(DrawContext c,int x,int y,float delta){
        fit(x,y);var images=UiResources.images();var type=UiResources.text();images.beginFrame();type.beginFrame();c.getMatrices().push();
        try{c.getMatrices().translate(ox,oy,200);c.getMatrices().scale(scale,scale,1);
            sprite(c,"settings_panel",0,0,1000,730,1162,846);
            text(c,page==Page.HOME?"설정":page==Page.GUI?"GUI 비율":page==Page.EFFECT?"스킬 이펙트":"시점 · 조작",500,85,34,true,true);
            sprite(c,"close",915,37,47,45,106,100);
            if(page==Page.HOME){String[] labels={"스킬 이펙트","시점 · 조작","키세팅",HudCustomization.locked()?"HUD 잠금: 켜짐":"HUD 잠금: 꺼짐"};String[] icons={"effect_icon","camera_icon","keyboard_icon","effect_icon"};int[] iw={146,135,143,146},ih={149,120,125,149};
                for(int i=0;i<4;i++){int yy=193+i*108;sprite(c,hit(155,yy,690,103)?"category_hover":"category",155,yy,690,103,665,hit(155,yy,690,103)?144:140);sprite(c,icons[i],191,yy+23,58,58,iw[i],ih[i]);text(c,labels[i],300,yy+52,28,false,false);}
                button(c,"HUD \uBC30\uCE58 \uCD08\uAE30\uD654",300,640,400,false);
            }else{
                for(int i=0;i<rows();i++)slider(c,i);
                if(page==Page.CAMERA){
                    sprite(c,camera.side<0?"checkbox_checked":"checkbox",145,491,35,35,camera.side<0?95:88,90);text(c,"반대쪽 어깨",198,509,23,false,false);
                    sprite(c,camera.smartCast?"toggle_on":"toggle_off",589,485,92,52,camera.smartCast?185:169,camera.smartCast?106:98);text(c,"스마트 시전",706,509,23,false,false);
                    text(c,"일반 마우스 조준 · F5의 1인칭과 정면 시점은 유지됩니다",500,566,20,false,true);
                }else if(page==Page.GUI){text(c,"0% 숨김 · 50% 현재 크기 · 100% 2배",500,449,23,false,true);text(c,"핫바만 바뀌고 초상화·체력·마나는 그대로입니다 (자동 저장)",500,484,20,false,true);}else{int ty=449;text(c,"화면에 보이는 효과만 조절합니다",500,ty,23,false,true);text(c,"마나 · 피해 · 판정 · 지속시간은 바뀌지 않습니다",500,ty+35,20,false,true);}
                text(c,notice,500,603,18,false,true);button(c,"초기화",125,640,205,false);button(c,page==Page.GUI?"뒤로":"취소",397,640,205,false);button(c,page==Page.GUI?"완료":"저장",670,640,205,true);
            }
        }finally{c.getMatrices().pop();images.endFrame();}
    }

    private void confirmHudReset(){
        client.setScreen(new ConfirmScreen(confirmed->{
            boolean saved=HudCustomization.reset(confirmed);
            client.setScreen(this);
            if(confirmed)SharedNotifications.offer("hud:reset",saved?"system":"warning",saved?"HUD \uBC30\uCE58 \uCD08\uAE30\uD654 \uC644\uB8CC":"HUD \uBC30\uCE58 \uC800\uC7A5 \uC2E4\uD328",saved?"HUD \uC704\uCE58\uC640 \uD06C\uAE30\uB97C \uAE30\uBCF8\uAC12\uC73C\uB85C \uB418\uB3CC\uB838\uC2B5\uB2C8\uB2E4.":"\uD604\uC7AC \uD654\uBA74\uC5D0\uB9CC \uBC18\uC601\uB418\uC5C8\uC2B5\uB2C8\uB2E4.");
        },Text.literal("HUD \uBC30\uCE58 \uCD08\uAE30\uD654"),Text.literal("HUD \uC704\uCE58\uC640 \uD06C\uAE30\uB9CC \uAE30\uBCF8\uAC12\uC73C\uB85C \uB418\uB3CC\uB9B4\uAE4C\uC694? \uCC44\uD305 \uBC30\uCE58\uC640 \uB2E4\uB978 \uC124\uC815\uC740 \uC720\uC9C0\uB429\uB2C8\uB2E4."),Text.literal("\uCD08\uAE30\uD654"),Text.literal("\uCDE8\uC18C")));
    }

    private void slider(DrawContext c,int row){
        int y=rowY(row);float v=value(row),t=(v-min(row))/(max(row)-min(row)),kx=knobX(t);
        text(c,label(row),TRACK_X,y,22,false,false);
        // Value box: dark plate + thin gold frame; brighter while typing.
        float bx=boxX(row),by=y-BOX_H/2;boolean edit=editing==row,hot=edit||hit(bx,by,BOX_W,BOX_H);
        HudMesh.quad(c,bx,by,BOX_W,BOX_H,edit?0xF0132A44:0xE00B1A2C,edit?0xF0132A44:0xE00B1A2C);
        frame(c,bx,by,BOX_W,BOX_H,hot?0xFFF0D08A:0xFFB89458);
        String shown=edit?buffer+((Util.getMeasuringTimeMs()/450)%2==0?"|":" "):format(row,v);
        text(c,shown,bx+BOX_W/2,y,21,false,true);
        sprite(c,"slider_track",(int)TRACK_X,y+22,(int)TRACK_W,30,469,51);
        bar(c,TRACK_X+9,y+24,kx-(TRACK_X+9),26);
        sprite(c,"slider_knob",Math.round(kx)-19,y+18,38,38,87,89);
    }
    /** Bright capsule from the track start to the knob centre: fixed end caps, stretched middle. */
    private static void bar(DrawContext c,float x,float y,float w,float h){
        if(w<2)return;var id=Identifier.of("magiccodex","textures/gui/followup/slider_bar.png");var img=UiResources.images();
        // Source regions are fixed (cached once); only the destination widths change while dragging.
        int tw=402,th=44,capSrc=30,ix=Math.round(x),iy=Math.round(y),ih=Math.round(h),total=Math.round(w);
        int cap=Math.min(18,total/2),mid=total-2*cap;
        img.drawTexture(c,id,ix,iy,0,0,cap,ih,capSrc,th,tw,th,0xFFFFFFFF);
        if(mid>0)img.drawTexture(c,id,ix+cap,iy,capSrc,0,mid,ih,tw-2*capSrc,th,tw,th,0xFFFFFFFF);
        img.drawTexture(c,id,ix+cap+Math.max(0,mid),iy,tw-capSrc,0,cap,ih,capSrc,th,tw,th,0xFFFFFFFF);
    }
    private static void frame(DrawContext c,float x,float y,float w,float h,int color){
        HudMesh.line(c,x,y,x+w,y,1.6f,color);HudMesh.line(c,x,y+h,x+w,y+h,1.6f,color);
        HudMesh.line(c,x,y,x,y+h,1.6f,color);HudMesh.line(c,x+w,y,x+w,y+h,1.6f,color);
    }

    private void adjust(){
        if(dragging<0)return;float t=fraction(mx);int row=dragging;set(row,min(row)+t*(max(row)-min(row)));
        // Soft tick every 5% of the track (throttled) so dragging is audible but not noisy.
        int step=Math.round(t*20);long now=Util.getMeasuringTimeMs();
        if(step!=lastStep&&now-lastTick>45){lastStep=step;lastTick=now;client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.5f+t*.4f,.12f));}
    }

    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return true;fit(x,y);
        if(editing>=0&&!hit(boxX(editing),rowY(editing)-BOX_H/2,BOX_W,BOX_H))commitEdit();
        if(hit(915,37,47,45)){click();close();return true;}
        if(page==Page.HOME){if(hit(300,640,400,56)){click();confirmHudReset();return true;}for(int i=0;i<4;i++)if(hit(155,193+i*108,690,103)){click();if(i==3){HudCustomization.toggle();return true;}client.setScreen(i==2?new KeySettingsScreen(this):new SettingsScreen(this,i==0?Page.EFFECT:i==3?Page.GUI:Page.CAMERA));return true;}return true;}
        for(int i=0;i<rows();i++){
            if(hit(boxX(i),rowY(i)-BOX_H/2,BOX_W,BOX_H)){click();editing=i;buffer=(page==Page.EFFECT||page==Page.GUI)?Integer.toString(Math.round(value(i)*100)):format(i,value(i));return true;}
            if(hit(TRACK_X-19,rowY(i)+16,TRACK_W+38,44)){dragging=i;lastStep=Integer.MIN_VALUE;adjust();return true;}
        }
        if(page==Page.CAMERA){if(hit(140,480,300,53)){click();camera.side=-camera.side;}if(hit(580,480,275,53)){click();camera.smartCast=!camera.smartCast;}}
        if(hit(125,640,205,56)){click();if(page==Page.GUI){set(0,.5f);}else if(page==Page.CAMERA){camera.sideOffset=AimSettings.DEFAULT_SIDE;camera.height=1;camera.distance=5;camera.side=1;camera.smartCast=false;camera.shoulder=true;}else{own=other=1;bloom=BLOOM_DEFAULT;}}
        else if(hit(397,640,205,56)){click();close();}else if(hit(670,640,205,56)){click();save();}return true;
    }
    /** Typed value: number in the row's unit ("%" optional on the effect page); clamped; invalid input is ignored. */
    static Float parse(String raw,boolean percent){return SettingsSlider.parse(raw,percent);}
    private void commitEdit(){if(editing<0)return;Float v=parse(buffer,page==Page.EFFECT||page==Page.GUI);if(v!=null)set(editing,v);editing=-1;buffer="";}
    @Override public boolean charTyped(char chr,int modifiers){
        if(editing<0)return super.charTyped(chr,modifiers);
        if((Character.isDigit(chr)||chr=='.'||chr=='-'||chr=='%'||chr==',')&&buffer.length()<7)buffer+=chr;
        return true;
    }
    private void save(){commitEdit();if(page==Page.GUI){close();return;}try{if(page==Page.CAMERA){if(!camera.commit())throw new java.io.IOException("camera save failed");}else{var prefs=Class.forName("dev.portablevfx.client.api.VfxPreferences");prefs.getMethod("save",float.class,float.class).invoke(null,own,other);if(bloomAvailable)prefs.getMethod("saveBloom",float.class).invoke(null,bloom);}close();SharedNotifications.offer("settings:saved","system","설정을 저장했습니다","변경한 화면 설정이 적용되었습니다.");}catch(Exception|LinkageError error){notice="저장하지 못했습니다. 기존 설정은 유지됩니다.";}}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(button==0&&dragging>=0){fit(x,y);adjust();return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=-1;return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(editing>=0){
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER||key==GLFW.GLFW_KEY_TAB){commitEdit();click();return true;}
            if(key==GLFW.GLFW_KEY_ESCAPE){editing=-1;buffer="";return true;}
            if(key==GLFW.GLFW_KEY_BACKSPACE){if(!buffer.isEmpty())buffer=buffer.substring(0,buffer.length()-1);return true;}
            return true;
        }
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}return super.keyPressed(key,scan,mods);}
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}
