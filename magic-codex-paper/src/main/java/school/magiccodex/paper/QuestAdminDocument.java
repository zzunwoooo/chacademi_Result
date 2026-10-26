package school.magiccodex.paper;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import static school.magiccodex.paper.QuestDefinition.*;

final class QuestAdminDocument {
    static Map<String,String> fields(QuestDefinition q){
        var f=new HashMap<String,String>();f.put("title",q.title());f.put("description",q.description());f.put("rank",q.rank());f.put("completion-limit",""+q.completionLimit());f.put("daily",""+q.daily());f.put("enabled",""+q.enabled());f.put("opens-at",QuestBridge.timeText(q.opensAt()));f.put("permission",q.permission());f.put("reward.label",q.rewardLabel());f.put("reward.money",""+q.money());f.put("reward.house-points",""+q.housePoints());f.put("reward.items",String.join("\n",q.items()));f.put("reward.commands",String.join("\n",q.commands()));f.put("goal-count",""+q.objectives().size());f.put("main",""+q.main());f.put("requires",String.join("\n",q.requires()));
        for(int i=0;i<q.objectives().size();i++){var g=q.objectives().get(i);String k="goal."+i+".";f.put(k+"type",g.type().name());f.put(k+"target",g.target());f.put(k+"amount",""+g.amount());f.put(k+"label",g.label());f.put(k+"server",g.server());f.put(k+"world",g.world());}return Map.copyOf(f);
    }
    static QuestDefinition parse(String id,Map<String,String> f){
        int n=Integer.parseInt(f.getOrDefault("goal-count","1"));if(n<1||n>6)throw new IllegalArgumentException("클리어 조건은 1~6개");var goals=new ArrayList<Objective>();
        for(int i=0;i<n;i++){String k="goal."+i+".";Type t=Type.valueOf(f.getOrDefault(k+"type","KILL"));String target=f.getOrDefault(k+"target","");
            if(t==Type.KILL&&!Arrays.stream(EntityType.values()).anyMatch(e->e.name().equals(target)&&e.isAlive()))throw new IllegalArgumentException("몹 이름을 확인하세요 (예: ZOMBIE)");
            if(t==Type.SUBMIT){var item=QuestBridge.submissionItem(target);if(item.isEmpty())throw new IllegalArgumentException("제출 아이템");}
            goals.add(new Objective(t,target,Integer.parseInt(f.getOrDefault(k+"amount","1")),f.getOrDefault(k+"label",target),f.getOrDefault(k+"server",""),f.getOrDefault(k+"world","")));
        }
        var q=new QuestDefinition(id,f.getOrDefault("title",""),f.getOrDefault("description",""),f.getOrDefault("permission",""),bool(f,"daily"),bool(f,"enabled"),goals,f.getOrDefault("reward.label",""),lines(f.getOrDefault("reward.commands","")),lines(f.getOrDefault("reward.items","")),f.getOrDefault("rank","F"),Integer.parseInt(f.getOrDefault("completion-limit","1")),QuestBridge.parseTime(f.getOrDefault("opens-at","")),Double.parseDouble(f.getOrDefault("reward.money","0")),Integer.parseInt(f.getOrDefault("reward.house-points","0")),bool(f,"main"),lines(f.getOrDefault("requires","")));
        for(var item:q.items())if(Material.matchMaterial(item.split(":")[0])==null||!Material.matchMaterial(item.split(":")[0]).isItem()||Material.matchMaterial(item.split(":")[0]).isAir())throw new IllegalArgumentException("보상 아이템 종류 오류");
        if(q.encode().length()>60000)throw new IllegalArgumentException("의뢰 내용이 너무 큽니다");return q;
    }
    private static boolean bool(Map<String,String> f,String k){String v=f.getOrDefault(k,"false");if(!Set.of("true","false").contains(v))throw new IllegalArgumentException(k);return Boolean.parseBoolean(v);}
    private static List<String> lines(String v){return v.lines().map(String::trim).filter(s->!s.isEmpty()).toList();}
}
