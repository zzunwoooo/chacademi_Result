package dev.portablevfx.client.mixin;

import dev.portablevfx.client.render.gl.OpenGlContextPolicy;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Vanilla 1.21.4 requests 3.2, which NVIDIA can honor literally even on modern hardware. */
@Mixin(Window.class)
public abstract class WindowContextMixin {
    @Unique private int portablevfx$contextMajor = 3;

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V", remap = false))
    private void portablevfx$requireClaudeContext(Args args) {
        int hint = args.get(0);
        int value = args.get(1);
        if (hint == GLFW.GLFW_CONTEXT_VERSION_MAJOR) portablevfx$contextMajor = value;
        if (hint == GLFW.GLFW_CONTEXT_VERSION_MINOR)
            args.set(1, OpenGlContextPolicy.requiredMinor(portablevfx$contextMajor, value));
    }
}
