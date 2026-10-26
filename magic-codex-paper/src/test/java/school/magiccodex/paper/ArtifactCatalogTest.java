package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import org.bukkit.configuration.file.YamlConfiguration;import org.junit.jupiter.api.Test;import school.magiccodex.protocol.ArtifactProtocol;import static org.junit.jupiter.api.Assertions.*;
class ArtifactCatalogTest {
 @Test void shippedCatalogHas150VisibleEntriesAndAllDeclaredIconsExist()throws Exception {
  var y=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/artifacts.yml").toFile());var root=y.getConfigurationSection("artifacts");assertNotNull(root);assertEquals(150,root.getKeys(false).size());int icons=0,usable=0;var entries=new ArrayList<ArtifactProtocol.Entry>();
  for(String id:root.getKeys(false)){var s=root.getConfigurationSection(id);String icon=s.getString("icon","");if(!icon.isBlank()){icons++;assertTrue(Files.exists(Path.of("../magic-codex-fabric/src/main/resources/assets/magiccodex",icon.substring("magiccodex:".length()))),id);}if(s.getBoolean("use"))usable++;if(!s.getBoolean("enabled",true))continue;
   int grade=List.of("common","rare","unique","legendary").indexOf(s.getString("grade"));assertTrue(grade>=0,id);entries.add(new ArtifactProtocol.Entry(id,s.getString("name"),grade,s.getString("description",""),s.getString("effect",""),icon,s.getBoolean("use")?s.getInt("cooldown"):ArtifactProtocol.PASSIVE));
  }
  assertEquals(150,entries.size());assertEquals(150,icons);assertEquals(20,usable);assertTrue(root.getBoolean("bug_repellent_incense.enabled",true));assertTrue(root.getBoolean("comeback_medal.enabled",true));
  // 보류였던 29개도 2026-10-26 전부 구현 → 모두 켜짐
  for(String id:List.of("broom_cord","squeaky_shoes","dancing_ribbon","waving_glove","sleepy_pillow","shy_mask","discovery_journal","thermos_lunchbox","laughing_mask","turtle_shell_backpack","stretch_headband","jack_in_the_box","yum_bib","broom_cushion","folding_spyglass","tap_dance_shoes","chatty_parrot","pinwheel_charm","shapeshift_mask","sequence_necklace","fire_spirit_lantern","mirror_twin","rainbow_prism","chaos_dice","the_only_tome","inverted_mana_flask","shadow_lord_mask","star_echo","storm_wings"))assertTrue(root.getBoolean(id+".enabled",true),id);
  var decoded=new ArrayList<ArtifactProtocol.Entry>();for(byte[] packet:ArtifactProtocol.catalog(123,entries)){assertTrue(packet.length<=ArtifactProtocol.MAX_BYTES);decoded.addAll(((ArtifactProtocol.Catalog)ArtifactProtocol.decode(packet)).entries());}assertEquals(entries,decoded);
 }
}
