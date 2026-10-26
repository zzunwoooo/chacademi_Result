package dev.portablevfx.protocol;

import java.util.UUID;

/** Stops one visual instance; clients treat an unknown instance as a no-op. */
public record StopEffect(UUID instanceId) implements EffectMessage {
    public StopEffect {
        ProtocolValidation.requireUuid(instanceId);
    }
}
