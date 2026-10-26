package dev.portablevfx.client.network;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PacketAdmissionTest {
    @Test void bothFinishFormatsReachReservedControlBudget() {
        assertTrue(VfxNetworking.isFinishCandidate(8,34));assertTrue(VfxNetworking.isFinishCandidate(11,35));
        assertTrue(VfxNetworking.isFinishCandidate(11,160));assertFalse(VfxNetworking.isFinishCandidate(11,161));
        assertFalse(VfxNetworking.isFinishCandidate(12,160));
    }

    @Test void sustainedPlayFloodCannotConsumeStopOrClearReserve() {
        var budget = new PacketAdmission(0);
        for (long second=0; second<20; second++) {
            long now = second * 1_000_000_000L;
            int admitted = 0;
            for (int i=0;i<10000;i++) if (budget.admit(false, now)) admitted++;
            assertEquals(64, admitted);
            // Stop all 128 active instances, then clear, despite the play flood.
            for (int i=0;i<129;i++) assertTrue(budget.admit(true, now));
            assertFalse(budget.takeClearRequest());
        }
    }
    @Test void controlFloodIsBoundedAndRequestsFailSafeClear() {
        var budget = new PacketAdmission(0);
        for (int i=0;i<256;i++) assertTrue(budget.admit(true, 0));
        assertFalse(budget.admit(true, 0));
        assertTrue(budget.takeClearRequest());
        assertFalse(budget.takeClearRequest());
        assertTrue(budget.admit(true, 1_000_000_000L));
    }
    @Test void elapsedTimeAndResetRestoreOnlyBoundedCapacity() {
        var budget = new PacketAdmission(0);
        for (int i=0;i<64;i++) assertTrue(budget.admit(false, 0));
        assertFalse(budget.admit(false, 0));
        assertTrue(budget.admit(false, 7_812_500));
        assertFalse(budget.admit(false, 7_812_500));
        budget.reset(8_000_000);
        for (int i=0;i<64;i++) assertTrue(budget.admit(false, 8_000_000));
        assertFalse(budget.admit(false, 8_000_000));
    }
    @Test void poseStormCannotStarveNewPhasePlayOrImpact() {
        var budget = new PacketAdmission(0);
        for (int i=0;i<100000;i++) budget.admit(PacketAdmission.classify(i % 2 == 0 ? 7 : 13, false), 0);
        assertEquals(PacketAdmission.Result.REJECT, budget.admit(PacketAdmission.Kind.STREAM, 0));
        for (int i=0;i<64;i++) assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.classify(i % 2 == 0 ? 12 : 9, false), 0));
        assertFalse(budget.takeClearRequest(), "stream exhaustion never requests a fail-safe clear");
    }
    @Test void streamOverflowIsCoalescedThenBounded() {
        var budget = new PacketAdmission(0);
        for (int i=0;i<128;i++) assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.Kind.STREAM, 0));
        for (int i=0;i<64;i++) assertEquals(PacketAdmission.Result.COALESCE, budget.admit(PacketAdmission.Kind.STREAM, 0));
        assertEquals(PacketAdmission.Result.REJECT, budget.admit(PacketAdmission.Kind.STREAM, 0));
        assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.Kind.STREAM, 50_000_000L), "refills 32 per 50 ms tick");
    }
    @Test void classificationSeparatesStreamsFromPhasesAndControls() {
        for (int op : new int[] {5, 7, 13}) assertEquals(PacketAdmission.Kind.STREAM, PacketAdmission.classify(op, false));
        for (int op : new int[] {1, 4, 6, 9, 10, 12, 99, -1}) assertEquals(PacketAdmission.Kind.PLAY, PacketAdmission.classify(op, false));
        assertEquals(PacketAdmission.Kind.CONTROL, PacketAdmission.classify(2, true));
    }
    @Test void sustainedServerViewerBudgetsFitClientBuckets() {
        // paper RelayLimits: PLAY 48 burst + 8/tick, STREAM 16(+8 initial poses)/tick, CONTROL 10/tick.
        var budget = new PacketAdmission(0);
        for (int tick=0; tick<200; tick++) {
            long now = tick * 50_000_000L;
            for (int i=0;i<(tick == 0 ? 32 : 8);i++) assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.Kind.PLAY, now));
            for (int i=0;i<24;i++) assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.Kind.STREAM, now));
            for (int i=0;i<10;i++) assertEquals(PacketAdmission.Result.ADMIT, budget.admit(PacketAdmission.Kind.CONTROL, now));
        }
        assertFalse(budget.takeClearRequest());
    }
}
