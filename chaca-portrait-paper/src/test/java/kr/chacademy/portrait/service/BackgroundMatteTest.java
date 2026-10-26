package kr.chacademy.portrait.service;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import kr.chacademy.portrait.core.*;
import org.bukkit.configuration.file.YamlConfiguration;
class BackgroundMatteTest {
    private BufferedImage fixture(){
        var im=new BufferedImage(320,480,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();
        g.setColor(new Color(238,238,238));g.fillRect(0,0,320,480);
        g.setColor(new Color(35,30,60));g.fillOval(95,30,130,160);
        g.setColor(new Color(232,180,145));g.fillOval(110,65,100,110);
        g.setColor(new Color(40,60,130));g.fillRoundRect(65,180,190,310,55,55);
        // Shirt patch is exactly the background color, but is enclosed inside the subject.
        g.setColor(new Color(238,238,238));g.fillRect(135,215,50,150);g.dispose();return im;
    }
    @Test void segmentationPreservesSameColorClothingAndMakesExteriorTransparent() throws Exception {
        var in=fixture();var bytes=new ByteArrayOutputStream();ImageIO.write(in,"png",bytes);
        long start=System.nanoTime();byte[] png=BackgroundMatte.remove(bytes.toByteArray());
        var out=ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(0,out.getRGB(0,0)>>>24);assertEquals(0,out.getRGB(15,240)>>>24);
        assertEquals(255,out.getRGB(155,280)>>>24);
        assertEquals(in.getRGB(155,280)&0xffffff,out.getRGB(155,280)&0xffffff);
        assertEquals(255,out.getRGB(160,70)>>>24);
        assertNull(PortraitService.validateResult(png,true));
        Path qa=Path.of("build/matte-qa");Files.createDirectories(qa);ImageIO.write(in,"png",qa.resolve("fixture-input.png").toFile());Files.write(qa.resolve("fixture-output.png"),png);
        System.out.println("MATTE_FIXTURE_MS="+(System.nanoTime()-start)/1_000_000);
    }
    @Test void defaultResolutionProducesBoundedTransparentPng() throws Exception {
        var im=new BufferedImage(1024,1536,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();
        g.drawImage(fixture(),0,0,1024,1536,null);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(im,"png",bytes);
        long start=System.nanoTime();byte[] png=BackgroundMatte.remove(bytes.toByteArray());
        assertNull(PortraitService.validateResult(png,true));
        assertEquals(1024,ImageIO.read(new ByteArrayInputStream(png)).getWidth());
        System.out.println("MATTE_1024x1536_MS="+(System.nanoTime()-start)/1_000_000+" PNG_BYTES="+png.length);
    }
    @Test void rejectsUniformImageInsteadOfInventingSubject() {
        var im=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
        assertThrows(IOException.class,()->BackgroundMatte.cut(im));
    }
    @Test void rejectsInconsistentBackgroundCorners() {
        var im=fixture();im.setRGB(0,0,Color.RED.getRGB());
        assertThrows(IOException.class,()->BackgroundMatte.cut(im));
    }
    @Test void modelTwoUsesOpaqueRequestAndOnePointFiveKeepsNativeAlpha() {
        var c=new YamlConfiguration();c.set("image.background","transparent");var s=new PortraitSettings(c);
        assertEquals("opaque",s.requestBackground("gpt-image-2"));
        assertEquals("transparent",s.requestBackground("gpt-image-1.5"));
        assertTrue(PromptBuilder.forModel("Fully transparent background.","gpt-image-2").contains("#EEEEEE"));
        assertFalse(PromptBuilder.forModel("Fully transparent background.","gpt-image-2").contains("Fully transparent background."));
        assertEquals("Fully transparent background.",PromptBuilder.forModel("Fully transparent background.","gpt-image-1.5"));
    }
}
