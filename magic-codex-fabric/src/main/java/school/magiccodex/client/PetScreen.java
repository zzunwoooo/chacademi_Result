package school.magiccodex.client;
import java.io.ByteArrayInputStream;
import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.PetProtocol;
import school.magiccodex.protocol.PetProtocol.Entry;

public final class PetScreen extends Screen {
    private static final Identifier BODY=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    private final Map<String,Icon> icons=new HashMap<>();
    private record Icon(Entry source,ItemStack stack){}
    private final Map<String,LivingEntity> previewEntities=new HashMap<>();
    private String hover="",previous="";
    private TextFieldWidget search;
    private static final float DEFAULT_YAW=170; // Ten degrees off the original front view.
    private float yaw=DEFAULT_YAW,zoom=1;private boolean dragging;private double dragX;
    public PetScreen(){super(Text.literal("펫 도감"));}
    public record Fit(float x,float y,float scale){double mx(double x){return (x-this.x)/scale;}double my(double y){return (y-this.y)/scale;}}
    public static Fit fit(int width,int height){float s=Math.max(.1f,Math.min((width-16)/1280f,(height-16)/720f));return new Fit((width-1280*s)/2,(height-720*s)/2,s);}
    @Override protected void init(){
        super.init();var f=fit(width,height);
        search=new TextFieldWidget(textRenderer,Math.round(f.x()+400*f.scale()),Math.round(f.y()+166*f.scale()),Math.max(80,Math.round(480*f.scale())),Math.max(16,Math.round(22*f.scale())),Text.literal("펫 이름 또는 ID 검색"));
        search.setMaxLength(96);search.setPlaceholder(Text.literal("펫 이름 또는 ID 검색"));search.setText(PetClient.STATE.query());
        search.setChangedListener(PetClient.STATE::search);addDrawableChild(search);
    }
    private static boolean in(double x,double y,double rx,double ry,double w,double h){return x>=rx&&x<rx+w&&y>=ry&&y<ry+h;}
    private String hit(double x,double y){
        if(in(x,y,1171,28,66,60))return "close";
        if(in(x,y,400,100,480,58))return "filter"+Math.min(3,(int)((x-400)/120));
        if(in(x,y,866,208,50,50))return "favorite";
        if(in(x,y,207,340,86,80))return "prev";
        if(in(x,y,987,340,86,80))return "next";
        if(in(x,y,495,637,290,68))return "summon";
        if(in(x,y,1055,670,160,32))return "refresh";
        if(in(x,y,442,200,396,316))return "model";
        return "";
    }
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var f=fit(width,height);double mx=f.mx(mouseX),my=f.my(mouseY);
        String hot=hit(mx,my);if(!hot.equals(hover)&&!hot.isEmpty()&&!hot.equals("model"))sound(1.4f,.055f);hover=hot;
        var entry=PetClient.STATE.selected();
        if(entry!=null&&!entry.id().equals(previous)){previous=entry.id();yaw=DEFAULT_YAW;zoom=1;}
        var text=UiResources.text();var images=UiResources.images();text.beginFrame();images.beginFrame();
        c.fill(0,0,width,height,0x46040C18);
        c.getMatrices().push();
        try{
            c.getMatrices().translate(f.x,f.y,0);c.getMatrices().scale(f.scale,f.scale,1);
            double time=Util.getMeasuringTimeMs()/1000.0;
            PetBackdrop.draw(c,time,false);
            PetAssets.draw(c,"title_plate",413,19,454,74,0xFFFFFFFF);
            PetAssets.draw(c,"paw",551,39,32,29,0xFFFFFFFF);label(c,"펫 도감",662,55,30,0xFFF4E7BE,true);
            PetAssets.draw(c,"filter_track",400,100,480,58,0xFFFFFFFF);
            PetAssets.draw(c,"filter_active",400+PetClient.STATE.filter()*120,99,120,60,0xDEFFFFFF);
            String[] tabs={"전체","1성","2성","3성"};
            for(int i=0;i<4;i++)label(c,tabs[i],460+i*120,130,24,PetClient.STATE.filter()==i?0xFFFFF2CC:0xFFE0E4EE,PetClient.STATE.filter()==i);
            // 도감 체력 (오너 2026-10-26): 교화해 보유한 펫이 많을수록 누적되는 최대 체력 — 등급 탭 바로 아래 한 줄
            String summary=PetClient.STATE.summary();if(!summary.isEmpty())label(c,summary,640,174,17,0xFFE9D3A0,false);
            round(c,"close",1171,28,66,60,"close",false);
            round(c,"prev",207,340,86,80,"chevron_left",false);
            round(c,"next",987,340,86,80,"chevron_left",true);
            if(entry==null){
                label(c,!PetClient.STATE.query().isEmpty()?"검색 결과가 없습니다.":PetClient.STATE.filter()!=0&&!PetClient.busy()?"해당 등급의 펫이 없습니다.":PetClient.status(),640,359,24,0xFFD1DFE8,false);
                label(c,PetClient.busy()?"잠시만 기다려 주세요":"우측 아래에서 새로고침할 수 있습니다",640,396,17,0xFFAFBECF,false);
            }else{
                boolean drawn=false;
                c.enableScissor(400,190,858,516);
                try{
                    if(PetClient.previewing()&&entry.id().startsWith("preview_"))drawn=preview(c,entry.id(),mx,my);
                    if(!drawn)drawn=PetClient.models().draw(c,entry.id(),640,358,265*zoom,yaw,time);
                }finally{c.disableScissor();}
                if(!drawn){icon(c,entry);String modelStatus=PetClient.models().status(entry.id());if(!modelStatus.equals("missing")&&!modelStatus.isEmpty())label(c,modelStatus,640,489,15,0xFFC0CEDA,false);}
                PetBackdrop.draw(c,time,true);
                PetAssets.draw(c,PetClient.STATE.favorite(entry.id())?"favorite_on":"favorite_off",866,208,50,50,hover.equals("favorite")?0xFFFFFFFF:0xEBFFFFFF);
                int count=entry.stars();float start=640-(count*37+(count-1)*10)/2f;
                for(int i=0;i<count;i++)PetAssets.draw(c,"favorite_on",start+i*47,522,37,35,0xFFFFFFFF);
                PetAssets.draw(c,"name_rule",382,576,516,22,0xC8FFFFFF);
                float size=Math.min(32,32*184/Math.max(1,text.width(entry.name(),32,BOLD)));
                label(c,entry.name(),640,587,size,entry.id().endsWith("_shiny")?0xFFFFFF55:0xFFF3F3EA,true);
                PetAssets.draw(c,"title_plate",530,608,220,28,0xD5FFFFFF);
                label(c,(PetClient.STATE.index()+1)+" / "+PetClient.STATE.visible().size(),640,622,17,0xFFE5E9EE,false);
                PetAssets.draw(c,"summon_button",495,637,290,68,PetClient.busy()?0x86FFFFFF:entry.owned()||entry.active()?0xFFFFFFFF:0x80FFFFFF);
                PetAssets.draw(c,"paw",548,659,27,25,0xF2FFFFFF);
                label(c,PetClient.busy()?"처리 중…":entry.active()?"소환 해제":entry.owned()?"소환하기":"소환 불가",660,671,25,0xFFF4EED9,true);
                if(hover.equals("model"))label(c,"드래그 회전 · 휠 확대",640,193,15,0xFFC4D2DF,false);
                if(hover.equals("favorite"))tooltip(c,PetClient.STATE.favorite(entry.id())?"즐겨찾기 해제":"즐겨찾기 추가",891,183);
                // 펫 카드(모델·이름)에 마우스를 올리면 능력 설명 (오너 2026-10-26). 바닐라 펫처럼 info 가 비면 없음.
                if(!dragging&&(hover.equals("model")||in(mx,my,382,566,516,40)))info(c,entry,(float)mx,(float)my);
            }
            label(c,"새로고침",1133,685,17,hover.equals("refresh")?0xFF9BE4EF:0xFFB4C4D4,false);
            if(PetClient.previewing())label(c,"미리보기",143,62,16,0xFFBBD0DE,false);
            String notice=PetClient.notice();if(!notice.isEmpty())tooltip(c,notice,640,474);
        }finally{c.getMatrices().pop();images.endFrame();}
        if(search!=null)search.render(c,mouseX,mouseY,delta);
        entrance.draw(c,width,height);
    }
    private boolean preview(DrawContext c,String id,double mx,double my){
        if(client.world==null)return false;
        var entity=previewEntities.computeIfAbsent(id,key->switch(key){case "preview_fox"->new FoxEntity(EntityType.FOX,client.world);case "preview_cat"->new CatEntity(EntityType.CAT,client.world);default->new WolfEntity(EntityType.WOLF,client.world);});
        InventoryScreen.drawEntity(c,446,210,834,501,165,0,640+(float)(mx-640)*.03f,370+(float)(my-370)*.03f,entity);return true;
    }
    private void icon(DrawContext c,Entry entry){
        // Only materialize an item when the selected snapshot's serialized icon changes.
        Icon cached=icons.get(entry.id());
        if(cached==null||cached.source()!=entry){
            ItemStack stack=decodeIcon(entry);icons.put(entry.id(),new Icon(entry,stack));
        }
        ItemStack stack=icons.get(entry.id()).stack();
        if(stack.isEmpty()){PetAssets.draw(c,"paw",596,320,88,84,0xAACEDDEB);return;}
        c.getMatrices().push();c.getMatrices().translate(556,283,100);c.getMatrices().scale(10.5f,10.5f,1);c.drawItem(stack,0,0);c.getMatrices().pop();
    }
    private ItemStack decodeIcon(Entry entry){
            try{byte[] bytes=entry.icon();if(bytes.length==0)return ItemStack.EMPTY;var nbt=NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtSizeTracker.of(2L*1024*1024));return ItemStack.fromNbt(client.world.getRegistryManager(),nbt).orElse(ItemStack.EMPTY);}
            catch(Exception e){PetClient.log(e);return ItemStack.EMPTY;}
    }
    private void round(DrawContext c,String id,float x,float y,float w,float h,String glyph,boolean flip){
        boolean over=hover.equals(id);PetAssets.draw(c,"round_button",x,y,w,h,over?0xFFFFFFFF:0xE8FFFFFF);
        if(over)HudMesh.arc(c,x+w/2,y+h/2,h*.40f,1.3f,0,(float)(Math.PI*2),0xBF6CE8F3);
        c.getMatrices().push();if(flip){c.getMatrices().translate(x+w,y,0);c.getMatrices().scale(-1,1,1);x=0;y=0;}
        PetAssets.draw(c,glyph,x+w*.35f,y+h*.29f,w*.30f,h*.42f,0xFFFFFFFF);c.getMatrices().pop();
    }
    private void label(DrawContext c,String s,float x,float y,float size,int color,boolean bold){UiResources.text().draw(c,s,x,y,size,color,bold?BOLD:BODY,true);}
    private Entry infoSource;private List<String> infoLines=List.of();private float infoWidth;
    /** 여러 줄 능력 설명: 이 창의 툴팁과 같은 색(0xED0A1C2A)·글꼴·글자색으로, 모서리만 둥근 카드(FishingArt.card) — 마우스 오른쪽 아래. */
    private void info(DrawContext c,Entry entry,float mx,float my){
        if(entry.info().isBlank())return;
        if(infoSource!=entry){infoSource=entry;infoLines=wrap(entry.info(),17,620);infoWidth=0;for(var l:infoLines)infoWidth=Math.max(infoWidth,UiResources.text().width(l,17,BODY));}
        float w=infoWidth+30,h=infoLines.size()*24+12;
        float x=Math.min(mx+16,1280-8-w),y=Math.min(my+16,720-8-h);
        c.getMatrices().push();
        try{
            c.getMatrices().translate(0,0,300);
            FishingArt.card(c,x,y,w,h,0xED0A1C2A,0,1);
            for(int i=0;i<infoLines.size();i++)UiResources.text().draw(c,infoLines.get(i),x+15,y+18+i*24,17,0xFFE4EDF0,BODY,false);
        }finally{c.getMatrices().pop();}
    }
    /** \n 으로 나누고, 너무 긴 줄은 띄어쓰기에서 (없으면 글자 단위로) 접는다. */
    private static List<String> wrap(String text,float size,float max){
        var out=new ArrayList<String>();var t=UiResources.text();
        for(String line:text.split("\n",-1)){
            line=line.strip();if(line.isEmpty())continue;
            while(t.width(line,size,BODY)>max){
                int cut=line.length()-1;while(cut>1&&t.width(line.substring(0,cut),size,BODY)>max)cut--;
                int space=line.lastIndexOf(' ',cut);if(space>cut/2)cut=space;
                out.add(line.substring(0,cut).strip());line=line.substring(cut).strip();
                if(out.size()>24)break;
            }
            if(!line.isEmpty())out.add(line);
            if(out.size()>24)break;
        }
        return List.copyOf(out);
    }
    private void tooltip(DrawContext c,String s,float x,float y){float w=Math.min(790,UiResources.text().width(s,17,BODY)+30);HudMesh.capsule(c,x-w/2,y-18,w,36,0xED0A1C2A,0xED0A1C2A);label(c,s,x,y,17,0xFFE4EDF0,false);}
    private void sound(float pitch,float volume){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),pitch,volume));}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(search!=null&&search.mouseClicked(x,y,button)){setFocused(search);return true;}
        if(search!=null)search.setFocused(false);
        if(button!=0)return false;var f=fit(width,height);String h=hit(f.mx(x),f.my(y));var e=PetClient.STATE.selected();
        if(h.equals("close")){close();return true;}
        if(h.equals("model")){dragging=true;dragX=x;return true;}
        if(!h.isEmpty())sound(1.1f,.18f);
        if(h.startsWith("filter"))PetClient.STATE.filter(Integer.parseInt(h.substring(6)));
        else switch(h){
            case "favorite"->{if(e!=null)PetClient.favorite(e.id());}
            case "prev"->PetClient.STATE.step(-1);
            case "next"->PetClient.STATE.step(1);
            case "refresh"->PetClient.reload();
            case "summon"->{if(e!=null&&(e.owned()||e.active()))PetClient.request(e.active()?PetProtocol.DISMISS:PetProtocol.SUMMON,e.id(),true);}
        }
        return !h.isEmpty();
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(button==0&&dragging){yaw+=(x-dragX)/fit(width,height).scale()*.6f;dragX=x;return true;}return false;}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){var f=fit(width,height);if(hit(f.mx(x),f.my(y)).equals("model")){zoom=(float)Math.clamp(zoom+vertical*.08,.65,1.45);return true;}PetClient.STATE.step(vertical>0?-1:1);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(search!=null&&search.isFocused())return super.keyPressed(key,scan,mods);if(key==GLFW.GLFW_KEY_LEFT){PetClient.STATE.step(-1);sound(1.1f,.15f);return true;}if(key==GLFW.GLFW_KEY_RIGHT){PetClient.STATE.step(1);sound(1.1f,.15f);return true;}return super.keyPressed(key,scan,mods);}
    @Override public void tick(){if(client.player==null||client.world==null||!client.player.isAlive())close();}
    @Override public boolean shouldPause(){return false;}
    public void verifyRenderer(){UiResources.images().verifyGpuState();}
}
