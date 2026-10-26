package dev.portablevfx.client.render;

import dev.portablevfx.client.render.gl.GpuFailureException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectRendererFailureClassificationTest {
    @Test void onlyGlAndLinkageFailuresDisableTheBackend() {
        assertTrue(EffectRenderer.gpuFailure(new GpuFailureException("link failed")));
        assertTrue(EffectRenderer.gpuFailure(new UnsatisfiedLinkError("native")));
        assertFalse(EffectRenderer.gpuFailure(new IllegalStateException("Claude live particle budget exceeded")));
        assertFalse(EffectRenderer.gpuFailure(new IllegalArgumentException("Invalid Claude time")));
    }
}
