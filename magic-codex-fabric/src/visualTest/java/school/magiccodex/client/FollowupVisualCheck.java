package school.magiccodex.client;
import net.fabricmc.api.ClientModInitializer;import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.util.ScreenshotRecorder;import org.lwjgl.glfw.GLFW;import java.nio.file.*;
/** Hosting-only isolated rendering fixture; never packaged in the production mod. */
public final class FollowupVisualCheck implements ClientModInitializer {
 private int step,ticks;private final Path out=Path.of("followup-visual");
 public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null||c.currentScreen==null)return;try{
  Files.createDirectories(out);
  if(ticks==0){c.options.pauseOnLostFocus=false;GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1280,720);c.options.getGuiScale().setValue(2+step/3);c.onResolutionChanged();
   if(step%3<2){var screen=new SettingsScreen(null);c.setScreen(screen);if(step%3==1){float scale=Math.min(screen.width*.9f/1000,screen.height*.9f/730),ox=(screen.width-1000*scale)/2,oy=(screen.height-730*scale)/2;screen.mouseClicked(ox+500*scale,oy+365*scale,0);if(c.currentScreen==screen)throw new AssertionError("camera category click");}}
   else{var k=new KeySettingsScreen(null,CodexData::previewSpells,false,out.resolve("keys.yml"));c.setScreen(k);k.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);if(k.pickerOpen())throw new AssertionError("physical key opened picker");k.mouseClicked(0,0,4);if(k.pickerOpen())throw new AssertionError("physical thumb opened picker");var fit=KeySettingsScreen.layoutFor(k.width,k.height);k.mouseClicked(fit.x()+(MouseBindingLayout.X+28*MouseBindingLayout.S)*fit.scale(),fit.y()+(MouseBindingLayout.Y+221*MouseBindingLayout.S)*fit.scale(),0);if(k.pickerKey()!=KeySettingsLayout.THUMB_1)throw new AssertionError("PNG thumb hit");}
  }
  if(++ticks<60)return;UiResources.images().verifyGpuState();try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve("case-"+step+".png"));}
  Files.writeString(out.resolve("results.txt"),"PASS "+step+" GUI="+c.options.getGuiScale().getValue()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);ticks=0;if(++step==9){Files.writeString(out.resolve("done.txt"),"PASS 9 GPU-rendered settings/camera/key cases across GUI2/3/4; physical input blocked; PNG thumb hit");c.scheduleStop();}
 }catch(Throwable e){e.printStackTrace();try{Files.writeString(out.resolve("failed.txt"),e.toString());}catch(Exception ignored){}c.scheduleStop();}});}
}
