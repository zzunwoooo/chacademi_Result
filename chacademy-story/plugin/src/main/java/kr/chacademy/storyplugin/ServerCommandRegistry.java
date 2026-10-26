package kr.chacademy.storyplugin;
import java.util.*;
/** One administrator-owned file. Validation completes before replacing the last known good snapshot. */
final class ServerCommandRegistry {
 private Map<String,List<String>> commands=Map.of();
 List<String> commands(String id){return commands.get(id);}
 int size(){return commands.size();}
 void replace(Object root){commands=parse(root);}
 static Map<String,List<String>> parse(Object root){
  if(!(root instanceof Map<?,?> all)||all.size()!=1||!(all.get("commands") instanceof Map<?,?> entries))throw new IllegalArgumentException("Expected only commands: dialogue-id: [command strings] in dialogues/server_command.yml");
  if(entries.size()>512)throw new IllegalArgumentException("Too many dialogue IDs (max 512)");
  var parsed=new LinkedHashMap<String,List<String>>();
  for(var e:entries.entrySet()){
   if(!(e.getKey() instanceof String id)||!StorySafety.validId(id)||!(e.getValue() instanceof List<?> list)||list.size()>64)throw new IllegalArgumentException("Each commands.<dialogue-id> must be a list of at most 64 strings");
   var values=new ArrayList<String>();
   for(Object raw:list){if(!(raw instanceof String c)||c.isBlank()||c.length()>1000||c.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid command in commands."+id);values.add(c.strip());}
   parsed.put(id,List.copyOf(values));
  }
  return Map.copyOf(parsed);
 }
}
