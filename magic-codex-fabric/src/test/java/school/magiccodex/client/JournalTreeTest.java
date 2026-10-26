package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import school.magiccodex.protocol.JournalProtocol.Entry;
class JournalTreeTest {
 Entry e(String id,boolean main,String state){return new Entry("story",id,id,"",state,main,"","");}
 @Test void independentGroupsAndChaptersPreserveUnrelatedExpansion(){var rows=List.of(e("a",true,"active"),e("b",true,"active"),e("c",false,"active"));var all=JournalTree.rows(rows,false,k->k.endsWith("a")?"1장":"2장",Set.of());assertEquals(7,all.size());var one=JournalTree.rows(rows,false,k->k.endsWith("a")?"1장":"2장",Set.of("main:1장"));assertTrue(one.stream().noneMatch(x->x.key().equals("story:a")));assertTrue(one.stream().anyMatch(x->x.key().equals("story:b")));assertTrue(one.stream().anyMatch(x->x.key().equals("story:c")));}
 @Test void legacyMissingChapterAndCompletedFilterRemainUsable(){var rows=List.of(e("a",true,"claimed"),e("b",true,"active"));var all=JournalTree.rows(rows,true,k->"",Set.of());assertEquals(3,all.size());assertEquals("story:a",all.get(1).key());assertEquals(1,all.get(1).depth());}
}
