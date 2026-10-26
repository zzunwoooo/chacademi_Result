package school.magiccodex.client;
import java.util.*;
/** Deduplicate responses by role/operation/sequence, not by their text. */
final class ConversationHistory {
 record Entry(String speaker,String text){}
 private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
 void add(String key,String speaker,String text){
  if(text==null||text.isEmpty()||entries.containsKey(key))return;
  entries.put(key,new Entry(speaker,text));if(entries.size()>80)entries.remove(entries.keySet().iterator().next());
 }
 List<Entry> entries(){return List.copyOf(entries.values());}
}