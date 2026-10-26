package dev.portablevfx.client.claude;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaudeStopDepthTest {
 @Test void parserAcceptsDepthAndStopAndPreservesDefaults() throws Exception {
  var e=ClaudeEffect.parse(json(true,"jumpToTail",.3).toString());assertTrue(e.materials().get("glow").depthWrite());
  assertEquals(new StopBehavior("jumpToTail",.3),e.systems().getFirst().layers().getFirst().onStop());
  var j=json(false,"finish",0);j.getAsJsonObject("materials").getAsJsonObject("glow").remove("depthWrite");layer(j).remove("onStop");
  e=ClaudeEffect.parse(j.toString());assertFalse(e.materials().get("glow").depthWrite());assertEquals(StopBehavior.FINISH,e.systems().getFirst().layers().getFirst().onStop());
 }
 @Test void strictParserRejectsUnsupportedAndMisdeclaredBehavior() throws Exception {
  var a=json(true,"finish",0);a.getAsJsonObject("materials").getAsJsonObject("glow").addProperty("blend","additive");
  assertTrue(assertThrows(ValidationException.class,()->ClaudeEffect.parse(a.toString())).getMessage().contains("require alpha blend"));
  var n=json(false,"jumpToTail",-.1);assertThrows(ValidationException.class,()->ClaudeEffect.parse(n.toString()));
  var u=json(false,"jumpToTail",.1);layer(u).getAsJsonObject("onStop").addProperty("mode","erase");assertThrows(ValidationException.class,()->ClaudeEffect.parse(u.toString()));
  var f=json(false,"finish",0);layer(f).getAsJsonObject("onStop").addProperty("mystery",true);assertThrows(ValidationException.class,()->ClaudeEffect.parse(f.toString()));
  var m=json(true,"finish",0);m.getAsJsonObject("features").getAsJsonArray("required").remove(new JsonPrimitive("depthWrite"));assertThrows(ValidationException.class,()->ClaudeEffect.parse(m.toString()));
 }
 @Test void normalFinishPreservesLocalsAndNaturalCurves() throws Exception {
  var s=sim("local",StopBehavior.FINISH);s.advance(.2,Transform.IDENTITY);var before=s.snapshot();s.stopEmission(false);assertEquals(before,s.snapshot());
  s.advance(.1,Transform.IDENTITY);assertEquals(.3,s.snapshot().getFirst().age(),1e-12);s.advance(2,Transform.IDENTITY);assertTrue(s.isFinished());
 }
 @Test void tailJumpIsExactAndDoesNotMoveOrAgeTrailSamples() throws Exception {
  var s=sim("local",new StopBehavior("jumpToTail",.317));s.advance(.2,Transform.IDENTITY);var b=s.snapshot().getFirst();s.stopEmission(false);var a=s.snapshot().getFirst();
  assertEquals(1.683,a.age(),1e-12);assertEquals(b.worldPosition(),a.worldPosition());assertEquals(b.worldVelocity(),a.worldVelocity());assertEquals(b.trail(),a.trail());
  assertEquals(1.683,s.renderSnapshot().getFirst().age(),1e-12);s.advance(.1,Transform.IDENTITY);assertEquals(1.783,s.snapshot().getFirst().age(),1e-12);
  assertTrue(s.snapshot().getFirst().trail().stream().anyMatch(p->p.age()>.2));s.stopEmission(false);assertEquals(1.783,s.snapshot().getFirst().age(),1e-12);
  s.advance(.3,Transform.IDENTITY);assertTrue(s.isFinished());
 }
 @Test void shortRemainingLifeIsNeverExtended() throws Exception {
  var s=sim("world",new StopBehavior("jumpToTail",.5));s.advance(1.8,Transform.IDENTITY);var b=s.snapshot();s.stopEmission(false);assertEquals(b,s.snapshot());s.advance(.3,Transform.IDENTITY);assertTrue(s.isFinished());
 }
 @Test void zeroTailRemovesImmediately() throws Exception {
  var s=sim("local",new StopBehavior("jumpToTail",0));s.advance(.2,Transform.IDENTITY);s.stopEmission(false);assertTrue(s.snapshot().isEmpty());assertTrue(s.renderSnapshot().isEmpty());assertTrue(s.isFinished());
 }
 @Test void hitClearsLocalsButRetainedWorldParticlesApplyTail() throws Exception {
  var l=sim("local",new StopBehavior("jumpToTail",.3));l.advance(.2,Transform.IDENTITY);l.stopEmission(true);assertTrue(l.isFinished());
  var w=sim("world",new StopBehavior("jumpToTail",.3));w.advance(.2,Transform.IDENTITY);w.stopEmission(true);assertEquals(1.7,w.snapshot().getFirst().age(),1e-12);w.advance(.31,Transform.IDENTITY);assertTrue(w.isFinished());
 }
 @Test void veryLongLifeIsCutToFinishLingerCap() throws Exception {
  /* 메모 #79: 수명 10초 입자는 FINISH 뒤 MAX_FINISH_LINGER 초만 남는다. */
  var s=sim("local",StopBehavior.FINISH,10);s.advance(.2,Transform.IDENTITY);s.stopEmission(false);
  assertEquals(10-ClaudeSimulation.MAX_FINISH_LINGER,s.snapshot().getFirst().age(),1e-12);
  s.advance(ClaudeSimulation.MAX_FINISH_LINGER+.05,Transform.IDENTITY);assertTrue(s.isFinished());
 }
 private static ClaudeSimulation sim(String space,StopBehavior stop)throws Exception { return sim(space,stop,2); }
 private static ClaudeSimulation sim(String space,StopBehavior stop,double life)throws Exception {
  var e=ClaudeEffect.parse(json(false,"finish",0).toString());var b=e.systems().getFirst().layers().getFirst();Range z=new Range(0,0);Range3 z3=new Range3(z,z,z);
  Trail trail=new Trail(b.material(),1,0,List.of(new CurveKey(0,1,0,0)),List.of(new ColorKey(0,Vec3.X)));
  Layer l=new Layer("test",b.material(),b.render(),0,space,Vec3.ZERO,new Emission(0,0,List.of(new Burst(0,1,1,0))),new Shape("none",0,1,Vec3.ZERO,0),new Start(new Range(life,life),new Range(1,1),new Range(1,1),z,null),z3,new Velocity("local",z3,z,z),0,0,null,b.colorOverLifetime(),b.sizeOverLifetime(),null,trail,Set.of(),stop);
  return new ClaudeSimulation(new ClaudeEffect("test","1.9.0",1.0/120,9.81,e.post(),e.materials(),e.meshes(),List.of(new SystemDef("test","attached",10,true,List.of(l))),Set.of()),"test",Transform.IDENTITY,0);
 }
 private static JsonObject layer(JsonObject j){return j.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();}
 static JsonObject json(boolean depth,String mode,double tail)throws Exception {
  var path=Path.of(ClaudeStopDepthTest.class.getResource("/claude/samples/Fireball/vfx.json").toURI());var j=JsonParser.parseString(Files.readString(path)).getAsJsonObject();j.addProperty("schemaVersion","1.13.0");
  var s=j.getAsJsonArray("systems").get(0).getAsJsonObject().deepCopy();s.addProperty("id","test");s.addProperty("role","static");var l=s.getAsJsonArray("layers").get(0).getAsJsonObject().deepCopy();l.addProperty("id","test");
  var ls=new JsonArray();ls.add(l);s.add("layers",ls);var ss=new JsonArray();ss.add(s);j.add("systems",ss);
  var m=j.getAsJsonObject("materials").getAsJsonObject("glow").deepCopy();m.addProperty("blend","alpha");m.addProperty("depthWrite",depth);var ms=new JsonObject();ms.add("glow",m);j.add("materials",ms);
  var stop=new JsonObject();stop.addProperty("mode",mode);stop.addProperty("tail",tail);l.add("onStop",stop);var r=new JsonArray();for(String f:List.of("alphaBlend","billboard","hdrTint"))r.add(f);if(depth)r.add("depthWrite");if(mode.equals("jumpToTail"))r.add("stopTail");l.add("requires",r);
  var all=r.deepCopy();all.add("bloom");all.add("tonemapNeutral");var by=new JsonObject();by.add("test/test",r.deepCopy());j.getAsJsonObject("features").add("required",all);j.getAsJsonObject("features").add("byLayer",by);return j;
 }
}
