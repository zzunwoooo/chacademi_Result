package kr.chacademy.story.dialogue;
import java.io.IOException;
import java.util.*;
/** Commands in the local dialogue file, executed only as the connected player. */
public record DialogueCommands(List<String> end,Map<String,List<String>> endings,Map<String,List<String>> events) {
    public static final DialogueCommands EMPTY=new DialogueCommands(List.of(),Map.of(),Map.of());
    public static List<String> warnings(Object root){
        if(!(root instanceof Map<?,?> m))return List.of();
        var out=new ArrayList<String>();
        for(Object key:m.keySet())if(!Set.of("end","endings","events").contains(String.valueOf(key)))
            out.add("commands."+key+": unsupported key; put ordinary ending commands in server dialogues/server_command.yml under commands.<dialogue-id>.");
        if(m.containsKey("end")||m.containsKey("endings"))out.add("Local end/endings commands do not execute ordinary commands. Use server dialogues/server_command.yml; chaca_reward first-reward markers are preserved.");
        return List.copyOf(out);
    }
    public static DialogueCommands parse(Object root)throws IOException {
        if(root==null)return EMPTY;
        if(!(root instanceof Map<?,?> m))throw new IOException("commands must be a map");
        return new DialogueCommands(list(m.get("end")),map(m.get("endings")),map(m.get("events")));
    }
    private static Map<String,List<String>> map(Object raw)throws IOException {
        if(raw==null)return Map.of();if(!(raw instanceof Map<?,?> m))throw new IOException("command events/endings must be maps");
        Map<String,List<String>> out=new LinkedHashMap<>();
        for(var e:m.entrySet()){String id=String.valueOf(e.getKey());if(!DialogueParser.ID_PATTERN.matcher(id).matches())throw new IOException("invalid command event/ending id");out.put(id,list(e.getValue()));}
        return Map.copyOf(out);
    }
    private static List<String> list(Object raw)throws IOException {
        if(raw==null)return List.of();if(!(raw instanceof List<?> list)||list.size()>64)throw new IOException("commands must be a list of at most 64 strings");
        List<String> out=new ArrayList<>();for(Object value:list){if(!(value instanceof String s))throw new IOException("command must be text");s=s.strip();if(s.startsWith("/"))s=s.substring(1);if(s.isBlank()||s.length()>256||s.chars().anyMatch(Character::isISOControl))throw new IOException("invalid player command");out.add(s);}return List.copyOf(out);
    }
    public static String expand(String command,String account,String nickname){return command.replace("{player}",account).replace("{account}",account).replace("{nickname}",nickname);}
}
