package school.magiccodex.paper;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import school.magiccodex.database.DatabaseSettings;

/** Async facade. Fish rows survive terminal actions; JDBC callbacks only publish committed state. */
final class FishTank implements AutoCloseable {
 record Fish(String id,String fish,double size,long caughtAt,String token) {
  Fish(String id,String fish,double size,long caughtAt){this(id,fish,size,caughtAt,"");}
 }
 static final class Tank {final List<Fish> fish=new ArrayList<>();final Set<String> locks=new HashSet<>();boolean loaded,loading;final List<Runnable> waiting=new ArrayList<>();}
 enum Put {OK,DUPLICATE,FULL,FAILED}
 private final MagicCodexBridge plugin;
 private final DatabaseSettings settings;
 private final java.nio.file.Path sqlite;
 private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"MagicCodex-fish-tank");t.setDaemon(true);return t;});
 private final Map<UUID,Tank> tanks=new HashMap<>();
 private final FishCatchJournal journal;
 private Connection connection;private FishTankSql storage;private volatile int capacity=100;
 FishTank(MagicCodexBridge plugin){
  this.plugin=plugin;try{journal=new FishCatchJournal(plugin.getDataFolder().toPath().resolve("fish-catch-recovery"));}catch(java.io.IOException e){throw new IllegalStateException("Cannot initialize catch recovery journal",e);}DatabaseSettings s;
  try{var folder=plugin.getDataFolder().toPath();var dedicated=folder.resolve("player-state-database.properties");s=DatabaseSettings.load(java.nio.file.Files.exists(dedicated)?dedicated:folder.resolve("database.properties"));}
  catch(Exception e){s=new DatabaseSettings(false,"","","");}
  settings=s;sqlite=plugin.getDataFolder().toPath().resolve("fishing.db");
  io.execute(()->{try{db();int stuck=db().stuckCount();if(stuck>0)plugin.getLogger().warning("FISH TANK 판매·꺼내기 도중 멈춘 물고기 "+stuck+"마리 (수조 칸 차지). /유저관리 <닉네임> 낚시 수조 멈춘처리 로 확인하세요");}catch(Exception e){failure("schema",e);}});
  io.scheduleWithFixedDelay(()->{try{recover();}catch(Exception e){failure("catch recovery (records retained)",e);}},5,5,TimeUnit.SECONDS);
 }
 private FishTankSql db()throws Exception{
  if(connection==null||connection.isClosed()||!connection.isValid(2)){
   if(connection!=null)try{connection.close();}catch(Exception ignored){}
   connection=settings.connect(sqlite);settings.configure(connection);storage=new FishTankSql(connection,settings.mariaDb());try{storage.init();}catch(Exception e){connection.close();connection=null;storage=null;throw e;}
  }return storage;
 }
 private void failure(String action,Exception e){plugin.getLogger().severe("FISH TANK "+action+" failed ("+e.getClass().getSimpleName()+")");}
 private void main(Runnable r){if(plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,r);}
 void capacity(int n){capacity=Math.clamp(n,1,school.magiccodex.protocol.FishTankProtocol.MAX_FISH);}int capacity(){return capacity;}
 Tank tank(UUID id){return tanks.computeIfAbsent(id,k->new Tank());}int count(UUID id){return tank(id).fish.size();}boolean full(UUID id){return count(id)>=capacity;}boolean loaded(UUID id){return tank(id).loaded;}
 void refresh(UUID id,Runnable done){
  var t=tank(id);if(done!=null)t.waiting.add(done);if(t.loading)return;t.loading=true;
  io.execute(()->{
   List<Fish> fish=List.of();Set<String> locks=Set.of();boolean ok=false;
   try{fish=db().list(id);locks=db().locks(id);ok=true;}catch(Exception e){failure("read",e);}
   var f=fish;var l=locks;boolean success=ok;
   main(()->{t.loaded=success;if(success){t.fish.clear();t.fish.addAll(f);t.locks.clear();t.locks.addAll(l);}t.loading=false;var callbacks=List.copyOf(t.waiting);t.waiting.clear();for(var r:callbacks)r.run();});
  });
 }
 void forget(UUID id){tanks.remove(id);}
 Fish add(UUID id,String species,double size){return add(id,species,size,null);}
 Fish add(UUID id,String species,double size,BiConsumer<Fish,Put> done){
  var f=new Fish(UUID.randomUUID().toString(),species,size,System.currentTimeMillis());
  final FishCatchJournal.Pending pending;try{pending=journal.save(id,f);}catch(java.io.IOException e){throw new IllegalStateException("Cannot persist caught fish",e);}
  tank(id).fish.add(f);write(id,f,result->{if(result==Put.OK||result==Put.DUPLICATE)try{journal.acknowledge(pending);}catch(java.io.IOException e){failure("catch acknowledge",e);}else tank(id).fish.removeIf(x->x.id().equals(f.id()));if(done!=null)done.accept(f,result);});return f;
 }
 private void recover()throws Exception{for(var pending:journal.pending()){Put result=db().put(pending.owner(),pending.fish(),capacity);if(result==Put.OK||result==Put.DUPLICATE)journal.acknowledge(pending);}}
 void itemInfo(Collection<String> ids,Consumer<Map<String,Fish>> done){
  var copy=List.copyOf(ids);io.execute(()->{Map<String,Fish> rows;try{rows=db().items(copy);}catch(Exception e){failure("item metadata",e);rows=Map.of();}var out=rows;main(()->done.accept(out));});
 }
 void registerItem(Fish fish,BiConsumer<Fish,Boolean> done){
  var saved=new Fish(fish.id(),fish.fish(),fish.size(),fish.caughtAt(),UUID.randomUUID().toString());
  io.execute(()->{boolean ok=false;try{ok=db().registerItem(saved);}catch(Exception e){failure("register item",e);}boolean success=ok;main(()->done.accept(saved,success));});
 }
 void put(UUID id,Fish fish,Consumer<Put> done){write(id,fish,done);}
 private void write(UUID id,Fish fish,Consumer<Put> done){
  int cap=capacity;io.execute(()->{Put result;
   try{result=db().put(id,fish,cap);}catch(Exception e){result=Put.FAILED;failure("insert (item remains owned; retry checks committed identity)",e);}
   Put out=result;main(()->{if(out==Put.OK){var t=tank(id);t.fish.removeIf(x->x.id().equals(fish.id()));t.fish.add(fish);}if(done!=null)done.accept(out);});
  });
 }
 void remove(UUID id,Collection<Fish> fish,Consumer<List<Fish>> done){remove(id,fish,FishTankSql.Action.RELEASE,done);}
 void remove(UUID id,Collection<Fish> fish,FishTankSql.Action action,Consumer<List<Fish>> done){
  var list=List.copyOf(fish);io.execute(()->{List<Fish> out;
   try{out=db().claim(id,list,action);}catch(Exception e){failure("claim",e);out=List.of();}
   var claimed=out;main(()->{var ids=new HashSet<String>();for(Fish f:claimed)ids.add(f.id());tank(id).fish.removeIf(f->ids.contains(f.id()));done.accept(claimed);});
  });
 }
 void finish(UUID id,List<Fish> fish,FishTankSql.Action action,boolean success,Consumer<Boolean> done){
  io.execute(()->{boolean ok=false;try{ok=db().finish(id,fish,action,success);}catch(Exception e){failure("finalize (claim retained for reconciliation)",e);}boolean out=ok;
   main(()->{refresh(id,()->{if(done!=null)done.accept(out);});});
  });
 }
 /** 판매·꺼내기 도중 멈춘 물고기 목록 (실패하면 null). */
 void stuck(UUID id,Consumer<List<FishTankSql.Stuck>> done){
  io.execute(()->{List<FishTankSql.Stuck> rows=null;try{rows=db().stuck(id);}catch(Exception e){failure("stuck claims",e);}var out=rows;main(()->done.accept(out));});
 }
 void locks(UUID id,Set<String> species,Consumer<Boolean> done){
  var copy=Set.copyOf(species);io.execute(()->{boolean ok=false;try{db().locks(id,copy);ok=true;}catch(Exception e){failure("locks",e);}boolean out=ok;
   main(()->{if(out){var t=tank(id);t.locks.clear();t.locks.addAll(copy);}if(done!=null)done.accept(out);});
  });
 }
 @Override public void close(){io.shutdown();try{io.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{if(connection!=null)connection.close();}catch(Exception ignored){}}
}