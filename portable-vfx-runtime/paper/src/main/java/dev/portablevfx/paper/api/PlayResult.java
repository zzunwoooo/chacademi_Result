package dev.portablevfx.paper.api;

import java.util.UUID;

/**
 * A handle is active if at least one recipient was sent the effect or has it queued for delivery.
 * {@code recipients}: viewers the PLAY was sent to in this call. {@code deferred}: eligible viewers
 * whose per-viewer budget was exhausted; their PLAY (with the original start tick) is retried on the
 * next ticks until a short deadline. {@code skippedRateLimited}: eligible viewers that were throttled
 * and could not be queued. All three zero means no eligible viewer exists in range.
 */
public record PlayResult(UUID handle, int recipients, int skippedRateLimited, double effectiveRadius, int deferred) {
    public PlayResult(UUID handle, int recipients, int skippedRateLimited, double effectiveRadius) {
        this(handle, recipients, skippedRateLimited, effectiveRadius, 0);
    }
}
