package school.chacademia.gimmick;
import org.bukkit.*;
import org.bukkit.entity.*;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
final class GimmickProtection {
 interface Regions { boolean allowed(Location at); }
 private final Regions regions;
 GimmickProtection(){this(GimmickProtection::query);}
 GimmickProtection(Regions regions){this.regions=regions;}
 boolean at(Location at){if(at==null||at.getWorld()==null)return false;try{return regions.allowed(at);}catch(RuntimeException|LinkageError e){return false;}}
 boolean pair(Player p,Location at){return p!=null&&p.isOnline()&&p.getGameMode()!=GameMode.SPECTATOR&&at(p.getLocation())&&at(at);}
 private static boolean query(Location at){
  if(!Bukkit.getPluginManager().isPluginEnabled("WorldGuard"))return false;
  var flag=WorldGuard.getInstance().getFlagRegistry().get("chacademi-magic");if(!(flag instanceof StateFlag magic))return false;
  var q=WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
  // Direct flags, never OP/session bypass. Both magical and physical interaction respect explicit denies.
  return q.queryState(BukkitAdapter.adapt(at),(RegionAssociable)null,magic,Flags.BUILD,Flags.INTERACT)!=StateFlag.State.DENY;
 }
 static boolean excluded(LivingEntity e){
  if(e instanceof Player||e instanceof ArmorStand||e.hasMetadata("NPC")||e.getScoreboardTags().contains("chacademia_pet")||e instanceof Tameable t&&t.isTamed()||e instanceof AbstractHorse h&&h.isTamed())return true;
  var pets=Bukkit.getPluginManager().getPlugin("MCPets");if(pets==null||!pets.isEnabled())return false;
  try{return Class.forName("fr.nocsy.mcpets.data.Pet",false,pets.getClass().getClassLoader()).getMethod("getFromEntity",Entity.class).invoke(null,e)!=null;}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return true;}
 }
}
