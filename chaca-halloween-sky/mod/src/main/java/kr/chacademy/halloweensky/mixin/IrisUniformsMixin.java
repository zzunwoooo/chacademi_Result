package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyState;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 셰이더(Complementary 수정본)에 할로윈 값 두 개를 넘긴다. Iris 가 없으면 이 믹스인은 그냥 건너뛴다.
 * chacaHalloween = 전체 분위기 0..1, chacaSweep = 쓸림 앞줄 (SkyState 와 같은 식).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms", remap = false)
abstract class IrisUniformsMixin {
    @Inject(method = "addIrisExclusiveUniforms", at = @At("TAIL"), remap = false, require = 0)
    private static void chacaSky$uniforms(UniformHolder uniforms, CallbackInfo ci) {
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "chacaHalloween", SkyState::amount);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "chacaSweep", SkyState::sweep);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "chacaDay", SkyState::day);
    }
}
