package school.magiccodex.client;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import school.magiccodex.protocol.QuestProtocol.*;

public final class QuestVisualCheck {
    public static void register(){int[] ticks={0},phase={0};ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.getOverlay()!=null)return;try{
        if(phase[0]==0&&c.currentScreen!=null&&c.world==null){c.options.pauseOnLostFocus=false;c.options.getGuiScale().setValue(2);c.onResolutionChanged();QuestClient.data=new Response(false,0,6,"",List.of(
            card("a","야생 순찰","북쪽 숲에 나타난 좀비를 처치해 줄 아카데미 학생을 구합니다. 함께 숲길을 지켜 주세요.","active",List.of(new Goal("좀비 처치",7,10))),
            card("b","식당 재료 납품","식당에서 사용할 밀을 모아 제출해 주세요.","available",List.of(new Goal("밀 제출",32,32))),
            card("c","사서 선생님 만나기","도서관의 사서 선생님에게 말을 걸어 주세요.","available",List.of(new Goal("사서 선생님과 대화",0,1))),
            card("d","숲 현장 조사","새로운 숲을 방문해 보세요.","claimed",List.of(new Goal("숲 방문",1,1))),
            card("e","유적 수호자 조사","수호자를 찾아 처치해 주세요.","available",List.of(new Goal("수호자 처치",0,1))),
            card("f","종합 현장 실습 의뢰","목표가 여섯 개인 복합 의뢰입니다. 학교와 야생, 던전을 오가며 완료할 수 있습니다.","active",List.of(new Goal("좀비 처치",5,10),new Goal("밀 제출",16,32),new Goal("사서와 대화",1,1),new Goal("숲 방문",0,1),new Goal("이벤트 참가",0,1),new Goal("유적 조사",0,1)))));
            c.setScreen(new QuestScreen());phase[0]=1;ticks[0]=0;}
        if(phase[0]>0&&++ticks[0]==45){Path out=Path.of("visual-check/quests-0320");Files.createDirectories(out);try(var im=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){im.writeTo(out.resolve("quest-"+phase[0]+".png"));}
            if(phase[0]==1){var l=CodexLayout.codexFit(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight());c.currentScreen.mouseClicked(l.x()+l.scale()*820,l.y()+l.scale()*590,0);phase[0]=2;ticks[0]=0;}
            else if(phase[0]==2){c.options.getGuiScale().setValue(3);c.onResolutionChanged();phase[0]=3;ticks[0]=0;}
            else if(phase[0]==3){c.options.getGuiScale().setValue(2);c.onResolutionChanged();var fields=new HashMap<String,String>();fields.put("title","북쪽 숲의 야간 순찰");fields.put("description","북쪽 숲에 나타난 좀비를 처치해 줄 아카데미 학생을 구합니다.\n해가 지면 숲길이 위험해지니 동료와 함께 순찰해 주세요.");fields.put("rank","E");fields.put("completion-limit","3");fields.put("daily","false");fields.put("enabled","true");fields.put("goal-count","1");fields.put("goal.0.type","KILL");fields.put("goal.0.target","ZOMBIE");fields.put("goal.0.amount","10");fields.put("goal.0.label","좀비 처치");fields.put("reward.house-points","5");fields.put("reward.money","1000");fields.put("reward.items","EMERALD:3");fields.put("reward.commands","");c.setScreen(new QuestAdminScreen(new school.magiccodex.protocol.QuestAdminProtocol.Response("",List.of(new school.magiccodex.protocol.QuestAdminProtocol.Summary("patrol","북쪽 숲의 야간 순찰","E","공개")),"patrol","test",fields)));phase[0]=4;ticks[0]=0;}
            else if(phase[0]==4){c.currentScreen.mouseScrolled(550,170,0,-3);phase[0]=5;ticks[0]=0;}
            else if(phase[0]==5){c.options.getGuiScale().setValue(3);c.onResolutionChanged();phase[0]=6;ticks[0]=0;}
            else{Files.writeString(out.resolve("done.txt"),"QUEST_UI_RENDERED: six cards, six objectives, GUI 2 and 3");c.scheduleStop();}}
    }catch(Exception e){throw new IllegalStateException(e);}});}
    private static Card card(String id,String title,String desc,String status,List<Goal> goals){return new Card(id,title,desc,"기숙사 점수  +5\n돈  +1,000\n아이템  EMERALD × 3",status,goals,"C",status.equals("claimed")?3:1,3);}
}
