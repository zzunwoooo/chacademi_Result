package school.magiccodex.client;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.SchoolProtocol;

/** Real test server / PAPI fixture. This class is excluded from release jars. */
public final class SchoolLiveCheck {
    public static void register(){
        int[] phase={-2},ticks={0};long start=System.currentTimeMillis();
        ClientLifecycleEvents.CLIENT_STARTED.register(started->ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(System.currentTimeMillis()-start>300000)throw new IllegalStateException("School live timeout phase="+phase[0]);
            if(c.getOverlay()!=null)return;
            if(phase[0]==-2&&c.currentScreen!=null){
                if(!Files.exists(Path.of("../../output/house-points-ui-v1/server-test/ready.txt")))return;
                c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.options.getViewDistance().setValue(3);c.onResolutionChanged();c.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
                var address=ServerAddress.parse("127.0.0.1:25596");ConnectScreen.connect(c.currentScreen,c,address,new ServerInfo("School isolated test","127.0.0.1:25596",ServerInfo.ServerType.OTHER),false,null);phase[0]=-1;return;
            }
            if(c.player==null||c.world==null)return;
            if(phase[0]==-1){if(c.currentScreen!=null||!SchoolClient.nickname("").equals("별하"))return;phase[0]=0;ticks[0]=0;}
            c.inGameHud.getChatHud().clear(false);
            if(++ticks[0]<60)return;ticks[0]=0;
            try{
                switch(phase[0]++){
                    case 0->{server(c,"기숙사점수 소속 CodexPreview 루미나");server(c,"기숙사점수 설정 아르케온 1280");server(c,"기숙사점수 설정 루미나 1060");server(c,"기숙사점수 설정 베스티아즈 920");server(c,"기숙사점수 설정 노크세르 740");}
                    case 1->SchoolClient.open();
                    case 2->{check(c.currentScreen instanceof SchoolScreen,"School opens");check(SchoolClient.scores().equals(List.of(1280L,1060L,920L,740L)),"Score command sync");check(SchoolClient.total()==0,"Initially empty");shot(c,"01-empty");check(SchoolClient.request(SchoolProtocol.DONATE,0,"wind_basket",c.currentScreen),"Donation request");}
                    case 3->{check(SchoolClient.total()==1,"One donation saved");check(SchoolClient.page(0)!=null&&SchoolClient.page(0).records().size()==1,"Immediate record refresh");check(SchoolClient.scores().get(1)==1061,"Points awarded");check(SchoolClient.donor("wind_basket").equals("별하"),"PAPI donor name");shot(c,"02-donated");check(SchoolClient.request(SchoolProtocol.DONATE,0,"wind_basket",c.currentScreen),"Duplicate request");}
                    case 4->{check(SchoolClient.total()==1&&SchoolClient.scores().get(1)==1061,"Duplicate no reward");shot(c,"03-duplicate");var spell=new CodexData.Spell("wind_basket","바람 바구니",CodexData.Category.WIND,"주변에 떨어진 아이템을 모아 가져옵니다.","떨어진 아이템 줍기.","바람에 실려 온 작은 기록.",15,true,"magiccodex:textures/spells/novice/wind_basket.png","magic.learned.wind_basket","마법 바람바구니",0,true,30);MagicCodexClient.openFromMenu();var codex=new CodexScreen(List.of(spell));c.setScreen(codex);codex.state().selectSlot(0);}
                    case 5->{check(((CodexScreen)c.currentScreen).state().selected()!=null&&SchoolClient.donor("wind_basket").equals("별하"),"Codex selected donor");shot(c,"04-codex-donor");c.currentScreen.close();server(c,"기증기록 초기화 wind_basket 확인");server(c,"기숙사점수 추가 루미나 50");SchoolClient.open();}
                    case 6->{check(SchoolClient.total()==0,"Record reset");check(SchoolClient.scores().get(1)==1111,"Reset keeps points; independent +50");shot(c,"05-reset");c.currentScreen.close();server(c,"스탯창 별하");}
                    case 7->{check(c.currentScreen instanceof StatsScreen,"Nickname lookup opens stats");check(((StatsScreen)c.currentScreen).displayedValues().nickname().equals("별하"),"Stats PAPI name");shot(c,"06-stats-nickname");c.currentScreen.close();server(c,"deop CodexPreview");SchoolClient.open();}
                    case 8->{server(c,"기숙사점수 추가 루미나 999");check(SchoolClient.request(SchoolProtocol.DONATE,0,"wind_basket",c.currentScreen),"Unowned donation request");}
                    case 9->{check(SchoolClient.total()==0&&SchoolClient.scores().get(1)==1111,"Non-op cannot add points or donate unknown spell");shot(c,"07-permission-denied");
                        String result="SCHOOL_E2E_OK: actual packets; PAPI names; house assignment; point adjustment; first donation; duplicate rejected; independent reset; nickname stats lookup; non-op denial";
                        Path out=Path.of("visual-check/school-0210");Files.writeString(out.resolve("done.txt"),result);System.out.println(result);c.scheduleStop();}
                }
            }catch(Exception e){throw new IllegalStateException("School phase "+phase[0],e);}
        }));
    }
    private static void server(MinecraftClient c,String command){c.getNetworkHandler().sendChatCommand(command);}
    private static void check(boolean valid,String label){if(!valid)throw new IllegalStateException(label);}
    private static void shot(MinecraftClient c,String name)throws Exception{Path out=Path.of("visual-check/school-0210");Files.createDirectories(out);try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(out.resolve(name+".png"));}}
}
