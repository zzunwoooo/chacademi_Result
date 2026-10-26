package dev.portablevfx.client.claude;

import com.google.gson.*;
import dev.portablevfx.client.render.EffectBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static dev.portablevfx.client.claude.ClaudeEffect.*;
import static org.junit.jupiter.api.Assertions.*;

/** CPU-only checks for late render frames, authored stop tails, and projectile expiry. */
class ClaudeFinishTimingTest {
    @Test void delayedFinishStopsAtRecordedBoundaryAndCatchesUpExistingParticles() throws Exception {
        var json=fixture("static","finish",0,2);
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,json);
            handle.sceneTime(11);handle.seek(1);handle.finishEmissionAt(.25f,false);backend.update(0);
            var actual=simulation(handle);
            var expected=new ClaudeSimulation(ClaudeEffect.parse(json.toString()),"test",Transform.IDENTITY,0);
            expected.advance(.25,Transform.IDENTITY,10.25);expected.stopEmission(false);
            expected.advance(.75,Transform.IDENTITY,11);
            assertEquals(30,actual.totalSpawned(),"No new particles may appear after the recorded FINISH");
            assertEquals(expected.snapshot(),actual.snapshot());
            assertEquals(1,actual.time(),1e-9);
        }
    }

    @Test void delayedRenderDoesNotRestartAnAlreadyElapsedAuthoredTail() throws Exception {
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,fixture("attached","jumpToTail",.3,30));
            handle.seek(1);handle.finishEmissionAt(.25f,false);backend.update(0);
            assertFalse(handle.exists(),"The 0.3-second tail ended before this delayed render");
        }
    }

    @Test void finishReceivedAfterASubTickRenderDoesNotSeekBackwards() throws Exception {
        var json=fixture("static","finish",0,2);
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,json);
            handle.seek(.275f);backend.update(0);
            long emitted=simulation(handle).totalSpawned();
            handle.seek(.3f);assertDoesNotThrow(()->handle.finishEmissionAt(.25f,false));backend.update(0);
            assertEquals(emitted,simulation(handle).totalSpawned());
            assertEquals(.3,simulation(handle).time(),1e-7);
            assertFalse(simulation(handle).isEmitting());
        }
    }

    @Test void repeatedFinishDoesNotResetTailOrDoubleAdvanceTheRenderClock() throws Exception {
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,fixture("attached","jumpToTail",.3,30));
            handle.seek(.25f);handle.finishEmissionAt(.25f,false);backend.update(.25f);
            assertEquals(.25,simulation(handle).time(),1e-9);assertTrue(handle.exists());
            handle.seek(.4f);handle.finishEmissionAt(.25f,false);backend.update(.15f);
            assertEquals(.4,simulation(handle).time(),1e-7);assertTrue(handle.exists());
            handle.seek(.6f);handle.finishEmissionAt(.25f,false);backend.update(.2f);
            assertFalse(handle.exists());
        }
    }

    @Test void durationExpiryRemovesOnlyProjectileLocalBodyAndDrainsWorldParticles() throws Exception {
        var json=fixture("projectile","finish",0,30);
        var system=json.getAsJsonArray("systems").get(0).getAsJsonObject();
        var world=layer(json).deepCopy();world.addProperty("id","trail");world.addProperty("space","world");
        world.getAsJsonObject("start").add("lifetime",JsonParser.parseString("[2,2]"));
        world.getAsJsonArray("requires").add("worldSpace");system.getAsJsonArray("layers").add(world);
        json.getAsJsonObject("features").getAsJsonArray("required").add("worldSpace");
        json.getAsJsonObject("features").getAsJsonObject("byLayer").add("test/trail",world.getAsJsonArray("requires").deepCopy());
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,json);handle.seek(.25f);backend.update(0);
            assertTrue(simulation(handle).snapshot().stream().anyMatch(p->!p.layer().worldSpace()));
            handle.seek(.5f);handle.expireEmissionAt(.5f);backend.update(0);
            assertTrue(handle.exists());assertFalse(simulation(handle).snapshot().isEmpty());
            assertTrue(simulation(handle).snapshot().stream().allMatch(p->p.layer().worldSpace()));
            assertFalse(simulation(handle).isEmitting());
            handle.seek(3);backend.update(0);assertFalse(handle.exists());
        }
    }

    @Test void attachedAndStaticExpiryPreserveAuthoredLocalTails() throws Exception {
        for(String role:new String[]{"attached","static"})try(var backend=new ClaudeBackend()) {
            var handle=play(backend,fixture(role,"jumpToTail",.3,30));
            handle.seek(.25f);handle.expireEmissionAt(.25f);backend.update(0);
            assertTrue(handle.exists(),role+" local particles must not be cleared");
            assertTrue(simulation(handle).snapshot().stream().allMatch(p->p.age()>=29.7-1e-9));
            handle.seek(.6f);backend.update(0);assertFalse(handle.exists());
        }
    }

    @Test void explicitFinishFalseStillPreservesProjectileLocalParticles() throws Exception {
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,fixture("projectile","finish",0,30));
            handle.seek(.5f);handle.finishEmissionAt(.5f,false);backend.update(0);
            assertTrue(handle.exists());assertFalse(simulation(handle).snapshot().isEmpty());
            assertFalse(simulation(handle).isEmitting());
        }
    }

    @Test void intentionalLoopContinuesUntilFinishEvenBeyondAuthoredDuration() throws Exception {
        var json=fixture("attached","finish",0,2);
        json.getAsJsonArray("systems").get(0).getAsJsonObject().addProperty("duration",.2);
        try(var backend=new ClaudeBackend()) {
            var handle=play(backend,json);handle.seek(1);backend.update(0);
            assertTrue(handle.exists());assertTrue(simulation(handle).isEmitting());
            assertEquals(120,simulation(handle).totalSpawned());
        }
    }

    private static JsonObject fixture(String role,String mode,double tail,double lifetime) throws Exception {
        var json=ClaudeStopDepthTest.json(false,mode,tail);
        var system=json.getAsJsonArray("systems").get(0).getAsJsonObject();
        system.addProperty("role",role);system.addProperty("loop",true);system.addProperty("duration",10);
        if(role.equals("attached"))json.getAsJsonObject("features").getAsJsonArray("required").add("attachedFollow");
        var layer=layer(json);layer.addProperty("space","local");
        layer.add("emission",JsonParser.parseString("{\"rateOverTime\":120,\"rateOverDistance\":0,\"bursts\":[]}"));
        layer.getAsJsonObject("start").add("lifetime",JsonParser.parseString("["+lifetime+","+lifetime+"]"));
        return json;
    }
    private static JsonObject layer(JsonObject json) {
        return json.getAsJsonArray("systems").get(0).getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();
    }
    private static EffectBackend.Instance play(ClaudeBackend backend,JsonObject json) throws Exception {
        Path folder=Path.of(ClaudeFinishTimingTest.class.getResource("/claude/samples/Fireball/vfx.json").toURI()).getParent();
        var asset=backend.load(json.toString().getBytes(StandardCharsets.UTF_8),1,p->Files.readAllBytes(folder.resolve(p)));
        var handle=backend.play(asset,0);assertNotNull(handle);handle.transform(0,0,0,0,0,0,1);return handle;
    }
    private static ClaudeSimulation simulation(EffectBackend.Instance handle) throws Exception {
        var field=handle.getClass().getDeclaredField("simulation");field.setAccessible(true);return (ClaudeSimulation)field.get(handle);
    }
}
