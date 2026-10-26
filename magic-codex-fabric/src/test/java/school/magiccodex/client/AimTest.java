package school.magiccodex.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static school.magiccodex.client.CastingState.Kind.*;

class AimTest {
    // ---------- aimed casting state ----------
    private CastingState state() {
        var s = new CastingState(); s.catalog(CodexData.previewSpells());
        var b = new SpellKeySettings(); b.select(0, "magical_flame"); b.bind(0, 49); s.setBindings(b);
        s.input(90, 1, true, true, 0, false, x -> {}); // Z: magic mode on
        return s;
    }

    @Test void aimedKeyDefersTheCastAndConfirmSendsItOnce() {
        var s = state(); var sent = new ArrayList<String>();
        assertEquals(AIM, s.input(49, 1, false, true, 100, true, spell -> true, spell -> sent.add(spell.id())).kind());
        assertTrue(sent.isEmpty(), "preview only, nothing sent yet");
        assertEquals(0, s.remaining("magical_flame", 100));
        assertEquals(PENDING, s.fire("magical_flame", 200, true, spell -> sent.add(spell.id())).kind());
        assertEquals(List.of("magical_flame"), sent);
        assertEquals(PENDING, s.fire("magical_flame", 300, true, spell -> sent.add(spell.id())).kind(), "a second click waits for the server");
        assertEquals(1, sent.size());
    }

    @Test void coolingDownSpellIsNotAimedAndConfirmRechecksCooldown() {
        var s = state(); var sent = new ArrayList<String>();
        s.input(49, 1, false, true, 100, true, spell -> false, spell -> sent.add(spell.id()));
        s.serverResult("magical_flame", 5000, 150);
        s.input(49, 0, false, true, 160, true, spell -> true, spell -> {});
        assertEquals(COOLDOWN, s.input(49, 1, false, true, 170, true, spell -> true, spell -> sent.add(spell.id())).kind());
        assertEquals(COOLDOWN, s.fire("magical_flame", 180, true, spell -> sent.add(spell.id())).kind());
        assertEquals(1, sent.size());
    }

    @Test void confirmAfterMagicModeOffDoesNothing() {
        var s = state(); var sent = new ArrayList<String>();
        s.input(49, 1, false, true, 100, true, spell -> true, spell -> {});
        s.input(90, 0, true, true, 110, false, x -> {}); s.input(90, 1, true, true, 120, false, x -> {}); // Z off
        assertFalse(s.enabled());
        assertEquals(PASS, s.fire("magical_flame", 130, true, spell -> sent.add(spell.id())).kind());
        assertTrue(sent.isEmpty());
    }

    // ---------- generated shapes ----------
    @Test void packagedTargetingTableCoversTheMainShapes() throws Exception {
        var file = java.nio.file.Path.of("src/main/resources/assets/magiccodex/spell-targeting.json");
        var shapes = SpellTargets.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(SpellTargets.Mode.AREA, shapes.get("falling_star").mode());
        assertEquals(4, shapes.get("falling_star").radius(), 1e-9);
        assertEquals(34, shapes.get("falling_star").range(), 1e-9); // #80 사거리 1.7배
        assertEquals(SpellTargets.Mode.UNIT, shapes.get("flame_lash").mode());
        assertEquals(8, shapes.get("flame_lash").range(), 1e-9);
        assertEquals(SpellTargets.Mode.LINE, shapes.get("ember_bolt").mode());
        assertEquals(SpellTargets.Mode.WALL, shapes.get("fire_curtain").mode());
        assertEquals(SpellTargets.Mode.CONE, shapes.get("crimson_breath").mode());
        assertEquals(50, shapes.get("crimson_breath").width(), 1e-9);
        assertEquals(SpellTargets.Filter.AIR, shapes.get("pressure_press").filter());
        assertNull(shapes.get("feather_fall"), "self spells keep the instant cast");
    }

    @Test void malformedEntriesAreSkippedAndValuesClamped() {
        var shapes = SpellTargets.parse("{\"spells\":{\"ok\":{\"mode\":\"area\",\"range\":999,\"radius\":-3},"
                + "\"bad mode\":{\"mode\":\"area\"},\"weird\":{\"mode\":\"spiral\"},\"nan\":{\"mode\":\"unit\",\"range\":\"x\"}}}");
        assertEquals(48, shapes.get("ok").range(), 1e-9);
        assertEquals(0, shapes.get("ok").radius(), 1e-9);
        assertNull(shapes.get("bad mode"));
        assertNull(shapes.get("weird"));
        assertEquals(15, shapes.get("nan").range(), 1e-9);
    }

    // ---------- math ----------
    @Test void projectAndUnprojectAreInverse() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, .05f, 512f);
        var view = new Matrix4f().rotateX(.3f).rotateY(1.1f);
        var vp = new Matrix4f(projection).mul(view);
        float[] s = AimMath.project(vp, 3, -1.5, -8, 1600, 900);
        assertNotNull(s);
        double[] d = AimMath.unproject(new Matrix4f(vp).invert(), s[0], s[1], 1600, 900);
        double len = Math.sqrt(3 * 3 + 1.5 * 1.5 + 8 * 8);
        assertEquals(3 / len, d[0], 1e-3);
        assertEquals(-1.5 / len, d[1], 1e-3);
        assertEquals(-8 / len, d[2], 1e-3);
        assertNull(AimMath.project(projection, 0, 0, 5, 1600, 900), "behind the camera (identity view looks to -Z)");
        float[] centre = AimMath.project(projection, 0, 0, -5, 1600, 900);
        assertEquals(800, centre[0], 1e-3); assertEquals(450, centre[1], 1e-3);
    }

    @Test void yawPitchFollowsMinecraftConvention() {
        float[] south = AimMath.yawPitch(0, 0, 1);
        assertEquals(0, south[0], 1e-4); assertEquals(0, south[1], 1e-4);
        assertEquals(90, AimMath.yawPitch(-1, 0, 0)[0], 1e-4, "west is yaw 90");
        assertEquals(45, AimMath.yawPitch(0, -1, 1)[1], 1e-4, "looking down is positive pitch");
    }

    @Test void rangeClampKeepsTheGroundPointInsideTheEyeRange() {
        double reach = AimMath.horizontalReach(20, -1.62);
        assertEquals(Math.sqrt(400 - 1.62 * 1.62), reach, 1e-9);
        assertEquals(0, AimMath.horizontalReach(5, 9), 1e-9);
        double[] in = AimMath.clampHorizontal(0, 0, 3, 4, 10);
        assertArrayEquals(new double[]{3, 4, 0}, in, 1e-9);
        double[] out = AimMath.clampHorizontal(0, 0, 30, 40, 10);
        assertEquals(6, out[0], 1e-9); assertEquals(8, out[1], 1e-9); assertEquals(1, out[2], 1e-9);
    }

    @Test void cursorStepMatchesTheViewTurnItReplaces() {
        // 1000 px tall, fov 50 -> 20 px per degree; 10 look units = 1.5 degree = 30 px
        assertEquals(30, AimMath.cursorStep(10, 1000, 50, 1), 1e-9);
        assertEquals(15, AimMath.cursorStep(10, 1000, 50, .5), 1e-9);
        assertEquals(24, AimMath.ringSegments(1));
        assertEquals(192, AimMath.ringSegments(100));
    }

    // ---------- settings ----------
    @Test void settingsAreClampedWhenHandEdited() throws Exception {
        var file = Files.createTempFile("aim", ".properties");
        Files.writeString(file, "shoulder=false\nside=left\ndistance=99\nside-offset=-4\ncursor-speed=abc\nsmart-cast=true\n");
        var s = AimSettings.load(file);
        assertFalse(s.shoulder);
        assertEquals(-1, s.side);
        assertEquals(AimSettings.MAX_DISTANCE, s.distance, 1e-6);
        assertEquals(0, s.sideOffset, 1e-6);
        assertEquals(1, s.cursorSpeed, 1e-6);
        assertTrue(s.smartCast);
        assertTrue(AimSettings.load(file.resolveSibling("missing.properties")).shoulder);
    }
}
