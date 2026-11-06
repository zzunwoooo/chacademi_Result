package kr.kkaegul.client.casino.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * 고화질 텍스처 렌더러.
 * <p>
 * 마인크래프트 GUI는 원래 텍스처를 "가장 가까운 픽셀"로 그려서, 큰 PNG를 작게 그리면 깨진다.
 * 그래서
 * <ol>
 *     <li>원본 PNG(0번) + 절반씩 줄인 단계별 이미지를 미리 만들어 두고 (Lanczos, 알파 보정)</li>
 *     <li>지금 화면에 실제로 그려질 픽셀 크기(GUI 크기 × GUI 배율)를 계산해서</li>
 *     <li>그보다 크거나 같은 것 중 가장 작은 단계를 골라</li>
 *     <li>부드러운 보간(.mcmeta blur)으로 그린다</li>
 * </ol>
 * 축소 비율이 항상 2배 이하라서 어떤 GUI 배율·해상도에서도 원본에 가깝게 보인다.
 * 화면이 아주 크면(4K, 높은 GUI 배율) 원본 PNG가 그대로 쓰인다.
 * <p>
 * 좌표·크기는 모두 GUI 단위 float (소수 위치 가능 → 부드러운 애니메이션).
 */
public final class HqTexture {

    private final String name;
    /** [단계][0=가로, 1=세로], 0번이 원본 */
    private final int[][] levels;
    private final Identifier[] ids;

    public HqTexture(String name, int[][] levels) {
        this.name = name;
        this.levels = levels;
        this.ids = new Identifier[levels.length];
        for (int i = 0; i < levels.length; i++) {
            ids[i] = Identifier.of("kkaegul", "textures/casino/" + name + "/" + i + ".png");
        }
    }

    public String name() {
        return name;
    }

    /** 원본 가로 픽셀 */
    public int width() {
        return levels[0][0];
    }

    /** 원본 세로 픽셀 */
    public int height() {
        return levels[0][1];
    }

    /** 세로 h일 때 비율에 맞는 가로 */
    public float widthFor(float h) {
        return h * width() / height();
    }

    /** 전체 이미지를 (x, y, w, h)에 그린다. */
    public void draw(DrawContext ctx, float x, float y, float w, float h) {
        drawRegion(ctx, x, y, w, h, 0, 0, width(), height());
    }

    /** 세로 h 기준으로 비율 유지해서 그린다. 그린 가로를 반환. */
    public float drawH(DrawContext ctx, float x, float y, float h) {
        float w = widthFor(h);
        draw(ctx, x, y, w, h);
        return w;
    }

    /**
     * 원본 픽셀 기준 영역 (sx, sy, sw, sh)을 화면 (x, y, w, h)에 그린다. 9-slice 배경에 사용.
     */
    public void drawRegion(DrawContext ctx, float x, float y, float w, float h,
                           float sx, float sy, float sw, float sh) {
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return;
        String key = name + "|r" + sx + "," + sy + "," + sw + "," + sh;
        if (HqCache.drawCrisp(ctx, key, x, y, w, h, true, (pw, ph) -> {
            HqImage img = source();
            if (img == null) return null;
            float kx = (float) img.w / width(), ky = (float) img.h / height();
            int[] out = new int[pw * ph];
            img.resampleInto(sx * kx, sy * ky, sw * kx, sh * ky, out, pw, 0, 0, pw, ph);
            return out;
        })) return;
        drawRegionLod(ctx, x, y, w, h, sx, sy, sw, sh, -1);
    }

    /**
     * 색·투명도를 곱해서 그린다 (argb = 0xFFFFFFFF면 그대로). 애니메이션 연출용 (단계 이미지 + GPU 보간).
     */
    public void drawTinted(DrawContext ctx, float x, float y, float w, float h, int argb) {
        if (argb == -1) {
            draw(ctx, x, y, w, h);
            return;
        }
        if (((argb >>> 24) & 0xFF) == 0 || w <= 0 || h <= 0) return;
        drawRegionLod(ctx, x, y, w, h, 0, 0, width(), height(), argb);
    }

    /** 원본 PNG (0번 단계) 픽셀 */
    HqImage source() {
        return HqImage.load(ids[0]);
    }

    /** 예전 방식: 미리 줄여 둔 단계 이미지를 GPU 보간으로 (애니메이션 중 등) */
    private void drawRegionLod(DrawContext ctx, float x, float y, float w, float h,
                               float sx, float sy, float sw, float sh, int argb) {
        double gui = MinecraftClient.getInstance().getWindow().getScaleFactor();
        // 화면 픽셀 / 원본 픽셀 (가로·세로 중 더 큰 쪽 기준 → 어느 방향도 2배 넘게 줄지 않음)
        float need = (float) Math.max(w * gui / sw, h * gui / sh);
        int lvl = 0;
        for (int i = 1; i < levels.length; i++) {
            float f = (float) levels[i][0] / levels[0][0];
            if (f >= need) lvl = i;
            else break;
        }
        int lw = levels[lvl][0], lh = levels[lvl][1];
        float fx = (float) lw / levels[0][0];
        float fy = (float) lh / levels[0][1];
        int ru = Math.round(sx * fx);
        int rv = Math.round(sy * fy);
        int rw = Math.max(1, Math.min(lw - ru, Math.round(sw * fx)));
        int rh = Math.max(1, Math.min(lh - rv, Math.round(sh * fy)));

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(w / rw, h / rh, 1f);
        if (argb == -1) ctx.drawTexture(RenderLayer::getGuiTextured, ids[lvl], 0, 0, ru, rv, rw, rh, rw, rh, lw, lh);
        else ctx.drawTexture(RenderLayer::getGuiTextured, ids[lvl], 0, 0, ru, rv, rw, rh, rw, rh, lw, lh, argb);
        m.pop();
    }

    /**
     * 가로로만 늘어나는 배경 (양 끝 capPx 원본 픽셀은 비율 유지, 가운데만 늘림).
     */
    public void drawStretchH(DrawContext ctx, float x, float y, float w, float h, float capPx) {
        drawStretchH(ctx, x, y, w, h, capPx, capPx);
    }

    /** 가로로만 늘어나는 배경. 왼쪽 capLeftPx, 오른쪽 capRightPx 원본 픽셀은 비율 유지. */
    public void drawStretchH(DrawContext ctx, float x, float y, float w, float h, float capLeftPx, float capRightPx) {
        float k = h / height();
        float l = capLeftPx * k, r = capRightPx * k;
        if (l + r > w) {
            float f = w / (l + r);
            l *= f;
            r *= f;
        }
        float lSrc = l / k, rSrc = r / k;
        final float fl = l, fr = r;
        if (HqCache.drawCrisp(ctx, name + "|h" + capLeftPx + "," + capRightPx, x, y, w, h, true, (pw, ph) -> {
            HqImage img = source();
            if (img == null) return null;
            double g = HqCache.gui();
            float kx = (float) img.w / width(), ky = (float) img.h / height();
            int lp = Math.min(pw, (int) Math.round(fl * g)), rp = Math.min(pw - lp, (int) Math.round(fr * g));
            int[] out = new int[pw * ph];
            img.resampleInto(0, 0, lSrc * kx, img.h, out, pw, 0, 0, lp, ph);
            img.resampleInto(lSrc * kx, 0, (width() - lSrc - rSrc) * kx, img.h, out, pw, lp, 0, pw - lp - rp, ph);
            img.resampleInto((width() - rSrc) * kx, 0, rSrc * kx, img.h, out, pw, pw - rp, 0, rp, ph);
            return out;
        })) return;
        drawRegion(ctx, x, y, l, h, 0, 0, lSrc, height());
        drawRegion(ctx, x + l, y, w - l - r, h, lSrc, 0, width() - lSrc - rSrc, height());
        drawRegion(ctx, x + w - r, y, r, h, width() - rSrc, 0, rSrc, height());
    }

    /**
     * 세로로만 늘어나는 배경 (위 topPx, 아래 bottomPx 원본 픽셀은 비율 유지, 가운데만 늘림).
     * 가로는 w에 맞춰 비율 그대로 축소.
     */
    public void drawStretchV(DrawContext ctx, float x, float y, float w, float h, float topPx, float bottomPx) {
        float k = w / width();
        float top = topPx * k, bottom = bottomPx * k;
        if (top + bottom > h) {
            float s = h / (top + bottom);
            top *= s;
            bottom *= s;
        }
        final float ft = top, fb = bottom;
        if (HqCache.drawCrisp(ctx, name + "|v" + topPx + "," + bottomPx, x, y, w, h, true, (pw, ph) -> {
            HqImage img = source();
            if (img == null) return null;
            double g = HqCache.gui();
            float kx = (float) img.w / width(), ky = (float) img.h / height();
            int tp = Math.min(ph, (int) Math.round(ft * g)), bp = Math.min(ph - tp, (int) Math.round(fb * g));
            int[] out = new int[pw * ph];
            img.resampleInto(0, 0, img.w, topPx * ky, out, pw, 0, 0, pw, tp);
            img.resampleInto(0, topPx * ky, img.w, (height() - topPx - bottomPx) * ky, out, pw, 0, tp, pw, ph - tp - bp);
            img.resampleInto(0, (height() - bottomPx) * ky, img.w, bottomPx * ky, out, pw, 0, ph - bp, pw, bp);
            return out;
        })) return;
        drawRegion(ctx, x, y, w, top, 0, 0, width(), topPx);
        drawRegion(ctx, x, y + top, w, h - top - bottom, 0, topPx, width(), height() - topPx - bottomPx);
        drawRegion(ctx, x, y + h - bottom, w, bottom, 0, height() - bottomPx, width(), bottomPx);
    }

    /**
     * 9-slice: 네 모서리(원본 insetPx 픽셀)는 화면에서 cornerSize 크기로 고정하고 나머지만 늘린다.
     * 버튼처럼 비율이 제각각인 곳에 사용.
     */
    public void drawNine(DrawContext ctx, float x, float y, float w, float h, float insetPx, float cornerSize) {
        drawNine(ctx, x, y, w, h, insetPx, cornerSize, 0);
    }

    /**
     * 9-slice + 색 씻기 (비활성 버튼). wash의 알파만큼 RGB를 wash 색으로 섞는다 (투명도·모양은 그대로).
     */
    public void drawNine(DrawContext ctx, float x, float y, float w, float h, float insetPx, float cornerSize, int wash) {
        float c = Math.min(cornerSize, Math.min(w, h) / 2f);
        float in = insetPx;
        final float fc = c;
        final int wa = (wash >>> 24) & 0xFF, wr = (wash >> 16) & 0xFF, wg = (wash >> 8) & 0xFF, wb = wash & 0xFF;
        if (HqCache.drawCrisp(ctx, name + "|n" + insetPx + "," + Math.round(c * HqCache.gui()) + (wa > 0 ? "|w" + Integer.toHexString(wash) : ""), x, y, w, h, true, (pw, ph) -> {
            HqImage img = source();
            if (img == null) return null;
            double g = HqCache.gui();
            float kx = (float) img.w / width(), ky = (float) img.h / height();
            int cp = (int) Math.min(Math.round(fc * g), Math.min(pw, ph) / 2);
            double[][] sxs = {{0, in * kx}, {in * kx, (width() - 2 * in) * kx}, {(width() - in) * kx, in * kx}};
            double[][] sys = {{0, in * ky}, {in * ky, (height() - 2 * in) * ky}, {(height() - in) * ky, in * ky}};
            int[][] dxs = {{0, cp}, {cp, pw - 2 * cp}, {pw - cp, cp}};
            int[][] dys = {{0, cp}, {cp, ph - 2 * cp}, {ph - cp, cp}};
            int[] out = new int[pw * ph];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    img.resampleInto(sxs[i][0], sys[j][0], sxs[i][1], sys[j][1], out, pw, dxs[i][0], dys[j][0], dxs[i][1], dys[j][1]);
                }
            }
            if (wa > 0) {
                for (int k = 0; k < out.length; k++) {
                    int p = out[k], a = p >>> 24;
                    int r = (p >> 16) & 0xFF, g2 = (p >> 8) & 0xFF, b = p & 0xFF;
                    r += (wr - r) * wa / 255;
                    g2 += (wg - g2) * wa / 255;
                    b += (wb - b) * wa / 255;
                    out[k] = (a << 24) | (r << 16) | (g2 << 8) | b;
                }
            }
            return out;
        })) return;
        float[][] xs = {{0, in, x, c}, {in, width() - in, x + c, w - 2 * c}, {width() - in, width(), x + w - c, c}};
        float[][] ys = {{0, in, y, c}, {in, height() - in, y + c, h - 2 * c}, {height() - in, height(), y + h - c, c}};
        for (float[] cx : xs) {
            for (float[] cy : ys) {
                drawRegion(ctx, cx[2], cy[2], cx[3], cy[3], cx[0], cy[0], cx[1] - cx[0], cy[1] - cy[0]);
            }
        }
    }
}
