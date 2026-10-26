package kr.chacademi.chatlayout.mixin;

import kr.chacademi.chatlayout.ChatLayout;
import kr.chacademi.chatlayout.InteractionMode;
import kr.chacademi.chatlayout.HudPointerRoute;
import kr.chacademi.chatlayout.HudMouseState;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes optional HUD-cursor gestures after vanilla mouse state bookkeeping. */
@Mixin(MouseHandler.class)
public abstract class HudMouseMixin implements HudMouseState {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Override
    public void chacademi$clearHudPointerMovement() {
        accumulatedDX = 0;
        accumulatedDY = 0;
    }

    @Redirect(method="onScroll",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"),require=1)
    private boolean chacademi$hudScroll(Screen screen,double x,double y,double horizontal,double vertical){
        return HudPointerRoute.route(InteractionMode.isMagicCursor(screen),
            ()->ChatLayout.handleScroll(x,y,vertical),()->screen.mouseScrolled(x,y,horizontal,vertical));
    }

    @Redirect(
        method = "onPress",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(DDI)Z"),
        require = 1
    )
    private boolean chacademi$hudClick(Screen screen, double x, double y, int button) {
        return HudPointerRoute.route(InteractionMode.isMagicCursor(screen),
            () -> ChatLayout.handleClick(x, y, button), () -> screen.mouseClicked(x, y, button));
    }

    @Redirect(
        method = "onPress",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseReleased(DDI)Z"),
        require = 1
    )
    private boolean chacademi$hudRelease(Screen screen, double x, double y, int button) {
        return HudPointerRoute.route(InteractionMode.isMagicCursor(screen),
            () -> ChatLayout.handleRelease(x, y, button), () -> screen.mouseReleased(x, y, button));
    }

    @Redirect(
        method = "handleAccumulatedMovement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseDragged(DDIDD)Z"),
        require = 1
    )
    private boolean chacademi$hudDrag(Screen screen, double x, double y, int button, double dx, double dy) {
        return HudPointerRoute.route(InteractionMode.isMagicCursor(screen),
            () -> ChatLayout.handleDrag(x, y, button), () -> screen.mouseDragged(x, y, button, dx, dy));
    }
}