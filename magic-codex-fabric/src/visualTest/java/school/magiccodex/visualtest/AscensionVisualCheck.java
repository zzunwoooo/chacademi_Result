package school.magiccodex.visualtest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.client.AscensionScreen;

/** Actual game renderer, including asynchronous texture upload and GUI scale changes. */
final class AscensionVisualCheck {
    static void register(){
        int[] phase={0},ticks={0};long start=System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>180000)throw new IllegalStateException("Ascension visual timeout");
            if(c.getOverlay()!=null)return;
            if(phase[0]==0&&c.currentScreen!=null){
                c.options.getGuiScale().setValue(2);c.onResolutionChanged();
                var screen=AscensionScreen.preview(5);screen.visualTime(0);c.setScreen(screen);phase[0]=1;return;
            }
            if(!(c.currentScreen instanceof AscensionScreen s)||!s.ready()||++ticks[0]<24)return;ticks[0]=0;
            try{
                s.verifyRenderer();Path dir=Path.of("visual-check/ascension-0180");Files.createDirectories(dir);
                try(var shot=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){shot.writeTo(dir.resolve("phase-"+phase[0]+".png"));}
                switch(phase[0]++){
                    case 1->s.visualTime(2.6);
                    case 2->s.visualTime(3.4);
                    case 3->s.visualTime(6);
                    case 4->{c.options.getGuiScale().setValue(3);c.onResolutionChanged();var high=AscensionScreen.preview(9);high.visualTime(7);c.setScreen(high);}
                    default->{System.out.println("ASCENSION_VISUAL_OK: five stages, circles 5/9, GUI 2/3, real filtered PNG rendering.");c.scheduleStop();}
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        });
    }
}
