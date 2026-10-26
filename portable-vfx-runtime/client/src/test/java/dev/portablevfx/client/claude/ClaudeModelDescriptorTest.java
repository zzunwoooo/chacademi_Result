package dev.portablevfx.client.claude;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaudeModelDescriptorTest {
 @Test void typedModelDescriptorsAndAnimationArePreserved()throws Exception {
  var e=ClaudeEffect.parse(json().toString());var m=e.models().get("spirit");assertEquals("models/Spirit.glb",m.file());assertEquals("lambert",m.shading().mode());assertEquals("none",m.textures());assertEquals(0,m.shading().outline().width());
  var l=e.system("test").layers().getFirst();assertEquals("spirit",l.render().model());assertEquals(new ModelAnimation("idle",1,true),l.animation());assertTrue(l.requires().contains("modelParticle"));
 }
 @Test void unsafeFilesUnknownFieldsClipsAndMissingDepthAreRejected()throws Exception {
  var path=json();model(path).addProperty("file","models/../outside.glb");assertThrows(ValidationException.class,()->ClaudeEffect.parse(path.toString()));
  var field=json();model(field).addProperty("skinning",true);assertThrows(ValidationException.class,()->ClaudeEffect.parse(field.toString()));
  var clip=json();layer(clip).getAsJsonObject("animation").addProperty("clip","missing");assertThrows(ValidationException.class,()->ClaudeEffect.parse(clip.toString()));
  var depth=json();depth.getAsJsonObject("materials").getAsJsonObject("glow").addProperty("depthWrite",false);assertThrows(ValidationException.class,()->ClaudeEffect.parse(depth.toString()));
  var ref=json();layer(ref).getAsJsonObject("render").addProperty("model","unknown");assertThrows(ValidationException.class,()->ClaudeEffect.parse(ref.toString()));
 }
 @Test void modelRotationIsSupportedButNonModelAnimationIsNot()throws Exception {
  var r=json();layer(r).getAsJsonObject("start").add("rotation3D",JsonParser.parseString("[[0,0],[0,0],[0,0]]"));
  layer(r).getAsJsonArray("requires").add("rotation3D");r.getAsJsonObject("features").getAsJsonArray("required").add("rotation3D");r.getAsJsonObject("features").getAsJsonObject("byLayer").getAsJsonArray("test/test").add("rotation3D");assertNotNull(ClaudeEffect.parse(r.toString()).system("test").layers().getFirst().start().rotation3D());
  var a=ClaudeStopDepthTest.json(false,"finish",0);layer(a).add("animation",JsonParser.parseString("{\"clip\":\"idle\",\"speed\":1,\"loop\":true}"));assertThrows(ValidationException.class,()->ClaudeEffect.parse(a.toString()));
 }
 private static JsonObject layer(JsonObject j){return j.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();}
 private static JsonObject model(JsonObject j){return j.getAsJsonObject("models").getAsJsonObject("spirit");}
 private static JsonObject json()throws Exception {
  var j=ClaudeStopDepthTest.json(true,"finish",0);j.add("models",JsonParser.parseString("{\"spirit\":{\"file\":\"models/Spirit.glb\",\"format\":\"gltf-binary\",\"coordinateConversion\":\"gltfToUnity\",\"rig\":\"nodeTRS\",\"height\":1,\"clips\":{\"idle\":{\"duration\":3,\"loop\":true}},\"sockets\":{\"muzzle\":{\"position\":[0,1,0],\"clip\":\"idle\",\"time\":0}},\"shading\":{\"lightDirection\":[0,1,0],\"ambient\":0.5,\"diffuse\":0.5,\"wrap\":0.5,\"rimColor\":[0,0,1],\"rimPower\":2,\"rimStrength\":0.5,\"translucentFresnelMin\":0.5}}}"));
  layer(j).getAsJsonObject("render").addProperty("type","model");layer(j).getAsJsonObject("render").addProperty("model","spirit");layer(j).add("animation",JsonParser.parseString("{\"clip\":\"idle\",\"speed\":1,\"loop\":true}"));
  for(var a:new JsonArray[]{layer(j).getAsJsonArray("requires"),j.getAsJsonObject("features").getAsJsonArray("required"),j.getAsJsonObject("features").getAsJsonObject("byLayer").getAsJsonArray("test/test")}){a.remove(new JsonPrimitive("billboard"));a.add("modelParticle");}return j;
 }
}
