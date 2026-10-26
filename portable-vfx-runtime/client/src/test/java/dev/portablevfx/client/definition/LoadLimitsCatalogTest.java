package dev.portablevfx.client.definition;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
final class LoadLimitsCatalogTest {
    @TempDir Path root;
    @Test void defaultsPrewarmTheWholeKnownCatalogButRespectAnExplicitLowerBudget()throws Exception {
        assertEquals(1024*LoadLimits.MIB,LoadLimits.DEFAULT.prewarmRgbaBytes());
        assertEquals(1024*LoadLimits.MIB,LoadLimits.DEFAULT.residentRgbaBytes());
        Path file=root.resolve("load-limits.properties");Files.writeString(file,"resident-rgba-mib=64\nresident-systems=8\n");
        var limits=LoadLimits.load(file);assertEquals(64*LoadLimits.MIB,limits.prewarmRgbaBytes());assertEquals(8,limits.prewarmSystems());
    }
}
