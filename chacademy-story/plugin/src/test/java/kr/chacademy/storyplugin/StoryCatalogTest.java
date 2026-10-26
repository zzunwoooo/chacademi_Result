package kr.chacademy.storyplugin;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoryCatalogTest {
    private byte[] packet(String c,String d){var out=new ByteArrayOutputStream();StoryCodec.writeString(out,c);StoryCodec.writeString(out,d);return out.toByteArray();}
    @Test void onlyBoundedIdsAreAcceptedAndEmptyCatalogClearsSuggestions(){
        assertEquals(new StoryCatalog(List.of("intro"),List.of("ch1-1","ch1-2")),StoryCodec.catalog(packet("intro","ch1-2\nch1-1")));
        assertTrue(StoryCodec.catalog(packet("","")).dialogues().isEmpty());
        for(String bad:List.of("../path","op player","ch1\nch1","한글","x".repeat(65),"ch1\n"))assertThrows(IllegalArgumentException.class,()->StoryCodec.catalog(packet("",bad)));
        var ids=java.util.stream.IntStream.range(0,129).mapToObj(i->"id"+i).toList();assertThrows(IllegalArgumentException.class,()->StoryCodec.catalog(packet("",String.join("\n",ids))));
    }
    @Test void rejectsTruncatedOversizeAndTrailingData(){
        byte[] good=packet("intro","ch1");assertThrows(IllegalArgumentException.class,()->StoryCodec.catalog(Arrays.copyOf(good,good.length-1)));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.catalog(Arrays.copyOf(good,good.length+1)));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.catalog(new byte[StoryCatalog.MAX_PACKET+1]));
    }
    @Test void maximumCatalogAndReloadDiscriminatorRemainCompatible(){
        var ids=java.util.stream.IntStream.range(0,128).mapToObj(i->"id"+i+"x".repeat(58)).toList();
        var both=StoryCodec.catalog(packet(String.join("\n",ids),String.join("\n",ids)));assertEquals(128,both.cutscenes().size());assertEquals(128,both.dialogues().size());
        assertArrayEquals(new byte[]{0},StoryCodec.reloadContent(StoryCodec.KIND_CUTSCENE));assertArrayEquals(new byte[]{1},StoryCodec.reloadContent(StoryCodec.KIND_DIALOGUE));
    }
}
