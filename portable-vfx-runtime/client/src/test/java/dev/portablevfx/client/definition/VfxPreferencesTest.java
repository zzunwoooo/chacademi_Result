package dev.portablevfx.client.definition;
import dev.portablevfx.client.api.VfxPreferences;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class VfxPreferencesTest {
 @TempDir Path dir;
 @Test void defaultsAndBoundsRemainSafe(){var p=VfxPreferences.load(dir.resolve("absent"));assertEquals(1,p.opacity(true));assertEquals(1,p.opacity(false));assertEquals(0,VfxPreferences.bounded(-1));assertEquals(1,VfxPreferences.bounded(Float.NaN));}
 @Test void savedIndependentOpacityAndUnrelatedSettingsSurviveRestart()throws Exception{var f=dir.resolve("vfx.properties");Files.writeString(f,"own=0.25\nother=0\nfuture.setting=keep\n");var p=VfxPreferences.load(f);assertEquals(.25f,p.opacity(true));assertEquals(0,p.opacity(false));p.save(f);var restored=VfxPreferences.load(f);assertEquals(.25f,restored.opacity(true));assertEquals(0,restored.opacity(false));assertTrue(Files.readString(f).contains("future.setting=keep"));}
 @Test void failedSaveLeavesExistingDestinationIntact()throws Exception{var target=dir.resolve("directory");Files.createDirectory(target);Files.writeString(target.resolve("keep"),"preserved");var p=VfxPreferences.load(dir.resolve("absent"));assertThrows(java.io.IOException.class,()->p.save(target));assertEquals("preserved",Files.readString(target.resolve("keep")));try(var files=Files.list(dir)){assertEquals(1,files.count());}}
}
