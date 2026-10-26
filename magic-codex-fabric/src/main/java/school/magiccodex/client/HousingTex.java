package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 하우징 UI PNG (CHACADEMI_HOUSING_COMPONENTS / ICONS / LAYOUTS, GPT 제작). 원본에서 보이는 영역만 잘라 줄인 파일이다
 * (textures/gui/housing/*.png). inset = 늘리지 않는 테두리 두께 (잘라 줄인 파일 기준 px, 0 이면 통째로 늘림).
 */
final class HousingTex {
    record Tex(String name,int w,int h,int inset){
        Identifier id(){return Identifier.of("magiccodex","textures/gui/housing/"+name+".png");}
    }
    static final Tex BACKGROUND=new Tex("background",1672,941,0);
    static final Tex BUTTON_HOVER=new Tex("button_hover",557,95,21);
    static final Tex BUTTON_NORMAL=new Tex("button_normal",549,102,23);
    static final Tex BUTTON_PRESSED=new Tex("button_pressed",543,113,25);
    static final Tex GUESTBOOK_ENTRY=new Tex("guestbook_entry",828,167,30);
    static final Tex ICON_BGM=new Tex("icon_bgm",117,160,0);
    static final Tex ICON_CATEGORY_FURNITURE=new Tex("icon_category_furniture",98,160,0);
    static final Tex ICON_CATEGORY_PROPS=new Tex("icon_category_props",128,160,0);
    static final Tex ICON_CATEGORY_SURFACE=new Tex("icon_category_surface",151,160,0);
    static final Tex ICON_CATEGORY_WALL=new Tex("icon_category_wall",148,160,0);
    static final Tex ICON_CLOSE=new Tex("icon_close",160,155,0);
    static final Tex ICON_EXPAND=new Tex("icon_expand",160,157,0);
    static final Tex ICON_FRIENDS=new Tex("icon_friends",160,129,0);
    static final Tex ICON_GUESTBOOK=new Tex("icon_guestbook",160,128,0);
    static final Tex ICON_HEART=new Tex("icon_heart",160,136,0);
    static final Tex ICON_MORE=new Tex("icon_more",160,39,0);
    static final Tex ICON_ONLINE=new Tex("icon_online",160,159,0);
    static final Tex ICON_PLAY=new Tex("icon_play",133,160,0);
    static final Tex ICON_RETRIEVE=new Tex("icon_retrieve",160,157,0);
    static final Tex ICON_ROTATE=new Tex("icon_rotate",160,160,0);
    static final Tex ICON_SAVE=new Tex("icon_save",160,157,0);
    static final Tex ICON_SEARCH=new Tex("icon_search",156,160,0);
    static final Tex ICON_STOP=new Tex("icon_stop",160,155,0);
    static final Tex ICON_TITLE_STAR=new Tex("icon_title_star",156,160,0);
    static final Tex ICON_TITLE_UNDERLINE=new Tex("icon_title_underline",682,48,0);
    static final Tex ICON_UNDO=new Tex("icon_undo",160,149,0);
    static final Tex ICON_VISITOR=new Tex("icon_visitor",145,160,0);
    static final Tex ICON_ZOOM_MINUS=new Tex("icon_zoom_minus",160,21,0);
    static final Tex ICON_ZOOM_PLUS=new Tex("icon_zoom_plus",160,152,0);
    static final Tex INPUT_FIELD=new Tex("input_field",613,81,18);
    static final Tex ITEM_SLOT=new Tex("item_slot",267,260,57);
    static final Tex ITEM_SLOT_SELECTED=new Tex("item_slot_selected",268,264,58);
    static final Tex LAYOUT_SELECTED=new Tex("layout_selected",124,120,26);
    static final Tex LAYOUT_UNSELECTED=new Tex("layout_unselected",108,102,22);
    static final Tex LIKE_NORMAL=new Tex("like_normal",610,121,27);
    static final Tex LIKE_PRESSED=new Tex("like_pressed",608,112,25);
    static final Tex PANEL_GUESTBOOK=new Tex("panel_guestbook",320,788,70);
    static final Tex PANEL_INVENTORY=new Tex("panel_inventory",1020,161,36);
    static final Tex PANEL_ROOM_INFO=new Tex("panel_room_info",487,320,70);
    static final Tex SCROLL_THUMB=new Tex("scroll_thumb",328,45,10);
    static final Tex SCROLL_TRACK_HORIZONTAL=new Tex("scroll_track_horizontal",603,32,7);
    static final Tex SCROLL_TRACK_VERTICAL=new Tex("scroll_track_vertical",28,430,6);
    static final Tex SPECTATOR_PLATE=new Tex("spectator_plate",539,82,18);
    static final Tex TAB_SELECTED=new Tex("tab_selected",558,100,22);
    static final Tex TAB_UNSELECTED=new Tex("tab_unselected",570,99,22);
    static final Tex TILE_INVALID=new Tex("tile_invalid",407,225,0);
    static final Tex TILE_VALID=new Tex("tile_valid",404,220,0);
    static final Tex TOAST=new Tex("toast",929,134,34);
    static final Tex TOOLTIP=new Tex("tooltip",440,112,25);

    static final Identifier[] HOUSE_EMBLEM={
            Identifier.of("magiccodex","textures/gui/party/house_star.png"),Identifier.of("magiccodex","textures/gui/party/house_moon.png"),
            Identifier.of("magiccodex","textures/gui/party/house_leaf.png"),Identifier.of("magiccodex","textures/gui/party/house_sun.png")};

    private HousingTex(){}

    /** 통째로 늘려 그리기. */
    static void stretch(HudTextureCache images,DrawContext c,Tex t,float x,float y,float w,float h,int tint){
        images.drawTexture(c,t.id(),Math.round(x),Math.round(y),0,0,Math.round(x+w)-Math.round(x),Math.round(y+h)-Math.round(y),t.w(),t.h(),t.w(),t.h(),tint);
    }
    /** 비율을 지키며 (cx,cy) 가운데, 긴 변이 size 가 되게 (아이콘). */
    static void icon(HudTextureCache images,DrawContext c,Tex t,float cx,float cy,float size,int tint){
        float k=size/Math.max(t.w(),t.h()),w=t.w()*k,h=t.h()*k;
        stretch(images,c,t,cx-w/2,cy-h/2,w,h,tint);
    }
    /**
     * 9조각: 모서리(inset)는 화면 corner 크기로 고정, 가장자리·가운데만 늘린다.
     * @param corner 화면(가상 좌표)에서의 모서리 크기
     */
    static void nine(HudTextureCache images,DrawContext c,Tex t,float x,float y,float w,float h,float corner,int tint){
        if(t.inset()<=0){stretch(images,c,t,x,y,w,h,tint);return;}
        float k=Math.min(corner,Math.min(w,h)/2);
        int[] sx={0,t.inset(),t.w()-t.inset(),t.w()},sy={0,t.inset(),t.h()-t.inset(),t.h()};
        int[] dx={Math.round(x),Math.round(x+k),Math.round(x+w-k),Math.round(x+w)};
        int[] dy={Math.round(y),Math.round(y+k),Math.round(y+h-k),Math.round(y+h)};
        for(int row=0;row<3;row++)for(int col=0;col<3;col++){
            int dw=dx[col+1]-dx[col],dh=dy[row+1]-dy[row],sw=sx[col+1]-sx[col],sh=sy[row+1]-sy[row];
            if(dw<=0||dh<=0||sw<=0||sh<=0)continue;
            images.drawTexture(c,t.id(),dx[col],dy[row],sx[col],sy[row],dw,dh,sw,sh,sw,sh,tint);
        }
    }
    static int alpha(int color,float a){return ((int)(((color>>>24)&0xFF)*Math.clamp(a,0,1))<<24)|(color&0xFFFFFF);}
    static int shade(int color,float k){
        int r=Math.clamp(Math.round(((color>>16)&0xFF)*k),0,255),g=Math.clamp(Math.round(((color>>8)&0xFF)*k),0,255),b=Math.clamp(Math.round((color&0xFF)*k),0,255);
        return (color&0xFF000000)|(r<<16)|(g<<8)|b;
    }
    static int mix(int a,int b,float t){
        int r=Math.round(((a>>16)&0xFF)*(1-t)+((b>>16)&0xFF)*t),g=Math.round(((a>>8)&0xFF)*(1-t)+((b>>8)&0xFF)*t),bl=Math.round((a&0xFF)*(1-t)+(b&0xFF)*t);
        return (a&0xFF000000)|(r<<16)|(g<<8)|bl;
    }
}
