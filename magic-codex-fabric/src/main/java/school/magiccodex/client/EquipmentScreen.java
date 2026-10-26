package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.EquipmentProtocol;

/** Approved panel, live avatar and shared animated circle backdrop. */
public final class EquipmentScreen extends Screen {
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/equipment/panel.png"),FONT=Identifier.of("magiccodex","hud_bold");
    private static final String[] NAMES={"머리","상의","하의","신발","아티팩트","반지","귀걸이","목걸이"};
    private final StatsStars stars=new StatsStars();
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    private int selected=-1;
    private boolean broomLayout;
    private static final Identifier PANEL_BROOM=Identifier.of("magiccodex","textures/gui/equipment/panel_broom.png"),SLOT_LABEL_FONT=Identifier.of("magiccodex","title");
    private static void slotLabel(DrawContext c,String s,float x,float y){UiResources.text().draw(c,s,x,y,25,0xFFDCE4FA,SLOT_LABEL_FONT,true);}
    private static final Identifier BROOM_SLOT=Identifier.of("magiccodex","textures/gui/equipment/broom_equip_slot.png");
    public EquipmentScreen(){super(Text.literal("장비"));}
    @Override protected void init(){sound();broomLayout=BroomUiLink.available();if(broomLayout)BroomUiLink.refresh();ArtifactClient.query();}
    /** Artifact slot (index 4): always opens the artifact codex and shows what was equipped there (owner decision 2026-10-15). */
    private static final int ARTIFACT_SLOT=4;
    private CodexLayout layout(){return BroomSelectionLayout.equipmentFit(width,height,broomLayout);}
    private float stretch(){return BroomSelectionLayout.equipmentStretch(broomLayout);}
    private void sound(){if(client!=null)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.1f,.2f));}
    /** Slot frame left edge in the panel art (left gold frame 307..456, right 1216..1366; right was 1208 = 8px too far left). */
    private static int x(int i){return i<4?307:1216;}
    private int y(int i){return 156+(i%4)*(broomLayout?192:158);}
    private int extra(){return broomLayout?120:0;}
    private static boolean hit(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    private List<Integer> choices(){List<Integer> list=new ArrayList<>();if(EquipmentClient.data!=null)for(int i=0;i<EquipmentClient.data.candidates().size();i++)if(EquipmentClient.data.candidates().get(i).slot()==selected)list.add(i);return list;}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var l=layout();double mx=l.localX(mouseX),my=l.localY(mouseY)/stretch();var text=UiResources.text();var images=UiResources.images();text.beginFrame();images.beginFrame();c.fill(0,0,width,height,0x48040911);
        int hovered=-1;for(int i=0;i<8;i++)if(hit(mx,my,x(i),y(i),151,137))hovered=i;
        c.getMatrices().push();
        try{
            c.getMatrices().translate(l.x(),l.y(),0);c.getMatrices().scale(l.scale(),l.scale()*stretch(),1);
            // Broom layout: a pre-built taller panel (slots re-spaced on clean background, no seams or stray lines).
            if(broomLayout)images.drawTexture(c,PANEL_BROOM,0,0,0,0,1672,1061,1672,1061,1672,1061);
            else images.drawTexture(c,PANEL,0,0,0,0,1672,941,1672,941);
            int circle=EquipmentClient.data==null?StatsClient.values().circle():EquipmentClient.data.circle();double time=Util.getMeasuringTimeMs()/1000.0;
            c.getMatrices().push();c.getMatrices().translate(490,-22,0);c.getMatrices().scale(.92f,.92f,1);StatsBackdrop.draw(c,circle,time);c.getMatrices().pop();
            stars.update(circle,time);drawStars(c,false);
            var p=client.player;if(p!=null){float body=p.bodyYaw,yaw=p.getYaw(),pitch=p.getPitch(),head=p.headYaw,prev=p.prevHeadYaw;try{InventoryScreen.drawEntity(c,574,174,1100,632,211,0,836+(float)Math.clamp((mx-836)*.08,-15,15),407+(float)Math.clamp((my-407)*.06,-10,10),p);}finally{p.bodyYaw=body;p.setYaw(yaw);p.setPitch(pitch);p.headYaw=head;p.prevHeadYaw=prev;}}
            c.getMatrices().push();c.getMatrices().translate(0,0,300);drawStars(c,true);
            if(broomLayout&&BroomUiLink.available()){
                images.drawTexture(c,BROOM_SLOT,544,650,72,186,584,90,2029,312,2172,724);
                // Same look as the baked slot labels (머리, 상의…): serif semibold, pale blue-white, left of the content.
                var id=BroomUiLink.equipped();slotLabel(c,"빗자루",617,695);HudMesh.line(c,688,676,688,714,1,0x88D8B970);
                if(!id.isBlank()){
                    c.enableScissor(690,659,1108,729);
                    try{BroomUiLink.render(c,id,892,694,330,90,14,0);}finally{c.disableScissor();}

                }else label(c,"연습용 빗자루 (10초)",877,695,23,0xFFC0CDD9);
                if(selected<0&&hit(mx,my,544,650,584,90))HudMesh.line(c,563,734,1109,734,1,0xFF8DDDF3);
            }
            // Nickname: centred between the bar's gold lines (normal 662/717, broom 764/829).
            String name=NicknameClient.display(EquipmentClient.data==null?StatsClient.values().nickname():EquipmentClient.data.name());label(c,name,836,broomLayout?796.5f:690,27,0xFFF3E9D3);
            if(selected<0&&hit(mx,my,591,664+(broomLayout?100:0),490,50)){HudMesh.line(c,686,711+(broomLayout?100:0),986,711+(broomLayout?100:0),1,0xFF92E7F2);tooltip(c,"닉네임 설정",836,659+(broomLayout?100:0));}
            double[] values=EquipmentClient.data==null?null:new double[]{EquipmentClient.data.power(),EquipmentClient.data.health(),EquipmentClient.data.mana(),EquipmentClient.data.regen(),EquipmentClient.data.haste()};
            c.fill(145,778+extra(),1527,853+extra(),0xFF122236);
            int[][] icons={{188,784,61,60},{551,784,61,60},{899,784,50,60},{1238,783,74,62}};
            for(int i=0;i<5;i++){
                int left=185+i*275;
                if(i>0)HudMesh.line(c,left-25,789+extra(),left-25,839+extra(),1,0x88758896);
                if(i<4){int[] r=icons[i];images.drawTexture(c,PANEL,left,791+extra(),r[0],r[1],48,48,r[2],r[3],1672,941);}
                else MagicHasteIcon.draw(c,left+24,815+extra(),23);
                label(c,values==null?"—":"+"+StatsValues.number(values[i]),left+139,817+extra(),29,0xFFF1EDDF);
            }
            for(int i=0;i<8;i++){
                ItemStack item=i<EquipmentClient.equipped.size()?EquipmentClient.equipped.get(i):ItemStack.EMPTY;
                if(i==ARTIFACT_SLOT){if(!ArtifactClient.equipped().isEmpty())artifactSlot(c,i);}
                else if(!item.isEmpty()){c.fill(x(i)+9,y(i)+9,x(i)+143,y(i)+129,0xFF132237);drawItem(c,item,x(i)+28,y(i)+21,94);}
                if(i==hovered||i==selected){c.fill(x(i)+7,y(i)+7,x(i)+145,y(i)+131,0x253BC2EA);HudMesh.line(c,x(i)+15,y(i)+129,x(i)+136,y(i)+129,2,0xFF8DDDF3);}
            }
            String notice=EquipmentClient.notice;
            if(EquipmentClient.data==null&&EquipmentClient.supported())notice="장비 정보를 불러오는 중입니다.";
            if(!notice.isBlank())label(c,notice,836,737+extra(),19,0xFFD2DDE3);
            if(selected>=0)picker(c,mx,my);
            else if(hovered==ARTIFACT_SLOT){var a=ArtifactClient.equipped().isEmpty()?null:ArtifactClient.find(ArtifactClient.equipped().get(0));tooltip(c,a==null?"아티팩트 · 클릭하여 도감 열기":a.name()+" · 클릭하여 도감 열기",(float)mx,(float)my);}
            else if(hovered>=0){ItemStack item=hovered<EquipmentClient.equipped.size()?EquipmentClient.equipped.get(hovered):ItemStack.EMPTY;tooltip(c,item.isEmpty()?NAMES[hovered]+" · 클릭하여 장착":item.getName().getString()+" · 클릭 교체 / 우클릭 해제",(float)mx,(float)my);}
            else if(hit(mx,my,176,781+extra(),1290,75))tooltip(c,"추가 장비 효과 · 지능 / 체력 / 최대 마나 / 마나 회복 / 마법 가속",836,770+extra());
            c.getMatrices().pop();
        }finally{c.getMatrices().pop();images.endFrame();}
        entrance.draw(c,width,height);
    }
    /** Equipped artifact in the equipment window: icon when the artifact has one, otherwise its name in the grade colour. */
    private void artifactSlot(DrawContext c,int i){
        var a=ArtifactClient.find(ArtifactClient.equipped().get(0));if(a==null)return;
        c.fill(x(i)+9,y(i)+9,x(i)+143,y(i)+129,0xFF132237);
        var t=UiResources.text();
        if(a.icon().isBlank()||!UiResources.images().drawCentered(c,a.icon(),x(i)+76,y(i)+69,96,0xFFFFFFFF)){
            float size=Math.min(19f,19f*126/Math.max(1,t.width(a.name(),19,FONT)));
            t.draw(c,a.name(),x(i)+76,y(i)+69,size,ArtifactCodexScreen.GRADE_COLORS[a.grade()],FONT,true);
        }
        if(!a.usable())return;
        // Usable artifact: key badge, and the remaining cooldown on a dark veil.
        long left=ArtifactClient.cooldownLeft(a.id());
        if(left>0){c.fill(x(i)+9,y(i)+9,x(i)+143,y(i)+129,0xA0060C14);t.draw(c,((left+999)/1000)+"초",x(i)+76,y(i)+69,26,0xFFEAF3F5,FONT,true);}
        String key=ArtifactClient.useKeyLabel();float kw=Math.max(26,t.width(key,15,FONT)+12);
        c.fill((int)(x(i)+139-kw),y(i)+105,x(i)+139,y(i)+125,0xE0102437);
        t.draw(c,key,x(i)+139-kw/2,y(i)+115,15,0xFFE9D5A1,FONT,true);
    }
    private void drawStars(DrawContext c,boolean front){for(int i=0;i<stars.count();i++)if(stars.foreground(i)==front){float x=836+stars.x(i)*242,y=408+stars.y(i)*210,size=4+(stars.depth(i)+1)*2;HudMesh.star(c,x,y,size*1.8f,0x225CD6EF);HudMesh.star(c,x,y,size,0xDDE5D5A7);}}
    private void picker(DrawContext c,double mx,double my){
        c.fill(548,172,1125,640,0xF5101E30);HudMesh.line(c,564,174,1109,174,1.5f,0xFFD8C28C);label(c,NAMES[selected]+" 선택",836,207,27,0xFFE9D5A1);label(c,"×",1090,206,29,0xFFDAE3EA);
        var list=choices();if(list.isEmpty())label(c,"장착 가능한 아이템이 없습니다.",836,392,23,0xFFB7C6D1);
        for(int n=0;n<list.size();n++){int px=568+n%6*89,py=240+n/6*60;boolean hover=hit(mx,my,px,py,80,55);c.fill(px,py,px+80,py+55,hover?0xAA28617B:0x88253849);drawItem(c,EquipmentClient.candidates.get(list.get(n)),px+20,py+7,40);}
        label(c,EquipmentClient.busy()?"서버 확인 중…":"아이템을 클릭하면 장착합니다.",836,623,19,0xFFAFBDC9);
        for(int n=0;n<list.size();n++){int px=568+n%6*89,py=240+n/6*60;if(hit(mx,my,px,py,80,55))tooltip(c,EquipmentClient.candidates.get(list.get(n)).getName().getString(),(float)mx,(float)my);}
    }
    private void drawItem(DrawContext c,ItemStack item,int x,int y,int size){if(item.isEmpty())return;c.getMatrices().push();c.getMatrices().translate(x,y,20);c.getMatrices().scale(size/16f,size/16f,1);c.drawItem(item,0,0);c.getMatrices().pop();}
    private void label(DrawContext c,String s,float x,float y,float size,int color){var t=UiResources.text();float fit=Math.min(size,size*490/Math.max(1,t.width(s,size,FONT)));t.draw(c,s,x,y,fit,color,FONT,true);}
    private void tooltip(DrawContext c,String s,float x,float y){var t=UiResources.text();float w=Math.min(610,t.width(s,20,FONT)+24),cx=Math.clamp(x,w/2+100,1570-w/2),cy=Math.max(185,y-35);c.getMatrices().push();c.getMatrices().translate(0,0,400);HudMesh.capsule(c,cx-w/2,cy-19,w,38,0xF5102437,0xF5102437);label(c,s,cx,cy,20,0xFFEAF3F5);c.getMatrices().pop();}
    @Override public boolean mouseClicked(double x,double y,int button){
        var l=layout();double mx=l.localX(x),my=l.localY(y)/stretch();
        if(button==0&&hit(mx,my,1440,60,90,88)){close();return true;}
        if(button==0&&selected<0&&hit(mx,my,591,664+(broomLayout?100:0),490,50)){NicknameClient.open(this);return true;}
        if(button==0&&selected<0&&broomLayout&&BroomUiLink.available()&&hit(mx,my,544,650,584,90)){client.setScreen(new BroomSelectionScreen(this));return true;}
        if(selected>=0){if(button==1||hit(mx,my,1065,184,50,46)){selected=-1;sound();return true;}if(button==0){var list=choices();for(int n=0;n<list.size();n++)if(hit(mx,my,568+n%6*89,240+n/6*60,80,55)){if(EquipmentClient.request(EquipmentProtocol.EQUIP,selected,EquipmentClient.data.candidates().get(list.get(n)).inventory())){selected=-1;sound();}return true;}}return true;}
        if(hit(mx,my,EquipmentScreen.x(ARTIFACT_SLOT),y(ARTIFACT_SLOT),151,137)){if(button==0){sound();client.setScreen(new ArtifactCodexScreen(this));}return true;}
        for(int i=0;i<8;i++)if(hit(mx,my,EquipmentScreen.x(i),y(i),151,137)){
            if(button==0){selected=i;sound();}else if(button==1){EquipmentClient.request(EquipmentProtocol.REMOVE,i,0);sound();}return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==256&&selected>=0){selected=-1;return true;}return super.keyPressed(key,scan,mods);}
    @Override public void tick(){if(client.player==null||!client.player.isAlive())close();}
    @Override public void removed(){EquipmentClient.closed();}
    @Override public boolean shouldPause(){return false;}
}
