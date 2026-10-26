package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.*;import org.bukkit.entity.*;import org.bukkit.damage.DamageSource;import org.bukkit.event.entity.*;
import java.lang.reflect.*;import java.util.*;import java.util.function.*;
import com.sk89q.worldguard.protection.*;import com.sk89q.worldguard.protection.regions.*;import com.sk89q.worldguard.protection.flags.*;import com.sk89q.worldguard.protection.flags.registry.*;import com.sk89q.worldedit.math.BlockVector3;
import static org.junit.jupiter.api.Assertions.*;
class ProtectionTest {
 @SuppressWarnings("unchecked") static <T>T mock(Class<T> type,BiFunction<String,Object[],Object> fn){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()){case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);case "toString"->type.getSimpleName();default->fn.apply(m.getName(),a);});}
 World world=mock(World.class,(m,a)->m.equals("getName")?"school":null);
 Player player(Location[] at,BiFunction<String,Object[],Object> extra){return mock(Player.class,(m,a)->switch(m){case "isValid"->true;case "getLocation"->at[0];case "isOp","hasPermission"->throw new AssertionError("OP bypass must never be consulted");default->extra.apply(m,a);});}
 @Test void castAndIncomingEffectsRejectDenyAndRecheckMovingTargets(){
  var gate=new MagicProtection((at,flag)->flag.equals("magic")&&at.getX()>=10?false:null);
  Location[] from={new Location(world,0,64,0)},to={new Location(world,20,64,0)};var caster=player(from,(m,a)->null);var target=player(to,(m,a)->null);
  assertTrue(gate.at(from[0]));assertFalse(gate.at(to[0]));assertFalse(gate.pair(caster,to[0]));assertFalse(gate.harm(caster,target));
  to[0]=new Location(world,2,64,0);assertTrue(gate.harm(caster,target));from[0]=new Location(world,11,64,0);assertFalse(gate.pair(caster,to[0]));
 }
 @Test void worldguardPvpAndInvincibilityAndUnavailableQueryFailClosed(){
  var caster=player(new Location[]{new Location(world,0,0,0)},(m,a)->null);var victim=player(new Location[]{new Location(world,1,0,0)},(m,a)->null);
  assertFalse(new MagicProtection((at,f)->f.equals("pvp")?false:null).harm(caster,victim));
  assertFalse(new MagicProtection((at,f)->f.equals("invincible")?true:null).harm(caster,victim));
  assertFalse(new MagicProtection((at,f)->{throw new IllegalStateException();}).at(caster.getLocation()));
  assertFalse(new MagicProtection().at(caster.getLocation()));
 }
 @Test void actualWorldguardFlagConflictAndDenyResolution(){
  var registry=new SimpleFlagRegistry();var flag=MagicProtection.claim(registry);assertSame(flag,registry.get("chacademi-magic"));
  assertThrows(IllegalStateException.class,()->MagicProtection.claim(registry));
  var wrong=new SimpleFlagRegistry();wrong.register(new StringFlag("chacademi-magic"));assertThrows(IllegalStateException.class,()->MagicProtection.claim(wrong));
  var region=new ProtectedCuboidRegion("restricted",BlockVector3.at(0,0,0),BlockVector3.at(10,100,10));region.setFlag(flag,StateFlag.State.DENY);
  assertEquals(StateFlag.State.DENY,new RegionResultSet(Set.of(region),null).queryState(null,flag));
 }
 @Test void cancelledExecutionDamageIsNotConvertedIntoForcedDeath(){
  var gate=new MagicProtection((at,f)->null);var source=mock(DamageSource.class,(m,a)->null);
  var caster=player(new Location[]{new Location(world,0,64,0)},(m,a)->null);boolean[] cancel={true};Player[] victim={null};int[] calls={0};
  victim[0]=player(new Location[]{new Location(world,1,64,0)},(m,a)->{if(m.equals("setHealth"))throw new AssertionError("Never force death");if(m.equals("damage")){calls[0]++;var e=new EntityDamageByEntityEvent(caster,victim[0],EntityDamageEvent.DamageCause.MAGIC,source,(Double)a[0]);e.setCancelled(cancel[0]);gate.observe(e);}return null;});
  assertFalse(gate.damage(caster,victim[0],999));assertEquals(1,calls[0]);cancel[0]=false;assertTrue(gate.damage(caster,victim[0],999));
 }
 @Test void onlyNpcsAndNonLivingDecorationsAreExcluded(){
  assertTrue(TargetPolicy.excluded(true,false,Set.of()));
  assertTrue(TargetPolicy.excluded(false,true,Set.of()));
  assertTrue(TargetPolicy.excluded(false,false,Set.of("chacademia_npc")));
  for(String tag:List.of("chacademia_pet","chacademia_taming_fx","owned","CHA_pet","GM_gate"))
   assertFalse(TargetPolicy.excluded(false,false,Set.of(tag)));
 }
 @Test void poisonCannotUseSupportPathToBypassPvpButSupportAudienceUnchanged(){
  for(String type:List.of("poison","instant_damage","wither","unknown")){assertFalse(SupportPolicy.beneficial(type));assertFalse(SupportPolicy.permits(false,false,false));assertTrue(SupportPolicy.permits(false,false,true));}
  assertTrue(SupportPolicy.permits(SupportPolicy.beneficial("regeneration"),false,false));assertTrue(SupportPolicy.permits(false,true,false));
 }
}
