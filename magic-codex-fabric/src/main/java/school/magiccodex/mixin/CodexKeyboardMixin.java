package school.magiccodex.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.MagicCodexClient;
import school.magiccodex.client.CastingClient;
import school.magiccodex.client.PlayerHudClient;
import school.magiccodex.client.StatsClient;

/** Listen before vanilla resolves conflicting key bindings. Other UI text input stays untouched. */
@Mixin(Keyboard.class)
public abstract class CodexKeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void magiccodex$openShortcut(long window, int key, int scan, int action, int modifiers, CallbackInfo ci) {
        var client = MinecraftClient.getInstance();
        if (window == client.getWindow().getHandle() && ((!school.magiccodex.client.KyudichiClient.reservesKey(client, key, scan) && CastingClient.handleKey(client, key, scan, action))
                || PlayerHudClient.handleCursorKey(client, key, action)
                || school.magiccodex.client.UiKeys.handleKey(client, key, scan, action)
                || school.magiccodex.client.EquipmentClient.handleKey(client,key,scan,action)
                || StatsClient.handleKey(client, key, scan, action)
                || MagicCodexClient.handleOpenShortcut(client, key, scan, action)
                || school.magiccodex.client.KyudichiClient.handleKey(client, key, scan, action)))
            ci.cancel();
    }
}
