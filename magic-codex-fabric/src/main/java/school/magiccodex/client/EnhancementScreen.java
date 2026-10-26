package school.magiccodex.client;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;import net.minecraft.client.gui.screen.Screen;import net.minecraft.client.sound.PositionedSoundInstance;import net.minecraft.sound.SoundEvent;import net.minecraft.text.Text;import net.minecraft.util.*;
import school.magiccodex.protocol.EnhancementProtocol;import school.magiccodex.protocol.EnhancementProtocol.Response;
import org.lwjgl.glfw.GLFW;
/** PNG shell and stat icons are separate from dynamic text and time-based circuit rendering. */
public final class EnhancementScreen extends Screen {
 private static final Identifier FONT=Identifier.of("magiccodex","hud_bold");
 private static Identifier asset(String n){return Identifier.of("magiccodex","textures/gui/enhancement/"+n+".png");}
 private static final Identifier PANEL=asset("panel"),CORE=asset("core-intact"),GLOW=asset("mana-glow"),BUTTON=asset("button"),TOOLTIP=asset("tooltip");
 private static final Identifier[] ICONS={asset("icon-power"),asset("icon-mana"),asset("icon-haste")};
 private final long[] flashes=new long[10];
 private Response data;private long start=-1,waiting=-1,ended=-1;private int sent;private float scale,left,top;private boolean heldSpace;
 public EnhancementScreen(Response r){super(Text.literal("마력 회로 강화"));data=r;if(r.state()==1)start=Util.getMeasuringTimeMs();}
 Response snapshot(){return data;}double elapsed(){return start<0?-1:Util.getMeasuringTimeMs()-start-data.lead();}
 public void receive(Response r){
  if(r.token()!=data.token()){start=-1;sent=0;ended=-1;java.util.Arrays.fill(flashes,0);}
  if(r.state()==1&&(data.state()!=1||r.token()!=data.token()))start=Util.getMeasuringTimeMs();
  int hits=r.hitMask()&~data.hitMask(),miss=r.missMask()&~data.missMask();
  if(hits!=0){for(int i=0;i<10;i++)if((hits&(1<<i))!=0)flashes[i]=Util.getMeasuringTimeMs();vanilla("block.amethyst_block.hit",.65f,.85f+Integer.bitCount(r.hitMask())*.055f);vanilla("block.amethyst_block.chime",.25f,1.1f);}else if(miss!=0)sound("appraisal.denied",.3f,1);
  if(r.state()!=data.state()&&(r.state()==2||r.state()==3)){ended=Util.getMeasuringTimeMs();if(r.state()==2){vanilla("block.beacon.activate",.45f,1.35f);vanilla("block.amethyst_cluster.break",.5f,1.1f);}else sound("light.ritual",.55f,1);}
  if(r.state()==4)sound("appraisal.denied",.4f,1);data=r;waiting=-1;
 }
 private void layout(){scale=.9f*Math.min(width/790f,height/530f);left=(width-768*scale)/2;top=(height-512*scale)/2;}
 private void text(DrawContext c,String s,int x,int y,int size,int color,boolean center){UiResources.text().draw(c,s,x,y,size,color,FONT,center);}
 private void fitText(DrawContext c,String value,int x,int y,int size,int maxWidth,int color,boolean right){float measured=UiResources.text().width(value,size,FONT);float actual=measured>maxWidth?size*maxWidth/measured:size;float w=UiResources.text().width(value,actual,FONT);UiResources.text().draw(c,value,right?x-w:x,y,actual,color,FONT,false);}
 private void sprite(DrawContext c,Identifier id,float x,float y,float w,float h,int tw,int th,int tint){c.getMatrices().push();try{c.getMatrices().translate(x,y,0);c.getMatrices().scale(w/100,h/100,1);UiResources.images().drawTexture(c,id,0,0,0,0,100,100,tw,th,tw,th,tint);c.draw();}finally{c.getMatrices().pop();}}
 private float[] node(int i){int n=data.nodes();if(n==1)return new float[]{500,200};if(n<=5)return new float[]{325+i*350f/(n-1),195+(i%2==0?-12:12)};int row=i/5,col=i%5;return new float[]{325+(row==0?col:4-col)*86,170+row*84+(col%2)*8};}
 private Identifier icon(int i){return i==2&&data.kind()==EnhancementProtocol.ACCESSORY?ICONS[1]:ICONS[i];}
 private String num(double v){return String.format(Locale.ROOT,v==Math.rint(v)?"%.0f":"%.1f",v);}
 private boolean hit(double x,double y,float a,float b,float w,float h){return x>=a&&x<=a+w&&y>=b&&y<=b+h;}
 @Override public void render(DrawContext c,int mx,int my,float delta){layout();double x=(mx-left)/scale,y=(my-top)/scale,t=Util.getMeasuringTimeMs()/1000d;var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();c.fill(0,0,width,height,0x60040B14);c.getMatrices().push();
 try{c.getMatrices().translate(left,top,60);c.getMatrices().scale(scale,scale,1);
  sprite(c,PANEL,0,0,768,512,1536,1024,0xF5FFFFFF);
  for(int i=0;i<28;i++){float sx=295+(float)((i*.618+t*.007)%1)*405,sy=100+(float)((i*.279+t*.004)%1)*210;HudMesh.disk(c,sx,sy,.6f,((int)(45+22*Math.sin(t+i))<<24)|0x94CEE2);}
  text(c,"마력 회로 강화",384,32,23,0xFFE9D19B,true);text(c,"×",711,33,22,0xFFE9D19B,true);
  text(c,"마력코어",76,84,14,0xFFE9D19B,false);text(c,"마력 회로",297,84,14,0xFFE9D19B,false);text(c,"장비 미리보기",76,267,14,0xFFE9D19B,false);
  float pulse=(float)(.5+.5*Math.sin(t*1.8));
  sprite(c,GLOW,96-pulse*5,87-pulse*5,140+pulse*10,132+pulse*10,1254,1254,((int)(48+42*pulse)<<24)|0x92CFF5);
  // Soft rim behind the core: layered, continuous breathing light, no new textures per frame.
  for(int ring=0;ring<4;ring++){int alpha=(int)((13+19*pulse)*(1-ring*.2f));HudMesh.arc(c,166,153,43+ring*1.7f+pulse*2,2.2f,0,(float)(Math.PI*2),(alpha<<24)|0xB3AFF7);}
  sprite(c,CORE,102,87,128,128,1254,1254,0xFFFFFFFF);
  text(c,data.nodes()+"코어",166,220,13,0xFFDFE9F0,true);text(c,"‹",82,153,21,0xFFE1C88E,true);text(c,"›",251,153,21,0xFFE1C88E,true);
  // 장비 미리보기 (오너 2026-10-26: 강화 대상 아이템 표시). 서버는 주 손 아이템(마법봉·장신구)을 강화하므로 주 손을 그린다.
  // 72 크기 칸에 고해상도 아이콘을 그 크기로 새로 그림(ExactItemIcons), 없으면 일반 아이템 그리기 — 재구성 창과 같은 규칙.
  if(client!=null&&client.player!=null){var held=client.player.getMainHandStack();if(!held.isEmpty()){c.draw();c.getMatrices().push();try{c.getMatrices().translate(0,0,10);ReconfigurationScreen.drawStack(c,held,166,318,72);c.draw();}finally{c.getMatrices().pop();}}}
  fitText(c,data.name(),80,365,13,172,0xFFE9D19B,false);text(c,"강화 횟수 "+data.attempts()+" / "+data.limit(),165,391,12,0xFFCCD8DF,true);
  // 장신구 강화(kind 1)는 세 번째 줄이 초당 마나 회복 — 새 그림 없이 마나 아이콘을 같이 씀.
  boolean accessory=data.kind()==EnhancementProtocol.ACCESSORY;
  String[] labels=accessory?new String[]{"지능","마나 최대치","초당 마나 회복"}:new String[]{"지능","마나 최대치","마법 가속"};for(int i=0;i<3;i++){int sy=412+i*23;sprite(c,icon(i),73,sy-7,24,24,1254,1254,0xFFFFFFFF);text(c,labels[i],99,sy+4,11,0xFFDEE6EF,false);fitText(c,num(data.before().get(i))+" → "+num(data.before().get(i)+data.gains().get(i)),259,sy+4,11,90,0xFF8EE5EB,true);}
  double elapsed=elapsed();int active=0;while(active<data.nodes()&&elapsed>data.targets().get(active)+data.window())active++;
  int travel=0;while(travel<data.nodes()-1&&elapsed>data.targets().get(travel))travel++;

  for(int i=0;i<data.nodes()-1;i++){float[] a=node(i),b=node(i+1);HudMesh.line(c,a[0],a[1],b[0],b[1],1.4f,0xFF5E829E);}
  for(int i=0;i<data.nodes();i++){float[] a=node(i);int mask=1<<i;boolean ok=(data.hitMask()&mask)!=0,bad=(data.missMask()&mask)!=0;int color=bad?0xFFBD7782:ok?0xFF8BEFF3:0xFF91AECB;
   for(int side=0;side<4;side++){double angle=side*Math.PI/2;HudMesh.line(c,a[0]+(float)Math.cos(angle)*15,a[1]+(float)Math.sin(angle)*15,a[0]+(float)Math.cos(angle+Math.PI/2)*15,a[1]+(float)Math.sin(angle+Math.PI/2)*15,ok?2:1,color);}
   text(c,ok?"✓":bad?"×":String.valueOf(i+1),Math.round(a[0]),Math.round(a[1]),14,color,true);
  }
  if(data.state()==1&&active<data.nodes()){
   float[] a=node(active);int target=data.targets().get(active),prev=active==0?0:data.targets().get(active-1);float progress=(float)Math.clamp((elapsed-prev)/(target-prev),0,1);
   boolean inWindow=school.magiccodex.protocol.EnhancementTiming.inWindow(elapsed,target,data.window());
   float radius=school.magiccodex.protocol.EnhancementTiming.radius(elapsed,prev,target);
   HudMesh.arc(c,a[0],a[1],15,1,0,(float)(Math.PI*2),inWindow?0xFF8FF5EF:0x886D9CAF);
   HudMesh.arc(c,a[0],a[1],radius,inWindow?2:1,0,(float)(Math.PI*2),inWindow?0xFF8FF5EF:0xFFE7CE8B);
   float[] destination=node(travel),from=travel==0?new float[]{destination[0]-35,destination[1]}:node(travel-1);
   int travelPrev=travel==0?0:data.targets().get(travel-1);float journey=(float)Math.clamp((elapsed-travelPrev)/(data.targets().get(travel)-travelPrev),0,1);
   float ox=from[0]+(destination[0]-from[0])*journey,oy=from[1]+(destination[1]-from[1])*journey;
   sprite(c,GLOW,ox-18,oy-18,36,36,1254,1254,0xB0FFFFFF);HudMesh.disk(c,ox,oy,3,0xFFE2FBFF);
  }
  for(int i=0;i<data.nodes();i++){long age=Util.getMeasuringTimeMs()-flashes[i];if(flashes[i]>0&&age<550){float u=age/550f,fade=(1-u)*(1-u);float[] pos=node(i);int color=((int)(255*fade)<<24)|0xB6FFFF;HudMesh.arc(c,pos[0],pos[1],15+42*u,2*(1-u),0,(float)(Math.PI*2),color);HudMesh.star(c,pos[0],pos[1],24*fade,color);for(int j=0;j<12;j++){double angle=j*Math.PI/6+i;float dx=(float)Math.cos(angle),dy=(float)Math.sin(angle),dist=18+58*u;HudMesh.line(c,pos[0]+dx*dist,pos[1]+dy*dist,pos[0]+dx*(dist+10*fade),pos[1]+dy*(dist+10*fade),1.5f,color);}}}
  text(c,data.state()==1?(elapsed<0?"회로 준비 중…":"구슬이 표식에 닿으면 SPACE"):"SPACE · 회로 스타캐치",497,312,13,0xFFE0E4E8,true);
  text(c,"기본 확률",343,361,12,0xFFDAE3EC,true);text(c,num(data.base()*100)+"%",343,382,22,0xFFF5EFE4,true);text(c,"모두 성공 시",650,361,12,0xFFDAE3EC,true);text(c,num(Math.min(1,data.base()+data.bonus())*100)+"%",650,382,22,0xFF8CEBEF,true);
  for(int i=0;i<data.nodes();i++){int sx=Math.round(497+(i-(data.nodes()-1)/2f)*10);HudMesh.star(c,sx,366,3,(data.hitMask()&(1<<i))!=0?0xFF8FF2F2:(data.missMask()&(1<<i))!=0?0xFFC18189:0xFF7993AE);}
  text(c,num(data.cost())+" G",497,394,13,0xFFE9D19B,true);
  sprite(c,BUTTON,369,391,259,83,2172,724,waiting>=0?0x99FFFFFF:0xFFFFFFFF);
  String caption=data.state()==1?"강화 진행 중…":data.state()>=2?"확인":waiting>=0?"요청 중…":"강화 시작";text(c,caption,498,432,17,0xFFF0E6D3,true);
  String notice=data.message().isBlank()?"하나라도 실패하면 기본 확률 적용":data.message();text(c,notice,497,467,11,data.state()==3||data.state()==4?0xFFE8A9AA:0xFFCCD6DF,true);
  if(ended>=0&&Util.getMeasuringTimeMs()-ended<850){float u=(Util.getMeasuringTimeMs()-ended)/850f;float sz=90+160*u;sprite(c,GLOW,497-sz/2,196-sz/2,sz,sz,1254,1254,((int)((1-u)*190)<<24)|0xFFFFFF);text(c,data.state()==2?"강화 성공":"강화 실패",497,277,22,data.state()==2?0xFFECD697:0xFFD6A5B2,true);}
  if(hit(x,y,98,93,140,140)&&data.state()!=1){
   c.draw();c.getMatrices().push();try{c.getMatrices().translate(0,0,40);
    c.fill(207,130,343,276,0xFA0A1825);sprite(c,TOOLTIP,177,103,196,198,1122,1402,0xFFFFFFFF);
    text(c,"마력코어 · "+data.nodes()+"코어",275,143,12,0xFFE9D19B,true);
    HudMesh.line(c,214,156,336,156,.6f,0x807D8D98);
    for(int i=0;i<3;i++){int row=177+i*30;sprite(c,icon(i),208,row-12,24,24,1254,1254,0xFFFFFFFF);text(c,data.kind()==EnhancementProtocol.ACCESSORY?school.magiccodex.protocol.CoreAffinity.accessoryLabel(i,data.affinity()):school.magiccodex.protocol.CoreAffinity.label(i,data.affinity()),236,row,11,0xFFDDE8EE,false);}
    c.draw();
   }finally{c.getMatrices().pop();}
  }
  c.draw();
 }finally{c.getMatrices().pop();images.endFrame();}}
 private void vanilla(String n,float volume,float pitch){if(client!=null)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("minecraft",n)),pitch,volume));}
 private void sound(String n,float volume,float pitch){if(client!=null)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex",n)),pitch,volume));}
 @Override public boolean mouseClicked(double mx,double my,int button){if(button!=0)return false;layout();double x=(mx-left)/scale,y=(my-top)/scale;if(hit(x,y,696,19,30,28)){close();return true;}
  if(data.state()==0&&waiting<0&&(hit(x,y,68,133,26,42)||hit(x,y,238,133,26,42))){waiting=Util.getMeasuringTimeMs();EnhancementClient.send(data.token(),EnhancementProtocol.NEXT,0);return true;}
  if(hit(x,y,393,413,210,34)&&data.state()!=1&&waiting<0){if(data.state()>=2){close();return true;}waiting=Util.getMeasuringTimeMs();sound("appraisal.start",.4f,1);EnhancementClient.send(data.token(),EnhancementProtocol.START,0);return true;}return false;
 }
 @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_SPACE){if(!heldSpace&&data.state()==1){heldSpace=true;int i=0;while(i<data.nodes()&&elapsed()>data.targets().get(i)+data.window())i++;if(i<data.nodes()&&((sent|data.hitMask()|data.missMask())&(1<<i))==0){sent|=1<<i;EnhancementClient.send(data.token(),EnhancementProtocol.HIT,i);}}return true;}return super.keyPressed(key,scan,modifiers);}
 @Override public boolean keyReleased(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_SPACE){heldSpace=false;return true;}return super.keyReleased(key,scan,modifiers);}
 @Override public void tick(){if(waiting>=0&&Util.getMeasuringTimeMs()-waiting>7000)waiting=-1;}
 @Override public void close(){EnhancementClient.closed(data.token());super.close();}
 @Override public boolean shouldPause(){return false;}
}
