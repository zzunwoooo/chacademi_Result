package school.magiccodex.paper;
import java.nio.file.Path;import java.util.UUID;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import school.magiccodex.database.DatabaseSettings;import school.magiccodex.protocol.MileageProtocol;
class MileageStoreTest {
 @TempDir Path dir;
 MileageStore open() throws Exception{return new MileageStore(DatabaseSettings.load(dir.resolve("none.properties")),dir.resolve("isolated.db"));}
 @Test void replayDebitsOnceAndPaidOrderKeepsOriginalPayload() throws Exception {UUID owner=UUID.randomUUID();try(var s=open()){
  s.change(owner,"\uC124\uC815",1000);String product=s.add(0,"Item","",100,true,new byte[]{1,2,3});
  var first=s.buy(owner,"request",product,3,100);assertNotNull(first.order());assertEquals(700,first.balance());assertEquals(300,first.order().total());
  s.replaceItem(product,new byte[]{9});s.delete(product);var replay=s.buy(owner,"request",product,3,100);assertEquals(first.order().id(),replay.order().id());assertEquals(700,s.balance(owner));assertArrayEquals(new byte[]{1,2,3},replay.order().bytes());
  assertEquals(1,s.paid(owner,100).size());s.done(first.order().id());s.done(first.order().id());assertTrue(s.paid(owner,100).isEmpty());
 }}
 @Test void invalidPriceHiddenProductQuantityAndFundsNeverDebit() throws Exception {UUID owner=UUID.randomUUID();try(var s=open()){
  s.change(owner,"\uC124\uC815",200);String product=s.add(0,"Item","",100,true,new byte[]{1});
  assertNull(s.buy(owner,"stale",product,1,99).order());assertNull(s.buy(owner,"funds",product,3,100).order());assertNull(s.buy(owner,"quantity",product,100,100).order());
  s.save(product,0,"Item","",100,false);assertNull(s.buy(owner,"hidden",product,1,100).order());assertEquals(200,s.balance(owner));assertTrue(s.paid(owner,100).isEmpty());
 }}
 @Test void balanceBoundsRollbackAndOwnerCannotReadAnotherOwnersReplay() throws Exception {UUID owner=UUID.randomUUID(),other=UUID.randomUUID();try(var s=open()){
  s.change(owner,"\uC124\uC815",MileageProtocol.MAX_BALANCE);assertThrows(IllegalStateException.class,()->s.change(owner,"\uCD94\uAC00",1));assertEquals(MileageProtocol.MAX_BALANCE,s.balance(owner));
  s.change(owner,"\uC124\uC815",100);assertThrows(IllegalStateException.class,()->s.change(owner,"\uBE7C\uAE30",101));String product=s.add(0,"Item","",100,true,new byte[]{1});s.buy(owner,"request",product,1,100);assertNull(s.buy(other,"request",product,1,100).order());assertEquals(0,s.balance(other));
 }}
}
