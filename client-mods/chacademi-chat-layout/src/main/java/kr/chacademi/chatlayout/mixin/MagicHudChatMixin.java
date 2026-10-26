package kr.chacademi.chatlayout.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Optional MagicCodex 0.39.0 HUD callback. Only its visibility read treats chat as an unobstructed HUD. */
@Pseudo
@Mixin(targets="school.magiccodex.client.PlayerHudClient",remap=false)
public abstract class MagicHudChatMixin {
    @Redirect(method="lambda$initialize$7",at=@At(value="FIELD",target="Lnet/minecraft/class_310;field_1755:Lnet/minecraft/class_437;",remap=false),remap=false,require=0,expect=3)
    private static Screen chacademi$keepHudWhileChatting(Minecraft client){
        return client.screen instanceof ChatScreen?null:client.screen;
    }
}