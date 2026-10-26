package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.npctalk.NpcTalkProtocol;
import net.minecraft.item.ItemStack;

/** Existing dialogue artwork/fonts, explicit direct-input mode and modal scrollable history. */
public final class NpcTalkScreen extends Screen {
 private static final Identifier FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
 private static final Identifier PANEL=asset("dialogue-panel"),NAME=asset("speaker-nameplate"),CHOICE=asset("choice-button");
 private static boolean sound=true;
 private record Line(String text,float[] ends){}
 private record Option(int number,String label,Runnable action){}
 private final Screen parent;private final String session;
 private String speaker,portraitName,text="",stallText="",message="";
 private int hearts,page,shown,historyScroll,inputOffset;private boolean inputEnabled,directInput,log,closed;
 private List<NpcTalkProtocol.Button> buttons=List.of();private List<Line> lines=List.of();
 private long start,lastSound,messageAt,closeAt;private String offerTitle,offerToken;
 private final NpcTalkFlow flow=new NpcTalkFlow();private final ConversationHistory history=new ConversationHistory();
 private char suppressedShortcut;private TextFieldWidget input;private Identifier portrait;private boolean portraitPresent;private int resourceGeneration=-1;
 // 내 차례 (버튼·직접 말하기 직후 내 일러스트 + 내 문장). 그동안 도착한 NPC 대사는 끝난 뒤 처음부터 타이핑, 작별(닫기)은 끝난 뒤 처리.
 private PlayerTurn turn;private boolean lineDuringTurn,heldClose;private String heldFarewell="";
 // 선물 창 (대화 버튼 3번): 서버가 보낸 칸·등급. null 이면 닫힘. 아이템 그림은 내 인벤토리 그 칸에서 그린다
 private List<NpcTalkProtocol.GiftSlot> gifts;private int giftPage;
 private static final int GIFT_COLS=6,GIFT_ROWS=2,GIFT_PER_PAGE=GIFT_COLS*GIFT_ROWS,GIFT_CELL=96,GIFT_GAP=18,GIFT_X=800-(GIFT_COLS*GIFT_CELL+(GIFT_COLS-1)*GIFT_GAP)/2,GIFT_Y=338;
 private static final int GOLD=0xFFC9A24B;private static final String[] GRADE_NAMES={"","일반","고급","유일","전설"};private static final int[] GRADE_COLORS={0,0xFF9AA3B2,0xFF5F9DEA,0xFFB184F0,0xFFE8A93C};
 public NpcTalkScreen(NpcTalkProtocol.Response open,Screen parent){super(Text.literal("NPC 대화"));this.parent=parent;session=open.session();apply(open);}
 private static Identifier asset(String name){return Identifier.of("magiccodex","textures/gui/dialogue/"+name+".png");}
 String session(){return session;}
 private void apply(NpcTalkProtocol.Response open){
  speaker=open.speaker();portraitName=open.portrait();hearts=open.hearts();buttons=open.buttons();inputEnabled=open.input();
  portrait=portraitName!=null&&portraitName.matches("[a-z0-9_/-]{1,120}")?asset(portraitName):null;resourceGeneration=-1;
  history.add("open:0",speaker,open.text());setText(open.text());
 }
 void reopen(NpcTalkProtocol.Response open){apply(open);}
 @Override protected void init(){String draft=input==null?"":input.getText();input=new TextFieldWidget(textRenderer,0,0,800,30,Text.literal("직접 말하기"));input.setMaxLength(NpcTalkProtocol.MAX_INPUT_CHARS*2);input.setText(draft);input.setFocused(directInput&&!log);}
 private void trace(String event,int sequence){NpcUiTrace.event(event,sequence,input!=null&&input.isFocused(),input==null?0:input.getText().codePointCount(0,input.getText().length()),flow.waiting());}
 void receive(NpcTalkProtocol.Response r){
  trace("response-op-"+r.op(),r.seq());
  switch(r.op()){
   case NpcTalkProtocol.S_THINKING->{if(flow.matches(r.seq()))stallText="";}
   case NpcTalkProtocol.S_STALL->{if(flow.matches(r.seq()))stallText=r.text();}
   case NpcTalkProtocol.S_LINE->{if(!flow.line(r.seq()))return;offerTitle=offerToken=null;history.add("npc:"+r.seq(),speaker,r.text());setText(r.text());if(turn!=null)lineDuringTurn=true;}
   case NpcTalkProtocol.S_QUEST_OFFER->{if(!flow.current(r.seq()))return;offerTitle=r.text();offerToken=r.token();directInput=false;if(input!=null)input.setFocused(false);}
   case NpcTalkProtocol.S_GIFTS->{if(!flow.line(r.seq()))return;gifts=NpcTalkProtocol.giftSlots(r.text());giftPage=0;directInput=false;if(input!=null)input.setFocused(false);}
   case NpcTalkProtocol.S_INFO->{if(r.seq()!=0&&!flow.current(r.seq()))return;flow.rejected(r.seq());stallText="";note(r.text());}
   case NpcTalkProtocol.S_CLOSE->{if(turn!=null){heldClose=true;heldFarewell=r.text()==null?"":r.text();}else serverClose(r.text());}
   default->{}
  }
 }
 void serverClose(String farewell){
  closed=true;directInput=false;offerTitle=offerToken=null;
  if(farewell==null||farewell.isEmpty()){if(client!=null)client.setScreen(parent);return;}
  log=false;history.add("farewell",speaker,farewell);setText(farewell);closeAt=Util.getMeasuringTimeMs()+(long)farewell.length()*23+1600;
 }
 private void setText(String value){text=value==null?"":value;stallText="";layout();page=shown=0;start=Util.getMeasuringTimeMs();}
 private void note(String value){message=value;messageAt=Util.getMeasuringTimeMs();}
 private boolean dispatch(int op,String value,boolean accept){
  if(closed||log||flow.waiting())return false;
  int seq=flow.begin(Util.getMeasuringTimeMs());trace("send-op-"+op,seq);
  if(seq==0)return false;
  if(!NpcTalkClient.send(session,op,seq,value,accept)){flow.rejected(seq);trace("send-failed",seq);note("NPC 대화 서버 연결이 없습니다. 다시 말을 걸어 주세요.");return false;}
  return true;
 }
 private void submit(){
  if(!directInput||!inputEnabled||input==null||closed||log||flow.waiting())return;
  String value=input.getText().strip();trace("submit",flow.pending());
  if(!NpcTalkProtocol.validInput(value)){note(value.isEmpty()?"할 말을 입력해 주세요.":"100자 이내의 문장으로 입력해 주세요.");return;}
  if(dispatch(NpcTalkProtocol.C_SAY,value,false)){history.add("player:"+flow.pending(),"나",value);input.setText("");directInput=false;input.setFocused(false);click();beginTurn(value);}
 }
 private void pressButton(NpcTalkProtocol.Button button){
  // 선물 버튼: 대사 대신 서버가 선물 창(S_GIFTS)이나 "지금은 괜찮아~" 대사로 대답한다
  if(NpcTalkProtocol.GIFT_BUTTON.equals(button.id())){if(dispatch(NpcTalkProtocol.C_BUTTON,button.id(),false))click();return;}
  if(dispatch(NpcTalkProtocol.C_BUTTON,button.id(),false)){history.add("player:"+flow.pending(),"나",button.label());click();beginTurn(button.label());}}
 private void beginTurn(String said){turn=PlayerTurn.start(said,sound);lineDuringTurn=heldClose=false;heldFarewell="";}
 private void finishTurn(){turn=null;if(heldClose){heldClose=false;serverClose(heldFarewell);return;}if(lineDuringTurn){lineDuringTurn=false;page=shown=0;start=Util.getMeasuringTimeMs();}}
 private void answerQuest(boolean accept){if(offerToken!=null&&dispatch(NpcTalkProtocol.C_QUEST,offerToken,accept)){history.add("player:"+flow.pending(),"나",accept?"부탁을 수락했어요.":"부탁을 거절했어요.");offerTitle=offerToken=null;click();beginTurn(accept?"부탁, 제가 해 볼게요.":"이번에는 어려울 것 같아요.");}}
 private void openInput(){
  if(!inputEnabled||closed||log||flow.waiting())return;
  shown=count();while(!lastPage()){page++;}shown=count();directInput=true;input.setFocused(true);trace("input-open",0);click();
 }
 private List<Option> options(){
  if(log||directInput||closed||turn!=null||flow.waiting()||gifts!=null)return List.of();
  if(offerToken!=null)return List.of(new Option(1,"부탁 수락: "+offerTitle,()->answerQuest(true)),new Option(2,"거절",()->answerQuest(false)));
  if(!complete()||!lastPage())return List.of();
  var out=new ArrayList<Option>();int n=1;
  for(var b:buttons)out.add(new Option(n++,b.label(),()->pressButton(b)));
  if(inputEnabled)out.add(new Option(4,"직접 말하기",this::openInput));return out;
 }
 private void layout(){
  var rows=new ArrayList<Line>();StringBuilder current=new StringBuilder();
  for(int cp:text.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(cp=='\n'||UiResources.text().width(current+ch,29,FONT)>1330){rows.add(line(current.toString()));current.setLength(0);if(cp=='\n')continue;}current.append(ch);}
  if(!current.isEmpty()||rows.isEmpty())rows.add(line(current.toString()));lines=List.copyOf(rows);
 }
 private Line line(String s){int[] cps=s.codePoints().toArray();float[] ends=new float[cps.length+1];StringBuilder prefix=new StringBuilder();for(int i=0;i<cps.length;i++){prefix.appendCodePoint(cps[i]);ends[i+1]=UiResources.text().width(prefix.toString(),29,FONT);}return new Line(s,ends);}
 private int count(){return lines.subList(page*3,Math.min(page*3+3,lines.size())).stream().mapToInt(l->l.ends.length-1).sum();}
 private boolean complete(){return shown>=count();}private boolean lastPage(){return (page+1)*3>=lines.size();}
 private CodexLayout fit(){float s=Math.min(width/1600f,height/900f);return new CodexLayout((width-1600*s)/2,(height-900*s)/2,s);}
 @Override public boolean shouldPause(){return false;}
 @Override public void renderBackground(DrawContext c,int x,int y,float delta){}
 @Override public void tick(){suppressedShortcut=0;long now=Util.getMeasuringTimeMs();if(closeAt>0&&now>closeAt){client.setScreen(parent);return;}if(flow.timeout(now)){stallText="";note("응답이 늦습니다. 연결 상태를 확인하고 다시 말을 걸어 주세요.");trace("response-timeout",0);}if(client!=null&&(client.player==null||client.world==null))close();}
 @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
  long now=Util.getMeasuringTimeMs();if(turn!=null){turn.tick(log);start=now-(long)shown*23;}int next=(int)Math.min(count(),Math.max(shown,(now-start)/23));
  if(!log&&next>shown){if(sound&&now-lastSound>=45){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.8f,.07f));lastSound=now;}shown=next;}
  if(resourceGeneration!=UiResources.generation()){resourceGeneration=UiResources.generation();portraitPresent=portrait!=null&&client.getResourceManager().getResource(portrait).isPresent();}
  var f=fit();double mx=f.localX(mouseX),my=f.localY(mouseY);var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();c.fill(0,0,width,height,0x30030A12);c.getMatrices().push();
  try{
   c.getMatrices().translate(f.x(),f.y(),0);c.getMatrices().scale(f.scale(),f.scale(),1);
   if(turn!=null)turn.drawPortrait(c);else{c.enableScissor(65,75,765,PlayerTurnPortraitLayout.CLIP_BOTTOM);try{if(portraitPresent)images.drawTexture(c,portrait,65,75,0,0,700,1050,0,0,1024,1536); // 0,0 = whole file (any portrait size)}finally{c.disableScissor();}}
   // The lower nameplate is the only NPC name. Vector hearts avoid font/placeholder glyphs.

   label(c,sound?"♪ 소리 켜짐":"♪ 소리 꺼짐",1110,54,21,0xFFD8DFE7,false,false,175);label(c,"기록",1305,54,21,0xFFD8DFE7,false,false,90);label(c,log?"기록 닫기 ×":"닫기 ×",1430,54,21,0xFFD8DFE7,false,false,130);
   if(log){drawHistory(c);return;}
   if(directInput&&inputEnabled){c.draw();c.getMatrices().push();c.getMatrices().translate(0,0,400);
    var dim=f.viewportBounds(width,height);c.fill(dim.left(),dim.top(),dim.right(),dim.bottom(),0xA0050A13);c.fill(420,300,1180,540,0xF3101D2C);c.drawBorder(420,300,760,240,0xFF647F96);label(c,"직접 말하기",454,338,28,0xFFE8D19B,true,false,560);FriendsScreen.cross(c,1130,334,hit(mx,my,1114,318,32,32)?0xFF92E7F2:0xFFF0F3F5);
    images.drawTexture(c,CHOICE,454,390,73,270,570,60,1952,210,2098,749,input.isFocused()?0xFFFFFFFF:0xFFB8C4D0);
    drawInput(c,now);images.drawTexture(c,CHOICE,1038,390,73,270,128,60,1952,210,2098,749,hit(mx,my,1038,390,128,60)?0xFFFFFFFF:0xFFCBD5DD);label(c,"전송",1102,420,22,0xFFF0F3F5,false,true,90);label(c,"Enter 전송 · Esc 입력 닫기",454,500,19,0xFFB8C4D0,false,false,650);c.draw();c.getMatrices().pop();
   }
   if(turn!=null)turn.drawPanel(c,images,470,225);else{
   images.drawTexture(c,PANEL,60,639,44,199,1480,220,2085,316,2172,724);images.drawTexture(c,NAME,86,615,200,244,470,54,1810,235,2172,724);label(c,speaker,131,642,23,0xFFE8D19B,true,false,225);c.draw();c.getMatrices().push();c.getMatrices().translate(0,0,300);for(int i=0;i<NpcTalkProtocol.MAX_HEARTS;i++)heart(c,376+i*27,632,i<Math.clamp(hearts,0,NpcTalkProtocol.MAX_HEARTS)?0xFFF19BBE:0xFF92979F);c.draw();c.getMatrices().pop();
   if(flow.waiting()&&!closed)label(c,stallText.isEmpty()?".".repeat((int)(now/350%3)+1):stallText,120,704,29,0xFFB8C4D0,false,false,1330);
   else{int remaining=shown;for(int i=page*3;i<Math.min(page*3+3,lines.size());i++){var row=lines.get(i);int n=Math.min(remaining,row.ends.length-1);remaining-=n;if(n>0){int y=704+(i-page*3)*43;boolean partial=n<row.ends.length-1;if(partial)c.enableScissor(115,y-24,120+(int)Math.ceil(row.ends[n])+1,y+26);label(c,row.text,120,y,29,0xFFF0F1F4,false,false,1330);if(partial)c.disableScissor();}}}
   }
   var opts=options();for(int i=0;i<opts.size();i++){int y=590-opts.size()*75+i*75;boolean hovered=hit(mx,my,962,y,545,67);images.drawTexture(c,CHOICE,962,y,73,270,545,67,1952,210,2098,749,hovered?0xFFFFFFFF:0xE8D5E1EE);String option=opts.get(i).number()+". "+opts.get(i).label();label(c,option,1006,y+34,24,0xE8000000,false,false,465);label(c,option,1005,y+33,24,hovered?0xFF92E7F2:0xFFEEF1F5,false,false,465);if(hovered)HudMesh.line(c,1005,y+53,1490,y+53,1,0x8092E7F2);}
   if(gifts!=null)drawGifts(c,mx,my,f);
   String hint=gifts!=null?"":turn!=null?turn.hint("Enter"):closed||directInput?"":flow.waiting()?"처리 중…":!complete()?"Enter  바로 보기":!lastPage()?"Enter  다음":"4  직접 말하기 · Esc  닫기";
   label(c,hint,800,825,20,0xFFC1CEDD,false,true,520);if(!message.isEmpty()&&now-messageAt<7000)label(c,message,800,874,19,0xFFE8D19B,false,true,1300);
  }finally{c.getMatrices().pop();images.endFrame();}
 }
 private ItemStack giftStack(int slot){return client==null||client.player==null?ItemStack.EMPTY:client.player.getInventory().getStack(slot);}
 private int giftPages(){return Math.max(1,(gifts.size()+GIFT_PER_PAGE-1)/GIFT_PER_PAGE);}
 private int[] giftCell(int i){int k=i%GIFT_PER_PAGE;return new int[]{GIFT_X+(k%GIFT_COLS)*(GIFT_CELL+GIFT_GAP),GIFT_Y+(k/GIFT_COLS)*(GIFT_CELL+GIFT_GAP)};}
 /** 선물 창: 어둡게 깐 위에 금색 실 테두리 창, 등급 색 칸, 아래 쪽수. */
 private void drawGifts(DrawContext c,double mx,double my,CodexLayout f){
  c.draw();c.getMatrices().push();c.getMatrices().translate(0,0,400);
  try{
   var dim=f.viewportBounds(width,height);c.fill(dim.left(),dim.top(),dim.right(),dim.bottom(),0xA0050A13);
   c.fill(380,250,1220,660,0xF2101823);c.drawBorder(380,250,840,410,GOLD);c.drawBorder(386,256,828,398,0x66C9A24B);
   label(c,"선물 고르기",800,292,28,0xFFE8D19B,true,true,500);FriendsScreen.cross(c,1180,282,hit(mx,my,1164,266,32,32)?0xFF92E7F2:0xFFF0F3F5);
   int from=giftPage*GIFT_PER_PAGE;String hovered="";int hoveredGrade=0;
   for(int i=from;i<Math.min(gifts.size(),from+GIFT_PER_PAGE);i++){
    var g=gifts.get(i);int[] at=giftCell(i);var stack=giftStack(g.slot());boolean over=hit(mx,my,at[0],at[1],GIFT_CELL,GIFT_CELL);
    c.fill(at[0],at[1],at[0]+GIFT_CELL,at[1]+GIFT_CELL,over?0x40FFFFFF:0x26FFFFFF);c.drawBorder(at[0],at[1],GIFT_CELL,GIFT_CELL,GRADE_COLORS[g.grade()]);
    if(!stack.isEmpty()){c.getMatrices().push();c.getMatrices().translate(at[0]+16,at[1]+16,0);c.getMatrices().scale(4,4,1);c.drawItem(stack,0,0);c.getMatrices().pop();if(stack.getCount()>1)label(c,String.valueOf(stack.getCount()),at[0]+GIFT_CELL-30,at[1]+GIFT_CELL-14,20,0xFFF0F1F4,true,false,60);}
    if(over&&!stack.isEmpty()){hovered=stack.getName().getString();hoveredGrade=g.grade();}
   }
   if(!hovered.isEmpty()){label(c,hovered,800,578,25,0xFFF0F1F4,true,true,700);label(c,GRADE_NAMES[hoveredGrade],800,606,20,GRADE_COLORS[hoveredGrade],false,true,200);}
   else label(c,"줄 선물을 눌러 주세요",800,590,21,0xFFB8C4D0,false,true,700);
   int pages=giftPages();
   if(pages>1){label(c,"‹",730,636,30,giftPage>0?(hit(mx,my,710,616,40,40)?0xFF92E7F2:0xFFE8D19B):0x55E8D19B,true,true,40);label(c,(giftPage+1)+" / "+pages,800,636,21,0xFFE8D19B,false,true,120);label(c,"›",870,636,30,giftPage<pages-1?(hit(mx,my,850,616,40,40)?0xFF92E7F2:0xFFE8D19B):0x55E8D19B,true,true,40);}
  }finally{c.draw();c.getMatrices().pop();}
 }
 private boolean giftClick(double mx,double my){
  if(hit(mx,my,1164,266,32,32)||!hit(mx,my,380,250,840,410)){gifts=null;click();return true;}
  int pages=giftPages();
  if(pages>1&&hit(mx,my,710,616,40,40)&&giftPage>0){giftPage--;click();return true;}
  if(pages>1&&hit(mx,my,850,616,40,40)&&giftPage<pages-1){giftPage++;click();return true;}
  int from=giftPage*GIFT_PER_PAGE;
  for(int i=from;i<Math.min(gifts.size(),from+GIFT_PER_PAGE);i++){int[] at=giftCell(i);if(hit(mx,my,at[0],at[1],GIFT_CELL,GIFT_CELL)){giveGift(gifts.get(i).slot());return true;}}
  return true;
 }
 /** 고른 칸 번호만 보낸다. 아이템·수량·받을 수 있는지는 서버가 다시 확인한다. */
 private void giveGift(int slot){
  var stack=giftStack(slot);if(stack.isEmpty())return;String name=stack.getName().getString();
  gifts=null;
  if(dispatch(NpcTalkProtocol.C_GIFT,String.valueOf(slot),false)){history.add("player:"+flow.pending(),"나",name+" (선물)");click();beginTurn("("+name+"을(를) 건넸다)");}
 }
 private void drawInput(DrawContext c,long now){
  String value=input.getText();if(value.isEmpty()){inputOffset=0;label(c,"할 말을 입력하세요",476,420,23,0xFF9FB0C0,false,false,395);if(input.isFocused()&&now/500%2==0)c.fill(476,406,478,434,0xFF91E8F5);return;}
  // Track the actual edit cursor so clipboard edits and arrows remain visible.
  int cursor=input.getCursor(),from=0;while(from<cursor&&UiResources.text().width(value.substring(from,cursor),23,FONT)>520)from++;
  int to=value.length();while(to>from&&UiResources.text().width(value.substring(from,to),23,FONT)>520)to--;
  inputOffset=from;int anchor=((school.magiccodex.mixin.TextFieldAccessor)input).magiccodex$selectionEnd();int lo=Math.max(from,Math.min(cursor,anchor)),hi=Math.min(to,Math.max(cursor,anchor));if(input.isFocused()&&lo<hi){int a=476+(int)UiResources.text().width(value.substring(from,lo),23,FONT),b=476+(int)UiResources.text().width(value.substring(from,hi),23,FONT);c.fill(a,404,b,436,0x8865A5C5);}label(c,value.substring(from,to),476,420,23,0xFFF0F1F4,false,false,520);
  if(input.isFocused()&&now/500%2==0){int x=476+(int)UiResources.text().width(value.substring(from,cursor),23,FONT);c.fill(x,406,x+2,434,0xFF91E8F5);}
 }
 private void drawHistory(DrawContext c){
  c.fill(240,130,1510,855,0xF30D192A);label(c,"대화 기록",274,168,27,0xFFE8D19B,true,false,1100);
  var rows=new ArrayList<String>();for(var e:history.entries()){rows.add(e.speaker());rows.addAll(UiResources.text().wrapText(e.text(),1150,25));rows.add("");}
  int visible=17,max=Math.max(0,rows.size()-visible);historyScroll=Math.clamp(historyScroll,0,max);
  c.enableScissor(270,198,1470,803);try{for(int i=historyScroll;i<Math.min(rows.size(),historyScroll+visible);i++)label(c,rows.get(i),274,218+(i-historyScroll)*34,25,0xFFE5E8EC,false,false,1150);}finally{c.disableScissor();}
  if(max>0){c.fill(1481,201,1484,795,0x4088AABB);int thumb=Math.max(24,594*visible/rows.size()),y=201+(594-thumb)*historyScroll/max;c.fill(1481,y,1484,y+thumb,0xFF9EC4CE);}
  label(c,"휠로 스크롤 · Esc 또는 기록 닫기",274,825,19,0xFFB2C2D0,false,false,1100);
 }
 private void heart(DrawContext c,float x,float y,int color){
  // Opaque scan-line icon avoids font availability and culled triangle-fan geometry.
  String[] rows={"01100110","11111111","11111111","11111111","01111110","00111100","00011000"};
  for(int row=0;row<rows.length;row++)for(int col=0;col<8;col++)if(rows[row].charAt(col)=='1')c.fill((int)x+col*3,(int)y+row*3,(int)x+col*3+3,(int)y+row*3+3,color);
 }
 private void label(DrawContext c,String value,float x,float y,float size,int color,boolean bold,boolean centered,float max){var font=bold?BOLD:FONT;float actual=Math.min(size,size*max/Math.max(max,UiResources.text().width(value,size,font)));UiResources.text().draw(c,value,x,y,actual,color,font,centered);}
 private static boolean hit(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
 private void click(){if(client!=null)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.2f,.12f));}
 private void advanceText(){if(turn!=null){if(turn.advance())finishTurn();return;}if(closed)return;if(!complete()){shown=count();return;}if(!lastPage()){page++;start=Util.getMeasuringTimeMs();shown=0;}}
 @Override public boolean keyPressed(int key,int scan,int mods){
  if(log){if(key==GLFW.GLFW_KEY_ESCAPE){log=false;input.setFocused(directInput);}return true;}
  if(gifts!=null){if(key==GLFW.GLFW_KEY_ESCAPE){gifts=null;click();}return true;}
  if(key==GLFW.GLFW_KEY_ESCAPE){if(directInput){directInput=false;input.setFocused(false);}else close();return true;}
  if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){if(directInput)submit();else advanceText();return true;}
  if(directInput){return input!=null&&input.isFocused()&&input.keyPressed(key,scan,mods)||super.keyPressed(key,scan,mods);}
  if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_4){int n=key-GLFW.GLFW_KEY_1+1;for(var o:options())if(o.number()==n){suppressedShortcut=(char)(48+n);o.action().run();return true;}}
  return super.keyPressed(key,scan,mods);
 }
 @Override public boolean charTyped(char ch,int mods){char suppressed=suppressedShortcut;suppressedShortcut=0;if(ch==suppressed)return true;if(log||closed||!directInput||!inputEnabled||input==null||!input.isFocused())return false;boolean typed=input.charTyped(ch,mods);trace("typed",0);return typed;}
 @Override public boolean mouseClicked(double x,double y,int button){
  if(button!=0)return false;var f=fit();double mx=f.localX(x),my=f.localY(y);
  if(log){if(hit(mx,my,1280,24,280,60)){log=false;input.setFocused(directInput);}return true;}
  if(gifts!=null)return giftClick(mx,my);
  if(directInput){if(hit(mx,my,1038,390,128,60)){submit();return true;}if(hit(mx,my,1114,318,32,32)){directInput=false;input.setFocused(false);click();return true;}boolean focused=hit(mx,my,454,390,570,60);input.setFocused(focused);if(focused){String value=input.getText();inputOffset=Math.min(inputOffset,value.length());int at=inputOffset;while(at<value.length()&&UiResources.text().width(value.substring(inputOffset,at+1),23,FONT)<mx-476)at++;input.setCursor(at,false);}trace("focus",0);return true;}
  if(hit(mx,my,1090,24,180,60)){sound=!sound;click();return true;}
  if(hit(mx,my,1280,24,130,60)){log=true;historyScroll=Integer.MAX_VALUE;if(input!=null)input.setFocused(false);trace("history-open",0);return true;}
  if(hit(mx,my,1420,24,140,60)){close();return true;}
  var opts=options();for(int i=0;i<opts.size();i++)if(hit(mx,my,962,590-opts.size()*75+i*75,545,67)){opts.get(i).action().run();return true;}
  if(hit(mx,my,60,639,1480,220))advanceText();return true;
 }
 @Override public boolean mouseScrolled(double x,double y,double h,double v){if(log){historyScroll=Math.max(0,historyScroll-(int)Math.signum(v)*3);return true;}if(directInput)return true;return super.mouseScrolled(x,y,h,v);}
 @Override public void close(){if(!closed){closed=true;NpcTalkClient.send(session,NpcTalkProtocol.C_CLOSE,0,"",false);}if(client!=null)client.setScreen(parent);}
}