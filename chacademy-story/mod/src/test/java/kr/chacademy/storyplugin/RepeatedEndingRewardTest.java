package kr.chacademy.storyplugin;
import java.util.*;
import java.nio.file.Path;
import kr.chacademy.story.dialogue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RepeatedEndingRewardTest {
 @TempDir Path temp;
 @Test void twoNormalPlaysRunTwoEndCommandsButAffinityAndFirstRewardOnlyOnce() throws Exception {
  var ledger=new LocalDialogueStore(temp.resolve("progress.properties"));var sessions=new LocalEndingSessions();UUID player=UUID.randomUUID();
  var registry=new ServerCommandRegistry();registry.replace(Map.of("commands",Map.of("ch1-3",List.of("cutscene play {player} test"))));
  var dialogue=DialogueParser.parse("ch1-3",Map.of("scenes",Map.of("s20",Map.of("lines",List.of(Map.of("text","Finish")),"affinity",List.of(Map.of("npc","teacher","add",5))))));
  int[] counts={0,0};
  for(int play=0;play<2;play++){
   var session=sessions.begin(player,"ch1-3",registry.commands("ch1-3"),0);sessions.ready(player,session.id(),session.token(),0);
   var runner=new DialogueRunner(dialogue,null,Affinity.fromVars(Map.of(Affinity.VAR_PREFIX+"teacher","30")),Map.of(),new DialogueRunner.Listener(){
    public boolean affinity(String id,String key,List<Dialogue.AffinityChange> changes){try{return ledger.claimAffinity(id,key,changes);}catch(Exception e){throw new RuntimeException(e);}}
    public void event(String id,String event,String npc,int add){}
    public void done(String id,String ending){counts[0]+=sessions.finish(player,id,session.token(),ending,0).size();try{if(ledger.complete(id))counts[1]++;}catch(Exception e){throw new RuntimeException(e);}}
   });runner.choose("");runner.choose("");runner.stop();
  }
  assertArrayEquals(new int[]{2,1},counts);assertTrue(ledger.hasAffinity("ch1-3","scene.s20"),"호감도 변화는 한 번 처리 표시만 (값은 서버)");assertTrue(ledger.done("ch1-3"));
 }
 @Test void cancelDoesNotCompleteOrClaimFirstReward() throws Exception {
  var ledger=new LocalDialogueStore(temp.resolve("progress.properties"));
  var d=DialogueParser.parse("ch1-3",Map.of("scenes",Map.of("s20",Map.of("lines",List.of(Map.of("text","Finish"))))));int[] counts={0,0};
  var runner=new DialogueRunner(d,null,Map.of(),Map.of(),new DialogueRunner.Listener(){public void event(String a,String b,String c,int v){}public void done(String a,String b){counts[0]++;}public void aborted(String id){counts[1]++;}});
  runner.stop();runner.stop();runner.choose("");assertArrayEquals(new int[]{0,1},counts);assertFalse(ledger.done("ch1-3"));
 }
}
