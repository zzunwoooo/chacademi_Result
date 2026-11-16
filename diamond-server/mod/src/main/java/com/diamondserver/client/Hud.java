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
        int y = x < PX + PW + 4 ? Math.max(BANNER_Y, leftBottom() + 4) : BANNER_Y;
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

    /** 왼쪽 위 묶음(패널 · 대기열 · 걸린 효과)의 맨 아래 y */
    static int leftBottom() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (St.Effect e : St.effects) if (e.endsAt() > now) n++;
        return n == 0 ? queueBottom() : queueBottom() + 3 + 5 + 11 * n + 2;
    }

    /** 지금 걸려 있는 효과: 대기열 아래에 "이름 … 12초" */
    static void drawEffects(DrawContext ctx, MinecraftClient mc) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (St.Effect e : St.effects) if (e.endsAt() > now) n++;
        if (n == 0) return;
        TextRenderer f = mc.textRenderer;
        int y = queueBottom() + 3, h = 5 + 11 * n + 2;
        box(ctx, PX, y, PW, h, BG, RED);
        int i = 0;
        for (St.Effect e : St.effects) {
            if (e.endsAt() <= now) continue;
            String left = ((e.endsAt() - now + 999) / 1000) + "초";
            int ly = y + 5 + i * 11;
            ctx.drawText(f, left, PX + PW - 7 - f.getWidth(left), ly, WHITE, false);
            ctx.drawText(f, f.trimToWidth(e.name(), PW - 20 - f.getWidth(left)), PX + 7, ly, RED, false);
            i++;
        }
    }

    /** 정전: 화면 전체를 까맣게 (0.4초에 걸쳐 어두워짐). 다른 화면 요소보다 먼저 그린다 */
    static void drawBlackout(DrawContext ctx, MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now >= St.blackoutUntil) return;
        float in = Math.min(1f, (now - St.blackoutFrom) / 400f);
        float out = Math.min(1f, (St.blackoutUntil - now) / 400f);
        int a = Math.round(250 * Math.max(0f, Math.min(in, out)));
        ctx.fill(0, 0, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight(), a << 24);
    }

    /** 대상 룰렛: 화면 가운데 위쪽에 이름 칸들이 나오고 불빛이 돌다가 한 명에서 멈춘다 */
    static void drawPick(DrawContext ctx, MinecraftClient mc) {
        St.Pick p = St.pick;
        if (p == null) return;
        long age = System.currentTimeMillis() - p.at();
        if (age > p.ms() + St.PICK_HOLD_MS) { St.pick = null; return; }
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int n = p.names().size();
        boolean done = age >= p.ms();
        // 불빛 위치: 처음엔 빠르게, 끝으로 갈수록 느리게 돌아 정해진 사람에서 멈춘다
        float t = Math.min(1f, age / (float) p.ms());
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        int steps = n * 4 + p.winner();
        int lit = done ? p.winner() : (int) Math.floor(ease * steps) % n;

        final int bw = 92, bh = 22, gap = 6;
        int perRow = Math.max(1, Math.min(n, (sw - 20 + gap) / (bw + gap)));
        int rows = (n + perRow - 1) / perRow;
        int top = Math.max(84, sh / 2 - 44 - rows * (bh + gap));
        String title = f.trimToWidth(p.title(), sw - 60);
        int tw = f.getWidth(title) + 24;
        box(ctx, (sw - tw) / 2, top, tw, 18, 0xE60D1424, AQUA);
        ctx.drawText(f, title, (sw - f.getWidth(title)) / 2, top + 5, WHITE, false);
        for (int i = 0; i < n; i++) {
            int row = i / perRow, inRow = Math.min(perRow, n - row * perRow);
            int rowW = inRow * bw + (inRow - 1) * gap;
            int x = (sw - rowW) / 2 + (i % perRow) * (bw + gap), y = top + 24 + row * (bh + gap);
            boolean on = i == lit;
            boolean blink = done && on && (age / 200) % 2 == 0;
            box(ctx, x, y, bw, bh, on ? (blink ? 0xFF8A2A2A : 0xFF5A1F1F) : 0xE0101826, on ? RED : BORDER);
            String name = f.trimToWidth(p.names().get(i), bw - 10);
            ctx.drawText(f, name, x + (bw - f.getWidth(name)) / 2, y + 7, on ? WHITE : GRAY, false);
        }
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
