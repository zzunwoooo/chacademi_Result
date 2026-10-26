package kr.chacademy.cutscene;
import org.junit.jupiter.api.Test;
import kr.chacademy.cutscene.data.Cutscene;
import kr.chacademy.cutscene.data.CutsceneLoader;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** cutscene.yml 검사 (파일 없이): NaN·무한대 거절, 예전 image: 키도 경로 검사, 범위 자르기. */
class CutsceneLoaderTest {
    static Map<String,Object> m(Object... kv){Map<String,Object> o=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}
    static Map<String,Object> scene(Object... kv){Map<String,Object> s=m("images",List.of("a.png"),"duration",5.0);s.putAll(m(kv));return s;}
    @Test void validCutsceneIsReadAndClamped() throws Exception {
        Cutscene c=CutsceneLoader.parse("ch1_x",m("title","T","end_fade",1.5,"type_speed",99,"scenes",List.of(
                scene("zoom",m("x",0.3,"y",2,"from",1,"to",9),"transition","fade","lines",List.of(m("text","hi","start",1,"end",2))),
                scene("images",List.of("b.png","c.png"),"fps","12","duration","7.5"))));
        assertEquals(2,c.scenes().size());assertEquals(14.0,c.totalDuration(),1e-9);
        assertEquals(1.0,c.typeSpeed(),1e-9);                       // 범위 밖은 자름
        assertEquals(1.0,c.scenes().get(0).zoomY(),1e-9);assertEquals(4.0,c.scenes().get(0).zoomTo(),1e-9);
        assertEquals(12.0,c.scenes().get(1).fps(),1e-9);assertEquals(7.5,c.scenes().get(1).duration(),1e-9);
        assertEquals(Cutscene.AUTO,c.textY(),1e-9);assertEquals("",c.bgm());
    }
    @Test void nonFiniteNumbersAreRejectedEverywhere() {
        // yml 의 .nan / .inf 는 Double 로, 따옴표 친 "NaN" 은 글자로 들어온다: 둘 다 막는다
        for (Object bad : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "NaN", "Infinity", "-Infinity"}) {
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of(scene("duration",bad)))),"duration "+bad);
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("end_fade",bad,"scenes",List.of(scene()))),"end_fade "+bad);
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("text_y",bad,"scenes",List.of(scene()))),"text_y "+bad);
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of(scene("zoom",m("to",bad))))),"zoom.to "+bad);
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of(scene("lines",List.of(m("text","a","start",bad)))))),"line.start "+bad);
        }
    }
    @Test void everyDurationIsFiniteSoTheCutsceneAlwaysEnds() throws Exception {
        Cutscene c=CutsceneLoader.parse("x",m("scenes",List.of(scene("duration","abc"),scene("duration",1e308),scene("duration",-5))));
        assertTrue(Double.isFinite(c.totalDuration()));
        assertEquals(5+600+0.2+1.2,c.totalDuration(),1e-9);
    }
    @Test void imageNamesCannotEscapeTheFolderIncludingLegacyImageKey() {
        for (String bad : List.of("../x.png","a/b.png","a\\b.png","..png","x.jpg","")) {
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of(m("images",List.of(bad))))),"images "+bad);
            assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of(m("image",bad)))),"image "+bad);
        }
        assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("bgm","../a.ogg","scenes",List.of(scene()))));
        assertThrows(IOException.class,()->CutsceneLoader.parse("Bad Id",m("scenes",List.of(scene()))));
        assertThrows(IOException.class,()->CutsceneLoader.parse("x",m("scenes",List.of())));
        assertThrows(IOException.class,()->CutsceneLoader.parse("x","not a map"));
    }
    @Test void legacySingleImageStillWorks() throws Exception {
        assertEquals(List.of("old.png"),CutsceneLoader.parse("x",m("scenes",List.of(m("image","old.png")))).scenes().get(0).images());
    }
}
