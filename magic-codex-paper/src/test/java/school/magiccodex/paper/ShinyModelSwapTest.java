package school.magiccodex.paper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShinyModelSwapTest {
 static class Model {final String id;double scale=1;boolean destroyed;Model(String id){this.id=id;}}
 static class Backend implements ShinyModelSwap.Models<Model> {
  final Map<String,Model> map=new LinkedHashMap<>();final List<Model> created=new ArrayList<>();String missing,fail;
  Backend(String... ids){for(String id:ids)map.put(id,new Model(id));}
  public Map<String,Model> current(){return map;}
  public Model create(String id){if(id.equals(missing))return null;var m=new Model(id);created.add(m);return m;}
  public void scale(Model n,Model o){n.scale=o.scale;}
  public void remove(String id){map.remove(id);}
  public void add(Model m){map.put(m.id,m);if(m.id.equals(fail))throw new IllegalStateException("attach failed after insert");}
  public void destroy(Model m){m.destroyed=true;}
 }
 @Test void registeredMythicWithoutCustomTagGetsAppearance(){assertTrue(ShinyModelSwap.customAppearance(false,true));assertFalse(ShinyModelSwap.customAppearance(false,false));assertTrue(ShinyModelSwap.customAppearance(true,false));}
 @Test void allExistingFamiliesSetAndClear(){for(String id:List.of("doxy","fenrir_mother","fenrir_pup","goblin","ca_wolf")){String shiny=ShinyModelSwap.target(id,true);assertEquals(id,ShinyModelSwap.target(shiny,false));assertEquals(shiny,ShinyModelSwap.target(shiny,true));assertEquals(id,ShinyModelSwap.target(id,false));}}
 @Test void setClearRepeatPreservesScaleAndIgnoresEffects(){var b=new Backend("doxy","taming_seal");b.map.get("doxy").scale=.55;var effect=b.map.get("taming_seal");ShinyModelSwap.apply(b,true);assertEquals(.55,b.map.get("doxy_shiny").scale);ShinyModelSwap.apply(b,true);assertEquals(1,b.created.size());ShinyModelSwap.apply(b,false);assertEquals(.55,b.map.get("doxy").scale);assertSame(effect,b.map.get("taming_seal"));assertFalse(effect.destroyed);}
 @Test void missingReplacementLeavesOriginalAttached(){var b=new Backend("goblin");var old=b.map.get("goblin");b.missing="goblin_shiny";assertThrows(IllegalStateException.class,()->ShinyModelSwap.apply(b,true));assertSame(old,b.map.get("goblin"));assertFalse(old.destroyed);}
 @Test void failedAttachmentRollsBackEvenAfterInsertion(){var b=new Backend("goblin");var old=b.map.get("goblin");b.fail="goblin_shiny";assertThrows(IllegalStateException.class,()->ShinyModelSwap.apply(b,true));assertEquals(Set.of("goblin"),b.map.keySet());assertSame(old,b.map.get("goblin"));assertFalse(old.destroyed);assertTrue(b.created.getFirst().destroyed);}
 @Test void multipleModelsArePreparedBeforeAnyChange(){var b=new Backend("goblin","doxy");var original=new HashMap<>(b.map);b.missing="doxy_shiny";assertThrows(IllegalStateException.class,()->ShinyModelSwap.apply(b,true));assertEquals(original,b.map);assertTrue(original.values().stream().noneMatch(m->m.destroyed));assertTrue(b.created.stream().allMatch(m->m.destroyed));}
 @Test void failedClearKeepsShinyModel(){var b=new Backend("ca_wolf_s");var old=b.map.get("ca_wolf_s");b.fail="ca_wolf";assertThrows(IllegalStateException.class,()->ShinyModelSwap.apply(b,false));assertSame(old,b.map.get("ca_wolf_s"));assertEquals(1,b.map.size());assertFalse(old.destroyed);}
 @Test void unsupportedAttachedModelFailsInsteadOfReportingSuccess(){var b=new Backend("unregistered_model");assertThrows(IllegalStateException.class,()->ShinyModelSwap.apply(b,true));assertNull(ShinyModelSwap.target("unregistered_model",true));assertEquals(1,b.map.size());}
}