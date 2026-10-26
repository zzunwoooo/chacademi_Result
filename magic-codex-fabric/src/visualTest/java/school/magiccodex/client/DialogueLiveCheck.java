package school.magiccodex.client;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.*;
import school.magiccodex.protocol.*;
public final class DialogueLiveCheck {
    public static void register(){int[] ticks={0},phase={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
        if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;ConnectScreen.connect(new TitleScreen(),c,ServerAddress.parse("127.0.0.1:25995"),new ServerInfo("Dialogue test","127.0.0.1:25995",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
        if(c.player==null||c.world==null)return;if(++ticks[0]<45)return;ticks[0]=0;
        switch(phase[0]++){
            case 1->c.getNetworkHandler().sendChatCommand("\uB300\uD654\uAD00\uB9AC reload reward_test"); // Fixture YAML must be staged on the test server.
            case 2->c.getNetworkHandler().sendChatCommand("\uB300\uD654\uAD00\uB9AC preview reward_test");
            case 3->{if(!response(c).preview())throw new AssertionError("preview flag");choose(c);}
            case 4->c.getNetworkHandler().sendChatCommand("대화 elena");
            case 5->choose(c);
            case 6->choose(c);
            case 7->c.getNetworkHandler().sendChatCommand("대화 arden");
            case 8->choose(c);
            case 9->choose(c);
            case 10->c.getNetworkHandler().sendChatCommand("대화 reward_test");
            case 11->{var r=response(c);choose(c);DialogueClient.send(r.session(),r.sequence(),"c0",false);}
            case 12->c.getNetworkHandler().sendChatCommand("대화 reward_test");
            case 13->choose(c);
            case 14->c.getNetworkHandler().sendChatCommand("메인퀘스트");
            case 15->{if(!response(c).text().contains("완료 · 교수님"))throw new AssertionError("journal labels");c.getNetworkHandler().sendChatCommand("dialoguefixture verify");}
            case 16->{System.out.println("DIALOGUE_CLIENT_LIVE_PASS");c.scheduleStop();}
        }
    }catch(Throwable e){System.err.println("DIALOGUE_CLIENT_FAIL "+e);c.scheduleStop();}});}
    private static Map<String,String> fields(){var f=new HashMap<String,String>();f.put("title","동작 검증");f.put("start","start");f.put("enabled","true");f.put("nodes","start");f.put("node.start.speaker","사서 엘레나");f.put("node.start.portrait","elena-neutral");f.put("node.start.text","선택지 실행 검증");f.put("node.start.choices","1");f.put("node.start.choice.0.text","확인");f.put("node.start.choice.0.actions","flag reward_seen yes\ncommand dialoguefixture reward {player}");return f;}
    private static DialogueProtocol.Response response(net.minecraft.client.MinecraftClient c)throws Exception{if(!(c.currentScreen instanceof DialogueScreen))throw new AssertionError("dialogue screen missing");var f=DialogueScreen.class.getDeclaredField("data");f.setAccessible(true);return (DialogueProtocol.Response)f.get(c.currentScreen);}
    private static void choose(net.minecraft.client.MinecraftClient c)throws Exception{var r=response(c);if(r.choices().isEmpty())throw new AssertionError("no choices");DialogueClient.send(r.session(),r.sequence(),r.choices().getFirst().id(),false);}
}
