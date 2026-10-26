package school.magiccodex.paper;
import java.util.*;import java.util.concurrent.*;import java.util.function.Consumer;import java.util.function.BooleanSupplier;import org.bukkit.Bukkit;import org.bukkit.entity.Player;import school.magiccodex.database.DatabaseSettings;
/** Async shared ownership cache and durable purchase/outbox recovery. Main thread never waits on JDBC. */
final class ArtifactOwnershipService implements AutoCloseable {
 private final MagicCodexBridge plugin;private final ArtifactOwnershipStore store;private final ArtifactPurchaseJournal journal;private final ItemsAdderItems items;
 private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"MagicCodex-artifact-ownership");t.setDaemon(true);return t;});
 private final Map<UUID,Set<String>> snapshots=new HashMap<>();private final Map<String,ArtifactPurchaseJournal.Purchase> pending=new ConcurrentHashMap<>();private final Map<String,Consumer<List<ArtifactOwnershipStore.Pull>>> callbacks=new ConcurrentHashMap<>();private final Set<UUID> purchasing=new HashSet<>();private final Set<String> delivering=new HashSet<>();
 private final java.util.function.Supplier<String> mailTitle,mailBody; private final Map<String,List<ArtifactOwnershipStore.Pull>> completedResults=new ConcurrentHashMap<>(); private final Consumer<Player> changed;private volatile boolean closed,working;private long healthyAt;private boolean warned,announced;private final org.bukkit.scheduler.BukkitTask timer;
 ArtifactOwnershipService(MagicCodexBridge plugin,Consumer<Player> changed,java.util.function.Supplier<String> mailTitle,java.util.function.Supplier<String> mailBody)throws Exception {
  this.plugin=plugin;this.changed=changed;this.mailTitle=mailTitle;this.mailBody=mailBody;items=new ItemsAdderItems(plugin.getLogger());var folder=plugin.getDataFolder().toPath();var dedicated=folder.resolve("player-state-database.properties");var settings=DatabaseSettings.load(java.nio.file.Files.exists(dedicated)?dedicated:folder.resolve("database.properties"));if(!settings.mariaDb())throw new IllegalStateException("Shared artifacts require the existing MariaDB player-state connection");
  store=new ArtifactOwnershipStore(()->settings.connect(null));journal=new ArtifactPurchaseJournal(folder.resolve("artifact-purchase-receipts"));pending.putAll(journal.paid());if(journal.uncertain()>0)throw new IllegalStateException("Uncertain artifact debit receipt requires operator reconciliation; no automatic replay");
  timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,40);
 }
 boolean ready(UUID player){return !closed&&System.currentTimeMillis()-healthyAt<6000&&snapshots.containsKey(player)&&!purchasing.contains(player);}
 boolean owns(UUID player,String id){return !closed&&System.currentTimeMillis()-healthyAt<6000&&snapshots.getOrDefault(player,Set.of()).contains(id);}
 void forget(UUID player){snapshots.remove(player);}
 /** The receipt is persisted before debit, then marked paid before any DB submission. Ambiguous disk failures are quarantined. */
 boolean purchase(UUID player,List<ArtifactOwnershipStore.Choice> choices,String shardItem,BooleanSupplier debit,Consumer<List<ArtifactOwnershipStore.Pull>> done){
  if(!ready(player))return false;String operation=UUID.randomUUID().toString();var p=new ArtifactPurchaseJournal.Purchase(operation,player,List.copyOf(choices),shardItem);
  try{journal.prepare(p);if(!debit.getAsBoolean()){journal.remove(operation);return false;}journal.paid(p);purchasing.add(player);callbacks.put(operation,done);pending.put(operation,p);tick();return true;}
  catch(Exception e){healthyAt=0;plugin.getLogger().severe("Artifact receipt persistence failed; operation quarantined for reconciliation. No ownership was silently discarded.");return false;}
 }
 void admin(UUID player,String id,Boolean own,Consumer<Boolean> done){io.execute(()->{boolean ok=false;try{if(!store.migrationComplete())throw new IllegalStateException();if(id==null)store.reset(player);else store.set(player,id,Boolean.TRUE.equals(own));ok=true;}catch(Exception ignored){}boolean result=ok;main(()->{if(result){snapshots.remove(player);tick();}done.accept(result);});});}
 /** 관리자 보기: DB의 보유 목록 (오프라인 유저도). 콜백은 메인 스레드, 실패면 null. */
 void owned(UUID player,Consumer<Set<String>> done){if(closed){done.accept(null);return;}io.execute(()->{Set<String> rows=null;try{rows=store.owned(player);}catch(Exception e){plugin.getLogger().warning("Artifact ownership read failed: "+e);}var out=rows;main(()->done.accept(out));});}
 /** 관리자 뽑기 기록. 실패면 null. */
 void history(UUID player,Consumer<List<ArtifactOwnershipStore.History>> done){io.execute(()->{List<ArtifactOwnershipStore.History> rows=null;try{rows=store.history(player);}catch(Exception e){plugin.getLogger().warning("Artifact history read failed: "+e);}var out=rows;main(()->done.accept(out));});}
 /** 관리자 완료처리: 우편 없이 지급 완료로. null = DB 실패. */
 void markDelivered(UUID player,ArtifactOwnershipStore.History row,Consumer<Boolean> done){io.execute(()->{Boolean ok=null;try{ok=store.markDelivered(player,row.operation(),row.index());}catch(Exception e){plugin.getLogger().warning("Artifact delivery mark failed: "+e);}var out=ok;main(()->done.accept(out));});}
 /** 관리자 다시주기: 새 우편 한 통(새 출처 키)을 보내고 지급 완료로 표시. 결과 문구(null=성공)를 메인 스레드로. */
 void redeliver(UUID player,ArtifactOwnershipStore.History row,Consumer<String> done){
  var mail=Bukkit.getServicesManager().load(MailService.class);var item=items.create(row.item(),row.amount());
  if(mail==null||item==null){done.accept(mail==null?"우편 기능이 준비되지 않았어요":"아이템("+row.item()+")을 만들 수 없어요 (ItemsAdder 확인)");return;}
  mail.sendSystem("artifact-admin-redo:"+row.operation()+":"+row.index()+":"+UUID.randomUUID(),player,mailTitle.get(),mailBody.get(),List.of(item)).whenComplete((id,error)->{
   if(error!=null){plugin.getLogger().warning("Artifact admin redelivery mail failed: "+error);main(()->done.accept("우편을 보내지 못했어요"));return;}
   try{markDelivered(player,row,ok->done.accept(null));}catch(java.util.concurrent.RejectedExecutionException closing){plugin.getLogger().warning("Artifact admin redelivery sent but not marked (shutting down): "+row);}
  });
 }
 private void tick(){if(closed||working)return;working=true;var players=Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();io.execute(()->{
  try{
   if(!store.migrationComplete())throw new IllegalStateException("Artifact migration not complete");
   var completed=completedResults;
   for(var p:List.copyOf(pending.values())){var result=store.purchase(p.operation(),p.player(),p.choices(),p.shardItem());journal.remove(p.operation());pending.remove(p.operation());completed.put(p.operation(),result);}
   // 접속자마다 새 연결을 열던 N+1 조회 대신, 주기마다 연결 하나 + IN 묶음 조회 (보유 목록·미지급 우편)
   var poll=store.poll(players);var fresh=poll.owned();var mail=poll.deliveries();
   main(()->{working=false;healthyAt=System.currentTimeMillis();if(!announced){announced=true;plugin.getLogger().info("Shared artifact ownership ready; both migration markers verified; local LP ownership fallback disabled.");}if(warned){plugin.getLogger().info("Shared artifact database recovered; pending receipts replayed safely.");warned=false;}for(var e:fresh.entrySet()){var old=snapshots.put(e.getKey(),e.getValue());if(!e.getValue().equals(old)){Player p=Bukkit.getPlayer(e.getKey());if(p!=null&&plugin.playerStateReady(p))changed.accept(p);}}snapshots.keySet().retainAll(new HashSet<>(players));for(var e:List.copyOf(completed.entrySet())){var callback=callbacks.remove(e.getKey());completed.remove(e.getKey());if(callback!=null)try{callback.accept(e.getValue());}catch(RuntimeException ignored){plugin.getLogger().warning("Artifact result UI delivery failed; committed ownership remains available on reconnect.");}}purchasing.removeIf(id->pending.values().stream().noneMatch(p->p.player().equals(id)));for(var row:mail)deliver(row);working=false;});
  }catch(Exception error){main(()->{healthyAt=0;working=false;if(!warned){warned=true;plugin.getLogger().warning("Shared artifact database unavailable or migration incomplete; spending disabled, paid receipts retained for retry.");}});}
 });}
 private void deliver(ArtifactOwnershipStore.Delivery row){
  String key=row.operation()+":"+row.index();if(!delivering.add(key))return;
  try{var mail=Bukkit.getServicesManager().load(MailService.class);var item=items.create(row.item(),row.amount());if(mail==null||item==null){delivering.remove(key);return;}
   mail.sendSystem("artifact-shared:"+key,row.player(),mailTitle.get(),mailBody.get(),List.of(item)).whenComplete((id,error)->{
    if(error!=null){main(()->delivering.remove(key));return;}if(closed)return;
    try{io.execute(()->{try{store.delivered(row);}catch(Exception ignored){}main(()->delivering.remove(key));});}catch(RejectedExecutionException ignored){}
   });
  }catch(RuntimeException error){delivering.remove(key);}
 }
 private void main(Runnable r){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{if(!closed)r.run();});}
 @Override public void close(){closed=true;timer.cancel();io.shutdown();try{if(!io.awaitTermination(8,TimeUnit.SECONDS))io.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}snapshots.clear();}
}
