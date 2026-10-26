package school.magiccodex.paper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Set;
/** Optional Motion link. No mana or cooldown changes here. */
final class BroomCastGuard {
 /** Owner 2026-10-17: spells that move the rider are refused on a broom. #35 (2026-10-25): the two leap-to-target spells
  *  (MythicMobs leap = server velocity on the rider, same kind as 상승 기류) were missing. */
 private static final Set<String> MOVES_CASTER=Set.of("gale_dash","light_leap","hunting_pounce","roar_leap");
 static boolean blocked(String spell,boolean riding){return riding&&(MOVES_CASTER.contains(spell)||"flight".equals(spell));}
 /** Owner 2026-10-17: the flight key while riding ends the flight (no mana, no cooldown). @return true when it dismounted. */
 static boolean endFlight(Player player,String spell){
  if(!"flight".equals(spell))return false;
  var plugin=Bukkit.getPluginManager().getPlugin("ChacademyMotion");
  if(plugin==null||!plugin.isEnabled())return false;
  try{
   if(!Boolean.TRUE.equals(plugin.getClass().getMethod("isRidingBroom",Player.class).invoke(plugin,player)))return false;
   plugin.getClass().getMethod("stopBroom",Player.class).invoke(plugin,player);return true;
  }catch(ReflectiveOperationException|LinkageError e){return false;}
 }
 static boolean blocked(Player player,String spell){
  if(!MOVES_CASTER.contains(spell)&&!"flight".equals(spell))return false;
  var plugin=Bukkit.getPluginManager().getPlugin("ChacademyMotion");
  if(plugin==null||!plugin.isEnabled())return false;
  try{return blocked(spell,Boolean.TRUE.equals(plugin.getClass().getMethod("isRidingBroom",Player.class).invoke(plugin,player)));}
  catch(ReflectiveOperationException|LinkageError e){return false;}
 }
}
