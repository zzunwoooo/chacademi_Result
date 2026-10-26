package dev.portablevfx.client.claude;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaudePathScaleTest {
 private static final double H=1.0/120;
 @Test void strictPathParsingRequiresLinkLocalAndNoCompetingMotion()throws Exception {
  var j=jsonPath();var e=ClaudeEffect.parse(j.toString());assertNotNull(e.system("test").layers().getFirst().path());
  var world=j.deepCopy();layer(world).addProperty("space","world");assertThrows(ValidationException.class,()->ClaudeEffect.parse(world.toString()));
  var speed=j.deepCopy();layer(speed).getAsJsonObject("start").add("speed",JsonParser.parseString("[0,1]"));assertThrows(ValidationException.class,()->ClaudeEffect.parse(speed.toString()));
  var role=j.deepCopy();role.getAsJsonArray("systems").get(0).getAsJsonObject().addProperty("role","static");assertThrows(ValidationException.class,()->ClaudeEffect.parse(role.toString()));
  var field=j.deepCopy();layer(field).getAsJsonObject("path").addProperty("unsupported",1);assertThrows(ValidationException.class,()->ClaudeEffect.parse(field.toString()));
 }
 @Test void scaleAndReversedRangesPreserveSamplingAndValidateBothEndpoints()throws Exception {
  var j=ClaudeStopDepthTest.json(false,"finish",0);j.add("scale",JsonParser.parseString("{\"input\":\"areaRadius\",\"unit\":\"m\",\"reference\":4,\"min\":0.5,\"max\":2.5}"));
  j.getAsJsonObject("features").getAsJsonArray("required").add("instanceScale");layer(j).getAsJsonObject("start").add("size",JsonParser.parseString("[3,1]"));
  var e=ClaudeEffect.parse(j.toString());assertEquals(2,e.scale().factor(8.0));assertEquals(.5,e.scale().factor(.1));assertEquals(2.5,e.scale().factor(100.0));assertEquals(1,e.scale().factor(null));
  var r=e.system("test").layers().getFirst().start().size();assertEquals(3,r.min());assertEquals(1,r.max());assertEquals(3-2*new Random(7).nextDouble(),r.sample(new Random(7)));
  layer(j).getAsJsonObject("start").add("size",JsonParser.parseString("[3,-1]"));assertThrows(ValidationException.class,()->ClaudeEffect.parse(j.toString()));
 }
 @Test void linkInputIsRequiredAndZeroLengthHasSafeBasis()throws Exception {
  var e=fixture(true,false,false);var s=new ClaudeSimulation(e,"test",Transform.IDENTITY,0);
  assertThrows(IllegalStateException.class,()->s.advance(.1,Transform.IDENTITY));s.linkLength(0);s.advance(.1,Transform.IDENTITY);assertFalse(s.snapshot().isEmpty());
  assertEquals(Basis.IDENTITY,Transform.link(Vec3.ZERO,Vec3.ZERO).basis());
  var vertical=Transform.link(Vec3.ZERO,new Vec3(0,10,0)).basis();assertEquals(Vec3.X,vertical.right());assertEquals(Vec3.Y,vertical.forward());
  assertThrows(IllegalArgumentException.class,()->s.linkLength(-1));
 }
 @Test void pathUsesHermiteAndSampledBendAndLiveTarget()throws Exception {
  var e=fixture(true,false,false);var s=new ClaudeSimulation(e,"test",Transform.IDENTITY,0);s.linkLength(10);s.advance(1,Transform.IDENTITY);
  vector(new Vec3(2,2,5),s.snapshot().getFirst().worldPosition());
  var arrival=fixture(true,true,false);var a=new ClaudeSimulation(arrival,"test",Transform.IDENTITY,0);a.linkLength(10);a.advance(.2,Transform.IDENTITY);
  vector(new Vec3(0,0,10),a.snapshot().getFirst().worldPosition());a.linkLength(20);a.advance(0,Transform.IDENTITY);
  vector(new Vec3(0,0,20),a.renderSnapshot().getFirst().worldPosition());
 }
 @Test void pathScaleChangesOffsetsButDoesNotMovePhysicalTarget()throws Exception {
  var e=fixture(true,false,true);var s=new ClaudeSimulation(e,"test",Transform.IDENTITY,0,null,4.0);s.linkLength(10);s.advance(1,Transform.IDENTITY);
  assertEquals(2,s.instanceScale());vector(new Vec3(4,4,5),s.snapshot().getFirst().worldPosition());assertEquals(2,s.snapshot().getFirst().size(),1e-12);
 }
 @Test void uniformScalePreservesNoiseGravityDragAndTimeSemantics()throws Exception {
  var e=fixture(false,false,true);var a=new ClaudeSimulation(e,"test",Transform.IDENTITY,17,null,2.0);var b=new ClaudeSimulation(e,"test",Transform.IDENTITY,17,null,4.0);
  a.advance(.5,Transform.IDENTITY);b.advance(.5,Transform.IDENTITY);var x=a.snapshot().getFirst();var y=b.snapshot().getFirst();
  vector(x.worldPosition().multiply(2),y.worldPosition());vector(x.worldVelocity().multiply(2),y.worldVelocity());assertEquals(x.age(),y.age());assertEquals(x.rotation(),y.rotation());assertEquals(x.size()*2,y.size(),1e-12);
 }
 @Test void widthAndUniformScaleAreIndependent()throws Exception {
  var e=fixture(false,false,true);e=new ClaudeEffect(e.name(),e.schemaVersion(),H,e.gravity(),e.post(),e.materials(),e.meshes(),e.systems(),e.requiredFeatures(),new Width(2,.1,10,Set.of("test"),Set.of()),e.scale());
  var a=new ClaudeSimulation(e,"test",Transform.IDENTITY,17,2.0,4.0);var b=new ClaudeSimulation(e,"test",Transform.IDENTITY,17,6.0,4.0);a.advance(.3,Transform.IDENTITY);b.advance(.3,Transform.IDENTITY);
  var x=a.snapshot().getFirst();var y=b.snapshot().getFirst();assertEquals(x.worldPosition().x()*3,y.worldPosition().x(),1e-10);assertEquals(x.worldPosition().y(),y.worldPosition().y(),1e-10);assertEquals(x.size(),y.size());assertEquals(3,y.widthScale());
 }
 private static ClaudeEffect fixture(boolean path,boolean arrival,boolean scaled)throws Exception {
  var e=ClaudeEffect.parse(ClaudeStopDepthTest.json(false,"finish",0).toString());var b=e.system("test").layers().getFirst();Range z=new Range(0,0);Range3 z3=new Range3(z,z,z);
  PathFollow follow=path?new PathFollow(List.of(new CurveKey(0,arrival?1:0,0,arrival?0:1),new CurveKey(1,1,arrival?0:1,0)),new Range(1,1),new Range(2,2),z,z,z,1):null;
  var l=new Layer("test",b.material(),b.render(),0,"local",Vec3.ZERO,new Emission(0,0,List.of(new Burst(0,1,1,0))),new Shape("none",0,1,new Vec3(2,0,0),0),
    new Start(new Range(2,2),new Range(1,1),path?z:new Range(1,1),z,null),z3,new Velocity("local",z3,z,z),path?0:1,path?0:.3,path?null:new Noise(.2,.7,.3),
    new Gradient(List.of(new ColorKey(0,Vec3.X)),List.of(new AlphaKey(0,1))),List.of(new CurveKey(0,1,0,0)),null,null,Set.of(),StopBehavior.FINISH,follow);
  return new ClaudeEffect("test","1.11.0",H,9.81,e.post(),e.materials(),e.meshes(),List.of(new SystemDef("test",path?"link":"static",1,false,List.of(l))),Set.of(),null,scaled?new Scale("targetHeight",2,.5,3):null);
 }
 private static JsonObject layer(JsonObject j){return j.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();}
 private static JsonObject jsonPath()throws Exception {
  var j=ClaudeStopDepthTest.json(false,"finish",0);j.getAsJsonArray("systems").get(0).getAsJsonObject().addProperty("role","link");
  layer(j).add("path",JsonParser.parseString("{\"progress\":[{\"t\":0,\"value\":0,\"inTangent\":0,\"outTangent\":1},{\"t\":1,\"value\":1,\"inTangent\":1,\"outTangent\":0}],\"bend\":{\"x\":[0,0],\"y\":[0,0]},\"wave\":{\"amplitude\":[0,0],\"cycles\":[0,0],\"phase\":[0,0],\"verticalRatio\":1}}"));
  layer(j).getAsJsonArray("requires").add("pathFollow");j.getAsJsonObject("features").getAsJsonObject("byLayer").getAsJsonArray("test/test").add("pathFollow");j.getAsJsonObject("features").getAsJsonArray("required").add("pathFollow");j.getAsJsonObject("features").getAsJsonArray("required").add("linkTarget");return j;
 }
 private static void vector(Vec3 expected,Vec3 actual){assertEquals(expected.x(),actual.x(),1e-9);assertEquals(expected.y(),actual.y(),1e-9);assertEquals(expected.z(),actual.z(),1e-9);}
}
