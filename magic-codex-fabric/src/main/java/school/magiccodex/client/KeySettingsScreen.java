package school.magiccodex.client;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import net.minecraft.client.sound.PositionedSoundInstance;

import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.CodexData.Spell;
import school.magiccodex.client.CodexHitboxes.Rect;

/**
 * Editing is local and transactional: changes take effect only after Save.
 * Click a rendered key to pick its spell (max 6), right-click a key or slot to clear,
 * HUD order follows persistent registration order.
 */
public final class KeySettingsScreen extends Screen {
    private static final Identifier BACKGROUND=id("textures/gui/key_settings_base.png");
    private static final Identifier BODY=id("label"), TITLE=id("title");
    private static final int IVORY=0xFFF0E3C8, GOLD=0xFFD0B98C, MUTED=0xFFA4ADB5, TEAL=0xFF8AD7C9;
    private final Screen parent;
    private final Supplier<List<Spell>> catalog;
    private final boolean catalogBacked;
    private final Path path;
    private SpellKeySettings saved, draft;
    /** UI shortcut keys (UiKeys), edited and saved together with the spells. */
    private UiKeys.Binds uiSaved=UiKeys.current().copy(), uiDraft=uiSaved.copy();
    private CodexTypography type;
    /** selected = slot picked for click-to-swap reordering; pickerKey = key whose spell is being chosen. */
    private int selected=-1, pickerKey=-1, page;
    private int dragSlot=-1; private double pressX,pressY; private boolean dragMoved, swallowChar;
    private String query="", notice="";
    private boolean confirmDiscard;
    private double mx,my;
    private long lastSound;
    private long savedPopupAt = -1;
    private HudTextureCache images;
    private int resourceGeneration=-1;
    private final UiResources.Entrance entrance=new UiResources.Entrance();

    public KeySettingsScreen(Screen parent) {
        this(parent,CodexCatalog::spells,true,CastingClient.activePath());
    }
    /** Development fixture uses the same event/render paths and a separate temporary settings file. */
    public KeySettingsScreen(Screen parent,Supplier<List<Spell>> catalog,boolean catalogBacked,Path path) {
        super(Text.literal("마법 키 설정")); this.parent=parent; this.catalog=catalog; this.catalogBacked=catalogBacked; this.path=path;
        try { saved=SpellKeySettings.load(path); }
        catch(Exception error) { saved=new SpellKeySettings(); notice="설정 읽기 실패 · 기존 파일은 저장 전까지 유지됩니다.";
            org.slf4j.LoggerFactory.getLogger("magiccodex").warn("키 설정 읽기 실패",error); }
        draft=saved.copy();
    }
    public boolean isCatalogBacked() { return catalogBacked; }
    public List<SpellKeySettings.Binding> bindings() { return draft.slots(); }
    public boolean pickerOpen() { return pickerKey>=0; }
    public int pickerKey() { return pickerKey; }
    private boolean dirty() { return !draft.sameAs(saved) || !uiDraft.sameAs(uiSaved); }
    private boolean waiting() { return catalogBacked && !PermissionClient.catalogConfirmed(); }
    private static Identifier id(String value) { return Identifier.of("magiccodex",value); }
    @Override protected void init() { type=UiResources.text();images=UiResources.images();resourceGeneration=UiResources.generation(); }
    private Spell spell(String id) { return catalog.get().stream().filter(s->s.id().equals(id)).findFirst().orElse(null); }
    private List<Spell> choices() {
        String needle=query.toLowerCase(Locale.ROOT);
        return catalog.get().stream().filter(s->s.permissionKnown() && s.discovered())
                .filter(s->s.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta) {
        if(type==null||images==null||resourceGeneration!=UiResources.generation())init();
        swallowChar=false; // Key and char events of one press are delivered before the next frame.
        type.beginFrame(); images.beginFrame(); var layout=layoutFor(width,height); mx=layout.localX(mouseX); my=layout.localY(mouseY);
        ctx.fill(0,0,width,height,0x55050B13);
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(layout.x(),layout.y(),0); ctx.getMatrices().scale(layout.scale(),layout.scale(),1);
            drawBackground(ctx);
            text(ctx,"마법 키 설정",836,102,38,IVORY,TITLE);
            text(ctx,"화면의 키를 클릭해 마법(최대 6개)이나 UI 단축키를 등록하세요",836,140,19,GOLD,BODY);
            for(var key:KeySettingsLayout.KEYS) drawKey(ctx,key);
            drawMouse(ctx);
            text(ctx,CastingClient.preset()==NormalPresetMode.OFF?"OFF · 프리셋 "+CastingClient.editingPreset()+"의 설정을 편집합니다.":"현재 사용중인 프리셋에 적용됩니다.",836,166,17,GOLD,BODY);
            if(catalogBacked){label(ctx,"프리셋 "+CastingClient.presetLabel()+" · 변경",1270,139,19,TEAL);}

            text(ctx,waiting()?"서버에서 배운 마법을 확인하고 있습니다…":!notice.isEmpty()?notice:
                    "우클릭으로 등록 해제 · 처음 등록한 마법부터 왼쪽에 표시됩니다",836,567,17,GOLD,BODY);
            int cursor=uiDraft.get(UiKeys.Action.CURSOR);
            label(ctx,"마법 순서: "+(cursor<0?"채팅창을 연 채":"["+KeySettingsLayout.name(cursor)+"] 키를 누른 채")+" 화면 우측 하단 마법 칸을 끌어서 바꿀 수 있습니다",171,639,16,MUTED);
            text(ctx,"등록 " + draft.slots().stream().filter(b->!b.empty()).count()+" / 6",1116,639,18,GOLD,BODY);
            button(ctx,KeySettingsLayout.SAVE,dirty()?"저장 *":"저장",true);
            button(ctx,KeySettingsLayout.RESET,"초기화",false);
            if(hover(KeySettingsLayout.CLOSE) && !pickerOpen() && !confirmDiscard) overlay(ctx,KeySettingsLayout.CLOSE,0x334CB2A0,0x99C9AD7A,5);
            // Typography renders at z+1. Lift the entire modal above the base text/icons.
            if(pickerOpen() || confirmDiscard) {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(0,0,20);
                if(pickerOpen()) { ctx.getMatrices().push(); ctx.getMatrices().translate(0,PICKER_DY,0); double saved=my; my-=PICKER_DY; try { drawPicker(ctx); } finally { my=saved; ctx.getMatrices().pop(); } }
                if(confirmDiscard) drawDiscard(ctx);
                ctx.getMatrices().pop();
            }
            if(!pickerOpen() && !confirmDiscard) drawSavedPopup(ctx);
        } finally { ctx.getMatrices().pop(); images.endFrame(); }
        entrance.draw(ctx,width,height);
    }
    /** Reuse the approved PNG frame at a smaller size without leaving its old large slots behind. */
    private void sprite(DrawContext ctx,String name,int x,int y,int w,int h,int sw,int sh){images.drawTexture(ctx,id("textures/gui/followup/"+name+".png"),x,y,0,0,w,h,sw,sh,sw,sh,0xFFFFFFFF);}
    private void drawBackground(DrawContext ctx) {
        // Original pre-mouse design (key_settings_base.png); the keyboard is drawn a little smaller so the mouse fits on its right.
        slice(ctx,new Rect(0,0,1672,158),new Rect(0,0,1672,158));
        // Compact: no slot row; the bottom strip (divider + buttons) follows the keyboard directly.
        int bottom=HEIGHT-133;
        slice(ctx,new Rect(0,158,140,bottom-158),new Rect(0,158,140,bottom-158));
        slice(ctx,new Rect(1535,158,137,bottom-158),new Rect(1535,158,137,bottom-158));
        slice(ctx,new Rect(140,260,50,150),new Rect(140,158,1395,bottom-158));
        var k=KeySettingsLayout.KEYBOARD_SRC;
        slice(ctx,k,new Rect(Math.round(KeySettingsLayout.KEYBOARD_X),Math.round(KeySettingsLayout.KEYBOARD_Y),Math.round(k.width()*KeySettingsLayout.KEYBOARD_SCALE),Math.round(k.height()*KeySettingsLayout.KEYBOARD_SCALE)));
        slice(ctx,new Rect(0,808,1672,133),new Rect(0,bottom,1672,133));
    }
    private void slice(DrawContext ctx,Rect source,Rect target) {
        images.drawTexture(ctx,BACKGROUND,target.x(),target.y(),source.x(),source.y(),
                target.width(),target.height(),source.width(),source.height(),1672,941,0xE8FFFFFF);
    }
    private void drawSavedPopup(DrawContext ctx) {
        if(savedPopupAt<0) return;
        long elapsed=Util.getMeasuringTimeMs()-savedPopupAt;
        if(elapsed>=2400) { savedPopupAt=-1; return; }
        float alpha=Math.min(1f,Math.min(elapsed/180f,(2400-elapsed)/400f));
        if(alpha<=0) return;
        // Independent floating toast: fades in, holds, then fades away; never takes input focus.
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0,Math.round((1-alpha)*-6),40);
        Rect box=new Rect(629,555,414,56);
        overlay(ctx,new Rect(box.x()+3,box.y()+4,box.width(),box.height()),fade(0x55000000,alpha),0,9);
        overlay(ctx,box,fade(0xF214302F,alpha),fade(0xFF8FC9B6,alpha),9);
        text(ctx,"✓",661,583,24,fade(TEAL,alpha),BODY);
        text(ctx,"키 설정을 저장했습니다",851,583,22,fade(IVORY,alpha),BODY);
        ctx.getMatrices().pop();
    }
    private static int fade(int color,float alpha) { return (Math.round((color>>>24)*alpha)<<24)|(color&0xFFFFFF); }
    private void drawKey(DrawContext ctx,KeySettingsLayout.Key key) {
        Rect r=key.box(); boolean allowed=KeySettingsLayout.allowed(key.code());
        boolean blocked=KeySettingsLayout.reservedShortcut(key.code());
        UiKeys.Action ui=uiDraft.actionOf(key.code());
        int slot=-1;
        for(int i=0;i<6;i++) if(!draft.get(i).empty() && draft.get(i).keyCode()==key.code()) slot=i;
        boolean hot=hover(r) && !pickerOpen() && !confirmDiscard;
        if(!allowed) overlay(ctx,r,blocked?0xA06A303B:0x294C5661,blocked?0xFFE38A90:0x446B7781,6);
        if(slot>=0) {
            overlay(ctx,r,slot==selected?0x44499084:0x22277569,slot==selected?0xFF8AD7C9:0x9974A59B,6);
            Spell spell=spell(draft.get(slot).spellId());
            if(spell!=null) art(ctx,spell,r.centerX(),r.centerY()-4,44);
            text(ctx,key.label(),r.x()+r.width()-15,r.y()+r.height()-13,16,IVORY,BODY);
        } else if(ui!=null) {
            // UI shortcut on this key: its short name in gold, the key name small underneath
            overlay(ctx,r,0x2A6A5530,0xAAD0B98C,6);
            float size=Math.min(15f,15f*(r.width()-8)/Math.max(1f,type.width(ui.shortLabel,15,BODY)));
            text(ctx,ui.shortLabel,r.centerX(),r.centerY()-7,size,GOLD,BODY);
            text(ctx,key.label(),r.centerX(),r.y()+r.height()-13,12,IVORY,BODY);
        } else {
            text(ctx,key.label(),r.centerX(),r.centerY()-(blocked?7:0),key.label().length()>5?17:21,blocked?0xFFFFC3C3:allowed?0xFFD5DADF:0xFF858E99,BODY);
            if(blocked) text(ctx,"지정 불가",r.centerX(),r.y()+r.height()-13,12,0xFFF0A4A8,BODY);
        }
        if(hot) overlay(ctx,r,blocked?0x337F3540:allowed?0x224FC5AF:0x22717B89,blocked?0xFFFFA4A8:allowed?0xFFA2DACE:0x998E949A,6);
    }
    /** Panel height after removing the slot row (key_settings_base.png is 1672x941). */
    public static final int HEIGHT=723;
    /** The spell picker (designed for the 941-tall panel) is lifted to stay inside the compact one. */
    private static final int PICKER_DY=-118;
    /** Screen fit for the compact 1672x723 panel. */
    public static CodexLayout layoutFor(int width,int height){float s=Math.min(width/1672f,height/(float)HEIGHT);return new CodexLayout((width-1672*s)/2,(height-HEIGHT*s)/2,s);}
    private void drawMouse(DrawContext ctx){
        images.drawTexture(ctx,id("textures/gui/followup/mouse.png"),(int)MouseBindingLayout.X,(int)MouseBindingLayout.Y,0,0,(int)MouseBindingLayout.W,(int)MouseBindingLayout.H,280,497,280,497,0xFFFFFFFF);
        for(int code:new int[]{KeySettingsLayout.THUMB_1,KeySettingsLayout.THUMB_2}){
            boolean hot=!pickerOpen()&&!confirmDiscard&&MouseBindingLayout.hit(mx,my)==code;boolean assigned=draft.slotOfKey(code)>=0;int color=hot?TEAL:assigned?GOLD:0x7794A7B6;
            var points=MouseBindingLayout.polygon(code);for(int i=0;i<points.length;i++){var a=points[i];var b=points[(i+1)%points.length];HudMesh.line(ctx,MouseBindingLayout.X+(float)a[0]*MouseBindingLayout.S,MouseBindingLayout.Y+(float)a[1]*MouseBindingLayout.S,MouseBindingLayout.X+(float)b[0]*MouseBindingLayout.S,MouseBindingLayout.Y+(float)b[1]*MouseBindingLayout.S,hot?2:1,color);}
            // Label right next to its thumb button (left of the mouse), vertically centred on it.
            float minX=Float.MAX_VALUE,sumY=0;for(var p:points){minX=Math.min(minX,MouseBindingLayout.X+(float)p[0]*MouseBindingLayout.S);sumY+=MouseBindingLayout.Y+(float)p[1]*MouseBindingLayout.S;}
            String name=KeySettingsLayout.name(code);label(ctx,name,minX-8-type.width(name,17,BODY),sumY/points.length,17,hot?TEAL:assigned?GOLD:IVORY);
        }
    }
    private void drawPicker(DrawContext ctx) {
        ctx.fill(124,48-PICKER_DY,1549,HEIGHT-24-PICKER_DY,0xC507111B);
        // left: UI shortcuts (this key opens …)
        Rect left=KeySettingsLayout.UI_PANEL;
        overlay(ctx,left,0xF20D1C2C,GOLD,12);
        text(ctx,"UI 단축키",left.centerX(),201,24,IVORY,TITLE);
        boolean mouseKey=KeySettingsLayout.mouse(pickerKey);
        text(ctx,mouseKey?"마우스 버튼에는 마법만":"이 키로 열기",left.centerX(),236,16,MUTED,BODY);
        UiKeys.Action[] actions=UiKeys.Action.values();
        for(int i=0;i<actions.length;i++){
            Rect r=KeySettingsLayout.uiChoice(i); var a=actions[i];
            int at=uiDraft.get(a); boolean current=at==pickerKey;
            overlay(ctx,r,!mouseKey&&hover(r)?0x5545706C:0x66213043,current?GOLD:!mouseKey&&hover(r)?TEAL:0x996B7B80,6);
            label(ctx,a.label,r.x()+12,r.centerY(),17,mouseKey?MUTED:IVORY);
            String k=current?"현재 이 키":at<0?"미지정":KeySettingsLayout.name(at);
            label(ctx,k,r.x()+r.width()-12-type.width(k,15,BODY),r.centerY(),15,current?GOLD:MUTED);
        }
        // right: spells
        Rect right=KeySettingsLayout.SPELL_PANEL;
        overlay(ctx,right,0xF20D1C2C,GOLD,12);
        text(ctx,"["+KeySettingsLayout.name(pickerKey)+"] 키에 등록할 마법",right.centerX(),201,26,IVORY,TITLE);
        text(ctx,waiting()?"권한 확인 중…":"검색: " + (query.isEmpty()?"이름을 입력하세요":query),right.centerX(),236,18,MUTED,BODY);
        button(ctx,KeySettingsLayout.PICKER_CLOSE,"×",false);
        var all=choices(); page=Math.min(page,Math.max(0,(all.size()-1)/9));
        for(int i=0;i<9;i++) {
            int index=page*9+i; if(index>=all.size()) break;
            Spell s=all.get(index); Rect r=KeySettingsLayout.choice(i);
            String state=choiceState(s);
            boolean blocked=state.equals(FULL), current=state.equals(CURRENT);
            overlay(ctx,r,hover(r)&&!blocked?0x5545706C:0x66213043,blocked?0xFF505D68:current?0xFFD0B98C:hover(r)?TEAL:0x996B7B80,7);
            art(ctx,s,r.x()+44,r.centerY(),64);
            label(ctx,shorten(s.name(),142,19),r.x()+86,r.centerY()-12,19,blocked?MUTED:IVORY);
            label(ctx,shorten(state.isEmpty()?s.category().label:state,142,14),r.x()+86,r.centerY()+18,14,current?GOLD:MUTED);
        }
        for(int i=0;i<9;i++)if(hover(KeySettingsLayout.choice(i))&&page*9+i<all.size()){
            String description=all.get(page*9+i).description();
            if(description!=null&&!description.isBlank()){
                overlay(ctx,new Rect(598,695,756,36),0xF0112230,0xAA998A6E,6);
                label(ctx,shorten(description.replace('\n',' '),726,17),613,713,17,IVORY);
            }
            break;
        }
        if(all.isEmpty()) text(ctx,waiting()?"잠시만 기다려 주세요":"등록할 수 있는 배운 마법이 없습니다",right.centerX(),469,23,MUTED,BODY);
        button(ctx,KeySettingsLayout.PREVIOUS,"이전",false); button(ctx,KeySettingsLayout.NEXT,"다음",false);
        text(ctx,(page+1)+" / "+Math.max(1,(all.size()+8)/9)+"    ·    ESC 취소",right.centerX(),762,18,GOLD,BODY);
    }
    private static final String CURRENT="현재 이 키", FULL="6개 모두 등록됨";
    /** Picker subtitle: empty = free to register, otherwise why/what happens on click. */
    private String choiceState(Spell s) {
        int at=draft.slotOfSpell(s.id());
        if(at>=0 && draft.get(at).keyCode()==pickerKey) return CURRENT;
        if(at>=0) return "등록됨 · "+KeySettingsLayout.name(draft.get(at).keyCode())+" → 이 키로 이동";
        if(draft.slotOfKey(pickerKey)<0 && draft.count()>=SpellKeySettings.LIMIT) return FULL;
        return "";
    }
    private void drawDiscard(DrawContext ctx) {
        ctx.fill(124,48,1549,HEIGHT-24,0xD007111B);
        overlay(ctx,new Rect(550,350,572,254),0xFF101F2D,GOLD,10);
        text(ctx,"저장하지 않은 변경이 있습니다",836,416,26,IVORY,TITLE);
        text(ctx,"변경을 취소하고 나갈까요?",836,460,20,MUTED,BODY);
        button(ctx,KeySettingsLayout.CONTINUE,"계속 설정",false); button(ctx,KeySettingsLayout.DISCARD,"변경 취소",false);
    }
    private void button(DrawContext ctx,Rect r,String label,boolean primary) {
        boolean hot=hover(r);
        overlay(ctx,r,hot?0x554C9E91:primary?0x162C8F86:0x10283B4D,hot?TEAL:0x778F9EA6,6);
        text(ctx,label,r.centerX(),r.centerY(),20,IVORY,BODY);
    }
    /** Rounded/chamfered translucent overlay stays within the artwork instead of a square hover box. */
    private static void overlay(DrawContext ctx,Rect r,int fill,int edge,int corner) {
        for(int row=0;row<r.height();row++) {
            int inset=Math.max(0,corner-Math.min(row,r.height()-1-row));
            int left=r.x()+inset,right=r.x()+r.width()-inset;
            ctx.fill(left,r.y()+row,right,r.y()+row+1,fill);
            if(row==0 || row==r.height()-1) ctx.fill(left,r.y()+row,right,r.y()+row+1,edge);
            else { ctx.fill(left,r.y()+row,left+1,r.y()+row+1,edge); ctx.fill(right-1,r.y()+row,right,r.y()+row+1,edge); }
        }
    }
    private boolean hover(Rect r) { return r.contains(mx,my); }
    private void text(DrawContext ctx,String value,float x,float y,float size,int color,Identifier font) { type.draw(ctx,value,x,y,size,color,font,true); }
    private void label(DrawContext ctx,String value,float x,float y,float size,int color) { type.draw(ctx,value,x,y,size,color,BODY,false); }
    private String shorten(String s,float w,float size) {
        if(type.width(s,size,BODY)<=w) return s;
        while(!s.isEmpty() && type.width(s+"…",size,BODY)>w) s=s.substring(0,s.offsetByCodePoints(s.length(),-1));
        return s+"…";
    }
    private void art(DrawContext ctx,Spell spell,float x,float y,int size) {
        if(!images.drawCentered(ctx,spell.icon(),x,y,size,0xFFFFFFFF)) text(ctx,"✦",x,y,size/2f,GOLD,TITLE);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        var layout=layoutFor(width,height); mx=layout.localX(x); my=layout.localY(y);
        if(catalogBacked&&!pickerOpen()&&!confirmDiscard&&button==0&&mx>=1250&&mx<=1520&&my>=115&&my<=158){if(dirty()){notice="변경 내용을 저장하거나 취소한 뒤 프리셋을 변경해 주세요.";sound(true);}else CastingClient.switchPreset(()->{if(client.currentScreen==this)client.setScreen(new KeySettingsScreen(parent));});return true;}
        if(pickerOpen()&&!confirmDiscard) my-=PICKER_DY;
        // Physical side buttons never open the editor. Click the drawn controls.
        if(button!=0 && button!=1) return false;
        if(confirmDiscard) {
            if(button==0 && hover(KeySettingsLayout.CONTINUE)) { confirmDiscard=false; sound(false); }
            else if(button==0 && hover(KeySettingsLayout.DISCARD)) { sound(false); leave(); }
            return true;
        }
        if(pickerOpen()) {
            if(button!=0) return true;
            if(hover(KeySettingsLayout.PICKER_CLOSE)) { pickerKey=-1; sound(false); return true; }
            if(hover(KeySettingsLayout.PREVIOUS)) { page=Math.max(0,page-1); sound(false); return true; }
            if(hover(KeySettingsLayout.NEXT)) { page=Math.min(Math.max(0,(choices().size()-1)/9),page+1); sound(false); return true; }
            UiKeys.Action[] actions=UiKeys.Action.values();
            for(int i=0;i<actions.length;i++) if(hover(KeySettingsLayout.uiChoice(i))) { assignUi(actions[i]); return true; }
            if(waiting()) return true;
            for(int i=0;i<9;i++) if(hover(KeySettingsLayout.choice(i))) {
                int index=page*9+i; var choices=choices(); if(index>=choices.size()) return true;
                choose(choices.get(index)); return true;
            }
            return true;
        }
        if(button==0 && hover(KeySettingsLayout.CLOSE)) { close(); return true; }
        if(button==0 && hover(KeySettingsLayout.SAVE)) { save(); return true; }
        if(button==0 && hover(KeySettingsLayout.RESET)) { draft.clear(); uiDraft.reset(); selected=-1; notice=""; sound(false); return true; }
        int mouseKey=MouseBindingLayout.hit(mx,my);
        if(mouseKey>=0){if(button==1){draft.clearKey(mouseKey);sound(false);}else openPicker(mouseKey);return true;}
        for(var key:KeySettingsLayout.KEYS) if(hover(key.box())) {
            if(button==1) {
                if(draft.clearKey(key.code())>=0) { notice=""; sound(false); }
                UiKeys.Action ui=uiDraft.actionOf(key.code());
                if(ui!=null) { uiDraft.set(ui,-1); notice=ui.label+" 단축키를 해제했습니다."; sound(false); }
                if(selected>=0 && draft.get(selected).empty()) selected=-1;
            } else openPicker(key.code());
            return true;
        }
        return true;
    }
    /** Opens the spell picker for a keyboard key; reserved keys explain why they are refused. */
    private boolean openPicker(int code) {
        if(!KeySettingsLayout.mouse(code)&&!KeySettingsLayout.KEYS.stream().anyMatch(k->k.code()==code)) return false;
        if(!KeySettingsLayout.allowed(code)) {
            notice=KeySettingsLayout.reservedShortcut(code)?KeySettingsLayout.RESERVED_MESSAGE:"이 키는 지정할 수 없습니다.";
            sound(true); return false;
        }
        selected=-1; pickerKey=code; page=0; query=""; notice=""; sound(false);
        return true;
    }
    private void choose(Spell s) {
        if(!s.permissionKnown() || !s.discovered()) { notice="배운 마법만 키를 지정할 수 있습니다."; sound(true); return; }
        if(choiceState(s).equals(CURRENT)) { pickerKey=-1; sound(false); return; }
        UiKeys.Action ui=uiDraft.actionOf(pickerKey);
        int movedFrom=draft.slotOfSpell(s.id())>=0?draft.get(draft.slotOfSpell(s.id())).keyCode():-1;
        try {
            draft.assign(pickerKey,s.id()); notice="";
            // the UI shortcut that had this key takes the spell's old key, or none
            if(ui!=null) {
                int give=movedFrom>=0&&!KeySettingsLayout.mouse(movedFrom)&&uiDraft.actionOf(movedFrom)==null?movedFrom:-1;
                uiDraft.set(ui,give);
                notice=ui.label+" 단축키는 "+(give<0?"미지정이 되었습니다 (키를 눌러 왼쪽 메뉴에서 다시 지정)":KeySettingsLayout.name(give)+" 키로 옮겼습니다.");
            }
            pickerKey=-1; sound(false);
        }
        catch(IllegalArgumentException error) { notice=error.getMessage(); pickerKey=-1; sound(true); }
    }
    /** Left menu of the picker: this key opens a UI. The action's old key goes to whatever had this key (swap). */
    private void assignUi(UiKeys.Action a) {
        int key=pickerKey;
        if(KeySettingsLayout.mouse(key)) { notice="마우스 버튼에는 마법만 등록할 수 있습니다."; sound(true); return; }
        int old=uiDraft.get(a);
        if(old==key) { pickerKey=-1; sound(false); return; }
        UiKeys.Action other=uiDraft.actionOf(key);
        uiDraft.set(a,key); notice="";
        if(other!=null && other!=a) {
            uiDraft.set(other,old);
            notice=other.label+" 단축키는 "+(old<0?"미지정이 되었습니다.":KeySettingsLayout.name(old)+" 키로 옮겼습니다.");
        }
        int slot=draft.slotOfKey(key);
        if(slot>=0) {
            String spell=draft.get(slot).spellId();
            if(old>=0 && draft.slotOfKey(old)<0 && KeySettingsLayout.allowed(old)) { draft.assign(old,spell); notice="마법을 "+KeySettingsLayout.name(old)+" 키로 옮겼습니다."; }
            else { draft.clearKey(key); notice="이 키의 마법 등록을 해제했습니다."; }
        }
        pickerKey=-1; sound(false);
    }
    private void save() {
        try { draft.save(path); saved=draft.copy(); if(catalogBacked) { CastingClient.saved(saved); UiKeys.commit(uiDraft); } uiSaved=uiDraft.copy();
            notice=""; savedPopupAt=Util.getMeasuringTimeMs(); sound(false); }
        catch(Exception error) { notice=error.getMessage(); sound(true); org.slf4j.LoggerFactory.getLogger("magiccodex").warn("키 설정 저장 실패",error); }
    }
    private void sound(boolean error) {
        long now=Util.getMeasuringTimeMs(); if(now-lastSound<70) return; lastSound=now;
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),error?0.7f:1.15f,error?0.18f:0.25f));
    }
    @Override public boolean keyPressed(int code,int scan,int modifiers) {
        if(code==GLFW.GLFW_KEY_ESCAPE) {
            if(confirmDiscard) confirmDiscard=false;
            else if(pickerOpen()) pickerKey=-1;
            else if(selected>=0) selected=-1;
            else close();
            return true;
        }
        if(confirmDiscard || dragSlot>=0) return true;
        if(pickerOpen()) { if(code==GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) { query=query.substring(0,query.offsetByCodePoints(query.length(),-1)); page=0; } return true; }
        // Keep Escape and text search handling above, without selecting a binding here.
        // Physical keys never open the editor; rendered-key mouse clicks select it.
        return true;
    }
    @Override public boolean charTyped(char chr,int modifiers) {
        if(swallowChar) { swallowChar=false; return true; }
        if(pickerOpen() && !confirmDiscard && !Character.isISOControl(chr) && query.length()<32) { query+=chr; page=0; }
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(pickerOpen() && !confirmDiscard && vertical!=0) { page=Math.clamp(page+(vertical<0?1:-1),0,Math.max(0,(choices().size()-1)/9)); sound(false); }
        return true;
    }
    @Override public void close() { sound(false); if(dirty()) confirmDiscard=true; else leave(); }
    private void leave() { client.setScreen(parent); }
    public void invalidateImageCache() { images=null;type=null; }
    public void verifyImageRenderer() { if(images==null || images.size()==0) throw new IllegalStateException("No key setting images"); images.verifyGpuState(); }
    @Override public void removed() { type=null;invalidateImageCache(); }
    @Override public boolean shouldPause() { return false; }
}
