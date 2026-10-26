package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import school.magiccodex.protocol.PetProtocol;
import school.magiccodex.protocol.PetProtocol.*;

class PetTest {
    @TempDir Path root;
    private static Entry pet(String id,int grade){return new Entry(id,id,grade,true,false,new byte[0]);}
    private static void apply(PetCatalogState s,long seq,List<Entry> values){s.expect(seq);for(var p:PetProtocol.split(seq,"",values))s.accept(p);}
    @Test void fullCatalogSurvivesFragmentationAndOutOfOrderDelivery(){
        var list=new ArrayList<Entry>();
        for(int i=0;i<512;i++)list.add(new Entry("pet_"+i,"한글 펫 "+i,1+i%3,i%2==0,i==1,new byte[8192]));
        var parts=new ArrayList<>(PetProtocol.split(9,"전송 완료",list));Collections.reverse(parts);
        var state=new PetCatalogState();state.expect(9);
        for(int i=0;i<parts.size();i++){
            byte[] wire=PetProtocol.encode(parts.get(i));assertTrue(wire.length<=24000);
            assertEquals(i==parts.size()-1,state.accept(PetProtocol.response(wire)));
        }
        assertEquals(256,state.visible().size());assertEquals(8192,state.visible().getFirst().icon().length);
    }
    @Test void favoritesLeadWithoutChangingSelectionAndFiltersWrap(){
        var s=new PetCatalogState();apply(s,1,List.of(pet("a",1),pet("b",2),pet("c",2)));
        s.step(1);assertEquals("b",s.selected().id());s.toggle("c");
        assertEquals("c",s.visible().getFirst().id());assertEquals("b",s.selected().id());
        assertSame(s.visible(),s.visible());s.filter(2);s.step(1);assertEquals("c",s.selected().id());
        s.filter(3);assertNull(s.selected());s.filter(0);assertEquals("c",s.selected().id());
        s.clearCatalog();assertTrue(s.favorite("c"));assertTrue(s.visible().isEmpty());
    }
    @Test void staleAndInvalidResponsesNeverReplaceCatalog(){
        var s=new PetCatalogState();apply(s,1,List.of(pet("original",1)));s.expect(3);
        assertFalse(s.accept(new Response(2,0,1,"",List.of(pet("old",2)))));
        assertEquals("original",s.selected().id());
        assertThrows(IllegalArgumentException.class,()->s.accept(new Response(3,0,1,"",List.of(pet("dup",1),pet("dup",2)))));
        assertEquals("original",s.selected().id());
    }
    @Test void protocolRejectsUnsafeIdsOversizedTruncatedAndTrailingPackets(){
        for(String id:new String[]{"../x","x/y","x\\y","a..b","/op test",""})assertFalse(PetProtocol.validId(id));
        byte[] good=PetProtocol.encode(new Request(1,PetProtocol.SUMMON,"fox"));
        assertEquals("fox",PetProtocol.request(good).id());
        assertThrows(IllegalArgumentException.class,()->PetProtocol.request(Arrays.copyOf(good,good.length+1)));
        assertThrows(IllegalArgumentException.class,()->PetProtocol.request(Arrays.copyOf(good,good.length-1)));
        assertThrows(IllegalArgumentException.class,()->PetProtocol.response(new byte[24001]));
        assertThrows(IllegalArgumentException.class,()->PetProtocol.encode(new Request(1,4,"fox")));
        assertThrows(IllegalArgumentException.class,()->new Entry("fox","fox",4,true,false,new byte[0]));
    }
    @Test void searchIsOwnedOnlyAndCombinesNameIdStarsAndSelection(){
        var s=new PetCatalogState();apply(s,1,List.of(
            new Entry("fox_red","\uBD89\uC740 \uC5EC\uC6B0",3,true,false,new byte[0]),
            new Entry("fox_blue","Blue Fox",2,true,false,new byte[0]),
            new Entry("fox_secret","Secret Fox",3,false,false,new byte[0])));
        s.search(" FOX ");assertEquals(2,s.visible().size());
        s.search("\uC5EC\uC6B0");assertEquals("fox_red",s.selected().id());
        s.search("\uFF46\uFF4F\uFF58");s.filter(2);assertEquals("fox_blue",s.selected().id());
        s.search("secret");assertTrue(s.visible().isEmpty());assertNull(s.selected());
        s.search("");s.filter(0);assertEquals(2,s.visible().size());
        s.reset();assertEquals("",s.query());assertTrue(s.visible().isEmpty());
    }
    private JsonObject fixture(String version){
        var o=JsonParser.parseString("""
            {"meta":{"format_version":"4.10"},"resolution":{"width":16,"height":16},
             "textures":[{"source":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII="}],
             "elements":[{"uuid":"cube","from":[-2,0,-2],"to":[2,8,2],"faces":{"north":{"texture":0,"uv":[0,0,16,16]}}}],
             "outliner":[{"uuid":"bone","origin":[0,0,0],"children":["cube"]}],
             "animations":[{"name":"idle","length":2,"animators":{"bone":{"keyframes":[
               {"channel":"position","time":0,"data_points":[{"x":2,"y":0,"z":0}]},
               {"channel":"position","time":2,"data_points":[{"x":4,"y":0,"z":0}]}]}}}]}
            """).getAsJsonObject();o.getAsJsonObject("meta").addProperty("format_version",version);return o;
    }
    @Test void blockbenchLegacyAndV5AnimationDirectionsAndSeparateGroups()throws Exception{
        var old=PetBbModel.parse(fixture("4.10"),root.resolve("pet.bbmodel"),root);
        assertEquals(-3,PetBbModel.sample(old.tracks.getFirst().keys(),1)[0]);assertEquals(8,old.span);
        var json=fixture("5.0");json.add("groups",JsonParser.parseString("[{\"uuid\":\"bone\",\"origin\":[0,0,0],\"rotation\":[0,0,90]}]"));
        var next=PetBbModel.parse(json,root.resolve("pet.bbmodel"),root);
        assertEquals(3,PetBbModel.sample(next.tracks.getFirst().keys(),1)[0]);
        assertEquals(-4,next.center[0],.001);assertEquals(8,next.span,.001);
    }
    @Test void missingFilesAndOversizedTexturesFailBoundedly()throws Exception{
        assertThrows(NoSuchFileException.class,()->PetBbModel.load(root,"absent"));
        assertThrows(IllegalArgumentException.class,()->PetBbModel.load(root,"../outside"));
        var json=fixture("5.0");var tex=json.getAsJsonArray("textures").get(0).getAsJsonObject();
        byte[] bytes=Base64.getDecoder().decode(tex.get("source").getAsString().split(",")[1]);
        java.nio.ByteBuffer.wrap(bytes).putInt(16,9999);tex.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes));
        assertThrows(IllegalArgumentException.class,()->PetBbModel.parse(json,root.resolve("pet.bbmodel"),root));
    }
    @Test void molangIsIgnoredAndDuplicateHierarchyRejected()throws Exception{
        var json=fixture("5.0");var points=json.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonObject("animators").getAsJsonObject("bone").getAsJsonArray("keyframes").get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject();
        points.addProperty("x","query.life_time");var model=PetBbModel.parse(json,root.resolve("pet.bbmodel"),root);assertFalse(model.warnings.isEmpty());
        json.getAsJsonArray("outliner").add("cube");assertThrows(IllegalArgumentException.class,()->PetBbModel.parse(json,root.resolve("pet.bbmodel"),root));
    }
    @Test void summaryAndInfoSurviveTheWire(){
        var parts=PetProtocol.split(9,"","도감 체력 +8 · 점수 51 / 612",List.of(new Entry("ca_fox","여우",2,true,false,new byte[0],"2성\n장착 중: 이동속도 +10%")));
        var back=PetProtocol.response(PetProtocol.encode(parts.getFirst()));
        assertEquals("도감 체력 +8 · 점수 51 / 612",back.summary());
        assertEquals("2성\n장착 중: 이동속도 +10%",back.entries().getFirst().info());
    }
}
