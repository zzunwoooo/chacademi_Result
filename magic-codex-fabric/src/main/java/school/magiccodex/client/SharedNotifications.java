package school.magiccodex.client;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents; import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;import net.minecraft.util.*;
/** Reusable quest/friend/system notices. Discovery ACK and interactive friend requests stay separate. */
public final class SharedNotifications {
 private static final NotificationQueue QUEUE=new NotificationQueue(); private static final NotificationPresentation PRESENTATION=new NotificationPresentation(); private static boolean initialized; private static HudTextureCache images; private static CodexTypography text; private static int resourceGeneration=-1; private record Hit(String id,float x,float y,float w,float h){} private static final java.util.List<Hit> hits=new java.util.ArrayList<>();
private static boolean dialogue(net.minecraft.client.gui.screen.Screen s){return s instanceof DialogueScreen || s!=null&&s.getClass().getName().equals("kr.chacademy.cutscene.client.CutsceneScreen");}
static boolean click(double x,double y,int button){
        if(HudCustomization.editingGesture())return false;var hudPoint=HudCustomization.pointer("notifications",x,y);x=hudPoint[0];y=hudPoint[1];if(button!=0)return false;for(var h:java.util.List.copyOf(hits))if(x>=h.x&&x<h.x+h.w&&y>=h.y&&y<h.y+h.h){QUEUE.dismiss(h.id);hits.remove(h);return true;}return false;}
 private static final Identifier FONT=Identifier.of("magiccodex",NotificationPresentation.BODY_FACE),BOLD=Identifier.of("magiccodex",NotificationPresentation.TITLE_FACE);
 public static void offer(String id,String kind,String title,String body){if(kind==null||kind.isBlank())kind="system";enqueue(new NotificationQueue.Notice(id,kind,school.magiccodex.protocol.JournalProtocol.cut(title,140),school.magiccodex.protocol.JournalProtocol.cut(body,500),3500));}
 /** Item icons for notices (e.g. shop receipts), keyed by notice id; bounded like the queue. */
 private static final java.util.Map<String,net.minecraft.item.ItemStack> ICONS=new java.util.LinkedHashMap<>(){protected boolean removeEldestEntry(java.util.Map.Entry<String,net.minecraft.item.ItemStack> e){return size()>16;}};
 /** A "shop" notice whose left icon is the given item (its resource-pack icon, else the item's own model). */
 public static void item(String id,String title,String body,net.minecraft.item.ItemStack icon){var stack=icon==null?net.minecraft.item.ItemStack.EMPTY:icon.copy();MinecraftClient.getInstance().execute(()->{if(!stack.isEmpty())ICONS.put(id,stack);QUEUE.offerIfAbsent(new NotificationQueue.Notice(id,"shop",school.magiccodex.protocol.JournalProtocol.cut(title,140),school.magiccodex.protocol.JournalProtocol.cut(body,500),4500));});}
 public static void unavailable(){enqueue(new NotificationQueue.Notice("broom:unavailable","warning","사용할 수 없는 마법","빗자루에서 내린 뒤 사용할 수 있어요.",2800));}
 private static void enqueue(NotificationQueue.Notice notice){MinecraftClient.getInstance().execute(()->{
  var target=NotificationRouting.destination(notice.id(),notice.kind());
  if(target==NotificationRouting.Destination.DISCOVERY_ONLY)return;
  if(target==NotificationRouting.Destination.SYSTEM_CHAT){var c=MinecraftClient.getInstance();if(c.player!=null)c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(NotificationRouting.message(notice.title(),notice.body())));return;}
  QUEUE.offerIfAbsent(notice);
 });}
 private static NotificationPresentation.Surface surface(MinecraftClient c){return c.currentScreen==null?NotificationPresentation.Surface.WORLD:c.currentScreen instanceof HudCursorScreen||c.currentScreen instanceof ShopScreen||c.currentScreen instanceof MileageScreen||c.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen?NotificationPresentation.Surface.CURSOR:dialogue(c.currentScreen)?NotificationPresentation.Surface.DIALOGUE:NotificationPresentation.Surface.HIDDEN;}
 private static boolean visible(MinecraftClient c){return c.world!=null&&c.player!=null&&c.player.isAlive()&&!c.options.hudHidden&&c.getOverlay()==null&&surface(c)!=NotificationPresentation.Surface.HIDDEN;}
 private static void closeResources(){CARDS.clear();if(images!=null)images.close();if(text!=null)text.close();images=null;text=null;resourceGeneration=-1;}
 private static void prepare(MinecraftClient c){if(resourceGeneration!=UiResources.generation()){closeResources();resourceGeneration=UiResources.generation();}if(images==null)images=new HudTextureCache(c);if(text==null)text=new CodexTypography(c);}
 public static void initialize(){
  if(initialized)return;initialized=true;
  ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
   if(dialogue(screen)||screen instanceof HudCursorScreen||screen instanceof ShopScreen||screen instanceof MileageScreen||screen instanceof net.minecraft.client.gui.screen.ChatScreen){
    ScreenEvents.afterRender(screen).register((s,ctx,x,y,delta)->{if(client.currentScreen==s&&PRESENTATION.owns(ctx,true,surface(client)))render(ctx,client,visible(client),Util.getMeasuringTimeMs());});
    ScreenMouseEvents.allowMouseClick(screen).register((s,x,y,b)->!click(x,y,b));
   }
  });
  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c->{NotificationPresentation.tickSound(c,Util.getMeasuringTimeMs());if(!visible(c)){QUEUE.frame(Util.getMeasuringTimeMs(),false,0);hits.clear();}});
  ClientPlayConnectionEvents.DISCONNECT.register((h,c)->{QUEUE.clear();ICONS.clear();hits.clear();PRESENTATION.reset();closeResources();});
  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(c->closeResources());
  HudRenderCallback.EVENT.register((ctx,t)->{var c=MinecraftClient.getInstance();if(PRESENTATION.owns(ctx,false,surface(c)))render(ctx,c,visible(c),Util.getMeasuringTimeMs());});
 }
 /** 카드 한 장의 줄바꿈 결과 (카드 단위 1920x1080 기준). 긴 제목·본문은 자르지 않고 줄을 늘린다. */
 record Card(java.util.List<String> title,java.util.List<String> body,float height){}
 /** 1080p에서 카드 가로 약 640px (승인 패키지 기준 640x103에서 시작). 오른쪽 아래 기준점은 이전과 같다. */
 static final float CARD_SCALE=640f/560f;
 static final float W=560,TEXT_X=98,TEXT_RIGHT=W-50,TITLE_SIZE=22,BODY_SIZE=17,TITLE_LINE=28,BODY_LINE=23,MIN_H=96,SEAT_X=52,SEAT=66,ICON=40;
 private static final java.util.Map<NotificationQueue.Notice,Card> CARDS=new java.util.LinkedHashMap<>(){protected boolean removeEldestEntry(java.util.Map.Entry<NotificationQueue.Notice,Card> e){return size()>24;}};
 static String title(NotificationQueue.Notice n){return n.kind().equals("quest")?"새로운 퀘스트":n.title();}
 static String body(NotificationQueue.Notice n){return n.kind().equals("quest")?n.title():n.body();}
 /** 순수 배치: 측정 함수만 받아 단위 테스트가 가능하다. */
 static Card layout(String title,String body,java.util.function.ToDoubleFunction<String> titleWidth,java.util.function.ToDoubleFunction<String> bodyWidth){
  float room=TEXT_RIGHT-TEXT_X;var t=title==null||title.isBlank()?java.util.List.<String>of():CodexTextLayout.wrap(title.strip(),room,titleWidth);var b=body==null||body.isBlank()?java.util.List.<String>of():CodexTextLayout.wrap(body.strip(),room,bodyWidth);
  float h=22+Math.max(1,t.size())*TITLE_LINE+(b.isEmpty()?0:6+b.size()*BODY_LINE)+17;return new Card(t,b,Math.max(MIN_H,h));
 }
 private static Card card(NotificationQueue.Notice n){return CARDS.computeIfAbsent(n,k->layout(title(k),body(k),s->text.width(s,TITLE_SIZE,BOLD),s->text.width(s,BODY_SIZE,FONT)));}
 static void render(net.minecraft.client.gui.DrawContext ctx,MinecraftClient c,boolean show,long now){
  hits.clear(); int sw=c.getWindow().getScaledWidth(),sh=c.getWindow().getScaledHeight();float base=Math.min(sw/1920f,sh/1080f),scale=base*CARD_SCALE;float w=W*scale,gap=10*base,bottom=230*base*.9f;int capacity=(int)((sh-bottom-24*base)/(MIN_H*scale+gap));
  var notices=QUEUE.frame(now,show,capacity);if(notices.isEmpty())return;ctx.draw();prepare(c);images.beginFrame();text.beginFrame();
  var cards=new java.util.ArrayList<Card>();float total=0,top=24*base;
  for(var v:notices){var card=card(v.notice());float next=total+(cards.isEmpty()?0:gap)+card.height()*scale;if(!cards.isEmpty()&&sh-bottom-next<top)break;cards.add(card);total=next;}
        try(var customization=HudCustomization.begin(ctx,"notifications",sw-24*base*.9f-w,sh-bottom-total,w,total)){
  try{float stack=0;for(int i=0;i<cards.size();i++){var v=notices.get(i);var card=cards.get(i);float h=card.height()*scale;if(v.announce())NotificationPresentation.playSound(c,v.notice().kind(),now);float enter=Math.min(1,v.age()/260f),alpha=NotificationPresentation.alpha(v.age(),v.notice().duration());float x=sw-24*base*.9f-w+(1-enter)*(w+24*base),y=sh-bottom-stack-h;stack+=h+gap;
   if(alpha>.1f)hits.add(new Hit(v.notice().id(),x,y,w,h)); int tint=NotificationSkin.argb(alpha,0xFFFFFF);ctx.getMatrices().push();ctx.getMatrices().translate(x,y,400);ctx.getMatrices().scale(scale,scale,1);
   float ch=card.height();
   // 공용 바탕: PNG 자체 알파를 그대로 쓴다 (패널 전체를 더 투명하게 만들지 않음, 페이드 인/아웃만 곱한다).
   NotificationSkin.background(images,ctx,0,0,W,ch,tint);
   float cy=ch/2;NotificationSkin.seat(images,ctx,SEAT_X,cy,SEAT,tint);
   var itemIcon=ICONS.get(v.notice().id());
   if(itemIcon!=null){if(alpha>.35f){ctx.getMatrices().push();ctx.getMatrices().translate(SEAT_X-ICON/2,cy-ICON/2,10);ctx.getMatrices().scale(ICON/16f,ICON/16f,1);ctx.drawItem(itemIcon,0,0);ctx.getMatrices().pop();}}
   else NotificationSkin.icon(images,ctx,v.notice().kind(),SEAT_X,cy,ICON,tint);
   NotificationSkin.close(images,ctx,W-26,24,17,false,tint);
   float ty=card.body().isEmpty()?ch/2-(card.title().size()-1)*TITLE_LINE/2f:22+TITLE_LINE/2f;
   for(var line:card.title()){text.draw(ctx,line,TEXT_X,ty,TITLE_SIZE,NotificationSkin.argb(alpha,NotificationSkin.TITLE),BOLD,false);ty+=TITLE_LINE;}
   float by=22+Math.max(1,card.title().size())*TITLE_LINE+6+BODY_LINE/2f;
   for(var line:card.body()){text.draw(ctx,line,TEXT_X,by,BODY_SIZE,NotificationSkin.argb(alpha,NotificationSkin.BODY),FONT,false);by+=BODY_LINE;}
   ctx.getMatrices().pop();}
  }finally{ctx.draw();images.endFrame();}

        }
}
}
