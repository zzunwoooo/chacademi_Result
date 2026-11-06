package kr.kkaegul.client.preview;

import kr.kkaegul.client.ui.Canvas;
import kr.kkaegul.client.ui.TextRaster;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** 미리보기용 캔버스 (게임 없이 화면 코드를 PNG 로) */
public final class AwtCanvas implements Canvas {
    private final Graphics2D g;
    private final float w, h, k;
    private final File root;
    private final Map<String, BufferedImage> cache = new HashMap<>();
    private final Deque<AffineTransform> stack = new ArrayDeque<>();

    // ───── 점검(audit): 그린 상자와 글자 영역을 기록 (기기 픽셀 좌표) ─────
    public record Box(java.awt.geom.Rectangle2D r, String what, BufferedImage src) {}
    public final java.util.List<Box> boxes = new java.util.ArrayList<>();
    public record TextBox(java.awt.geom.Rectangle2D r, String text, float px) {}
    public final java.util.List<TextBox> texts = new java.util.ArrayList<>();
    private java.awt.geom.Rectangle2D dev(float x, float y, float w, float h) {
        return g.getTransform().createTransformedShape(new java.awt.geom.Rectangle2D.Float(x, y, w, h)).getBounds2D();
    }

    /** pxW x pxH 이미지에 가상 1080 기준으로 */
    public AwtCanvas(BufferedImage out, File texRoot) {
        this.g = out.createGraphics();
        this.root = texRoot;
        this.k = out.getHeight() / 1080f;
        this.w = out.getWidth() / k;
        this.h = 1080;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(k, k);
    }

    private BufferedImage img(String id) {
        return cache.computeIfAbsent(id, i -> {
            try {
                File f = new File(root, i + ".png");
                return f.exists() ? ImageIO.read(f) : null;
            } catch (Exception e) { return null; }
        });
    }

    /** 미리보기용 아이템 그림: -Dkkaegul.items=폴더 (마인크래프트 textures/item). 없으면 회색 칸 */
    @Override public boolean item(String id, float x, float y, float size) {
        String n = id.toLowerCase().replace("minecraft:", "");
        String dir = System.getProperty("kkaegul.items", "");
        BufferedImage im = cache.computeIfAbsent("§item:" + n, i -> {
            try {
                File f = new File(dir, n + ".png");
                return dir.isEmpty() || !f.exists() ? null : ImageIO.read(f);
            } catch (Exception e) { return null; }
        });
        if (im == null) { fill(x + size * .15f, y + size * .15f, size * .7f, size * .7f, size * .12f, 0x55807060); return false; }
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(im, Math.round(x), Math.round(y), Math.round(size), Math.round(size), null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
        return true;
    }

    @Override public float width() { return w; }
    @Override public float height() { return h; }

    @Override public void image(String id, float x, float y, float ww, float hh, int argb) {
        BufferedImage im = img(id);
        if (im == null) { fill(x, y, ww, hh, 0, 0x80FF00FF); return; }
        imagePart(id, x, y, ww, hh, 0, 0, 1, 1, argb);
    }

    @Override public void imagePart(String id, float x, float y, float ww, float hh, float u0, float v0, float u1, float v1, int argb) {
        BufferedImage im = img(id);
        if (im == null || ww <= 0 || hh <= 0) return;
        int sx0 = Math.round(u0 * im.getWidth()), sy0 = Math.round(v0 * im.getHeight()), sx1 = Math.round(u1 * im.getWidth()), sy1 = Math.round(v1 * im.getHeight());
        if (sx1 <= sx0 || sy1 <= sy0) return;
        BufferedImage src = im.getSubimage(sx0, sy0, sx1 - sx0, sy1 - sy0);
        if ((argb >>> 24) > 40) boxes.add(new Box(dev(x, y, ww, hh), "img:" + id, src));
        float a = (argb >>> 24) / 255f;
        if ((argb & 0xFFFFFF) != 0xFFFFFF) {
            float[] sc = {((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, 1};
            BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            copy.createGraphics().drawImage(src, 0, 0, null);
            src = new RescaleOp(sc, new float[4], null).filter(copy, null);
        }
        var old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, a))));
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.scale(ww / src.getWidth(), hh / src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.setTransform(t);
        g.setComposite(old);
    }

    @Override public int[] size(String id) {
        BufferedImage im = img(id);
        return im == null ? null : new int[]{im.getWidth(), im.getHeight()};
    }

    @Override public void text(String s, float x, float y, float size, int argb, int align, int style) {
        // 게임처럼 최종 픽셀 크기로 래스터화 후 그린다
        float px = size * k * scaleY();
        TextRaster.Raster r = TextRaster.raster(s, px, argb & 0xFFFFFF, style);
        if (r == null) return;
        float inv = size / px;
        float left = align == CENTER ? x - r.advance() * inv / 2 : align == RIGHT ? x - r.advance() * inv : x;
        float base = y + size * TextRaster.CENTER_TO_BASELINE;
        if ((argb >>> 24) > 60) texts.add(new TextBox(dev(left, y - size * .5f, r.advance() * inv, size), s, px / k));
        var old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (argb >>> 24) / 255f));
        AffineTransform t = g.getTransform();
        g.translate(left - r.left() * inv, base - r.baseline() * inv);
        g.scale(inv, inv);
        g.drawImage(r.image(), 0, 0, null);
        g.setTransform(t);
        g.setComposite(old);
    }

    private float scaleY() {
        AffineTransform t = g.getTransform();
        return (float) Math.hypot(t.getScaleY(), t.getShearY()) / k;
    }

    @Override public float textWidth(String s, float size, int style) { return TextRaster.width(s, size, style); }

    @Override public void fill(float x, float y, float ww, float hh, float radius, int argb) {
        if ((argb >>> 24) > 200 && ww * hh > 0) boxes.add(new Box(dev(x, y, ww, hh), String.format("fill:%08X", argb), null));
        g.setColor(new Color(argb, true));
        if (radius <= 0) g.fill(new java.awt.geom.Rectangle2D.Float(x, y, ww, hh));
        else g.fill(new RoundRectangle2D.Float(x, y, ww, hh, radius * 2, radius * 2));
    }

    @Override public void push() { stack.push(g.getTransform()); }
    @Override public void pop() { g.setTransform(stack.pop()); }
    @Override public void translate(float x, float y) { g.translate(x, y); }
    @Override public void scale(float sx, float sy) { g.scale(sx, sy); }
    @Override public void rotate(float deg) { g.rotate(Math.toRadians(deg)); }
}
