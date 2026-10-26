package kr.chacademy.story.compat;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
/** MagicCodex 대화창 창구(school.magiccodex.client.api.ExternalDialogue)를 리플렉션으로 부르는 부분. 같은 모양의 가짜 창구로 확인한다. */
class MagicCodexDialogueLinkTest {
    /** 진짜 창구와 같은 public static 시그니처 (JDK 타입만). */
    public static final class FakeApi {
        static final List<Map<String,Object>> shown=new ArrayList<>();static final List<String> closed=new ArrayList<>();
        static BiConsumer<String,String> onChoice;static boolean fail;
        public static int apiVersion(){return 1;}
        public static boolean show(Map<String,Object> frame,BiConsumer<String,String> choice,Consumer<String> closedCallback){if(fail)throw new IllegalStateException("boom");shown.add(frame);onChoice=choice;return true;}
        public static void close(String session){closed.add(session);}
        public static boolean isShowing(String session){return !shown.isEmpty()&&session.equals(shown.get(shown.size()-1).get("session"));}
    }
    public static final class OldApi {
        public static int apiVersion(){return 0;}
        public static boolean show(Map<String,Object> frame,BiConsumer<String,String> choice,Consumer<String> closedCallback){return true;}
        public static void close(String session){}
        public static boolean isShowing(String session){return true;}
    }
    /** 버전은 맞지만 show 모양이 다른 창구. */
    public static final class WrongShape {
        public static int apiVersion(){return 1;}
        public static boolean show(String frame){return true;}
        public static void close(String session){}
        public static boolean isShowing(String session){return true;}
    }
    private static void use(Class<?> api){
        MagicCodexClientLink.nickname("x"); // 실제 탐색(look)을 먼저 끝내 둔다. 그 뒤에 가짜로 바꾼다
        MagicCodexClientLink.resolveDialogue(api.getName());
    }
    @Test void callsShowCloseAndIsShowingThroughReflection(){
        use(FakeApi.class);FakeApi.shown.clear();FakeApi.closed.clear();FakeApi.fail=false;
        assertTrue(MagicCodexClientLink.dialogueAvailable());
        List<String> picked=new ArrayList<>();
        Map<String,Object> frame=new HashMap<>();frame.put("session","story:ch1");frame.put("sequence",1);
        assertTrue(MagicCodexClientLink.showDialogue(frame,(s,c)->picked.add(s+"/"+c),s->{}));
        assertSame(frame,FakeApi.shown.get(0));
        FakeApi.onChoice.accept("story:ch1","c1");assertEquals(List.of("story:ch1/c1"),picked);
        assertTrue(MagicCodexClientLink.dialogueShowing("story:ch1"));assertFalse(MagicCodexClientLink.dialogueShowing("story:other"));
        MagicCodexClientLink.closeDialogue("story:ch1");assertEquals(List.of("story:ch1"),FakeApi.closed);
    }
    @Test void failuresInsideMagicCodexNeverEscape(){
        use(FakeApi.class);FakeApi.fail=true;
        try{assertFalse(MagicCodexClientLink.showDialogue(new HashMap<>(),(s,c)->{},s->{}));}finally{FakeApi.fail=false;}
    }
    @Test void missingOldOrDifferentApiIsUnavailableAndCallsAreNoOps(){
        for(String name:new String[]{"no.such.ExternalDialogue",OldApi.class.getName(),WrongShape.class.getName()}){
            MagicCodexClientLink.nickname("x");MagicCodexClientLink.resolveDialogue(name);
            assertFalse(MagicCodexClientLink.dialogueAvailable(),name);
            assertFalse(MagicCodexClientLink.showDialogue(new HashMap<>(),(s,c)->{},s->{}));
            assertFalse(MagicCodexClientLink.dialogueShowing("story:ch1"));
            MagicCodexClientLink.closeDialogue("story:ch1");
        }
    }
}
