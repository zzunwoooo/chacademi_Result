package school.magiccodex.paper;
import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.inventory.ItemStack;import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.ShopAdminProtocol;import school.magiccodex.protocol.ShopAdminProtocol.*;
/** Independent operator session; no wallet/inventory effects or live schema migration. */
final class ShopAdminBridge implements PluginMessageListener,Listener,AutoCloseable {
 private final MagicCodexBridge plugin;private final ShopStore store;
 private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"MagicCodex-shop-admin-io");t.setDaemon(true);return t;});
 private record Session(Player player,long token,long sequence,long expires){}
 private final Map<UUID,Session> sessions=new HashMap<>();private final Set<UUID> busy=new HashSet<>();
 private List<String> shopIds=List.of();private boolean closing;
 ShopAdminBridge(MagicCodexBridge p)throws Exception{plugin=p;var dir=p.getDataFolder().toPath();try{store=io.submit(()->new ShopStore(ShopMailboxDatabaseSettings.load(dir),dir.resolve("shops.db"))).get(15,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
  CmdKit.register(p,"/상점관리 편집",()->ChacaApi.branch(p,ChacaApi.상점관리,CmdKit.literal("편집").executes(CmdKit.run(ctx->edit(CmdKit.self(ctx),""))).then(CmdKit.word("상점",()->shopIds).executes(CmdKit.run(ctx->edit(CmdKit.self(ctx),CmdKit.text(ctx,"상점"))))),"상점 상품 편집 창 열기","편집"));refreshShopIds();Bukkit.getScheduler().runTaskTimer(p,this::refreshShopIds,100,100);
  Bukkit.getMessenger().registerIncomingPluginChannel(p,ShopAdminProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(p,ShopAdminProtocol.RESPONSE);Bukkit.getPluginManager().registerEvents(this,p);
 }
 private long idsVersion=-1; // IO 스레드에서만 읽고 쓴다
 /** 탭 완성용 상점 ID: 카탈로그 버전이 바뀐 때에만, 상품 payload 없이 ID만 읽는다. */
 private void refreshShopIds(){if(closing)return;work(()->{long v=store.version();if(v==idsVersion)return null;List<String> ids=store.shopIds();idsVersion=v;return ids;},ids->{if(ids!=null)shopIds=ids;},ignored->{});}
 private boolean allowed(Player p){return !closing&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p&&(p.isOp()||p.hasPermission("magiccodex.shop.admin"));}
 private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,r);}
 private <T>void work(Callable<T> task,Consumer<T> done,Consumer<String> fail){try{io.execute(()->{try{T v=task.call();main(()->done.accept(v));}catch(Exception e){plugin.getLogger().warning("Shop admin: "+e.getClass().getSimpleName());main(()->fail.accept(e instanceof IllegalArgumentException||e instanceof IllegalStateException?e.getMessage():"저장 결과를 확인할 수 없습니다. 새로고침 후 확인하고 다시 저장하세요."));}});}catch(RejectedExecutionException e){fail.accept("관리 요청이 많습니다. 잠시 뒤 다시 시도하세요.");}}
 /** /상점관리 편집 [상점] (OP는 허브가 검사, 편집 패킷은 OP 또는 magiccodex.shop.admin). */
 private void edit(Player p,String shop)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  if(!shop.isEmpty()&&!shop.matches("[a-z0-9_-]{1,48}"))throw ChacaApi.error("상점 ID를 확인해 주세요");
  if(!p.getListeningPluginChannels().contains(ShopAdminProtocol.RESPONSE))throw ChacaApi.error("상점 편집 창이 있는 차카데미 모드가 필요해요");
  if(busy.contains(p.getUniqueId()))throw ChacaApi.error("앞의 관리 요청을 처리하는 중이에요");
  var s=new Session(p,ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE),0,System.currentTimeMillis()+600000);sessions.put(p.getUniqueId(),s);
  Request r=new Request(ShopAdminProtocol.LIST,0,s.token,shop,0,"","","off","off","",0,"",0,-1,"");read(p,s,r,"");
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){if(!channel.equals(ShopAdminProtocol.REQUEST)||!allowed(p))return;Request r;try{r=ShopAdminProtocol.request(bytes);}catch(IllegalArgumentException ignored){return;}var s=sessions.get(p.getUniqueId());if(s==null||s.player!=p||s.token!=r.session()||r.sequence()<=s.sequence||System.currentTimeMillis()>s.expires)return;
  if(busy.contains(p.getUniqueId()))return;s=new Session(p,s.token,r.sequence(),System.currentTimeMillis()+600000);sessions.put(p.getUniqueId(),s);
  if(r.action()==ShopAdminProtocol.CLOSE){sessions.remove(p.getUniqueId());return;}
  if(r.action()==ShopAdminProtocol.LIST){read(p,s,r,"");return;}
  ItemStack held=null;String buy,sell;
  try{buy=r.action()==ShopAdminProtocol.DELETE?"":ShopStore.price(r.buy());sell=r.action()==ShopAdminProtocol.DELETE?"":ShopStore.price(r.sell());if(r.action()==ShopAdminProtocol.ADD){held=picked(p,r);held.setAmount(1);}}
  catch(IllegalArgumentException e){read(p,s,r,e.getMessage());return;}
  byte[] payload=held==null?null:held.serializeAsBytes();if(payload!=null&&payload.length>ShopAdminProtocol.MAX_PREVIEW){read(p,s,r,"아이템 데이터가 너무 큽니다. 원본을 줄이지 않고 등록을 중단했습니다.");return;}
  Session accepted=s;busy.add(p.getUniqueId());
  work(()->{switch(r.action()){case ShopAdminProtocol.ADD->store.adminAdd(r.shop(),r.revision(),r.name(),payload,buy,sell,r.category(),r.sort());case ShopAdminProtocol.EDIT->store.adminEdit(r.shop(),r.revision(),r.product(),r.name(),buy,sell,r.category(),r.sort());case ShopAdminProtocol.DELETE->store.adminDelete(r.shop(),r.revision(),r.product());default->throw new IllegalArgumentException("관리 동작");}return snapshot(accepted,r,"상품 "+(r.action()==ShopAdminProtocol.DELETE?"삭제":"저장")+" 완료");},v->deliver(p,accepted,v),m->{busy.remove(p.getUniqueId());read(p,accepted,r,m);});
 }
 /** 등록할 아이템: 관리 창에서 고른 내 인벤토리 칸(서버의 실제 칸을 읽음). 고른 뒤 그 칸이 바뀌었으면 거절. slot -1 = 주 손(예전 방식). */
 private static ItemStack picked(Player p,Request r){
  if(r.slot()<0){var held=p.getInventory().getItemInMainHand().clone();if(held.getType().isAir())throw new IllegalArgumentException("등록할 아이템을 주 손에 들어 주세요.");return held;}
  var item=p.getInventory().getItem(r.slot());if(item==null||item.getType().isAir())throw new IllegalArgumentException("고른 칸이 비어 있어요. 인벤토리를 다시 열어 골라 주세요.");
  if(!item.getType().getKey().toString().equals(r.slotItem()))throw new IllegalArgumentException("고른 칸의 아이템이 바뀌었어요. 다시 골라 주세요.");
  return item.clone();
 }
 private void read(Player p,Session s,Request r,String message){busy.add(p.getUniqueId());work(()->snapshot(s,r,message),v->deliver(p,s,v),m->{busy.remove(p.getUniqueId());if(allowed(p)){p.sendPluginMessage(plugin,ShopAdminProtocol.RESPONSE,ShopAdminProtocol.encode(new Response(r.sequence(),s.token,r.shop(),r.revision(),m,List.of(),List.of(),0,-1)));}});}
 private Response snapshot(Session session,Request r,String message)throws Exception{
  var catalog=store.catalog();var sorted=catalog.values().stream().sorted(Comparator.comparing(ShopStore.Shop::id)).toList();var shops=new ArrayList<Shop>();int metadata=0;for(var entry:sorted){int cost=(entry.id().length()+entry.title().length())*3+8;if(shops.size()==128||metadata+cost>12000)break;metadata+=cost;shops.add(new Shop(entry.id(),entry.title()));}
  String id=r.shop().isEmpty()?(sorted.isEmpty()?"":sorted.getFirst().id()):r.shop();var shop=catalog.get(id);
  if(shop==null)return new Response(r.sequence(),session.token,id,0,message.isEmpty()?"등록된 상점을 선택하세요. /상점관리 만들기 로 상점을 먼저 만들 수 있습니다.":message,shops,List.of(),0,-1);
  String q=r.search().toLowerCase(Locale.ROOT);var matching=shop.products().stream().filter(p->p.name().toLowerCase(Locale.ROOT).contains(q)||p.category().toLowerCase(Locale.ROOT).contains(q)||p.id().contains(q)).toList();
  int offset=r.offset()<matching.size()?r.offset():0,index=offset,size=0;var products=new ArrayList<Product>();
  // Budget for UTF-8 shop/title metadata plus the original, unmodified ItemStack previews.
  for(;index<matching.size()&&products.size()<20;index++){var p=matching.get(index);int cost=p.bytes().length+700;if(p.bytes().length>ShopAdminProtocol.MAX_PREVIEW)throw new IllegalStateException("기존 상품 데이터가 UI 허용 크기를 초과합니다.");if(size+cost>38000&&!products.isEmpty())break;size+=cost;products.add(new Product(p.id(),p.name(),p.buy(),p.sell(),p.category(),p.sort(),p.bytes()));}
  // 교환 상품(#98)은 편집 창에 따로 칸이 없어 안내 줄로만 보여 준다. 바꾸기: /상점관리 교환 <상점> <상품> <아이템키> <개수|없음>
  if(message.isEmpty()){var ex=shop.products().stream().filter(ShopStore.Product::exchange).map(p->p.name()+" ← "+p.costItem()+" ×"+p.costCount()).toList();if(!ex.isEmpty()){String m="교환 상품(판매 없음, 구매가·판매가 무시): "+String.join(", ",ex);message=m.length()>240?m.substring(0,237)+"…":m;}}
  if(sorted.size()>shops.size()&&message.isEmpty())message="상점 목록은 패킷 크기에 맞춰 일부 표시합니다. /상점관리 편집 <상점> 으로 직접 열 수 있습니다.";
  return new Response(r.sequence(),session.token,id,shop.revision(),message,shops,List.copyOf(products),offset,index<matching.size()?index:-1);
 }
 private void deliver(Player p,Session s,Response response){busy.remove(p.getUniqueId());var current=sessions.get(p.getUniqueId());if(!allowed(p)||current==null||current.token!=s.token||current.sequence!=response.sequence())return;shopIds=response.shops().stream().map(Shop::id).toList();p.sendPluginMessage(plugin,ShopAdminProtocol.RESPONSE,ShopAdminProtocol.encode(response));}
 @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());}
 @Override public void close(){closing=true;sessions.clear();io.shutdown();try{if(io.awaitTermination(10,TimeUnit.SECONDS))store.close();}catch(Exception e){plugin.getLogger().warning("Shop admin close: "+e.getClass().getSimpleName());}}
}
