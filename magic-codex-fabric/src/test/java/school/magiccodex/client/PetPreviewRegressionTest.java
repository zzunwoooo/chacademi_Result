package school.magiccodex.client;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PetPreviewRegressionTest {
 @TempDir Path root;
 JsonObject fixture(){return JsonParser.parseString("""
 {"resolution":{"width":16,"height":16},"textures":[{"source":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII="}],
 "elements":[
 {"uuid":"visible","from":[0,0,0],"to":[2,2,2],"faces":{"north":{"texture":0,"uv":[0,0,2,2]}}},
 {"uuid":"helper","from":[0,-100,0],"to":[20,100,20],"faces":{"north":{"texture":0,"uv":[0,0,16,16]}}}],
 "outliner":["visible",{"uuid":"helper_group","name":"body","children":[{"uuid":"nested","children":["helper"]}]}]}
 """).getAsJsonObject();}
 @Test void hiddenAndUnexportedDescendantsDoNotReappearOrShrinkPreview()throws Exception{
  for(String flag:new String[]{"visibility","export"}){var j=fixture();j.getAsJsonArray("outliner").get(1).getAsJsonObject().addProperty(flag,false);assertEquals(2,PetBbModel.parse(j,root.resolve("x.bbmodel"),root).span);}
 }
 @Test void reservedHitboxHiddenButOrdinaryBodyKept()throws Exception{
  var j=fixture();assertEquals(200,PetBbModel.parse(j,root.resolve("x.bbmodel"),root).span);
  j.getAsJsonArray("outliner").get(1).getAsJsonObject().addProperty("name","hitbox");assertEquals(2,PetBbModel.parse(j,root.resolve("x.bbmodel"),root).span);
 }
 @Test void untexturedFacesAreNotPaintedWithTextureZero()throws Exception{
  for(JsonElement texture:new JsonElement[]{null,JsonNull.INSTANCE,new JsonPrimitive(-1)}){var j=fixture();var face=j.getAsJsonArray("elements").get(1).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north");if(texture==null)face.remove("texture");else face.add("texture",texture);assertEquals(2,PetBbModel.parse(j,root.resolve("x.bbmodel"),root).span);}
 }
 @Test void separateV5HiddenGroupIsRespected()throws Exception{
  var j=fixture();j.add("groups",JsonParser.parseString("[{\"uuid\":\"helper_group\",\"name\":\"body\",\"visibility\":false}]"));assertEquals(2,PetBbModel.parse(j,root.resolve("x.bbmodel"),root).span);
 }
}
