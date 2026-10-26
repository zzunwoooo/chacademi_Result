package school.magiccodex.client;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatIconFontTest {
    @Test void channelGlyphsShareRaisedBaselineWithoutResizingTheirInk() throws Exception {
        try(var in=getClass().getResourceAsStream("/assets/magiccodex/font/chat.json")) {
            assertNotNull(in);
            var providers=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("providers");
            assertEquals(6,providers.size());
            double reference=Double.NaN;
            for(var value:providers){
                var row=value.getAsJsonObject();int height=row.get("height").getAsInt(),ascent=row.get("ascent").getAsInt();
                assertEquals(12,height,"Keep glyph size and row spacing unchanged");
                assertTrue(ascent<=height,"Minecraft bitmap provider requires ascent <= height");
                assertEquals(3,ascent-9,"Raise the previous baseline by three font units");
                String texture=row.get("file").getAsString().replace("magiccodex:","/assets/magiccodex/textures/");
                try(var png=getClass().getResourceAsStream(texture)){
                    assertNotNull(png,texture);var image=ImageIO.read(png);
                    int top=image.getHeight(),bottom=-1;
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)>0){top=Math.min(top,y);bottom=Math.max(bottom,y);}
                    assertTrue(bottom>=top,texture);
                    double center=(top+bottom+1)*height/(2.0*image.getHeight())-ascent;
                    if(Double.isNaN(reference))reference=center;
                    assertEquals(reference,center,.15,"PNG padding must not misalign channel silhouettes: "+texture);
                }
            }
        }
    }
}
