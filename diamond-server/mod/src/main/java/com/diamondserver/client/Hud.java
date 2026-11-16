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

        // 남은 목표가 바뀌면 1.5초 동안 패널 오른쪽에 표시 (늘면 빨강, 줄면 초록)
        long gAge = System.currentTimeMillis() - St.goalChangedAt;
        if (St.goalDelta != 0 && gAge >= 0 && gAge < 1500) {
            float a = gAge < 1000 ? 1f : 1f - (gAge - 1000) / 500f;
            String d = (St.goalDelta > 0 ? "+" : "-") + n(Math.abs(St.goalDelta));
            ctx.drawText(f, d, PX + PW + 5, PY + 6, alpha(St.goalDelta > 0 ? RED : GREEN, Math.max(0.05f, a)), true);
        }

        // 보유 다이아가 바뀌면 1.5초 동안 패널 오른쪽에 +N / -N 표시
        long age = System.currentTimeMillis() - St.heldChangedAt;
        if (St.heldDelta != 0 && age >= 0 && age < 1500) {
            float a = age < 1000 ? 1f : 1f - (age - 1000) / 500f;
            String d = (St.heldDelta > 0 ? "+" : "-") + n(Math.abs(St.heldDelta));
            ctx.drawText(f, d, PX + PW + 5, PY + 19, alpha(St.heldDelta > 0 ? GREEN : RED, Math.max(0.05f, a)), true);
        }
    }

    // 후원 알림 배너 자리 (위쪽 가운데, 알림보다 위)
    static final int BANNER_Y = 46, BANNER_H = 32;

    static boolean bannerShowing() {
        return St.banner != null && System.currentTimeMillis() - St.banner.at() < St.BANNER_MS;
    }

    /** 대기열 상자의 아래쪽 y (대기열이 없으면 패널 아래) */
    static int queueBottom() {
        if (St.queue.isEmpty()) return PY + PH;
        int lines = Math.min(4, St.queue.size()) + (St.queue.size() > 4 ? 1 : 0);
        return PY + PH + 3 + 5 + 11 * lines + 2;
    }

    /**
     * 후원 알림: 윗줄 "누구님 별풍선 N개", 아랫줄 "이벤트 이름 → 대상".
     * 그린 배너의 아래쪽 y 를 돌려준다 (안 그렸으면 -1). 화면이 좁아 왼쪽 대기열과 겹칠 때는 대기열 아래로 내려 그린다.
     */
    static int drawBanner(DrawContext ctx, MinecraftClient mc) {
        if (!bannerShowing()) return -1;
        St.Banner b = St.banner;
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        long age = System.currentTimeMillis() - b.at();
        float a = age > St.BANNER_MS - 500 ? Math.max(0.05f, (St.BANNER_MS - age) / 500f) : 1f;
        int color = b.kind().equals("good") ? GREEN : b.kind().equals("bad") ? RED : AQUA;
        int max = sw - 40;
        String top = f.trimToWidth(b.donor() + "님 별풍선 " + n(b.count()) + "개", max);
        String bottom = f.trimToWidth(b.name() + " → " + b.target(), max);
        int w = Math.max(f.getWidth(top), f.getWidth(bottom)) + 32;
        int x = (sw - w) / 2;
        int y = x < PX + PW + 4 ? Math.max(BANNER_Y, queueBottom() + 4) : BANNER_Y;
        box(ctx, x, y, w, BANNER_H, alpha(0xE60D1424, a), alpha(color, a));
        ctx.fill(x + 1, y + 1, x + w - 1, y + 3, alpha(color, a));
        ctx.drawText(f, top, x + (w - f.getWidth(top)) / 2, y + 8, alpha(GRAY, a), false);
        ctx.drawText(f, bottom, x + (w - f.getWidth(bottom)) / 2, y + 19, alpha(color, a), false);
        return y + BANNER_H;
    }

    /** 줄 서 있는 이벤트: 다이아 패널 바로 아래, 최대 4줄 */
    static void drawQueue(DrawContext ctx, MinecraftClient mc) {
        if (St.queue.isEmpty()) return;
        TextRenderer f = mc.textRenderer;
        int lines = Math.min(4, St.queue.size());
        boolean more = St.queue.size() > 4;
        int y = PY + PH + 3, h = 5 + 11 * (lines + (more ? 1 : 0)) + 2;
        box(ctx, PX, y, PW, h, BG, BORDER);
        for (int i = 0; i < lines; i++) {
            String s = f.trimToWidth(St.queue.get(i), PW - 20);
            ctx.fill(PX + 6, y + 8 + i * 11, PX + 9, y + 11 + i * 11, i == 0 ? AQUA : 0xFF6B7890);
            ctx.drawText(f, s, PX + 13, y + 5 + i * 11, i == 0 ? WHITE : GRAY, false);
        }
        if (more) ctx.drawText(f, "외 " + (St.queue.size() - 4) + "건", PX + 13, y + 5 + lines * 11, 0xFF6B7890, false);
    }

    /** bannerBottom = drawBanner 가 돌려준 값 (배너가 떠 있으면 알림은 그 아래부터) */
    static void drawToasts(DrawContext ctx, MinecraftClient mc, int bannerBottom) {
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        long now = System.currentTimeMillis();
        int y = bannerBottom > 0 ? bannerBottom + TOAST_GAP : TOAST_Y;
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
