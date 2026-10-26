package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.FishingArt.Art;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.*;

import static school.magiccodex.client.FishingArt.*;

/**
 * 낚시 패널 (2026-10-20, GPT's approved references): a panel in the middle of the screen, the world stays visible.
 *
 * READY   "click to cast" -> the server throws the bobber towards the first cast's spot
 * SINKING the bobber sinks at the float's speed, the camera follows it down the biome's underwater picture; click stops
 *         (or it stops at the line's length / the water's real depth)
 * BITE    after the server's wait the fish comes, circles and teases (fake nibbles: clicking now scares it off), the
 *         float's sensitivity gives an early tremble, then the real bite: "!!" and a timing bar
 * REEL    hold the left button to wind (tension rises), release to let the line run (the fish pulls it down). Up to the
 *         surface = caught; tension over the line's limit = snapped; dragged to the end of the line = escaped
 * RESULT  catch / chest / lost views, then back to READY (owner 2026-10-20: the panel stays open)
 * The bottom slots show the held rod's set; clicking one opens the gear window (not while the bobber is out).
 */
final class FishingScreen extends Screen {
    // ---- reference geometry (1672x941 mock-ups)
    static final float PX = 433, PY = 1, PW = 807, PH = 901;
    static final float VX = 445, VY = 12, VW = 783, VB = 756, VH = VB - VY;       // water view
    static final float BOBBER_X = 835;
    static final float RULER_X = 1153, RULER_TOP = 103, RULER_BOTTOM = 570;
    static final float SLOT_Y = 776, SLOT_W = 174, SLOT_H = 92;
    static final float[] SLOT_X = {466, 655, 843, 1031};
    static final float GAUGE_X = 482, GAUGE_TOP = 214, GAUGE_BOTTOM = 539;      // tension gauge on the left (owner 2026-10-20)
    /** Where the line ties on hook.png (the top of its eye), as a fraction of the picture. */
    static final float HOOK_EYE_X = 78 / 128f, HOOK_EYE_Y = 17 / 128f;

    private static final int NONE = 0, READY = 1, CASTING = 2, SINKING = 3, BITING = 4, REEL = 5, WAIT_RESULT = 6, RESULT = 7;

    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private int phase = NONE;
    private long session;
    private Gear gear;
    private int background;
    private float maxDepth = 15, floorDepth = 15;
    private boolean bottomless;
    private int tankCount, tankCapacity;
    private String notice = "";
    private long noticeAt, phaseAt, openedAt, lastFrame;
    private boolean closingByServer, pendingReady;

    // sinking / camera
    private float bobberDepth, camTop = -1;
    // bite
    private Bite bite;
    private long biteAt;
    private Random rng = new Random();
    private float approachEnd, warningStart, warningEnd, biteTime, sweep, zoneStart, zoneWidth, gaugeTime;
    private float[] teaseTimes = new float[0];
    private int fishSide = 1;
    private float fishW;
    private boolean hooked, missed, spooked, biteSounded;
    private long hookClickAt, lineEndAt;
    // fish swimming around the bobber while it waits: x, dy (px from the bobber), speed, dir, phase, size, shape, alpha, real
    private final List<float[]> swimmers = new ArrayList<>();
    private boolean approachCaptured;
    private float apX, apDy, apSize, apAlpha;
    // a fish darting away (spooked / missed / escaped / slipped / snapped)
    private long fleeAt;
    private float fleeX, fleeDepth, fleeDy, fleeDir, fleeAlpha, fleeW, fleeRot;
    private int fleeShape = -1;
    private String lossMessage;
    // reel
    private float fishDepth, stamina = 1, tension, overFor, slackFor, heat, fishX, wobble;
    private boolean holding, graceUsed, pulling;
    private float nextEvent, eventEnd, eventKind;
    private long reelClickSound, creakSound;
    // reel attacks: what hit, when; heldMask = winding during attack i
    private int attacksDone, heldMask;
    private final int[] wear = new int[2];
    private long attackAt;
    private int attackKind, attackDamage;
    // panel shake: thump at a pull's start, attack hit, one-shot jolts (hook set, catch)
    private long thumpAt, joltAt;
    private float thumpAmp, joltAmp;
    /** Overall shake strength (2026-10-21: halved; only the hook, the catch and a line about to snap shake hard). */
    static final float SHAKE = .5f;
    // fish mood (2026-10-21): resisting = a pull (winding raises tension), tired = a short rest after a pull or low stamina
    static final int MOOD_NORMAL = FishMood.NORMAL, MOOD_RESIST = FishMood.RESIST, MOOD_TIRED = FishMood.TIRED;
    private int mood;
    private long moodAt;
    // result tooltip (drawn last, in screen coordinates)
    private ItemStack hoverStack = ItemStack.EMPTY;
    private String hoverLabel;
    private float hoverX, hoverY;
    // result
    private Result result;
    private long resultAt;
    private final List<ItemStack> rewardIcons = new ArrayList<>();
    // ambience
    private final List<float[]> ambient = new ArrayList<>();   // depth, x, speed, size, dir, phase, shape, alpha
    private final List<float[]> motes = new ArrayList<>();     // x, y, speed, size, phase
    private final List<float[]> ripples = new ArrayList<>();   // x, y, startMs
    // gear window
    private GearList gearList;
    private boolean gearOpen;
    private int gearTab, gearSelected = -1, gearScroll;
    private String gearNotice = "";

    FishingScreen() { super(Text.literal("낚시")); }

    long session() { return session; }

    @Override protected void init() {
        if (openedAt == 0) {
            openedAt = Util.getMeasuringTimeMs();
            var r = new Random();
            for (int i = 0; i < 46; i++) motes.add(new float[]{VX + r.nextFloat() * VW, VY + r.nextFloat() * VH, 4 + r.nextFloat() * 10, 1.2f + r.nextFloat() * 2.4f, r.nextFloat() * 6.28f});
        }
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}

    @Override public void close() {
        if (gearOpen) { gearOpen = false; return; }
        if (!closingByServer && session != 0) FishingClient.cancel(session);
        super.close();
    }
    void closeFromServer() { closingByServer = true; super.close(); }

    // ------------------------------------------------------------------ server frames

    void onSession(FishingProtocol.Session s) {
        boolean newSession = s.session() != session;
        session = s.session(); gear = s.gear(); background = s.background(); maxDepth = Math.max(1, s.maxDepth());
        floorDepth = Math.max(maxDepth, s.floorDepth()); bottomless = s.bottomless();
        tankCount = s.tankCount(); tankCapacity = s.tankCapacity();
        if (!s.notice().isBlank()) say(s.notice());
        if (newSession || ambient.isEmpty()) seedAmbient(s.session());
        if (s.phase() == FishingProtocol.PHASE_WATER) {
            setPhase(SINKING);
            bobberDepth = 0; camTop = -1; bite = null; result = null;
            stopped = hooked = missed = spooked = holding = false;
            lineEndAt = 0; fleeShape = -1; lossMessage = null; swimmers.clear();
            ripples.add(new float[]{BOBBER_X, surfaceY(), Util.getMeasuringTimeMs()});
            sound(SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, 1.1f, .35f);
        } else {
            if (phase == RESULT || phase == WAIT_RESULT) pendingReady = true;
            else { setPhase(READY); camTop = -1; }
        }
    }

    void onBite(Bite b) {
        if (b.session() != session || phase != SINKING && phase != BITING) return;
        bite = b; biteAt = Util.getMeasuringTimeMs();
        rng = new Random(b.seed());
        hooked = missed = spooked = biteSounded = approachCaptured = false; stopped = false;
        fleeShape = -1; lossMessage = null; heldMask = 0; attacksDone = 0;
        boolean chest = b.shape() == FishingProtocol.SHAPE_CHEST;
        float approach = chest ? 0 : (b.depth() >= 50 && special(FishingProtocol.FLOAT, "abyss_approach") ? 1.25f : 1.6f);
        float t = b.waitMs() / 1000f;
        approachEnd = t + approach;
        teaseTimes = new float[chest ? 0 : b.teases()];
        float at = approachEnd + .5f;
        for (int i = 0; i < teaseTimes.length; i++) { teaseTimes[i] = at; at += 1.25f + rng.nextFloat() * .8f; }
        warningStart = at + .25f;
        float sensitivity = gear == null ? .2f : Math.max(.2f, gear.sensitivity());
        warningEnd = warningStart + sensitivity;
        biteTime = warningEnd;
        // hook gauge (v2): the server sets the needle time and the zone width
        zoneWidth = Math.clamp(b.zone(), .03f, .6f);
        sweep = Math.max(.2f, b.sweepMs() / 1000f);
        float lo = .3f, hi = Math.max(lo, .95f - zoneWidth);
        zoneStart = Math.min(1 - zoneWidth, lo + rng.nextFloat() * (hi - lo));
        gaugeTime = gaugeDuration();
        fishSide = rng.nextBoolean() ? 1 : -1;
        fishW = chest ? 120 : 70 + 330 * (float) Math.pow(b.size(), .8);
        seedSwimmers(b, chest);
        setPhase(BITING);
    }

    void onResult(Result r) {
        if (r.session() != session) return;
        result = r; resultAt = Util.getMeasuringTimeMs(); gear = r.gear();
        tankCount = r.tankCount(); tankCapacity = r.tankCapacity();
        rewardIcons.clear();
        for (var w : r.rewards()) rewardIcons.add(FishingClient.decode(w.preview()));
        if (!r.notice().isBlank()) say(r.notice());
        setPhase(RESULT);
        switch (r.outcome()) {
            case FishingProtocol.CAUGHT -> sound(r.grade() >= 3 ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP, r.grade() >= 2 ? 1.3f : 1.7f, .5f);
            case FishingProtocol.TREASURE -> sound(SoundEvents.BLOCK_CHEST_OPEN, 1.1f, .6f);
            case FishingProtocol.SNAPPED -> sound(SoundEvents.ENTITY_ITEM_BREAK, .9f, .6f);
            default -> sound(SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, .8f, .5f);
        }
    }

    void onGear(GearList g) {
        gearList = g; gearOpen = true; gearNotice = g.notice();
        if (g.gear() != null) gear = g.gear();
        if (phase == NONE && session == 0) setPhase(NONE);
        gearSelected = Math.min(gearSelected, cards().size());   // 0 = the equipped piece, then the owned cards
        if (gearSelected < 0 && !cards().isEmpty()) gearSelected = 0;
    }

    private void setPhase(int p) { phase = p; phaseAt = Util.getMeasuringTimeMs(); }
    private void say(String text) { notice = text; noticeAt = Util.getMeasuringTimeMs(); }
    private boolean special(int kind, String code) {
        if (gear == null) return false;
        for (String part : gear.piece(kind).special().split(";")) if (part.strip().split(":")[0].equals(code)) return true;
        return false;
    }
    private float effect(int kind, String code) {
        if (gear == null) return 1;
        for (String part : gear.piece(kind).special().split(";")) {
            String[] kv = part.strip().split(":");
            if (kv[0].equals(code) && kv.length > 1) try { return Float.parseFloat(kv[1]); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private void seedAmbient(long seed) {
        ambient.clear();
        var r = new Random(seed ^ 0x5EEDL);
        float range = depthRange();
        int n = 9;
        for (int i = 0; i < n; i++) {
            float d = (float) Math.pow(r.nextFloat(), .8) * range;
            float size = 40 + 260 * (d / range) * r.nextFloat() + 30 * r.nextFloat();
            int shape = r.nextFloat() < .7f ? 0 : 1 + r.nextInt(4);
            ambient.add(new float[]{d, VX + r.nextFloat() * VW, 14 + r.nextFloat() * 26, size, r.nextBoolean() ? 1 : -1, r.nextFloat() * 6.28f, shape, .45f + .4f * r.nextFloat()});
        }
        // a school of tiny fish near the top
        for (int i = 0; i < 7; i++)
            ambient.add(new float[]{range * (.08f + .05f * r.nextFloat()), VX + 120 + r.nextFloat() * 140, 30, 18 + r.nextFloat() * 8, 1, r.nextFloat() * 6.28f, 0, .55f});
    }

    // ------------------------------------------------------------------ depth <-> picture

    /**
     * Metres shown by the picture. With a floor in reach the sea floor sits right under maxDepth (as before); in open
     * water (the line ran out before the floor) the art is stretched so its floor stays below anything visible.
     */
    private float depthRange() { return bottomless ? Math.max(maxDepth * 1.6f, maxDepth + 12) : Math.max(10, maxDepth) * 1.08f; }
    private float bgScale() { return VW / 724f; }
    private float bgHeight() { return 2172 * bgScale(); }
    private float surfaceBg() { return 64 * bgScale(); }
    private float pxPerMeter() { return (bgHeight() - 190 * bgScale() - surfaceBg()) / depthRange(); }
    private float depthToBg(float d) { return surfaceBg() + d * pxPerMeter(); }
    private float screenY(float depth) { return VY + depthToBg(depth) - camTop; }
    private float surfaceY() { return screenY(0); }
    /** The bobber's screen y; hanging at the end of the line in open water it bounces a little. */
    private float bobberY() {
        float y = screenY(bobberDepth);
        if (bottomless && lineEndAt != 0) {
            float u = since(lineEndAt);
            y += 7 * (float) Math.exp(-3.2f * u) * (float) Math.sin(u * 10) + 1.4f * (float) Math.sin(u * 1.7f);
        }
        return y;
    }

    private float focusDepth() {
        return switch (phase) {
            case REEL -> fishDepth;
            case SINKING, BITING, WAIT_RESULT -> bobberDepth;
            case RESULT -> result != null && result.outcome() == FishingProtocol.CAUGHT ? 0 : bobberDepth;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ simulation

    private float since(long at) { return (Util.getMeasuringTimeMs() - at) / 1000f; }

    private void step(float dt) {
        switch (phase) {
            case SINKING -> {
                float speed = gear == null ? 1.5f : gear.sinkSpeed();
                if (background == FishingProtocol.BG_OCEAN) speed *= effect(FishingProtocol.FLOAT, "sea_sink");
                if (bite == null && !stopped) {
                    bobberDepth = Math.min(maxDepth, bobberDepth + speed * dt);
                    if (bobberDepth >= maxDepth && since(phaseAt) > .3f) stopSinking();
                }
            }
            case BITING -> { stepSwimmers(dt); stepBite(); }
            case WAIT_RESULT -> { if (missed || spooked) stepSwimmers(dt); }
            case REEL -> stepReel(dt);
            default -> {}
        }
        // camera
        float target = Math.clamp(depthToBg(focusDepth()) - VH * .42f, 0, bgHeight() - VH);
        if (camTop < 0) camTop = target;
        camTop += (target - camTop) * Math.min(1, dt * 4.5f);
        // ambient fish swim
        for (float[] f : ambient) {
            f[1] += f[4] * f[2] * dt;
            if (f[1] < VX - f[3] - 40) { f[1] = VX - f[3] - 40; f[4] = 1; }
            if (f[1] > VX + VW + f[3] + 40) { f[1] = VX + VW + f[3] + 40; f[4] = -1; }
            if (Math.random() < dt * .04) f[4] = -f[4];
        }
        for (float[] m : motes) { m[1] -= m[2] * dt; m[0] += (float) Math.sin(m[4] + m[1] * .02) * dt * 6; if (m[1] < VY) { m[1] = VB; m[0] = VX + (float) Math.random() * VW; } }
    }

    private void stopSinking() {
        if (phase != SINKING || bite != null || stopped) return;
        FishingClient.stop(session, bobberDepth);
        sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.6f, .25f);
        // open water: the line ran out, the bobber hangs mid-water
        if (bottomless && bobberDepth >= maxDepth - .01f) lineEndAt = Util.getMeasuringTimeMs();
        // the bite frame arrives right away; keep sinking state until then
        phaseAt = Util.getMeasuringTimeMs();
        bite = null;
        stopped = true;
    }
    private boolean stopped;

    // ---- fish around the bobber while it waits (item: several silhouettes, the real one comes to bite)

    private void seedSwimmers(Bite b, boolean chest) {
        swimmers.clear();
        var r = new Random(b.seed() ^ 0x5717L);
        int n = 3 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            boolean real = i == 0 && !chest;
            int shape = real ? b.shape() : r.nextFloat() < .7f ? FishingProtocol.SHAPE_FISH : 1 + r.nextInt(4);
            float size = real ? Math.clamp(fishW * .6f, 46, 170) : 34 + r.nextFloat() * 70;
            float dy = (r.nextBoolean() ? 1 : -1) * (real ? 40 + r.nextFloat() * 70 : 45 + r.nextFloat() * 130);
            float x = VX + 60 + r.nextFloat() * (VW - 120);
            swimmers.add(new float[]{x, dy, 22 + r.nextFloat() * 34, r.nextBoolean() ? 1 : -1, r.nextFloat() * 6.28f, size, shape,
                    real ? .5f : .28f + .2f * r.nextFloat(), real ? 1 : 0});
        }
    }
    private float[] realSwimmer() {
        for (float[] f : swimmers) if (f[8] > 0) return f;
        return null;
    }
    private float swimY(float[] f, float t) {
        return Math.max(surfaceY() + f[5] * .3f + 10, bobberY() + f[1] + (float) Math.sin(t * .9f + f[4]) * 8);
    }
    private void stepSwimmers(float dt) {
        if (bite == null) return;
        float t = since(biteAt);
        for (float[] f : swimmers) {
            boolean real = f[8] > 0;
            if (real && approachCaptured) continue;
            f[0] += f[3] * f[2] * dt;
            float margin = f[5] * .5f + 20;
            if (f[0] < VX + margin) { f[0] = VX + margin; f[3] = 1; }
            if (f[0] > VX + VW - margin) { f[0] = VX + VW - margin; f[3] = -1; }
            if (Math.random() < dt * .18) f[3] = -f[3];
            if (real && Math.abs(f[0] - BOBBER_X) > 260) f[3] = Math.signum(BOBBER_X - f[0]);   // the biter lingers nearby
        }
        if (!approachCaptured && t >= bite.waitMs() / 1000f) {
            approachCaptured = true;
            float[] f = realSwimmer();
            if (f != null) {
                apX = f[0]; apDy = swimY(f, t) - bobberY(); apSize = f[5]; apAlpha = f[7];
                if (Math.abs(apX - BOBBER_X) > 30) fishSide = apX > BOBBER_X ? 1 : -1;
            } else { apX = BOBBER_X + fishSide * (VW * .6f + fishW); apDy = 0; apSize = fishW; apAlpha = 0; }
        }
    }

    // ---- bite + hook gauge

    private void stepBite() {
        if (bite == null) return;
        float t = since(biteAt);
        if (spooked || missed) {
            if (since(phaseAt) > 1.2f && phase == BITING) setPhase(WAIT_RESULT);
            return;
        }
        if (!hooked && !biteSounded && t >= biteTime) { biteSounded = true; biteSound(); }
        if (!hooked && t > biteTime + gaugeTime) { missed = true; startBiteFlee(); report(FishingProtocol.MISSED); }
    }
    /** The real bite: a clear "띵" (hat + pling), one more higher pling for a revealed legendary. */
    private void biteSound() {
        sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.6f, .85f);
        sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.5f, .6f);
        if (bite != null && bite.grade() == 3) sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 2f, .5f);
    }

    // hook gauge: the needle crosses the bar in bite.sweepMs; a rushing (legendary) needle runs x1.6 near the zone
    private float rushLo() { return Math.max(0, zoneStart - .12f); }
    private float rushHi() { return Math.min(1, zoneStart + zoneWidth + .12f); }
    private float gaugeDuration() {
        if (bite == null || !FishingProtocol.rushes(bite.zone())) return sweep;
        float a = rushLo(), b = rushHi();
        return sweep * (a + (b - a) / 1.6f + (1 - b));
    }
    private float cursorAt(float e) {
        if (e <= 0) return 0;
        if (bite == null || !FishingProtocol.rushes(bite.zone())) return Math.min(1, e / sweep);
        float a = rushLo(), b = rushHi(), ta = a * sweep, tb = ta + (b - a) * sweep / 1.6f;
        if (e < ta) return e / sweep;
        if (e < tb) return a + (e - ta) * 1.6f / sweep;
        return Math.min(1, b + (e - tb) / sweep);
    }
    private float cursor() { return bite == null ? 0 : cursorAt(since(biteAt) - biteTime); }
    private boolean biting() { return bite != null && since(biteAt) >= biteTime && !hooked && !missed && !spooked; }

    private void click() {
        float t = since(biteAt);
        if (t < biteTime) {
            // only a fish that is actually there (coming in / nibbling) can be scared; an empty bobber ignores clicks
            boolean chest = bite.shape() == FishingProtocol.SHAPE_CHEST;
            boolean visible = !chest && approachCaptured && targetFish(t)[6] > .3f;
            if (!visible && !teasing(t)) return;
            spooked = true; startBiteFlee();
            sound(SoundEvents.ENTITY_FISH_SWIM, 1.4f, .6f);
            report(FishingProtocol.SPOOKED);
            return;
        }
        float cur = cursor();
        if (cur >= zoneStart && cur <= zoneStart + zoneWidth) {
            hooked = true; hookClickAt = Util.getMeasuringTimeMs();
            startReel();
        } else {
            missed = true; startBiteFlee();
            report(FishingProtocol.MISSED);
        }
    }

    // ---- fleeing fish: fast start, accelerating, gone within ~0.4 s

    private void startBiteFlee() {
        phaseAt = Util.getMeasuringTimeMs();
        fleeShape = -1;
        if (bite == null || bite.shape() == FishingProtocol.SHAPE_CHEST) return;
        var f = targetFish(since(biteAt));
        flee(f[0], bobberDepth, f[1] - screenY(bobberDepth), f[0] >= BOBBER_X ? 1 : -1, f[2], f[5], bite.shape(), f[3]);
    }
    private void reelFlee() {
        fleeShape = -1;
        if (bite == null || bite.shape() == FishingProtocol.SHAPE_CHEST) return;
        boolean vanished = bite.pattern() == FishingProtocol.TELEPORT && since(phaseAt) < eventEnd;
        flee(fishX, fishDepth, 0, fishSide > 0 ? -1 : 1, vanished ? 0 : .95f, fishW, bite.shape(), 0);
    }
    private void flee(float x, float depth, float dy, float dir, float alpha, float w, int shape, float rot) {
        fleeAt = Util.getMeasuringTimeMs();
        fleeX = x; fleeDepth = depth; fleeDy = dy; fleeDir = dir; fleeAlpha = alpha; fleeW = w; fleeRot = rot;
        fleeShape = alpha > .02f ? shape : -1;
    }

    // ---- reel

    private void startReel() {
        // the button is still down from the hooking click: winding starts right away
        fishDepth = bobberDepth; stamina = 1; tension = .2f; overFor = 0; slackFor = 0; heat = 0; graceUsed = false; holding = true;
        fishX = BOBBER_X; wobble = 0; pulling = false; thumpAt = 0; attackAt = 0; mood = MOOD_NORMAL; moodAt = 0;
        jolt(6); // the hook is set: one firm jolt
        attacksDone = 0; heldMask = 0; wear[0] = wear[1] = 0;
        nextEvent = 1.5f + rng.nextFloat() * 2; eventEnd = 0; eventKind = 0;
        sound(SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, .7f, .6f);
        setPhase(REEL);
    }

    /** Fish pull multiplier from its struggle pattern at reel time t (seeded, so it is the same every frame). */
    private float pattern(float t) {
        int p = bite == null ? 0 : bite.pattern();
        if (t >= nextEvent && eventEnd < t) {
            float len = switch (p) { case FishingProtocol.DASH -> .8f; case FishingProtocol.DIVE -> 1.2f; case FishingProtocol.SURGE -> .55f; case FishingProtocol.TELEPORT -> .45f; default -> .6f; };
            eventEnd = t + len;
            eventKind = 1;
            nextEvent = eventEnd + 2.2f + rng.nextFloat() * 2.3f;
            if (p == FishingProtocol.TELEPORT) fishX = VX + 120 + rng.nextFloat() * (VW - 240);
            if (p == FishingProtocol.DASH || p == FishingProtocol.TELEPORT) sound(SoundEvents.ENTITY_GENERIC_SPLASH, 1.3f, .3f);
        }
        boolean event = t < eventEnd;
        return switch (p) {
            case FishingProtocol.CALM -> 1 + .1f * (float) Math.sin(t * 2);
            case FishingProtocol.THRASH -> 1 + .38f * (float) Math.max(0, Math.sin(t * 9));
            case FishingProtocol.DASH -> event ? 1.65f : .9f;
            case FishingProtocol.DIVE -> event ? 1.35f : .95f;
            case FishingProtocol.ZIGZAG -> 1 + .25f * (float) Math.sin(t * 4.2);
            case FishingProtocol.SURGE -> event ? 1.75f : (nextEvent - t < 1.2f ? .35f : .9f);
            case FishingProtocol.TELEPORT -> event ? 1.5f : 1;
            case FishingProtocol.DECOY -> 1 + .25f * (float) Math.max(0, Math.sin(t * 7));
            default -> 1;
        };
    }

    /**
     * Tension model (2026-10-20, tuned by simulation): winding brings line in and builds tension (plus a slow "heat" while
     * the reel keeps turning); letting go drops it to near zero within about a second, the fish only holding a little of
     * it while it pulls. A strong pull takes line even while you wind. Too slack for 1.6 s and the hook slips out.
     */
    private void stepReel(float dt) {
        if (bite == null || phase != REEL) return;
        float t = since(phaseAt);
        float p = bite.power(), strain = bite.strain();
        float mod = pattern(t);
        boolean event = t < eventEnd;
        float st = stamina;
        int nextMood = fishMood(t, event, mod, st);
        if (nextMood == MOOD_TIRED) mod = Math.min(mod, .6f); // a tired fish really pulls less
        if (nextMood != mood) {
            mood = nextMood; moodAt = Util.getMeasuringTimeMs();
            if (mood == MOOD_TIRED) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, .55f);
        }
        float force = (.20f + .075f * p) * strain * (.4f + .6f * st) * mod;
        float reelPower = gear == null ? 10 : gear.reelPower();
        float reel = FishingProtocol.reelSpeed(reelPower);
        if (background == FishingProtocol.BG_OCEAN) reel *= effect(FishingProtocol.ROD, "sea_reel");
        if (bite.magic()) reel *= effect(FishingProtocol.ROD, "magic_reel");
        float maxT = gear == null ? 100 : Math.max(10, gear.maxTension());
        float down = (.5f + .30f * p) * strain * (.4f + .6f * st) * mod * (bite.pattern() == FishingProtocol.DIVE && event ? 1.8f : 1);
        float rise = special(FishingProtocol.ROD, "tension_rise") ? effect(FishingProtocol.ROD, "tension_rise") : 1;
        if (holding) {
            if (event && mod > 1.25f) fishDepth += down * .35f * dt;          // it takes line even while you wind
            else fishDepth -= reel * .75f * (mood == MOOD_TIRED ? 1.3f : 1) * Math.max(.15f, 1 - .8f * force) * dt;
            heat += .18f * strain * dt;
            // winding into a pull strains the line; winding while it rests is cheap
            float target = ((.30f + force) * 100f / maxT + heat) * rise * (mood == MOOD_TIRED ? .8f : 1) + (mood == MOOD_RESIST ? .12f : 0);
            tension += (target - tension) * Math.min(1, 2.2f * dt);
        } else {
            fishDepth += down * dt;
            heat = Math.max(0, heat - .5f * dt);
            float target = force * .35f * 100f / maxT;
            tension += (target - tension) * Math.min(1, 3f * dt);
        }
        stamina -= (.012f + (event ? .05f : 0)) * dt;
        if (holding) stamina -= (.08f * reelPower / 10f) / (.5f + .12f * p) * dt;
        stamina = Math.max(0, stamina);
        // 손맛: a heavy thump when a pull starts (the panel shakes, see shake())
        long now = Util.getMeasuringTimeMs();
        boolean pull = event || mod > 1.25f;
        if (pull && !pulling && now - thumpAt > 450) { thumpAt = now; thumpAmp = 4 + 3 * Math.clamp((mod - 1) / .75f, 0, 1); }
        pulling = pull;
        // fish moves sideways
        wobble += dt;
        float sway = bite.pattern() == FishingProtocol.ZIGZAG ? 170 : 70;
        float targetX = BOBBER_X + (float) Math.sin(wobble * (bite.pattern() == FishingProtocol.ZIGZAG ? 2.6 : 1.1)) * sway;
        fishX += (targetX - fishX) * Math.min(1, dt * 2.5f);
        // reel clicks
        if (holding && now - reelClickSound > 140) { reelClickSound = now; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f + (float) Math.random() * .2f, .07f); }
        // the line creaks in the red zone, faster towards 100%
        if (tension >= .85f) {
            float red = Math.clamp((tension - .85f) / .15f, 0, 1);
            if (now - creakSound > (long) (520 - 340 * red)) {
                creakSound = now;
                sound(SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, 1.55f + .45f * red + (float) Math.random() * .08f, .10f + .10f * red);
            }
        }
        // fish attacks (the server charges their wear)
        attacks(t);
        if (phase != REEL) return;
        // outcomes
        if (tension >= 1) {
            overFor += dt;
            float limit = .15f;
            if (!graceUsed && special(FishingProtocol.LINE, "snap_grace")) limit += effect(FishingProtocol.LINE, "snap_grace");
            if (overFor > limit) { graceUsed = true; reelFlee(); report(FishingProtocol.SNAPPED); return; }
        } else { if (overFor > .15f) graceUsed = true; overFor = 0; }
        if (tension < .12f) {
            slackFor += dt;
            if (slackFor > 1.6f) { lossMessage = "바늘이 빠졌다.."; reelFlee(); report(FishingProtocol.ESCAPED); return; }
        } else slackFor = 0;
        float length = gear == null ? 15 : gear.lineLength();
        if (fishDepth >= length) { fishDepth = length; reelFlee(); report(FishingProtocol.ESCAPED); return; }
        if (fishDepth <= 0) { fishDepth = 0; jolt(6); report(FishingProtocol.CAUGHT); }
    }

    /** Resisting while it pulls; tired for a moment after each pull (and for good once stamina runs low). */
    private int fishMood(float t, boolean event, float mod, float st) {
        return FishMood.of(bite.pattern() == FishingProtocol.SURGE, t, event, mod, st, eventEnd, nextEvent);
    }
    private void jolt(float amp) { joltAt = Util.getMeasuringTimeMs(); joltAmp = amp; }

    /** Plays the bite's attacks that are due. Winding at that moment doubles the wear (heldMask bit i). */
    private void attacks(float t) {
        var list = bite.attacks();
        while (attacksDone < list.size() && t * 1000 >= list.get(attacksDone).atMs()) {
            int i = attacksDone++;
            var a = list.get(i);
            if (holding) heldMask |= 1 << i;
            int damage = a.damage() * (holding ? 2 : 1);
            int k = a.kind() == FishingProtocol.ATTACK_LINE ? 0 : 1;
            wear[k] += damage;
            attackAt = Util.getMeasuringTimeMs(); attackKind = a.kind(); attackDamage = damage;
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, .7f, .6f);
            sound(SoundEvents.ENTITY_GENERIC_SPLASH, k == 0 ? 1.5f : .9f, .4f);
            Piece piece = gear == null ? null : gear.piece(k == 0 ? FishingProtocol.LINE : FishingProtocol.ROD);
            if (damage > 0 && piece != null && !piece.fallback() && piece.maxDurability() > 0 && piece.durability() >= 0 && wear[k] >= piece.durability()) {
                if (k == 0) { lossMessage = "물고기가 낚싯줄을 끊어버렸다.."; reelFlee(); report(FishingProtocol.SNAPPED); }
                else { lossMessage = "낚싯대가 버티지 못했다.."; reelFlee(); report(FishingProtocol.ESCAPED); }
                return;
            }
        }
    }

    private void report(int outcome) {
        if (bite == null) return;
        FishingClient.result(session, bite.token(), outcome, (int) (Util.getMeasuringTimeMs() - biteAt), heldMask);
        if (outcome == FishingProtocol.CAUGHT || outcome == FishingProtocol.SNAPPED || outcome == FishingProtocol.ESCAPED) setPhase(WAIT_RESULT);
        holding = false;
    }

    @Override public void tick() {
        if (client == null || client.player == null || !client.player.isAlive()) { close(); return; }
        if (phase == WAIT_RESULT && since(phaseAt) > 8) { SharedNotifications.offer("fishing:timeout", "error", "낚시를 멈췄어요", "서버 응답이 늦어서 낚시를 멈췄어요."); close(); return; }
        if (phase == CASTING && since(phaseAt) > 10) { say("찌를 던지지 못했어요. 다시 클릭해 주세요."); setPhase(READY); }
    }

    // ------------------------------------------------------------------ input

    private CodexLayout layout() { return CodexLayout.fit(width, height); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        if (gearOpen) { gearClick(mx, my, button); return true; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button);
        // bottom slots: gear window
        for (int k = 0; k < 4; k++) if (inside(mx, my, SLOT_X[k], SLOT_Y, SLOT_W, SLOT_H)) {
            if (gearLocked()) say("낚시 중에는 장비를 바꿀 수 없어요.");
            else { gearTab = k; gearSelected = -1; FishingClient.openGear(); sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, .3f); }
            return true;
        }
        if (!inside(mx, my, VX, VY, VW, VH)) return super.mouseClicked(mouseX, mouseY, button);
        switch (phase) {
            case READY -> {
                if (session == 0) return true;
                FishingClient.recast(session); setPhase(CASTING);
                sound(SoundEvents.ENTITY_FISHING_BOBBER_THROW, .6f, .4f);
            }
            case SINKING -> stopSinking();
            case BITING -> { if (!spooked && !missed && !hooked && bite != null) click(); }
            case REEL -> holding = true;
            case RESULT -> {
                if (inside(mx, my, resultButtonX(), resultButtonY(), resultButtonW(), resultButtonH())) toReady();
            }
            default -> {}
        }
        return true;
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) holding = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (gearOpen) { gearScroll = Math.max(0, gearScroll - (int) Math.signum(v)); return true; }
        return super.mouseScrolled(mouseX, mouseY, h, v);
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) && phase == RESULT) { toReady(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    private void toReady() {
        result = null;
        setPhase(READY); pendingReady = false; camTop = -1; bite = null; stopped = false;
        lossMessage = null; fleeShape = -1; lineEndAt = 0; swimmers.clear();
    }
    private boolean gearLocked() { return phase == CASTING || phase == SINKING || phase == BITING || phase == REEL || phase == WAIT_RESULT; }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1000f, 0, .05f);
        lastFrame = now;
        if (!gearOpen) step(dt); else if (phase == REEL) step(dt);
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        hoverStack = ItemStack.EMPTY; hoverLabel = null;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            float t = (now - openedAt) / 1000f;
            float[] shake = gearOpen ? new float[2] : shake();
            m.push();
            try {
                m.translate(shake[0], shake[1], 0);
                panel(c, t, gearOpen ? -1 : mx, gearOpen ? -1 : my);
            } finally { m.pop(); }
            if (gearOpen) gearWindow(c, t, mx, my);
            // a reward without a readable item: a small label next to the cursor, above everything
            if (hoverLabel != null && hoverStack.isEmpty()) {
                m.push();
                try { m.translate(0, 0, 400); tooltip(c, hoverLabel, hoverX, hoverY); } finally { m.pop(); }
            }
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
        // item tooltip last, in screen coordinates, after the panel's batches are flushed (same as the inventory screens)
        if (!hoverStack.isEmpty() && entrance.ready()) {
            c.draw();
            if (!ItemTooltipRenderer.render(c, textRenderer, hoverStack, mouseX, mouseY)) c.drawItemTooltip(textRenderer, hoverStack, mouseX, mouseY);
        }
    }

    /**
     * Panel shake offset (reference px): a heavy thump when a pull starts and a small shake while it lasts (손맛),
     * a bigger jolt when the fish attacks the gear, and a fine tremble that grows through the red tension zone.
     */
    private float[] shake() {
        float ox = 0, oy = 0;
        if (phase != REEL && phase != WAIT_RESULT) return new float[]{0, 0};
        // one-shot jolt: hook set (start of REEL) and the catch (decays within .3 s; nothing else while waiting for the result)
        float j = since(joltAt);
        if (joltAt != 0 && j < .3f) { float k = 1 - j / .3f, a = joltAmp * k * k; ox += a * (float) Math.sin(j * 60); oy += a * .8f * (float) Math.cos(j * 47); }
        if (phase != REEL) return new float[]{ox, oy};
        float time = Util.getMeasuringTimeMs() / 1000f;
        float u = since(thumpAt);
        if (thumpAt != 0 && u < .25f) { float k = 1 - u / .25f, a = SHAKE * thumpAmp * k * k; ox += a * .55f * (float) Math.sin(u * 70); oy += a * (float) Math.cos(u * 52); }
        float v = since(attackAt);
        if (attackAt != 0 && v < .35f) { float k = 1 - v / .35f, a = SHAKE * 11 * k * k; ox += a * (float) Math.sin(v * 64); oy += a * .8f * (float) Math.cos(v * 49); }
        // no constant tremble while a pull lasts; only a line about to snap trembles
        if (tension >= .92f) {
            float r = Math.clamp((tension - .92f) / .08f, 0, 1), a = SHAKE * (.5f + 1.9f * r);
            ox += a * (float) Math.sin(time * (70 + 40 * r)); oy += a * .6f * (float) Math.cos(time * (83 + 40 * r));
        }
        return new float[]{ox, oy};
    }

    private void panel(DrawContext c, float t, double mx, double my) {
        c.enableScissor((int) VX, (int) VY, (int) (VX + VW), (int) (PY + PH - 10));
        try {
            rect(c, VX, VY, VW, PY + PH - VY, 0xFF081428);
            water(c, t);
            scene(c, t, mx, my);
            alarms(c, t);
        } finally { c.disableScissor(); }
        // bottom bar
        rect(c, VX, VB, VW, PY + PH - 12 - VB, 0xD9081428);
        hline(c, VX + 22, VX + VW - 22, VB, 0x55E7C77E);
        slots(c, t, mx, my);
        // a soft dark strip behind the title so it reads on bright water
        float dx = 472 + width("낚시", 25, TITLE) + 14;
        String tank = tankCapacity > 0 ? "아공간 수조 " + tankCount + " / " + tankCapacity : "";
        float stripEnd = Math.max(dx + 60, tank.isEmpty() ? 0 : dx + 18 + width(tank, 15, BODY) + 40) + 90;
        hgrad(c, VX, 19, stripEnd - VX, 40, 0x8C000000, 0x00000000);
        draw(c, FRAME_PANEL, PX, PY, PW, PH, 0xFFFFFFFF);
        text(c, "낚시", 472, 38, 25, TITLE_GOLD, TITLE, false);
        diamond(c, dx, 39, 5, GOLD);
        bloom(c, dx, 39, 14, 0xE7C77E, .25f + .1f * (float) Math.sin(t * 2));
        if (!tank.isEmpty()) {
            int tc = tankCount >= tankCapacity ? 0xFFFF7A6E : tankCount * 10 >= tankCapacity * 9 ? 0xFFF2D37A : 0xFFC9D6E4;
            text(c, tank, dx + 18, 40, 15, tc, BODY, false);
        }
        if (!notice.isBlank() && since(noticeAt) < 5) {
            float a = Math.min(1, since(noticeAt) * 4) * Math.min(1, (5 - since(noticeAt)) * 1.5f);
            float s = fit(notice, 18, BODY, VW - 120);
            card(c, BOBBER_X - width(notice, s, BODY) / 2 - 18, 62, width(notice, s, BODY) + 36, 34, argb(.75f * a, 0x0A1428), argb(.5f * a, 0xE7C77E), 1.2f);
            text(c, notice, BOBBER_X, 79, s, argb(a, 0xF3E9D3), BODY, true);
        }
    }

    // ---- water: picture, light shafts, motes, ambient fish

    private void water(DrawContext c, float t) {
        var bg = BACKGROUND[Math.clamp(background, 0, 3)];
        draw(c, bg, VX, VY - camTop, VW, bgHeight(), 0xFFFFFFFF);
        int light = switch (background) { case FishingProtocol.BG_OCEAN -> 0x8FD8FF; case FishingProtocol.BG_ICE -> 0xD5F6FF; case FishingProtocol.BG_CAVE -> 0x6FE6D0; default -> 0xA6FFE0; };
        float strength = background == FishingProtocol.BG_CAVE ? .45f : 1;
        float sy = surfaceY();
        // light shafts from the surface, slowly swaying
        for (int i = 0; i < 6; i++) {
            float x = VX + 90 + i * 128 + (float) Math.sin(t * .23 + i * 1.7) * 30;
            float a = (.06f + .04f * (float) Math.sin(t * .6 + i * 2.1)) * strength;
            var m = c.getMatrices(); m.push();
            try {
                m.translate(x, sy - 30, 0); m.multiply(new org.joml.Quaternionf().rotateZ(.18f + .04f * (float) Math.sin(t * .3 + i)));
                glow(c, RAY, -46, 0, 92, 640, argb(a, light));
            } finally { m.pop(); }
        }
        // caustic glimmer along the surface
        for (int i = 0; i < 9; i++) {
            float x = VX + 40 + i * 90 + (float) Math.sin(t * .8 + i * 3.1) * 22;
            bloom(c, x, sy + 4, 60, light, (.05f + .05f * (float) Math.sin(t * 1.7 + i * 1.3)) * strength);
        }
        // depth darkening
        float camDepth = (camTop + VH / 2 - surfaceBg()) / pxPerMeter();
        float dark = Math.clamp(camDepth / Math.max(30, depthRange()), 0, .55f);
        if (dark > .01f) HudMesh.quad(c, VX, VY, VW, VH, argb(dark * .6f, 0x020812), argb(dark * .6f, 0x020812));
        // open water: no sea floor, the deep fades into dark water below the end of the line
        if (bottomless) {
            float f0 = screenY(maxDepth) + 30, f1 = screenY(maxDepth + depthRange() * .22f);
            if (f0 < VB) {
                vgrad(c, VX, f0, VW, Math.max(1, f1 - f0), 0x00040A16, 0xFF040A16);
                if (f1 < VB) rect(c, VX, f1, VW, VB - f1 + 2, 0xFF040A16);
            }
        }
        // ambient fish (behind everything)
        for (float[] f : ambient) {
            float y = screenY(f[0]) + (float) Math.sin(t * .9 + f[5]) * 6;
            if (y < VY - f[3] || y > VB + f[3]) continue;
            int shape = (int) f[6];
            float depthFade = Math.clamp(1 - f[0] / depthRange() * .5f, .45f, 1);
            drawCentered(c, SHAPES[shape], f[1], y, f[3], f[3], argb(f[7] * depthFade, 0x061224), f[4] < 0, (float) Math.sin(t * 1.3 + f[5]) * .04f, false);
        }
        // drifting motes (bloom dust)
        for (float[] m : motes) {
            float tw = .5f + .5f * (float) Math.sin(t * 1.4 + m[4]);
            bloom(c, m[0], m[1], m[3] * 3.2f, light, .10f * tw * strength);
        }
        // surface ripples
        long now = Util.getMeasuringTimeMs();
        ripples.removeIf(r -> now - r[2] > 1600);
        for (float[] r : ripples) {
            float u = (now - r[2]) / 1600f;
            HudMesh.arc(c, r[0], surfaceY() + 2, 10 + u * 70, 2 * (1 - u) + .5f, (float) Math.PI, (float) Math.PI, argb((1 - u) * .7f, 0xDFF7FF));
        }
    }

    // ---- bobber, line, target fish, phase overlays

    private void scene(DrawContext c, float t, double mx, double my) {
        switch (phase) {
            case NONE -> idle(c, t, "낚싯대를 물에 던지면 낚시가 시작돼요.");
            case READY -> ready(c, t, mx, my);
            case CASTING -> { ready(c, t, -1, -1); centerHint(c, t, "던지는 중…", VY + VH * .55f); }
            case SINKING -> { sinking(c, t); ruler(c, t, bobberDepth); }
            case BITING -> { biting(c, t); ruler(c, t, bobberDepth); }
            case REEL -> { reeling(c, t); ruler(c, t, fishDepth); tensionGauge(c, t); bars(c, t); }
            case WAIT_RESULT -> {
                if (bite != null && (missed || spooked)) biting(c, t);
                else {
                    fleeing(c);
                    if (lossMessage != null) text(c, lossMessage, BOBBER_X, VY + VH * .62f, 28, argb(Math.min(1, since(phaseAt) * 2), 0xF3E9D3), TITLE, true);
                    waitDots(c, t);
                }
            }
            case RESULT -> result(c, t, mx, my);
            default -> {}
        }
    }

    /** Red-zone vignette, the attack flash and its floating text (inside the water view, over the scene). */
    private void alarms(DrawContext c, float t) {
        if (phase == REEL && tension >= .85f) {
            float r = Math.clamp((tension - .85f) / .15f, 0, 1);
            float pulse = .5f + .5f * (float) Math.sin(t * (7 + 9 * r));
            float a = (.18f + .22f * r) * (.55f + .45f * pulse), bw = 70 + 30 * r;
            int edge = argb(a, 0xFF2A1E), clear = edge & 0xFFFFFF;
            hgrad(c, VX, VY, bw, VH, edge, clear);
            hgrad(c, VX + VW - bw, VY, bw, VH, clear, edge);
            vgrad(c, VX, VY, VW, bw * .8f, edge, clear);
            vgrad(c, VX, VB - bw * .8f, VW, bw * .8f, clear, edge);
        }
        if (attackAt != 0 && (phase == REEL || phase == WAIT_RESULT)) {
            float u = since(attackAt);
            if (u < .3f) rect(c, VX, VY, VW, VH, argb(.32f * (1 - u / .3f), 0xFF2A1E));
            if (u < 1.6f) {
                float a = Math.min(1, u * 6) * Math.min(1, (1.6f - u) * 2.5f);
                float y = VY + VH * .36f - u * 22, pop = u < .12f ? 1.25f - u * 2 : 1;
                boolean line = attackKind == FishingProtocol.ATTACK_LINE;
                bloom(c, BOBBER_X, y, 130, 0xFF5A4A, .25f * a);
                outlined(c, line ? "줄을 물어뜯었다!" : "낚싯대를 거세게 흔든다!", BOBBER_X, y, 30 * pop, argb(a, 0xFF8A7A), TITLE);
                text(c, (line ? "낚싯줄 −" : "낚싯대 −") + attackDamage, BOBBER_X, y + 36, 20, argb(a, 0xFFE4E9EF), BOLD, true);
            }
        }
    }

    private void idle(DrawContext c, float t, String msg) { centerHint(c, t, msg, VY + VH * .45f); }

    private void centerHint(DrawContext c, float t, String msg, float y) {
        float a = .75f + .25f * (float) Math.sin(t * 2.2);
        text(c, msg, BOBBER_X, y, 21, argb(a, 0xE4E9EF), BODY, true);
    }

    private void ready(DrawContext c, float t, double mx, double my) {
        float sy = surfaceY();
        float bob = (float) Math.sin(t * 2.4) * 3;
        // line from the rod tip to the float
        line(c, BOBBER_X - 210, VY - 10, BOBBER_X, sy - 18 + bob, .55f);
        bloom(c, BOBBER_X, sy - 6 + bob, 34, 0xFFE2C8, .22f);
        drawCentered(c, BOBBER, BOBBER_X, sy - 10 + bob, 46, 46, 0xFFFFFFFF, false, (float) Math.sin(t * 1.9) * .08f, false);
        if ((int) (t * 10) % 18 == 0 && ripples.size() < 3) ripples.add(new float[]{BOBBER_X, sy, Util.getMeasuringTimeMs()});
        if (phase == READY) {
            boolean over = inside(mx, my, VX, VY, VW, VH);
            float y = VY + VH * .52f;
            draw(c, MOUSE_LEFT, BOBBER_X - 105, y - 22, 40, 40, 0xFFFFFFFF);
            text(c, "클릭해서 던지기", BOBBER_X - 58, y - 2, 22, over ? 0xFFFFF4DC : TEXT, BOLD, false);
            bloom(c, BOBBER_X - 85, y - 2, 30, 0xFF6A6A, .12f + .08f * (float) Math.sin(t * 3));
        }
    }

    private void line(DrawContext c, float x0, float y0, float x1, float y1, float alpha) {
        HudMesh.line(c, x0, y0, x1, y1, 4.5f, argb(alpha * .16f, 0xCFEFFF));
        HudMesh.line(c, x0, y0, x1, y1, 1.3f, argb(alpha, 0xE9F6FF));
    }

    /** Line from above down to the bobber; taut and humming a little when it hangs at the end of the line. */
    private void bobberLine(DrawContext c, float x, float y) {
        if (bottomless && lineEndAt != 0) {
            float u = since(lineEndAt);
            float vib = (float) Math.sin(u * 42) * 1.6f * (float) Math.exp(-2.2f * u);
            HudMesh.line(c, BOBBER_X, VY - 10, x + vib, y, 5.5f, argb(.2f, 0xCFEFFF));
            HudMesh.line(c, BOBBER_X, VY - 10, x + vib, y, 1.6f, 0xFFF2FAFF);
        } else line(c, BOBBER_X, VY - 10, x, y, .7f);
    }
    /** "낚싯줄이 끝까지 풀렸다" for a moment after the line ran out in open water. */
    private void lineEndHint(DrawContext c, float y) {
        if (!bottomless || lineEndAt == 0) return;
        float u = since(lineEndAt);
        if (u > 2.6f) return;
        float a = Math.min(1, u * 4) * Math.min(1, (2.6f - u) * 2);
        text(c, "낚싯줄이 끝까지 풀렸다", BOBBER_X, y + 92, 18, argb(a, 0xF2D9A0), BOLD, true);
    }

    private void glowBobber(DrawContext c, float t, float x, float y, float pulse) {
        bloom(c, x, y, 30 + 8 * pulse, 0x55D8FF, .45f + .25f * pulse);
        bloom(c, x, y, 12, 0xE6FBFF, .8f);
        HudMesh.disk(c, x, y, 5.5f, 0xFF7FE3FF);
        HudMesh.disk(c, x, y, 2.6f, 0xFFF2FDFF);
    }

    private void sinking(DrawContext c, float t) {
        float y = bobberY();
        bobberLine(c, BOBBER_X, y);
        glowBobber(c, t, BOBBER_X, y, .5f + .5f * (float) Math.sin(t * 5));
        lineEndHint(c, y);
        if (bite == null && !stopped) {
            // chevrons under the bobber
            for (int i = 0; i < 2; i++) {
                float cy = y + 30 + i * 9 + (t * 14 % 9);
                int col = argb(.75f - i * .25f, 0x7FE3FF);
                HudMesh.line(c, BOBBER_X - 6, cy, BOBBER_X, cy + 6, 1.8f, col);
                HudMesh.line(c, BOBBER_X, cy + 6, BOBBER_X + 6, cy, 1.8f, col);
            }
            draw(c, MOUSE_LEFT, BOBBER_X + 38, y - 26, 38, 38, 0xFFFFFFFF);
            text(c, "멈추기", BOBBER_X + 57, y + 34, 18, TEXT, BOLD, true);
        }
    }

    /**
     * The biting fish: x, y, alpha, rotation, mirrored (1/0), size, visible alpha (0 while it only swims around with
     * the others; from the approach on it is the drawn alpha).
     */
    private float[] targetFish(float t) {
        float y0 = bobberY();
        float near = fishW * .55f + 18, nose = fishW * .47f;
        float side = fishSide, wait = bite.waitMs() / 1000f;
        if (t < wait || !approachCaptured) {
            float[] f = realSwimmer();
            if (f == null) return new float[]{BOBBER_X + side * (VW * .6f + fishW), y0, 0, 0, side > 0 ? 1 : 0, fishW, 0};
            return new float[]{f[0], swimY(f, t), f[7], (float) Math.sin(t * 1.3f + f[4]) * .04f, f[3] < 0 ? 1 : 0, f[5], 0};
        }
        float x, y = y0 + (float) Math.sin(t * 1.7) * 4 + fishW * .04f, alpha = 1, rot = 0, size = fishW;
        if (t < approachEnd) {
            float u = Math.clamp((t - wait) / Math.max(.1f, approachEnd - wait), 0, 1);
            float e = 1 - (1 - u) * (1 - u);
            x = apX + (BOBBER_X + side * near - apX) * e;
            y = y0 + apDy + (y - y0 - apDy) * e;
            size = apSize + (fishW - apSize) * e;
            alpha = apAlpha + (1 - apAlpha) * Math.min(1, u * 1.6f);
        } else {
            x = BOBBER_X + side * (near + (float) Math.sin(t * 1.3) * 10);
            for (float tt : teaseTimes) {
                float d = t - tt;
                if (d >= 0 && d < .8f) {
                    float k = d < .22f ? d / .22f : d < .37f ? 1 : 1 - (d - .37f) / .43f;
                    x = BOBBER_X + side * (near - (near - nose) * k);
                }
            }
            if (t >= warningStart) {
                float k = Math.min(1, (t - warningStart) / Math.max(.1f, warningEnd - warningStart));
                x = BOBBER_X + side * (near - (near - nose - 4) * k);
            }
            if (t >= biteTime) x = BOBBER_X + side * nose;
            rot = (float) Math.sin(t * 2.1) * .03f;
        }
        return new float[]{x, y, alpha, rot, side > 0 ? 1 : 0, size, alpha};
    }

    private boolean teasing(float t) {
        for (float tt : teaseTimes) { float d = t - tt; if (d >= .18f && d < .45f) return true; }
        return false;
    }

    /** Silhouettes swimming around the bobber (darker / smaller than the biter once it comes in). */
    private void swimmers(DrawContext c, float t) {
        for (float[] f : swimmers) {
            if (f[8] > 0) continue;
            float y = swimY(f, t);
            drawCentered(c, SHAPES[(int) f[6]], f[0], y, f[5], f[5], argb(f[7], 0x050B16), f[3] < 0, (float) Math.sin(t * 1.3f + f[4]) * .05f, false);
        }
    }

    /** A fish darting away: fast from the start, accelerating, gone within ~0.4 s. */
    private void fleeing(DrawContext c) {
        if (fleeShape < 0) return;
        float u = since(fleeAt);
        if (u > .42f) return;
        float d = 520 * u + 2600 * u * u;
        float x = fleeX + fleeDir * d, y = screenY(fleeDepth) + fleeDy - 26 * u;
        float a = fleeAlpha * Math.max(0, 1 - u / .4f);
        float stretch = 1 + Math.min(.22f, u * .7f);
        drawCentered(c, SHAPES[fleeShape], x, y, fleeW * stretch, fleeW / stretch, argb(.92f * a, 0x040C1A), fleeDir < 0, fleeRot, false);
        for (int i = 0; i < 4; i++) {
            float bx = x - fleeDir * (fleeW * .5f + 10 + i * 16), by = y + (float) Math.sin(i * 2.3f + u * 20) * 6;
            HudMesh.arc(c, bx, by, 2.5f + i % 2, 1.1f, 0, (float) (Math.PI * 2), argb(.55f * a, 0xDFF7FF));
        }
    }

    private void hook(DrawContext c, float lineX, float lineY, float size, int tint) {
        draw(c, HOOK, lineX - HOOK_EYE_X * size, lineY - HOOK_EYE_Y * size, size, size, tint);
    }

    private void biting(DrawContext c, float t0) {
        if (bite == null) { sinking(c, t0); return; }
        float t = since(biteAt);
        boolean chest = bite.shape() == FishingProtocol.SHAPE_CHEST;
        float y = bobberY();
        float dip = teasing(t) ? 5 : 0;
        float tremble = t >= warningStart && t < biteTime ? (float) Math.sin(t * 70) * 2.2f : 0;
        if (biting() || hooked) dip = 14;
        if (missed || spooked) dip = 0;
        float by = y + dip;
        swimmers(c, t);
        // target fish
        if (missed || spooked) fleeing(c);
        else if (!chest) {
            var f = targetFish(t);
            float fx = f[0], fy = f[1], a = f[2];
            boolean mirror = f[4] > 0;     // silhouettes face right: on the right side it must face left
            if (bite.grade() >= 0 && f[6] > 0) bloom(c, fx, fy, f[5] * .62f, GRADE[bite.grade()] & 0xFFFFFF, .22f * f[6]);
            int body = f[6] > 0 ? 0x040C1A : 0x050B16;
            drawCentered(c, SHAPES[bite.shape()], fx, fy, f[5], f[5], argb((f[6] > 0 ? .92f : 1) * a, body), mirror, f[3], false);
            if (teasing(t)) bloom(c, BOBBER_X, by, 22, 0xBFEFFF, .35f);
        }
        if (missed || spooked) {
            line(c, BOBBER_X, VY - 10, BOBBER_X, by, .7f);
            hook(c, BOBBER_X, by, 30, 0xFFE4E9EF);
            String msg = spooked ? "물고기가 놀라 도망갔다.." : "미끼만 먹고 도망갔다..";
            text(c, msg, BOBBER_X, VY + VH * .62f, 28, argb(Math.min(1, since(phaseAt) * 2), 0xF3E9D3), TITLE, true);
            return;
        }
        bobberLine(c, BOBBER_X + tremble, by);
        float pulse = t >= warningStart ? .5f + .5f * (float) Math.sin(t * 18) : .5f + .5f * (float) Math.sin(t * 5);
        glowBobber(c, t, BOBBER_X + tremble, by, pulse);
        lineEndHint(c, by);
        if (t >= warningStart && t < biteTime) {
            float u = (t - warningStart) / Math.max(.1f, biteTime - warningStart);
            glow(c, RING, BOBBER_X - 26 - u * 20, by - 26 - u * 20, 52 + u * 40, 52 + u * 40, argb(.55f * (1 - u), 0x9FE9FF));
        }
        if (biting()) {
            // !! + timing bar
            float since = t - biteTime;
            float pop = since < .12f ? 1.4f - since * 3 : 1;
            bloom(c, BOBBER_X + 18, by - 40, 46, 0xFFE08A, .45f);
            outlined(c, "!!", BOBBER_X + 20, by - 42, 34 * pop, 0xFFFFE38C, BOLD);
            draw(c, MOUSE_LEFT, BOBBER_X + 40, by - 24, 34, 34, 0xFFFFFFFF);
            float bx = BOBBER_X - 131, bw = 262, byy = by + 72;
            card(c, bx, byy, bw, 20, 0xCC0A1428, 0xFFE7C77E, 1.6f);
            float zx = bx + 4 + (bw - 8) * zoneStart, zw = (bw - 8) * zoneWidth;
            bloom(c, zx + zw / 2, byy + 10, zw * .9f, 0x3FD8FF, .35f);
            rect(c, zx, byy + 4, zw, 12, 0xFF2FC8E8);
            float cx = bx + 4 + (bw - 8) * cursor();
            rect(c, cx - 1.6f, byy - 3, 3.2f, 26, 0xFFFFFFFF);
            bloom(c, cx, byy + 10, 16, 0xFFFFFF, .4f);
        } else if (t < bite.waitMs() / 1000f) {
            float a = .35f + .2f * (float) Math.sin(t * 2.5);
            text(c, "입질을 기다리는 중…", BOBBER_X, by + 60, 18, argb(a, 0xBFD3E8), BODY, true);
        }
        if (hooked) waitDots(c, t);
    }

    private void waitDots(DrawContext c, float t) {
        for (int i = 0; i < 3; i++) HudMesh.disk(c, BOBBER_X - 16 + i * 16, VY + VH * .6f, 4, argb(.35f + .5f * (float) Math.max(0, Math.sin(t * 6 - i)), 0xE4E9EF));
    }

    private void reeling(DrawContext c, float t0) {
        float t = since(phaseAt);
        boolean chest = bite.shape() == FishingProtocol.SHAPE_CHEST;
        float fy = screenY(fishDepth);
        // gentle sway only (was a constant ±2..7 px tremble); stronger only near the snapping point
        float shake = (float) Math.sin(t * (holding ? 14 : 8)) * (.6f + Math.max(0, tension - .85f) * 10);
        // an attack jolts the fish
        float hit = since(attackAt);
        if (attackAt != 0 && hit < .35f) shake += SHAKE * 22 * (1 - hit / .35f) * (float) Math.sin(hit * 50);
        boolean vanished = bite.pattern() == FishingProtocol.TELEPORT && t < eventEnd && eventEnd - t > .25f;
        // decoys
        if (bite.pattern() == FishingProtocol.DECOY) for (int i = 0; i < 2; i++) {
            float dx = (float) Math.sin(t * (1.1 + i * .4) + i * 2) * 220;
            drawCentered(c, SHAPES[bite.shape()], BOBBER_X + dx, fy + 50 * (i == 0 ? -1 : 1), fishW * .9f, fishW * .9f, 0x88040C1A, dx > 0, 0, false);
        }
        float mouthX = fishX + (chest ? 0 : fishW * .45f * (fishSide > 0 ? -1 : 1));
        line(c, BOBBER_X - 180, VY - 10, mouthX, fy + shake * .3f, .8f);
        if (!vanished) {
            if (bite.grade() >= 0) bloom(c, fishX, fy, fishW * .6f, GRADE[bite.grade()] & 0xFFFFFF, .2f);
            float rot = (float) Math.sin(t * 9) * (.05f + tension * .06f);
            if (attackAt != 0 && hit < .35f) rot += .35f * (1 - hit / .35f) * (float) Math.sin(hit * 38);
            // mood: tired = pale blue glow + calm silhouette, resisting = red glow (the label below says it in words)
            float moodIn = Math.min(1, since(moodAt) / .2f);
            if (mood == MOOD_TIRED) bloom(c, fishX, fy, fishW * .75f, 0x7FD8FF, .45f * moodIn);
            else if (mood == MOOD_RESIST) bloom(c, fishX, fy, fishW * .7f, 0xFF4A3A, .35f * moodIn);
            int body = mood == MOOD_TIRED ? 0xF2223A55 : mood == MOOD_RESIST ? 0xF2330A0C : 0xF2040C1A;
            if (chest) drawCentered(c, SHAPES[FishingProtocol.SHAPE_CHEST], fishX + shake, fy, fishW, fishW, 0xEE050B16, false, rot, false);
            else drawCentered(c, SHAPES[bite.shape()], fishX + shake, fy, fishW, fishW, body, fishSide > 0, rot, false);
            if (!chest && mood != MOOD_NORMAL) {
                boolean tired = mood == MOOD_TIRED;
                float pop = since(moodAt) < .15f ? 1.25f - since(moodAt) * 1.6f : 1;
                outlined(c, tired ? "지쳤다! 지금 감기" : "저항 중 · 풀어 주기", fishX, fy - fishW * .55f - 18, 17 * pop, tired ? 0xFF9FEAFF : 0xFFFF8C7E, BOLD);
            }
            // struggle lines
            if (!chest) for (int i = 0; i < 3; i++) {
                float a = .25f + .35f * (float) Math.max(0, Math.sin(t * 10 + i * 2));
                float ox = fishX + (fishSide > 0 ? 1 : -1) * fishW * .48f, oy = fy - fishW * .12f + i * 10;
                HudMesh.arc(c, ox, oy, 12 + i * 6, 1.6f, -.6f, 1.2f, argb(a, 0xDCEBFF));
            }
        } else if (special(FishingProtocol.ROD, "reveal_teleport")) {
            bloom(c, fishX, fy, fishW * .45f, 0xFFE08A, .35f + .2f * (float) Math.sin(t * 20));
            glow(c, RING, fishX - 30, fy - 30, 60, 60, 0xAAFFE08A);
        }
        // hint
        draw(c, holding ? MOUSE_LEFT : MOUSE, 512 + 433 - 433, 133, 44, 44, 0xFFFFFFFF);
        HudMesh.line(c, 570, 140, 570, 156, 2.2f, 0xFFFF5A5A); HudMesh.line(c, 564, 150, 570, 156, 2.2f, 0xFFFF5A5A); HudMesh.line(c, 576, 150, 570, 156, 2.2f, 0xFFFF5A5A);
        text(c, "감기 (누르기)", 588, 147, 15, holding ? 0xFFFFFFFF : MUTED, BODY, false);
        HudMesh.line(c, 570, 168, 570, 184, 2.2f, 0xFFE4E9EF); HudMesh.line(c, 564, 174, 570, 168, 2.2f, 0xFFE4E9EF); HudMesh.line(c, 576, 174, 570, 168, 2.2f, 0xFFE4E9EF);
        text(c, "풀기 (떼기)", 588, 176, 15, holding ? MUTED : 0xFFFFFFFF, BODY, false);
    }

    /** Metres covered by the depth ruler: down to the floor when it is in reach, else the open-water view. */
    private float rulerRange() {
        if (!bottomless) return Math.max(1, Math.max(maxDepth, Math.min(floorDepth, maxDepth + .5f)));
        float view = Math.max(maxDepth * 1.6f, maxDepth + 12);
        return floorDepth < FishingProtocol.MAX_DEPTH && floorDepth <= view ? floorDepth : view;
    }

    private void ruler(DrawContext c, float t, float current) {
        float top = RULER_TOP, bottom = RULER_BOTTOM, range = rulerRange(), span = bottom - top;
        vline(c, RULER_X, top, bottom, 0xB0DCE6F0);
        boolean floorIn = floorDepth < FishingProtocol.MAX_DEPTH && floorDepth <= range + .01f;
        float lineY = top + span * Math.clamp(maxDepth / range, 0, 1), floorY = top + span * Math.clamp(floorDepth / range, 0, 1);
        // rows taken by the line-end / floor labels (left side)
        var busy = new ArrayList<Float>();
        if (bottomless) busy.add(lineY);
        if (floorIn) busy.add(floorY);
        // zones: 얕은 0-10 / 중층 10-25 / 깊은 25-50 / 심해 50+
        float[] edges = {0, 10, 25, 50, Float.MAX_VALUE};
        String[] names = {"얕은", "중층", "깊은", "심해"};
        for (int z = 0; z < 4 && edges[z] < range; z++) {
            float lo = edges[z], hi = Math.min(edges[z + 1], range);
            if (z > 0) hline(c, RULER_X - 16, RULER_X - 2, top + span * lo / range, 0x8CA8D8FF);
            float my = top + span * (lo + hi) / 2 / range;
            boolean free = (hi - lo) / range * span > 24;
            for (float b : busy) if (Math.abs(b - my) < 18) free = false;
            if (free) FishingArt.right(c, names[z], RULER_X - 10, my, 12, 0x99A8C4DC, BODY);
        }
        // scale (right)
        float step = range <= 12 ? 2 : range <= 30 ? 5 : range <= 60 ? 10 : 20;
        for (float d = 0; d <= range + .01f; d += step / 2) {
            float y = top + span * d / range;
            boolean major = Math.abs(d / step - Math.round(d / step)) < .01f;
            hline(c, RULER_X - (major ? 7 : 4), RULER_X + (major ? 7 : 4), y, 0xB0DCE6F0);
            if (major && d > 0 && range - d > step * .45f) text(c, num(d) + "m", RULER_X + 15, y, 15, 0xD0E4E9EF, BODY, false);
        }
        hline(c, RULER_X - 7, RULER_X + 7, bottom, 0xB0DCE6F0);
        text(c, "0m", RULER_X + 15, top, 15, 0xD0E4E9EF, BODY, false);
        // end of the line (gold)
        if (bottomless) {
            HudMesh.line(c, RULER_X - 11, lineY, RULER_X + 11, lineY, 2.4f, 0xFFE7C77E);
            diamond(c, RULER_X, lineY, 3.5f, GOLD);
            FishingArt.right(c, "줄 끝 " + num(Math.round(maxDepth * 10) / 10f) + "m", RULER_X - 15, lineY, 14, 0xFFF2D9A0, BOLD);
        }
        // the real bottom (sand) or how deep it goes below the ruler
        if (floorIn) {
            HudMesh.line(c, RULER_X - 11, floorY, RULER_X + 11, floorY, 3f, 0xFFD9B77A);
            FishingArt.right(c, "바닥 " + num(Math.round(floorDepth * 10) / 10f) + "m", RULER_X - 15, floorY + (bottomless && Math.abs(floorY - lineY) < 16 ? 16 : 0), 14, 0xFFE6CB92, BOLD);
        } else {
            String deep = floorDepth >= FishingProtocol.MAX_DEPTH ? "50m 이상" : num(Math.round(floorDepth)) + "m";
            FishingArt.right(c, "▼ 바닥 " + deep, RULER_X + 30, bottom + 20, 14, 0xFFE6CB92, BOLD);
        }
        float y = top + span * Math.clamp(current / range, 0, 1);
        bloom(c, RULER_X, y, 16, 0x55D8FF, .55f);
        HudMesh.disk(c, RULER_X, y, 4.5f, 0xFF7FE3FF);
        text(c, "현재 " + Math.round(current) + "m", RULER_X + 14, y, 14, 0xFFFFFFFF, BOLD, false);
    }
    private static String num(float v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 10) / 10f); }

    /** Tension gauge (left side). In the red zone it shakes and blinks, faster towards 100%. */
    private void tensionGauge(DrawContext c, float t) {
        float red = tension >= .85f ? Math.clamp((tension - .85f) / .15f, 0, 1) : -1;
        float x = GAUGE_X + (red >= 0 ? (float) Math.sin(t * (60 + 30 * red)) * SHAKE * (1 + 2 * red) : 0), top = GAUGE_TOP, bottom = GAUGE_BOTTOM, h = bottom - top;
        text(c, "장력", x, top - 26, 17, TEXT, BOLD, true);
        // zones: 안전 (bottom 40%), 주의, 위험 (top 25%)
        rect(c, x - 2.2f, top + h * .25f, 4.4f, h * .35f, 0xFFE7C24E);
        rect(c, x - 2.2f, top + h * .6f, 4.4f, h * .4f, 0xFF3FC86A);
        rect(c, x - 2.2f, top, 4.4f, h * .25f, 0xFFE8483E);
        if (red >= 0) {
            float blink = (float) Math.sin(t * Math.PI * 2 * (4 + 6 * red)) > 0 ? 1 : .25f;
            rect(c, x - 4.5f, top, 9, h, argb(.55f * blink, 0xFF3A2E));
            bloom(c, x, top + h * .12f, 34 + 10 * red, 0xFF3A2E, .35f * blink);
        }
        text(c, "위험", x + 13, top + h * .12f, 13, 0xFFFF7A6E, BODY, false);
        text(c, "주의", x + 13, top + h * .42f, 13, 0xFFF2D37A, BODY, false);
        text(c, "안전", x + 13, top + h * .8f, 13, 0xFF7FE59A, BODY, false);
        float v = Math.clamp(tension, 0, 1.05f);
        float y = bottom - h * Math.min(1, v);
        int col = v > .75f ? 0xFF6A5A : v > .4f ? 0xF2D37A : 0x7FE59A;
        bloom(c, x, y, 18 + (v > .75f ? 8 * (float) Math.sin(t * 25) : 0), col, .5f);
        rect(c, x - 12, y - 1.6f, 24, 3.2f, red >= 0 && (float) Math.sin(t * Math.PI * 2 * (4 + 6 * red)) > 0 ? 0xFFFF6A5A : 0xFFFFFFFF);
        text(c, Math.round(Math.min(1, v) * 100) + "%", x + 46, y, 15, 0xFFFFFFFF, BOLD, false);
    }

    private void bars(DrawContext c, float t) {
        float length = gear == null ? 15 : gear.lineLength();
        bar(c, "체력", stamina, 633, 0x4FD7EC);
        bar(c, "낚싯줄", Math.clamp(1 - fishDepth / Math.max(1, length), 0, 1), 690, 0xE9F2FF);
    }
    private void bar(DrawContext c, String label, float value, float y, int rgb) {
        float bx = 840, bw = 287;
        text(c, label, 774, y, 17, TEXT, BOLD, false);
        rect(c, bx, y - 2, bw, 4, 0x55DCE6F0);
        rect(c, bx, y - 2, bw * value, 4, 0xFF000000 | rgb);
        bloom(c, bx + bw * value, y, 10, rgb, .55f);
        FishingArt.right(c, Math.round(value * 100) + "%", 1185, y, 17, TEXT, BOLD);
    }

    // ---- result

    private float resultButtonX() { return isLossResult() ? 728 : 697; }
    private float resultButtonY() { return isLossResult() ? 640 : result != null && result.outcome() == FishingProtocol.TREASURE ? 696 : 646; }
    private float resultButtonW() { return isLossResult() ? 216 : 278; }
    private float resultButtonH() { return isLossResult() ? 57 : 65; }
    private boolean isLossResult() { return result != null && result.outcome() != FishingProtocol.CAUGHT && result.outcome() != FishingProtocol.TREASURE; }

    private void result(DrawContext c, float t, double mx, double my) {
        if (result == null) return;
        float u = since(resultAt);
        float in = Math.min(1, u * 3);
        int o = result.outcome();
        if (o == FishingProtocol.CAUGHT) {
            int gc = GRADE[result.grade()] & 0xFFFFFF;
            float cx = 835, cy = 275;
            // bloom burst: rotating rays for 유일 / 전설, soft halo for all
            bloom(c, cx, cy, 250, gc, .18f * in + .05f * (float) Math.sin(t * 2));
            if (result.grade() >= 2) for (int i = 0; i < 10; i++) {
                var m = c.getMatrices(); m.push();
                try {
                    m.translate(cx, cy, 0); m.multiply(new org.joml.Quaternionf().rotateZ((float) (t * .25 + i * Math.PI / 5)));
                    glow(c, RAY, -24, 0, 48, 230, argb((result.grade() == 3 ? .13f : .08f) * in, gc));
                } finally { m.pop(); }
            }
            brackets(c, 632, 160, 406, 235, 26, argb(in, gc));
            var artwork = FishingArt.fish(result.fishId());
            float pop = u < .25f ? .7f + 1.4f * u : u < .45f ? 1.05f - (u - .25f) * .25f : 1;
            if (artwork != null) drawCentered(c, artwork, cx, cy, 300 * pop, 300 * pop, argb(in, 0xFFFFFF), false, 0, false);
            else {
                bloom(c, cx, cy, 160, gc, .18f * in);
                drawCentered(c, SHAPES[result.shape()], cx, cy, 380 * pop, 380 * pop, argb(in, 0x0A1530), false, 0, false);
            }
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4 + t * .6;
                float tw = .5f + .5f * (float) Math.sin(t * 3 + i);
                HudMesh.star(c, cx + (float) Math.cos(a) * 215, cy + (float) Math.sin(a) * 120, 4 + 3 * tw, argb(.6f * tw * in, gc));
            }
            text(c, grade(result.grade()), cx, 427, 20, argb(in, gc), BOLD, true);
            bloom(c, cx, 478, 140, gc, .10f * in);
            text(c, result.name(), cx, 478, fit(result.name(), 44, TITLE, 520), argb(in, 0xF3E9D3), TITLE, true);
            text(c, cm(result.sizeCm()), cx, 528, 21, argb(in, 0xDCE3EA), BODY, true);
            var badges = new ArrayList<String>();
            if (result.record()) badges.add("최고 기록 갱신");
            if (result.newEntry()) badges.add("도감 신규 등록");
            float total = 0;
            for (String b : badges) total += width(b, 17, BOLD) + 62;
            float x = cx - (total - 24 + (badges.size() > 1 ? 0 : 0)) / 2;
            for (String b : badges) {
                float w = width(b, 17, BOLD) + 50;
                card(c, x, 562, w, 36, argb(.85f * in, 0x161E3E), argb(in, gc), 1.4f);
                diamond(c, x + 20, 580, 5, argb(in, gc));
                text(c, b, x + 34, 580, 17, argb(in, 0xF3E9D3), BOLD, false);
                x += w + 12;
            }
        } else if (o == FishingProtocol.TREASURE) {
            int gc = GRADE[result.grade()] & 0xFFFFFF;
            float cx = 835, cy = 290;
            bloom(c, cx, cy, 230, gc, .2f * in);
            bloom(c, cx, cy - 30, 90, 0xFFE6A0, .35f * in + .1f * (float) Math.sin(t * 3));
            brackets(c, 638, 127, 396, 336, 26, argb(in, gc));
            drawCentered(c, chest(result.grade(), true), cx, cy, 300, 300, argb(in, 0xFFFFFF), false, 0, false);
            text(c, grade(result.grade()), cx, 476, 20, argb(in, gc), BOLD, true);
            text(c, "보물 발견", cx, 524, 42, argb(in, 0xF3E9D3), TITLE, true);
            // reward slots: hovering one shows the normal item tooltip (drawn last, see render)
            int n = rewardIcons.size();
            float w = 111, gap = 20, x = cx - (n * w + (n - 1) * gap) / 2;
            for (int i = 0; i < n; i++) {
                float rx = x + i * (w + gap);
                boolean over = inside(mx, my, rx, 567, w, 106);
                card(c, rx, 567, w, 106, argb(.85f * in, 0x0B1530), argb(in, over ? 0xFFE7C77E : 0xC8A877), 1.4f);
                AlchemyScreen.item(c, rewardIcons.get(i), rx + w / 2, 607, 52);
                text(c, "×" + result.rewards().get(i).amount(), rx + w / 2, 655, 17, argb(in, 0xF3E9D3), BOLD, true);
                if (over) { hoverStack = rewardIcons.get(i); hoverLabel = rewardName(i); hoverX = (float) mx + 14; hoverY = (float) my; }
            }
        } else {
            String msg = switch (o) {
                case FishingProtocol.SPOOKED -> "물고기가 놀라 도망갔다..";
                case FishingProtocol.MISSED -> "미끼만 먹고 도망갔다..";
                case FishingProtocol.SNAPPED -> "줄이 끊어져 버렸다..";
                case FishingProtocol.ESCAPED -> "놓쳐버렸다..";
                default -> "낚시를 확인하지 못했다..";
            };
            // the client saw why (hook slipped, line bitten through, rod gave out)
            if (lossMessage != null && (o == FishingProtocol.ESCAPED || o == FishingProtocol.SNAPPED)) msg = lossMessage;
            // the empty hook swaying, the fish swimming off
            float sway = (float) Math.sin(t * 1.6) * 8;
            float hy = 390;
            if (o == FishingProtocol.SNAPPED) { line(c, 720, VY - 10, 735 + sway, 250, .6f); HudMesh.line(c, 735 + sway, 250, 742 + sway, 262, 1.2f, 0x88E9F6FF); }
            else {
                line(c, 720, VY - 10, 765 + sway, hy - 20, .65f);
                if (o == FishingProtocol.ESCAPED) draw(c, BOBBER, 750 + sway, hy - 26, 34, 34, 0xFFE4E9EF);
                else hook(c, 765 + sway, hy - 20, 34, 0xFFE4E9EF);
            }
            float fx = 1010 + u * 160;
            drawCentered(c, SHAPES[result.shape() == FishingProtocol.SHAPE_CHEST ? 0 : result.shape()], fx, 380, 250, 250, argb(Math.max(0, .9f - u * .25f), 0x040C1A), false, -.12f, false);
            for (int i = 0; i < 5; i++) {
                float by = 360 - ((t * 40 + i * 30) % 150);
                HudMesh.arc(c, fx - 120 - i * 7, by, 3 + i % 3, 1.2f, 0, (float) (Math.PI * 2), argb(.5f, 0xDFF7FF));
            }
            text(c, msg, 835, 490, 32, argb(in, 0xF3E9D3), TITLE, true);
        }
        boolean over = inside(mx, my, resultButtonX(), resultButtonY(), resultButtonW(), resultButtonH());
        button(c, resultButtonX(), resultButtonY(), resultButtonW(), resultButtonH(), "확인", false, over, true);
    }

    /** Fallback label for a reward whose item could not be read: next to the cursor (left, vertically centred). */
    private void tooltip(DrawContext c, String label, float left, float cy) {
        float w = width(label, 16, BODY) + 28;
        card(c, left, cy - 16, w, 32, 0xF00B1428, 0xFFE7C77E, 1.2f);
        text(c, label, left + w / 2, cy, 16, TEXT, BODY, true);
    }
    /** The reward's name as the client shows it (localized), the server's name only as a fallback. */
    private String rewardName(int i) {
        var w = result.rewards().get(i);
        ItemStack stack = i < rewardIcons.size() ? rewardIcons.get(i) : ItemStack.EMPTY;
        if (stack.isEmpty()) return w.name();
        String name = ItemTooltipRenderer.localName(stack, stack.getName()).getString();
        return name.isBlank() ? w.name() : name;
    }

    // ---- bottom slots

    private String slotValue(Piece p) {
        if (p.kind() == FishingProtocol.BAIT) return p.count() < 0 ? "∞" : p.count() + "개";
        if (p.fallback() || p.maxDurability() < 0) return "기본";
        return p.durability() + "/" + p.maxDurability();
    }
    private Art icon(Piece p) {
        Art a = gear(p.kind(), p.id());
        return FishingArt.exists(a.id()) ? a : DEFAULT_ICONS[p.kind()];
    }

    private void slots(DrawContext c, float t, double mx, double my) {
        int hovered = -1;
        for (int k = 0; k < 4; k++) {
            float x = SLOT_X[k];
            Piece p = gear == null ? null : gear.piece(k);
            boolean over = inside(mx, my, x, SLOT_Y, SLOT_W, SLOT_H);
            if (over) hovered = k;
            int gc = p == null ? GRADE[0] : GRADE[p.grade()];
            boolean broken = p != null && p.maxDurability() > 0 && p.durability() <= 0;
            card(c, x, SLOT_Y, SLOT_W, SLOT_H, over ? 0xF0142648 : 0xE60D1A35, broken ? 0xFFFF7A6E : alpha(gc, over ? 1 : .85f), over ? 2 : 1.5f);
            if (over) bloom(c, x + SLOT_W / 2, SLOT_Y + SLOT_H / 2, SLOT_W * .55f, gc & 0xFFFFFF, .12f);
            Art a = p == null ? DEFAULT_ICONS[k] : icon(p);
            draw(c, a, x + 14, SLOT_Y + 14, 64, 64, 0xFFFFFFFF);
            text(c, FishingProtocol.KIND_LABELS[k], x + 120, SLOT_Y + 34, 17, TEXT, BOLD, true);
            text(c, p == null ? "-" : slotValue(p), x + 120, SLOT_Y + 64, 17, broken ? WARN : 0xFFDCE3EA, BOLD, true);
        }
        if (hovered >= 0 && gear != null) {
            Piece p = gear.piece(hovered);
            float x = SLOT_X[hovered] - 2, w = 224, h = 140, y = SLOT_Y - h - 18;
            card(c, x, y, w, h, 0xF20B1428, 0xFF45D2E6, 1.6f);
            HudMesh.line(c, x + 86, y + h, x + 96, y + h + 10, 1.6f, 0xFF45D2E6); HudMesh.line(c, x + 96, y + h + 10, x + 106, y + h, 1.6f, 0xFF45D2E6);
            text(c, (p.fallback() ? "기본 " : "") + p.name(), x + 18, y + 28, fit(p.name(), 20, BOLD, w - 36), GRADE[p.grade()], BOLD, false);
            String l1 = switch (hovered) {
                case 0 -> "감는 힘 " + num(gear.reelPower()) + " · 챔질 " + num(gear.hookWindow()) + "초";
                case 1 -> "가라앉기 " + num(gear.sinkSpeed()) + "m/초 · 감도 " + num(gear.sensitivity()) + "초";
                case 2 -> "최대 길이 " + num(gear.lineLength()) + "m · 장력 " + num(gear.maxTension());
                default -> grade(p.grade()) + " 미끼";
            };
            text(c, l1, x + 18, y + 60, fit(l1, 15, BODY, w - 36), 0xFFC9D6E4, BODY, false);
            String l2 = hovered == 3 ? (p.count() < 0 ? "기본 미끼 (줄지 않음)" : "남은 개수 " + p.count()) : p.maxDurability() < 0 ? "내구도 없음 (기본 장비)" : "내구도 " + p.durability() + "/" + p.maxDurability();
            text(c, l2, x + 18, y + 86, 15, 0xFFC9D6E4, BODY, false);
            text(c, gearLocked() ? "낚시 중에는 바꿀 수 없어요" : "클릭하여 장비 변경", x + 18, y + 114, 15, gearLocked() ? WARN : MUTED, BODY, false);
        }
    }

    // ------------------------------------------------------------------ gear window (equipment reference)

    static final float GX = 363, GY = 188, GW = 946, GH = 581;   // frames_manifest.json: source crop 363,188

    private List<Owned> cards() {
        var list = new ArrayList<Owned>();
        if (gearList == null) return list;
        for (Owned o : gearList.owned()) if (o.kind() == gearTab) list.add(o);
        return list;
    }

    private void gearWindow(DrawContext c, float t, double mx, double my) {
        rect(c, 0, 0, 1672, 941, 0x66040810);
        rect(c, GX + 8, GY + 8, GW - 16, GH - 16, 0xF50B1730);
        draw(c, FRAME_GEAR, GX, GY, GW, GH, 0xFFFFFFFF);
        text(c, "낚시 장비", 415, 233, 33, IVORY, TITLE, false);
        float tw = width("낚시 장비", 33, TITLE);
        hline(c, 410, 1270, 261, 0x55E7C77E);
        diamond(c, 415 + tw + 18, 261, 4, GOLD);
        // close X (code-drawn)
        boolean overX = inside(mx, my, 1242, 212, 36, 36);
        HudMesh.line(c, 1250, 220, 1270, 240, 2.2f, overX ? 0xFFFFE9B0 : 0xFFE7C77E); HudMesh.line(c, 1270, 220, 1250, 240, 2.2f, overX ? 0xFFFFE9B0 : 0xFFE7C77E);
        // tabs
        float[] tabX = {475, 601, 719, 843};
        for (int k = 0; k < 4; k++) {
            boolean on = k == gearTab, over = inside(mx, my, tabX[k] - 55, 268, 110, 36);
            text(c, FishingProtocol.KIND_LABELS[k], tabX[k], 285, 19, on ? 0xFFFFE6A8 : over ? TEXT : MUTED, BOLD, true);
            if (on) { rect(c, tabX[k] - 58, 304, 116, 2.6f, 0xFFE7C77E); bloom(c, tabX[k], 305, 40, 0xE7C77E, .18f); }
        }
        hline(c, 418, 946, 307, 0x40E7C77E);
        vline(c, 971, 300, 735, 0x40E7C77E);
        diamond(c, 971, 300, 3.5f, GOLD);
        var list = cards();
        // the equipped piece first
        Piece eq = gear == null ? null : gear.piece(gearTab);
        int total = list.size() + 1;
        text(c, "보유 장비 " + list.size() + "개", 418, 331, 15, MUTED, BODY, false);
        int perPage = 6;
        gearScroll = Math.clamp(gearScroll, 0, Math.max(0, (total - 1) / 3 - 1));
        int start = gearScroll * 3;
        for (int i = start; i < Math.min(total, start + perPage); i++) {
            int slot = i - start;
            float x = 418 + (slot % 3) * 182, y = 348 + (slot / 3) * 191, w = 166, h = 172;
            boolean equipped = i == 0;
            Owned o = equipped ? null : list.get(i - 1);
            int grade = equipped ? (eq == null ? 0 : eq.grade()) : o.grade();
            boolean selected = gearSelected == i, over = inside(mx, my, x, y, w, h);
            boolean broken = !equipped && o.maxDurability() > 0 && o.durability() <= 0;
            int gc = GRADE[grade];
            card(c, x, y, w, h, selected ? 0xF0172B52 : over ? 0xE8132544 : 0xE00E1C38, broken ? 0xFFFF7A6E : alpha(gc, selected ? 1 : .7f), selected ? 2.4f : 1.5f);
            if (selected) bloom(c, x + w / 2, y + h / 2, w * .6f, gc & 0xFFFFFF, .14f);
            Art a = equipped ? (eq == null ? DEFAULT_ICONS[gearTab] : icon(eq)) : gearIcon(o);
            bloom(c, x + w / 2, y + 72, 46, gc & 0xFFFFFF, .10f);
            draw(c, a, x + w / 2 - 52, y + 22, 104, 104, broken ? 0xFF8A8A8A : 0xFFFFFFFF);
            String name = equipped ? (eq == null ? "-" : (eq.fallback() ? "기본 " : "") + eq.name()) : o.name();
            text(c, name, x + w / 2, y + 135, fit(name, 16, BOLD, w - 16), TEXT, BOLD, true);
            String val = equipped ? (eq == null ? "" : slotValue(eq)) : gearTab == 3 ? o.count() + "개" : o.maxDurability() < 0 ? "기본" : o.durability() + "/" + o.maxDurability();
            text(c, val, x + w / 2, y + 157, 15, broken ? WARN : 0xFFC9D6E4, BODY, true);
            if (equipped) {
                card(c, x + 10, y + 12, 58, 24, 0xE00B1530, 0xFF45D2E6, 1.2f);
                text(c, "장착 중", x + 39, y + 24, 13, 0xFF9FEFFF, BOLD, true);
            }
        }
        if (total > perPage) {
            int pages = (total + 2) / 3 - 1;
            float frac = (float) gearScroll / Math.max(1, pages - 1);
            rect(c, 950, 350, 3, 360, 0x30E7C77E);
            rect(c, 949, 350 + frac * 300, 5, 60, 0xA0E7C77E);
        }
        // detail
        int sel = gearSelected < 0 ? 0 : Math.min(gearSelected, total - 1);
        boolean equippedSel = sel == 0;
        Owned so = equippedSel ? null : list.get(sel - 1);
        String name = equippedSel ? (eq == null ? "-" : (eq.fallback() ? "기본 " : "") + eq.name()) : so.name();
        int grade = equippedSel ? (eq == null ? 0 : eq.grade()) : so.grade();
        text(c, name, 1003, 333, fit(name, 27, TITLE, 270), IVORY, TITLE, false);
        text(c, grade(grade) + " " + FishingProtocol.KIND_LABELS[gearTab], 1005, 366, 15, GRADE[grade], BOLD, false);
        Art big = equippedSel ? (eq == null ? DEFAULT_ICONS[gearTab] : icon(eq)) : gearIcon(so);
        bloom(c, 1135, 444, 100, GRADE[grade] & 0xFFFFFF, .16f + .04f * (float) Math.sin(t * 2));
        draw(c, big, 1135 - 75, 444 - 75, 150, 150, 0xFFFFFFFF);
        hline(c, 1003, 1267, 531, 0x40E7C77E);
        String stat = equippedSel ? (gearList == null ? "" : gearList.stats().get(gearTab)) : so.stats();
        String dur = equippedSel ? (eq == null ? "" : gearTab == 3 ? (eq.count() < 0 ? "∞" : eq.count() + "개") : eq.maxDurability() < 0 ? "기본" : eq.durability() + "/" + eq.maxDurability())
                : gearTab == 3 ? so.count() + "개" : so.maxDurability() < 0 ? "기본" : so.durability() + "/" + so.maxDurability();
        text(c, gearTab == 3 ? "개수" : "내구도", 1003, 556, 16, MUTED, BODY, false);
        FishingArt.right(c, dur, 1267, 556, 16, TEXT, BOLD);
        hline(c, 1003, 1267, 574, 0x22E7C77E);
        var lines = new ArrayList<String>(List.of(stat.split(" · ")));
        String sp = equippedSel ? "" : so.special();
        float ly = 592;
        for (String s : lines) { text(c, s, 1003, ly, fit(s, 15, BODY, 264), 0xFFC9D6E4, BODY, false); ly += 20; }
        if (!sp.isBlank()) text(c, sp, 1003, ly, fit(sp, 14, BODY, 264), 0xFFF2D9A0, BODY, false);
        boolean locked = gearList != null && gearList.locked();
        boolean canEquip = !equippedSel && !locked && !(so.maxDurability() > 0 && so.durability() <= 0);
        boolean overEquip = inside(mx, my, 1004, 640, 263, 44), overClose = inside(mx, my, 1004, 690, 263, 44);
        button(c, 1004, 640, 263, 44, equippedSel ? "장착 중" : "장착", true, overEquip, canEquip);
        button(c, 1004, 690, 263, 44, "닫기", false, overClose, true);
        String gn = locked && gearNotice.isBlank() ? "낚시 중에는 장비를 바꿀 수 없어요." : gearNotice;
        if (!gn.isBlank()) text(c, gn, 680, 744, fit(gn, 15, BODY, 520), WARN, BODY, true);
    }

    private Art gearIcon(Owned o) {
        Art a = gear(o.kind(), o.id());
        return FishingArt.exists(a.id()) ? a : DEFAULT_ICONS[o.kind()];
    }

    private void gearClick(double mx, double my, int button) {
        if (inside(mx, my, 1242, 212, 36, 36) || inside(mx, my, 1004, 690, 263, 44) || !inside(mx, my, GX, GY, GW, GH)) { gearOpen = false; return; }
        float[] tabX = {475, 601, 719, 843};
        for (int k = 0; k < 4; k++) if (inside(mx, my, tabX[k] - 55, 268, 110, 36)) { gearTab = k; gearSelected = 0; gearScroll = 0; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, .25f); return; }
        var list = cards();
        int total = list.size() + 1, start = gearScroll * 3;
        for (int i = start; i < Math.min(total, start + 6); i++) {
            int slot = i - start;
            float x = 418 + (slot % 3) * 182, y = 348 + (slot / 3) * 191;
            if (inside(mx, my, x, y, 166, 172)) {
                if (gearSelected == i && i > 0 && button == 0) equipSelected(list);   // double click equips
                gearSelected = i; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, .2f); return;
            }
        }
        if (inside(mx, my, 1004, 640, 263, 44)) equipSelected(list);
    }
    private void equipSelected(List<Owned> list) {
        if (gearSelected <= 0 || gearSelected > list.size()) return;
        if (gearList != null && gearList.locked()) { gearNotice = "낚시 중에는 장비를 바꿀 수 없어요."; return; }
        var o = list.get(gearSelected - 1);
        if (o.maxDurability() > 0 && o.durability() <= 0) { gearNotice = "고장 난 장비는 장착할 수 없어요."; return; }
        FishingClient.equip(o.kind(), o.slot());
        gearSelected = 0;
    }

    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }
}
