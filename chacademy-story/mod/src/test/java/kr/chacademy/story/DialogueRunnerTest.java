package kr.chacademy.story;
import org.junit.jupiter.api.Test;
import kr.chacademy.story.dialogue.Dialogue;
import kr.chacademy.story.dialogue.DialogueRunner;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 진행기는 화면 없이 "입력 → 프레임 + 서버에 알릴 것"만 만든다. MagicCodex 대화창이 그 프레임을 그린다. */
class DialogueRunnerTest {
    private final List<String> calls=new ArrayList<>();
    private final DialogueRunner.Listener listener=new DialogueRunner.Listener(){
        public void event(String id,String event,String npc,int add){calls.add("event:"+event+":"+npc+":"+add);}
        public void done(String id,String scene){calls.add("done:"+scene);}
        public void progress(String id,String scene,int line){calls.add(scene+":"+line);}
    };
    private static final Path FOLDER=Path.of("config","chaca_dialogue","ch1");
    private static Dialogue.Speaker speaker(String id,String name,String... faceAndFile){
        var portraits=new LinkedHashMap<String,String>();for(int i=0;i<faceAndFile.length;i+=2)portraits.put(faceAndFile[i],faceAndFile[i+1]);
        return new Dialogue.Speaker(id,name,"",portraits);
    }
    private static Dialogue.Scene scene(String id,List<Dialogue.Line> lines,List<Dialogue.Choice> choices,String next){
        return new Dialogue.Scene(id,List.of(),lines,choices,next,"",List.of());
    }
    private static Dialogue dialogue(Dialogue.Scene... scenes){
        var speakers=new LinkedHashMap<String,Dialogue.Speaker>();
        speakers.put("me",speaker("me","{player}"));
        speakers.put("teacher",speaker("teacher","마중 선생님","기본","t1.png","웃음","t2.png"));
        speakers.put("rin",speaker("rin","린","기본","r1.png"));
        var map=new LinkedHashMap<String,Dialogue.Scene>();for(var s:scenes)map.put(s.id(),s);
        return new Dialogue("ch1","1장 · {player}",scenes[0].id(),speakers,map);
    }
    private DialogueRunner run(Dialogue d,Map<String,Integer> affinity){return new DialogueRunner(d,FOLDER,affinity,Map.of("{player}","별빛"),listener);}
    @SuppressWarnings("unchecked") private static List<List<String>> choices(Map<String,Object> frame){return (List<List<String>>)frame.get("choices");}

    @Test void eachLineIsOneFrameWithIncreasingSequenceAndStoryOwnsNoRendering(){
        var d=dialogue(scene("wake",List.of(new Dialogue.Line("me","","으으… {player}?"),new Dialogue.Line("teacher","웃음","어서 오시오."),new Dialogue.Line("teacher","","앉으시오.")),List.of(),""));
        var r=run(d,Map.of());
        var f1=r.frame();
        assertTrue(((String)f1.get("session")).startsWith("story:ch1:"));assertEquals("1장 · 별빛",f1.get("title"));assertEquals("별빛",f1.get("speaker"));assertEquals("으으… 별빛?",f1.get("text"));
        assertEquals(true,f1.get("playerPortrait"));assertNull(f1.get("portraitFile")); // 나 = 내 일러스트, 그림 파일 없음
        assertEquals(false,f1.get("closable"));assertEquals(false,f1.get("last"));assertTrue(choices(f1).isEmpty());
        assertEquals(List.of(FOLDER.resolve("t2.png")),f1.get("preload"));
        assertEquals(f1,r.frame(),"진행이 없으면 같은 프레임 (가려졌다 다시 띄울 때)");
        r.choose("");
        var f2=r.frame();
        assertTrue((int)f2.get("sequence")>(int)f1.get("sequence"));assertEquals("마중 선생님",f2.get("speaker"));assertEquals(false,f2.get("playerPortrait"));
        assertEquals(FOLDER.resolve("t2.png"),f2.get("portraitFile"));
        r.choose("");
        var f3=r.frame();
        assertEquals(FOLDER.resolve("t2.png"),f3.get("portraitFile"),"표정을 비우면 앞 표정 유지");assertEquals(true,f3.get("last"));
        assertFalse(r.isEnded());
        r.choose("");
        assertTrue(r.isEnded());
        assertEquals(List.of("wake:0","wake:1","wake:2","done:wake"),calls);
        r.choose("");assertEquals(4,calls.size(),"끝난 뒤 입력은 무시");
    }
    @Test void choicesAppearOnLastLineFilteredByNeedAndMapBackById(){
        var hidden=new Dialogue.Choice("비밀 이야기","secret",new Dialogue.Need("teacher",50,null),List.of(),"");
        var a=new Dialogue.Choice("괜찮아요, {player}예요","fine",null,List.of(new Dialogue.AffinityChange("teacher",2)),"c_wake_1");
        var b=new Dialogue.Choice("꿈을 꿨어요","",null,List.of(new Dialogue.AffinityChange("rin",-1)),"");
        var d=dialogue(scene("wake",List.of(new Dialogue.Line("teacher","","하나"),new Dialogue.Line("teacher","","둘")),List.of(hidden,a,b),"ignored"),
                scene("fine",List.of(new Dialogue.Line("rin","","다행이다")),List.of(),""),scene("secret",List.of(),List.of(),""));
        var r=run(d,Map.of("teacher",30));
        assertTrue(choices(r.frame()).isEmpty(),"마지막 대사 전에는 선택지 없음");
        r.choose("c0");assertEquals("하나",r.frame().get("text"),"선택지가 없는 프레임에서 선택 id 는 무시");
        r.choose("");
        var f=r.frame();
        assertEquals(List.of(List.of("c0","괜찮아요, 별빛예요"),List.of("c1","꿈을 꿨어요")),choices(f));assertEquals(false,f.get("last"));
        assertEquals(List.of(FOLDER.resolve("r1.png")),f.get("preload"),"선택지로 이어질 장면의 첫 그림");
        r.choose("");assertEquals("둘",r.frame().get("text"),"선택지가 있으면 그냥 넘길 수 없음");
        r.choose("c7");r.choose("cx");r.choose("zz");assertEquals(f,r.frame());
        r.choose("c0");
        assertEquals("다행이다",r.frame().get("text"));assertEquals(FOLDER.resolve("r1.png"),r.frame().get("portraitFile"));
        assertEquals(List.of("wake:0","wake:1","event:c_wake_1:teacher:2","fine:0"),calls);
        r.choose("");assertTrue(r.isEnded());assertEquals("done:fine",calls.get(calls.size()-1));
    }
    @Test void choiceWithoutTargetEndsInTheSameSceneAndLegacyAffinityOnlyEventIsSent(){
        var b=new Dialogue.Choice("끝","",null,List.of(new Dialogue.AffinityChange("rin",-1)),"");
        var d=dialogue(scene("wake",List.of(new Dialogue.Line("rin","","안녕")),List.of(b),""));
        var r=run(d,Map.of());
        assertEquals(List.of(List.of("c0","끝")),choices(r.frame()));
        r.choose("c0");
        assertTrue(r.isEnded());assertEquals(List.of("wake:0","event::rin:-1","done:wake"),calls);
    }
    @Test void needUsesAffinityChangedEarlierInTheSameDialogueAndRedirectsOnEnter(){
        var up=new Dialogue.Choice("칭찬","b",null,List.of(new Dialogue.AffinityChange("teacher",20)),"");
        var friend=new Dialogue.Choice("친구","",new Dialogue.Need("teacher",40,null),List.of(),"");
        var stranger=new Dialogue.Choice("남","",new Dialogue.Need("teacher",-1000,39),List.of(),"");
        var sceneB=new Dialogue.Scene("b",List.of(new Dialogue.Redirect(new Dialogue.Need("teacher",90,null),"never")),List.of(new Dialogue.Line("teacher","","고맙소")),List.of(friend,stranger),"","e_b",List.of(new Dialogue.AffinityChange("teacher",1)));
        var d=dialogue(scene("a",List.of(new Dialogue.Line("teacher","","자")),List.of(up),""),sceneB,scene("never",List.of(new Dialogue.Line("rin","","x")),List.of(),""));
        var r=run(d,Map.of("teacher",25));
        r.choose("c0");
        assertEquals(List.of(List.of("c0","친구")),choices(r.frame()),"25+20+1=46 → 40 이상 조건만 보임");
        assertEquals(List.of("a:0","event::teacher:20","event:e_b::0","b:0"),calls);
        var high=new DialogueRunner(d,FOLDER,Map.of("teacher",95),Map.of(),listener,"",0);
        assertEquals("자",high.frame().get("text"));
        calls.clear();
        var redirected=new DialogueRunner(dialogue(sceneB,scene("never",List.of(new Dialogue.Line("rin","","x")),List.of(),"")),FOLDER,Map.of("teacher",95),Map.of(),listener);
        assertEquals("x",redirected.frame().get("text"));assertEquals(List.of("never:0"),calls,"redirect 된 장면의 이벤트·호감도는 실행하지 않음");
    }
    @Test void emptyScenesChainOrFinishImmediatelyAndStopNeverReportsDone(){
        var d=dialogue(scene("a",List.of(),List.of(),"b"),scene("b",List.of(),List.of(),""));
        var r=run(d,Map.of());
        assertTrue(r.isEnded(),"대사도 선택지도 없으면 열 것이 없다");assertEquals(List.of("a:0","b:0","done:b"),calls);
        calls.clear();
        var only=dialogue(scene("a",List.of(),List.of(new Dialogue.Choice("가자","",null,List.of(),"")),""));
        var c=run(only,Map.of());
        assertFalse(c.isEnded());assertEquals("",c.frame().get("text"));assertEquals("",c.frame().get("speaker"));assertEquals(1,choices(c.frame()).size());
        c.stop();assertTrue(c.isEnded());c.choose("c0");assertEquals(List.of("a:0"),calls,"멈춘 대화는 done 을 보내지 않는다");
    }
    @Test void resumeClampsLineAndUnknownSceneStartsFromTheBeginning(){
        var d=dialogue(scene("wake",List.of(new Dialogue.Line("teacher","","하나"),new Dialogue.Line("teacher","","둘")),List.of(),""));
        assertEquals("둘",new DialogueRunner(d,null,Map.of(),Map.of(),listener,"wake",99).frame().get("text"));
        assertEquals("하나",new DialogueRunner(d,null,Map.of(),Map.of(),listener,"wake",-3).frame().get("text"));
        assertEquals("하나",new DialogueRunner(d,null,Map.of(),Map.of(),listener,"gone",1).frame().get("text"));
        assertNull(new DialogueRunner(d,null,Map.of(),Map.of(),listener,"wake",1).frame().get("portraitFile")); // 폴더가 없으면 그림 없이
    }
    @Test void framesUseOnlyJdkTypesSoTheyCrossTheReflectionBoundary(){
        var d=dialogue(scene("wake",List.of(new Dialogue.Line("teacher","","하나")),List.of(new Dialogue.Choice("a","",null,List.of(),""),new Dialogue.Choice("b","",null,List.of(),"")),""));
        for(var e:run(d,Map.of()).frame().entrySet()){
            Object v=e.getValue();
            assertTrue(v instanceof String||v instanceof Integer||v instanceof Boolean||v instanceof Path||v instanceof List,e.getKey()+" = "+v.getClass());
        }
    }
}
