package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.protocol.SocialProtocol.*;

/** Real game rendering, input events and C2S/S2C wire messages; excluded from releases. */
final class SocialVisualCheck {
    static final List<Entry> list=new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile String sent;
    static long warmUploads;static int oldGeneration;
    static java.util.concurrent.CompletableFuture<Void> reload;
    static void register(){int[] phase={0},ticks={0};long started=System.currentTimeMillis();
        UiCacheProbe.register();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(initial->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
            if(System.currentTimeMillis()-started>200000)throw new IllegalStateException("Social test timed out in phase "+phase[0]+" screen="+c.currentScreen);
            if(phase[0]==0){if(c.getOverlay()!=null)return;
                System.out.println("SOCIAL_INITIAL_SCREEN "+c.currentScreen);
                String[] dorms={"아르케온","루미나","베스티아즈","노크세르"};for(int i=0;i<14;i++)list.add(new Entry(UUID.nameUUIDFromBytes(("friend"+i).getBytes()),i==0?"하루":String.format("친구%02d",i),dorms[i%4],i<10));
                ServerPlayNetworking.registerGlobalReceiver(SocialClient.Query.ID,(p,context)->{var r=SocialProtocol.request(p.bytes());Response response=null;
                    if(r.action()==SocialProtocol.LIST)response=new Response(SocialProtocol.SNAPSHOT,r.sequence(),SocialProtocol.NONE,0,"","","",0,list);
                    if(r.action()==SocialProtocol.ADD){list.add(new Entry(UUID.randomUUID(),r.text(),"루미나",true));response=new Response(SocialProtocol.SNAPSHOT,r.sequence(),SocialProtocol.NONE,0,"","","친구를 추가했습니다.",0,list);}
                    if(r.action()==SocialProtocol.REMOVE){list.removeIf(e->e.id().equals(r.target()));response=new Response(SocialProtocol.SNAPSHOT,r.sequence(),SocialProtocol.NONE,0,"","","삭제했습니다.",0,list);}
                    if(r.action()==SocialProtocol.WHISPER){var e=list.stream().filter(f->f.id().equals(r.target())).findFirst().orElseThrow();response=new Response(SocialProtocol.COMPOSE,r.sequence(),e.id(),891,e.name(),e.dormitory(),"cast",1000,List.of());}
                    if(r.action()==SocialProtocol.SEND){check(r.ticket()==891,"Ticket missing");sent=r.text();response=new Response(SocialProtocol.SENT,r.sequence(),r.target(),891,"하루","루미나","전언을 보냈습니다.",0,List.of());}
                    if(response!=null)ServerPlayNetworking.send(context.player(),new SocialClient.Reply(SocialProtocol.encode(response)));
                });
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();
                phase[0]=1;c.createIntegratedServerLoader().start("stats-014-visual",()->{});return;
            }
            if(c.player==null||c.world==null||c.getServer()==null)return;
            if(++ticks[0]<25)return;ticks[0]=0;
            if(phase[0]==1){if(c.currentScreen!=null)return;SocialClient.open();phase[0]=2;}
            else if(phase[0]==2){check(c.currentScreen instanceof FriendsScreen,"Open failed");shot(c,"01-friends-1280");var f=(FriendsScreen)c.currentScreen;var l=SocialLayout.friends(f.width,f.height);f.mouseScrolled(l.x()+500*l.scale(),l.y()+400*l.scale(),0,-5);check(f.scrollOffset()>0,"Wheel scrolling failed");phase[0]=3;}
            else if(phase[0]==3){shot(c,"02-scrolled");var f=(FriendsScreen)c.currentScreen;var l=SocialLayout.friends(f.width,f.height);double x=l.x()+400*l.scale(),y=l.y()+400*l.scale();float before=f.scrollOffset();f.mouseClicked(x,y,0);f.mouseDragged(x,y-80*l.scale(),0,0,-80*l.scale());f.mouseReleased(x,y,0);check(f.scrollOffset()>before,"Dragging failed");clickFriends(c,820,168);phase[0]=4;}
            else if(phase[0]==4){for(char ch:"TestFriend".toCharArray())c.currentScreen.charTyped(ch,0);shot(c,"03-add-dialog");c.currentScreen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);phase[0]=5;}
            else if(phase[0]==5){check(list.stream().anyMatch(e->e.name().equals("TestFriend")),"Add request missing");GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1920,1080);phase[0]=6;}
            else if(phase[0]==6){var f=(FriendsScreen)c.currentScreen;var l=SocialLayout.friends(f.width,f.height);f.mouseScrolled(l.x()+500*l.scale(),l.y()+400*l.scale(),0,100);shot(c,"04-friends-1920");clickFriends(c,750,256);phase[0]=7;}
            else if(phase[0]==7){check(c.currentScreen instanceof WindMessageScreen,"Whisper did not open");for(char ch:"도서관 앞에서 만날까?".toCharArray())c.currentScreen.charTyped(ch,0);phase[0]=8;}
            else if(phase[0]==8){shot(c,"05-wind-message");c.currentScreen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);phase[0]=9;}
            else if(phase[0]==9){check("도서관 앞에서 만날까?".equals(sent),"Korean draft payload mismatch");phase[0]=10;}
            else if(phase[0]==10){check(c.currentScreen==null,"Sent composer did not close");SocialClient.open();phase[0]=11;}
            else if(phase[0]==11){clickFriends(c,902,256);phase[0]=12;}
            else if(phase[0]==12){shot(c,"06-delete-confirm");clickFriends(c,615,502);phase[0]=13;}
            else if(phase[0]==13){check(list.size()==14,"Delete failed");warmUploads=UiResources.images().uploadCount();c.setScreen(new FriendsScreen());phase[0]=14;}
            else if(phase[0]==14){check(UiResources.images().uploadCount()==warmUploads,"Reopening friends reuploaded cached PNGs");shot(c,"07-cached-friends");c.setScreen(new WindMessageScreen(new Response(SocialProtocol.COMPOSE,0,list.getFirst().id(),891,"하루","루미나","",1000,List.of())));phase[0]=15;}
            else if(phase[0]==15){check(UiResources.images().uploadCount()==warmUploads,"Reopening whisper reuploaded cached PNG");for(char ch:"다시 열어도 유지되는 전언".toCharArray())c.currentScreen.charTyped(ch,0);shot(c,"08-cached-whisper");oldGeneration=UiResources.generation();reload=c.reloadResources();phase[0]=16;}
            else if(phase[0]==16){if(!reload.isDone()||c.getOverlay()!=null)return;check(UiResources.generation()>oldGeneration,"Reload did not invalidate cache");phase[0]=17;}
            else if(phase[0]==17){shot(c,"09-reloaded-whisper");var field=WindMessageScreen.class.getDeclaredField("message");field.setAccessible(true);var input=field.get(c.currentScreen);var text=input.getClass().getDeclaredMethod("text");text.setAccessible(true);check(text.invoke(input).equals("다시 열어도 유지되는 전언"),"Reload lost whisper draft");c.setScreen(stats());phase[0]=18;}
            else if(phase[0]==18){shot(c,"10-stats");warmUploads=UiResources.images().uploadCount();c.setScreen(stats());phase[0]=19;}
            else if(phase[0]==19){check(UiResources.images().uploadCount()==warmUploads,"Reopening stats reuploaded panel");shot(c,"11-cached-stats");UiCacheProbe.report();System.out.println("SOCIAL_VISUAL_OK: real payloads, friends/whisper/stats cached reopen, resource reload invalidation, draft retained, 4 dorm colors, 2 resolutions, drag/wheel.");c.scheduleStop();phase[0]=20;}
        }catch(Exception e){throw new IllegalStateException(e);}}));
    }
    private static void clickFriends(MinecraftClient c,int x,int y){var s=c.currentScreen;var f=SocialLayout.friends(s.width,s.height);s.mouseClicked(f.x()+x*f.scale(),f.y()+y*f.scale(),0);}
    private static void check(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private static StatsScreen stats(){return new StatsScreen(()->new StatsValues("CodexPreview",5,"루미나",24.0,20.0,78.0,100.0,5.0,12.0,62,200,10.0),false);}
    private static void shot(MinecraftClient c,String name)throws Exception{if(c.currentScreen instanceof FriendsScreen f)f.verifyRenderer();if(c.currentScreen instanceof WindMessageScreen w)w.verifyRenderer();Path dir=Path.of("visual-check/social-017");Files.createDirectories(dir);try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(dir.resolve(name+".png"));}}
}
