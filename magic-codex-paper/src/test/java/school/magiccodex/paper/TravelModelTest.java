package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TravelProtocol;

/** 지도 이동 설정·판정 (2026-10-23). */
class TravelModelTest {
    static Map<String, Object> m(Object... kv) { var out = new LinkedHashMap<String, Object>(); for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]); return out; }

    static Map<String, Object> yml(String self) {
        return m("server-id", self,
                "servers", m("school", m("proxy", "school", "label", "학교"), "wild", m("proxy", "wild", "label", "야생1"), "wild2", m("proxy", "wild2", "label", "야생2", "enabled", false, "closed-text", "준비 중입니다")),
                "tabs", m("school", m("label", "학교", "server", "school", "world", "world", "map", "image",
                                "image", m("texture", "magiccodex:textures/gui/travel/maps/school.png", "size", List.of(1024, 512),
                                        "top-left", List.of(-512, -256), "top-right", List.of(512, -256), "bottom-left", List.of(-512, 256))),
                        "wild1", m("label", "야생1", "server", "wild", "world", "world", "map", "terrain"),
                        "wild2", m("label", "야생2", "server", "wild2", "world", "world", "map", "terrain")),
                "points", m("harbor", m("name", "항구 마을", "server", "school", "world", "world", "x", 10, "y", 64, "z", 20, "radius", 4),
                        "camp", m("name", "야생 야영지", "server", "wild", "world", "world", "x", 10, "y", 64, "z", 20),
                        "camp2", m("name", "두 번째 야생 야영지", "server", "wild2", "world", "world", "x", 10, "y", 64, "z", 20),
                        "wild_spawn", m("name", "야생 스폰", "server", "wild", "world", "world", "spawn", true, "from", List.of("school"))),
                "rules", m("personal-travel-mana", 100, "personal-travel-cooldown-seconds", 60));
    }

    @Test void parsesTabsInOrderAndPoints() {
        var c = TravelModel.parse(yml("school"));
        assertEquals(List.of("school", "wild1", "wild2"), c.tabs().stream().map(TravelModel.TabDef::id).toList());
        assertEquals(TravelProtocol.MAP_IMAGE, c.tabs().getFirst().map());
        assertEquals(4, c.points().size());
        assertEquals(3, c.points().get("camp").radius());   // default radius
        assertEquals("wild1", c.tab("wild", "world").id());
        // the same coordinates on two wilds are two different places
        assertNotEquals(c.tab("wild", "world"), c.tab("wild2", "world"));
        assertEquals(List.of("harbor"), c.localPoints("world").stream().map(TravelModel.PointDef::id).toList());
    }

    @Test void hashIgnoresServerIdAndKeyOrder() {
        var a = TravelModel.parse(yml("school")); var b = TravelModel.parse(yml("wild"));
        assertEquals(a.hash(), b.hash());
        var changed = yml("school"); ((Map<String, Object>) ((Map<String, Object>) changed.get("points")).get("harbor")).put("radius", 5);
        assertNotEquals(a.hash(), TravelModel.parse(changed).hash());
    }

    @Test void rejectsBadConfig() {
        var noSelf = yml("arena");
        assertThrows(IllegalArgumentException.class, () -> TravelModel.parse(noSelf));
        var badServer = yml("school"); ((Map<String, Object>) ((Map<String, Object>) badServer.get("points")).get("harbor")).put("server", "nowhere");
        assertThrows(IllegalArgumentException.class, () -> TravelModel.parse(badServer));
        var line = yml("school");
        var img = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) line.get("tabs")).get("school")).get("image");
        img.put("bottom-left", List.of(0, -256)); img.put("top-left", List.of(-512, -256)); img.put("top-right", List.of(512, -256));
        assertThrows(IllegalArgumentException.class, () -> TravelModel.parse(line));   // three corners on one line
        var radius = yml("school"); ((Map<String, Object>) ((Map<String, Object>) radius.get("points")).get("harbor")).put("radius", 100);
        assertThrows(IllegalArgumentException.class, () -> TravelModel.parse(radius));
    }

    @Test void verdicts() {
        var c = TravelModel.parse(yml("school"));
        long now = 1_000_000;
        var local = Map.of("world", "uid-school");
        var status = new HashMap<String, TravelModel.ServerStatus>();
        var harbor = c.points().get("harbor"); var camp = c.points().get("camp"); var camp2 = c.points().get("camp2");
        assertEquals(TravelProtocol.LOCKED, TravelModel.publicPoint(c, harbor, false, status, local, now).state());
        assertEquals(TravelProtocol.UNLOCKED, TravelModel.publicPoint(c, harbor, true, status, local, now).state());
        // remote server never heard from / stale / full / alive
        assertEquals(TravelProtocol.UNAVAILABLE, TravelModel.publicPoint(c, camp, true, status, local, now).state());
        status.put("wild", new TravelModel.ServerStatus("wild", now - 60_000, 1, 100, Map.of("world", "u"), ""));
        assertTrue(TravelModel.publicPoint(c, camp, true, status, local, now).reason().contains("닫혀"));
        status.put("wild", new TravelModel.ServerStatus("wild", now - 1000, 100, 100, Map.of("world", "u"), ""));
        assertTrue(TravelModel.publicPoint(c, camp, true, status, local, now).reason().contains("정원"));
        status.put("wild", new TravelModel.ServerStatus("wild", now - 1000, 3, 100, Map.of("world", "u"), ""));
        assertEquals(TravelModel.Verdict.OK, TravelModel.publicPoint(c, camp, true, status, local, now));
        // a disabled server keeps the point, unusable with a reason
        var v = TravelModel.publicPoint(c, camp2, true, status, local, now);
        assertEquals(TravelProtocol.UNAVAILABLE, v.state()); assertFalse(v.reason().isBlank());
    }

    @Test void personalPointWorldResetAndRemovedServer() {
        var c = TravelModel.parse(yml("school"));
        long now = 5_000;
        var local = Map.of("world", "uid-now");
        var reset = new TravelModel.Personal("school", "world", "uid-old", 0, 64, 0, 0, 0, 1, "spell:spatial_leap");
        assertTrue(TravelModel.personal(c, reset, Map.of(), local, now).reason().contains("초기화"));
        var same = new TravelModel.Personal("school", "world", "uid-now", 0, 64, 0, 0, 0, 1, "spell:spatial_leap");
        assertEquals(TravelModel.Verdict.OK, TravelModel.personal(c, same, Map.of(), local, now));
        var gone = new TravelModel.Personal("old_server", "world", "x", 0, 64, 0, 0, 0, 1, "spell:spatial_leap");
        var v = TravelModel.personal(c, gone, Map.of(), local, now);
        assertEquals(TravelProtocol.UNAVAILABLE, v.state()); assertTrue(v.reason().contains("old_server"));   // shown, never deleted
        var noWorld = new TravelModel.Personal("school", "world_nether", "", 0, 64, 0, 0, 0, 1, "s");
        assertEquals(TravelProtocol.UNAVAILABLE, TravelModel.personal(c, noWorld, Map.of(), local, now).state());
    }

    @Test void wildSpawnOnlyFromSchoolAlwaysOpenNoPortal() {
        var school = TravelModel.parse(yml("school")); var wild = TravelModel.parse(yml("wild"));
        var sp = school.points().get("wild_spawn");
        assertTrue(sp.spawn()); assertEquals(List.of("school"), sp.from());
        long now = 10_000;
        var status = Map.of("wild", new TravelModel.ServerStatus("wild", now, 1, 100, Map.of("world", "u"), "", Map.of("world", new double[]{5, 70, -3})));
        // from school: open without any discovery
        assertEquals(TravelModel.Verdict.OK, TravelModel.publicPoint(school, sp, false, status, Map.of("world", "s"), now));
        // from the wild itself: refused
        var v = TravelModel.publicPoint(wild, wild.points().get("wild_spawn"), false, Map.of(), Map.of("world", "u"), now);
        assertEquals(TravelProtocol.UNAVAILABLE, v.state()); assertTrue(v.reason().contains("학교에서만"));
        // no portal / no unlock check on the wild server
        assertTrue(wild.localPoints("world").stream().noneMatch(TravelModel.PointDef::spawn));
        // no travel cooldown is decided by the bridge (spawn flag); personal-travel numbers parsed
        assertEquals(100, school.rules().personalMana()); assertEquals(60, school.rules().personalCooldownSeconds());
    }

    @Test void preparingServerText() {
        var c = TravelModel.parse(yml("school"));
        var v = TravelModel.destination(c, "wild2", "world", "", Map.of(), Map.of(), 0);
        assertEquals("야생2 · 준비 중입니다", v.reason());
    }

    @Test void heartbeatWorldsRoundTrip() {
        var uids = new java.util.LinkedHashMap<String, String>(); uids.put("world", "u1"); uids.put("world_nether", "u2");
        var spawns = Map.of("world", new double[]{1.5, 64, -2.25});
        String packed = TravelModel.packWorlds(uids, spawns);
        var u = new HashMap<String, String>(); var s = new HashMap<String, double[]>();
        TravelModel.unpackWorlds(packed, u, s);
        assertEquals(uids, u); assertEquals(1, s.size()); assertEquals(-2.25, s.get("world")[2], 0.06);
    }

    @Test void insideAndCooldown() {
        var p = new TravelModel.PointDef("a", "A", "school", "world", 0, 64, 0, 0, 0, 4, true);
        assertTrue(TravelModel.inside(p, 3.9, 64, 0));
        assertFalse(TravelModel.inside(p, 4.1, 64, 0));
        assertFalse(TravelModel.inside(p, 0, 80, 0));    // far above (a cliff) does not count
        assertFalse(TravelModel.inside(p, 0, 60, 0));    // tunnel below
        var off = new TravelModel.Rules(false, 0);
        assertEquals(0, TravelModel.cooldownLeft(off, 10, 20));
        var on = new TravelModel.Rules(false, 30);
        assertEquals(20_000, TravelModel.cooldownLeft(on, 10_000, 20_000));
    }
}
