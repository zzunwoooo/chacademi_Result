package school.magiccodex.client;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
public final class ShinyLiveCheck {
 public static void register(){int[] phase={0},ticks={0},index={0};long start=System.currentTimeMillis();
  String[] types={"wolf","fox","rabbit","cow","creeper","ender_dragon","wither","warden","elder_guardian","ocean_guardian","iron_golem","snow_golem","sheep","pig","chicken","mooshroom","cat","horse","donkey","mule","skeleton_horse","zombie_horse","parrot","llama","trader_llama","villager","wandering_trader","zombie","husk","drowned","zombie_villager","skeleton","stray","bogged","wither_skeleton","spider","cave_spider","silverfish","endermite","enderman","slime","magma_cube","blaze","ghast","witch","evoker","vindicator","illusioner","vex","pillager","ravager","giant","shulker","phantom","bat","bee","allay","breeze","creaking","armadillo","sniffer","camel","goat","frog","tadpole","axolotl","turtle","dolphin","cod","salmon","pufferfish","tropical_fish","squid","glow_squid","polar_bear","panda","ocelot","piglin","piglin_brute","zombified_piglin","hoglin","zoglin","strider"};
  List<String> failures=new ArrayList<>();
  ClientLifecycleEvents.CLIENT_STARTED.register(c0->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
   if(System.currentTimeMillis()-start>300000)throw new IllegalStateException("Shiny visual timeout");if(c.getOverlay()!=null)return;
   if(phase[0]==0&&c.currentScreen!=null){c.options.pauseOnLostFocus=false;c.options.getViewDistance().setValue(3);c.options.getGuiScale().setValue(2);c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25993"),new ServerInfo("Shiny test","127.0.0.1:25993",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
   if(c.world==null||c.player==null)return;ticks[0]++;
   if(phase[0]==1&&ticks[0]>30){c.player.networkHandler.sendChatCommand("shinyfixture "+types[index[0]]);ticks[0]=0;phase[0]=2;}
   else if(phase[0]==2&&ticks[0]>45){
    String type=types[index[0]].equals("ocean_guardian")?"guardian":types[index[0]];
    var f=ShinyClient.class.getDeclaredField("textures");f.setAccessible(true);var textures=(Map<?,?>)f.get(null);
    boolean rendered=textures.keySet().stream().anyMatch(k->k.toString().startsWith(type+"/"));
    if(!rendered)failures.add(type);var out=Path.of("visual-check/shiny-0300");Files.createDirectories(out);
    if(index[0]<10||!rendered)try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(type+".png"));}
    Files.writeString(out.resolve("progress.txt"),(index[0]+1)+"/83 "+type+"="+rendered+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    if(++index[0]==types.length){Files.writeString(out.resolve("done.txt"),failures.isEmpty()?"SHINY_LIVE_PASS: 83 entity types rendered server-marked alternate textures":"SHINY_LIVE_FAIL: "+failures);c.scheduleStop();phase[0]=3;}
    else{c.player.networkHandler.sendChatCommand("shinyfixture "+types[index[0]]);ticks[0]=0;}
   }
  }catch(Exception e){throw new IllegalStateException(e);}}));
 }
}
