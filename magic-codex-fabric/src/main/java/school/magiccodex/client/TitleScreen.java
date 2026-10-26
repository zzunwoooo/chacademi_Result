package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.TitleProtocol;

/** Approved PNG skins, dynamic server nickname and independently scrollable owned lists. */
public final class TitleScreen extends Screen {
    private static final Identifier BODY=Identifier.of("magiccodex","hud_bold"),HEADING=Identifier.of("magiccodex","ui_extra_bold");
    private static final int GOLD=0xffdec58e,WHITE=0xfff1e9d5,MUTED=0xffabb8bf,CYAN=0xff96e4ec;
    private record Sprite(Identifier id,int u,int v,int sw,int sh,int tw,int th){}
    private static final Map<String,Sprite> SKINS=Map.ofEntries(
        sprite("main-panel",31,29,1343,1045,1402,1122),sprite("name-preview",40,217,1941,342,2021,778),sprite("list-panel",29,36,1245,1133,1303,1207),sprite("apply-button",38,192,2002,337,2079,756),
        sprite("row-normal",0,0,852,104,852,104),sprite("row-selected",0,0,852,104,852,104),sprite("selected-value",0,0,540,96,540,96),sprite("check",0,0,48,48,48,48),sprite("close",0,0,84,84,84,84),sprite("divider",0,0,852,20,852,20),sprite("scrollbar-track",0,0,14,584,14,584),sprite("scrollbar-thumb",0,0,14,400,14,400));
    private static Map.Entry<String,Sprite> sprite(String name,int u,int v,int sw,int sh,int tw,int th){return Map.entry(name,new Sprite(Identifier.of("magiccodex","textures/gui/titles/"+name+".png"),u,v,sw,sh,tw,th));}
    private TitleProtocol.Response data;private final List<List<TitleProtocol.Entry>> lists=new ArrayList<>();private final int[] scroll={0,0};private final String[] selected={"",""};
    private String message="";private long noticeUntil,refresh;private int drag=-1;private boolean requested;private final UiResources.Entrance entrance=new UiResources.Entrance();
    public TitleScreen(){super(Text.literal("칭호 설정"));lists.add(List.of());lists.add(List.of());}
    public void receive(TitleProtocol.Response r,boolean applied){boolean reset=data==null||data.revision()!=r.revision()||applied;data=r;for(int side=0;side<2;side++){final int which=side;var list=new ArrayList<TitleProtocol.Entry>();list.add(new TitleProtocol.Entry("",side,"선택 안 함",0xabb8bf));list.addAll(r.entries().stream().filter(e->e.side()==which).toList());lists.set(side,List.copyOf(list));if(reset)selected[side]=side==0?r.prefix():r.suffix();if(list.stream().noneMatch(e->e.id().equals(selected[which])))selected[side]="";scroll[side]=Math.clamp(scroll[side],0,Math.max(0,list.size()-5));}if(!r.message().isBlank())notice(r.message());refresh=Util.getMeasuringTimeMs()+30000;}
    public void notice(String text){message=text;noticeUntil=Util.getMeasuringTimeMs()+5000;}
    public void retry(){refresh=Util.getMeasuringTimeMs()+2000;}
    @Override public boolean shouldPause(){return false;}
    @Override public void tick(){if(client.player==null||client.world==null){close();return;}long now=Util.getMeasuringTimeMs();if(!requested||now>=refresh){if(TitleClient.request(this,TitleProtocol.OPEN,"","")){requested=true;refresh=now+30000;}else if(!TitleClient.supported()&&!requested){notice("칭호 플러그인을 확인해 주세요.");requested=true;}}}
    private void skin(DrawContext c,String name,int x,int y,int w,int h,int tint){var s=SKINS.get(name);UiResources.images().drawTexture(c,s.id(),x,y,s.u(),s.v(),w,h,s.sw(),s.sh(),s.tw(),s.th(),tint);}
    private void label(DrawContext c,String text,float x,float y,float size,int color,boolean centered,float max){float fit=Math.max(Math.min(20,size),Math.min(size,size*max/Math.max(1,UiResources.text().width(text,size,BODY))));String display=TitleLayout.elide(text,max,s->UiResources.text().width(s,fit,BODY));UiResources.text().draw(c,display,x,y,fit,color,BODY,centered);}
    private TitleProtocol.Entry value(int side){return lists.get(side).stream().filter(e->e.id().equals(selected[side])).findFirst().orElse(new TitleProtocol.Entry("",side,"선택 안 함",side==0?CYAN&0xffffff:GOLD&0xffffff));}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var fit=TitleLayout.fit(width,height);double mx=fit.localX(mouseX),my=fit.localY(mouseY);var images=UiResources.images();var text=UiResources.text();text.beginFrame();images.beginFrame();c.fill(0,0,width,height,0x50050910);c.getMatrices().push();
        try{c.getMatrices().translate(fit.x(),fit.y(),0);c.getMatrices().scale(fit.scale(),fit.scale(),1);
            skin(c,"main-panel",0,0,1080,880,0xd9ffffff);skin(c,"name-preview",38,96,1004,152,0xebffffff);
            skin(c,"list-panel",38,266,492,456,0xf2ffffff);skin(c,"list-panel",550,266,492,456,0xf2ffffff);
            skin(c,"selected-value",148,324,270,48,-1);skin(c,"selected-value",660,324,270,48,-1);
            skin(c,"divider",64,392,426,10,-1);skin(c,"divider",576,392,426,10,-1);skin(c,"divider",160,752,760,10,-1);
            long now=Util.getMeasuringTimeMs();double seconds=now/1000.0;float drift=(float)Math.sin(seconds*Math.PI*2/24);float pulse=(float)(.5+.5*Math.sin(seconds*Math.PI*2/4.5));
            // Cached skins + a handful of smooth meshes, no per-frame PNG or text allocations for VFX.
            for(int i=0;i<5;i++){float x=120+i*202+drift*16,y=224+(float)Math.sin(seconds*.32+i)*5;HudMesh.disk(c,x,y,10+6*pulse,((int)(8+5*pulse)<<24)|0x75cbdc);HudMesh.star(c,x,y,2+2*pulse,((int)(80+50*pulse)<<24)|0xe5d5a4);}
            text.draw(c,"칭호 설정",540,68,34,GOLD,HEADING,true);label(c,"이름 미리보기",540,129,20,0xffc4c7c5,true,500);
            if(data==null)label(c,"칭호를 불러오는 중",540,185,29,MUTED,true,900);else composed(c);
            label(c,"접두사",284,298,26,GOLD,true,450);label(c,"접미사",796,298,26,GOLD,true,450);
            for(int side=0;side<2;side++){var val=value(side);label(c,val.name(),side==0?283:795,348,22,side==0?CYAN:GOLD,true,234);int x=side==0?64:576,sx=side==0?506:1018;
                var list=lists.get(side);for(int row=0;row<5&&row+scroll[side]<list.size();row++){var e=list.get(row+scroll[side]);int y=416+row*60;boolean chosen=e.id().equals(selected[side]),hover=TitleLayout.row(mx,my,side)==row;skin(c,chosen?"row-selected":"row-normal",x,y,426,52,chosen?-1:hover?0xf2ffffff:0xc7ffffff);label(c,e.name(),x+24,y+26,25,chosen?WHITE:e.id().isEmpty()?MUTED:0xffe7e6de,false,334);if(chosen)skin(c,"check",x+384,y+14,24,24,-1);}
                skin(c,"scrollbar-track",sx,416,7,292,-1);int count=Math.max(5,list.size()),h=Math.max(18,292*5/count),max=Math.max(0,list.size()-5),y=416+(max==0?0:(292-h)*scroll[side]/max);skin(c,"scrollbar-thumb",sx,y,7,h,-1);
            }
            HudMesh.capsule(c,820,42,156,52,TitleLayout.contains(mx,my,820,42,156,52)?0xB0377390:0x80213B4C,0x80213B4C);label(c,"닉네임 설정",898,68,20,GOLD,true,140);boolean can=data!=null&&!TitleClient.waiting();skin(c,"apply-button",352,780,376,66,can?-1:0x90ffffff);label(c,TitleClient.waiting()&&data!=null?"확인 중":"적용",540,813,27,can?GOLD:MUTED,true,290);skin(c,"close",TitleLayout.CLOSE_X,TitleLayout.CLOSE_Y,TitleLayout.CLOSE_SIZE,TitleLayout.CLOSE_SIZE,TitleLayout.contains(mx,my,TitleLayout.CLOSE_X,TitleLayout.CLOSE_Y,TitleLayout.CLOSE_SIZE,TitleLayout.CLOSE_SIZE)?-1:0xccffffff);
            if(now<noticeUntil&&!message.isBlank())label(c,message,540,739,19,CYAN,true,710);
        }finally{c.getMatrices().pop();images.endFrame();}entrance.draw(c,width,height);
    }
    private void composed(DrawContext c){var p=value(0);var s=value(1);String a=p.id().isEmpty()?"":TitleLayout.elide(p.name(),300,v->UiResources.text().width(v,34,BODY)),b=s.id().isEmpty()?"":TitleLayout.elide(s.name(),240,v->UiResources.text().width(v,34,BODY)),name=NicknameClient.display(data.nickname());float wa=UiResources.text().width(a,34,BODY),wn=UiResources.text().width(name,44,BODY),wb=UiResources.text().width(b,34,BODY),gaps=(a.isEmpty()?0:18)+(b.isEmpty()?0:18),total=wa+wn+wb+gaps,scale=Math.min(1,900/Math.max(1,total)),x=540-total*scale/2;
        if(!a.isEmpty()){label(c,a,x+wa*scale/2,185,34*scale,0xff000000|p.color(),true,900);x+=(wa+18)*scale;}if(!b.isEmpty()){label(c,b,x+wb*scale/2,185,34*scale,0xff000000|s.color(),true,900);x+=(wb+18)*scale;}label(c,name,x+wn*scale/2,185,44*scale,WHITE,true,900);
    }
    private void sound(){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.12f,.23f));}
    private void pick(int side,int index){if(data==null||TitleClient.waiting()||index<0||index>=lists.get(side).size())return;selected[side]=lists.get(side).get(index).id();sound();}
    private void cycle(int side){var list=lists.get(side);if(list.isEmpty())return;int current=0;for(int i=0;i<list.size();i++)if(list.get(i).id().equals(selected[side]))current=i;int next=(current+1)%list.size();pick(side,next);scroll[side]=Math.clamp(next-4,0,Math.max(0,list.size()-5));}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return super.mouseClicked(x,y,button);var l=TitleLayout.fit(width,height);double mx=l.localX(x),my=l.localY(y);if(TitleLayout.contains(mx,my,820,42,156,52)){NicknameClient.open(this);return true;}if(TitleLayout.contains(mx,my,TitleLayout.CLOSE_X,TitleLayout.CLOSE_Y,TitleLayout.CLOSE_SIZE,TitleLayout.CLOSE_SIZE)){sound();close();return true;}if(TitleLayout.contains(mx,my,352,780,376,66)){apply();return true;}for(int side=0;side<2;side++){int row=TitleLayout.row(mx,my,side);if(row>=0){pick(side,row+scroll[side]);return true;}if(TitleLayout.contains(mx,my,side==0?500:1012,416,20,292)){drag=side;dragScroll(my);return true;}}return super.mouseClicked(x,y,button);}
    private void apply(){if(data!=null&&TitleClient.request(this,TitleProtocol.APPLY,selected[0],selected[1]))sound();}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){var l=TitleLayout.fit(width,height);double mx=l.localX(x),my=l.localY(y);for(int side=0;side<2;side++)if(TitleLayout.contains(mx,my,side==0?38:550,392,492,330)){scroll[side]=Math.clamp(scroll[side]-(int)Math.signum(vertical),0,Math.max(0,lists.get(side).size()-5));return true;}return false;}
    private void dragScroll(double y){if(drag>=0)scroll[drag]=(int)Math.round(Math.clamp((y-416)/292,0,1)*Math.max(0,lists.get(drag).size()-5));}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(drag<0)return false;dragScroll(TitleLayout.fit(width,height).localY(y));return true;}
    @Override public boolean mouseReleased(double x,double y,int button){drag=-1;return super.mouseReleased(x,y,button);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT){cycle(0);return true;}if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT){cycle(1);return true;}if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER){apply();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void removed(){TitleClient.closed(this);super.removed();}
    public void verifyRenderer(){UiResources.images().verifyGpuState();}
}
