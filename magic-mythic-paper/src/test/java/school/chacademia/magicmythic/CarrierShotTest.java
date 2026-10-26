package school.chacademia.magicmythic;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarrierShotTest {
    @Test
    void leadTicksFollowPing() {
        assertEquals(1.0, CarrierShot.leadTicks(0));
        assertEquals(3.0, CarrierShot.leadTicks(100));
        assertEquals(8.0, CarrierShot.leadTicks(2000)); // 상한
        assertEquals(1.0, CarrierShot.leadTicks(-5));
    }

    @Test
    void leadOffsetIsCapped() {
        Vector off = CarrierShot.leadOffset(new Vector(0, 0, 1.35), 100);
        assertEquals(4.05, off.getZ(), 1e-9);
        Vector capped = CarrierShot.leadOffset(new Vector(0, 0, 2.0), 1000);
        assertEquals(8.0, capped.length(), 1e-9);
    }

    @Test
    void onlyForwardComponentIsAdded() {
        Vector fly = new Vector(0, 0, 1.35);
        assertEquals(1.35, CarrierShot.extraSpeed(fly, new Vector(0, 0, 1)), 1e-9);   // 앞으로
        assertEquals(0.0, CarrierShot.extraSpeed(fly, new Vector(1, 0, 0)), 1e-9);    // 옆으로
        assertEquals(0.0, CarrierShot.extraSpeed(fly, new Vector(0, 0, -1)), 1e-9);   // 뒤로
        double diag = Math.sqrt(0.5);
        assertEquals(1.35 * diag, CarrierShot.extraSpeed(fly, new Vector(diag, 0, diag)), 1e-9);
    }
}
