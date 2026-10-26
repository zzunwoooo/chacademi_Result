package kr.chacademy.story;
import kr.chacademy.story.dialogue.Affinity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 로컬 대화의 선택지 조건은 서버가 보낸 MagicCodex 호감도만 쓴다 (2026-10-21). */
class AffinityVarsTest {
 @Test void onlyServerSentAffinityIsUsedAndNotSubstitutedAsText(){
  var vars=Map.of("{player}","별하",Affinity.VAR_PREFIX+"teacher_ellna","24",Affinity.VAR_PREFIX+"bad","x",Affinity.VAR_PREFIX,"3");
  assertEquals(Map.of("teacher_ellna",24),Affinity.fromVars(vars));
  assertEquals(Map.of("{player}","별하"),Affinity.withoutAffinity(vars));
  assertEquals(Map.of(),Affinity.fromVars(null));
 }
}
