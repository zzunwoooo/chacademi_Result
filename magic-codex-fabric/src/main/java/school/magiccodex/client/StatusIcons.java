package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import school.magiccodex.protocol.ElementProtocol;
import school.magiccodex.protocol.StatusProtocol;

/**
 * Status icons of the HUD v4 reference (2026-10-19): the colored 256 px symbol alone (no ring, no frame), time left
 * written under it where there is room (target HUD, own HUD). A very soft dark halo behind the symbol keeps it
 * readable on bright sky. Last second: blinks. Used above heads, on the target HUD and on the player's own HUD.
 * Element marks are drawn the same way with their element PNG.
 */
final class StatusIcons {
    private StatusIcons() {}

    static String path(StatusProtocol.Status s) { return "magiccodex:textures/status/" + s.id + ".png"; }

    /** Symbol PNG fitted to `size`, with a faint halo for contrast. */
    static void draw(DrawContext ctx, HudTextureCache images, String icon, float cx, float cy, float size, float alpha) {
        HudMesh.disk(ctx, cx, cy, size * .38f, argb(0x050A14, .20f * alpha));
        images.drawCentered(ctx, icon, cx, cy, Math.max(1, Math.round(size)), argb(0xFFFFFF, alpha));
    }

    static void status(DrawContext ctx, HudTextureCache images, StatusClient.Line line, float cx, float cy, float d, long now, float alpha) {
        draw(ctx, images, path(line.status()), cx, cy, d, alpha * line.blink(now));
    }

    static void element(DrawContext ctx, HudTextureCache images, ElementMarkClient.Shown mark, float cx, float cy, float d, long now, float alpha) {
        long left = mark.end() - now;
        float a = alpha * (left < 1000 ? .45f + .55f * (float) Math.abs(Math.sin(now / 110.0)) : 1f);
        draw(ctx, images, ElementMarkClient.icon(mark.element()), cx, cy, d * .9f, a);
    }

    /** "3초", "2분"; "" without an end (vanilla infinite effects). */
    static String timeLeft(StatusClient.Line line, long now) { return line.infinite() ? "" : seconds(line.end() - now); }
    static String timeLeft(ElementMarkClient.Shown mark, long now) { return seconds(mark.end() - now); }
    private static String seconds(long ms) {
        long sec = Math.max(1, (long) Math.ceil(ms / 1000.0));
        return sec >= 60 ? (sec + 59) / 60 + "분" : sec + "초";
    }

    /** "…" after the third status above a head. */
    static void more(DrawContext ctx, float cx, float cy, float d, float alpha) {
        float dot = Math.max(1f, d * .07f), gap = d * .2f;
        for (int i = -1; i <= 1; i++) HudMesh.disk(ctx, cx + i * gap, cy, dot, argb(0xE8EEF7, alpha));
    }

    /** Hover popup text (owner 2026-10-19). Numbers match the default config.yml status values. */
    static String description(StatusProtocol.Status s) {
        return switch (s) {
            case STUN -> "움직이거나 공격·마법을 쓸 수 없습니다.";
            case ROOT -> "움직일 수 없습니다. 공격과 마법은 쓸 수 있습니다.";
            case DISARM -> "근접 공격과 마법을 쓸 수 없습니다.";
            case SLOW -> "이동 속도가 느려집니다.";
            case BURN -> "끝날 때 그동안 받은 피해의 10%를\n한 번 더 받습니다.";
            case RECOVERY_SEAL -> "체력과 마나가 회복되지 않습니다.";
            case MAGIC_WEAKNESS -> "주는 마법 피해가 30% 줄어듭니다.";
            case GRAVITY -> "비행할 수 없습니다.";
            case MOVEMENT_LOCK -> "이동 마법(비행 포함)을 쓸 수 없습니다.";
            case POISON -> "매초 피해를 받습니다.";
            case PHYSICAL_WEAKNESS -> "주는 공격 피해(근접·화살)가 30% 줄어듭니다.";
            case BLINDNESS -> "앞이 잘 보이지 않습니다.";
            case POTION_SEAL -> "물약을 마시거나 던질 수 없습니다.";
            case MANA_BURN -> "최대 마나의 10%를 빼앗겼습니다.\n아이콘이 사라질 때까지 다시 빼앗기지 않습니다.";
            case SLEEP -> "움직일 수 없습니다. 피해를 받으면 깨어납니다.";
            case FEAR -> "겁에 질려 상대에게서 멀어집니다.\n공격할 수 없습니다.";
        };
    }

    /** One hoverable icon on screen (GUI px). */
    record Hit(float cx, float cy, float r, StatusClient.Line line, ElementMarkClient.Shown mark) {}

    /** Hover popup: name in the status color, description, time left. Kept inside the screen. */
    static void tooltip(DrawContext ctx, CodexTypography text, Hit h, float mx, float my, int screenW, int screenH, long now) {
        String title, body, time;
        int rgb;
        if (h.line() != null) {
            var s = h.line().status();
            title = s.label; body = description(s); rgb = s.color;
            time = h.line().infinite() ? "계속" : String.format(java.util.Locale.ROOT, "남은 시간 %.1f초", Math.max(0, h.line().end() - now) / 1000f);
        } else {
            var e = h.mark().element();
            title = e.label + " 표식"; body = "다른 원소 마법을 맞히면\n원소 반응이 일어납니다."; rgb = ElementMarkClient.color(e);
            time = String.format(java.util.Locale.ROOT, "남은 시간 %.1f초", Math.max(0, h.mark().end() - now) / 1000f);
        }
        var title_ = net.minecraft.util.Identifier.of("magiccodex", "hud_bold");
        var face = net.minecraft.util.Identifier.of("magiccodex", "label");
        float ts = 13, bs = 11, pad = 9, gap = 4, lineH = bs + 3;
        String[] lines = body.split("\n");
        float w = Math.max(text.width(title, ts, title_), text.width(time, bs, face));
        for (String l : lines) w = Math.max(w, text.width(l, bs, face));
        w += pad * 2;
        float h2 = pad * 2 + ts + gap + lines.length * lineH + gap + bs;
        float x = mx + 12, y = my + 12;
        if (x + w > screenW - 4) x = mx - 12 - w;
        if (y + h2 > screenH - 4) y = my - 12 - h2;
        x = Math.max(4, x); y = Math.max(4, y);
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(0, 0, 400);
            int edge = 0xFF000000 | rgb;
            HudMesh.capsule(ctx, x, y, w, h2, edge, edge);
            HudMesh.capsule(ctx, x + 1.5f, y + 1.5f, w - 3, h2 - 3, 0xF2102536, 0xF20C1F32);
            float ty = y + pad + ts / 2;
            text.draw(ctx, title, x + pad, ty, ts, 0xFF000000 | lighten(rgb), title_, false);
            float ly = ty + ts / 2 + gap + lineH / 2;
            for (String l : lines) { text.draw(ctx, l, x + pad, ly, bs, 0xFFE8EEF5, face, false); ly += lineH; }
            text.draw(ctx, time, x + pad, ly - lineH / 2 + gap + bs / 2, bs, 0xFFA9B8C9, face, false);
        } finally { ctx.getMatrices().pop(); }
    }

    static int lighten(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return ((r + (255 - r) / 4) << 16) | ((g + (255 - g) / 4) << 8) | (b + (255 - b) / 4);
    }
    static int argb(int rgb, float alpha) { return (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF); }

}
