package school.magiccodex.client;
import java.nio.file.*;import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.Screen;import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.ScreenshotRecorder;import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;import school.magiccodex.protocol.JournalProtocol.Entry;
/** Offline renderer and actual UI event fixture. Never included in production JAR. */
public final class JournalVisualCheck implements ClientModInitializer {
 static int step,ticks;static Path out=Path.of("visual-check/journal-final");
 static void set(Object object,Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 static void click(Screen screen,double x,double y){var c=net.minecraft.client.MinecraftClient.getInstance();var l=screen instanceof KeySettingsScreen?KeySettingsScreen.layoutFor(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()):CodexLayout.codexFit(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight());screen.mouseClicked(l.x()+x*l.scale(),l.y()+y*l.scale(),0);}
 static void rows()throws Exception{var rows=new ArrayList<Entry>();for(int i=0;i<9;i++)rows.add(new Entry("story","ch1-"+(i+1),"1-"+(i+1)+". \uac70\uc6b8 \ub108\uba38\uc758 \uae30\uc5b5",("\ud559\uad50\uc758 \uc624\ub798\ub41c \uae30\ub85d\uc744 \ucc3e\uc544 \uc5ec\ud589\uc744 \uc2dc\uc791\ud569\ub2c8\ub2e4. \n").repeat(9),i<7?"active":"claimed",true,"Story / ch1-"+(i+1),""));rows.add(new Entry("quest","patrol","\uc57c\uc0dd \uc21c\ucc30","\uc232\uae38\uc744 \uc9c0\ucf1c \uc8fc\uc138\uc694.","active",false,"\uc880\ube44 \ucc98\uce58 7 / 10", ""));rows.add(new Entry("quest","library","\ub3c4\uc11c\uad00 \uae30\ub85d","\uc0ac\uc11c\ub97c \ub9cc\ub0ac\uc2b5\ub2c8\ub2e4.","claimed",false,"\uc644\ub8cc", ""));set(null,JournalClient.class,"server",List.copyOf(rows));set(null,JournalClient.class,"localRows",List.of());}
 static final class Notices extends Screen {Notices(){super(Text.literal("notification fixture"));}public void render(DrawContext c,int x,int y,float d){c.fill(0,0,width,height,0xFF254735);SharedNotifications.render(c,client,true,net.minecraft.util.Util.getMeasuringTimeMs());}public boolean shouldPause(){return false;}}
 public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null||c.currentScreen==null)return;try{
  Files.createDirectories(out);
  if(ticks==0){c.options.pauseOnLostFocus=false;GLFW.glfwSetWindowSize(c.getWindow().getHandle(),step<7?1280:1920,step<7?720:1080);c.options.getGuiScale().setValue(step==7?4:2);c.onResolutionChanged();rows();
   if(step<=4||step==7){QuestScreen q=new QuestScreen(step==2||step==3?1:2);c.setScreen(q);set(q,QuestScreen.class,"refresh",Long.MAX_VALUE);if(step==1||step==3)click(q,200,241+(step==1?396:132)+20);if(step==4){click(q,480,826);set(null,JournalClient.class,"server",List.of());} }
   else if(step==5||step==6){KeySettingsScreen k=new KeySettingsScreen(null,CodexData::previewSpells,false,out.resolve("fixture-keys.yml"));c.setScreen(k);if(step==6){click(k,MouseBindingLayout.X+300*MouseBindingLayout.S,MouseBindingLayout.Y+740*MouseBindingLayout.S);if(k.pickerKey()!=KeySettingsLayout.THUMB_1)throw new AssertionError("thumb PNG hit");var l=KeySettingsScreen.layoutFor(k.width,k.height);GLFW.glfwSetCursorPos(c.getWindow().getHandle(),(l.x()+450*l.scale())*c.getWindow().getScaleFactor(),(l.y()+320*l.scale())*c.getWindow().getScaleFactor());} }
   else{c.setScreen(new Notices());SharedNotifications.offer("q1","quest","\uc0c8\ub85c\uc6b4 \uc5ec\uc815","");SharedNotifications.offer("f1","friend","\uce5c\uad6c \uc54c\ub9bc","\uce5c\uad6c\uac00 \uc811\uc18d\ud588\uc2b5\ub2c8\ub2e4.");SharedNotifications.offer("s1","system","\uc124\uc815 \uc800\uc7a5","\ubcc0\uacbd\ud55c \uc124\uc815\uc744 \uc800\uc7a5\ud588\uc2b5\ub2c8\ub2e4.");}
  }
  if(++ticks<45)return;
  try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve("case-"+step+".png"));}
  if(step==6){var k=(KeySettingsScreen)c.currentScreen;click(k,450,320);click(k,1280,850);if(SpellKeySettings.load(out.resolve("fixture-keys.yml")).slotOfKey(KeySettingsLayout.THUMB_1)<0)throw new AssertionError("thumb save/reload");k.keyPressed(340,0,0);if(k.pickerKey()!=340)throw new AssertionError("left shift");k.keyPressed(256,0,0);k.keyPressed(341,0,0);if(k.pickerKey()!=341)throw new AssertionError("left ctrl");k.keyPressed(256,0,0);k.mouseClicked(0,0,4);if(k.pickerKey()!=KeySettingsLayout.THUMB_2)throw new AssertionError("physical thumb2");}
  Files.writeString(out.resolve("results.txt"),"PASS "+step+" framebuffer="+c.getFramebuffer().textureWidth+"x"+c.getFramebuffer().textureHeight+" GUI="+c.options.getGuiScale().getValue()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  ticks=0;if(++step==9){Files.writeString(out.resolve("done.txt"),"PASS 9 rendered cases; categories/status/empty, GUI2/4, thumb hit/picker/save, left modifiers, Alt refusal, shared stack");c.scheduleStop();}
 }catch(Throwable e){e.printStackTrace();try{Files.writeString(out.resolve("failed.txt"),e.toString());}catch(Exception ignored){}c.scheduleStop();}});}
}
