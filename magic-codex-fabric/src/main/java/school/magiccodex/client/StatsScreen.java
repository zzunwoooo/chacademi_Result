package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import static school.magiccodex.client.StatsLayout.*;

/**
 * 정보창 v4 (내 정보 · 상대 정보, 오너 납품 docs/design/profile-ui-v4 · 2026-10-26).
 * 패널·아이콘은 납품 PNG 그대로, 글자·수치·구분선·진행 막대·닫기·꺾쇠·포커스는 개발 프롬프트 runtime 값으로 코드에서 그린다.
 * 레퍼런스에 없는 장식은 넣지 않는다. 연결 안 된 기능은 알파 0.4 + 마우스를 올리면 이유.
 * 본인 = 단축키(StatsClient)·상단 메뉴, 상대 = 서버가 여는 창(/스탯창 닉, 웅크리고 우클릭). 제목은 둘 다 "정보창".
 */
public final class StatsScreen extends Screen {
    private static final String ROOT="magiccodex:textures/gui/profile/";
    private static final Identifier MAIN=Identifier.of("magiccodex","textures/gui/profile/panels/01_main_window.png"),
            SECTION=Identifier.of("magiccodex","textures/gui/profile/panels/02_section_panel.png"),
            SLOT=Identifier.of("magiccodex","textures/gui/profile/panels/03_equipment_slot.png"),
            BUTTON=Identifier.of("magiccodex","textures/gui/profile/panels/04_button_normal.png"),
            BUTTON_HOVER=Identifier.of("magiccodex","textures/gui/profile/panels/05_button_hover.png"),
            QUEST_ROW=Identifier.of("magiccodex","textures/gui/profile/panels/06_quest_row.png");
    private static final String[] STAT_ICONS={"intelligence","max-health","mana","mana-regen","magic-haste","defense"};
    private static final String[] STAT_NAMES={"지능","최대 체력","마나","마나 회복","마법 가속","방어력"};
    /** assets/tiers/tier-mapping.json (tier 0~8) — 원본 PNG 는 기존 큐디치 경지 텍스처와 같은 파일(해시 동일), 수정 금지. */
    private static final String[] TIER_FILES={"bronze","silver","gold","platinum","diamond","master","challenger_1","challenger_2","challenger_3"};
    private static final String[] TIER_NAMES={"브론즈","실버","골드","플래티넘","다이아몬드","마스터","챌린저 1","챌린저 2","챌린저 3"};
    private static final String[] ARMOR_NAMES={"머리","상의","하의","신발"},ACCESSORY_NAMES={"아티팩트","반지","귀걸이","목걸이","빗자루"};
    private static final Identifier BODY=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    // layout-metrics.json runtime
    private static final int TEXT=0xFFF4F7FF,MUTED=0xFFB6D9F4,GOLD=0xFFF5D18A,DIVIDER=0xFF35516D,TRACK=0xFF1C3554,FILL=0xFF80B7F8,STROKE=0xFF48617D,FOCUS=0xFFB6D9F4;
    private static final float DISABLED=.4f;
    private final Supplier<StatsValues> source;
    private final boolean live;
    private RemoteStatsClient.View remote;
    private CodexTypography text;
    private HudTextureCache images;
    private boolean sounded,opened;
    private int hovered=-1,focused=-1;
    private long noticeAt=-1,lastClick=-1000;
    private String notice="";
    private int resourceGeneration=-1;
    private float lookX,lookY;
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    /** 화면의 클릭·포커스 대상 하나. reason = 비활성 이유 (null 이면 활성). */
    private record Action(Box box,String label,String reason,Runnable run){boolean enabled(){return reason==null;}}

    public StatsScreen(){this(StatsClient::values,true);}
    public StatsScreen(RemoteStatsClient.View view){this(view::values,false);remote=view;}
    /** Preview fixture shares production rendering, but never subscribes to permissions. */
    public StatsScreen(Supplier<StatsValues> source,boolean live){super(Text.literal("정보창"));this.source=source;this.live=live;}
    public boolean displays(RemoteStatsClient.View view){return remote==view;}
    public StatsValues displayedValues(){return source.get();}
    public boolean isLive(){return live;}
    public void showNotice(String message){notice=message==null?"":message;noticeAt=Util.getMeasuringTimeMs();}
    /** 내 정보창에 서버 스냅샷(내 것)을 붙인다. 상대 정보창이면 false. */
    boolean attach(RemoteStatsClient.View view){
        if(!live||view==null||!view.self())return false;
        if(remote!=null&&remote!=view)remote.close();
        remote=view;return true;
    }
    private boolean self(){return live||remote!=null&&remote.self();}
    public net.minecraft.entity.player.PlayerEntity portraitPlayer(){return self()&&client!=null?client.player:remote==null?null:remote.avatar();}
    private UUID targetId(){return remote!=null?remote.id():client!=null&&client.player!=null?client.player.getUuid():null;}

    @Override protected void init(){
        text=UiResources.text();images=UiResources.images();resourceGeneration=UiResources.generation();
        if(!sounded){sound(1.12f,.20f);sounded=true;}
        if(!opened){
            opened=true;
            if(live)RemoteStatsClient.requestSelf();
            StatsRanks.request(self()||remote==null?"":remote.profile().name()); // remote==null 은 미리보기 고정값(live=false)뿐
            if(remote!=null&&!self())OtherPortraits.open(remote.id());
        }
    }
    public void invalidateResources(){text=null;images=null;}

    // ------------------------------------------------------------------ 값 (null = 모름 → "—")
    private Double popularity(StatsValues v){return remote!=null?remote.popularity():v.popularity();}
    private RemoteStatsClient.View extended(){return remote!=null&&remote.extended()?remote:null;}

    @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta){
        if(text==null||images==null||resourceGeneration!=UiResources.generation())init();
        var l=StatsLayout.fit(width,height);var v=source.get();long now=Util.getMeasuringTimeMs();
        double mx=l.localX(mouseX),my=l.localY(mouseY);
        var actions=actions();
        int hover=-1;for(int i=0;i<actions.size();i++)if(actions.get(i).box().contains(mx,my))hover=i;
        if(hover>=0&&hover!=hovered&&actions.get(hover).enabled())sound(1.4f,.07f);hovered=hover;
        lookX+=((float)Math.clamp((mx-PORTRAIT.centerX())*.06,-14,14)-lookX)*.2f;
        lookY+=((float)Math.clamp((my-PORTRAIT.centerY())*.05,-10,10)-lookY)*.2f;
        text.beginFrame();images.beginFrame();ctx.fill(0,0,width,height,0x30040911);
        float line=Math.max(1f,1f/l.scale()); // 1px 구분선이 작은 창에서도 사라지지 않게
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(l.x(),l.y(),0);ctx.getMatrices().scale(l.scale(),l.scale(),1);
            images.drawTexture(ctx,MAIN,0,0,0,0,WIDTH,HEIGHT,WIDTH,HEIGHT);
            portrait(ctx,mx,my);
            ctx.getMatrices().push();ctx.getMatrices().translate(0,0,200);
            dividers(ctx,line);
            header(ctx,actions,line);
            identity(ctx,v,actions,line);
            cards(ctx);
            adventure(ctx,v);
            equipment(ctx,actions);
            stats(ctx,v);
            buttons(ctx,actions);
            drawNotice(ctx,now);
            tooltip(ctx,actions,v,mx,my);
            ctx.getMatrices().pop();
        }finally{ctx.getMatrices().pop();images.endFrame();}
        entrance.draw(ctx,width,height);
    }

    // ------------------------------------------------------------------ 클릭·포커스 대상
    private List<Action> actions(){
        var out=new ArrayList<Action>();
        boolean self=self();
        out.add(new Action(CLOSE,"닫기",null,this::close));
        out.add(new Action(TITLE_LINK,"칭호 자세히",self&&live?null:"상대의 칭호 목록은 볼 수 없어요",()->TitleClient.open()));
        String voteReason=self?"자신의 인기도는 조절할 수 없어요":extended()==null?"서버 업데이트 후 사용할 수 있어요":null;
        out.add(new Action(POPULARITY_BUTTONS[0],"인기도 올리기",voteReason,()->social(StatsSocialActions.Action.POPULARITY)));
        out.add(new Action(POPULARITY_BUTTONS[1],"인기도 내리기",voteReason,()->social(StatsSocialActions.Action.POPULARITY_DOWN)));
        String housing=HousingClient.supported()?null:"하우징 서버에 연결되어 있지 않아요";
        if(self){
            out.add(new Action(BOTTOM_BUTTONS[0],"내 방 가기",housing,()->visit(targetId())));
            out.add(new Action(BOTTOM_BUTTONS[1],"친구 목록",SocialClient.supported()?null:"친구 기능에 연결되어 있지 않아요",()->{SocialClient.open();}));
        }else{
            boolean friend=friend();
            out.add(new Action(BOTTOM_BUTTONS[0],"상대 방 가기",housing!=null?housing:friend?null:"친구의 방만 방문할 수 있어요",()->visit(remote.id())));
            out.add(new Action(BOTTOM_BUTTONS[1],"친구 추가",friend?"이미 친구예요":null,()->social(StatsSocialActions.Action.FRIEND)));
        }
        out.add(new Action(EQUIPMENT_LINK,"장비 자세히",self&&live?null:"상대의 장비 창은 열 수 없어요",()->{EquipmentClient.open();}));
        return out;
    }
    private boolean friend(){return remote!=null&&SocialClient.entries().stream().anyMatch(e->e.id().equals(remote.id()));}
    private void visit(UUID owner){
        if(owner==null)return;
        if(HousingClient.send(new HousingProtocol.Visit(owner)))close();
        else showNotice("하우징 서버에 연결되어 있지 않아요.");
    }
    private void social(StatsSocialActions.Action action){
        if(remote==null||self()){showNotice("다른 플레이어의 정보창에서 사용할 수 있어요.");return;}
        try{showNotice(remote.action(action));}
        catch(RuntimeException error){showNotice("요청을 처리하지 못했습니다.");org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Stats action failed",error);}
    }
    private void activate(Action a){
        if(!a.enabled()){showNotice(a.reason());return;}
        long now=Util.getMeasuringTimeMs();if(now-lastClick<400)return;lastClick=now;
        sound(1.12f,.2f);a.run().run();
    }

    // ------------------------------------------------------------------ 그리기
    private void dividers(DrawContext c,float w){
        hline(c,48,1623,HEADER_LINE_Y,w);
        vline(c,COLUMN_LINE_1,119,890,w);vline(c,COLUMN_LINE_2,119,890,w);
        for(int y:RIGHT_LINES)hline(c,1298,1608,y,w);
        for(int y:CENTER_LINES)hline(c,564,1255,y,w);
        hline(c,58,527,LEFT_DIVIDER_1,w);hline(c,58,527,LEFT_DIVIDER_2,w);
        hline(c,362,528,IDENTITY_DIVIDER_Y,w);
        vline(c,POP_DIVIDER_X,741,774,w);
    }
    private void hline(DrawContext c,float x1,float x2,float y,float w){HudMesh.line(c,x1,y,x2,y,w,DIVIDER);}
    private void vline(DrawContext c,float x,float y1,float y2,float w){HudMesh.line(c,x,y1,x,y2,w,DIVIDER);}
    private void header(DrawContext c,List<Action> actions,float w){
        HudMesh.line(c,TITLE_BAR_X,TITLE_BAR_Y1,TITLE_BAR_X,TITLE_BAR_Y2,Math.max(2,w),GOLD);
        draw(c,"정보창",TITLE_X,TITLE_Y,FONT_TITLE,TEXT,BOLD,0,400);
        var b=CLOSE_ICON;int color=hovered==0?0xFFFFE4A8:GOLD;
        HudMesh.line(c,b.x(),b.y(),b.right(),b.bottom(),3,color);HudMesh.line(c,b.right(),b.y(),b.x(),b.bottom(),3,color);
        if(focused==0)focus(c,CLOSE);
    }
    private void portrait(DrawContext c,double mx,double my){
        var r=PORTRAIT;UUID id=targetId();boolean drawn=false;
        if(self()){
            if(PortraitClient.ready()){illustration(c,PortraitClient.imageWidth(),PortraitClient.imageHeight(),(x,w)->PortraitClient.draw(c,x,r.y(),w));drawn=true;}
        }else if(id!=null){
            var status=OtherPortraits.status(id);
            if(status==OtherPortraits.Status.READY){illustration(c,OtherPortraits.width(id),OtherPortraits.height(id),(x,w)->OtherPortraits.draw(c,id,x,r.y(),w));drawn=true;}
            else if(status==OtherPortraits.Status.LOADING){
                c.getMatrices().push();c.getMatrices().translate(0,0,200);
                draw(c,"불러오는 중",r.centerX(),r.centerY(),FONT_BODY,MUTED,BODY,1,r.w()-20);
                c.getMatrices().pop();drawn=true;
            }
        }
        if(!drawn)skin(c);
    }
    private interface Painter{void paint(int x,int w);}
    /** 일러스트: 초상 칸 높이에 맞춰 비율 유지, 가운데, 칸 밖은 잘라낸다 (원본 PNG 그대로 그림). */
    private void illustration(DrawContext c,int iw,int ih,Painter painter){
        var r=PORTRAIT;int w=Math.max(1,Math.round(r.h()*(float)iw/Math.max(1,ih)));
        c.enableScissor(r.x(),r.y(),r.right(),r.bottom());
        try{
            painter.paint(Math.round(r.centerX()-w/2f),w);
            // 일러스트마다 배경·밝기가 달라 칸 아래 경계가 튀지 않게, 아래 90px 를 창 바탕색(#0B1B30)으로 살짝 흐리게 (오너 2026-10-26)
            c.fillGradient(r.x(),r.bottom()-90,r.right(),r.bottom(),0x000B1B30,0xB00B1B30);
        }finally{c.disableScissor();}
    }
    private void skin(DrawContext c){
        var p=portraitPlayer();if(!(p instanceof LivingEntity entity))return;var r=PORTRAIT;
        float body=p.bodyYaw,yaw=p.getYaw(),pitch=p.getPitch(),head=p.headYaw,previous=p.prevHeadYaw;
        try{InventoryScreen.drawEntity(c,r.x(),r.y(),r.right(),r.bottom(),240,0,r.centerX()+lookX,r.y()+190+lookY,entity);}
        finally{p.bodyYaw=body;p.setYaw(yaw);p.setPitch(pitch);p.headYaw=head;p.prevHeadYaw=previous;}
    }
    private void identity(DrawContext c,StatsValues v,List<Action> actions,float w){
        String name=remote!=null&&!live?remote.profile().name():v.nickname();
        draw(c,name,IDENTITY.x(),NAME_Y,FONT_NAME,TEXT,BOLD,0,IDENTITY.w());
        var ext=extended();String title=ext==null?"":ext.extra().title();
        if(!title.isEmpty())draw(c,title,IDENTITY.x(),TITLE_LINE_Y,FONT_BODY,GOLD,BODY,0,IDENTITY.w());
        icon(c,"action-icons/class-attainment",ROW_ICON_X,CLASS_Y,ROW_ICON_SIZE,1);
        draw(c,v.circle()<=0?"클래스 미정":"클래스 "+v.circle(),ROW_TEXT_X,CLASS_Y,FONT_BODY,TEXT,BODY,0,528-ROW_TEXT_X);
        icon(c,"action-icons/house-school",ROW_ICON_X,HOUSE_Y,ROW_ICON_SIZE,1);
        draw(c,v.dormitory().isBlank()?"기숙사 미정":v.dormitory(),ROW_TEXT_X,HOUSE_Y,FONT_BODY,TEXT,BODY,0,528-ROW_TEXT_X);
        link(c,actions,1,"칭호 자세히",TITLE_LINK.x()+6,TITLE_LINK.centerY(),false);
        // 인기도 · 하우징 좋아요
        icon(c,"action-icons/popularity",POP_STAR_X,POP_Y,26,1);
        draw(c,"인기도 "+number(popularity(v)),POP_TEXT_X,POP_Y,FONT_BODY,TEXT,BODY,0,POPULARITY_BUTTONS[0].x()-POP_TEXT_X-6);
        for(int i=0;i<2;i++){
            var a=actions.get(2+i);var b=a.box();float alpha=a.enabled()?1:DISABLED;
            icon(c,i==0?"action-icons/vote-up":"action-icons/vote-down",(int)b.centerX(),(int)b.centerY(),hovered==2+i&&a.enabled()?30:28,alpha);
            if(focused==2+i||hovered==2+i&&a.enabled())focus(c,b);
        }
        icon(c,"action-icons/house-school",HOUSING_ICON_X,POP_Y,28,1);
        draw(c,"하우징",HOUSING_TEXT_X,POP_Y,FONT_BODY,TEXT,BODY,0,80);
        icon(c,"action-icons/housing-like",LIKE_ICON_X,POP_Y,24,1);
        draw(c,ext==null||ext.extra().likes()==null?"—":StatsLayout.thousands(ext.extra().likes()),LIKE_TEXT_X,POP_Y,FONT_BODY,TEXT,BODY,0,528-LIKE_TEXT_X);
    }
    private void cards(DrawContext c){
        boolean asked=net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(KyudichiQueueClient.Payload.ID);
        card(c,PVP,TIER_ICONS[0],"1:1 대전",KyudichiQueueProtocol.MODE_DUEL,asked);
        card(c,QUIDDITCH,TIER_ICONS[1],"큐디치",KyudichiQueueProtocol.MODE_KYUDICHI,asked);
    }
    private void card(DrawContext c,Box b,Box icon,String title,int mode,boolean connected){
        NotificationSkin.nine(images,c,SECTION,340,254,7,b.x(),b.y(),b.w(),b.h(),1,0xFFFFFFFF);
        draw(c,title,b.x()+CARD_TITLE_DX,CARD_TITLE_Y,FONT_SECTION,GOLD,BOLD,0,b.w()-CARD_TITLE_DX-12);
        HudMesh.line(c,b.x()+8,CARD_HEADER_LINE_Y,b.right()-8,CARD_HEADER_LINE_Y,1,DIVIDER);
        var p=StatsRanks.get(mode);float x=b.x()+CARD_TEXT_DX;float max=b.right()-x-10;
        if(p!=null){
            int tier=Math.clamp(p.tier(),0,TIER_FILES.length-1);
            images.drawCentered(c,"magiccodex:textures/gui/kyudichi/ui/tiers/"+TIER_FILES[tier]+".png",icon.centerX(),icon.centerY(),Math.min(icon.w(),icon.h()),0xFFFFFFFF);
            draw(c,TIER_NAMES[tier],x,CARD_TIER_Y,FONT_SECTION,TEXT,BOLD,0,max);
            draw(c,"레이팅",x+5,CARD_RATING_LABEL_Y,FONT_SMALL,MUTED,BODY,0,max);
            draw(c,StatsLayout.thousands(p.rating()),x+5,CARD_RATING_Y,40,GOLD,BOLD,0,max);
        }else{
            draw(c,"—",x,CARD_TIER_Y,FONT_SECTION,TEXT,BOLD,0,max);
            draw(c,"레이팅",x+5,CARD_RATING_LABEL_Y,FONT_SMALL,MUTED,BODY,0,max);
            draw(c,!connected?"—":StatsRanks.answered(mode)?"기록 없음":"불러오는 중",x+5,CARD_RATING_Y,FONT_BODY,MUTED,BODY,0,max);
        }
        HudMesh.line(c,x-10,CARD_RANK_LINE_Y,b.right()-14,CARD_RANK_LINE_Y,1,DIVIDER);
        draw(c,StatsLayout.rank(p==null?0:p.rank()),x+5,CARD_RANK_Y,FONT_BODY,TEXT,BODY,0,max);
    }
    private void adventure(DrawContext c,StatsValues v){
        draw(c,"모험 기록",SECTION_TEXT_X,ADVENTURE_TITLE_Y,FONT_SECTION,GOLD,BOLD,0,400);
        draw(c,"발견한 마법",SECTION_TEXT_X,SPELL_ROW_Y,FONT_BODY,TEXT,BODY,0,SPELL_BAR.x()-SECTION_TEXT_X-8);
        bar(c,SPELL_BAR,StatsLayout.progress(v.learned(),v.total()));
        draw(c,(v.learned()==null?"—":String.valueOf(v.learned()))+" / "+(v.total()<=0?"—":v.total()),ADVENTURE_VALUE_RIGHT,SPELL_ROW_Y,FONT_BODY,TEXT,BODY,2,ADVENTURE_VALUE_RIGHT-SPELL_BAR.right()-10);
        var ext=extended();
        draw(c,"진행 중 퀘스트",SECTION_TEXT_X,QUEST_ROW_Y,FONT_BODY,TEXT,BODY,0,400);
        draw(c,ext==null||ext.extra().activeQuests()==null?"—":String.valueOf(ext.extra().activeQuests()),TRACK_VALUE_RIGHT,QUEST_ROW_Y,FONT_BODY,TEXT,BODY,2,200);
        NotificationSkin.nine(images,c,QUEST_ROW,692,70,7,QUEST_PANEL.x(),QUEST_PANEL.y(),QUEST_PANEL.w(),QUEST_PANEL.h(),1,0xFFFFFFFF);
        draw(c,"추적 중인 퀘스트",TRACK_TEXT_X,TRACK_LABEL_Y,FONT_SMALL,TEXT,BODY,0,400);
        if(ext==null||ext.extra().activeQuests()==null)draw(c,"—",TRACK_TEXT_X,TRACK_NAME_Y,21,TEXT,BODY,0,TRACK_BAR.x()-TRACK_TEXT_X-12);
        else if(ext.extra().tracked().isEmpty())draw(c,"추적 중인 퀘스트 없음",TRACK_TEXT_X,TRACK_NAME_Y,21,MUTED,BODY,0,TRACK_BAR.x()-TRACK_TEXT_X-12);
        else{
            var e=ext.extra();
            draw(c,e.tracked(),TRACK_TEXT_X,TRACK_NAME_Y,21,TEXT,BODY,0,TRACK_BAR.x()-TRACK_TEXT_X-12);
            if(e.trackedRequired()>0){
                draw(c,e.trackedCurrent()+" / "+e.trackedRequired(),TRACK_VALUE_RIGHT,TRACK_LABEL_Y,FONT_SMALL,TEXT,BODY,2,200);
                bar(c,TRACK_BAR,StatsLayout.progress(e.trackedCurrent(),e.trackedRequired()));
            }
        }
    }
    /** 진행 막대: 높이 13 · 반지름 6.5 · 트랙 #1C3554 · 채움 #80B7F8 · 외곽 1px #48617D. fraction null = 빈 막대. */
    private void bar(DrawContext c,Box b,Float fraction){
        HudMesh.capsule(c,b.x(),b.y(),b.w(),b.h(),STROKE,STROKE);
        HudMesh.capsule(c,b.x()+1,b.y()+1,b.w()-2,b.h()-2,TRACK,TRACK);
        if(fraction!=null&&fraction>0)HudMesh.capsule(c,b.x()+1,b.y()+1,Math.max(b.h()-2,(b.w()-2)*fraction),b.h()-2,FILL,FILL);
    }
    private void equipment(DrawContext c,List<Action> actions){
        draw(c,"장착 장비",SECTION_TEXT_X,EQUIPMENT_TITLE_Y,FONT_SECTION,GOLD,BOLD,0,400);
        link(c,actions,actions.size()-1,"장비 자세히",EQUIPMENT_LINK.right()-6,EQUIPMENT_LINK.centerY(),true);
        for(int i=0;i<4;i++){
            var b=ARMOR_SLOTS[i];draw(c,ARMOR_NAMES[i],b.centerX(),ARMOR_LABEL_Y,FONT_SMALL,TEXT,BODY,1,b.w());
            slot(c,b);item(c,b,armor(i));
        }
        for(int i=0;i<5;i++){
            var b=ACCESSORY_SLOTS[i];draw(c,ACCESSORY_NAMES[i],b.centerX(),ACCESSORY_LABEL_Y,FONT_SMALL,TEXT,BODY,1,b.w()+20);
            slot(c,b);
            if(i==0)artifact(c,b);
            else if(i==4)broom(c,b);
            else item(c,b,accessory(i-1));
        }
    }
    private void slot(DrawContext c,Box b){NotificationSkin.nine(images,c,SLOT,144,78,8,b.x(),b.y(),b.w(),b.h(),1,0xFFFFFFFF);}
    /** 머리·상의·하의·신발: 상대·서버 스냅샷 → 그 값, 내 정보(옛 서버) → 장비 창 값. */
    private ItemStack armor(int i){
        if(remote!=null)return remote.armor(i);
        return i<EquipmentClient.equipped.size()?EquipmentClient.equipped.get(i):ItemStack.EMPTY;
    }
    /** 반지·귀걸이·목걸이 */
    private ItemStack accessory(int i){
        var ext=extended();if(ext!=null)return ext.accessory(i);
        if(live&&5+i<EquipmentClient.equipped.size())return EquipmentClient.equipped.get(5+i);
        return ItemStack.EMPTY;
    }
    private String artifactId(){
        if(live){var list=ArtifactClient.equipped();return list.isEmpty()?"":list.get(0);}
        var ext=extended();return ext==null?"":ext.extra().artifact();
    }
    private String broomId(){
        if(live)return BroomUiLink.equipped();
        var ext=extended();return ext==null?"":ext.extra().broom();
    }
    private void item(DrawContext c,Box b,ItemStack item){
        if(item==null||item.isEmpty())return;int size=Math.min(52,b.h()-20);
        c.getMatrices().push();c.getMatrices().translate(b.centerX()-size/2f,b.centerY()-size/2f,20);c.getMatrices().scale(size/16f,size/16f,1);c.drawItem(item,0,0);c.getMatrices().pop();
    }
    private void artifact(DrawContext c,Box b){
        String id=artifactId();if(id.isEmpty())return;var a=ArtifactClient.find(id);
        if(a==null){draw(c,id,b.centerX(),b.centerY(),15,TEXT,BODY,1,b.w()-12);return;}
        if(a.icon().isBlank()||!images.drawCentered(c,a.icon(),b.centerX(),b.centerY(),Math.min(56,b.h()-16),0xFFFFFFFF))
            draw(c,a.name(),b.centerX(),b.centerY(),15,ArtifactCodexScreen.GRADE_COLORS[Math.clamp(a.grade(),0,3)],BODY,1,b.w()-12);
    }
    private void broom(DrawContext c,Box b){
        String id=broomId();if(id.isBlank())return;
        c.enableScissor(b.x()+3,b.y()+3,b.right()-3,b.bottom()-3);
        try{if(!BroomUiLink.render(c,id,b.centerX(),b.centerY(),b.w()-30,90,14,0))draw(c,BroomUiLink.text("name",id),b.centerX(),b.centerY(),15,TEXT,BODY,1,b.w()-12);}
        finally{c.disableScissor();}
    }
    private void stats(DrawContext c,StatsValues v){
        draw(c,"능력치",STATS_TITLE_X,STATS_TITLE_Y,FONT_SECTION,GOLD,BOLD,0,240);
        String mana=StatsValues.number(v.mana())+" / "+StatsValues.number(v.maxMana());
        String[] values={StatsValues.number(v.power()),StatsValues.number(v.health()),mana,StatsValues.number(v.manaRegen()),StatsValues.number(v.haste()),StatsValues.number(v.armor())};
        for(int i=0;i<6;i++){
            int y=STAT_ROW_Y[i];
            icon(c,"stat-icons/"+STAT_ICONS[i],STAT_ICON_X+STAT_ICON_SIZE/2,y,STAT_ICON_SIZE,1);
            int labelY=i==4?HASTE_LABEL_Y:y;
            draw(c,STAT_NAMES[i],STAT_LABEL_X,labelY,FONT_BODY,TEXT,BODY,0,120);
            draw(c,values[i],STAT_VALUE_RIGHT,labelY,25,TEXT,BODY,2,STAT_VALUE_RIGHT-STAT_LABEL_X-120);
        }
        Double reduction=StatsLayout.hasteReduction(v.haste());
        draw(c,"재사용 대기시간 감소",STAT_LABEL_X,HASTE_NOTE_Y,FONT_FOOTNOTE+2,MUTED,BODY,0,160);
        draw(c,reduction==null?"—":String.format(java.util.Locale.ROOT,"%.1f%%",reduction),STAT_VALUE_RIGHT,HASTE_NOTE_Y,FONT_FOOTNOTE+2,MUTED,BODY,2,70);
    }
    private void buttons(DrawContext c,List<Action> actions){
        boolean self=self();
        for(int i=0;i<2;i++){
            int index=4+i;var a=actions.get(index);var b=a.box();float alpha=a.enabled()?1:DISABLED;
            boolean hover=hovered==index&&a.enabled();
            NotificationSkin.nine(images,c,hover?BUTTON_HOVER:BUTTON,228,65,12,b.x(),b.y(),b.w(),b.h(),1,tint(alpha));
            int color=alpha(TEXT,alpha);
            if(i==1){
                // 친구 아이콘 + 글자 (레퍼런스·조립 미리보기). 방 가기 버튼은 글자만 (방 아이콘 PNG 없음).
                icon(c,self?"action-icons/friend-list":"action-icons/friend-add",b.x()+59,(int)b.centerY(),36,alpha);
                draw(c,a.label(),b.x()+135,b.centerY(),FONT_BODY,color,BODY,1,b.w()-100);
            }else draw(c,a.label(),b.centerX(),b.centerY(),FONT_BODY,color,BODY,1,b.w()-24);
            if(focused==index)focus(c,b);
        }
    }
    /** "칭호 자세히 >" · "장비 자세히 >": 글자 + 꺾쇠 7×14 (1.5px #B6D9F4). right = true 면 x 가 꺾쇠 오른쪽 끝. */
    private void link(DrawContext c,List<Action> actions,int index,String label,float x,float y,boolean right){
        var a=actions.get(index);float alpha=a.enabled()?1:DISABLED;boolean hover=hovered==index&&a.enabled();
        int color=alpha(hover?TEXT:MUTED,alpha);
        float tw=text.width(label,FONT_SMALL,BODY),total=tw+12+7,left=right?x-total:x;
        draw(c,label,left,y,FONT_SMALL,color,BODY,0,200);
        float cx=left+tw+12;
        HudMesh.line(c,cx,y-7,cx+7,y,1.5f,color);HudMesh.line(c,cx+7,y,cx,y+7,1.5f,color);
        if(focused==index)focus(c,a.box());
    }
    private void focus(DrawContext c,Box b){
        float x1=b.x()-2,y1=b.y()-2,x2=b.right()+2,y2=b.bottom()+2;
        HudMesh.line(c,x1,y1,x2,y1,2,FOCUS);HudMesh.line(c,x2,y1,x2,y2,2,FOCUS);HudMesh.line(c,x2,y2,x1,y2,2,FOCUS);HudMesh.line(c,x1,y2,x1,y1,2,FOCUS);
    }
    private void drawNotice(DrawContext c,long now){
        long age=now-noticeAt;if(noticeAt<0||age>=2600||notice.isEmpty())return;
        float alpha=Math.min(1,Math.min(age/140f,(2600-age)/400f));
        float w=Math.min(900,text.width(notice,FONT_SMALL,BODY)+48);
        HudMesh.capsule(c,WIDTH/2f-w/2,898,w,36,alpha(0xF0102437,alpha),alpha(0xF0102437,alpha));
        draw(c,notice,WIDTH/2f,916,FONT_SMALL,alpha(TEXT,alpha),BODY,1,w-24);
    }
    private void tooltip(DrawContext c,List<Action> actions,StatsValues v,double mx,double my){
        String tip=null;
        if(hovered>=0){
            var a=actions.get(hovered);
            if(!a.enabled())tip=a.reason();
            else if(hovered==2||hovered==3){
                tip=a.label();
                if(remote!=null&&remote.voted())tip+=" · 오늘 이미 조절해 인기도 제한 해제권 1개를 써요 (보유 "+remote.tickets()+"개)";
            }
        }
        if(tip==null){
            for(int i=0;i<4&&tip==null;i++)if(ARMOR_SLOTS[i].contains(mx,my)){var it=armor(i);tip=it.isEmpty()?ARMOR_NAMES[i]+" · 비어 있음":it.getName().getString();}
            for(int i=0;i<5&&tip==null;i++)if(ACCESSORY_SLOTS[i].contains(mx,my)){
                if(i==0){String id=artifactId();var a=id.isEmpty()?null:ArtifactClient.find(id);tip=id.isEmpty()?"아티팩트 · 비어 있음":a==null?id:a.name();}
                else if(i==4){String id=broomId();tip=id.isBlank()?"빗자루 · 비어 있음":BroomUiLink.text("name",id);}
                else{var it=accessory(i-1);tip=it.isEmpty()?ACCESSORY_NAMES[i]+" · 비어 있음":it.getName().getString();}
            }
        }
        if(tip==null){
            var ext=extended();String title=ext==null?"":ext.extra().title();
            if(!title.isEmpty()&&mx>=IDENTITY.x()&&mx<IDENTITY.right()+10&&my>=TITLE_LINE_Y-16&&my<TITLE_LINE_Y+16&&text.width(title,FONT_BODY,BODY)>IDENTITY.w())tip=title;
            else if(mx>=1298&&mx<1609&&my>=586&&my<682){Double r=StatsLayout.hasteReduction(v.haste());if(r!=null)tip="재사용 대기시간 "+String.format(java.util.Locale.ROOT,"%.1f",r)+"% 감소";}
        }
        if(tip==null||tip.isBlank())return;
        float w=Math.min(700,text.width(tip,FONT_SMALL,BODY)+28),x=(float)Math.clamp(mx,60+w/2,WIDTH-60-w/2),y=(float)Math.max(80,my-34);
        c.getMatrices().push();
        try{
            c.getMatrices().translate(0,0,400);
            HudMesh.capsule(c,x-w/2,y-18,w,36,0xF5102437,0xF5102437);
            draw(c,tip,x,y,FONT_SMALL,TEXT,BODY,1,w-20);
        }finally{c.getMatrices().pop();}
    }
    private void icon(DrawContext c,String name,int cx,int cy,int size,float alpha){images.drawCentered(c,ROOT+name+".png",cx,cy,size,tint(alpha));}
    /** align 0 왼쪽 · 1 가운데 · 2 오른쪽. 넘치면 max 폭에 맞게 글자를 줄인다 (한 줄 유지). */
    private void draw(DrawContext c,String s,float x,float y,float size,int color,Identifier font,int align,float max){
        if(s==null||s.isEmpty())return;
        float fit=Math.min(size,size*max/Math.max(1,text.width(s,size,font)));
        if(align==2)text.draw(c,s,x-text.width(s,fit,font),y,fit,color,font,false);
        else text.draw(c,s,x,y,fit,color,font,align==1);
    }
    private static int tint(float alpha){return alpha(0xFFFFFFFF,alpha);}
    private static int alpha(int color,float alpha){return ((int)(((color>>>24)&0xFF)*Math.clamp(alpha,0,1))<<24)|(color&0xFFFFFF);}
    private static String number(Double value){return value==null?"—":StatsValues.number(value);}

    // ------------------------------------------------------------------ 입력
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(StatsClient.matches(key,scan)||key==GLFW.GLFW_KEY_ESCAPE){close();return true;}
        var actions=actions();
        if(key==GLFW.GLFW_KEY_TAB){int n=actions.size();boolean back=(modifiers&GLFW.GLFW_MOD_SHIFT)!=0;focused=focused<0?(back?n-1:0):(focused+(back?-1:1)+n)%n;return true;}
        if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_SPACE||key==GLFW.GLFW_KEY_KP_ENTER)&&focused>=0&&focused<actions.size()){activate(actions.get(focused));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0){
            var l=StatsLayout.fit(width,height);double mx=l.localX(x),my=l.localY(y);
            for(var a:actions())if(a.box().contains(mx,my)){focused=-1;activate(a);return true;}
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public void tick(){if(client.player==null||client.world==null||!client.player.isAlive())close();}
    @Override public boolean shouldPause(){return false;}
    @Override public void close(){sound(.95f,.18f);super.close();}
    @Override public void removed(){if(remote!=null)remote.close();invalidateResources();super.removed();}
    private void sound(float pitch,float volume){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),pitch,volume));}
    public void verifyRenderer(){if(images==null||images.size()==0)throw new IllegalStateException("Stats image missing");images.verifyGpuState();}
}
