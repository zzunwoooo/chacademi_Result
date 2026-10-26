package dev.portablevfx.client.claude;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClaudeLifecycleTest {
 @Test void validSequenceAndAuthoredLongHoldRangeAreAdmitted()throws Exception {
  var j=sequence();assertDoesNotThrow(()->ClaudeEffect.parse(j.toString()));
  j.getAsJsonObject("sequence").getAsJsonObject("hold").addProperty("max",86400);assertDoesNotThrow(()->ClaudeEffect.parse(j.toString()));
 }
 @Test void unknownLifecycleFieldAndOutOfRangeDefaultReject()throws Exception {
  var unknown=sequence();unknown.getAsJsonObject("sequence").addProperty("mystery",true);assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(unknown));
  var range=sequence();range.getAsJsonObject("sequence").getAsJsonObject("hold").addProperty("default",99);assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(range));
 }
 @Test void nonMainPhaseRequiresSequence()throws Exception {
  var j=ClaudeStopDepthTest.json(false,"finish",0);system(j).addProperty("phase","sustain");assertThrows(IllegalArgumentException.class,()->ClaudeEffect.parse(j.toString()));
 }
 @Test void triggerBranchRequiresTriggerEnabledSequence()throws Exception {
  var j=sequence();system(j).addProperty("phase","end");system(j).addProperty("endBranch","trigger");assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(j));
 }
 @Test void activeSequenceMustDeclareCapabilityAndTriggerConvention()throws Exception {
  var j=sequence();j.getAsJsonObject("features").getAsJsonArray("required").remove(new JsonPrimitive("phasedSequence"));assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(j));
  var t=sequence();t.getAsJsonObject("sequence").addProperty("triggerInput","trigger");t.getAsJsonObject("sequence").addProperty("onTrigger","different");t.getAsJsonObject("features").getAsJsonArray("required").add("triggeredEnd");assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(t));
 }
 @Test void salvoReferencesCountsAndNumericStopReferencesAreStrict()throws Exception {
  var valid=salvo();assertDoesNotThrow(()->ClaudeLifecycle.validate(valid));
  var missing=valid.deepCopy();missing.getAsJsonObject("salvo").getAsJsonArray("shots").get(0).getAsJsonObject().addProperty("impactSystem","missing");assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(missing));
  var count=valid.deepCopy();count.getAsJsonObject("salvo").addProperty("count",2);assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(count));
  var numeric=valid.deepCopy();numeric.getAsJsonObject("salvo").getAsJsonArray("shots").get(0).getAsJsonObject().add("stopSystems",JsonParser.parseString("[1]"));assertThrows(IllegalArgumentException.class,()->ClaudeLifecycle.validate(numeric));
 }
 private static JsonObject system(JsonObject j){return j.getAsJsonArray("systems").get(0).getAsJsonObject();}
 private static JsonObject sequence()throws Exception {
  var j=ClaudeStopDepthTest.json(false,"finish",0);system(j).addProperty("phase","sustain");
  j.add("sequence",JsonParser.parseString("{\"sustainStart\":0.5,\"hold\":{\"min\":0.1,\"max\":10,\"default\":3},\"holdInput\":\"holdDuration\",\"onSustainStop\":\"stopEmitting\",\"onCancel\":\"jumpToEnd\",\"triggerInput\":null,\"onTrigger\":null}"));j.getAsJsonObject("features").getAsJsonArray("required").add("phasedSequence");return j;
 }
 private static JsonObject salvo(){return JsonParser.parseString("{\"systems\":[{\"id\":\"1\",\"role\":\"attached\"},{\"id\":\"flight\",\"role\":\"projectile\"},{\"id\":\"hit\",\"role\":\"impact\"}],\"features\":{\"required\":[\"salvoSequence\"]},\"salvo\":{\"count\":1,\"projectileSystem\":\"flight\",\"impactSystem\":\"hit\",\"frame\":\"attached\",\"fireStart\":{\"min\":0,\"max\":10,\"default\":1},\"fireStartInput\":\"fireStart\",\"interval\":{\"min\":0.1,\"max\":1,\"default\":0.2},\"intervalInput\":\"fireInterval\",\"onFire\":\"stopIdleAndLaunch\",\"shots\":[{\"index\":0,\"slot\":[0,1,0],\"aim\":[0,0],\"stopSystems\":[\"1\"],\"projectileSystem\":\"flight\",\"impactSystem\":\"hit\"}]}}").getAsJsonObject();}
}
