package school.magiccodex.client;

import java.util.*;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.resource.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.protocol.SocialProtocol.Entry;
import school.magiccodex.protocol.PartyUiProtocol.Person;

/**
 * 수락/거절 알림 카드 (친구 신청, 파티 초대). 화면을 열지 않는 HUD 표시라 조작을 빼앗지 않으며, Ctrl 커서 모드에서만 클릭된다.
 * 파티 초대는 파티 UI 아틀라스의 알림 패널(레퍼런스 우하단)로 그린다. 한 번에 카드 하나, 파티 초대가 먼저.
 */
public final class FriendRequestToast {
    /** 1920x1080 기준 좌표. */
    static final float W=600,H=168,X=1920-W-28,Y=236,BUTTON_W=150,BUTTON_H=46,BUTTON_Y=104,ACCEPT_X=W-2*BUTTON_W-36,DECLINE_X=W-BUTTON_W-22;
    static final long SHOW_MILLIS=20000;
    /** 원형 받침 오른쪽 글자 시작 (카드 기준). */
    static final float TEXT_X=108;
    /** 파티 초대 카드 (1920x1080 기준, 레퍼런스 크기 그대로). */
    static final float P_W=563,P_H=148,P_X=1920-P_W-28,P_ACCEPT_X=337,P_DECLINE_X=446,P_BUTTON_Y=73,P_BUTTON_W=95,P_BUTTON_H=43,P_CLOSE_X=526,P_CLOSE_Y=37;
    private static final ArrayDeque<Person> PARTY=new ArrayDeque<>();
    private static Person party;
    private static HudTextureCache images;
    private static final Identifier BODY=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final FriendRequestQueue QUEUE=new FriendRequestQueue();
    private static Entry current;private static long started,pausedAt;
    private static CodexTypography text;
    private FriendRequestToast(){}
    static void initialize(){
        HudRenderCallback.EVENT.register((ctx,ticks)->render(ctx));
        // HudCursorScreen(Ctrl 커서 모드)의 클릭만 가로챈다. 다른 화면에서는 카드가 보이지 않는다.
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
            if(screen instanceof HudCursorScreen)ScreenMouseEvents.allowMouseClick(screen).register((s,x,y,button)->!click(x,y,button));
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){public Identifier getFabricId(){return Identifier.of("magiccodex","friend_request_toast");}public void reload(ResourceManager manager){close();}});
    }
    static void offer(Entry e){
        if(current!=null&&current.id().equals(e.id()))return;
        if(QUEUE.offer(e))MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,1.2f,.35f));
    }
    /** 서버가 보낸 받은 신청 목록과 맞춘다 (친구창에서 처리했거나 상대가 취소한 신청의 카드는 닫는다). */
    static void sync(List<Entry> incoming){
        var ids=new HashSet<UUID>();for(var e:incoming)ids.add(e.id());
        QUEUE.retain(ids);if(current!=null&&!ids.contains(current.id()))current=null;
    }
    /** 파티 초대 알림. 같은 사람의 초대가 이미 떠 있거나 대기 중이면 다시 띄우지 않는다. */
    static void offerParty(Person inviter){
        if(inviter==null||party!=null&&party.id().equals(inviter.id())||PARTY.stream().anyMatch(p->p.id().equals(inviter.id()))||PARTY.size()>=FriendRequestQueue.TOASTS)return;
        PARTY.add(inviter);MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,1.2f,.35f));
    }
    /** 프록시가 보낸 받은 초대 목록과 맞춘다 (창에서 처리했거나 만료된 초대의 카드는 닫는다). */
    static void syncParty(List<Person> invites){
        var ids=new HashSet<UUID>();for(var p:invites)ids.add(p.id());
        PARTY.removeIf(p->!ids.contains(p.id()));if(party!=null&&!ids.contains(party.id()))party=null;
    }
    static void reset(){QUEUE.clear();PARTY.clear();current=null;party=null;started=pausedAt=0;close();}
    private static void close(){if(text!=null){text.close();text=null;}if(images!=null){images.close();images=null;}}
    private static boolean visible(MinecraftClient c){return c.player!=null&&c.world!=null&&c.player.isAlive()&&!c.options.hudHidden&&c.getOverlay()==null&&(c.currentScreen==null||c.currentScreen instanceof HudCursorScreen);}
    private static float scale(MinecraftClient c){return Math.min(c.getWindow().getScaledWidth()/1920f,c.getWindow().getScaledHeight()/1080f);}
    /** 카드 기준 좌표로 바꾼 마우스 위치. 카드는 화면 오른쪽에 붙는다. */
    private static float localX(MinecraftClient c,double x){float s=scale(c);return (float)((x-(c.getWindow().getScaledWidth()-(1920-X)*s))/s);}
    private static float localY(MinecraftClient c,double y){return (float)(y/scale(c)-Y);}
    static int hit(float x,float y){return FriendRequestQueue.hit(x,y);}
    /** 파티 카드 기준 좌표: 1=수락, 2=거절, 3=닫기(X), 0=없음. */
    static int partyHit(float x,float y){
        if(Math.abs(x-P_CLOSE_X)<=14&&Math.abs(y-P_CLOSE_Y)<=14)return 3;
        if(y<P_BUTTON_Y||y>=P_BUTTON_Y+P_BUTTON_H)return 0;
        return x>=P_ACCEPT_X&&x<P_ACCEPT_X+P_BUTTON_W?1:x>=P_DECLINE_X&&x<P_DECLINE_X+P_BUTTON_W?2:0;
    }
    private static float partyX(MinecraftClient c,double x){float s=scale(c);return (float)((x-(c.getWindow().getScaledWidth()-(1920-P_X)*s))/s);}
    /** @return 클릭을 소비했으면 true */
    static boolean click(double x,double y,int button){
        if(HudCustomization.editingGesture())return false;var hudPoint=HudCustomization.pointer("requests",x,y);x=hudPoint[0];y=hudPoint[1];
        var c=MinecraftClient.getInstance();
        if(button!=0||current==null&&party==null||!visible(c)||!HudPointer.active())return false;
        if(party!=null){
            float px=partyX(c,x),py=localY(c,y);
            if(px<0||px>=P_W||py<0||py>=P_H)return false;
            int hit=partyHit(px,py);if(hit==0)return true;
            c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.15f,.23f));
            // X는 닫기만 한다 (초대는 /파티 창에 2분 동안 남는다). 보내지 못했으면 카드를 그대로 둔다.
            if(hit==3||PartyUiClient.respond(party.id(),hit==1))party=null;
            return true;
        }
        float lx=localX(c,x),ly=localY(c,y);
        if(lx<0||lx>=W||ly<0||ly>=H)return false;
        int hit=hit(lx,ly);if(hit==0)return true;
        c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.15f,.23f));
        // 전송하지 못했으면(연결 문제 등) 카드를 그대로 둔다.
        if(SocialClient.respond(null,current.id(),hit==1))current=null;
        return true;
    }
    private static void render(DrawContext ctx){
        var c=MinecraftClient.getInstance();long now=Util.getMeasuringTimeMs();
        if(!visible(c)){if(current!=null&&pausedAt==0)pausedAt=now;return;}
        if(pausedAt!=0){started+=now-pausedAt;pausedAt=0;}
        if(current==null&&party==null){party=PARTY.poll();if(party!=null)started=now;}
        if(party!=null){renderParty(ctx,c,now);return;}
        if(current==null){current=QUEUE.poll();if(current==null)return;started=now;}
        long age=now-started;
        // 시간이 지나면 거절하지 않고 닫는다. 신청은 친구창의 받은 신청 목록에 남는다.
        if(age>=SHOW_MILLIS){current=null;return;}
        if(text==null)text=new CodexTypography(c);if(images==null)images=new HudTextureCache(c);
        boolean cursor=HudPointer.active();var w=c.getWindow();
        float s=scale(c),alpha=Math.min(1,Math.min(age/220f,(SHOW_MILLIS-age)/500f));
        try(var customization=HudCustomization.begin(ctx,"requests",w.getScaledWidth()-(1920-X)*s,Y*s,W*s,H*s)){
        int hot=cursor?hitInside(localX(c,HudPointer.x()),localY(c,HudPointer.y())):0;
        int white=color(alpha,0xFF,0xFFFFFF);
        text.beginFrame();images.beginFrame();ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(w.getScaledWidth()-(1920-X)*s+(1-alpha)*24*s,Y*s,340);ctx.getMatrices().scale(s,s,1);
            // 2026-10-24 승인 알림 외형: 공용 바탕 + 원형 받침(social) + 라벨 없는 버튼 PNG. 문구·버튼 위치·동작은 그대로.
            NotificationSkin.background(images,ctx,0,0,W,H,white);
            NotificationSkin.seat(images,ctx,TEXT_X/2,H/2-4,70,white);NotificationSkin.icon(images,ctx,"social",TEXT_X/2,H/2-4,42,white);
            text.draw(ctx,"친구 신청",TEXT_X,36,24,color(alpha,0xFF,NotificationSkin.TITLE),BOLD,false);
            if(QUEUE.size()>0){String more="+"+QUEUE.size()+"건 대기";text.draw(ctx,more,W-30-text.width(more,18,BODY),36,18,color(alpha,0xFF,NotificationSkin.GOLD),BODY,false);}
            String name=current.name(),tail=" 님이 친구 신청을 보냈습니다";
            float room=W-TEXT_X-30,tailWidth=text.width(tail,20,BODY),size=Math.max(10,Math.min(22,22*(room-tailWidth)/Math.max(1,text.width(name,22,BOLD)))),nameWidth=text.width(name,size,BOLD);
            if(nameWidth+tailWidth<=room){
                text.draw(ctx,name,TEXT_X,70,size,color(alpha,0xFF,SocialLayout.color(current.dormitory())&0xFFFFFF),BOLD,false);
                text.draw(ctx,tail,TEXT_X+nameWidth,70,20,color(alpha,0xFF,NotificationSkin.BODY),BODY,false);
            }else{
                // 아주 긴 이름: 줄을 나눠 버튼과 겹치지 않게 한다.
                float fit=Math.max(10,Math.min(22,22*room/Math.max(1,text.width(name,22,BOLD))));
                text.draw(ctx,name,TEXT_X,62,fit,color(alpha,0xFF,SocialLayout.color(current.dormitory())&0xFFFFFF),BOLD,false);
                text.draw(ctx,tail.strip(),TEXT_X,84,17,color(alpha,0xFF,NotificationSkin.BODY),BODY,false);
            }
            String hint=cursor?"버튼을 눌러 주세요":UiKeys.cursorHint()+" 클릭";float hintSize=Math.max(11,Math.min(16,16*(ACCEPT_X-TEXT_X-12)/Math.max(1,text.width(hint,16,BODY))));
            text.draw(ctx,hint,TEXT_X,BUTTON_Y+BUTTON_H/2,hintSize,color(alpha,0xFF,NotificationSkin.MUTED),BODY,false);
            button(ctx,"수락",ACCEPT_X,true,hot==1,alpha);button(ctx,"거절",DECLINE_X,false,hot==2,alpha);
            // 남은 표시 시간 (테두리 안쪽, 금색)
            HudMesh.quad(ctx,24,H-13,(W-48)*(1-age/(float)SHOW_MILLIS),2,color(alpha,0x90,NotificationSkin.GOLD),color(alpha,0x90,NotificationSkin.GOLD));
        }finally{ctx.getMatrices().pop();images.endFrame();}

        }
}
    private static void renderParty(DrawContext ctx,MinecraftClient c,long now){
        long age=now-started;
        if(age>=SHOW_MILLIS){party=null;return;}
        if(text==null)text=new CodexTypography(c);if(images==null)images=new HudTextureCache(c);
        boolean cursor=HudPointer.active();var w=c.getWindow();
        float s=scale(c),alpha=Math.min(1,Math.min(age/220f,(SHOW_MILLIS-age)/500f));
        try(var customization=HudCustomization.begin(ctx,"requests",w.getScaledWidth()-(1920-P_X)*s,Y*s,P_W*s,P_H*s)){
        int hot=cursor?partyHitInside(partyX(c,HudPointer.x()),localY(c,HudPointer.y())):0;
        int white=color(alpha,0xFF,0xFFFFFF);
        text.beginFrame();images.beginFrame();ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(w.getScaledWidth()-(1920-P_X)*s+(1-alpha)*24*s,Y*s,340);ctx.getMatrices().scale(s,s,1);
            // 2026-10-24 승인 알림 외형으로 통일: 공용 바탕 + 원형 받침 안에 초대한 사람 얼굴 + 버튼/닫기 PNG.
            NotificationSkin.background(images,ctx,0,0,P_W,P_H,white);
            NotificationSkin.seat(images,ctx,57,P_H/2,70,white);
            var skin=PartyUiClient.face(party);
            ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,skin,35,Math.round(P_H/2-22),8,8,44,44,8,8,64,64);
            ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,skin,35,Math.round(P_H/2-22),40,8,44,44,8,8,64,64);
            text.draw(ctx,"파티 초대",116,46,22,color(alpha,0xFF,NotificationSkin.TITLE),BOLD,false);
            if(PARTY.size()>0)text.draw(ctx,"+"+PARTY.size(),116+text.width("파티 초대",22,BOLD)+10,46,16,color(alpha,0xFF,NotificationSkin.GOLD),BODY,false);
            if(!cursor){String hint=UiKeys.cursorHint()+" 클릭";text.draw(ctx,hint,P_CLOSE_X-22-text.width(hint,14,BODY),P_CLOSE_Y,14,color(alpha,0xFF,NotificationSkin.MUTED),BODY,false);}
            String name=party.nickname(),tail="님이 파티에 초대했어요.";
            float room=P_ACCEPT_X-116-14,full=text.width(name+tail,16,BODY);
            if(full<=room)text.draw(ctx,name+tail,116,P_BUTTON_Y+P_BUTTON_H/2,16,color(alpha,0xFF,NotificationSkin.BODY),BODY,false);
            else{
                // 긴 이름은 두 줄로 나눠 버튼과 겹치지 않게 한다.
                float fit=Math.max(10,Math.min(16,16*room/Math.max(1,text.width(name,16,BOLD))));
                text.draw(ctx,name,116,P_BUTTON_Y+P_BUTTON_H/2-10,fit,color(alpha,0xFF,NotificationSkin.TITLE),BOLD,false);
                text.draw(ctx,tail,116,P_BUTTON_Y+P_BUTTON_H/2+11,Math.min(15,15*room/Math.max(1,text.width(tail,15,BODY))),color(alpha,0xFF,NotificationSkin.BODY),BODY,false);
            }
            partyButton(ctx,"수락",P_ACCEPT_X,true,hot==1,alpha);partyButton(ctx,"거절",P_DECLINE_X,false,hot==2,alpha);
            NotificationSkin.close(images,ctx,P_CLOSE_X,P_CLOSE_Y,18,hot==3,white);
            // 남은 표시 시간
            HudMesh.quad(ctx,24,P_H-13,(P_W-48)*(1-age/(float)SHOW_MILLIS),2,color(alpha,0x90,NotificationSkin.GOLD),color(alpha,0x90,NotificationSkin.GOLD));
        }finally{ctx.getMatrices().pop();images.endFrame();}

        }
}
    private static int partyHitInside(float x,float y){return x<0||x>=P_W||y<0||y>=P_H?0:partyHit(x,y);}
    private static void partyButton(DrawContext ctx,String label,float x,boolean accept,boolean hover,float alpha){
        NotificationSkin.button(images,ctx,accept,x,P_BUTTON_Y,P_BUTTON_W,P_BUTTON_H,hover,color(alpha,0xFF,0xFFFFFF));
        text.draw(ctx,label,x+P_BUTTON_W/2,P_BUTTON_Y+P_BUTTON_H/2,18,NotificationSkin.labelColor(accept,alpha),BOLD,true);
    }
    private static int hitInside(float x,float y){return x<0||x>=W||y<0||y>=H?0:hit(x,y);}
    private static int color(float alpha,int a,int rgb){return ((int)(a*alpha)<<24)|(rgb&0xFFFFFF);}
    private static void button(DrawContext ctx,String label,float x,boolean accept,boolean hover,float alpha){
        NotificationSkin.button(images,ctx,accept,x,BUTTON_Y,BUTTON_W,BUTTON_H,hover,color(alpha,0xFF,0xFFFFFF));
        text.draw(ctx,label,x+BUTTON_W/2,BUTTON_Y+BUTTON_H/2,21,NotificationSkin.labelColor(accept,alpha),BOLD,true);
    }
}
