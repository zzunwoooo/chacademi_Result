package school.magiccodex.paper;
import java.nio.file.*;import java.sql.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.paper.FishTank.Fish;import school.magiccodex.paper.FishTank.Put;import school.magiccodex.paper.FishTankSql.Action;
class FishTankSqlTest {
 @TempDir Path dir;final UUID owner=UUID.randomUUID();
 Connection connection()throws Exception{var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("tank.db"));try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=10000");}return c;}
 Fish fish(){return new Fish(UUID.randomUUID().toString(),"fish_001",25.5,1760000000000L);}
 FishTankSql store(Connection c)throws Exception{var s=new FishTankSql(c,false);s.init();return s;}
 @Test void itemIdentityDateAndOneUseGenerationSurviveReentry()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();assertEquals(Put.OK,s.put(owner,f,100));
   var first=s.claim(owner,List.of(f),Action.ITEMIZE);assertEquals(1,first.size());assertTrue(s.finish(owner,first,Action.ITEMIZE,true));
   assertEquals(Put.OK,s.put(owner,first.getFirst(),100));assertEquals(Put.DUPLICATE,s.put(owner,first.getFirst(),100));
   var second=s.claim(owner,s.list(owner),Action.ITEMIZE);assertNotEquals(first.getFirst().token(),second.getFirst().token());s.finish(owner,second,Action.ITEMIZE,true);
   assertEquals(Put.DUPLICATE,s.put(owner,first.getFirst(),100));assertEquals(Put.OK,s.put(owner,second.getFirst(),100));
   var actual=s.list(owner).getFirst();assertEquals(f.id(),actual.id());assertEquals(f.caughtAt(),actual.caughtAt());assertEquals(f.size(),actual.size());
  }
 }
 @Test void terminalSaleAndReleaseNeverAllowCopiedItemsBack()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,100);var item=s.claim(owner,List.of(f),Action.ITEMIZE);s.finish(owner,item,Action.ITEMIZE,true);s.put(owner,item.getFirst(),100);
   var sale=s.claim(owner,s.list(owner),Action.SELL);s.finish(owner,sale,Action.SELL,true);assertEquals(Put.DUPLICATE,s.put(owner,item.getFirst(),100));
   var released=fish();s.put(owner,released,100);s.claim(owner,List.of(released),Action.RELEASE);assertEquals(Put.DUPLICATE,s.put(owner,released,100));assertTrue(s.list(owner).isEmpty());
  }
 }
 @Test void concurrentCapacityChecksCannotBothTakeTheLastSlot()throws Exception{
  try(var a=connection();var b=connection()){var one=store(a);var two=store(b);one.put(owner,fish(),2);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
   try{var x=executor.submit(()->{start.await();return one.put(owner,fish(),2);});var y=executor.submit(()->{start.await();return two.put(owner,fish(),2);});start.countDown();var results=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(r->r==Put.OK).count());assertEquals(1,results.stream().filter(r->r==Put.FULL).count());assertEquals(2,one.list(owner).size());}finally{executor.shutdownNow();}
  }
 }
 @Test void concurrentClaimsOnlyOneServerCanPayOrGive()throws Exception{
  try(var a=connection();var b=connection()){var one=store(a);var two=store(b);var f=fish();one.put(owner,f,100);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
   try{var x=executor.submit(()->{start.await();return one.claim(owner,List.of(f),Action.SELL);});var y=executor.submit(()->{start.await();return two.claim(owner,List.of(f),Action.ITEMIZE);});start.countDown();assertEquals(1,x.get(15,TimeUnit.SECONDS).size()+y.get(15,TimeUnit.SECONDS).size());}finally{executor.shutdownNow();}
  }
 }
 @Test void committedLockIsRecheckedInsideSaleTransaction()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,100);var stale=s.list(owner);s.locks(owner,Set.of(f.fish()));assertTrue(s.claim(owner,stale,Action.SELL).isEmpty());assertEquals(1,s.list(owner).size());}
 }
 @Test void failedLockReplacementRollsBackItsDelete()throws Exception{
  try(var c=connection()){var s=store(c);s.locks(owner,Set.of("fish_001"));try(var p=c.createStatement()){p.execute("CREATE TRIGGER fail_lock BEFORE INSERT ON codex_fish_lock WHEN NEW.fish='fish_002' BEGIN SELECT RAISE(ABORT,'injected');END");}
   assertThrows(SQLException.class,()->s.locks(owner,Set.of("fish_002")));assertEquals(Set.of("fish_001"),s.locks(owner));}
 }
 @Test void midBatchFailureRollsBackEveryClaim()throws Exception{
  try(var c=connection()){var s=store(c);var first=fish();var second=fish();s.put(owner,first,100);s.put(owner,second,100);
   try(var p=c.createStatement()){p.execute("CREATE TRIGGER fail_second BEFORE UPDATE ON codex_fish_tank WHEN NEW.id='"+second.id()+"' BEGIN SELECT RAISE(ABORT,'injected');END");}
   assertThrows(SQLException.class,()->s.claim(owner,List.of(first,second),Action.SELL));assertEquals(2,s.list(owner).size());}
 }
 @Test void failedPaymentRestoresSameFishAndReservesCapacityUntilResolved()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,1);var claimed=s.claim(owner,List.of(f),Action.SELL);assertEquals(Put.FULL,s.put(owner,fish(),1));assertTrue(s.finish(owner,claimed,Action.SELL,false));assertEquals(f,s.list(owner).getFirst());assertEquals(1,s.claim(owner,List.of(f),Action.SELL).size());}
 }
 @Test void reconnectKeepsWithdrawnTokenAndSoldTombstone()throws Exception{
  Fish item;try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,100);var claimed=s.claim(owner,List.of(f),Action.ITEMIZE);item=claimed.getFirst();s.finish(owner,claimed,Action.ITEMIZE,true);}
  try(var c=connection()){var s=store(c);assertEquals(Put.OK,s.put(owner,item,100));var sale=s.claim(owner,s.list(owner),Action.SELL);s.finish(owner,sale,Action.SELL,true);}
  try(var c=connection()){assertEquals(Put.DUPLICATE,store(c).put(owner,item,100));}
 }
 @Test void failureAfterPaymentQuarantinesClaimRatherThanReselling()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,100);var sale=s.claim(owner,List.of(f),Action.SELL);
   try(var p=c.createStatement()){p.execute("CREATE TRIGGER fail_finish BEFORE UPDATE ON codex_fish_tank WHEN NEW.fish_state='SOLD' BEGIN SELECT RAISE(ABORT,'injected');END");}
   assertThrows(SQLException.class,()->s.finish(owner,sale,Action.SELL,true));assertTrue(s.claim(owner,List.of(f),Action.SELL).isEmpty());assertEquals(Put.DUPLICATE,s.put(owner,f,100));}
 }
 @Test void foreignOwnerAndChangedItemMetadataCannotClaimValue()throws Exception{
  try(var c=connection()){var s=store(c);var f=fish();s.put(owner,f,100);assertTrue(s.claim(UUID.randomUUID(),List.of(f),Action.SELL).isEmpty());var take=s.claim(owner,List.of(f),Action.ITEMIZE);s.finish(owner,take,Action.ITEMIZE,true);var item=take.getFirst();
   assertEquals(Put.DUPLICATE,s.put(owner,new Fish(item.id(),item.fish(),999,item.caughtAt(),item.token()),100));assertEquals(Put.OK,s.put(owner,item,100));}
 }
 @Test void legacyRowsMigrateWithoutLosingIdentity()throws Exception{
  try(var c=connection()){try(var p=c.createStatement()){p.execute("CREATE TABLE codex_fish_tank(id VARCHAR(36) PRIMARY KEY,uuid VARCHAR(36),fish VARCHAR(64),size DOUBLE,caught_at BIGINT)");}var s=store(c);var f=fish();assertEquals(Put.OK,s.put(owner,f,100));assertEquals(f,s.list(owner).getFirst());}
 }
 @Test void itemMetadataRegistrationPersistsWithoutDiscardingIdentity()throws Exception{
  var f=fish();var item=new Fish(f.id(),f.fish(),f.size(),f.caughtAt(),UUID.randomUUID().toString());
  try(var c=connection()){var s=store(c);assertTrue(s.registerItem(item));assertEquals(Map.of(item.id(),item),s.items(List.of(item.id(),item.id())));assertTrue(s.list(owner).isEmpty());}
  try(var c=connection()){var s=store(c);assertEquals(Put.OK,s.put(owner,item,100));assertTrue(s.items(List.of(item.id())).isEmpty());assertThrows(SQLException.class,()->s.registerItem(item));assertEquals(item,s.list(owner).getFirst());}
 }
}