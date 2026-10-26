package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 서버 쪽 대화 흐름 검증 (Bukkit 없이): 가능한 이동 / 불가능한 이동, 선택지 하나만, need, 끝, 이어서 보기. */
class StoryGraphTest {
    static Map<String,Object> m(Object... kv){Map<String,Object> o=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}
    static List<Object> lines(int n){List<Object> l=new ArrayList<>();for(int i=0;i<n;i++)l.add(m("who","me","text","t"+i));return l;}
    /** example/dialogue/ch1_wakeup 과 같은 모양 + 이벤트 이름. */
    static Map<String,Object> wakeup(){
        return m("id","ch1_wakeup","start","wake","scenes",m(
            "wake",m("lines",lines(4),"choices",List.of(
                m("text","a","to","fine","affinity",m("npc","teacher","add",2),"event","c_wake_1"),
                m("text","b","to","dream","event","told_dream"),
                m("text","c","to","close","need",m("npc","teacher","min",50),"event","c_wake_3"))),
            "fine",m("lines",lines(1),"next","go"),
            "dream",m("lines",lines(2),"next","go","event","e_dream"),
            "close",m("lines",lines(1),"next","go"),
            "go",m("redirect",List.of(m("npc","teacher","min",60,"to","go_friend")),"lines",lines(1)),
            "go_friend",m("lines",lines(1))));
    }
    static StoryGraph graph(Map<String,Object> root){
        List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
        StoryGraph g=StoryGraph.parse("ch1_wakeup",root,errors,warnings);
        assertTrue(errors.isEmpty(),errors.toString());assertNotNull(g);return g;
    }
    static StoryGraph.Walk fresh(int teacher){return new StoryGraph.Walk(graph(wakeup()),Map.of("teacher",teacher),"",0);}
    static void readTo(StoryGraph.Walk w,String scene,int last){for(int i=0;i<=last;i++)assertTrue(w.progress(scene,i),scene+"#"+i);}

    @Test void normalPlaythroughIsLegal(){
        var w=fresh(25);
        readTo(w,"wake",3);
        assertTrue(w.event("told_dream"));            // 선택지 이벤트가 먼저 오고
        assertTrue(w.event("e_dream"));               // 들어가는 장면의 이벤트
        readTo(w,"dream",1);
        assertTrue(w.progress("go",0));
        assertFalse(w.done("go_friend"));             // 호감도 25 → 친구 결말은 불가능
        assertTrue(w.done("go"));
    }
    @Test void forgedJumpsEventsAndEndingsAreRejected(){
        var w=fresh(25);
        assertFalse(w.done("go_friend"));             // 시작하자마자 결말
        assertFalse(w.progress("go_friend",0));       // 건너뛰기
        assertFalse(w.event("nonexistent"));
        assertTrue(w.progress("wake",0));
        assertFalse(w.progress("go",0));              // wake 에서 바로 go 로는 못 감
        assertFalse(w.progress("wake",9));            // 없는 줄
        assertFalse(w.event("e_dream"));              // 아직 들어가지 않은 장면의 이벤트
        assertFalse(w.done("wake"));                  // 선택지가 있는 장면에서는 끝낼 수 없음
        assertFalse(w.event("c_wake_3"));             // need 50 인데 서버가 아는 호감도는 25
    }
    @Test void onlyOneChoiceEventPerVisit(){
        var w=fresh(80);
        readTo(w,"wake",3);
        assertTrue(w.event("c_wake_1"));
        assertFalse(w.event("told_dream"));           // 같은 방문에서 두 번째 선택지
        assertFalse(w.event("c_wake_3"));
        assertFalse(w.progress("dream",0));           // 고른 선택지와 다른 곳
        assertTrue(w.progress("fine",0));
        assertFalse(w.event("told_dream"));           // 떠난 장면의 다른 선택지
    }
    @Test void needAndRedirectUseServerSideAffinityIncludingLocalChanges(){
        var w=fresh(58);                              // 58 + 선택지 2 = 60 → go 대신 go_friend
        readTo(w,"wake",3);
        assertTrue(w.event("c_wake_1"));
        assertTrue(w.progress("fine",0));
        assertFalse(w.progress("go",0));
        assertTrue(w.progress("go_friend",0));
        assertFalse(w.done("go"));
        assertTrue(w.done("go_friend"));
        var high=fresh(50);
        readTo(high,"wake",3);
        assertTrue(high.event("c_wake_3"));           // need 를 만족하면 가능
        assertTrue(high.progress("close",0));
    }
    @Test void lateChoiceEventAfterProgressIsAcceptedOnce(){
        var w=fresh(25);
        readTo(w,"wake",3);
        assertTrue(w.progress("dream",0));            // 이벤트보다 진행 보고가 먼저 와도
        assertTrue(w.event("told_dream"));
        assertFalse(w.event("c_wake_1"));
        assertTrue(w.event("e_dream"));
    }
    @Test void resumeStartsAtSavedLineWithoutEntryEffects(){
        var w=new StoryGraph.Walk(graph(wakeup()),Map.of("teacher",25),"dream",1);
        assertEquals("dream",w.scene);assertEquals(1,w.line);
        assertFalse(w.event("e_dream"));              // 이어서 볼 때 진입 이벤트는 다시 오지 않는다
        assertTrue(w.progress("dream",1));
        assertTrue(w.progress("go",0));
        assertTrue(w.done("go"));
        var gone=new StoryGraph.Walk(graph(wakeup()),Map.of(),"deleted_scene",3);
        assertEquals("",gone.scene);                  // 없는 장면이면 처음부터
        assertTrue(gone.progress("wake",0));
    }
    @Test void emptyScenesPassThroughAndFinish(){
        var root=m("start","a","scenes",m(
            "a",m("lines",lines(1),"next","b"),
            "b",m("event","e_b","next","c"),                       // 대사도 선택지도 없음 → 바로 c
            "c",m("event","e_c","affinity",List.of(m("npc","rin","add",5)))));  // 여기서 끝
        var w=new StoryGraph.Walk(graph(root),Map.of(),"",0);
        assertTrue(w.progress("a",0));
        assertTrue(w.event("e_b"));assertTrue(w.event("e_c"));
        assertTrue(w.progress("b",0));assertTrue(w.progress("c",0));
        assertFalse(w.done("b"));assertTrue(w.done("c"));assertTrue(w.finished());
        assertFalse(w.progress("a",0));               // 끝난 뒤에는 아무 데도 못 감
    }
    @Test void choiceWithoutTargetEndsDialogueAtThatScene(){
        var root=m("scenes",m("s1",m("lines",lines(1),"choices",List.of(m("text","bye","event","c_bye"),m("text","more","to","s2"))),"s2",m("lines",lines(1))));
        var w=new StoryGraph.Walk(graph(root),Map.of(),"",0);
        assertTrue(w.progress("s1",0));
        assertTrue(w.event("c_bye"));
        assertFalse(w.progress("s2",0));
        assertTrue(w.done("s1"));
        var other=new StoryGraph.Walk(graph(root),Map.of(),"",0);
        assertTrue(other.progress("s1",0));assertTrue(other.progress("s2",0));
        assertFalse(other.event("c_bye"));assertFalse(other.done("s1"));assertTrue(other.done("s2"));
    }
    @Test void loopBackAllowsANewChoiceOnTheNextVisit(){
        var root=m("scenes",m("hub",m("lines",lines(2),"choices",List.of(m("text","a","to","hub","event","c_a"),m("text","b","to","hub","event","c_b"),m("text","end","to","out"))),"out",m("lines",lines(1))));
        var w=new StoryGraph.Walk(graph(root),Map.of(),"",0);
        readTo(w,"hub",1);
        assertTrue(w.event("c_a"));assertFalse(w.event("c_b"));
        assertTrue(w.progress("hub",0));              // 같은 장면으로 다시 들어옴
        assertTrue(w.event("c_b"));
        assertTrue(w.progress("hub",0));
        assertTrue(w.progress("out",0));assertTrue(w.done("out"));
    }
    @Test void loaderRejectsBadKeysAndNonFiniteNumbers(){
        for(Map<String,Object> bad:List.of(
                m("scenes",m("Wake",m("lines",lines(1)))),                                   // 대문자 장면 id
                m("scenes",m("장면",m("lines",lines(1)))),
                m("scenes",m("x".repeat(65),m("lines",lines(1)))),
                m("scenes",m("a",m("event","Bad Event"))),
                m("scenes",m("a",m("choices",List.of(m("event","c,1"))))),
                m("scenes",m("a",m("choices",List.of(m("need",m("npc","t","min","NaN")))))),
                m("scenes",m("a",m("affinity",List.of(m("npc","t","add",Double.POSITIVE_INFINITY))))),
                m("start","missing","scenes",m("a",m("lines",lines(1)))),
                m("scenes",m("a",m("choices",List.of(m(),m(),m(),m(),m(),m(),m())))),        // 선택지 7개
                m("scenes",m()))){
            List<String> errors=new ArrayList<>();
            assertTrue(StoryGraph.parse("x",bad,errors,new ArrayList<>())==null,bad.toString());
            assertFalse(errors.isEmpty());
        }
        List<String> errors=new ArrayList<>();
        StoryGraph.parse("x",m("scenes",m("Wake",m())),errors,new ArrayList<>());
        assertTrue(errors.get(0).contains("Wake"));   // 어느 키가 문제인지 알려 준다
    }
    @Test void serverCommandsAreClampedAndCrossChecked(){
        List<String> warnings=new ArrayList<>();
        var def=StoryDef.parse("ch1_wakeup",m("npcs",List.of("teacher","bad npc"),"end",List.of("say hi"),
                "events",m("told_dream",List.of("give {player} bread 1"),"ghost",List.of("say x"),"Bad.Name",List.of("say y")),
                "endings",m("go_friend",List.of("say f")),
                "affinity",m("end",m("teacher",99),"events",m("c_wake_1",m("teacher",50,"rin",-3)),"endings",m("go_friend",m("teacher",5)))),
                graph(wakeup()),warnings);
        assertEquals(List.of("teacher"),def.npcs);
        assertEquals(50,def.affinityEnd.get("teacher").intValue());                 // 끝·결말 ±50
        assertEquals(20,def.affinityEvents.get("c_wake_1").get("teacher").intValue()); // 이벤트 ±20
        assertEquals(-3,def.affinityEvents.get("c_wake_1").get("rin").intValue());
        assertTrue(def.declares("told_dream"));assertTrue(def.declares("c_wake_1"));assertFalse(def.declares("Bad.Name"));
        assertTrue(warnings.stream().anyMatch(w->w.contains("ghost")));  // 흐름에 없는 이벤트 경고
        assertTrue(warnings.stream().anyMatch(w->w.contains("Bad.Name")));
        assertNull(def.allowReplay);
        assertEquals(Boolean.TRUE,StoryDef.parse("x",m("allow-replay",true),null,new ArrayList<>()).allowReplay);
    }
    @Test void eventlessSelfLoopChoiceIsFollowedSoLaterNeedsMatchTheClient(){
        // hub (대사 1줄): "더 묻기" 는 이벤트 없이 자기 장면으로 돌아오며 호감도 +10 (클라 계산용), 30 이상이면 leave 가 보인다
        var g=graph(m("id","ch1_wakeup","scenes",m(
            "hub",m("lines",lines(1),"choices",List.of(
                m("text","more","to","hub","affinity",List.of(m("npc","t","add",10))),
                m("text","leave","to","out","need",m("npc","t","min",30),"event","left"))),
            "out",m("lines",lines(1)))));
        var w=new StoryGraph.Walk(g,Map.of("t",25),"",0);
        assertTrue(w.progress("hub",0));
        assertFalse(w.event("left"));                 // 25 < 30
        assertTrue(w.progress("hub",0));              // "더 묻기" 로 다시 들어옴 → 서버 계산도 35
        assertTrue(w.event("left"));
        assertTrue(w.progress("out",0));
        assertTrue(w.done("out"));
    }
    @Test void passThroughSceneReportIsAcceptedOnlyOncePerMove(){
        // a --(이벤트 없는 선택지)--> mid (대사 없음, 처음 들어올 때 +10) --> a.  두 번째로 같은 길을 가도 새 이동으로 계산한다
        var g=graph(m("id","ch1_wakeup","scenes",m(
            "a",m("lines",lines(1),"choices",List.of(
                m("text","loop","to","mid"),
                m("text","self","to","a","event","again"),
                m("text","end","to","fin","need",m("npc","t","min",30),"event","fin_ev"))),
            "mid",m("next","a","affinity",List.of(m("npc","t","add",10))),
            "fin",m("lines",lines(1)))));
        var w=new StoryGraph.Walk(g,Map.of("t",25),"",0);
        assertTrue(w.progress("a",0));
        assertTrue(w.progress("mid",0));assertTrue(w.progress("a",0));    // 첫 바퀴: mid 의 호감도 +10 → 35
        assertTrue(w.progress("mid",0));assertTrue(w.progress("a",0));    // 둘째 바퀴 (늦은 보고로 뭉개지 않는다)
        assertTrue(w.event("again"));assertTrue(w.progress("a",0));       // 이름 있는 선택지로 자기 자신에게
        assertTrue(w.event("fin_ev"));                                     // 35 ≥ 30
        assertTrue(w.progress("fin",0));assertTrue(w.done("fin"));
        assertFalse(w.progress("mid",0));                                  // 끝난 뒤의 보고는 인정하지 않는다
    }
}
