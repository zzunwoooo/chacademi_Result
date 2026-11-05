package kr.kkaegul.client.mc;

import kr.kkaegul.client.ui.Canvas;
import kr.kkaegul.client.ui.TextRaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/** 게임용 Canvas: 가상 좌표(세로 1080) → GUI 좌표 */
public final class McCanvas implements Canvas {
    private final MinecraftClient client;
    private final Gfx gfx;
    private DrawContext ctx;
    private float w, h;
    private Gfx.Tex round;

    public McCanvas(MinecraftClient client, Gfx gfx) {
        this.client = client;
        this.gfx = gfx;
    }

    /** 이번 프레임 시작. 가상 해상도 배율을 걸고 그 배율을 돌려준다 */
    public float begin(DrawContext ctx) {
        this.ctx = ctx;
        float sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float s = sh / 1080f;
        this.h = 1080;
        this.w = sw / s;
        ctx.getMatrices().push();
        ctx.getMatrices().scale(s, s, 1);
        return s;
    }

    public void end() {
        ctx.getMatrices().pop();
        ctx = null;
    }

    private MatrixStack m() { return ctx.getMatrices(); }

    @Override public float width() { return w; }
    @Override public float height() { return h; }

    @Override public void image(String id, float x, float y, float ww, float hh, int argb) {
        imagePart(id, x, y, ww, hh, 0, 0, 1, 1, argb);
    }

    @Override public void imagePart(String id, float x, float y, float ww, float hh, float u0, float v0, float u1, float v1, int argb) {
        if (ww <= 0 || hh <= 0 || (argb >>> 24) == 0) return;
        Gfx.Tex t = gfx.image(id);
        if (t == null) return;
        int rx = Math.round(u0 * t.width()), ry = Math.round(v0 * t.height());
        int rw = Math.max(1, Math.round((u1 - u0) * t.width())), rh = Math.max(1, Math.round((v1 - v0) * t.height()));
        draw(t, x, y, ww, hh, rx, ry, rw, rh, argb);
    }

    private void draw(Gfx.Tex t, float x, float y, float ww, float hh, float u, float v, int rw, int rh, int argb) {
        m().push();
        m().translate(x, y, 0);
        m().scale(ww / rw, hh / rh, 1);
        ctx.drawTexture(id -> t.layer(), t.id(), 0, 0, u, v, rw, rh, rw, rh, t.width(), t.height(), argb);
        m().pop();
    }

    @Override public int[] size(String id) { return gfx.size(id); }

    @Override public void text(String s, float x, float y, float size, int argb, int align, int style) {
        if (s == null || s.isEmpty() || (argb >>> 24) == 0 || !TextRaster.ready()) return;
        var mat = m().peek().getPositionMatrix();
        float scaleFactor = (float) client.getWindow().getScaleFactor();
        float ppu = (float) Math.hypot(mat.m10(), mat.m11()) * scaleFactor; // 가상 1단위가 화면 몇 픽셀인지
        if (ppu <= 0) return;
        float px = Math.max(4, size * ppu);
        int q = Math.round(px * 4);
        px = q / 4f;
        int rgb = argb & 0xFFFFFF;
        String key = s + '\u0000' + q + '\u0000' + rgb + '\u0000' + style;
        Gfx.TextTex tt = gfx.text(s, px, rgb, style, key);
        if (tt == null) return;
        Gfx.Tex t = tt.tex();
        float inv = 1 / ppu;
        float adv = tt.advance() * inv;
        float left = align == CENTER ? x - adv / 2 : align == RIGHT ? x - adv : x;
        float baseline = y + size * TextRaster.CENTER_TO_BASELINE;
        int tint = (argb & 0xFF000000) | 0xFFFFFF;
        draw(t, left - tt.left() * inv, baseline - tt.baseline() * inv, t.width() * inv, t.height() * inv, 0, 0, t.width(), t.height(), tint);
    }

    @Override public float textWidth(String s, float size, int style) { return TextRaster.width(s, size, style); }

    @Override public void fill(float x, float y, float ww, float hh, float r, int argb) {
        if (ww <= 0 || hh <= 0 || (argb >>> 24) == 0) return;
        Gfx.Tex t = round();
        r = Math.max(0, Math.min(r, Math.min(ww, hh) / 2));
        int H = 64; // 텍스처 반지름(px)
        if (r < .5f) { draw(t, x, y, ww, hh, H - 1, H - 1, 2, 2, argb); return; }
        // 9조각: 모서리 4 + 변 4 + 가운데
        draw(t, x, y, r, r, 0, 0, H, H, argb);
        draw(t, x + ww - r, y, r, r, H, 0, H, H, argb);
        draw(t, x, y + hh - r, r, r, 0, H, H, H, argb);
        draw(t, x + ww - r, y + hh - r, r, r, H, H, H, H, argb);
        if (ww - 2 * r > 0) {
            draw(t, x + r, y, ww - 2 * r, r, H - 1, 0, 2, H, argb);
            draw(t, x + r, y + hh - r, ww - 2 * r, r, H - 1, H, 2, H, argb);
        }
        if (hh - 2 * r > 0) {
            draw(t, x, y + r, r, hh - 2 * r, 0, H - 1, H, 2, argb);
            draw(t, x + ww - r, y + r, r, hh - 2 * r, H, H - 1, H, 2, argb);
        }
        if (ww - 2 * r > 0 && hh - 2 * r > 0) draw(t, x + r, y + r, ww - 2 * r, hh - 2 * r, H - 1, H - 1, 2, 2, argb);
    }

    /** 흰 원(128px, 알파 곱함) — 둥근 사각형 재료 */
    private Gfx.Tex round() {
        if (round != null) return round;
        int S = 128;
        NativeImage img = new NativeImage(S, S, false);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                double dx = x + .5 - S / 2.0, dy = y + .5 - S / 2.0;
                double d = Math.sqrt(dx * dx + dy * dy);
                int a = (int) Math.round(255 * Math.max(0, Math.min(1, S / 2.0 - d + .5)));
                img.setColorArgb(x, y, a << 24 | a << 16 | a << 8 | a);
            }
        round = gfx.uploadRaw(img);
        return round;
    }

    @Override public boolean face(String account, float x, float y, float size) {
        var nh = client.getNetworkHandler();
        if (nh == null) return false;
        String name = account == null || account.isEmpty() ? (client.player == null ? "" : client.player.getGameProfile().getName()) : account;
        var entry = nh.getPlayerListEntry(name);
        if (entry == null) return false;
        var skin = entry.getSkinTextures().texture();
        m().push();
        m().translate(x, y, 0);
        m().scale(size / 64f, size / 64f, 1);
        ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, skin, 0, 0, 8, 8, 64, 64, 8, 8, 64, 64);
        ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, skin, 0, 0, 40, 8, 64, 64, 8, 8, 64, 64);
        m().pop();
        return true;
    }

    @Override public void push() { m().push(); }
    @Override public void pop() { m().pop(); }
    @Override public void translate(float x, float y) { m().translate(x, y, 0); }
    @Override public void scale(float sx, float sy) { m().scale(sx, sy, 1); }
    @Override public void rotate(float deg) { m().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(deg)); }
}
