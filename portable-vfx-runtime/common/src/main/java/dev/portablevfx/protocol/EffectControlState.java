package dev.portablevfx.protocol;

import java.util.UUID;

/** Bounded per-instance sequence gate. A terminal phase never accepts a later pose or second impact. */
public final class EffectControlState {
    private final UUID instanceId;
    private final String dimensionId;
    private long sequence = -1;
    private boolean terminal;
    public EffectControlState(UUID instanceId, String dimensionId) {
        ProtocolValidation.requireUuid(instanceId); VfxProtocol.validateId(dimensionId);
        this.instanceId=instanceId; this.dimensionId=dimensionId;
    }
    public boolean accept(UUID id, String dimension, long next, boolean finish) {
        if (terminal || !instanceId.equals(id) || !dimensionId.equals(dimension) || next < 0 || next <= sequence) return false;
        sequence=next; terminal=finish; return true;
    }
    public long sequence() { return sequence; }
    public boolean terminal() { return terminal; }
}
