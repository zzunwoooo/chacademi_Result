package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.QuestProtocol;

/** Approved PNG assets, dynamic server text, six cards and a detail panel. */
public final class QuestScreen extends Screen {
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/quests/panel.png"),CARD=Identifier.of("magiccodex","textures/gui/quests/quest-card.png"),BUTTON=Identifier.of("magiccodex","textures/gui/quests/accept-button.png"),FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final Identifier STAMP=Identifier.of("magiccodex","textures/gui/quests/completed-stamp.png");
    private static final int GOLD=0xFFE8D19B,WHITE=0xFFF1EEE3,MUTED=0xFFB9CBD8,INK=0xFF263A4A;
    private String selected="",notice="";private long refresh,waiting,noticeUntil;private int page,tab,rewardScroll,descriptionScroll,category;private boolean confirmAbandon; private final Set<String> closedJournal=new HashSet<>(); private int journalScroll;
    public QuestScreen(){super(Text.literal("퀘스트 일지"));}
    QuestScreen(int initialTab){this();tab=initialTab;}
    @Override public boolean shouldPause(){return false;}
    void received(String message){waiting=0;page=board().page();if(!message.isEmpty()){notice=message;noticeUntil=Util.getMeasuringTimeMs()+6000;}}
    @Override public void tick(){long n=Util.getMeasuringTimeMs();if(waiting!=0&&n>waiting){waiting=0;notice="응답이 늦습니다. 잠시 후 다시 시도해 주세요.";noticeUntil=n+6000;}if(n>=refresh&&waiting==0){if(tab==0)QuestClient.send(0,"",page,tab);else JournalClient.refresh();refresh=n+3000;}}
    private QuestProtocol.Response board(){if(tab==0)return QuestClient.data;var rows=JournalClient.rows().stream().filter(e->tab==1?e.state().equals("claimed"):Set.of("active","paying").contains(e.state())).toList();var cards=rows.stream().map(e->new QuestProtocol.Card(e.key(),e.title(),e.description(),e.reward(),e.state(),List.of(),"F",e.state().equals("claimed")?1:0,1,e.main())).toList();return new QuestProtocol.Response(false,0,rows.size(),"",cards);}
    private school.magiccodex.protocol.JournalProtocol.Entry record(String key){return JournalClient.rows().stream().filter(e->e.key().equals(key)).findFirst().orElse(null);}
    private boolean actionable(QuestProtocol.Card card){return tab==0||card.id().startsWith("quest:");}
    private QuestProtocol.Card selected(){var cards=board().cards();if(cards.isEmpty())return null;return cards.stream().filter(c->c.id().equals(selected)).findFirst().orElse(cards.getFirst());}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){var l=CodexLayout.codexFit(width,height);double mx=l.localX(mouseX),my=l.localY(mouseY);var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();c.fill(0,0,width,height,0x65050A14);c.getMatrices().push();try{
        c.getMatrices().translate(l.x(),l.y(),0);c.getMatrices().scale(l.scale(),l.scale(),1);images.drawTexture(c,PANEL,0,0,0,0,1671,941,1671,941);
        text(c,"퀘스트 일지",835,58,35,GOLD,true,true);for(int i=0;i<3;i++){int x=130+i*277;HudMesh.line(c,x,131,x+242,131,i==tab?3:1,i==tab?GOLD:0x555E7181);text(c,List.of("수락 가능","완료","진행중").get(i),x+121,113,21,i==tab?GOLD:MUTED,i==tab,true);}
        if(tab!=0){drawJournal(c,mx,my);return;}
        text(c,"×",1555,94,38,WHITE,true,true);
        // Subtle time-based glints, no frame-stepped animation or text glow.
        double time=Util.getMeasuringTimeMs()/1000d;for(int i=0;i<12;i++){float x=120+(i*137)%1430,y=145+(i*79)%680;int a=(int)(25+25*(1+Math.sin(time*.8+i)));HudMesh.disk(c,x+(float)Math.sin(time*.2+i)*8,y,1.4f,(a<<24)|0xB1DFFF);}
        var chosen=selected();var cards=board().cards();
        for(int i=0;i<cards.size();i++){var q=cards.get(i);int x=119+(i%3)*289,y=161+(i/3)*318;images.drawTexture(c,CARD,x,y,0,0,278,306,1161,1355,1161,1355,paperTint(q.state()));
            if(tab==0){c.getMatrices().push();c.getMatrices().translate(0,0,-.75f);text(c,q.rank(),x+139,y+178,190,0x0E6C5239,true,true);UiResources.text().drawOutline(c,q.rank(),x+139,y+178,190,0x306C5239,BOLD,true);c.getMatrices().pop();}
            if(q.main())text(c,"메인",x+139,y+40,17,INK,true,true);wrapped(c,q.title(),x+32,y+76,214,27,INK,true,2);
            divider(c,x+30,y+133,218,0xCF806743);
            wrapped(c,q.description(),x+32,y+157,214,18,INK,false,4);
            if(tab==0&&q.state().equals("active")){long done=q.goals().stream().filter(g->g.current()>=g.required()).count();text(c,"목표 "+done+" / "+q.goals().size(),x+32,y+256,17,INK,true,false);}
            if(q.state().equals("claimed")){c.getMatrices().push();c.getMatrices().translate(x+174,y+224,2);c.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(-13));images.drawTexture(c,STAMP,-66,-66,0,0,132,132,1254,1254,1254,1254);c.getMatrices().pop();}

        }
        if(cards.isEmpty())text(c,List.of("지금 수락할 의뢰가 없습니다.","아직 완료한 의뢰가 없습니다.","진행 중인 의뢰가 없습니다.").get(tab),548,440,27,MUTED,false,true);
        text(c,"‹",260,826,34,GOLD,true,true);text(c,(board().page()+1)+" / "+Math.max(1,(board().total()+5)/6),370,826,22,WHITE,false,true);text(c,"›",480,826,34,GOLD,true,true);
        if(chosen!=null){text(c,(tab!=0?state(chosen.state()):chosen.main()?"메인 퀘스트":"남은 서브 의뢰 "+Math.max(0,chosen.limit()-chosen.completions())+" / "+chosen.limit()),775,826,21,GOLD,true,true);wrapped(c,chosen.title(),1060,185,350,28,GOLD,true,2);text(c,"난이도",1452,185,18,MUTED,false,true);text(c,chosen.rank(),1530,185,26,GOLD,true,true);divider(c,1060,224,492,0xAAAC9872);var lines=descriptionLines(chosen.description());descriptionScroll=Math.clamp(descriptionScroll,0,Math.max(0,lines.size()-3));for(int i=0;i<3&&i+descriptionScroll<lines.size();i++)text(c,lines.get(i+descriptionScroll),1060,255+i*28,21,WHITE,false,false);if(lines.size()>3)text(c,"설명 위에서 휠로 더 보기",1060,332,14,MUTED,false,false);
            text(c,tab==0?"\uc758\ub8b0 \ubaa9\ud45c":"\uae30\ub85d \uc0c1\uc138",1060,381,23,GOLD,true,false);
            if(tab!=0){var entry=record(chosen.id());if(entry!=null){wrapped(c,entry.details(),1060,450,470,19,WHITE,false,5);}}
for(int i=0;i<chosen.goals().size();i++){var g=chosen.goals().get(i);int y=418+i*34;fitted(c,g.label(),1060,y,23,330,WHITE,false);text(c,g.current()+" / "+g.required(),1535,y,21,g.current()>=g.required()?0xFF8AE1C9:MUTED,true,true);}
            text(c,"보상",1060,663,23,GOLD,true,false);var rewards=rewardLines(chosen.reward());rewardScroll=Math.clamp(rewardScroll,0,Math.max(0,rewards.size()-3));for(int i=0;i<3&&i+rewardScroll<rewards.size();i++)fitted(c,rewards.get(i+rewardScroll),1130,663+i*25,19,411,WHITE,false);
            if(rewards.size()>3)text(c,"휠로 더 보기",1515,734,13,MUTED,false,true);
            if(actionable(chosen)&&!chosen.state().equals("claimed")){images.drawTexture(c,BUTTON,1082,731,0,0,440,104,2172,724,2172,724);String button=waiting!=0?"처리 중…":switch(chosen.state()){case "active"->"제출 및 보상 받기";case "paying"->"관리자 확인 대기";default->"의뢰 수락";};text(c,button,1301,783,25,WHITE,true,true);}

            if(actionable(chosen)&&chosen.state().equals("active"))text(c,confirmAbandon?"다시 누르면 포기합니다":"의뢰 포기",1300,833,18,MUTED,false,true);
        }
        if(Util.getMeasuringTimeMs()<noticeUntil)fitted(c,notice,120,869,19,850,GOLD,false);
    }finally{c.getMatrices().pop();images.endFrame();}}
    private List<JournalTree.Row> journalRows(){return JournalTree.rows(JournalClient.rows(),tab==1,JournalClient::chapter,closedJournal);}
    private void arrow(DrawContext c,int x,int y,boolean expanded){if(expanded){HudMesh.line(c,x-9,y-4,x,y+5,3,GOLD);HudMesh.line(c,x,y+5,x+9,y-4,3,GOLD);}else{HudMesh.line(c,x-4,y-9,x+5,y,3,GOLD);HudMesh.line(c,x+5,y,x-4,y+9,3,GOLD);}}
    private void categoryBar(DrawContext c,int y,String title,boolean expanded){
        var bar=Identifier.of("magiccodex","textures/gui/quest_category_bar.png");var images=UiResources.images();int tint=expanded?0xFFFFFFFF:0xCCFFFFFF;
        images.drawTexture(c,bar,119,y,55,243,26,58,100,221,2172,724,tint);
        images.drawTexture(c,bar,145,y,155,243,807,58,1863,221,2172,724,tint);
        images.drawTexture(c,bar,952,y,2018,243,26,58,100,221,2172,724,tint);
        images.drawCentered(c,"minecraft:textures/item/"+(title.equals("\uba54\uc778 \ud018\uc2a4\ud2b8")?"book":"paper")+".png",164,y+29,28,0xFFFFFFFF);
        text(c,title,209,y+29,25,GOLD,true,false);if(expanded){HudMesh.line(c,925,y+25,934,y+34,3,GOLD);HudMesh.line(c,934,y+34,943,y+25,3,GOLD);}else{HudMesh.line(c,930,y+20,939,y+29,3,GOLD);HudMesh.line(c,939,y+29,930,y+38,3,GOLD);}
    }
    private void drawJournal(DrawContext c,double mx,double my){
        text(c,"×",1555,94,38,WHITE,true,true);var data=board();var cards=data.cards();var chosen=selected();
        var rows=journalRows();journalScroll=Math.clamp(journalScroll,0,Math.max(0,rows.size()-9));
        for(int i=0;i<9&&journalScroll+i<rows.size();i++){
            var row=rows.get(journalScroll+i);int y=171+i*66;
            if(row.depth()==0){categoryBar(c,y,row.title(),!closedJournal.contains(row.key()));continue;}
            boolean active=chosen!=null&&chosen.id().equals(row.key());int x=129+row.depth()*20;
            c.fill(x,y,970,y+58,active?0xBB35536D:0x66162D43);
            if(row.entry()==null){fitted(c,row.title(),x+20,y+29,22,680,GOLD,true);arrow(c,934,y+29,!closedJournal.contains(row.key()));}
            else{if(active)c.fill(x,y+3,x+5,y+55,GOLD);text(c,"ㆍ",x+18,y+29,24,MUTED,false,false);fitted(c,row.title(),x+46,y+29,24,900-x-46,WHITE,active);}
        }
        if(rows.size()>9)text(c,"목록 위에서 휠로 더 보기",545,802,16,MUTED,false,true);
        if(chosen!=null){var entry=record(chosen.id());text(c,entry!=null&&entry.main()?"\uba54\uc778 \ud018\uc2a4\ud2b8":"\uc77c\uc9c0",1052,196,21,MUTED,false,false);wrapped(c,chosen.title(),1052,245,480,31,GOLD,true,2);
            text(c,state(chosen.state()),1052,327,22,GOLD,true,false); // The approved panel already contains the gold divider.
            var lines=descriptionLines(chosen.description().isBlank()?"\uc124\uba85\uc774 \uc544\uc9c1 \uc5c6\uc2b5\ub2c8\ub2e4.":chosen.description());descriptionScroll=Math.clamp(descriptionScroll,0,Math.max(0,lines.size()-7));for(int i=0;i<7&&i+descriptionScroll<lines.size();i++)text(c,lines.get(i+descriptionScroll),1052,423+i*28,21,WHITE,false,false);
            if(lines.size()>7)text(c,"\uc124\uba85 \uc704\uc5d0\uc11c \ud720\ub85c \ub354 \ubcf4\uae30",1052,625,14,MUTED,false,false);
            if(entry!=null){wrapped(c,entry.details(),1052,651,475,17,MUTED,false,3);}
            if(actionable(chosen)&&chosen.state().equals("active")){UiResources.images().drawTexture(c,BUTTON,1082,731,0,0,440,104,2172,724,2172,724);text(c,waiting!=0?"\ucc98\ub9ac \uc911…":"\uc81c\ucd9c \ubc0f \ubcf4\uc0c1 \ubc1b\uae30",1301,783,25,WHITE,true,true);text(c,confirmAbandon?"\ub2e4\uc2dc \ub204\ub974\uba74 \ud3ec\uae30\ud569\ub2c8\ub2e4":"\uc758\ub8b0 \ud3ec\uae30",1300,833,18,MUTED,false,true);}
        }
        if(Util.getMeasuringTimeMs()<noticeUntil)fitted(c,notice,120,869,19,850,GOLD,false);
    }
    private boolean journalClick(double x,double y){if(tab==0)return false;
        var rows=journalRows();journalScroll=Math.clamp(journalScroll,0,Math.max(0,rows.size()-9));
        for(int i=0;i<9&&journalScroll+i<rows.size();i++)if(hit(x,y,119,171+i*66,859,58)){
            var row=rows.get(journalScroll+i);if(row.entry()==null){if(!closedJournal.add(row.key()))closedJournal.remove(row.key());}
            else{selected=row.key();category=row.entry().main()?0:1;descriptionScroll=rewardScroll=0;confirmAbandon=false;}click();return true;
        }return false;
    }
    private static int paperTint(String state){return switch(state){case "active","paying"->0xFFE5EFE4;case "claimed"->0xFFB1ADA3;default->0xFFFFF1D2;};}
    private static void divider(DrawContext c,float x,float y,float width,int color){c.getMatrices().push();c.getMatrices().translate(0,0,.6f);float mid=x+width/2;HudMesh.line(c,x,y,mid-10,y,2.3f,color);HudMesh.line(c,mid+10,y,x+width,y,2.3f,color);c.getMatrices().translate(mid,y,0);c.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(45));c.fill(-3,-3,3,3,color);c.getMatrices().pop();}
    private static String state(String s){return switch(s){case "active"->"진행 중";case "claimed"->"완료";case "paying"->"처리 확인 중";default->"수락 가능";};}
    private void text(DrawContext c,String s,float x,float y,float size,int color,boolean bold,boolean center){UiResources.text().draw(c,s,x,y,size,color,bold?BOLD:FONT,center);}
    private void fitted(DrawContext c,String s,float x,float y,float size,float w,int color,boolean bold){var f=bold?BOLD:FONT;float fit=Math.min(size,size*w/Math.max(w,UiResources.text().width(s,size,f)));text(c,s,x,y,fit,color,bold,false);}
    private void wrapped(DrawContext c,String s,float x,float y,float w,float size,int color,boolean bold,int max){var f=bold?BOLD:FONT;String line="";int row=0;for(int i=0;i<s.length();i++){String next=line+s.charAt(i);if(s.charAt(i)=='\n'||UiResources.text().width(next,size,f)>w){text(c,line,x,y+row*(size+7),size,color,bold,false);if(++row>=max)return;line=s.charAt(i)=='\n'?"":String.valueOf(s.charAt(i));}else line=next;}if(!line.isEmpty())text(c,line,x,y+row*(size+7),size,color,bold,false);}
    private static boolean hit(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;var l=CodexLayout.codexFit(width,height);double mx=l.localX(x),my=l.localY(y);if(journalClick(mx,my))return true;if(hit(mx,my,1520,65,70,65)){close();return true;}
        for(int i=0;i<3;i++)if(hit(mx,my,130+i*277,94,242,42)){tab=i;page=0;selected="";descriptionScroll=rewardScroll=0;waiting=0;if(tab==0)send(0,"");else JournalClient.refresh();return true;}
        for(int i=0;tab==0&&i<board().cards().size();i++)if(hit(mx,my,119+(i%3)*289,161+(i/3)*318,278,306)){selected=board().cards().get(i).id();confirmAbandon=false;descriptionScroll=rewardScroll=0;click();return true;}
        if(waiting!=0)return true;
        if(tab==0&&(hit(mx,my,220,797,80,58)||hit(mx,my,440,797,80,58))){int step=mx<370?-1:1;page=Math.clamp(page+step,0,Math.max(0,(board().total()-1)/6));if(tab==0)send(0,"");return true;}
        var q=selected();if(q!=null&&actionable(q)&&hit(mx,my,1100,746,405,75)){if(q.state().equals("available"))send(1,q.id());else if(q.state().equals("active"))send(2,q.id());return true;}
        if(q!=null&&actionable(q)&&q.state().equals("active")&&hit(mx,my,1110,817,390,34)){if(confirmAbandon){send(3,q.id());confirmAbandon=false;}else confirmAbandon=true;return true;}return super.mouseClicked(x,y,button);
    }
    private void send(int action,String id){if(QuestClient.send(action,id.startsWith("quest:")?id.substring(6):id,tab==0?page:0,tab)){waiting=Util.getMeasuringTimeMs()+8000;refresh=waiting+1000;click();}}
    private List<String> descriptionLines(String s){var lines=new ArrayList<String>();String line="";for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(ch=='\n'||UiResources.text().width(line+ch,21,FONT)>456){lines.add(line);line=ch=='\n'?"":String.valueOf(ch);}else line+=ch;}if(!line.isEmpty())lines.add(line);return lines;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){var l=CodexLayout.codexFit(width,height);if(tab!=0&&hit(l.localX(x),l.localY(y),119,171,859,594)){journalScroll=Math.clamp(journalScroll+(int)-Math.signum(vertical),0,Math.max(0,journalRows().size()-9));return true;}if(hit(l.localX(x),l.localY(y),1050,642,500,98)){rewardScroll+=(int)-Math.signum(vertical);return true;}if(hit(l.localX(x),l.localY(y),1050,tab==0?236:400,490,tab==0?101:230)){descriptionScroll+=(int)-Math.signum(vertical);return true;}return super.mouseScrolled(x,y,horizontal,vertical);}
    private List<String> rewardLines(String value){return value.lines().map(line->{var m=java.util.regex.Pattern.compile("([A-Z_]+) × ([0-9]+)").matcher(line);var out=new StringBuffer();while(m.find()){String id=m.group(1).toLowerCase(java.util.Locale.ROOT);var item=net.minecraft.registry.Registries.ITEM.get(Identifier.of("minecraft",id));var stack=item.getDefaultStack();String translated=ItemTooltipRenderer.localName(stack,stack.getName()).getString();m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(translated+" × "+m.group(2)));}m.appendTail(out);return out.toString();}).toList();}
    private void click(){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1f,.25f));}
}
