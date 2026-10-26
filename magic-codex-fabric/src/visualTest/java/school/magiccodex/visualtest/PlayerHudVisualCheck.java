package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.*;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.item.*;
import net.minecraft.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;

/** Real integrated world, real player rendering, and mixin checks; only in run/saves/hud-070-visual. */
final class PlayerHudVisualCheck {
    static void register(){
        int[] phase={0},ticks={0};
        long start=System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("HUD visual check timeout at phase "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==0 && c.currentScreen!=null){
                c.options.pauseOnLostFocus=false;
                c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                phase[0]=1;
                if(Files.exists(Path.of("saves/hud-070-visual/level.dat")))c.createIntegratedServerLoader().start("hud-070-visual",()->{});
                else c.createIntegratedServerLoader().createAndStart("hud-070-visual",
                    new LevelInfo("HUD 0.7 visual test",GameMode.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES),DataConfiguration.SAFE_MODE),
                    new GeneratorOptions(42L,false,false),
                    r->r.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createDimensionsRegistryHolder(),new TitleScreen());
                return;
            }
            if(c.player==null || c.world==null || c.getServer()==null)return;
            if(phase[0]==12 && !c.isWindowFocused()){
                GLFW.glfwFocusWindow(c.getWindow().getHandle());return;
            }
            if(phase[0]==1){
                if(c.player.getHealth()<=0){c.player.requestRespawn();return;}
                if(c.currentScreen!=null)return;
                c.getServer().execute(()->{
                    c.getServer().setDifficulty(Difficulty.NORMAL,true);
                    var p=c.getServer().getPlayerManager().getPlayer(c.player.getUuid());
                    if(p==null)throw new IllegalStateException("Missing local player");
                    p.getAbilities().invulnerable=true;p.sendAbilitiesUpdate();
                    p.setHealth(16);p.getHungerManager().setFoodLevel(14);
                    p.addExperience(250);
                    p.getInventory().setStack(0,new ItemStack(Items.BLAZE_ROD));
                    p.getInventory().setStack(1,new ItemStack(Items.DIAMOND_PICKAXE));
                    p.getInventory().setStack(2,new ItemStack(Items.BREAD,8));
                    p.getInventory().setStack(3,new ItemStack(Items.BOOK));
                    p.getInventory().setStack(4,new ItemStack(Items.TORCH,32));
                    p.getInventory().setStack(40,new ItemStack(Items.SHIELD));
                    p.equipStack(EquipmentSlot.HEAD,new ItemStack(Items.LEATHER_HELMET));
                    p.equipStack(EquipmentSlot.CHEST,new ItemStack(Items.LEATHER_CHESTPLATE));
                });
                c.player.setPitch(8);c.player.setYaw(30);
                phase[0]=2;ticks[0]=0;PlayerHudClient.frame(1);return;
            }
            if(++ticks[0]<45)return;ticks[0]=0;
            try{
                PlayerHudClient.verifyRenderer();
                Files.createDirectories(Path.of("visual-check/hud-072-horizontal"));
                try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){
                    image.writeTo(Path.of("visual-check/hud-072-horizontal/phase-"+phase[0]+".png"));
                }
                if(phase[0]>=2 && phase[0]<10){
                    PlayerHudClient.frame(phase[0]);phase[0]++;
                }else if(phase[0]==10){
                    GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1920,1080);
                    c.options.getGuiScale().setValue(3);c.onResolutionChanged();phase[0]=11;
                }else if(phase[0]==11){
                    c.options.getGuiScale().setValue(4);c.onResolutionChanged();phase[0]=12;
                }else if(phase[0]==12){
                    var dispatcher=net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
                    var source=(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)c.getNetworkHandler().getCommandSource();
                    dispatcher.execute("화면표시 테두리 5",source);
                    if(PlayerHudClient.frame()!=5)throw new IllegalStateException("Frame command failed");
                    try{dispatcher.execute("화면표시 테두리 10",source);throw new IllegalStateException("Accepted rank10");}
                    catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){}
                    dispatcher.execute("화면표시 끄기",source);
                    if(PlayerHudClient.active())throw new IllegalStateException("HUD off failed");
                    dispatcher.execute("화면표시 켜기",source);dispatcher.execute("화면표시 테두리 9",source);
                    float yaw=c.player.getYaw(),pitch=c.player.getPitch();
                    if(!PlayerHudClient.handleCursorKey(c,GLFW.GLFW_KEY_LEFT_CONTROL,GLFW.GLFW_PRESS)
                        || !(c.currentScreen instanceof HudCursorScreen) || c.mouse.isCursorLocked())
                        throw new IllegalStateException("Ctrl did not unlock cursor");
                    c.currentScreen.mouseClicked(20,20,0);
                    if(c.options.attackKey.isPressed())throw new IllegalStateException("Cursor click attacked");
                    if(c.player.getYaw()!=yaw || c.player.getPitch()!=pitch)throw new IllegalStateException("HUD modified player aim");
                    phase[0]=13;
                }else if(phase[0]==13){
                    if(c.currentScreen instanceof HudCursorScreen)throw new IllegalStateException("Released Ctrl stuck open");
                    // Minecraft intentionally keeps the cursor released while another app has focus.
                    // Closing the Alt screen must not seize that other app's pointer.
                    if(c.isWindowFocused() && c.currentScreen==null && !c.mouse.isCursorLocked())throw new IllegalStateException("Cursor did not relock while focused");
                    System.out.println("HUD_CURSOR_RELEASE: focused="+c.isWindowFocused()+", locked="+c.mouse.isCursorLocked()+", screen="+(c.currentScreen==null?"none":c.currentScreen.getClass().getSimpleName()));
                    if(c.player.experienceLevel<=0 || c.player.getArmor()<=0)throw new IllegalStateException("Missing XP/armor fixture");
                    net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher().execute("화면표시 끄기",
                        (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)c.getNetworkHandler().getCommandSource());
                    phase[0]=14;
                }else if(phase[0]==14){
                    System.out.println("PLAYER_HUD_VISUAL_OK: all 9 frames in real world; 1280 GUI2 and 1920 GUI3/4; equipped player; Alt unlock/click protection/release with focus-aware relock; GPU trilinear filters; command rank bounds and on/off.");
                    c.scheduleStop();phase[0]=15;
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        });
    }
}



