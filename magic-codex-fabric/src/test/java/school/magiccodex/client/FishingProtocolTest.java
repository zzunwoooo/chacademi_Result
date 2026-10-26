package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.*;

/** Round trip of every 낚시 frame and request. */
class FishingProtocolTest {
    static Gear gear() {
        return new Gear(List.of(new Piece(0, "bamboo_common", "대나무 낚싯대", 0, 49, 50, 0, false, ""),
                new Piece(1, "cork_common", "코르크 찌", 0, -1, -1, 0, true, ""),
                new Piece(2, "cotton_common", "무명실 낚싯줄", 0, -1, -1, 0, true, ""),
                new Piece(3, "earthworm_common", "지렁이", 0, -1, -1, -1, true, "")), 10, .6f, 1.5f, .2f, 15, 100);
    }

    @Test void roundTrip() {
        var frames = List.<Frame>of(
                new Session(7, FishingProtocol.PHASE_WATER, FishingProtocol.BG_OCEAN, 12.75f, FishingProtocol.MAX_DEPTH, 3, 4096, 10, 100, gear(), ""),
                new Bite(7, 99, FishingProtocol.SHAPE_EEL, .4f, -1, 3000, 2, 600, 3, FishingProtocol.DIVE, 1234L, 12.5f, true, 2700, .22f, 1.4f,
                        List.of(new Attack(4200, FishingProtocol.ATTACK_LINE, 3), new Attack(9000, FishingProtocol.ATTACK_ROD, 4))),
                new Result(7, FishingProtocol.CAUGHT, "fish_001", "붕어", 0, 0, 21.4f, true, false,
                        List.of(new Reward("붕어", 1, new byte[]{1, 2, 3})), false, gear(), 11, 100, "알림"),
                new Close(7, "끝"),
                new GearList(gear(), false, List.of(new Owned(1, 5, "feather_common", "깃털 찌", 0, 40, 40, 1, "가라앉는 속도 2", "")),
                        List.of("a", "b", "c", "d"), ""),
                new Codex(List.of(new Entry("fish_001", "붕어", 0, 0, true, 21.4f, 3, "설명", "사계절", "민물", "0~10m", "언제나")),
                        new int[]{1, 0, 0, 0}, 1, 2, List.of(List.of(new Reward("골드 5,000", 0, new byte[0])), List.of(), List.of(), List.of()), ""));
        for (Frame f : frames) {
            var back = FishingProtocol.decode(FishingProtocol.encode(f));
            if (f instanceof Codex c) {
                var b = (Codex) back;
                assertEquals(c.entries(), b.entries());
                assertArrayEquals(c.treasures(), b.treasures());
                assertEquals(c.tierRewards().getFirst().getFirst().name(), b.tierRewards().getFirst().getFirst().name());
            } else if (f instanceof Result r) {
                var b = (Result) back;
                assertEquals(r.name(), b.name()); assertEquals(r.sizeCm(), b.sizeCm()); assertEquals(r.gear(), b.gear()); assertEquals(r.tankCount(), b.tankCount());
                assertArrayEquals(r.rewards().getFirst().preview(), b.rewards().getFirst().preview());
            } else assertEquals(f, back);
        }
        for (Request r : List.of(Request.stop(7, 12.5f), Request.result(7, 99, FishingProtocol.SNAPPED, 4500, 0b10), Request.of(FishingProtocol.RECAST, 7),
                Request.equip(2, 17), Request.claim(3), Request.of(FishingProtocol.CODEX_OPEN, 0)))
            assertEquals(r, FishingProtocol.request(FishingProtocol.request(r)));
    }

    @Test void bottomless() {
        assertTrue(new Session(1, 1, 0, 12.75f, FishingProtocol.MAX_DEPTH, 3, 1, 0, 100, gear(), "").bottomless());
        assertFalse(new Session(1, 1, 0, 10, 10, 3, 1, 0, 100, gear(), "").bottomless());
    }

    /** v3: the needle rush follows the zone width (legendary .12 rushes, 유일 .15 does not); no flag on the wire. */
    @Test void rushFromZone() {
        assertTrue(FishingProtocol.rushes(.12f));
        assertFalse(FishingProtocol.rushes(.15f));
        assertFalse(FishingProtocol.rushes(.22f));
    }

    /** v3: a harder fish (more power / strain) and a deeper bite need a longer honest catch; a chest skips the approach and teases. */
    @Test void minimumCatchScalesWithDifficulty() {
        var easy = new Bite(1, 1, FishingProtocol.SHAPE_FISH, .3f, -1, 3000, 1, 600, 1, FishingProtocol.CALM, 1L, 10, false, 2700, .22f, 1, List.of());
        var hard = new Bite(1, 1, FishingProtocol.SHAPE_FISH, .3f, -1, 3000, 1, 600, 9, FishingProtocol.CALM, 1L, 10, false, 2700, .22f, 3, List.of());
        var deep = new Bite(1, 1, FishingProtocol.SHAPE_FISH, .3f, -1, 3000, 1, 600, 1, FishingProtocol.CALM, 1L, 40, false, 2700, .22f, 1, List.of());
        int e = FishingProtocol.minimumCatchMillis(easy, 10, 1);
        assertTrue(FishingProtocol.minimumCatchMillis(hard, 10, 1) > e);
        assertTrue(FishingProtocol.minimumCatchMillis(deep, 10, 1) > e);
        assertTrue(FishingProtocol.minimumCatchMillis(easy, 10, 1.5f) < e);
        // easy: wait 3 s + approach/tease/warning/needle 4.1 s = 7.1 s, reel 10 m at 3.5 m/s * .975 * (1 - .8*.066) = 3.1 s -> 10.2 s * .9 - .4 = 8.8 s
        assertTrue(e > 8300 && e < 9300, "minimum " + e);
        // a legit catch winding at .975 x reelSpeed the whole time (tired fish, weakest pull) is never refused
        double legit = 7125 + 10 / (FishingProtocol.reelSpeed(10) * FishingProtocol.MAX_WIND * (1 - .8 * (.275 * .4 * .6))) * 1000;
        assertTrue(e < legit, "minimum " + e + " legit " + legit);
    }

    @Test void tiers() {
        assertEquals(0, FishingProtocol.tiersReached(49, 200));
        assertEquals(1, FishingProtocol.tiersReached(50, 200));
        assertEquals(4, FishingProtocol.tiersReached(200, 200));
    }
}
