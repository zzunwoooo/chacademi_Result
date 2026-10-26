package school.magiccodex.client;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.AppraisalProtocol.Response;

/** Server results are committed before the local rune sequence begins. */
public final class AppraisalScreen extends Screen {
 private static final Identifier FONT=Identifier.of("magiccodex","hud_bold");
 private static final Identifier PANEL=asset("nebula-background"),BUTTON=asset("button"),CORE=asset("core-intact"),BROKEN=asset("core-broken"),RUNE=asset("rune-cyan"),GLOW=asset("mana-glow");
 private static final double STEP=AppraisalTimeline.STEP;
 private Response data;private long start=-1,waiting=-1;private int sounded=-1;private float scale,left,top;
 public AppraisalScreen(Response r){super(Text.literal("마력코어 감정"));data=r;if(r.result()&&!r.repaired())start=Util.getMeasuringTimeMs();}
 Response snapshot(){return data;} double animationAge(){return age();}
 public long token(){return data.token();}
 public void receive(Response r){if(!r.message().isEmpty())sound("appraisal.denied",.45f,1f);data=r;waiting=-1;sounded=-1;if(r.result()&&!r.repaired())start=Util.getMeasuringTimeMs();}
 private static Identifier asset(String n){return Identifier.of("magiccodex","textures/gui/appraisal/"+n+".png");}
 private void layout(){scale=Math.min(1,Math.min(width/370f,height/490f));left=(width-320*scale)/2;top=(height-460*scale)/2;}
 private double age(){return start<0?-1:(Util.getMeasuringTimeMs()-start)/1000.0;}
 private boolean animating(){return age()>=0&&age()<AppraisalTimeline.end(data.nodes());}
 private void label(DrawContext c,String s,int x,int y,int size,int color){UiResources.text().draw(c,s,x,y,size,color,FONT,true);}
 private void sprite(DrawContext c,Identifier id,int x,int y,int w,int h,int tw,int th){UiResources.images().drawTexture(c,id,x,y,0,0,w,h,tw,th,tw,th);}
 @Override public void render(DrawContext c,int mx,int my,float delta){
  layout();var images=UiResources.images();var text=UiResources.text();images.beginFrame();text.beginFrame();images.prepareRegion(BROKEN,0,0,1254,1254);
  c.fill(0,0,width,height,0x65070F1B);c.getMatrices().push();
  try{
   c.getMatrices().translate(left,top,60);c.getMatrices().scale(scale,scale,1);
   floating(c,PANEL,-30,-35,380,530,1024,1536,0xF0FFFFFF);ambient(c);label(c,"마력코어 감정",160,39,19,0xFFEBD19F);label(c,"×",285,34,20,0xFFEBD19F);
   double age=age();boolean playing=animating();int shown=playing?AppraisalTimeline.shown(age,data.nodes()):data.nodes();
   HudMesh.arc(c,160,169,78,.6f,0,(float)(Math.PI*2),0x909C9384);
   for(int i=1;i<=10;i++){
    double a=-Math.PI/2+(i-1)*Math.PI/5;float x=160+(float)Math.cos(a)*78,y=169+(float)Math.sin(a)*78;
    boolean lit=i<=shown;HudMesh.arc(c,x,y,9,lit?1.1f:.65f,0,(float)(Math.PI*2),i==4||i==7||i==10?0xFFDFC47F:lit?0xFF80E9EF:0x607F98A6);
    if(lit)sprite(c,RUNE,Math.round(x)-9,Math.round(y)-10,18,20,1254,1254);
   }
   boolean broken=data.state()==2&&!playing;
   double breath=.5+.5*Math.sin(Util.getMeasuringTimeMs()/1100.0);
   float glowSize=(float)(114+18*breath);
   floating(c,GLOW,160-glowSize/2,169-glowSize/2,glowSize,glowSize,1254,1254,((int)(65+45*breath)<<24)|0xFFFFFF);
   float blend=data.state()==2?(start<0?1:AppraisalTimeline.brokenBlend(age,data.nodes())):0;
   if(blend<1)floating(c,CORE,85,87,150,164,1254,1254,((int)((1-blend)*255)<<24)|0xFFFFFF);
   if(blend>0)floating(c,BROKEN,85,87,150,164,1254,1254,((int)(blend*255)<<24)|0xFFFFFF);
   if(playing&&AppraisalTimeline.flying(age,data.nodes())){
    float p=(float)((age%STEP)/STEP),x=260-100*p,y=85+84*p-(float)Math.sin(p*Math.PI)*28;
    for(int j=1;j<=8;j++)HudMesh.disk(c,x+j*3,y-j*2,1.5f,((60-j*6)<<24)|0x74DEEA);
    floating(c,RUNE,x-12,y-14,24,28,1254,1254,0xFFFFFFFF);
    if(shown>0){float pulse=1-p;HudMesh.arc(c,160,169,25+28*p,1.2f,0,(float)(Math.PI*2),((int)(pulse*100)<<24)|0x9DEEF3);}
   }
   if(playing&&shown>0){
    float elapsed=(float)(age-shown*STEP),fade=Math.max(0,1-elapsed/.38f);
    boolean gate=shown==4||shown==7||shown==10;
    if(fade>0){float size=90+elapsed*(gate?250:180);
     floating(c,GLOW,160-size/2,169-size/2,size,size,1254,1254,((int)(fade*(gate?210:145))<<24)|0xFFFFFF);
     HudMesh.star(c,160,169,fade*(gate?17:10),((int)(fade*210)<<24)|0xD9F9FF);
     HudMesh.arc(c,160,169,28+elapsed*120,1.5f,0,(float)(Math.PI*2),((int)(fade*170)<<24)|0x9FECF4);
    }
   }
   if(start>=0){int phase=playing?shown:11;if(phase!=sounded){sounded=phase;
    if(phase>0&&phase<11){vanilla("block.amethyst_block.hit",.5f,.85f+phase*.06f);vanilla("block.amethyst_block.chime",.22f,1f+phase*.025f);if(phase==4||phase==7||phase==10)vanilla("block.beacon.activate",.4f,phase==4?1.6f:phase==7?1.25f:.95f);}
    else if(phase==11){if(data.nodes()<10)sound(broken?"dark.gate":"light.ritual",.5f,broken?.7f:.9f);if(data.nodes()==10){vanilla("block.beacon.activate",.45f,1.2f);vanilla("block.amethyst_block.chime",.55f,.85f);}else sound("appraisal.finish",.5f,broken?.8f:1f);}
   }}
   double finishAge=age-AppraisalTimeline.end(data.nodes());
   if(start>=0&&!playing&&finishAge>=0&&finishAge<.85){
    float u=(float)(finishAge/.85),fade=1-u,size=95+210*u;
    int color=broken?0xC5ACF2:0xC8F9FF;
    floating(c,GLOW,160-size/2,169-size/2,size,size,1254,1254,((int)(fade*220)<<24)|0xFFFFFF);
    HudMesh.arc(c,160,169,30+100*u,2*fade+.4f,0,(float)(Math.PI*2),((int)(fade*210)<<24)|color);
    for(int i=0;i<20;i++){double a=i*Math.PI/10+.13;float radius=22+u*(65+i%4*9);HudMesh.star(c,160+(float)Math.cos(a)*radius,169+(float)Math.sin(a)*radius,fade*(i%3+1),((int)(fade*220)<<24)|color);}
   }
   String status=playing?"코어 형성 중… "+shown+"/10":broken?"코어 파괴":data.state()==0?"미감정 마력코어":"감정 종료 · "+shown+"/10";
   label(c,status,160,274,17,broken?0xFFD2B6F0:0xFFE7EDF2);
   if(playing&&shown<10&&AppraisalTimeline.flying(age,data.nodes())){
    String probability=data.chances().size()==10?String.format(java.util.Locale.ROOT,"%.1f%%",data.chances().get(shown)*100):"—";
    label(c,(shown+1)+"번째 코어 · 성공 확률 "+probability,160,299,12,0xFFE8CC81);
   }
   HudMesh.line(c,40,317,154,317,.7f,0xB0C1A875);HudMesh.line(c,166,317,280,317,.7f,0xB0C1A875);HudMesh.star(c,160,317,3,0xFFE0C78E);
   if(!data.message().isEmpty()){
    String msg=data.message();int cut=Math.min(21,msg.length());label(c,msg.substring(0,cut),160,328,11,0xFFF2B5A4);if(cut<msg.length())label(c,msg.substring(cut),160,344,11,0xFFF2B5A4);
   }else if(playing){if(shown>=4)label(c,(shown>=10?10:shown>=7?7:4)+"단계 돌파",160,336,15,0xFFE8CC81);}
   else if(data.result())label(c,broken?"파괴된 코어는 사용할 수 없습니다.":shown==10?"모든 룬이 코어에 융합되었습니다.":"형성이 멈췄습니다.",160,336,11,0xFFB5C5D1);
   else if(data.state()==0){label(c,"감정 비용"+"  "+String.format(java.util.Locale.ROOT,"%,.0f G",data.cost()),160,331,15,0xFFEBD19F);label(c,data.balance()<0?"보유 금액 —":"보유 금액  "+String.format(java.util.Locale.ROOT,"%,.0f G",data.balance()),160,352,11,0xFFB5C5D1);}
   sprite(c,BUTTON,37,352,246,82,2172,724);
   String button=playing?"감정 중…":waiting>=0?"확인 중…":data.result()||data.state()!=0||!data.message().isEmpty()?"확인":"감정하기";
   label(c,button,160,392,17,0xFFE7D6A9);c.draw();
  }finally{c.getMatrices().pop();images.endFrame();}
 }
 /** Fixed-size procedural field: no entities, allocations or image frames per star. */
 private void ambient(DrawContext c){
  double t=Util.getMeasuringTimeMs()/1000.0;
  floating(c,PANEL,-38+(float)Math.sin(t*.1)*5,-42+(float)Math.cos(t*.12)*5,396,548,1024,1536,0x24FFFFFF);
  for(int i=0;i<7;i++){
   float x=(float)(145+52*Math.sin(t*.075+i*1.7)),y=(float)(160+44*Math.cos(t*.09+i*2.1));
   floating(c,GLOW,x-66,y-45,132,90,1254,1254,0x127CBADA);
  }
  for(int i=0;i<64;i++){
   double u=(i*.61803398875+t*.012)%1;
   float x=35+(float)u*250,y=90+(float)((i*.381966+t*.008)%1)*155;
   y+=7*(float)Math.sin(t*.24+i);
   int alpha=(int)(30+48*(.5+.5*Math.sin(t*.7+i*2.3)));
   HudMesh.disk(c,x,y,i%9==0?.85f:.45f,(alpha<<24)|0xB3DEEF);
   if(i%16==0)HudMesh.star(c,x,y,1.8f,(alpha<<24)|0xC9E7F6);
  }
 }
 private void floating(DrawContext c,Identifier id,float x,float y,float w,float h,int tw,int th,int tint){
  c.getMatrices().push();try{c.getMatrices().translate(x,y,0);c.getMatrices().scale(w/100,h/100,1);
   UiResources.images().drawTexture(c,id,0,0,0,0,100,100,tw,th,tw,th,tint);c.draw();
  }finally{c.getMatrices().pop();}
 }
 private void vanilla(String n,float v,float pitch){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("minecraft",n)),pitch,v));}
 private void sound(String n,float v,float pitch){client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex",n)),pitch,v));}
 @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;layout();double mx=(x-left)/scale,my=(y-top)/scale;if(mx>272&&mx<300&&my>20&&my<48){close();return true;}if(mx>=45&&mx<=275&&my>=373&&my<=412&&!animating()&&(start<0||age()>AppraisalTimeline.end(data.nodes())+.85)){
  if(data.result()||data.state()!=0||!data.message().isEmpty()){close();return true;}if(waiting<0){sound("appraisal.start",.4f,1f);waiting=Util.getMeasuringTimeMs();AppraisalClient.claim(data.token());}return true;}return false;}
 @Override public void tick(){if(waiting>=0&&Util.getMeasuringTimeMs()-waiting>8000){waiting=-1;data=new Response(data.token(),data.state(),data.nodes(),false,false,data.cost(),data.balance(),data.materials(),"응답 지연: 닫은 뒤 다시 확인해 주세요.");}}
 @Override public boolean shouldPause(){return false;}
}
