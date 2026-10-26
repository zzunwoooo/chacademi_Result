package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.google.gson.*;
import school.magiccodex.protocol.PetCompanionProtocol;

/** 동행 펫: 애니메이션 고르기와 클라 움직임 (순수 계산). */
class PetCompanionTest {
    @TempDir Path root;
    private static final PetCompanionMotion.Ground FLAT = (x, from, z) -> 64;

    @Test void animationNamesPickKinds() {
        assertEquals(PetBbModel.IDLE, PetBbModel.kind("animation.pet.idle"));
        assertEquals(PetBbModel.WALK, PetBbModel.kind("Walk"));
        assertEquals(PetBbModel.WALK, PetBbModel.kind("move"));
        assertEquals(PetBbModel.WALK, PetBbModel.kind("run_fast"));
        assertEquals(PetBbModel.FLY, PetBbModel.kind("flying"));
        assertEquals(PetBbModel.FLY, PetBbModel.kind("glide"));
        assertEquals(PetBbModel.FLY, PetBbModel.kind("fly_move"));
        assertEquals(-1, PetBbModel.kind("attack"));
        assertEquals(-1, PetBbModel.kind(null));
    }

    private JsonObject model(String animations) {
        return JsonParser.parseString("""
            {"meta":{"format_version":"5.0"},"resolution":{"width":16,"height":16},
             "textures":[{"source":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII="}],
             "elements":[{"uuid":"cube","from":[-2,3,-2],"to":[2,11,2],"faces":{"north":{"texture":0,"uv":[0,0,16,16]}}}],
             "outliner":[{"uuid":"bone","origin":[0,0,0],"children":["cube"]}],
             "animations":[""" + animations + "]}").getAsJsonObject();
    }
    private static String anim(String name, float length) {
        return "{\"name\":\"" + name + "\",\"length\":" + length + ",\"animators\":{\"bone\":{\"keyframes\":[{\"channel\":\"rotation\",\"time\":0,\"data_points\":[{\"x\":1,\"y\":0,\"z\":0}]}]}}}";
    }

    @Test void clipsPerStateFallBackToIdle() throws Exception {
        var m = PetBbModel.parse(model(anim("attack", 1) + "," + anim("idle", 2) + "," + anim("walk", 3) + "," + anim("fly", 4)), root.resolve("p.bbmodel"), root);
        assertEquals(2, m.idle.length(), 1e-6); assertEquals(3, m.walk.length(), 1e-6); assertEquals(4, m.fly.length(), 1e-6);
        assertEquals(2, m.length, 1e-6);
        assertNotNull(m.idle.byNode().get("bone")[0]);
        assertEquals(3, m.bottom, 1e-6);
        var only = PetBbModel.parse(model(anim("idle", 2)), root.resolve("p.bbmodel"), root);
        assertSame(only.idle, only.walk); assertSame(only.idle, only.fly);
        assertSame(only.idle, only.clip(PetBbModel.FLY));
        var first = PetBbModel.parse(model(anim("attack", 1) + "," + anim("walk", 3)), root.resolve("p.bbmodel"), root);
        assertEquals(1, first.idle.length(), 1e-6);   // idle 이 없으면 첫 애니메이션 (예전과 같음)
        var none = PetBbModel.parse(model(""), root.resolve("p.bbmodel"), root);
        assertEquals(0, none.idle.length(), 1e-6); assertTrue(none.tracks.isEmpty());
    }

    @Test void sampleIntoReusesArray() {
        var keys = java.util.List.of(new PetBbModel.Key(0, new float[]{0, 0, 0}, "linear"), new PetBbModel.Key(2, new float[]{4, 2, 0}, "linear"));
        float[] out = new float[3];
        assertSame(out, PetBbModel.sample(keys, 1, out));
        assertEquals(2, out[0], 1e-6); assertEquals(1, out[1], 1e-6);
        assertArrayEquals(PetBbModel.sample(keys, 1), out, 1e-6f);
    }

    @Test void groundPetStaysNearAndFollows() {
        var m = new PetCompanionMotion(7);
        m.tick(PetCompanionProtocol.GROUND, 0, 64, 0, false, FLAT);
        assertTrue(m.placed); assertEquals(64, m.y, 1e-9);
        assertTrue(Math.hypot(m.x, m.z) < 2);
        for (int i = 0; i < 2000; i++) {
            m.tick(PetCompanionProtocol.GROUND, 0, 64, 0, false, FLAT);
            assertTrue(Math.hypot(m.x, m.z) <= PetCompanionMotion.WANDER_MAX + .01, "주인 주변 3블록 안");
        }
        // 주인이 6블록 떨어지면 걸어서 따라옴 (순간이동 아님)
        double startX = m.x;
        for (int i = 0; i < 80; i++) m.tick(PetCompanionProtocol.GROUND, startX + 6, 64, m.z, false, FLAT);
        assertTrue(Math.abs(m.x - (startX + 6)) <= PetCompanionMotion.FOLLOW + .01);
        // 8블록 넘게 멀어지면 바로 옆에 나타남
        m.tick(PetCompanionProtocol.GROUND, 100, 64, 100, false, FLAT);
        assertTrue(Math.hypot(m.x - 100, m.z - 100) < 2);
        assertEquals(m.x, m.px, 1e-9);   // 순간이동은 보간하지 않음
    }

    @Test void groundPetWalksThenIdles() {
        var m = new PetCompanionMotion(3);
        boolean walked = false, idled = false;
        for (int i = 0; i < 1000; i++) {
            m.tick(PetCompanionProtocol.GROUND, 0, 64, 0, false, FLAT);
            if (m.anim == PetBbModel.WALK) walked = true;
            if (walked && m.anim == PetBbModel.IDLE) idled = true;
        }
        assertTrue(walked); assertTrue(idled);
    }

    @Test void flyingPetHoversAtShoulder() {
        var m = new PetCompanionMotion(11);
        for (int i = 0; i < 400; i++) m.tick(PetCompanionProtocol.FLY, 0, 64, 0, false, FLAT);
        assertTrue(m.y > 64 + 1.2 && m.y < 64 + 1.9, "어깨~머리 높이: " + m.y);
        assertEquals(PetBbModel.FLY, m.anim);
        var s = new PetCompanionMotion(11);
        for (int i = 0; i < 400; i++) s.tick(PetCompanionProtocol.SWIM, 0, 40, 0, true, FLAT);
        assertTrue(s.y > 40 && s.y < 41.2, "물속에선 몸 높이: " + s.y);
    }

    @Test void headingTurnsTowardMovementTheShortWay() {
        assertEquals(0, PetCompanionMotion.wrap((float) (Math.PI * 2)), 1e-5);
        assertEquals((float) -Math.PI / 2, PetCompanionMotion.wrap((float) (Math.PI * 1.5)), 1e-5);
        float a = PetCompanionMotion.approach(3f, -3f, .5f);   // 3 → -3 은 π 를 넘어 짧게 돈다
        assertTrue(Math.abs(a) > 3f);
    }
}
