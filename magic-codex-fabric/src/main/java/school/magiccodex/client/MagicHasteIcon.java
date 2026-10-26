package school.magiccodex.client;
import net.minecraft.client.gui.DrawContext;
/** Shared transparent PNG artwork for equipment and stats. */
final class MagicHasteIcon {
    private MagicHasteIcon(){}
    static void draw(DrawContext c,float x,float y,float radius){StatIcons.draw(c,7,Math.round(x),Math.round(y),Math.round(radius*2));}
}
