package school.magiccodex.client;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
public final class QuestLiveCheck {
    public static void register(){int[] ticks={0},phase={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
        if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;ConnectScreen.connect(new TitleScreen(),c,ServerAddress.parse("127.0.0.1:25994"),new ServerInfo("Quest test","127.0.0.1:25994",ServerInfo.ServerType.OTHER),false,null);phase[0]=1;return;}
        if(c.player==null||c.world==null)return;if(++ticks[0]<70)return;ticks[0]=0;
        switch(phase[0]++){
            case 1->{var fields=new java.util.HashMap<String,String>();fields.put("title","관리자창 납품 테스트");fields.put("description","학교 식당의 밀을 구해 주세요.");fields.put("rank","B");fields.put("completion-limit","2");fields.put("enabled","true");fields.put("daily","false");fields.put("goal-count","1");fields.put("goal.0.type","SUBMIT");fields.put("goal.0.target","WHEAT");fields.put("goal.0.amount","32");fields.put("goal.0.label","밀 제출");fields.put("reward.house-points","7");fields.put("reward.money","0");fields.put("reward.items","EMERALD:2");QuestAdminClient.send(2,"editor_test","",fields);}
            case 2->{if(!(c.currentScreen instanceof QuestAdminScreen))throw new AssertionError("Admin response did not open editor");c.getNetworkHandler().sendChatCommand("questfixture supplies");QuestClient.send(1,"editor_test",0,2);}
            case 3->{check("editor_test","active",32);QuestClient.send(2,"editor_test",0,0);}
            case 4->{check("editor_test","available",0);QuestClient.send(2,"editor_test",0,0);}
            case 5->{c.getNetworkHandler().sendChatCommand("questfixture supplies");QuestClient.send(1,"editor_test",0,2);}
            case 6->{check("editor_test","active",32);QuestClient.send(2,"editor_test",0,1);}
            case 7->{check("editor_test","claimed",32);QuestClient.send(1,"editor_test",0,1);}
            case 8->{check("editor_test","claimed",32);QuestClient.send(2,"editor_test",0,1);}
            case 9->{c.getNetworkHandler().sendChatCommand("questfixture verify");Path d=Path.of("visual-check/quests-0320/live-done.txt");Files.createDirectories(d.getParent());Files.writeString(d,"QUEST_CLIENT_LIVE_PASS");c.scheduleStop();}

        }
    }catch(Throwable e){System.err.println("QUEST_CLIENT_FAIL "+e);c.scheduleStop();}});}
    private static void check(String id,String state,int progress){var card=QuestClient.data.cards().stream().filter(q->q.id().equals(id)).findFirst().orElseThrow();if(!card.state().equals(state)||card.goals().getFirst().current()!=progress)throw new AssertionError(id+" "+card);}
}
