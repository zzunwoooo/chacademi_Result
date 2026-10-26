package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** 낚시 보상 우편 대기열 (A01 안 B, 2026-10-21): 저장 → 대기 목록 → 확인 뒤 삭제, 깨진 파일은 자동 처리하지 않음. */
class FishMailOutboxTest {
 @TempDir Path temp;
 @Test void savedRecordStaysUntilAcknowledged()throws Exception{
  var box=new FishMailOutbox(temp.resolve("outbox"));UUID owner=UUID.randomUUID();
  var saved=box.save("fishing:1:2:0",owner,FishingBridge.receiptName("fishing:1:2"),"낚시 보상","본문",List.of(new byte[]{1,2,3}),"","");
  var broken=new ArrayList<Path>();var pending=box.pending(broken);
  assertEquals(1,pending.size());assertTrue(broken.isEmpty());
  var e=pending.getFirst();assertEquals("fishing:1:2:0",e.source());assertEquals(owner,e.recipient());assertArrayEquals(new byte[]{1,2,3},e.items().getFirst());assertEquals(saved.receipt(),e.receipt());
  // 같은 source 로 다시 저장해도 레코드는 하나 (우편 source_key 와 같은 키)
  box.save("fishing:1:2:0",owner,saved.receipt(),"낚시 보상","본문",List.of(new byte[]{1,2,3}),"","");assertEquals(1,box.pending(null).size());
  box.acknowledge(e);assertTrue(box.pending(null).isEmpty());
 }
 @Test void tankClaimIsKept()throws Exception{
  var box=new FishMailOutbox(temp.resolve("outbox"));String fish=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
  box.save("fish-tank-admin:"+fish+":"+token+":0",UUID.randomUUID(),FishingBridge.receiptName("x"),"t","b",List.of(new byte[]{9}),fish,token);
  var e=box.pending(null).getFirst();assertEquals(fish,e.tankFish());assertEquals(token,e.tankToken());
 }
 @Test void brokenRecordIsReportedNotDeleted()throws Exception{
  var root=temp.resolve("outbox");var box=new FishMailOutbox(root);
  Path bad=root.resolve(FishMailOutbox.fileName("x"));Files.writeString(bad,"source=y\nitems=1\n");
  var broken=new ArrayList<Path>();assertTrue(box.pending(broken).isEmpty());assertEquals(List.of(bad),broken);assertTrue(Files.exists(bad));
 }
 @Test void receiptKeyIsAValidNamespacedKeyPart(){assertTrue(FishingBridge.receiptName("fishing-unequip:"+UUID.randomUUID()).matches("[a-z0-9_]{1,64}"));}
}
