package school.magiccodex.client;

import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import school.magiccodex.protocol.ArtifactProtocol;

/**
 * 유물함 소환 (artifact summon) window, opened by the server (/아티팩트소환). [소환하기] asks the server to spend a key
 * item and roll; the answer arrives before any chest is picked, so the pick is purely presentational (the approved
 * reference: five identical chests; the picked chest's grade shows first in the row, the other four flash cosmetic
 * decoy grades, then the picked chest moves to the centre and opens).
 * All animation lives in {@link SummonScene}; this class draws it with the MagicCodex UI texture cache (additive for
 * light) and fonts, handles input and maps the scene's sound cues to vanilla sounds.
 */
final class ArtifactSummonScreen extends Screen {
    private static final Map<String, int[]> DIMS = Map.ofEntries(
            Map.entry("fx_smoke_0", new int[]{224, 210}), Map.entry("fx_smoke_1", new int[]{223, 186}),
            Map.entry("fx_smoke_2", new int[]{166, 254}), Map.entry("fx_smoke_3", new int[]{227, 184}),
            Map.entry("fx_ray", new int[]{228, 256}), Map.entry("fx_burst", new int[]{196, 202}),
            Map.entry("fx_glint", new int[]{256, 106}), Map.entry("fx_spark_multi", new int[]{230, 224}),
            Map.entry("fx_mote", new int[]{189, 189}), Map.entry("fx_spark4", new int[]{186, 188}),
            Map.entry("fx_ring_rune", new int[]{241, 237}), Map.entry("fx_glow_wide", new int[]{231, 231}),
            Map.entry("fx_ring", new int[]{228, 226}), Map.entry("fx_glow_core", new int[]{172, 170}),
            Map.entry("chest_closed", new int[]{499, 354}), Map.entry("chest_open", new int[]{488, 434}),
            Map.entry("frame_common", new int[]{384, 384}), Map.entry("frame_rare", new int[]{384, 384}),
            Map.entry("frame_unique", new int[]{384, 384}), Map.entry("frame_legendary", new int[]{384, 384}),
            Map.entry("frame_common_fill", new int[]{384, 384}), Map.entry("frame_rare_fill", new int[]{384, 384}),
            Map.entry("frame_unique_fill", new int[]{384, 384}), Map.entry("frame_legendary_fill", new int[]{384, 384}));
    private static final Identifier BACKGROUND = Identifier.of("magiccodex", "textures/gui/summon/background.png");
    private static final Identifier BUTTON = Identifier.of("magiccodex", "textures/gui/artifact/button.png");
    private static final Identifier PANEL = Identifier.of("magiccodex", "textures/gui/artifact/tooltip.png");
    private static final Identifier TITLE = Identifier.of("magiccodex", "title"), BOLD = Identifier.of("magiccodex", "hud_bold"),
            LABEL = Identifier.of("magiccodex", "label"), BODY = Identifier.of("magiccodex", "body");
    private static final int GOLD = 0xFFE7C77E, IVORY = 0xFFF3E9D3, TEXT = 0xFFE4E9EF, MUTED = 0xFF9AA8BB, WARN = 0xFFFF9E9E;

    record Box(float x, float y, float w, float h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
        float cx() { return x + w / 2; }
        float cy() { return y + h / 2; }
    }
    private static final Box SUMMON = new Box(626, 866, 200, 58), SUMMON5 = new Box(846, 866, 200, 58), CLOSE = new Box(1572, 30, 60, 60);
    private static final Box AGAIN = new Box(631, 806, 200, 58), CONFIRM = new Box(841, 806, 200, 58), CONFIRM_ONLY = new Box(736, 806, 200, 58);
    private static final Box INFO = new Box(386, 694, 900, 156);

    private final SummonScene scene = new SummonScene();
    private float scale = 1, ox, oy;
    private long deadline;
    private String notice = "", resultNotice = "";

    ArtifactSummonScreen() { super(Text.literal("유물함 소환")); }

    @Override protected void init() {
        ArtifactClient.query();
        ArtifactClient.summonInfo();
        scene.set(SummonScene.State.IDLE, Util.getMeasuringTimeMs());
    }

    @Override public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ server

    void onSummon(ArtifactProtocol.Summon m) {
        long now = Util.getMeasuringTimeMs();
        switch (m.kind()) {
            case ArtifactProtocol.SUMMON_RESULT -> {
                var entry = ArtifactClient.find(m.artifactId());
                scene.grade = m.grade(); scene.decoys = m.decoys().clone(); scene.isNew = m.isNew();
                scene.name = entry == null ? m.artifactId() : entry.name();
                scene.icon = entry == null ? "" : entry.icon();
                resultNotice = m.notice(); notice = "";
                scene.hover = -1; scene.picked = -1;
                scene.set(SummonScene.State.PICK, now);
            }
            case ArtifactProtocol.SUMMON_REFUSED -> {
                notice = m.notice();
                if (scene.state == SummonScene.State.WAITING) scene.set(SummonScene.State.IDLE, now);
                sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f);
            }
            default -> { }
        }
    }

    /** Five-pull result: all five chests drop in and open together. */
    void onSummonMulti(ArtifactProtocol.SummonMulti m) {
        long now = Util.getMeasuringTimeMs();
        var list = new SummonScene.Multi[m.pulls().size()];
        for (int i = 0; i < list.length; i++) {
            var x = m.pulls().get(i); var entry = ArtifactClient.find(x.artifactId());
            list[i] = new SummonScene.Multi(entry == null ? x.artifactId() : entry.name(), entry == null ? "" : entry.icon(), x.grade(), x.isNew());
        }
        scene.multi = list;
        resultNotice = m.notice(); notice = "";
        scene.set(SummonScene.State.MULTI, now);
    }

    private void startSummon() { start(1); }

    /** count 1 = 소환하기, 5 = 소환하기 ×5 (server decides everything; the client only checks the key count first). */
    private void start(int count) {
        long now = Util.getMeasuringTimeMs();
        if (ArtifactClient.summonKeys() < cost() * count) { notice = itemName() + " " + cost() * count + "개가 필요합니다."; sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f); return; }
        if (!(count == 1 ? ArtifactClient.summon() : ArtifactClient.summonMulti())) { notice = "서버에 연결되어 있지 않습니다."; return; }
        notice = "";
        deadline = now + 6000;
        scene.set(SummonScene.State.WAITING, now);
        sound(SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1f, .7f);
    }

    /** OPEN/STATE notice is "<item name>\u0001<cost>\u0001<grade chances>". */
    private String[] info() { String[] p = ArtifactClient.summonRates().split("\u0001", -1); return p.length == 3 ? p : new String[]{"유물함 열쇠", "1", ""}; }
    private String itemName() { return info()[0]; }
    private int cost() { try { return Math.max(1, Integer.parseInt(info()[1])); } catch (NumberFormatException e) { return 1; } }

    @Override public void tick() {
        if (client == null || client.player == null || !client.player.isAlive()) { close(); return; }
        long now = Util.getMeasuringTimeMs();
        if (scene.state == SummonScene.State.WAITING && now > deadline) {
            notice = "서버 응답이 늦습니다. 결과는 아티팩트 도감에서 확인해 주세요.";
            scene.set(SummonScene.State.IDLE, now); ArtifactClient.summonInfo();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        var images = UiResources.images(); images.beginFrame(); UiResources.text().beginFrame();
        try {
            // background covers the window (drawn by the scene through Canvas.background so the camera moves it);
            // everything else lives in the 1672x941 reference space, fully visible
            c.fill(0, 0, width, height, 0xFF03060C);
            scale = Math.min(width / SummonScene.W, height / SummonScene.H);
            ox = (width - SummonScene.W * scale) / 2; oy = (height - SummonScene.H * scale) / 2;
            double mx = (mouseX - ox) / scale, my = (mouseY - oy) / scale;
            var m = c.getMatrices(); m.push();
            try {
                m.translate(ox, oy, 0); m.scale(scale, scale, 1);
                if (scene.state == SummonScene.State.PICK) {
                    int over = scene.chestAt(mx, my);
                    if (over != scene.hover && over >= 0) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, .45f);
                    scene.hover = over;
                }
                scene.draw(canvas(c), now, now);
                var cues = scene.cues(now);
                for (int i = 0; i < cues.size(); i++) play(cues.get(i), scene.cueIndex(i));
                overlay(c, mx, my, now);
            } finally { m.pop(); }
        } finally { images.endFrame(); }
    }

    private void overlay(DrawContext c, double mx, double my, long now) {
        var s = scene.state;
        text(c, "유물함 소환", 836, 62, 42, IVORY, TITLE);
        closeButton(c, mx, my);
        if (s == SummonScene.State.IDLE || s == SummonScene.State.WAITING) {
            if (s == SummonScene.State.IDLE) text(c, "다섯 유물함 가운데 하나를 열어 아티팩트를 얻습니다", 836, 112, 21, GOLD, BODY);
            nine(c, PANEL, 992, 396, 9, 93, 981, 294, 45, INFO, 0xF0FFFFFF);
            String[] info = info();
            text(c, "[소환하기]는 " + info[0] + " " + info[1] + "개, [소환하기 ×5]는 " + cost() * 5 + "개를 사용합니다", 836, 726, 23, GOLD, BOLD);
            text(c, "한 번에 무작위 아티팩트 1개(×5는 5개)를 얻습니다. 이미 가진 아티팩트가 나오면", 836, 764, 19, TEXT, BODY);
            text(c, "아티팩트 조각으로 바뀌어 우편으로 지급됩니다.", 836, 792, 19, TEXT, BODY);
            text(c, info[2].isEmpty() ? "" : "등급 확률  " + info[2], 836, 828, 17, MUTED, LABEL);
            boolean ready = s == SummonScene.State.IDLE && ArtifactClient.summonKeys() >= cost();
            boolean ready5 = s == SummonScene.State.IDLE && ArtifactClient.summonKeys() >= cost() * 5;
            button(c, SUMMON, s == SummonScene.State.WAITING ? "소환 중…" : "소환하기", ready, mx, my);
            button(c, SUMMON5, "소환하기 ×5", ready5, mx, my);
            textLeft(c, "보유 " + info[0] + "  " + ArtifactClient.summonKeys() + "개", SUMMON5.x() + SUMMON5.w() + 26, SUMMON5.cy(), 20,
                    ArtifactClient.summonKeys() >= cost() ? IVORY : WARN, BOLD);
            if (!notice.isEmpty()) text(c, notice, 836, 666, 20, WARN, BOLD);
        } else if (s == SummonScene.State.MULTI && scene.multiReady(now)) {
            if (!resultNotice.isEmpty()) text(c, resultNotice, 836, 766, 19, MUTED, BODY);
            if (ArtifactClient.summonKeys() >= cost() * 5) {
                button(c, AGAIN, "다시 ×5", true, mx, my);
                button(c, CONFIRM, "확인", true, mx, my);
            } else button(c, CONFIRM_ONLY, "확인", true, mx, my);
        } else if (s == SummonScene.State.CARD) {
            if (!resultNotice.isEmpty()) text(c, resultNotice, 836, 766, 19, MUTED, BODY);
            if (ArtifactClient.summonKeys() >= cost()) {
                button(c, AGAIN, "다시 소환", true, mx, my);
                button(c, CONFIRM, "확인", true, mx, my);
            } else button(c, CONFIRM_ONLY, "확인", true, mx, my);
        }
    }

    private SummonScene.Canvas canvas(DrawContext c) {
        var images = UiResources.images();
        return new SummonScene.Canvas() {
            @Override public void background(float zoom, float dx, float dy) {
                // reference space is centred in the window, so cover-size the image about the reference centre
                float cover = Math.max(width / SummonScene.W, height / SummonScene.H) / scale * zoom;
                float bw = SummonScene.W * cover, bh = SummonScene.H * cover;
                var m = c.getMatrices(); m.push();
                try {
                    m.translate(SummonScene.W / 2 + dx - bw / 2, SummonScene.H / 2 + dy - bh / 2, 0);
                    m.scale(bw / 1672f, bh / 941f, 1);
                    images.drawTexture(c, BACKGROUND, 0, 0, 0, 0, 1672, 941, 1672, 941, 1672, 941);
                } finally { m.pop(); }
            }
            @Override public void sprite(String name, float cx, float cy, float w, float h, float rot, float squash, int argb, boolean additive) {
                int[] d = DIMS.get(name);
                if (d == null || w < .5f || h < .5f || (argb >>> 24) == 0) return;
                var m = c.getMatrices(); m.push();
                try {
                    m.translate(cx, cy, 0);
                    if (squash != 1) m.scale(1, squash, 1);
                    if (rot != 0) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rot));
                    m.scale(w / d[0], h / d[1], 1);
                    var id = Identifier.of("magiccodex", "textures/gui/summon/" + name + ".png");
                    if (additive) images.drawTextureAdditive(c, id, -d[0] / 2, -d[1] / 2, 0, 0, d[0], d[1], d[0], d[1], d[0], d[1], argb);
                    else images.drawTexture(c, id, -d[0] / 2, -d[1] / 2, 0, 0, d[0], d[1], d[0], d[1], d[0], d[1], argb);
                } finally { m.pop(); }
            }
            @Override public void fillScreen(int argb) { c.fill(-4000, -4000, 6000, 5000, argb); }
            @Override public void rect(float cx, float cy, float w, float h, int argb) {
                c.fill(Math.round(cx - w / 2), Math.round(cy - h / 2), Math.round(cx + w / 2), Math.round(cy + h / 2), argb);
            }
            @Override public void text(String value, float x, float y, float size, int argb, SummonScene.Face face, boolean centered) {
                if (value == null || value.isBlank() || (argb >>> 24) < 4) return;
                UiResources.text().draw(c, value, x, y, size, argb, switch (face) { case TITLE -> TITLE; case BOLD -> BOLD; case LABEL -> LABEL; case BODY -> BODY; }, centered);
            }
            @Override public void icon(String path, float cx, float cy, float size, int argb) {
                if (path != null && !path.isBlank() && size >= 2) images.drawCentered(c, path, cx, cy, Math.round(size), argb);
            }
        };
    }

    // ------------------------------------------------------------------ sounds

    private void play(SummonScene.Cue cue, int index) {
        switch (cue) {
            case GATHER -> sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1.4f, .45f);
            case RING -> sound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), .8f + .2f * index, .5f);
            case SHIFT -> sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.1f, .6f);
            case BLACKOUT -> sound(SoundEvents.BLOCK_BEACON_DEACTIVATE, .6f, .7f);
            case METEOR -> sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, .6f, .8f);
            case IMPACT -> sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, .8f, .8f);
            case GRADE -> {
                switch (index) {
                    case 0 -> sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, .5f);
                    case 1 -> sound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.1f, .7f);
                    case 2 -> sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f, .5f);
                    default -> sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.1f, .5f);
                }
            }
            case DECOY -> sound(index == 3 ? SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK : SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, .9f + .2f * index, .35f + .07f * index);
            case MOVE -> sound(SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.2f, .45f);
            case BEAT -> sound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), .7f + .15f * index, .6f);
            case OPEN -> {
                sound(SoundEvents.BLOCK_CHEST_OPEN, 1.1f, .6f);
                switch (index) {
                    case 0 -> sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, .6f);
                    case 1 -> sound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.1f, .8f);
                    case 2 -> sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, .6f);
                    default -> { sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, .6f); sound(SoundEvents.ITEM_TOTEM_USE, 1.2f, .35f); }
                }
            }
            case STAR -> sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f + .12f * index, .45f);
            case CARD -> sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, .8f);
            case LAND -> {   // five-pull: one thump per chest, brighter for higher grades
                sound(SoundEvents.BLOCK_WOOD_PLACE, .8f, .6f);
                switch (index) {
                    case 0 -> sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), .9f, .35f);
                    case 1 -> sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, .6f);
                    case 2 -> { sound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, .7f); sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.3f, .35f); }
                    default -> { sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, .9f, .6f); sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.4f, .5f); }
                }
            }
            case CHARGE -> sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1.2f + .1f * index, .55f);
        }
    }

    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double mx = (mouseX - ox) / scale, my = (mouseY - oy) / scale;
        long now = Util.getMeasuringTimeMs();
        if (CLOSE.contains(mx, my)) { click(); close(); return true; }
        switch (scene.state) {
            case IDLE -> { if (SUMMON.contains(mx, my)) startSummon(); else if (SUMMON5.contains(mx, my)) start(5); }
            case MULTI -> {
                if (!scene.multiReady(now)) { scene.skip(now); break; }
                boolean again = ArtifactClient.summonKeys() >= cost() * 5;
                if (again && AGAIN.contains(mx, my)) { click(); start(5); }
                else if ((again ? CONFIRM : CONFIRM_ONLY).contains(mx, my)) { click(); scene.set(SummonScene.State.IDLE, now); ArtifactClient.summonInfo(); }
            }
            case PICK -> {
                int i = scene.chestAt(mx, my);
                if (i >= 0) {
                    scene.picked = i;
                    scene.set(SummonScene.State.OPENING, now);
                    sound(SoundEvents.UI_BUTTON_CLICK.value(), 1f, .4f);
                    sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.3f, .5f);
                }
            }
            case OPENING -> {
                if (scene.continueReady(now)) scene.set(SummonScene.State.CARD, now);
                else scene.skip(now);
            }
            case CARD -> {
                boolean again = ArtifactClient.summonKeys() >= cost();
                if (again && AGAIN.contains(mx, my)) { click(); startSummon(); }
                else if ((again ? CONFIRM : CONFIRM_ONLY).contains(mx, my)) { click(); scene.set(SummonScene.State.IDLE, now); ArtifactClient.summonInfo(); }
            }
            default -> { }
        }
        return true;
    }

    private void click() { sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f); }

    // ------------------------------------------------------------------ pieces (artifact codex UI parts)

    private void closeButton(DrawContext c, double mx, double my) {
        boolean over = CLOSE.contains(mx, my);
        float x = CLOSE.cx(), y = CLOSE.cy(), r = 13;
        if (over) HudMesh.capsule(c, CLOSE.x() + 6, CLOSE.y() + 6, CLOSE.w() - 12, CLOSE.h() - 12, 0x33FFFFFF, 0x33FFFFFF);
        HudMesh.line(c, x - r, y - r, x + r, y + r, 3, over ? 0xFFFFFFFF : 0xFFD8C28C);
        HudMesh.line(c, x - r, y + r, x + r, y - r, 3, over ? 0xFFFFFFFF : 0xFFD8C28C);
    }

    private void button(DrawContext c, Box r, String label, boolean enabled, double mx, double my) {
        boolean over = enabled && r.contains(mx, my);
        nine(c, BUTTON, 1086, 362, 29, 78, 1057, 282, 100, r, enabled ? over ? 0xFFFFFFFF : 0xFFE2E6EC : 0xFF7D8796);
        if (over) c.fill(Math.round(r.x() + 10), Math.round(r.y() + 8), Math.round(r.x() + r.w() - 10), Math.round(r.y() + r.h() - 8), 0x18FFFFFF);
        text(c, label, r.cx(), r.cy(), 25, enabled ? 0xFFF1E3C2 : 0xFF8A97A8, BOLD);
    }

    private static void text(DrawContext c, String value, float x, float y, float size, int color, Identifier face) {
        if (value != null && !value.isBlank()) UiResources.text().draw(c, value, x, y, size, color, face, true);
    }
    private static void textLeft(DrawContext c, String value, float x, float y, float size, int color, Identifier face) {
        if (value != null && !value.isBlank()) UiResources.text().draw(c, value, x, y, size, color, face, false);
    }

    /** Nine-slice, same rule as the artifact codex (frame border box lands on the box, corners keep their scale). 뽑기 상자 결과 창도 같이 쓴다. */
    static void nine(DrawContext c, Identifier texture, int tw, int th, int fl, int ft, int fr, int fb, int corner, Box cell, int tint) {
        float s = cell.h() / (fb - ft);
        int[] sx = {0, fl + corner, fr - corner, tw};
        int[] sy = {0, ft + corner, fb - corner, th};
        float x0 = cell.x() - fl * s, y0 = cell.y() - ft * s;
        float x3 = cell.x() + cell.w() + (tw - fr) * s, y3 = cell.y() + cell.h() + (th - fb) * s;
        float[] dx = {x0, x0 + sx[1] * s, x3 - (tw - sx[2]) * s, x3};
        float[] dy = {y0, y0 + sy[1] * s, y3 - (th - sy[2]) * s, y3};
        if (dx[2] < dx[1]) { float mid = (dx[1] + dx[2]) / 2; dx[1] = dx[2] = mid; }
        if (dy[2] < dy[1]) { float mid = (dy[1] + dy[2]) / 2; dy[1] = dy[2] = mid; }
        var images = UiResources.images();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int u = sx[col], v = sy[row], sw = sx[col + 1] - u, sh = sy[row + 1] - v;
            int x = Math.round(dx[col]), y = Math.round(dy[row]);
            int w = Math.round(dx[col + 1]) - x, h = Math.round(dy[row + 1]) - y;
            if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) continue;
            images.drawTexture(c, texture, x, y, u, v, w, h, sw, sh, tw, th, tint);
        }
    }
}
