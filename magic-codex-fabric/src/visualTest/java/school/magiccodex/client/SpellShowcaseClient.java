package school.magiccodex.client;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import java.nio.file.*;
/** Development-only live server showcase, keeps the client open afterwards. */
public final class SpellShowcaseClient {
 public static void register(){int[] phase={0},ticks={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
  if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(6);c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);c.onResolutionChanged();ConnectScreen.connect(new net.minecraft.client.gui.screen.TitleScreen(),c,ServerAddress.parse("127.0.0.1:25985"),new ServerInfo("어제 만든 마법 시연","127.0.0.1:25985",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
  if(c.player==null||c.world==null)return;int t=++ticks[0];
  if(phase[0]==1&&t>=240){c.getNetworkHandler().sendChatCommand("시연");phase[0]=2;ticks[0]=0;System.out.println("SHOWCASE_CLIENT_STARTED");}
  if(phase[0]==2&&((t%240)==65||(t%240)==145)&&t<31*240){Path out=Path.of("../../output/spell-showcase-v1/screenshots");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(String.format("spell-%02d-%03d.png",t/240+1,t%240)));}}
 }catch(Exception e){System.err.println("SHOWCASE_CLIENT_ERROR "+e);}});}
}
