package school.magiccodex.client;

import java.util.Set;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 알림 카드 외형 (2026-10-24 오너 승인 PNG 묶음, reference 12_notifications_approved).
 * 금색 테두리 + 반투명 네이비 바탕(PNG 자체 알파 ≈232/255 — 패널 전체 opacity를 따로 낮추지 않는다),
 * 원형 아이콘 받침, 라벨 없는 수락/거절 버튼, 닫기 X. 글자는 모두 런타임에 그린다.
 * 그리기만 담당하며 알림 대기열·클릭·소리 동작은 바꾸지 않는다.
 */
final class NotificationSkin {
    private NotificationSkin(){}
    private static Identifier gui(String name){return Identifier.of("magiccodex","textures/gui/notification/"+name+".png");}
    static final Identifier BACKGROUND=gui("toast_background"),SEAT=gui("icon_seat"),CLOSE=gui("close"),ACCEPT=gui("button_accept"),DECLINE=gui("button_decline");
    /** toast_background.png 1402x226. 원본 640x103 기준 각 변 32px 보호 → 이 PNG에서는 70px. */
    static final int BG_W=1402,BG_H=226,BG_CORNER=70;
    /** 카드 단위(1920x1080 기준)에서 PNG 1px의 크기. 560 단위 카드 = PNG 가로 1402px 비율. */
    static final float BG_SCALE=.4f;
    static final int BUTTON_SRC_H=160,BUTTON_CORNER=30,ACCEPT_SRC_W=372,DECLINE_SRC_W=368,SEAT_SRC=220,CLOSE_SRC=84,ICON_SRC=192;
    /** 텍스트 색 (레퍼런스: 크림색 제목, 옅은 회청색 본문, 금색 강조). */
    static final int TITLE=0xF4ECD8,BODY=0xC9D3E1,GOLD=0xE9C46A,MUTED=0x8FA3B5,ACCEPT_LABEL=0x1B2A44,DECLINE_LABEL=0xF1E6C8;
    /** icons/ 의 승인 아이콘 10종. 새 아이콘을 만들지 않는다. */
    static final Set<String> ICONS=Set.of("bag_unlock","collection","mail","match","quest","social","system","taming","warning","waypoint");

    /** 알림 kind → 아이콘 파일 이름. 서버가 아이콘 이름 그대로 kind를 보내면 그 아이콘을 쓴다. */
    static String icon(String kind){
        if(kind==null)return "system";
        return switch(kind){
            case "warning","error"->"warning";
            case "friend","party"->"social";
            case "shop"->"mail";
            default->ICONS.contains(kind)?kind:"system";
        };
    }
    static Identifier iconTexture(String kind){return gui("icons/"+icon(kind));}

    static void background(HudTextureCache images,DrawContext ctx,float x,float y,float w,float h,int tint){
        nine(images,ctx,BACKGROUND,BG_W,BG_H,BG_CORNER,x,y,w,h,BG_SCALE,tint);
    }
    /** 원형 받침 + 아이콘. size는 받침 지름 (카드 단위). */
    static void seat(HudTextureCache images,DrawContext ctx,float cx,float cy,float size,int tint){
        int s=Math.round(size);images.drawTexture(ctx,SEAT,Math.round(cx-size/2),Math.round(cy-size/2),0,0,s,s,SEAT_SRC,SEAT_SRC,SEAT_SRC,SEAT_SRC,tint);
    }
    static void icon(HudTextureCache images,DrawContext ctx,String kind,float cx,float cy,float size,int tint){
        int s=Math.round(size);images.drawTexture(ctx,iconTexture(kind),Math.round(cx-size/2),Math.round(cy-size/2),0,0,s,s,ICON_SRC,ICON_SRC,ICON_SRC,ICON_SRC,tint);
    }
    static void close(HudTextureCache images,DrawContext ctx,float cx,float cy,float size,boolean hover,int tint){
        int s=Math.round(size),x=Math.round(cx-size/2),y=Math.round(cy-size/2);
        images.drawTexture(ctx,CLOSE,x,y,0,0,s,s,CLOSE_SRC,CLOSE_SRC,CLOSE_SRC,CLOSE_SRC,tint);
        if(hover)images.drawTextureAdditive(ctx,CLOSE,x,y,0,0,s,s,CLOSE_SRC,CLOSE_SRC,CLOSE_SRC,CLOSE_SRC,scaleAlpha(tint,.45f));
    }
    /** 라벨 없는 수락(금색)/거절(네이비) 버튼. 모서리 깎임은 늘이지 않는다. */
    static void button(HudTextureCache images,DrawContext ctx,boolean accept,float x,float y,float w,float h,boolean hover,int tint){
        Identifier id=accept?ACCEPT:DECLINE;int sw=accept?ACCEPT_SRC_W:DECLINE_SRC_W;float k=h/BUTTON_SRC_H;
        nine(images,ctx,id,sw,BUTTON_SRC_H,BUTTON_CORNER,x,y,w,h,k,tint);
        if(hover)nineAdditive(images,ctx,id,sw,BUTTON_SRC_H,BUTTON_CORNER,x,y,w,h,k,scaleAlpha(tint,accept?.22f:.3f));
    }
    static int labelColor(boolean accept,float alpha){return argb(alpha,accept?ACCEPT_LABEL:DECLINE_LABEL);}
    static int argb(float alpha,int rgb){return ((int)(255*Math.clamp(alpha,0,1))<<24)|(rgb&0xFFFFFF);}
    private static int scaleAlpha(int tint,float f){int a=(int)(((tint>>>24)&0xFF)*f);return (a<<24)|(tint&0xFFFFFF);}

    /** 9-slice: 원본 모서리 corner px는 k배로만 줄이고, 변과 가운데만 늘인다. */
    static void nine(HudTextureCache images,DrawContext ctx,Identifier id,int sw,int sh,int corner,float x,float y,float w,float h,float k,int tint){slices(images,ctx,id,sw,sh,corner,x,y,w,h,k,tint,false);}
    private static void nineAdditive(HudTextureCache images,DrawContext ctx,Identifier id,int sw,int sh,int corner,float x,float y,float w,float h,float k,int tint){slices(images,ctx,id,sw,sh,corner,x,y,w,h,k,tint,true);}
    private static void slices(HudTextureCache images,DrawContext ctx,Identifier id,int sw,int sh,int corner,float x,float y,float w,float h,float k,int tint,boolean additive){
        float c=Math.min(corner*k,Math.min(w,h)/2f);
        int[] sx={0,corner,sw-corner,sw},sy={0,corner,sh-corner,sh};
        int[] dx={Math.round(x),Math.round(x+c),Math.round(x+w-c),Math.round(x+w)},dy={Math.round(y),Math.round(y+c),Math.round(y+h-c),Math.round(y+h)};
        for(int row=0;row<3;row++)for(int col=0;col<3;col++){
            int dw=dx[col+1]-dx[col],dh=dy[row+1]-dy[row],pw=sx[col+1]-sx[col],ph=sy[row+1]-sy[row];
            if(dw<=0||dh<=0||pw<=0||ph<=0)continue;
            if(additive)images.drawTextureAdditive(ctx,id,dx[col],dy[row],sx[col],sy[row],dw,dh,pw,ph,pw,ph,tint);
            else images.drawTexture(ctx,id,dx[col],dy[row],sx[col],sy[row],dw,dh,pw,ph,pw,ph,tint);
        }
    }
}
