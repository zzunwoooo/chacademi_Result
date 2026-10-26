package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Screen part of aiming: the magic cursor, the hint bar while a spell is aimed
 * (spell icon, left click = cast, right click = cancel) and the shoulder-view crosshair.
 * Text uses the HUD's own fonts (magiccodex:label / magiccodex:hud_bold) through CodexTypography.
 */
public final class AimHud {
    private static final Identifier BODY = Identifier.of("magiccodex", "label"), BOLD = Identifier.of("magiccodex", "hud_bold");
    private static final Identifier CROSSHAIR = Identifier.ofVanilla("hud/crosshair");
    private static final String MOUSE_LEFT = "magiccodex:textures/aim/mouse_left.png", MOUSE_RIGHT = "magiccodex:textures/aim/mouse_right.png";
    private static HudTextureCache images;
    private static CodexTypography text;
    private static int generation = -1;

    private AimHud() {}

    static void close() {
        if (images != null) { images.close(); images = null; }
        if (text != null) { text.close(); text = null; }
    }

    static void render(DrawContext ctx) {
        var c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null || c.options.hudHidden) return;
        if (!HudPointer.hudVisibleUnder(c.currentScreen)) return;
        int width = c.getWindow().getScaledWidth(), height = c.getWindow().getScaledHeight();
        boolean targeting = AimTargeting.targeting();
        int color = AimRenderer.COLOR;
        if (ShoulderCamera.active() && c.options.getPerspective() == Perspective.THIRD_PERSON_BACK && c.currentScreen == null && !c.player.isSpectator())
            ctx.drawGuiTexture(RenderLayer::getCrosshair,CROSSHAIR,width/2-7,height/2-7,15,15);
        if (targeting) hint(ctx, c, width, height, color);
    }

    /**
     * Small pill just above the hunger/armour chips (which sit above the hotbar): spell icon, left mouse = cast,
     * right mouse = cancel. Same units, capsule and bold font as those chips so it reads as part of the HUD.
     */
    private static void hint(DrawContext ctx, MinecraftClient c, int width, int height, int rgb) {
        var spell = AimTargeting.spell();
        var preview = AimTargeting.preview();
        if (generation != UiResources.generation()) { close(); generation = UiResources.generation(); }
        if (images == null) images = new HudTextureCache(c);
        if (text == null) text = new CodexTypography(c);
        images.beginFrame();
        text.beginFrame();
        float scale, centerY, centerX = width / 2f;
        if (PlayerHudClient.active()) {
            var l = PlayerHudLayout.player(width, height, PlayerHudSettings.get().percent()); // same dock as the chips
            scale = l.scale();
            centerX = (l.hotbarLeft() + 238) * scale; // follows the dock when it moves aside for the portrait
            // Chips: centre hotbarTop-25, 28 tall -> top at hotbarTop-39. Pill (24 tall) sits 7 units above that.
            centerY = (l.hotbarTop() - 39 - 7 - 12) * scale;
        } else {
            scale = 1;
            centerY = height - 22 - 30 - 12 - 14; // vanilla hotbar + hunger/armour rows
        }
        float textSize = 12.5f, icon = 18, mouse = 15, gap = 6;
        boolean chargeSpell = SpellChargeClient.supported(spell.id());
        String cast = chargeSpell ? "눌러서 충전" : "발동", cancel = "취소";
        float castW = text.width(cast, textSize, BOLD), cancelW = text.width(cancel, textSize, BOLD);
        float total = icon + gap * 2 + mouse + 4 + castW + gap * 2 + mouse + 4 + cancelW;
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(centerX, centerY, 200);
            ctx.getMatrices().scale(scale, scale, 1);
            HudMesh.capsule(ctx, -total / 2 - 12, -12, total + 24, 24, 0x90101925, 0x90101925);
            float x = -total / 2;
            images.drawCentered(ctx, spell.icon(), x + icon / 2, 0, (int) icon, 0xFFFFFFFF);
            x += icon + gap * 2;
            images.drawCentered(ctx, MOUSE_LEFT, x + mouse / 2, 0, (int) mouse, 0xFF000000 | rgb);
            x += mouse + 4;
            text.draw(ctx, cast, x, 0, textSize, 0xFFF1F3F5, BOLD, false);
            x += castW + gap * 2;
            images.drawCentered(ctx, MOUSE_RIGHT, x + mouse / 2, 0, (int) mouse, 0xFFE6E0D2);
            x += mouse + 4;
            text.draw(ctx, cancel, x, 0, textSize, 0xFFF1F3F5, BOLD, false);
            boolean charging = SpellChargeClient.charging();
            if (charging) {
                // 338 충전: a thin bar in the pill's own capsule style just above it, aim colour, brighter when full
                float k = SpellChargeClient.progress(), barW = total + 24, barX = -barW / 2, barY = -12 - 4 - 5;
                HudMesh.capsule(ctx, barX, barY, barW, 5, 0x90101925, 0x90101925);
                int fill = k >= 1f ? 0xFFFFF1C8 : 0xE0000000 | rgb;
                if (k > 0) HudMesh.capsule(ctx, barX, barY, Math.max(5, barW * k), 5, fill, fill);
            }
            if (preview != null && !preview.valid() && preview.reason() != null) {
                float blink = .75f + .25f * (float) Math.sin(Util.getMeasuringTimeMs() / 160.0);
                int alpha = Math.round(255 * blink) << 24;
                text.drawOutline(ctx, preview.reason(), 0, charging ? -32 : -24, 12, alpha | 0xFFB4A8, BOLD, true);
            }
        } finally {
            ctx.getMatrices().pop();
            images.endFrame();
        }
    }
}
