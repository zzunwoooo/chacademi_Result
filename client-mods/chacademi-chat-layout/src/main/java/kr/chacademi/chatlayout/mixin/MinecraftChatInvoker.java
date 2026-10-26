package kr.chacademi.chatlayout.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses the same chat-status checks and notices as vanilla's normal chat opener. */
@Mixin(Minecraft.class)
public interface MinecraftChatInvoker {
    @Invoker("openChatScreen")
    void chacademi$openNativeChat(String initial);
}
