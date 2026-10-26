package dev.portablevfx.client.definition;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextureBudgetTest {
    static byte[] png(int w,int h) { return ByteBuffer.allocate(24).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452).putInt(w).putInt(h).array(); }
    @Test void readsPng() { assertEquals(65536,TextureBudget.texels("x.png",png(256,256))); }
    @Test void rejectsBombDimensions() { for(int v:new int[]{0,-1,4097,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->TextureBudget.texels("x.png",png(v,1))); }
    @Test void rejectsShortHeaders() { for(String s:new String[]{"x.png","x.dds","x.tga"}) assertThrows(IllegalArgumentException.class,()->TextureBudget.texels(s,new byte[1])); }
    @Test void ignoresModelBytes() { assertEquals(0,TextureBudget.texels("x.efkmodel",new byte[1])); }
}
