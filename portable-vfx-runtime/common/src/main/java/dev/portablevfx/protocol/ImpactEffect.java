package dev.portablevfx.protocol;

import java.util.UUID;

/** One server message finishes the previous phase and starts an explicitly identified world phase. */
public record ImpactEffect(UUID instanceId, long sequence, PlayEffect impact, EffectBasis basis) implements EffectMessage {
    public ImpactEffect {
        ProtocolValidation.requireUuid(instanceId);
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
        if (impact == null || basis == null) throw new IllegalArgumentException("impact and basis must not be null");
        if (instanceId.equals(impact.instanceId())) throw new IllegalArgumentException("impact requires a fresh instance UUID");
        if (impact.anchor() != EffectAnchor.WORLD || impact.offsetX() != 0 || impact.offsetY() != 0 || impact.offsetZ() != 0)
            throw new IllegalArgumentException("impact requires an unoffset world anchor");
    }
}
