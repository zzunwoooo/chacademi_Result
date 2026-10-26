package dev.portablevfx.client.render.gl;

/** Claude rendering shaders use GLSL 330. Do not downgrade newer contexts. */
public final class OpenGlContextPolicy {
    private OpenGlContextPolicy() { }
    public static int requiredMinor(int major, int minor) {
        return major == 3 ? Math.max(3, minor) : minor;
    }
}
