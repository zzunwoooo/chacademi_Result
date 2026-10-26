package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.client.*;

/** Check the existing badge bounds with an actual catalog spell; never edits the spell files. */
final class CircleVisualCheck {
    static void register(){
        final String source;
        try{source=Files.readString(Path.of("../../output/all-spells-v3/chacademia-spells/config/magiccodex/spells/wind_basket.yml"));}
        catch(Exception error){throw new IllegalStateException(error);}
        var loader=new SpellYamlLoader();
        var spells=List.of(loader.parse(source).withPermission(true),
            loader.parse(source+"\ncircle: 1\n").withPermission(true),
            loader.parse(source+"\ncircle: 9\n").withPermission(true));
        int[] phase={0},ticks={0};long start=System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(System.currentTimeMillis()-start>180000)throw new IllegalStateException("Circle visual timeout");
            if(client.getOverlay()!=null)return;
            if(phase[0]==0 && client.currentScreen!=null){
                client.options.getGuiScale().setValue(2);client.onResolutionChanged();
                show(client,spells.getFirst());phase[0]=1;ticks[0]=0;return;
            }
            if(!(client.currentScreen instanceof CodexScreen screen) || ++ticks[0]<35)return;
            ticks[0]=0;
            try{
                screen.verifyImageRenderer();
                Path directory=Path.of("visual-check/circle-082");Files.createDirectories(directory);
                String name=switch(phase[0]){case 1->"unspecified";case 2->"circle-1";default->"circle-9-gui3";};
                try(var shot=ScreenshotRecorder.takeScreenshot(client.getFramebuffer())){shot.writeTo(directory.resolve(name+".png"));}
                if(phase[0]<3){
                    if(phase[0]==2){client.options.getGuiScale().setValue(3);client.onResolutionChanged();}
                    show(client,spells.get(phase[0]));phase[0]++;
                }else{
                    System.out.println("CIRCLE_VISUAL_OK: actual YAML spell displayed with unspecified, 1 and 9 circle badges; GUI2/3; original textures verified.");
                    client.scheduleStop();
                }
            }catch(Exception error){throw new IllegalStateException(error);}
        });
    }
    private static void show(MinecraftClient client,CodexData.Spell spell){
        var screen=new CodexScreen(List.of(spell));client.setScreen(screen);screen.state().selectSlot(0);
    }
}
