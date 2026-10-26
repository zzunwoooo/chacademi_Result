package school.magiccodex.paper;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TargetHudProtocol;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TargetHudAimTest {
 @Test void aimRoundtripCoversFirstPersonAndShoulderOffset(){
  for(var a:List.of(new TargetHudProtocol.Aim(0,0,0,20,-30),new TargetHudProtocol.Aim(2,.5f,-3,120,40),new TargetHudProtocol.Aim(8,0,0,-180,90))){
   byte[] b=TargetHudProtocol.encodeAim(a);assertEquals(21,b.length);assertEquals(a,TargetHudProtocol.decodeAim(b));
  }
 }
 @Test void rejectsNonFiniteOffsetsAnglesAndOutOfBoundsCamera(){
  for(float v:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
   assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(v,0,0,0,0));
   assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(0,v,0,0,0));
   assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(0,0,v,0,0));
   assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(0,0,0,v,0));
   assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(0,0,0,0,v));
  }
  assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(8,.1f,0,0,0));
  assertThrows(IllegalArgumentException.class,()->new TargetHudProtocol.Aim(0,0,0,0,91));
 }
 @Test void rejectsWrongLengthVersionAndMalformedEncodedFloat(){
  byte[] b=TargetHudProtocol.encodeAim(new TargetHudProtocol.Aim(0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->TargetHudProtocol.decodeAim(null));
  for(int n:new int[]{0,20,22,4096})assertThrows(IllegalArgumentException.class,()->TargetHudProtocol.decodeAim(Arrays.copyOf(b,n)));
  byte[] wrong=b.clone();wrong[0]=2;assertThrows(IllegalArgumentException.class,()->TargetHudProtocol.decodeAim(wrong));
  byte[] nan=b.clone();java.nio.ByteBuffer.wrap(nan).putFloat(1,Float.NaN);assertThrows(IllegalArgumentException.class,()->TargetHudProtocol.decodeAim(nan));
 }
 record Scene(World world,Player player,LivingEntity eyeMob,LivingEntity cameraMob,TamingBridge taming){}
 Scene scene(){
  World w=mock(World.class);Player p=mock(Player.class);LivingEntity a=mock(LivingEntity.class),b=mock(LivingEntity.class);
  when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.isOnline()).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);when(p.getWorld()).thenReturn(w);
  when(p.getEyeLocation()).thenAnswer(x->new Location(w,0,64,0,0,0));when(p.getLocation()).thenReturn(new Location(w,0,63,0));
  for(LivingEntity e:List.of(a,b)){when(e.isValid()).thenReturn(true);when(e.getWorld()).thenReturn(w);when(e.getScoreboardTags()).thenReturn(Set.of());}
  when(a.getLocation()).thenReturn(new Location(w,0,64,10));when(b.getLocation()).thenReturn(new Location(w,2,64,10));
  when(w.rayTraceEntities(any(Location.class),any(org.bukkit.util.Vector.class),anyDouble(),eq(.25),any())).thenAnswer(call->{
   Location from=call.getArgument(0);org.bukkit.util.Vector dir=call.getArgument(1);double limit=call.getArgument(2);RayTraceResult result=null;double best=Double.POSITIVE_INFINITY;
   for(LivingEntity e:List.of(a,b)){Location at=e.getLocation();BoundingBox box=new BoundingBox(at.getX()-.3,63.5,9.7,at.getX()+.3,64.5,10.3);RayTraceResult hit=box.rayTrace(from.toVector(),dir,limit);if(hit!=null){double d=hit.getHitPosition().distanceSquared(from.toVector());if(d<best){best=d;result=new RayTraceResult(hit.getHitPosition(),e);}}}
   return result;
  });
  TamingBridge taming=mock(TamingBridge.class,CALLS_REAL_METHODS);when(taming.pets()).thenReturn(mock(PetBridge.class));return new Scene(w,p,a,b,taming);
 }
 Object look(Scene s,TargetHudProtocol.Aim ray,long tick)throws Exception{
  TargetHudBridge hud=mock(TargetHudBridge.class,CALLS_REAL_METHODS);Map<UUID,Object> aims=new HashMap<>();
  if(ray!=null){Class<?> c=Class.forName(TargetHudBridge.class.getName()+"$Aim");Constructor<?> ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);aims.put(s.player().getUniqueId(),ctor.newInstance(ray,tick));}
  for(var pair:Map.of("aims",(Object)aims,"taming",s.taming(),"looked",new HashMap<>()).entrySet()){Field f=TargetHudBridge.class.getDeclaredField(pair.getKey());f.setAccessible(true);f.set(hud,pair.getValue());}
  Method look=TargetHudBridge.class.getDeclaredMethod("look",Player.class);look.setAccessible(true);
  try(var ignored=mockStatic(Bukkit.class)){when(Bukkit.getCurrentTick()).thenReturn(100);return look.invoke(hud,s.player());}
 }
 @Test void freshShoulderCameraSelectsCrosshairMobInsteadOfHeadRayMob()throws Exception{Scene s=scene();assertSame(s.eyeMob(),s.taming().lookTarget(s.player(),25));assertSame(s.cameraMob(),look(s,new TargetHudProtocol.Aim(2,0,0,0,0),100));}
 @Test void firstPersonZeroOffsetRetainsEyeRayTarget()throws Exception{Scene s=scene();assertSame(s.eyeMob(),look(s,new TargetHudProtocol.Aim(0,0,0,0,0),100));}
 @Test void absentAndStaleAimFallBackToExistingEyeRay()throws Exception{Scene s=scene();assertSame(s.eyeMob(),look(s,null,0));assertSame(s.eyeMob(),look(s,new TargetHudProtocol.Aim(2,0,0,0,0),69));}
 @Test void thirtyTickFreshnessBoundaryStillUsesCameraRay()throws Exception{Scene s=scene();assertSame(s.cameraMob(),look(s,new TargetHudProtocol.Aim(2,0,0,0,0),70));}
}