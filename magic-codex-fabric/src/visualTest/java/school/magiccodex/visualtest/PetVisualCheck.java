package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.client.*;

final class PetVisualCheck {
    static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("Pet check timeout");
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.hudHidden=false;
                c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                c.createIntegratedServerLoader().start("hud-070-visual",()->{});phase[0]=-1;return;
            }
            if(c.player==null||c.world==null||c.getServer()==null)return;
            c.player.setYaw(30);c.player.setPitch(8);
            if(phase[0]==-1&&c.currentScreen==null){
                c.getServer().execute(()->c.getServer().getOverworld().setTimeOfDay(6000));
                command(c,"펫도감 preview");phase[0]=0;ticks[0]=0;return;
            }
            if(!(c.currentScreen instanceof PetScreen s))return;
            c.inGameHud.getChatHud().clear(false);if(++ticks[0]<90)return;ticks[0]=0;
            try{
                Path out=Path.of("visual-check/pet-0200");Files.createDirectories(out);s.verifyRenderer();
                String[] names={"all","fox","favorite","grade-two","model","model-rotated","gui-three","missing-model"};
                try(var shot=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){shot.writeTo(out.resolve(names[phase[0]]+".png"));}
                switch(phase[0]++){
                    case 0->click(c,PetScreen.fit(s.width,s.height),1018,380);
                    case 1->click(c,PetScreen.fit(s.width,s.height),891,233);
                    case 2->click(c,PetScreen.fit(s.width,s.height),700,130);
                    case 3->{click(c,PetScreen.fit(s.width,s.height),460,130);command(c,"펫도감 model codex_fixture");}
                    case 4->{var f=PetScreen.fit(s.width,s.height);click(c,f,640,350);s.mouseDragged(f.x()+740*f.scale(),f.y()+350*f.scale(),0,100*f.scale(),0);s.mouseReleased(f.x()+740*f.scale(),f.y()+350*f.scale(),0);}
                    case 5->{c.options.getGuiScale().setValue(3);c.onResolutionChanged();}
                    case 6->command(c,"펫도감 model missing_pet");
                    default->{System.out.println("PET_UI_OK: filters, arrows, favorite, bbmodel textured hierarchy/idle/rotation, missing file, GUI scales 2/3");c.scheduleStop();}
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void click(MinecraftClient c,PetScreen.Fit f,float x,float y){c.currentScreen.mouseClicked(f.x()+x*f.scale(),f.y()+y*f.scale(),0);}
    private static void command(MinecraftClient c,String value){try{ClientCommandManager.getActiveDispatcher().execute(value,(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());}catch(Exception e){throw new IllegalStateException(e);}}
}
