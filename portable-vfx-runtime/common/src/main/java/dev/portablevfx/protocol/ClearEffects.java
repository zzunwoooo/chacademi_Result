package dev.portablevfx.protocol;

/** Clears all PortableVFX instances on each client receiving this message. */
public record ClearEffects() implements EffectMessage {
}
