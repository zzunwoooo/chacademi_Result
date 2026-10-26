package school.chacademia.magicmythic;
import java.util.Set;
/** Ownership, tame state, mythic names and old configurable deny lists are not combat exclusions. */
final class TargetPolicy {
 static boolean excluded(boolean armor,boolean npc,Set<String> tags){
  return armor || npc || tags.contains("chacademia_npc");
 }
}
