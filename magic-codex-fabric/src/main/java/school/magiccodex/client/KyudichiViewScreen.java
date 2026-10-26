package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import school.magiccodex.client.kui.Pen;

/**
 * Base for the 큐디치 windows: draws a kui view (design canvas W×H, e.g. 1672×941) scaled to fit and centred,
 * and maps clicks back through the view's named hit areas (press + release on the same control = click).
 */
abstract class KyudichiViewScreen extends Screen {
    private Pen last;
    private String pressed;
    protected float ox, oy, scale = 1;

    KyudichiViewScreen(String title) { super(Text.literal(title)); }

    abstract float designW();
    abstract float designH();
    abstract void paint(Pen p);
    abstract void clicked(String id, int button);
    /** Fraction of the screen the canvas may use. */
    float fill() { return .96f; }
    int dim() { return 0x99060B12; }
    void pressed(String id, float x, float y) { }
    /** Drawn in screen pixels before the view (e.g. a background that covers the whole screen). */
    void backdrop(KyudichiCanvas canvas) { }
    void released(float x, float y) { }

    /** Control under a design-pixel point in the last frame, or null. */
    String hitAt(float x, float y) { return last == null ? null : last.hitAt(x, y); }

    float mx(double screenX) { return (float) ((screenX - ox) / scale); }
    float my(double screenY) { return (float) ((screenY - oy) / scale); }

    @Override public boolean shouldPause() { return false; }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        if (dim() != 0) c.fill(0, 0, width, height, dim());
        scale = Math.min(width * fill() / designW(), height * fill() / designH());
        ox = (width - designW() * scale) / 2; oy = (height - designH() * scale) / 2;
        var images = UiResources.images(); var type = UiResources.text();
        images.beginFrame(); type.beginFrame();
        var canvas = new KyudichiCanvas(c, images, type);
        backdrop(canvas);
        canvas.push(ox, oy, scale);
        try {
            var pen = new Pen(canvas, mx(mouseX), my(mouseY), pressed);
            paint(pen);
            last = pen;
        } finally { canvas.pop(); images.endFrame(); }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        String id = last == null ? null : last.hitAt(mx(x), my(y));
        if (id != null) { pressed = id; pressed(id, mx(x), my(y)); return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        String was = pressed; pressed = null;
        released(mx(x), my(y));
        String id = last == null ? null : last.hitAt(mx(x), my(y));
        if (was != null && was.equals(id)) { sound(1.3f, .2f); clicked(id, button); return true; }
        return super.mouseReleased(x, y, button);
    }

    void sound(float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
    }
}
