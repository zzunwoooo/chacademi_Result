package school.magiccodex.client;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
/** Preserve vanilla's actual rebound sneak input while riding, including its release after dismount. */
final class BroomClientLink {
 private static java.lang.reflect.Method active;
 private static final BroomKeyReservation reservation=new BroomKeyReservation();
 static boolean riding(){
  if(!FabricLoader.getInstance().isModLoaded("chacademy_motion"))return false;
  try{if(active==null)active=Class.forName("kr.chacademy.motion.broom.BroomFlight").getMethod("active");return Boolean.TRUE.equals(active.invoke(null));}
  catch(ReflectiveOperationException|LinkageError e){return false;}
 }
 static boolean reserve(MinecraftClient c,boolean matches,int action){
  if(!CastingClient.gameplay(c)){reservation.clear();return false;}
  return reservation.input(matches,riding(),action);
 }
 static boolean reserved(){return reservation.held()||riding();}
 static void clear(){reservation.clear();}
}
