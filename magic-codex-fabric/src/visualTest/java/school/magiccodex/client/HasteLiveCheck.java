package school.magiccodex.client;

import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Util;

public final class HasteLiveCheck {
    private static final CodexData.Spell SPELL=new CodexData.Spell("haste_test","가속 시험",CodexData.Category.WIND,"마법 가속으로 재사용 대기시간이 줄어듭니다.","","",10,true,"magiccodex:textures/spells/novice/wind_basket.png","minecraft.command.say","say HASTE_TEST",0,true,10);
    public static void register(){int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>300000)throw new IllegalStateException("Haste timeout "+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                if(!Files.exists(Path.of("../../output/magic-haste-v1/server-test/ready.txt")))return;
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                ConnectScreen.connect(c.currentScreen,c,ServerAddress.parse("127.0.0.1:25598"),new ServerInfo("Haste isolated test","127.0.0.1:25598",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;
            }
            if(c.player==null||c.world==null)return;
            if(phase[0]==-1){if(c.currentScreen!=null)return;phase[0]=0;ticks[0]=0;}
            c.inGameHud.getChatHud().clear(false);if(++ticks[0]<60)return;ticks[0]=0;
            try{switch(phase[0]++){
                case 0->{server(c,"clear @s");server(c,"item replace entity @s weapon.mainhand with minecraft:amethyst_shard");server(c,"장비설정 artifact 12 4 40 2 75");server(c,"codexmana haste CodexPreview 25");}
                case 1->EquipmentClient.open();
                case 2->{check(ManaClient.haste()==25,"Base haste synchronized");check(EquipmentClient.request(1,4,0),"Equip request");}
                case 3->{check(EquipmentClient.data.haste()==75&&ManaClient.haste()==100,"Base + gear haste");shot(c,"01-equipment");c.setScreen(new StatsScreen());}
                case 4->{check(((StatsScreen)c.currentScreen).displayedValues().haste()==100,"Self stat haste");shot(c,"02-stats");c.currentScreen.close();server(c,"스탯창 별하");}
                case 5->{check(c.currentScreen instanceof StatsScreen&&((StatsScreen)c.currentScreen).displayedValues().haste()==100,"Remote profile haste");shot(c,"03-remote-stats");c.currentScreen.close();MagicCodexClient.openFromMenu();var codex=new CodexScreen(List.of(SPELL));c.setScreen(codex);codex.state().selectSlot(0);}
                case 6->{shot(c,"04-codex");c.currentScreen.close();ManaClient.cast(SPELL);}
                case 7->{long remaining=CastingClient.state().remaining("haste_test",Util.getMeasuringTimeMs());check(remaining>1000&&remaining<3000,"10 seconds / haste 100 = 5 second cooldown");EquipmentClient.open();}
                case 8->{check(EquipmentClient.request(2,4,0),"Unequip request");}
                case 9->{check(EquipmentClient.data.haste()==0&&ManaClient.haste()==25,"Gear removal restores base");c.currentScreen.close();ManaClient.cast(SPELL);}
                case 10->{long remaining=CastingClient.state().remaining("haste_test",Util.getMeasuringTimeMs());check(remaining>3500&&remaining<6000,"Base haste25 = 8 second cooldown");server(c,"codexmana haste CodexPreview 0");}
                case 11->{check(ManaClient.haste()==0,"Admin reset");long remaining=CastingClient.state().remaining("haste_test",Util.getMeasuringTimeMs());check(remaining>0&&remaining<4000,"Running cooldown is not stretched");Path out=Path.of("visual-check/haste-0230");Files.createDirectories(out);Files.writeString(out.resolve("done.txt"),"HASTE_E2E_OK: base+equipment, self/remote stats, codex preview, actual 5s/8s cooldown packets, unequip and running cooldown preservation");c.scheduleStop();}
            }}catch(Exception e){throw new IllegalStateException("Haste phase "+phase[0],e);}
        }));
    }
    private static void server(MinecraftClient c,String s){c.getNetworkHandler().sendChatCommand(s);}
    private static void check(boolean ok,String s){if(!ok)throw new IllegalStateException(s);}
    private static void shot(MinecraftClient c,String name)throws Exception{Path out=Path.of("visual-check/haste-0230");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve(name+".png"));}}
}
