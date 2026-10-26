package school.magiccodex.client.api;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalFrameTest {
    private static Map<String,Object> base(){
        var m=new HashMap<String,Object>();m.put("session","story:ch1_wakeup");m.put("sequence",3);
        m.put("title","1장");m.put("speaker","마중 선생님");m.put("text","어서 오시오.");return m;
    }
    @Test void minimalFrameUsesServerDialogueDefaults(){
        var f=ExternalFrame.parse(base());
        assertEquals("story:ch1_wakeup",f.session());assertEquals(3,f.sequence());assertEquals("어서 오시오.",f.text());
        assertTrue(f.closable());assertFalse(f.last());assertFalse(f.playerPortrait());
        assertTrue(f.choices().isEmpty());assertNull(f.portraitFile());assertEquals("",f.portrait());assertEquals("",f.message());
    }
    @Test void choicesAcceptListsAndArraysAndKeepOrder(){
        var m=base();m.put("choices",List.of(List.of("c0","괜찮아요"),new String[]{"c1","꿈을 꿨어요"},Map.entry("c2"," ")));
        var f=ExternalFrame.parse(m);
        assertEquals(List.of(new ExternalFrame.Choice("c0","괜찮아요"),new ExternalFrame.Choice("c1","꿈을 꿨어요"),new ExternalFrame.Choice("c2","…")),f.choices());
    }
    @Test void structuralErrorsAreRejectedWithoutPartialFrames(){
        for(Object session:new Object[]{null,"","a b","한글","x".repeat(81),7}){var m=base();m.put("session",session);assertThrows(IllegalArgumentException.class,()->ExternalFrame.parse(m));}
        for(Object sequence:new Object[]{null,-1,"3",(long)Integer.MAX_VALUE+1}){var m=base();m.put("sequence",sequence);assertThrows(IllegalArgumentException.class,()->ExternalFrame.parse(m));}
        var many=base();var seven=new ArrayList<List<String>>();for(int i=0;i<7;i++)seven.add(List.of("c"+i,"x"));many.put("choices",seven);
        assertThrows(IllegalArgumentException.class,()->ExternalFrame.parse(many));
        for(Object choices:new Object[]{"c0",List.of(List.of("C0","대문자")),List.of(List.of("c0","a"),List.of("c0","b")),List.of(List.of("only-id")),List.of(List.of("",""))}){
            var m=base();m.put("choices",choices);assertThrows(IllegalArgumentException.class,()->ExternalFrame.parse(m));
        }
        assertThrows(IllegalArgumentException.class,()->ExternalFrame.parse(null));
    }
    @Test void overlongTextIsCutToServerLimitsWithoutSplittingSurrogates(){
        var m=base();m.put("text","가".repeat(1599)+"😀"+"나");m.put("title","t".repeat(500));m.put("speaker","s".repeat(100));m.put("message","m".repeat(999));
        var f=ExternalFrame.parse(m);
        assertEquals(1599,f.text().length());assertEquals(140,f.title().length());assertEquals(64,f.speaker().length());assertEquals(200,f.message().length());
        assertEquals("ab",ExternalFrame.cut("ab",2));assertEquals("a",ExternalFrame.cut("a😀",2));
    }
    @Test void portraitsOnlyAcceptBundledKeysAndPngPaths(){
        var m=base();m.put("portrait","npc/teacher_smile");m.put("portraitFile",Path.of("config","chaca_dialogue","ch1","..","ch1","teacher.PNG"));
        m.put("preload",List.of("a.png","b.jpg",Path.of("a.png"),"c.png","d.png","e.png","f.png","g.png","h.png"));m.put("playerPortrait",true);m.put("closable",false);m.put("last",true);
        var f=ExternalFrame.parse(m);
        assertEquals("npc/teacher_smile",f.portrait());assertTrue(f.portraitFile().isAbsolute());
        assertEquals(Path.of("config","chaca_dialogue","ch1","teacher.PNG").toAbsolutePath(),f.portraitFile());
        assertEquals(ExternalFrame.MAX_PRELOAD,f.preload().size());assertEquals(Path.of("a.png").toAbsolutePath(),f.preload().get(0));
        assertTrue(f.playerPortrait());assertFalse(f.closable());assertTrue(f.last());
        for(Object bad:new Object[]{"../../etc/passwd","Teacher","with space","x".repeat(121),5}){var b=base();b.put("portrait",bad);assertEquals("",ExternalFrame.parse(b).portrait());}
        for(Object bad:new Object[]{"portrait.jpg","",Path.of("folder"),5}){var b=base();b.put("portraitFile",bad);assertNull(ExternalFrame.parse(b).portraitFile());}
    }
    @Test void unknownKeysAndLooseTypesFromReflectiveCallersAreTolerated(){
        // 다른 모드가 리플렉션으로 넘기는 Map: 모르는 키는 무시, 숫자는 Number 면 되고, 글자가 아닌 값은 빈 글자로
        var m=base();m.put("sequence",7L);m.put("futureKey",List.of(1,2));m.put("title",null);m.put("text",42);m.put("choices",null);m.put("preload","a.png");
        m.put("closable","false");m.put("portraitFile","portraits/teacher_1.png");
        var f=ExternalFrame.parse(m);
        assertEquals(7,f.sequence());assertEquals("",f.title());assertEquals("",f.text());assertTrue(f.choices().isEmpty());assertTrue(f.preload().isEmpty());
        assertTrue(f.closable(),"Boolean 이 아니면 기본값");assertEquals(Path.of("portraits","teacher_1.png").toAbsolutePath(),f.portraitFile());
        var six=base();var list=new ArrayList<List<String>>();for(int i=0;i<ExternalFrame.MAX_CHOICES;i++)list.add(List.of("c"+i,"가".repeat(200)));six.put("choices",list);
        var g=ExternalFrame.parse(six);
        assertEquals(ExternalFrame.MAX_CHOICES,g.choices().size());assertEquals(140,g.choices().get(5).text().length());assertEquals("c5",g.choices().get(5).id());
        assertThrows(UnsupportedOperationException.class,()->g.choices().add(new ExternalFrame.Choice("x","y")));
    }
    @Test void sessionAndSequenceDecideOpenUpdateRepeatOrStale(){
        var f=ExternalFrame.parse(base());
        assertEquals(ExternalFrame.Step.OPEN,ExternalFrame.step(null,-1,f));
        assertEquals(ExternalFrame.Step.OPEN,ExternalFrame.step("story:other",99,f));
        assertEquals(ExternalFrame.Step.UPDATE,ExternalFrame.step("story:ch1_wakeup",2,f));
        assertEquals(ExternalFrame.Step.REPEAT,ExternalFrame.step("story:ch1_wakeup",3,f));
        assertEquals(ExternalFrame.Step.STALE,ExternalFrame.step("story:ch1_wakeup",4,f));
    }
}
