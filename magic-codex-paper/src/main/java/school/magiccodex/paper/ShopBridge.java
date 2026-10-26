package school.magiccodex.paper;
import java.math.BigDecimal;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import java.util.logging.Level;
import org.bukkit.*;import org.bukkit.command.CommandSender;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.inventory.ItemStack;import org.bukkit.persistence.PersistentDataType;import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.ShopProtocol;import school.magiccodex.protocol.ShopProtocol.*;

/**
 * Admin shops only. Money and inventory effects are journalled, never retried blindly after ambiguity.
 *
 * 거래 상태 (shop_orders.state):
 *  구매: prepared -> paying -> paid -> done            (실패: failed)
 *  판매: prepared -> sale_removed -> credit_pending -> done (실패: failed)
 *  교환: prepared -> paid -> done  (2026-10-26, #98: 돈 대신 비용 아이템. 비용 회수 + 영수증 shop_sale_<id> 를 같은 틱에 기록·저장한 뒤 paid)
 *  관리자 환불: (미완료 상태) -> refunding -> failed
 * 부작용(출금/아이템 회수/입금)은 항상 "그 직전 상태가 DB에 기록된 뒤"에만 실행한다. 그래서
 *  - 구매 prepared            : 출금 없음이 보장 -> 어느 서버에서든 자동 실패 처리
 *  - 구매 paid                : 우편 발송은 source key 로 멱등 -> 자동 재발송
 *  - 판매 prepared (이 서버)  : 아이템 회수와 PDC 영수증(shop_sale_<id>)이 같은 틱에 기록됨 -> 영수증 유무로 판정
 *  - 판매 sale_removed (이 서버): 입금은 credit_pending 기록 뒤에만 실행 -> 자동으로 이어서 입금
 *  - 교환 prepared (이 서버)  : 판매처럼 영수증 유무로 판정 (있으면 paid 로 이어서 우편, 없으면 실패). 서버 시작 때 자동 실패 처리하지 않는다
 *  - 교환 paid                : 구매 paid 와 같음 (우편 멱등 재발송)
 *  - 구매 paying / 판매 credit_pending / refunding / origin 없는 옛 기록 : 판정 불가 -> 관리자 처리(/유저관리 <닉네임> 거래기록 · /상점관리 거래처리)
 */
final class ShopBridge implements PluginMessageListener,Listener,AutoCloseable {
 private final MagicCodexBridge plugin;private final ShopStore store;private final String origin;
 /** 새 요청만 이 한도로 거절한다(work). 이미 시작한 거래의 후속 기록(must)은 거절하지 않으므로, 대기열이 찬 것 때문에 돈/아이템이 움직인 뒤 기록이 빠지는 일은 없다. */
 private static final int QUEUE_LIMIT=64;
 private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),r->{var t=new Thread(r,"MagicCodex-shop-io");t.setDaemon(true);return t;});
 private Map<String,ShopStore.Shop> catalog=Map.of();private Map<String,String> bindings=Map.of();private long version=-1;private boolean refreshing;private volatile boolean closing;
 private record Session(Player player,long token,long sequence,String shop,Entity anchor){}
 private final Map<UUID,Session> sessions=new HashMap<>();private final Set<UUID>busy=new HashSet<>();private final Map<UUID,Long>limits=new HashMap<>();
 /** 카탈로그 한 판(revision)마다 한 번만 역직렬화하는 상품 견본. 읽기 전용으로만 쓰고, 고쳐 쓸 때는 clone 한다. */
 private final Map<String,ItemStack> samples=new HashMap<>();
 /** 부작용을 아직 실행하지 않은 것이 확실한 진행 중 기록의 되돌리기 작업 (종료 시에만 실행). */
 private final Map<String,Callable<?>> reverts=new HashMap<>();
 /** 관리자가 방금 `기록`으로 본 거래 ID (탭 완성용). */
 private final Map<String,List<String>> listed=new HashMap<>();
 /** 튜토리얼 전용 재료 상점 (메모 #12): 설정, 지금 그 상점을 연 대상 유저 → 구매를 세기 시작한 시각. */
 private TutorialShop.Config tutorial=TutorialShop.Config.OFF;private final Map<UUID,Long> tutorialOpen=new HashMap<>();
 /** 교환 상품 비용 아이템 판정·견본 (ItemsAdder 는 선택). */
 private final ItemsAdderItems itemsAdder;
 ShopBridge(MagicCodexBridge plugin)throws Exception{this.plugin=plugin;itemsAdder=new ItemsAdderItems(plugin.getLogger());var dir=plugin.getDataFolder().toPath();origin=Files.readString(dir.resolve("mailbox-origin.txt")).strip();try{store=io.submit(()->new ShopStore(ShopMailboxDatabaseSettings.load(dir),dir.resolve("shops.db"))).get(15,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
  try{loadTutorial();}catch(Exception e){plugin.getLogger().severe("tutorial-shop.yml 을 읽지 못해 튜토리얼 재료 상점을 끕니다: "+e.getMessage());}catalog=io.submit(store::catalog).get(10,TimeUnit.SECONDS);bindings=io.submit(()->store.bindings(origin)).get(10,TimeUnit.SECONDS);version=io.submit(store::version).get(10,TimeUnit.SECONDS);
  try{int closed=io.submit(()->store.failPrepared(origin)).get(10,TimeUnit.SECONDS);if(closed>0)plugin.getLogger().info("Shop recovery: 출금 전 구매 기록 "+closed+"건을 실패로 닫았습니다.");}catch(Exception e){plugin.getLogger().warning("Shop recovery at startup skipped: "+e.getClass().getSimpleName());}
  Bukkit.getMessenger().registerIncomingPluginChannel(plugin,ShopProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,ShopProtocol.RESPONSE);Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getServicesManager().register(ShopBridge.class,this,plugin,org.bukkit.plugin.ServicePriority.Normal);CmdKit.register(plugin,"/상점관리·/유저관리 거래기록",this::commands);citizens();refreshCompletionKeys();Bukkit.getScheduler().runTaskTimer(plugin,this::refresh,100,100);for(Player p:Bukkit.getOnlinePlayers())recoverLater(p,0);
 }
 private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,r);}
 /** 메인 스레드 후속 처리는 반드시 여기를 거친다: 예외가 나도 busy 가 풀리고, 거래 기록은 현재 상태로 남아 자동 복구/관리자 처리 대상이 된다. */
 private void guard(UUID owner,Runnable r){try{r.run();}catch(Throwable t){plugin.getLogger().log(Level.SEVERE,"Shop continuation failed"+(owner==null?"":" for "+owner)+"; the order keeps its recorded state for recovery/review",t);if(owner!=null){busy.remove(owner);var p=Bukkit.getPlayer(owner);if(p!=null)CmdKit.notify(p,"warning","shop-error","상점 처리 중 문제가 생겼어요","상점을 다시 열어 주세요. 계속되면 관리자에게 알려 주세요.",3000);}}}
 /** 새 요청: 대기열이 차 있으면 아무 부작용 없이 거절한다. */
 private <T>void work(UUID owner,Callable<T>task,Consumer<T>success,Consumer<String>fail){if(io.getQueue().size()>=QUEUE_LIMIT){fail.accept("상점이 바쁩니다. 잠시 뒤 다시 시도해 주세요.");return;}must(owner,task,success,fail);}
 /** 진행 중 거래의 후속 기록: 대기열 한도로 거절하지 않는다. */
 private <T>void must(UUID owner,Callable<T>task,Consumer<T>success,Consumer<String>fail){try{io.execute(()->{T v;try{v=task.call();}catch(Exception e){plugin.getLogger().warning("Shop operation held: "+e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage()));String m=e instanceof IllegalArgumentException||e instanceof IllegalStateException?String.valueOf(e.getMessage()):"거래 기록 확인이 필요합니다. 자동으로 재처리하지 않습니다.";main(()->guard(owner,()->fail.accept(m)));return;}main(()->guard(owner,()->success.accept(v)));});}catch(RejectedExecutionException e){guard(owner,()->fail.accept("상점이 종료 중입니다. 잠시 뒤 다시 시도해 주세요."));}}
 private record Catalog(long version,Map<String,ShopStore.Shop> shops,Map<String,String> bindings){}
 private void refresh(){if(refreshing||closing)return;refreshing=true;work(null,()->{long v=store.version();return v==version?null:new Catalog(v,store.catalog(),store.bindings(origin));},c->{refreshing=false;if(c!=null){version=c.version;catalog=c.shops;bindings=c.bindings;samples.clear();}refreshCompletionKeys();},m->refreshing=false);}
 private boolean current(Player p){return !closing&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p;}
 private boolean ready(Player p){return current(p)&&p.hasPermission("magiccodex.shop")&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&plugin.playerStateReady(p);}
 private boolean near(Player p,Entity anchor){return anchor==null||anchor.isValid()&&p.getWorld()==anchor.getWorld()&&p.getLocation().distanceSquared(anchor.getLocation())<=64;}
 private record Economy(Class<?>type,Object provider){}
 @SuppressWarnings({"rawtypes","unchecked"})private Economy economy(){for(Class<?>type:Bukkit.getServicesManager().getKnownServices())if(type.getName().equals("net.milkbowl.vault.economy.Economy")){var r=Bukkit.getServicesManager().getRegistration((Class)type);if(r!=null)return new Economy(type,r.getProvider());}return null;}
 private double balance(OfflinePlayer p)throws Exception{var e=economy();if(e==null)throw new IllegalStateException("경제 서비스 연결이 필요합니다.");double v=((Number)e.type.getMethod("getBalance",OfflinePlayer.class).invoke(e.provider,p)).doubleValue();if(!Double.isFinite(v)||v<0)throw new IllegalStateException("잔액을 확인할 수 없습니다.");return v;}
 private boolean money(OfflinePlayer p,String amount,boolean deposit)throws Exception{var e=economy();if(e==null)throw new IllegalStateException("경제 서비스 연결이 필요합니다.");var cost=new BigDecimal(amount);int digits=((Number)e.type.getMethod("fractionalDigits").invoke(e.provider)).intValue();if(digits>=0&&cost.stripTrailingZeros().scale()>digits)throw new IllegalStateException("경제 서비스가 지원하는 소수점 자릿수를 확인하세요.");if(!deposit&&BigDecimal.valueOf(balance(p)).compareTo(cost)<0)return false;if(cost.signum()==0)return true;Object result=e.type.getMethod(deposit?"depositPlayer":"withdrawPlayer",OfflinePlayer.class,double.class).invoke(e.provider,p,cost.doubleValue());return (boolean)result.getClass().getMethod("transactionSuccess").invoke(result);}
 private ItemStack sample(ShopStore.Product product){return plugin.coreAppearance(samples.computeIfAbsent(product.id(),k->ItemStack.deserializeBytes(product.bytes())));}
 private int owned(Player p,ItemStack sample){int n=0;for(var i:p.getInventory().getStorageContents())if(i!=null&&plugin.coreSimilar(i,sample))n+=i.getAmount();return n;}
 private NamespacedKey saleKey(String order){return new NamespacedKey(plugin,"shop_sale_"+order.replace("-",""));}
 // ---- 교환 상품 (2026-10-26, #98) ----
 /** 교환 비용으로 칠 수 있는 칸의 키: ItemsAdder 아이템이면 그 키, 꾸밈 없는(메타 없는) 바닐라 아이템이면 minecraft:id, 그 밖은 null(세지 않음). */
 private String costKey(ItemStack i){if(i==null||i.getType().isAir())return null;String ia=itemsAdder.idOf(i);if(ia!=null)return ia;return i.hasItemMeta()?null:i.getType().getKey().toString();}
 private String[] costKeys(ItemStack[] c){var out=new String[c.length];for(int i=0;i<c.length;i++)out[i]=costKey(c[i]);return out;}
 private static int[] amounts(ItemStack[] c){var out=new int[c.length];for(int i=0;i<c.length;i++)out[i]=c[i]==null||c[i].getType().isAir()?0:c[i].getAmount();return out;}
 /** 비용 아이템을 지금 셀 수 있는가: 바닐라는 언제나, ItemsAdder 키는 ItemsAdder 가 켜져 있을 때만. */
 private boolean costReady(String key){return key.startsWith("minecraft:")||itemsAdder.available();}
 /** 비용 아이템 1개짜리 견본 (카탈로그 한 판마다 캐시). 만들 수 없으면 null. */
 private ItemStack costSample(String key){var cached=samples.get("cost:"+key);if(cached!=null)return cached;ItemStack made=null;
  if(key.startsWith("minecraft:")){var m=Material.matchMaterial(key);if(m!=null&&m.isItem()&&!m.isAir())made=new ItemStack(m,1);}else made=itemsAdder.create(key,1);
  if(made!=null)samples.put("cost:"+key,made);return made;}
 private String costName(String key){var s=costSample(key);return s==null?key:AlchemyBridge.displayName(s);}
 /** 비용 아이템 total 개를 우편 첨부용 묶음으로 (관리자 환불). 견본을 만들 수 없으면 null. */
 private List<ItemStack> costStacks(String key,int total){var sample=costSample(key);if(sample==null)return null;var out=new ArrayList<ItemStack>();int left=total;while(left>0){var stack=sample.clone();stack.setAmount(Math.min(left,sample.getMaxStackSize()));left-=stack.getAmount();out.add(stack);}return out;}
 private void clearReceipt(ShopStore.Order order){var live=Bukkit.getPlayer(order.owner());if(live!=null&&current(live)&&live.getPersistentDataContainer().has(saleKey(order.id()),PersistentDataType.BYTE)){live.getPersistentDataContainer().remove(saleKey(order.id()));live.saveData();}}
 /** NPC 대화창 상점 버튼 (ChacaNPC → NpcSocialFacade.openShop, 2026-10-25). 일반 NPC 클릭과 같은 open — 거리·튜토리얼 상점 분기 그대로. false = 아직 준비 안 됨(메시지는 open 이 알림). */
 boolean openFromNpc(Player p,String shop,Entity anchor){if(p==null||shop==null||shop.isBlank()||!p.isOnline())return false;open(p,shop,anchor);return true;}
 private void open(Player p,String input,Entity anchor){String id=ShopNpcTarget.canonical(input);if(!ready(p)||!near(p,anchor)||busy.contains(p.getUniqueId()))return;
  if(ShopNpcTarget.fish(id)){var fish=Bukkit.getServicesManager().load(FishShopBridge.class);if(fish==null)CmdKit.notify(p,"warning","shop-open","물고기 판매소를 열 수 없어요","관리자에게 알려 주세요.",3000);else fish.open(p,anchor,npcName(anchor));return;}
  // 튜토리얼 대상이면 일반 잡화점 대신 전용 재료 상점 (대상 여부는 공유 DB 에서 읽는다. 읽지 못하면 아무 상점도 열지 않는다).
  var cfg=tutorial;UUID who=p.getUniqueId();tutorialOpen.remove(who);
  if(cfg.redirects(id)||cfg.enabled()&&id.equals(cfg.shop())){busy.add(who);
   work(who,()->store.tutorialStarted(who),started->{busy.remove(who);if(!current(p))return;String target=started>0?cfg.shop():id;
    if(started>0&&!catalog.containsKey(target)){plugin.getLogger().warning("튜토리얼 상점 "+target+" 이 없어요 (tutorial-shop.yml shop / /상점관리 만들기)");CmdKit.notify(p,"warning","shop-tutorial","상점을 준비하고 있어요","잠시 뒤 다시 찾아와 주세요.",3000);return;}
    if(started>0)tutorialOpen.put(who,started);openShop(p,target,anchor);},m->{busy.remove(who);if(current(p))CmdKit.notify(p,"warning","shop-open","상점을 열 수 없어요",m,3000);});
   return;}
  openShop(p,id,anchor);}
 private void openShop(Player p,String id,Entity anchor){if(!ready(p)||!near(p,anchor)||busy.contains(p.getUniqueId()))return;
  if(!catalog.containsKey(id))return;UUID owner=p.getUniqueId();var s=new Session(p,ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE),0,id,anchor);sessions.put(owner,s);send(p,s,0,"","");busy.add(owner);
  // 열 때마다 이전 거래를 정리한다: 안전하게 판정되는 것은 자동 처리, 나머지는 안내만 한다.
  recover(p,m->{busy.remove(owner);if(m.isEmpty()||!current(p))return;var latest=sessions.get(owner);if(latest!=null&&latest.token==s.token&&latest.sequence==0)send(p,latest,0,m,"");else CmdKit.notify(p,"warning","shop-recover","상점",m,3000);});}
 String openBound(Player p,int npcId,Entity anchor){String id=bindings.get("citizens:"+npcId);if(id==null)return "NONE";if(!ready(p)||!near(p,anchor)||!catalog.containsKey(id)&&!ShopNpcTarget.fish(id))return "BLOCKED";if(busy.contains(p.getUniqueId()))return "BUSY";if(ShopNpcTarget.fish(id)&&Bukkit.getServicesManager().load(FishShopBridge.class)==null)return "BLOCKED";if(!p.getListeningPluginChannels().contains(ShopNpcTarget.fish(id)?school.magiccodex.protocol.FishShopProtocol.RESPONSE:ShopProtocol.RESPONSE)){CmdKit.notify(p,"warning","shop-mod","상점을 열 수 없어요","차카데미 모드를 업데이트해 주세요.",3000);return "BLOCKED";}open(p,id,anchor);return "OPENED";}
 private void send(Player p,Session s,long sequence,String message,String search){send(p,s,sequence,message,search,"");}
 private void send(Player p,Session s,long sequence,String message,String search,String completedOperation){
  if(!current(p))return;var shop=catalog.get(s.shop);if(shop==null)return;
  String cash="";try{cash=BigDecimal.valueOf(balance(p)).stripTrailingZeros().toPlainString();}catch(Exception ignored){}
  var list=new ArrayList<Product>();String q=search.toLowerCase(Locale.ROOT);int bytes=0;boolean limited=false;
  // 인벤토리에 그 종류가 하나도 없으면 상품마다 36칸을 비교하지 않는다.
  var held=EnumSet.noneOf(Material.class);for(var i:p.getInventory().getStorageContents())if(i!=null)held.add(i.getType());
  String[] invKeys=null;int[] invAmounts=null;/* 교환 비용 보유 수: 교환 상품이 있을 때만 한 번 센다 */
  for(var item:shop.products())if(item.name().toLowerCase(Locale.ROOT).contains(q)){
   // Keep the complete original serialized stack. Limit the catalogue page, never strip lore/components.
   // 실제로 쓰는 바이트(이름·ID·가격 UTF-8 + 숫자 칸)에 여유를 더해 계산한다. 개수 제한은 프로토콜 한도(256)만.
   byte[] preview=item.bytes();int cost=preview.length+item.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+item.id().length()+item.buy().length()+item.sell().length()+64;
   // 교환 상품: 비용 아이템 견본·이름·보유 수가 더 붙는다. 견본이 너무 크면 견본만 뺀다(이름·개수는 그대로).
   byte[] costPreview=new byte[0];String costName="";int costHeld=0;
   if(item.exchange()){var cs=costSample(item.costItem());if(cs!=null){byte[] b=cs.serializeAsBytes();if(b.length<=ShopProtocol.MAX_PREVIEW_BYTES)costPreview=b;}costName=costName(item.costItem());if(costName.length()>120)costName=costName.substring(0,120);if(costReady(item.costItem())){if(invKeys==null){var inv=p.getInventory().getStorageContents();invKeys=costKeys(inv);invAmounts=amounts(inv);}costHeld=ShopExchange.held(invKeys,invAmounts,item.costItem());}
cost+=costPreview.length+costName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+24;}
   if(preview.length>ShopProtocol.MAX_PREVIEW_BYTES||bytes+cost>ShopProtocol.MAX_BYTES-4096||list.size()==256){limited=true;break;}
   ItemStack stack;try{stack=sample(item);}catch(RuntimeException e){continue;/* 읽을 수 없는 상품 데이터는 목록에서만 뺀다 */}
   int have=held.contains(stack.getType())?owned(p,stack):0;
   // 교환 상품은 buy = 1개당 비용 아이템 개수, sell = "" (판매 없음). 화면은 costCount>0 이면 "조각 ×10" 처럼 보인다.
   list.add(item.exchange()?new Product(item.id(),item.name(),String.valueOf(item.costCount()),"",have,preview,costName,item.costCount(),costHeld,costPreview):new Product(item.id(),item.name(),item.buy(),item.sell(),have,preview));bytes+=cost;
  }
  if(limited&&message.isEmpty())message="검색으로 상품을 좁혀 주세요. 전체 아이템 정보를 유지해 목록을 제한합니다.";
  p.sendPluginMessage(plugin,ShopProtocol.RESPONSE,ShopProtocol.encode(new Response(sequence,s.token,shop.id(),shop.title(),shop.portrait(),shop.revision(),cash,message,List.copyOf(list),completedOperation,shownName(s,shop),shop.role())));
 }
 /** NPC 이름이 있으면 그것, 없으면(명령으로 연 상점) 상점 설정의 주인 이름. */
 private String shownName(Session s,ShopStore.Shop shop){String n=npcName(s.anchor);return n.isBlank()?shop.owner():n;
 }
 private String npcName(Entity anchor){
  if(anchor==null)return "";
  try{var dep=Bukkit.getPluginManager().getPlugin("Citizens");if(dep!=null){Class<?> api=Class.forName("net.citizensnpcs.api.CitizensAPI",true,dep.getClass().getClassLoader());Object registry=api.getMethod("getNPCRegistry").invoke(null);Class<?> registryType=Class.forName("net.citizensnpcs.api.npc.NPCRegistry",true,dep.getClass().getClassLoader());Object npc=registryType.getMethod("getNPC",Entity.class).invoke(registry,anchor);if(npc!=null){Class<?> npcType=Class.forName("net.citizensnpcs.api.npc.NPC",true,dep.getClass().getClassLoader());String value=String.valueOf(npcType.getMethod("getName").invoke(npc));return value.length()>120?value.substring(0,120):value;}}}catch(ReflectiveOperationException ignored){}
  var custom=anchor.customName();if(custom==null)return "";String value=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(custom);return value.length()>120?value.substring(0,120):value;
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){if(!channel.equals(ShopProtocol.REQUEST)||!ready(p))return;Request r;try{r=ShopProtocol.request(bytes);}catch(IllegalArgumentException ignored){return;}var s=sessions.get(p.getUniqueId());if(s==null||s.player!=p||s.token!=r.session()||!s.shop.equals(r.shop())||r.sequence()<=s.sequence)return;
  // 닫기는 거래 중·연속 요청 제한·거리와 상관없이 받는다 (세션만 지움). 튜토리얼 재료 구매 확인이 닫기에서 일어난다.
  if(r.action()==ShopProtocol.CLOSE){sessions.remove(p.getUniqueId());tutorialClosed(p,s.shop);return;}
  if(!near(p,s.anchor))return;long now=System.currentTimeMillis();if(busy.contains(p.getUniqueId())||now<limits.getOrDefault(p.getUniqueId(),0L))return;limits.put(p.getUniqueId(),now+200);s=new Session(p,s.token,r.sequence(),s.shop,s.anchor);sessions.put(p.getUniqueId(),s);if(r.action()==ShopProtocol.CLOSE){sessions.remove(p.getUniqueId());return;}if(r.action()==ShopProtocol.OPEN){send(p,s,r.sequence(),"",r.search());return;}trade(p,s,r);}
 private void trade(Player p,Session s,Request r){var shop=catalog.get(s.shop);var product=shop==null?null:shop.products().stream().filter(i->i.id().equals(r.product())).findFirst().orElse(null);if(product==null||shop.revision()!=r.revision()){send(p,s,r.sequence(),"상점 설정이 변경되었습니다. 다시 확인해 주세요.",r.search());return;}int quantity=ShopProtocol.quantity(r.quantity());boolean selling=r.action()==ShopProtocol.SELL;ItemStack sample;try{sample=sample(product);}catch(RuntimeException e){send(p,s,r.sequence(),"이 상품은 지금 거래할 수 없습니다. 관리자에게 알려 주세요.",r.search());return;}if(quantity>sample.getMaxStackSize()*9){send(p,s,r.sequence(),"한 거래의 우편 첨부 가능 수량을 초과했습니다.",r.search());return;}boolean exchange=product.exchange();if(!exchange&&economy()==null){send(p,s,r.sequence(),"경제 서비스 연결이 필요합니다.",r.search());return;}
  // 공통 지급 칸 판정 (RewardDelivery, 2026-10-26 검수): 아공간 배낭 쪽을 넘기는 중·잠긴 쪽이면 27칸(9~35)에서 판매·교환 아이템을 빼지 않는다
  // (넘김이 끝날 때 옛 쪽이 공유 DB 에 그대로 남아 뺀 아이템이 복제됨). 연금술·의뢰·우편 수령과 같은 거절.
  if((selling||exchange)&&!RewardDelivery.mainUsable(plugin,p)){send(p,s,r.sequence(),"배낭 쪽을 넘기는 중이에요. 잠시 뒤 다시 해 주세요.",r.search());return;}
  var before=MailboxBridge.copy(p.getInventory().getStorageContents());var after=MailboxBridge.copy(before);
  // 교환 상품: 돈 대신 비용 아이템(개수 × 수량)을 인벤토리에서 낸다. 판매는 없다. 경제(Vault)는 필요 없다.
  if(exchange){if(selling){send(p,s,r.sequence(),"교환 상품은 팔 수 없어요.",r.search());return;}if(!costReady(product.costItem())){send(p,s,r.sequence(),"아이템 정보를 확인할 수 없어 지금은 교환할 수 없어요.",r.search());return;}
   int[] counts=amounts(after),left=ShopExchange.take(costKeys(after),counts,product.costItem(),ShopExchange.total(product.costCount(),quantity));
   if(left==null){send(p,s,r.sequence(),costName(product.costItem())+"이(가) 부족합니다. ("+ShopExchange.total(product.costCount(),quantity)+"개 필요)",r.search());return;}
   for(int i=0;i<after.length;i++)if(left[i]!=counts[i]){if(left[i]<=0)after[i]=null;else after[i].setAmount(left[i]);}}if(selling){if(owned(p,sample)<quantity){send(p,s,r.sequence(),"판매할 아이템 수량이 부족합니다.",r.search());return;}int left=quantity;for(int i=0;i<after.length&&left>0;i++)if(after[i]!=null&&after[i].isSimilar(sample)){int n=Math.min(left,after[i].getAmount());left-=n;after[i].setAmount(after[i].getAmount()-n);if(after[i].getAmount()==0)after[i]=null;}}
  UUID owner=p.getUniqueId();busy.add(owner);
  // 접수 단계(work): 대기열이 차 있으면 여기서 거절되고 아무 것도 바뀌지 않는다.
  work(owner,()->store.prepare(owner,r.operation(),s.shop,r.revision(),r.product(),quantity,selling,origin),order->{
   if(!ready(p)||!near(p,s.anchor)||(selling||exchange)&&(!RewardDelivery.mainUsable(plugin,p)||!MailboxBridge.same(before,p.getInventory().getStorageContents()))){abort(order,List.of("prepared"),()->done(p,s,r,"접속/인벤토리가 변경되었습니다."));return;}
   if(selling)sell(p,order,after,()->{audit(order,s.shop,sample);done(p,s,r,"판매가 완료되었습니다.",true);},m->done(p,s,r,m));
   else if(exchange)exchange(p,order,after,()->{audit(order,s.shop,sample);done(p,s,r,"교환이 완료되었습니다. 우편함을 확인해 주세요.",true);},m->done(p,s,r,m));
   else buy(p,s,r,order,sample);
  },message->done(p,s,r,message));
 }
 /** 부작용이 없었던 것이 확실한 기록을 실패로 닫는다. 이 기록마저 실패하면 prepared 는 다음에 자동으로 닫히고, paying 은 관리자 목록에 남으므로 무엇을 하면 되는지 로그에 적는다. */
 private void abort(ShopStore.Order order,List<String> from,Runnable then){must(order.owner(),()->store.move(order.id(),from,"failed"),v->then.run(),m->{if(from.contains("paying"))plugin.getLogger().severe("SHOP REVIEW order="+order.id()+" player="+order.owner()+": 돈·아이템은 움직이지 않았으나 실패 기록을 남기지 못했습니다. /상점관리 처리 "+order.id()+" 취소 로 닫으세요.");then.run();});}
 /** 운영 기록 (메모 #92): 끝까지 성공한 구매·판매만. sample = 거래한 아이템 한 개. */
 private static void audit(ShopStore.Order o,String shop,ItemStack sample){
  boolean sell=o.mode().equals("sell");String item=sample==null?o.product():sample.getType().name();String kind=o.exchange()?"교환":sell?"판매":"구매";
  AuditStats.add(o.owner(),"상점"+kind,1);
  AuditLog.log(o.owner(),AuditLog.상점,kind+" · "+o.title()+" · "+o.product()+" x"+o.quantity()+" · "+(o.exchange()?"비용 "+o.cost()+" x"+o.total():"합계 "+o.total()),
   "방식",kind,"상점",shop,"상점이름",o.title(),"상품",o.product(),"아이템",item,"수량",o.quantity(),"합계",o.total(),"비용아이템",o.cost(),"거래번호",o.id());
 }
 /** 교환: 비용 아이템 회수와 영수증(PDC)을 같은 틱·같은 플레이어 데이터에 기록하고 저장한다 (판매와 같은 방식). 이후는 exchangeSaved. */
 private void exchange(Player p,ShopStore.Order order,ItemStack[] after,Runnable ok,Consumer<String> fail){
  try{p.getInventory().setStorageContents(after);p.getPersistentDataContainer().set(saleKey(order.id()),PersistentDataType.BYTE,(byte)1);p.saveData();}catch(RuntimeException e){plugin.getLogger().log(Level.WARNING,"Shop exchange save failed for order "+order.id(),e);fail.accept("교환 아이템 저장을 확인해야 합니다. 상점을 다시 열면 자동으로 확인합니다.");return;}
  exchangeSaved(p,order,ok,fail);
 }
 /** 비용 회수가 디스크에 저장된 것을 확인한 뒤에만 prepared -> paid, 그다음 우편 지급(paid -> done, 멱등)과 영수증 정리. */
 private void exchangeSaved(Player p,ShopStore.Order order,Runnable ok,Consumer<String> fail){var file=MailboxBridge.playerFile(p);String key=saleKey(order.id()).toString();must(order.owner(),()->{if(!DeliveryReceipt.saved(file,key))throw new IllegalStateException("교환 아이템 저장 확인이 필요합니다. 잠시 뒤 상점을 다시 열면 자동으로 확인합니다.");store.state(order.id(),"prepared","paid");return true;},v->deliver(order,()->{clearReceipt(order);ok.run();},m->fail.accept("교환은 기록되었습니다. 상품 우편은 상점을 다시 열거나 접속할 때 자동으로 보냅니다.")),fail);}
 private void buy(Player p,Session s,Request r,ShopStore.Order order,ItemStack sample){
  String id=order.id();UUID owner=order.owner();
  // 출금 전에 paying 을 먼저 기록한다. 이 기록이 실패하면 출금하지 않는다.
  reverts.put(id,()->store.move(id,List.of("prepared","paying"),"failed"));
  must(owner,()->{store.state(id,"prepared","paying");return true;},armed->{
   reverts.remove(id);
   if(!ready(p)||!near(p,s.anchor)){abort(order,List.of("paying"),()->done(p,s,r,"접속 상태가 변경되어 결제를 취소했습니다."));return;}
   // money() 가 직접 던지는 IllegalStateException 은 경제 서비스 호출 전의 사전 검사 실패다: 출금되지 않은 것이 확실하다.
   boolean paid;try{paid=money(p,order.total(),false);}catch(IllegalStateException e){String why=String.valueOf(e.getMessage());abort(order,List.of("paying"),()->done(p,s,r,why));return;}catch(Exception e){plugin.getLogger().log(Level.SEVERE,"SHOP REVIEW order="+id+" player="+owner+" state=paying: 출금 결과를 알 수 없습니다 (total="+order.total()+")",e);done(p,s,r,"결제 결과 확인이 필요합니다. 자동으로 다시 결제하지 않습니다. 관리자에게 문의해 주세요.");return;}
   if(!paid){abort(order,List.of("paying"),()->done(p,s,r,"잔액이 부족하거나 결제가 거절되었습니다."));return;}
   must(owner,()->{store.state(id,"paying","paid");return true;},v->deliver(order,()->{audit(order,s.shop,sample);done(p,s,r,"구매가 완료되었습니다.",true);},m->done(p,s,r,m)),m->{plugin.getLogger().severe("SHOP REVIEW order="+id+" player="+owner+" state=paying: 출금은 완료했으나 paid 기록에 실패했습니다 (total="+order.total()+"). /상점관리 거래처리 "+id+" 완료 로 상품을 지급하세요.");done(p,s,r,"결제는 되었으나 기록 확인이 필요합니다. 다시 결제하지 말고 관리자에게 문의해 주세요.");});
  },m->{reverts.remove(id);abort(order,List.of("prepared","paying"),()->done(p,s,r,m));});
 }
 /** 아이템 회수와 영수증(PDC)을 같은 틱·같은 플레이어 데이터에 기록한 뒤, 디스크 저장을 확인하고 대금을 지급한다. */
 private void sell(Player p,ShopStore.Order order,ItemStack[] after,Runnable ok,Consumer<String> fail){
  try{p.getInventory().setStorageContents(after);p.getPersistentDataContainer().set(saleKey(order.id()),PersistentDataType.BYTE,(byte)1);p.saveData();}catch(RuntimeException e){plugin.getLogger().log(Level.WARNING,"Shop sale save failed for order "+order.id(),e);fail.accept("판매 저장을 확인해야 합니다. 상점을 다시 열면 자동으로 확인합니다.");return;}
  saleSaved(p,order,ok,fail);
 }
 private void saleSaved(Player p,ShopStore.Order order,Runnable ok,Consumer<String> fail){var file=MailboxBridge.playerFile(p);String key=saleKey(order.id()).toString();must(order.owner(),()->{if(!DeliveryReceipt.saved(file,key))throw new IllegalStateException("판매 아이템 저장 확인이 필요합니다. 잠시 뒤 상점을 다시 열면 자동으로 확인합니다.");store.state(order.id(),"prepared","sale_removed");return true;},v->credit(order,ok,fail),fail);}
 /** sale_removed -> credit_pending -> (입금) -> done. 입금은 credit_pending 이 기록된 뒤에만 실행한다. */
 private void credit(ShopStore.Order order,Runnable ok,Consumer<String> fail){
  String id=order.id();UUID owner=order.owner();
  reverts.put(id,()->store.move(id,List.of("credit_pending"),"sale_removed"));
  must(owner,()->{store.state(id,"sale_removed","credit_pending");return true;},armed->{
   reverts.remove(id);
   boolean paid;try{paid=money(Bukkit.getOfflinePlayer(owner),order.total(),true);}catch(IllegalStateException e){String why=String.valueOf(e.getMessage());must(owner,()->store.move(id,List.of("credit_pending"),"sale_removed"),v->fail.accept(why),fail);return;}catch(Exception e){plugin.getLogger().log(Level.SEVERE,"SHOP REVIEW order="+id+" player="+owner+" state=credit_pending: 입금 결과를 알 수 없습니다 (total="+order.total()+")",e);fail.accept("돈 지급 결과 확인이 필요합니다. 자동으로 다시 지급하지 않습니다. 관리자에게 문의해 주세요.");return;}
   if(!paid){must(owner,()->store.move(id,List.of("credit_pending"),"sale_removed"),v->fail.accept("돈 지급이 거절되었습니다. 상점을 다시 열면 자동으로 다시 시도합니다."),fail);return;}
   must(owner,()->{store.state(id,"credit_pending","done");return true;},v->{var live=Bukkit.getPlayer(owner);if(live!=null&&current(live)){live.getPersistentDataContainer().remove(saleKey(id));live.saveData();}ok.run();},m->{plugin.getLogger().severe("SHOP REVIEW order="+id+" player="+owner+" state=credit_pending: 입금은 완료했으나 done 기록에 실패했습니다 (total="+order.total()+"). /상점관리 거래처리 "+id+" 완료 로 닫으세요.");fail.accept("판매 대금은 지급되었으나 기록 확인이 필요합니다. 관리자에게 문의해 주세요.");});
  },m->{reverts.remove(id);fail.accept(m);});
 }
 private List<ItemStack> stacks(ShopStore.Order order){var sample=plugin.coreAppearance(ItemStack.deserializeBytes(order.bytes()));var attachments=new ArrayList<ItemStack>();int left=order.quantity();while(left>0){var stack=sample.clone();stack.setAmount(Math.min(left,sample.getMaxStackSize()));left-=stack.getAmount();attachments.add(stack);}return attachments;}
 /** paid -> (우편) -> done. 우편은 source key "shop:<거래ID>" 로 멱등이라 몇 번을 다시 해도 한 통만 생긴다. */
 private void deliver(ShopStore.Order order,Runnable ok,Consumer<String> fail){
  MailService mail=Bukkit.getServicesManager().load(MailService.class);if(mail==null){fail.accept("결제 완료. 우편 저장 확인이 필요합니다. 다시 결제하지 마세요.");return;}
  CompletableFuture<UUID> sent;try{sent=mail.sendSystem("shop:"+order.id(),order.owner(),order.title().substring(0,Math.min(76,order.title().length()))+" 구매",order.quantity()+"개 상품을 구매했습니다.",stacks(order));}catch(RuntimeException e){plugin.getLogger().log(Level.WARNING,"Shop delivery mail failed for order "+order.id(),e);fail.accept("결제 완료. 우편 저장 확인이 필요합니다. 다시 결제하지 마세요.");return;}
  sent.whenComplete((id,error)->main(()->guard(order.owner(),()->{if(error!=null){fail.accept("결제 완료. 우편 저장 확인이 필요합니다. 다시 결제하지 마세요.");return;}must(order.owner(),()->{store.state(order.id(),"paid","done");return true;},v->ok.run(),fail);})));
 }
 /** busy 는 호출한 쪽이 잡고 있어야 한다. then 에는 유저에게 보여 줄 안내(없으면 빈 문자열)가 온다. */
 private void recover(Player p,Consumer<String> then){UUID owner=p.getUniqueId();work(owner,()->store.open(owner),orders->recoverNext(p,new ArrayDeque<>(orders),new int[2],then),m->then.accept(""));}
 private void recoverNext(Player p,Deque<ShopStore.Order> queue,int[] left,Consumer<String> then){
  if(queue.isEmpty()||!current(p)){then.accept(left[0]>0?"이전 거래 기록을 관리자가 확인해야 합니다. 관리자에게 문의해 주세요.":left[1]>0?"다른 서버에서 하던 판매가 남아 있습니다. 그 서버에 접속하면 자동으로 정리됩니다.":"");return;}
  var o=queue.poll();UUID owner=o.owner();boolean mine=origin.equals(o.origin());Runnable next=()->recoverNext(p,queue,left,then);Consumer<String> held=m->next.run();/* 자동 처리가 이번에 안 되면 기록은 그대로 두고 다음에 다시 시도한다 */
  switch(o.mode()+"/"+o.state()){
   case "buy/prepared"->{if(o.origin().isEmpty()){left[0]++;next.run();}else must(owner,()->store.move(o.id(),List.of("prepared"),"failed"),v->{if(v)plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") prepared -> failed (출금 전)");next.run();},held);}
   case "buy/paid"->deliver(o,()->{CmdKit.notify(p,"shop","shop-recover-paid","이전에 결제한 상품을 우편으로 보냈어요","우편함을 확인해 주세요.",0);plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") paid -> done (우편 발송)");next.run();},held);
   case "exchange/paid"->deliver(o,()->{clearReceipt(o);CmdKit.notify(p,"shop","shop-recover-paid","이전에 교환한 상품을 우편으로 보냈어요","우편함을 확인해 주세요.",0);plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") exchange paid -> done (우편 발송)");next.run();},held);
   case "exchange/prepared"->{
    if(!mine){left[o.origin().isEmpty()?0:1]++;next.run();}
    else if(p.getPersistentDataContainer().has(saleKey(o.id()),PersistentDataType.BYTE))exchangeSaved(p,o,()->{CmdKit.notify(p,"shop","shop-recover-paid","이전에 교환한 상품을 우편으로 보냈어요","우편함을 확인해 주세요.",0);plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") exchange prepared -> done (비용 회수 확인, 우편 발송)");next.run();},held);
    else must(owner,()->store.move(o.id(),List.of("prepared"),"failed"),v->{if(v)plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") exchange prepared -> failed (비용 회수 전)");next.run();},held);
   }
   case "sell/prepared"->{
    if(!mine){left[o.origin().isEmpty()?0:1]++;next.run();}
    else if(p.getPersistentDataContainer().has(saleKey(o.id()),PersistentDataType.BYTE))saleSaved(p,o,()->{CmdKit.notify(p,"shop","shop-recover-sale","이전 판매 대금을 받았어요",o.total()+" G",0);plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") prepared -> done (판매 대금 지급)");next.run();},held);
    else must(owner,()->store.move(o.id(),List.of("prepared"),"failed"),v->{if(v)plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") prepared -> failed (아이템 회수 전)");next.run();},held);
   }
   case "sell/sale_removed"->{if(!mine){left[o.origin().isEmpty()?0:1]++;next.run();}else credit(o,()->{CmdKit.notify(p,"shop","shop-recover-sale","이전 판매 대금을 받았어요",o.total()+" G",0);plugin.getLogger().info("Shop recovery: order "+o.id()+" ("+owner+") sale_removed -> done (판매 대금 지급)");next.run();},held);}
   default->{left[0]++;next.run();}
  }
 }
 private void recoverLater(Player p,int attempt){if(closing)return;Bukkit.getScheduler().runTaskLater(plugin,()->{if(closing||!current(p))return;UUID owner=p.getUniqueId();if(!ready(p)||busy.contains(owner)){if(attempt<5)recoverLater(p,attempt+1);return;}busy.add(owner);recover(p,m->{busy.remove(owner);if(!m.isEmpty()&&current(p))CmdKit.notify(p,"warning","shop-recover","상점",m,3000);});},100L);}
 @EventHandler public void join(PlayerJoinEvent e){recoverLater(e.getPlayer(),0);}
 private void done(Player p,Session s,Request r,String message){done(p,s,r,message,false);}private void done(Player p,Session s,Request r,String message,boolean success){busy.remove(p.getUniqueId());var latest=sessions.get(p.getUniqueId());if(current(p)&&latest!=null&&latest.token==s.token&&latest.sequence==r.sequence())send(p,latest,r.sequence(),message,r.search(),success?r.operation():"");}
 @SuppressWarnings("unchecked")private void citizens(){var dep=Bukkit.getPluginManager().getPlugin("Citizens");if(dep==null)return;try{Class<? extends Event>type=(Class<? extends Event>)Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent",true,dep.getClass().getClassLoader());Bukkit.getPluginManager().registerEvent(type,this,EventPriority.HIGH,(l,event)->{try{var npc=event.getClass().getMethod("getNPC").invoke(event);int npcId=((Number)npc.getClass().getMethod("getId").invoke(npc)).intValue();if(plugin.dialogueBridge()!=null&&plugin.dialogueBridge().claimed(npcId))return;String id="citizens:"+npcId;String shop=bindings.get(id);if(shop!=null){var p=(Player)event.getClass().getMethod("getClicker").invoke(event);var anchor=(Entity)npc.getClass().getMethod("getEntity").invoke(npc);if(event instanceof Cancellable c)c.setCancelled(true);open(p,shop,anchor);}}catch(Exception e){plugin.getLogger().warning("Shop NPC event unavailable");}},plugin,true);}catch(Exception e){plugin.getLogger().warning("Shop Citizens hook unavailable");}}
 @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)public void interact(PlayerInteractEntityEvent e){if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||e.getRightClicked().hasMetadata("NPC"))return;for(String tag:e.getRightClicked().getScoreboardTags()){String shop=bindings.get("tag:"+tag);if(shop!=null){e.setCancelled(true);open(e.getPlayer(),shop,e.getRightClicked());break;}}}

 /** NPC 칸 후보: Citizens 번호(숫자만)와 이 서버의 태그 연결. */
 private List<String> completionNpcKeys=List.of();
 private void refreshCompletionKeys(){
  var keys=new TreeSet<String>(bindings.keySet());
  var dep=Bukkit.getPluginManager().getPlugin("Citizens");
  if(dep!=null&&dep.isEnabled())try{
   ClassLoader loader=dep.getClass().getClassLoader();
   Object registry=Class.forName("net.citizensnpcs.api.CitizensAPI",true,loader).getMethod("getNPCRegistry").invoke(null);
   var idMethod=Class.forName("net.citizensnpcs.api.npc.NPC",true,loader).getMethod("getId");
   if(registry instanceof Iterable<?> npcs)for(Object npc:npcs){
    if(keys.size()>=4096)break;
    int id=((Number)idMethod.invoke(npc)).intValue();
    if(id>=0&&id<=999999999)keys.add("citizens:"+id);
   }
  }catch(Exception|LinkageError ignored){}
  completionNpcKeys=List.copyOf(keys);
 }
 /** 명령 칸에는 Citizens NPC 번호만 적는다 (7 → citizens:7). citizens:7·tag:이름 도 그대로 받는다. */
 static String npcKey(String input){return input.matches("[0-9]{1,9}")?"citizens:"+input:input;}
 static String npcShown(String key){return key.startsWith("citizens:")?key.substring(9):key;}
 private static String modeText(ShopStore.Order o){return o.exchange()?"교환":o.mode().equals("buy")?"구매":"판매";}
 private static String stateText(ShopStore.Order o){boolean buy=o.mode().equals("buy");if(o.exchange()&&o.state().equals("paid"))return "비용 회수됨·상품 미지급(paid)";return switch(o.state()){case "prepared"->buy?"출금 전":"아이템 회수 전후";case "paying"->"출금 확인 필요";case "paid"->"결제됨·상품 미지급";case "sale_removed"->"아이템 회수됨·대금 미지급";case "credit_pending"->"대금 지급 확인 필요";case "refunding"->"환불 처리 중";default->o.state();}+"("+o.state()+")";}
 private String guide(ShopStore.Order o){boolean buy=o.mode().equals("buy"),legacy=o.origin().isEmpty();
  if(o.exchange()&&o.state().equals("prepared"))return "교환한 서버에 유저가 접속하면 자동으로 판정합니다. 직접 처리: 비용 아이템이 사라졌으면 완료(상품 우편 지급) 또는 환불(비용 아이템 우편 반환), 그대로면 취소.";
  if(o.exchange()&&o.state().equals("paid"))return "비용 아이템은 회수됐습니다. 유저가 접속하거나 상점을 열면 자동으로 우편 지급합니다. 완료로 바로 지급, 환불로 비용 아이템을 돌려줄 수 있습니다.";
  return switch(o.state()){
  case "prepared"->buy?(legacy?"옛 형식 기록입니다. 돈이 빠졌는지 확인 → 빠졌으면 완료(상품 우편 지급) 또는 환불(돈 반환), 아니면 취소.":"출금 전 기록입니다. 유저가 접속하거나 상점을 열면 자동으로 닫힙니다. 취소로 바로 닫아도 됩니다."):"거래한 서버에 유저가 접속하면 자동으로 판정합니다. 직접 처리: 아이템이 사라졌으면 환불(아이템 우편 반환), 그대로면 취소.";
  case "paying"->"출금 직전·직후에 멈췄습니다. 잔액·로그(SHOP REVIEW)로 확인 → 돈이 빠졌으면 완료(상품 우편 지급) 또는 환불(돈 반환), 안 빠졌으면 취소.";
  case "paid"->"유저가 접속하거나 상점을 열면 자동으로 우편 지급합니다. 완료로 바로 지급할 수 있습니다.";
  case "sale_removed"->"거래한 서버에 유저가 접속하면 자동으로 대금을 지급합니다. 직접 처리: 환불(아이템 우편 반환).";
  case "credit_pending"->"대금 지급 직전·직후에 멈췄습니다. 잔액·로그로 확인 → 지급됐으면 완료(기록만 닫음), 안 됐으면 환불(아이템 우편 반환).";
  case "refunding"->"환불 도중 멈췄습니다. 환불을 다시 실행하거나, 이미 돌려줬으면 취소로 닫으세요.";
  default->"알 수 없는 상태입니다. 확인 후 취소로 닫을 수 있습니다.";};}
 private void records(CommandSender sender,UUID owner){work(null,()->store.open(owner),orders->{
  var out=new ArrayList<String>();out.add("── "+CmdKit.name(owner)+" 미완료 거래 "+orders.size()+"건 ──");listed.put(sender.getName(),orders.stream().map(ShopStore.Order::id).toList());
  for(var o:orders){String item;try{item=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(ItemStack.deserializeBytes(o.bytes()).displayName());}catch(RuntimeException e){item=o.product();}
   out.add(o.id()+" | "+modeText(o)+" | "+stateText(o)+" | "+item+" "+o.quantity()+"개 · "+(o.exchange()?"비용 "+o.cost()+" ×"+o.total():"합계 "+o.total())+" | 상점 "+o.title()+" | "+QuestBridge.timeText(o.created())+" | "+(o.origin().isEmpty()?"서버 기록 없음(옛 기록)":o.origin().equals(origin)?"이 서버":"다른 서버"));out.add("  → "+guide(o));}
  if(!orders.isEmpty())out.add("처리: /상점관리 거래처리 <거래번호> 완료|환불|취소");
  CmdKit.lines(sender,out);
 },m->CmdKit.fail(sender,"거래 기록을 불러오지 못했어요",new IllegalStateException(m)));}
 /** 관리자 처리. 상태 변경은 store.adminMove 의 비교-교체 한 번으로만 일어나므로 같은 처리를 두 번 실행해도 보상은 한 번만 나간다. */
 private void resolve(CommandSender sender,String id,String action){
  work(null,()->store.order(id),found->{
   if(found==null){CmdKit.userFail(sender,"거래 기록이 없어요. /유저관리 <닉네임> 거래기록 으로 번호를 확인해 주세요");return;}
   UUID owner=found.owner();boolean buy=found.mode().equals("buy"),legacy=found.origin().isEmpty();String admin=sender.getName();
   // 새 형식 구매의 prepared 는 출금 전이 보장된다: 완료(상품 무료 지급)·환불(돈 무료 지급)은 막고 취소만 허용한다. 아래 from 목록에서도 빼 두어 조회 뒤 상태가 바뀌어도 안전하다.
   // 판매 prepared 는 아이템 회수가 prepared 상태에서 일어나므로(회수 전후를 알 수 없음) 기존 처리를 그대로 둔다.
   if(buy&&!legacy&&found.state().equals("prepared")&&(action.equals("완료")||action.equals("환불"))){CmdKit.userFail(sender,"출금 전 구매 기록이라 "+action+" 처리는 할 수 없어요. 취소로 닫아 주세요");return;}
   if(busy.contains(owner)){CmdKit.userFail(sender,"이 유저의 거래를 지금 처리하는 중이에요. 잠시 뒤 다시 해 주세요");return;}
   busy.add(owner);Consumer<String> finish=m->{busy.remove(owner);CmdKit.lines(sender,List.of("── 거래처리 "+id+" ──",m));plugin.getLogger().warning("SHOP ADMIN admin="+admin+" player="+owner+" order="+id+" action="+action+" result="+m);};
   Consumer<ShopStore.Order> audit=o->plugin.getLogger().warning("SHOP ADMIN admin="+admin+" player="+owner+" order="+id+" action="+action+" mode="+o.mode()+" from="+o.state()+" qty="+o.quantity()+" total="+o.total()+(o.cost().isEmpty()?"":" cost="+o.cost()));
   if(found.exchange()){exchangeResolve(found,action,finish,audit);return;}
   switch(action){
    case "완료"->{
     if(buy)must(owner,()->store.adminMove(id,legacy?List.of("prepared","paying","paid"):List.of("paying","paid"),"paid"),o->{audit.accept(o);deliver(o,()->finish.accept("상품을 우편으로 지급하고 거래를 완료했습니다."),m->finish.accept("결제됨(paid)으로 기록했습니다. 우편 발송은 유저 접속·상점 열기 때 자동으로 다시 시도합니다: "+m));},finish);
     else must(owner,()->store.adminMove(id,List.of("prepared","sale_removed","credit_pending"),"done"),o->{audit.accept(o);finish.accept("판매 거래를 완료로 닫았습니다. (추가 지급 없음)");},finish);
    }
    case "환불"->must(owner,()->store.adminMove(id,buy?(legacy?List.of("prepared","paying","refunding"):List.of("paying","refunding")):List.of("prepared","sale_removed","credit_pending","refunding"),"refunding"),o->{audit.accept(o);
      if(buy){boolean paid;try{paid=money(Bukkit.getOfflinePlayer(owner),o.total(),true);}catch(IllegalStateException e){finish.accept("입금하지 못했습니다("+e.getMessage()+"). 기록은 '환불 처리 중'으로 남깁니다. 해결한 뒤 환불을 다시 실행하세요.");return;}catch(Exception e){plugin.getLogger().log(Level.SEVERE,"SHOP ADMIN refund result unknown order="+id+" player="+owner+" total="+o.total(),e);finish.accept("입금 결과를 확인할 수 없습니다. 기록은 '환불 처리 중'으로 남깁니다. 잔액을 확인해 지급됐으면 취소, 아니면 환불을 다시 실행하세요.");return;}
       if(!paid){finish.accept("입금이 거절되었습니다. 기록은 '환불 처리 중'으로 남깁니다. 경제 플러그인을 확인한 뒤 환불을 다시 실행하세요.");return;}
       plugin.getLogger().warning("SHOP ADMIN REFUND PAID admin="+admin+" player="+owner+" order="+id+" total="+o.total());
       must(owner,()->{store.state(id,"refunding","failed");return true;},v->finish.accept(o.total()+" 을(를) 돌려주고 거래를 실패로 닫았습니다."),m->finish.accept("돈은 돌려줬으나 기록을 닫지 못했습니다. 환불을 다시 실행하지 말고 /상점관리 거래처리 "+id+" 취소 로 닫으세요."));
      }else{MailService mail=Bukkit.getServicesManager().load(MailService.class);if(mail==null){finish.accept("우편 기능을 사용할 수 없습니다. 기록은 '환불 처리 중'으로 남깁니다. 환불을 다시 실행하세요.");return;}
       CompletableFuture<UUID> sent;try{sent=mail.sendSystem("shop-refund:"+id,owner,o.title().substring(0,Math.min(72,o.title().length()))+" 판매 취소","판매가 취소되어 아이템 "+o.quantity()+"개를 돌려드립니다.",stacks(o));}catch(RuntimeException e){finish.accept("우편을 만들지 못했습니다("+e.getClass().getSimpleName()+"). 기록은 '환불 처리 중'으로 남깁니다.");return;}
       sent.whenComplete((mailId,error)->main(()->guard(owner,()->{if(error!=null){finish.accept("우편 저장에 실패했습니다. 기록은 '환불 처리 중'으로 남깁니다. 환불을 다시 실행하세요. (같은 우편이 두 번 생기지 않습니다)");return;}must(owner,()->{store.state(id,"refunding","failed");return true;},v->finish.accept("아이템 "+o.quantity()+"개를 우편으로 돌려주고 거래를 실패로 닫았습니다."),m->finish.accept("아이템은 우편으로 돌려줬으나 기록을 닫지 못했습니다. 환불을 다시 실행하면 닫힙니다. (우편은 중복되지 않습니다)"));})));
      }},finish);
    case "취소"->must(owner,()->store.adminMove(id,List.of("prepared","paying","sale_removed","credit_pending","refunding"),"failed"),o->{audit.accept(o);finish.accept("보상 없이 거래를 실패로 닫았습니다.");},finish);
    default->finish.accept("처리 방법은 완료, 환불, 취소 중 하나입니다.");
   }
  },m->CmdKit.fail(sender,"거래 기록을 불러오지 못했어요",new IllegalStateException(m)));
 }

 /** 교환 거래 관리자 처리: 완료 = 상품 우편 지급, 환불 = 비용 아이템 우편 반환(멱등 출처 키), 취소 = 보상 없이 닫기. 상태 변경은 adminMove 한 번. */
 private void exchangeResolve(ShopStore.Order found,String action,Consumer<String> finish,Consumer<ShopStore.Order> audit){
  String id=found.id();UUID owner=found.owner();
  switch(action){
   case "완료"->must(owner,()->store.adminMove(id,List.of("prepared","paid"),"paid"),o->{audit.accept(o);deliver(o,()->{clearReceipt(o);finish.accept("상품을 우편으로 지급하고 교환을 완료했습니다.");},m->finish.accept("paid 로 기록했습니다. 우편은 유저 접속·상점 열기 때 자동으로 다시 시도합니다: "+m));},finish);
   case "환불"->must(owner,()->store.adminMove(id,List.of("prepared","paid","refunding"),"refunding"),o->{audit.accept(o);
     MailService mail=Bukkit.getServicesManager().load(MailService.class);int total;try{total=Integer.parseInt(o.total());}catch(NumberFormatException e){total=0;}var stacks=total>0?costStacks(o.cost(),total):null;
     if(mail==null||stacks==null){finish.accept((mail==null?"우편 기능을 사용할 수 없습니다":"비용 아이템("+o.cost()+")을 만들 수 없습니다 (ItemsAdder 확인)")+". 기록은 '환불 처리 중'으로 남깁니다. 환불을 다시 실행하세요.");return;}
     CompletableFuture<UUID> sent;try{sent=mail.sendSystem("shop-refund:"+id,owner,o.title().substring(0,Math.min(72,o.title().length()))+" 교환 취소","교환이 취소되어 "+costName(o.cost())+" "+total+"개를 돌려드립니다.",stacks);}catch(RuntimeException e){finish.accept("우편을 만들지 못했습니다("+e.getClass().getSimpleName()+"). 기록은 '환불 처리 중'으로 남깁니다.");return;}
     int count=total;sent.whenComplete((mailId,error)->main(()->guard(owner,()->{if(error!=null){finish.accept("우편 저장에 실패했습니다. 기록은 '환불 처리 중'으로 남깁니다. 환불을 다시 실행하세요. (같은 우편이 두 번 생기지 않습니다)");return;}must(owner,()->{store.state(id,"refunding","failed");return true;},v->{clearReceipt(o);finish.accept("비용 아이템 "+count+"개를 우편으로 돌려주고 교환을 실패로 닫았습니다.");},m->finish.accept("비용 아이템은 돌려줬으나 기록을 닫지 못했습니다. 환불을 다시 실행하면 닫힙니다. (우편은 중복되지 않습니다)"));})));
    },finish);
   case "취소"->must(owner,()->store.adminMove(id,List.of("prepared","paid","refunding"),"failed"),o->{audit.accept(o);clearReceipt(o);finish.accept("보상 없이 교환 기록을 실패로 닫았습니다.");},finish);
   default->finish.accept("처리 방법은 완료, 환불, 취소 중 하나입니다.");
  }
 }

 // ------------------------------------------------------------------ 명령 (2026-10-20): /상점관리 · /유저관리 <닉네임> 거래기록

 private Collection<String> shopIds(){return catalog.keySet();}
 private Collection<String> targets(){var out=new ArrayList<String>(new TreeSet<>(catalog.keySet()));out.add(ShopNpcTarget.LABEL);return out;}
 private static final List<String> PRICE_HINTS=List.of("없음","0","100","1000");
 private static String price(String input)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  String v=input.equals("없음")?"off":input;
  try{return ShopStore.price(v);}catch(NumberFormatException e){throw ChacaApi.error("가격은 숫자나 '없음'으로 적어 주세요");}catch(IllegalArgumentException e){throw ChacaApi.error(e.getMessage());}
 }
 private ShopStore.Shop shop(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  String id=CmdKit.text(ctx,"상점");var shop=catalog.get(id);if(shop==null)throw ChacaApi.error("상점을 찾을 수 없어요: "+id);return shop;
 }
 private static Map<String,String> productNames(ShopStore.Shop shop){var out=new LinkedHashMap<String,String>();for(var p:shop.products())out.put(p.id(),p.name());return out;}
 /** 저장소 작업 → 성공 문구 알림 + 카탈로그 새로고침. 입력 문제(IllegalArgument/State)는 유저 실패, 그 밖은 콘솔. */
 private void admin(CommandSender sender,Callable<String> task){
  if(io.getQueue().size()>=QUEUE_LIMIT){CmdKit.userFail(sender,"상점이 바빠요. 잠시 뒤 다시 해 주세요");return;}
  try{io.execute(()->{try{String m=task.call();main(()->{CmdKit.ok(sender,m);refresh();});}catch(IllegalArgumentException|IllegalStateException e){main(()->CmdKit.userFail(sender,e.getMessage()==null?"입력을 확인해 주세요":e.getMessage()));}catch(Exception e){main(()->CmdKit.fail(sender,"상점 설정을 저장하지 못했어요",e));}});}
  catch(RejectedExecutionException e){CmdKit.userFail(sender,"상점이 종료 중이에요");}
 }
 private void commands(){
  var shopArg=(java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack,String>>)()->CmdKit.word("상점",this::shopIds);
  var root=CmdKit.literal("목록").executes(CmdKit.run(ctx->{var out=new ArrayList<String>();out.add("── 상점 ("+catalog.size()+"개) ──");catalog.values().stream().sorted(Comparator.comparing(ShopStore.Shop::id)).forEach(sh->out.add(sh.id()+" · "+sh.title()+" · 상품 "+sh.products().size()+"개 · 초상 "+sh.portrait()));CmdKit.lines(CmdKit.sender(ctx),out);}))
   .then(shopArg.get().executes(CmdKit.run(ctx->{var sh=shop(ctx);var out=new ArrayList<String>();out.add("── "+sh.title()+" ("+sh.id()+") 상품 ──");for(var pr:sh.products())out.add((pr.category().isEmpty()?"":"["+pr.category()+" "+pr.sort()+"] ")+pr.name()+(pr.exchange()?" · 교환 "+pr.costItem()+" ×"+pr.costCount()+" (판매 없음)":" · 구매 "+(pr.buy().isEmpty()?"없음":pr.buy())+" · 판매 "+(pr.sell().isEmpty()?"없음":pr.sell()))+" · "+pr.id());if(sh.products().isEmpty())out.add("(상품 없음)");CmdKit.lines(CmdKit.sender(ctx),out);})));
  ChacaApi.branch(plugin,ChacaApi.상점관리,root,"상점 목록·상품 목록","목록");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("만들기").then(CmdKit.word("상점ID",List::of).then(CmdKit.argument("이름",com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(CmdKit.run(ctx->{
   String id=CmdKit.text(ctx,"상점ID"),title=com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"이름").strip();
   if(!id.matches("[a-z0-9_-]{1,48}"))throw ChacaApi.error("상점 ID는 영문 소문자·숫자·_·- 로 48자까지예요");
   if(title.isEmpty()||title.length()>80)throw ChacaApi.error("이름은 1~80자예요");
   admin(CmdKit.sender(ctx),()->{store.create(id,title);return "상점 "+title+"("+id+")을(를) 만들었어요";});
  })))),"새 상점 만들기","만들기 ");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("열기").then(shopArg.get().executes(CmdKit.run(ctx->adminOpen(ctx,CmdKit.self(ctx)))).then(CmdKit.player("닉네임").executes(CmdKit.run(ctx->adminOpen(ctx,ChacaApi.resolveOnline(ctx,"닉네임")))))),"상점 창 열기 (NPC 없이)","열기 ");
  var add=CmdKit.literal("상품추가").then(shopArg.get().then(CmdKit.word("구매가",()->PRICE_HINTS).then(CmdKit.word("판매가",()->PRICE_HINTS).executes(CmdKit.run(ctx->{
   var sh=shop(ctx);Player p=CmdKit.self(ctx);String buy=price(CmdKit.text(ctx,"구매가")),sell=price(CmdKit.text(ctx,"판매가"));
   var item=p.getInventory().getItemInMainHand().clone();if(item.getType().isAir())throw ChacaApi.error("등록할 아이템을 주 손에 들어 주세요");item.setAmount(1);
   byte[] bytes=item.serializeAsBytes();if(bytes.length>32768)throw ChacaApi.error("아이템 데이터가 너무 커요");
   String name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.displayName());if(name.length()>120)name=name.substring(0,120);String finalName=name;
   admin(CmdKit.sender(ctx),()->{store.add(sh.id(),finalName,bytes,buy,sell);return sh.title()+"에 "+finalName+"을(를) 올렸어요";});
  })))));
  ChacaApi.branch(plugin,ChacaApi.상점관리,add,"손에 든 아이템을 상품으로 올리기","상품추가 ");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("가져오기").then(CmdKit.word("파일",()->ShopImportFile.names(plugin)).executes(CmdKit.run(ctx->{
   var file=ShopImportFile.load(plugin,CmdKit.text(ctx,"파일"));
   admin(CmdKit.sender(ctx),()->{var result=store.importProducts(file.shop(),file.title(),file.portrait(),file.owner(),file.role(),file.entries());return file.title()+"("+file.shop()+") "+(result.created()?"상점을 만들고 ":"")+"상품 "+result.added()+"개 추가 · "+result.updated()+"개 갱신"+(file.portrait().isEmpty()?" · 초상은 /상점관리 초상 "+file.shop()+" <이름> 으로 지정":"");});
  }))),"shops 폴더의 상품 목록 파일 한 번에 등록 (같은 이름은 가격·분류·순서만 갱신)","가져오기 ");
  var product=CmdKit.wordIn("상품",ctx->{var sh=catalog.get(CmdKit.text(ctx,"상점"));return sh==null?List.of():AdminCommandRules.catalogChoices(productNames(sh));});
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("가격").then(shopArg.get().then(product.then(CmdKit.word("구매가",()->PRICE_HINTS).then(CmdKit.word("판매가",()->PRICE_HINTS).executes(CmdKit.run(ctx->{
   var sh=shop(ctx);String id=CmdKit.pick(productNames(sh),CmdKit.text(ctx,"상품"),"상품");String buy=price(CmdKit.text(ctx,"구매가")),sell=price(CmdKit.text(ctx,"판매가"));
   admin(CmdKit.sender(ctx),()->{store.prices(sh.id(),id,buy,sell);return productNames(sh).get(id)+" 가격: 구매 "+(buy.isEmpty()?"없음":buy)+" · 판매 "+(sell.isEmpty()?"없음":sell);});
  })))))),"상품 구매가·판매가 바꾸기","가격 ");
  // 교환 상품 지정·해제 (2026-10-26, #98): /상점관리 교환 <상점> <상품> <아이템키> <개수|없음>
  var exchangeProduct=CmdKit.wordIn("상품",ctx->{var sh=catalog.get(CmdKit.text(ctx,"상점"));return sh==null?List.of():AdminCommandRules.catalogChoices(productNames(sh));});
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("교환").then(shopArg.get().then(exchangeProduct.then(CmdKit.word("아이템키",()->List.of("item:artifact_shard")).then(CmdKit.word("개수",()->List.of("없음","10","30","80")).executes(CmdKit.run(ctx->{
   var sh=shop(ctx);String id=CmdKit.pick(productNames(sh),CmdKit.text(ctx,"상품"),"상품");String countText=CmdKit.text(ctx,"개수");
   String key;int count;
   if(countText.equals("없음")){key="";count=0;}
   else{try{key=ShopExchange.key(CmdKit.text(ctx,"아이템키"));count=ShopExchange.count(Integer.parseInt(countText));}catch(NumberFormatException e){throw ChacaApi.error("개수는 숫자나 '없음'으로 적어 주세요");}catch(IllegalArgumentException e){throw ChacaApi.error(e.getMessage());}}
   String k=key;int n=count;
   admin(CmdKit.sender(ctx),()->{store.exchange(sh.id(),id,k,n);return productNames(sh).get(id)+(n==0?" 교환 해제 (원래 구매가·판매가로)":" 교환: "+k+" ×"+n+" (판매 막힘)");});
  })))))),"상품을 돈 대신 아이템으로 사는 교환 상품으로 (개수 '없음' = 해제)","교환 ");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("초상")
.then(shopArg.get().then(CmdKit.word("초상",()->new TreeSet<>(catalog.values().stream().map(ShopStore.Shop::portrait).toList())).executes(CmdKit.run(ctx->{
   var sh=shop(ctx);String id=CmdKit.text(ctx,"초상");if(!id.matches("[a-z0-9_/-]{1,120}")||id.contains(".."))throw ChacaApi.error("초상 ID는 영문 소문자·숫자·_·-·/ 만 써요");
   admin(CmdKit.sender(ctx),()->{store.portrait(sh.id(),id);return sh.title()+" 초상을 "+id+"(으)로 바꿨어요";});
  })))),"상점 초상 바꾸기","초상 ");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("NPC연결").then(CmdKit.word("상점",this::targets).then(CmdKit.word("NPC",()->completionNpcKeys.stream().map(ShopBridge::npcShown).toList()).executes(CmdKit.run(ctx->{
   String target=CmdKit.text(ctx,"상점"),key=npcKey(CmdKit.text(ctx,"NPC"));
   if(!ShopNpcTarget.fish(target)&&!catalog.containsKey(target))throw ChacaApi.error("상점을 찾을 수 없어요: "+target);
   if(!ShopNpcTarget.validKey(key))throw ChacaApi.error("NPC는 Citizens 번호로 적어 주세요 (예: 7)");
   admin(CmdKit.sender(ctx),()->{store.bind(origin,key,target);return "이 서버 NPC "+npcShown(key)+"을(를) "+ShopNpcTarget.label(target)+"에 연결했어요";});
  })))),"이 서버 NPC를 상점·물고기판매소에 연결","NPC연결 ");
  var bound=CmdKit.wordIn("NPC",ctx->{String t=ShopNpcTarget.canonical(CmdKit.text(ctx,"상점"));return bindings.entrySet().stream().filter(e->e.getValue().equals(t)).map(e->npcShown(e.getKey())).sorted().toList();});
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("NPC해제").then(CmdKit.word("상점",this::targets).then(bound.executes(CmdKit.run(ctx->{
   String target=CmdKit.text(ctx,"상점"),key=npcKey(CmdKit.text(ctx,"NPC"));
   if(!ShopNpcTarget.validKey(key))throw ChacaApi.error("NPC는 Citizens 번호로 적어 주세요 (예: 7)");
   var sender=CmdKit.sender(ctx);
   if(io.getQueue().size()>=QUEUE_LIMIT){CmdKit.userFail(sender,"상점이 바빠요. 잠시 뒤 다시 해 주세요");return;}
   work(null,()->store.unbind(origin,key,target),ok->{if(ok){CmdKit.ok(sender,"NPC "+npcShown(key)+" 연결을 풀었어요 ("+ShopNpcTarget.label(target)+")");refresh();}else CmdKit.userFail(sender,"그 NPC는 "+ShopNpcTarget.label(target)+"에 연결되어 있지 않아요");},m->CmdKit.fail(sender,"연결을 풀지 못했어요",new IllegalStateException(m)));
  })))),"NPC 연결 풀기","NPC해제 ");
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("NPC목록").executes(CmdKit.run(ctx->{
   var out=new ArrayList<String>();out.add("── 이 서버 상점 NPC ("+bindings.size()+") ──");
   bindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->out.add("NPC "+npcShown(e.getKey())+" → "+ShopNpcTarget.label(e.getValue())));
   if(bindings.isEmpty())out.add("(연결된 NPC 없음)");CmdKit.lines(CmdKit.sender(ctx),out);
  })),"이 서버의 NPC 연결 목록","NPC목록");
  var order=CmdKit.wordIn("거래번호",ctx->listed.getOrDefault(CmdKit.sender(ctx).getName(),List.of()));
  for(String action:List.of("완료","환불","취소"))order.then(CmdKit.literal(action).executes(CmdKit.run(ctx->{
   String id;try{id=UUID.fromString(CmdKit.text(ctx,"거래번호")).toString();}catch(IllegalArgumentException e){throw ChacaApi.error("거래번호를 확인해 주세요 (/유저관리 <닉네임> 거래기록)");}
   resolve(CmdKit.sender(ctx),id,action);
  })));
  ChacaApi.branch(plugin,ChacaApi.상점관리,CmdKit.literal("거래처리").then(order),"멈춘 거래 처리 (완료: 상품 지급 / 환불 / 취소)","거래처리 ");
  ChacaApi.branch(plugin,ChacaApi.유저관리,CmdKit.literal("거래기록").executes(CmdKit.run(ctx->records(CmdKit.sender(ctx),ChacaApi.target(ctx)))),"끝나지 않은 상점 거래 보기","거래기록");
  ChacaApi.reload(plugin,"상점","상점 목록·상품·NPC 연결 (DB에서 다시 읽기)",()->{version=-1;refresh();});
  ChacaApi.reload(plugin,"튜토리얼상점","tutorial-shop.yml (튜토리얼 전용 재료 상점·필요 재료)",this::loadTutorial);
  ChacaApi.branch(plugin,ChacaApi.유저관리,CmdKit.literal("튜토리얼상점")
   .then(CmdKit.literal("시작").executes(CmdKit.run(ctx->tutorialAdmin(ctx,"시작"))))
   .then(CmdKit.literal("다시").executes(CmdKit.run(ctx->tutorialAdmin(ctx,"다시"))))
   .then(CmdKit.literal("끝").executes(CmdKit.run(ctx->tutorialAdmin(ctx,"끝"))))
   .then(CmdKit.literal("보기").executes(CmdKit.run(ctx->tutorialAdmin(ctx,"보기")))),
   "튜토리얼 전용 재료 상점 대상 지정(시작·다시=구매 기록 새로)·해제(끝)·구매 현황(보기) — 스토리 시작명령용","튜토리얼상점 보기");
 }

 // ------------------------------------------------------------------ 튜토리얼 전용 재료 상점 (2026-10-25, 메모 #12)

 private void loadTutorial()throws Exception{
  var file=new java.io.File(plugin.getDataFolder(),"tutorial-shop.yml");if(!file.exists())plugin.saveResource("tutorial-shop.yml",false);
  var y=new org.bukkit.configuration.file.YamlConfiguration();y.load(file);tutorial=TutorialShop.parse(y);
  if(tutorial.enabled())plugin.getLogger().info("튜토리얼 재료 상점: "+tutorial.shop()+" (대신 열 상점 "+tutorial.general()+", 필요 재료 "+tutorial.required().size()+"종, 신호 "+tutorial.signal()+")");
 }
 /** 상점 창을 닫음: 튜토리얼 대상이 전용 상점을 닫았으면, 대상이 된 뒤 결제를 마친 구매로 필요 재료가 다 찼는지 확인하고 스토리 신호를 보낸다. */
 private void tutorialClosed(Player p,String shopId){
  UUID owner=p.getUniqueId();Long started=tutorialOpen.remove(owner);var cfg=tutorial;
  if(started==null||!cfg.enabled()||!shopId.equals(cfg.shop())||cfg.required().isEmpty())return;
  var shop=catalog.get(cfg.shop());if(shop==null)return;
  var missing=new ArrayList<String>();var need=TutorialShop.requiredIds(shop.products(),cfg.required(),missing);
  if(!missing.isEmpty()){plugin.getLogger().warning("튜토리얼 상점 "+cfg.shop()+" 에 없는(또는 구매 불가) 필요 재료: "+missing+" — 신호를 보내지 않습니다");return;}
  work(owner,()->store.bought(owner,need.keySet(),started),got->{
   if(!TutorialShop.complete(need,got)||!current(p))return;
   plugin.getLogger().info("튜토리얼 재료 구매 완료: "+p.getName()+" → 스토리 신호 "+cfg.signal());
   StoryProgressEvent.fire(p,cfg.signal());
  },m->plugin.getLogger().warning("튜토리얼 재료 구매 확인 실패 ("+p.getName()+"): "+m));
 }
 private void tutorialAdmin(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,String action)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  UUID owner=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);String who=CmdKit.name(owner);var cfg=tutorial;
  switch(action){
   case "시작","다시"->work(null,()->store.tutorialStart(owner,action.equals("다시")),at->{tutorialOpen.remove(owner);plugin.getLogger().info("튜토리얼 상점 대상 "+action+": "+who+" ("+owner+")");CmdKit.ok(sender,who+" 님은 이제 "+(cfg.enabled()?"튜토리얼 재료 상점("+cfg.shop()+")":"튜토리얼 재료 상점(설정 꺼짐: tutorial-shop.yml shop)")+"을 봐요"+(action.equals("다시")?" (구매 기록을 지금부터 다시 세요)":""));},m->CmdKit.fail(sender,"튜토리얼 상점 대상으로 지정하지 못했어요",new IllegalStateException(m)));
   case "끝"->work(null,()->store.tutorialEnd(owner),ok->{tutorialOpen.remove(owner);CmdKit.ok(sender,ok?who+" 님은 이제 일반 상점을 봐요":who+" 님은 튜토리얼 상점 대상이 아니었어요");},m->CmdKit.fail(sender,"튜토리얼 상점 대상을 풀지 못했어요",new IllegalStateException(m)));
   default->{
    var shop=cfg.enabled()?catalog.get(cfg.shop()):null;var missing=new ArrayList<String>();var need=shop==null?Map.<String,Integer>of():TutorialShop.requiredIds(shop.products(),cfg.required(),missing);
    work(null,()->{long at=store.tutorialStarted(owner);return new Object[]{at,at>0?store.bought(owner,need.keySet(),at):Map.<String,Integer>of()};},v->{
     long at=(Long)v[0];@SuppressWarnings("unchecked") var got=(Map<String,Integer>)v[1];var out=new ArrayList<String>();
     out.add("── "+who+" 튜토리얼 재료 상점 ──");
     out.add(at>0?" 대상 (구매 기록 시작 "+QuestBridge.timeText(at)+")":" 대상 아님 (일반 상점을 봐요)");
     out.add(" 설정: "+(cfg.enabled()?"상점 "+cfg.shop()+(shop==null?" (상점 없음!)":"")+" · 대신 열 상점 "+cfg.general()+" · 신호 "+cfg.signal():"꺼짐 (tutorial-shop.yml shop 비어 있음)"));
     if(cfg.required().isEmpty())out.add(" 필요 재료: 정하지 않음 (신호를 보내지 않아요)");
     if(shop!=null)for(var pr:shop.products())if(need.containsKey(pr.id()))out.add(" · "+pr.name()+" "+got.getOrDefault(pr.id(),0)+" / "+need.get(pr.id()));
     if(!missing.isEmpty())out.add(" ⚠ 상점에 없는(또는 구매 불가) 재료: "+String.join(", ",missing));
     if(at>0&&!need.isEmpty())out.add(TutorialShop.complete(need,got)?" → 다 샀어요. 상점 창을 닫으면 신호를 보내요":" → 아직 덜 샀어요");
     CmdKit.lines(sender,out);
    },m->CmdKit.fail(sender,"튜토리얼 상점 기록을 읽지 못했어요",new IllegalStateException(m)));
   }
  }
 }
 /** /상점관리 열기 <상점> [닉네임]: NPC 없이 연다. */
 private void adminOpen(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,Player p)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  var sh=shop(ctx);String who=plugin.names().name(p);
  if(!p.getListeningPluginChannels().contains(ShopProtocol.RESPONSE))throw ChacaApi.error(who+" 님은 차카데미 모드가 없어 상점을 열 수 없어요");
  if(!ready(p))throw ChacaApi.error(who+" 님은 지금 상점을 열 수 없어요 (캐릭터 불러오는 중·관전 등)");
  if(busy.contains(p.getUniqueId()))throw ChacaApi.error(who+" 님의 거래를 처리하는 중이에요. 잠시 뒤 다시 해 주세요");
  open(p,sh.id(),null);
 }
 @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());tutorialOpen.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());listed.remove(e.getPlayer().getName());/* busy remains until the queued order steps finish */}
 @Override public void close(){closing=true;Bukkit.getServicesManager().unregister(ShopBridge.class,this);
  // 여기부터 메인 스레드 후속 처리는 실행되지 않는다. 부작용 전인 것이 확실한 기록만 되돌리고, 나머지는 기록된 상태 그대로 다음 접속 때 복구한다.
  for(var revert:reverts.values())try{io.execute(()->{try{revert.call();}catch(Exception e){plugin.getLogger().warning("Shop shutdown revert failed: "+e.getClass().getSimpleName());}});}catch(RejectedExecutionException ignored){}
  reverts.clear();io.shutdown();try{if(io.awaitTermination(10,TimeUnit.SECONDS))store.close();else plugin.getLogger().warning("Shop IO still pending at shutdown; unfinished orders keep their recorded state.");}catch(Exception e){Thread.currentThread().interrupt();}sessions.clear();}
}
