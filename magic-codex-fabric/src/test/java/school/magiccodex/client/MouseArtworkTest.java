package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import javax.imageio.ImageIO;
class MouseArtworkTest {
 @Test void approvedPngAndThumbHitShareCoordinates()throws Exception{var image=ImageIO.read(getClass().getResourceAsStream("/assets/magiccodex/textures/gui/followup/mouse.png"));assertEquals(280,image.getWidth());assertEquals(497,image.getHeight());assertEquals(KeySettingsLayout.THUMB_1,hit(28,221));assertEquals(KeySettingsLayout.THUMB_2,hit(34,303));assertEquals(-1,hit(180,120));assertEquals(-1,hit(20,20));assertTrue((image.getRGB(28,221)>>>24)>32);assertTrue((image.getRGB(34,303)>>>24)>32);}
 private int hit(double x,double y){return MouseBindingLayout.hit(MouseBindingLayout.X+x*MouseBindingLayout.S,MouseBindingLayout.Y+y*MouseBindingLayout.S);}
 @Test void keyboardAndSlotsRemainDisjointFromMouse(){for(var key:KeySettingsLayout.KEYS)assertTrue(key.box().x()+key.box().width()<MouseBindingLayout.X);for(int i=0;i<6;i++)assertTrue(KeySettingsLayout.slot(i).y()>MouseBindingLayout.Y+MouseBindingLayout.H);}
}
