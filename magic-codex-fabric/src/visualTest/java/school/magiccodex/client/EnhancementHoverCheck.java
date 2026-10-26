package school.magiccodex.client;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import school.magiccodex.protocol.EnhancementProtocol.Response;
public class EnhancementHoverCheck {
 static class Preview extends Screen {
  final EnhancementScreen inner=new EnhancementScreen(new Response(1,0,10,0,10,0,0,80,800,"수습 마법봉","",2000,10000,.4,.1,List.of(24d,0d,0d),List.of(2d,5d,1d),List.of(1000,2000,3000,4000,5000,6000,7000,8000,9000,10000)));
  Preview(){super(Text.literal("Hover preview"));}
  protected void init(){inner.init(client,width,height);}
  public void render(DrawContext c,int mx,int my,float delta){float scale=.9f*Math.min(width/790f,height/530f);inner.render(c,Math.round((width-768*scale)/2+166*scale),Math.round((height-512*scale)/2+155*scale),delta);}
 }
 public static void register(){int[] ticks={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{if(++ticks[0]==20){c.options.getGuiScale().setValue(2);c.onResolutionChanged();c.setScreen(new Preview());}if(ticks[0]==100){var path=java.nio.file.Path.of("visual-check/enhancement-0275/09-hover-card.png");try(var im=net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(path);}c.scheduleStop();}}catch(Exception e){throw new RuntimeException(e);}});}
}
