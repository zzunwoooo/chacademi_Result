package school.magiccodex.paper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.*;
import net.william278.husksync.sync.*;
import net.william278.husksync.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import school.magiccodex.database.DatabaseSettings;

/** Native LOCKSTEP integration. No migration/bootstrap or independent WAL-to-central authority. */
final class BagHuskCoordinator implements BagHuskSnapshotLink.Authority {
 private static final class Holder {
  final BagSyncSession session; final OnlineUser user; final AtomicLong captures=new AtomicLong();
  volatile BagSharedSql.Row row;volatile boolean drained,external;volatile CompletableFuture<Boolean> inFlight;
  Holder(BagSyncSession s,OnlineUser u,BagSharedSql.Row r){session=s;user=u;row=r;}
 }
 private final MagicCodexBridge plugin;private final BagPagesBridge bags;private final BukkitHuskSyncAPI api;
 private final Connection connection;private final BagSharedSql shared;private final BagPageCodec.Journal journal;
 private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-shared-bag");t.setDaemon(true);return t;});
 private final Map<UUID,Holder> holders=new ConcurrentHashMap<>();
 private final Set<UUID> managed=ConcurrentHashMap.newKeySet();
 private final Set<UUID> prevalidated=ConcurrentHashMap.newKeySet();
 private final boolean allowCleanBootstrap;
 private BagHuskSnapshotLink link;
 private final DataSyncer original;private final BagLockstepSyncer hook;private volatile boolean closing;
 BagHuskCoordinator(MagicCodexBridge plugin,BagPagesBridge bags,DatabaseSettings settings,Path folder,boolean allowCleanBootstrap)throws Exception {
  this.plugin=plugin;this.bags=bags;this.allowCleanBootstrap=allowCleanBootstrap;
  Plugin husk=plugin.getServer().getPluginManager().getPlugin("HuskSync");
  if(husk==null||!husk.isEnabled()||!husk.getPluginMeta().getVersion().startsWith("3.8.7"))throw new IllegalStateException("Shared bag requires enabled HuskSync 3.8.7");
  api=BukkitHuskSyncAPI.getInstance();original=api.getPlugin().getDataSyncer();
  if(original.getClass()!=LockstepDataSyncer.class)throw new IllegalStateException("DataSyncer is owned by another integration; shared bag did not replace it");
  if(!settings.mariaDb())throw new IllegalStateException("Shared bag requires a reviewed shared MariaDB schema; local SQLite is not a fallback");
  connection=settings.connect(folder.resolve("bag-pages.db"));settings.configure(connection);shared=new BagSharedSql(connection,true);
  try{shared.load(new UUID(0,0));}catch(Exception ex){connection.close();throw new IllegalStateException("Shared bag schema/migration is not installed; no table was created",ex);}
  journal=new BagPageCodec.Journal(folder.resolve("bag-shared-pending"));hook=new BagLockstepSyncer(api,this);
 }
 void install(){link=new BagHuskSnapshotLink(plugin,api,this);link.register();api.setDataSyncer(hook);plugin.getLogger().info("Shared bag owns HuskSync 3.8.7 LOCKSTEP hook; no recovery hook installed");}
 private void main(Runnable r){if(Bukkit.isPrimaryThread())r.run();else if(!closing)Bukkit.getScheduler().runTask((Plugin)api.getPlugin(),r);}
 boolean bootstrap(OnlineUser user){
  if(!allowCleanBootstrap)return false;
  UUID owner=user.getUuid();
  io.execute(()->{try{
   if(user.hasDisconnected())return;
   var existing=api.getPlugin().getDatabase().getLatestSnapshot(user);
   if(existing.isPresent()){var snapshot=existing.orElseThrow();main(()->user.applySnapshot(snapshot,DataSnapshot.UpdateCause.SYNCHRONIZED));return;}
   var session=new BagSyncSession(owner);UUID head=UUID.randomUUID();
   if(!shared.bootstrapClean(owner,head,session.lease(),api.getPlugin().getServerName())){block(owner,"Existing shared state has no matching native snapshot; reconciliation required");return;}
   api.getPlugin().getDatabase().ensureUser(user);
   session.initializeClean(head);var row=shared.load(owner);var holder=new Holder(session,user,row);holders.put(owner,holder);
   main(()->{
    if(user.hasDisconnected()){block(owner,"Disconnected during clean enrollment; lease retained");return;}
    save(user,DataSnapshot.SaveCause.SAVE_COMMAND,(u,packed)->main(()->{
     try{
      var binding=(BagSnapshotBinding)api.unpackSnapshot(packed).getData().get(BagHuskSnapshotLink.ID);
      holder.session.awaitLocalApply(binding);link.approveLocalSnapshot(owner,binding);
      user.applySnapshot(packed,DataSnapshot.UpdateCause.NEW_USER);
     }catch(RuntimeException ex){block(owner,"Clean enrollment apply failed; original state retained");}
    }));
   });
  }catch(Exception ex){block(owner,"Clean enrollment failed: "+ex.getClass().getSimpleName());}});
  return true;
 }
 void joining(UUID id){bags.sharedBlocked(id);}
 void requestStatus(UUID id){if(!holders.containsKey(id))bags.sharedBlocked(id);}
 boolean inputReady(UUID id){if(api.getPlugin().getDataSyncer()!=hook)return false;var h=holders.get(id);return h!=null&&h.session.phase()==BagSyncSession.Phase.READY&&!h.user.isLocked()&&!h.user.hasDisconnected();}
 private String inventory(DataSnapshot.Unpacked s){Data inv=s.getData().get(Identifier.INVENTORY);if(inv==null)throw new IllegalStateException("Snapshot inventory missing");return api.getDataSerializer(Identifier.INVENTORY).orElseThrow().serialize(inv);}
 private BagCommitFlow.Proof proof(DataSnapshot.Packed packed){var data=api.unpackSnapshot(packed);var raw=data.getData().get(BagHuskSnapshotLink.ID);if(!(raw instanceof BagSnapshotBinding b))throw new IllegalStateException("Native store has no bag binding");return new BagCommitFlow.Proof(packed.getId(),inventory(data),b);}
 @Override public boolean managedSnapshot(UUID owner,UUID snapshot){return managed.contains(snapshot);}
 boolean prevalidatedSnapshot(UUID snapshot){return prevalidated.contains(snapshot);}
 @Override public CompletionStage<Boolean> verifyIncoming(BagSnapshotBinding b){
  return CompletableFuture.supplyAsync(()->{
   try{
    var old=holders.get(b.owner());if(old!=null&&!old.drained){block(b.owner(),"Existing bag session needs reconciliation");return false;}
    var user=api.getOnlineUser(b.owner()).filter(u->!u.hasDisconnected()).orElse(null);if(user==null)return false;
    var session=new BagSyncSession(b.owner());BagSharedSql.Row row=null;
    // The sender releases central ownership only after exact native readback. Brief overlap stays locked.
    for(int i=0;i<20;i++){row=shared.claim(b.owner(),b,session.lease(),api.getPlugin().getServerName());if(row!=null)break;Thread.sleep(100);}
    if(row==null)return false;session.verifiedIncoming(b,row.head(),row.sequence(),row.active());
    var h=new Holder(session,user,row);holders.put(b.owner(),h);
    if(user.hasDisconnected()){block(b.owner(),"Disconnected after ownership claim; explicit reconciliation required");return false;}
    return true;
   }catch(Exception ex){block(b.owner(),"Incoming shared bag verification failed: "+ex.getClass().getSimpleName());return false;}
  },io);
 }
 @Override public void complete(UUID owner,BagSnapshotBinding binding){complete(owner,binding,null);}
 @Override public void complete(UUID owner,BagSnapshotBinding binding,String originalInventory){
  var h=holders.get(owner);if(h==null){block(owner,"Missing claimed bag session");return;}
  try{
   String live=api.getDataSerializer(Identifier.INVENTORY).orElseThrow().serialize(h.user.getInventory().orElseThrow());
   if(originalInventory==null)h.session.complete(binding,live);
   else{
    // Verify original bytes first; applied inventory may differ only by proven text-color case.
    binding.verify(owner,binding.snapshot(),originalInventory);
    if(!BagInventoryProof.equivalent(originalInventory,live))throw new IllegalStateException("Applied inventory differs beyond text color case");
    h.session.complete(binding,originalInventory);
   }
   CompletableFuture.supplyAsync(()->{try(var p=connection.prepareStatement("SELECT pages FROM codex_bag_unlock WHERE uuid=?")){p.setString(1,owner.toString());try(var rs=p.executeQuery()){return rs.next()?Math.clamp(rs.getInt(1),1,3):1;}}catch(SQLException ex){throw new CompletionException(ex);}},io)
    .whenComplete((unlocked,error)->main(()->{if(error!=null||h.user.hasDisconnected()){block(owner,"Unlock state or connection changed after sync");return;}readyWhenUnlocked(owner,h,unlocked,0);}));
  }catch(RuntimeException ex){block(owner,"Applied inventory differs from bag binding");}
 }

 private void readyWhenUnlocked(UUID owner,Holder h,int unlocked,int tries){
  if(closing||h.user.hasDisconnected()||h.session.phase()!=BagSyncSession.Phase.READY)return;
  if(h.user.isLocked()){
   if(tries>=200){block(owner,"Native sync lock was not released");return;}
   Bukkit.getScheduler().runTaskLater((Plugin)api.getPlugin(),()->readyWhenUnlocked(owner,h,unlocked,tries+1),1L);return;
  }
  bags.sharedReady(owner,h.row,unlocked);
 }
 @Override public void block(UUID owner,String reason){var h=holders.get(owner);if(h!=null){synchronized(h){h.session.block();if(reason.startsWith("External"))h.external=true;if(h.external)h.user.getCustomDataStore().remove(BagHuskSnapshotLink.ID);}}main(()->bags.sharedBlocked(owner));plugin.getLogger().warning("Shared bag blocked "+owner+": "+reason+"; original WAL/native data retained, reconciliation required");}
 /** Captures exactly once on the main thread; the immutable Packed snapshot is used by every later stage. */
 boolean save(OnlineUser user,DataSnapshot.SaveCause cause){return save(user,cause,null);}
 boolean save(OnlineUser user,DataSnapshot.SaveCause cause,java.util.function.BiConsumer<net.william278.husksync.user.User,DataSnapshot.Packed> after){
  var h=holders.get(user.getUuid());if(h==null||h.external)return false;
  if(h.drained)return true;
  if(api.getPlugin().getDataSyncer()!=hook){block(user.getUuid(),"DataSyncer ownership changed");return true;}
  var done=new CompletableFuture<Boolean>();
  main(()->{
   boolean release=cause.equals(DataSnapshot.SaveCause.DISCONNECT)||cause.equals(DataSnapshot.SaveCause.SERVER_SHUTDOWN);
   var previous=h.inFlight;
   if(previous!=null&&!previous.isDone()){
    if(cause.equals(DataSnapshot.SaveCause.SERVER_SHUTDOWN)){
     try{if(!previous.get(20,TimeUnit.SECONDS)){done.complete(false);return;}}catch(Exception ex){block(user.getUuid(),"Prior save did not drain before shutdown");done.complete(false);return;}
    }else if(release){previous.whenComplete((ok,error)->main(()->{if(error!=null||!Boolean.TRUE.equals(ok)){block(user.getUuid(),"Prior save failed before handoff");return;}save(user,cause,after);}));return;}
    else{previous.whenComplete((ok,error)->done.complete(error==null&&Boolean.TRUE.equals(ok)));return;}
   }
   h.inFlight=done;
   boolean wasLocked=user.isLocked();
   try{
    if(release)h.session.seal();
    if(h.session.phase()!=BagSyncSession.Phase.READY&&h.session.phase()!=BagSyncSession.Phase.SEALED)throw new IllegalStateException("Transition or reconciliation in progress");
    api.getPlugin().lockPlayer(user.getUuid());
    var unpacked=api.unpackSnapshot(user.createSnapshot(cause));String inv=inventory(unpacked);
    var binding=h.session.capture(unpacked.getId(),inv,inv);unpacked.setData(BagHuskSnapshotLink.ID,binding);
    var packed=api.packSnapshot(unpacked);long capture=h.captures.incrementAndGet();managed.add(packed.getId());
    // Fire the optional native event on this same main-thread capture boundary. Worker/shutdown waits never
    // depend on a later main-thread event, and SERVER_SHUTDOWN(false) deliberately skips it.
    if(cause.fireDataSaveEvent()&&api.getPlugin().fireIsCancelled(api.getPlugin().getDataSaveEvent(user,packed)))throw new IllegalStateException("Native save event cancelled");
    BagCommitFlow.verify(binding,proof(packed));prevalidated.add(packed.getId());
    io.execute(()->{
     boolean ok;
     synchronized(h){ok=BagCommitFlow.run(binding,release,release||!wasLocked,new BagCommitFlow.Port(){
      public boolean reserve()throws Exception{return shared.reserveCapture(user.getUuid(),h.session.lease(),capture,binding);}
      public void writeDatabase()throws Exception{var callback=new CompletableFuture<Void>();hook.saveData(user,packed,(u,p)->callback.complete(null));callback.get(15,TimeUnit.SECONDS);}
      public BagCommitFlow.Proof readDatabase(){return proof(api.getPlugin().getDatabase().getLatestSnapshot(user).orElseThrow());}
      public void writeRedis(){api.getPlugin().getRedisManager().setUserData(user,packed);}
      public BagCommitFlow.Proof peekRedis()throws Exception{try(var redis=new BagNativeRedis(api.getPlugin())){return proof(redis.peek(user));}}
      public boolean acknowledgeCas(boolean handoff)throws Exception{
       boolean sealed=h.session.nativeCommitVerified(binding,packed.getId(),inv,packed.getId(),inv);
       if(sealed!=handoff)throw new IllegalStateException("Capture handoff phase changed");
       return shared.acknowledgeNative(user.getUuid(),h.session.lease(),binding,packed.getId(),inv,packed.getId(),inv,handoff);
      }
      public void releaseCheckout()throws Exception{try(var redis=new BagNativeRedis(api.getPlugin())){redis.releaseOwnCheckout(user);}}
      public void unlock(){api.getPlugin().unlockPlayer(user.getUuid());}
      public void blocked(String stage,Exception ex){block(user.getUuid(),stage+" failed: "+ex.getClass().getSimpleName());}
     });h.session.finishCapture(ok);}
     if(ok&&release)h.drained=true;
     if(ok&&after!=null)try{after.accept(user,packed);}catch(RuntimeException ex){plugin.getLogger().warning("Shared bag native save callback failed after verified commit: "+ex.getClass().getSimpleName());}
     managed.remove(packed.getId());prevalidated.remove(packed.getId());done.complete(ok);
    });
   }catch(RuntimeException ex){api.getPlugin().lockPlayer(user.getUuid());block(user.getUuid(),"Atomic capture failed: "+ex.getClass().getSimpleName());done.complete(false);}
  });
  // Native shutdown closes DB/Redis immediately after saveCurrentUserData. Must finish the event-free path first.
  if(cause.equals(DataSnapshot.SaveCause.SERVER_SHUTDOWN))try{done.get(25,TimeUnit.SECONDS);}catch(Exception ex){block(user.getUuid(),"Shutdown handoff incomplete; lease retained");}
  return true;
 }
 String switchPage(Player p,int target){
  UUID owner=p.getUniqueId();var h=holders.get(owner);if(h==null||!inputReady(owner))return "배낭 동기화 중이거나 재조정이 필요해요";
  UUID op;byte[][] next;byte[] targetBytes;org.bukkit.inventory.ItemStack[] incoming;UUID nextHead=UUID.randomUUID();long nextSeq=h.row.sequence()+1;
  try{
   op=h.session.beginSwitch(h.user.isLocked());api.getPlugin().lockPlayer(owner);bags.sharedBlocked(owner);
   targetBytes=h.row.pages()[target-1];incoming=BagPagesBridge.items(targetBytes);
   next=h.row.pages().clone();next[h.row.active()-1]=BagPagesBridge.page(p.getInventory(),9);next[target-1]=BagPageCodec.emptyPage();
   journal.save(owner,"shared",target,nextSeq,next,targetBytes);
   Path receipt=plugin.getDataFolder().toPath().resolve("bag-shared-pending").resolve(owner+"-operation.txt");
   Files.writeString(receipt,"lease="+h.session.lease()+"\nbase="+h.row.head()+"\nhead="+nextHead+"\nseq="+nextSeq+"\n");
   try(var channel=java.nio.channels.FileChannel.open(receipt,StandardOpenOption.WRITE)){channel.force(true);}
  }catch(Exception ex){block(owner,"Page preparation failed: "+ex.getClass().getSimpleName());return "저장 확인에 실패했어요. 관리자에게 알려 주세요";}
  io.execute(()->{try{
   if(!shared.switchPages(owner,h.session.lease(),h.row.head(),nextHead,nextSeq,target,next))throw new IllegalStateException("Page ownership CAS rejected");
   var row=shared.load(owner);
   main(()->{try{
    if(h.user.hasDisconnected()||h.session.phase()!=BagSyncSession.Phase.SWITCHING)throw new IllegalStateException("Player left before page apply");
    for(int i=0;i<27;i++)p.getInventory().setItem(9+i,incoming[i]);
    p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin,"bag_page_commit"),org.bukkit.persistence.PersistentDataType.LONG,nextSeq);
    p.saveData();h.session.committedSwitch(op,nextHead,nextSeq,target);h.row=row;
    p.updateInventory();api.getPlugin().unlockPlayer(owner);bags.sharedReady(owner,row,bags.sharedUnlocked(owner));
   }catch(RuntimeException ex){block(owner,"Page apply incomplete; WAL retained: "+ex.getClass().getSimpleName());}});
  }catch(Exception ex){block(owner,"Page CAS failed; WAL retained: "+ex.getClass().getSimpleName());}});
  return null;
 }
 /**
  * 382 배낭 정리 (LifeSpells): one switch-like transition whose target is the page already in the inventory. The sorted
  * inactive pages and the sorted active page (= the incoming page, written to slots 9..35 after the CAS) go through
  * exactly the switchPage path: input blocked + HuskSync lock, durable WAL + receipt, central CAS on lease/head/seq,
  * player marker + saveData, committedSwitch. Every refusal happens before beginSwitch, so a refusal never blocks the bag.
  * check = compute only. Null = started (or sortable); otherwise the refusal.
  */
 String sortPages(Player p,int unlocked,boolean check){
  UUID owner=p.getUniqueId();var h=holders.get(owner);if(h==null||!inputReady(owner))return "배낭 동기화 중이에요. 잠시 뒤 다시 해 주세요";
  var base=h.row;int active=base.active();if(active<1||active>unlocked||active>3)return "지금 쪽에서는 정리할 수 없어요";
  org.bukkit.inventory.ItemStack[][] sorted;byte[][] next;byte[] targetBytes;
  try{
   var now=new org.bukkit.inventory.ItemStack[unlocked][];
   for(int i=0;i<unlocked;i++){
    if(i==active-1){var a=new org.bukkit.inventory.ItemStack[27];for(int s=0;s<27;s++){var it=p.getInventory().getItem(9+s);a[s]=it==null||it.getType().isAir()?null:it.clone();}now[i]=a;}
    else now[i]=BagPagesBridge.items(base.pages()[i]);
   }
   sorted=LifeSpells.arrange(now);
   if(sorted==null)return "이미 잘 정리돼 있어요";
   next=base.pages().clone();
   for(int i=0;i<unlocked;i++)if(i!=active-1)next[i]=BagPagesBridge.encode(sorted[i]);
   next[active-1]=BagPageCodec.emptyPage();targetBytes=BagPagesBridge.encode(sorted[active-1]);
  }catch(RuntimeException ex){return "배낭 쪽을 읽지 못해 정리하지 않았어요";}
  if(check)return null;
  var incoming=sorted[active-1];UUID op;UUID nextHead=UUID.randomUUID();long nextSeq=base.sequence()+1;
  try{
   op=h.session.beginSwitch(h.user.isLocked());api.getPlugin().lockPlayer(owner);bags.sharedBlocked(owner);
   journal.save(owner,"shared",active,nextSeq,next,targetBytes);
   Path receipt=plugin.getDataFolder().toPath().resolve("bag-shared-pending").resolve(owner+"-operation.txt");
   Files.writeString(receipt,"lease="+h.session.lease()+"\nbase="+base.head()+"\nhead="+nextHead+"\nseq="+nextSeq+"\nsort=1\n");
   try(var channel=java.nio.channels.FileChannel.open(receipt,StandardOpenOption.WRITE)){channel.force(true);}
  }catch(Exception ex){block(owner,"Sort preparation failed: "+ex.getClass().getSimpleName());return "저장 확인에 실패했어요. 관리자에게 알려 주세요";}
  io.execute(()->{try{
   if(!shared.switchPages(owner,h.session.lease(),base.head(),nextHead,nextSeq,active,next))throw new IllegalStateException("Sort ownership CAS rejected");
   var row=shared.load(owner);
   main(()->{try{
    if(h.user.hasDisconnected()||h.session.phase()!=BagSyncSession.Phase.SWITCHING)throw new IllegalStateException("Player left before sort apply");
    for(int i=0;i<27;i++)p.getInventory().setItem(9+i,incoming[i]);
    p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin,"bag_page_commit"),org.bukkit.persistence.PersistentDataType.LONG,nextSeq);
    p.saveData();h.session.committedSwitch(op,nextHead,nextSeq,active);h.row=row;
    p.updateInventory();api.getPlugin().unlockPlayer(owner);bags.sharedReady(owner,row,bags.sharedUnlocked(owner));
    CmdKit.notify(p,"system","life-pack_sort","배낭을 정리했어요","열린 "+unlocked+"쪽을 종류별로 정리하고 같은 아이템을 합쳤어요",0);
   }catch(RuntimeException ex){block(owner,"Sort apply incomplete; WAL retained: "+ex.getClass().getSimpleName());}});
  }catch(Exception ex){block(owner,"Sort CAS failed; WAL retained: "+ex.getClass().getSimpleName());}});
  return null;
 }
 void beforeBridgeDisable(){
  closing=true;for(var h:holders.values())if(!h.drained)save(h.user,DataSnapshot.SaveCause.SERVER_SHUTDOWN);
  // A standalone plugin disable must not leave a drained no-op syncer in an otherwise running server.
  if(!plugin.getServer().isStopping()&&!api.getPlugin().isDisabling()&&api.getPlugin().getDataSyncer()==hook){
   for(var h:holders.values())h.user.getCustomDataStore().remove(BagHuskSnapshotLink.ID);
   api.setDataSyncer(original);terminate();
   plugin.getLogger().warning("Shared bag hook removed outside server shutdown; bag sessions require reconciliation; native inventory saves remain enabled");
  }
 }
 void terminate(){io.shutdown();try{io.awaitTermination(25,TimeUnit.SECONDS);connection.close();}catch(Exception ex){plugin.getLogger().warning("Shared bag finalization incomplete; original data retained");}}
}
