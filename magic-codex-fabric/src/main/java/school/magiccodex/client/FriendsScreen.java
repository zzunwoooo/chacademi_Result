package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.protocol.SocialProtocol.Entry;

public final class FriendsScreen extends SocialScreen {
    private Input search,addName;
    private boolean addDialog,dragging,scrollbar;
    /** true면 친구 목록 대신 대기 중인 친구 신청(받은 것 먼저, 그다음 보낸 것)을 보여 준다. */
    private boolean requests;
    private record Ask(Entry entry,boolean incoming){}
    private Entry deleting;
    private float scroll;
    private double dragY;
    private long refresh;
    public FriendsScreen(){super("친구 목록");}
    @Override SocialLayout.Fit fit(){return SocialLayout.friends(width,height);}
    @Override protected void init(){String query=search==null?"":search.text(),draft=addName==null?"":addName.text();super.init();search=new Input("친구 검색",16,query);addName=new Input("추가할 친구",16,draft);if(addDialog)addName.focus(true);}
    private List<Entry> visible(){String q=search==null?"":search.text().toLowerCase(Locale.ROOT);return SocialClient.entries().stream().filter(e->e.name().toLowerCase(Locale.ROOT).contains(q)).sorted(Comparator.comparing(Entry::online).reversed().thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER)).toList();}
    private List<Ask> asks(){
        String q=search==null?"":search.text().toLowerCase(Locale.ROOT);var list=new ArrayList<Ask>();
        for(var e:SocialClient.incoming())if(e.name().toLowerCase(Locale.ROOT).contains(q))list.add(new Ask(e,true));
        for(var e:SocialClient.outgoing())if(e.name().toLowerCase(Locale.ROOT).contains(q))list.add(new Ask(e,false));
        return list;
    }
    private int rows(){return requests?asks().size():visible().size();}
    public float scrollOffset(){return scroll;}
    public void updated(int action){scroll=Math.clamp(scroll,0,SocialLayout.maxScroll(rows()));if(action==SocialProtocol.ADD||action==SocialProtocol.REMOVE){addDialog=false;deleting=null;addName.widget.setText("");}}
    @Override public void tick(){super.tick();if(client.currentScreen!=this)return;long n=Util.getMeasuringTimeMs();if(n>=refresh&&!SocialClient.waiting(this,SocialProtocol.LIST)){refresh=n+(SocialClient.request(this,SocialProtocol.LIST,SocialProtocol.NONE,0,"")?5000:1100);}}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var f=fit();double mx=f.x(mouseX),my=f.y(mouseY);boolean modal=addDialog||deleting!=null;
        start(c);
        try{
            asset(c,"friends_panel",0,0,1000,870,0,0,1340,1174,0xEEFFFFFF);
            // Small two-person header glyph.
            HudMesh.disk(c,81,77,8,GOLD);HudMesh.disk(c,104,72,9,GOLD);HudMesh.capsule(c,69,91,24,12,GOLD,GOLD);HudMesh.quad(c,69,97,24,8,GOLD,GOLD);HudMesh.capsule(c,92,87,25,12,GOLD,GOLD);HudMesh.quad(c,92,93,25,12,GOLD,GOLD);
            label(c,"친구 목록 ("+SocialClient.entries().size()+"/50)",140,84,34,WHITE,true);
            boolean close=in(mx,my,915,62,42,42)&&!modal;cross(c,936,82,close?CYAN:WHITE);
            box(c,55,145,635,48,!modal&&search.focus());search.draw(c,63,147,619,44,"친구 검색");
            button(c,"+ 친구 추가",711,145,223,48,!modal&&in(mx,my,711,145,223,48));
            boolean tabFriends=!modal&&in(mx,my,500,62,180,42),tabRequests=!modal&&in(mx,my,690,62,210,42);
            button(c,"친구",500,62,180,42,!requests||tabFriends);button(c,SocialClient.incoming().isEmpty()?"신청":"신청 "+SocialClient.incoming().size(),690,62,210,42,requests||tabRequests);
            var list=requests?List.<Entry>of():visible();var asks=requests?asks():List.<Ask>of();int count=requests?asks.size():list.size();
            scroll=Math.clamp(scroll,0,SocialLayout.maxScroll(count));
            c.enableScissor(52,211,938,755);
            int hot=0;
            try{for(int i=0;i<list.size();i++){
                int y=Math.round(215+i*90-scroll);if(y+82<211||y>755)continue;
                Entry e=list.get(i);row(c,e,y);
                for(int action=0;action<3;action++){
                    int cx=750+action*76,cy=y+41;boolean over=!modal&&in(mx,my,cx-26,cy-26,52,52)&&my>=215&&my<755;
                    if(over)hot=1+i*3+action;
                    asset(c,over?"action_button_hover":"action_button",cx-39,cy-39,78,78,0,0,1254,1254,e.online()||action==2?0xFFFFFFFF:0x70FFFFFF);
                    icon(c,action,cx,cy,over?CYAN:e.online()||action==2?WHITE:MUTED);
                }
            }
            for(int i=0;i<asks.size();i++){
                int y=Math.round(215+i*90-scroll);if(y+82<211||y>755)continue;
                Ask a=asks.get(i);base(c,a.entry(),y);fitted(c,a.incoming()?"친구 신청을 받았습니다":"수락을 기다리는 중",520,y+41,19,160,a.incoming()?CYAN:MUTED,false);
                for(int k=a.incoming()?0:1;k<2;k++){
                    int bx=694+k*122;boolean over=!modal&&in(mx,my,bx,y+19,112,44)&&my>=215&&my<755;
                    if(over)hot=500+i*2+k;
                    button(c,a.incoming()?(k==0?"수락":"거절"):"취소",bx,y+19,112,44,over);
                }
            }}finally{c.disableScissor();}
            float max=SocialLayout.maxScroll(count),thumb=count<=6?540:Math.max(45,540*6f/count),ty=215+(max==0?0:(540-thumb)*scroll/max);
            HudMesh.quad(c,947,215,7,540,0x55425665,0x55425665);HudMesh.quad(c,947,ty,7,thumb,0xFFDCC17F,0xFFAE986C);
            if(requests){if(asks.isEmpty())center(c,"대기 중인 친구 신청이 없습니다",500,475,25,MUTED);}
            else if(list.isEmpty())center(c,!SocialClient.cacheKnown()?(SocialClient.waiting(this,SocialProtocol.LIST)?"친구 목록을 불러오는 중…":"친구 목록을 확인하고 있어요"):SocialClient.entries().isEmpty()?"친구를 추가해 보세요":"검색 결과가 없습니다",500,475,25,MUTED);
            center(c,busy&&!modal?(requests?"처리하고 있어요…":"바람의 전언을 준비하고 있어요…"):!requests&&!SocialClient.outgoing().isEmpty()?"보낸 친구 신청 "+SocialClient.outgoing().size()+"건이 수락을 기다리고 있어요":"드래그하여 더 보기",500,778,20,MUTED);
            if(busy&&!modal || !SocialClient.cacheKnown()){
                float t=Util.getMeasuringTimeMs()/180f;
                for(int i=0;i<3;i++)HudMesh.disk(c,482+i*18,!SocialClient.cacheKnown()?514:804,3,((int)(100+155*(.5+.5*Math.sin(t-i)))<<24)|0x92E7F2);
            }
            if(hot!=0&&hot<500){int action=(hot-1)%3;String label=new String[]{"바람의 전언","정보 확인","친구 삭제"}[action];int tx=(int)Math.clamp(mx-78,50,790),ty2=(int)Math.clamp(my-54,200,707);c.getMatrices().push();c.getMatrices().translate(0,0,20);box(c,tx,ty2,156,38,true);center(c,label,tx+78,ty2+19,20,WHITE);c.getMatrices().pop();}
            hover(hot!=0?hot:close?1000:tabFriends?1001:tabRequests?1002:0);
            if(modal){c.getMatrices().push();c.getMatrices().translate(0,0,40);chamfer(c,40,45,920,776,28,0xA0081420);chamfer(c,210,298,580,246,12,0xFFA99770);chamfer(c,211,299,578,244,11,0xFA102536);
                center(c,addDialog?"친구 신청":"친구 삭제",500,342,30,WHITE);
                if(addDialog){fitted(c,"접속 중인 플레이어의 닉네임을 입력해 주세요. 상대가 수락하면 친구가 됩니다.",252,382,20,496,MUTED,false);box(c,245,405,510,49,true);addName.draw(c,250,407,500,45,"닉네임");}
                else center(c,deleting.name()+"님을 친구 목록에서 삭제할까요?",500,409,23,WHITE);
                button(c,busy?"처리 중…":addDialog?"신청":"삭제",510,480,210,44,in(mx,my,510,480,210,44));button(c,"취소",280,480,210,44,in(mx,my,280,480,210,44));c.getMatrices().pop();
            }
            c.getMatrices().push();c.getMatrices().translate(0,0,60);toast(c,120,814,760);c.getMatrices().pop();
        }finally{end(c);}
    }
    private void row(DrawContext c,Entry e,int y){
        base(c,e,y);
        HudMesh.disk(c,571,y+41,6,e.online()?0xFF52D4A1:0xFF7E8A98);label(c,e.online()?"온라인":"오프라인",590,y+41,20,e.online()?WHITE:MUTED,false);
    }
    /** 행 배경·얼굴·이름·기숙사 (친구 행과 신청 행 공통). */
    private void base(DrawContext c,Entry e,int y){
        int d=SocialLayout.dorm(e.dormitory());
        if(d==0)box(c,55,y,879,82,false);
        else{String file=switch(d){case 1->"row_arkeon";case 2->"row_lumina";case 3->"row_bestiaz";default->"row_noxer";};int[] b=switch(d){case 1->new int[]{42,253,2089,208};case 2->new int[]{38,242,2096,216};case 3->new int[]{37,242,2098,218};default->new int[]{41,247,2091,215};};asset(c,file,55,y,879,82,b[0],b[1],b[2],b[3],0xCCFFFFFF);}
        var p=client.getNetworkHandler()==null?null:client.getNetworkHandler().getPlayerListEntry(e.id());
        box(c,72,y+12,58,58,false);
        if(p!=null){var skin=p.getSkinTextures().texture();c.drawTexture(RenderLayer::getGuiTextured,skin,78,y+18,8,8,46,46,8,8,64,64);c.drawTexture(RenderLayer::getGuiTextured,skin,78,y+18,40,8,46,46,8,8,64,64);}
        else center(c,e.name().isEmpty()?"?":e.name().substring(0,1),101,y+41,25,MUTED);
        fitted(c,e.name(),236,y+29,26,273,SocialLayout.color(e.dormitory()),true);
        label(c,e.dormitory().isEmpty()?"기숙사 미정":e.dormitory(),236,y+58,19,SocialLayout.color(e.dormitory()),false);
    }
    static void cross(DrawContext c,float x,float y,int color){HudMesh.line(c,x-10,y-10,x+10,y+10,1.8f,color);HudMesh.line(c,x+10,y-10,x-10,y+10,1.8f,color);}
    static void icon(DrawContext c,int action,float x,float y,int color){
        if(action==0){HudMesh.line(c,x-10,y+10,x+10,y-10,2.3f,color);for(int i=0;i<4;i++){float v=i*4;HudMesh.line(c,x-8+v,y+7-v,x-10+v,y-1-v,2.2f,color);HudMesh.line(c,x-5+v,y+5-v,x+4+v,y+3-v,2.2f,color);}}
        else if(action==1){HudMesh.disk(c,x,y-7,6,color);HudMesh.capsule(c,x-11,y+2,22,12,color,color);}
        else HudMesh.line(c,x-10,y,x+10,y,3,color);
    }
    private void submit(){if(busy)return;if(addDialog){String name=addName.text().strip();if(name.isBlank()||name.length()>16){notice("정확한 닉네임을 입력해 주세요.");return;}busy=SocialClient.request(this,SocialProtocol.ADD,SocialProtocol.NONE,0,name);}else if(deleting!=null)busy=SocialClient.request(this,SocialProtocol.REMOVE,deleting.id(),0,"");sound(1.1f,.2f);}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return super.mouseClicked(x,y,button);var f=fit();double mx=f.x(x),my=f.y(y);
        if(addDialog||deleting!=null){if(in(mx,my,280,480,210,44)&&!busy){addDialog=false;deleting=null;sound(1,.15f);return true;}if(in(mx,my,510,480,210,44)){submit();return true;}if(addDialog)addName.click(mx,my);return true;}
        if(in(mx,my,915,62,42,42)){close();return true;}
        if(in(mx,my,500,62,180,42)||in(mx,my,690,62,210,42)){boolean next=mx>=690;if(next!=requests&&!busy){requests=next;scroll=0;sound(1.1f,.2f);}return true;}
        if(in(mx,my,711,145,223,48)){addDialog=true;addName.focus(true);search.focus(false);sound(1.1f,.2f);return true;}
        if(search.click(mx,my))return true;
        if(in(mx,my,940,215,20,540)){scrollbar=dragging=true;dragY=my;setScrollbar(my);return true;}
        if(requests&&in(mx,my,55,215,879,540)){
            var asks=asks();int row=(int)((my-215+scroll)/90);
            if(row<asks.size()){
                Ask a=asks.get(row);float by=215+row*90-scroll+19;
                for(int k=a.incoming()?0:1;k<2;k++)if(in(mx,my,694+k*122,by,112,44)){
                    if(busy)return true;sound(1.13f,.2f);
                    busy=a.incoming()?SocialClient.respond(this,a.entry().id(),k==0):SocialClient.request(this,SocialProtocol.WITHDRAW,a.entry().id(),0,"");return true;
                }
            }
            dragging=true;scrollbar=false;dragY=my;return true;
        }
        if(in(mx,my,55,215,879,540)){
            var list=visible();int row=(int)((my-215+scroll)/90);
            if(row<list.size())for(int a=0;a<3;a++)if(in(mx,my,724+a*76,215+row*90-scroll+15,52,52)){
                Entry e=list.get(row);if(busy)return true;sound(1.13f,.2f);
                if(a==2)deleting=e;
                else if(!e.online())notice("접속 중인 친구에게 사용할 수 있습니다.");
                else if(a==0)busy=SocialClient.request(this,SocialProtocol.WHISPER,e.id(),0,"");
                else{client.setScreen(null);client.getNetworkHandler().sendChatCommand("스탯창 "+e.id());}return true;
            }
            dragging=true;scrollbar=false;dragY=my;return true;
        }
        return true;
    }
    private void setScrollbar(double my){float max=SocialLayout.maxScroll(rows());scroll=(float)Math.clamp((my-215)/540*max,0,max);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(dragging&&button==0){double my=fit().y(y);if(scrollbar)setScrollbar(my);else scroll=(float)Math.clamp(scroll+dragY-my,0,SocialLayout.maxScroll(rows()));dragY=my;return true;}return false;}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;scrollbar=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(!addDialog&&deleting==null&&in(fit().x(x),fit().y(y),55,215,902,540)){scroll=(float)Math.clamp(scroll-vertical*55,0,SocialLayout.maxScroll(rows()));return true;}return false;}
    @Override public boolean charTyped(char c,int mods){if(busy)return true;if(addDialog&&addName.focus())return addName.typed(c,mods);if(search.focus()){scroll=0;return search.typed(c,mods);}return super.charTyped(c,mods);}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){if(addDialog||deleting!=null){if(!busy){addDialog=false;deleting=null;}return true;}close();return true;}
        if(addDialog||deleting!=null){if(busy)return true;if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){submit();return true;}return addDialog&&addName.key(key,scan,mods);}
        if(search.focus()){scroll=0;return search.key(key,scan,mods);}return super.keyPressed(key,scan,mods);
    }
    @Override public void close(){sound(.95f,.18f);super.close();}
}
