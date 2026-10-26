package school.magiccodex.client;
import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PreparedSpellIconsTest {
 static final List<String> HELD=List.of("barrelroll_stitch","horizon_trim","folding_broom_wings","wingmate_towline","potion_effect_decant","twin_cup_draught","wandering_alembic","heartfold_wrapping","conversation_ripples");
 static final Path ROOT=Path.of("src/main/resources/assets/magiccodex");
 @Test void twentyTwoPreparedIdsHavePngsAndPreserveCustomAssignments()throws Exception{
  var expected=new HashSet<>(HELD);expected.addAll(List.of("landing_airbrake","hushed_soles","thorn_manners","cinder_skipring","droplet_relay","folding_tideplane","earth_shaping","moss_coating","flint_sorting","dormant_seed","material_exchange","remembering_field","afterlife_gate"));assertEquals(expected,SpellYamlLoader.PREPARED_20261020_ICON_IDS);
  String template=Files.readString(ROOT.resolve("default-spells/021_landing_airbrake.yml"));var loader=new SpellYamlLoader();
  for(String id:expected){
   String source=template.replaceAll("(?m)^id:.*$","id: "+id).replaceAll("(?m)^icon:.*$","icon: ''");
   var spell=loader.parse(source);assertEquals(id,spell.id());assertEquals("magiccodex:textures/spells/reviewed_20261020/"+id+".png",spell.icon());
   var png=Files.readAllBytes(ROOT.resolve("textures/spells/reviewed_20261020/"+id+".png"));assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},Arrays.copyOf(png,8));
   assertEquals("magiccodex:textures/custom/user.png",loader.parse(source.replace("icon: ''","icon: magiccodex:textures/custom/user.png")).icon());
   String previous=SpellYamlLoader.REPLACED_ICON_PREVIOUS_PATHS.get(id);
   if(previous!=null)assertEquals(spell.icon(),loader.parse(source.replace("icon: ''","icon: "+previous)).icon());
  }
 }
 @Test void heldArtworkDoesNotRegisterOrEnableHeldSpells()throws Exception{
  var result=new SpellYamlLoader().load(ROOT.resolve("default-spells"));assertTrue(result.success(),result.errors().toString());
  var index=Files.readAllLines(ROOT.resolve("default-spells/index.txt"));
  for(String id:HELD){assertTrue(result.spells().stream().noneMatch(s->s.id().equals(id)));assertTrue(index.stream().noneMatch(s->s.endsWith("_"+id)));}
 }
}