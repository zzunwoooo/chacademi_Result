package kr.kkaegul.client.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 창을 열 때 커서가 가운데로 튀지 않게 원래 위치로 되돌리는 용도 */
@Mixin(Mouse.class)
public interface MouseAccessor {
    @Accessor("x") void kkaegul$setX(double x);
    @Accessor("y") void kkaegul$setY(double y);
}
