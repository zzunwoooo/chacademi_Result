package dev.portablevfx.paper.internal;

import dev.portablevfx.protocol.VfxProtocol;

/** Hard ceilings cannot be raised by configuration. Invalid numeric settings use safe defaults. */
public record RelayLimits(double maxRadius, double defaultRadius, int maxActiveHandles,
                          int maxPacketsPerTick, int maxPacketsPerPlayerPerTick,
                          int maxPlaysPerTick, int maxDurationTicks,
                          int finishDrainTicks, int playRetryTicks) {
    /** FINISH 이후 잔여 입자를 hard STOP 할 수 있도록 핸들을 추적하는 기본 시간(5초). */
    public static final int DEFAULT_FINISH_DRAIN_TICKS = 100;
    /** 예산 때문에 밀린 PLAY 를 다음 tick 들에 재시도하는 기본 기한(0.5초). 0 이면 재시도하지 않는다. */
    public static final int DEFAULT_PLAY_RETRY_TICKS = 10;
    /**
     * 전역 tick 예산 중 POSE/LINK_POSE/ORIENT 스트림이 쓸 수 있는 최대 비율. 나머지는
     * PLAY/IMPACT/FINISH/STOP/CLEAR 전용이라 pose 폭주가 새 시전을 막을 수 없다.
     */
    public static final int STREAM_SHARE_PERCENT = 50;
    public static final RelayLimits DEFAULT = new RelayLimits(128, 64, 4096, 8192, 32, 1024, 12000,
            DEFAULT_FINISH_DRAIN_TICKS, DEFAULT_PLAY_RETRY_TICKS);

    /** 기존 7개 인수 호출과의 호환용: drain/retry 는 기본값. */
    public RelayLimits(double maxRadius, double defaultRadius, int maxActiveHandles,
                       int maxPacketsPerTick, int maxPacketsPerPlayerPerTick,
                       int maxPlaysPerTick, int maxDurationTicks) {
        this(maxRadius, defaultRadius, maxActiveHandles, maxPacketsPerTick, maxPacketsPerPlayerPerTick,
                maxPlaysPerTick, maxDurationTicks, DEFAULT_FINISH_DRAIN_TICKS, DEFAULT_PLAY_RETRY_TICKS);
    }

    /*
     * Hard per-viewer class budgets inside maxPacketsPerPlayerPerTick. Each is strictly below the
     * matching client PacketAdmission bucket (client/network/PacketAdmission.java), so poses can
     * never starve new-phase PLAY/IMPACT and a compliant relay never trips client fail-safes:
     *   PLAY/IMPACT (1,4,6,9,10,12): token bucket 48 burst, +8/tick  (client 64 burst, 200/s = 10/tick)
     *   POSE/LINK_POSE/ORIENT (7,13,5): 16/tick, plus <=8 initial cast poses (client 128 burst, 640/s = 32/tick)
     *   STOP/CLEAR/FINISH (2,3,8,11):   10/tick                     (client 256 burst, 256/s = 12.8/tick)
     */
    public static final int VIEWER_PLAY_BURST = 48, VIEWER_PLAYS_PER_TICK = 8;
    public static final int VIEWER_STREAM_PER_TICK = 16;
    public static final int VIEWER_CONTROLS_PER_TICK = 10;

    public RelayLimits {
        maxRadius = bounded(maxRadius, 128, 1, 256);
        defaultRadius = bounded(defaultRadius, Math.min(64, maxRadius), 1, maxRadius);
        maxActiveHandles = bounded(maxActiveHandles, 1, 16384);
        maxPacketsPerTick = bounded(maxPacketsPerTick, 1, 32768);
        maxPacketsPerPlayerPerTick = bounded(maxPacketsPerPlayerPerTick, 1, 128);
        maxPlaysPerTick = bounded(maxPlaysPerTick, 1, 4096);
        maxDurationTicks = bounded(maxDurationTicks, 1, VfxProtocol.MAX_DURATION_TICKS);
        finishDrainTicks = bounded(finishDrainTicks, 0, 1200);
        playRetryTicks = bounded(playRetryTicks, 0, 40);
    }

    /** 이번 tick 에 POSE/LINK_POSE/ORIENT 가 쓸 수 있는 전역 상한(최소 1). */
    public int streamPacketsPerTick() {
        return Math.max(1, (int) ((long) maxPacketsPerTick * STREAM_SHARE_PERCENT / 100));
    }

    /** FINISH 이후 추적 중(draining) 핸들의 메모리 보호 상한. 활성 핸들 상한과 별도로 센다. */
    public int maxDrainingHandles() {
        return Math.max(1024, maxActiveHandles * 4);
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double bounded(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
