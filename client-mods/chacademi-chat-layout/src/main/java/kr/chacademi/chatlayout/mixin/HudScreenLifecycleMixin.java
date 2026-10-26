package kr.chacademi.chatlayout.mixin;

import kr.chacademi.chatlayout.ChatLayout;
import kr.chacademi.chatlayout.InteractionMode;
import kr.chacademi.chatlayout.HudMouseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Saves and releases an addon gesture when Alt closes MagicCodex's cursor screen. */
@Mixin(Screen.class)
public abstract class HudScreenLifecycleMixin {
    @Inject(method = "removed", at = @At("HEAD"), require = 1)
    private void chacademi$hudRemoved(CallbackInfo ci) {
        if (InteractionMode.isMagicCursor(this)) {
            ChatLayout.handleRemoved();
            ((HudMouseState)(Object)Minecraft.getInstance().mouseHandler).chacademi$clearHudPointerMovement();
        }
    }
}