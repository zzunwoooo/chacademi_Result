package school.magiccodex.client;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.*;

/** Isolated renderer check, excluded from the shipped mod. */
public final class DialogueVisualCheck {
    public static void register(){int[] ticks={0},phase={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
        if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();c.setScreen(new DialogueScreen(new DialogueProtocol.Response(UUID.randomUUID().toString(),0,false,true,"잊힌 마법의 첫 번째 단서","사서 엘레나","elena-neutral","이 문양… 오래전에 사라진 대마법사의 인장이군요.\n당신은 이 책을 어디에서 발견했나요?",List.of(new DialogueProtocol.Choice("c0","숲속 유적에서 발견했어요."),new DialogueProtocol.Choice("c1","이 인장에 대해 더 알고 싶어요."),new DialogueProtocol.Choice("c2","아직 말씀드릴 수 없어요.")),""),null));phase[0]=1;ticks[0]=0;}
        if(phase[0]>0&&++ticks[0]==60){Path out=Path.of("visual-check/dialogue-0330");Files.createDirectories(out);try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve("dialogue-"+phase[0]+".png"));}
            if(phase[0]==1){c.options.getGuiScale().setValue(3);c.onResolutionChanged();phase[0]=2;ticks[0]=0;}
            else{Files.writeString(out.resolve("done.txt"),"DIALOGUE_RENDER_PASS: GUI scale 2/3, portrait, name, text, choices");c.scheduleStop();}}
    }catch(Exception e){throw new IllegalStateException(e);}});}
}
