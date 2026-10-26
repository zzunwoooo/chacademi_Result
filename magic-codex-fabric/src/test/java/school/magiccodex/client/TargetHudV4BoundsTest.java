package school.magiccodex.client;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TargetHudV4BoundsTest {
 record Box(double l,double t,double r,double b){}
 String source()throws Exception{return Files.readString(Path.of("src/main/java/school/magiccodex/client/TargetHudClient.java"),StandardCharsets.UTF_8);}
 float v(String name)throws Exception{var m=Pattern.compile("\\b"+name+"\\s*=\\s*(-?[0-9.]+)").matcher(source());assertTrue(m.find(),name);return Float.parseFloat(m.group(1));}
 BufferedImage image(String name)throws Exception{try(var in=getClass().getResourceAsStream("/assets/magiccodex/textures/gui/target_hud/"+name)){assertNotNull(in,name);return ImageIO.read(in);}}
 Font font()throws Exception{try(var in=getClass().getResourceAsStream("/assets/magiccodex/font/maruburi_semibold.ttf")){assertNotNull(in);return Font.createFont(Font.TRUETYPE_FONT,in);}}
 Box alpha(BufferedImage im){int l=im.getWidth(),t=im.getHeight(),r=0,b=0;for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++)if((im.getRGB(x,y)>>>24)>0){l=Math.min(l,x);t=Math.min(t,y);r=Math.max(r,x+1);b=Math.max(b,y+1);}assertTrue(r>l&&b>t);return new Box(l,t,r,b);}
 BufferedImage glyph(String text,float size,float device)throws Exception{float px=Math.round(size*device*64)/64f;var fill=CodexTextRasterizer.rasterize(font(),text,px);var outline=CodexTextRasterizer.rasterize(font(),text,px,true);var g=fill.createGraphics();g.drawImage(outline,0,0,null);g.dispose();return fill;}
 Box textBox(String text,float size,float device,double x,double y,boolean center)throws Exception{var im=glyph(text,size,device);var a=alpha(im);double left=center?x-im.getWidth()/(2d*device):x;double top=y-im.getHeight()/(2d*device);return new Box(left+a.l/device,top+a.t/device,left+a.r/device,top+a.b/device);}
 @Test void approvedNativeFrameAndCoordinatesAreExactlyTheLatest2048Layout()throws Exception{
  for(String n:new String[]{"frame.png","frame_hp.png","track.png","track_hp.png"}){var im=image(n);assertEquals(2048,im.getWidth(),n);assertEquals(84,im.getHeight(),n);}
  assertEquals(2048,v("FRAME_W"));assertEquals(-70,v("NAME_Y"));assertEquals(-12,v("DIVIDER_Y"));assertEquals(-24,v("DAMAGE_Y"));assertEquals(74,v("NAME_SIZE"));assertEquals(430,v("DIVIDER_W"));
  assertFalse(source().contains("title_decoration.png"));assertTrue(source().contains("title_divider.png"));
 }
 @Test void actualFontAndAlphaBoundsStayOnScreenAndClearOfGaugeAtGuiScales()throws Exception{
  var report=new StringBuilder("guiWidth,deviceScale,frameOrigin,frameInkTop,gaugeInkTop,nameBottom,dividerTop,dividerBottom,damageBottom\n");
  var div=image("title_divider.png");var ab=alpha(div);double dh=Math.round(v("DIVIDER_W")*div.getHeight()/div.getWidth());var frameAlpha=alpha(image("frame.png"));var gaugeAlpha=alpha(image("track.png"));
  for(int sw:new int[]{320,640,960,1280,1920,2560})for(float device:new float[]{1,2,3,4}){
   float w=TargetHudLayout.width(sw),s=w/v("FRAME_W");double fx=(sw-w)/2d,fy=8+(v("NAME_SIZE")*.5-v("NAME_Y"))*s;
   double divTop=fy+(Math.round(v("DIVIDER_Y")-dh/2)+ab.t*dh/div.getHeight())*s,divBottom=fy+(Math.round(v("DIVIDER_Y")-dh/2)+ab.b*dh/div.getHeight())*s;
   var name=textBox("서리숲의 수호자",v("NAME_SIZE")*s,device,sw/2d,fy+v("NAME_Y")*s,true);
   float damageSize=v("DAMAGE_SIZE")*s;String damage="123,456";double dx=fx+(v("FRAME_W")-40)*s-CodexTextRasterizer.width(font(),damage,damageSize);
   var dmg=textBox(damage,damageSize,device,dx,fy+v("DAMAGE_Y")*s,false);
   assertTrue(name.t>=0&&name.l>=0&&name.r<=sw,"Name clipped at "+sw+" / "+device);
   assertTrue(name.b<fy,"Name touches frame at "+sw+" / "+device);
   assertTrue(divTop>=0&&divBottom<=fy+3.1*s,"Divider extends past frame ornament boundary");
   assertTrue(dmg.l>=fx&&dmg.r<=fx+w&&dmg.b<=fy+gaugeAlpha.t*s,"Damage text clipped/inside actual gauge at "+sw+" / "+device);
   report.append(sw+","+device+","+fy+","+(fy+frameAlpha.t*s)+","+(fy+gaugeAlpha.t*s)+","+name.b+","+divTop+","+divBottom+","+dmg.b+"\n");
  }
  Files.createDirectories(Path.of("build/hud-v4-audit"));Files.writeString(Path.of("build/hud-v4-audit/bounds.csv"),report);
  preview();
 }
 @Test void hpManaAndFullStatusRowFitWithinTheNativeFrame()throws Exception{
  assertEquals(54,v("GAUGE_X"));assertEquals(1940,v("GAUGE_W"));assertTrue(v("GAUGE_X")+v("GAUGE_W")<=v("FRAME_W"));
  assertTrue(v("HP_Y")+v("HP_H")<=v("MP_Y"));assertTrue(v("MP_Y")+v("MP_H")<=v("FRAME_H"));
  for(int n=0;n<17;n++){float x=TargetHudLayout.iconX(v("ICON_RIGHT"),v("ICON_D"),v("ICON_STEP"),n);assertTrue(x-v("ICON_D")/2>=0);assertTrue(x+v("ICON_D")/2<=v("FRAME_W"));}
 }
 void preview()throws Exception{
  int sw=1280;float w=TargetHudLayout.width(sw),s=w/v("FRAME_W"),fx=(sw-w)/2,fy=8+(v("NAME_SIZE")*.5f-v("NAME_Y"))*s;
  var out=new BufferedImage(sw,180,BufferedImage.TYPE_INT_ARGB);var g=out.createGraphics();g.setColor(new Color(0x182334));g.fillRect(0,0,sw,180);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
  var at=g.getTransform();g.translate(fx,fy);g.scale(s,s);
  var div=image("title_divider.png");int dh=Math.round(v("DIVIDER_W")*div.getHeight()/div.getWidth());g.drawImage(div,Math.round((v("FRAME_W")-v("DIVIDER_W"))/2),Math.round(v("DIVIDER_Y")-dh/2),Math.round(v("DIVIDER_W")),dh,null);
  g.drawImage(image("track.png"),0,0,2048,84,null);
  var hp=image("hp_normal.png");g.drawImage(hp,54,19,54+1455,55,0,0,Math.round(hp.getWidth()*.75f),hp.getHeight(),null);
  var mp=image("mana.png");g.drawImage(mp,54,57,54+1164,67,0,0,Math.round(mp.getWidth()*.6f),mp.getHeight(),null);g.drawImage(image("frame.png"),0,0,2048,84,null);g.setTransform(at);
  draw(g,"서리숲의 수호자",v("NAME_SIZE")*s,sw/2f,fy+v("NAME_Y")*s,true);
  String d="123,456";float ds=v("DAMAGE_SIZE")*s;draw(g,d,ds,fx+(2048-40)*s-CodexTextRasterizer.width(font(),d,ds),fy+v("DAMAGE_Y")*s,false);
  draw(g,"750 / 1000",v("HP_TEXT")*s,sw/2f,fy+(19+18)*s,true);g.dispose();ImageIO.write(out,"png",Path.of("build/hud-v4-audit/preview.png").toFile());
 }
 void draw(Graphics2D g,String text,float size,float x,float y,boolean center)throws Exception{float px=Math.round(size*2*64)/64f;var fill=CodexTextRasterizer.rasterize(font(),text,px);var border=CodexTextRasterizer.rasterize(font(),text,px,true);for(var im:new BufferedImage[]{border,fill}){var p=im.createGraphics();p.setComposite(AlphaComposite.SrcIn);p.setColor(im==border?Color.BLACK:new Color(0xFFF5DB));p.fillRect(0,0,im.getWidth(),im.getHeight());p.dispose();int w=Math.round(im.getWidth()/2f),h=Math.round(im.getHeight()/2f);g.drawImage(im,Math.round(center?x-w/2f:x),Math.round(y-h/2f),w,h,null);}}
}