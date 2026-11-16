package com.diamondserver.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * 항상 떠 있는 화면 요소: 남은 목표·보유 다이아, 이벤트 대기열, 걸린 효과, 후원 알림, 알림 글, 대상 룰렛.
 *
 * ★ 디자인 없음: 꾸밈(상자·색·그림) 없이 글자만 쓴다. 화면 모양은 오너가 GPT 로 받아 오는 그림으로 정한다.
 *   그림이 오면 이 파일의 그리기만 바꾸면 된다 (자리·내용을 만드는 쪽은 그대로).
 */
public final class Hud {
    private Hud() { }

    static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0;
    static final int X = 6, Y = 6, LINE = 10;
    static final int TOP_Y = 46;        // 화면 위쪽 가운데 글(후원 알림·알림 글)이 시작하는 높이

    static String n(long v) { return String.format("%,d", v); }

    private static String signed(long v) { return (v > 0 ? "+" : "-") + n(Math.abs(v)); }

    private static void left(DrawContext ctx, TextRenderer f, String s, int y, int color) {
        ctx.drawText(f, s, X, y, color, true);
    }

    private static void center(DrawContext ctx, TextRenderer f, int sw, String s, int y, int color) {
        ctx.drawText(f, s, (sw - f.getWidth(s)) / 2, y, color, true);
    }

    /** 남은 목표 · 보유 다이아 (값이 바뀌면 1.5초 동안 옆에 +N / -N) */
    static void drawPanel(DrawContext ctx, MinecraftClient mc) {
        TextRenderer f = mc.textRenderer;
        long now = System.currentTimeMillis();
        String goal = "남은 목표 " + (St.done ? "달성" : n(St.goal));
        if (St.goalDelta != 0 && now - St.goalChangedAt < 1500) goal += " (" + signed(St.goalDelta) + ")";
        String held = "보유 다이아 " + n(St.held);
        if (St.heldDelta != 0 && now - St.heldChangedAt < 1500) held += " (" + signed(St.heldDelta) + ")";
        left(ctx, f, goal, Y, WHITE);
        left(ctx, f, held, Y + LINE, WHITE);
    }

    private static int queueLines() {
        return St.queue.isEmpty() ? 0 : Math.min(4, St.queue.size()) + (St.queue.size() > 4 ? 1 : 0);
    }

    private static int queueTop() { return Y + LINE * 2 + 4; }

    private static int effectsTop() { return queueTop() + queueLines() * LINE + (queueLines() > 0 ? 4 : 0); }

    /** 줄 서 있는 이벤트: 최대 4줄, 맨 위가 진행 중 */
    static void drawQueue(DrawContext ctx, MinecraftClient mc) {
        if (St.queue.isEmpty()) return;
        TextRenderer f = mc.textRenderer;
        int lines = Math.min(4, St.queue.size());
        for (int i = 0; i < lines; i++)
            left(ctx, f, f.trimToWidth((i == 0 ? "> " : "  ") + St.queue.get(i), 130), queueTop() + i * LINE, i == 0 ? WHITE : GRAY);
        if (St.queue.size() > 4) left(ctx, f, "  외 " + (St.queue.size() - 4) + "건", queueTop() + lines * LINE, GRAY);
    }

    /** 지금 걸려 있는 효과와 남은 시간 */
    static void drawEffects(DrawContext ctx, MinecraftClient mc) {
        TextRenderer f = mc.textRenderer;
        long now = System.currentTimeMillis();
        int i = 0;
        for (St.Effect e : St.effects) {
            if (e.endsAt() <= now) continue;
            left(ctx, f, f.trimToWidth(e.name(), 100) + " " + ((e.endsAt() - now + 999) / 1000) + "초", effectsTop() + i * LINE, WHITE);
            i++;
        }
    }

    /** 정전: 화면 전체를 까맣게 (기능이지 꾸밈이 아니다). 다른 것보다 먼저 그린다 */
    static void drawBlackout(DrawContext ctx, MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now >= St.blackoutUntil) return;
        float in = Math.min(1f, (now - St.blackoutFrom) / 400f);
        float out = Math.min(1f, (St.blackoutUntil - now) / 400f);
        int a = Math.round(250 * Math.max(0f, Math.min(in, out)));
        ctx.fill(0, 0, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight(), a << 24);
    }

    static boolean bannerShowing() {
        return St.banner != null && System.currentTimeMillis() - St.banner.at() < St.BANNER_MS;
    }

    /** 후원 알림 두 줄: "누구님 별풍선 N개" / "이벤트 이름 → 대상". 그린 글의 아래쪽 y 를 돌려준다 (안 그렸으면 -1) */
    static int drawBanner(DrawContext ctx, MinecraftClient mc) {
        if (!bannerShowing()) return -1;
        St.Banner b = St.banner;
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        center(ctx, f, sw, f.trimToWidth(b.donor() + "님 별풍선 " + n(b.count()) + "개", sw - 40), TOP_Y, WHITE);
        center(ctx, f, sw, f.trimToWidth(b.target().isEmpty() ? b.name() : b.name() + " → " + b.target(), sw - 40), TOP_Y + LINE, WHITE);
        return TOP_Y + LINE * 2;
    }

    /** 알림 글 (위쪽 가운데, 후원 알림이 떠 있으면 그 아래부터) */
    static void drawToasts(DrawContext ctx, MinecraftClient mc, int bannerBottom) {
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        long now = System.currentTimeMillis();
        int y = bannerBottom > 0 ? bannerBottom + 4 : TOP_Y;
        for (St.Toast t : St.toasts) {
            if (now - t.at() > St.TOAST_MS) continue;
            center(ctx, f, sw, f.trimToWidth(t.text(), sw - 16), y, WHITE);
            y += LINE;
        }
    }

    /** 대상 룰렛: 이름들이 한 줄로 나오고 "[ 이름 ]" 표시가 돌다가 한 명에서 멈춘다 */
    static void drawPick(DrawContext ctx, MinecraftClient mc) {
        St.Pick p = St.pick;
        if (p == null) return;
        long age = System.currentTimeMillis() - p.at();
        if (age > p.ms() + St.PICK_HOLD_MS) { St.pick = null; return; }
        TextRenderer f = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int n = p.names().size();
        boolean done = age >= p.ms();
        // 처음엔 빠르게, 끝으로 갈수록 느리게 돌아 정해진 사람에서 멈춘다
        float t = Math.min(1f, age / (float) p.ms());
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        int steps = n * 4 + p.winner();
        int lit = done ? p.winner() : (int) Math.floor(ease * steps) % n;

        final int cell = 98;
        int perRow = Math.max(1, Math.min(n, (sw - 20) / cell));
        int rows = (n + perRow - 1) / perRow;
        int top = Math.max(84, sh / 2 - 30 - rows * (LINE + 2));
        center(ctx, f, sw, f.trimToWidth(p.title(), sw - 60), top, WHITE);
        for (int i = 0; i < n; i++) {
            int row = i / perRow, inRow = Math.min(perRow, n - row * perRow);
            int x = (sw - inRow * cell) / 2 + (i % perRow) * cell, y = top + LINE + 4 + row * (LINE + 2);
            String name = f.trimToWidth(p.names().get(i), cell - 22);
            String s = i == lit ? "[ " + name + " ]" : name;
            ctx.drawText(f, s, x + (cell - f.getWidth(s)) / 2, y, i == lit ? WHITE : GRAY, true);
        }
    }
}
