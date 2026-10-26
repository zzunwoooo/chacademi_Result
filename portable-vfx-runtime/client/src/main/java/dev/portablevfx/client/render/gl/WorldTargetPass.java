package dev.portablevfx.client.render.gl;

import static org.lwjgl.opengl.GL33C.*;

/** Post-composite spatial pass. Never clears/copies world depth or edits a shader-pack target. */
public final class WorldTargetPass implements AutoCloseable {
    private final GlStateSnapshot state;

    private WorldTargetPass(int framebuffer, int width, int height) {
        if (framebuffer <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid main world framebuffer or dimensions");
        state = new GlStateSnapshot();
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Main world framebuffer is incomplete");
            if (glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL_NONE)
                throw new IllegalStateException("Main world framebuffer has no retained depth attachment");
            glViewport(0, 0, width, height);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_RASTERIZER_DISCARD);
            glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMask(true, true, true, true);
            glDepthRange(0, 1);
            glDepthFunc(GL_LEQUAL);
            glEnable(GL_DEPTH_TEST);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            // Claude rendering sets its own per-layer depth write and blending policy.
        } catch (RuntimeException | Error failure) {
            state.close();
            throw failure;
        }
    }

    public static WorldTargetPass begin(int framebuffer, int width, int height) {
        return new WorldTargetPass(framebuffer, width, height);
    }

    @Override public void close() { state.close(); }
}
