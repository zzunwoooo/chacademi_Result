package dev.portablevfx.paper.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global and per-viewer packet budgets. The global per-tick budget is split by class: POSE/ORIENT
 * streams may use at most RelayLimits.streamPacketsPerTick() of it, so the remainder is always
 * available to PLAY/IMPACT/FINISH/STOP/CLEAR even though poses are sent first each tick. Every per-viewer class budget is a strict subset of the
 * client's PacketAdmission bucket for the same class (see RelayLimits viewer constants), so a
 * compliant relay never makes a client drop PLAY/IMPACT because poses used the shared budget.
 */
final class SendBudget {
    private long tick = Long.MIN_VALUE;
    private int packets;
    /** 이번 tick 에 보낸 POSE/LINK_POSE/ORIENT 수. RelayLimits.streamPacketsPerTick() 을 넘지 못한다. */
    private int streamPackets;
    private int plays;
    private final Map<UUID, Integer> perPlayer = new HashMap<>();
    private final Map<UUID, Integer> streams = new HashMap<>();
    private final Map<UUID, Integer> controls = new HashMap<>();
    /** Persistent per-viewer PLAY/IMPACT token bucket mirroring the client's burst/refill. */
    private final Map<UUID, double[]> playTokens = new HashMap<>();

    void sync(long now) {
        if (tick != now) {
            tick = now;
            packets = 0;
            streamPackets = 0;
            plays = 0;
            perPlayer.clear();
            streams.clear();
            controls.clear();
        }
    }

    boolean takePlay(RelayLimits limits) {
        if (plays >= limits.maxPlaysPerTick()) return false;
        plays++;
        return true;
    }

    boolean takePacket(UUID player, RelayLimits limits) {
        return takePackets(player, limits, 1);
    }

    boolean takePackets(UUID player, RelayLimits limits, int count) {
        if (!hasPackets(player, limits, count)) return false;
        packets += count;
        perPlayer.merge(player, count, Integer::sum);
        return true;
    }

    private boolean hasPackets(UUID player, RelayLimits limits, int count) {
        return count >= 1 && packets + count <= limits.maxPacketsPerTick()
                && perPlayer.getOrDefault(player, 0) + count <= limits.maxPacketsPerPlayerPerTick();
    }

    /** New-phase PLAY/IMPACT: one viewer play token plus {@code count} packets (2 for PLAY+initial pose). */
    boolean takeViewerPlay(UUID player, RelayLimits limits, int count) {
        double[] bucket = playTokens.computeIfAbsent(player, ignored -> new double[] {RelayLimits.VIEWER_PLAY_BURST, tick});
        double refilled = Math.min(RelayLimits.VIEWER_PLAY_BURST,
                bucket[0] + Math.max(0, tick - (long) bucket[1]) * RelayLimits.VIEWER_PLAYS_PER_TICK);
        bucket[0] = refilled; bucket[1] = tick;
        if (refilled < 1 || !takePackets(player, limits, count)) return false;
        bucket[0] = refilled - 1;
        return true;
    }

    /** Remaining POSE/LINK_POSE/ORIENT slots for this viewer in the current tick. */
    int streamRemaining(UUID player, RelayLimits limits) {
        int left = Math.min(RelayLimits.VIEWER_STREAM_PER_TICK - streams.getOrDefault(player, 0),
                limits.maxPacketsPerPlayerPerTick() - perPlayer.getOrDefault(player, 0));
        // 전역 예산은 종류별로 나뉜다: 스트림은 자기 몫(기본 50%)까지만 쓰고 PLAY/제어 몫을 침범하지 못한다.
        left = Math.min(left, limits.streamPacketsPerTick() - streamPackets);
        return Math.max(0, Math.min(left, limits.maxPacketsPerTick() - packets));
    }

    boolean takeStream(UUID player, RelayLimits limits) {
        if (streamRemaining(player, limits) < 1 || !takePacket(player, limits)) return false;
        streams.merge(player, 1, Integer::sum);
        streamPackets++;
        return true;
    }

    boolean takeControl(UUID player, RelayLimits limits) {
        if (controls.getOrDefault(player, 0) >= RelayLimits.VIEWER_CONTROLS_PER_TICK || !takePacket(player, limits)) return false;
        controls.merge(player, 1, Integer::sum);
        return true;
    }

    void forget(UUID player) { playTokens.remove(player); }

    int packets() { return packets; }
    int streamPackets() { return streamPackets; }
    int plays() { return plays; }
}
