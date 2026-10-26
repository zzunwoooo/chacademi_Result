package school.magiccodex.client.kui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Java2D canvas for layout previews: same fonts and text placement rule as CodexTypography (ink-centred vertically,
 * ink-left or ink-centred horizontally), same parts. Faces, brooms and spell icons are simple stand-ins.
 */
public final class AwtCanvas implements Canvas {
    private final Graphics2D g;
    private final Path assets;
    private final Map<String, BufferedImage> images = new HashMap<>();
    private final Map<Face, Font> fonts = new HashMap<>();
    private final ArrayDeque<AffineTransform> stack = new ArrayDeque<>();
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    public AwtCanvas(BufferedImage target, Path assets) throws Exception {
        this.assets = assets;
        g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        Path font = assets.resolve("font");
        fonts.put(Face.TITLE, Font.createFont(Font.TRUETYPE_FONT, font.resolve("maruburi_bold.ttf").toFile()));
        fonts.put(Face.SERIF, Font.createFont(Font.TRUETYPE_FONT, font.resolve("maruburi_semibold.ttf").toFile()));
        fonts.put(Face.SANS, Font.createFont(Font.TRUETYPE_FONT, font.resolve("pretendard_medium.ttf").toFile()));
        fonts.put(Face.SANS_BOLD, Font.createFont(Font.TRUETYPE_FONT, font.resolve("pretendard_bold.ttf").toFile()));
    }

    private BufferedImage part(String name) {
        return images.computeIfAbsent(name, n -> {
            try { return ImageIO.read(assets.resolve("textures/gui/kyudichi/ui/" + n + ".png").toFile()); }
            catch (Exception e) { throw new IllegalStateException(n, e); }
        });
    }
    private void alpha(float a) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(a, 0, 1))); }

    @Override public void image(String p, float x, float y, float w, float h, float a) {
        alpha(a);
        var t = g.getTransform();
        g.translate(x, y); g.scale(w / part(p).getWidth(), h / part(p).getHeight());
        g.drawImage(part(p), 0, 0, null);
        g.setTransform(t); alpha(1);
    }
    @Override public void imageRotated(String p, float cx, float cy, float w, float h, float deg, float a) {
        var t = g.getTransform();
        g.translate(cx, cy); g.rotate(Math.toRadians(deg));
        image(p, -w / 2, -h / 2, w, h, a);
        g.setTransform(t);
    }
    @Override public void texture(String resource, float x, float y, float w, float h, float a) {
        alpha(a);
        g.setColor(new Color(0x6E5BBF)); g.fill(new Ellipse2D.Float(x + w * .12f, y + h * .12f, w * .76f, h * .76f));
        g.setColor(new Color(0xE9DDFF)); g.fill(new Ellipse2D.Float(x + w * .38f, y + h * .38f, w * .24f, h * .24f));
        alpha(1);
    }
    @Override public void head(UUID id, String skin, float x, float y, float size) {
        int hash = id.hashCode();
        Color hair = new Color(0x20 + (hash & 0x3F), 0x18 + ((hash >> 6) & 0x2F), 0x14 + ((hash >> 12) & 0x2F));
        g.setColor(new Color(0xE8B796)); g.fill(new java.awt.geom.Rectangle2D.Float(x, y, size, size));
        g.setColor(hair); g.fill(new java.awt.geom.Rectangle2D.Float(x, y, size, size * .38f));
        g.setColor(new Color(0x3A7BD5)); float e = size / 8;
        g.fill(new java.awt.geom.Rectangle2D.Float(x + e, y + e * 4.2f, e * 1.4f, e)); g.fill(new java.awt.geom.Rectangle2D.Float(x + e * 5.6f, y + e * 4.2f, e * 1.4f, e));
    }
    @Override public void avatar(UUID id, String skin, float cx, float cy, float r) {
        var clip = g.getClip();
        g.clip(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        head(id, skin, cx - r, cy - r, r * 2);
        g.setClip(clip);
    }
    @Override public boolean broom(String id, float cx, float cy, float w, float h) {
        g.setStroke(new BasicStroke(Math.max(2, h / 10), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x8A5A32)); g.draw(new java.awt.geom.Line2D.Float(cx - w * .45f, cy + h * .3f, cx + w * .2f, cy - h * .25f));
        g.setColor(new Color(0xC9A15A)); g.fill(new Ellipse2D.Float(cx + w * .1f, cy - h * .45f, w * .35f, h * .35f));
        return true;
    }
    @Override public void text(String s, float x, float y, float size, int argb, Face face, int align) {
        if (s.isBlank()) return;
        var layout = new TextLayout(s, fonts.get(face).deriveFont(size), FRC);
        var b = layout.getBounds();
        float left = align == CENTER ? x - (float) b.getWidth() / 2 - (float) b.getX() : align == RIGHT ? x - (float) b.getWidth() - (float) b.getX() : x - (float) b.getX();
        float top = y - (float) b.getHeight() / 2 - (float) b.getY();
        g.setColor(new Color(argb, true));
        layout.draw(g, left, top);
    }
    @Override public float width(String s, float size, Face face) { return s.isEmpty() ? 0 : (float) new TextLayout(s, fonts.get(face).deriveFont(size), FRC).getAdvance(); }
    @Override public void fill(float x, float y, float w, float h, int argb) {
        // no antialiasing on rectangles: like the game's quads, adjacent strips must not leave seams
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(argb, true)); g.fill(new java.awt.geom.Rectangle2D.Float(x, y, w, h));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }
    @Override public void arc(float cx, float cy, float inner, float outer, float start, float end, int argb) {
        var a = new Area(new Arc2D.Float(cx - outer, cy - outer, outer * 2, outer * 2, 90 - start * 360, -(end - start) * 360, Arc2D.PIE));
        if (inner > 0) a.subtract(new Area(new Ellipse2D.Float(cx - inner, cy - inner, inner * 2, inner * 2)));
        g.setColor(new Color(argb, true)); g.fill(a);
    }
    @Override public void push(float tx, float ty, float s) { stack.push(g.getTransform()); g.translate(tx, ty); g.scale(s, s); }
    @Override public void pop() { g.setTransform(stack.pop()); }
    @Override public void clip(float x, float y, float w, float h) { g.setClip(new java.awt.geom.Rectangle2D.Float(x, y, w, h)); }
    @Override public void unclip() { g.setClip(null); }

    public static void save(BufferedImage img, String file) throws Exception { ImageIO.write(img, "png", new File(file)); }
}
