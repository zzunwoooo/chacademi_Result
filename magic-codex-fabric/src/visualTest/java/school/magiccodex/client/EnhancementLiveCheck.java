package school.magiccodex.client;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import school.magiccodex.protocol.EnhancementProtocol;
public class EnhancementLiveCheck {
 static class WandPreview extends net.minecraft.client.gui.screen.Screen {
 final net.minecraft.item.ItemStack item;
 WandPreview(net.minecraft.item.ItemStack stack){super(net.minecraft.text.Text.literal("Wand"));item=stack.copy();var lore=new java.util.ArrayList<>(item.getOrDefault(net.minecraft.component.DataComponentTypes.LORE,net.minecraft.component.type.LoreComponent.DEFAULT).lines());if(ManaCoreTooltip.matches(item)){item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,net.minecraft.text.Text.literal("마력코어"));lore.clear();lore.add(net.minecraft.text.Text.literal("강화 재료"));lore.add(net.minecraft.text.Text.literal("마력이 흐르는 회로가 새겨져 있습니다."));}else lore.add(net.minecraft.text.Text.literal("처음 마력을 다루는 이를 위한 마법봉입니다."));item.set(net.minecraft.component.DataComponentTypes.LORE,new net.minecraft.component.type.LoreComponent(lore));}
 public void render(net.minecraft.client.gui.DrawContext c,int x,int y,float delta){c.drawItemTooltip(textRenderer,item,width/2, height/2);}
 public boolean shouldPause(){return false;}
 }

 static EnhancementScreen screen(MinecraftClient c){if(!(c.currentScreen instanceof EnhancementScreen))throw new IllegalStateException("No enhancement UI");return (EnhancementScreen)c.currentScreen;}
 static void cmd(MinecraftClient c,String s){c.getNetworkHandler().sendChatCommand(s);}
 static void select(MinecraftClient c,int slot){c.player.getInventory().selectedSlot=slot;c.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));}
 static void shot(MinecraftClient c,String name)throws Exception{Path p=Path.of("visual-check/enhancement-0275");Files.createDirectories(p);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(p.resolve(name+".png"));}}
 static void check(boolean b,String msg){if(!b)throw new IllegalStateException(msg);}
 public static void register(){int[] phase={-2},ticks={0},sent={0};long started=System.currentTimeMillis();
 ClientLifecycleEvents.CLIENT_STARTED.register(mc->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
  if(System.currentTimeMillis()-started>230000)throw new IllegalStateException("Enhancement timeout phase="+phase[0]);if(c.getOverlay()!=null)return;
  if(phase[0]==-2){if(!Files.exists(Path.of("../../output/enhancement-system-v6/server-test/ready.txt")))return;c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25598"),new ServerInfo("Enhancement test","127.0.0.1:25598",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;}
  if(c.player==null||c.world==null)return;if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;}
  if(phase[0]==3&&c.currentScreen instanceof EnhancementScreen e&&e.snapshot().state()==1){var r=e.snapshot();for(int i=0;i<r.nodes();i++)if((sent[0]&(1<<i))==0&&e.elapsed()>=r.targets().get(i)){sent[0]|=1<<i;e.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE,0,0);e.keyReleased(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE,0,0);EnhancementClient.send(r.token(),1,i);}}
  if(phase[0]==8&&ticks[0]==10)check((screen(c).snapshot().missMask()&512)==0,"future node must not be judged early");
  if(phase[0]==3&&ticks[0]==50)shot(c,"02-hit-flash");
  c.inGameHud.getChatHud().clear(false);if(++ticks[0]<(phase[0]==3||phase[0]==8?200:65))return;ticks[0]=0;
  switch(phase[0]++){
   case 0->{select(c,0);cmd(c,"appraisalfixture setup");}
   case 1->{cmd(c,"gamemode creative");cmd(c,"강화");float scale=.9f*Math.min(c.getWindow().getScaledWidth()/790f,c.getWindow().getScaledHeight()/530f);double left=(c.getWindow().getScaledWidth()-768*scale)/2,top=(c.getWindow().getScaledHeight()-512*scale)/2;org.lwjgl.glfw.GLFW.glfwSetCursorPos(c.getWindow().getHandle(),(left+166*scale)*c.getWindow().getScaleFactor(),(top+155*scale)*c.getWindow().getScaleFactor());}
   case 2->{var s=screen(c).snapshot();check(s.cost()==100,"cost="+s.cost());shot(c,"01-offer");EnhancementClient.send(s.token(),0,0);EnhancementClient.send(s.token(),0,0);}
   case 3->{var s=screen(c).snapshot();check(s.state()==2,"state="+s.state()+" hits="+s.hitMask()+" miss="+s.missMask());check(s.balance()==4900,"single charge");check(s.attempts()==1,"attempt");check(c.player.getInventory().getStack(1).isEmpty(),"success consumes core");check(ManaClient.maximum()==105&&ManaClient.haste()==1,"wand stats "+ManaClient.maximum()+" / "+ManaClient.haste());shot(c,"02-success");c.currentScreen.close();select(c,2);}
   case 4->{check(ManaClient.maximum()==100&&ManaClient.haste()==0,"unequip reset");select(c,0);cmd(c,"appraisalfixture ten");}
   case 5->cmd(c,"강화");
   case 6->{var s=screen(c).snapshot();check(s.nodes()==10,"ten");shot(c,"03-ten-nodes");EnhancementClient.send(s.token(),0,0);}
   case 7->{shot(c,"04-circuit");var s=screen(c).snapshot();EnhancementClient.send(s.token(),1,9);}
   case 8->{var s=screen(c).snapshot();check(s.state()==3,"failed starcatch");check(c.player.getInventory().getStack(1).isEmpty(),"failure consumes core");check(s.balance()==4800&&s.attempts()==2,"failure charge/attempt");check(ManaClient.maximum()==105,"failure preserves stats");shot(c,"05-failure");c.currentScreen.close();cmd(c,"appraisalfixture verify");cmd(c,"appraisalfixture ten");cmd(c,"appraisalfixture poor");}
   case 9->cmd(c,"강화");
   case 10->{var s=screen(c).snapshot();EnhancementClient.send(s.token(),0,0);}
   case 11->{var s=screen(c).snapshot();check(s.state()==4&&s.message().equals("돈이 부족합니다."),"poor");check(!c.player.getInventory().getStack(1).isEmpty(),"core preserved");shot(c,"06-poor");c.setScreen(new WandPreview(c.player.getMainHandStack()));}
   case 12->{shot(c,"07-wand-tooltip");c.setScreen(new WandPreview(c.player.getInventory().getStack(1)));}
   case 13->{shot(c,"08-core-tooltip");Path out=Path.of("visual-check/enhancement-0275");Files.writeString(out.resolve("done.txt"),"ENHANCEMENT_E2E_OK");c.scheduleStop();}
  }
 }catch(Exception e){throw new IllegalStateException(e);}}));}
}

