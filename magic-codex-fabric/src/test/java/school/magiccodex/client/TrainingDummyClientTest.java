package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TrainingDummyProtocol.Kind;

/** #107: 연달아 맞은 피해 숫자가 겹치지 않게 흩어지는 자리, 종류별 크기 순서. */
class TrainingDummyClientTest {
    @Test void firstNumberSitsInTheMiddle() {
        assertArrayEquals(new float[]{0, 0}, TrainingDummyClient.stack(0, 123), 0f);
    }
    @Test void quickHitsStackUpwardAndAlternateSides() {
        float[] a = TrainingDummyClient.stack(1, 7), b = TrainingDummyClient.stack(2, 7), c = TrainingDummyClient.stack(3, 7);
        assertTrue(a[0] > 0 && b[0] < 0 && c[0] > 0);
        assertEquals(TrainingDummyClient.STACK_STEP, a[1], 1e-6);
        assertEquals(2 * TrainingDummyClient.STACK_STEP, b[1], 1e-6);
        assertEquals(0, TrainingDummyClient.stack(4, 7)[1], 1e-6);   // 4칸에서 다시 아래부터
        for (int n = 1; n < 40; n++) assertTrue(Math.abs(TrainingDummyClient.stack(n, n * 31L)[0]) <= .29f);
    }
    @Test void heavyIsBiggestDotIsSmallest() {
        assertTrue(TrainingDummyClient.height(Kind.HEAVY) > TrainingDummyClient.height(Kind.REACTION));
        assertTrue(TrainingDummyClient.height(Kind.REACTION) > TrainingDummyClient.height(Kind.NORMAL));
        assertTrue(TrainingDummyClient.height(Kind.NORMAL) > TrainingDummyClient.height(Kind.DOT));
        assertNotEquals(TrainingDummyClient.color(Kind.NORMAL), TrainingDummyClient.color(Kind.HEAVY));
    }
}
