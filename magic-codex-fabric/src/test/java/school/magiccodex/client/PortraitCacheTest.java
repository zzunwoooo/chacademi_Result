package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PortraitCacheTest {
    @TempDir Path dir;
    @Test void successReplacesSlotAndRetiresOnlyLegacyGeneratedFiles()throws Exception {
        Files.write(dir.resolve("portrait.png"),new byte[]{1});Files.writeString(dir.resolve("portrait.sha"),"old");
        Files.writeString(dir.resolve("unrelated.png"),"keep");
        PortraitCache.write(dir,"a".repeat(64),new byte[]{1},()->true);
        PortraitCache.write(dir,"b".repeat(64),new byte[]{2},()->true);
        assertEquals("b".repeat(64),PortraitCache.read(dir,20).sha());
        assertArrayEquals(new byte[]{2},PortraitCache.read(dir,20).png());
        assertFalse(Files.exists(dir.resolve("portrait.png")));assertFalse(Files.exists(dir.resolve("portrait.sha")));
        assertEquals("keep",Files.readString(dir.resolve("unrelated.png")));
    }
    @Test void staleCompletionCannotReplaceSuccessfulCache()throws Exception {
        PortraitCache.write(dir,"b".repeat(64),new byte[]{2},()->true);
        PortraitLoadFence fence=new PortraitLoadFence();long old=fence.next();fence.next();
        PortraitCache.write(dir,"a".repeat(64),new byte[]{1},()->fence.current(old));
        assertEquals("b".repeat(64),PortraitCache.read(dir,20).sha());
    }
    @Test void cancelledDuringWriteLeavesPreviousSlot()throws Exception {
        PortraitCache.write(dir,"a".repeat(64),new byte[]{1},()->true);
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        PortraitCache.write(dir,"b".repeat(64),new byte[]{2},()->checks.incrementAndGet()==1);
        assertEquals("a".repeat(64),PortraitCache.read(dir,20).sha());
    }
}
