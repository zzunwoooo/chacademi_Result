package school.chacademia.magicmythic;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
/** A16: falling spells land once, on a fixed tick, and the gameplay/visual configs agree on that tick. */
class MeteorFallTest {
 @Test void landsExactlyOnceOnTheComputedTick(){
  double sky=Math.sqrt(9+576+361);var f=new MeteorFall(68,sky,0.6);
  assertEquals(52,f.fallTicks);assertEquals(120,f.landingTick());
  int landed=0,at=-1;
  for(int t=1;t<=400;t++)if(f.tick()){landed++;at=t;}
  assertEquals(1,landed);assertEquals(120,at);assertEquals(MeteorFall.State.LANDED,f.state());
 }
 @Test void cancelBeforeLandingNeverLands(){
  var f=new MeteorFall(10,6,1);for(int t=0;t<5;t++)assertFalse(f.tick());
  assertEquals(MeteorFall.State.CHARGING,f.state());
  for(int t=0;t<6;t++)assertFalse(f.tick());assertEquals(MeteorFall.State.FALLING,f.state());
  f.cancel();for(int t=0;t<50;t++)assertFalse(f.tick());assertEquals(MeteorFall.State.CANCELLED,f.state());
 }
 @Test void degenerateInputsStillLandOnce(){
  var f=new MeteorFall(0,0,0);assertTrue(f.tick());assertFalse(f.tick());
 }
 private static Map<String,String> params(String line){var out=new HashMap<String,String>();for(String kv:line.substring(line.indexOf('{')+1,line.indexOf('}')).split(";"))if(kv.contains("="))out.put(kv.split("=",2)[0],kv.split("=",2)[1]);return out;}
 @Test void fallingStarUsesChameteorInSourceAndOperatingConfigAndMatchesTheVisual(){
  for(String file:List.of("mythicmobs/Skills/chacademi_spells.yml","../deployment/operating-config/MythicMobs/Skills/chacademi_spells.yml")){
   var skills=YamlConfiguration.loadConfiguration(Path.of(file).toFile()).getStringList("CHA_falling_star.Skills");
   assertEquals(1,skills.size(),file);assertTrue(skills.getFirst().startsWith("chameteor{"),file);
   var p=params(skills.getFirst());assertEquals("falling_star",p.get("spell"),file+": spell= missing");assertEquals("fire",p.get("element"),file);
   for(String bindings:List.of("../portable-vfx-runtime/paper/src/main/resources/spell-bindings.yml","../deployment/operating-config/PortableVFX/spell-bindings.yml")){
    var b=YamlConfiguration.loadConfiguration(Path.of(bindings).toFile()).getConfigurationSection("bindings.falling_star");assertNotNull(b,bindings);
    assertEquals(Double.parseDouble(p.get("speed")),b.getDouble("speed"),1e-9,bindings);
    var phases=b.getMapList("phases");
    var meteor=phases.stream().filter(m->"claude:fallingstar/meteor".equals(m.get("effect"))).findFirst().orElseThrow();
    assertEquals(Integer.parseInt(p.get("charge")),((Number)meteor.get("delay-ticks")).intValue(),bindings);
    var off=(List<?>)meteor.get("offset");
    assertEquals(Double.parseDouble(p.get("skyx")),((Number)off.get(0)).doubleValue(),1e-9);assertEquals(Double.parseDouble(p.get("skyy")),((Number)off.get(1)).doubleValue(),1e-9);assertEquals(Double.parseDouble(p.get("skyz")),((Number)off.get(2)).doubleValue(),1e-9);
    assertTrue(phases.stream().noneMatch(m->"impact".equals(m.get("trigger"))),bindings+": impact must come from the server");
    assertEquals(2,phases.stream().filter(m->"external:impact".equals(m.get("trigger"))).count(),bindings);
   }
  }
 }
}
