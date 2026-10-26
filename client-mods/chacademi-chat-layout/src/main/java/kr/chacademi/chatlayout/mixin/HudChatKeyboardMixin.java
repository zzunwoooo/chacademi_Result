package kr.chacademi.chatlayout.mixin;

import kr.chacademi.chatlayout.HudChatKeyPolicy;
import kr.chacademi.chatlayout.InteractionMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges existing chat/command bindings only while the optional MagicCodex cursor is open. */
@Mixin(KeyboardHandler.class)
public abstract class HudChatKeyboardMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private final HudChatKeyPolicy chacademi$chatOpener = new HudChatKeyPolicy();

    @Redirect(method = "keyPress", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(III)Z"), require = 1)
    private boolean chacademi$hudChatKey(Screen screen, int keyCode, int scanCode, int modifiers,
                                         long window, int eventKey, int eventScan,
                                         int action, int eventModifiers) {
        if (minecraft.screen == screen && minecraft.player != null && minecraft.level != null
            && minecraft.getOverlay() == null
            && chacademi$chatOpener.request(screen, InteractionMode.isMagicCursor(screen), action,
                minecraft.options.keyChat.matches(keyCode, scanCode),
                minecraft.options.keyCommand.matches(keyCode, scanCode))) {
            return true;
        }
        return screen.keyPressed(keyCode, scanCode, modifiers);
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void chacademi$openPendingHudChat(CallbackInfo ci) {
        String initial = chacademi$chatOpener.take(minecraft.screen);
        if (initial != null && minecraft.player != null && minecraft.level != null
            && minecraft.getOverlay() == null && minecraft.isWindowActive()) {
            ((MinecraftChatInvoker)(Object)minecraft).chacademi$openNativeChat(initial);
        }
    }
}
