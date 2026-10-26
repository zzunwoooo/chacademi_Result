package school.magiccodex.client;
import java.awt.Font;import java.nio.file.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class NameplateLayoutTest {
 @Test void realCodexFontFitsFourHangulAndShrinksMeasuredWideNames()throws Exception{
  Font font;try(var in=Files.newInputStream(Path.of("src/main/resources/assets/magiccodex/font/pretendard_bold.ttf"))){font=Font.createFont(Font.TRUETYPE_FONT,in);}
  float four=CodexTextRasterizer.width(font,"차카데미",250);assertTrue(four<=1100);
  for(String value:new String[]{"차카데미","WWWWWWWWWWWW","iiiiiiiiiiii","😀엘레나교수님😀","아주아주아주긴마법학교교수이름"}){
   float size=250*Math.min(1,1100/CodexTextRasterizer.width(font,value,250));var img=CodexTextRasterizer.rasterize(font,value,size);assertTrue(img.getWidth()<=1110,value+" "+img.getWidth());
  }
  assertTrue(CodexTextRasterizer.width(font,"WWWWWWWW",250)>CodexTextRasterizer.width(font,"iiiiiiii",250));
 }
}
