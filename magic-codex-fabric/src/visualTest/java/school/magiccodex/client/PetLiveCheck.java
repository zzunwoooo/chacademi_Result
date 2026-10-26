package school.magiccodex.client;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.PetProtocol;

/** Isolated live 1.21.4 server test; never included in the release mod. */
public final class PetLiveCheck {
    public static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>300000)throw new IllegalStateException("Pet live test timeout phase="+phase[0]+" status="+PetClient.status());
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                if(!Files.exists(Path.of("../../output/pet-codex-0200/server-test/ready.txt")))return;
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                var address=ServerAddress.parse("127.0.0.1:25595");
                ConnectScreen.connect(c.currentScreen,c,address,new ServerInfo("Pet isolated test","127.0.0.1:25595",ServerInfo.ServerType.OTHER),false,null);
                phase[0]=-1;return;
            }
            if(c.player==null||c.world==null)return;
            if(phase[0]==-1&&c.currentScreen==null){PetClient.open();phase[0]=0;ticks[0]=0;return;}
            if(!(c.currentScreen instanceof PetScreen s))return;
            if(++ticks[0]<85)return;ticks[0]=0;
            try{
                Path out=Path.of("visual-check/pet-0200");Files.createDirectories(out);
                switch(phase[0]++){
                    case 0->{
                        check(PetClient.STATE.visible().size()==3,"MCPets catalog, status="+PetClient.status());
                        check(PetClient.STATE.visible().stream().anyMatch(e->e.id().equals("codex_fox")&&e.stars()==3),"MCPets grade config");
                        check(PetClient.STATE.visible().stream().anyMatch(e->e.id().equals("codex_cat")&&e.stars()==1),"Second pet");
                        shot(c,"live-catalog");
                        PetClient.STATE.filter(3);
                    }
                    case 1->{check(PetClient.STATE.visible().size()==1&&PetClient.STATE.selected().id().equals("codex_fox"),"Filter");shot(c,"live-grade-three");PetClient.reload();}
                    case 2->{
                        var e=PetClient.STATE.selected();check(e!=null&&e.owned(),"Granted permission");
                        check(PetClient.request(PetProtocol.SUMMON,"codex_fox",true),"Summon sent");
                    }
                    case 3->{check(PetClient.STATE.selected().active(),"MCPets spawn status="+PetClient.notice());shot(c,"live-summoned");check(PetClient.request(PetProtocol.DISMISS,"codex_fox",true),"Dismiss sent");}
                    case 4->{check(!PetClient.STATE.selected().active(),"MCPets dismiss");shot(c,"live-dismissed");
                        String result="PET_LIVE_OK: MCPets 4.1.11 catalog, stars, permissions, summon, dismiss over actual mod/plugin packets";
                        Files.writeString(out.resolve("live-done.txt"),result);System.out.println(result);c.scheduleStop();
                    }
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
    private static void check(boolean okay,String label){if(!okay)throw new IllegalStateException(label);}
    private static void shot(MinecraftClient c,String name)throws Exception{Path out=Path.of("visual-check/pet-0200");Files.createDirectories(out);try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve(name+".png"));}}
}
