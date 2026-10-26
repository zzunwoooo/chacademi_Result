package school.magiccodex.paper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;

import school.magiccodex.protocol.MailboxProtocol;
import school.magiccodex.protocol.MailboxProtocol.*;

/**
 * Main-thread inventory, bounded JDBC worker, owner-fenced claims and durable origin receipts.
 *
 * 첨부 상태 (mail_items.state): ready -> pending(token=수령번호, origin=수령하던 서버) -> claimed, 취소 시 pending -> ready.
 * 아이템 지급과 영수증(PDC mail_receipt_<수령번호>)은 같은 틱에 같은 플레이어 데이터에 기록하고, claimed 기록이 끝난 뒤에만 영수증을 지운다.
 * 그래서 수령하던 서버에서 pending 을 다시 만났을 때:
 *  - 영수증이 없다  -> 아이템이 지급되지 않은 것이 확실 -> ready 로 되돌린다 (자동)
 *  - 영수증이 있고 디스크에도 저장됨 -> 지급된 것이 확실 -> claimed 로 닫는다 (자동)
 *  - 다른 서버에서 수령하던 첨부 -> 그 서버에 접속해 우편함을 열면 위 규칙으로 정리된다. 관리자는 /유저관리 <닉네임> 우편 기록·처리 로 직접 처리할 수 있다.
 */
final class MailboxBridge implements MailService,PluginMessageListener,Listener,AutoCloseable {
 private final MagicCodexBridge plugin;private final MailboxStore store;private final String origin;
 /** 새 요청만 이 한도로 거절한다(work). 이미 예약한 수령의 후속 기록(must)과 시스템 우편 저장은 거절하지 않는다. */
 private static final int QUEUE_LIMIT=64;
 private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),r->{var t=new Thread(r,"MagicCodex-mailbox-io");t.setDaemon(true);return t;});
 private record Session(Player connection,long token,long sequence){}
 private final Map<UUID,Session> sessions=new HashMap<>();private final Set<UUID> busy=new HashSet<>();private final Map<UUID,Long> limits=new HashMap<>();private volatile boolean closing;
 /** 관리자가 방금 `기록`으로 본 수령번호 (탭 완성용). */
 private final Map<String,List<String>> listed=new HashMap<>();
 MailboxBridge(MagicCodexBridge plugin)throws Exception{
  this.plugin=plugin;Path dir=plugin.getDataFolder().toPath(),identity=dir.resolve("mailbox-origin.txt");
  if(!Files.exists(identity))Files.writeString(identity,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);
  origin=UUID.fromString(Files.readString(identity).strip()).toString();
  try{store=io.submit(()->new MailboxStore(ShopMailboxDatabaseSettings.load(dir),dir.resolve("mailbox.db"))).get(15,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
  Bukkit.getMessenger().registerIncomingPluginChannel(plugin,MailboxProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,MailboxProtocol.RESPONSE);
  Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getServicesManager().register(MailService.class,this,plugin,ServicePriority.Normal);
  CmdKit.register(plugin,"/유저관리 우편",this::commands);
 }
 private boolean current(Player p){return !closing&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p;}
 private boolean allowed(Player p){return current(p)&&p.hasPermission("magiccodex.mailbox")&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&plugin.playerStateReady(p);}
 private void main(Runnable action){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,action);}
 /** 메인 스레드 후속 처리는 반드시 여기를 거친다: 예외가 나도 busy 가 풀리고, 첨부는 기록된 상태로 남아 다음에 우편함을 열 때 자동으로 판정된다. */
 private void guard(UUID owner,Runnable r){try{r.run();}catch(Throwable t){plugin.getLogger().log(Level.SEVERE,"Mailbox continuation failed"+(owner==null?"":" for "+owner)+"; attachments keep their recorded state",t);if(owner!=null){busy.remove(owner);var p=Bukkit.getPlayer(owner);if(p!=null)CmdKit.notify(p,"warning","mail-error","우편 처리 중 문제가 생겼어요","우편함을 다시 열어 주세요.",3000);}}}
 /** 새 요청: 대기열이 차 있으면 아무 것도 바꾸지 않고 거절한다. */
 private <T>void work(UUID owner,Callable<T> task,Consumer<T> success,Consumer<String> fail){if(io.getQueue().size()>=QUEUE_LIMIT){fail.accept("우편함이 바쁩니다. 잠시 뒤 다시 시도해 주세요.");return;}must(owner,task,success,fail);}
 /** 진행 중인 수령의 후속 기록: 대기열 한도로 거절하지 않는다. */
 private <T>void must(UUID owner,Callable<T> task,Consumer<T> success,Consumer<String> fail){try{io.execute(()->{T value;try{value=task.call();}catch(Exception e){plugin.getLogger().warning("Mailbox operation held: "+e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage()));String m=e instanceof IllegalStateException||e instanceof IllegalArgumentException?String.valueOf(e.getMessage()):"우편 처리 기록을 확인해야 합니다. 다시 지급하지 않습니다.";main(()->guard(owner,()->fail.accept(m)));return;}main(()->guard(owner,()->success.accept(value)));});}catch(RejectedExecutionException e){guard(owner,()->fail.accept("우편함이 종료 중입니다. 잠시 뒤 다시 시도해 주세요."));}}
 @Override public CompletableFuture<UUID> sendSystem(String source,UUID recipient,String title,String body,List<ItemStack> attachments){
  if(!Bukkit.isPrimaryThread())throw new IllegalStateException("server thread required");
  if(closing||source==null||source.isBlank()||source.length()>160||recipient==null||title==null||title.isBlank()||title.length()>80||body==null||body.length()>2000||attachments==null||attachments.size()>9)throw new IllegalArgumentException("mail limits");
  var stored=new ArrayList<MailboxStore.Item>();int index=0;
  for(ItemStack original:attachments){if(original==null||original.getType().isAir()||original.getAmount()<1||original.getAmount()>original.getMaxStackSize()||original.getAmount()>99)throw new IllegalArgumentException("attachment stack");ItemStack copy=original.clone();byte[] bytes=copy.serializeAsBytes();if(bytes.length>32768)throw new IllegalArgumentException("attachment bytes");String name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(copy.displayName());if(name.length()>120)name=name.substring(0,120);stored.add(new MailboxStore.Item(index++,name,copy.getAmount(),bytes,"ready","",""));}
  var future=new CompletableFuture<UUID>();try{io.execute(()->{try{future.complete(store.create(source,recipient,title,body,List.copyOf(stored)));}catch(Exception e){future.completeExceptionally(e);}});}catch(RejectedExecutionException e){future.completeExceptionally(e);}return future;
 }
 @Override public CompletableFuture<Boolean> restoreDeletedMail(UUID recipient,UUID mailId){if(!Bukkit.isPrimaryThread()||closing)throw new IllegalStateException("server thread required");Objects.requireNonNull(recipient);Objects.requireNonNull(mailId);var future=new CompletableFuture<Boolean>();try{io.execute(()->{try{future.complete(store.restore(recipient,mailId));}catch(Exception e){future.completeExceptionally(e);}});}catch(RejectedExecutionException e){future.completeExceptionally(e);}return future;}
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
  if(!channel.equals(MailboxProtocol.REQUEST)||!allowed(p))return;Request r;try{r=MailboxProtocol.request(bytes);}catch(IllegalArgumentException e){return;}
  UUID owner=p.getUniqueId();long now=System.currentTimeMillis();if(busy.contains(owner)||now<limits.getOrDefault(owner,0L))return;
  Session s=sessions.get(owner);if(r.action()==MailboxProtocol.OPEN){s=new Session(p,ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE),r.sequence());sessions.put(owner,s);}else{if(s==null||s.connection!=p||s.token!=r.session()||r.sequence()<=s.sequence)return;s=new Session(p,s.token,r.sequence());sessions.put(owner,s);}
  limits.put(owner,now+150);if(r.action()==MailboxProtocol.CLOSE){sessions.remove(owner);return;}busy.add(owner);long token=s.token;
  switch(r.action()){
   case MailboxProtocol.CLAIM,MailboxProtocol.CLAIM_ALL -> claim(p,r,token);
   case MailboxProtocol.DELETE_CLAIMED -> work(owner,()->store.deleteClaimed(owner),n->snapshot(p,r,token,n+"개의 수령한 우편을 삭제했습니다."),m->finishError(p,r,token,m));
   default -> recover(p,r,token);
  }
 }
 private NamespacedKey receipt(String token){return new NamespacedKey(plugin,"mail_receipt_"+token.replace("-",""));}
 static Path playerFile(Player p){return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("playerdata").resolve(p.getUniqueId()+".dat");}
 private void recover(Player p,Request r,long session){
  UUID owner=p.getUniqueId();Set<String> receipts=new HashSet<>();for(var key:p.getPersistentDataContainer().getKeys())if(key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))&&key.getKey().startsWith("mail_receipt_"))receipts.add(key.getKey().substring(13));
  Path savedFile=playerFile(p);work(owner,()->{var tokens=new HashMap<String,String>();for(var ref:store.pending(owner))tokens.put(ref.item().token(),ref.item().origin());int review=0,elsewhere=0,saving=0;
   for(var entry:tokens.entrySet()){
    if(!entry.getValue().equals(origin)){if(entry.getValue().isEmpty())review++;else elsewhere++;continue;}
    // 이 서버에서 수령하던 첨부: 영수증이 없으면 지급 전에 멈춘 것이므로 다시 수령할 수 있게 되돌린다.
    if(!receipts.contains(entry.getKey().replace("-",""))){store.settle(owner,entry.getKey(),false);plugin.getLogger().info("Mailbox recovery: claim "+entry.getKey()+" ("+owner+") pending -> ready (영수증 없음)");continue;}
    if(DeliveryReceipt.saved(savedFile,receipt(entry.getKey()).toString())){store.settle(owner,entry.getKey(),true);plugin.getLogger().info("Mailbox recovery: claim "+entry.getKey()+" ("+owner+") pending -> claimed (영수증 저장 확인)");}else saving++;
   }
   return review>0?"이전 수령 기록을 관리자가 확인해야 합니다. 관리자에게 문의해 주세요.":elsewhere>0?"다른 서버에서 받던 첨부가 있습니다. 그 서버에서 우편함을 열면 자동으로 정리됩니다.":saving>0?"이전 수령을 저장하는 중입니다. 잠시 뒤 우편함을 다시 열어 주세요.":"";},message->snapshot(p,r,session,message),m->finishError(p,r,session,m));
 }
 private record Snapshot(MailboxStore.Page page,MailboxStore.Mail mail){}
 private void snapshot(Player p,Request r,long token,String message){must(p.getUniqueId(),()->{var page=store.list(p.getUniqueId(),r.page());String selected=r.mail();if(selected.isEmpty()&&!page.entries().isEmpty())selected=page.entries().getFirst().id();return new Snapshot(page,selected.isEmpty()?null:store.detail(p.getUniqueId(),selected));},data->{busy.remove(p.getUniqueId());if(!current(p))return;var s=sessions.get(p.getUniqueId());if(s==null||s.token!=token||s.sequence!=r.sequence())return;var attachments=new ArrayList<Attachment>();if(data.mail!=null)for(var i:data.mail.items()){byte[] preview=i.bytes();try{ItemStack stack=plugin.coreAppearance(ItemStack.deserializeBytes(i.bytes()));preview=stack.serializeAsBytes();if(preview.length>3072)preview=new ItemStack(stack.getType(),stack.getAmount()).serializeAsBytes();}catch(RuntimeException e){preview=new byte[0];/* 미리보기만 비운다 */}attachments.add(new Attachment(i.index(),i.name(),i.amount(),i.state().equals("claimed"),preview.length<=3072?preview:new byte[0]));}p.sendPluginMessage(plugin,MailboxProtocol.RESPONSE,MailboxProtocol.encode(new Response(r.sequence(),token,message,r.page(),data.page.more(),data.page.entries(),data.mail==null?"":data.mail.id(),data.mail==null?"":data.mail.body(),List.copyOf(attachments))));},m->finishError(p,r,token,m));}
 private void finishError(Player p,Request r,long token,String message){busy.remove(p.getUniqueId());if(current(p))p.sendPluginMessage(plugin,MailboxProtocol.RESPONSE,MailboxProtocol.encode(new Response(r.sequence(),token,message,r.page(),false,List.of(),"","",List.of())));}
 static ItemStack[] copy(ItemStack[] source){return Arrays.stream(source).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);}
 static boolean same(ItemStack[] a,ItemStack[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++){if(a[i]==null||a[i].getType().isAir()){if(b[i]!=null&&!b[i].getType().isAir())return false;}else if(b[i]==null||!Arrays.equals(a[i].serializeAsBytes(),b[i].serializeAsBytes()))return false;}return true;}
 /** Inserts a whole attachment or leaves the simulated inventory unchanged. Never drops overflow. 칸 규칙은 RewardPlan 하나 (모든 칸 사용 가능일 때). */
 static boolean insert(ItemStack[] inventory,ItemStack value){return RewardDelivery.insertWhole(inventory,null,value);}
 /** 운영 기록 (메모 #92): 수령이 영수증까지 확인된 첨부만. */
 private static void audit(UUID owner,String claim,List<MailboxStore.Ref> fits){
  var items=new StringBuilder();var mails=new java.util.LinkedHashSet<String>();
  for(var f:fits){if(items.length()<400)items.append(items.length()==0?"":", ").append(f.item().name()).append(" x").append(f.item().amount());mails.add(f.mail());}
  AuditLog.log(owner,AuditLog.우편,"우편 수령 · 첨부 "+fits.size()+"개 · "+AuditText.clip(items.toString(),120),"수령번호",claim,"첨부",items.toString(),"우편",String.join(",",mails));
 }
 private void claim(Player p,Request r,long session){
  if(r.action()==MailboxProtocol.CLAIM&&r.mail().isEmpty()){finishError(p,r,session,"우편을 선택해 주세요.");return;}
  UUID owner=p.getUniqueId();work(owner,()->store.ready(owner,r.action()==MailboxProtocol.CLAIM_ALL?"":r.mail()),refs->{
   if(!allowed(p)){busy.remove(owner);return;}var before=copy(p.getInventory().getStorageContents());var next=copy(before);var fits=new ArrayList<MailboxStore.Ref>();
   // 공통 지급 칸 판정 (RewardDelivery, 2026-10-26): 핫바 + 잠금 해제된 지금 쪽 27칸만. 쪽 넘기는 중·잠긴 쪽이면 27칸은 쓰지 않는다.
   boolean[] usable=RewardDelivery.usable(plugin,p);
   for(var ref:refs)try{if(RewardDelivery.insertWhole(next,usable,plugin.coreAppearance(ItemStack.deserializeBytes(ref.item().bytes()))))fits.add(ref);}catch(RuntimeException e){plugin.getLogger().warning("Mailbox attachment unreadable: mail "+ref.mail()+" #"+ref.item().index());}
   if(fits.isEmpty()){snapshot(p,r,session,"수령할 첨부가 없거나 인벤토리 공간이 부족합니다.");return;}
   String claim=UUID.randomUUID().toString();must(owner,()->{store.reserve(owner,List.copyOf(fits),claim,origin);return true;},reserved->{
    if(!allowed(p)||!same(before,p.getInventory().getStorageContents())){must(owner,()->{store.settle(owner,claim,false);return true;},v->snapshot(p,r,session,"인벤토리가 변경되었습니다. 다시 수령해 주세요."),m->finishError(p,r,session,m));return;}
    // Item movement and receipt are written into the same vanilla player-data snapshot on the origin server.
    // A crash with no durable receipt remains pending for review; never blindly release/replay it.
    try{p.getInventory().setStorageContents(next);p.getPersistentDataContainer().set(receipt(claim),PersistentDataType.BYTE,(byte)1);p.saveData();}
    catch(RuntimeException e){finishError(p,r,session,"수령 저장을 확인해야 합니다. 자동으로 재지급하지 않습니다.");return;}
    Path file=playerFile(p);String savedKey=receipt(claim).toString();must(owner,()->{if(!DeliveryReceipt.saved(file,savedKey))throw new IllegalStateException("아이템 저장 영수증 확인이 필요합니다. 자동으로 재지급하지 않습니다.");store.settle(owner,claim,true);return true;},v->{if(current(p)){p.getPersistentDataContainer().remove(receipt(claim));p.saveData();}audit(owner,claim,fits);snapshot(p,r,session,fits.size()+"개의 첨부를 수령했습니다. 공간이 부족한 첨부는 우편에 남아 있습니다.");},m->finishError(p,r,session,m));
   },m->finishError(p,r,session,m));
  },m->finishError(p,r,session,m));
 }
 // ------------------------------------------------------------------ /유저관리 <닉네임> 우편 (2026-10-20)

 /** 관리자가 방금 목록으로 본 우편 ID (번호 → ID). 관리자 이름 + 대상별. */
 private final Map<String,List<String>> listedMail=new HashMap<>();
 private void commands(){
  var root=CmdKit.literal("우편")
   .then(CmdKit.literal("보내기").then(CmdKit.argument("제목",com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(CmdKit.run(this::sendMail))))
   .then(CmdKit.literal("목록").executes(CmdKit.run(this::listMail)))
   .then(CmdKit.literal("삭제").then(CmdKit.confirm(CmdKit.argument("번호",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,1000)),this::deleteMail)))
   .then(CmdKit.literal("기록").executes(CmdKit.run(ctx->held(CmdKit.sender(ctx),ChacaApi.target(ctx)))));
  var claim=CmdKit.wordIn("수령번호",ctx->listed.getOrDefault(CmdKit.sender(ctx).getName(),List.of()));
  for(String action:List.of("완료","반환"))claim.then(CmdKit.literal(action).executes(CmdKit.run(ctx->settle(ctx,action.equals("완료")))));
  root.then(CmdKit.literal("처리").then(claim));
  ChacaApi.branch(plugin,ChacaApi.유저관리,root,"우편 보내기(손에 든 아이템 첨부)·목록·삭제·수령 기록 처리","우편 목록");
 }
 private void sendMail(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  UUID owner=ChacaApi.target(ctx);String title=com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"제목").strip();
  if(title.isEmpty()||title.length()>80)throw ChacaApi.error("제목은 1~80자예요");
  var sender=CmdKit.sender(ctx);var attachments=new ArrayList<ItemStack>();
  // 손에 든 아이템의 사본을 첨부한다. 손에서는 우편 저장이 확인된 뒤에만 뺀다 (실패해도 아이템이 사라지지 않음). 콘솔이나 빈손이면 글만.
  ItemStack hand=null;int slot=-1;
  if(sender instanceof Player p){slot=p.getInventory().getHeldItemSlot();ItemStack held=p.getInventory().getItemInMainHand();if(!held.getType().isAir()){if(held.getAmount()>99||held.serializeAsBytes().length>32768)throw ChacaApi.error("손에 든 아이템이 너무 커서 첨부할 수 없어요");hand=held.clone();attachments.add(hand);}}
  final ItemStack attached=hand;final int heldSlot=slot;Player admin=sender instanceof Player p?p:null;
  String who=CmdKit.name(owner);String body=attached==null?"관리자가 보낸 우편입니다.":"관리자가 보낸 우편입니다. 첨부를 받아 주세요.";
  String item=attached==null?"-":attached.getType()+"x"+attached.getAmount();
  CompletableFuture<UUID> sent;
  try{sent=sendSystem("admin:"+UUID.randomUUID(),owner,title,body,attachments);}
  catch(RuntimeException e){plugin.getLogger().warning("MAIL ADMIN send-failed admin="+sender.getName()+" player="+owner+" title="+title+" item="+item+" error="+e);throw ChacaApi.error(adminSendResult(false,attached!=null,false));}
  sent.whenComplete((id,error)->main(()->{
   if(error!=null){plugin.getLogger().warning("MAIL ADMIN send-failed admin="+sender.getName()+" player="+owner+" title="+title+" item="+item+" error="+error);CmdKit.fail(sender,adminSendResult(false,attached!=null,false),error);return;}
   boolean taken=false;
   if(admin!=null&&attached!=null){ItemStack now=admin.isOnline()?admin.getInventory().getItem(heldSlot):null;
    if(now!=null&&!now.getType().isAir()&&Arrays.equals(now.serializeAsBytes(),attached.serializeAsBytes())){admin.getInventory().setItem(heldSlot,null);taken=true;}
    else plugin.getLogger().warning("MAIL ADMIN copy-retained admin="+sender.getName()+" player="+owner+" mail="+id+" item="+item+" (보낸 뒤 손의 아이템이 바뀌었거나 오프라인이라 빼지 못함)");}
   plugin.getLogger().info("MAIL ADMIN send admin="+sender.getName()+" player="+owner+" mail="+id+" title="+title+" item="+item+" taken="+taken);
   CmdKit.ok(sender,who+" 님에게 "+adminSendResult(true,attached!=null,taken));
  }));
 }
 /** 관리자 우편 결과 문구: 실제 결과(성공 여부·첨부 여부·손에서 뺐는지)대로만 말한다. */
 static String adminSendResult(boolean ok,boolean attachment,boolean taken){
  if(!ok)return "우편을 보내지 못했어요"+(attachment?" (첨부는 손에 그대로 있어요)":"");
  if(!attachment)return "우편을 보냈어요";
  return taken?"우편을 보냈어요 (첨부 1개)":"우편을 보냈어요 (첨부 1개, 손의 아이템이 바뀌어 빼지 못했어요 — 사본이 남았는지 확인해 주세요)";
 }
 private void listMail(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  UUID owner=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);String who=CmdKit.name(owner);
  work(null,()->store.list(owner,0),page->{
   var out=new ArrayList<String>();out.add("── "+who+" 우편함 (최근 20통) ──");var ids=new ArrayList<String>();
   for(var e:page.entries()){ids.add(e.id());out.add(ids.size()+". "+e.title()+" · "+QuestBridge.timeText(e.created())+" · 첨부 "+(e.total()==0?"없음":(e.total()-e.remaining())+"/"+e.total()+" 받음"));}
   if(page.entries().isEmpty())out.add("(우편 없음)");else out.add("삭제: 우편 삭제 <번호> 확인 (받지 않은 첨부도 함께 사라져요)");
   if(page.more())out.add("… 더 오래된 우편이 있어요");
   listedMail.put(sender.getName()+"|"+owner,List.copyOf(ids));CmdKit.lines(sender,out);
  },m->CmdKit.fail(sender,"우편함을 읽지 못했어요",new IllegalStateException(m)));
 }
 private void deleteMail(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  UUID owner=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);int number=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"번호");
  var ids=listedMail.get(sender.getName()+"|"+owner);
  if(ids==null)throw ChacaApi.error("먼저 우편 목록으로 번호를 확인해 주세요");
  if(number>ids.size())throw ChacaApi.error("그 번호의 우편이 없어요 (우편 목록으로 확인)");
  String id=ids.get(number-1);String who=CmdKit.name(owner);
  work(null,()->store.adminDelete(owner,id),ok->{
   plugin.getLogger().warning("MAIL ADMIN delete admin="+sender.getName()+" player="+owner+" mail="+id+" ok="+ok);
   if(ok)CmdKit.ok(sender,who+" 님 "+number+"번 우편을 지웠어요");else CmdKit.userFail(sender,"이미 지운 우편이거나 받는 중인 첨부가 있어요");
  },m->CmdKit.fail(sender,"우편을 지우지 못했어요",new IllegalStateException(m)));
 }
 /** 수령 도중 멈춘 첨부 목록. */
 private void held(CommandSender sender,UUID owner){work(null,()->store.held(owner),rows->{
   var tokens=new LinkedHashSet<String>();for(var h:rows)tokens.add(h.item().token());listed.put(sender.getName(),List.copyOf(tokens));
   var out=new ArrayList<String>();out.add("── "+CmdKit.name(owner)+" 수령 도중 멈춘 첨부 "+rows.size()+"개 ──");
   for(var h:rows)out.add(h.item().token()+" | 우편 \""+h.title()+"\" ("+QuestBridge.timeText(h.created())+") | "+h.item().name()+" "+h.item().amount()+"개 | "+(h.item().origin().isEmpty()?"서버 기록 없음":h.item().origin().equals(origin)?"이 서버에서 수령":"다른 서버에서 수령"));
   if(!rows.isEmpty())out.add("수령하던 서버에서 유저가 우편함을 열면 자동으로 정리돼요. 직접 처리: 우편 처리 <수령번호> 완료(이미 받음, 다시 안 줌) | 반환(못 받음, 다시 수령 가능)");
   CmdKit.lines(sender,out);
  },m->CmdKit.fail(sender,"수령 기록을 읽지 못했어요",new IllegalStateException(m)));}
 private void settle(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,boolean claimed)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  UUID owner=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);String token;
  try{token=UUID.fromString(CmdKit.text(ctx,"수령번호")).toString();}catch(IllegalArgumentException e){throw ChacaApi.error("수령번호를 확인해 주세요 (우편 기록)");}
  if(busy.contains(owner))throw ChacaApi.error("이 유저의 우편을 지금 처리하는 중이에요. 잠시 뒤 다시 해 주세요");
  busy.add(owner);String admin=sender.getName();
  must(owner,()->store.settle(owner,token,claimed),n->{busy.remove(owner);plugin.getLogger().warning("MAIL ADMIN admin="+admin+" player="+owner+" claim="+token+" action="+(claimed?"완료":"반환")+" items="+n);
   if(n==0)CmdKit.userFail(sender,"처리할 첨부가 없어요. 이미 처리했거나 수령번호가 달라요");else CmdKit.ok(sender,claimed?"첨부 "+n+"개를 수령 완료로 닫았어요 (다시 주지 않음)":"첨부 "+n+"개를 되돌렸어요. 우편함에서 다시 받을 수 있어요");
  },m->{busy.remove(owner);CmdKit.fail(sender,"수령 기록을 바꾸지 못했어요",new IllegalStateException(m));});
 }
 @EventHandler public void quit(PlayerQuitEvent e){listed.remove(e.getPlayer().getName());listedMail.keySet().removeIf(k->k.startsWith(e.getPlayer().getName()+"|"));sessions.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());/* busy remains until queued reserve/settle completes */}
 @Override public void close(){closing=true;Bukkit.getServicesManager().unregister(MailService.class,this);io.shutdown();try{if(io.awaitTermination(10,TimeUnit.SECONDS))store.close();else plugin.getLogger().warning("Mailbox IO still pending at shutdown; receipts retained.");}catch(Exception e){Thread.currentThread().interrupt();}sessions.clear();}
}
