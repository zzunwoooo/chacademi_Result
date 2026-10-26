package kr.chacademy.motionplugin.broom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarrierVelocityTest {
    @Test
    void smoothsBunchedPackets() {
        var v = new CarrierVelocity();
        // 실제 1.2 블록/틱인데 패킷이 2틱씩 몰려 옴: 0, 2.4, 0, 2.4 ...
        for (int i = 0; i < 40; i++) v.sample(0, 0, i % 2 == 0 ? 0 : 2.4);
        double z = v.get()[2];
        assertTrue(z > 0.8 && z < 1.6, "평균 근처여야 함: " + z);
    }

    @Test
    void firstSampleIsTakenAsIs() {
        var v = new CarrierVelocity();
        v.sample(1, 0, 0);
        assertEquals(1.0, v.get()[0]);
    }

    @Test
    void teleportJumpResets() {
        var v = new CarrierVelocity();
        for (int i = 0; i < 10; i++) v.sample(1, 0, 0);
        v.sample(100, 0, 0);
        assertEquals(0.0, v.get()[0]);
        v.sample(Double.NaN, 0, 0);
        assertEquals(0.0, v.get()[0]);
    }
}
