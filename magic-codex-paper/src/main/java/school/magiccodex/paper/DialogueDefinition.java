package school.magiccodex.paper;

import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable validated graph. Stable IDs keep story progress independent of dialogue edits. */
record DialogueDefinition(String id,String title,String start,boolean enabled,String permission,List<String> npcs,Map<String,Node> nodes,String storyId,String storyTitle,Map<String,String> stages) {
    record Choice(String id,String text,String next,List<String> conditions,List<String> actions){}
    record Node(String speaker,String portrait,String text,List<String> conditions,List<Choice> choices){}
    private static final java.util.regex.Pattern ID=java.util.regex.Pattern.compile("[a-z0-9_-]{1,48}");
    static void id(String value){if(value==null||!ID.matcher(value).matches())throw new IllegalArgumentException("ID: 영문 소문자·숫자·밑줄·하이픈 1~48자");}
    private static String str(Map<String,String> f,String k,String def,int max){String v=f.getOrDefault(k,def);if(v.length()>max)throw new IllegalArgumentException(k+" 길이 초과");return v;}
    private static List<String> lines(String value){return value.lines().map(String::trim).filter(s->!s.isEmpty()).toList();}
    static DialogueDefinition fromFields(String id,Map<String,String> f){
        id(id);String title=str(f,"title",id,100),start=str(f,"start","start",48);id(start);
        String enabled=f.getOrDefault("enabled","false");if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException("enabled true/false");
        var ids=lines(f.getOrDefault("nodes","start"));if(ids.isEmpty()||ids.size()>32||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("장면 ID: 중복 없이 1~32개");
        var nodes=new LinkedHashMap<String,Node>();
        for(String key:ids){id(key);String p="node."+key+".";
            String portrait=str(f,p+"portrait","elena-neutral",160);if(!portrait.isEmpty()&&!portrait.matches("[a-z0-9_/-]{1,120}"))throw new IllegalArgumentException("일러스트 ID 오류");
            var cs=new ArrayList<Choice>();int count=Integer.parseInt(f.getOrDefault(p+"choices","0"));if(count<0||count>6)throw new IllegalArgumentException("선택지 0~6개");
            for(int i=0;i<count;i++){String cp=p+"choice."+i+".";String next=str(f,cp+"next","",48);if(!next.isEmpty())id(next);var conditions=lines(str(f,cp+"conditions","",1500));var actions=lines(str(f,cp+"actions","",2500));validateConditions(conditions);validateActions(actions);cs.add(new Choice("c"+i,str(f,cp+"text","다음",140),next,conditions,actions));}
            var conditions=lines(str(f,p+"conditions","",1500));validateConditions(conditions);
            nodes.put(key,new Node(str(f,p+"speaker","",64),portrait,str(f,p+"text","",1600),conditions,List.copyOf(cs)));
        }
        if(!nodes.containsKey(start))throw new IllegalArgumentException("시작 장면이 없습니다");
        for(var n:nodes.values())for(var c:n.choices)if(!c.next.isEmpty()&&!nodes.containsKey(c.next))throw new IllegalArgumentException("다음 장면 없음: "+c.next);
        var npcs=lines(str(f,"npcs","",1500));if(npcs.size()>32||npcs.stream().anyMatch(n->!n.matches("(?:citizens:[0-9]{1,9}|tag:[a-zA-Z0-9_-]{1,64})")))throw new IllegalArgumentException("NPC 연결: citizens:번호 또는 tag:태그");
        String storyId=str(f,"story-id","",48);if(!storyId.isEmpty())id(storyId);var stages=new TreeMap<String,String>();for(String line:lines(str(f,"story-stages","",3000))){int at=line.indexOf('=');if(at<1)throw new IllegalArgumentException("이야기 단계: ID=표시 문구");String key=line.substring(0,at).trim(),label=line.substring(at+1).trim();id(key);if(label.length()>140)throw new IllegalArgumentException("이야기 단계 설명 최대 140자");stages.put(key,label);}
        return new DialogueDefinition(id,title,start,Boolean.parseBoolean(enabled),str(f,"permission","",100),List.copyOf(npcs),Collections.unmodifiableMap(nodes),storyId,str(f,"story-title",title,100),Map.copyOf(stages));
    }
    private static void validateConditions(List<String> conditions){if(conditions.size()>12)throw new IllegalArgumentException("조건 최대 12개");for(String c:conditions){String[] a=c.split(" ",3);if(a[0].equals("permission")&&a.length==2&&a[1].matches("[a-zA-Z0-9_.*-]{1,100}"))continue;if(a[0].equals("custom")&&a.length==3){id(a[1]);continue;}if(a[0].equals("quest")&&a.length==3&&Set.of("active","completed").contains(a[2])){id(a[1]);continue;}if(a.length!=3||!Set.of("flag","not-flag","story").contains(a[0]))throw new IllegalArgumentException("조건 형식: "+c);id(a[1]);id(a[2]);}}
    private static void validateActions(List<String> actions){if(actions.size()>12)throw new IllegalArgumentException("동작 최대 12개");for(String action:actions){String[] a=action.split(" ",3);if(a.length<2)throw new IllegalArgumentException("동작 형식: "+action);switch(a[0]){case "flag","story"->{if(a.length!=3)throw new IllegalArgumentException("flag/story ID 값");id(a[1]);id(a[2]);}case "quest","event"->{if(a.length!=2)throw new IllegalArgumentException("quest/event ID");id(a[1]);}case "command"->{if(action.length()>600||a[1].startsWith("/"))throw new IllegalArgumentException("명령어는 / 없이 600자 이하");}case "custom"->{if(a.length!=3)throw new IllegalArgumentException("custom 핸들러ID 인자");id(a[1]);}default->throw new IllegalArgumentException("알 수 없는 동작: "+a[0]);}}}
    Map<String,String> fields(){var f=new TreeMap<String,String>();f.put("title",title);f.put("story-id",storyId);f.put("story-title",storyTitle);f.put("story-stages",String.join("\n",new TreeMap<>(stages).entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()));f.put("start",start);f.put("enabled",""+enabled);f.put("permission",permission);f.put("npcs",String.join("\n",npcs));f.put("nodes",String.join("\n",nodes.keySet()));for(var e:nodes.entrySet()){String p="node."+e.getKey()+".";var n=e.getValue();f.put(p+"speaker",n.speaker);f.put(p+"portrait",n.portrait);f.put(p+"text",n.text);f.put(p+"conditions",String.join("\n",n.conditions));f.put(p+"choices",""+n.choices.size());for(int i=0;i<n.choices.size();i++){var c=n.choices.get(i);String cp=p+"choice."+i+".";f.put(cp+"text",c.text);f.put(cp+"next",c.next);f.put(cp+"conditions",String.join("\n",c.conditions));f.put(cp+"actions",String.join("\n",c.actions));}}return Map.copyOf(f);}
    String encode(){var y=new YamlConfiguration();new TreeMap<>(fields()).forEach(y::set);return y.saveToString();}
    static DialogueDefinition decode(String id,String text)throws Exception{var y=new YamlConfiguration();y.loadFromString(text);var f=new HashMap<String,String>();for(String k:y.getKeys(true))if(!y.isConfigurationSection(k))f.put(k,y.getString(k,""));return fromFields(id,f);}
    static String revision(DialogueDefinition d){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(d.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static boolean matches(List<String> conditions,Map<String,String> state,java.util.function.Predicate<String> permissions){return matches(conditions,state,permissions,(key,arg)->false);}
    static boolean matches(List<String> conditions,Map<String,String> state,java.util.function.Predicate<String> permissions,java.util.function.BiPredicate<String,String> external){for(String c:conditions){String[] a=c.split(" ",3);boolean ok=switch(a[0]){case "permission"->permissions.test(a[1]);case "custom"->external.test("custom:"+a[1],a[2]);case "quest"->external.test("quest:"+a[1],a[2]);case "flag"->a[2].equals(state.get("flag."+a[1]));case "not-flag"->!a[2].equals(state.get("flag."+a[1]));case "story"->a[2].equals(state.get("story."+a[1]));default->false;};if(!ok)return false;}return true;}
}
