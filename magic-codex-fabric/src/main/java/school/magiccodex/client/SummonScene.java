package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Artifact summon reveal (유물함 소환), pure timeline/drawing logic with no Minecraft types so the exact same code can
 * be rendered offline for checking. Reference space 1672 x 941 (the approved summon background).
 *
 * Order (owner, 2026-10-15 rev 2): pick a chest → its GRADE is revealed first in the row (lit rings = grade + 1,
 * grade colour, 유일 turns cyan → purple, 전설 blackout + falling star, then a grade banner) → the other four flash
 * decoy grades (cosmetic, server weights favour high grades for a near-miss feel) → the picked chest moves to the
 * centre, charges with heartbeats and bursts open → the artifact rises → result card.
 * Everything is time based (no per-frame steps), so it is as smooth as the game's frame rate.
 * Glows / rays / sparkles are additive, smoke is alpha.
 */
final class SummonScene {
    static final float W = 1672, H = 941;
    static final int[] GRADE = {0xFFDCE3EA, 0xFF71E5F2, 0xFFD2A8FF, 0xFFFFD582};
    static final String[] GRADE_NAME = {"일반", "고급", "유일", "전설"};
    static final String[] FRAME = {"frame_common", "frame_rare", "frame_unique", "frame_legendary"};
    static final float[] CHEST_X = {270, 553, 836, 1119, 1402};
    static final float BASE = 640;                       // chest bottom line in the row
    static final float CX = 836, CBASE = 735;            // chosen chest bottom when centred
    static final float CLOSED_W = 250, CLOSED_H = 177, OPEN_W = 244, OPEN_H = 217;
    static final float SEAM = 0.47f;                     // lid seam, fraction of the closed chest height from its top

    enum State { IDLE, WAITING, PICK, OPENING, CARD, MULTI }
    enum Face { TITLE, BOLD, LABEL, BODY }
    enum Cue { GATHER, RING, SHIFT, BLACKOUT, METEOR, IMPACT, GRADE, DECOY, MOVE, BEAT, OPEN, STAR, CARD, LAND, CHARGE }

    /** Drawing back end (Minecraft DrawContext in game, a software rasteriser for the offline preview). */
    interface Canvas {
        /** Full-window background, zoomed about the reference centre and shifted (camera). */
        void background(float zoom, float dx, float dy);
        void sprite(String name, float cx, float cy, float w, float h, float rotDeg, float squashY, int argb, boolean additive);
        void fillScreen(int argb);
        void rect(float cx, float cy, float w, float h, int argb);
        void text(String value, float x, float y, float size, int argb, Face face, boolean centered);
        void icon(String path, float cx, float cy, float size, int argb);
    }

    State state = State.IDLE;
    long stateStart;
    int hover = -1, picked = -1, grade;
    int[] decoys = new int[4];
    String name = "", icon = "";
    boolean isNew;
    /** Five-pull (소환하기 ×5): one entry per chest, left to right. */
    record Multi(String name, String icon, int grade, boolean isNew) {}
    Multi[] multi = new Multi[0];
    private final float[] lift = new float[5];
    private long lastDraw = -1;
    private double lastT = -1;
    private final List<Cue> fired = new ArrayList<>();
    private final List<Integer> cueIndex = new ArrayList<>();

    // ------------------------------------------------------------------ timeline (seconds since the pick)

    void set(State next, long now) { state = next; stateStart = now; lastT = -1; }
    double t(long now) { return (now - stateStart) / 1000.0; }
    int rings() { return grade + 1; }

    double ringAt(int i) { return .7 + .3 * i; }
    double ringsDone() { return ringAt(rings() - 1) + .3; }
    double shiftAt() { return ringsDone() + .15; }                 // 유일
    double blackoutAt() { return ringsDone() + .1; }               // 전설
    double meteorAt() { return blackoutAt() + .35; }
    double impactAt() { return meteorAt() + .5; }
    double gradeAt() { return grade == 3 ? impactAt() + .25 : grade == 2 ? shiftAt() + .35 : ringsDone() + .1; }
    double decoyAt(int k) { return gradeAt() + .55 + .16 * k; }
    double moveAt() { return gradeAt() + 1.9; }
    double beatAt(int k) { return moveAt() + .7 + .3 * k; }
    double openAt() { return moveAt() + 1.65; }
    double settled() { return openAt() + 1.1 + .14 * rings(); }
    boolean continueReady(long now) { return state == State.OPENING && t(now) >= openAt() + 1.2; }

    // five-pull: chests drop in one by one (colour = grade), short charge, all open at once
    double landAt(int i) { return .25 + .17 * i; }
    double multiOpenAt() { return landAt(multi.length - 1) + .22 + .85; }
    int multiBest() { int b = 0; for (Multi m : multi) b = Math.max(b, m.grade()); return b; }
    boolean multiReady(long now) { return state == State.MULTI && t(now) >= multiOpenAt() + .9; }

    /** Click while opening: jump to the next stage (grade reveal → move, charge → open; five-pull → open). */
    void skip(long now) {
        if (state == State.MULTI) {
            double to = multiOpenAt();
            if (t(now) < to) { stateStart = now - (long) (to * 1000) - 1; lastT = to - 1e-3; }
            return;
        }
        if (state != State.OPENING) return;
        double t = t(now), to = t < moveAt() ? moveAt() : t < openAt() ? openAt() : -1;
        if (to > 0) { stateStart = now - (long) (to * 1000) - 1; lastT = to - 1e-3; }
    }

    /** Sound cues crossed since the last call, with an index (ring / decoy grade / beat / star number). */
    List<Cue> cues(long now) {
        fired.clear(); cueIndex.clear();
        double t = t(now);
        if (state == State.OPENING) {
            cross(t, 0, Cue.GATHER, 0);
            for (int i = 0; i < rings(); i++) cross(t, ringAt(i), Cue.RING, i);
            if (grade == 2) cross(t, shiftAt(), Cue.SHIFT, 0);
            if (grade == 3) { cross(t, blackoutAt(), Cue.BLACKOUT, 0); cross(t, meteorAt(), Cue.METEOR, 0); cross(t, impactAt(), Cue.IMPACT, 0); }
            cross(t, gradeAt(), Cue.GRADE, grade);
            for (int k = 0; k < 4; k++) cross(t, decoyAt(k), Cue.DECOY, decoys[k]);
            cross(t, moveAt(), Cue.MOVE, 0);
            for (int k = 0; k < 3; k++) cross(t, beatAt(k), Cue.BEAT, k);
            cross(t, openAt(), Cue.OPEN, grade);
            for (int i = 0; i < rings(); i++) cross(t, openAt() + 1.0 + .14 * i, Cue.STAR, i);
        } else if (state == State.CARD) cross(t, 0, Cue.CARD, grade);
        else if (state == State.MULTI) {
            for (int i = 0; i < multi.length; i++) cross(t, landAt(i) + .22, Cue.LAND, multi[i].grade());
            cross(t, multiOpenAt() - .6, Cue.CHARGE, multiBest());
            cross(t, multiOpenAt(), Cue.OPEN, multiBest());
            cross(t, multiOpenAt() + .35, Cue.CARD, multiBest());
        }
        lastT = t;
        return fired;
    }
    int cueIndex(int i) { return cueIndex.get(i); }
    private void cross(double t, double at, Cue cue, int index) { if (lastT < at && t >= at) { fired.add(cue); cueIndex.add(index); } }

    /** Chest under the mouse in the row (PICK), or -1. */
    int chestAt(double x, double y) {
        for (int i = 0; i < 5; i++) {
            float cx = CHEST_X[i];
            if (x >= cx - CLOSED_W / 2 && x <= cx + CLOSED_W / 2 && y >= BASE - CLOSED_H - 30 && y <= BASE + 10) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    void draw(Canvas raw, long now, long clock) {
        double dt = lastDraw < 0 ? 0 : Math.min(.1, (clock - lastDraw) / 1000.0);
        lastDraw = clock;
        double t = t(now), time = clock / 1000.0;
        float[] cam = camera(t, time);
        raw.background(cam[0], cam[1], cam[2]);
        Canvas c = cam[0] == 1 && cam[1] == 0 && cam[2] == 0 ? raw : new Camera(raw, cam[0], cam[1], cam[2]);
        motes(c, time);
        switch (state) {
            case IDLE, WAITING, PICK -> row(c, time, t, dt);
            case OPENING -> opening(c, raw, t, time);
            case CARD -> card(c, raw, t, time);
            case MULTI -> multi(c, raw, t, time);
        }
    }

    /** zoom, dx, dy: slight push-in on the picked chest during the grade reveal, kick + shake at impacts. */
    private float[] camera(double t, double time) {
        if (state == State.MULTI) {
            float shake = decay(t - multiOpenAt(), .45f) * 10;
            for (int i = 0; i < multi.length; i++) shake += decay(t - landAt(i) - .22, .18f) * (2 + 2 * multi[i].grade());
            double charge = (t - (multiOpenAt() - .6)) / .6;
            if (charge > 0 && charge < 1) shake += (float) (charge * charge) * 3;
            float zoom = 1 + .04f * decay(t - multiOpenAt(), .6f);
            return new float[]{zoom, (float) Math.sin(time * 61) * shake, (float) Math.cos(time * 53) * shake * .7f};
        }
        if (state != State.OPENING || picked < 0) return new float[]{1, 0, 0};
        float push = smooth(clamp01((float) (t / .8))) * (1 - smooth(clamp01((float) ((t - moveAt()) / .6))));
        float zoom = 1 + .07f * push;
        float dx = (CX - CHEST_X[picked]) * .07f * push, dy = -(BASE - H / 2) * .07f * push * .2f;
        float shake = 0;
        if (grade == 3) shake += decay(t - impactAt(), .5f) * 14;
        shake += decay(t - openAt(), .45f) * 10;
        double charge = (t - beatAt(0)) / (openAt() - beatAt(0));
        if (charge > 0 && charge < 1) shake += (float) (charge * charge) * 4;
        zoom += .06f * decay(t - openAt(), .6f);
        dx += (float) Math.sin(time * 61) * shake; dy += (float) Math.cos(time * 53) * shake * .7f;
        return new float[]{zoom, dx, dy};
    }

    private void motes(Canvas c, double time) {
        for (int i = 0; i < 22; i++) {
            float x = (float) ((hash(i, 1) * W + time * 8 * (hash(i, 2) - .5) + W) % W);
            float y = (float) (H - ((time * (14 + 20 * hash(i, 3)) + hash(i, 4) * H) % (H + 60)));
            float a = (float) (.22 + .18 * Math.sin(time * 2 + i));
            float s = 12 + 12 * hash(i, 5);
            c.sprite("fx_mote", x, y, s, s, 0, 1, rgba(0xFFB9D4FF, a), true);
        }
    }

    private void platform(Canvas c, float x, float base, float width, double time, int color, float alpha) {
        c.sprite("fx_glow_wide", x, base + 4, width * 1.1f, width * 1.1f, 0, .22f, rgba(color, alpha * .45f), true);
        c.sprite("fx_ring_rune", x, base + 6, width, width, (float) (time * 14), .27f, rgba(color, alpha), true);
    }
    private void closedChest(Canvas c, float x, float base, float scale, int argb) {
        c.sprite("chest_closed", x, base - CLOSED_H * scale / 2, CLOSED_W * scale, CLOSED_H * scale, 0, 1, argb, false);
    }
    private void openChest(Canvas c, float x, float base, float scale, int argb) {
        c.sprite("chest_open", x, base - OPEN_H * scale / 2, OPEN_W * scale, OPEN_H * scale, 0, 1, argb, false);
    }

    private void row(Canvas c, double time, double t, double dt) {
        for (int i = 0; i < 5; i++) {
            boolean over = state == State.PICK && hover == i;
            lift[i] += (float) (((over ? 1 : 0) - lift[i]) * (1 - Math.exp(-dt * 12)));
            float l = lift[i];
            float bob = (float) Math.sin(time * 1.8 + i * 1.3) * 5 - 16 * smooth(l);
            float x = CHEST_X[i];
            float pulse = state == State.WAITING ? (float) (.5 + .5 * Math.sin(t * 6 + i)) : 0;
            platform(c, x, BASE, 250, time + i, 0xFF8FC8FF, .34f + .5f * l + .25f * pulse);
            if (l > .01f || pulse > 0) {
                c.sprite("fx_glow_wide", x, BASE - CLOSED_H / 2 + bob, 380, 380, 0, 1, rgba(0xFFFFE1A8, .5f * l + .2f * pulse), true);
                for (int k = 0; k < 5; k++) {           // orbiting sparks with short trails
                    for (int j = 0; j < 4; j++) {
                        double a = time * 1.8 - j * .07 + k * 1.2566;
                        c.sprite("fx_spark4", x + (float) Math.cos(a) * 135, BASE - CLOSED_H / 2 + bob + (float) Math.sin(a) * 46, 24 - j * 4, 24 - j * 4,
                                (float) (time * 50), 1, rgba(0xFFFFE9C0, .85f * l * (1 - j * .24f)), true);
                    }
                }
            }
            closedChest(c, x, BASE + bob, 1 + .06f * smooth(l), 0xFFFFFFFF);
            if (l > .01f) c.sprite("fx_glint", x, BASE - CLOSED_H * SEAM + bob, 230 * l, 66, 0, 1, rgba(0xFFFFF0D0, .6f * l), true);
        }
        if (state == State.PICK) c.text("유물함을 하나 선택하세요", CX, 150, 30, 0xFFF1E3C2, Face.BOLD, true);
    }

    /** Light colour: 유일 shows cyan until the shift. */
    private int lightColor(double t) { return grade == 2 && t < shiftAt() ? GRADE[1] : GRADE[grade]; }

    private void opening(Canvas c, Canvas raw, double t, double time) {
        float move = smooth(clamp01((float) ((t - moveAt()) / .6)));
        boolean open = t >= openAt();
        // ---- dim: row stage mild, blackout for 전설, deep for the opening
        float dim = .3f * smooth(clamp01((float) t / .6f)) + .25f * move;
        if (grade == 3) {
            double b = t - blackoutAt();
            if (b > 0 && t < impactAt() + .5) dim = Math.max(dim, b < .25 ? lerp(.3f, .9f, (float) (b / .25)) : t < impactAt() ? .9f : lerp(.9f, .3f, clamp01((float) ((t - impactAt()) / .5))));
        }
        raw.fillScreen(rgba(0xFF02050B, dim));

        // ---- the other four chests: wait, flash a decoy grade, leave when the picked one moves
        int k = 0;
        for (int i = 0; i < 5; i++) {
            if (i == picked) continue;
            int g = decoys[k];
            double d = t - decoyAt(k++);
            float away = 1 - move, x = CHEST_X[i] + (CHEST_X[i] - CX) * .15f * move;
            if (away <= 0) continue;
            float on = clamp01((float) (d / .2)) * away, fresh = decay(d, .5f);
            if (on > 0) {
                platform(c, x, BASE, 250, time + i, GRADE[g], (.35f + .4f * on) * away);
                c.sprite("fx_glow_wide", x, BASE - CLOSED_H / 2, 360 + 120 * fresh, 360 + 120 * fresh, 0, 1, rgba(GRADE[g], .5f * on + .4f * fresh), true);
                beam(c, x, BASE - CLOSED_H * SEAM, 90 + 60 * fresh, 260 + 280 * clamp01((float) (d / .35)), rgba(GRADE[g], (.28f + .6f * fresh) * on), time + i);
                if (g == 3) godRays(c, x, BASE - CLOSED_H / 2, 300, time, rgba(GRADE[3], .35f * on), 8);
            } else platform(c, x, BASE, 250, time + i, 0xFF8FC8FF, .3f * away);
            closedChest(c, x, BASE, 1, rgba(0xFFFFFFFF, away));
            if (on > 0) {
                c.sprite("fx_glint", x, BASE - CLOSED_H * SEAM, 240, 64, 0, 1, rgba(GRADE[g], .8f * on), true);
                if (fresh > 0) shockwave(c, x, BASE, d, GRADE[g], 380);
            }
        }

        // ---- picked chest
        float x = lerp(CHEST_X[picked], CX, move), base = lerp(BASE, CBASE, move), scale = lerp(1, 1.5f, move);
        float seamY = base - CLOSED_H * scale * SEAM, midY = base - CLOSED_H * scale / 2;
        int color = lightColor(t);
        float lit = clamp01((float) ((t - ringAt(0)) / .3));

        // floor rings: one per grade step, each flares when it lights
        float floorW = lerp(250, 520, move);
        if (t > .2) c.sprite("fx_ring_rune", x, base + 6, floorW * 1.15f, floorW * 1.15f, (float) (time * 10), .27f, rgba(0xFF9CC7FF, .3f), true);
        for (int i = 0; i < rings(); i++) {
            double r = t - ringAt(i);
            if (r < 0) continue;
            float fl = decay(r, .4f), w = floorW * (1 + .32f * i) + 80 * fl;
            int rc = grade == 2 && t < shiftAt() ? GRADE[1] : GRADE[grade];
            c.sprite(i % 2 == 0 ? "fx_ring_rune" : "fx_ring", x, base + 6, w, w, (float) ((i % 2 == 0 ? 1 : -1) * time * (16 + 7 * i)), .27f, rgba(rc, .6f + .4f * fl), true);
            if (fl > 0 && !open) beam(c, x, base - 10, 120 * fl + 40, 500 * fl, rgba(rc, .7f * fl), time);
        }
        if (lit > 0) c.sprite("fx_glow_wide", x, base + 6, floorW * 2.2f, floorW * 2.2f, 0, .27f, rgba(color, .35f * lit), true);
        if (grade == 3 && t > impactAt()) {
            float a = clamp01((float) ((t - impactAt()) / .4));
            c.sprite("fx_ring_rune", x, base + 6, floorW * 2.6f, floorW * 2.6f, (float) (-time * 6), .27f, rgba(GRADE[3], .5f * a), true);
            constellation(c, x, base + 6, floorW * 1.3f, time, a);
        }

        // gather: motes spiral into the chest right after the pick
        if (t < 1.2) for (int i = 0; i < 28; i++) {
            float u = clamp01((float) ((t - hash(i, 51) * .35) / .8)); if (u <= 0 || u >= 1) continue;
            for (int j = 0; j < 4; j++) {
                float uu = Math.max(0, u - j * .035f);
                double ang = hash(i, 52) * 6.283 + uu * 3.2;
                float r = (1 - smooth(uu)) * (300 + 160 * hash(i, 53));
                c.sprite("fx_spark4", x + (float) Math.cos(ang) * r, midY + (float) Math.sin(ang) * r * .55f, 22 - j * 4, 22 - j * 4, 0, 1,
                        rgba(0xFFCFE6FF, (1 - u * .3f) * (1 - j * .23f)), true);
            }
        }

        if (grade == 3) meteor(c, raw, t, x, seamY);

        if (!open) {
            double charge = (t - beatAt(0)) / (openAt() - beatAt(0));
            float ch = clamp01((float) charge), suck = clamp01((float) ((t - (openAt() - .18)) / .18));
            float heart = 0;
            for (int b = 0; b < 3; b++) heart = Math.max(heart, decay(t - beatAt(b), .22f) * (.6f + .2f * b));
            float glow = Math.max(lit * .45f, (.5f + .5f * ch) * clamp01((float) ((t - ringAt(0)) / .4)));
            float aura = (300 + 200 * ch + 120 * heart) * (1 - .55f * suck) * scale;
            // behind the chest: aura, god rays once the grade is known, smoke
            c.sprite("fx_glow_wide", x, midY, aura, aura, 0, 1, rgba(color, (.55f * glow + .4f * heart)), true);
            if (t > gradeAt()) godRays(c, x, midY, (260 + 160 * ch) * scale * (1 - .5f * suck), time, rgba(color, .3f + .25f * ch + .3f * heart), grade == 3 ? 14 : 10);
            for (int s = 0; s < 6; s++) smoke(c, s, x, seamY, time, Math.max(lit * .6f, ch), color, scale);
            float shake = (float) Math.sin(time * 70) * 6 * ch * ch;
            closedChest(c, x + shake, base, scale * (1 + .03f * heart - .03f * suck), 0xFFFFFFFF);
            // seam light and rays in front
            float seam = Math.max(lit * .5f, ch);
            c.sprite("fx_glint", x + shake, seamY, CLOSED_W * scale * (.8f + .6f * seam), 80 + 40 * heart, 0, 1, rgba(color, .9f * seam), true);
            c.sprite("fx_glow_core", x + shake, seamY, 120 + 120 * heart, 120 + 120 * heart, 0, 1, rgba(brighten(color), .6f * seam + .4f * heart), true);
            if (ch > 0) for (int r = 0; r < 7; r++)
                ray(c, x + (r - 3) * 24 * scale, seamY, -50 + r * 16.6f + (float) Math.sin(time * 3 + r) * 5, 70 * scale, (120 + 320 * ch * (.6f + .4f * hash(r, 9))) * scale, rgba(color, .75f * ch));
            // particles converging into the seam while charging
            if (ch > 0) for (int i = 0; i < 18; i++) {
                float u = (float) ((time * .9 + hash(i, 61)) % 1);
                double ang = hash(i, 62) * 6.283;
                float r = (1 - u) * 340 * scale;
                c.sprite("fx_spark4", x + (float) Math.cos(ang) * r, seamY + (float) Math.sin(ang) * r * .6f, 20, 20, 0, 1, rgba(color, ch * u), true);
            }
            // reveal accents on the row (before the move)
            if (grade == 2 && t >= shiftAt()) { flash(c, x, midY, t - shiftAt(), GRADE[2], 600); shockwave(c, x, base, t - shiftAt(), GRADE[2], 700); }
            if (grade == 3 && t >= impactAt()) { flash(c, x, midY, t - impactAt(), GRADE[3], 900); shockwave(c, x, base, t - impactAt(), GRADE[3], 1100); }
            gradeBanner(c, x, base - CLOSED_H * scale - 70, t, 1 - move);
            if (t < moveAt()) c.text("클릭하여 넘기기", W - 120, H - 36, 16, 0x88D8E2EA, Face.LABEL, true);
        } else {
            double o = t - openAt();
            float pulse = .5f + .5f * (float) Math.sin(time * 4);
            // behind: god rays, pillar, burst, glow
            godRays(c, x, seamY - 120, 820, time, rgba(color, .26f + .08f * pulse), grade == 3 ? 16 : 12);
            c.sprite("fx_glow_wide", x, seamY - 100, 780, 780, 0, 1, rgba(color, .38f), true);
            float ph = 820 * smooth(clamp01((float) o / .3f));
            c.sprite("fx_ray", x, seamY - ph / 2 + 40, 300, ph, 0, 1, rgba(color, .55f + .25f * pulse), true);
            c.sprite("fx_ray", x, seamY - ph / 2 + 40, 120, ph * .9f, 0, 1, rgba(0xFFFFFFFF, .45f), true);
            c.sprite("fx_burst", x, seamY - 60, 700, 700, (float) (time * 14), 1, rgba(color, .3f), true);
            openChest(c, x, base, scale, 0xFFFFFFFF);
            c.sprite("fx_glow_core", x, seamY - 10, 260, 140, 0, 1, rgba(brighten(color), .7f), true);
            // lid burst: sparks with gravity, glittering confetti afterwards
            for (int i = 0; i < 44; i++) {
                double life = o / (.9 + .6 * hash(i, 71));
                if (life >= 1) continue;
                double ang = -Math.PI / 2 + (hash(i, 72) - .5) * 2.6, sp = 380 + 520 * hash(i, 73);
                float px = x + (float) (Math.cos(ang) * sp * o * (1 - .45 * o));
                float py = seamY + (float) (Math.sin(ang) * sp * o * (1 - .45 * o) + 520 * o * o);
                String sprite = i % 4 == 0 ? "fx_spark_multi" : "fx_spark4";
                float size = (i % 4 == 0 ? 40 : 26) * (1 - (float) life * .5f);
                c.sprite(sprite, px, py, size, size, (float) (time * 120 + i * 30), 1, rgba(i % 3 == 0 ? 0xFFFFFFFF : color, (float) (1 - life)), true);
            }
            for (int i = 0; i < 16; i++) {               // lingering glitter
                float u = (float) ((time * .35 + hash(i, 81)) % 1);
                float gx = x + (hash(i, 82) - .5f) * 520, gy = seamY - 80 - u * 420;
                float tw = .5f + .5f * (float) Math.sin(time * 9 + i * 2);
                c.sprite("fx_spark4", gx, gy, 18 + 14 * tw, 18 + 14 * tw, 0, 1, rgba(color, (1 - u) * tw * clamp01((float) o / .4f)), true);
            }
            // artifact rises out of the light
            float rise = easeBack(clamp01((float) ((o - .12) / .8)));
            float iy = lerp(seamY, 320, rise), is = lerp(.25f, 1, clamp01(rise));
            if (o > .12) {
                c.sprite("fx_glow_wide", x, iy, 430 * is, 430 * is, 0, 1, rgba(color, .8f), true);
                c.sprite("fx_ring_rune", x, iy, 330 * is, 330 * is, (float) (time * 30), 1, rgba(color, .7f), true);
                c.sprite("fx_ring", x, iy, 250 * is, 250 * is, (float) (-time * 50), 1, rgba(brighten(color), .5f), true);
                c.icon(icon, x, iy + (float) Math.sin(time * 2.2) * 6, 200 * is, 0xFFFFFFFF);
                c.sprite("fx_spark_multi", x + 100 * is, iy - 90 * is, 56, 56, (float) (time * 30), 1, rgba(0xFFFFFFFF, .5f + .5f * (float) Math.sin(time * 6)), true);
            }
            shockwave(c, x, base, o, color, 1300);
            flash(c, x, seamY, o, brighten(color), 1100);
            raw.fillScreen(rgba(0xFFFFFFFF, .75f * decay(o, .35f)));
            // result text (grade already known: emphasise the artifact)
            float ta = clamp01((float) (o - .8) / .3f);
            if (ta > 0) {
                float tx = 1270;
                c.text(GRADE_NAME[grade], tx, 268, 34, rgba(GRADE[grade], ta), Face.TITLE, true);
                for (int s = 0; s < rings(); s++) {
                    double st = o - (1.0 + .14 * s);
                    if (st < 0) continue;
                    float pop = 1 + .7f * decay(st, .22f);
                    c.sprite("fx_spark4", tx + (s - (rings() - 1) / 2f) * 46, 318, 44 * pop, 44 * pop, 0, 1, rgba(0xFFFFD582, ta), true);
                }
                c.text(name, tx, 382, 44, rgba(0xFFF3E9D3, ta), Face.TITLE, true);
                c.text(isNew ? "새로운 아티팩트 획득" : "이미 가진 아티팩트", tx, 434, 21, rgba(isNew ? 0xFF9FE6B0 : 0xFFB7C6D1, ta), Face.BODY, true);
            }
            if (o > 1.2) c.text("클릭하여 계속", CX, 890, 20, rgba(0xFFD8E2EA, .45f + .35f * (float) Math.sin(time * 4)), Face.BODY, true);
        }
    }

    /** Five-pull: chests fall into the row one after another, each lighting up in its grade colour, then all open together. */
    private void multi(Canvas c, Canvas raw, double t, double time) {
        double open = t - multiOpenAt();
        raw.fillScreen(rgba(0xFF02050B, .35f * smooth(clamp01((float) t / .4f)) + (open > 0 ? .2f * clamp01((float) open / .3f) : 0)));
        float charge = clamp01((float) ((t - (multiOpenAt() - .6)) / .6));
        for (int i = 0; i < multi.length && i < CHEST_X.length; i++) {
            var m = multi[i];
            int color = GRADE[m.grade()];
            float x = CHEST_X[i];
            double d = t - landAt(i), landed = d - .22;
            if (d < 0) continue;
            if (open < 0) {
                // fall in with a short trail, land with a grade-coloured shock
                float u = clamp01((float) (d / .22)), y = BASE - 460 * (1 - u * u);
                float lit = landed < 0 ? 0 : clamp01((float) (landed / .15));
                float fresh = decay(landed, .45f);
                if (landed < 0) for (int j = 1; j < 4; j++)
                    c.sprite("fx_glint", x, y - CLOSED_H / 2 - j * 40, 60, 120, 90, 1, rgba(color, .35f * (1 - j / 4f)), true);
                platform(c, x, BASE, 250, time + i, lit > 0 ? color : 0xFF8FC8FF, .35f + .45f * lit + .3f * charge);
                if (lit > 0) {
                    float pulse = .5f + .5f * (float) Math.sin(time * 5 + i);
                    c.sprite("fx_glow_wide", x, BASE - CLOSED_H / 2, 360 + 140 * fresh + 120 * charge, 360 + 140 * fresh + 120 * charge, 0, 1,
                            rgba(color, (.35f + .1f * pulse) * lit + .45f * fresh + .3f * charge), true);
                    beam(c, x, BASE - CLOSED_H * SEAM, 80 + 60 * fresh + 40 * charge, (220 + 60 * m.grade()) * lit + 260 * fresh, rgba(color, (.3f + .1f * m.grade()) * lit + .5f * fresh), time + i);
                    if (m.grade() >= 2) godRays(c, x, BASE - CLOSED_H / 2, 200 + 60 * m.grade() + 120 * charge, time + i, rgba(color, (.18f + .08f * m.grade()) * lit + .2f * charge), m.grade() == 3 ? 12 : 8);
                    if (m.grade() == 3) c.sprite("fx_ring_rune", x, BASE + 6, 420, 420, (float) (-time * 20), .27f, rgba(color, .6f * lit), true);
                    shockwave(c, x, BASE, landed, color, 260 + 80 * m.grade());
                }
                float shake = (float) Math.sin(time * 70 + i) * 5 * charge * charge;
                closedChest(c, x + shake, y, 1 + .04f * fresh, 0xFFFFFFFF);
                if (lit > 0) c.sprite("fx_glint", x + shake, BASE - CLOSED_H * SEAM, 240 * (.7f + .5f * charge), 70, 0, 1, rgba(color, .55f * lit + .4f * charge), true);
            } else {
                // opened: burst, then the result card in the chest's place (all at the same moment)
                flash(c, x, BASE - CLOSED_H / 2, open, brighten(color), 520 + 120 * m.grade());
                shockwave(c, x, BASE, open, color, 600);
                for (int k = 0; k < 14; k++) {
                    double life = open / (.7 + .4 * hash(i * 16 + k, 91));
                    if (life >= 1) continue;
                    double ang = -Math.PI / 2 + (hash(i * 16 + k, 92) - .5) * 2.8, sp = 260 + 300 * hash(i * 16 + k, 93);
                    float px = x + (float) (Math.cos(ang) * sp * open), py = BASE - CLOSED_H / 2 + (float) (Math.sin(ang) * sp * open + 480 * open * open);
                    c.sprite("fx_spark4", px, py, 22, 22, (float) (time * 120), 1, rgba(k % 3 == 0 ? 0xFFFFFFFF : color, (float) (1 - life)), true);
                }
                multiCard(c, m, x, 430, open, time);
            }
        }
        if (open >= 0) raw.fillScreen(rgba(0xFFFFFFFF, .7f * decay(open, .35f)));
        else c.text("클릭하여 넘기기", W - 120, H - 36, 16, 0x88D8E2EA, Face.LABEL, true);
    }

    private void multiCard(Canvas c, Multi m, float x, float y, double open, double time) {
        float a = smooth(clamp01((float) (open - .05) / .3f)), flip = easeBack(clamp01((float) (open - .05) / .45f));
        if (a <= 0) return;
        float size = 220, w = size * Math.max(.02f, flip);
        int color = GRADE[m.grade()];
        if (m.grade() >= 2) godRays(c, x, y, 260 + 40 * m.grade(), time, rgba(color, (.12f + .08f * m.grade()) * a), m.grade() == 3 ? 12 : 8);
        c.sprite("fx_glow_wide", x, y, 360, 360, 0, 1, rgba(color, (.25f + .08f * m.grade()) * a), true);
        c.sprite(FRAME[m.grade()] + "_fill", x, y, w, size, 0, 1, rgba(0xFFFFFFFF, a), false);
        if (flip > .5f) c.icon(m.icon(), x, y, 150 * (w / size), rgba(0xFFFFFFFF, a));
        c.sprite(FRAME[m.grade()], x, y, w, size, 0, 1, rgba(0xFFFFFFFF, a), false);
        float sweep = clamp01((float) ((open - .4) / .5));
        if (sweep > 0 && sweep < 1) c.sprite("fx_glint", x - size / 2 + size * sweep, y, 90, size * .9f, 90, 1, rgba(0xFFFFFFFF, .35f * (1 - sweep)), true);
        c.text(GRADE_NAME[m.grade()], x, y + size / 2 + 30, 26, rgba(brighten(color), a), Face.TITLE, true);
        float nameSize = Math.min(24, 24 * 11f / Math.max(11, m.name().length()));
        c.text(m.name(), x, y + size / 2 + 66, nameSize, rgba(0xFFF3E9D3, a), Face.BOLD, true);
        if (m.isNew()) {
            c.sprite("fx_spark_multi", x + size / 2 - 8, y - size / 2 + 10, 56, 56, (float) (time * 40), 1, rgba(0xFF9FE6B0, a), true);
            c.text("NEW", x + size / 2 - 8, y - size / 2 + 10, 17, rgba(0xFFEFFFF2, a), Face.BOLD, true);
        }
    }

    /** Grade stars above the picked chest once the grade is known (row stage; no grade text, the effects tell it). */
    private void gradeBanner(Canvas c, float x, float y, double t, float alpha) {
        double g = t - gradeAt();
        if (g < 0 || alpha <= 0) return;
        float pop = 1 + .45f * decay(g, .3f), a = clamp01((float) (g / .15)) * alpha;
        c.sprite("fx_glint", x, y, 360 * pop, 90, 0, 1, rgba(GRADE[grade], .7f * a), true);
        for (int s = 0; s < rings(); s++) {
            double st = g - (.12 + .1 * s);
            if (st < 0) continue;
            float sp = 1 + .7f * decay(st, .2f);
            c.sprite("fx_spark4", x + (s - (rings() - 1) / 2f) * 38, y + 46, 34 * sp, 34 * sp, 0, 1, rgba(0xFFFFD582, a), true);
        }
    }

    private void meteor(Canvas c, Canvas raw, double t, float x, float y) {
        double m = (t - meteorAt()) / (impactAt() - meteorAt());
        if (m < 0 || m > 1.25) return;
        float k = (float) Math.min(1, m * m);
        float sx = 1600, sy = -80;
        float ang = (float) Math.toDegrees(Math.atan2(y - sy, x - sx));
        float a = m > 1 ? 1 - (float) ((m - 1) / .25) : 1;
        for (int j = 0; j < 8; j++) {                     // tail of glints and sparks along the path
            float kk = Math.max(0, k - j * .045f);
            float hx = lerp(sx, x, kk), hy = lerp(sy, y, kk);
            c.sprite("fx_glint", hx, hy, 300 - j * 22, 80 - j * 7, ang, 1, rgba(GRADE[3], a * (1 - j / 8f)), true);
            c.sprite("fx_spark4", hx + (float) Math.sin(j * 7.0) * 18, hy + (float) Math.cos(j * 5.0) * 18, 28 - j * 2, 28 - j * 2, 0, 1, rgba(0xFFFFF4D0, a * (1 - j / 8f)), true);
        }
        float hx = lerp(sx, x, k), hy = lerp(sy, y, k);
        c.sprite("fx_glow_wide", hx, hy, 260, 260, 0, 1, rgba(GRADE[3], .7f * a), true);
        c.sprite("fx_glow_core", hx, hy, 140, 140, 0, 1, rgba(0xFFFFFFFF, a), true);
        c.sprite("fx_spark4", hx, hy, 120, 120, (float) (m * 360), 1, rgba(0xFFFFF4D0, a), true);
        if (m >= 1) raw.fillScreen(rgba(0xFFFFF4D8, .8f * decay(t - impactAt(), .3f)));
    }

    private void constellation(Canvas c, float x, float y, float r, double time, float a) {
        float px = 0, py = 0;
        for (int k = 0; k <= 8; k++) {
            double ang = k * Math.PI / 4 - time * .12;
            float sx = x + (float) Math.cos(ang) * r * (k % 2 == 0 ? 1 : .82f), sy = y + (float) Math.sin(ang) * r * .27f * (k % 2 == 0 ? 1 : .82f);
            if (k > 0) {
                float len = (float) Math.hypot(sx - px, sy - py), deg = (float) Math.toDegrees(Math.atan2(sy - py, sx - px));
                c.sprite("fx_glint", (sx + px) / 2, (sy + py) / 2, len * 1.1f, 22, deg, 1, rgba(GRADE[3], .45f * a), true);
            }
            if (k < 8) c.sprite("fx_spark4", sx, sy, 36, 36, 0, 1, rgba(GRADE[3], a * (.7f + .3f * (float) Math.sin(time * 5 + k))), true);
            px = sx; py = sy;
        }
    }

    private void godRays(Canvas c, float x, float y, float len, double time, int argb, int count) {
        for (int i = 0; i < count; i++) {
            float ang = (float) (i * 360.0 / count + time * 9);
            float l = len * (.75f + .25f * (float) Math.sin(time * 1.7 + i * 1.9));
            double r = Math.toRadians(ang);
            c.sprite("fx_ray", x + (float) Math.sin(r) * l / 2, y - (float) Math.cos(r) * l / 2, 70, l, ang, 1, argb, true);
        }
    }

    private void beam(Canvas c, float x, float y, float w, float len, int argb, double time) {
        c.sprite("fx_ray", x, y - len / 2 + 20, w, len, 0, 1, argb, true);
        c.sprite("fx_ray", x, y - len * .45f + 20, w * .4f, len * .9f, 0, 1, rgba(0xFFFFFFFF, ((argb >>> 24) / 255f) * .5f), true);
    }

    private void smoke(Canvas c, int k, float x, float y, double time, float p, int color, float scale) {
        if (p <= 0) return;
        double ang = time * (.6 + .2 * hash(k, 41)) + k * 1.05;
        float r = (150 + 40 * hash(k, 42)) * scale;
        float rise = (float) ((time * .25 + hash(k, 43)) % 1);
        c.sprite("fx_smoke_" + (k % 4), x + (float) Math.cos(ang) * r, y + (float) Math.sin(ang) * r * .35f - rise * 90, 190 * scale, 170 * scale,
                (float) (time * 20 * (k % 2 == 0 ? 1 : -1)), 1, rgba(darken(color), .5f * p * (1 - rise * .6f)), false);
    }

    private void ray(Canvas c, float x, float seamY, float angDeg, float w, float len, int argb) {
        double a = Math.toRadians(angDeg);
        c.sprite("fx_ray", x + (float) Math.sin(a) * len / 2, seamY - (float) Math.cos(a) * len / 2, w, len, angDeg, 1, argb, true);
    }

    private void flash(Canvas c, float x, float y, double since, int color, float size) {
        float a = decay(since, .45f);
        if (a <= 0) return;
        c.sprite("fx_glow_core", x, y, size * (.6f + .4f * (1 - a)), size * (.6f + .4f * (1 - a)), 0, 1, rgba(color, a), true);
        c.sprite("fx_burst", x, y, size * 1.1f, size * 1.1f, (float) (since * 90), 1, rgba(color, a * .7f), true);
    }

    private void shockwave(Canvas c, float x, float base, double since, int color, float size) {
        if (since < 0 || since > .7) return;
        float u = (float) (since / .7), a = (1 - u) * (1 - u);
        c.sprite("fx_ring", x, base + 6, size * (.2f + u), size * (.2f + u), 0, .27f, rgba(color, a), true);
        c.sprite("fx_ring", x, base - CLOSED_H * .6f, size * .8f * (.2f + u), size * .8f * (.2f + u), 0, 1, rgba(color, a * .6f), true);
    }

    private void card(Canvas c, Canvas raw, double t, double time) {
        float a = smooth(clamp01((float) t / .35f)), flip = easeBack(clamp01((float) t / .45f));
        raw.fillScreen(rgba(0xFF02050B, .62f * a));
        float y = 360, size = 330, w = size * Math.max(.02f, flip);
        int color = GRADE[grade];
        godRays(c, CX, y, 640, time, rgba(color, .22f * a), grade == 3 ? 16 : 12);
        c.sprite("fx_glow_wide", CX, y, 660, 660, 0, 1, rgba(color, .4f * a), true);
        c.sprite("fx_ring_rune", CX, y, 540, 540, (float) (time * 10), 1, rgba(color, .3f * a), true);
        c.sprite(FRAME[grade] + "_fill", CX, y, w, size, 0, 1, rgba(0xFFFFFFFF, a), false);   // dark inner panel shaped to the frame hole
        if (flip > .5f) c.icon(icon, CX, y, 220 * (w / size), rgba(0xFFFFFFFF, a));
        c.sprite(FRAME[grade], CX, y, w, size, 0, 1, rgba(0xFFFFFFFF, a), false);
        float sweep = clamp01((float) ((t - .35) / .5));
        if (sweep > 0 && sweep < 1) c.sprite("fx_glint", CX - size / 2 + size * sweep, y, 120, size * .9f, 90, 1, rgba(0xFFFFFFFF, .35f * (1 - sweep)), true);
        c.text(GRADE_NAME[grade], CX, y + size / 2 + 40, 40, rgba(color, a), Face.TITLE, true);
        for (int s = 0; s < rings(); s++)
            c.sprite("fx_spark4", CX + (s - (rings() - 1) / 2f) * 40, y + size / 2 + 86, 36, 36, 0, 1, rgba(0xFFFFD582, a), true);
        c.text(name, CX, y + size / 2 + 128, 30, rgba(0xFFF3E9D3, a), Face.BOLD, true);
        if (isNew) {
            c.sprite("fx_spark_multi", CX + size / 2 - 6, y - size / 2 + 10, 74, 74, (float) (time * 40), 1, rgba(0xFF9FE6B0, a), true);
            c.text("NEW", CX + size / 2 - 6, y - size / 2 + 10, 22, rgba(0xFFEFFFF2, a), Face.BOLD, true);
        }
    }

    /** Applies the camera (zoom about the reference centre + shift) to everything except full-screen fills. */
    private record Camera(Canvas c, float z, float dx, float dy) implements Canvas {
        float x(float x) { return (x - W / 2) * z + W / 2 + dx; }
        float y(float y) { return (y - H / 2) * z + H / 2 + dy; }
        public void background(float zoom, float ddx, float ddy) { c.background(zoom, ddx, ddy); }
        public void sprite(String n, float cx, float cy, float w, float h, float r, float s, int argb, boolean add) { c.sprite(n, x(cx), y(cy), w * z, h * z, r, s, argb, add); }
        public void fillScreen(int argb) { c.fillScreen(argb); }
        public void rect(float cx, float cy, float w, float h, int argb) { c.rect(x(cx), y(cy), w * z, h * z, argb); }
        public void text(String v, float x, float y, float size, int argb, Face f, boolean centered) { c.text(v, x(x), y(y), size * z, argb, f, centered); }
        public void icon(String p, float cx, float cy, float size, int argb) { c.icon(p, x(cx), y(cy), size * z, argb); }
    }

    // ------------------------------------------------------------------ math

    static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float smooth(float t) { return t * t * (3 - 2 * t); }
    static float easeBack(float t) { float s = 1.7f, u = t - 1; return 1 + u * u * ((s + 1) * u + s); }
    /** 1 at since = 0, fading to 0 at since = length; 0 before. */
    static float decay(double since, float length) { if (since < 0 || since > length) return 0; float u = (float) (since / length); return (1 - u) * (1 - u); }
    static float hash(int i, int salt) {
        int h = i * 374761393 + salt * 668265263; h = (h ^ (h >>> 13)) * 1274126177; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
    static int rgba(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 255) * clamp01(alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }
    static int darken(int argb) {
        int r = ((argb >> 16) & 255) * 45 / 100, g = ((argb >> 8) & 255) * 45 / 100, b = (argb & 255) * 55 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    static int brighten(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return 0xFF000000 | ((r + (255 - r) / 2) << 16) | ((g + (255 - g) / 2) << 8) | (b + (255 - b) / 2);
    }
}
