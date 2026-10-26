package kr.chacademy.story;
import org.junit.jupiter.api.Test;
import kr.chacademy.story.dialogue.Dialogue;
import kr.chacademy.story.dialogue.DialogueRunner;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DialogueResumeTest {
    @Test void resumeDoesNotRedirectOrReplayEntryEffectAndReportsExactLine() {
        var lines=List.of(new Dialogue.Line("me","","first"),new Dialogue.Line("me","","second"));
        var wake=new Dialogue.Scene("wake",List.of(new Dialogue.Redirect(new Dialogue.Need("teacher",50,null),"other")),lines,List.of(),"","e_wake",List.of(new Dialogue.AffinityChange("teacher",2)));
        var other=new Dialogue.Scene("other",List.of(),lines,List.of(),"","e_other",List.of());
        var dialogue=new Dialogue("story","Story","wake",Map.of(),Map.of("wake",wake,"other",other));
        List<String> calls=new ArrayList<>();
        var listener=new DialogueRunner.Listener(){
            public void event(String id,String event,String npc,int add){calls.add("event:"+event);}
            public void done(String id,String scene){calls.add("done:"+scene);}
            public void progress(String id,String scene,int line){calls.add(scene+":"+line);}
        };
        var runner=new DialogueRunner(dialogue,null,Map.of("teacher",70),Map.of(),listener,"wake",1);
        assertFalse(runner.isEnded());assertEquals(List.of("wake:1"),calls);
        assertEquals("second",runner.frame().get("text"));
        // 이어 본 곳이 마지막 대사: 넘기면 그 장면에서 끝난다
        runner.choose("");
        assertTrue(runner.isEnded());assertEquals(List.of("wake:1","done:wake"),calls);
    }
}
