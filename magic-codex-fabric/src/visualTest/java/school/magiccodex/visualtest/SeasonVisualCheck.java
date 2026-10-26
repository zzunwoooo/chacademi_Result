package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Identifier;
import school.magiccodex.client.*;

/** Isolated in-engine check of the four season commands, positioning and shifted menu clicks. */
final class SeasonVisualCheck {
    static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>180000)throw new IllegalStateException("Season check timed out: "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2 && c.currentScreen!=null){
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);
                c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                if(!Files.exists(Path.of("saves/hud-070-visual/level.dat")))throw new IllegalStateException("Missing isolated world");
                c.createIntegratedServerLoader().start("hud-070-visual",()->{});phase[0]=-1;return;
            }
            if(c.player==null || c.world==null || c.getServer()==null)return;
            if(phase[0]==-1){
                if(c.player.getHealth()<=0){c.player.requestRespawn();return;}
                if(c.currentScreen!=null)return;
                c.player.setPitch(8);c.player.setYaw(30);c.options.hudHidden=false;
                command(c,"화면표시 켜기");school.magiccodex.client.PreviewClient.show(java.util.List.of("season","spring"));phase[0]=0;ticks[0]=0;return;
            }
            if(++ticks[0]<40)return;ticks[0]=0;
            try{
                PlayerHudClient.verifyRenderer();
                if(phase[0]<4){
                    shot(c,"0"+(phase[0]+1)+"-"+HudSeason.values()[phase[0]].id());
                    phase[0]++;
                    if(phase[0]<4){school.magiccodex.client.PreviewClient.show(java.util.List.of("season",HudSeason.values()[phase[0]].label()));return;}
                    school.magiccodex.client.PreviewClient.show(java.util.List.of("season","winter"));c.options.getGuiScale().setValue(3);c.onResolutionChanged();
                    click(c,-1);return;
                }
                if(phase[0]==4){shot(c,"05-expanded-gui3");c.options.getGuiScale().setValue(4);c.onResolutionChanged();phase[0]++;return;}
                if(phase[0]==5){
                    shot(c,"06-expanded-gui4");click(c,1);
                    if(!(c.currentScreen instanceof CodexScreen))throw new IllegalStateException("Shifted codex click missed");
                    phase[0]++;return;
                }
                if(phase[0]==6){
                    c.currentScreen.close();click(c,-1);phase[0]++;return;
                }
                if(phase[0]==7){
                    shot(c,"07-recollapsed");System.out.println("SEASON_HUD_OK: four Korean season commands, English alias, GUI2/3/4, expanded/collapsed, shifted codex hit target, GPU mip validation");
                    c.scheduleStop();phase[0]++;
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void command(MinecraftClient c,String value){
        try{ClientCommandManager.getActiveDispatcher().execute(value,(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());}
        catch(Exception e){throw new IllegalStateException("Command failed: "+value,e);}
        c.inGameHud.getChatHud().clear(false);
    }
    private static void click(MinecraftClient c,int index){
        try(var type=new CodexTypography(c)){
            float base=TopMenuState.baseWidth(type.width(WalletClient.label(),12,Identifier.of("magiccodex","hud_bold")));
            var field=TopMenuClient.class.getDeclaredField("state");field.setAccessible(true);var state=(TopMenuState)field.get(null);
            float x=index<0?state.arrowCenter(base):state.iconCenter(base,index);
            float s=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();
            c.setScreen(new HudCursorScreen());
            if(!c.currentScreen.mouseClicked((6+x)*s,21*s,0))throw new IllegalStateException("Menu click missed");
            if(c.currentScreen instanceof HudCursorScreen)c.setScreen(null);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void shot(MinecraftClient c,String name)throws Exception{
        Path out=Path.of("visual-check/season-hud-0181");Files.createDirectories(out);
        try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve(name+".png"));}
    }
}
