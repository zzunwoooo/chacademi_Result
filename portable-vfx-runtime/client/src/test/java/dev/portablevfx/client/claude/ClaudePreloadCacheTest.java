package dev.portablevfx.client.claude;

import com.google.gson.JsonParser;
import dev.portablevfx.client.render.EffectBackend;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No GL context: off-thread preparation, content identity, caps, cancellation, and lease cleanup. */
class ClaudePreloadCacheTest {
    private static byte[] manifest(String system)throws Exception{
        var path=Path.of(ClaudePreloadCacheTest.class.getResource("/claude/samples/Fireball/vfx.json").toURI());
        var json=JsonParser.parseString(Files.readString(path)).getAsJsonObject();json.addProperty("portableVfxSystem",system);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] png(int color)throws IOException{
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,color);
        var out=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",out));return out.toByteArray();
    }
    @Test void prepareIsBackgroundSafeAndInstallDeduplicatesAcrossAssets()throws Exception{
        var data=manifest("projectile");var png=png(0xffffffff);
        var cache=new ClaudeBackend.PreparationCache(4);var backend=new ClaudeBackend();
        var prepared=CompletableFuture.supplyAsync(()->{
            try{return ClaudeBackend.prepare(data,1,p->png,cache);}catch(IOException e){throw new RuntimeException(e);}
        }).get();
        var first=backend.installPrepared(prepared);var second=backend.installPrepared(prepared);prepared.close();
        assertEquals(1,cache.diagnostics().decodeCount());assertEquals(1,backend.diagnostics().textureCount());
        assertEquals(4,backend.diagnostics().retainedTextureBytes());assertEquals(0,backend.diagnostics().textureUploads());
        cache.close();assertEquals(4,cache.diagnostics().retainedBytes());
        first.close();assertEquals(4,backend.diagnostics().retainedTextureBytes());
        assertNotNull(backend.play(second,1));backend.stopAll();assertEquals(1,backend.diagnostics().assetCount());
        assertNotNull(backend.play(second,2));second.close();assertEquals(0,backend.diagnostics().retainedTextureBytes());
        assertEquals(0,cache.diagnostics().retainedBytes());backend.close();backend.close();
    }
    @Test void liveCacheIsBoundedAndChangedBytesEvictOnlyReleasedEntries()throws Exception{
        var data=manifest("projectile");var white=png(0xffffffff);var red=png(0xffff0000);
        try(var cache=new ClaudeBackend.PreparationCache(4)){
            var first=ClaudeBackend.prepare(data,1,p->white,cache);
            assertThrows(IOException.class,()->ClaudeBackend.prepare(data,1,p->red,cache));
            assertEquals(1,cache.diagnostics().decodeCount());assertEquals(4,cache.diagnostics().retainedBytes());
            first.close();assertEquals(0,cache.diagnostics().pinnedTextureCount());
            try(var second=ClaudeBackend.prepare(data,1,p->red,cache)){
                assertEquals(2,cache.diagnostics().decodeCount());assertEquals(4,cache.diagnostics().retainedBytes());
                assertEquals(1,cache.diagnostics().textureCount());
            }
            cache.close();assertEquals(0,cache.diagnostics().retainedBytes());
        }
    }
    @Test void selectorVariantsShareParsedDefinitionAndIdenticalPngBytes()throws Exception{
        var projectile=manifest("projectile");var impact=manifest("impact");var white=png(0xffffffff);
        try(var cache=new ClaudeBackend.PreparationCache(4);
            var first=ClaudeBackend.prepare(projectile,1,p->white,cache);
            var second=ClaudeBackend.prepare(impact,1,p->white,cache)){
            assertEquals(1,cache.diagnostics().parseCount());assertEquals(1,cache.diagnostics().decodeCount());
            assertTrue(cache.diagnostics().cacheHits()>0);
        }
    }
    @Test void failedPreparationReleasesPartialLeasesAndClosedPreparedCannotInstall()throws Exception{
        var data=manifest("projectile");var white=png(0xffffffff);int[] reads={0};
        try(var cache=new ClaudeBackend.PreparationCache(4);var backend=new ClaudeBackend()){
            assertThrows(IOException.class,()->ClaudeBackend.prepare(data,1,p->{if(++reads[0]>1)throw new IOException("fixture missing");return white;},cache));
            assertEquals(0,cache.diagnostics().pinnedTextureCount());
            var prepared=ClaudeBackend.prepare(data,1,p->white,cache);prepared.close();prepared.close();
            assertThrows(IOException.class,()->backend.installPrepared(prepared));assertEquals(0,backend.diagnostics().assetCount());
            cache.close();assertEquals(0,cache.diagnostics().retainedBytes());
            assertThrows(IOException.class,()->ClaudeBackend.prepare(data,1,p->white,cache));
        }
    }
    @Test void installAndPrewarmKeepRenderThreadOwnership()throws Exception{
        var data=manifest("projectile");var white=png(0xffffffff);
        try(var cache=new ClaudeBackend.PreparationCache(4);var backend=new ClaudeBackend();var prepared=ClaudeBackend.prepare(data,1,p->white,cache)){
            assertTrue(CompletableFuture.supplyAsync(()->assertThrows(IllegalStateException.class,()->backend.installPrepared(prepared))).get().getMessage().contains("owning render thread"));
            assertThrows(IllegalArgumentException.class,()->backend.prewarmStep(0,720));
            assertThrows(IllegalArgumentException.class,()->backend.prewarmStep(720,-1));
            assertEquals(0,backend.diagnostics().textureUploads());
        }
    }
}
