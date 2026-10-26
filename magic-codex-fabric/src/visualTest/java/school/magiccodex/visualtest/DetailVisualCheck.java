package school.magiccodex.visualtest;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.*;

final class DetailVisualCheck {
    private static long warmUploads;private static CodexScreen parent;
    static void register(){int[] phase={0},ticks={0};long start=System.currentTimeMillis();
        UiCacheProbe.register();
        ClientLifecycleEvents.CLIENT_STARTED.register(initial->ClientTickEvents.END_CLIENT_TICK.register(c->{try{
            if(System.currentTimeMillis()-start>120000)throw new IllegalStateException("Detail visual timeout "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==0){
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();
                var first=new CodexData.Spell("pressure_press","윈드 프레스",CodexData.Category.WIND,
                    "공중에 있는 대상을 낙하시킵니다.",
                    "앵무새를 1회 공격하기.",
                    "날개를 스친 순간 공기가 눌리는 감각이 남았다. 무거워진 바람 속에서는 새도 날개를 펼 수 없었다.",20,true,
                    "magiccodex:textures/spells/expansion_2/pressure_press.png","magic.learned.pressure_press","마법 pressure_press",0,true);
                var longText=new StringBuilder();for(int i=1;i<=45;i++)longText.append("기록 ").append(i).append(": 바람의 흐름을 관찰하고 새로운 마법을 발견합니다.\n");
                c.setScreen(new CodexScreen(List.of(first,
                    new CodexData.Spell("feather_step","매우 긴 연구 기록",CodexData.Category.WIND,longText.toString(),longText.toString(),longText.toString(),10,true),
                    new CodexData.Spell("wind_bell","미발견 마법",CodexData.Category.WIND,"숨겨진 조건 확인용","절대 노출되지 않아야 하는 비밀 조건","연구 기록은 공개됩니다.",10,false))));
                click(c,0);hover(c,500,850);phase[0]=1;return;
            }
            if(++ticks[0]<25)return;ticks[0]=0;
            switch(phase[0]){
                case 1 -> {shot(c,"01-layout-1280");hover(c,1250,615);phase[0]=2;}
                case 2 -> {check(field(c,"activeText")!=null,"Condition tooltip absent");shot(c,"02-condition-hover-1280");click(c,1);hover(c,1250,512);phase[0]=3;}
                case 3 -> {check((int)field(c,"tooltipMaxScroll")>0,"Long text not scrollable");shot(c,"03-long-hover");var l=layout(c);c.currentScreen.mouseScrolled(l.x()+1250*l.scale(),l.y()+512*l.scale(),0,-1);check((int)field(c,"tooltipScroll")==3,"Tooltip scroll failed");phase[0]=4;}
                case 4 -> {shot(c,"04-long-scrolled");click(c,2);hover(c,1250,615);phase[0]=5;}
                case 5 -> {check(field(c,"activeText")==null,"Locked condition leaked");shot(c,"05-locked-condition");GLFW.glfwSetWindowSize(c.getWindow().getHandle(),1920,1080);phase[0]=6;}
                case 6 -> {click(c,0);hover(c,500,850);phase[0]=7;}
                case 7 -> {shot(c,"06-layout-1920");hover(c,1250,615);phase[0]=8;}
                case 8 -> {shot(c,"07-condition-hover-1920");warmUploads=UiResources.images().uploadCount();parent=new CodexScreen(((CodexScreen)c.currentScreen).state().filtered());c.setScreen(parent);click(c,0);hover(c,500,850);phase[0]=9;}
                case 9 -> {check(UiResources.images().uploadCount()==warmUploads,"Reopening codex reuploaded images");shot(c,"08-cached-codex");c.setScreen(new KeySettingsScreen(parent,CodexData::previewSpells,false,Path.of("visual-check/cache-keys.yml")));phase[0]=10;}
                case 10 -> {((KeySettingsScreen)c.currentScreen).verifyImageRenderer();shot(c,"09-keys");warmUploads=UiResources.images().uploadCount();c.setScreen(new KeySettingsScreen(parent,CodexData::previewSpells,false,Path.of("visual-check/cache-keys.yml")));phase[0]=11;}
                case 11 -> {check(UiResources.images().uploadCount()==warmUploads,"Reopening key settings reuploaded images");shot(c,"10-cached-keys");UiCacheProbe.report("detail-0171");System.out.println("DETAIL_VISUAL_OK: two resolutions, detail layout, tooltip scroll, hidden conditions, codex/key settings cached reopen.");phase[0]=12;c.scheduleStop();}
            }
        }catch(Exception e){throw new IllegalStateException(e);}}));
    }
    private static CodexLayout layout(MinecraftClient c){return CodexLayout.codexFit(c.currentScreen.width,c.currentScreen.height);}
    private static void click(MinecraftClient c,int card){var l=layout(c);var box=CodexHitboxes.card(card);c.currentScreen.mouseClicked(l.x()+box.centerX()*l.scale(),l.y()+box.centerY()*l.scale(),0);}
    private static void hover(MinecraftClient c,int x,int y)throws Exception{
        var l=layout(c);
        // Feed the game's cursor callback directly: a background test window may not receive OS cursor callbacks.
        var method=c.mouse.getClass().getDeclaredMethod("onCursorPos",long.class,double.class,double.class);
        method.setAccessible(true);
        method.invoke(c.mouse,c.getWindow().getHandle(),(l.x()+x*l.scale())*c.getWindow().getScaleFactor(),(l.y()+y*l.scale())*c.getWindow().getScaleFactor());
    }
    private static Object field(MinecraftClient c,String name)throws Exception{var f=CodexScreen.class.getDeclaredField(name);f.setAccessible(true);return f.get(c.currentScreen);}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void shot(MinecraftClient c,String name)throws Exception{if(c.currentScreen instanceof CodexScreen s)s.verifyImageRenderer();Path dir=Path.of("visual-check/detail-0171");Files.createDirectories(dir);try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(dir.resolve(name+".png"));}}
}
