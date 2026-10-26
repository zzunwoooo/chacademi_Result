package school.magiccodex.client;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class DefaultSpellMigrationTest {
 @TempDir Path dir;
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 @Test void replacesKnownDefaultsWithoutBackupAndIsIdempotent()throws Exception {
  byte[] old=bytes("old\r\n");Files.write(dir.resolve("one.yml"),old);
  var history=Map.of("one.yml",Set.of(DefaultSpellMigration.hash(bytes("old\n"))));
  var defaults=Map.of("one.yml",bytes("new\n"));
  DefaultSpellMigration.migrate(dir,defaults,history,"v1");
  assertEquals("new\n",Files.readString(dir.resolve("one.yml")));
  assertFalse(Files.exists(dir.resolve(".defaults-backup")));
  DefaultSpellMigration.migrate(dir,defaults,history,"v1");
  assertFalse(Files.exists(dir.resolve(".defaults-backup")));
  assertEquals("v1",Files.readString(dir.resolve(".defaults-version")));
 }
 @Test void preservesCustomFilesAndRestoresMissingDefaultsOnEveryRun()throws Exception {
  Files.writeString(dir.resolve("one.yml"),"my custom spell");Files.writeString(dir.resolve("extra.yml"),"custom extra");
  var defaults=Map.of("one.yml",bytes("new"),"two.yml",bytes("two"));
  DefaultSpellMigration.migrate(dir,defaults,Map.of(),"v1");
  assertEquals("my custom spell",Files.readString(dir.resolve("one.yml")));assertEquals("custom extra",Files.readString(dir.resolve("extra.yml")));
  Files.delete(dir.resolve("two.yml"));DefaultSpellMigration.migrate(dir,defaults,Map.of(),"v1");
  assertEquals("two",Files.readString(dir.resolve("two.yml")));
 }
 @Test void legacyBackupDirectoryIsNeverReadOrModified()throws Exception {
  byte[] old=bytes("old");Files.write(dir.resolve("one.yml"),old);String h=DefaultSpellMigration.hash(old);
  Path backup=dir.resolve(".defaults-backup/v1");Files.createDirectories(backup);Files.writeString(backup.resolve("one.yml."+h+".bak"),"wrong");
  DefaultSpellMigration.migrate(dir,Map.of("one.yml",bytes("new")),Map.of("one.yml",Set.of(h)),"v1");
  assertEquals("new",Files.readString(dir.resolve("one.yml")));
  assertEquals("wrong",Files.readString(backup.resolve("one.yml."+h+".bak")));
 }
 @Test void upgradesDeployed147AndKeepsUserEdited101()throws Exception {
  try(var in=getClass().getResourceAsStream("/default-history/147_solar_judgment.yml")){Files.copy(in,dir.resolve("147_solar_judgment.yml"));}
  Files.writeString(dir.resolve("101_earth_shaping.yml"),"my custom spell");
  new SpellYamlLoader().installDefaults(dir);
  assertFalse(Files.exists(dir.resolve("147_solar_judgment.yml")));assertFalse(Files.exists(dir.resolve("101_earth_shaping.yml"))); // 2026-10-25 renumber: renamed
  var spell=new SpellYamlLoader().parse(Files.readString(dir.resolve("246_solar_judgment.yml")));
  assertEquals(800,spell.manaCost());assertEquals(300,spell.cooldown());assertFalse(spell.command().isBlank());
  assertEquals("my custom spell",Files.readString(dir.resolve("188_earth_shaping.yml")));
  assertTrue(Files.exists(dir.resolve(".defaults-version")));
 }
 @Test void cooldownResetUnblocksTheLocalCooldown(){
  CastingState state=new CastingState();state.serverResult("solar_judgment",300000,1000);assertTrue(state.remaining("solar_judgment",1001)>0);
  state.clearCooldown("solar_judgment");assertEquals(0,state.remaining("solar_judgment",1001));
 }
 @Test void everyShippedDefaultIsManagedExceptHolyTaming()throws Exception{
  for(String id:new String(DefaultSpellMigration.resource("index.txt"),StandardCharsets.UTF_8).lines().filter(s->!s.isBlank()).toList())
   assertEquals(!id.endsWith("_rainbow_collar"),DefaultSpellMigration.managedHistoryName(id+".yml"),id);
  for(String name:List.of("233_rainbow_collar.yml","custom.yml","notes.txt"))assertFalse(DefaultSpellMigration.managedHistoryName(name),name);
 }
 @Test void renumberMovesShippedAndEditedCopiesWithoutDuplicateIds()throws Exception{
  Files.write(dir.resolve("034_ember_bolt.yml"),bytes("shipped\n"));
  Files.write(dir.resolve("035_rekindle.yml"),bytes("player edit\n"));
  Files.write(dir.resolve("036_flame_forging.yml"),bytes("old shipped\n"));Files.write(dir.resolve("018_flame_forging.yml"),bytes("already new\n"));
  Files.write(dir.resolve("037_ember_trap.yml"),bytes("old edit\n"));Files.write(dir.resolve("009_ember_trap.yml"),bytes("already new\n"));
  var history=Map.of("036_flame_forging.yml",Set.of(DefaultSpellMigration.hash(bytes("old shipped\n"))));
  var renames=DefaultSpellMigration.parseRenames("# c\n034_ember_bolt.yml\t049_ember_bolt.yml\n035_rekindle.yml\t050_rekindle.yml\n036_flame_forging.yml\t018_flame_forging.yml\n037_ember_trap.yml\t009_ember_trap.yml\nbad\n");
  assertEquals(4,renames.size());
  DefaultSpellMigration.applyRenames(dir,renames,history);
  assertEquals("shipped\n",Files.readString(dir.resolve("049_ember_bolt.yml")));assertFalse(Files.exists(dir.resolve("034_ember_bolt.yml")));
  assertEquals("player edit\n",Files.readString(dir.resolve("050_rekindle.yml")));
  assertFalse(Files.exists(dir.resolve("036_flame_forging.yml")));assertEquals("already new\n",Files.readString(dir.resolve("018_flame_forging.yml")));
  assertFalse(Files.exists(dir.resolve("037_ember_trap.yml")));assertEquals("old edit\n",Files.readString(dir.resolve("037_ember_trap.yml.renamed")));
 }
 @Test void shippedRenumberListCoversEveryRenamedDefault()throws Exception{
  var renames=DefaultSpellMigration.parseRenames(new String(DefaultSpellMigration.resource("renamed-defaults.tsv"),StandardCharsets.UTF_8));
  var index=new HashSet<>(new String(DefaultSpellMigration.resource("index.txt"),StandardCharsets.UTF_8).lines().filter(s->!s.isBlank()).map(s->s+".yml").toList());
  assertTrue(renames.size()>250);for(var e:renames.entrySet()){assertTrue(index.contains(e.getValue()),e.getValue());assertFalse(index.contains(e.getKey()),e.getKey());
   assertEquals(e.getKey().substring(4),e.getValue().substring(4),"same id");}
 }

 @Test void retiredShippedSpellsAreRemovedButEditedOnesStay()throws Exception {
  Files.write(dir.resolve("215_copper_polish.yml"),bytes("shipped\r\n"));
  Files.write(dir.resolve("216_wax_melting.yml"),bytes("player edit\n"));
  var history=Map.of("215_copper_polish.yml",Set.of(DefaultSpellMigration.hash(bytes("shipped\n"))),"216_wax_melting.yml",Set.of(DefaultSpellMigration.hash(bytes("shipped\n"))));
  DefaultSpellMigration.removeRetired(dir,List.of("215_copper_polish.yml","216_wax_melting.yml","../x.yml"),history);
  assertFalse(Files.exists(dir.resolve("215_copper_polish.yml")));
  assertTrue(Files.exists(dir.resolve("216_wax_melting.yml")));
  for(String name:List.of("063_thawing_heat.yml","225_light_beam.yml","265_dimension_mark.yml"))assertTrue(DefaultSpellMigration.managedHistoryName(name),name);
  assertTrue(DefaultSpellMigration.allHistory("a.yml\th1\r\nb.yml\th2\n").get("a.yml").contains("h1"));
 }
 @Test void intelligenceRenameReachesShipped157And176()throws Exception {
  var history=DefaultSpellMigration.allHistory(new String(DefaultSpellMigration.resource("known-hashes.tsv"),java.nio.charset.StandardCharsets.UTF_8));
  for(String name:List.of("243_mana_conduit.yml","272_misfortune_whisper.yml")){ // 2026-10-25 renumber (was 157 / 176); old hashes copied to the new names
   assertTrue(DefaultSpellMigration.managedHistoryName(name),name);
   String text=new String(DefaultSpellMigration.resource(name),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(text.contains("지능"),name);
   String old=text.replace("지능 계수","마력 계수").replace("공격력과 지능","공격력과 마력").replace("order: 243","order: 157").replace("order: 272","order: 176");
   assertTrue(history.get(name).contains(DefaultSpellMigration.hash(old.getBytes(java.nio.charset.StandardCharsets.UTF_8))),"previous shipped "+name+" hash must be known");
  }
 }
 @Test void fishTrapRenameReachesShippedCopiesButNotEdits()throws Exception {
  assertTrue(DefaultSpellMigration.managedHistoryName("106_fish_trap.yml"));
  byte[] next=DefaultSpellMigration.resource("106_fish_trap.yml");String text=new String(next,java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(text.contains("name: 아공간 수조"));assertFalse(text.contains("통발"));
  byte[] old=text.replace("name: 아공간 수조","name: 통발").replace("order: 106","order: 324").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var history=DefaultSpellMigration.allHistory(new String(DefaultSpellMigration.resource("known-hashes.tsv"),java.nio.charset.StandardCharsets.UTF_8));
  assertTrue(history.get("106_fish_trap.yml").contains(DefaultSpellMigration.hash(old)),"previous shipped 324 hash must be known under the new name");
  Files.write(dir.resolve("106_fish_trap.yml"),old);
  DefaultSpellMigration.migrate(dir,Map.of("106_fish_trap.yml",next),history,"t");
  assertArrayEquals(next,Files.readAllBytes(dir.resolve("106_fish_trap.yml")));
  Files.write(dir.resolve("106_fish_trap.yml"),bytes("player edit\n"));
  DefaultSpellMigration.migrate(dir,Map.of("106_fish_trap.yml",next),history,"t");
  assertArrayEquals(bytes("player edit\n"),Files.readAllBytes(dir.resolve("106_fish_trap.yml")));
 }
}
