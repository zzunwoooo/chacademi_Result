package school.magiccodex.client;
import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MobSpellIconsTest {
 @Test void all24KnownAndBlankIconsResolveButCustomIconsSurvive() throws Exception {
  assertEquals(24,MobSpellIcons.PREVIOUS.size());var root=Path.of("src/main/resources/assets/magiccodex");
  for(var e:MobSpellIcons.PREVIOUS.entrySet()) {
   String id=e.getKey(),next="magiccodex:textures/spells/mob_skill/"+id+".png";
   assertEquals(next,MobSpellIcons.resolve(id,e.getValue()));assertEquals(next,MobSpellIcons.resolve(id,""));assertEquals(next,MobSpellIcons.resolve(id,next));
   assertEquals("custom:textures/art.png",MobSpellIcons.resolve(id,"custom:textures/art.png"));
   var png=Files.readAllBytes(root.resolve("textures/spells/mob_skill/"+id+".png"));assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},Arrays.copyOf(png,8));
   var files=Files.list(root.resolve("default-spells"));try {var file=files.filter(p->p.getFileName().toString().endsWith("_"+id+".yml")).findFirst().orElseThrow();var spell=new SpellYamlLoader().parse(Files.readString(file));assertEquals(next,spell.icon());
    var synced=new CodexData.Spell(id,spell.name(),spell.category(),spell.description(),spell.condition(),spell.research(),spell.cooldown(),true,e.getValue(),spell.permission(),spell.command(),spell.order(),true,spell.manaCost());
    assertEquals(next,synced.icon());assertEquals(spell.command(),synced.command());assertEquals(spell.manaCost(),synced.manaCost());assertEquals(spell.order(),synced.order());assertTrue(synced.discovered());
   } finally {files.close();}
  }
 }
 @Test void unrelatedIdsAndSharedOldTexturesAreNotReassigned() {
  assertEquals("magiccodex:textures/spells/novice/feather_step.png",MobSpellIcons.resolve("feather_step","magiccodex:textures/spells/novice/feather_step.png"));
  assertEquals("",MobSpellIcons.resolve("unrelated",""));
 }
}
