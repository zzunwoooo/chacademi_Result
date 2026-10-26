package school.magiccodex.client;
import org.junit.jupiter.api.Test;import javax.imageio.ImageIO;import static org.junit.jupiter.api.Assertions.*;
class UnassignedNameplateTest {
 @Test void dedicatedApprovedTextureIsTransparentAndKeepsStudentAspect()throws Exception{try(var in=getClass().getResourceAsStream("/assets/magiccodex/textures/nameplates/nameplate_unassigned.png")){assertNotNull(in);var im=ImageIO.read(in);assertEquals(2048,im.getWidth());assertEquals(768,im.getHeight());assertTrue(im.getColorModel().hasAlpha());assertEquals(0,im.getRGB(0,0)>>>24);assertTrue((im.getRGB(1200,350)>>>24)>200);}}
 @Test void onlyProfessorUsesTwoPixelNarrowerAtlas(){for(int i=0;i<6;i++)assertEquals(i==4?2046:2048,NameplatePolicy.textureWidth(i));assertFalse(NameplatePolicy.selfPreview(true,true,false,true,false,false));assertTrue(NameplatePolicy.selfPreview(false,true,false,true,false,false));assertFalse(NameplatePolicy.selfPreview(false,false,false,true,false,false));}
}