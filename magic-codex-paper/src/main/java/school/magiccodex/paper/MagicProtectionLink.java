package school.magiccodex.paper;
import org.bukkit.*;import org.bukkit.entity.*;
final class MagicProtectionLink {
 private static boolean call(String method,Class<?>[] types,Object... args){
  var p=Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");if(p==null||!p.isEnabled())return false;
  try{return Boolean.TRUE.equals(p.getClass().getMethod(method,types).invoke(p,args));}catch(ReflectiveOperationException|RuntimeException|LinkageError e){return false;}
 }
 static boolean cast(Player p){return call("canCast",new Class[]{Player.class},p);}
 static boolean at(Player p,Location l){return call("canAffect",new Class[]{Player.class,Location.class},p,l);}
 static boolean target(Player p,Entity e){return call("canTarget",new Class[]{Player.class,Entity.class},p,e);}
 static boolean touch(Player p,Entity e){return call("canTouch",new Class[]{Player.class,Entity.class},p,e);}
 /** Status effect from ChAcademiaMagicMythic (stun, burn, ...). Without the plugin: burn falls back to vanilla fire. */
 static boolean status(LivingEntity e,String id){
  var p=Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
  if(p==null||!p.isEnabled())return id.equals("burn")&&e.getFireTicks()>0;
  try{return Boolean.TRUE.equals(p.getClass().getMethod("hasStatus",LivingEntity.class,String.class).invoke(p,e,id));}
  catch(NoSuchMethodException old){return id.equals("burn")&&e.getFireTicks()>0;}
  catch(ReflectiveOperationException|RuntimeException|LinkageError ex){return false;}
 }
}
