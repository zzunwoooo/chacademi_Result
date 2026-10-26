package dev.portablevfx.client.claude;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

class ClaudeDepthFormatTest {
    @Test void resolvesActualUnsizedDepthStorageWithoutGuessingUploadType() {
        assertEquals(GL_DEPTH_COMPONENT16,ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,16,GL_UNSIGNED_NORMALIZED,0));
        assertEquals(GL_DEPTH_COMPONENT24,ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,24,GL_UNSIGNED_NORMALIZED,0));
        assertEquals(GL_DEPTH_COMPONENT32,ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,32,GL_UNSIGNED_NORMALIZED,0));
        assertEquals(GL_DEPTH_COMPONENT32F,ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,32,GL_FLOAT,0));
        assertEquals(GL_DEPTH24_STENCIL8,ClaudePostPass.sizedDepthFormat(GL_DEPTH_STENCIL,24,GL_UNSIGNED_NORMALIZED,8));
        assertEquals(GL_DEPTH32F_STENCIL8,ClaudePostPass.sizedDepthFormat(GL_DEPTH_STENCIL,32,GL_FLOAT,8));
    }
    @Test void unknownStorageFailsRatherThanLosingHostDepthPrecision() {
        assertThrows(IllegalStateException.class,()->ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,20,GL_UNSIGNED_NORMALIZED,0));
        assertThrows(IllegalStateException.class,()->ClaudePostPass.sizedDepthFormat(GL_DEPTH_COMPONENT,24,GL_FLOAT,0));
        assertThrows(IllegalStateException.class,()->ClaudePostPass.sizedDepthFormat(GL_DEPTH_STENCIL,24,GL_UNSIGNED_NORMALIZED,0));
    }
}
