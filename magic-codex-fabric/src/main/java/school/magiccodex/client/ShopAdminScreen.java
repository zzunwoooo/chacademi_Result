package school.magiccodex.client;
import java.io.ByteArrayInputStream;import java.util.*;
import net.minecraft.client.gui.DrawContext;import net.minecraft.item.ItemStack;import net.minecraft.nbt.*;import net.minecraft.registry.Registries;
import school.magiccodex.protocol.ShopAdminProtocol;import school.magiccodex.protocol.ShopAdminProtocol.*;
/** Operator-only catalogue editor. Existing typography/widgets, no replacement of the player shop. 등록은 내 인벤토리에서 칸을 골라서. */
public final class ShopAdminScreen extends SocialScreen {
 private Response data;private Input search,name,buy,sell,category,sort;private boolean buyAllowed=true,sellAllowed=false,creating=true;
 private String selected="";private int scroll;private final Deque<Integer> previous=new ArrayDeque<>();
 private Request confirmation;private Map<String,ItemStack> icons=Map.of();
 /** 인벤토리 고르기 창: picking=열림. pickSlot=고른 칸(0~35, 0~8 핫바), pickItem=그 칸 아이템 ID(서버가 다시 확인). */
 private boolean picking;private int pickSlot=-1;private String pickItem="";private ItemStack pickStack=ItemStack.EMPTY;
 private static final int SLOT=76,GAP=8,GRID_X=296;
 ShopAdminScreen(){super("상점 물품 관리");}
 @Override SocialLayout.Fit fit(){float s=Math.min(width*.86f/1340,height*.86f/900);return new SocialLayout.Fit((width-1340*s)/2,(height-900*s)/2,s);}
 @Override protected void init(){super.init();search=new Input("상품 검색",80,search==null?"":search.text());name=new Input("상품명",120,name==null?"":name.text());buy=new Input("구매 가격",32,buy==null?"0":buy.text());sell=new Input("판매 가격",32,sell==null?"0":sell.text());category=new Input("분류",40,category==null?"":category.text());sort=new Input("순서",5,sort==null?"0":sort.text());}
 private List<Input> inputs(){return List.of(search,name,category,sort,buy,sell);}
 void receive(Response r){long before=data==null?0:data.revision();data=r;busy=false;confirmation=null;notice(r.message());var decoded=new HashMap<String,ItemStack>();for(var p:r.products())decoded.put(p.id(),decode(p.preview()));icons=Map.copyOf(decoded);scroll=0;
  if(!creating&&r.products().stream().anyMatch(p->p.id().equals(selected)))select(selected);else{creating=true;selected="";if(r.revision()!=before)clearPick();}
 }
 void failed(String m){busy=false;confirmation=null;notice(m);}
 private ItemStack decode(byte[] b){if(b.length==0||client.world==null)return ItemStack.EMPTY;try{return ItemStack.fromNbt(client.world.getRegistryManager(),NbtIo.readCompressed(new ByteArrayInputStream(b),NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY);}catch(Exception e){return ItemStack.EMPTY;}}
 private String displayName(Product p){var item=icons.getOrDefault(p.id(),ItemStack.EMPTY);return item.isEmpty()?p.name():ItemTooltipRenderer.localName(item,item.getName()).getString();}
 private void select(String id){selected=id;creating=false;confirmation=null;clearPick();var p=data.products().stream().filter(x->x.id().equals(id)).findFirst().orElse(null);if(p!=null){name.widget.setText(displayName(p));buyAllowed=!p.buy().isEmpty();sellAllowed=!p.sell().isEmpty();buy.widget.setText(buyAllowed?p.buy():"0");sell.widget.setText(sellAllowed?p.sell():"0");category.widget.setText(p.category());sort.widget.setText(String.valueOf(p.sort()));}}
 private void clearPick(){pickSlot=-1;pickItem="";pickStack=ItemStack.EMPTY;}
 private void newProduct(){creating=true;selected="";confirmation=null;clearPick();name.widget.setText("");buy.widget.setText("0");sell.widget.setText("0");buyAllowed=true;sellAllowed=false;for(var i:inputs())i.focus(false);picking=true;}
 /** 고른 칸: 이름이 비어 있으면 아이템 이름으로 채운다. 분류·순서는 직전 값을 그대로 둬서 같은 분류를 연달아 넣기 쉽게. */
 private void pick(int slot){var stack=client.player.getInventory().getStack(slot);if(stack.isEmpty())return;pickSlot=slot;pickItem=Registries.ITEM.getId(stack.getItem()).toString();pickStack=stack.copyWithCount(1);name.widget.setText(ItemTooltipRenderer.localName(stack,stack.getName()).getString());picking=false;notice((slot<9?"핫바 "+(slot+1):"인벤토리 "+(slot-8))+"번 칸을 골랐어요. 가격을 넣고 등록 확인을 누르세요.");}
 private void list(String shop,int offset){if(data==null||busy)return;confirmation=null;busy=ShopAdminClient.request(this,new Request(ShopAdminProtocol.LIST,1,data.session(),shop,data.revision(),"","","off","off",search.text(),offset,"",0,-1,""));if(!busy)notice("서버 연결 또는 이전 요청을 확인하세요.");}
 private void prepare(int action){if(data==null||busy||data.revision()<1)return;if(action==ShopAdminProtocol.DELETE&&selected.isEmpty())return;
  try{String b=buyAllowed?buy.text():"off",s=sellAllowed?sell.text():"off";int order=0;String group=category.text().strip();
   if(action!=ShopAdminProtocol.DELETE){ShopAdminProtocol.price(b);ShopAdminProtocol.price(s);if(name.text().isBlank())throw new IllegalArgumentException("상품 이름을 입력하세요.");
    if(!sort.text().strip().matches("[0-9]{1,5}"))throw new IllegalArgumentException("순서는 0~99999 숫자로 입력하세요.");order=Integer.parseInt(sort.text().strip());
    if(action==ShopAdminProtocol.ADD&&pickSlot<0)throw new IllegalArgumentException("「인벤토리에서 등록」으로 아이템을 먼저 고르세요.");}
   String confirmedName=action==ShopAdminProtocol.DELETE?data.products().stream().filter(p->p.id().equals(selected)).map(this::displayName).findFirst().orElseThrow():name.text();
   confirmation=new Request(action,1,data.session(),data.shop(),data.revision(),selected,confirmedName,b,s,search.text(),data.offset(),group,order,action==ShopAdminProtocol.ADD?pickSlot:-1,action==ShopAdminProtocol.ADD?pickItem:"");for(var i:inputs())i.focus(false);
  }catch(IllegalArgumentException e){notice(e.getMessage());}
 }
 private void confirm(){if(confirmation==null||busy)return;busy=ShopAdminClient.request(this,confirmation);if(busy)confirmation=null;else notice("서버 연결 또는 이전 요청을 확인하세요.");}
 private void cycle(int step){if(data==null||data.shops().isEmpty())return;int at=0;for(int i=0;i<data.shops().size();i++)if(data.shops().get(i).id().equals(data.shop()))at=i;var s=data.shops().get(Math.floorMod(at+step,data.shops().size()));creating=true;selected="";clearPick();previous.clear();search.widget.setText("");list(s.id(),0);}
 private void field(DrawContext c,Input input,int x,int y,int w,String hint){box(c,x,y,w,44,input.focus());input.draw(c,x+6,y+2,w-12,40,hint);}
 private void icon(DrawContext c,ItemStack item,float x,float y,float scale){c.getMatrices().push();c.getMatrices().translate(x,y,0);c.getMatrices().scale(scale,scale,1);c.drawItem(item,0,0);c.drawStackOverlay(textRenderer,item,0,0);c.getMatrices().pop();}
 /** 칸 위치: 위 3줄 = 인벤토리 9~35, 한 줄 띄우고 맨 아래 = 핫바 0~8 (바닐라 인벤토리와 같은 배치). */
 private static int slotX(int slot){return GRID_X+(slot%9)*(SLOT+GAP);}
 private static int slotY(int slot){return slot<9?568:300+(slot/9-1)*(SLOT+GAP);}
 @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){var f=fit();double mx=f.x(mouseX),my=f.y(mouseY);ItemStack hover=ItemStack.EMPTY;boolean modal=picking||confirmation!=null;start(c);try{
  chamfer(c,8,8,1324,884,22,0xFFD0BD88);chamfer(c,11,11,1318,878,20,0xF00D2033);label(c,"상점 물품 관리",52,62,34,WHITE,true);button(c,"닫기",1190,36,100,46,!modal&&in(mx,my,1190,36,100,46));label(c,"운영자 · 상품 원본 유지 · 저장 전 확인",52,99,18,MUTED,false);
  button(c,"‹",50,124,54,48,!modal&&in(mx,my,50,124,54,48));button(c,"›",706,124,54,48,!modal&&in(mx,my,706,124,54,48));
  String title=data==null?"상점 불러오는 중":data.shops().stream().filter(s->s.id().equals(data.shop())).map(s->s.title()+" · "+s.id()).findFirst().orElse(data.shop().isEmpty()?"등록된 상점 없음":data.shop());fitted(c,title,120,148,26,570,GOLD,true);
  button(c,"새로고침",820,124,200,48,!modal&&!busy&&in(mx,my,820,124,200,48));button(c,"인벤토리에서 등록",1034,124,236,48,!modal&&!busy&&in(mx,my,1034,124,236,48));
  box(c,50,188,550,48,search.focus());search.draw(c,56,190,538,44,"상품 이름 / 분류 / UUID 검색");button(c,"검색",614,188,146,48,!modal&&!busy&&in(mx,my,614,188,146,48));
  if(data!=null){for(int row=0;row<8&&row+scroll<data.products().size();row++){var p=data.products().get(row+scroll);int y=252+row*60;boolean h=!modal&&in(mx,my,50,y,710,55);box(c,50,y,710,55,p.id().equals(selected)||h);var item=icons.getOrDefault(p.id(),ItemStack.EMPTY);if(!item.isEmpty()){icon(c,item,60,y+5,2.7f);if(h)hover=item;}fitted(c,displayName(p),116,y+19,22,610,WHITE,true);fitted(c,(p.category().isEmpty()?"분류 없음":p.category())+" · 순서 "+p.sort()+" · 구매 "+(p.buy().isEmpty()?"차단":p.buy())+" · 판매 "+(p.sell().isEmpty()?"차단":p.sell()),116,y+43,18,610,MUTED,false);}
   if(data.products().isEmpty())center(c,"상품이 없습니다. 검색 또는 인벤토리에서 등록",400,448,21,MUTED);
  }
  button(c,"이전 목록",50,748,180,46,!modal&&!busy&&!previous.isEmpty()&&in(mx,my,50,748,180,46));button(c,"다음 목록",580,748,180,46,!modal&&!busy&&data!=null&&data.nextOffset()>=0&&in(mx,my,580,748,180,46));center(c,data==null?"":(data.offset()+1)+"번부터 · 화면 내 스크롤",405,770,18,MUTED);
  label(c,creating?"새 상품 등록":"선택 상품 수정",820,211,27,WHITE,true);
  if(creating&&!pickStack.isEmpty()){icon(c,pickStack,1218,190,2.6f);if(!modal&&in(mx,my,1218,190,42,42))hover=pickStack;}
  label(c,creating?(pickSlot<0?"「인벤토리에서 등록」으로 아이템을 고르세요":(pickSlot<9?"핫바 "+(pickSlot+1):"인벤토리 "+(pickSlot-8))+"번 칸 · 원본 그대로 1개 단위 복사"):"아이템 원본 유지 · 이름·분류·순서·가격 수정",820,249,18,MUTED,false);
  label(c,"상품 이름",820,284,20,GOLD,false);field(c,name,820,300,450,"상품 이름");
  label(c,"분류",820,364,20,GOLD,false);label(c,"순서 · 작을수록 위",1124,364,18,GOLD,false);field(c,category,820,380,290,"예: 광물");field(c,sort,1124,380,146,"0");
  label(c,"구매 가격 · 유저가 지불",820,446,20,GOLD,false);button(c,buyAllowed?"구매 허용":"구매 차단",1094,428,176,38,buyAllowed);field(c,buy,820,470,450,"0 이상 · 소수점 6자리");
  label(c,"판매 가격 · 유저가 받음",820,536,20,GOLD,false);button(c,sellAllowed?"판매 허용":"판매 차단",1094,518,176,38,sellAllowed);field(c,sell,820,560,450,"0 이상 · 소수점 6자리");
  label(c,"무료 거래는 0 · 차단은 허용 버튼으로 설정",820,628,18,MUTED,false);button(c,busy?"처리 중…":creating?"등록 확인":"수정 확인",820,660,450,55,!modal&&!busy&&in(mx,my,820,660,450,55));button(c,"상품 삭제…",820,748,450,46,!modal&&!busy&&!creating&&in(mx,my,820,748,450,46));
  toast(c,50,808,1220);
  if(picking){c.draw();c.getMatrices().push();c.getMatrices().translate(0,0,400);HudMesh.quad(c,0,0,1340,900,0xCF07131E,0xCF07131E);box(c,250,180,840,600,false);
   label(c,"등록할 아이템 고르기",290,226,27,WHITE,true);label(c,"내 인벤토리 · 고른 칸의 아이템을 원본 그대로 1개 단위로 복사해요 (소모 안 함)",290,262,18,MUTED,false);
   var inv=client.player.getInventory();
   for(int slot=0;slot<36;slot++){int x=slotX(slot),y=slotY(slot);var stack=inv.getStack(slot);boolean h=!stack.isEmpty()&&in(mx,my,x,y,SLOT,SLOT);box(c,x,y,SLOT,SLOT,h||slot==pickSlot);if(!stack.isEmpty()){icon(c,stack,x+14,y+14,3f);if(h)hover=stack;}}
   label(c,"핫바",GRID_X,556,16,MUTED,false);
   button(c,"취소",545,672,250,52,in(mx,my,545,672,250,52));c.draw();c.getMatrices().pop();}
  if(confirmation!=null){c.draw();c.getMatrices().push();c.getMatrices().translate(0,0,400);HudMesh.quad(c,0,0,1340,900,0xCF07131E,0xCF07131E);box(c,310,280,720,340,false);center(c,confirmation.action()==ShopAdminProtocol.DELETE?"이 상품을 삭제할까요?":"이 설정을 저장할까요?",670,329,29,WHITE);fitted(c,confirmation.name(),355,388,26,630,GOLD,true);label(c,confirmation.action()==ShopAdminProtocol.DELETE?"목록에서 제거합니다. 이전 거래 기록은 유지됩니다.":(confirmation.category().isEmpty()?"분류 없음":confirmation.category())+" · 순서 "+confirmation.sort()+" · 구매 "+confirmation.buy()+" · 판매 "+confirmation.sell(),355,442,20,MUTED,false);label(c,confirmation.action()==ShopAdminProtocol.ADD?"고른 칸의 아이템을 원본 그대로 등록합니다. 그 칸이 바뀌었으면 거절돼요.":"다른 관리자의 변경이 있으면 저장되지 않습니다.",355,480,18,MUTED,false);button(c,"취소",355,535,250,52,in(mx,my,355,535,250,52));button(c,"확인",735,535,250,52,in(mx,my,735,535,250,52));c.draw();c.getMatrices().pop();}
 }finally{end(c);}if(confirmation==null&&!hover.isEmpty()&&entrance.ready())c.drawItemTooltip(textRenderer,hover,mouseX,mouseY);
 }
 @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return super.mouseClicked(x,y,button);var f=fit();double mx=f.x(x),my=f.y(y);
  if(confirmation!=null){if(in(mx,my,355,535,250,52))confirmation=null;else if(in(mx,my,735,535,250,52))confirm();return true;}
  if(picking){if(in(mx,my,545,672,250,52)){picking=false;return true;}for(int slot=0;slot<36;slot++)if(in(mx,my,slotX(slot),slotY(slot),SLOT,SLOT)){pick(slot);break;}return true;}
  if(in(mx,my,1190,36,100,46)){close();return true;}if(busy)return true;
  if(in(mx,my,50,124,54,48))cycle(-1);else if(in(mx,my,706,124,54,48))cycle(1);
  else if(in(mx,my,820,124,200,48)){previous.clear();list(data==null?"":data.shop(),0);}
  else if(in(mx,my,1034,124,236,48))newProduct();
  else if(in(mx,my,614,188,146,48)){previous.clear();list(data==null?"":data.shop(),0);}
  else if(in(mx,my,50,748,180,46)&&!previous.isEmpty())list(data.shop(),previous.pop());
  else if(in(mx,my,580,748,180,46)&&data!=null&&data.nextOffset()>=0){previous.push(data.offset());list(data.shop(),data.nextOffset());}
  else if(in(mx,my,1094,428,176,38))buyAllowed=!buyAllowed;
  else if(in(mx,my,1094,518,176,38))sellAllowed=!sellAllowed;
  else if(in(mx,my,820,660,450,55))prepare(creating?ShopAdminProtocol.ADD:ShopAdminProtocol.EDIT);
  else if(in(mx,my,820,748,450,46)&&!creating)prepare(ShopAdminProtocol.DELETE);
  else {for(var i:inputs())i.click(mx,my);if(data!=null)for(int row=0;row<8&&row+scroll<data.products().size();row++)if(in(mx,my,50,252+row*60,710,55)){select(data.products().get(row+scroll).id());break;}}
  return true;
 }
 @Override public boolean mouseScrolled(double x,double y,double h,double v){if(!picking&&confirmation==null&&!busy&&data!=null)scroll=Math.clamp(scroll-(int)Math.signum(v),0,Math.max(0,data.products().size()-8));return true;}
 @Override public boolean keyPressed(int key,int scan,int mods){if(confirmation!=null){if(key==256)confirmation=null;return true;}if(picking){if(key==256)picking=false;return true;}if(busy)return key==256?super.keyPressed(key,scan,mods):true;if(search.focus()&&(key==257||key==335)){previous.clear();list(data==null?"":data.shop(),0);return true;}for(var i:inputs())if(i.focus()&&i.key(key,scan,mods))return true;return super.keyPressed(key,scan,mods);}
 @Override public boolean charTyped(char ch,int mods){if(busy||confirmation!=null||picking)return true;for(var i:inputs())if(i.focus()&&i.typed(ch,mods))return true;return super.charTyped(ch,mods);}
 @Override public void close(){if(data!=null&&!busy)ShopAdminClient.request(this,new Request(ShopAdminProtocol.CLOSE,1,data.session(),data.shop(),0,"","","off","off","",0,"",0,-1,""));ShopAdminClient.detach(this);client.setScreen(null);}
}
