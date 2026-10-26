package dev.portablevfx.client.claude;

import com.google.gson.*;
import dev.portablevfx.client.render.EffectBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;

/** CPU-only: frame budgets, clock regressions and oversized targets degrade per instance/frame, never the backend. */
class ClaudeBudgetRecoveryTest {
    @Test void liveParticleOverflowStopsNewestInstancesInsteadOfFailingBackend() throws Exception {
        var json=dense();
        try(var backend=new ClaudeBackend()) {
            List<EffectBackend.Instance> handles=new ArrayList<>();
            for(int i=0;i<4;i++)handles.add(play(backend,json,i));
            for(var handle:handles)handle.seek(3);
            assertDoesNotThrow(()->backend.update(0));
            assertTrue(handles.get(0).exists());assertTrue(handles.get(1).exists());
            assertFalse(handles.get(2).exists());assertFalse(handles.get(3).exists());
            assertEquals(2,backend.budgetStops());
            // The backend keeps accepting and simulating new work afterwards.
            var next=play(backend,json,9);next.seek(.1f);assertDoesNotThrow(()->backend.update(0));assertTrue(next.exists());
        }
    }
    @Test void backwardsSeekHoldsLastSampleButInvalidTimesStillReject() throws Exception {
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,dense(),0);
            handle.seek(1);backend.update(0);
            assertDoesNotThrow(()->handle.seek(.5f));backend.update(0);
            assertTrue(handle.exists());
            handle.seek(1.25f);assertDoesNotThrow(()->backend.update(0));
            assertThrows(IllegalArgumentException.class,()->handle.seek(Float.NaN));
            assertThrows(IllegalArgumentException.class,()->handle.seek(-1));
        }
    }
    @Test void geometryVertexBudgetTruncatesWholePrimitives() throws Exception {
        var effect=ClaudeEffect.parse(dense().toString());
        var sim=new ClaudeSimulation(effect,"test",Transform.IDENTITY,0);sim.advance(1,Transform.IDENTITY);
        var particles=sim.renderSnapshot();var camera=new Vec3(0,0,10);
        float[] full=ClaudeGeometry.particles(effect,particles,Vec3.X,Vec3.Y,camera,2_000_000);
        assertTrue(full.length>63*ClaudeGeometry.STRIDE,"fixture must exceed the small budget");
        float[] limited=assertDoesNotThrow(()->ClaudeGeometry.particles(effect,particles,Vec3.X,Vec3.Y,camera,63));
        assertEquals(60*ClaudeGeometry.STRIDE,limited.length);
        assertArrayEquals(Arrays.copyOf(full,limited.length),limited);
        assertEquals(0,ClaudeGeometry.particles(effect,particles,Vec3.X,Vec3.Y,camera,0).length);
    }
    @Test void oversizedTargetsUseAspectPreservingInternalHdrSize() {
        assertArrayEquals(new int[]{3840,2160},ClaudePostPass.internalSize(3840,2160));
        assertArrayEquals(new int[]{4096,2048},ClaudePostPass.internalSize(4096,2048));
        for(int[] size:new int[][]{{7680,4320},{8192,8192},{5120,2880},{16384,1},{1,16384*1024}}){
            int[] internal=ClaudePostPass.internalSize(size[0],size[1]);
            assertTrue((long)internal[0]*internal[1]<=ClaudePostPass.MAX_PIXELS);
            assertTrue(internal[0]>=1&&internal[1]>=1&&internal[0]<=size[0]&&internal[1]<=size[1]);
            if(size[0]>=1024&&size[1]>=1024)assertEquals((double)size[0]/size[1],(double)internal[0]/internal[1],.01);
        }
        int[] uhd8k=ClaudePostPass.internalSize(7680,4320);assertTrue((long)uhd8k[0]*uhd8k[1]>ClaudePostPass.MAX_PIXELS*0.99);
        assertThrows(IllegalArgumentException.class,()->ClaudePostPass.internalSize(0,720));
    }

    /** Static looping billboard emitting 1000/s with 10 s lifetime: ~3000 live particles after 3 s. */
    private static JsonObject dense() throws Exception {
        var json=ClaudeStopDepthTest.json(false,"finish",0);
        var system=json.getAsJsonArray("systems").get(0).getAsJsonObject();
        system.addProperty("loop",true);system.addProperty("duration",10);
        var layer=system.getAsJsonArray("layers").get(0).getAsJsonObject();layer.addProperty("space","local");
        layer.add("emission",JsonParser.parseString("{\"rateOverTime\":1000,\"rateOverDistance\":0,\"bursts\":[]}"));
        layer.getAsJsonObject("start").add("lifetime",JsonParser.parseString("[10,10]"));
        return json;
    }
    private static EffectBackend.Instance play(ClaudeBackend backend,JsonObject json,long seed) throws Exception {
        Path folder=Path.of(ClaudeBudgetRecoveryTest.class.getResource("/claude/samples/Fireball/vfx.json").toURI()).getParent();
        var asset=backend.load(json.toString().getBytes(StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(p)));
        var handle=backend.play(asset,seed);assertNotNull(handle);handle.transform(0,0,0,0,0,0,1);return handle;
    }
}
