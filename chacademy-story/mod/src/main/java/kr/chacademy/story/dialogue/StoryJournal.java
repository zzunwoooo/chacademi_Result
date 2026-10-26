package kr.chacademy.story.dialogue;
import java.util.*;import java.util.function.*;import java.nio.file.*;
import net.minecraft.client.Minecraft;
/** The existing per-account ledger remains authoritative; this exposes read/display-only operations. */
public final class StoryJournal {
 private record Cached(long modified,Dialogue dialogue){}private static final Map<String,Cached> cache=new HashMap<>();
 public static void install(){try{Class<?> api=Class.forName("school.magiccodex.client.api.ExternalJournal");api.getMethod("register",Supplier.class,BiFunction.class).invoke(null,(Supplier<List<Map<String,String>>>)StoryJournal::records,(BiFunction<String,String,String>)StoryJournal::edit);}catch(ReflectiveOperationException|LinkageError ignored){}}
 private static LocalDialogueStore store()throws java.io.IOException{var p=Minecraft.getInstance().player;if(p==null)throw new java.io.IOException("No player");return new LocalDialogueStore(DialogueLoader.rootDir().resolve("_progress").resolve(p.getUUID()+".properties"));}
 private static Dialogue content(String id)throws java.io.IOException{if(ServerDialogues.get(id)!=null)return DialogueLoader.load(id);long m=Files.getLastModifiedTime(DialogueLoader.folder(id).resolve("dialogue.yml")).toMillis();var c=cache.get(id);if(c==null||c.modified()!=m){c=new Cached(m,DialogueLoader.load(id));cache.put(id,c);}return c.dialogue();}
 public static List<Map<String,String>> records(){var result=new ArrayList<Map<String,String>>();try{var ledger=store();var ids=new TreeSet<>(DialogueLoader.listIds());ids.addAll(ledger.journalIds());for(String id:ids.stream().limit(600).toList()){
   String title=ledger.journalTitle(id),description=ledger.journalDescription(id),chapter="";try{var d=content(id);title=d.title();description=d.description();chapter=d.chapter();}catch(java.io.IOException ignored){}
   result.add(Map.of("id",id,"title",title,"description",description,"state",ledger.journalState(id),"category","main","chapter",chapter));
  }}catch(java.io.IOException ignored){}return List.copyOf(result);}
 private static String edit(String id,String operation){try{var ledger=store();if(!ledger.journalIds().contains(id))content(id);return ledger.journalEdit(id,operation);}catch(java.io.IOException e){return "ERROR: "+e.getMessage();}}
 public static void granted(Dialogue d){try{Class.forName("school.magiccodex.client.api.ExternalJournal").getMethod("granted",String.class,String.class,String.class).invoke(null,d.id(),d.title(),d.description());}catch(ReflectiveOperationException|LinkageError ignored){}}
}
