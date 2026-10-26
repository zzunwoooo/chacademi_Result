package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import school.magiccodex.protocol.JournalProtocol.Entry;
class JournalAdminRulesTest {
 Entry e(String id,String title,String state,boolean main){return new Entry("story",id,title,"",state,main,"","");}
 @Test void duplicateSpacedTitlesRejectInsteadOfChoosingAnId(){var rows=List.of(e("a","첫 번째 만남","active",true),e("b","첫 번째 만남","active",true));assertNull(JournalAdminRules.resolve(rows,"첫 번째 만남"));assertEquals("a",JournalAdminRules.resolve(rows,"a").id());assertEquals(List.of("첫 번째 만남"),JournalAdminRules.choices(rows));}
 @Test void titleFirstWithBareIdFallback(){var rows=List.of(e("a","첫 만남","active",true),e("b","","active",true));assertEquals(List.of("b","첫 만남"),JournalAdminRules.choices(rows));assertNull(JournalAdminRules.resolve(rows,"story:a"));}
 @Test void completeOnlyTargetsActiveRecordsInChosenCategory(){var rows=List.of(e("a","A","active",true),e("b","B","claimed",true),e("c","C","available",true),e("d","D","active",false));assertEquals(List.of("a"),JournalAdminRules.candidates(rows,true,"완료").stream().map(Entry::id).toList());assertTrue(JournalAdminRules.candidates(rows,true,"주기").stream().allMatch(e->e.id().equals("c")));}
}
