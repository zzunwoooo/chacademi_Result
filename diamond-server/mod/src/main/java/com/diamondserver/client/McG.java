package com.diamondserver.client;

import com.diamondserver.ui.G;
import com.diamondserver.ui.Paint;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * 게임 안 그리기 도구. 화면 코드의 좌표(가로 1920 × 세로 1080 기준)를 실제 창 크기에 맞춰 그린다.
 * 그림과 글자는 지금 창 크기에 딱 맞는 픽셀 크기로 미리 만들어(조각) 올린 뒤, 늘리지 않고 그대로 붙인다.
 * 그래서 창 크기나 GUI 비율이 달라도 흐려지거나 깨지지 않는다. 조각은 다시 쓰고, 오래 안 쓴 것부터 지운다.
 */
public final class McG implements G {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int MAX_PIECES = 900;

    private record Piece(Identifier id, int w, int h) { }
    private static final Map<String, Piece> PIECES = new LinkedHashMap<>(256, 0.75f, true);
    private static final List<Identifier> FREE = new ArrayList<>();
    private static final Map<String, BufferedImage> SOURCES = new HashMap<>();
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    private static int counter;

    private DrawContext ctx;
    private double gs = 1, r = 1, vw = 1920, vh = 1080;

    /** 한 번 그리기 시작할 때마다 부른다 */
    public McG begin(DrawContext ctx) {
        this.ctx = ctx;
        Window w = MC.getWindow();
        double fbW = Math.max(1, w.getFramebufferWidth()), fbH = Math.max(1, w.getFramebufferHeight());
        gs = Math.max(1e-6, w.getScaleFactor());
        r = Math.min(fbW / 1920.0, fbH / 1080.0);
        vw = fbW / r;
        vh = fbH / r;
        return this;
    }

    /** 마우스 좌표(게임 GUI 단위)를 화면 코드 좌표로 */
    public double toV(double gui) { return gui * gs / r; }

    @Override public double vw() { return vw; }
    @Override public double vh() { return vh; }

    private static int white(double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | 0xFFFFFF;
    }

    /** 받은 그림 원본 (모드 안 textures/ui/). 없으면 null */
    private static BufferedImage source(String name) {
        if (SOURCES.containsKey(name)) return SOURCES.get(name);
        BufferedImage im = null;
        try {
            var res = MC.getResourceManager().getResource(Identifier.of("diamondserver", "textures/ui/" + name + ".png"));
            if (res.isPresent()) try (InputStream in = res.get().getInputStream()) { im = ImageIO.read(in); }
        } catch (Exception ignored) { }
        SOURCES.put(name, im);
        return im;
    }

    private interface Painter { void paint(Graphics2D g); }

    /** key 에 해당하는 조각을 찾거나 새로 만든다 (pw × ph 픽셀) */
    private Piece piece(String key, int pw, int ph, boolean smooth, Painter p) {
        Piece piece = PIECES.get(key);
        if (piece != null) return piece;
        BufferedImage bi = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        Paint.hints(g);
        try { p.paint(g); } catch (Exception ignored) { }
        g.dispose();
        int[] data = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        NativeImage img = new NativeImage(pw, ph, true);
        for (int yy = 0; yy < ph; yy++) for (int xx = 0; xx < pw; xx++) img.setColorArgb(xx, yy, data[yy * pw + xx]);
        Identifier id = FREE.isEmpty() ? Identifier.of("diamondserver", "piece/" + (counter++)) : FREE.remove(FREE.size() - 1);
        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
        MC.getTextureManager().registerTexture(id, tex);      // 같은 이름에 새 그림을 올리면 옛 그림은 자동으로 정리된다
        if (smooth) try { tex.setFilter(true, false); } catch (Exception ignored) { }
        piece = new Piece(id, pw, ph);
        PIECES.put(key, piece);
        if (PIECES.size() > MAX_PIECES) {                     // 오래 안 쓴 조각부터 지움
            Iterator<Map.Entry<String, Piece>> it = PIECES.entrySet().iterator();
            for (int i = 0; i < 100 && it.hasNext(); i++) { FREE.add(it.next().getValue().id()); it.remove(); }
        }
        return piece;
    }

    /** 조각을 실제 화면 픽셀 칸에 맞춰, 늘리지 않고 붙인다. (u0~u1: 가로로 보일 부분, 픽셀) */
    private void blit(Piece piece, double x, double y, int u0, int u1, double alpha) {
        if (alpha <= 0.003 || u1 <= u0) return;
        long px = Math.round(x * r), py = Math.round(y * r);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) ((px + u0) / gs), (float) (py / gs), 0f);
        m.scale((float) (1 / gs), (float) (1 / gs), 1f);
        ctx.drawTexture(RenderLayer::getGuiTextured, piece.id(), 0, 0, (float) u0, 0f, u1 - u0, piece.h(), piece.w(), piece.h(), white(alpha));
        m.pop();
    }

    private Piece imgPiece(String name, double w, double h, boolean smooth) {
        BufferedImage src = source(name);
        if (src == null) return null;
        int pw = Math.max(1, (int) Math.round(w * r)), ph = Math.max(1, (int) Math.round(h * r));
        if (pw > 4096 || ph > 4096) return null;
        return piece("i|" + name + "|" + pw + "x" + ph + (smooth ? "s" : ""), pw, ph, smooth, g -> g.drawImage(Paint.scaled(src, pw, ph), 0, 0, null));
    }

    @Override
    public void img(String name, double x, double y, double w, double h, double alpha) {
        if (w <= 0 || h <= 0) return;
        Piece p = imgPiece(name, w, h, false);
        if (p != null) blit(p, x, y, 0, p.w(), alpha);
    }

    @Override
    public void imgClip(String name, double x, double y, double w, double h, double from, double to) {
        Piece p = imgPiece(name, w, h, false);
        if (p == null) return;
        blit(p, x, y, (int) Math.round(p.w() * Math.max(0, from)), (int) Math.round(p.w() * Math.min(1, to)), 1);
    }

    @Override
    public void imgRot(String name, double cx, double cy, double w, double h, double deg) {
        Piece p = imgPiece(name, w, h, true);
        if (p == null) return;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) (cx * r / gs), (float) (cy * r / gs), 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) deg));
        m.scale((float) (1 / gs), (float) (1 / gs), 1f);
        m.translate(-p.w() / 2f, -p.h() / 2f, 0f);
        ctx.drawTexture(RenderLayer::getGuiTextured, p.id(), 0, 0, 0f, 0f, p.w(), p.h(), p.w(), p.h());
        m.pop();
    }

    @Override
    public void rect(double x, double y, double w, double h, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        double k = r / gs;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) (x * k), (float) (y * k), 0f);
        m.scale((float) (w * k), (float) (h * k), 1f);
        ctx.fill(0, 0, 1, 1, argb);
        m.pop();
    }

    @Override public double textW(String s, double px) { return Paint.width(s, px); }

    @Override
    public void text(String s, double x, double y, double px, int anchor, int argb) {
        if (s == null || s.isEmpty() || (argb >>> 24) == 0) return;
        double w = Paint.width(s, px), pad = 2, hh = px * 1.6;
        int pw = Math.max(1, (int) Math.ceil((w + pad * 2) * r)), ph = Math.max(1, (int) Math.ceil(hh * r));
        if (pw > 4096) return;
        int rgb = argb | 0xFF000000;                           // 투명도는 붙일 때 준다 (서서히 사라지는 글자마다 조각을 새로 만들지 않게)
        Piece p = piece("t|" + s + "|" + Math.round(px * 4) + "|" + Integer.toHexString(rgb) + "|" + Math.round(r * 1000), pw, ph, false, g -> {
            g.scale(r, r);
            Paint.text(g, s, pad, hh / 2, px, rgb);
        });
        double left = anchor == 1 ? x - w / 2 : anchor == 2 ? x - w : x;
        blit(p, left - pad, y - hh / 2, 0, p.w(), ((argb >>> 24) & 0xFF) / 255.0);
    }

    @Override
    public void item(String mcId, double x, double y, double size) {
        ItemStack stack = STACKS.computeIfAbsent(mcId, k -> {
            Identifier id = Identifier.tryParse(k);
            ItemStack s = id == null ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(id));
            return s.isEmpty() ? new ItemStack(Items.PAPER) : s;
        });
        double k = r / gs;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) (x * k), (float) (y * k), 0f);
        m.scale((float) (size * k / 16.0), (float) (size * k / 16.0), 1f);
        ctx.drawItem(stack, 0, 0);
        m.pop();
    }

    @Override
    public void face(String uuid, double x, double y, double size) {
        SkinTextures skin = DefaultSkinHelper.getSteve();
        try {
            if (MC.getNetworkHandler() != null && uuid != null && !uuid.isEmpty()) {
                PlayerListEntry e = MC.getNetworkHandler().getPlayerListEntry(UUID.fromString(uuid));
                if (e != null) skin = e.getSkinTextures();
            }
        } catch (IllegalArgumentException ignored) { }
        double k = r / gs;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) (x * k), (float) (y * k), 0f);
        m.scale((float) (size * k / 8.0), (float) (size * k / 8.0), 1f);
        PlayerSkinDrawer.draw(ctx, skin, 0, 0, 8);
        m.pop();
    }
}
