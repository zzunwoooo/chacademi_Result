package school.magiccodex.client;
import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class GuidingStarDescriptionMigrationTest {
 @TempDir Path dir;
 @Test void shippedOldDescriptionUpdatesAndLocalEditsStay() throws Exception {
  String name="201_guiding_star.yml"; // 2026-10-25 renumber (was 132): the old shipped hash was copied to the new name
  byte[] old=Base64.getDecoder().decode("aWQ6IGd1aWRpbmdfc3RhcgpuYW1lOiDquLjsnqHsnbQg67OECmNhdGVnb3J5OiBsaWdodApkZXNjcmlwdGlvbjog7JuF7YGs66awIOyDge2DnOuhnCDsi5zsoITtlZjrqbQg7ZiE7J6sIOychOy5mOulvCDsoIDsnqXtlanri4jri6QuIOydvOuwmCDsi5zsoIQg7IucIOuouOumrCDsnITsl5Ag7KCA7J6lIOychOy5mOulvCDqsIDrpqztgqTripQg67Cp7ZalIO2ZlOyCtO2RnOqwgCAxMOy0iCDrj5nslYgg64KY7YOA64Kp64uI64ukLgppY29uOiBtYWdpY2NvZGV4OnRleHR1cmVzL3NwZWxscy9ub3ZpY2UvZ3VpZGluZ19zdGFyLnBuZwpwZXJtaXNzaW9uOiBtYWdpYy5sZWFybmVkLmd1aWRpbmdfc3RhcgpkaXNjb3Zlcnk6CiAgZGVzY3JpcHRpb246IOuCmOy5qOuwmOydhCDrk6Tqs6Ag64iE7KCBIDEsMDAw67iU66GdIOqxt+yKteuLiOuLpC4KcmVzZWFyY2g6IOq4uOydhCDsnoPsnYAg7JWE7J207JeQ6rKMIOuzhCDtlZjrgpjqsIAg7KeR7Jy866GcIOqwgOuKlCDrsKntlqXsnYQg7JWM66CkIOyjvOyXiOuLpC4KY2FzdDoKICBjb21tYW5kOiDrp4jrspUgZ3VpZGluZ19zdGFyCiAgY29vbGRvd24tc2Vjb25kczogMTAKICBtYW5hLWNvc3Q6IDgKb3JkZXI6IDEzMgo=");byte[] next=DefaultSpellMigration.resource(name);
  var history=DefaultSpellMigration.allHistory(new String(DefaultSpellMigration.resource("known-hashes.tsv"),java.nio.charset.StandardCharsets.UTF_8));
  assertTrue(history.get(name).contains(DefaultSpellMigration.hash(old)));Path file=dir.resolve(name);Files.write(file,old);
  DefaultSpellMigration.migrate(dir,Map.of(name,next),history,"quest-guide");assertArrayEquals(next,Files.readAllBytes(file));
  byte[] edited=(new String(old,java.nio.charset.StandardCharsets.UTF_8)+"\n# player customization\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file,edited);
  DefaultSpellMigration.migrate(dir,Map.of(name,next),history,"quest-guide");assertArrayEquals(edited,Files.readAllBytes(file));
  var yaml=new org.yaml.snakeyaml.Yaml().load(new String(next,java.nio.charset.StandardCharsets.UTF_8));var cast=(Map<?,?>)((Map<?,?>)yaml).get("cast");assertEquals(0,cast.get("cooldown-seconds"));assertEquals(8,cast.get("mana-cost"));
  String description=String.valueOf(((Map<?,?>)yaml).get("description"));assertTrue(description.contains("다시 시전"));assertFalse(description.contains("웅크"));
 }
}
