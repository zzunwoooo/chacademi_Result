package kr.chacademy.motionplugin.broom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class BroomConfigUpgradeTest {
 @TempDir Path dir;
 @Test void preservesSettingsAndExistingBackupAndDoesNotUpgradeTwice() throws Exception {
  String old="mastery-full-hours: 7.5\nboost-cooldown-ticks: 321\nroll-cooldown-ticks: 234\ncatch-window-ticks: 123\nblocked-worlds: [private_world]\ndebug: true\npractice-broom: old_practice\nbrooms: {}\n";
  Path file=dir.resolve("brooms.yml");Files.writeString(file,old);
  Files.writeString(dir.resolve("brooms.yml.v1.bak"),"earlier backup");
  var plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(dir.toFile());
  when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
  doAnswer(i->{try(var in=getClass().getResourceAsStream("/brooms.yml")){Files.copy(in,file);}return null;}).when(plugin).saveResource("brooms.yml",false);
  var service=new BroomService(plugin);
  assertEquals(30,service.brooms().size());
  assertEquals(old,Files.readString(dir.resolve("brooms.yml.v1.bak2")));
  assertEquals("earlier backup",Files.readString(dir.resolve("brooms.yml.v1.bak")));
  var y=YamlConfiguration.loadConfiguration(file.toFile());assertEquals(2,y.getInt("config-version"));
  assertEquals(7.5,y.getDouble("mastery-full-hours"));assertEquals(321,y.getInt("boost-cooldown-ticks"));
  assertEquals(234,y.getInt("roll-cooldown-ticks"));assertEquals(123,y.getInt("catch-window-ticks"));
  assertEquals(java.util.List.of("private_world"),y.getStringList("blocked-worlds"));assertTrue(y.getBoolean("debug"));
  assertNotEquals("old_practice",y.getString("practice-broom"));
  service.reload();verify(plugin,times(1)).saveResource("brooms.yml",false);
 }
}
