package school.magiccodex.client;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class MagicSeedDefaultsTest{
 @TempDir Path dir;
 private Path seed(String name)throws Exception{Path p=dir.resolve(name);try(var in=getClass().getResourceAsStream("/"+name)){assertNotNull(in);Files.copy(in,p);}return p;}
 @Test void cleanDefaultKeepsEquippedSpellsAndOnlyUnassignsTheirKeys()throws Exception{Path old=seed("launcher-magic-keys-before.yml"),clean=seed("launcher-magic-keys-clean.yml");byte[] a=Files.readAllBytes(old),b=Files.readAllBytes(clean);var before=SpellKeySettings.load(old);var after=SpellKeySettings.load(clean);assertEquals(6,before.count());assertEquals(before.count(),after.count());for(int i=0;i<6;i++){assertEquals(before.get(i).spellId(),after.get(i).spellId());assertEquals(-1,after.get(i).keyCode());}assertEquals(before.orderedSlots().stream().map(SpellKeySettings.Binding::spellId).toList(),after.orderedSlots().stream().map(SpellKeySettings.Binding::spellId).toList());for(int key=0;key<350;key++)assertEquals(-1,after.slotOfKey(key));assertArrayEquals(a,Files.readAllBytes(old));assertArrayEquals(b,Files.readAllBytes(clean));}
 @Test void unassignedDefaultUsesExistingDraftValidationUntilUserChoosesKeys()throws Exception{var after=SpellKeySettings.load(seed("launcher-magic-keys-clean.yml"));assertFalse(after.complete());assertThrows(java.io.IOException.class,()->after.save(dir.resolve("unconfirmed.yml")));assertFalse(Files.exists(dir.resolve("unconfirmed.yml")));}
}
