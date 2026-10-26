package dev.portablevfx.client.render.gl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class OpenGlContextPolicyTest {
    @Test void upgradesVanillaContext() {
        assertEquals(3, OpenGlContextPolicy.requiredMinor(3, 2));
        assertEquals(3, OpenGlContextPolicy.requiredMinor(3, 0));
    }
    @Test void retainsSupportedAndNewerContexts() {
        assertEquals(3, OpenGlContextPolicy.requiredMinor(3, 3));
        assertEquals(6, OpenGlContextPolicy.requiredMinor(4, 6));
        assertEquals(1, OpenGlContextPolicy.requiredMinor(4, 1));
    }
}
