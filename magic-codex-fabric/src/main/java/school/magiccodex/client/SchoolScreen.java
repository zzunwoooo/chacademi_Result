package school.magiccodex.client;

import java.time.*;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.SchoolProtocol;

/** Dynamic scores and scrollable recent records on the approved 1672 x 941 PNG. */
public final class SchoolScreen extends Screen {
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/school/house_points.png"),FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final int GOLD=0xFFE8D19B,WHITE=0xFFF1EEE3,MUTED=0xFFAFBDC9;
    private static final int[] COLORS={0xFFED737B,0xFF79B6F0,0xFF77C69D,0xFFE4C86C};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MM.dd  HH:mm");
    private int page,scroll;private boolean dragging;private double dragY;private long refresh,noticeUntil;
    private String notice="";
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    public SchoolScreen(){super(Text.literal("기숙사 점수"));}
    public void notice(String message){notice=message;noticeUntil=Util.getMeasuringTimeMs()+5000;}
    @Override public boolean shouldPause(){return false;}
    @Override public void tick(){
        long n=Util.getMeasuringTimeMs();int max=Math.max(0,(SchoolClient.total()-1)/SchoolProtocol.PAGE_SIZE);if(page>max){page=max;scroll=0;}
        if((n>=refresh||SchoolClient.page(page)==null)&&SchoolClient.request(SchoolProtocol.LIST,page,"",this))refresh=n+5000;
    }
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var layout=CodexLayout.codexFit(width,height);double mx=layout.localX(mouseX),my=layout.localY(mouseY);var text=UiResources.text();var images=UiResources.images();text.beginFrame();images.beginFrame();c.fill(0,0,width,height,0x60050910);
        c.getMatrices().push();
        try{
            c.getMatrices().translate(layout.x(),layout.y(),0);c.getMatrices().scale(layout.scale(),layout.scale(),1);
            images.drawTexture(c,PANEL,0,0,0,0,1672,941,1672,941);
            label(c,"기숙사 점수",267,82,40,WHITE,true);label(c,"기숙사별 누적 점수",270,123,22,MUTED,false);
            boolean close=hit(mx,my,1540,44,62,66);if(close)HudMesh.disk(c,1571,77,27,0x442C96C6);
            HudMesh.line(c,1559,65,1583,89,2.5f,GOLD);HudMesh.line(c,1583,65,1559,89,2.5f,GOLD);
            long max=SchoolClient.scores().stream().mapToLong(Math::abs).max().orElse(1);max=Math.max(1,max);
            for(int i=0;i<4;i++){
                float y=215+i*95;long score=SchoolClient.scores().get(i);
                label(c,SchoolProtocol.HOUSES.get(i),233,y,29,WHITE,true);
                if(score!=0){float w=704*Math.abs(score)/(float)max;HudMesh.capsule(c,526,y-12,Math.max(24,w),24,COLORS[i],COLORS[i]);HudMesh.line(c,540,y-7,526+Math.max(24,w)-12,y-7,1.5f,0x80FFFFFF);}
                String value=String.format(java.util.Locale.ROOT,"%,d점",score);float size=Math.min(30,30*290/Math.max(290,text.width(value,30,BOLD)));
                text.draw(c,value,1565-text.width(value,size,BOLD),y,size,COLORS[i],BOLD,false);
            }
            label(c,"최근 기증 기록",112,588,25,GOLD,true);
            String indicator="최신순  ·  "+SchoolClient.total()+"개";label(c,indicator,365,588,19,MUTED,false);
            label(c,"‹",1390,588,32,hit(mx,my,1365,560,50,47)?WHITE:GOLD,true);
            text.draw(c,(page+1)+" / "+Math.max(1,(SchoolClient.total()+19)/20),1470,588,20,MUTED,FONT,true);
            label(c,"›",1545,588,32,hit(mx,my,1520,560,50,47)?WHITE:GOLD,true);
            var data=SchoolClient.page(page);
            if(data==null)text.draw(c,SchoolClient.supported()?"기증 기록을 불러오는 중입니다.":"서버 연결이 필요합니다.",835,713,24,MUTED,FONT,true);
            else if(data.records().isEmpty())text.draw(c,"아직 기증 기록이 없습니다.",835,713,24,MUTED,FONT,true);
            else{
                scroll=Math.clamp(scroll,0,Math.max(0,data.records().size()-3));
                for(int row=0;row<3&&row+scroll<data.records().size();row++){
                    var e=data.records().get(row+scroll);int y=648+row*68;
                    fitted(c,e.spellName(),109,y,28,435,WHITE,true);label(c,"기증자",577,y,18,MUTED,false);fitted(c,e.nickname(),661,y,26,285,WHITE,true);
                    HudMesh.capsule(c,1006,y-19,224,38,0x33000000|((COLORS[e.house()]&0xFFFFFF)),0x33000000|((COLORS[e.house()]&0xFFFFFF)));
                    text.draw(c,SchoolProtocol.HOUSES.get(e.house()),1118,y,23,COLORS[e.house()],BOLD,true);
                    text.draw(c,DATE.format(Instant.ofEpochMilli(e.time()).atZone(ZoneId.systemDefault())),1430,y,19,MUTED,FONT,true);
                }
                if(data.records().size()>3){int h=176;float thumb=3f/data.records().size()*h,top=624+(h-thumb)*scroll/(data.records().size()-3f);HudMesh.line(c,1595,624,1595,800,3,0x443A6479);HudMesh.line(c,1595,top,1595,top+thumb,5,0xFF87AEB8);}
            }
            if(hit(mx,my,604,834,464,66))HudMesh.capsule(c,616,843,440,48,0x44377DA1,0x444BAAC2);
            text.draw(c,"마법 도감 열기",837,867,28,WHITE,BOLD,true);
            String footer=Util.getMeasuringTimeMs()<noticeUntil?notice:"휠 또는 드래그로 기록 보기";
            fitted(c,footer,87,858,17,475,MUTED,false);
        }finally{c.getMatrices().pop();images.endFrame();}
        entrance.draw(c,width,height);
    }
    private void label(DrawContext c,String s,float x,float y,float size,int color,boolean bold){UiResources.text().draw(c,s,x,y,size,color,bold?BOLD:FONT,false);}
    private void fitted(DrawContext c,String s,float x,float y,float size,float width,int color,boolean bold){var font=bold?BOLD:FONT;float fit=Math.min(size,size*width/Math.max(width,UiResources.text().width(s,size,font)));label(c,s,x,y,fit,color,bold);}
    private static boolean hit(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    private void sound(){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.1f,.23f));}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return false;var l=CodexLayout.codexFit(width,height);double mx=l.localX(x),my=l.localY(y);
        if(hit(mx,my,1540,44,62,66)){sound();close();return true;}
        if(hit(mx,my,604,834,464,66)){sound();MagicCodexClient.openFromMenu();return true;}
        if(hit(mx,my,1365,560,50,47)){turn(-1);return true;}if(hit(mx,my,1520,560,50,47)){turn(1);return true;}
        if(hit(mx,my,65,614,1540,202)){dragging=true;dragY=my;return true;}return super.mouseClicked(x,y,button);
    }
    private void turn(int delta){int next=Math.clamp(page+delta,0,Math.max(0,(SchoolClient.total()-1)/20));if(next!=page){page=next;scroll=0;refresh=0;sound();}}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){var l=CodexLayout.codexFit(width,height);if(hit(l.localX(x),l.localY(y),65,614,1540,202)){scroll+=(int)-Math.signum(vertical);clampScroll();return true;}return false;}
    private void clampScroll(){var p=SchoolClient.page(page);scroll=Math.clamp(scroll,0,p==null?0:Math.max(0,p.records().size()-3));}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!dragging)return false;double local=CodexLayout.codexFit(width,height).localY(y);int rows=(int)((dragY-local)/35);if(rows!=0){scroll+=rows;clampScroll();dragY=local;}return true;}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
}
