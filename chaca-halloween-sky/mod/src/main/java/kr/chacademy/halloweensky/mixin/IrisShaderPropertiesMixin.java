package kr.chacademy.halloweensky.mixin;

import java.nio.file.Path;
import kr.chacademy.halloweensky.ShaderPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** shaders.properties 에 호박달 그림(customTexture.chacaPumpkin) 한 줄을 끼운다. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
abstract class IrisShaderPropertiesMixin {
    @Inject(method = "readProperties", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void chacaSky$patch(Path shaderPath, String name, CallbackInfoReturnable<String> cir) {
        String patched = ShaderPatcher.patched(shaderPath.resolve(name));
        if (patched != null) cir.setReturnValue(patched);
    }
}
