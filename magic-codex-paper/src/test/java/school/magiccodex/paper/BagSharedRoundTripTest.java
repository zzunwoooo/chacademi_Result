package school.magiccodex.paper;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BagSharedRoundTripTest {
 private static byte[] page(int id){var slots=new ArrayList<byte[]>();for(int i=0;i<27;i++)slots.add(i==0?new byte[]{(byte)id}:new byte[0]);return BagPageCodec.encodePage(slots);}
 private static BagSnapshotBinding initial(UUID owner,UUID head,UUID snap){return new BagSnapshotBinding(owner,snap,head,0,1,BagSnapshotBinding.hash("A|hotbarH|armorR"));}
 private static void seed(Connection c,UUID owner,UUID head,UUID snap)throws Exception{try(var p=c.prepareStatement("INSERT INTO codex_bag_shared_v1(uuid,head,seq,active,page2,native_snapshot,native_hash) VALUES(?,?,0,1,?,?,?)")){p.setString(1,owner.toString());p.setString(2,head.toString());p.setBytes(3,page(22));p.setString(4,snap.toString());p.setString(5,BagSnapshotBinding.hash("A|hotbarH|armorR"));p.executeUpdate();}}
 private static boolean handoff(BagSharedSql sql,UUID owner,BagSyncSession session,BagSnapshotBinding binding,String inv,boolean badRedis)throws Exception{
  return BagCommitFlow.run(binding,true,true,new BagCommitFlow.Port(){
   public boolean reserve()throws Exception{return sql.reserveCapture(owner,session.lease(),1,binding);}
   public void writeDatabase(){} public BagCommitFlow.Proof readDatabase(){return new BagCommitFlow.Proof(binding.snapshot(),inv,binding);}
   public void writeRedis(){} public BagCommitFlow.Proof peekRedis(){return new BagCommitFlow.Proof(binding.snapshot(),badRedis?"old":inv,binding);}
   public boolean acknowledgeCas(boolean release)throws Exception{return sql.acknowledgeNative(owner,session.lease(),binding,binding.snapshot(),inv,binding.snapshot(),inv,release);}
   public void releaseCheckout(){}public void unlock(){}public void blocked(String stage,Exception ex){session.block();}
  });
 }
 @Test void schoolWildSchoolKeepsTheSameActivePageAndBothInactiveInventories()throws Exception{
  try(var c=DriverManager.getConnection("jdbc:sqlite::memory:")){
   var sql=new BagSharedSql(c,false);sql.createSchemaForStaging();UUID owner=UUID.randomUUID(),head=UUID.randomUUID(),snap=UUID.randomUUID();seed(c,owner,head,snap);
   var first=initial(owner,head,snap);var school=new BagSyncSession(owner);var row=sql.claim(owner,first,school.lease(),"school");school.verifiedIncoming(first,row.head(),row.sequence(),row.active());school.complete(first,"A|hotbarH|armorR");
   UUID op=school.beginSwitch(false),next=UUID.randomUUID();assertTrue(sql.switchPages(owner,school.lease(),head,next,1,2,new byte[][]{page(11),new byte[0],new byte[0]}));school.committedSwitch(op,next,1,2);school.seal();var transfer=school.capture(UUID.randomUUID(),"B|hotbarH|armorR","B|hotbarH|armorR");assertTrue(handoff(sql,owner,school,transfer,"B|hotbarH|armorR",false));
   var wild=new BagSyncSession(owner);row=sql.claim(owner,transfer,wild.lease(),"wild");assertNotNull(row);wild.verifiedIncoming(transfer,row.head(),row.sequence(),row.active());wild.complete(transfer,"B|hotbarH|armorR");assertEquals(2,row.active());assertArrayEquals(page(11),row.pages()[0]);
   op=wild.beginSwitch(false);UUID back=UUID.randomUUID();assertTrue(sql.switchPages(owner,wild.lease(),next,back,2,1,new byte[][]{new byte[0],page(23),new byte[0]}));wild.committedSwitch(op,back,2,1);wild.seal();var returning=wild.capture(UUID.randomUUID(),"A|hotbarH|armorR","A|hotbarH|armorR");assertTrue(handoff(sql,owner,wild,returning,"A|hotbarH|armorR",false));
   var again=new BagSyncSession(owner);row=sql.claim(owner,returning,again.lease(),"school");assertNotNull(row);again.verifiedIncoming(returning,row.head(),row.sequence(),row.active());again.complete(returning,"A|hotbarH|armorR");assertEquals(1,row.active());assertArrayEquals(page(23),row.pages()[1]);
  }
 }
 @Test void redisFailureAfterPageCommitPreventsDestinationClaimAndPreservesOriginalPages()throws Exception{
  try(var c=DriverManager.getConnection("jdbc:sqlite::memory:")){
   var sql=new BagSharedSql(c,false);sql.createSchemaForStaging();UUID owner=UUID.randomUUID(),head=UUID.randomUUID(),snap=UUID.randomUUID();seed(c,owner,head,snap);var first=initial(owner,head,snap);var school=new BagSyncSession(owner);var row=sql.claim(owner,first,school.lease(),"school");school.verifiedIncoming(first,row.head(),row.sequence(),row.active());school.complete(first,"A|hotbarH|armorR");var op=school.beginSwitch(false);UUID next=UUID.randomUUID();sql.switchPages(owner,school.lease(),head,next,1,2,new byte[][]{page(11),new byte[0],new byte[0]});school.committedSwitch(op,next,1,2);school.seal();var transfer=school.capture(UUID.randomUUID(),"B|hotbarH|armorR","B|hotbarH|armorR");assertFalse(handoff(sql,owner,school,transfer,"B|hotbarH|armorR",true));assertNull(sql.claim(owner,transfer,UUID.randomUUID(),"wild"));assertEquals(school.lease(),sql.load(owner).lease());assertArrayEquals(page(11),sql.load(owner).pages()[0]);
  }
 }
}
