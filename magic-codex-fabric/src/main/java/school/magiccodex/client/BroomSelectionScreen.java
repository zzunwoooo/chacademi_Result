package school.magiccodex.client;

import java.util.Arrays;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/** Five-card selector using the server's Motion snapshot and the existing equipment font/cache. */
final class BroomSelectionScreen extends Screen {
    private static final Identifier ATLAS=Identifier.of("magiccodex","textures/gui/review/broom_atlas.png");
    private static final Identifier FONT=Identifier.of("magiccodex","hud_bold");
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/equipment/broom_selection_panel.png");
    private static final Identifier CARD=Identifier.of("magiccodex","textures/gui/equipment/broom_selection_card.png");
    private final EquipmentScreen parent;
    private int page;
    private String selected="",pending="",notice="";
    private long requestVersion,deadline;
    BroomSelectionScreen(EquipmentScreen parent){super(Text.literal("빗자루 선택"));this.parent=parent;}
    @Override protected void init(){BroomUiLink.refresh();if(selected.isBlank())selected=BroomUiLink.equipped();}
    @Override public void tick(){
        if(client==null||client.player==null||!client.player.isAlive()){close();return;}
        var owned=Arrays.asList(BroomUiLink.owned());page=BroomSelectionLayout.page(page,owned.size());
        if(!selected.isBlank()&&!owned.contains(selected))selected="";
        if(!pending.isBlank()&&BroomUiLink.version()!=requestVersion){
            notice=pending.equals(BroomUiLink.equipped())?"장착했습니다.":"보유 권한을 확인해 주세요.";pending="";
        }else if(!pending.isBlank()&&Util.getMeasuringTimeMs()>deadline){pending="";notice="서버 응답이 늦습니다. 다시 선택해 주세요.";BroomUiLink.refresh();}
    }
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var l=CodexLayout.codexFit(width,height);double mx=l.localX(mouseX),my=l.localY(mouseY);
        String[] owned=BroomUiLink.owned();page=BroomSelectionLayout.page(page,owned.length);String equipped=BroomUiLink.equipped();
        var images=UiResources.images();var text=UiResources.text();images.beginFrame();text.beginFrame();
        c.fill(0,0,width,height,0x65040911);var m=c.getMatrices();m.push();
        try{
            m.translate(l.x(),l.y(),0);m.scale(l.scale(),l.scale(),1);
            atlas(c,20,18,1632,125,25,16,1485,113); atlas(c,20,143,1632,680,25,129,1485,166); atlas(c,20,823,1632,110,25,295,1485,76);
            label(c,"빗자루",836,72,42,0xFFF0D69D,440);
            label(c,"×",1547,95,48,0xFFE9D7AB,54);
            int hover=-1;
            for(int slot=0;slot<5;slot++){
                int index=page*5+slot;if(index>=owned.length)break;
                String id=owned[index];var r=BroomSelectionLayout.card(slot);boolean over=r.contains(mx,my);
                if(over)hover=slot;
                int sx=id.equals(selected)?432:over?230:30;
                atlas(c,r.x(),r.y(),r.w(),r.h(),sx,390,id.equals(selected)?209:197,361);
                int grade=grade(BroomUiLink.text("grade",id));int[] colors={0xFFCDDAE5,0xFF71E5F2,0xFFD2A8FF,0xFFFFD582};
                // One isolated circle per grade (the atlas cells overlap, so neighbours used to bleed in).
                int size=Math.min(r.w()-38,245);UiResources.images().drawTexture(c,Identifier.of("magiccodex","textures/gui/review/broom_circle_"+grade+".png"),r.x()+(r.w()-size)/2,r.y()+119+(245-size)/2,0,0,size,size,236,236,236,236,0xFFFFFFFF);
                label(c,BroomUiLink.text("name",id),r.x()+r.w()/2f,r.y()+39,25,0xFFF0E8D7,r.w()-40);
                label(c,BroomUiLink.text("grade",id),r.x()+r.w()/2f,r.y()+76,20,colors[grade],r.w()-50);
                c.enableScissor(r.x()+14,r.y()+95,r.x()+r.w()-14,r.y()+r.h()-44);
                try{
                    boolean rendered=BroomUiLink.render(c,id,r.x()+r.w()/2f,r.y()+260,282,0,70,over?(float)(Util.getMeasuringTimeMs()%12000)*.03f:0);
                    if(!rendered)label(c,"모델 준비 중",r.x()+r.w()/2f,r.y()+250,21,0xFF9AB0C4,r.w()-48);
                }finally{c.disableScissor();}
                if(id.equals(equipped)){atlas(c,r.x()+43,r.y()+r.h()-65,r.w()-86,40,1015,806,174,59);label(c,"장착 중",r.x()+r.w()/2f,r.y()+r.h()-46,21,0xFFC2F4FA,r.w()-70);}
            }
            if(owned.length==0)label(c,BroomUiLink.available()?"보유한 빗자루가 없습니다. 비행 마법은 연습용 빗자루로 10초간 탑승합니다.":"빗자루 정보를 연결할 수 없습니다.",836,444,26,0xFFD3DEE7,1160);
            if(BroomSelectionLayout.pages(owned.length)>1){label(c,"‹",101,463,55,page>0?0xFFEAD5A2:0xFF586779,50);label(c,"›",1567,463,55,page+1<BroomSelectionLayout.pages(owned.length)?0xFFEAD5A2:0xFF586779,50);}
            button(c,BroomSelectionLayout.EQUIP,pending.isBlank()?"장착":"확인 중…",!selected.isBlank()&&pending.isBlank(),mx,my);
            button(c,BroomSelectionLayout.BACK,"뒤로",true,mx,my);
            label(c,notice.isBlank()?"비행 숙련도 "+Math.round(BroomUiLink.mastery()*100)+"%   ·   "+(page+1)+" / "+BroomSelectionLayout.pages(owned.length):notice,836,934,16,0xFFB4C7D7,1000);
            String inspect=hover>=0?owned[page*5+hover]:!selected.isBlank()?selected:owned.length>0?owned[page*5]:""; if(!inspect.isBlank())tooltip(c,inspect,Math.max(0,hover),equipped);
        }finally{m.pop();images.endFrame();}
    }
    private static int grade(String text){return text.contains("전설")?3:text.contains("유일")||text.contains("영웅")?2:text.contains("고급")||text.contains("희귀")?1:0;}
    private static int score(float value){return Float.isFinite(value)?Math.clamp(Math.round(value*10),1,10):1;}
    private static void atlas(DrawContext c,int x,int y,int w,int h,int sx,int sy,int sw,int sh){UiResources.images().drawTexture(c,ATLAS,x,y,sx,sy,w,h,sw,sh,1536,1024,0xFFFFFFFF);}
    private void tooltip(DrawContext c,String id,int slot,String equipped){
        var values=BroomUiLink.gauges(id);var compare=BroomUiLink.gauges(equipped);var labels=BroomUiLink.labels();
        var m=c.getMatrices();m.push();try{
            m.translate(0,0,600);atlas(c,415,580,842,248,26,757,610,224);
            label(c,BroomUiLink.text("name",id),836,605,25,0xFFF0DFC0,610);
            for(int i=0;i<Math.min(5,Math.min(values.length,labels.length));i++){
                int y=637+i*35;int v=score(values[i]);label(c,labels[i],521,y+3,19,0xFFD4E1E9,160);
                for(int unit=0;unit<10;unit++)HudMesh.capsule(c,630+46*unit,y-5,42,10,unit<v?0xFF39D9ED:0xFF23364D,unit<v?0xFF39D9ED:0xFF23364D);label(c,Integer.toString(v),1155,y,20,0xFF65EAFF,60);
                for(int unit=0;unit<10;unit++)HudMesh.capsule(c,630+46*unit,y+8,42,8,0xFF23364D,0xFF23364D);
                if(!equipped.isBlank()&&i<compare.length){int old=score(compare[i]);for(int unit=0;unit<old;unit++)HudMesh.capsule(c,630+46*unit,y+8,42,8,0xFF9EB6CC,0xFF9EB6CC);label(c,Integer.toString(old),1155,y+14,15,0xFFCAD6E2,60);}
            }
        }finally{m.pop();}
    }
    private static void label(DrawContext c,String value,float x,float y,float size,int color,float max){
        var t=UiResources.text();String s=value==null?"":value.replace('\n',' ');
        size=Math.max(12,Math.min(size,size*max/Math.max(max,t.width(s,size,FONT)))); if(t.width(s,size,FONT)>max){while(s.length()>1&&t.width(s+"…",size,FONT)>max)s=s.substring(0,s.offsetByCodePoints(s.length(),-1));s+="…";}
        t.draw(c,s,x,y,size,color,FONT,true);
    }
    private static void button(DrawContext c,BroomSelectionLayout.Rect r,String title,boolean enabled,double mx,double my){
        atlas(c,r.x(),r.y(),r.w(),r.h(),645,enabled&&r.contains(mx,my)?879:790,352,84);
        label(c,title,r.x()+r.w()/2f,r.y()+r.h()/2f,29,enabled?0xFFE7D8B6:0xFF788B9D,r.w()-32);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return true;var l=CodexLayout.codexFit(width,height);double mx=l.localX(x),my=l.localY(y);String[] owned=BroomUiLink.owned();
        if(BroomSelectionLayout.CLOSE.contains(mx,my)||BroomSelectionLayout.BACK.contains(mx,my)){close();return true;}
        if(BroomSelectionLayout.PREVIOUS.contains(mx,my)){page=BroomSelectionLayout.page(page-1,owned.length);return true;}
        if(BroomSelectionLayout.NEXT.contains(mx,my)){page=BroomSelectionLayout.page(page+1,owned.length);return true;}
        if(BroomSelectionLayout.EQUIP.contains(mx,my)){
            if(pending.isBlank()&&!selected.isBlank()&&Arrays.asList(owned).contains(selected)){pending=selected;requestVersion=BroomUiLink.version();deadline=Util.getMeasuringTimeMs()+5000;notice="";BroomUiLink.equip(selected);}return true;
        }
        for(int i=0;i<5;i++)if(page*5+i<owned.length&&BroomSelectionLayout.card(i).contains(mx,my)){selected=owned[page*5+i];notice="";return true;}
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(vertical!=0)page=BroomSelectionLayout.page(page+(vertical<0?1:-1),BroomUiLink.owned().length);return true;}
    @Override public void close(){if(client!=null)client.setScreen(client.player==null?null:parent);}
    @Override public boolean shouldPause(){return false;}
}
