package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Identifier;
import school.magiccodex.client.*;

/** Real Paper-compatible server on localhost; no fake temperature snapshots. Never in release jar. */
final class TemperatureVisualCheck {
    static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>240000)throw new IllegalStateException("Temperature check timed out: "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                if(!Files.exists(Path.of("../../output/temperature-hud-v1/server-test/ready.txt")))return;
                System.out.println("TEMPERATURE_CONNECT_FROM "+c.currentScreen.getClass().getSimpleName());
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                var address=ServerAddress.parse("127.0.0.1:25595");
                ConnectScreen.connect(c.currentScreen,c,address,new ServerInfo("Temperature isolated test","127.0.0.1:25595",ServerInfo.ServerType.OTHER),false,null);
                phase[0]=-1;return;
            }
            if(c.player==null||c.world==null||c.currentScreen!=null)return;
            if(phase[0]==-1){
                if(!Float.isFinite(TemperatureClient.current()))return;
                check(TemperatureClient.current()==18,"Default server temperature");
                c.player.setYaw(30);c.player.setPitch(8);c.options.hudHidden=false;
                local(c,"화면표시 켜기");school.magiccodex.client.PreviewClient.show(java.util.List.of("season","summer"));phase[0]=0;ticks[0]=0;return;
            }
            if(++ticks[0]<100)return;ticks[0]=0;
            try{
                c.inGameHud.getChatHud().clear(false);PlayerHudClient.verifyRenderer();
                switch(phase[0]++){
                    case 0->{check(TemperatureClient.current()==18,"Default held");shot(c,"01-normal-18");server(c,"codextemp set CodexPreview 45");}
                    case 1->{check(TemperatureClient.current()==45,"Hot server push");shot(c,"02-hot-45");server(c,"codextemp set CodexPreview -15");school.magiccodex.client.PreviewClient.show(java.util.List.of("season","winter"));}
                    case 2->{check(TemperatureClient.current()==-15,"Cold server push");shot(c,"03-cold-minus15");server(c,"codextemp reset CodexPreview");}
                    case 3->{check(TemperatureClient.current()==18,"Reset server push");shot(c,"04-normal-again");school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","40"));server(c,"codextemp set CodexPreview 22");}
                    case 4->{check(TemperatureClient.current()==40&&TemperatureClient.previewing(),"Preview isolated");shot(c,"05-local-preview");school.magiccodex.client.PreviewClient.show(java.util.List.of("temperature","auto"));}
                    case 5->{check(TemperatureClient.current()==22&&!TemperatureClient.previewing(),"Auto returns latest server value");local(c,"화면표시 온도효과 끄기");server(c,"codextemp set CodexPreview 45");}
                    case 6->{check(TemperatureClient.current()==45&&!TemperatureClient.effects(),"Effects off preserves temperature");shot(c,"06-effects-off-hot");local(c,"화면표시 온도효과 켜기");c.options.hudHidden=true;}
                    case 7->{shot(c,"07-F1-no-overlay");c.options.hudHidden=false;c.options.getGuiScale().setValue(3);c.onResolutionChanged();server(c,"codextemp set CodexPreview -100");expand(c);}
                    case 8->{check(TemperatureClient.current()==-100,"Negative label fits");shot(c,"08-expanded-minus100-gui3");server(c,"codextemp reset CodexPreview");server(c,"deop CodexPreview");}
                    case 9->{check(TemperatureClient.current()==18,"Reset before permission test");server(c,"codextemp set CodexPreview 45");}
                    case 10->{
                        check(TemperatureClient.current()==18,"Unauthorized client command changed temperature");
                        String result="TEMPERATURE_E2E_OK: real Paper bridge packets; set/reset/default; hot/cold/normal; preview/auto; VFX off/F1; GUI2/3; negative label; non-op denial";
                        Files.writeString(Path.of("visual-check/temperature-0190/done.txt"),result);
                        System.out.println(result);c.scheduleStop();
                    }
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void check(boolean good,String label){if(!good)throw new IllegalStateException(label+": "+TemperatureClient.label());}
    private static void local(MinecraftClient c,String text){
        try{ClientCommandManager.getActiveDispatcher().execute(text,(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());c.inGameHud.getChatHud().clear(false);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void server(MinecraftClient c,String text){c.getNetworkHandler().sendChatCommand(text);}
    private static void expand(MinecraftClient c){
        try(var type=new CodexTypography(c)){
            float base=TopMenuState.baseWidth(type.width(WalletClient.label(),12,Identifier.of("magiccodex","hud_bold")));
            float s=PlayerHudLayout.of(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()).scale();
            c.setScreen(new HudCursorScreen());check(c.currentScreen.mouseClicked((6+base-20)*s,21*s,0),"Shifted menu hit");c.setScreen(null);
        }
    }
    private static void shot(MinecraftClient c,String name)throws Exception{
        Path out=Path.of("visual-check/temperature-0190");Files.createDirectories(out);
        try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve(name+".png"));}
    }
}
