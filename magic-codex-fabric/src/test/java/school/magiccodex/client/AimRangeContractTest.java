package school.magiccodex.client;
import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.Test;import org.yaml.snakeyaml.Yaml;import static org.junit.jupiter.api.Assertions.*;
class AimRangeContractTest {
 @Test void allPreviewTargetRangesMatchServerRuntimeYaml()throws Exception{
  var shapes=SpellTargets.parse(Files.readString(Path.of("src/main/resources/assets/magiccodex/spell-targeting.json")));
  Map<?,?> root=new Yaml().load(Files.readString(Path.of("../magic-mythic-paper/server-config/spell-runtime.yml")));
  Map<?,?> routes=(Map<?,?>)root.get("spells");assertNotNull(routes);
  for(var entry:shapes.entrySet()){Map<?,?> route=(Map<?,?>)routes.get(entry.getKey());assertNotNull(route,entry.getKey());assertEquals(((Number)route.get("range")).doubleValue(),entry.getValue().range(),1e-9,entry.getKey());}
 }
 /** 1-6 #6: the server knows the same ground spells the client snaps to the ground (horizontal range, ground drop). */
 @Test void groundFlagMatchesGroundedPreviewShapes()throws Exception{
  var shapes=SpellTargets.parse(Files.readString(Path.of("src/main/resources/assets/magiccodex/spell-targeting.json")));
  Map<?,?> routes=(Map<?,?>)((Map<?,?>)new Yaml().load(Files.readString(Path.of("../magic-mythic-paper/server-config/spell-runtime.yml")))).get("spells");
  int ground=0;
  for(var entry:shapes.entrySet()){Map<?,?> route=(Map<?,?>)routes.get(entry.getKey());
   boolean expected="AIM".equals(route.get("target"))&&AimTargeting.grounded(entry.getValue().mode());
   assertEquals(expected,Boolean.TRUE.equals(route.get("ground")),entry.getKey());if(expected)ground++;}
  assertTrue(ground>10);
  assertTrue(AimMath.horizontalWithin(0,0,3,4,5));assertFalse(AimMath.horizontalWithin(0,0,3,4.1,5));
 }
 @Test void coneAndWaveKeepMechanicalEnvelopeSeparateFromTargetRay()throws Exception{
  var shapes=SpellTargets.parse(Files.readString(Path.of("src/main/resources/assets/magiccodex/spell-targeting.json")));
  var cone=shapes.get("crimson_breath");assertEquals(26,cone.range());assertEquals(9,cone.length());assertEquals(50,cone.width());
  var firebird=shapes.get("firebird_hunt");assertEquals(17,firebird.range());assertEquals(20,firebird.length());assertEquals(3.2,firebird.width());
  assertTrue(shapes.get("tidal_wave").casterOrigin());assertFalse(shapes.get("ember_bolt").casterOrigin());
 }
}
