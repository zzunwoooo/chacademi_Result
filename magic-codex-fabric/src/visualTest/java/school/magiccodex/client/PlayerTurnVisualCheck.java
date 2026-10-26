package school.magiccodex.client;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.DialogueProtocol;
import school.magiccodex.npctalk.NpcTalkProtocol;

/** Synthetic non-private portrait; real two-screen rendering and turn transitions. Not shipped. */
public final class PlayerTurnVisualCheck implements net.fabricmc.api.ClientModInitializer {
    @Override public void onInitializeClient(){register();}
    private static Object field(Object target,String name)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
        var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);
    }
    private static void set(String name,Object value)throws Exception {
        var f=PortraitClient.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    public static void register(){
        int[] step={0},ticks={0};PlayerPortraitTexture[] texture={null};
        ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null||c.currentScreen==null)return;
            try {
                Path out=Path.of("visual-check/player-turn");Files.createDirectories(out);
                if(ticks[0]==0){
                    int mode=step[0]/4;boolean padded=(step[0]%4)>=2,ai=(step[0]%2)==1;
                    c.options.pauseOnLostFocus=false;
                    if(c.getWindow().isFullscreen()!=(mode==2))c.getWindow().toggleFullscreen();
                    if(mode!=2)GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1280,720);
                    c.options.getGuiScale().setValue(mode==1?3:2);c.onResolutionChanged();
                    if(texture[0]!=null)texture[0].close();
                    int w=padded?1024:512,h=padded?1536:768,ox=padded?200:0,oy=padded?300:0;
                    var image=new NativeImage(w,h,false);
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                        int px=x-ox,py=y-oy;
                        int color=px<0||px>=512||py<0||py>=768?0:
                            py<240?0xFFEFBD81:py<660?0xFF63AACD:0xFFB664B9;
                        image.setColorArgb(x,y,color);
                    }
                    var bounds=PlayerTurnPortraitLayout.bounds(w,h,(x,y)->image.getColorArgb(x,y)>>>24);
                    texture[0]=PlayerPortraitTexture.upload(image,bounds);set("texture",texture[0]);set("ready",true);set("confirmed",true);
                    String session=UUID.randomUUID().toString();Screen screen;
                    if(ai)screen=new NpcTalkScreen(new NpcTalkProtocol.Response(NpcTalkProtocol.S_OPEN,session,0,"엘라","elena-neutral","NPC turn",List.of(),0,false,""),null);
                    else screen=new DialogueScreen(new DialogueProtocol.Response(session,0,false,true,"Player layout check","엘라","elena-neutral","NPC turn",List.of(new DialogueProtocol.Choice("c0","Hello"),new DialogueProtocol.Choice("c1","Next")),""),null);
                    c.setScreen(screen);
                    call(screen,"beginTurn",new Class<?>[]{String.class},ai?"Hello":"c0");
                    if(field(screen,"turn")==null)throw new AssertionError("turn did not start");
                }
                if(++ticks[0]<25)return;
                try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve("case-"+step[0]+".png"));}
                Screen screen=c.currentScreen;String advance=screen instanceof DialogueScreen?"advance":"advanceText";
                call(screen,advance,new Class<?>[0]);
                if(field(screen,"turn")!=null)call(screen,advance,new Class<?>[0]);
                if(field(screen,"turn")!=null)throw new AssertionError("turn did not finish");
                Files.writeString(out.resolve("results.txt"),"PASS case="+step[0]+" framebuffer="+c.getFramebuffer().textureWidth+"x"+c.getFramebuffer().textureHeight+" gui="+c.options.getGuiScale().getValue()+" fullscreen="+c.getWindow().isFullscreen()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                ticks[0]=0;
                if(++step[0]==12){Files.writeString(out.resolve("done.txt"),"PLAYER_TURN_RENDER_PASS 12 cases; two screens, padding, GUI 2/3, window/fullscreen, turn entry/exit");c.scheduleStop();}
            } catch(Throwable e){e.printStackTrace();try{Files.writeString(Path.of("visual-check/player-turn/failed.txt"),e.toString());}catch(Exception ignored){}c.scheduleStop();}
        });
    }
}
