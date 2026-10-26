package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import school.magiccodex.protocol.LootBoxProtocol;

/**
 * 뽑기 상자 (API 상자) 연출 + 결과 창 — docs/design/loot-box-v1 (승인 레퍼런스 reference-corrected.png) 그대로.
 *
 * <p>화면 중앙에 닫힌 상자 등장 → 작게 들썩임(위치·회전) → 대표 등급의 열린 상자 PNG 로 바뀌며 뚜껑이 열림(빛은 PNG 에 들어 있음)
 * → 결과: 1개면 가운데 한 칸, 10개면 5열×2행. 상자는 좌우 반전하지 않고, 파괴·화염 효과·새 배경·장식은 넣지 않는다(게임 화면 위에 그대로).
 * 상자 PNG 는 1254×1254 원본 캔버스를 자르지 않고 바닥 기준점(698,1134)을 고정점으로 그린다.</p>
 * <p>결과 패널·칸·확인 버튼은 기존 UI 재사용: 아티팩트 소환 창의 패널(artifact/tooltip.png)·버튼(artifact/button.png)·
 * 등급 칸(summon/frame_&lt;등급&gt;[_fill].png). 칸 위 마우스 = 기존 아이템 툴팁(ItemTooltipRenderer), 서버가 지급 확정한 ItemStack 그대로.</p>
 * <p>이 창이 열릴 때는 서버 지급이 이미 끝났다. 확인 버튼·ESC 는 창을 닫기만 한다(지급 트리거 아님).</p>
 */
final class LootBoxScreen extends Screen {
    static final float W = 1672, H = 941;
    private static final int CANVAS = 1254, ANCHOR_X = 698, ANCHOR_Y = 1134;
    private static final Identifier CLOSED = Identifier.of("magiccodex", "textures/gui/lootbox/chest_closed.png");
    private static final Identifier[] OPEN = {
            Identifier.of("magiccodex", "textures/gui/lootbox/chest_open_common.png"), Identifier.of("magiccodex", "textures/gui/lootbox/chest_open_rare.png"),
            Identifier.of("magiccodex", "textures/gui/lootbox/chest_open_unique.png"), Identifier.of("magiccodex", "textures/gui/lootbox/chest_open_legendary.png")};
    private static final String[] GRADE_KEY = {"common", "rare", "unique", "legendary"};
    private static final Identifier PANEL = Identifier.of("magiccodex", "textures/gui/artifact/tooltip.png");
    private static final Identifier BUTTON = Identifier.of("magiccodex", "textures/gui/artifact/button.png");
    private static final Identifier BOLD = Identifier.of("magiccodex", "hud_bold"), BODY = Identifier.of("magiccodex", "body");
    /** 상자 캔버스가 화면(기준 공간)에 그려지는 크기와 바닥 기준점 위치. */
    private static final float CHEST = 640, FOOT_X = W / 2, FOOT_Y = 735;

    private final LootBoxProtocol.Play play;
    private final List<ItemStack> items = new ArrayList<>();
    private final float speed;
    private long start = -1;
    private boolean doneSent, openedSound, appearSound;
    private int bumps;
    private float scale = 1, ox, oy;

    LootBoxScreen(LootBoxProtocol.Play play) {
        super(Text.literal(play.title()));
        this.play = play;
        this.speed = Math.clamp(play.speed(), 50, 200) / 100f;
    }

    @Override protected void init() {
        if (!items.isEmpty() || client == null || client.world == null) return;
        for (var r : play.results()) items.add(decode(r.item()));
    }

    private ItemStack decode(byte[] b) {
        try { return ItemStack.fromNbt(client.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY); }
        catch (Exception e) { return ItemStack.EMPTY; }
    }

    @Override public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ 시간표 (속도 100% 기준 ms)

    private float t(float ms) { return ms / speed; }
    private float appearEnd() { return t(420); }
    private float rumbleEnd() { return t(1380); }
    private float resultAt() { return t(2450); }

    private long elapsed(long now) { if (start < 0) start = now; return now - start; }

    // ------------------------------------------------------------------ 그리기

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        long e = elapsed(now);
        var images = UiResources.images(); images.beginFrame(); UiResources.text().beginFrame();
        ItemStack hover = ItemStack.EMPTY;
        try {
            scale = Math.min(width / W, height / H);
            ox = (width - W * scale) / 2; oy = (height - H * scale) / 2;
            double mx = (mouseX - ox) / scale, my = (mouseY - oy) / scale;
            var m = c.getMatrices(); m.push();
            try {
                m.translate(ox, oy, 0); m.scale(scale, scale, 1);
                float fade = e < resultAt() - t(150) ? 1 : Math.max(0, 1 - (e - (resultAt() - t(150))) / t(300));
                if (fade > 0) chest(c, e, fade);
                if (e >= resultAt() - t(150)) hover = result(c, Math.min(1, (e - (resultAt() - t(150))) / t(300)), mx, my);
                sounds(e);
            } finally { m.pop(); }
        } finally { images.endFrame(); }
        if (!hover.isEmpty()) {
            c.getMatrices().push(); c.getMatrices().translate(0, 0, 400);
            if (!ItemTooltipRenderer.render(c, textRenderer, hover, mouseX, mouseY)) c.drawItemTooltip(textRenderer, hover, mouseX, mouseY);
            c.getMatrices().pop();
        }
    }

    /** 닫힌 상자 등장·들썩임 → 등급 PNG 로 열림. 회전·이동은 바닥 기준점을 축으로. */
    private void chest(DrawContext c, long e, float fade) {
        float alpha = fade, size = CHEST, dx = 0, dy = 0, rot = 0;
        Identifier art = CLOSED;
        float crossfade = 0;
        if (e < appearEnd()) {
            float p = e / appearEnd(), ease = 1 - (1 - p) * (1 - p) * (1 - p);
            alpha *= ease; size *= .9f + .1f * ease; dy = 26 * (1 - ease);
        } else if (e < rumbleEnd()) {
            float p = (e - appearEnd()) / (rumbleEnd() - appearEnd()), env = (float) Math.sin(p * Math.PI);
            dx = (float) (7 * Math.sin(p * Math.PI * 8)) * env;
            rot = (float) (2.6 * Math.sin(p * Math.PI * 6 + .4)) * env;
            dy = (float) (-11 * Math.abs(Math.sin(p * Math.PI * 3))) * env;
        } else {
            float p = Math.min(1, (e - rumbleEnd()) / t(260));
            size *= 1 + .045f * (float) Math.sin(p * Math.PI);
            crossfade = Math.min(1, (e - rumbleEnd()) / t(110));
            art = OPEN[Math.clamp(play.grade(), 0, 3)];
        }
        if (crossfade > 0 && crossfade < 1) drawChest(c, CLOSED, size, dx, dy, rot, alpha * (1 - crossfade));
        drawChest(c, art, size, dx, dy, rot, alpha * (art == CLOSED ? 1 : Math.max(crossfade, 0)));
    }

    private void drawChest(DrawContext c, Identifier art, float size, float dx, float dy, float rot, float alpha) {
        if (alpha <= 0.01f) return;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(FOOT_X + dx, FOOT_Y + dy, 0);
            if (rot != 0) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rot));
            float k = size / CANVAS;
            m.scale(k, k, 1);
            int tint = (Math.round(alpha * 255) << 24) | 0xFFFFFF;
            UiResources.images().drawTexture(c, art, -ANCHOR_X, -ANCHOR_Y, 0, 0, CANVAS, CANVAS, CANVAS, CANVAS, CANVAS, CANVAS, tint);
        } finally { m.pop(); }
    }

    // ------------------------------------------------------------------ 결과

    private boolean single() { return items.size() == 1; }
    private ArtifactSummonScreen.Box panel() { return single() ? new ArtifactSummonScreen.Box(W / 2 - 250, 255, 500, 390) : new ArtifactSummonScreen.Box(W / 2 - 440, 205, 880, 475); }
    private ArtifactSummonScreen.Box confirm() { return single() ? new ArtifactSummonScreen.Box(W / 2 - 130, 548, 260, 60) : new ArtifactSummonScreen.Box(W / 2 - 130, 586, 260, 60); }
    private ArtifactSummonScreen.Box cell(int i) {
        if (single()) return new ArtifactSummonScreen.Box(W / 2 - 95, 300, 190, 190);
        float size = 142, gap = 20, left = W / 2 - (5 * size + 4 * gap) / 2;
        return new ArtifactSummonScreen.Box(left + (i % 5) * (size + gap), 250 + (i / 5) * (size + gap), size, size);
    }

    private ItemStack result(DrawContext c, float alpha, double mx, double my) {
        int a = Math.round(Math.clamp(alpha, 0, 1) * 255);
        if (a < 4) return ItemStack.EMPTY;
        int tint = (a << 24) | 0xFFFFFF;
        ArtifactSummonScreen.nine(c, PANEL, 992, 396, 9, 93, 981, 294, 45, panel(), (Math.round(a * .94f) << 24) | 0xFFFFFF);
        ItemStack hover = ItemStack.EMPTY;
        var images = UiResources.images();
        for (int i = 0; i < items.size(); i++) {
            var box = cell(i);
            int g = Math.clamp(play.results().get(i).grade(), 0, 3);
            var fill = Identifier.of("magiccodex", "textures/gui/summon/frame_" + GRADE_KEY[g] + "_fill.png");
            var frame = Identifier.of("magiccodex", "textures/gui/summon/frame_" + GRADE_KEY[g] + ".png");
            stretch(c, fill, box, tint);
            stretch(c, frame, box, tint);
            var stack = items.get(i);
            float icon = box.w() * .58f;
            if (!stack.isEmpty() && a > 100) drawStack(c, stack, box.cx(), box.cy(), icon);
            if (stack.getCount() > 1 && a > 100) UiResources.text().draw(c, Integer.toString(stack.getCount()), box.x() + box.w() * .80f, box.y() + box.h() * .80f, box.h() * .16f, 0xFFF3E9D3, BOLD, true);
            if (alpha >= 1 && box.contains(mx, my) && !stack.isEmpty()) hover = stack;
        }
        var b = confirm();
        boolean over = alpha >= 1 && b.contains(mx, my);
        ArtifactSummonScreen.nine(c, BUTTON, 1086, 362, 29, 78, 1057, 282, 100, b, (a << 24) | (over ? 0xFFFFFF : 0xE2E6EC));
        UiResources.text().draw(c, "확인", b.cx(), b.cy(), 25, (a << 24) | (over ? 0xFFFFFF : 0xF1E3C2), BOLD, true);
        if (!play.notice().isBlank()) UiResources.text().draw(c, play.notice(), W / 2, panel().y() + panel().h() + 30, 19, (a << 24) | 0xE4E9EF, BODY, true);
        return hover;
    }

    private static void stretch(DrawContext c, Identifier id, ArtifactSummonScreen.Box b, int tint) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(b.x(), b.y(), 0); m.scale(b.w() / 384f, b.h() / 384f, 1);
            UiResources.images().drawTexture(c, id, 0, 0, 0, 0, 384, 384, 384, 384, 384, 384, tint);
        } finally { m.pop(); }
    }

    /**
     * 가구 보상(2026-10-26 가구 뽑기권)의 표시용 아이템에 서버가 붙인 그림 경로 (PDC magiccodexbridge:lootbox_icon), 없으면 null.
     * 하우징 보관함과 같은 가구 PNG(magiccodex:textures/gui/housing/...)를 그 크기로 그린다.
     */
    static String rewardIcon(ItemStack stack) {
        var d = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (d == null) return null;
        String path = d.copyNbt().getCompound("PublicBukkitValues").getString("magiccodexbridge:lootbox_icon");
        return path.isBlank() || path.length() > 96 || !path.startsWith("magiccodex:textures/") || !path.endsWith(".png") ? null : path;
    }

    /** 고해상도 아이콘은 그 크기로 새로 그리고(ExactItemIcons), 없으면 일반 아이템 그리기 — 가방 창과 같은 규칙. 가구 보상은 가구 그림. */
    private static void drawStack(DrawContext c, ItemStack stack, float cx, float cy, float size) {
        String icon = rewardIcon(stack);
        if (icon != null && UiResources.images().drawCentered(c, icon, cx, cy, Math.round(size), 0xFFFFFFFF)) return;
        var m = c.getMatrices(); m.push();
        try {
            if (!ExactItemIcons.draw(c, stack, cx - size / 2, cy - size / 2, size)) {
                m.translate(cx - size / 2, cy - size / 2, 0); m.scale(size / 16f, size / 16f, 1);
                c.drawItem(stack, 0, 0);
            }
        } finally { m.pop(); }
    }

    // ------------------------------------------------------------------ 소리

    private void sounds(long e) {
        if (!appearSound) { appearSound = true; sound(SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1.2f, .45f); }
        if (e >= appearEnd() && e < rumbleEnd()) {
            int beat = (int) ((e - appearEnd()) / ((rumbleEnd() - appearEnd()) / 3f));
            if (beat >= bumps && bumps < 3) { bumps++; sound(SoundEvents.BLOCK_WOOD_HIT, .8f + .1f * bumps, .6f); }
        }
        if (e >= rumbleEnd() && !openedSound) {
            openedSound = true;
            sound(SoundEvents.BLOCK_CHEST_OPEN, 1.1f, .6f);
            switch (Math.clamp(play.grade(), 0, 3)) {   // 아티팩트 소환 창의 열림 소리와 같은 등급별 소리
                case 0 -> sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, .6f);
                case 1 -> sound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.1f, .8f);
                case 2 -> sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, .6f);
                default -> { sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, .6f); sound(SoundEvents.ITEM_TOTEM_USE, 1.2f, .35f); }
            }
        }
        if (e >= resultAt() && !doneSent) { doneSent = true; LootBoxClient.done(play.opening()); }
    }

    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }

    // ------------------------------------------------------------------ 입력

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        long e = elapsed(Util.getMeasuringTimeMs());
        double mx = (mouseX - ox) / scale, my = (mouseY - oy) / scale;
        if (e < resultAt()) {   // 연출 건너뛰기: 바로 결과로 (열림 소리는 한 번)
            start = Util.getMeasuringTimeMs() - (long) resultAt();
            appearSound = true; bumps = 3;
            if (!openedSound) { openedSound = true; sound(SoundEvents.BLOCK_CHEST_OPEN, 1.1f, .6f); }
            return true;
        }
        if (confirm().contains(mx, my)) { sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f); close(); }
        return true;
    }

    @Override public void close() {
        if (!doneSent) { doneSent = true; LootBoxClient.done(play.opening()); }
        super.close();
    }

    @Override public void tick() { if (client == null || client.player == null) close(); }
}
