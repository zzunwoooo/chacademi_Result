package school.chacademia.magicmythic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PotionCooldownsTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @Test void healthAndManaDoNotBlockEachOther() {
        var c = new PotionCooldowns();
        c.start(a, PotionCooldowns.Kind.HEALTH, 1000, 10_000);
        assertEquals(10_000, c.remaining(a, PotionCooldowns.Kind.HEALTH, 1000));
        assertEquals(0, c.remaining(a, PotionCooldowns.Kind.MANA, 1000));
        c.start(a, PotionCooldowns.Kind.MANA, 2000, 15_000);
        assertEquals(9_000, c.remaining(a, PotionCooldowns.Kind.HEALTH, 2000));
        assertEquals(15_000, c.remaining(a, PotionCooldowns.Kind.MANA, 2000));
    }

    @Test void playersAreSeparateAndCooldownEnds() {
        var c = new PotionCooldowns();
        c.start(a, PotionCooldowns.Kind.HEALTH, 0, 10_000);
        assertEquals(0, c.remaining(b, PotionCooldowns.Kind.HEALTH, 0));
        assertEquals(0, c.remaining(a, PotionCooldowns.Kind.HEALTH, 10_000));
        assertEquals(1, c.remaining(a, PotionCooldowns.Kind.HEALTH, 9_999));
    }

    @Test void zeroCooldownAndPruneAndForget() {
        var c = new PotionCooldowns();
        c.start(a, PotionCooldowns.Kind.MANA, 0, 0);
        assertEquals(0, c.size());
        c.start(a, PotionCooldowns.Kind.MANA, 0, 15_000);
        c.start(b, PotionCooldowns.Kind.HEALTH, 0, 10_000);
        c.prune(12_000);
        assertEquals(1, c.size());
        c.forget(a);
        assertEquals(0, c.size());
    }

    @Test void secondsRoundUp() {
        assertEquals(1, PotionCooldowns.seconds(1));
        assertEquals(10, PotionCooldowns.seconds(9_001));
        assertEquals(15, PotionCooldowns.seconds(15_000));
        assertEquals(1, PotionCooldowns.seconds(0));
    }
}
