package dev.portablevfx.client.network;

/**
 * Separate bounded reserves per traffic class, checked before payload allocation. A control flood
 * requests fail-safe clearing. Server budgets (paper RelayLimits.VIEWER_*) stay strictly inside
 * these buckets, so a compliant relay is never throttled here:
 * <ul>
 *   <li>PLAY: new-phase PLAY 1/4/6/10/12 and IMPACT 9 (and unknown opcodes): 64 burst, 200/s.</li>
 *   <li>STREAM: POSE 7, LINK_POSE 13, ORIENT 5: 128 burst, 640/s. Exhaustion falls through to a
 *       bounded OVERFLOW bucket (64 burst, 640/s) whose poses are coalesced per instance, so the
 *       stalest sample is the one dropped. Poses can never spend PLAY tokens.</li>
 *   <li>CONTROL: STOP 2, CLEAR 3, FINISH 8/11: 256 burst, 256/s.</li>
 * </ul>
 */
final class PacketAdmission {
    enum Kind { PLAY, STREAM, CONTROL }
    enum Result { REJECT, ADMIT, COALESCE }

    static final double PLAY_BURST = 64, PLAY_RATE = 200;
    static final double STREAM_BURST = 128, STREAM_RATE = 640;
    static final double OVERFLOW_BURST = 64, OVERFLOW_RATE = 640;
    static final double CONTROL_BURST = 256, CONTROL_RATE = 256;

    private double plays = PLAY_BURST, streams = STREAM_BURST, overflow = OVERFLOW_BURST, controls = CONTROL_BURST;
    private long last;
    private boolean clearRequested;

    PacketAdmission(long now) { last = now; }

    /** Classifies by envelope opcode only; full validation happens after admission. */
    static Kind classify(int opcode, boolean priorityControl) {
        if (priorityControl) return Kind.CONTROL;
        return opcode == 5 || opcode == 7 || opcode == 13 ? Kind.STREAM : Kind.PLAY;
    }

    synchronized boolean admit(boolean control, long now) {
        return admit(control ? Kind.CONTROL : Kind.PLAY, now) != Result.REJECT;
    }

    synchronized Result admit(Kind kind, long now) {
        double elapsed = Math.max(0, now - last) / 1_000_000_000d;
        plays = Math.min(PLAY_BURST, plays + elapsed * PLAY_RATE);
        streams = Math.min(STREAM_BURST, streams + elapsed * STREAM_RATE);
        overflow = Math.min(OVERFLOW_BURST, overflow + elapsed * OVERFLOW_RATE);
        controls = Math.min(CONTROL_BURST, controls + elapsed * CONTROL_RATE);
        last = now;
        switch (kind) {
            case CONTROL -> {
                if (controls < 1) { clearRequested = true; return Result.REJECT; }
                controls--;
            }
            case STREAM -> {
                if (streams >= 1) { streams--; return Result.ADMIT; }
                if (overflow < 1) return Result.REJECT;
                overflow--;
                return Result.COALESCE;
            }
            case PLAY -> {
                if (plays < 1) return Result.REJECT;
                plays--;
            }
        }
        return Result.ADMIT;
    }

    synchronized boolean takeClearRequest() { boolean clear = clearRequested; clearRequested = false; return clear; }
    synchronized void reset(long now) {
        plays = PLAY_BURST; streams = STREAM_BURST; overflow = OVERFLOW_BURST; controls = CONTROL_BURST;
        last = now; clearRequested = false;
    }
}
