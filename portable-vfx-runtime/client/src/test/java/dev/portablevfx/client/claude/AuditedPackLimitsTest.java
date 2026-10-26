package dev.portablevfx.client.claude;
import com.google.gson.*;import java.nio.charset.StandardCharsets;import java.util.*;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AuditedPackLimitsTest {
 static JsonObject fixture(String name)throws Exception{try(var in=AuditedPackLimitsTest.class.getResourceAsStream("/claude/reviewed-20261020/"+name+"/vfx.json")){assertNotNull(in);return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}}
 @Test void exactReviewedExportsPreserveEverySystemAndLayer()throws Exception{
  for(String name:List.of("elementreactions","cinderskipring","finalknot","nightchain")){
   var json=fixture(name);var parsed=ClaudeEffect.parse(json.toString());assertEquals(json.getAsJsonArray("systems").size(),parsed.systems().size());
   for(var system:json.getAsJsonArray("systems")){var s=system.getAsJsonObject();assertEquals(s.getAsJsonArray("layers").size(),parsed.system(s.get("id").getAsString()).layers().size());}
   json.addProperty("portableVfxSystem",parsed.systems().getFirst().id());assertDoesNotThrow(()->ClaudeEffect.parse(json.toString()));
  }
 }
 @Test void renamedOrEditedExportsCannotInheritExceptions()throws Exception{
  for(String name:List.of("elementreactions","cinderskipring","finalknot","nightchain")){
   var renamed=fixture(name);renamed.addProperty("name","unreviewed");assertThrows(ClaudeEffect.ValidationException.class,()->ClaudeEffect.parse(renamed.toString()));
   var edited=fixture(name);edited.getAsJsonObject("simulation").addProperty("gravity",9.81234);assertThrows(ClaudeEffect.ValidationException.class,()->ClaudeEffect.parse(edited.toString()));
   assertEquals(new AuditedPackLimits.Limits(16,64),AuditedPackLimits.forEffect(edited));
  }
 }
}
