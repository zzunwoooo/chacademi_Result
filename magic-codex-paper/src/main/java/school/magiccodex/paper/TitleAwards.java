package school.magiccodex.paper;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 칭호 획득 경로 설정 (2026-10-25, 메모 #21·#89). title-awards.yml(획득 경로) + player-presets.yml(특정 유저 사전 설정).
 * 순수 로직: 어떤 사건이 어떤 장부 키 → 칭호 ID 로 바뀌는지만 정한다. 실제 지급·중복 방지는 TitleStore.award(장부)가 한다.
 * 장부 키: discovery.&lt;마법&gt; · affinity.any.&lt;점수&gt; · affinity.npc.&lt;NPC&gt; (자동 칭호 prefix_npc_&lt;NPC&gt; 포함) · fishing.&lt;규칙&gt; · preset.title.&lt;칭호&gt; · preset.house · preset.spell.&lt;마법&gt;
 */
final class TitleAwards {
    record FishRule(String id,double minCm,String fish,String title){}
    record Preset(int house,List<String> titles,List<String> spells){Preset{titles=List.copyOf(titles);spells=List.copyOf(spells);}}
    static final TitleAwards EMPTY=new TitleAwards(Map.of(),new TreeMap<>(),30,Map.of(),"",0x96e4ec,List.of(),Map.of());
    /** NPC별 자동 칭호 ID 머리 (prefix_npc_&lt;NPC ID&gt;). */
    static final String NPC_PREFIX="prefix_npc_";
    final Map<String,String> discovery;final NavigableMap<Integer,String> anyNpc;final int npcThreshold;final Map<String,String> npc;final List<FishRule> fishing;
    /** NPC별 자동 접두 칭호 이름 모양 ("{npc}의"), 비어 있으면 자동 칭호 없음. */
    final String npcFormat;final int npcColor;
    /** 키: 소문자 UUID 또는 소문자 마인크래프트 계정명. */
    private final Map<String,Preset> presets;
    private TitleAwards(Map<String,String> discovery,NavigableMap<Integer,String> anyNpc,int npcThreshold,Map<String,String> npc,String npcFormat,int npcColor,List<FishRule> fishing,Map<String,Preset> presets){
        this.npcFormat=npcFormat;this.npcColor=npcColor;
        this.discovery=Map.copyOf(discovery);this.anyNpc=Collections.unmodifiableNavigableMap(new TreeMap<>(anyNpc));this.npcThreshold=npcThreshold;this.npc=Map.copyOf(npc);this.fishing=List.copyOf(fishing);this.presets=Map.copyOf(presets);
    }
    private static String titleId(String where,String value){
        String v=value==null?"":value.trim();if(v.isEmpty())return "";
        if(!school.magiccodex.protocol.TitleProtocol.id(v))throw new IllegalArgumentException(where+": 칭호 ID 형식 확인 ("+v+")");return v;
    }
    private static Map<String,String> idMap(ConfigurationSection s,String where){
        var out=new LinkedHashMap<String,String>();if(s==null)return out;
        for(String k:s.getKeys(false)){String t=titleId(where+"."+k,s.getString(k));if(!t.isEmpty())out.put(k,t);}return out;
    }
    static TitleAwards parse(YamlConfiguration awards,YamlConfiguration presets){
        var discovery=idMap(awards.getConfigurationSection("discovery"),"discovery");
        var any=new TreeMap<Integer,String>();var anySection=awards.getConfigurationSection("affinity.first-any");
        if(anySection!=null)for(String k:anySection.getKeys(false)){int score;try{score=Integer.parseInt(k.trim());}catch(NumberFormatException e){throw new IllegalArgumentException("affinity.first-any: 점수는 정수 ("+k+")");}
            if(score<1||score>100)throw new IllegalArgumentException("affinity.first-any: 점수는 1~100 ("+k+")");String t=titleId("affinity.first-any."+k,anySection.getString(k));if(!t.isEmpty())any.put(score,t);}
        int threshold=awards.getInt("affinity.npc-threshold",30);if(threshold<1||threshold>100)throw new IllegalArgumentException("affinity.npc-threshold 는 1~100");
        var npc=idMap(awards.getConfigurationSection("affinity.npc"),"affinity.npc");
        String format=awards.getString("affinity.npc-prefix.format","").trim();
        if(!format.isEmpty()&&!format.contains("{npc}"))throw new IllegalArgumentException("affinity.npc-prefix.format 에 {npc} 가 있어야 해요 ("+format+")");
        String hex=awards.getString("affinity.npc-prefix.color","#96E4EC").trim();if(!hex.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("affinity.npc-prefix.color 확인 ("+hex+")");
        var fish=new ArrayList<FishRule>();var fs=awards.getConfigurationSection("fishing");
        if(fs!=null)for(String k:fs.getKeys(false)){var r=fs.getConfigurationSection(k);if(r==null)continue;String t=titleId("fishing."+k,r.getString("title"));double min=r.getDouble("min-cm",0);
            if(!k.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("fishing: 규칙 ID는 영문 소문자·숫자·_- ("+k+")");
            if(t.isEmpty()||min<=0)continue; // 칭호 또는 기준 크기가 비어 있으면 꺼진 규칙
            fish.add(new FishRule(k,min,r.getString("fish","").trim(),t));}
        var people=new LinkedHashMap<String,Preset>();var ps=presets.getConfigurationSection("players");
        if(ps!=null)for(String k:ps.getKeys(false)){var s=ps.getConfigurationSection(k);if(s==null)continue;
            String houseText=s.getString("house","").trim();int house=houseText.isEmpty()?-1:SchoolBridge.parseHouse(houseText);
            if(!houseText.isEmpty()&&house<0)throw new IllegalArgumentException("players."+k+".house: 아르케온·루미나·베스티아즈·노크세르 중 하나 ("+houseText+")");
            var titles=new ArrayList<String>();for(String t:s.getStringList("titles")){String id=titleId("players."+k+".titles",t);if(!id.isEmpty()&&!titles.contains(id))titles.add(id);}
            var spells=new ArrayList<String>();for(String sp:s.getStringList("spells")){String id=sp==null?"":sp.trim();if(id.isEmpty())continue;if(!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("players."+k+".spells: 마법 ID 형식 확인 ("+id+")");if(!spells.contains(id))spells.add(id);}
            people.put(k.trim().toLowerCase(Locale.ROOT),new Preset(house,titles,spells));}
        return new TitleAwards(discovery,any,threshold,npc,format,Integer.parseInt(hex.substring(1),16),fish,people);
    }
    Map<String,String> discoveryKeys(Collection<String> spells){
        var out=new LinkedHashMap<String,String>();if(discovery.isEmpty())return out;
        for(String s:spells){String t=discovery.get(s);if(t!=null)out.put("discovery."+s,t);}return out;
    }
    /** scores: NPC ID → 현재 호감도 점수. */
    Map<String,String> affinityKeys(Map<String,Integer> scores){
        var out=new LinkedHashMap<String,String>();int best=0;
        for(var e:scores.entrySet()){int score=e.getValue()==null?0:e.getValue();best=Math.max(best,score);String t=npcTitle(e.getKey());if(t!=null&&score>=npcThreshold)out.put("affinity.npc."+e.getKey(),t);}
        for(var e:anyNpc.headMap(best,true).entrySet())out.put("affinity.any."+e.getKey(),e.getValue());
        return out;
    }
    /** NPC 칭호 ID: affinity.npc 에 적은 것이 우선, 없으면 자동 칭호 prefix_npc_<id> (자동이 꺼져 있거나 ID 형식이 안 맞으면 null). */
    String npcTitle(String npcId){
        String t=npc.get(npcId);if(t!=null)return t;if(npcFormat.isEmpty()||npcId==null)return null;
        String auto=NPC_PREFIX+npcId.toLowerCase(Locale.ROOT);return school.magiccodex.protocol.TitleProtocol.id(auto)?auto:null;
    }
    /** 자동 NPC 칭호 이름 (예: 리아 → 리아의). */
    String npcTitleName(String npcName){return npcFormat.replace("{npc}",npcName.strip());}
    Map<String,String> fishKeys(String fish,double cm){
        var out=new LinkedHashMap<String,String>();
        for(var r:fishing)if(cm>=r.minCm()&&(r.fish().isEmpty()||r.fish().equals(fish)))out.put("fishing."+r.id(),r.title());return out;
    }
    Preset preset(UUID id,String accountName){
        var p=presets.get(id.toString().toLowerCase(Locale.ROOT));if(p==null&&accountName!=null)p=presets.get(accountName.toLowerCase(Locale.ROOT));return p;
    }
    /** 설정 파일이 가리키는 모든 칭호 ID (titles.yml 확인 경고용). */
    Set<String> referencedTitles(){
        var out=new TreeSet<String>(discovery.values());out.addAll(anyNpc.values());out.addAll(npc.values());fishing.forEach(r->out.add(r.title()));presets.values().forEach(p->out.addAll(p.titles()));return out;
    }
}
