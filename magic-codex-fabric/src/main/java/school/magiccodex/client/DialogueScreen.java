package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.DialogueProtocol;

/**
 * Character reveal clips stable full-line textures instead of baking every text prefix.
 *
 * <p>두 가지로 쓰인다. (1) 서버 대화: DialogueProtocol 응답을 받아 그리고 선택을 DialogueClient 로 보낸다 (driver == null, 예전 그대로).
 * (2) 외부 대화(api.ExternalDialogue → ExternalDialogueHost): 같은 화면을 그리되 선택·넘기기·닫힘을 {@link Driver} 에게만 알리고
 * DialogueProtocol 패킷은 보내지 않는다.
 */
public final class DialogueScreen extends Screen {
    private static final Identifier FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final Identifier PANEL=asset("dialogue-panel"),NAME=asset("speaker-nameplate"),CHOICE=asset("choice-button");
    private static boolean sound=true;
    private final Screen parent;private DialogueProtocol.Response data;private final List<String> history=new ArrayList<>();
    private List<Line> lines=List.of();private int page,shown,historyPage;private long start,lastSound,waiting;private boolean log,closed;
    private Identifier portrait;private boolean portraitPresent;private int resourceGeneration=-1;private record Line(String text,float[] ends){}
    // 내 차례 (선택 직후 내 일러스트 + 고른 문장). 그동안 온 서버 응답·닫기는 끝난 뒤 적용한다.
    private PlayerTurn turn;private DialogueProtocol.Response heldResponse;private boolean heldClose;
    /** 외부 대화 드라이버. choose: 선택지 id, 선택지 없는 프레임을 넘기면 "". removed: 드라이버가 닫은 게 아닌데 화면이 사라짐. */
    interface Driver{void choose(int sequence,String choice);void removed();}
    /** 외부 프레임에만 있는 값 (서버 응답에는 없음). */
    record External(boolean closable,boolean last,boolean playerPortrait,java.nio.file.Path portraitFile){}
    private Driver driver;private External external,heldExternal;private final boolean externalMode;private boolean driverClosed,removing;
    // 기록 창: 보고 있는 항목의 줄바꿈 결과 (매 프레임 다시 재지 않는다). 항목이나 폰트 세대가 바뀌면 다시 계산.
    private String logEntry;private List<String> logRows=List.of();private int logGeneration=-1;
    public DialogueScreen(DialogueProtocol.Response data,Screen parent){super(Text.literal("NPC 대화"));this.parent=parent;this.externalMode=false;receive(data);}
    /** 외부 대화용. ExternalDialogueHost 만 만든다. */
    DialogueScreen(DialogueProtocol.Response data,Driver driver,External external){super(Text.literal("대화"));this.parent=null;this.externalMode=true;this.driver=driver;this.external=external;receive(data);}
    /** 외부 대화로 만들어진 화면인지 (드라이버가 이미 닫은 뒤 "내 차례"만 남은 상태 포함). */
    boolean external(){return externalMode;}
    private boolean closable(){return !externalMode||external==null||external.closable();}
    String session(){return data.session();}
    private static Identifier asset(String name){return Identifier.of("magiccodex","textures/gui/dialogue/"+name+".png");}
    void receive(DialogueProtocol.Response r){if(turn!=null){if(heldResponse==null||r.sequence()>=heldResponse.sequence())heldResponse=r;waiting=0;return;}if(data!=null&&data.session().equals(r.session())&&r.sequence()<data.sequence())return;boolean changed=data==null||data.sequence()!=r.sequence()||!data.session().equals(r.session());data=r;waiting=0;if(changed){history.add(r.speaker()+"\n"+r.text());if(history.size()>80)history.removeFirst();page=0;layout();restart();}portrait=r.portrait().matches("[a-z0-9_/-]{1,120}")?asset(r.portrait()):null;resourceGeneration=-1;}
    /** 외부 프레임. "내 차례" 중이면 서버 응답처럼 끝날 때까지 들고 있는다. */
    void receiveExternal(DialogueProtocol.Response r,External x){
        if(!externalMode||driverClosed)return;
        if(turn!=null){if(heldResponse==null||!heldResponse.session().equals(r.session())||r.sequence()>=heldResponse.sequence()){heldResponse=r;heldExternal=x;}waiting=0;return;}
        if(data!=null&&data.session().equals(r.session())&&r.sequence()<data.sequence())return;
        external=x;receive(r);
    }
    /** 앞 외부 대화가 끝난 창을 새 외부 세션이 이어받는다 (마지막 "내 차례"가 끝난 뒤 새 대화가 보이고, 기록은 이어진다). */
    void adopt(Driver next,DialogueProtocol.Response r,External x){
        if(!externalMode)return;
        driver=next;driverClosed=false;closed=false;heldClose=false;
        if(turn!=null){heldResponse=r;heldExternal=x;waiting=0;return;}
        external=x;receive(r);
    }
    /** 드라이버가 대화를 끝냄 (Driver.removed 는 부르지 않는다). "내 차례" 중이면 끝난 뒤 닫는다. */
    void externalClose(){
        if(!externalMode)return;
        driverClosed=true;driver=null;
        if(turn!=null){heldClose=true;heldResponse=null;heldExternal=null;waiting=0;return;}
        closed=true;if(client!=null&&client.currentScreen==this&&!removing)client.setScreen(parent);
    }
    private void layout(){var rows=new ArrayList<Line>();StringBuilder current=new StringBuilder();for(int cp:data.text().codePoints().toArray()){String ch=new String(Character.toChars(cp));if(cp=='\n'||UiResources.text().width(current+ch,29,FONT)>1330){rows.add(line(current.toString()));current.setLength(0);if(cp=='\n')continue;}current.append(ch);}if(!current.isEmpty()||rows.isEmpty())rows.add(line(current.toString()));lines=List.copyOf(rows);}
    private Line line(String s){int[] cps=s.codePoints().toArray();float[] ends=new float[cps.length+1];StringBuilder prefix=new StringBuilder();for(int i=0;i<cps.length;i++){prefix.appendCodePoint(cps[i]);ends[i+1]=UiResources.text().width(prefix.toString(),29,FONT);}return new Line(s,ends);}
    private void restart(){start=Util.getMeasuringTimeMs();shown=0;}
    private int count(){return lines.subList(page*3,Math.min(page*3+3,lines.size())).stream().mapToInt(l->l.ends.length-1).sum();}
    private boolean complete(){return shown>=count();}
    private boolean lastPage(){return (page+1)*3>=lines.size();}
    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext c,int x,int y,float delta){}
    @Override public void tick(){if(waiting>0&&Util.getMeasuringTimeMs()>waiting){waiting=0;if(!externalMode)close();}}
    private CodexLayout layoutFit(){float scale=Math.min(width/1600f,height/900f);return new CodexLayout((width-1600*scale)/2,(height-900*scale)/2,scale);}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        long now=Util.getMeasuringTimeMs();int next=(int)Math.min(count(),Math.max(shown,(now-start)/23));if(turn!=null)turn.tick(log);
        if(!log&&next>shown){if(sound&&now-lastSound>=45){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.8f,.07f));lastSound=now;}shown=next;}
        if(resourceGeneration!=UiResources.generation()){resourceGeneration=UiResources.generation();portraitPresent=portrait!=null&&client.getResourceManager().getResource(portrait).isPresent();}
        var fit=layoutFit();double mx=fit.localX(mouseX),my=fit.localY(mouseY);var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();
        c.fill(0,0,width,height,0x30030A12);c.getMatrices().push();try{
            c.getMatrices().translate(fit.x(),fit.y(),0);c.getMatrices().scale(fit.scale(),fit.scale(),1);
            if(turn!=null)turn.drawPortrait(c);else if(external!=null&&drawExternalPortrait(c)){}else if(portraitPresent)images.drawTexture(c,portrait,65,-5,0,0,700,1050,0,0,1024,1536); // 0,0 = whole file: portraits may be 512x768 or 1024x1536
            if(!externalMode){c.fill(28,28,610,112,0xCB101D2C);label(c,(data.preview()?"[미리보기] ":"")+data.title(),47,69,25,0xFFE3C48C,true,false,540);}
            label(c,sound?"♪ 소리 켜짐":"♪ 소리 꺼짐",1110,54,21,0xFFD8DFE7,false,false,175);label(c,"기록",1305,54,21,0xFFD8DFE7,false,false,90);if(closable())label(c,"닫기 ×",1445,54,21,0xFFD8DFE7,false,false,100);
            if(turn!=null)turn.drawPanel(c,images,255,193);else{
            images.drawTexture(c,PANEL,60,639,44,199,1480,220,2085,316,2172,724);
            images.drawTexture(c,NAME,86,615,200,244,255,54,1810,235,2172,724);
            label(c,data.speaker(),131,642,23,0xFFE8D19B,true,false,193);
            int remaining=shown;for(int i=page*3;i<Math.min(page*3+3,lines.size());i++){
                var row=lines.get(i);int n=Math.min(remaining,row.ends.length-1);remaining-=n;
                if(n>0){int y=704+(i-page*3)*43;boolean partial=n<row.ends.length-1;if(partial)c.enableScissor(115,y-24,120+(int)Math.ceil(row.ends[n])+1,y+26);
                    label(c,row.text,120,y,29,0xFFF0F1F4,false,false,1400);if(partial)c.disableScissor();}
            }
            if(complete()&&lastPage())for(int i=0;i<data.choices().size();i++){var choice=data.choices().get(i);int y=590-data.choices().size()*75+i*75;boolean hover=hit(mx,my,962,y,545,67);images.drawTexture(c,CHOICE,962,y,73,270,545,67,1952,210,2098,749,hover?0xFFFFFFFF:0xFFCBD5DD);label(c,choice.text(),1005,y+33,24,0xFFEEF1F5,false,false,465);}
            }
            label(c,turn!=null?turn.hint("SPACE"):waiting>0?"처리 중…":!complete()?"SPACE  바로 보기":!lastPage()?"SPACE  다음":"SPACE  "+(data.choices().isEmpty()?(externalMode&&external!=null&&!external.last()?"다음":"닫기"):"선택지 확인"),800,825,20,0xFFC1CEDD,false,true,420);
            if(!data.message().isEmpty())label(c,data.message(),800,874,19,0xFFE8D19B,false,true,1300);
            if(log){c.fill(200,130,1400,605,0xF30D192A);label(c,"대화 기록 · 클릭해서 닫기",245,166,25,0xFFE8D19B,true,false,1100);int from=Math.max(0,history.size()-1-historyPage);String entry=history.isEmpty()?"":history.get(from);int yy=212;for(String row:logRows(entry)){if(yy>540)break;label(c,row,245,yy,25,0xFFE5E8EC,false,false,1110);yy+=35;}label(c,"휠: 이전 / 다음 대화",245,572,19,0xFFB2C2D0,false,false,1000);}
        }finally{c.getMatrices().pop();images.endFrame();}super.render(c,mouseX,mouseY,delta);
    }
    private List<String> logRows(String entry){
        if(logGeneration!=UiResources.generation()||!entry.equals(logEntry)){logGeneration=UiResources.generation();logEntry=entry;logRows=UiResources.text().wrapText(entry,1090,25);}
        return logRows;
    }
    /** 외부 프레임의 초상화: 내 일러스트(요청했고 준비됐으면) → 파일. 둘 다 안 되면 false (MagicCodex 내장 초상화로). */
    private boolean drawExternalPortrait(DrawContext c){
        if(external.playerPortrait()&&PortraitClient.ready()){
            c.enableScissor(0,0,1600,PlayerTurnPortraitLayout.CLIP_BOTTOM);
            try{PortraitClient.drawTurn(c);}finally{c.disableScissor();}
            return true;
        }
        if(ExternalPortraits.draw(c,external.portraitFile()))return true;
        ExternalPortraits.request(external.portraitFile()); // 리소스를 다시 불러 텍스처가 내려갔거나 파일이 늦게 생긴 경우 (안에서 2초에 한 번만 확인)
        return false;
    }
    private void label(DrawContext c,String text,float x,float y,float size,int color,boolean bold,boolean centered,float max){var font=bold?BOLD:FONT;float actual=Math.min(size,size*max/Math.max(max,UiResources.text().width(text,size,font)));UiResources.text().draw(c,text,x,y,actual,color,font,centered);}
    private static boolean hit(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    private void advance(){if(turn!=null){if(turn.advance())finishTurn();return;}if(!complete()){shown=count();return;}if(!lastPage()){page++;restart();return;}if(data.choices().isEmpty()){if(externalMode)proceed();else close();}else if(data.choices().size()==1)select(data.choices().getFirst().id());}
    /** 외부 대화에서 선택지 없는 프레임을 넘김. 드라이버가 바로 다음 프레임을 주거나 닫는다. */
    private void proceed(){var d=driver;if(waiting!=0||d==null)return;int sequence=data.sequence();waiting=Util.getMeasuringTimeMs()+10000;d.choose(sequence,"");}
    private void select(String choice){if(waiting!=0||turn!=null)return;
        if(externalMode){var d=driver;if(d==null)return;int sequence=data.sequence();waiting=Util.getMeasuringTimeMs()+10000;beginTurn(choice);d.choose(sequence,choice);return;} // 내 차례를 먼저 시작해야 드라이버가 바로 준 다음 프레임이 그 뒤로 미뤄진다
        if(DialogueClient.send(data.session(),data.sequence(),choice,false)){waiting=Util.getMeasuringTimeMs()+10000;beginTurn(choice);}}
    /** 선택지가 2개 이상일 때만 ("계속" 같은 단일 진행 버튼은 내 대사로 보이지 않음). 내 일러스트가 없으면 시작하지 않음. */
    private void beginTurn(String choice){if(data.choices().size()<2)return;for(var ch:data.choices())if(ch.id().equals(choice)){turn=PlayerTurn.start(ch.text(),sound);if(turn!=null){heldResponse=null;heldExternal=null;heldClose=false;history.add(turn.speaker()+"\n"+turn.text());if(history.size()>80)history.removeFirst();}return;}}
    private void finishTurn(){turn=null;if(heldClose){heldClose=false;heldResponse=null;heldExternal=null;if(externalMode)externalClose();else serverClose();return;}var r=heldResponse;var x=heldExternal;heldResponse=null;heldExternal=null;if(r!=null){if(externalMode&&x!=null)external=x;receive(r);}}
    @Override public boolean shouldCloseOnEsc(){return closable();}
    @Override public boolean keyPressed(int key,int scan,int mods){
        // 닫을 수 없는 외부 대화: ESC 는 대화를 닫지 않고 바닐라 게임 메뉴를 연다 (언제든 접속을 끊을 수 있게). 메뉴가 닫히면 부른 쪽이 다시 보여 준다.
        if(key==GLFW.GLFW_KEY_ESCAPE&&!closable()){if(client!=null)client.setScreen(new GameMenuScreen(true));return true;}
        if(key==GLFW.GLFW_KEY_SPACE){if(!log)advance();return true;}if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_6&&turn==null&&complete()&&lastPage()&&!log){int index=key-GLFW.GLFW_KEY_1;if(index<data.choices().size())select(data.choices().get(index).id());return true;}return super.keyPressed(key,scan,mods);}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;var f=layoutFit();double mx=f.localX(x),my=f.localY(y);if(log){log=false;return true;}if(hit(mx,my,1090,24,180,60)){sound=!sound;return true;}if(hit(mx,my,1280,24,130,60)){shown=count();log=true;historyPage=0;return true;}if(closable()&&hit(mx,my,1420,24,140,60)){close();return true;}if(turn==null&&complete()&&lastPage())for(int i=0;i<data.choices().size();i++)if(hit(mx,my,962,590-data.choices().size()*75+i*75,545,67)){select(data.choices().get(i).id());return true;}if(hit(mx,my,60,639,1480,220)){advance();return true;}return super.mouseClicked(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){if(log){historyPage=Math.clamp(historyPage+(int)Math.signum(v),0,Math.max(0,history.size()-1));return true;}return super.mouseScrolled(x,y,h,v);}
    void serverClose(){if(turn!=null){heldClose=true;waiting=0;return;}closed=true;if(client!=null)client.setScreen(parent);}
    @Override public void removed(){
        super.removed();
        var d=driver;
        if(externalMode&&d!=null&&!driverClosed){removing=true;try{d.removed();}finally{removing=false;}}
    }
    @Override public void close(){
        if(externalMode){if(!closable())return;closed=true;if(client!=null)client.setScreen(parent);return;} // 외부 대화는 패킷을 보내지 않는다. 닫힘은 removed() 가 드라이버에 알린다
        if(!closed){closed=true;DialogueClient.send(data.session(),data.sequence(),"",true);}if(client!=null)client.setScreen(parent);}
}
