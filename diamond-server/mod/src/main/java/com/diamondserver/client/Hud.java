package com.diamondserver.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * 항상 떠 있는 화면 요소: 왼쪽 위 다이아 패널(남은 목표·보유 다이아) + 위쪽 가운데 알림.
 * 지금은 색 상자로 그린 임시 디자인이다. 정식 그림이 오면 이 파일의 그리기만 바꾸면 된다.
 */
public final class Hud {
    private Hud() { }

    static final int BG = 0xC8101826, BORDER = 0xFF2B3D63, WHITE = 0xFFFFFFFF, GRAY = 0xFFA8B3C7,
            AQUA = 0xFF5CE1F5, GREEN = 0xFF63E6A0, RED = 0xFFFF6B6B;

    private static final ItemStack DIAMOND = new ItemStack(Items.DIAMOND);

    // 다이아 패널 자리 (화면 왼쪽 위)
    static final int PX = 6, PY = 6, PW = 134, PH = 34;
    // 알림은 패널 아래 높이에서 시작 (패널·보스 체력바와 겹치지 않게)
    static final int TOAST_Y = 46, TOAST_H = 18, TOAST_GAP = 3;

    static String n(long v) { return String.format("%,d", v); }

    static int alpha(int argb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    static void box(DrawContext ctx, int x, int y, int w, int h, int bg, int border) {
        ctx.fill(x, y, x + w, y + h, bg);
        ctx.fill(x, y, x + w, y + 1, border);
        ctx.fill(x, y + h - 1, x + w, y + h, border);
        ctx.fill(x, y + 1, x + 1, y + h - 1, border);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    static void drawPanel(DrawContext ctx, MinecraftClient mc) {
        TextRenderer f = mc.textRenderer;
        box(ctx, PX, PY, PW, PH, BG, BORDER);
        ctx.drawItem(DIAMOND, PX + 5, PY + 9);
        int right = PX + PW - 7;

        ctx.drawText(f, "남은 목표", PX + 26, PY + 6, GRAY, false);
        String goal = St.done ? "달성" : n(St.goal);
        ctx.drawText(f, goal, right - f.getWidth(goal), PY + 6, St.done ? GREEN : WHITE, false);

        ctx.drawText(f, "보유", PX + 26, PY + 19, GRAY, false);
        String held = n(St.held);
        ctx.drawText(f, held, right - f.getWidth(held), PY + 19, AQUA, false);

        // 보유 다이아가 바뀌면 1.5초 동안 패널 오른쪽에 +N / -N 표시
        long age = System.currentTimeMillis() - St.heldChangedAt;
        if (St.heldDelta != 0 && age >= 0 && age < 1500) {
            float a = age < 1000 ? 1f : 1f - (age - 1000) / 500f;
            String d = (St.heldDelta > 0 ? "+" : "-") + n(Math.abs(St.heldDelta));
            ctx.drawText(f, d, PX + PW + 5, PY + 19, alpha(St.heldDelta > 0 ? GREEN : RED, Math.max(0.05f, a)), true);
        }
    }

    static void drawToasts(DrawContext ctx, MinecraftClient mc) {
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        long now = System.currentTimeMillis();
        int y = TOAST_Y;
        for (St.Toast t : St.toasts) {
            long age = now - t.at();
            if (age > St.TOAST_MS) continue;
            float a = age > St.TOAST_MS - 500 ? Math.max(0.05f, (St.TOAST_MS - age) / 500f) : 1f;
            int w = Math.min(sw - 8, f.getWidth(t.text()) + 20);
            int x = (sw - w) / 2;
            box(ctx, x, y, w, TOAST_H, alpha(0xE0101826, a), alpha(BORDER, a));
            ctx.fill(x + 1, y + 1, x + 4, y + TOAST_H - 1, alpha(t.ok() ? GREEN : RED, a));
            ctx.drawText(f, t.text(), x + 10, y + 5, alpha(WHITE, a), false);
            y += TOAST_H + TOAST_GAP;
        }
    }
}
