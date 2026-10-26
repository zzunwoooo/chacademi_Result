package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.PartyUiProtocol;
import school.magiccodex.protocol.PartyUiProtocol.Person;
import static school.magiccodex.client.PartyUiAtlas.*;

/**
 * 파티 창 (/파티). 레퍼런스(CHACADEMI_PARTY_UI) 좌표를 그대로 가상 좌표로 쓴다: 파티 패널 48~1030, 초대 패널 1062~1625.
 * 상태는 Velocity 가 보내는 것만 그리고, 모든 동작은 프록시가 다시 검사한다.
 */
public final class PartyScreen extends SocialScreen {
    // 글꼴: 제목은 차카데미 명조(마루부리), 본문은 프리텐다드.
    private static final Identifier TITLE=Identifier.of("magiccodex","section"),SERIF=Identifier.of("magiccodex","heading");
    private static final Identifier STAR=Identifier.of("magiccodex","textures/gui/party/house_star.png");
    private static final int TITLE_GOLD=0xFFF0D293,SUB=0xFFBFD3E6,GREEN=0xFF7BE08E,OFF=0xFF8A949C,TEXT=0xFFE6E9EE,BLUE_TEXT=0xFFDCEBFA,GOLD_TEXT=0xFFF1D9A0,ROSE_TEXT=0xFFF3A9B6;
    // 파티 패널
    private static final float ROW_X=86,ROW_W=910,ROW_TOP=268,ROW_STEP=126,ROW_H=114,LIST_H=492;
    // 초대 패널
    private static final float C_X=1096,C_W=490,C_TOP=288,C_STEP=116,C_H=102,C_LIST_H=336;
    private static final long CANDIDATE_REFRESH=2000,BUSY_TIMEOUT=4000;

    private enum Confirm { LEAVE, PROMOTE, KICK }
    private Input search;
    private boolean inviteOpen;
    private float openT,scroll,candidateScroll;
    private long lastFrame,busyAt,nextCandidates;
    private Confirm confirm;private Person confirmTarget;
    private boolean dragging,draggingCandidates;private double dragY;
    private int hot;

    /** Window this one was opened from (결투장 로비): drawn underneath and shown again on close. */
    private final net.minecraft.client.gui.screen.Screen parent;
    public PartyScreen(){this(null);}
    public PartyScreen(net.minecraft.client.gui.screen.Screen parent){super("파티");this.parent=parent;}

    @Override SocialLayout.Fit fit(){
        float s=Math.min(width*.94f/1580,height*.9f/860);
        float right=1030+(1627-1030)*ease(openT);
        return new SocialLayout.Fit((width-s*(right-48))/2-s*48,(height-s*860)/2-s*70,s);
    }
    private static float ease(float t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    @Override protected void init(){String query=search==null?"":search.text();super.init();search=new Input("닉네임 검색",16,query);PartyUiClient.send(PartyUiProtocol.OPEN,null);if(parent!=null&&client!=null)parent.init(client,width,height);}

    /** 프록시에서 새 상태·후보 목록이 왔을 때. */
    void stateChanged(){
        scroll=Math.clamp(scroll,0,maxScroll(rows().size(),ROW_STEP,LIST_H));
        candidateScroll=Math.clamp(candidateScroll,0,maxScroll(visibleCandidates().size(),C_STEP,C_LIST_H));
        if(confirmTarget!=null&&PartyUiClient.state().members().stream().noneMatch(m->m.id().equals(confirmTarget.id()))){confirm=null;confirmTarget=null;}
        if(confirm==Confirm.LEAVE&&!PartyUiClient.state().inParty())confirm=null;
    }
    void result(boolean ok,String message){busy=false;notice(message);if(ok)sound(1.2f,.18f);}

    private UUID self(){return client.player==null?null:client.player.getUuid();}
    private boolean leader(){var l=PartyUiClient.state().leader();return l!=null&&l.id().equals(self());}
    /** 파티가 있으면 파티원, 없으면 나에게 온 초대. */
    private List<Person> rows(){var s=PartyUiClient.state();return s.inParty()?s.members():s.invites();}
    private boolean canInvite(){var s=PartyUiClient.state();return !s.inParty()||leader()&&s.members().size()<s.capacity();}
    private List<Person> visibleCandidates(){
        String q=search==null?"":search.text().strip().toLowerCase(Locale.ROOT);
        return PartyUiClient.candidates().stream().filter(p->q.isEmpty()||p.nickname().toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    /** 마지막 줄 아래 간격은 빼고 계산한다. */
    private static float maxScroll(int count,float step,float view){float gap=step==ROW_STEP?ROW_STEP-ROW_H:C_STEP-C_H;return Math.max(0,count*step-gap-view);}

    private boolean act(int action,UUID target){
        if(busy)return false;
        if(!PartyUiClient.send(action,target)){notice("파티 서버에 연결되어 있지 않아요. 잠시 후 다시 열어 주세요.");return false;}
        busy=true;busyAt=Util.getMeasuringTimeMs();sound(1.13f,.2f);return true;
    }

    @Override public void tick(){
        super.tick();if(client.currentScreen!=this)return;long now=Util.getMeasuringTimeMs();
        if(busy&&now-busyAt>BUSY_TIMEOUT)busy=false;
        if(inviteOpen&&now>=nextCandidates){nextCandidates=now+CANDIDATE_REFRESH;PartyUiClient.send(PartyUiProtocol.CANDIDATES,null);}
    }

    // ---------------------------------------------------------------- 그리기
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        if(parent!=null){parent.render(c,-1,-1,delta);c.getMatrices().push();c.getMatrices().translate(0,0,200);c.fill(0,0,width,height,0x99060A12);}
        try{renderParty(c,mouseX,mouseY,delta);}finally{if(parent!=null)c.getMatrices().pop();}
    }
    private void renderParty(DrawContext c,int mouseX,int mouseY,float delta){
        long now=Util.getMeasuringTimeMs();float dt=lastFrame==0?0:Math.min(.1f,(now-lastFrame)/1000f);lastFrame=now;
        openT=Math.clamp(openT+(inviteOpen?dt:-dt)/.22f,0,1);
        var f=fit();double mx=f.x(mouseX),my=f.y(mouseY);boolean modal=confirm!=null;
        int newHot=0;
        start(c);
        try{
            var state=PartyUiClient.state();
            // ---- 파티 패널
            stretch(images,c,MAIN_PANEL,48,70,982,798,0xFFFFFFFF);
            images.drawTexture(c,STAR,88,110,0,0,64,64,128,128,128,128,0xFFFFFFFF);
            type.draw(c,"파티",172,142,44,TITLE_GOLD,TITLE,false);
            boolean overClose=!modal&&near(mx,my,978,124,24);if(overClose)newHot=1;
            icon(images,c,CLOSE,978,124,46,overClose?0xFFFFFFFF:0xFFD8DCE2);
            String sub=state.inParty()?"함께하는 동료 "+state.members().size()+"명":state.invites().isEmpty()?"아직 파티가 없어요":"받은 파티 초대 "+state.invites().size()+"건";
            type.draw(c,sub,95,227,25,SUB,SERIF,false);
            if(canInvite()){
                boolean over=!modal&&in(mx,my,836,188,154,58);if(over)newHot=2;
                nine(images,c,INVITE_BUTTON,836,188,154,58,58f/112,over||inviteOpen?0xFFFFFFFF:0xFFE2E2E2);
                icon(images,c,PLUS,880,217,24,0xFFFFFFFF);
                type.draw(c,"초대",904,217,25,over?0xFFFFF0C8:GOLD_TEXT,BOLD,false);
            }
            var list=rows();
            scroll=Math.clamp(scroll,0,maxScroll(list.size(),ROW_STEP,LIST_H));
            c.enableScissor(60,262,1020,ROUND(ROW_TOP+LIST_H+6));
            try{
                for(int i=0;i<list.size();i++){
                    float top=ROW_TOP+i*ROW_STEP-scroll;if(top+ROW_H<262||top>ROW_TOP+LIST_H+6)continue;
                    int h=state.inParty()?memberRow(c,list.get(i),top,mx,my,modal,i):inviteRow(c,list.get(i),top,mx,my,modal,i);
                    if(h!=0)newHot=h;
                }
            }finally{c.disableScissor();}
            if(list.isEmpty())type.draw(c,"동료를 초대하면 파티가 만들어져요",539,470,23,MUTED,BODY,true);
            if(list.size()>4)scrollbar(c,1006,ROW_TOP,LIST_H,scroll,maxScroll(list.size(),ROW_STEP,LIST_H),list.size()*ROW_STEP);
            if(state.inParty()){
                boolean over=!modal&&in(mx,my,378,779,316,54);if(over)newHot=3;
                nine(images,c,LEAVE,378,779,316,54,54f/121,over?0xFFFFFFFF:0xFFE0E0E0);
                leaveIcon(c,466,806,over?0xFFF3DDA6:TEXT);
                type.draw(c,"파티 나가기",490,806,22,over?0xFFF3DDA6:TEXT,BODY,false);
            }
            // ---- 초대 패널
            if(openT>0){
                float a=ease(openT);int tint=PartyUiAtlas.alpha(0xFFFFFFFF,a);
                c.getMatrices().push();c.getMatrices().translate((1-a)*-40,0,0);
                try{int h=invitePanel(c,mx+(1-a)*40,my,modal||openT<1,a,tint);if(h!=0)newHot=h;}
                finally{c.getMatrices().pop();}
            }
            if(busy){float t=now/180f;for(int i=0;i<3;i++)HudMesh.disk(c,722+i*18,806,3,((int)(100+155*(.5+.5*Math.sin(t-i)))<<24)|0x92E7F2);}
            if(modal)newHot=confirmDialog(c,mx,my);
            c.getMatrices().push();c.getMatrices().translate(0,0,60);toast(c,300,880,478);c.getMatrices().pop();
        }finally{end(c);}
        hot=newHot;hover(hot);
    }
    private static int ROUND(float v){return Math.round(v);}

    private int memberRow(DrawContext c,Person p,float top,double mx,double my,boolean modal,int index){
        boolean me=p.id().equals(self()),inList=my>=262&&my<ROW_TOP+LIST_H+6;int hit=0;
        nine(images,c,MEMBER_ROW,ROW_X,top,ROW_W,ROW_H,.5f,0xFFFFFFFF);
        avatar(c,p,115,top+17,82,p.online());
        icon(images,c,p.online()?ONLINE:OFFLINE,221,top+81,17,0xFFFFFFFF);
        String name=fit(p.nickname(),29,me?250:280,BOLD);
        type.draw(c,name,248,top+44,29,p.online()?0xFFFFFFFF:0xFFB4BCC4,BOLD,false);
        if(p.leader()){
            float x=248+type.width(name,29,BOLD)+16;
            icon(images,c,CROWN,x+12,top+46,22,0xFFFFFFFF);
            HudMesh.line(c,x+36,top+35,x+36,top+57,1.2f,0x88D8BB7A);
            type.draw(c,"파티장",x+48,top+46,21,GOLD_TEXT,BODY,false);
        }
        type.draw(c,p.online()?"온라인":"오프라인",249,top+81,19,p.online()?GREEN:OFF,BODY,false);
        float by=top+32;
        if(leader()&&!me){
            if(button(c,"정보",INFO,546,by,133,BLUE,BLUE_HOVER,BLUE_TEXT,mx,my,!modal&&inList,p.online()))hit=100+index*3;
            if(button(c,"위임",CROWN,693,by,133,PartyUiAtlas.GOLD,GOLD_HOVER,GOLD_TEXT,mx,my,!modal&&inList,true))hit=101+index*3;
            if(button(c,"강퇴",KICK,841,by,133,ROSE,ROSE_HOVER,ROSE_TEXT,mx,my,!modal&&inList,true))hit=102+index*3;
        }else if(button(c,"정보",INFO,816,by,159,BLUE,BLUE_HOVER,BLUE_TEXT,mx,my,!modal&&inList,p.online()||me))hit=100+index*3;
        return hit;
    }
    /** 파티가 없을 때: 나에게 온 초대 한 줄 (수락·거절). */
    private int inviteRow(DrawContext c,Person p,float top,double mx,double my,boolean modal,int index){
        boolean inList=my>=262&&my<ROW_TOP+LIST_H+6;int hit=0;
        nine(images,c,MEMBER_ROW,ROW_X,top,ROW_W,ROW_H,.5f,0xFFFFFFFF);
        avatar(c,p,115,top+17,82,true);
        type.draw(c,fit(p.nickname(),29,280,BOLD),248,top+44,29,0xFFFFFFFF,BOLD,false);
        type.draw(c,"님이 파티에 초대했어요",249,top+81,19,SUB,BODY,false);
        if(button(c,"수락",null,693,top+32,133,BLUE,BLUE_HOVER,BLUE_TEXT,mx,my,!modal&&inList,true))hit=400+index*2;
        if(button(c,"거절",null,841,top+32,133,ROSE,ROSE_HOVER,ROSE_TEXT,mx,my,!modal&&inList,true))hit=401+index*2;
        return hit;
    }
    private int invitePanel(DrawContext c,double mx,double my,boolean inactive,float a,int tint){
        int hit=0;
        stretch(images,c,INVITE_PANEL,1062,95,563,567,tint);
        images.drawTexture(c,STAR,1102,132,0,0,52,52,128,128,128,128,tint);
        type.draw(c,"파티 초대",1177,158,34,PartyUiAtlas.alpha(TITLE_GOLD,a),TITLE,false);
        boolean overClose=!inactive&&near(mx,my,1578,142,22);if(overClose)hit=5;
        icon(images,c,CLOSE,1578,142,42,PartyUiAtlas.alpha(overClose?0xFFFFFFFF:0xFFD8DCE2,a));
        nine(images,c,SEARCH_FIELD,1099,207,489,59,59f/91,tint);
        icon(images,c,SEARCH,1132,237,30,PartyUiAtlas.alpha(0xFFD0D8E0,a));
        if(a>=.99f)search.draw(c,1156,207,420,59,"닉네임 검색");
        var list=visibleCandidates();
        candidateScroll=Math.clamp(candidateScroll,0,maxScroll(list.size(),C_STEP,C_LIST_H));
        boolean inList=my>=282&&my<C_TOP+C_LIST_H;
        c.enableScissor(1080,282,1600,ROUND(C_TOP+C_LIST_H));
        try{
            for(int i=0;i<list.size();i++){
                float top=C_TOP+i*C_STEP-candidateScroll;if(top+C_H<282||top>C_TOP+C_LIST_H)continue;
                var p=list.get(i);
                nine(images,c,SEARCH_ROW,C_X,top,C_W,C_H,.55f,tint);
                avatar(c,p,1113,top+15,75,true);
                icon(images,c,ONLINE,1213,top+67,16,tint);
                type.draw(c,fit(p.nickname(),26,190,BOLD),1240,top+34,26,PartyUiAtlas.alpha(0xFFFFFFFF,a),BOLD,false);
                type.draw(c,"온라인",1240,top+67,18,PartyUiAtlas.alpha(GREEN,a),BODY,false);
                boolean invited=p.invited();
                boolean over=!inactive&&!invited&&inList&&in(mx,my,1446,top+24,123,53);if(over)hit=200+i;
                nine(images,c,over?BLUE_HOVER:BLUE,1446,top+24,123,53,53f/93,PartyUiAtlas.alpha(invited?0x88FFFFFF:0xFFFFFFFF,a));
                if(invited)type.draw(c,"초대함",1507,top+50,20,PartyUiAtlas.alpha(MUTED,a),BODY,true);
                else{icon(images,c,PLUS,1476,top+50,20,PartyUiAtlas.alpha(0xFFDCEBFA,a));type.draw(c,"초대",1492,top+50,21,PartyUiAtlas.alpha(over?0xFFFFFFFF:BLUE_TEXT,a),BODY,false);}
            }
        }finally{c.disableScissor();}
        if(list.isEmpty()){
            String empty=PartyUiClient.candidatesAt()==0?"동료 목록을 불러오는 중…":PartyUiClient.candidates().isEmpty()?"초대할 수 있는 동료가 없어요":"검색 결과가 없어요";
            type.draw(c,empty,1343,440,21,PartyUiAtlas.alpha(MUTED,a),BODY,true);
        }
        scrollbar(c,1595,295,330,candidateScroll,maxScroll(list.size(),C_STEP,C_LIST_H),list.size()*C_STEP);
        return hit;
    }
    private int confirmDialog(DrawContext c,double mx,double my){
        c.getMatrices().push();c.getMatrices().translate(0,0,40);int hit=0;
        try{
            HudMesh.quad(c,48,70,982,798,0x99050B14,0x99050B14);
            stretch(images,c,TOAST,258,398,563,150,0xFFFFFFFF);
            String name=confirmTarget==null?"":confirmTarget.nickname();
            String text=switch(confirm){case LEAVE->leader()&&PartyUiClient.state().members().size()>1?"파티를 나갈까요? 파티장은 다른 동료에게 넘어가요.":"파티를 나갈까요?";case PROMOTE->name+"님에게 파티장을 넘길까요?";case KICK->name+"님을 파티에서 내보낼까요?";};
            type.draw(c,text,539,445,Math.min(23,23*500/Math.max(1,type.width(text,23,BODY))),TEXT,BODY,true);
            var yes=confirm==Confirm.KICK?ROSE:confirm==Confirm.PROMOTE?PartyUiAtlas.GOLD:BLUE;var yesHover=confirm==Confirm.KICK?ROSE_HOVER:confirm==Confirm.PROMOTE?GOLD_HOVER:BLUE_HOVER;
            int yesText=confirm==Confirm.KICK?ROSE_TEXT:confirm==Confirm.PROMOTE?GOLD_TEXT:BLUE_TEXT;
            String label=switch(confirm){case LEAVE->"나가기";case PROMOTE->"위임";case KICK->"강퇴";};
            if(button(c,label,null,384,480,150,yes,yesHover,yesText,mx,my,true,true))hit=300;
            if(button(c,"취소",null,546,480,150,BLUE,BLUE_HOVER,BLUE_TEXT,mx,my,true,true))hit=301;
        }finally{c.getMatrices().pop();}
        return hit;
    }
    /** @return 마우스가 위에 있으면 true */
    private boolean button(DrawContext c,String label,Piece icon,float x,float y,float w,Piece normal,Piece hover,int textColor,double mx,double my,boolean live,boolean enabled){
        boolean over=live&&enabled&&in(mx,my,x,y,w,52);
        nine(images,c,over?hover:normal,x,y,w,52,52f/93,enabled?0xFFFFFFFF:0x77FFFFFF);
        int color=enabled?(over?0xFFFFFFFF:textColor):0xFF7D8894;
        if(icon==null){type.draw(c,label,x+w/2,y+26,21,color,BODY,true);return over;}
        float textW=type.width(label,21,BODY),total=24+10+textW,left=x+(w-total)/2;
        PartyUiAtlas.icon(images,c,icon,left+12,y+26,icon==KICK||icon==INFO?22:19,enabled?0xFFFFFFFF:0x77FFFFFF);
        type.draw(c,label,left+34,y+26,21,color,BODY,false);
        return over;
    }
    private void avatar(DrawContext c,Person p,float x,float y,float size,boolean online){
        int inset=Math.round(size*.085f),fx=Math.round(x)+inset,fy=Math.round(y)+inset,fs=Math.round(size)-2*inset;
        HudMesh.quad(c,fx,fy,fs,fs,0xFF0B1826,0xFF0B1826);
        var skin=PartyUiClient.face(p);
        c.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,8,8,fs,fs,8,8,64,64);
        c.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,40,8,fs,fs,8,8,64,64);
        if(!online)HudMesh.quad(c,fx,fy,fs,fs,0x88071019,0x88071019);
        stretch(images,c,AVATAR_FRAME,x,y,size,size,0xFFFFFFFF);
    }
    private void scrollbar(DrawContext c,float x,float y,float h,float value,float max,float content){
        if(max<=0)return;
        nine(images,c,SCROLL_TRACK,x,y,8,h,8f/35,0xFFFFFFFF);
        float thumb=Math.max(40,h*h/Math.max(h,content)),ty=y+(h-thumb)*value/max;
        nine(images,c,SCROLL_THUMB,x+.5f,ty,7,thumb,7f/31,0xFFFFFFFF);
    }
    private static void leaveIcon(DrawContext c,float x,float y,int color){
        // 문 + 바깥 화살표
        HudMesh.line(c,x-2,y-10,x-10,y-10,1.6f,color);HudMesh.line(c,x-10,y-10,x-10,y+10,1.6f,color);HudMesh.line(c,x-10,y+10,x-2,y+10,1.6f,color);
        HudMesh.line(c,x-4,y,x+10,y,1.6f,color);HudMesh.line(c,x+10,y,x+5,y-5,1.6f,color);HudMesh.line(c,x+10,y,x+5,y+5,1.6f,color);
    }
    private String fit(String value,float size,float room,Identifier face){
        if(type.width(value,size,face)<=room)return value;
        var cps=value.codePoints().toArray();
        for(int n=cps.length-1;n>0;n--){String s=new String(cps,0,n)+"…";if(type.width(s,size,face)<=room)return s;}
        return "…";
    }
    private static boolean near(double x,double y,float cx,float cy,float r){return (x-cx)*(x-cx)+(y-cy)*(y-cy)<=r*r;}

    // ---------------------------------------------------------------- 입력
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return super.mouseClicked(x,y,button);
        var f=fit();double mx=f.x(x),my=f.y(y);var state=PartyUiClient.state();
        if(confirm!=null){
            if(hot==300){
                var target=confirmTarget;var kind=confirm;
                if(act(kind==Confirm.LEAVE?PartyUiProtocol.LEAVE:kind==Confirm.PROMOTE?PartyUiProtocol.PROMOTE:PartyUiProtocol.KICK,kind==Confirm.LEAVE?null:target.id())){confirm=null;confirmTarget=null;}
            }else if(hot==301||!in(mx,my,258,398,563,150)){confirm=null;confirmTarget=null;sound(1,.15f);}
            return true;
        }
        if(hot==1){close();return true;}
        if(hot==2){toggleInvite();return true;}
        if(hot==3){confirm=Confirm.LEAVE;confirmTarget=null;sound(1.1f,.2f);return true;}
        if(hot==5){toggleInvite();return true;}
        if(hot>=100&&hot<300){
            var list=rows();
            if(hot<200){
                int index=(hot-100)/3,action=(hot-100)%3;if(index>=list.size())return true;var p=list.get(index);
                if(action==0)info(p);
                else{confirm=action==1?Confirm.PROMOTE:Confirm.KICK;confirmTarget=p;sound(1.1f,.2f);}
            }else{
                var candidates=visibleCandidates();int index=hot-200;
                if(index<candidates.size())act(PartyUiProtocol.INVITE,candidates.get(index).id());
            }
            return true;
        }
        if(hot>=400&&hot<500){
            var invites=state.invites();int index=(hot-400)/2;
            if(index<invites.size())act((hot-400)%2==0?PartyUiProtocol.ACCEPT:PartyUiProtocol.DENY,invites.get(index).id());
            return true;
        }
        if(openT>=1){
            if(search.click(mx,my))return true;
            if(in(mx,my,1590,290,18,340)){dragging=draggingCandidates=true;dragY=my;return true;}
            if(in(mx,my,C_X,C_TOP,C_W,C_LIST_H)){dragging=true;draggingCandidates=true;dragY=my;return true;}
        }
        if(in(mx,my,ROW_X,ROW_TOP,ROW_W,LIST_H)){dragging=true;draggingCandidates=false;dragY=my;return true;}
        search.focus(false);
        return true;
    }
    private void info(Person p){
        if(p.id().equals(self())){MagicCodexClient.dismiss();client.setScreen(new StatsScreen());return;}
        if(!p.online()){notice("접속 중인 동료만 정보를 볼 수 있어요.");return;}
        sound(1.13f,.2f);client.setScreen(null);
        if(client.getNetworkHandler()!=null)client.getNetworkHandler().sendChatCommand("스탯창 "+p.id());
    }
    private void toggleInvite(){
        inviteOpen=!inviteOpen;sound(1.1f,.2f);
        if(inviteOpen){nextCandidates=Util.getMeasuringTimeMs()+CANDIDATE_REFRESH;PartyUiClient.send(PartyUiProtocol.CANDIDATES,null);search.focus(true);}
        else search.focus(false);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!dragging||button!=0)return false;double my=fit().y(y);
        if(draggingCandidates)candidateScroll=(float)Math.clamp(candidateScroll+dragY-my,0,maxScroll(visibleCandidates().size(),C_STEP,C_LIST_H));
        else scroll=(float)Math.clamp(scroll+dragY-my,0,maxScroll(rows().size(),ROW_STEP,LIST_H));
        dragY=my;return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(confirm!=null)return true;var f=fit();double mx=f.x(x),my=f.y(y);
        if(openT>=1&&in(mx,my,C_X,C_TOP,C_W+30,C_LIST_H)){candidateScroll=(float)Math.clamp(candidateScroll-vertical*58,0,maxScroll(visibleCandidates().size(),C_STEP,C_LIST_H));return true;}
        if(in(mx,my,ROW_X,ROW_TOP,ROW_W+30,LIST_H)){scroll=(float)Math.clamp(scroll-vertical*63,0,maxScroll(rows().size(),ROW_STEP,LIST_H));return true;}
        return false;
    }
    @Override public boolean charTyped(char ch,int mods){if(inviteOpen&&search.focus()){candidateScroll=0;return search.typed(ch,mods);}return super.charTyped(ch,mods);}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){
            if(confirm!=null){confirm=null;confirmTarget=null;return true;}
            if(inviteOpen){toggleInvite();return true;}
            close();return true;
        }
        if(confirm!=null){
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){hot=300;mouseClicked(0,0,0);}
            return true;
        }
        if(inviteOpen&&search.focus()){candidateScroll=0;return search.key(key,scan,mods);}
        return super.keyPressed(key,scan,mods);
    }
    @Override public void close(){sound(.95f,.18f);if(parent!=null&&client!=null)client.setScreen(parent);else super.close();}
}
