package school.magiccodex.client;

import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.ElementProtocol;
import school.magiccodex.protocol.StatusProtocol;
import school.magiccodex.protocol.TargetHudProtocol;

/**
 * Target health HUD at the top of the screen (HUD v4 reference, owner 2026-10-19) and the player's own status
 * icons at the top right (where vanilla effect icons were; vanilla effect icons are hidden).
 *
 *              별숲의 수호자                     name (rare/변색종 = lavender, rare gauge)
 *              ───◇───                  280     title divider · damage I dealt to it
 *   ◆[██████████████720 / 1000█████        ]◆   one HP gauge clipped to the ratio (gold frame on top)
 *    [=================                      ]   mana gauge (players / mobs with mana only; otherwise the HP
 *    ♥                              ✦  ✕  🔥     gauge fills the whole opening, frame_hp.png)
 *   38%                            3초 5초 8초   taming odds · element mark (left) + statuses from the right, time below
 *
 * Geometry is in frame-asset pixels (frame.png 2048 x 84, HP x54 y19 1940x36, MP x54 y57 1940x10 from layout.json)
 * scaled to the HUD width. All images go through HudTextureCache (full-size PNG, alpha-correct trilinear mipmaps);
 * text is baked at the real device size by CodexTypography with a black stroke under a colored fill.
 * The server decides the targets - see TargetHudBridge. Up to two bars: with a boss near, the boss on top and the
 * normal mob under the crosshair below it (78 % size); otherwise one bar (combat partner 10 s, else crosshair).
 * All boss bars are hidden (owner 2026-10-19: this HUD replaces them).
 */
public final class TargetHudClient {
    private TargetHudClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(TargetHudProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > TargetHudProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    /** Client -> server crosshair ray (TargetHudProtocol.AIM_CHANNEL): the bar follows the aim, not the head (owner 2026-10-19). */
    public record AimPayload(byte[] bytes) implements CustomPayload {
        static final Id<AimPayload> ID = new Id<>(Identifier.of(TargetHudProtocol.AIM_CHANNEL));
        static final PacketCodec<RegistryByteBuf, AimPayload> CODEC = new PacketCodec<>() {
            public AimPayload decode(RegistryByteBuf b) { int n = Math.min(b.readableBytes(), TargetHudProtocol.AIM_BYTES); byte[] a = new byte[n]; b.readBytes(a); b.skipBytes(b.readableBytes()); return new AimPayload(a); }
            public void encode(RegistryByteBuf b, AimPayload value) { b.writeBytes(value.bytes()); }
        };
        public Id<AimPayload> getId() { return ID; }
    }
    private static float[] lastAim;
    private static int aimTicks;

    /** Every 2 ticks: send the crosshair ray when it moved (0.5 deg / 5 cm), else once a second as keepalive. */
    private static void sendAim(MinecraftClient c) {
        if (++aimTicks % 2 != 0) return;
        var p = c.player;
        if (p == null || c.world == null || c.getNetworkHandler() == null || !ClientPlayNetworking.canSend(AimPayload.ID)) return;
        var eye = p.getEyePos();
        var cam = ShoulderCamera.active() && AimView.valid() ? AimView.camera() : eye;
        var off = cam.subtract(eye);
        if (off.lengthSquared() > TargetHudProtocol.MAX_AIM_OFFSET * TargetHudProtocol.MAX_AIM_OFFSET) off = net.minecraft.util.math.Vec3d.ZERO;
        float[] now = {(float) off.x, (float) off.y, (float) off.z, p.getYaw(), Math.clamp(p.getPitch(), -90f, 90f)};
        boolean moved = lastAim == null || Math.abs(now[3] - lastAim[3]) > .5f || Math.abs(now[4] - lastAim[4]) > .5f
            || Math.abs(now[0] - lastAim[0]) + Math.abs(now[1] - lastAim[1]) + Math.abs(now[2] - lastAim[2]) > .05f;
        if (!moved && aimTicks % 20 != 0) return;
        try {
            ClientPlayNetworking.send(new AimPayload(TargetHudProtocol.encodeAim(new TargetHudProtocol.Aim(now[0], now[1], now[2], now[3], now[4]))));
            lastAim = now;
        } catch (IllegalArgumentException ignored) {}
    }

    // ---------------------------------------------------------------- assets (magiccodex:textures/gui/target_hud)
    private static final String DIR = "magiccodex:textures/gui/target_hud/";
    private static final String FRAME = DIR + "frame.png", FRAME_HP = DIR + "frame_hp.png", TRACK = DIR + "track.png",
        TRACK_HP = DIR + "track_hp.png", HP = DIR + "hp_normal.png", HP_RARE = DIR + "hp_rare.png", MANA = DIR + "mana.png",
        DIVIDER = DIR + "title_divider.png", HEART = DIR + "heart.png";
    private static final Identifier TITLE = Identifier.of("magiccodex", "title"), BOLD = Identifier.of("magiccodex", "hud_bold");

    // ---------------------------------------------------------------- geometry (frame-asset px, frame.png 2048 x 84)
    static final int FRAME_W = 2048, FRAME_H = 84;
    static final float GAUGE_X = 54, GAUGE_W = 1940, HP_Y = 19, HP_H = 36, HP_ONLY_H = 48, MP_Y = 57, MP_H = 10;
    /** Sizes are chosen for readability at ~40 % screen width (the reference draws text and icons smaller). */
    static final float NAME_Y = -70, NAME_SIZE = 74, DIVIDER_Y = -12, DIVIDER_W = 430, DAMAGE_Y = -24, DAMAGE_SIZE = 54; // owner: name + divider sit right on the frame
    static final float HP_TEXT = 34, BELOW = 20, HEART_X = 70, HEART_SIZE = 58, ICON_D = 86, ICON_STEP = 104, ICON_RIGHT = 1994,
        TIME_SIZE = 34, PCT_SIZE = 36;
    /** Icon row height below the frame: icon + time text. */
    static final float ROW_H = BELOW + ICON_D + 6 + TIME_SIZE;

    static float width(int screenW) { return TargetHudLayout.width(screenW); }

    // ---------------------------------------------------------------- state
    /** One bar on screen: eased values per entity, kept per slot. */
    private static final class Bar { int id = Integer.MIN_VALUE; long shownAt; float health = -1, mana = -1; }
    private static final Bar[] bars = {new Bar(), new Bar()};
    private static List<TargetHudProtocol.Show> targets = List.of(), preview = List.of();
    private static long receivedAt, previewUntil;
    private static long lastFrame;
    static final float SECOND_SCALE = .78f;
    /** Icons drawn this frame (GUI px), for the hover popup. */
    private static final List<StatusIcons.Hit> hits = new ArrayList<>();
    private static ClientWorld world;
    private static HudTextureCache images;
    private static CodexTypography text;
    private static int generation = -1;
    private static final long TIMEOUT_MS = 1600, FADE_MS = 140;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(AimPayload.ID, AimPayload.CODEC);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(TargetHudClient::sendAim);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            TargetHudProtocol.Frame f;
            try { f = TargetHudProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(f));
        });
        HudLayerRegistrationCallback.EVENT.register(drawer -> {
            drawer.attachLayerAfter(IdentifiedLayer.CROSSHAIR, Identifier.of("magiccodex", "target_hud"), TargetHudClient::render);
            // Owner 2026-10-19: vanilla effect icons (buffs too) are gone; debuffs show as 차카데미 icons top right.
            drawer.removeLayer(IdentifiedLayer.STATUS_EFFECTS);
            // Owner 2026-10-19: every boss bar is hidden; bosses show on this HUD instead.
            drawer.removeLayer(IdentifiedLayer.BOSS_BAR);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { reset(); close(); });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> { reset(); lastAim = null; });
    }

    private static void accept(TargetHudProtocol.Frame f) {
        sync();
        long now = Util.getMeasuringTimeMs();
        targets = f.targets();
        receivedAt = now;
    }

    private static void sync() {
        var c = MinecraftClient.getInstance();
        if (c.world != world) { reset(); world = c.world; }
    }
    private static void reset() { targets = List.of(); preview = List.of(); previewUntil = 0; for (Bar b : bars) { b.id = Integer.MIN_VALUE; b.health = b.mana = -1; } }
    static void close() {
        if (images != null) { images.close(); images = null; }
        if (text != null) { text.close(); text = null; }
    }

    private static List<TargetHudProtocol.Show> current(long now) {
        if (!preview.isEmpty() && now < previewUntil) return preview;
        if (now - receivedAt <= TIMEOUT_MS) return targets;
        return List.of();
    }

    private static String name(TargetHudProtocol.Show t) {
        if (!t.name().isBlank()) return t.name();
        if (!t.translationKey().isBlank()) return Text.translatable(t.translationKey()).getString();
        var c = MinecraftClient.getInstance();
        var e = c.world == null ? null : c.world.getEntityById(t.entityId());
        return e == null ? "" : e.getName().getString();
    }

    // ---------------------------------------------------------------- render

    private static void render(DrawContext ctx, RenderTickCounter ticks) {
        var c = MinecraftClient.getInstance();
        sync();
        if (c.player == null || c.world == null || c.options.hudHidden || !HudPointer.hudVisibleUnder(c.currentScreen)) return;
        long now = Util.getMeasuringTimeMs();
        var list = current(now);
        boolean self = !StatusClient.of(c.player.getId()).isEmpty() || ElementMarkClient.markOf(c.player.getId()) != null;
        if (list.isEmpty() && !self) { for (Bar b : bars) b.id = Integer.MIN_VALUE; lastFrame = now; return; }
        if (generation != UiResources.generation()) { close(); generation = UiResources.generation(); }
        if (images == null) images = new HudTextureCache(c);
        if (text == null) text = new CodexTypography(c);
        images.beginFrame(); text.beginFrame();
        hits.clear();
        try {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 120);
            int sw = c.getWindow().getScaledWidth();
            float w = width(sw);
            float y = 8;
            if (KyudichiClient.active()) y += 215 * KyudichiHud.scale(sw, c.getWindow().getScaledHeight());
            if(!list.isEmpty())try(var customization=HudCustomization.begin(ctx,"target",(sw-w)/2,y,w,(337*w/FRAME_W+6)*(list.size()>1?1.78f:1))){
            for (int i = 0; i < bars.length; i++) {
                Bar bar = bars[i];
                if (i >= list.size()) { bar.id = Integer.MIN_VALUE; continue; }
                var t = list.get(i);
                if (t.entityId() != bar.id) { bar.id = t.entityId(); bar.shownAt = now; bar.health = bar.mana = -1; }
                float alpha = Math.clamp((now - bar.shownAt) / (float) FADE_MS, 0, 1);
                float bw = i == 0 ? w : w * SECOND_SCALE;
                y = drawTarget(ctx, c, t, bar, now, alpha, bw, y) + 6;
            }
            }
            if (self) drawSelf(ctx, c, now);
            // Hover popup: only while the mouse pointer is out (Ctrl cursor / chat).
            if (HudPointer.active() && !hits.isEmpty()) {
                float mx = (float) HudPointer.x(), my = (float) HudPointer.y();
                for (var h : hits) {
                    float dx = mx - h.cx(), dy = my - h.cy();
                    if (dx * dx + dy * dy <= h.r() * h.r()) {
                        StatusIcons.tooltip(ctx, text, h, mx, my, c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight(), now);
                        break;
                    }
                }
            }
            ctx.getMatrices().pop();
        } finally {
            images.endFrame();
        }
        lastFrame = now;
    }

    /** Draws one bar whose name top is at `top` (GUI px); returns the GUI y of its bottom (below the icon row). */
    private static float drawTarget(DrawContext ctx, MinecraftClient c, TargetHudProtocol.Show t, Bar bar, long now, float alpha, float w, float top) {
        int sw = c.getWindow().getScaledWidth();
        float s = w / FRAME_W;
        float fx = (sw - w) / 2f, fy = top + (NAME_SIZE * .5f - NAME_Y) * s;
        boolean rare = t.shiny(), hasMana = t.hasMana();
        // Smooth gauges: ease toward the server value (~4 updates per second).
        float dt = Math.clamp((now - lastFrame) / 1000f, 0, .1f);
        bar.health = bar.health < 0 ? t.health() : bar.health + (t.health() - bar.health) * Math.min(1, dt * (t.health() > bar.health ? 14 : 9));
        if (hasMana) bar.mana = bar.mana < 0 ? t.mana() : bar.mana + (t.mana() - bar.mana) * Math.min(1, dt * 12);
        float fHp = Math.clamp(bar.health / t.maxHealth(), 0, 1);
        int a8 = Math.round(alpha * 255) << 24, white = a8 | 0xFFFFFF;
        float hpH = hasMana ? HP_H : HP_ONLY_H;
        float rowY = FRAME_H + BELOW;            // top of the icon row
        float iconY = rowY + ICON_D / 2;

        var m = ctx.getMatrices();
        m.push();
        try {
            m.translate(fx, fy, 0);
            m.scale(s, s, 1);
            // title divider
            int[] dsz = images.size(DIVIDER);
            if (dsz != null) {
                int dh = Math.round(DIVIDER_W * dsz[1] / (float) dsz[0]);
                images.drawStretched(ctx, DIVIDER, Math.round(FRAME_W / 2f - DIVIDER_W / 2), Math.round(DIVIDER_Y - dh / 2f), Math.round(DIVIDER_W), dh, white);
            }
            // back to front: dark tracks -> HP -> MP -> gold frame
            images.drawStretched(ctx, hasMana ? TRACK : TRACK_HP, 0, 0, FRAME_W, FRAME_H, white);
            String strip = rare ? HP_RARE : HP;
            int[] hsz = images.size(strip);
            // Clip (never squash) so the colors keep their place along the gauge.
            if (hsz != null && fHp > 0)
                images.drawPart(ctx, strip, Math.round(GAUGE_X), Math.round(HP_Y), Math.max(1, Math.round(GAUGE_W * fHp)), Math.round(hpH),
                    0, 0, Math.max(1, Math.round(hsz[0] * fHp)), hsz[1], white);
            if (hasMana) {
                int[] msz = images.size(MANA);
                float fm = Math.clamp(bar.mana / t.maxMana(), 0, 1);
                if (msz != null && fm > 0)
                    images.drawPart(ctx, MANA, Math.round(GAUGE_X), Math.round(MP_Y), Math.max(1, Math.round(GAUGE_W * fm)), Math.round(MP_H),
                        0, 0, Math.max(1, Math.round(msz[0] * fm)), msz[1], white);
            }
            images.drawStretched(ctx, hasMana ? FRAME : FRAME_HP, 0, 0, FRAME_W, FRAME_H, white);
            // taming odds heart
            if (t.tameChance() >= 0) images.drawCentered(ctx, HEART, HEART_X, rowY + HEART_SIZE / 2, Math.round(HEART_SIZE), white);
            // element mark (left) + statuses filled from the right
            var statuses = t.entityId() < 0 ? previewLines(now) : StatusClient.of(t.entityId());
            var mark = t.entityId() < 0 ? previewMark(now) : ElementMarkClient.markOf(t.entityId());
            int slot = 0;
            for (var line : statuses) {
                float ix = TargetHudLayout.iconX(ICON_RIGHT, ICON_D, ICON_STEP, slot++);
                StatusIcons.status(ctx, images, line, ix, iconY, ICON_D, now, alpha);
                rememberHit(new StatusIcons.Hit(fx + ix * s, fy + iconY * s, ICON_D / 2 * s, line, null));
            }
            if (mark != null) {
                float ix = TargetHudLayout.iconX(ICON_RIGHT, ICON_D, ICON_STEP, slot);
                StatusIcons.element(ctx, images, mark, ix, iconY, ICON_D, now, alpha);
                rememberHit(new StatusIcons.Hit(fx + ix * s, fy + iconY * s, ICON_D / 2 * s, null, mark));
            }
        } finally {
            m.pop();
        }

        // ---- text (GUI px): black stroke + colored fill, baked at device resolution
        String name = name(t);
        int nameColor = rare ? 0xD7C6FF : t.kind() == TargetHudProtocol.KIND_PLAYER ? 0xFFFFFF : 0xFBEFD6;
        float nameSize = NAME_SIZE * s, nameMax = 1500 * s;
        float nw = text.width(name, nameSize, TITLE);
        if (nw > nameMax) nameSize *= nameMax / nw;
        label(ctx, name, fx + FRAME_W / 2f * s, fy + NAME_Y * s, nameSize, a8 | nameColor, TITLE, true);
        if (t.damage() >= 0.5f) {
            String dmg = number(t.damage());
            float dSize = DAMAGE_SIZE * s;
            label(ctx, dmg, fx + (FRAME_W - 40) * s - text.width(dmg, dSize, TITLE), fy + DAMAGE_Y * s, dSize, a8 | 0xF4E4C2, TITLE, false);
        }
        // Numbers show the server value (the gauge eases); a new text sprite only when the value really changes.
        String hp = number(t.health()) + " / " + number(t.maxHealth());
        label(ctx, hp, fx + FRAME_W / 2f * s, fy + (HP_Y + hpH / 2) * s, Math.max(HP_TEXT, hpH * .9f) * s, a8 | 0xFFFFFF, TITLE, true);
        if (t.tameChance() >= 0) {
            double pct = t.tameChance() * 100;
            String odds = pct > 0 && pct < 10 ? String.format(Locale.ROOT, "%.1f%%", pct) : Math.round(pct) + "%";
            label(ctx, odds, fx + HEART_X * s, fy + (rowY + HEART_SIZE + 6 + PCT_SIZE / 2) * s, PCT_SIZE * s, a8 | 0xFFFFFF, TITLE, true);
        }
        // time left under each icon
        float timeY = fy + (rowY + ICON_D + 6 + TIME_SIZE / 2) * s;
        var statuses = t.entityId() < 0 ? previewLines(now) : StatusClient.of(t.entityId());
        var mark = t.entityId() < 0 ? previewMark(now) : ElementMarkClient.markOf(t.entityId());
        int slot = 0;
        for (var line : statuses) {
            String left = StatusIcons.timeLeft(line, now);
            float ix = TargetHudLayout.iconX(ICON_RIGHT, ICON_D, ICON_STEP, slot++);
            if (!left.isEmpty()) label(ctx, left, fx + ix * s, timeY, TIME_SIZE * s, a8 | 0xFFFFFF, TITLE, true);
        }
        if (mark != null) {
            float ix = TargetHudLayout.iconX(ICON_RIGHT, ICON_D, ICON_STEP, slot);
            label(ctx, StatusIcons.timeLeft(mark, now), fx + ix * s, timeY, TIME_SIZE * s, a8 | 0xFFFFFF, TITLE, true);
        }
        return fy + (FRAME_H + ROW_H) * s;
    }

    /** HUD text: black stroke first, then the colored fill on top (drawOutline alone only draws the stroke). */
    private static void label(DrawContext ctx, String value, float x, float y, float size, int color, Identifier face, boolean centered) {
        text.drawOutline(ctx, value, x, y, size, (color & 0xFF000000) | 0x000000, face, centered);
        text.draw(ctx, value, x, y, size, color, face, centered);
    }

    /** Own statuses: top right, oldest at the right edge, element mark left of them; wraps every 10. */
    private static void drawSelf(DrawContext ctx, MinecraftClient c, long now) {
        int sw = c.getWindow().getScaledWidth(), sh = c.getWindow().getScaledHeight();
        float ui = Math.clamp(Math.min(sw / 960f, sh / 540f), .8f, 1.4f), d = 22 * ui, step = d * 1.25f, ts = 8.5f * ui, rowH = d + ts + 6 * ui;
        var lines = StatusClient.of(c.player.getId());
        var mark = ElementMarkClient.markOf(c.player.getId());
        int n = lines.size() + (mark == null ? 0 : 1);
        try(var customization=HudCustomization.begin(ctx,"self-status",sw-6-d-(Math.min(10,n)-1)*step,6,d+(Math.min(10,n)-1)*step,Math.max(1,(n+9)/10)*rowH)){
        for (int i = 0; i < n; i++) {
            int row = i / 10, col = i % 10;
            float cx = sw - 6 - d / 2 - col * step, cy = 6 + d / 2 + row * rowH, ty = cy + d / 2 + 2 * ui + ts / 2;
            if (i < lines.size()) {
                StatusIcons.status(ctx, images, lines.get(i), cx, cy, d, now, 1); rememberHit(new StatusIcons.Hit(cx, cy, d / 2, lines.get(i), null));
                String left = StatusIcons.timeLeft(lines.get(i), now);
                if (!left.isEmpty()) label(ctx, left, cx, ty, ts, 0xFFFFFFFF, TITLE, true);
            } else {
                StatusIcons.element(ctx, images, mark, cx, cy, d, now, 1); rememberHit(new StatusIcons.Hit(cx, cy, d / 2, null, mark));
                label(ctx, StatusIcons.timeLeft(mark, now), cx, ty, ts, 0xFFFFFFFF, TITLE, true);
            }
        }

        }
}

    private static void rememberHit(StatusIcons.Hit hit){var p=HudCustomization.screenPoint(hit.cx(),hit.cy(),hit.r());hits.add(new StatusIcons.Hit(p[0],p[1],p[2],hit.line(),hit.mark()));}
    static String number(float v) {
        if (!Float.isFinite(v)) return "0";
        long r = Math.round(v);
        return r >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM", v / 1_000_000f) : String.valueOf(r);
    }

    // ---------------------------------------------------------------- preview (server /테스트 몹 체력바 → magiccodex:preview "targethud ...")

    /** normal · shiny · player · boss · off. False when unknown. */
    static boolean showPreview(String value) {
        switch (value) {
            case "normal" -> startPreview(0);
            case "shiny" -> startPreview(1);
            case "player" -> startPreview(2);
            case "boss" -> startPreview(3);
            case "off" -> { previewUntil = 0; preview = List.of(); }
            default -> { return false; }
        }
        return true;
    }

    private static long previewStart;
    private static void startPreview(int kind) {
        long now = Util.getMeasuringTimeMs();
        previewStart = now; previewUntil = now + 15000;
        var wolf = new TargetHudProtocol.Show(-1, new UUID(0, 3), TargetHudProtocol.KIND_VANILLA, 0, "달빛 늑대", "", 720, 1000, -1, 0, 280, .38f);
        preview = switch (kind) {
            case 1 -> List.of(new TargetHudProtocol.Show(-1, new UUID(0, 1), TargetHudProtocol.KIND_CUSTOM, TargetHudProtocol.FLAG_SHINY, "달빛 늑대", "",
                720, 1000, 160, 240, 280, .38f));
            case 2 -> List.of(new TargetHudProtocol.Show(-1, new UUID(0, 2), TargetHudProtocol.KIND_PLAYER, 0, "차카", "", 14, 20, 82, 120, 12, -1));
            case 3 -> List.of(new TargetHudProtocol.Show(-2, new UUID(0, 4), TargetHudProtocol.KIND_CUSTOM, TargetHudProtocol.FLAG_BOSS | TargetHudProtocol.FLAG_COMBAT,
                "고대의 수호자", "", 8400, 12000, 600, 900, 1530, .05f), wolf);
            default -> List.of(wolf);
        };
        for (Bar b : bars) b.id = Integer.MIN_VALUE;
        var c = MinecraftClient.getInstance();
        if (c.player != null) StatusClient.preview(c.player.getId(), List.of(
            new StatusClient.Line(StatusProtocol.Status.SLOW, now, now + 8000),
            new StatusClient.Line(StatusProtocol.Status.BURN, now, now + 12000),
            new StatusClient.Line(StatusProtocol.Status.MANA_BURN, now, now + 5000)));
    }
    private static List<StatusClient.Line> previewLines(long now) {
        var all = List.of(
            new StatusClient.Line(StatusProtocol.Status.POISON, previewStart, previewStart + 14000),
            new StatusClient.Line(StatusProtocol.Status.STUN, previewStart, previewStart + 6000),
            new StatusClient.Line(StatusProtocol.Status.BURN, previewStart, previewStart + 10000),
            new StatusClient.Line(StatusProtocol.Status.SLOW, previewStart, previewStart + 15000));
        var out = new ArrayList<StatusClient.Line>();
        for (var l : all) if (l.end() > now) out.add(l);
        return out;
    }
    private static ElementMarkClient.Shown previewMark(long now) {
        long start = previewStart + ((now - previewStart) / 3000) * 3000;
        return new ElementMarkClient.Shown(ElementProtocol.Element.WATER, start, start + 3000);
    }
}
