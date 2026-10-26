package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.util.*;
import school.magiccodex.protocol.TelegraphProtocol;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TelegraphBoundaryTest {
 @Test void circleDoesNotDamageOutsideDrawnRadius()throws Exception{boundary(TelegraphProtocol.Shape.CIRCLE,2.99,0,3.01,0);}
 @Test void lineDoesNotDamageOutsideDrawnWidth()throws Exception{boundary(TelegraphProtocol.Shape.LINE,.99,4,1.01,4);}
 @Test void coneDoesNotDamageOutsideDrawnRadius()throws Exception{boundary(TelegraphProtocol.Shape.CONE,0,2.99,0,3.01);}
 void boundary(TelegraphProtocol.Shape shape,double ix,double iz,double ox,double oz)throws Exception{
  var world=mock(World.class);var plugin=mock(MagicMythicPlugin.class);var targets=mock(Targets.class);when(plugin.targets()).thenReturn(targets);var caster=mock(LivingEntity.class);when(caster.isValid()).thenReturn(true);when(caster.getWorld()).thenReturn(world);
  var inside=mock(Player.class);var outside=mock(Player.class);when(inside.getLocation()).thenReturn(new Location(world,ix,64,iz));when(outside.getLocation()).thenReturn(new Location(world,ox,64,oz));when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of(inside,outside));when(targets.enemy(eq(caster),any())).thenReturn(true);
  var mech=new TelegraphMech(plugin,new Params(Map.of()));var method=TelegraphMech.class.getDeclaredMethod("hit",LivingEntity.class,TelegraphProtocol.Show.class,double.class);method.setAccessible(true);method.invoke(mech,caster,new TelegraphProtocol.Show(1,shape,0,64,0,0,shape==TelegraphProtocol.Shape.LINE?8:3,2,60,20,TelegraphProtocol.RED),8d);
  verify(targets).hurt(caster,inside,8,"");verify(targets,never()).hurt(eq(caster),eq(outside),anyDouble(),anyString());
 }
}
