package com.diamondserver.ui;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 글자 재기·그리기와 그림 크기 맞추기. 게임(McG)과 검수용 미리보기(AwtG)가 똑같이 이 코드를 써서
 * 글자 너비·위치가 두 곳에서 정확히 같다.
 * 글꼴은 맑은 고딕 굵게 (윈도우에 기본으로 있는 글꼴. 없으면 자바 기본 글꼴로 대신 그린다).
 */
public final class Paint {
    private Paint() { }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final Map<Integer, Font> FONTS = new HashMap<>();

    static Font font(double px) {
        return FONTS.computeIfAbsent((int) Math.round(px * 4), k -> new Font("Malgun Gothic", Font.BOLD, 1).deriveFont((float) (k / 4.0)));
    }

    public static double width(String s, double px) {
        return s == null || s.isEmpty() ? 0 : font(px).getStringBounds(s, FRC).getWidth();
    }

    public static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    /** 글자를 그린다. x = 왼쪽 끝, yCenter = 글자 세로 가운데 */
    public static void text(Graphics2D g, String s, double x, double yCenter, double px, int argb) {
        if (s == null || s.isEmpty()) return;
        Font f = font(px);
        LineMetrics lm = f.getLineMetrics(s, FRC);
        g.setFont(f);
        g.setColor(new java.awt.Color(argb, true));
        g.drawString(s, (float) x, (float) (yCenter + (lm.getAscent() - lm.getDescent()) / 2.0));
    }

    /** 그림을 w×h 로 맞춘다. 많이 줄일 때는 절반씩 여러 번 줄여서 깨끗하게 */
    public static BufferedImage scaled(BufferedImage src, int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        BufferedImage cur = src;
        while (cur.getWidth() >= w * 2 && cur.getHeight() >= h * 2) cur = step(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return cur.getWidth() == w && cur.getHeight() == h ? cur : step(cur, w, h);
    }

    private static BufferedImage step(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
