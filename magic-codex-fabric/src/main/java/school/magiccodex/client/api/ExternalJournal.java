package school.magiccodex.client.api;
import java.util.*;import java.util.function.*;
/** JDK-only API for Story's Mojang-mapped mod; display edits never execute story effects. */
public final class ExternalJournal {
 private static Supplier<List<Map<String,String>>> reader=List::of;
 private static BiFunction<String,String,String> editor=(id,op)->"ERROR: Story provider unavailable";
 public static void register(Supplier<List<Map<String,String>>> read,BiFunction<String,String,String> edit){reader=Objects.requireNonNull(read);editor=Objects.requireNonNull(edit);}
 public static List<Map<String,String>> records(){return reader.get();}
 public static void granted(String id,String title,String description){school.magiccodex.client.SharedNotifications.offer("story:"+id,"quest",title,description);}
 public static String edit(String id,String operation){return editor.apply(id,operation);}
}
