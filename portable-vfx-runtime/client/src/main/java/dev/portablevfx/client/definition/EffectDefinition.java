package dev.portablevfx.client.definition;

/** A resource definition chooses a backend, never a Java class or executable script. */
public record EffectDefinition(String effect, int durationTicks, float magnification, String backend) {
    public EffectDefinition(String effect, int durationTicks, float magnification) {
        this(effect, durationTicks, magnification, "claude");
    }
}
