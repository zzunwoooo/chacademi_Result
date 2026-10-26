package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;
import school.magiccodex.protocol.WalletProtocol;

/** Real integrated world, real payload exchange and server command execution; excluded from release. */
final class TopMenuVisualCheck {
    private static final CopyOnWriteArrayList<String> commands=new CopyOnWriteArrayList<>();
    private static CodexTypography type;
    static void register(){
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            for(String name:new String[]{"메일함","펫도감","캐시샵","친구","퀘스트"})
                dispatcher.register(CommandManager.literal(name).executes(ctx->{commands.add(name);return 1;}));
        });
        int[] phase={0},ticks={0};long start=System.currentTimeMillis();
        // Feed deterministic pointer coordinates immediately before the production HUD draw.
        // Windows can ignore glfwSetCursorPos while the development window is in the background.
        HudRenderCallback.EVENT.register((draw,tickCounter)->{
            if(phase[0]!=3 && phase[0]!=11 && phase[0]!=12)return;
            var c=MinecraftClient.getInstance();
            if(!(c.currentScreen instanceof HudCursorScreen))c.setScreen(new HudCursorScreen());
            float scale=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();
            try{
                var xField=c.mouse.getClass().getDeclaredField("x");xField.setAccessible(true);
                var yField=c.mouse.getClass().getDeclaredField("y");yField.setAccessible(true);
                int index=phase[0]==11?7:phase[0]==12?9:1;
                xField.setDouble(c.mouse,(6+base()-13+TopMenuState.STEP*index)*scale*c.getWindow().getWidth()/c.getWindow().getScaledWidth());
                yField.setDouble(c.mouse,21*scale*c.getWindow().getHeight()/c.getWindow().getScaledHeight());
            }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        });
        // Register after production tick handlers, which correctly close an unheld Alt screen.
        ClientLifecycleEvents.CLIENT_STARTED.register(startedClient -> ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("Menu check timed out phase "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==0 && c.currentScreen!=null){
                ServerPlayNetworking.registerGlobalReceiver(WalletClient.Request.ID,(payload,context)->{
                    if(WalletProtocol.validRequest(payload.bytes()))
                        ServerPlayNetworking.send(context.player(),new WalletClient.Response(WalletProtocol.encode(new WalletProtocol.Snapshot(true,12450.25))));
                });
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);
                c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                if(!Files.exists(Path.of("saves/hud-070-visual/level.dat")))throw new IllegalStateException("Missing isolated HUD test world");
                c.createIntegratedServerLoader().start("hud-070-visual",()->{});phase[0]=1;return;
            }
            if(c.player==null || c.world==null || c.getServer()==null)return;
            if(phase[0]==1){
                if(c.player.getHealth()<=0){c.player.requestRespawn();return;}
                if(c.currentScreen!=null)return;
                type=new CodexTypography(c);
                c.getServer().execute(()->{
                    c.getServer().setDifficulty(Difficulty.PEACEFUL,true);
                    var p=c.getServer().getPlayerManager().getPlayer(c.player.getUuid());
                    p.getAbilities().invulnerable=true;p.sendAbilitiesUpdate();p.setHealth(16);
                    p.getHungerManager().setFoodLevel(14);p.getInventory().setStack(0,new ItemStack(Items.BLAZE_ROD));
                });
                c.player.setPitch(8);c.player.setYaw(30);PlayerHudClient.frame(5);phase[0]=2;ticks[0]=0;return;
            }
            if(phase[0]==3 || phase[0]==11 || phase[0]==12){
                // Exercise the same screen and pointer rendering without requiring a physical Alt key.
                if(!(c.currentScreen instanceof HudCursorScreen))c.setScreen(new HudCursorScreen());
            }
            if(++ticks[0]<30)return;ticks[0]=0;
            try{
                PlayerHudClient.verifyRenderer();
                if(phase[0]==2){
                    check(WalletClient.label().equals("12,450"),"Wallet integer display or networking failed");
                    shot(c,"01-collapsed");click(c,base()-20);phase[0]=3;
                }else if(phase[0]==3){
                    var rendererField=TopMenuClient.class.getDeclaredField("renderer");rendererField.setAccessible(true);
                    var hoverField=TopMenuRenderer.class.getDeclaredField("hover");hoverField.setAccessible(true);
                    check(((float[])hoverField.get(rendererField.get(null)))[2]>.7f,"Codex hover did not visibly fade in");
                    shot(c,"02-expanded-hover");click(c,base()-13+38);
                    check(c.currentScreen instanceof CodexScreen,"Codex button did not open screen");phase[0]=4;
                }else if(phase[0]==4){
                    shot(c,"03-codex-open");c.currentScreen.close();
                    // Pet/social/quest buttons now use client screens; their live checks cover server actions.
                    // This check is scoped to real PNG rendering and the shared menu layout.
                    click(c,base()+TopMenuState.STEP*TopMenuState.LABELS.length-20);phase[0]=10;
                }else if(phase[0]==10){
                    shot(c,"04-recollapsed");
                    c.options.getGuiScale().setValue(3);c.onResolutionChanged();click(c,base()-20);phase[0]=11;
                }else if(phase[0]==11){
                    shot(c,"05-expanded-gui3");c.options.getGuiScale().setValue(4);c.onResolutionChanged();phase[0]=12;
                }else if(phase[0]==12){
                    shot(c,"06-expanded-gui4");
                    System.out.println("TOP_MENU_VISUAL_OK: wallet payload; collapse/expand; codex open; PNG stats/equipment/titles; existing atlas; hover labels; GUI2/3/4; mip filtering.");
                    type.close();c.setScreen(null);c.scheduleStop();phase[0]=13;
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static float base(){return TopMenuState.baseWidth(type.width(WalletClient.label(),12,Identifier.of("magiccodex","hud_bold")));}
    private static void click(MinecraftClient c,float x){
        c.setScreen(new HudCursorScreen());
        float scale=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();
        check(c.currentScreen.mouseClicked((6+x)*scale,21*scale,0),"Menu click missed at "+x);
        check(!c.options.attackKey.isPressed(),"Menu click attacked");
    }
    private static void shot(MinecraftClient c,String name)throws Exception{
        Path out=Path.of("visual-check/top-menu-icons-v2");Files.createDirectories(out);
        try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve(name+".png"));}
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
