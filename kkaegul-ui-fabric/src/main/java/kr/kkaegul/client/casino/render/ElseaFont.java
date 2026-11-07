package kr.kkaegul.client.casino.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 카페24 써라운드 글꼴 (화면 픽셀 1:1).
 * <p>
 * 마인크래프트 글꼴 렌더러를 쓰지 않고, 글자를 "실제 화면 픽셀 크기"로 직접 그려(안티앨리어싱)
 * 텍스처로 올린 뒤 화면 픽셀 경계에 맞춰 찍는다. → GUI 배율·크기와 상관없이 깨지지 않음.
 */
public final class ElseaFont {

    public enum Align { LEFT, CENTER, RIGHT }

    /** HUD 기본 글자 크기 */
    public static final float SIZE = 9f;

    private static Font base;
    private static boolean loadFailed;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    /** 글자 크기(px)별: 기준선에서 글자 가운데까지 (음수 = 위) */
    private static final Map<Integer, Float> CENTER = new LinkedHashMap<>();
    private static final Map<String, Float> ADVANCE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Float> e) {
            return size() > 4000;
        }
    };

    private ElseaFont() {
    }

    private static Font font(float px) {
        if (base == null && !loadFailed) {
            try {
                Optional<Resource> r = MinecraftClient.getInstance().getResourceManager()
                        .getResource(Identifier.of("kkaegul", "font/casino/ssurround.ttf"));
                if (r.isPresent()) {
                    try (InputStream in = r.get().getInputStream()) {
                        base = Font.createFont(Font.TRUETYPE_FONT, in);
                    }
                }
            } catch (Exception ignored) {
            }
            if (base == null) {
                loadFailed = true;
                base = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            }
        }
        return base.deriveFont(px);
    }

    /** 화면 픽셀 크기 (0.25px 단위로 맞춤 → 캐시 재사용) */
    private static float px(float size) {
        float p = (float) (size * HqCache.gui());
        return Math.max(4f, Math.round(p * 4f) / 4f);
    }

    private static float advancePx(String s, float px) {
        String k = px + "|" + s;
        Float v = ADVANCE.get(k);
        if (v == null) {
            v = (float) font(px).getStringBounds(s, FRC).getWidth();
            ADVANCE.put(k, v);
        }
        return v;
    }

    private static float centerPx(float px) {
        int k = Math.round(px * 4);
        Float v = CENTER.get(k);
        if (v == null) {
            GlyphVector gv = font(px).createGlyphVector(FRC, "가나다라ABC");
            Rectangle2D b = gv.getVisualBounds();
            v = (float) (b.getMinY() + b.getMaxY()) / 2f;
            CENTER.put(k, v);
        }
        return v;
    }

    /** 글자 크기 size일 때 가로 폭 (GUI 픽셀) */
    public static float width(String s, float size) {
        if (s == null || s.isEmpty()) return 0;
        float px = px(size);
        return (float) (advancePx(s, px) / HqCache.gui()) * (size / (float) (px / HqCache.gui()));
    }

    public static float width(String s) {
        return width(s, SIZE);
    }

    /**
     * 글자 그리기.
     *
     * @param x    정렬 기준 x (LEFT=왼쪽 끝, CENTER=가운데, RIGHT=오른쪽 끝)
     * @param cy   세로 가운데
     * @param size 글자 크기 (GUI 픽셀)
     * @return 그린 가로 폭
     */
    public static float draw(DrawContext ctx, String s, float x, float cy, float size, int argb, Align align) {
        return draw(ctx, s, x, cy, size, argb, align, 0);
    }

    /** 윤곽선(outline ARGB, 0이면 없음)이 있는 글자 */
    public static float draw(DrawContext ctx, String s, float x, float cy, float size, int argb, Align align, int outline) {
        if (s == null || s.isEmpty() || size <= 0) return 0;
        if ((argb & 0xFC000000) == 0) argb |= 0xFF000000;
        final int color = argb;
        double gui = HqCache.gui();
        float px = px(size);
        float k = (float) (size * gui / px); // 0.25px 반올림 보정
        float adv = advancePx(s, px);
        float w = (float) (adv / gui) * k;
        float left = switch (align) {
            case LEFT -> x;
            case CENTER -> x - w / 2f;
            case RIGHT -> x - w;
        };
        // 그림 (기준선 위 ascent, 아래 descent, 여백 pad)
        Font f = font(px);
        java.awt.font.LineMetrics lm = f.getLineMetrics(s, FRC);
        float ow = outline == 0 ? 0 : Math.max(1.5f, px * 0.11f);
        int pad = 2 + (int) Math.ceil(ow);
        int iw = (int) Math.ceil(adv) + pad * 2 + 2;
        int asc = (int) Math.ceil(lm.getAscent());
        int ih = asc + (int) Math.ceil(lm.getDescent()) + pad * 2;
        String key = "t|" + px + "|" + Integer.toHexString(color) + "|" + Integer.toHexString(outline) + "|" + s;
        Identifier id = HqCache.obtain(key, iw, ih, false, (pw, ph) -> render(s, f, pw, ph, pad, pad + asc, color, outline, ow));
        if (id == null) return w;
        // 기준선 위치 (GUI) = 글자 가운데가 cy에 오도록
        float baseline = (float) (cy - centerPx(px) / gui * k);
        float imgX = left - (float) (pad / gui) * k;
        float imgY = baseline - (float) ((pad + asc) / gui) * k;
        MatrixStack m = ctx.getMatrices();
        m.push();
        if (HqCache.enabled && HqCache.unitMatrix(ctx) && Math.abs(k - 1f) < 1e-3f) {
            // 화면 픽셀에 맞추기
            Matrix4f pm = m.peek().getPositionMatrix();
            double tx = pm.m30() + imgX, ty = pm.m31() + imgY;
            double sx = Math.round(tx * gui) / gui, sy = Math.round(ty * gui) / gui;
            m.translate((float) (imgX + (sx - tx)), (float) (imgY + (sy - ty)), 0);
            m.scale((float) (1 / gui), (float) (1 / gui), 1f);
        } else {
            m.translate(imgX, imgY, 0);
            m.scale((float) (k / gui), (float) (k / gui), 1f);
        }
        ctx.drawTexture(RenderLayer::getGuiTextured, id, 0, 0, 0, 0, iw, ih, iw, ih, iw, ih);
        m.pop();
        return w;
    }

    private static int[] render(String s, Font f, int pw, int ph, int x, int baseline, int argb, int outline, float ow) {
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setFont(f);
        if (outline != 0) {
            java.awt.Shape shape = new java.awt.font.TextLayout(s, f, g.getFontRenderContext())
                    .getOutline(java.awt.geom.AffineTransform.getTranslateInstance(x, baseline));
            g.setColor(new java.awt.Color(outline, true));
            g.setStroke(new java.awt.BasicStroke(ow * 2, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.draw(shape);
            g.setColor(new java.awt.Color(argb, true));
            g.fill(shape);
            g.dispose();
            return img.getRGB(0, 0, pw, ph, null, 0, pw);
        }
        g.setColor(new java.awt.Color(argb, true));
        g.drawString(s, (float) x, (float) baseline);
        g.dispose();
        return img.getRGB(0, 0, pw, ph, null, 0, pw);
    }

    /** HUD용: 기본 크기, 왼쪽 정렬 */
    public static float drawMid(DrawContext ctx, String s, float x, float cy, int argb) {
        return draw(ctx, s, x, cy, SIZE, argb, Align.LEFT);
    }
}
