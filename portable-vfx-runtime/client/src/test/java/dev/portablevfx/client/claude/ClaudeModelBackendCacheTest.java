package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.render.EffectBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLAUDE_MODEL_FIXTURE_DIR",matches=".+")
final class ClaudeModelBackendCacheTest {
    @Test void sixSystemsShareModelsAndReleaseEveryLeaseWithoutGl()throws Exception {
        Path folder=Path.of(System.getenv("CLAUDE_MODEL_FIXTURE_DIR")).getParent();
        var root=JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();
        var assets=new ArrayList<EffectBackend.Asset>();
        try(var cache=new ClaudeBackend.PreparationCache(128L*1024*1024);var backend=new ClaudeBackend()) {
            for(var s:root.getAsJsonArray("systems")){
                var selected=root.deepCopy();selected.addProperty("portableVfxSystem",s.getAsJsonObject().get("id").getAsString());
                try(var prepared=ClaudeBackend.prepare(selected.toString().getBytes(StandardCharsets.UTF_8),1,
                        path->Files.readAllBytes(folder.resolve(path)),cache)){assets.add(backend.installPrepared(prepared));}
            }
            assertEquals(3,cache.diagnostics().parseCount(),"One manifest plus the two unique GLBs, independent of six system leases");
            assertTrue(cache.diagnostics().retainedBytes()>0);assertTrue(backend.worldDepthPending());
            cache.close();assertTrue(cache.diagnostics().retainedBytes()>0,"Live assets keep their prepared data");
            for(var asset:assets)asset.close();
            assertEquals(0,backend.diagnostics().retainedTextureBytes());assertEquals(0,cache.diagnostics().retainedBytes());
        }
    }
    @Test void mismatchedAuthoredClipRejectsAndDoesNotLeakModelLease()throws Exception {
        Path folder=Path.of(System.getenv("CLAUDE_MODEL_FIXTURE_DIR")).getParent();
        var root=JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();
        root.addProperty("portableVfxSystem","summon");
        root.getAsJsonObject("models").getAsJsonObject("spirit").getAsJsonObject("clips").getAsJsonObject("summon").addProperty("duration",12);
        var cache=new ClaudeBackend.PreparationCache(128L*1024*1024);
        assertThrows(java.io.IOException.class,()->ClaudeBackend.prepare(root.toString().getBytes(StandardCharsets.UTF_8),1,
                path->Files.readAllBytes(folder.resolve(path)),cache));
        cache.close();assertEquals(0,cache.diagnostics().retainedBytes());
    }
}
