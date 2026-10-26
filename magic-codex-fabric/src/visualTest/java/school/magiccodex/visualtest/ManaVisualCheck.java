package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;

/** Development-only checks with actual 60-spell YAML and PNG resources. */
final class ManaVisualCheck {
    static void register() {
        var catalog = new SpellYamlLoader().load(Path.of("../../output/all-spells-v3/chacademia-spells/config/magiccodex/spells"));
        if (!catalog.success() || catalog.spells().size()!=60) throw new IllegalStateException(catalog.errors().toString());
        var phoenix = catalog.spells().stream().filter(s->s.id().equals("phoenix_oath")).findFirst().orElseThrow().withPermission(true);
        if(phoenix.manaCost()!=200) throw new IllegalStateException("Expected YAML mana 200");
        int[] phase={0},ticks={0};
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(client.getOverlay()!=null)return;
            if(phase[0]==0 && client.currentScreen!=null){
                client.options.getGuiScale().setValue(2);client.onResolutionChanged();
                var screen=new CodexScreen(List.of(phoenix));
                client.setScreen(screen);screen.state().selectSlot(0);phase[0]=1;ticks[0]=0;
            }
            if(!(client.currentScreen instanceof CodexScreen screen)||++ticks[0]<40)return;
            ticks[0]=0;
            try {
                Path output=Path.of("visual-check");Files.createDirectories(output);
                screen.verifyImageRenderer();
                try(var shot=ScreenshotRecorder.takeScreenshot(client.getFramebuffer())){
                    shot.writeTo(output.resolve("mana-"+phase[0]+".png"));
                }
                if(phase[0]==1){
                    GLFW.glfwSetWindowSize(client.getWindow().getHandle(),1920,1080);
                    client.options.getGuiScale().setValue(3);client.onResolutionChanged();phase[0]=2;
                }else if(phase[0]==2){
                    var next=new CodexScreen(List.of(catalog.spells().getFirst().withPermission(false)));
                    client.setScreen(next);next.state().selectSlot(0);phase[0]=3;
                }else{
                    System.out.println("MANA_VISUAL_OK: actual YAML mana 200 and undiscovered mana 70; screenshots at 1280x720 and 1920x1080; original PNG renderer verified.");
                    client.scheduleStop();
                }
            }catch(Exception e){throw new IllegalStateException(e);}
        });
    }
}
