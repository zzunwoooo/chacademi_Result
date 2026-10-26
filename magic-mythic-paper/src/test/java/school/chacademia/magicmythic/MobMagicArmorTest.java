package school.chacademia.magicmythic;
import org.junit.jupiter.api.*;
import org.bukkit.*;import org.bukkit.attribute.AttributeInstance;import org.bukkit.configuration.file.YamlConfiguration;import org.bukkit.entity.*;
import static org.junit.jupiter.api.Assertions.*;
/** 몹 스펙 #109 (2026-10-26): 몹이 플레이어에게 쓰는 마법은 마법 방어(상한 45%), 플레이어→몹은 그대로. */
class MobMagicArmorTest {
 World world=ProtectionTest.mock(World.class,(m,a)->m.equals("getName")?"wild":null);
 Location at=new Location(world,0,64,0);
 @AfterEach void reset(){MagicArmor.current=MagicArmor.Settings.DEFAULT;}
 Player player(double armor,double[] got){
  var attr=ProtectionTest.mock(AttributeInstance.class,(m,a)->m.equals("getValue")?armor:null);
  return ProtectionTest.mock(Player.class,(m,a)->switch(m){case "isValid"->true;case "getLocation"->at;case "getAttribute"->attr;case "getHealth"->20.0;
   case "damage"->{got[0]=(Double)a[0];yield null;}default->null;});
 }
 Zombie mob(double[] got){return ProtectionTest.mock(Zombie.class,(m,a)->switch(m){case "isValid"->true;case "getLocation"->at;case "getHealth"->40.0;case "damage"->{got[0]=(Double)a[0];yield null;}default->null;});}
 @Test void settingDefaultsOnAndCanBeTurnedOff(){
  assertTrue(MagicArmor.Settings.DEFAULT.mobMagicArmor());
  assertTrue(MagicArmor.Settings.load(new YamlConfiguration()).mobMagicArmor());
  var y=new YamlConfiguration();y.set("mob-magic-armor",false);assertFalse(MagicArmor.Settings.load(y).mobMagicArmor());
 }
 @Test void mobSpellOnPlayerIsReducedByMagicArmor(){
  double[] hit={-1},unused={-1};var gate=new MagicProtection((l,f)->null);
  var p=player(20,hit);var m=mob(unused);
  assertTrue(MagicProtection.mobMagic(m,p));assertFalse(MagicProtection.pvp(m,p));
  gate.damage(m,p,10);assertEquals(7.5,hit[0],1e-9); // 다이아 방어력 20 → 25%
  var heavy=player(40,hit);gate.damage(m,heavy,10);assertEquals(5.5,hit[0],1e-9); // 상한 45%
 }
 @Test void playerSpellOnMobAndSwitchedOffStayVanilla(){
  double[] hit={-1},onMob={-1};var gate=new MagicProtection((l,f)->null);
  var p=player(20,hit);var m=mob(onMob);
  assertFalse(MagicProtection.mobMagic(p,m));gate.damage(p,m,10);assertEquals(10,onMob[0],1e-9);
  var off=MagicArmor.Settings.DEFAULT;MagicArmor.current=new MagicArmor.Settings(true,off.cap(),off.perPoint(),off.epfMax(),off.resistanceCap(),off.fallbackScale(),true,false);
  assertFalse(MagicProtection.mobMagic(m,p));gate.damage(m,p,10);assertEquals(10,hit[0],1e-9);
 }
}
