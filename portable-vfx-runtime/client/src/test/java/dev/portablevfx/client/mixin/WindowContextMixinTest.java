package dev.portablevfx.client.mixin;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class WindowContextMixinTest {
    private static final class Hook extends WindowContextMixin { }
    private static final class HintArgs extends Args {
        HintArgs(int hint, int value) { super(new Object[]{hint, value}); }
        public <T> void set(int index, T value) { values[index] = value; }
        public void setAll(Object... values) { System.arraycopy(values, 0, this.values, 0, values.length); }
    }
    private static int apply(Hook hook, int hint, int value) throws Exception {
        var method = WindowContextMixin.class.getDeclaredMethod("portablevfx$requireClaudeContext", Args.class);
        method.setAccessible(true);
        var args = new HintArgs(hint, value);
        method.invoke(hook, args);
        assertEquals(hint, (int)args.get(0));
        return args.get(1);
    }
    @Test void actualHookUpgradesVanillaHintsWithoutChangingOtherHints() throws Exception {
        var hook = new Hook();
        assertEquals(3, apply(hook, GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3));
        assertEquals(3, apply(hook, GLFW.GLFW_CONTEXT_VERSION_MINOR, 2));
        assertEquals(GLFW.GLFW_OPENGL_CORE_PROFILE, apply(hook, GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE));
        assertEquals(1, apply(hook, GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1));
    }
    @Test void actualHookRetainsHigherVersionRequests() throws Exception {
        var hook = new Hook();
        assertEquals(4, apply(hook, GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4));
        assertEquals(1, apply(hook, GLFW.GLFW_CONTEXT_VERSION_MINOR, 1));
        assertEquals(6, apply(hook, GLFW.GLFW_CONTEXT_VERSION_MINOR, 6));
    }
}
