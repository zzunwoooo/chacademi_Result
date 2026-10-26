package school.magiccodex.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;

/** Grayscale coverage at the final display size; independent of Minecraft's bitmap font shader. */
public final class CodexTextRasterizer {
    public static final int PADDING = 2;
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private CodexTextRasterizer() {}

    public static float width(Font font, String text, float size) {
        return text.isEmpty() ? 0 : new TextLayout(text, font.deriveFont(size), CONTEXT).getAdvance();
    }

    /** Glyph ink top/bottom relative to the baseline (negative = above), for lining up text of different sizes. */
    public static float[] ink(Font font, String text, float size) {
        if (text.isEmpty()) return new float[]{0, 0};
        var r = new TextLayout(text, font.deriveFont(size), CONTEXT).getBounds();
        return new float[]{(float) r.getMinY(), (float) r.getMaxY()};
    }

    public static BufferedImage rasterize(Font font, String text, float pixelSize) {
        return rasterize(font,text,pixelSize,false);
    }
    public static BufferedImage rasterize(Font font,String text,float pixelSize,boolean outline){
        var layout = new TextLayout(text, font.deriveFont(pixelSize), CONTEXT);
        var bounds = layout.getPixelBounds(CONTEXT, 0, 0);
        var image = new BufferedImage(Math.max(1, bounds.width) + PADDING * 2,
                Math.max(1, bounds.height) + PADDING * 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(Color.WHITE);
            if(outline){graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);var shape=layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(PADDING-bounds.x,PADDING-bounds.y));var border=new java.awt.geom.Area(new java.awt.BasicStroke(2f).createStrokedShape(shape));border.subtract(new java.awt.geom.Area(shape));graphics.fill(border);}else layout.draw(graphics, PADDING - bounds.x, PADDING - bounds.y);
        } finally { graphics.dispose(); }
        return image;
    }

    private static TextLayout titleLayout(Font font,java.util.List<school.magiccodex.protocol.NameplateProtocol.TitleRun> runs,float size){
        String value=runs.stream().map(school.magiccodex.protocol.NameplateProtocol.TitleRun::text).collect(java.util.stream.Collectors.joining());
        var text=new java.text.AttributedString(value);text.addAttribute(java.awt.font.TextAttribute.FONT,font.deriveFont(size));int at=0;
        for(var run:runs){text.addAttribute(java.awt.font.TextAttribute.FOREGROUND,new Color(run.color()),at,at+run.text().length());at+=run.text().length();}
        return new TextLayout(text.getIterator(),CONTEXT);
    }
    public static float titleWidth(Font font,java.util.List<school.magiccodex.protocol.NameplateProtocol.TitleRun> runs,float size){return runs.isEmpty()?0:titleLayout(font,runs,size).getAdvance();}
    public static BufferedImage rasterizeTitles(Font font,java.util.List<school.magiccodex.protocol.NameplateProtocol.TitleRun> runs,float size){
        if(runs.isEmpty())return new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        var layout=titleLayout(font,runs,size);var bounds=layout.getPixelBounds(CONTEXT,0,0);var image=new BufferedImage(Math.max(1,bounds.width)+PADDING*2,Math.max(1,bounds.height)+PADDING*2,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);layout.draw(g,PADDING-bounds.x,PADDING-bounds.y);}finally{g.dispose();}return image;
    }
}
