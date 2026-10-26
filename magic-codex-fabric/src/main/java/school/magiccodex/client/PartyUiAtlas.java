package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 파티 UI PNG 아틀라스 (textures/gui/party_ui/panels.png, components.png).
 * 영역 좌표는 납품된 atlas_regions.json 그대로이고, inset 은 영역 안 실제로 보이는(불투명) 테두리까지의 여백이다.
 * 9조각으로 그려 모서리 장식은 비율 그대로 두고 가운데만 늘린다.
 */
final class PartyUiAtlas {
    static final Identifier PANELS=Identifier.of("magiccodex","textures/gui/party_ui/panels.png");
    static final Identifier COMPONENTS=Identifier.of("magiccodex","textures/gui/party_ui/components.png");
    static final int PANELS_W=1670,PANELS_H=941,COMPONENTS_W=1671,COMPONENTS_H=941;

    /** x,y,w,h = 아틀라스 영역 (여백 포함). l,t,r,b = 보이는 테두리까지의 여백. corner = 늘리지 않을 모서리 크기(원본 px). */
    record Piece(Identifier sheet,int x,int y,int w,int h,int l,int t,int r,int b,int corner){
        int innerW(){return w-l-r;} int innerH(){return h-t-b;}
    }
    private static Piece panel(int x,int y,int w,int h,int l,int t,int r,int b,int corner){return new Piece(PANELS,x,y,w,h,l,t,r,b,corner);}
    private static Piece part(int x,int y,int w,int h,int l,int t,int r,int b,int corner){return new Piece(COMPONENTS,x,y,w,h,l,t,r,b,corner);}

    static final Piece MAIN_PANEL=panel(35,55,1010,825,11,15,11,8,0);
    static final Piece INVITE_PANEL=panel(1050,80,590,595,10,11,12,10,0);
    static final Piece TOAST=panel(1050,690,590,180,10,16,12,12,60);
    static final Piece MEMBER_ROW=part(30,65,1610,185,12,15,10,10,30);
    static final Piece SEARCH_ROW=part(30,260,885,165,12,13,15,12,30);
    static final Piece AVATAR_FRAME=part(925,270,165,155,15,9,10,9,0);
    static final Piece SEARCH_FIELD=part(1095,290,550,115,12,12,15,12,44);
    static final Piece BLUE=part(50,445,250,120,13,13,11,14,30),BLUE_HOVER=part(305,440,260,130,18,17,17,19,30);
    static final Piece GOLD=part(580,445,245,120,14,14,11,14,30),GOLD_HOVER=part(830,440,260,130,22,18,16,18,30);
    static final Piece ROSE=part(1100,445,250,120,14,14,11,13,30),ROSE_HOVER=part(1360,440,265,130,22,19,17,17,30);
    static final Piece LEAVE=part(85,580,745,145,18,13,19,11,44);
    static final Piece INVITE_BUTTON=part(895,585,415,140,17,13,13,15,56);
    static final Piece PLUS=part(105,750,115,120,14,12,9,14,0),SEARCH=part(265,745,130,130,14,11,12,13,0);
    static final Piece CROWN=part(440,745,140,130,12,21,12,17,0),INFO=part(635,750,120,125,12,12,15,16,0);
    static final Piece KICK=part(805,740,145,140,17,17,19,15,0),CLOSE=part(995,740,145,140,14,14,14,8,0);
    static final Piece ONLINE=part(1190,765,90,95,18,22,16,16,0),OFFLINE=part(1300,770,90,90,18,17,15,16,0);
    static final Piece SCROLL_TRACK=part(1505,580,65,300,14,13,16,13,16),SCROLL_THUMB=part(1570,625,55,195,8,10,16,16,14);

    private PartyUiAtlas(){}

    /** 보이는 테두리가 (x,y,w,h)에 오도록 그대로 늘려 그린다 (패널·아이콘). 여백(빛 번짐)도 같은 비율로 함께 그린다. */
    static void stretch(HudTextureCache images,DrawContext c,Piece p,float x,float y,float w,float h,int tint){
        float sx=w/p.innerW(),sy=h/p.innerH();
        int dx=Math.round(x-p.l()*sx),dy=Math.round(y-p.t()*sy),dw=Math.round(p.w()*sx),dh=Math.round(p.h()*sy);
        images.drawTexture(c,p.sheet(),dx,dy,p.x(),p.y(),dw,dh,p.w(),p.h(),p.w(),p.h(),tint);
    }
    /** 아이콘: 보이는 부분이 (cx,cy)를 중심으로 높이 size 가 되게 (비율 유지). */
    static void icon(HudTextureCache images,DrawContext c,Piece p,float cx,float cy,float size,int tint){
        float k=size/p.innerH(),w=p.innerW()*k;stretch(images,c,p,cx-w/2,cy-size/2,w,size,tint);
    }
    /**
     * 9조각: 보이는 테두리가 (x,y,w,h)에 오고, 모서리는 높이 비율(scale)로만 줄인다.
     * @param scale 원본 1px 당 화면 크기 (보통 h / 보이는 높이)
     */
    static void nine(HudTextureCache images,DrawContext c,Piece p,float x,float y,float w,float h,float scale,int tint){
        // 원본 모서리(여백 포함)와 화면 모서리
        int sl=p.l()+p.corner(),sr=p.r()+p.corner(),st=p.t()+p.corner(),sb=p.b()+p.corner();
        float k=Math.min(scale,Math.min(w/(2f*Math.max(1,p.corner())),h/(2f*Math.max(1,p.corner()))));
        if(p.corner()==0){stretch(images,c,p,x,y,w,h,tint);return;}
        float x0=x-p.l()*k,y0=y-p.t()*k,x3=x+w+p.r()*k,y3=y+h+p.b()*k;
        float x1=x+p.corner()*k,x2=x+w-p.corner()*k,y1=y+p.corner()*k,y2=y+h-p.corner()*k;
        int[] sxs={p.x(),p.x()+sl,p.x()+p.w()-sr,p.x()+p.w()};
        int[] sys={p.y(),p.y()+st,p.y()+p.h()-sb,p.y()+p.h()};
        int[] dxs={Math.round(x0),Math.round(x1),Math.round(x2),Math.round(x3)};
        int[] dys={Math.round(y0),Math.round(y1),Math.round(y2),Math.round(y3)};
        for(int row=0;row<3;row++)for(int col=0;col<3;col++){
            int dw=dxs[col+1]-dxs[col],dh=dys[row+1]-dys[row],sw=sxs[col+1]-sxs[col],sh=sys[row+1]-sys[row];
            if(dw<=0||dh<=0||sw<=0||sh<=0)continue;
            images.drawTexture(c,p.sheet(),dxs[col],dys[row],sxs[col],sys[row],dw,dh,sw,sh,sw,sh,tint);
        }
    }
    static int alpha(int tint,float alpha){return ((int)(((tint>>>24)&0xFF)*Math.clamp(alpha,0,1))<<24)|(tint&0xFFFFFF);}
}
