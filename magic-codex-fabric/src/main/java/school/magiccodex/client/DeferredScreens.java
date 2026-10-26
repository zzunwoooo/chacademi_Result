package school.magiccodex.client;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import java.util.function.Supplier;

final class DeferredScreens {
    private static final NextTickAction queue=new NextTickAction();
    private static boolean initialized;
    static void initialize(){
        if(initialized)return;initialized=true;
        ClientTickEvents.START_CLIENT_TICK.register(c->queue.advance());
        ClientTickEvents.END_CLIENT_TICK.register(c->queue.drain(c.getNetworkHandler(),c.world));
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->queue.cancel());
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->queue.cancel());
    }
    static void open(Supplier<Screen> screen){open(screen,false);}
    static void openWhenIdle(Supplier<Screen> screen){open(screen,true);}
    private static void open(Supplier<Screen> screen,boolean onlyWhenIdle){
        var c=MinecraftClient.getInstance();
        if(c.player==null||c.world==null||c.getNetworkHandler()==null)return;
        queue.schedule(c.getNetworkHandler(),c.world,()->{
            if(c.player==null||!c.player.isAlive()||(onlyWhenIdle&&c.currentScreen!=null))return;
            MagicCodexClient.dismiss();c.setScreen(screen.get());
        });
    }
}