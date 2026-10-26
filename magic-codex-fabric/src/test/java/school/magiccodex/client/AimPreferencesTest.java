package school.magiccodex.client;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class AimPreferencesTest {
 @TempDir Path dir;
 @Test void cameraDefaultsAndCancelledDraftAreIndependent(){var a=new AimSettings();var draft=a.copy();assertEquals(1f,a.sideOffset);assertEquals(1,a.height);assertEquals(5,a.distance);draft.side=-1;draft.smartCast=true;draft.distance=2;assertEquals(1,a.side);assertFalse(a.smartCast);assertEquals(5,a.distance);}
 @Test void savesCameraAndKeepsUnknownProperties()throws Exception{var file=dir.resolve("aim.properties");Files.writeString(file,"future.setting=keep\n");var a=new AimSettings();a.side=-1;a.smartCast=true;assertTrue(a.persist(file));var restored=AimSettings.load(file);assertEquals(-1,restored.side);assertTrue(restored.smartCast);assertTrue(Files.readString(file).contains("future.setting=keep"));}
 @Test void invalidExistingConfigurationIsNotOverwritten()throws Exception{var file=dir.resolve("aim.properties");String invalid="future.setting="+"\\"+"uZZZZ";Files.writeString(file,invalid);assertFalse(new AimSettings().persist(file));assertEquals(invalid,Files.readString(file));}
 @Test void unversionedOldDefaultMigratesOnceAndChosenValuesStay()throws Exception{var file=dir.resolve("aim.properties");
  Files.writeString(file,"side-offset=1.5\nsmart-cast=true\n");var a=AimSettings.load(file);assertEquals(1f,a.sideOffset);assertTrue(a.migrated);assertTrue(a.smartCast);
  assertTrue(a.persist(file));assertTrue(Files.readString(file).contains("aim-version=2"));var again=AimSettings.load(file);assertEquals(1f,again.sideOffset);assertFalse(again.migrated);
  Files.writeString(file,"side-offset=1.2\n");assertEquals(1.2f,AimSettings.load(file).sideOffset);assertFalse(AimSettings.load(file).migrated);
  Files.writeString(file,"aim-version=2\nside-offset=1.5\n");assertEquals(1.5f,AimSettings.load(file).sideOffset);
  var chosen=AimSettings.load(file);assertTrue(chosen.persist(file));assertEquals(1.5f,AimSettings.load(file).sideOffset);}
}
