package school.magiccodex.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Drag-split mode (0 split, 1 one each, 2 clone) for the bag's drag preview counts. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("heldButtonType") int magiccodex$heldButtonType();
}
