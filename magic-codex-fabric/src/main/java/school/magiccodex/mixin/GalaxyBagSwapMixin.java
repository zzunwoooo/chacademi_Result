package school.magiccodex.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import school.magiccodex.client.GalaxyBagScreen;

/**
 * Survival inventory only (2026-10-24 아공간 배낭): every path that opens the exact vanilla {@code InventoryScreen}
 * (E key, tutorial, other mods) gets the bag instead. Creative, containers, crafting tables, shops and
 * subclasses of InventoryScreen are untouched.
 */
@Mixin(MinecraftClient.class)
public abstract class GalaxyBagSwapMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen magiccodex$galaxyBag(Screen screen) {
        var client = (MinecraftClient) (Object) this;
        return GalaxyBagScreen.replaces(client, screen) ? new GalaxyBagScreen(client.player) : screen;
    }
}
