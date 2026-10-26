package school.magiccodex.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.resource.*;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import org.lwjgl.glfw.GLFW;

public final class PlayerHudClient {
    private static boolean enabled=true;
    private static int frame=1;
    private static PlayerHudRenderer renderer;
    private PlayerHudClient(){}
    public static int frame(){return frame;}
    public static void frame(int value){
        if(value<1 || value>9)throw new IllegalArgumentException("Circle must be 1..9");
        frame=value;
    }
    public static boolean active(){
        var c=MinecraftClient.getInstance();
        return enabled && c.player!=null && c.world!=null && !c.player.isSpectator()
                && c.getCameraEntity()==c.player;
    }
    /** Read outside the HUD lambda (chat-layout's optional redirect targets that lambda; chat is now handled here). */
    private static net.minecraft.client.gui.screen.Screen screenNow(){return MinecraftClient.getInstance().currentScreen;}
    /** HUD cursor key (키 설정 "마우스 포인터", default left Ctrl; owner 2026-10-17). Held = cursor screen (pings, HUD drag, card clicks). */
    public static boolean handleCursorKey(MinecraftClient c,int key,int action){
        int cursor=UiKeys.code(UiKeys.Action.CURSOR);
        if(cursor<0 || key!=cursor)return false;
        if(c.currentScreen instanceof HudCursorScreen)return true;
        if(action==GLFW.GLFW_PRESS && active() && c.currentScreen==null && c.getOverlay()==null
                && c.isWindowFocused() && c.player.isAlive()){
            c.setScreen(new HudCursorScreen());
            return true;
        }
        return false;
    }
    public static void initialize(){
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->{
            dispatcher.register(ClientCommandManager.literal("화면표시")
                .executes(ctx->{ctx.getSource().sendFeedback(Text.literal("/화면표시 테두리 1~9 · 켜기 · 끄기 · 파티 · 온도효과"));return 1;})
                .then(TemperatureClient.command())
                .then(PartyHudClient.command())
                .then(ClientCommandManager.literal("테두리").then(ClientCommandManager.argument("번호",IntegerArgumentType.integer(1,9))
                    .executes(ctx->{frame(IntegerArgumentType.getInteger(ctx,"번호"));ctx.getSource().sendFeedback(Text.literal("HUD 테두리: "+frame+"번"));return 1;})))
                .then(ClientCommandManager.literal("켜기").executes(ctx->{enabled=true;return 1;}))
                .then(ClientCommandManager.literal("끄기").executes(ctx->{
                    enabled=false;var c=MinecraftClient.getInstance();
                    if(c.currentScreen instanceof HudCursorScreen)c.setScreen(null);
                    close();return 1;
                })));
        });
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.currentScreen instanceof HudCursorScreen){
                long window=c.getWindow().getHandle();
                int cursor=UiKeys.code(UiKeys.Action.CURSOR);
                boolean held=cursor>=0 && GLFW.glfwGetKey(window,cursor)==GLFW.GLFW_PRESS;
                if(!held || !active() || !c.isWindowFocused() || !c.player.isAlive())c.setScreen(null);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,c)->{close();TopMenuClient.reset();frame=1;});
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){
            public Identifier getFabricId(){return Identifier.of("magiccodex","player_hud");}
            public void reload(ResourceManager manager){close();}
        });
        HudLayerRegistrationCallback.EVENT.register(drawer->drawer.attachLayerAfter(IdentifiedLayer.SUBTITLES,Identifier.of("magiccodex","player_ui"),(context,ticks)->{
            var c=MinecraftClient.getInstance();
            if(!active() || c.options.hudHidden || !HudPointer.hudVisibleUnder(screenNow()))return;
            renderer().renderStatus(context,c.player,frame,(float)ManaClient.current(),(float)ManaClient.maximum(),HudPointer.active(),Util.getMeasuringTimeMs());
            TopMenuClient.render(context,Util.getMeasuringTimeMs());
            if(!(screenNow() instanceof net.minecraft.client.gui.screen.ChatScreen))TopMenuClient.renderTooltip(context);
        }));
    }
    /** Server preview (/테스트 화면 계절): spring|summer|autumn|winter or a season id/label. Returns false when unknown. */
    static boolean previewSeason(String value){
        for(var season:HudSeason.values()){
            if(season.id().equalsIgnoreCase(value)||season.label().equals(value)||season.name().equalsIgnoreCase(value)){TopMenuClient.season(season);return true;}
        }
        return false;
    }
    private static PlayerHudRenderer renderer(){
        if(renderer==null)renderer=new PlayerHudRenderer(MinecraftClient.getInstance());
        return renderer;
    }
    public static void hotbar(DrawContext context){renderer().renderHotbar(context,MinecraftClient.getInstance().player);}
    public static void verifyRenderer(){if(renderer!=null)renderer.verifyGpuState();TopMenuClient.verify();}
    private static void close(){if(renderer!=null){renderer.close();renderer=null;}TopMenuClient.close();}
}
