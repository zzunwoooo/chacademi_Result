package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PetCompanionProtocol;

class CompanionMathTest {
    @Test void tokenRoundTripKeepsArtifactRuntime() {
        String joined = CompanionMath.join("ca_owl_shiny", "r:war_drum=123;chant=1;", 4096);
        assertEquals("cpet=ca_owl_shiny;r:war_drum=123;chant=1;", joined);
        assertEquals("ca_owl_shiny", CompanionMath.pet(joined));
        assertEquals("r:war_drum=123;chant=1;", CompanionMath.withoutPet(joined));
    }

    @Test void noPetLeavesRuntimeUntouched() {
        assertEquals("chant=1;", CompanionMath.join(null, "chant=1;", 4096));
        assertNull(CompanionMath.pet("chant=1;"));
        assertEquals("chant=1;", CompanionMath.withoutPet("chant=1;"));
        assertEquals("", CompanionMath.withoutPet(null));
        assertNull(CompanionMath.pet(null));
        assertNull(CompanionMath.pet("cpet=../bad;"));
    }

    @Test void joinCutsArtifactPartAtSemicolonToFit() {
        String joined = CompanionMath.join("ca_a", "aaaa=1;bbbb=2;", 20);
        assertTrue(joined.length() <= 20, joined);
        assertEquals("cpet=ca_a;aaaa=1;", joined);
    }

    @Test void motionNames() {
        assertEquals(PetCompanionProtocol.GROUND, CompanionMath.motion("ground"));
        assertEquals(PetCompanionProtocol.FLY, CompanionMath.motion("FLY"));
        assertEquals(PetCompanionProtocol.SWIM, CompanionMath.motion("swim"));
        assertEquals(PetCompanionProtocol.GROUND, CompanionMath.motion(null));
    }

    @Test void combatWindowAndReady() {
        assertFalse(CompanionMath.inCombat(-1, 1000, 8000));
        assertTrue(CompanionMath.inCombat(1000, 8999, 8000));
        assertFalse(CompanionMath.inCombat(1000, 9000, 8000));
        assertTrue(CompanionMath.ready(0, 5000, 6000, 8000), "꺼낸 직후 쿨이 찬 상태");
        assertFalse(CompanionMath.ready(0, -1, 6000, 8000), "전투 중이 아니면 발동 안 함");
        assertFalse(CompanionMath.ready(70_000, 5000, 6000, 8000), "쿨 도는 중");
    }

    @Test void absorbKeepsLargerCurrent() {
        assertEquals(8, CompanionMath.absorb(0, 4));
        assertEquals(10, CompanionMath.absorb(10, 4));
    }

    @Test void levelsAndFactors() {
        assertEquals(0, CompanionMath.amplifier(1));
        assertEquals(1, CompanionMath.amplifier(2));
        assertEquals(-1, CompanionMath.amplifier(0));
        assertEquals(-0.5, CompanionMath.fall(0.5));
        assertEquals(-1, CompanionMath.fall(3));
        assertEquals(1.15, CompanionMath.factor(0.15), 1e-9);
        assertEquals(1, CompanionMath.factor(-1));
        assertEquals(1, CompanionMath.factor(Double.NaN));
    }
    @org.junit.jupiter.api.Test void collectionHealthMatchesOwnerTargets() {
        // 전체 점수 612 (커스텀 528 + 바닐라 84), 곡선 0.38, 최대 20
        org.junit.jupiter.api.Assertions.assertEquals(5, CompanionMath.collectionHealth(15, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(8, CompanionMath.collectionHealth(51, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(13, CompanionMath.collectionHealth(195, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(20, CompanionMath.collectionHealth(612, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(20, CompanionMath.collectionHealth(900, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(0, CompanionMath.collectionHealth(0, 612, 20, 0.38));
        org.junit.jupiter.api.Assertions.assertEquals(0, CompanionMath.collectionHealth(5, 0, 20, 0.38));
    }
}
