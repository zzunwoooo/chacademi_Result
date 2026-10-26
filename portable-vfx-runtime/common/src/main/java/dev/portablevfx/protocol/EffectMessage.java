package dev.portablevfx.protocol;

/** A bounded, visual-only message. Nothing in this protocol mutates gameplay state. */
public sealed interface EffectMessage permits PlayEffect, StopEffect, ClearEffects, OrientEffect, PoseEffect, FinishEffect, ImpactEffect {
}
