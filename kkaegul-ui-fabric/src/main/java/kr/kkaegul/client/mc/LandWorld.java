package kr.kkaegul.client.mc;

import kr.kkaegul.client.ui.LandView;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import org.joml.Matrix4f;

/**
 * 땅 경계 (월드에 그림). 차카데미 땅문서 경계 그리기를 옮김 — 텍스처 없이 색만.
 * 땅문서를 든 동안: 주변 청크(3×3) 경계에 반투명 벽, 서 있는 칸은 진하게.
 * 내 땅 안 (안 들었을 때): 서 있는 청크 경계만 얇고 흐리게.
 */
final class LandWorld {
    private LandWorld() {}

    private static final double BELOW = 3, ABOVE = 3, FADE = 26, CELL = 2;

    static void render(WorldRenderContext ctx, LandView v) {
        var c = MinecraftClient.getInstance();
        if (v == null || c.world == null || c.player == null || c.options.hudHidden) return;
        boolean holding = v.holding;
        if (!holding && v.mine == null) return;
        MatrixStack matrices = ctx.matrixStack();
        var out = ctx.consumers();
        if (matrices == null || out == null) return;
        var cam = ctx.camera().getPos();
        // 서버가 보낸 칸이 지금 서 있는 칸과 다르면 (이동 직후) 그리지 않음
        int pcx = c.player.getBlockX() >> 4, pcz = c.player.getBlockZ() >> 4;
        if (pcx != v.cx || pcz != v.cz) return;
        float pulse = .88f + .12f * (float) Math.sin(Util.getMeasuringTimeMs() / 300.0);
        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f m = matrices.peek().getPositionMatrix();
            VertexConsumer vc = out.getBuffer(RenderLayer.getDebugQuads());
            if (holding) {
                int r = v.radius, side = 2 * r + 1, x0 = v.cx - r, z0 = v.cz - r;
                double y0 = cam.y - BELOW, y1 = cam.y + ABOVE;
                for (int i = 0; i <= side; i++) for (int j = 0; j < side; j++)
                    edge(vc, m, cam.x, cam.z, true, (x0 + i) * 16.0, (z0 + j) * 16.0, y0, y1, v, i - 1, j, i, j, r, pulse);
                for (int j = 0; j <= side; j++) for (int i = 0; i < side; i++)
                    edge(vc, m, cam.x, cam.z, false, (z0 + j) * 16.0, (x0 + i) * 16.0, y0, y1, v, i, j - 1, i, j, r, pulse);
            } else {
                // 내 땅: 서 있는 청크 네 변만 얇게 (발밑 ~ 머리 위)
                double y0 = c.player.getY() - .5, y1 = c.player.getY() + 2.5;
                int color = LandView.rgb("mine");
                double x0 = v.cx * 16.0, z0 = v.cz * 16.0;
                thin(vc, m, cam.x, cam.z, true, x0, z0, y0, y1, color);
                thin(vc, m, cam.x, cam.z, true, x0 + 16, z0, y0, y1, color);
                thin(vc, m, cam.x, cam.z, false, z0, x0, y0, y1, color);
                thin(vc, m, cam.x, cam.z, false, z0 + 16, x0, y0, y1, color);
            }
        } finally { matrices.pop(); }
    }

    private static int priority(String st) {
        return switch (st) { case "blocked", "owned" -> 3; case "mine", "trusted" -> 2; default -> 1; };
    }

    private static void edge(VertexConsumer vc, Matrix4f m, double camX, double camZ, boolean fixedX, double at, double start, double y0, double y1,
                             LandView v, int ax, int az, int bx, int bz, int r, float pulse) {
        int side = 2 * r + 1;
        String a = ax >= 0 && ax < side && az >= 0 && az < side ? v.cell(ax - r, az - r) : null;
        String b = bx >= 0 && bx < side && bz >= 0 && bz < side ? v.cell(bx - r, bz - r) : null;
        if (a != null && a.isEmpty()) a = null;
        if (b != null && b.isEmpty()) b = null;
        boolean aCenter = ax == r && az == r, bCenter = bx == r && bz == r;
        String pick = aCenter ? a : bCenter ? b : a == null ? b : b == null ? a : priority(a) >= priority(b) ? a : b;
        if (pick == null) return;
        float strength = aCenter || bCenter ? .55f : .26f;
        int color = LandView.rgb(pick);
        for (double u = start; u < start + 16 - 1e-6; u += CELL) {
            double mid = u + CELL / 2;
            double d = fixedX ? Math.hypot(at - camX, mid - camZ) : Math.hypot(mid - camX, at - camZ);
            float fade = (float) Math.clamp(1 - d / FADE, 0, 1);
            if (fade <= 0) continue;
            for (double y = y0; y < y1 - 1e-6; y += CELL) {
                double top = Math.min(y1, y + CELL);
                double ym = (y + top) / 2, edgeFade = 1 - Math.abs(ym - (y0 + y1) / 2) / ((y1 - y0) / 2);
                float alpha = strength * fade * fade * (float) Math.clamp(.35 + .65 * edgeFade, 0, 1) * pulse;
                int argb = (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | color;
                if ((argb >>> 24) == 0) continue;
                quad(vc, m, fixedX, at, u, u + CELL, y, top, argb);
            }
        }
    }

    /** 얇은 띠: 위·아래 가장자리만 진한 선 + 아주 옅은 면 */
    private static void thin(VertexConsumer vc, Matrix4f m, double camX, double camZ, boolean fixedX, double at, double start, double y0, double y1, int color) {
        for (double u = start; u < start + 16 - 1e-6; u += CELL) {
            double mid = u + CELL / 2;
            double d = fixedX ? Math.hypot(at - camX, mid - camZ) : Math.hypot(mid - camX, at - camZ);
            float fade = (float) Math.clamp(1 - d / 20, 0, 1);
            if (fade <= 0) continue;
            int face = (Math.round(.10f * fade * 255) << 24) | color, line = (Math.round(.55f * fade * 255) << 24) | color;
            quad(vc, m, fixedX, at, u, u + CELL, y0, y1, face);
            quad(vc, m, fixedX, at, u, u + CELL, y0, y0 + .06, line);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, boolean fixedX, double at, double u0, double u1, double y0, double y1, int argb) {
        float a = (float) at, s0 = (float) u0, s1 = (float) u1, b = (float) y0, t = (float) y1;
        if (fixedX) {
            vc.vertex(m, a, b, s0).color(argb); vc.vertex(m, a, b, s1).color(argb); vc.vertex(m, a, t, s1).color(argb); vc.vertex(m, a, t, s0).color(argb);
            vc.vertex(m, a, t, s0).color(argb); vc.vertex(m, a, t, s1).color(argb); vc.vertex(m, a, b, s1).color(argb); vc.vertex(m, a, b, s0).color(argb);
        } else {
            vc.vertex(m, s0, b, a).color(argb); vc.vertex(m, s1, b, a).color(argb); vc.vertex(m, s1, t, a).color(argb); vc.vertex(m, s0, t, a).color(argb);
            vc.vertex(m, s0, t, a).color(argb); vc.vertex(m, s1, t, a).color(argb); vc.vertex(m, s1, b, a).color(argb); vc.vertex(m, s0, b, a).color(argb);
        }
    }
}
