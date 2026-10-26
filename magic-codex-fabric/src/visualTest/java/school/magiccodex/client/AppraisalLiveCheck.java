package school.magiccodex.client;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
public class AppraisalLiveCheck {
 static AppraisalScreen screen(MinecraftClient c){if(!(c.currentScreen instanceof AppraisalScreen))throw new IllegalStateException("No appraisal UI");return (AppraisalScreen)c.currentScreen;}
 static void cmd(MinecraftClient c,String s){c.getNetworkHandler().sendChatCommand(s);}
 static void select(MinecraftClient c,int slot){c.player.getInventory().selectedSlot=slot;c.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));}
 static void shot(MinecraftClient c,String name)throws Exception{Path p=Path.of("visual-check/appraisal-0265");Files.createDirectories(p);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(p.resolve(name+".png"));}}
 static int captured; static void check(boolean b){if(!b)throw new IllegalStateException("Appraisal assertion failed");}
 public static void register(){int[] phase={-2},ticks={0};long started=System.currentTimeMillis();
 ClientLifecycleEvents.CLIENT_STARTED.register(mc->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
  if(System.currentTimeMillis()-started>230000)throw new IllegalStateException("Appraisal timeout");if(c.getOverlay()!=null)return;
  if(phase[0]==-2){if(!Files.exists(Path.of("../../output/core-appraisal-system-v6/server-test/ready.txt")))return;c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25598"),new ServerInfo("Appraisal test","127.0.0.1:25598",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;}
  if(c.player==null||c.world==null)return;if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;}
  if(c.currentScreen instanceof AppraisalScreen a&&a.snapshot().result()&&a.snapshot().state()==2&&captured<3&&a.animationAge()>4.6+.16+captured*.18){shot(c,"break-transition-"+captured);captured++;} c.inGameHud.getChatHud().clear(false);if(++ticks[0]<(phase[0]==8?130:90))return;ticks[0]=0;
  switch(phase[0]++){
   case 0->{select(c,0);cmd(c,"appraisalfixture setup");}
   case 1->{check(ManaCoreTooltip.matches(c.player.getMainHandStack()));check(ManaCoreTooltip.unidentified(c.player.getMainHandStack()));cmd(c,"코어감정");}
   case 2->{var s=screen(c);check(s.snapshot().balance()==5000);check(s.snapshot().chances().size()==10);check(s.snapshot().chances().get(3)==1d);shot(c,"01-offer");AppraisalClient.claim(s.token()+1);AppraisalClient.claim(s.token());AppraisalClient.claim(s.token());}
   case 3->{check(screen(c).snapshot().nodes()==10);shot(c,"02-forming");}
   case 4->shot(c,"03-milestone");
   case 5->{shot(c,"04-complete");check(screen(c).snapshot().balance()==4000);check(!ManaCoreTooltip.unidentified(c.player.getMainHandStack()));c.currentScreen.close();select(c,1);cmd(c,"appraisalfixture break");}
   case 6->cmd(c,"코어감정");
   case 7->{var s=screen(c);AppraisalClient.claim(s.token());AppraisalClient.claim(s.token());}
   case 8->{check(screen(c).snapshot().state()==2);check(screen(c).snapshot().nodes()==3);shot(c,"05-broken");c.currentScreen.close();cmd(c,"코어감정");}
   case 9->{var s=screen(c);check(s.snapshot().materials()==0);shot(c,"06-no-repair");AppraisalClient.claim(s.token());AppraisalClient.claim(s.token());}
   case 10->{check(!screen(c).snapshot().repaired());check(screen(c).snapshot().balance()==3000);check(!c.player.getInventory().getStack(2).isEmpty());check(screen(c).snapshot().state()==2);shot(c,"07-rejected");c.currentScreen.close();cmd(c,"코어감정");}
   case 11->AppraisalClient.claim(screen(c).token());
   case 12->{check(screen(c).snapshot().message().equals("파괴된 코어는 사용할 수 없습니다."));check(screen(c).snapshot().balance()==3000);check(screen(c).snapshot().state()==2);shot(c,"08-still-broken");cmd(c,"appraisalfixture verify");}
   case 13->{Path out=Path.of("visual-check/appraisal-0265");Files.createDirectories(out);Files.writeString(out.resolve("done.txt"),"APPRAISAL_E2E_OK");c.scheduleStop();}
  }
 }catch(Exception e){throw new IllegalStateException(e);}}));}
}
