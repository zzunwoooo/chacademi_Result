package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.render.EffectBackend;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises every original delivered PNG in one backend's real decoded allocation budget. */
class ClaudePackBackendTest {
    /** BC7 (default): 26 of the 30 PNGs compress faithfully, 4 stay RGBA8. */
    @Test void allSixSystemsLoadAndSimulateTogetherWithinExistingBudget() throws Exception { loadAll(16_416_768L); }
    /** Legacy uncompressed path ({@code -Dportablevfx.claude.textureCompression=off}). */
    @Test void uncompressedTexturesKeepTheOriginalRgbaBudget() throws Exception {
        String previous=System.getProperty("portablevfx.claude.textureCompression");
        System.setProperty("portablevfx.claude.textureCompression","off");
        try{loadAll(58_195_968L);}
        finally{if(previous==null)System.clearProperty("portablevfx.claude.textureCompression");else System.setProperty("portablevfx.claude.textureCompression",previous);}
    }
    private void loadAll(long expectedTextureBytes) throws Exception {
        try(var cache=new ClaudeBackend.PreparationCache(64L*1024*1024);var backend=new ClaudeBackend()) {
            List<EffectBackend.Instance> instances=new ArrayList<>();
            for(String name:List.of("Fireball","FeatherFall","TidalWave")) {
                Path folder=Path.of(getClass().getResource("/claude/samples/"+name+"/vfx.json").toURI()).getParent();
                var source=JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();
                for(var system:source.getAsJsonArray("systems")) {
                    String id=system.getAsJsonObject().get("id").getAsString();var envelope=source.deepCopy();envelope.addProperty("portableVfxSystem",id);
                    EffectBackend.Asset asset;
                    try(var prepared=ClaudeBackend.prepare(envelope.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(p)),cache)){
                        asset=backend.installPrepared(prepared);
                    }
                    var handle=backend.play(asset,0);assertNotNull(handle);handle.transform(0,0,0,0,0,0,1);
                    if(name.equals("TidalWave")&&!id.equals("splash"))handle.effectWidth(14);
                    handle.seek(.8f);instances.add(handle);
                }
            }
            var bytes=ClaudeBackend.class.getDeclaredField("retainedTextureBytes");bytes.setAccessible(true);
            assertEquals(expectedTextureBytes,bytes.getLong(backend));assertTrue(bytes.getLong(backend)<64L*1024*1024);
            assertEquals(30,backend.diagnostics().textureCount());assertEquals(30,cache.diagnostics().decodeCount());
            assertEquals(3,cache.diagnostics().parseCount());assertTrue(cache.diagnostics().cacheHits()>0);
            backend.update(.8f);assertEquals(6,instances.size());
            for(var handle:instances)assertTrue(handle.exists());
            for(var handle:instances)handle.finishEmission();
            for(var handle:instances)handle.seek(25);
            backend.update(.1f);
            for(var handle:instances)assertFalse(handle.exists());
        }
    }
}
