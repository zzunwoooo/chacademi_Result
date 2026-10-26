package school.chacademia.magicmythic;
import java.lang.reflect.*;import java.util.*;import org.bukkit.*;import org.bukkit.entity.Player;
/** A visual handle following an existing gameplay projectile; never simulates collision or damage. */
final class GameplayVisual implements AutoCloseable {
 static final Map<String,String> PROJECTILES=Map.of("flint_sorting","flintsorting","thorn_seeds","thornseeds","mountain_back","mountainback","primordial_stone","primordialstone","merciful_arrow","mercifularrow");
 private final Object service;private final Method update,finish,basis;private final UUID handle;private boolean closed;
 private GameplayVisual(Object service,Method update,Method finish,Method basis,UUID handle){this.service=service;this.update=update;this.finish=finish;this.basis=basis;this.handle=handle;}
 static GameplayVisual projectile(Player player,String spell,Location at,org.bukkit.util.Vector direction){
  String pack=PROJECTILES.get(spell);if(pack==null)return null;
  return cast(player.getUniqueId(),"claude:"+pack+"/"+(spell.equals("mountain_back")?"roll":"projectile"),at,direction);
 }
 /** Any PortableVFX projectile-role system by full id, moved by the caller (mob shots: chamobshot vfx=...). */
 static GameplayVisual cast(UUID owner,String id,Location at,org.bukkit.util.Vector direction){
  if(id==null||id.isBlank()||at.getWorld()==null)return null;
  id=id.toLowerCase(Locale.ROOT); // Config systems are canonicalized to lowercase resource IDs; event names retain their authored case.
  try{
   var plugin=Bukkit.getPluginManager().getPlugin("PortableVFX");if(plugin==null||!plugin.isEnabled())return null;var loader=plugin.getClass().getClassLoader();
   Class<?> api=Class.forName("dev.portablevfx.paper.api.PortableVfxService",true,loader),request=Class.forName("dev.portablevfx.paper.api.EffectRequest",true,loader),frame=Class.forName("dev.portablevfx.protocol.EffectBasis",true,loader);
   var registration=Bukkit.getServicesManager().getRegistration(api);if(registration==null)return null;Object service=registration.getProvider();
   Method basis=frame.getMethod("projectile",double.class,double.class,double.class);Object orientation=basis.invoke(null,direction.getX(),direction.getY(),direction.getZ());
   Object req=request.getConstructor(String.class,String.class,double.class,double.class,double.class,float.class,int.class,float.class,float.class,float.class,int.class,float.class,double.class).newInstance(id,at.getWorld().getName(),at.getX(),at.getY(),at.getZ(),1f,440,0f,0f,0f,0xffffff,1f,64d);
   Object result=api.getMethod("startCast",request,frame,long.class).invoke(service,req,orientation,UUID.randomUUID().getLeastSignificantBits());UUID handle=(UUID)result.getClass().getMethod("handle").invoke(result);
   if(owner!=null)api.getMethod("owner",UUID.class,UUID.class).invoke(service,handle,owner);
   return new GameplayVisual(service,api.getMethod("updateCast",UUID.class,double.class,double.class,double.class,frame),api.getMethod("finishCast",UUID.class,boolean.class),basis,handle);
  }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){return null;}
 }
 void move(Location at,org.bukkit.util.Vector direction){if(closed)return;try{update.invoke(service,handle,at.getX(),at.getY(),at.getZ(),basis.invoke(null,direction.getX(),direction.getY(),direction.getZ()));}catch(ReflectiveOperationException|RuntimeException ignored){close();}}
 public void close(){if(closed)return;closed=true;try{finish.invoke(service,handle,true);}catch(ReflectiveOperationException|RuntimeException ignored){}}
}
