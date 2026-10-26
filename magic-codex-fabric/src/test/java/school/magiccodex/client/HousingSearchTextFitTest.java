package school.magiccodex.client;
import java.awt.Font;import java.nio.file.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class HousingSearchTextFitTest{
 @Test void exactProductionFontFitsNarrowUserSearchButtonAndLongHangulInput()throws Exception{var font=Font.createFont(Font.TRUETYPE_FONT,Path.of("src/main/resources/assets/magiccodex/font/pretendard_regular.ttf").toFile());float original=CodexTextRasterizer.width(font,"유저 검색",19),icon=50*.44f+8;float size=HousingSearchTextFit.buttonSize(19,94,icon,original);assertTrue(size<=19);assertTrue(CodexTextRasterizer.width(font,"유저 검색",size)+icon+12<=94.01);assertTrue(CodexTextRasterizer.width(font,"가나다라마바사아자차카타파하가나",23)+24<=488);}
 @Test void fittingNeverEnlargesOrProducesNegativeSizes(){assertEquals(19,HousingSearchTextFit.buttonSize(19,540,0,100));assertTrue(HousingSearchTextFit.buttonSize(19,94,30,400)>0);}
}
