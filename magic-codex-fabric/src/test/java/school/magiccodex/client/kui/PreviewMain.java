package school.magiccodex.client.kui;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders every 큐디치 view with the reference sample data to PNG (layout check, not a test).
 * Usage: PreviewMain &lt;assets dir (…/resources/assets/magiccodex)&gt; &lt;out dir&gt;
 */
public final class PreviewMain {
    public static void main(String[] a) throws Exception {
        Path assets = Path.of(a[0]); String out = a[1];
        for (var v : Samples.ALL) {
            var img = new BufferedImage(v.w(), v.h(), BufferedImage.TYPE_INT_ARGB);
            var canvas = new AwtCanvas(img, assets);
            var pen = new Pen(canvas, v.mx(), v.my(), null);
            v.paint().accept(pen);
            AwtCanvas.save(img, out + "/" + v.name() + ".png");
        }
    }
}
