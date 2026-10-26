package school.magiccodex.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.ChatScreen;

/** With chat open the HUD stays visible; clicks on the top-left menu, the magic slots (drag to swap) and the 큐디치 queue card go there instead of the chat. */
final class HudChatClicks {
    private HudChatClicks() {}
    static void initialize() {
        HudCustomization.initialize();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ChatScreen) {
                ScreenEvents.afterRender(screen).register((s,ctx,x,y,delta)->{if(PlayerHudClient.active()&&!client.options.hudHidden){ctx.getMatrices().push();try{ctx.getMatrices().translate(0,0,800);TopMenuClient.renderTooltip(ctx);}finally{ctx.getMatrices().pop();}}});
                ScreenMouseEvents.allowMouseClick(screen).register((s, x, y, button) -> !TopMenuClient.click(x, y, button) && !ChunkClaimClient.click(x, y, button) && !HudSlotDrag.press(x, y, button)
                        && !KyudichiQueueClient.click(x, y, button) && !KyudichiClient.leaveClick(x, y, button));
                // A slot dragged onto another swaps the two (bottom-right magic bar).
                ScreenMouseEvents.allowMouseRelease(screen).register((s, x, y, button) -> !(HudSlotDrag.release(x, y, button) | KyudichiQueueClient.release(x, y, button)));
                ScreenEvents.remove(screen).register(s -> { HudSlotDrag.cancel(); KyudichiQueueClient.cursorClosed(); });
            }
        });
    }
}
