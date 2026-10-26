package school.magiccodex.client;

import java.util.UUID;
import java.util.function.BiFunction;

/** UI extension point only. Actual social validation and mutations belong to the server. POPULARITY = 인기도 올리기, POPULARITY_DOWN = 내리기. */
public final class StatsSocialActions {
    public enum Action { FRIEND, POPULARITY, POPULARITY_DOWN }
    private static BiFunction<Action,UUID,String> handler;
    private StatsSocialActions(){}
    public static void connect(BiFunction<Action,UUID,String> receiver){handler=receiver;}
    public static void reset(){handler=null;}
    public static String activate(Action action,UUID target){
        if(handler==null)return action==Action.FRIEND?"친구 추가 기능은 아직 연결되지 않았습니다.":"인기도 기능은 아직 연결되지 않았습니다.";
        String result=handler.apply(action,target);
        return result==null?"":result;
    }
}
