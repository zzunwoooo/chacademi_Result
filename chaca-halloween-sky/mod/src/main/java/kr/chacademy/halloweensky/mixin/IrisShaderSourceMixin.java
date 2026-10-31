package kr.chacademy.halloweensky.mixin;

import java.nio.file.Path;
import kr.chacademy.halloweensky.ShaderPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris 가 셰이더 glsl 파일을 읽을 때 할로윈 변경분을 끼운다 (Complementary Unbound r5.5.1 일 때만). */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.include.IncludeGraph", remap = false)
abstract class IrisShaderSourceMixin {
    @Inject(method = "readFile", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void chacaSky$patch(Path path, CallbackInfoReturnable<String> cir) {
        String patched = ShaderPatcher.patched(path);
        if (patched != null) cir.setReturnValue(patched);
    }
}
