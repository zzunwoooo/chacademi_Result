package kr.kkaegul.client.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.List;

/**
 * 글자를 최종 픽셀 크기로 직접 래스터화 (Pretendard TTF, AWT). 게임과 미리보기가 같은 코드를 쓴다.
 * 마인크래프트 비트맵 폰트 대신 이걸 써서 한글이 고해상도에서도 또렷하게 나온다.
 */
public final class TextRaster {
    private TextRaster() {}

    private static final FontRenderContext CTX = new FontRenderContext(null, true, true);
    /** 세로 중앙 → 베이스라인 거리 (em 비율). Pretendard 한글 기준 */
    public static final float CENTER_TO_BASELINE = 0.36f;
    public static final int OUTLINE_RGB = 0x4A3426;

    private static volatile Font regular, bold;

    public static void load(InputStream semibold, InputStream extrabold) throws Exception {
        regular = Font.createFont(Font.TRUETYPE_FONT, semibold);
        bold = Font.createFont(Font.TRUETYPE_FONT, extrabold);
        raster("깨굴 운빨 0123456789", 20, 0xFFFFFF, 0); // 한글 글리프 준비
    }

    public static boolean ready() { return regular != null; }

    private static Font face(boolean b) { return b ? bold : regular; }

    public record Raster(BufferedImage image, float left, float baseline, float advance) {}

    private static TextLayout layout(List<Fmt.Run> runs, float px, boolean allBold) {
        StringBuilder sb = new StringBuilder();
        for (Fmt.Run r : runs) sb.append(r.text());
        AttributedString a = new AttributedString(sb.toString());
        int at = 0;
        for (Fmt.Run r : runs) {
            int end = at + r.text().length();
            a.addAttribute(TextAttribute.FONT, face(allBold || r.bold()).deriveFont(px), at, end);
            a.addAttribute(TextAttribute.FOREGROUND, new Color(r.rgb()), at, end);
            at = end;
        }
        return new TextLayout(a.getIterator(), CTX);
    }

    public static float width(String s, float px, int style) {
        List<Fmt.Run> runs = Fmt.parse(s, 0xFFFFFF, false);
        if (runs.isEmpty() || !ready()) return 0;
        float w = layout(runs, px, (style & Canvas.BOLD) != 0).getAdvance();
        if ((style & Canvas.OUTLINE) != 0) w += outline(px) * 2;
        return w;
    }

    static float outline(float px) { return Math.max(1.2f, px * 0.11f); }

    /**
     * @param rgb 기본 색 (색코드가 없을 때)
     */
    public static Raster raster(String s, float px, int rgb, int style) {
        List<Fmt.Run> runs = Fmt.parse(s, rgb, (style & Canvas.LIGHT) != 0);
        if (runs.isEmpty() || !ready()) return null;
        TextLayout l = layout(runs, px, (style & Canvas.BOLD) != 0);
        float ol = (style & Canvas.OUTLINE) != 0 ? outline(px) : 0;
        float sh = (style & Canvas.SHADOW) != 0 ? Math.max(1, px * 0.07f) : 0;
        float pad = 2 + ol + sh;
        float ascent = l.getAscent(), descent = l.getDescent();
        int w = (int) Math.ceil(l.getAdvance() + pad * 2), h = (int) Math.ceil(ascent + descent + pad * 2);
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float bx = pad, by = pad + ascent;
            Shape shape = l.getOutline(AffineTransform.getTranslateInstance(bx, by));
            if (sh > 0) {
                g.setColor(new Color(0, 0, 0, 90));
                g.fill(AffineTransform.getTranslateInstance(0, sh).createTransformedShape(shape));
            }
            if (ol > 0) {
                g.setColor(new Color(OUTLINE_RGB));
                g.setStroke(new BasicStroke(ol * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(shape);
            }
            l.draw(g, bx, by);
        } finally {
            g.dispose();
        }
        return new Raster(img, pad, pad + ascent, l.getAdvance() + ol * 2);
    }

    /** 단어 단위 줄바꿈 (색코드 유지) */
    public static java.util.List<String> wrap(String text, float maxW, float px, int style) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String para : Fmt.amp(text).split("\n", -1)) {
            String carry = lastCode(out.isEmpty() ? "" : out.get(out.size() - 1));
            String line = "";
            for (String word : para.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && width(carry + next, px, style) > maxW) {
                    out.add(carry + line);
                    carry = lastCode(carry + line);
                    line = word;
                } else line = next;
            }
            out.add(carry + line);
        }
        return out;
    }

    private static String lastCode(String s) {
        int i = s.lastIndexOf('§');
        return i >= 0 && i + 1 < s.length() ? s.substring(i, i + 2) : "";
    }
}
