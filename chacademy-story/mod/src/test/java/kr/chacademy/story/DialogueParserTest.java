package kr.chacademy.story;
import org.junit.jupiter.api.Test;
import kr.chacademy.story.dialogue.DialogueParser;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DialogueParserTest {
    private static Map<String,Object> map(Object... kv){var m=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    private static Map<String,Object> root(String sceneId,Map<String,Object> scene){return map("title","t","scenes",map(sceneId,scene));}
    private static Map<String,Object> line(String text){return map("who","me","text",text);}
    private static String error(String id,Object root){return assertThrows(IOException.class,()->DialogueParser.parse(id,root)).getMessage();}

    @Test void optionalJournalDescriptionUsesUserMetadataWithoutExampleFallback() throws IOException {
        var r=root("start",map("lines",List.of(line("hello"))));
        assertEquals("",DialogueParser.parse("chapter",r).description()); assertEquals("",DialogueParser.parse("chapter",r).chapter()); r.put("chapter","제1장");
        r.put("description","User-authored journal details");
        var d=DialogueParser.parse("chapter",r);
        assertEquals("제1장",d.chapter()); assertEquals("t",d.title());assertEquals("User-authored journal details",d.description());
    }
    @Test void readsScenesChoicesAndIgnoresOldWindowStyleKeys() throws IOException {
        var r=root("wake",map("lines",List.of(line("안녕")),"event","e_wake","choices",List.of(map("text","네","to","wake","event","c_wake_1","need",map("npc","teacher","min",10,"max",""),"affinity",List.of(map("npc","teacher","add",99))))));
        // 예전 자체 대화창용 값: 이상한 값이어도 오류가 아니다 (읽지 않는다)
        r.put("dim",Double.NaN);r.put("type_speed","빠르게");r.put("type_sound","minecraft:block.note_block.hat");r.put("type_sound_volume",Double.POSITIVE_INFINITY);
        var d=DialogueParser.parse("ch1_wakeup",r);
        assertEquals("wake",d.start());var s=d.scenes().get("wake");
        assertEquals("e_wake",s.event());assertEquals("c_wake_1",s.choices().get(0).event());
        assertEquals(10,s.choices().get(0).need().min());assertNull(s.choices().get(0).need().max());
        assertEquals(20,s.choices().get(0).affinity().get(0).add(),"한 번에 ±20");
    }
    @Test void sceneIdsAndEventNamesFollowTheServerRuleAndErrorsNameTheKey(){
        for(String bad:new String[]{"Wake","장면1","a b","x".repeat(65),"a.b"})assertTrue(error("ch1",root(bad,map("lines",List.of(line("x"))))).contains("scenes."+bad),bad);
        assertTrue(error("ch1",root("wake",map("event","E Wake"))).contains("scenes.wake.event"));
        assertTrue(error("ch1",root("wake",map("choices",List.of(map("text","a"),map("text","b","event","선택"))))).contains("scenes.wake.choices[1].event"));
        assertTrue(error("Bad Id",root("wake",map())).contains("대화 id"));
        assertDoesNotThrow(()->DialogueParser.parse("ch1",root("a-b_9",map("event","c_a-b_1"))));
    }
    @Test void nonFiniteNumbersAreRejectedWithTheirKey(){
        assertTrue(error("ch1",root("wake",map("choices",List.of(map("text","a","need",map("npc","teacher","min",Double.NaN)))))).contains("scenes.wake.choices[0].need.min"));
        assertTrue(error("ch1",root("wake",map("choices",List.of(map("text","a","need",map("npc","teacher","max","Infinity")))))).contains("need.max"));
        assertTrue(error("ch1",root("wake",map("redirect",List.of(map("npc","teacher","min",Double.NEGATIVE_INFINITY,"to","x"))))).contains("scenes.wake.redirect[0].min"));
        assertTrue(error("ch1",root("wake",map("affinity",List.of(map("npc","teacher","add","NaN"))))).contains("scenes.wake.affinity[0].add"));
        assertTrue(error("ch1",root("wake",map("affinity",map("teacher",Double.NaN)))).contains("scenes.wake.affinity.teacher"));
    }
    @Test void choicesAreCappedAtWhatTheDialogueWindowCanShow() throws IOException {
        var six=new ArrayList<Object>();for(int i=0;i<DialogueParser.MAX_CHOICES;i++)six.add(map("text","c"+i));
        assertEquals(6,DialogueParser.parse("ch1",root("wake",map("choices",six))).scenes().get("wake").choices().size());
        var seven=new ArrayList<>(six);seven.add(map("text","c6"));
        String message=error("ch1",root("wake",map("choices",seven)));
        assertTrue(message.contains("scenes.wake.choices")&&message.contains("7")&&message.contains("6"),message);
    }
    @Test void overlongLinesAndStructuralProblemsAreClearErrors(){
        assertTrue(error("ch1",root("wake",map("lines",List.of(line("x"),line("가".repeat(DialogueParser.MAX_LINE_CHARS+1)))))).contains("scenes.wake.lines[1]"));
        assertDoesNotThrow(()->DialogueParser.parse("ch1",root("wake",map("lines",List.of(line("가".repeat(DialogueParser.MAX_LINE_CHARS)))))));
        assertTrue(error("ch1",map("scenes",map())).contains("장면이 없음"));
        var r=root("wake",map());r.put("start","nowhere");assertTrue(error("ch1",r).contains("nowhere"));
        assertTrue(error("ch1",null).contains("비어"));
        assertTrue(error("ch1",map("speakers",map("t",map("portraits",map("기본","../t.png"))),"scenes",map("wake",map()))).contains("../t.png"));
    }
}
