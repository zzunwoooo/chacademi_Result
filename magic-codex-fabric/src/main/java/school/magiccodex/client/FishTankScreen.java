package school.magiccodex.client;

import java.text.SimpleDateFormat;
import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.FishTankProtocol;
import school.magiccodex.protocol.FishTankProtocol.*;

/**
 * 아공간 수조 (2026-10-20, owner spec + GPT's approved tank references and art in textures/gui/fish_tank).
 *
 * Fullscreen water: the caught fish swim freely (depth, smooth turns, tail wobble). Sunlight comes from above: the
 * light rays sway and flicker and two caustic layers move over the sand and under the surface like real water.
 * Clicking a fish (or a list row) zooms the camera onto it smoothly (eased progress + smoothed focus, the fish slows,
 * turns and comes closer to about 380px) while the background edge blurs through a radial mask (pre-blurred copy
 * under the sharp one) and the info panel slides in: name, grade, size, caught date, sale price,
 * description, and 아이템화 / 방생 (press twice) / 즉시 판매. The fish swims to a spot the clamped camera can reach;
 * a fish on the right half gets the panel mirrored to its left (side changes fade out and back in).
 * Every 6-14 s a quiet bubble sound plays and a few bubbles rise from the sand.
 * The right list (collapsible) has search and 최근 / 등급 / 크기 / 넣기. 넣기 lists the fish items in the inventory
 * (owner: no inventory band at the bottom); clicking one opens a popup with 수조에 넣기.
 * The server decides everything (FishTankBridge); this screen only shows and asks.
 */
final class FishTankScreen extends Screen {
    static final String ROOT = "textures/gui/fish_tank/";
    static final Identifier SECTION = Identifier.of("magiccodex", "section"), BODY = Identifier.of("magiccodex", "body"),
            LABEL = Identifier.of("magiccodex", "label");
    static final int WHITE = 0xFFFFFFFF, TEXT = 0xFFDCE4EE, MUTED = 0xFFB3C1D2, DIM = 0xFF7F8A99, GOLD = 0xFFE9C878, WARN = 0xFFFF9E9E;
    /** Grade colours on the tank panels (common = grey, advanced = mint sky, unique = violet, legendary = pale yellow). */
    static final int[] GRADE = {0xFFC9D2DC, 0xFF8FE3E8, 0xFFC9A3FF, 0xFFFFE08A};
    static final String[] GRADES = {"일반", "고급", "유일", "전설"};
    static final String[] PANELS = {"info_panel_common", "info_panel_advanced", "info_panel_unique", "info_panel_legendary"};
    static final String[] TABS = {"최근", "등급", "크기", "넣기"};
    static final int TAB_INSERT = 3;
    private static final Identifier BASE = tex("background_base"), BLUR = tex("background_blur"), FRONT = tex("background_foreground"),
            CAUSTICS = tex("caustics_tiled"), RAYS = tex("light_rays"), LOCK = Identifier.of("magiccodex", "textures/gui/fish_shop/lock_closed_gold.png");
    private static Identifier tex(String name) { return Identifier.of("magiccodex", ROOT + name + ".png"); }

    // ---- state from the server
    private int capacity = 100;
    private boolean inventoryFull, loaded;
    private final Map<String, Fish> fish = new LinkedHashMap<>();
    private List<Held> inventory = List.of();
    private final Map<String, Info> infos = new HashMap<>();

    // ---- swimming
    private static final class Swimmer {
        final String id; Fish data; Info info;
        float x, y, vx, vy, z, tz, face = 1, faceTarget = 1, tx, ty, retarget, speed, phase, length, alpha, pres = 1;
        float leave = -1; int leaveKind;     // leaving animation (release / itemize / sell), seconds since start
        float drop;                          // dropped in from the surface (put back), seconds left
        boolean placed, initial;             // placed once the view size is known
        Swimmer(String id) { this.id = id; }
    }
    private final Map<String, Swimmer> swimmers = new LinkedHashMap<>();
    private final Random random = new Random();
    private long lastFrame;
    private float time;
    private final float[][] motes = new float[46][5];   // x, y, size, speed, phase in 0..1 view units
    private final List<float[]> bubbles = new ArrayList<>();
    private float nextBubble = -1;                      // time of the next ambient bubble burst

    // ---- camera / selection
    private String selected, hovered;
    private float progress;            // 0 = whole tank, 1 = zoomed on the selected fish
    private float focusX, focusY, zoomTarget = 1.6f, zoomSmooth = 1.6f, presTarget = 1;
    private float list = 0;            // right list slide 0..1
    private boolean listOpen;
    private float camX, camY, zoom = 1, viewCx, viewCy, maskX, maskY;
    // panel side: 1 = panel right of the fish (fish at the left focus point), -1 = mirrored (fish on the right part)
    private int side = 1, sideShown = 1;
    private float sideFade = 1, focusSide = 1;   // panel fade while changing sides, smoothed focus side for the camera
    private float l0, t0, r0, b0;      // visible bounds in layout units

    // ---- UI
    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private int tab;
    private int scroll;
    private TextFieldWidget search;
    private String query = "", notice = "";
    private long noticeAt, pendingOp, pendingAt;
    private final FishReleaseConfirmation releaseConfirmation=new FishReleaseConfirmation();
    private String pendingFish = "";
    private int pendingAction = -1;
    private Held popup;
    private long popupAt;
    private long deselectAt;
    private boolean mouseDown;

    FishTankScreen() { super(Text.literal("아공간 수조")); }

    @Override protected void init() {
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 20, Text.literal("물고기 이름 검색"));
        search.setMaxLength(16); search.setText(query); search.setDrawsBackground(false);
        search.setChangedListener(v -> { query = v; scroll = 0; });
        for (float[] m : motes) seedMote(m, true);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}
    @Override public void removed() { releaseConfirmation.reset();popup=null;pendingOp=0;FishTankClient.close(); }

    // ------------------------------------------------------------------ server

    void onState(State s) {
        capacity = s.capacity(); inventoryFull = s.inventoryFull(); inventory = s.inventory();
        infos.clear(); for (Info i : s.species()) infos.put(i.id(), i);
        boolean first = !loaded; loaded = true;
        fish.clear(); for (Fish f : s.fish()) fish.put(f.id(), f);
        for (Fish f : s.fish()) {
            var sw = swimmers.get(f.id());
            if (sw == null) { sw = spawn(f, first); swimmers.put(f.id(), sw); }
            sw.data = f; sw.info = infos.get(f.fish());
        }
        // sizes: species base size + rank within that species among the fish in the tank (ranges change as fish come and go)
        Map<String, float[]> range = new HashMap<>();
        for (Fish f : s.fish()) range.merge(f.fish(), new float[]{f.size(), f.size()}, (a, b) -> new float[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        for (Fish f : s.fish()) { var sw = swimmers.get(f.id()); float[] r = range.get(f.fish()); if (sw != null && r != null) sw.length = FishTankUiMath.length(f.size(), r[0], r[1]); }
        for (var sw : swimmers.values()) if (!fish.containsKey(sw.id) && sw.leave < 0) { sw.leave = 0; sw.leaveKind = FishTankProtocol.QUERY; }
        if (selected != null && !fish.containsKey(selected) && deselectAt == 0) deselect();
        if (popup != null && inventory.stream().noneMatch(h -> h.slot() == popup.slot() && h.id().equals(popup.id()))) popup = null;
        if (!s.notice().isBlank()) say(s.notice());
    }
    void onResult(Result r) {
        if (r.operation() != pendingOp) return;
        pendingOp = 0; pendingAction = -1;
        say(r.notice());
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(r.ok() ? SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), r.ok() ? 1.3f : .7f, .45f));
        if (!r.ok()) return;
        if (r.action() == FishTankProtocol.INSERT) { popup = null; return; }
        var sw = swimmers.get(r.tankId());
        if (sw != null && sw.leave < 0) { sw.leave = 0; sw.leaveKind = r.action(); }
        if (r.tankId().equals(selected)) deselectAt = Util.getMeasuringTimeMs() + 450;
    }
    private void say(String s) { notice = s; noticeAt = Util.getMeasuringTimeMs(); }
    private boolean busy() { return pendingOp != 0; }
    private void ask(long op, int action, String id) {
        if (op == 0) { say("서버에 연결되어 있지 않아요."); return; }
        pendingOp = op; pendingAction = action; pendingFish = id; pendingAt = Util.getMeasuringTimeMs();
    }

    @Override public void tick() {
        if (busy() && Util.getMeasuringTimeMs() - pendingAt > 10000) { pendingOp = 0; pendingAction = -1; say("서버 응답이 늦어요. 다시 시도해 주세요."); FishTankClient.query(); }
    }

    // ------------------------------------------------------------------ swimming

    private float areaLeft() { return l0 + 50; }
    /** Fish keep out from under the open list (they turn away softly, never jump). */
    private float areaRight() { return listOpen ? Math.max(areaLeft() + 300, r0 - 400) : r0 - 50; }
    private float areaTop() { return t0 + (b0 - t0) * .14f; }
    private float areaBottom() { return t0 + (b0 - t0) * .77f; }

    private Swimmer spawn(Fish f, boolean initial) {
        var s = new Swimmer(f.id());
        s.data = f; s.info = infos.get(f.fish());
        int shape = s.info == null ? 0 : s.info.shape();
        s.length = FishTankUiMath.length(f.size(), f.size(), f.size()); // refined in onState once the species range is known
        s.speed = (24 + random.nextFloat() * 22) * switch (shape) { case 1 -> .8f; case 2 -> .6f; case 3 -> .7f; case 4 -> .55f; default -> 1f; };
        s.phase = random.nextFloat() * 6.283f;
        s.z = s.tz = random.nextFloat();
        s.face = s.faceTarget = random.nextBoolean() ? 1 : -1;
        s.vx = s.face * s.speed * .5f;
        s.initial = initial;
        if (r0 > l0) place(s);
        return s;
    }
    private void place(Swimmer s) {
        s.placed = true;
        float w = Math.max(200, areaRight() - areaLeft()), h = Math.max(120, areaBottom() - areaTop());
        s.x = areaLeft() + random.nextFloat() * w; s.y = areaTop() + random.nextFloat() * h;
        if (shape(s.data.fish()) == 3) s.y = areaTop() + h * (.7f + random.nextFloat() * .3f);
        if (!s.initial) { s.drop = 4; s.y = t0 - 40; s.vy = 220; s.vx = 0; }
        retarget(s);
    }
    private void retarget(Swimmer s) {
        float w = Math.max(200, areaRight() - areaLeft()), h = Math.max(120, areaBottom() - areaTop());
        int shape = s.info == null ? 0 : s.info.shape();
        s.tx = areaLeft() + random.nextFloat() * w;
        s.ty = shape == 3 ? areaTop() + h * (.72f + random.nextFloat() * .28f)
                : Math.clamp(s.y + (random.nextFloat() - .5f) * 260, areaTop(), areaBottom());
        s.tz = random.nextFloat();
        s.retarget = 4 + random.nextFloat() * 6;
    }

    private void update(float dt) {
        for (var it = swimmers.values().iterator(); it.hasNext(); ) {
            var s = it.next();
            if (!s.placed) { if (r0 > l0) place(s); else continue; }
            if (s.leave >= 0) {
                s.leave += dt;
                switch (s.leaveKind) {
                    case FishTankProtocol.RELEASE -> { s.vx += s.face * 260 * dt; s.vy -= 90 * dt; s.x += s.vx * dt; s.y += s.vy * dt; }
                    default -> { s.vx *= (float) Math.exp(-dt * 3); s.vy -= 20 * dt; s.x += s.vx * dt; s.y += s.vy * dt; }
                }
                if (s.leave > 1.1f) { it.remove(); if (s.id.equals(hovered)) hovered = null; }
                continue;
            }
            s.alpha = Math.min(1, s.alpha + dt * 1.6f);
            boolean focused = s.id.equals(selected) && progress > 0;
            int shape = s.info == null ? 0 : s.info.shape();
            if (s.drop > 0 && s.y < areaTop() + 40) {
                // put back: it sinks in from the surface and slows down in the water
                s.drop -= dt; if (s.drop <= 0) s.y = Math.max(s.y, areaTop());
                s.vy += (60 - s.vy) * Math.min(1, dt * 1.2f); s.vx *= (float) Math.exp(-dt);
            } else if (focused) {
                // swims gently to a spot the camera can bring to the focus point, then rests facing the panel
                float[] p = comfort(s);
                float max = Math.max(60, s.speed * 1.8f);
                float dvx = Math.clamp((p[0] - s.x) * 1.4f, -max, max), dvy = Math.clamp((p[1] - s.y) * 1.4f, -max * .6f, max * .6f);
                float a = Math.min(1, dt * 2.2f);
                s.vx += (dvx - s.vx) * a; s.vy += (dvy - s.vy) * a;
                s.faceTarget = Math.abs(s.vx) > (s.faceTarget == side ? 18 : 8) ? Math.signum(s.vx) : side;
                s.z += (1 - s.z) * Math.min(1, dt * 2.5f);
            } else {
                s.retarget -= dt;
                float dx = s.tx - s.x, dy = s.ty - s.y, d = (float) Math.sqrt(dx * dx + dy * dy);
                if (s.retarget <= 0 || d < 24) { retarget(s); dx = s.tx - s.x; dy = s.ty - s.y; d = Math.max(1, (float) Math.sqrt(dx * dx + dy * dy)); }
                float speed = s.speed;
                if (shape == 4) speed *= .35f + 1.4f * (float) Math.pow(Math.max(0, Math.sin(time * 2.1 + s.phase)), 3);
                float dvx = dx / d * speed, dvy = dy / d * speed * .45f;
                float a = Math.min(1, dt * .9f);
                s.vx += (dvx - s.vx) * a; s.vy += (dvy - s.vy) * a;
                s.z += (s.tz - s.z) * Math.min(1, dt * .12f);
                if (s.vx > 5) s.faceTarget = 1; else if (s.vx < -5) s.faceTarget = -1;
            }
            s.x += s.vx * dt; s.y += s.vy * dt;
            if (s.drop > 0 && s.y >= areaTop() + 40) s.drop = 0;
            if (!focused && s.drop <= 0) {
                if (s.x < areaLeft()) { s.x = areaLeft(); s.vx = Math.abs(s.vx); s.tx = Math.max(s.tx, s.x + 120); }
                if (s.x > areaRight()) {
                    if (s.x > r0 - 50) { s.x = r0 - 50; s.vx = -Math.abs(s.vx); }
                    else s.vx += (-s.speed - s.vx) * Math.min(1, dt * 1.5f);
                    if (s.tx > areaRight()) s.tx = areaRight() - 60 - random.nextFloat() * 200;
                }
                s.y = Math.clamp(s.y, areaTop(), areaBottom());
            }
            s.face += (s.faceTarget - s.face) * Math.min(1, dt * 3.2f);
            float presGoal = focused || s.id.equals(selected) ? 1 + (presTarget - 1) * ease(progress) : 1;
            s.pres += (presGoal - s.pres) * Math.min(1, dt * 5);
        }
        // motes drift up slowly with the water
        for (float[] m : motes) { m[1] -= m[3] * dt; m[0] += (float) Math.sin(time * .6 + m[4] * 6.283) * .004f * dt; if (m[1] < -.05f) seedMote(m, false); }
        ambientBubbles(dt);
    }

    /**
     * Ambient bubbles (owner): every 6-14 s a quiet underwater bubble sound and, at the same moment, a few small
     * bubbles rise from the sand at a random x. Driven by render, so it stops with the screen.
     */
    private void ambientBubbles(float dt) {
        if (nextBubble < 0) nextBubble = time + 3 + random.nextFloat() * 4;
        if (loaded && r0 > l0 && time >= nextBubble) {
            nextBubble = time + 6 + random.nextFloat() * 8;
            float x = areaLeft() + 40 + random.nextFloat() * Math.max(100, areaRight() - areaLeft() - 80);
            float y = t0 + (b0 - t0) * (.80f + random.nextFloat() * .06f);
            int n = 3 + random.nextInt(4);
            for (int i = 0; i < n; i++)   // x, y, radius, rise speed, phase, wobble, age (a little staggered)
                bubbles.add(new float[]{x + (random.nextFloat() - .5f) * 22, y + random.nextFloat() * 10, 2.2f + random.nextFloat() * 3.4f,
                        55 + random.nextFloat() * 45, random.nextFloat() * 6.283f, 2 + random.nextFloat() * 4, -i * (.08f + random.nextFloat() * .14f)});
            if (client != null) {
                var sounds = client.getSoundManager();
                sounds.play(PositionedSoundInstance.master(SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, .8f + random.nextFloat() * .5f, .10f + random.nextFloat() * .08f));
                if (random.nextFloat() < .3f)
                    sounds.play(PositionedSoundInstance.master(SoundEvents.AMBIENT_UNDERWATER_LOOP_ADDITIONS, .9f + random.nextFloat() * .25f, .12f));
            }
        }
        for (var it = bubbles.iterator(); it.hasNext(); ) {
            float[] b = it.next();
            b[6] += dt;
            if (b[6] <= 0) continue;
            b[1] -= b[3] * dt;
            b[3] = Math.min(b[3] + 14 * dt, 130);   // they speed up a little on the way up
            if (b[1] < t0 + (b0 - t0) * .08f) it.remove();
        }
    }
    private void drawBubbles(DrawContext c) {
        float top = t0 + (b0 - t0) * .08f;
        for (float[] b : bubbles) {
            if (b[6] <= 0) continue;
            float al = Math.min(1, b[6] / .25f) * smooth(top, top + 140, b[1]);
            if (al <= .01f) continue;
            float x = b[0] + (float) Math.sin(b[6] * 4.2 + b[4]) * b[5], r = b[2];
            HudMesh.disk(c, x, b[1], r, FishingArt.argb(.34f * al, 0xDFF6FF));                       // light rim
            HudMesh.disk(c, x, b[1], r * .72f, FishingArt.argb(.22f * al, 0x0B2A4A));                 // clear middle
            HudMesh.disk(c, x - r * .34f, b[1] - r * .34f, Math.max(.6f, r * .26f), FishingArt.argb(.75f * al, 0xFFFFFF));   // highlight
        }
    }
    private void seedMote(float[] m, boolean anywhere) {
        m[0] = random.nextFloat(); m[1] = anywhere ? random.nextFloat() : 1.05f; m[2] = .8f + random.nextFloat() * 1.8f;
        m[3] = .006f + random.nextFloat() * .014f; m[4] = random.nextFloat();
    }
    private float scaleOf(float z) { return FishTankUiMath.depthScale(z); }

    // ------------------------------------------------------------------ selection

    private void select(String id) {
        var s = swimmers.get(id);
        if (s == null || s.leave >= 0) return;
        boolean fresh = FishTankUiMath.fresh(progress,selected);
        if (fresh) { focusX = s.x; focusY = s.y; }
        selected = id; deselectAt = 0; releaseConfirmation.reset();
        float shown = s.length;                            // at depth 1, before the presentation scale
        var magnification=FishTankUiMath.magnification(shown);
        zoomTarget=magnification.zoom();presTarget=magnification.presentation();
        if (fresh) zoomSmooth = zoomTarget;
        // panel side from where the fish is on screen: right of the centre -> panel on the left (mirrored)
        int want = sideFor(toLocalX(s.x) > viewCx - 150 * ease(list) ? -1 : 1);
        side = want;
        if (panelShown() <= .01f) { sideShown = want; sideFade = 1; }   // nothing shown yet: no fade needed
        if (progress <= .001f) focusSide = want;
        click();
    }
    /** The wanted side, unless the right panel would overlap the open list (then it goes left). */
    private int sideFor(int want) {
        if (want > 0 && listOpen && viewCx - 150 + 47 + PANEL_W > r0 - 317 - 58) return -1;
        return want;
    }
    /**
     * A world spot near the focused fish where the clamped camera (final zoom) can actually put it at the focus
     * point of the panel side; the fish swims there gently while zooming (owner: it stopped behind the panel).
     */
    private float[] comfort(Swimmer s) {
        float z = Math.max(1, zoomTarget);
        float[] bg = bgRect();
        float hw = (r0 - l0) / 2 / z, hh = (b0 - t0) / 2 / z;
        float cx0 = bg[0] + hw, cx1 = Math.max(cx0, bg[2] - hw), cy0 = bg[1] + hh, cy1 = Math.max(cy0, bg[3] - hh);
        float ox = (viewCx - 150 * (listOpen ? 1 : 0) - 261 * side - viewCx) / z, oy = -30 / z, pad = 30 / z;
        float x0 = cx0 + ox + pad, x1 = cx1 + ox - pad;
        float y0 = Math.max(cy0 + oy + pad, areaTop()), y1 = Math.min(cy1 + oy - pad, areaBottom());
        return new float[]{x0 <= x1 ? Math.clamp(s.x, x0, x1) : (x0 + x1) / 2, y0 <= y1 ? Math.clamp(s.y, y0, y1) : (y0 + y1) / 2};
    }
    private void deselect() { selected = null; deselectAt = 0; releaseConfirmation.reset(); }
    private static float ease(float t) { return FishTankUiMath.ease(t); }

    private void camera(float dt) {
        progress=FishTankUiMath.progress(progress,selected!=null,dt);
        list += ((listOpen ? 1 : 0) - list) * Math.min(1, dt * 7);
        var s = selected == null ? null : swimmers.get(selected);
        if (s != null) {
            float k = FishTankUiMath.blend(dt,6);
            focusX += (s.x - focusX) * k; focusY += (s.y - focusY) * k;
        }
        float e = ease(progress);
        viewCx = (l0 + r0) / 2; viewCy = (t0 + b0) / 2;
        zoomSmooth += (zoomTarget - zoomSmooth) * (FishTankUiMath.blend(dt,4));
        zoom = 1 + (zoomSmooth - 1) * e;
        // panel side: the list opening over a right panel sends it left; a side change fades out, swaps, fades in
        if (selected != null && side > 0 && sideFor(1) < 0) side = -1;
        if (side != sideShown) { sideFade = Math.max(0, sideFade - dt / .16f); if (sideFade <= 0 || panelAlpha() <= .01f) sideShown = side; }
        else sideFade = Math.min(1, sideFade + dt / .22f);
        focusSide += (side - focusSide) * (1 - (float) Math.exp(-dt * 3.5f));
        if (progress <= .001f) focusSide = side;
        float fx = viewCx - 261 * focusSide - 150 * ease(list), fy = viewCy - 30;
        float followX = focusX - (fx - viewCx) / zoom, followY = focusY - (fy - viewCy) / zoom;
        camX = viewCx + (followX - viewCx) * e; camY = viewCy + (followY - viewCy) * e;
        // keep the view inside the background
        float[] bg = bgRect();
        float hw = (r0 - l0) / 2 / zoom, hh = (b0 - t0) / 2 / zoom;
        camX = Math.clamp(camX, bg[0] + hw, Math.max(bg[0] + hw, bg[2] - hw));
        camY = Math.clamp(camY, bg[1] + hh, Math.max(bg[1] + hh, bg[3] - hh));
        if (deselectAt != 0 && Util.getMeasuringTimeMs() >= deselectAt) deselect();
    }
    /** Background in world units: covers the view (16:9) with a little room for the camera. */
    private float[] bgRect() {
        float vw = r0 - l0, vh = b0 - t0;
        float w = Math.max(vw, vh * 16 / 9f) * 1.08f, h = w * 9 / 16f;
        float cx = (l0 + r0) / 2, bottom = b0 + (h - vh) * .2f;
        return new float[]{cx - w / 2, bottom - h, cx + w / 2, bottom};
    }
    private float toLocalX(float wx) { return FishTankUiMath.local(wx,camX,zoom,viewCx); }
    private float toLocalY(float wy) { return FishTankUiMath.local(wy,camY,zoom,viewCy); }
    private float toWorldX(double lx) { return FishTankUiMath.world(lx,camX,zoom,viewCx); }
    private float toWorldY(double ly) { return FishTankUiMath.world(ly,camY,zoom,viewCy); }

    // ------------------------------------------------------------------ model helpers

    private int count() { return fish.size(); }
    private Info info(String fishId) { return infos.get(fishId); }
    private String name(String fishId) { var i = info(fishId); return i == null ? fishId : i.name(); }
    private int grade(String fishId) { var i = info(fishId); return i == null ? 0 : Math.clamp(i.grade(), 0, 3); }
    private int shape(String fishId) { var i = info(fishId); return i == null ? 0 : i.shape(); }
    static String cm(float v) { return String.format(Locale.ROOT, "%.1f", v) + " cm"; }
    static String date(long at) { return at <= 0 ? "-" : new SimpleDateFormat("yyyy.MM.dd").format(new Date(at)); }
    static String money(long v) { return String.format(Locale.ROOT, "%,d", v); }

    private List<Fish> rows() {
        String q = query.strip();
        var out = new ArrayList<Fish>();
        for (var f : fish.values()) {
            var s = swimmers.get(f.id());
            if (s != null && s.leave >= 0) continue;
            if (!q.isEmpty() && !name(f.fish()).contains(q)) continue;
            out.add(f);
        }
        switch (tab) {
            case 1 -> out.sort(Comparator.comparingInt((Fish f) -> -grade(f.fish())).thenComparing(f -> -f.size()));
            case 2 -> out.sort(Comparator.comparingDouble((Fish f) -> -f.size()));
            default -> out.sort(Comparator.comparingLong((Fish f) -> -f.caughtAt()).thenComparing(Fish::id));
        }
        return out;
    }
    private List<Held> heldRows() {
        String q = query.strip();
        var out = new ArrayList<Held>();
        for (var h : inventory) if (q.isEmpty() || name(h.fish()).contains(q)) out.add(h);
        return out;
    }
    private int rowCount() { return tab == TAB_INSERT ? heldRows().size() : rows().size(); }
    private int visibleRows() { return Math.max(1, (int) ((listBottom() - LIST_TOP_OFF - t0) / ROW_H)); }
    private int maxScroll() { return Math.max(0, rowCount() - visibleRows()); }

    // ------------------------------------------------------------------ layout (approved references, 1672x941, anchored to the view edges)

    static final float ROW_H = 98, LIST_TOP_OFF = 205;
    private float listX() { return r0 - 317 + 340 * (1 - ease(list)); }
    private float listBottom() { return b0 - 34; }
    /** Info panel: right of the fish (side 1, the reference) or mirrored to the left of it (side -1). */
    private float panelX() { float cx = viewCx - 150 * ease(list); return sideShown > 0 ? cx + 47 : cx - 47 - PANEL_W; }
    private float panelY() { return viewCy - 266 + ROW_GAP / 2; }
    /** One info row; the panel lost the 개체 번호 row (reference 542x508 -> one row shorter). */
    static final float ROW_GAP = 38, BUTTONS_Y = 455 - ROW_GAP;
    static final float PANEL_W = 542, PANEL_H = 540 - ROW_GAP;
    static final float POP_W = 520, POP_H = 420;

    // ------------------------------------------------------------------ input

    private CodexLayout layout() { return CodexLayout.fit(width, height); }
    private static boolean in(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, .25f)); }
    private float panelAlpha() { return ease((progress - .4f) / .6f); }
    /** Panel alpha including the fade while it changes sides. */
    private float panelShown() { return panelAlpha() * ease(sideFade); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseDown = true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button);
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        if (popup != null) { popupClick(mx, my); return true; }
        if (in(mx, my, r0 - 66, t0 + 22, 40, 40)) { close(); return true; }
        // the list
        if (listOpen) {
            if (in(mx, my, listX() - 60, viewCy - 22, 40, 44)) { listOpen = false; search.setFocused(false); click(); return true; }
            if (listClick(mx, my)) return true;
        } else if (in(mx, my, r0 - 62, t0 + 113, 40, 40)) { listOpen = true; click(); return true; }
        search.setFocused(false);
        // the info panel
        var sel = selected == null ? null : fish.get(selected);
        if (sel != null && panelShown() > .5f && in(mx, my, panelX(), panelY(), PANEL_W, PANEL_H)) { panelClick(mx, my, sel); return true; }
        // the water
        String hit = pick(mx, my);
        if (hit != null && !hit.equals(selected)) { select(hit); return true; }
        if (hit == null && selected != null) { deselect(); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { mouseDown = false; return super.mouseReleased(mouseX, mouseY, button); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        var l = layout();
        if (listOpen && popup == null && l.localX(mouseX) >= listX() - 20) scroll = Math.clamp(scroll - (int) Math.signum(v), 0, maxScroll());
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, mods);
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (search.isFocused()) { search.setFocused(false); return true; }
            if (popup != null) { popup = null; return true; }
            if (selected != null) { deselect(); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char chr, int mods) { return search.isFocused() ? search.charTyped(chr, mods) : super.charTyped(chr, mods); }

    private boolean listClick(double mx, double my) {
        float x = listX();
        if (mx < x - 20) return false;
        if (in(mx, my, x, t0 + 87, 282, 44)) { search.setFocused(true); return true; }
        float[] tabX = {28, 99, 172, 260};
        for (int i = 0; i < 4; i++) if (in(mx, my, x + tabX[i] - 34, t0 + 152, 68, 40)) {
            if (tab != i) { tab = i; scroll = 0; click(); if (i == TAB_INSERT) FishTankClient.query(); }
            return true;
        }
        float top = t0 + LIST_TOP_OFF;
        if (my >= top && my < listBottom() && mx < x + 290) {
            int index = scroll + (int) ((my - top) / ROW_H);
            if (tab == TAB_INSERT) {
                var rows = heldRows();
                if (index >= 0 && index < rows.size()) { popup = rows.get(index); popupAt = Util.getMeasuringTimeMs(); click(); }
            } else {
                var rows = rows();
                if (index >= 0 && index < rows.size()) select(rows.get(index).id());
            }
            return true;
        }
        if (in(mx, my, r0 - 32, top, 16, listBottom() - top)) { scroll = Math.round((float) ((my - top) / (listBottom() - top)) * maxScroll()); return true; }
        return mx >= x - 20;   // the list area swallows clicks
    }

    private void panelClick(double mx, double my, Fish f) {
        float x = panelX(), y = panelY() + BUTTONS_Y;
        if (busy()) return;
        if (in(mx, my, x + 39, y, 133, 54)) {
            if (inventoryFull) { say("인벤토리에 빈 칸이 없어요."); return; }
            click(); ask(FishTankClient.fish(FishTankProtocol.ITEMIZE, f.id()), FishTankProtocol.ITEMIZE, f.id());
        } else if (in(mx, my, x + 182, y, 129, 54)) {
            long now = Util.getMeasuringTimeMs();
            if(releaseConfirmation.click(now)){click();ask(FishTankClient.fish(FishTankProtocol.RELEASE,f.id()),FishTankProtocol.RELEASE,f.id());}else click();
        } else if (in(mx, my, x + 322, y, 192, 54)) {
            if (f.locked()) { say("잠긴 종류는 팔 수 없어요. 물고기 판매소에서 잠금을 풀어 주세요."); return; }
            click(); ask(FishTankClient.sell(f.id(), f.price()), FishTankProtocol.SELL, f.id());
        }
    }

    private float[] popupRect() { return new float[]{viewCx - POP_W / 2, viewCy - POP_H / 2}; }
    private void popupClick(double mx, double my) {
        float[] r = popupRect(); float x = r[0], y = r[1] + 340;
        if (in(mx, my, x + 52, y, 150, 56)) { popup = null; click(); return; }
        if (in(mx, my, x + 220, y - 1, 250, 58)) {
            if (busy() || count() >= capacity) { if (count() >= capacity) say("수조가 가득 찼어요."); return; }
            click(); ask(FishTankClient.insert(popup.slot(), popup.id()), FishTankProtocol.INSERT, popup.id());
            return;
        }
        if (!in(mx, my, x, r[1], POP_W, POP_H)) popup = null;
    }

    /** Top-most fish under the mouse (layout units), by its silhouette ellipse. */
    private String pick(double mx, double my) {
        if (listOpen && mx >= listX() - 20) return null;
        float wx = toWorldX(mx), wy = toWorldY(my);
        String best = null; float bestZ = -9;
        for (var s : swimmers.values()) {
            if (s.leave >= 0 || s.alpha < .3f) continue;
            float[] d = dims(s);
            float dx = (wx - d[0]) / Math.max(8, d[2] / 2), dy = (wy - d[1]) / Math.max(6, d[3] / 2 + 6);
            float z = s.id.equals(selected) ? 9 : s.z;
            if (dx * dx + dy * dy <= 1 && z > bestZ) { best = s.id; bestZ = z; }
        }
        return best;
    }
    /** World centre x, y, drawn silhouette width, height of a swimmer. */
    private float[] dims(Swimmer s) {
        float len = s.length * scaleOf(s.z) * pres(s);
        int[] b = bounds(s);
        float h = len * (b[3] - b[1]) / Math.max(1f, b[2] - b[0]);
        float bob = (float) Math.sin(time * 1.7 + s.phase) * 3 + (shape(s.data.fish()) == 2 ? (float) Math.sin(time * 2.4 + s.phase) * 6 : 0);
        return new float[]{s.x, s.y + bob, len, h};
    }
    private float pres(Swimmer s) { return s.pres; }
    private int[] bounds(Swimmer s) {
        if (FishingArt.fish(s.data.fish()) == null) return new int[]{24, 64, 488, 448};
        int[] b = FishArtBounds.of(s.data.fish());
        return b == null ? new int[]{0, 0, 256, 256} : b;
    }

    // ------------------------------------------------------------------ drawing helpers

    private static void image(DrawContext c, String name, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint) {
        int x0 = Math.round(x), y0 = Math.round(y);
        int w0 = Math.round(x + w) - x0, h0 = Math.round(y + h) - y0;
        if (w0 <= 0 || h0 <= 0 || sw <= 0 || sh <= 0 || (tint >>> 24) == 0) return;
        UiResources.images().drawTexture(c, tex(name), x0, y0, u, v, w0, h0, sw, sh, tw, th, tint);
    }
    private static void whole(DrawContext c, String name, int tw, int th, float x, float y, float w, float h, int tint) { image(c, name, x, y, w, h, 0, 0, tw, th, tw, th, tint); }
    /** Nine-slice at the PNG's own corner size (the tank glass keeps its 1px outline and radius). */
    private static void nine(DrawContext c, String name, int tw, int th, int inset, float x, float y, float w, float h, int tint) {
        float k = Math.min(1, Math.min(w / (inset * 2f), h / (inset * 2f)));
        float e = inset * k;
        int[] sx = {0, inset, tw - inset, tw}; int[] sy = {0, inset, th - inset, th};
        float[] dx = {x, x + e, x + w - e, x + w}; float[] dy = {y, y + e, y + h - e, y + h};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            image(c, name, dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row], sx[col], sy[row], sx[col + 1] - sx[col], sy[row + 1] - sy[row], tw, th, tint);
    }
    private static void text(DrawContext c, String v, float x, float y, float size, int color, Identifier face, boolean centered) {
        if (v != null && !v.isBlank() && (color >>> 24) > 2) UiResources.text().draw(c, v, x, y, size, color, face, centered);
    }
    private static float width(String v, float size, Identifier face) { return UiResources.text().width(v, size, face); }
    private static float fit(String v, float size, Identifier face, float max) { float w = width(v, size, face); return w <= max ? size : Math.max(10, size * max / w); }
    private static int a(int color, float alpha) { return FishingArt.alpha(color, alpha); }
    private static int white(float alpha) { return FishingArt.argb(alpha, 0xFFFFFF); }

    private static final int NORMAL = 0, ACCENT = 1, CONFIRM = 2;
    private void button(DrawContext c, double mx, double my, float x, float y, float w, float h, String label, int style, int accent, boolean enabled, float alpha) {
        boolean over = in(mx, my, x, y, w, h);
        String file = !enabled ? "button_disabled" : style == CONFIRM ? "button_confirm" : style == ACCENT ? "button_accent"
                : over ? mouseDown ? "button_pressed" : "button_hover" : "button_normal";
        int tint = style == ACCENT && enabled ? FishingArt.argb(alpha, FishingArt.mix(accent, 0xFFFFFF, over ? .35f : 0)) : white(alpha);
        nine(c, file, 220, 54, 18, x, y, w, h, tint);
        text(c, label, x + w / 2, y + h / 2 + 1, fit(label, 20, BODY, w - 22), a(enabled ? WHITE : DIM, alpha), BODY, true);
    }

    /** A fish drawn by its silhouette length, mirrored by face (-1..1, passing through 0 is the turn), rotated. */
    private void drawFish(DrawContext c, String fishId, int shape, float cx, float cy, float len, float face, float rot, int tint, boolean additive) {
        var art = FishingArt.fish(fishId);
        int[] b;
        if (art == null) { art = FishingArt.SHAPES[Math.clamp(shape, 0, FishingArt.SHAPES.length - 2)]; b = new int[]{24, 64, 488, 448}; }
        else { b = FishArtBounds.of(fishId); if (b == null) b = new int[]{0, 0, art.w(), art.h()}; }
        float k = len / Math.max(1f, b[2] - b[0]);
        float w = art.w() * k, h = art.h() * k;
        float ox = ((b[0] + b[2]) / 2f - art.w() / 2f) * k, oy = ((b[1] + b[3]) / 2f - art.h() / 2f) * k;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(cx, cy, 0);
            if (rot != 0) m.multiply(new Quaternionf().rotateZ(rot));
            float f = Math.abs(face) < .08f ? Math.copySign(.08f, face == 0 ? 1 : face) : face;
            m.scale(f, 1, 1);
            FishingArt.draw(c, art, -w / 2 - ox, -h / 2 - oy, w, h, tint, additive);
        } finally { m.pop(); }
    }

    // ------------------------------------------------------------------ render

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        l0 = (float) l.localX(0); t0 = (float) l.localY(0); r0 = (float) l.localX(width); b0 = (float) l.localY(height);
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1000f, 0, .05f);
        lastFrame = now; time += dt;
        update(dt);
        camera(dt);
        var images = UiResources.images(); var type = UiResources.text();
        images.beginFrame(); type.beginFrame();
        c.fill(0, 0, width, height, 0xFF04122A);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            hovered = popup == null && progress < .05f ? pick(mx, my) : null;
            world(c);
            float e = ease(progress);
            if (e > .001f) HudMesh.radial(c, maskX, maskY, 250, 700, 4000, 0x00020814, FishingArt.argb(.42f * e, 0x020814));
            ui(c, mx, my);
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    // ---- the water

    private final float[] gx = new float[33 * 19], gy = new float[33 * 19], gu = new float[33 * 19], gv = new float[33 * 19];
    private final int[] gc = new int[33 * 19];

    private void world(DrawContext c) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(viewCx, viewCy, 0); m.scale(zoom, zoom, 1); m.translate(-camX, -camY, 0);
            float[] bg = bgRect();
            float e = ease(progress);
            var sel = selected == null ? null : swimmers.get(selected);
            // the focus stays where it was while zooming back out after a deselect
            if (sel != null) { float[] d = dims(sel); maskX = toLocalX(d[0]); maskY = toLocalY(d[1]); }
            float sx = maskX, sy = maskY;
            // 1. background: blurred copy under the sharp one, the sharp one fades out away from the focused fish
            if (e > .001f) grid(c, BLUR, false, bg[0], bg[1], bg[2], bg[3], 1, 1, (i, j, x, y) -> 0xFFFFFFFF);
            float fsx = sx, fsy = sy;
            grid(c, BASE, false, bg[0], bg[1], bg[2], bg[3], e > .001f ? 32 : 1, e > .001f ? 18 : 1, (i, j, x, y) -> {
                if (e <= .001f) return 0xFFFFFFFF;
                float dx = toLocalX(x) - fsx, dy = toLocalY(y) - fsy, d = (float) Math.sqrt(dx * dx + dy * dy);
                return FishingArt.argb(1 - e * smooth(230, 560, d), 0xFFFFFF);
            });
            // 2. surface shimmer: caustics stretched along the top, drifting with the waves
            caustics(c, bg[0], bg[1], bg[2], t0 + (b0 - t0) * .26f, 560, 150, .055f, .012f, .10f, 0xFFDDF8FF, true);
            // 3. sun shafts from above, swaying and flickering per shaft
            rays(c, 0, .28f, 0xD8F6FF);
            rays(c, 2.1f, .15f, 0xBDEBFF);
            // 4. caustics on the sand (two layers moving in different directions)
            float floor = t0 + (b0 - t0) * .64f;
            caustics(c, bg[0], floor, bg[2], bg[3], 330, 150, .021f, .013f, .26f, 0xFFCFF6FF, false);
            caustics(c, bg[0], floor, bg[2], bg[3], 470, 210, -.017f, .009f, .17f, 0xFFB9F0FF, false);
            // 5. motes
            float vw = bg[2] - bg[0], vh = bg[3] - bg[1];
            for (float[] p : motes) {
                float x = bg[0] + p[0] * vw, y = bg[1] + p[1] * vh;
                float tw = .5f + .5f * (float) Math.sin(time * 1.3 + p[4] * 20);
                HudMesh.disk(c, x, y, p[2], FishingArt.argb(.10f + .14f * tw, 0xCFEFFF));
            }
            // 6. fish, far to near; the focused one last
            var order = new ArrayList<>(swimmers.values());
            order.sort(Comparator.comparingDouble(s -> s.id.equals(selected) ? 9 : s.z));
            for (var s : order) fish(c, s, e, fsx, fsy);
            drawBubbles(c);
            // 7. near plants
            float fh = (bg[2] - bg[0]) * 512 / 2560f;
            int dark = FishingArt.argb(1, FishingArt.mix(0xFFFFFF, 0x26344A, .45f * e));
            grid(c, FRONT, false, bg[0], bg[3] - fh, bg[2], bg[3], 1, 1, (i, j, x, y) -> dark);
        } finally { m.pop(); }
    }
    private static float smooth(float a, float b, float x) { float t = Math.clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }

    private interface Tint { int at(int i, int j, float x, float y); }
    private void grid(DrawContext c, Identifier id, boolean additive, float x0, float y0, float x1, float y1, int cols, int rows, Tint tint) {
        int n = 0;
        for (int j = 0; j <= rows; j++) for (int i = 0; i <= cols; i++, n++) {
            float x = x0 + (x1 - x0) * i / cols, y = y0 + (y1 - y0) * j / rows;
            gx[n] = x; gy[n] = y; gu[n] = (float) i / cols; gv[n] = (float) j / rows; gc[n] = tint.at(i, j, x, y);
        }
        UiResources.images().drawGrid(c, id, additive, cols, rows, gx, gy, gu, gv, gc, false);
    }
    /**
     * Moving caustics (additive) over a world rectangle: tile size px x py, drift in tiles/s, wobbling UVs like a
     * refracting surface. surface = brightest at the top and fading down; else fading in from the top of the floor
     * and strongest under the light.
     */
    private void caustics(DrawContext c, float x0, float y0, float x1, float y1, float px, float py, float driftU, float driftV, float strength, int tint, boolean surface) {
        int cols = 24, rows = 8, n = 0;
        float cx = viewCx, spread = (r0 - l0) * .42f;
        for (int j = 0; j <= rows; j++) for (int i = 0; i <= cols; i++, n++) {
            float x = x0 + (x1 - x0) * i / cols, y = y0 + (y1 - y0) * j / rows;
            float fv = (float) j / rows;
            // perspective on the sand: tiles get shorter toward the horizon
            float vy = surface ? (y - y0) / py : (float) (Math.pow(fv, 1.35) * (y1 - y0) / py);
            float u = (x - x0) / px + time * driftU, v = vy + time * driftV;
            u += .07f * (float) Math.sin(time * .9 + v * 3.1);
            v += .07f * (float) Math.cos(time * .7 + u * 2.7);
            float mask = surface ? (1 - fv) * (1 - fv) : smooth(0, .3f, fv) * (.55f + .45f * (float) Math.exp(-Math.pow((x - cx) / spread, 2)));
            gx[n] = x; gy[n] = y; gu[n] = u; gv[n] = v;
            gc[n] = FishingArt.argb(strength * mask, tint);
        }
        UiResources.images().drawGrid(c, CAUSTICS, true, cols, rows, gx, gy, gu, gv, gc, true);
    }
    /** Light shafts from the top centre; the bottom sways with the water and each shaft flickers on its own. */
    private void rays(DrawContext c, float phase, float strength, int rgb) {
        float vw = r0 - l0, vh = b0 - t0;
        float w = vw * .72f, x0 = viewCx - w / 2, y0 = t0 - vh * .07f, h = vh * 1.05f;
        int cols = 12, rows = 8, n = 0;
        boolean mirror = phase > 1;
        for (int j = 0; j <= rows; j++) for (int i = 0; i <= cols; i++, n++) {
            float fu = (float) i / cols, fv = (float) j / rows;
            float sway = (float) Math.sin(time * .32 + fv * 1.6 + phase) * fv * vw * .028f + (float) Math.sin(time * .71 + fu * 4 + phase) * fv * vw * .006f;
            gx[n] = x0 + w * fu + sway; gy[n] = y0 + h * fv;
            gu[n] = mirror ? 1 - fu : fu; gv[n] = fv;
            float flicker = .72f + .28f * (float) (Math.sin(time * .83 + fu * 11 + phase) * Math.sin(time * .47 + fu * 5.3 + phase * 2));
            gc[n] = FishingArt.argb(strength * flicker * (1 - .35f * ease(progress)), rgb);
        }
        UiResources.images().drawGrid(c, RAYS, true, cols, rows, gx, gy, gu, gv, gc, false);
    }

    private void fish(DrawContext c, Swimmer s, float e, float sx, float sy) {
        boolean focused = s.id.equals(selected);
        float[] d = dims(s);
        float len = d[2];
        float alpha = s.alpha;
        if (s.leave >= 0) {
            float t = s.leave / 1.1f;
            alpha *= 1 - ease(t);
            if (s.leaveKind == FishTankProtocol.ITEMIZE) len *= 1 - .8f * ease(t);
            if (s.leaveKind == FishTankProtocol.ITEMIZE || s.leaveKind == FishTankProtocol.SELL) {
                int glow = s.leaveKind == FishTankProtocol.SELL ? 0xFFD27A : 0xCFE8FF;
                FishingArt.bloom(c, d[0], d[1], len * 1.1f, glow, .55f * (float) Math.sin(Math.PI * Math.min(1, t * 1.4)));
            }
        }
        if (alpha <= .01f) return;
        int shape = shape(s.data.fish());
        float rot = shape == 4 ? 0 : Math.clamp((float) Math.atan2(s.vy, Math.abs(s.vx) + 8) * .6f, -.35f, .35f) * Math.signum(s.face);
        rot += (float) Math.sin(time * (5 + s.speed * .05f) + s.phase) * (focused ? .02f : .035f);
        if (shape == 3) rot += (float) Math.sin(time * 1.6 + s.phase) * .05f;
        // depth: far fish are smaller (dims) and bluer; away from the focused fish everything dims
        float depth = focused ? 1 : s.z;
        int rgb = FishingArt.mix(0x7F9CC8, 0xFFFFFF, .45f + .55f * depth);
        if (!focused && e > .001f) {
            float dx = toLocalX(d[0]) - sx, dy = toLocalY(d[1]) - sy;
            float k = smooth(200, 520, (float) Math.sqrt(dx * dx + dy * dy));
            rgb = FishingArt.mix(rgb, 0x34507A, .55f * e * (.4f + .6f * k));
            alpha *= 1 - .35f * e * k;
        }
        if (focused) FishingArt.bloom(c, d[0], d[1], len * .75f, GRADE[grade(s.data.fish())] & 0xFFFFFF, .16f * e);
        else if (s.id.equals(hovered)) FishingArt.bloom(c, d[0], d[1], len * .7f, 0xCFEFFF, .14f);
        drawFish(c, s.data.fish(), shape, d[0], d[1], len, s.face, rot, FishingArt.argb(alpha, rgb), false);
        // sunlight glint: shallower fish near the shafts catch a little light
        float light = (float) Math.exp(-Math.pow((d[0] - viewCx) / ((r0 - l0) * .3f), 2)) * (1 - Math.clamp((d[1] - t0) / (b0 - t0), 0, 1));
        float glint = light * (.10f + .08f * (float) Math.sin(time * 2.3 + s.phase * 3)) * alpha;
        if (glint > .01f) drawFish(c, s.data.fish(), shape, d[0], d[1], len, s.face, rot, FishingArt.argb(glint, 0xDFF6FF), true);
        if (s.id.equals(hovered) && !focused) {
            String n = name(s.data.fish());
            float ty = d[1] - d[3] / 2 - 14;
            float w = width(n, 16, LABEL) + 18;
            FishingArt.card(c, d[0] - w / 2, ty - 11, w, 22, 0xB0081830, 0x50C9D6E6, 1);
            text(c, n, d[0], ty, 16, GRADE[grade(s.data.fish())], LABEL, true);
        }
    }

    // ---- the interface (layout units, not moved by the camera)

    private void ui(DrawContext c, double mx, double my) {
        // title + capacity next to it (owner: 수조 용량 표시)
        float tx = l0 + 37, ty = t0 + 45;
        text(c, "아공간 수조", tx, ty, 30, WHITE, SECTION, false);
        String cap = count() + " / " + capacity;
        text(c, cap, tx + width("아공간 수조", 30, SECTION) + 22, ty + 2, 21, count() >= capacity ? WARN : 0xFFA9C0DA, BODY, false);
        boolean overClose = in(mx, my, r0 - 66, t0 + 22, 40, 40);
        whole(c, "close", 64, 64, r0 - 70, t0 + 18, 48, 48, white(overClose ? 1 : .82f));
        if (!loaded) text(c, "불러오는 중…", viewCx, viewCy, 22, MUTED, BODY, true);
        else if (fish.isEmpty() && popup == null) text(c, "수조가 비어 있어요. 낚시로 물고기를 잡아 보세요.", viewCx - 150 * ease(list), viewCy - 40, 21, 0xC8DCE6F2, BODY, true);
        if (!listOpen && list < .05f) {
            boolean over = in(mx, my, r0 - 62, t0 + 113, 40, 40);
            whole(c, "arrow_left", 64, 64, r0 - 66, t0 + 109, 48, 48, white(over ? 1 : .78f));
        }
        if (list > .01f) list(c, mx, my);
        panel(c, mx, my);
        if (popup != null) popup(c, mx, my);
        long age = Util.getMeasuringTimeMs() - noticeAt;
        if (!notice.isBlank() && age < 4000) {
            float al = age > 3500 ? (4000 - age) / 500f : 1;
            float s = fit(notice, 19, BODY, 760), w = width(notice, s, BODY) + 44;
            float ny = b0 - 70;
            FishingArt.card(c, viewCx - w / 2, ny - 18, w, 36, FishingArt.argb(.86f * al, 0x08182E), FishingArt.argb(.55f * al, 0xC9D6E6), 1);
            text(c, notice, viewCx, ny, s, a(0xFFF1F5FA, al), BODY, true);
        }
    }

    private void list(DrawContext c, double mx, double my) {
        float x = listX(), al = ease(list);
        // glass behind the list: soft left edge
        float px = x - 18;
        HudMesh.quad(c, px - 40, t0 + 70, 40, listBottom() - t0 - 46, 0x00061428, FishingArt.argb(.55f * al, 0x061428));
        FishingArt.rect(c, px, t0 + 70, r0 - px, listBottom() - t0 - 46, FishingArt.argb(.55f * al, 0x061428));
        boolean overArrow = in(mx, my, x - 60, viewCy - 22, 40, 44);
        whole(c, "arrow_right", 64, 64, x - 64, viewCy - 24, 48, 48, white((overArrow ? 1 : .78f) * al));
        // search (underline field)
        float sy = t0 + 109;
        whole(c, "search", 64, 64, x + 2, sy - 14, 28, 28, white(.85f * al));
        boolean focused = search.isFocused();
        String shown = query.isEmpty() && !focused ? "물고기 이름 검색" : query + (focused && Util.getMeasuringTimeMs() / 500 % 2 == 0 ? "|" : "");
        text(c, shown, x + 46, sy, 19, a(query.isEmpty() && !focused ? 0xFF8E9BB0 : TEXT, al), BODY, false);
        FishingArt.hline(c, x, x + 282, t0 + 131, FishingArt.argb((focused ? .8f : .45f) * al, 0xC9D6E6));
        // tabs
        float[] tabX = {28, 99, 172, 260};
        for (int i = 0; i < 4; i++) {
            boolean on = tab == i, over = in(mx, my, x + tabX[i] - 34, t0 + 152, 68, 40);
            int color = i == TAB_INSERT ? GOLD : on ? WHITE : over ? 0xFFDCE4EE : 0xFFAFC0D4;
            text(c, TABS[i], x + tabX[i], t0 + 172, 21, a(color, al), LABEL, true);
            if (on) FishingArt.rect(c, x + tabX[i] - 24, t0 + 189, 48, 2, a(i == TAB_INSERT ? GOLD : WHITE, al));
        }
        float top = t0 + LIST_TOP_OFF;
        int visible = visibleRows();
        scroll = Math.clamp(scroll, 0, maxScroll());
        if (tab == TAB_INSERT) {
            var rows = heldRows();
            if (rows.isEmpty()) text(c, query.isBlank() ? "인벤토리에 물고기가 없어요." : "검색 결과가 없어요.", x + 141, top + 60, 18, a(MUTED, al), BODY, true);
            for (int k = 0; k < visible && scroll + k < rows.size(); k++) {
                var h = rows.get(scroll + k);
                float y = top + k * ROW_H;
                boolean over = in(mx, my, x, y, 282, ROW_H) && popup == null;
                nine(c, popup != null && popup.slot() == h.slot() || over ? "insert_slot_selected" : "insert_slot_normal", 256, 80, 12, x, y + 6, 282, ROW_H - 12, white(al));
                FishingArt.fishFit(c, h.fish(), shape(h.fish()), x + 56, y + ROW_H / 2, 88, 54, white(al), false);
                text(c, name(h.fish()), x + 120, y + 36, fit(name(h.fish()), 21, LABEL, 150), a(GRADE[grade(h.fish())], al), LABEL, false);
                text(c, cm(h.size()), x + 121, y + 66, 18, a(TEXT, al), BODY, false);
            }
        } else {
            var rows = rows();
            if (rows.isEmpty()) text(c, fish.isEmpty() ? "수조가 비어 있어요." : "검색 결과가 없어요.", x + 141, top + 60, 18, a(MUTED, al), BODY, true);
            for (int k = 0; k < visible && scroll + k < rows.size(); k++) {
                var f = rows.get(scroll + k);
                float y = top + k * ROW_H;
                boolean on = f.id().equals(selected), over = in(mx, my, x, y, 282, ROW_H) && popup == null;
                if (on || over) FishingArt.rect(c, x, y + 1, 278, ROW_H - 2, FishingArt.argb((on ? .10f : .06f) * al, 0xFFFFFF));
                if (on) FishingArt.rect(c, x, y + 10, 3, ROW_H - 20, a(GRADE[grade(f.fish())], al));
                FishingArt.fishFit(c, f.fish(), shape(f.fish()), x + 56, y + ROW_H / 2, 92, 58, white(al), false);
                text(c, name(f.fish()), x + 122, y + 36, fit(name(f.fish()), 22, LABEL, 150), a(GRADE[grade(f.fish())], al), LABEL, false);
                text(c, cm(f.size()), x + 123, y + 69, 18, a(TEXT, al), BODY, false);
                if (f.locked()) UiResources.images().drawTexture(c, LOCK, Math.round(x + 252), Math.round(y + 30), 0, 0, 13, 16, 128, 160, 128, 160, white(.8f * al));
                FishingArt.hline(c, x, x + 278, y + ROW_H, FishingArt.argb(.22f * al, 0xC9D6E6));
            }
        }
        // scrollbar
        int max = maxScroll();
        float bar = listBottom() - top;
        FishingArt.rect(c, r0 - 25, top + 12, 2, bar - 12, FishingArt.argb(.18f * al, 0xFFFFFF));
        if (max > 0) {
            float thumb = Math.max(50, bar * visible / Math.max(visible, rowCount()));
            float y0 = top + 12 + (bar - 12 - thumb) * scroll / max;
            FishingArt.rect(c, r0 - 26, y0, 4, thumb, FishingArt.argb(.65f * al, 0xFFFFFF));
        }
    }

    private void panel(DrawContext c, double mx, double my) {
        float al = panelShown();
        if (selected == null && progress <= 0) return;
        Fish f = selected == null ? null : fish.get(selected);
        if (f == null) f = lastPanel;
        if (f == null || al <= .01f) return;
        lastPanel = f;
        float x = panelX() + 28 * (1 - al) * sideShown, y = panelY();   // slides in from its own side
        int g = grade(f.fish());
        nine(c, PANELS[g], 540, 500, 48, x, y, PANEL_W, PANEL_H, white(al));
        FishingArt.rect(c, x + 39, y + 42, 3, 82, a(GRADE[g], al));
        String n = name(f.fish());
        text(c, n, x + 73, y + 67, fit(n, 40, SECTION, 410), a(WHITE, al), SECTION, false);
        text(c, GRADES[g], x + 74, y + 113, 24, a(GRADE[g], al), BODY, false);
        int line = FishingArt.argb(.32f * al, 0xC9D6E6);
        FishingArt.hline(c, x + 50, x + 500, y + 157, line);
        // owner (2026-10-20): no 개체 번호 row; the description and the buttons move up by one row
        String[] labels = {"크기", "잡은 날짜", "판매가"};
        String[] values = {cm(f.size()), date(f.caughtAt()), f.locked() ? "잠긴 종류 · 판매 안 함" : money(f.price()) + " G"};
        for (int i = 0; i < 3; i++) {
            float ry = y + 191 + i * ROW_GAP;
            text(c, labels[i], x + 49, ry, 21, a(0xFFC3CEDB, al), BODY, false);
            text(c, values[i], x + 233, ry, 21, a(i == 2 && f.locked() ? GOLD : WHITE, al), BODY, false);
        }
        FishingArt.hline(c, x + 50, x + 500, y + 335 - ROW_GAP, line);
        var info = info(f.fish());
        String desc = info == null ? "" : info.description();
        var lines = CodexTextLayout.wrap(desc, 450, s -> width(s, 21, BODY));
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            String s = lines.get(i);
            if (i == 2 && lines.size() > 3) s = s + "…";
            text(c, s, x + 49, y + 370 - ROW_GAP + i * 32, 21, a(0xFFE6ECF3, al), BODY, false);
        }
        float by = y + BUTTONS_Y;
        boolean busy = busy(), interactive = al > .5f;
        double bmx = interactive ? mx : -1, bmy = interactive ? my : -1;
        boolean confirm=releaseConfirmation.armed(Util.getMeasuringTimeMs());
        String item = busy && pendingAction == FishTankProtocol.ITEMIZE ? "꺼내는 중…" : inventoryFull ? "인벤토리 가득" : "아이템화";
        button(c, bmx, bmy, x + 39, by, 133, 54, item, ACCENT, GRADE[g] & 0xFFFFFF, !busy && !inventoryFull, al);
        button(c, bmx, bmy, x + 182, by, 129, 54, busy && pendingAction == FishTankProtocol.RELEASE ? "놓아주는 중…" : confirm ? "정말 방생?" : "방생",
                confirm ? CONFIRM : NORMAL, 0, !busy, al);
        String sell = f.locked() ? "잠김 · 판매 불가" : busy && pendingAction == FishTankProtocol.SELL ? "판매 중…" : "즉시 판매 · " + money(f.price()) + " G";
        button(c, bmx, bmy, x + 322, by, 192, 54, sell, NORMAL, 0, !busy && !f.locked(), al);
    }
    private Fish lastPanel;

    private void popup(DrawContext c, double mx, double my) {
        var h = popup;
        float t = ease((Util.getMeasuringTimeMs() - popupAt) / 220f);
        c.fill((int) Math.floor(l0), (int) Math.floor(t0), (int) Math.ceil(r0), (int) Math.ceil(b0), FishingArt.argb(.45f * t, 0x020814));
        float[] r = popupRect();
        var m = c.getMatrices(); m.push();
        try {
            float s = .94f + .06f * t;
            m.translate(viewCx, viewCy, 0); m.scale(s, s, 1); m.translate(-viewCx, -viewCy, 0);
            float x = r[0], y = r[1];
            int g = grade(h.fish());
            nine(c, PANELS[g], 540, 500, 48, x, y, POP_W, POP_H, white(t));
            FishingArt.bloom(c, x + POP_W / 2, y + 105, 120, GRADE[g] & 0xFFFFFF, .12f * t);
            FishingArt.fishFit(c, h.fish(), shape(h.fish()), x + POP_W / 2, y + 105, 300, 120, white(t), false);
            String n = name(h.fish());
            text(c, n, x + POP_W / 2, y + 203, fit(n, 34, SECTION, 420), a(WHITE, t), SECTION, true);
            text(c, GRADES[g], x + POP_W / 2, y + 240, 21, a(GRADE[g], t), BODY, true);
            text(c, "크기 " + cm(h.size()) + "  ·  잡은 날짜 " + date(h.caughtAt()), x + POP_W / 2, y + 276, 19, a(TEXT, t), BODY, true);
            boolean full = count() >= capacity;
            String status = full ? "수조가 가득 찼어요 (" + count() + " / " + capacity + ")" : "수조 " + count() + " / " + capacity + "  →  " + (count() + 1) + " / " + capacity;
            text(c, status, x + POP_W / 2, y + 311, 18, a(full ? WARN : 0xFFA9C0DA, t), BODY, true);
            float by = y + 340;
            button(c, mx, my, x + 52, by, 150, 56, "취소", NORMAL, 0, true, t);
            boolean enabled = !full && !busy();
            boolean over = enabled && in(mx, my, x + 220, by - 1, 250, 58);
            nine(c, !enabled ? "insert_button_disabled" : over ? "insert_button_hover" : "insert_button_normal", 290, 66, 18, x + 220, by - 1, 250, 58, white(t));
            String label = busy() && pendingAction == FishTankProtocol.INSERT ? "넣는 중…" : "수조에 넣기";
            text(c, label, x + 345, by + 28, 22, a(enabled ? GOLD : DIM, t), SECTION, true);
        } finally { m.pop(); }
    }
}
