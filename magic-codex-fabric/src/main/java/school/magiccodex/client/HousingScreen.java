package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingProtocol.*;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;
import school.magiccodex.protocol.SchoolProtocol;
import static school.magiccodex.client.HousingTex.*;

/**
 * 기숙사 내 방 꾸미기 창. 레퍼런스(CHACADEMI_HOUSING_LAYOUTS layout_edit / layout_visit, 1672×941)의 좌표를 가상 좌표로 쓴다.
 * 편집 모드(내 방, 기숙사실 문으로 열었을 때) / 구경 모드(남의 방). 방은 쿼터뷰로 직접 그리고, 가구는 모델이 생기기 전까지 상자로 그린다.
 * 배치는 이 창 안에서만 바꾸고 「저장」을 눌러야 서버에 올라간다 (서버가 다시 검사).
 */
public final class HousingScreen extends SocialScreen {
    private static final Identifier TITLE=Identifier.of("magiccodex","section"),SERIF=Identifier.of("magiccodex","heading");
    private static final float VW=1672,VH=941;
    private static final int TITLE_GOLD=0xFFF0D293,SUB=0xFFBFD3E6,GREEN=0xFF7BE08E,OFF=0xFF8A949C,TEXT=0xFFE6E9EE,GOLD_TEXT=0xFFF1D9A0,RED=0xFFF08A8A,ROSE=0xFFF3A9B6;
    private static final int DEFAULT_WALL=0xFFD9CDB5,DEFAULT_WAINSCOT=0xFF6B4A33,DEFAULT_FLOOR=0xFF9B6E45,DEFAULT_FLOOR_2=0xFF8C6240,TRIM=0xFF4E3524,GOLD_LINE=0xFFE1B866;
    private static final String[] TABS={"가구","소품","벽걸이","벽지·바닥"};
    private static final Tex[] TAB_ICONS={ICON_CATEGORY_FURNITURE,ICON_CATEGORY_PROPS,ICON_CATEGORY_WALL,ICON_CATEGORY_SURFACE};
    private static final float[] TAB_X={76,276,500,722},TAB_W={190,212,210,208};
    private static final float SLOT_X=75,SLOT_Y=740,SLOT_W=176,SLOT_H=141,SLOT_STEP=193,SLOT_VIEW=1520;
    private static final int UNDO_LIMIT=60;
    // hot ids
    private static final int H_CLOSE=1,H_EXPAND=2,H_BGM=3,H_GUESTBOOK=4,H_FRIENDS=5,H_HOME=6,H_ROTATE=10,H_RETRIEVE=11,H_UNDO=12,H_SAVE=13,H_SLOT=20,H_TAB=30,
            H_ZOOM_IN=50,H_ZOOM_OUT=51,H_LIKE=60,H_SEND=70,H_POP_OK=80,H_POP_CANCEL=81,H_POP_CLOSE=82,H_SEARCH_PREV=83,H_SEARCH_NEXT=84,H_ITEM=100,H_MORE=1000,H_DELETE=2000,H_VISIT=3000,H_GO_HOME=3999;

    private enum Popup { NONE, FRIENDS, EXPAND, CONFIRM_CLOSE }
    /** 들고 있는 가구. origin = 방에서 집었으면 원래 자리 (취소하면 되돌림), before = 집기 전 레이아웃 (되돌리기용). */
    private record Held(Def def,int rot,Placement origin,Layout before){}
    private record Drawn(int index,Placement p,Def def,float[][] hull){}

    private Popup popup=Popup.NONE;
    private boolean guestbookOpen;
    private int tab,slot,hot,hoverItem=-1;
    private Input search,post,userSearch;
    private int searchPage;private boolean queryDirty;private long queryAt;
    private float invScroll,bookScroll,friendScroll,zoom=1,panX,panY;
    private boolean panning,mouseDown;private double lastX,lastY,mouseX,mouseY;
    private final Layout[] edit=new Layout[HousingRules.SLOTS];
    private final boolean[] dirty=new boolean[HousingRules.SLOTS];
    @SuppressWarnings({"unchecked","rawtypes"}) private final Deque<Layout>[] undo=new Deque[HousingRules.SLOTS];
    private Held held;
    private Placement candidate;private String candidateWhy;
    private List<Drawn> drawn=List.of();
    private long moreEntry=-1,busyAt,toastAt;private String toast="";private boolean toastOk;
    private int savingSlot=-1;
    private UUID owner;
    private HousingIso iso;
    private List<Def> visibleItems=List.of();

    public HousingScreen(){super("내 방");for(int i=0;i<undo.length;i++)undo[i]=new ArrayDeque<>();}

    /**
     * Wide windows (owner 2026-10-19, like the 결투장 lobby): the canvas starts at the screen's left edge and {@link #dx} is the
     * extra width on the right. Left parts (title, room info) stay, right parts (top buttons, guestbook, zoom, tools, search)
     * move by dx, the room / inventory panel grow by dx and popups move by dx/2.
     */
    private float dx;
    @Override SocialLayout.Fit fit(){
        float s=Math.min(width/VW,height/VH);
        dx=Math.max(0,width/s-VW);
        return new SocialLayout.Fit(dx>0?0:(width-VW*s)/2,(height-VH*s)/2,s);
    }
    /** Draws a group moved right by `by` (design px); `mx` given to it must be moved back the same way. */
    private int shifted(DrawContext c,float by,java.util.function.IntSupplier draw){
        c.getMatrices().push();c.getMatrices().translate(by,0,0);
        try{return draw.getAsInt();}finally{c.getMatrices().pop();}
    }
    @Override protected void init(){
        String q=search==null?"":search.text(),t=post==null?"":post.text();
        super.init();search=new Input("가구 검색",16,q);post=new Input("한마디",HousingProtocol.GUESTBOOK_CHARS,t);
        userSearch=new Input("유저 검색",32,userSearch==null?"":userSearch.text());userSearch.widget.setChangedListener(value->queueUserSearch());
        if(owner==null){var room=HousingClient.room();if(room!=null)adopt(room,true);}
    }

    boolean searchVisible(){return popup==Popup.FRIENDS;}
    private void queueUserSearch(){searchPage=0;friendScroll=0;queryDirty=true;queryAt=Util.getMeasuringTimeMs()+300;HousingSearchClient.invalidate();}
    // ---------------------------------------------------------------- 서버에서 온 것
    void roomChanged(Room previous,Room room){
        boolean newRoom=previous==null||owner==null||!room.owner().id().equals(owner);
        adopt(room,newRoom);
    }
    private void adopt(Room room,boolean reset){
        if(reset){
            owner=room.owner().id();held=null;candidate=null;zoom=1;panX=panY=0;bookScroll=0;moreEntry=-1;slot=room.activeSlot();
            Arrays.fill(dirty,false);for(var d:undo)d.clear();
            if(popup!=Popup.NONE)popup=Popup.NONE;
            guestbookOpen=false;
        }
        for(int i=0;i<HousingRules.SLOTS;i++)if(reset||!dirty[i])edit[i]=room.layouts().getOrDefault(i,Layout.EMPTY);
        if(busy&&savingSlot<0)busy=false;
    }
    void catalogChanged(){}
    void guestbookChanged(){}
    void friendsChanged(){busy=false;}
    void result(boolean ok,String message){
        busy=false;
        if(ok&&savingSlot>=0){dirty[savingSlot]=false;}
        savingSlot=-1;
        if(ok&&popup==Popup.EXPAND)popup=Popup.NONE;
        if(ok&&message.startsWith("방명록을 남겼"))post.widget.setText("");
        say(message,ok);
        sound(ok?1.2f:.8f,.18f);
    }
    private void say(String message,boolean ok){if(message==null||message.isEmpty())return;toast=message;toastOk=ok;toastAt=Util.getMeasuringTimeMs();}

    private Room room(){return HousingClient.room();}
    private boolean editing(){var r=room();return r!=null&&r.editable();}
    private UUID self(){return client.player==null?null:client.player.getUuid();}
    private Layout layout(){var r=room();if(r==null)return Layout.EMPTY;return editing()?edit[slot]:r.shown();}
    private boolean anyDirty(){for(boolean d:dirty)if(d)return true;return false;}
    private int owned(String id){var r=room();return r==null?0:r.owned().getOrDefault(id,0);}
    private boolean send(Request r){
        if(busy)return false;
        if(!HousingClient.send(r)){say("하우징 서버에 연결되어 있지 않아요.",false);return false;}
        busy=true;busyAt=Util.getMeasuringTimeMs();return true;
    }
    private void change(Layout next){undo[slot].push(edit[slot]);while(undo[slot].size()>UNDO_LIMIT)undo[slot].removeLast();edit[slot]=next;dirty[slot]=true;}

    @Override public void tick(){
        super.tick();if(client.currentScreen!=this)return;HousingSearchClient.tick();
        if(room()==null){Arrays.fill(dirty,false);popup=Popup.NONE;super.close();return;}
        if(popup==Popup.FRIENDS&&queryDirty&&Util.getMeasuringTimeMs()>=queryAt){queryDirty=false;HousingSearchClient.send(userSearch.text(),searchPage);}
        if(busy&&Util.getMeasuringTimeMs()-busyAt>5000){busy=false;savingSlot=-1;}
    }

    // ---------------------------------------------------------------- 그리기
    @Override public void render(DrawContext c,int sx,int sy,float delta){
        var room=room();if(room==null)return;
        var f=fit();double mx=f.x(sx),my=f.y(sy);mouseX=mx;mouseY=my;
        boolean modal=popup!=Popup.NONE;int newHot=0;
        start(c);
        try{
            background(c,f);
            boolean edit=editing();
            visibleItems=edit?items():List.of();
            // ---- 방
            float[] area=roomArea();
            iso=HousingIso.fit(room.size(),area[0],area[1],area[2],area[3],zoom,panX,panY);
            c.enableScissor(Math.round(area[0]-300),Math.round(area[1]-20),Math.round(area[0]+area[2]),Math.round(area[1]+area[3]));
            try{int h=drawRoom(c,room,mx,my,!modal&&inRoom(mx,my));if(h!=0)newHot=h;}
            finally{c.disableScissor();}
            spectators(c,room,area);
            // ---- 제목·방 정보
            title(c,room,edit);
            {int h=infoPanel(c,room,mx,my,modal,edit);if(h!=0)newHot=h;}
            {int h=shifted(c,dx,()->topButtons(c,room,mx-dx,my,modal,edit));if(h!=0)newHot=h;}
            if(edit){
                int h=tools(c,mx,my,modal);if(h!=0)newHot=h;
                h=inventory(c,mx,my,modal);if(h!=0)newHot=h;
                if(guestbookOpen){h=guestbook(c,room,mx,my,modal,1311+dx,84,334,506);if(h!=0)newHot=h;}
            }else{int h=guestbook(c,room,mx,my,modal,1311+dx,135,334,650);if(h!=0)newHot=h;}
            {int h=shifted(c,dx,()->zoomButtons(c,mx-dx,my,modal,edit));if(h!=0)newHot=h;}
            drawToast(c,edit);
            if(!modal&&edit&&newHot>=H_ITEM&&newHot<H_MORE)itemTooltip(c,newHot-H_ITEM,mx,my);
            if(busy){long now=Util.getMeasuringTimeMs();float t=now/180f;for(int i=0;i<3;i++)HudMesh.disk(c,820+dx/2+i*18,edit?566:870,3,((int)(100+155*(.5+.5*Math.sin(t-i)))<<24)|0x92E7F2);}
            if(modal){c.getMatrices().push();c.getMatrices().translate(0,0,80);try{HudMesh.quad(c,-400,-400,VW+dx+800,VH+800,0xA0050B14,0xA0050B14);newHot=shifted(c,dx/2,()->popup(c,room,mx-dx/2,my));}finally{c.getMatrices().pop();}}
        }finally{end(c);}
        hot=newHot;hover(hot>=H_ITEM&&hot<H_MORE?0:hot);
    }

    private void background(DrawContext c,SocialLayout.Fit f){
        // 화면 전체를 덮게 (가상 좌표 밖까지), 비율 유지
        float x0=(float)f.x(0),y0=(float)f.y(0),x1=(float)f.x(width),y1=(float)f.y(height);
        float w=x1-x0,h=y1-y0,k=Math.max(w/VW,h/VH);
        stretch(images,c,BACKGROUND,x0+(w-VW*k)/2,y0+(h-VH*k)/2,VW*k,VH*k,0xFFFFFFFF);
    }
    private float[] roomArea(){return editing()?new float[]{300,84,1300+dx,512}:new float[]{300,92,1000+dx,800};}
    private boolean inRoom(double mx,double my){float[] a=roomArea();return mx>=a[0]&&mx<a[0]+a[2]&&my>=a[1]&&my<a[1]+a[3];}

    private void title(DrawContext c,Room room,boolean edit){
        icon(images,c,ICON_TITLE_STAR,72,56,74,0xFFFFFFFF);
        boolean mine=room.owner().id().equals(self());
        String text=edit?"내 방 꾸미기":mine?"내 방":room.owner().name()+"의 방";
        type.draw(c,fit(text,40,360,TITLE),122,50,40,TITLE_GOLD,TITLE,false);
        stretch(images,c,ICON_TITLE_UNDERLINE,112,78,190,13,0xFFFFFFFF);
    }

    private int infoPanel(DrawContext c,Room room,double mx,double my,boolean modal,boolean edit){
        int hit=0;var o=room.owner();
        nine(images,c,PANEL_ROOM_INFO,22,108,290,236,26,0xFFFFFFFF);
        face(c,o,44,128,92);
        type.draw(c,fit(o.name(),26,150,BOLD),150,152,26,0xFFFFFFFF,BOLD,false);
        if(o.house()>=0){
            images.drawTexture(c,HOUSE_EMBLEM[o.house()],150,178,0,0,24,24,128,128,128,128,0xFFFFFFFF);
            type.draw(c,SchoolProtocol.HOUSES.get(o.house()),180,190,18,SUB,BODY,false);
        }
        HudMesh.line(c,44,236,290,236,1f,0x66E1B866);
        icon(images,c,ICON_VISITOR,58,262,20,0xFFFFFFFF);
        type.draw(c,"오늘 "+fmt(room.today())+"   |   누적 "+fmt(room.total()),78,262,18,TEXT,BODY,false);
        boolean canLike=!edit&&!o.id().equals(self());
        if(canLike){
            boolean over=!modal&&in(mx,my,44,284,246,44);if(over)hit=H_LIKE;
            nine(images,c,room.liked()?LIKE_PRESSED:LIKE_NORMAL,44,284,246,44,12,over?0xFFFFFFFF:0xFFE6E6E6);
            icon(images,c,ICON_HEART,72,306,22,room.liked()?0xFFFFFFFF:0xFFB8B8C0);
            type.draw(c,"좋아요 "+fmt(room.likes()),92,306,19,room.liked()?ROSE:TEXT,BODY,false);
        }else{
            icon(images,c,ICON_HEART,58,304,20,0xFFFFFFFF);
            type.draw(c,"좋아요 "+fmt(room.likes()),78,304,18,TEXT,BODY,false);
        }
        return hit;
    }

    private record Btn(int hot,String label,Tex icon,float x,float w){}
    private int topButtons(DrawContext c,Room room,double mx,double my,boolean modal,boolean edit){
        var list=new ArrayList<Btn>();
        if(edit){
            list.add(new Btn(H_EXPAND,"방 확장",ICON_EXPAND,1142,103));list.add(new Btn(H_BGM,"BGM",ICON_BGM,1256,99));
            list.add(new Btn(H_GUESTBOOK,"방명록",ICON_GUESTBOOK,1366,97));list.add(new Btn(H_FRIENDS,"유저 검색",ICON_FRIENDS,1473,94));
        }else{
            if(room.home()&&!room.owner().id().equals(self()))list.add(new Btn(H_HOME,"내 방",ICON_TITLE_STAR,1212,110));
            list.add(new Btn(H_BGM,"BGM",ICON_BGM,1337,103));list.add(new Btn(H_FRIENDS,"유저 검색",ICON_FRIENDS,1452,115));
        }
        list.add(new Btn(H_CLOSE,"",ICON_CLOSE,1578,70));
        int hit=0;
        for(var b:list){
            boolean over=!modal&&in(mx,my,b.x(),22,b.w(),50);if(over)hit=b.hot();
            boolean active=b.hot()==H_GUESTBOOK&&guestbookOpen;
            button(c,b.label(),b.icon(),b.x(),22,b.w(),50,over,active);
        }
        return hit;
    }
    private void button(DrawContext c,String label,Tex icon,float x,float y,float w,float h,boolean over,boolean active){
        Tex bg=over&&mouseDown?BUTTON_PRESSED:over||active?BUTTON_HOVER:BUTTON_NORMAL;
        nine(images,c,bg,x,y,w,h,12,0xFFFFFFFF);
        int color=over||active?0xFFFFFFFF:GOLD_TEXT;
        if(label.isEmpty()){if(icon!=null)HousingTex.icon(images,c,icon,x+w/2,y+h/2,h*.44f,0xFFFFFFFF);return;}
        float size=Math.min(19,h*.4f);
        float iw=icon==null?0:h*.44f+8;
        if(label.equals("유저 검색"))size=HousingSearchTextFit.buttonSize(size,w,iw,type.width(label,size,BODY));
        float tw=type.width(label,size,BODY),left=x+(w-tw-iw)/2;
        if(icon!=null)HousingTex.icon(images,c,icon,left+h*.22f,y+h/2,h*.44f,0xFFFFFFFF);
        type.draw(c,label,left+iw,y+h/2,size,color,BODY,false);
    }

    // ---------------------------------------------------------------- 방
    private int drawRoom(DrawContext c,Room room,double mx,double my,boolean live){
        int n=room.size();var lay=layout();
        Def wallDef=lay.wallpaper().isEmpty()?null:HousingClient.def(lay.wallpaper());
        Def floorDef=lay.floor().isEmpty()?null:HousingClient.def(lay.floor());
        int wall=wallDef==null?DEFAULT_WALL:wallDef.color(),band=wallDef==null?DEFAULT_WAINSCOT:mix(wallDef.accent(),0xFF1A120C,.45f);
        int floorA=floorDef==null?DEFAULT_FLOOR:floorDef.color(),floorB=floorDef==null?DEFAULT_FLOOR_2:floorDef.accent();
        float H=HousingRules.WALL_HEIGHT,t=.14f;
        // 바닥 두께 (앞쪽 두 면)
        face(c,new float[][]{{0,0,n},{n,0,n},{n,-.3f,n},{0,-.3f,n}},shade(TRIM,1f));
        face(c,new float[][]{{n,0,0},{n,0,n},{n,-.3f,n},{n,-.3f,0}},shade(TRIM,.8f));
        // 벽 (뒤 두 면): 벽지가 없으면 기본 벽 그림(wall_default.png, 한 칸 × 높이 4칸)을 칸마다, 있으면 벽지 색 + 아래 판자 + 위 몰딩
        boolean wallArt=wallDef!=null&&loaded(wallDef.icon());
        if(wallArt){
            for(int i=0;i<n;i++){
                artFace(c,wallDef.icon(),new float[][]{{0,H,i},{0,H,i+1},{0,0,i},{0,0,i+1}},0xFFD6D2CC,0,1,0,1);
                artFace(c,wallDef.icon(),new float[][]{{i,H,0},{i+1,H,0},{i,0,0},{i+1,0,0}},0xFFFFFFFF,0,1,0,1);
            }
        }else if(wallDef==null){
            for(int i=0;i<n;i++){
                texFace(c,WALL_TEXTURE,new float[][]{{0,H,i},{0,H,i+1},{0,0,i+1},{0,0,i}},0xFFD6D2CC);
                texFace(c,WALL_TEXTURE,new float[][]{{i,H,0},{i+1,H,0},{i+1,0,0},{i,0,0}},0xFFFFFFFF);
            }
        }else{
        face(c,new float[][]{{0,0,0},{0,0,n},{0,H,n},{0,H,0}},shade(wall,.92f));
        face(c,new float[][]{{0,0,0},{n,0,0},{n,H,0},{0,H,0}},wall);
        if(wallDef!=null)for(int i=1;i<n;i++){ // 벽지 세로 무늬
            line3(c,0,.7f,i,0,H,i,alpha(wallDef.accent(),.35f),1.2f);line3(c,i,.7f,0,i,H,0,alpha(wallDef.accent(),.35f),1.2f);
        }
        face(c,new float[][]{{0,0,0},{0,0,n},{0,.7f,n},{0,.7f,0}},shade(band,.85f));
        face(c,new float[][]{{0,0,0},{n,0,0},{n,.7f,0},{0,.7f,0}},band);
        line3(c,0,.7f,0,0,.7f,n,GOLD_LINE,1.4f);line3(c,0,.7f,0,n,.7f,0,GOLD_LINE,1.4f);
        }
        // 벽 윗면·끝면 (두께)
        face(c,new float[][]{{-t,H,-t},{-t,H,n},{0,H,n},{0,H,0}},shade(TRIM,1.15f));
        face(c,new float[][]{{-t,H,-t},{n,H,-t},{n,H,0},{0,H,0}},shade(TRIM,1.15f));
        face(c,new float[][]{{-t,0,n},{0,0,n},{0,H,n},{-t,H,n}},shade(TRIM,.9f));
        face(c,new float[][]{{n,0,-t},{n,0,0},{n,H,0},{n,H,-t}},shade(TRIM,.75f));
        line3(c,0,0,0,0,H,0,alpha(0xFF000000,.25f),1.2f);
        // 바닥 칸 (바닥재 그림도 한 장이 2×2 칸)
        boolean floorArt=floorDef!=null&&loaded(floorDef.icon());
        for(int x=0;x<n;x++)for(int z=0;z<n;z++){
            float[][] cell={{x,0,z},{x+1,0,z},{x+1,0,z+1},{x,0,z+1}};
            // 바닥 그림 한 장이 2×2 칸을 덮는다 (한 칸에 한 장이면 나뭇결이 너무 촘촘함)
            if(floorDef==null){float u=(x&1)*.5f,v=(z&1)*.5f;texFace(c,FLOOR_TEXTURE,cell,0xFFFFFFFF,new float[]{u,u+.5f,u+.5f,u},new float[]{v,v,v+.5f,v+.5f});}
            else if(floorArt){float u=(x&1)*.5f,v=(z&1)*.5f;artFace(c,floorDef.icon(),new float[][]{{x,0,z},{x+1,0,z},{x,0,z+1},{x+1,0,z+1}},0xFFFFFFFF,u,u+.5f,v,v+.5f);}
            else face(c,cell,((x+z)&1)==0?floorA:floorB);
        }
        // 칸 구분선: 평소엔 숨기고, 가구를 들고 있을 때만 점선으로 또렷하게
        if(editing()&&held!=null){
            for(int i=1;i<n;i++){dashed(c,i,0,0,i,0,n,0xB0FFFFFF);dashed(c,0,0,i,n,0,i,0xB0FFFFFF);}
            if(held.def().wall())for(int i=1;i<n;i++){dashed(c,0,0,i,0,HousingRules.WALL_HEIGHT,i,0x90FFFFFF);dashed(c,i,0,0,i,HousingRules.WALL_HEIGHT,0,0x90FFFFFF);}
            if(held.def().wall())for(int y=1;y<HousingRules.WALL_HEIGHT;y++){dashed(c,0,y,0,0,y,n,0x90FFFFFF);dashed(c,0,y,0,n,y,0,0x90FFFFFF);}
        }

        // 놓인 것들
        var list=lay.placements();var order=new ArrayList<Integer>();
        for(int i=0;i<list.size();i++)order.add(i);
        order.sort(Comparator.comparingDouble(i->sortKey(list.get(i))));
        var hits=new ArrayList<Drawn>();
        for(int i:order){
            var p=list.get(i);var d=HousingClient.def(p.id());if(d==null||d.surface())continue;
            float[][] hull=drawPlaced(c,d,p,1f,false);
            hits.add(new Drawn(i,p,d,hull));
        }
        drawn=hits;
        showcases(c,room,hits);
        // 마우스 아래 가구
        hoverItem=-1;int hit=0;
        if(live&&held==null)for(int k=hits.size()-1;k>=0;k--)if(HousingIso.inside(hits.get(k).hull(),mx,my)){hoverItem=hits.get(k).index();break;}
        if(hoverItem>=0){
            for(var d:hits)if(d.index()==hoverItem){
                outline(c,d.hull(),editing()?0xFFFFF0C8:0xCCFFFFFF);
                c.getMatrices().push();c.getMatrices().translate(0,0,300); // 빗자루 모델(z 200) 위로
                try{label(c,d);}finally{c.getMatrices().pop();}
            }
            hit=500;
        }
        // 들고 있는 가구 미리보기
        candidate=null;candidateWhy=null;
        if(held!=null&&live){
            candidate=candidateAt(mx,my);
            if(candidate!=null){
                var others=lay.placements();
                candidateWhy=HousingRules.fits(held.def(),candidate,n,others,HousingClient::def);
                boolean ok=candidateWhy==null;
                if(held.def().floor()){
                    int[] cells=HousingRules.cells(held.def(),candidate);
                    for(int x=cells[0];x<cells[2];x++)for(int z=cells[1];z<cells[3];z++)tile(c,x,z,ok);
                }else{
                    int side=candidate.wallSide();float a0=candidate.x(),a1=a0+held.def().w(),y0=candidate.y(),y1=y0+held.def().wallCells();
                    face(c,wallQuad(side,a0-.02f,a1+.02f,y0-.02f,y1+.02f),ok?0x553FD27A:0x55E0485A);
                }
                drawPlaced(c,held.def(),candidate,.62f,true);
            }
        }
        return hit;
    }
    private static float sortKey(Placement p){
        var d=HousingClient.def(p.id());
        if(d==null)return 0;
        if(d.wall())return -1000+p.x()+p.y()*.01f; // 벽걸이는 언제나 바닥 가구 뒤
        int[] c=HousingRules.cells(d,p);
        return HousingIso.depth(c[0],c[1],c[2],c[3])+(d.height()<2?-.5f:0); // 러그처럼 납작한 것은 먼저
    }
    private float[][] drawPlaced(DrawContext c,Def d,Placement p,float alpha,boolean ghost){
        int[] size=d.icon().isEmpty()?null:images.size(d.icon());
        if(size!=null){
            int tint=alpha(0xFFFFFFFF,alpha);
            if(d.wall()){
                // 벽걸이 그림(정면, 한 칸 256px)을 벽 칸에 그대로 붙인다. 왼쪽 벽은 화면 왼쪽이 z 큰 쪽.
                int side=p.wallSide();float a0=p.x(),a1=p.x()+d.w(),y0=p.y(),y1=p.y()+d.wallCells(),e=.02f;
                float[][] q=side==0?new float[][]{{e,y1,a1},{e,y1,a0},{e,y0,a1},{e,y0,a0}}:new float[][]{{a0,y1,e},{a1,y1,e},{a0,y0,e},{a1,y0,e}};
                artFace(c,d.icon(),q,tint,0,1,0,1);
                return iso.wallRect(side,a0,a1,y0,y1);
            }
            // 바닥 가구 그림(쿼터뷰, 한 칸 = 가로 256px): 그림의 (32 + 가로칸×128, 높이-32) 점이 차지 칸의 맨 앞 꼭짓점에 오게.
            // 홀수 회전은 좌우를 뒤집어 그린다 (뒤집으면 가로·세로 칸이 바뀐 모양이 됨).
            int[] cells=HousingRules.cells(d,p);int fw=cells[2]-cells[0];
            float k=iso.tile/256f,ax=iso.sx(cells[2],0,cells[3]),ay=iso.sy(cells[2],0,cells[3]);
            float left=ax-(32+fw*128)*k,top=ay-(size[1]-32)*k,right=left+size[0]*k,bottom=top+size[1]*k;
            art(c,d.icon(),new float[]{left,right,left,right},new float[]{top,top,bottom,bottom},(p.rot()&1)==1,tint,0,1,0,1);
            float h=Math.max(.1f,d.height()/10f);
            return iso.boxHull(cells[0]+.08f,cells[1]+.08f,cells[2]-.08f,cells[3]-.08f,h);
        }
        int color=alpha(d.color(),alpha),accent=alpha(d.accent(),alpha);
        if(d.wall()){
            int side=p.wallSide();float a0=p.x()+.06f,a1=p.x()+d.w()-.06f,y0=p.y()+.06f,y1=p.y()+d.wallCells()-.06f;
            face(c,wallQuad(side,a0,a1,y0,y1),accent);
            face(c,wallQuad(side,a0+.08f,a1-.08f,y0+.08f,y1-.08f),color);
            return iso.wallRect(side,a0,a1,y0,y1);
        }
        int[] cells=HousingRules.cells(d,p);
        float x0=cells[0]+.06f,z0=cells[1]+.06f,x1=cells[2]-.06f,z1=cells[3]-.06f,h=Math.max(.04f,d.height()/10f);
        face(c,new float[][]{{x0,0,z1},{x1,0,z1},{x1,h,z1},{x0,h,z1}},shade(color,.78f));
        face(c,new float[][]{{x1,0,z0},{x1,0,z1},{x1,h,z1},{x1,h,z0}},shade(color,.62f));
        face(c,new float[][]{{x0,h,z0},{x1,h,z0},{x1,h,z1},{x0,h,z1}},color);
        float in=Math.min(.14f,Math.min(x1-x0,z1-z0)*.2f);
        face(c,new float[][]{{x0+in,h,z0+in},{x1-in,h,z0+in},{x1-in,h,z1-in},{x0+in,h,z1-in}},alpha(mix(color,accent,.35f),alpha));
        int edge=alpha(accent,alpha*.9f);
        line3(c,x0,h,z1,x1,h,z1,edge,1.3f);line3(c,x1,h,z0,x1,h,z1,edge,1.3f);line3(c,x1,0,z1,x1,h,z1,edge,1.1f);
        if(h>.3f){line3(c,x0,h*.5f,z1,x1,h*.5f,z1,alpha(accent,alpha*.6f),1f);line3(c,x1,h*.5f,z0,x1,h*.5f,z1,alpha(accent,alpha*.6f),1f);}
        return iso.boxHull(x0,z0,x1,z1,h);
    }
    private float[][] wallQuad(int side,float a0,float a1,float y0,float y1){
        float e=.02f;
        return side==0?new float[][]{{e,y0,a0},{e,y0,a1},{e,y1,a1},{e,y1,a0}}:new float[][]{{a0,y0,e},{a1,y0,e},{a1,y1,e},{a0,y1,e}};
    }
    /** 들고 있는 가구를 마우스 위치에 놓을 자리 (방 밖으로 너무 멀면 null). */
    private Placement candidateAt(double mx,double my){
        var d=held.def();int n=iso.size;
        if(d.floor()){
            float[] fz=iso.floorAt(mx,my);
            if(fz[0]<-1.5f||fz[1]<-1.5f||fz[0]>n+1.5f||fz[1]>n+1.5f)return null;
            int[] f=d.footprint(held.rot());
            int x=Math.clamp(Math.round(fz[0]-f[0]/2f),0,Math.max(0,n-f[0])),z=Math.clamp(Math.round(fz[1]-f[1]/2f),0,Math.max(0,n-f[1]));
            return new Placement(d.id(),Math.min(x,HousingRules.MAX_SIZE-1),0,Math.min(z,HousingRules.MAX_SIZE-1),held.rot());
        }
        int side=iso.wallSide(mx);float[] w=iso.wallAt(mx,my,side);
        if(w[0]<-1.5f||w[0]>n+1.5f||w[1]<-1||w[1]>HousingRules.WALL_HEIGHT+1)return null;
        int a=Math.clamp(Math.round(w[0]-d.w()/2f),0,Math.max(0,n-d.w())),y=Math.clamp(Math.round(w[1]-d.wallCells()/2f),0,Math.max(0,HousingRules.WALL_HEIGHT-d.wallCells()));
        return new Placement(d.id(),Math.min(a,HousingRules.MAX_SIZE-1),y,0,side);
    }
    private void tile(DrawContext c,int x,int z,boolean ok){
        float l=iso.sx(x,0,z+1),r=iso.sx(x+1,0,z),t=iso.sy(x,0,z),b=iso.sy(x+1,0,z+1);
        stretch(images,c,ok?TILE_VALID:TILE_INVALID,l,t,r-l,b-t,0xDDFFFFFF);
    }
    private void label(DrawContext c,Drawn d){
        float top=Float.MAX_VALUE,cx=0;for(var p:d.hull()){top=Math.min(top,p[1]);cx+=p[0];}cx/=d.hull().length;
        String name=d.def().name()+labelSuffix(d.def().id());float w=type.width(name,16,BODY)+30;
        nine(images,c,TOOLTIP,cx-w/2,top-40,w,32,8,0xF0FFFFFF);
        type.draw(c,name,cx,top-24,16,TEXT,BODY,true);
    }
    private void outline(DrawContext c,float[][] hull,int color){
        for(int i=0;i<hull.length;i++){var a=hull[i];var b=hull[(i+1)%hull.length];HudMesh.line(c,a[0],a[1],b[0],b[1],2f,color);}
    }
    private boolean loaded(String path){return path!=null&&!path.isEmpty()&&images.size(path)!=null;}
    /** 그림 한 장을 화면 네 점(왼쪽 위, 오른쪽 위, 왼쪽 아래, 오른쪽 아래)에 입힌다 (밉맵 있는 UI 텍스처). mirror = 좌우 뒤집기. */
    private boolean art(DrawContext c,String path,float[] xs,float[] ys,boolean mirror,int tint,float u0,float u1,float v0,float v1){
        float[] us=mirror?new float[]{u1,u0,u1,u0}:new float[]{u0,u1,u0,u1},vs={v0,v0,v1,v1};
        return images.drawGrid(c,Identifier.of(path),false,1,1,xs,ys,us,vs,new int[]{tint,tint,tint,tint},false);
    }
    /** 방 좌표 네 점(왼쪽 위, 오른쪽 위, 왼쪽 아래, 오른쪽 아래)에 그림을 입힌다. */
    private boolean artFace(DrawContext c,String path,float[][] p,int tint,float u0,float u1,float v0,float v1){
        float[] xs=new float[4],ys=new float[4];
        for(int i=0;i<4;i++){xs[i]=iso.sx(p[i][0],p[i][1],p[i][2]);ys[i]=iso.sy(p[i][0],p[i][1],p[i][2]);}
        return art(c,path,xs,ys,false,tint,u0,u1,v0,v1);
    }
    private static final Identifier WALL_TEXTURE=Identifier.of("magiccodex","textures/gui/housing/wall_default.png"),FLOOR_TEXTURE=Identifier.of("magiccodex","textures/gui/housing/floor_default.png");
    /** 방 좌표 네 점에 그림 한 장을 통째로 입힌다 (점 순서: 그림의 왼쪽 위 → 오른쪽 위 → 오른쪽 아래 → 왼쪽 아래). */
    private void texFace(DrawContext c,Identifier texture,float[][] p,int tint){texFace(c,texture,p,tint,new float[]{0,1,1,0},new float[]{0,0,1,1});}
    private void texFace(DrawContext c,Identifier texture,float[][] p,int tint,float[] u0,float[] v0){
        float[] xs=new float[4],ys=new float[4],us=u0.clone(),vs=v0.clone();
        for(int i=0;i<4;i++){xs[i]=iso.sx(p[i][0],p[i][1],p[i][2]);ys[i]=iso.sy(p[i][0],p[i][1],p[i][2]);}
        double area=0;for(int i=0;i<4;i++){int j=(i+1)%4;area+=xs[i]*ys[j]-xs[j]*ys[i];}
        if(area>0){swap(xs,1,3);swap(ys,1,3);swap(us,1,3);swap(vs,1,3);}
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(v->{var b=v.getBuffer(RenderLayer.getGuiTextured(texture));for(int i=0;i<4;i++)b.vertex(m,xs[i],ys[i],0).texture(us[i],vs[i]).color(tint);});
    }
    private static void swap(float[] a,int i,int j){float t=a[i];a[i]=a[j];a[j]=t;}
    /** 방 좌표 네 점 → 사각형 하나 (GUI 레이어는 뒷면을 버리므로 화면에서 시계 반대 방향으로 맞춘다). */
    private void face(DrawContext c,float[][] p,int color){
        float[] xs=new float[4],ys=new float[4];
        for(int i=0;i<4;i++){xs[i]=iso.sx(p[i][0],p[i][1],p[i][2]);ys[i]=iso.sy(p[i][0],p[i][1],p[i][2]);}
        quad(c,xs,ys,color);
    }
    static void quad(DrawContext c,float[] xs,float[] ys,int color){
        double area=0;for(int i=0;i<4;i++){int j=(i+1)%4;area+=xs[i]*ys[j]-xs[j]*ys[i];}
        if(area>0){float t=xs[1];xs[1]=xs[3];xs[3]=t;t=ys[1];ys[1]=ys[3];ys[3]=t;}
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(v->{var b=v.getBuffer(RenderLayer.getGui());for(int i=0;i<4;i++)b.vertex(m,xs[i],ys[i],0).color(color);});
    }
    /** 방 좌표 선분을 점선으로 (한 칸에 점 4개). */
    private void dashed(DrawContext c,float x0,float y0,float z0,float x1,float y1,float z1,int color){
        float len=Math.max(Math.abs(x1-x0),Math.max(Math.abs(y1-y0),Math.abs(z1-z0)));int n=Math.max(1,Math.round(len*4));
        for(int i=0;i<n;i++){float a=(i+.2f)/n,b=(i+.65f)/n;
            line3(c,x0+(x1-x0)*a,y0+(y1-y0)*a,z0+(z1-z0)*a,x0+(x1-x0)*b,y0+(y1-y0)*b,z0+(z1-z0)*b,color,1.6f);}
    }
    private void line3(DrawContext c,float x0,float y0,float z0,float x1,float y1,float z1,int color,float w){
        HudMesh.line(c,iso.sx(x0,y0,z0),iso.sy(x0,y0,z0),iso.sx(x1,y1,z1),iso.sy(x1,y1,z1),w,color);
    }

    private void spectators(DrawContext c,Room room,float[] area){
        var v=HousingClient.viewers();if(v==null)return;
        var names=new ArrayList<String>();int count=0;
        for(var s:v.shown())if(!s.id().equals(room.owner().id())){count++;if(names.size()<3)names.add(s.name());}
        int total=Math.max(count,v.total()-(v.shown().stream().anyMatch(s->s.id().equals(room.owner().id()))?1:0));
        if(total<=0)return;
        String text="구경 중 "+total+"명 · "+String.join(", ",names)+(total>names.size()?" 외":"");
        float cx=area[0]+area[2]/2,w=Math.min(420,type.width(text,16,BODY)+70);
        nine(images,c,SPECTATOR_PLATE,cx-w/2,98,w,38,10,0xF0FFFFFF);
        icon(images,c,ICON_VISITOR,cx-w/2+24,117,17,0xFFFFFFFF);
        type.draw(c,fit(text,16,w-60,BODY),cx-w/2+40,117,16,TEXT,BODY,false);
    }

    // ---------------------------------------------------------------- 편집 도구·보관함
    private int tools(DrawContext c,double mx,double my,boolean modal){
        int hit=0;
        String hint=held!=null?held.def().name()+" · R 회전 · 우클릭 취소 · Delete 보관함으로"
                :"보관함에서 골라 방에 놓아요 · 놓인 가구를 누르면 옮기고, 우클릭하면 보관함으로 돌아가요";
        if(held!=null&&candidateWhy!=null)hint=candidateWhy;
        type.draw(c,fit(hint,17,540+dx,BODY),320,622,17,held!=null&&candidateWhy!=null?RED:MUTED,BODY,false);
        Object[][] tools={{H_ROTATE,"회전",ICON_ROTATE,880f+dx,92f},{H_RETRIEVE,"회수",ICON_RETRIEVE,982f+dx,90f},{H_UNDO,"되돌리기",ICON_UNDO,1082f+dx,124f},{H_SAVE,"저장",ICON_SAVE,1216f+dx,100f}};
        for(var t:tools){
            int id=(int)t[0];float x=(float)t[3],w=(float)t[4];
            boolean over=!modal&&in(mx,my,x,600,w,44);if(over)hit=id;
            boolean glow=id==H_SAVE&&dirty[slot];
            button(c,(String)t[1],(Tex)t[2],x,600,w,44,over,glow);
        }
        HudMesh.line(c,1338+dx,604,1338+dx,640,1.2f,0x88E1B866);
        var room=room();
        for(int i=0;i<HousingRules.SLOTS;i++){
            float x=1370+dx+i*60.5f;boolean over=!modal&&in(mx,my,x,600,48,44);if(over)hit=H_SLOT+i;
            nine(images,c,i==slot?LAYOUT_SELECTED:LAYOUT_UNSELECTED,x,600,48,44,10,over?0xFFFFFFFF:0xFFE6E6E6);
            type.draw(c,String.valueOf(i+1)+(dirty[i]?"*":""),x+24,622,20,i==slot?0xFFFFFFFF:GOLD_TEXT,BOLD,true);
            if(room!=null&&room.activeSlot()==i)HudMesh.disk(c,x+42,606,4,GREEN);
        }
        return hit;
    }
    private List<Def> items(){
        var room=room();if(room==null)return List.of();
        String q=search==null?"":search.text().strip().toLowerCase(Locale.ROOT);
        var list=new ArrayList<Def>();
        for(var d:HousingClient.catalog()){
            int cat=d.category();int want=tab==0?HousingRules.CAT_FURNITURE:tab==1?HousingRules.CAT_PROP:tab==2?HousingRules.CAT_WALL:HousingRules.CAT_SURFACE;
            if(cat!=want||owned(d.id())<=0)continue;
            if(!q.isEmpty()&&!d.name().toLowerCase(Locale.ROOT).contains(q)&&!d.id().contains(q))continue;
            list.add(d);
        }
        return list;
    }
    private float maxInvScroll(){return Math.max(0,visibleItems.size()*SLOT_STEP-(SLOT_STEP-SLOT_W)-(SLOT_VIEW+dx));}
    private int inventory(DrawContext c,double mx,double my,boolean modal){
        int hit=0;
        nine(images,c,PANEL_INVENTORY,40,657,1592+dx,250,22,0xFFFFFFFF);
        for(int i=0;i<TABS.length;i++){
            boolean over=!modal&&in(mx,my,TAB_X[i],675,TAB_W[i],49);if(over)hit=H_TAB+i;
            nine(images,c,i==tab?TAB_SELECTED:TAB_UNSELECTED,TAB_X[i],675,TAB_W[i],49,12,over?0xFFFFFFFF:0xFFE8E8E8);
            float tw=type.width(TABS[i],19,BODY),left=TAB_X[i]+(TAB_W[i]-tw-32)/2;
            icon(images,c,TAB_ICONS[i],left+12,700,24,0xFFFFFFFF);
            type.draw(c,TABS[i],left+32,700,19,i==tab?0xFFFFFFFF:GOLD_TEXT,BODY,false);
        }
        nine(images,c,INPUT_FIELD,1238+dx,675,357,49,10,0xFFFFFFFF);
        icon(images,c,ICON_SEARCH,1264+dx,700,20,0xFFD0D8E0);
        search.draw(c,1276+dx,675,312,49,"가구 검색");
        invScroll=Math.clamp(invScroll,0,maxInvScroll());
        var list=visibleItems;var lay=layout();
        boolean live=!modal&&my>=SLOT_Y&&my<SLOT_Y+SLOT_H&&mx>=72&&mx<1600+dx;
        c.enableScissor(70,734,Math.round(1602+dx),888);
        try{
            for(int i=0;i<list.size();i++){
                float x=SLOT_X+i*SLOT_STEP-invScroll;if(x+SLOT_W<70||x>1602+dx)continue;
                var d=list.get(i);
                boolean over=live&&in(mx,my,x,SLOT_Y,SLOT_W,SLOT_H);if(over)hit=H_ITEM+i;
                boolean selected=held!=null&&held.def().id().equals(d.id())||d.surface()&&(d.id().equals(lay.wallpaper())||d.id().equals(lay.floor()));
                nine(images,c,selected||over?ITEM_SLOT_SELECTED:ITEM_SLOT,x,SLOT_Y,SLOT_W,SLOT_H,22,0xFFFFFFFF);
                int left=owned(d.id())-lay.usage().getOrDefault(d.id(),0);
                preview(c,d,x+SLOT_W/2,SLOT_Y+60,left>0||d.surface());
                type.draw(c,fit(d.name(),17,SLOT_W-20,BODY),x+SLOT_W/2,SLOT_Y+118,17,TEXT,BODY,true);
                if(d.surface()){
                    if(selected)type.draw(c,"적용 중",x+16,SLOT_Y+20,15,GREEN,BODY,false);
                }else{
                    String count="x"+Math.max(0,left);float cw=type.width(count,16,BOLD);
                    type.draw(c,count,x+SLOT_W-16-cw,SLOT_Y+20,16,left>0?GOLD_TEXT:RED,BOLD,false);
                }
            }
        }finally{c.disableScissor();}
        if(list.isEmpty())type.draw(c,HousingClient.catalog().isEmpty()?"가구 목록을 불러오는 중…":"이 칸에 가진 가구가 없어요",836+dx/2,810,20,MUTED,BODY,true);
        float max=maxInvScroll();
        if(max>0){
            float track=1520+dx;
            nine(images,c,SCROLL_TRACK_HORIZONTAL,75,888,track,8,3,0xFFFFFFFF);
            float thumb=Math.max(60,track*(SLOT_VIEW+dx)/(SLOT_VIEW+dx+max)),tx=75+(track-thumb)*invScroll/max;
            nine(images,c,SCROLL_THUMB,tx,887,thumb,10,4,0xFFFFFFFF);
        }
        return hit;
    }
    /** 보관함 칸 미리보기: 아이콘이 있으면 아이콘, 없으면 작은 상자 (벽지·바닥재는 색 견본). */
    private void preview(DrawContext c,Def d,float cx,float cy,boolean available){
        int tint=available?0xFFFFFFFF:0x77FFFFFF;
        if(d.surface()&&!d.icon().isEmpty()){
            // 벽지·바닥재 그림은 정사각형 견본으로 (벽지는 가운데쯤을 잘라서)
            int[] size=images.size(d.icon());
            if(size!=null){int side=Math.min(size[0],size[1]);images.drawPart(c,d.icon(),Math.round(cx-38),Math.round(cy-38),76,76,0,(size[1]-side)*.45f,side,side,tint);return;}
        }
        if(!d.icon().isEmpty()&&!d.surface()&&images.drawCentered(c,d.icon(),cx,cy,82,tint))return;
        var saved=iso;
        try{
            if(d.surface()){
                iso=new HousingIso(2,46,cx,cy-24);
                if(d.sub()==HousingRules.SURFACE_FLOOR){for(int x=0;x<2;x++)for(int z=0;z<2;z++)face(c,new float[][]{{x,0,z},{x+1,0,z},{x+1,0,z+1},{x,0,z+1}},alpha((x+z&1)==0?d.color():d.accent(),available?1:.5f));}
                else{iso=new HousingIso(2,46,cx,cy+16);face(c,new float[][]{{0,0,0},{2,0,0},{2,1.6f,0},{0,1.6f,0}},alpha(d.color(),available?1:.5f));face(c,new float[][]{{0,0,0},{2,0,0},{2,.45f,0},{0,.45f,0}},alpha(d.accent(),available?1:.5f));}
                return;
            }
            if(d.wall()){
                float tile=Math.min(58,110f/Math.max(d.w(),d.wallCells()+.5f));
                iso=new HousingIso(4,tile,cx-tile*d.w()/4,cy+tile*d.wallCells()*HousingIso.HEIGHT/2-tile*d.w()/8);
                drawPlaced(c,d,new Placement(d.id(),0,0,0,1),available?1:.5f,false);
                return;
            }
            int[] f=d.footprint(0);float h=Math.max(.05f,d.height()/10f);
            float tile=Math.min(64,100f/Math.max(1,(f[0]+f[1])/2f+h*HousingIso.HEIGHT*1.4f));
            iso=new HousingIso(8,tile,cx-(f[0]-f[1])*tile/4,cy-(f[0]+f[1])*tile/8+h*tile*HousingIso.HEIGHT/2);
            drawPlaced(c,d,new Placement(d.id(),0,0,0,0),available?1:.5f,false);
        }finally{iso=saved;}
    }
    private void itemTooltip(DrawContext c,int index,double mx,double my){
        if(index<0||index>=visibleItems.size())return;
        var d=visibleItems.get(index);int have=owned(d.id()),used=layout().usage().getOrDefault(d.id(),0);
        String kind=switch(d.category()){case HousingRules.CAT_FURNITURE->"가구 · "+d.w()+"×"+d.d();case HousingRules.CAT_PROP->"소품 · "+d.w()+"×"+d.d();
            case HousingRules.CAT_WALL->"벽걸이 · "+d.w()+"×"+d.wallCells();default->d.sub()==HousingRules.SURFACE_WALLPAPER?"벽지":"바닥재";};
        String line=d.surface()?kind+" · 누르면 적용/해제":kind+" · 설치 가능 "+Math.max(0,have-used)+" / "+have;
        float w=Math.max(type.width(d.name(),19,BOLD),type.width(line,15,BODY))+40,x=(float)Math.clamp(mx+16,10,VW-w-10),y=(float)my-76;
        c.getMatrices().push();c.getMatrices().translate(0,0,60);
        try{
            nine(images,c,TOOLTIP,x,y,w,66,10,0xF8FFFFFF);
            type.draw(c,d.name(),x+20,y+22,19,0xFFFFFFFF,BOLD,false);
            type.draw(c,line,x+20,y+46,15,SUB,BODY,false);
        }finally{c.getMatrices().pop();}
    }

    // ---------------------------------------------------------------- 방명록
    private record Row(Entry e,float top,float h,List<String> lines){}
    private int guestbook(DrawContext c,Room room,double mx,double my,boolean modal,float px,float py,float pw,float ph){
        int hit=0;
        nine(images,c,PANEL_GUESTBOOK,px,py,pw,ph,26,0xFFFFFFFF);
        icon(images,c,ICON_TITLE_STAR,px+48,py+56,44,0xFFFFFFFF);
        type.draw(c,"방명록",px+80,py+56,32,TITLE_GOLD,TITLE,false);
        HudMesh.line(c,px+24,py+96,px+pw-24,py+96,1f,0x66E1B866);
        float top=py+108,bottom=py+ph-96,x=px+20,w=pw-40;
        var book=HousingClient.guestbook();var entries=book==null?List.<Entry>of():book.entries();
        var rows=new ArrayList<Row>();float y=0;
        for(var e:entries){var lines=wrap(e.text(),16,w-24,4);float h=72+lines.size()*21;rows.add(new Row(e,y,h,lines));y+=h+10;}
        float content=Math.max(0,y-10),max=Math.max(0,content-(bottom-top));
        bookScroll=Math.clamp(bookScroll,0,max);
        boolean live=!modal&&my>=top&&my<bottom&&mx>=px&&mx<px+pw;
        UUID me=self();boolean mine=room.owner().id().equals(me);
        c.enableScissor(Math.round(px+10),Math.round(top),Math.round(px+pw-10),Math.round(bottom));
        try{
            for(int i=0;i<rows.size();i++){
                var r=rows.get(i);float ty=top+r.top()-bookScroll;if(ty+r.h()<top||ty>bottom)continue;
                var e=r.e();
                nine(images,c,GUESTBOOK_ENTRY,x,ty,w,r.h(),12,0xFFFFFFFF);
                face(c,e.author(),x+12,ty+12,46);
                type.draw(c,fit(e.author().name(),19,w-150,BOLD),x+68,ty+28,19,0xFFFFFFFF,BOLD,false);
                String when=ago(e.time());float ww=type.width(when,14,BODY);
                boolean canDelete=mine||e.author().id().equals(me);
                type.draw(c,when,x+w-(canDelete?40:14)-ww,ty+28,14,MUTED,BODY,false);
                for(int l=0;l<r.lines().size();l++)type.draw(c,r.lines().get(l),x+14,ty+70+l*21,16,TEXT,BODY,false);
                if(canDelete){
                    boolean over=live&&in(mx,my,x+w-36,ty+16,28,24);if(over)hit=H_MORE+i;
                    icon(images,c,ICON_MORE,x+w-22,ty+28,22,over?0xFFFFFFFF:0xFFB8C0C8);
                    if(moreEntry==e.id()){
                        boolean overDel=live&&in(mx,my,x+w-80,ty+r.h()-38,68,30);if(overDel)hit=H_DELETE+i;
                        button(c,"지우기",null,x+w-80,ty+r.h()-38,68,30,overDel,false);
                    }
                }
            }
        }finally{c.disableScissor();}
        if(rows.isEmpty())type.draw(c,book==null?"불러오는 중…":"첫 방명록을 남겨 주세요",px+pw/2,(top+bottom)/2,18,MUTED,BODY,true);
        if(max>0){
            nine(images,c,SCROLL_TRACK_VERTICAL,px+pw-14,top,6,bottom-top,2,0xFFFFFFFF);
            float thumb=Math.max(40,(bottom-top)*(bottom-top)/content),ty=top+(bottom-top-thumb)*bookScroll/max;
            nine(images,c,SCROLL_THUMB,px+pw-15,ty,8,thumb,3,0xFFFFFFFF);
        }
        HudMesh.line(c,px+24,py+ph-84,px+pw-24,py+ph-84,1f,0x55E1B866);
        float iy=py+ph-70,iw=pw-40-98;
        nine(images,c,INPUT_FIELD,px+20,iy,iw,52,10,0xFFFFFFFF);
        icon(images,c,ICON_GUESTBOOK,px+40,iy+26,20,0xFFD0D8E0);
        post.draw(c,px+52,iy,iw-36,52,"한마디 남기기");
        boolean over=!modal&&in(mx,my,px+pw-112,iy,92,52);if(over)hit=H_SEND;
        button(c,"남기기",null,px+pw-112,iy,92,52,over,false);
        return hit;
    }
    private float[] bookPanel(){return editing()?new float[]{1311+dx,84,334,506}:new float[]{1311+dx,135,334,650};}

    private int zoomButtons(DrawContext c,double mx,double my,boolean modal,boolean edit){
        float zx=edit&&guestbookOpen?1250:1603,x1=edit?zx:1511,y1=edit?508:800,x2=edit?zx:1585,y2=edit?560:800;
        int hit=0;
        boolean a=!modal&&in(mx,my,x1,y1,44,44),b=!modal&&in(mx,my,x2,y2,44,44);
        if(a)hit=H_ZOOM_IN;if(b)hit=H_ZOOM_OUT;
        button(c,"",ICON_ZOOM_PLUS,x1,y1,44,44,a,false);
        button(c,"",ICON_ZOOM_MINUS,x2,y2,44,44,b,false);
        return hit;
    }
    private void drawToast(DrawContext c,boolean edit){
        long age=Util.getMeasuringTimeMs()-toastAt;if(toast.isEmpty()||age>3600)return;
        float a=age<150?age/150f:age>3200?(3600-age)/400f:1;
        float[] area=roomArea();float cx=area[0]+area[2]/2,y=edit?534:840,w=Math.min(820,type.width(toast,18,BODY)+150);
        c.getMatrices().push();c.getMatrices().translate(0,0,50);
        try{
            nine(images,c,TOAST,cx-w/2,y,w,54,14,alpha(0xFFFFFFFF,a));
            type.draw(c,fit(toast,18,w-110,BODY),cx,y+27,18,alpha(toastOk?TEXT:0xFFFFC2C2,a),BODY,true);
        }finally{c.getMatrices().pop();}
    }

    // ---------------------------------------------------------------- 팝업
    private int popup(DrawContext c,Room room,double mx,double my){
        return switch(popup){
            case FRIENDS->friendsPopup(c,room,mx,my);
            case EXPAND->expandPopup(c,room,mx,my);
            case CONFIRM_CLOSE->confirmPopup(c,mx,my);
            case NONE->0;
        };
    }
    private int friendsPopup(DrawContext c,Room room,double mx,double my){
        int hit=0;float px=566,py=110,pw=540,ph=720;
        nine(images,c,PANEL_ROOM_INFO,px,py,pw,ph,30,0xFFFFFFFF);
        icon(images,c,ICON_TITLE_STAR,px+50,py+52,44,0xFFFFFFFF);
        type.draw(c,"유저 검색",px+82,py+52,32,TITLE_GOLD,TITLE,false);
        boolean overClose=in(mx,my,px+pw-62,py+30,40,40);if(overClose)hit=H_POP_CLOSE;
        icon(images,c,ICON_CLOSE,px+pw-42,py+50,26,overClose?0xFFFFFFFF:0xFFD0D4DA);
        HudMesh.line(c,px+28,py+94,px+pw-28,py+94,1f,0x66E1B866);
        nine(images,c,INPUT_FIELD,px+26,py+106,pw-52,46,10,0xFFFFFFFF);
        userSearch.draw(c,px+26,py+106,pw-52,46,"등록 닉네임 검색");
        float top=py+166,bottom=py+ph-76,x=px+26,w=pw-52,step=92,h=82;
        var rows=new ArrayList<school.magiccodex.protocol.HousingSearchProtocol.User>();
        UUID me=self();boolean homeRow=false;
        var page=HousingSearchClient.page;var friends=page==null?null:page.users();
        if(friends!=null)rows.addAll(friends);
        int count=rows.size()+(homeRow?1:0);
        float max=Math.max(0,count*step-(step-h)-(bottom-top));friendScroll=Math.clamp(friendScroll,0,max);
        boolean live=my>=top&&my<bottom;
        c.enableScissor(Math.round(px+10),Math.round(top),Math.round(px+pw-10),Math.round(bottom));
        try{
            int index=0;
            if(homeRow){
                float ty=top-friendScroll;
                nine(images,c,GUESTBOOK_ENTRY,x,ty,w,h,12,0xFFFFFFFF);
                icon(images,c,ICON_TITLE_STAR,x+40,ty+h/2,40,0xFFFFFFFF);
                type.draw(c,"내 방",x+80,ty+30,21,0xFFFFFFFF,BOLD,false);
                type.draw(c,"꾸미던 내 방으로 돌아가요",x+80,ty+56,15,SUB,BODY,false);
                boolean over=live&&in(mx,my,x+w-108,ty+20,96,42);if(over)hit=H_GO_HOME;
                button(c,"돌아가기",null,x+w-108,ty+20,96,42,over,false);
                index++;
            }
            for(int i=0;i<rows.size();i++,index++){
                float ty=top+index*step-friendScroll;if(ty+h<top||ty>bottom)continue;
                var f=rows.get(i);var p=f.person();
                nine(images,c,GUESTBOOK_ENTRY,x,ty,w,h,12,0xFFFFFFFF);
                face(c,p,x+12,ty+12,58);
                type.draw(c,fit(p.name(),21,w-230,BOLD),x+82,ty+30,21,f.online()?0xFFFFFFFF:0xFFB4BCC4,BOLD,false);
                if(f.online())icon(images,c,ICON_ONLINE,x+88,ty+57,12,0xFFFFFFFF);else HudMesh.disk(c,x+88,ty+57,4.5f,0xFF7D8894);
                String info=(f.online()?"온라인":"오프라인")+(f.friend()?" · 친구":"")+(p.house()>=0?" · "+SchoolProtocol.HOUSES.get(p.house()):"")+(f.size()>0?" · 방 "+f.size()+"×"+f.size():"");
                type.draw(c,fit(info,15,w-220,BODY),x+100,ty+57,15,f.online()?GREEN:OFF,BODY,false);
                boolean can=f.size()>0&&(f.friend()||p.id().equals(me)||client.player.hasPermissionLevel(2));
                boolean over=can&&live&&in(mx,my,x+w-108,ty+20,96,42);if(over)hit=H_VISIT+i;
                button(c,can?"방문":f.size()==0?"방 없음":"친구 전용",null,x+w-108,ty+20,96,42,over,false);
            }
        }finally{c.disableScissor();}
        if(page==null)type.draw(c,"검색 중…",px+pw/2,top+100,19,MUTED,BODY,true);
        else if(!page.error().isEmpty())type.draw(c,fit(page.error(),17,pw-50,BODY),px+pw/2,top+100,17,MUTED,BODY,true);
        else if(rows.isEmpty())type.draw(c,"검색 결과가 없어요.",px+pw/2,top+60,17,MUTED,BODY,true);
        boolean prev=page!=null&&page.error().isEmpty()&&searchPage>0,next=page!=null&&page.error().isEmpty()&&page.more();
        boolean overPrev=prev&&in(mx,my,px+26,py+ph-60,110,38),overNext=next&&in(mx,my,px+pw-136,py+ph-60,110,38);
        if(overPrev)hit=H_SEARCH_PREV;if(overNext)hit=H_SEARCH_NEXT;
        button(c,"이전",null,px+26,py+ph-60,110,38,overPrev,false);button(c,"다음",null,px+pw-136,py+ph-60,110,38,overNext,false);
        type.draw(c,(searchPage+1)+"쪽",px+pw/2,py+ph-41,18,SUB,BODY,true);
        return hit;
    }
    private int expandPopup(DrawContext c,Room room,double mx,double my){
        int hit=0;float px=586,py=290,pw=500,ph=310;
        nine(images,c,PANEL_ROOM_INFO,px,py,pw,ph,30,0xFFFFFFFF);
        type.draw(c,"방 넓히기",px+pw/2,py+56,32,TITLE_GOLD,TITLE,true);
        if(room.nextSize()==0){
            type.draw(c,"가장 넓은 방이에요. ("+room.size()+"×"+room.size()+")",px+pw/2,py+136,21,TEXT,BODY,true);
            boolean over=in(mx,my,px+pw/2-85,py+210,170,52);if(over)hit=H_POP_CANCEL;
            button(c,"닫기",null,px+pw/2-85,py+210,170,52,over,false);
            return hit;
        }
        type.draw(c,"지금 "+room.size()+"×"+room.size()+"   →   "+room.nextSize()+"×"+room.nextSize(),px+pw/2,py+112,23,0xFFFFFFFF,BOLD,true);
        nine(images,c,INPUT_FIELD,px+100,py+140,pw-200,50,10,0xFFFFFFFF);
        type.draw(c,"비용  "+fmt(room.nextCost()),px+pw/2,py+165,21,GOLD_TEXT,BOLD,true);
        boolean ok=in(mx,my,px+70,py+220,170,52),no=in(mx,my,px+pw-240,py+220,170,52);
        if(ok)hit=H_POP_OK;if(no)hit=H_POP_CANCEL;
        button(c,"넓히기",ICON_EXPAND,px+70,py+220,170,52,ok,true);
        button(c,"취소",null,px+pw-240,py+220,170,52,no,false);
        return hit;
    }
    private int confirmPopup(DrawContext c,double mx,double my){
        int hit=0;float px=566,py=340,pw=540,ph=240;
        nine(images,c,PANEL_ROOM_INFO,px,py,pw,ph,30,0xFFFFFFFF);
        type.draw(c,"저장하지 않은 레이아웃이 있어요.",px+pw/2,py+70,22,0xFFFFFFFF,BOLD,true);
        type.draw(c,"닫으면 바꾼 배치가 사라져요. 닫을까요?",px+pw/2,py+108,18,SUB,BODY,true);
        boolean ok=in(mx,my,px+80,py+150,170,52),no=in(mx,my,px+pw-250,py+150,170,52);
        if(ok)hit=H_POP_OK;if(no)hit=H_POP_CANCEL;
        button(c,"닫기",null,px+80,py+150,170,52,ok,false);
        button(c,"계속 꾸미기",null,px+pw-250,py+150,170,52,no,true);
        return hit;
    }

    // ---------------------------------------------------------------- 작은 도구
    private void face(DrawContext c,Person p,float x,float y,float size){
        nine(images,c,ITEM_SLOT,x,y,size,size,Math.max(6,size*.16f),0xFFFFFFFF);
        int inset=Math.round(size*.12f),fx=Math.round(x)+inset,fy=Math.round(y)+inset,fs=Math.round(size)-2*inset;
        HudMesh.quad(c,fx,fy,fs,fs,0xFF0B1826,0xFF0B1826);
        var skin=HousingClient.face(p);
        c.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,8,8,fs,fs,8,8,64,64);
        c.drawTexture(RenderLayer::getGuiTextured,skin,fx,fy,40,8,fs,fs,8,8,64,64);
    }
    private String fit(String value,float size,float room,Identifier font){
        if(type.width(value,size,font)<=room)return value;
        var cps=value.codePoints().toArray();
        for(int n=cps.length-1;n>0;n--){String s=new String(cps,0,n)+"…";if(type.width(s,size,font)<=room)return s;}
        return "…";
    }
    /** 글자 단위 줄바꿈 (한글은 띄어쓰기 없이도 끊김), 넘치면 마지막 줄 끝에 …. */
    private List<String> wrap(String text,float size,float width,int maxLines){
        var lines=new ArrayList<String>();var cps=text.codePoints().toArray();int start=0;
        while(start<cps.length&&lines.size()<maxLines){
            int end=start+1;
            while(end<=cps.length&&type.width(new String(cps,start,end-start),size,BODY)<=width)end++;
            end=Math.max(start+1,end-1);
            if(end<cps.length){int space=-1;for(int i=end;i>start+4;i--)if(cps[i-1]==' '){space=i;break;}if(space>0)end=space;}
            String line=new String(cps,start,end-start).strip();
            if(lines.size()==maxLines-1&&end<cps.length)line=fit(new String(cps,start,cps.length-start),size,width,BODY);
            lines.add(line);start=end;
        }
        return lines;
    }
    private static String fmt(long v){return String.format(Locale.ROOT,"%,d",v);}
    private static String ago(long time){
        long s=Math.max(0,(System.currentTimeMillis()-time)/1000);
        if(s<60)return "방금";if(s<3600)return s/60+"분 전";if(s<86400)return s/3600+"시간 전";return s/86400+"일 전";
    }

    // ---------------------------------------------------------------- 입력
    @Override public boolean mouseClicked(double x,double y,int button){
        var f=fit();double mx=f.x(x),my=f.y(y);mouseDown=true;
        var room=room();if(room==null)return true;
        if(popup!=Popup.NONE){if(button==0)popupClick(room,mx,my);return true;}
        boolean edit=editing();
        if(button==1){
            if(held!=null){cancelHeld();return true;}
            if(edit&&hoverItem>=0){retrieve(hoverItem);return true;}
            if(inRoom(mx,my)){panning=true;lastX=mx;lastY=my;return true;}
            return true;
        }
        if(button==2&&inRoom(mx,my)){panning=true;lastX=mx;lastY=my;return true;}
        if(button!=0)return super.mouseClicked(x,y,button);
        int h=hot;
        if(h==H_CLOSE){requestClose();return true;}
        if(h==H_EXPAND){popup=Popup.EXPAND;cancelHeld();sound(1.1f,.2f);return true;}
        if(h==H_BGM){say("BGM 고르기는 다음 업데이트에서 열려요.",true);sound(1f,.15f);return true;}
        if(h==H_GUESTBOOK){guestbookOpen=!guestbookOpen;if(!guestbookOpen)post.focus(false);sound(1.1f,.2f);return true;}
        if(h==H_FRIENDS){openFriends();return true;}
        if(h==H_HOME){visit(self());return true;}
        if(h==H_ZOOM_IN||h==H_ZOOM_OUT){zoom=Math.clamp(zoom*(h==H_ZOOM_IN?1.2f:1/1.2f),.5f,3f);sound(1.2f,.12f);return true;}
        if(h==H_LIKE){if(room.liked()){say("이미 좋아요를 누른 방이에요.",false);return true;}if(send(new Like(room.owner().id())))sound(1.3f,.2f);return true;} // #78: 한 번만, 취소 없음
        if(h==H_SEND){postGuestbook();return true;}
        if(h>=H_MORE&&h<H_DELETE){var e=entry(h-H_MORE);if(e!=null){moreEntry=moreEntry==e.id()?-1:e.id();sound(1.1f,.15f);}return true;}
        if(h>=H_DELETE&&h<H_VISIT){var e=entry(h-H_DELETE);if(e!=null&&send(new Delete(room.owner().id(),e.id())))moreEntry=-1;return true;}
        if(edit){
            if(h==H_ROTATE){rotate();return true;}
            if(h==H_RETRIEVE){
                if(held!=null)discardHeld();else say("회수할 가구를 집거나, 방에서 가구를 우클릭하세요.",false);
                return true;
            }
            if(h==H_UNDO){undoOnce();return true;}
            if(h==H_SAVE){save();return true;}
            if(h>=H_SLOT&&h<H_SLOT+HousingRules.SLOTS){if(slot!=h-H_SLOT){cancelHeld();slot=h-H_SLOT;sound(1.1f,.18f);}return true;}
            if(h>=H_TAB&&h<H_TAB+TABS.length){tab=h-H_TAB;invScroll=0;sound(1.1f,.15f);return true;}
            if(h>=H_ITEM&&h<H_MORE){pick(h-H_ITEM);return true;}
            if(search.click(mx,my)){post.focus(false);return true;}
        }
        if((!edit||guestbookOpen)&&post.click(mx,my)){search.focus(false);return true;}
        post.focus(false);
        search.focus(false);
        if(inRoom(mx,my)){
            if(edit&&held!=null){
                // #108: 내 방 꾸미기 중에는 특수 가구를 두 번 눌러 연다 (첫 번은 평소처럼 집힘 → 제자리로 되돌리고 연다)
                if(held.origin()!=null&&functional(held.def().id())&&Util.getMeasuringTimeMs()-pickedAt<DOUBLE_CLICK_MS){String id=held.def().id();cancelHeld();useFurniture(room,id);return true;}
                place();return true;
            }
            if(edit&&hoverItem>=0){pickUp(hoverItem);pickedAt=Util.getMeasuringTimeMs();return true;}
            if(!edit&&held==null&&hoverItem>=0&&openFunctional(room,hoverItem))return true;
            panning=true;lastX=mx;lastY=my;return true;
        }
        return true;
    }
    /**
     * #108 특수 가구 (오너 승인 2026-10-26). 새 화면은 만들지 않고 기존 창·카드를 그대로 연다 (빗자루·아티팩트·트로피·수조 구경 화면은 GPT 디자인 납품 대기,
     * {@link HousingShowcaseLayout}). 구경 중엔 한 번 클릭, 내 방 꾸미기 중엔 두 번 클릭.
     * <ul>
     *   <li>모두: 빗자루 거치대(걸린 빗자루 이름) · 아티팩트 진열장(장착 아티팩트 읽기 전용 도감) · 큐디치 트로피장(결투장 전적) · 기숙사 점수판(기숙사 점수 창)
     *       · 내 정보 액자(주인 정보창 — 남이면 /스탯창, 주인이 같은 서버에 접속 중일 때만 서버가 열어 줌)</li>
     *   <li>주인만: 연금 작업대(/연금술) · 거울 옷장(칭호 창) · 펫 쿠션(펫 창) · 미끼 상자(받기 기능 없음 → "준비 중")</li>
     * </ul>
     */
    private static final Set<String> SHARED=Set.of("house_scoreboard","profile_frame","broom_rack","artifact_cabinet","trophy_case"),
            OWNER_ONLY=Set.of("alchemy_bench","mirror_wardrobe","pet_cushion","bait_box_common","bait_box_rare","bait_box_unique");
    private static final long DOUBLE_CLICK_MS=350;
    private long pickedAt;
    private static boolean functional(String id){return SHARED.contains(id)||OWNER_ONLY.contains(id);}
    private boolean mine(){var r=room();return r!=null&&r.owner().id().equals(self());}
    /** 가구 이름표 뒤 문구. */
    private String labelSuffix(String id){
        if(!functional(id))return "";
        var r=room();if(r==null)return "";
        UUID owner=r.owner().id();boolean mine=mine();
        String extra=switch(id){
            case "broom_rack"->{String b=HousingShowcase.broom(owner,mine);yield b.isEmpty()?" · 걸린 빗자루 없음":" · "+HousingShowcase.broomName(owner,mine,b);}
            case "artifact_cabinet"->" · 장착 "+HousingShowcase.artifacts(owner,mine).size()+"개";
            case "trophy_case"->{int t=HousingShowcase.tier(owner);yield t>=0?" · "+HousingShowcaseLayout.TIER_NAMES[t]:HousingShowcase.noRecord(owner)?" · 전적 없음":"";}
            default->"";
        };
        if(editing())return extra+" · 두 번 눌러 열기";
        if(OWNER_ONLY.contains(id)&&!mine)return extra;
        return extra+" · 클릭해서 보기";
    }
    private boolean openFunctional(Room room,int index){
        var list=layout().placements();if(index<0||index>=list.size())return false;
        String id=list.get(index).id();if(!functional(id))return false;
        useFurniture(room,id);return true;
    }
    private void useFurniture(Room room,String id){
        UUID owner=room.owner().id();boolean mine=owner.equals(self());String ownerName=room.owner().name();
        if(OWNER_ONLY.contains(id)&&!mine){say("주인만 쓸 수 있어요.",false);sound(.9f,.15f);return;}
        // 다른 창으로 넘어가면 이 창이 닫히므로, 저장 안 한 배치가 있으면 먼저 저장하게 한다 (빗자루·미끼 알림과 진열장 구경은 방으로 돌아오므로 괜찮음)
        boolean staysHere=id.equals("broom_rack")||id.equals("artifact_cabinet")||id.startsWith("bait_box_");
        if(!staysHere&&editing()&&anyDirty()){say("저장하지 않은 레이아웃이 있어요. 먼저 저장해 주세요.",false);sound(.9f,.15f);return;}
        sound(1.13f,.2f);
        var h=client.getNetworkHandler();
        switch(id){
            case "house_scoreboard"->{
                if(!SchoolClient.supported()){say("기숙사 점수판을 열 수 없어요. 서버 연결을 확인해 주세요.",false);return;}
                SchoolClient.open();
            }
            case "profile_frame"->{
                if(mine){MagicCodexClient.dismiss();client.setScreen(new StatsScreen());return;}
                if(h==null){say("서버에 연결되어 있지 않아요.",false);return;}
                h.sendChatCommand("스탯창 "+owner); // 주인이 접속 중이 아니면 서버가 안내 문구를 보낸다 (창은 그대로)
            }
            case "broom_rack"->{ // 교체 지점 1 (빗자루 거치대 상세 카드, 디자인 납품 대기): 지금은 방 알림으로 이름만
                String b=HousingShowcase.broom(owner,mine);
                say(b.isEmpty()?(mine?"장착한 빗자루가 없어요.":ownerName+"님은 빗자루를 걸어 두지 않았어요."):(mine?"내":ownerName+"님의")+" 빗자루 · "+HousingShowcase.broomName(owner,mine,b),true);
            }
            case "artifact_cabinet"->{ // 교체 지점 2 (아티팩트 진열장 구경 창): 지금은 아티팩트 도감 구경 모드(읽기 전용), 닫으면 방으로 돌아옴
                var screen=new ArtifactCodexScreen(this,mine?"내":ownerName,HousingShowcase.artifacts(owner,mine));
                HousingClient.suspend(this,screen);client.setScreen(screen);
            }
            case "trophy_case"->{ // 교체 지점 3 (트로피장 전적 카드): 지금은 결투장 로비 전적 탭 (주인 닉네임으로 검색)
                MagicCodexClient.dismiss();client.setScreen(KyudichiLobbyScreen.record(school.magiccodex.protocol.KyudichiQueueProtocol.MODE_KYUDICHI,mine?"":ownerName));
            }
            case "alchemy_bench"->{if(h==null){say("서버에 연결되어 있지 않아요.",false);return;}h.sendChatCommand("연금술");}
            case "mirror_wardrobe"->TitleClient.open();
            case "pet_cushion"->PetClient.open();
            default->say("미끼 상자는 준비 중이에요.",true); // bait_box_*: 미끼 생성·받기 수치 미정 (오너 결정 대기)
        }
    }

    /** #108 가구 위 전시: 빗자루 거치대 = 걸린 빗자루 모델, 아티팩트 진열장 = 장착 아티팩트 아이콘, 트로피장 = 큐디치 티어 아이콘. 그리기만 (요청은 주인당 한 번). */
    private void showcases(DrawContext c,Room room,List<Drawn> hits){
        UUID owner=room.owner().id();boolean mine=mine();
        for(var d:hits){
            String id=d.def().id();
            if(!id.equals("broom_rack")&&!id.equals("artifact_cabinet")&&!id.equals("trophy_case"))continue;
            float x0=Float.MAX_VALUE,x1=-Float.MAX_VALUE,y0=Float.MAX_VALUE,y1=-Float.MAX_VALUE;
            for(var p:d.hull()){x0=Math.min(x0,p[0]);x1=Math.max(x1,p[0]);y0=Math.min(y0,p[1]);y1=Math.max(y1,p[1]);}
            float cx=(x0+x1)/2,h=y1-y0,tile=iso.tile;
            switch(id){
                case "broom_rack"->{
                    String b=HousingShowcase.broom(owner,mine);
                    if(b.isEmpty()||popup!=Popup.NONE)continue; // 모델은 z 200 에 그려져 창 위로 올라오므로 팝업 중엔 숨김
                    BroomUiLink.render(c,b,cx+tile*HousingShowcaseLayout.BROOM_X,y0+h*HousingShowcaseLayout.BROOM_Y,tile*HousingShowcaseLayout.BROOM_LENGTH,
                            HousingShowcaseLayout.BROOM_YAW,HousingShowcaseLayout.BROOM_PITCH,HousingShowcaseLayout.BROOM_SPIN);
                }
                case "artifact_cabinet"->{
                    var list=HousingShowcase.artifacts(owner,mine);
                    int per=HousingShowcaseLayout.ARTIFACT_PER_ROW,max=Math.min(list.size(),per*HousingShowcaseLayout.ARTIFACT_ROWS);
                    int size=Math.max(4,Math.round(tile*HousingShowcaseLayout.ARTIFACT_SIZE));float step=tile*HousingShowcaseLayout.ARTIFACT_STEP;
                    for(int i=0;i<max;i++){
                        var e=HousingShowcase.entry(list.get(i));if(e==null||e.icon().isBlank())continue;
                        int row=i/per,inRow=Math.min(per,max-row*per),col=i%per;
                        float ix=cx+(col-(inRow-1)/2f)*step,iy=y0+h*HousingShowcaseLayout.ARTIFACT_Y+row*tile*HousingShowcaseLayout.ARTIFACT_ROW_STEP;
                        images.drawCentered(c,e.icon(),ix,iy,size,0xFFFFFFFF);
                    }
                }
                default->{ // trophy_case
                    HousingShowcase.askTier(owner,room.owner().name(),mine);
                    int t=HousingShowcase.tier(owner);if(t<0)continue;
                    images.drawCentered(c,HousingShowcaseLayout.tierTexture(t),cx,y0+h*HousingShowcaseLayout.TIER_Y,Math.max(4,Math.round(tile*HousingShowcaseLayout.TIER_SIZE)),0xFFFFFFFF);
                }
            }
        }
    }
    private Entry entry(int index){var b=HousingClient.guestbook();return b==null||index<0||index>=b.entries().size()?null:b.entries().get(index);}
    private void popupClick(Room room,double mx,double my){
        int h=hot;
        switch(popup){
            case FRIENDS->{
                if(userSearch.click(mx-dx/2,my))return;
                if(h==H_SEARCH_PREV||h==H_SEARCH_NEXT){var page=HousingSearchClient.page;if(page!=null&&page.error().isEmpty()&&(h==H_SEARCH_PREV&&searchPage>0||h==H_SEARCH_NEXT&&page.more())){searchPage+=h==H_SEARCH_PREV?-1:1;friendScroll=0;HousingSearchClient.send(userSearch.text(),searchPage);}return;}
                if(h==H_POP_CLOSE){popup=Popup.NONE;sound(1,.15f);}
                else if(h==H_GO_HOME){popup=Popup.NONE;visit(self());}
                else if(h>=H_VISIT&&h<H_GO_HOME){var result=HousingSearchClient.page;var list=result==null?null:result.users();int i=h-H_VISIT;if(list!=null&&i<list.size()){popup=Popup.NONE;visit(list.get(i).person().id());}}
                else if(!in(mx,my,566+dx/2,110,540,720)){popup=Popup.NONE;sound(1,.15f);}
            }
            case EXPAND->{
                if(h==H_POP_OK){send(new Expand(room.size()));}
                else if(h==H_POP_CANCEL||!in(mx,my,586+dx/2,290,500,310)){popup=Popup.NONE;sound(1,.15f);}
            }
            case CONFIRM_CLOSE->{
                if(h==H_POP_OK){Arrays.fill(dirty,false);popup=Popup.NONE;close();}
                else if(h==H_POP_CANCEL||!in(mx,my,566+dx/2,340,540,240)){popup=Popup.NONE;sound(1,.15f);}
            }
            case NONE->{}
        }
    }
    private void openFriends(){
        popup=Popup.FRIENDS;friendScroll=0;cancelHeld();sound(1.1f,.2f);
        search.focus(false);post.focus(false);userSearch.focus(true);queueUserSearch();queryAt=0;
    }
    private void visit(UUID target){
        if(target==null)return;
        if(editing()&&anyDirty()){say("저장하지 않은 레이아웃이 있어요. 먼저 저장해 주세요.",false);return;}
        cancelHeld();
        if(HousingClient.send(new Visit(target)))sound(1.13f,.2f);else say("하우징 서버에 연결되어 있지 않아요.",false);
    }
    private void postGuestbook(){
        var room=room();if(room==null)return;
        String text=HousingProtocol.cleanText(post.text());
        if(text.isEmpty()){say("한마디를 적어 주세요.",false);return;}
        send(new Post(room.owner().id(),text));
    }
    private void save(){
        if(!dirty[slot]&&room()!=null&&room().activeSlot()==slot){say("바뀐 것이 없어요.",true);return;}
        cancelHeld();
        if(send(new Save(slot,edit[slot]))){savingSlot=slot;sound(1.2f,.2f);}
    }
    private void requestClose(){
        if(editing()&&anyDirty()){popup=Popup.CONFIRM_CLOSE;cancelHeld();sound(1.05f,.2f);return;}
        close();
    }

    // ---- 배치
    private void pick(int index){
        if(index<0||index>=visibleItems.size())return;
        var d=visibleItems.get(index);var lay=edit[slot];
        if(d.surface()){
            boolean wallpaper=d.sub()==HousingRules.SURFACE_WALLPAPER;
            String current=wallpaper?lay.wallpaper():lay.floor(),next=current.equals(d.id())?"":d.id();
            change(wallpaper?lay.surfaces(next,lay.floor()):lay.surfaces(lay.wallpaper(),next));
            sound(1.2f,.2f);return;
        }
        if(owned(d.id())-lay.usage().getOrDefault(d.id(),0)<=0){say(d.name()+"은(는) 모두 놓았어요.",false);return;}
        if(held!=null)cancelHeld();
        held=new Held(d,0,null,lay);sound(1.15f,.18f);
    }
    private void pickUp(int index){
        var lay=edit[slot];if(index<0||index>=lay.placements().size())return;
        var p=lay.placements().get(index);var d=HousingClient.def(p.id());if(d==null)return;
        var rest=new ArrayList<>(lay.placements());rest.remove(index);
        held=new Held(d,d.wall()?0:p.rot(),p,lay);
        edit[slot]=lay.with(rest);
        sound(1.15f,.18f);
    }
    private void place(){
        if(held==null)return;
        if(candidate==null){say("방 안에 놓아 주세요.",false);return;}
        if(candidateWhy!=null){say(candidateWhy,false);sound(.8f,.15f);return;}
        var before=held.before();
        var list=new ArrayList<>(edit[slot].placements());list.add(candidate);
        if(list.size()>HousingRules.MAX_PLACEMENTS){say("한 방에는 "+HousingRules.MAX_PLACEMENTS+"개까지 놓을 수 있어요.",false);return;}
        var next=edit[slot].with(list);
        edit[slot]=before;change(next);
        var d=held.def();held=null;sound(1.25f,.22f);
        // Shift: 같은 가구를 이어서 놓기
        if(hasShiftDown()&&owned(d.id())-next.usage().getOrDefault(d.id(),0)>0)held=new Held(d,candidate.rot(),null,next);
    }
    private void cancelHeld(){
        if(held==null)return;
        if(held.origin()!=null)edit[slot]=held.before();
        held=null;sound(.95f,.15f);
    }
    private void discardHeld(){
        if(held==null)return;
        var before=held.before();var now=edit[slot];
        if(held.origin()!=null){edit[slot]=before;change(now);}
        held=null;sound(1f,.18f);
    }
    private void retrieve(int index){
        var lay=edit[slot];if(index<0||index>=lay.placements().size())return;
        var rest=new ArrayList<>(lay.placements());rest.remove(index);
        change(lay.with(rest));sound(1f,.18f);
    }
    private void rotate(){
        if(held==null){say("회전할 가구를 먼저 집어 주세요.",false);return;}
        if(held.def().wall()){say("벽걸이는 마우스를 옮겨 다른 벽에 걸어요.",true);return;}
        held=new Held(held.def(),(held.rot()+1)%4,held.origin(),held.before());sound(1.2f,.15f);
    }
    private void undoOnce(){
        cancelHeld();
        if(undo[slot].isEmpty()){say("되돌릴 것이 없어요.",false);return;}
        edit[slot]=undo[slot].pop();dirty[slot]=true;sound(1.05f,.18f);
    }

    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!panning)return false;
        var f=fit();double mx=f.x(x),my=f.y(y);
        panX+=(float)(mx-lastX);panY+=(float)(my-lastY);lastX=mx;lastY=my;
        float lim=900*zoom;panX=Math.clamp(panX,-lim,lim);panY=Math.clamp(panY,-lim,lim);
        return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){panning=false;mouseDown=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        var f=fit();double mx=f.x(x),my=f.y(y);
        if(popup==Popup.FRIENDS){friendScroll=(float)Math.max(0,friendScroll-vertical*60);return true;}
        if(popup!=Popup.NONE)return true;
        boolean edit=editing();
        if(edit&&my>=657&&my<907&&mx>=40&&mx<1632+dx){invScroll=(float)Math.clamp(invScroll-vertical*SLOT_STEP,0,maxInvScroll());return true;}
        var b=bookPanel();
        if((!edit||guestbookOpen)&&in(mx,my,b[0],b[1],b[2],b[3])){bookScroll=(float)Math.max(0,bookScroll-vertical*50);return true;}
        if(inRoom(mx,my)){zoom=(float)Math.clamp(zoom*Math.pow(1.12,vertical),.5,3);return true;}
        return false;
    }
    @Override public boolean charTyped(char ch,int mods){
        if(popup==Popup.FRIENDS)return userSearch.focus()&&userSearch.typed(ch,mods);
        if(search.focus()){invScroll=0;return search.typed(ch,mods);}
        if(post.focus())return post.typed(ch,mods);
        return super.charTyped(ch,mods);
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){
            if(popup!=Popup.NONE){popup=Popup.NONE;HousingSearchClient.invalidate();return true;}
            if(search.focus()||post.focus()){search.focus(false);post.focus(false);return true;}
            if(held!=null){cancelHeld();return true;}
            if(moreEntry>=0){moreEntry=-1;return true;}
            if(guestbookOpen&&editing()){guestbookOpen=false;return true;}
            requestClose();return true;
        }
        if(popup==Popup.FRIENDS){if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){queryDirty=false;searchPage=0;friendScroll=0;HousingSearchClient.send(userSearch.text(),0);return true;}return userSearch.key(key,scan,mods);}
        if(popup!=Popup.NONE){
            if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&popup!=Popup.FRIENDS){hot=H_POP_OK;popupClick(room(),-1,-1);}
            return true;
        }
        if(post.focus()){
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){postGuestbook();return true;}
            return post.key(key,scan,mods);
        }
        if(search.focus()){invScroll=0;return search.key(key,scan,mods);}
        if(editing()){
            boolean ctrl=Screen.hasControlDown();
            if(key==GLFW.GLFW_KEY_R&&!ctrl){rotate();return true;}
            if(key==GLFW.GLFW_KEY_Z&&ctrl){undoOnce();return true;}
            if(key==GLFW.GLFW_KEY_S&&ctrl){save();return true;}
            if(key==GLFW.GLFW_KEY_DELETE||key==GLFW.GLFW_KEY_BACKSPACE){
                if(held!=null)discardHeld();else if(hoverItem>=0)retrieve(hoverItem);
                return true;
            }
        }
        return super.keyPressed(key,scan,mods);
    }
    @Override public void close(){
        if(editing()&&anyDirty()&&popup!=Popup.CONFIRM_CLOSE){popup=Popup.CONFIRM_CLOSE;return;}
        sound(.95f,.18f);super.close();
    }
    /** 구경 화면(아티팩트 진열장)으로 잠깐 넘어갈 때는 방을 닫지 않는다 (HousingClient.suspend). */
    @Override public void removed(){if(!HousingClient.suspended(this))HousingClient.closed();super.removed();}
}
