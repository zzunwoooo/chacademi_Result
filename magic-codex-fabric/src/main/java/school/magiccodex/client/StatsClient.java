package school.magiccodex.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

/** Reuses existing mana/permission subscriptions. No additional polling or server requests. */
public final class StatsClient {
    private static KeyBinding key;
    private static final CodexShortcut shortcut=new CodexShortcut();
    private static boolean pending;
    public record Additional(Double power,Double popularity,String dormitory,Integer circle){}
    private static Additional additional=new Additional(null,null,null,null);
    private StatsClient(){}
    /** Future server receiver may supply missing fields on the Minecraft client thread. */
    public static void updateAdditional(Additional value){
        if(!MinecraftClient.getInstance().isOnThread())throw new IllegalStateException("Use client thread");
        additional=value==null?new Additional(null,null,null,null):value;
    }
    public static StatsValues values(){
        var p=MinecraftClient.getInstance().player;
        var spells=CodexCatalog.spells();
        boolean known=PermissionClient.catalogConfirmed();
        return new StatsValues(p==null?"":SchoolClient.nickname(p.getName().getString()),
                AscensionClient.circle()>0?AscensionClient.circle():additional.circle()==null?PlayerHudClient.frame():additional.circle(),SchoolClient.dormitory(additional.dormitory()),
                EquipmentClient.power(additional.power()),p==null?null:(double)p.getMaxHealth(),
                ManaClient.available()?ManaClient.current():null,ManaClient.available()?ManaClient.maximum():null,
                ManaClient.available()?ManaClient.regeneration():null,p==null?null:(double)p.getArmor(),
                known?(int)spells.stream().filter(CodexData.Spell::discovered).count():null,spells.size(),additional.popularity(),ManaClient.available()?ManaClient.haste():null);
    }
    static KeyBinding binding(){return key;}
    public static boolean matches(int code,int scan){return key!=null && key.matchesKey(code,scan);}
    public static String keyLabel(){return key==null?"P":key.getBoundKeyLocalizedText().getString();}
    public static boolean handleKey(MinecraftClient c,int code,int scan,int action){
        boolean ready=c.world!=null && c.player!=null && c.player.isAlive()
                && (c.currentScreen==null || c.currentScreen instanceof StatsScreen)
                && c.getOverlay()==null && c.isWindowFocused();
        var result=shortcut.handle(code,action,matches(code,scan),ready);
        if(result==CodexShortcut.Result.OPEN){
            if(c.currentScreen instanceof StatsScreen screen)screen.close();
            else pending=true;
        }
        return result!=CodexShortcut.Result.PASS;
    }
    private static void reset(){shortcut.reset();pending=false;additional=new Additional(null,null,null,null);StatsSocialActions.reset();}
    public static void initialize(){
        key=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.magiccodex.stats",GLFW.GLFW_KEY_I,"category.magiccodex"));
        ClientPlayConnectionEvents.JOIN.register((handler,sender,c)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            while(key.wasPressed()){}
            if(!c.isWindowFocused()){shortcut.reset();pending=false;}
            if(c.world==null || c.player==null){reset();return;}
            if(pending){
                pending=false;
                if(c.currentScreen==null && c.getOverlay()==null && c.player.isAlive()){
                    MagicCodexClient.dismiss();
                    c.setScreen(new StatsScreen());
                }
            }
        });
    }
}
