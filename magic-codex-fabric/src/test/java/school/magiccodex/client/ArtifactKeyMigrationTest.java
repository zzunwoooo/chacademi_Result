package school.magiccodex.client;
import java.nio.file.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class ArtifactKeyMigrationTest {
 @TempDir Path dir;
 @Test void formerXSpellAndOtherSlotSurviveShortcutRebinding() throws Exception {
  Path p=dir.resolve("keys.yml");String old="version: 1\nslots: [{spell: solar_judgment, key: 88}, {spell: earth_shaping, key: 81}, null, null, null, null]\n";Files.writeString(p,old);
  var s=SpellKeySettings.load(p);assertEquals("solar_judgment",s.get(0).spellId());assertEquals(88,s.get(0).keyCode());assertEquals(81,s.get(1).keyCode());assertEquals(old,Files.readString(p));assertTrue(KeySettingsLayout.allowed(88));
 }
 @Test void remappedArtifactShortcutDoesNotDiscardSavedSpell()throws Exception {
  try{KeySettingsLayout.artifactKey(code->code==70);var s=new SpellKeySettings();s.select(0,"solar_judgment");assertDoesNotThrow(()->s.bind(0,70));assertTrue(KeySettingsLayout.allowed(88));
   Path p=dir.resolve("keys.yml");Files.writeString(p,"version: 1\nslots: [{spell: solar_judgment, key: 70}, null, null, null, null, null]\n");var loaded=SpellKeySettings.load(p);assertEquals("solar_judgment",loaded.get(0).spellId());assertEquals(70,loaded.get(0).keyCode());
  }finally{KeySettingsLayout.artifactKey(code->false);}
 }
}
