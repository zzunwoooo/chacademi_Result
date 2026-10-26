package school.magiccodex.client;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.TitleProtocol;
public final class TitleLiveCheck {
 public static void register(){int[] phase={0},ticks={0},total={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
  if(++total[0]>2400)throw new AssertionError("Live test timeout");
  if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();ConnectScreen.connect(new net.minecraft.client.gui.screen.TitleScreen(),c,ServerAddress.parse("127.0.0.1:25994"),new ServerInfo("Title test","127.0.0.1:25994",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
  if(c.player==null||c.world==null)return;if(++ticks[0]<50)return;ticks[0]=0;
  switch(phase[0]++){
   case 1->TitleClient.open();
   case 2->{var s=screen(c);if(!data(s).nickname().equals("차카테스트")||data(s).entries().size()!=8)throw new AssertionError("Nickname / owned list");shot(c,"title-default.png");click(c,100,562);click(c,620,502);click(c,540,813);}
   case 3->{var r=data(screen(c));if(!r.prefix().equals("prefix_bond")||!r.suffix().equals("suffix_researcher"))throw new AssertionError("Apply");shot(c,"title-selected.png");c.getNetworkHandler().sendChatCommand("titlefixture first");click(c,100,442);click(c,620,442);click(c,540,813);}
   case 4->{var r=data(screen(c));if(!r.prefix().isEmpty()||!r.suffix().isEmpty())throw new AssertionError("Clear");c.getNetworkHandler().sendChatCommand("titlefixture cleared");c.getNetworkHandler().sendChatCommand("titlefixture revoke");}
   case 5->{var r=data(screen(c));if(r.entries().stream().anyMatch(e->e.id().equals("prefix_bond")))throw new AssertionError("Revoke UI");net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new TitleClient.Query(TitleProtocol.encode(new TitleProtocol.Request(TitleProtocol.APPLY,999,r.token(),r.revision(),"prefix_bond","suffix_researcher"))));}
   case 6->{screen(c).close();c.getNetworkHandler().sendChatCommand("칭호");}
   case 7->{var r=data(screen(c));if(!r.prefix().isEmpty()||!r.suffix().isEmpty())throw new AssertionError("Forged selection changed DB");click(c,100,502);click(c,620,562);click(c,540,813);}
   case 8->{c.options.getGuiScale().setValue(3);c.onResolutionChanged();}
   case 9->{shot(c,"title-gui3.png");screen(c).verifyRenderer();c.getNetworkHandler().sendChatCommand("titlefixture last");}
   case 10->{screen(c).close();c.getNetworkHandler().sendChatCommand("칭호");}
   case 11->{var r=data(screen(c));if(!r.prefix().equals("prefix_starlight")||!r.suffix().equals("suffix_student"))throw new AssertionError("Reopen save");System.out.println("TITLE_CLIENT_LIVE_PASS");c.scheduleStop();}
  }
 }catch(Throwable e){System.err.println("TITLE_CLIENT_LIVE_FAIL "+e);c.scheduleStop();}});}
 private static TitleScreen screen(net.minecraft.client.MinecraftClient c){if(!(c.currentScreen instanceof TitleScreen s))throw new AssertionError("Title screen missing");return s;}
 private static TitleProtocol.Response data(TitleScreen s)throws Exception{var f=TitleScreen.class.getDeclaredField("data");f.setAccessible(true);var r=(TitleProtocol.Response)f.get(s);if(r==null)throw new AssertionError("Response missing");return r;}
 private static void click(net.minecraft.client.MinecraftClient c,int x,int y){var l=TitleLayout.fit(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight());screen(c).mouseClicked(l.x()+x*l.scale(),l.y()+y*l.scale(),0);}
 private static void shot(net.minecraft.client.MinecraftClient c,String name)throws Exception{Path out=Path.of("visual-check/titles-v1");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(name));}}
}
