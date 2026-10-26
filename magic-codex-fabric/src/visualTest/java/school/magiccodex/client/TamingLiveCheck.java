package school.magiccodex.client;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.TamingProtocol;
import school.magiccodex.protocol.PetProtocol;
public final class TamingLiveCheck {
 public static void register(){int[] phase={0},ticks={0};long started=System.currentTimeMillis();
  ClientLifecycleEvents.CLIENT_STARTED.register(c0->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
   if(System.currentTimeMillis()-started>240000)throw new IllegalStateException("Taming visual timeout phase "+phase[0]);
   if(c.getOverlay()!=null)return;
   if(phase[0]==0&&c.currentScreen!=null){c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
    ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25991"),new ServerInfo("Taming test","127.0.0.1:25991",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
   if(c.world==null||c.player==null)return;var f=TamingClient.class.getDeclaredField("state");f.setAccessible(true);var s=(TamingProtocol.State)f.get(null);ticks[0]++;
   if(phase[0]==1&&s!=null&&s.status()==TamingProtocol.TARGET&&ticks[0]>90){shot(c,"01-target");ManaClient.cast(CodexCatalog.spells().stream().filter(spell->spell.id().equals("taming")).findFirst().orElseThrow());phase[0]=2;ticks[0]=0;}
   else if(phase[0]==2&&s!=null&&s.status()==TamingProtocol.CHANNEL&&ticks[0]>25){shot(c,"02-channel");phase[0]=3;}
   else if(phase[0]==3&&s!=null&&s.status()==TamingProtocol.SUCCESS){shot(c,"03-success");phase[0]=4;ticks[0]=0;}
   else if(phase[0]==4&&ticks[0]>25){PetClient.open();phase[0]=5;ticks[0]=0;}
   else if(phase[0]==5&&ticks[0]>90){var p=PetClient.STATE.visible().stream().filter(e->e.id().equals("chacademia_wolf")).findFirst().orElseThrow();if(!p.owned())throw new IllegalStateException("Pet ownership did not refresh");shot(c,"04-pet-owned");if(!PetClient.request(PetProtocol.SUMMON,p.id(),true))throw new IllegalStateException("Summon request rejected");phase[0]=6;ticks[0]=0;}
   else if(phase[0]==6&&ticks[0]>100){var p=PetClient.STATE.visible().stream().filter(e->e.id().equals("chacademia_wolf")).findFirst().orElseThrow();if(!p.active())throw new IllegalStateException("MCPets summon failed "+PetClient.notice());shot(c,"05-pet-summoned");PetClient.request(PetProtocol.DISMISS,p.id(),true);phase[0]=7;ticks[0]=0;}
   else if(phase[0]==7&&ticks[0]>60){c.setScreen(null);c.player.networkHandler.sendChatCommand("tamingfixture boss");phase[0]=8;ticks[0]=0;}
   else if(phase[0]==8&&ticks[0]>35&&s!=null&&s.boss()&&s.status()==TamingProtocol.TARGET){var e=c.world.getEntityById(s.entityId());c.interactionManager.attackEntity(c.player,e);phase[0]=9;ticks[0]=0;}
   else if(phase[0]==9&&ticks[0]>20&&s!=null&&s.graceMs()>0){shot(c,"06-boss-sealed");ManaClient.cast(CodexCatalog.spells().stream().filter(spell->spell.id().equals("taming")).findFirst().orElseThrow());phase[0]=10;ticks[0]=0;}
   else if(phase[0]==10&&ticks[0]>25&&s!=null&&s.status()==TamingProtocol.CHANNEL){shot(c,"07-boss-channel");phase[0]=11;}
   else if(phase[0]==11&&s!=null&&s.status()==TamingProtocol.SUCCESS){shot(c,"08-boss-success");Files.writeString(Path.of("visual-check/taming-0290/done.txt"),"TAMING_LIVE_PASS: actual client spell packet, success, ownership refresh, MCPets summon/dismiss, player lethal hit -> boss grace -> capture");c.scheduleStop();phase[0]=12;}
  }catch(Exception e){throw new IllegalStateException(e);}}));
 }
 private static void shot(net.minecraft.client.MinecraftClient c,String name)throws Exception{var out=Path.of("visual-check/taming-0290");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(name+".png"));}}
}
