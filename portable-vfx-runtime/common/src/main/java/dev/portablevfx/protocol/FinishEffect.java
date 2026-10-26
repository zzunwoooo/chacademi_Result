package dev.portablevfx.protocol;

import java.util.UUID;

/** Stops emission and lets the existing phase's particles drain; terminal for its pose sequence. */
public record FinishEffect(UUID instanceId, String dimensionId, long sequence, boolean clearLocal) implements EffectMessage {
    public FinishEffect(UUID instanceId, String dimensionId, long sequence) { this(instanceId,dimensionId,sequence,true); }
    public FinishEffect {
        ProtocolValidation.requireUuid(instanceId);
        VfxProtocol.validateId(dimensionId);
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
    }
}
