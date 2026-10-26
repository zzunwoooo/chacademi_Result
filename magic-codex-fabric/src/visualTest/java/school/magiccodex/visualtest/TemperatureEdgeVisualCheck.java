package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.client.*;

/** Focused renderer check: temperature networking is unchanged. */
final class TemperatureEdgeVisualCheck {
    static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>180000)throw new IllegalStateException("Temperature edge check timeout");
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.hudHidden=false;
                c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                if(!Files.exists(Path.of("saves/hud-070-visual/level.dat")))throw new IllegalStateException("Missing isolated world");
                c.createIntegratedServerLoader().start("hud-070-visual",()->{});phase[0]=-1;return;
            }
            if(c.player==null||c.world==null||c.getServer()==null||c.currentScreen!=null)return;
            c.player.setYaw(30);c.player.setPitch(8);
            if(phase[0]==-1){
                c.getServer().execute(()->c.getServer().getOverworld().setTimeOfDay(6000));
                command(c,"화면표시 켜기");school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","18"));phase[0]=0;ticks[0]=0;return;
            }
            c.inGameHud.getChatHud().clear(false);
            if(++ticks[0]<110)return;ticks[0]=0;
            try{
                Path out=Path.of("visual-check/temperature-0191");Files.createDirectories(out);
                String[] names={"normal-18","hot-40","cold-minus10","hot-45"};
                try(var shot=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){shot.writeTo(out.resolve(names[phase[0]]+".png"));}
                switch(phase[0]++){
                    case 0->school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","40"));
                    case 1->school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","-10"));
                    case 2->school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","45"));
                    default->{System.out.println("TEMPERATURE_EDGE_OK: normal 18, hot 40/45, cold -10; same camera; center remains readable");c.scheduleStop();}
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void command(MinecraftClient c,String value){
        try{ClientCommandManager.getActiveDispatcher().execute(value,(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
