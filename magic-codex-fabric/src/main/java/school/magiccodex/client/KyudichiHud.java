package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import school.magiccodex.client.kui.Canvas;
import school.magiccodex.client.kui.HudView;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.client.kui.Theme;
import school.magiccodex.protocol.KyudichiProtocol;

/**
 * 큐디치 HUD from the owner's hud parts (hud_layout 1672×941, scaled by min(w/1672, h/941)):
 *  - score board + timer + snitch lock: top centre (layout position)
 *  - kill/goal feed: top right, starting below the vanilla status-effect icons
 *  - F key (+ quaffle hold timer while holding): bottom centre, lifted above the hotbar / action bar
 *    (the layout puts it on the very bottom edge where the hotbar is)
 *  - ball indicators in the world, at the screen edge with the matching arrow when off-screen
 *  - Ctrl: magnet ring and direction wheel (ping art), forfeit button while voting / with the cursor
 */
final class KyudichiHud {
    private KyudichiHud() {}
    static final String[] PING_ICONS = {"location", "ally", "enemy", "warning", "on_my_way", "help", "heal_me", "quaffle", "bludger", "snitch",
            "shrine_health", "shrine_mana", "shrine_stability", "goal", "shrine_haste"};
    private static final String[] WHEEL = {"idle", "warning", "on_my_way", "help", "heal_me"};
    private static final String[] INDICATOR = {"hud/indicator_quaffle", "hud/indicator_bludger", "hud/indicator_snitch"};
    private static final String[] ARROW = {"hud/arrow_quaffle", "hud/arrow_bludger", "hud/arrow_snitch"};
    private static final float[] ARROW_BASE = {180, 0, 90};          // direction each arrow PNG points (clockwise from →)
    /** On-screen scale per ball kind (owner 2026-10-20: bludger marker smaller). */
    private static final float FEED_SCALE = .8f;
    private static final float[] INDICATOR_SCALE = {.62f, .40f, .62f};
    private static final float[][] INDICATOR_SIZE = {{70, 72}, {76, 77}, {77, 70}}, ARROW_SIZE = {{36, 37}, {38, 43}, {37, 30}};

    private static HudTextureCache images; private static CodexTypography text; private static int generation = -1;
    private static float forfeitX, forfeitY, forfeitW, forfeitH; private static boolean forfeitShown;
    private static float leaveX, leaveY, leaveW, leaveH; private static boolean leaveShown;

    static void close() { if (images != null) { images.close(); images = null; } if (text != null) { text.close(); text = null; } }
    static boolean forfeitHit(double x, double y) { if(HudCustomization.editingGesture())return false;var p=HudCustomization.pointer("battle-action",x,y);x=p[0];y=p[1];return forfeitShown && x >= forfeitX && x <= forfeitX + forfeitW && y >= forfeitY && y <= forfeitY + forfeitH; }
    static boolean leaveHit(double x, double y) { if(HudCustomization.editingGesture())return false;var p=HudCustomization.pointer("battle-action",x,y);x=p[0];y=p[1];return leaveShown && x >= leaveX && x <= leaveX + leaveW && y >= leaveY && y <= leaveY + leaveH; }

    static float scale(float w, float h) { return Math.min(w / 1672f, h / 941f); }
    static final float KEYS_SCALE = .7f;
    /** Bottom edge (GUI px) the F key may use: above the action bar and the 차카데미 hotbar status chips. */
    static float keysBottom(float w, float h) {
        float bottom = h - 76;
        if (PlayerHudClient.active()) { var l = PlayerHudLayout.player((int) w, (int) h, PlayerHudSettings.get().percent()); bottom = Math.min(bottom, (l.hotbarTop() - 25 - 16) * l.scale()); }
        return bottom;
    }
    /** Feed / queue card top (GUI px): below the vanilla status-effect icons. */
    static float rightTop() { return 54; }

    static void render(DrawContext ctx, RenderTickCounter ticks) {
        var c = MinecraftClient.getInstance();
        if (!KyudichiClient.active()) { forfeitShown = false; leaveShown = false; return; }
        if (c.player == null || c.options.hudHidden || !HudPointer.hudVisibleUnder(c.currentScreen)) { leaveShown = false; return; }
        if (generation != UiResources.generation()) { close(); generation = UiResources.generation(); }
        if (images == null) images = new HudTextureCache(c);
        if (text == null) text = new CodexTypography(c);
        images.beginFrame(); text.beginFrame();
        float w = c.getWindow().getScaledWidth(), h = c.getWindow().getScaledHeight(), s = scale(w, h);
        long now = Util.getMeasuringTimeMs();
        var canvas = new KyudichiCanvas(ctx, images, text);
        var m = KyudichiClient.match;
        try {
            ctx.getMatrices().push(); ctx.getMatrices().translate(0, 0, 130);
            if (m.phase() != 0) markers(canvas, c, w, h, s, now, ticks.getTickDelta(false));
            // score board
            try(var customization=HudCustomization.begin(ctx,"battle-score",w/2f-322*s,14*s,646*s,(KyudichiClient.duel()?84:193)*s)){
            canvas.push(w / 2f - 836 * s, 0, s);
            HudView.score(new Pen(canvas, -1, -1, null), score(m, now));
            canvas.pop();
            }
            try(var customization=HudCustomization.begin(ctx,"battle-action",w/2f+338*s,38*s,186*s,48*s)){
            forfeit(ctx, canvas, m, w, s);
            leave(canvas, m, w, s);
            }
            // feed
            // owner 2026-10-20: kill feed a little smaller (80 %), still anchored top right
            float fs = s * FEED_SCALE;
            var feedLines=feed(now);
            if(!feedLines.isEmpty())try(var customization=HudCustomization.begin(ctx,"battle-feed",w-292*fs,rightTop(),277*fs,(67+63*(feedLines.size()-1))*fs)){
            canvas.push(w - 1672 * fs, rightTop() - 28 * fs, fs);
            HudView.feed(new Pen(canvas, -1, -1, null), feedLines);
            canvas.pop();
            }
            // F key
            if (!KyudichiClient.spectator() && !KyudichiClient.duel()) {
                // owner 2026-10-17: 70 % of the reference size, 24 px (design) higher
                float ks = s * KEYS_SCALE;
                try(var customization=HudCustomization.begin(ctx,"battle-keys",w/2f-124*ks,keysBottom(w,h)-24*s-128*ks,248*ks,128*ks)){
                canvas.push(w / 2f - 836 * ks, keysBottom(w, h) - 24 * s - 924 * ks, ks);
                HudView.keys(new Pen(canvas, -1, -1, null), keys(now));
                canvas.pop();
                }
            }
            magnetAndWheel(ctx, c, s, now);
        } finally { ctx.getMatrices().pop(); images.endFrame(); }
    }

    // ================================================================== data → views
    private static HudView.Score score(KyudichiProtocol.Match m, long now) {
        int left = Math.max(0, m.timeLeftMs() - (int) (now - KyudichiClient.matchAt));
        boolean duel = (m.flags() & 16) != 0;
        String sub = switch (m.phase()) { case 0 -> "준비"; case 1 -> duel ? "라운드 시작" : "곧 시작"; case 3 -> duel ? "라운드 종료" : "골!"; default -> (m.flags() & 2) != 0 ? "골든골" : ""; };
        boolean out = (m.flags() & 1) != 0;
        if (duel) {
            // 1:1: names instead of 우리 팀 / 상대 팀, the score is rounds won
            String a = nameOf(m, 0), b = nameOf(m, 1);
            if (m.myTeam() == 1) return new HudView.Score(b, m.scoreB(), Theme.RED, a, m.scoreA(), Theme.BLUE, Theme.time2(left), sub, false, true);
            return new HudView.Score(a, m.scoreA(), Theme.BLUE, b, m.scoreB(), Theme.RED, Theme.time2(left), sub, false, true);
        }
        if (m.myTeam() == 1) return new HudView.Score("우리 팀", m.scoreB(), Theme.RED, "상대 팀", m.scoreA(), Theme.BLUE, Theme.time2(left), sub, out);
        if (m.myTeam() == 0) return new HudView.Score("우리 팀", m.scoreA(), Theme.BLUE, "상대 팀", m.scoreB(), Theme.RED, Theme.time2(left), sub, out);
        return new HudView.Score("파랑 팀", m.scoreA(), Theme.BLUE, "빨강 팀", m.scoreB(), Theme.RED, Theme.time2(left), sub, out);
    }

    private static String nameOf(KyudichiProtocol.Match m, int team) {
        for (var p : m.players()) if (p.team() == team) return p.nick();
        return team == 0 ? "파랑" : "빨강";
    }

    private static List<HudView.FeedLine> feed(long now) {
        KyudichiClient.feed.removeIf(f -> now - f.at() > 6000);
        var out = new ArrayList<HudView.FeedLine>();
        int mine = KyudichiClient.match == null ? 2 : KyudichiClient.match.myTeam();
        for (int k = KyudichiClient.feed.size() - 1; k >= 0 && out.size() < 4; k--) {
            var line = KyudichiClient.feed.get(k);
            long age = now - line.at();
            float alpha = age > 5000 ? 1 - (age - 5000) / 1000f : Math.min(1, age / 150f);
            var f = line.feed();
            // the actor's team is f.team(); a kill's target is on the other team
            int targetTeam = 1 - f.team();
            int targetColor = mine == 2 ? (targetTeam == 0 ? Theme.BLUE : Theme.RED) : targetTeam == mine ? Theme.BLUE : Theme.RED;
            out.add(new HudView.FeedLine(f.type(), f.actor(), f.target(), f.points(), Theme.WHITE, targetColor, Math.clamp(alpha, 0, 1)));
        }
        return out;
    }

    private static HudView.Keys keys(long now) {
        var k = KyudichiClient.key;
        int action = KyudichiClient.live() ? k.action() : 0;
        float lit = action != 0 ? Math.max(0, 1 - (now - KyudichiClient.keyLitAt) / 220f) : 0;
        long age = now - KyudichiClient.keyAt;
        float cooldown = k.cooldownMs() > 0 ? Math.max(0, 1 - age / (float) k.cooldownMs()) : 0;
        int hold = Math.max(0, k.holdLeftMs() - (int) age);
        return new HudView.Keys(action, cooldown, lit, hold, 10_000);
    }

    // ================================================================== forfeit (hud/forfeit_button, art 352×62 at (4,4) inside 360×96)
    private static void forfeit(DrawContext ctx, Canvas canvas, KyudichiProtocol.Match m, float w, float s) {
        boolean vote = (m.flags() & 8) != 0;
        forfeitShown = (HudPointer.active() || vote) && m.myTeam() != 2 && m.phase() == 2;
        if (!forfeitShown) return;
        float fh = 32 * s, k = fh / 62f, fw = 352 * k, fx = w / 2f + (1160 - 836 + 14) * s, fy = (56 - 16) * s;
        boolean over = HudPointer.active() && HudPointer.x() >= fx && HudPointer.x() <= fx + fw && HudPointer.y() >= fy && HudPointer.y() <= fy + fh;
        canvas.image(over ? "hud/forfeit_button_hover" : "hud/forfeit_button", fx - 4 * k, fy - 4 * k, 360 * k, 96 * k, 1);
        canvas.text(vote ? "포기 투표 중 · 찬성" : "포기", fx + fw / 2, fy + fh / 2, 16 * s, vote ? 0xFFFFD6DA : Theme.CREAM, Canvas.Face.SERIF, Canvas.CENTER);
        forfeitX = fx; forfeitY = fy; forfeitW = fw; forfeitH = fh;
    }

    // ================================================================== spectator leave (same hud/forfeit_button art and place, 2026-10-20)
    /** Spectators get a "나가기" button where players see 포기; open chat (or the cursor) to click it. Sends SpectateLeave. */
    private static void leave(Canvas canvas, KyudichiProtocol.Match m, float w, float s) {
        leaveShown = m.myTeam() == 2;
        if (!leaveShown) return;
        float fh = 32 * s, k = fh / 62f, fw = 352 * k, fx = w / 2f + (1160 - 836 + 14) * s, fy = (56 - 16) * s;
        boolean over = HudPointer.active() && HudPointer.x() >= fx && HudPointer.x() <= fx + fw && HudPointer.y() >= fy && HudPointer.y() <= fy + fh;
        canvas.image(over ? "hud/forfeit_button_hover" : "hud/forfeit_button", fx - 4 * k, fy - 4 * k, 360 * k, 96 * k, 1);
        canvas.text("나가기", fx + fw / 2, fy + fh / 2, 16 * s, Theme.CREAM, Canvas.Face.SERIF, Canvas.CENTER);
        leaveX = fx; leaveY = fy; leaveW = fw; leaveH = fh;
    }

    // ================================================================== world markers
    private static void markers(Canvas canvas, MinecraftClient c, float w, float h, float s, long now, float delta) {
        if (!AimView.valid()) return;
        Vec3d cam = AimView.camera();
        for (var sh : KyudichiClient.shrines) {
            Vec3d p = new Vec3d(sh.x(), sh.y(), sh.z());
            float[] sp = AimView.toScreen(p);
            if (sp == null || !onScreen(sp, w, h, 8)) continue;
            float size = (float) Math.clamp(30 - p.distanceTo(cam) * 0.15, 16, 30);
            canvas.texture("magiccodex:textures/gui/kyudichi/ping/" + PING_ICONS[sh.type() == 3 ? 14 : 10 + sh.type()] + ".png", sp[0] - size / 2, sp[1] - size / 2, size, size, .92f);
        }
        var balls = KyudichiClient.balls;
        if (balls != null) for (var b : balls.balls()) {
            int k = Math.clamp(b.kind(), 0, 2);
            if (k == 2) continue; // owner 2026-10-17: no snitch marker (the server no longer sends it either)
            Vec3d p = KyudichiClient.ball(b);
            boolean danger = k == 1 && (b.flags() & 2) != 0;
            float alpha = danger && (now / 140) % 2 == 0 ? .55f : 1;
            float[] sp = AimView.toScreen(p);
            double d = p.distanceTo(cam);
            float iw = INDICATOR_SIZE[k][0] * s * INDICATOR_SCALE[k], ih = INDICATOR_SIZE[k][1] * s * INDICATOR_SCALE[k];
            if (sp != null && onScreen(sp, w, h, iw)) {
                float lift = ih * .9f;
                canvas.image(INDICATOR[k], sp[0] - iw / 2, sp[1] - ih / 2 - lift, iw, ih, alpha);
                canvas.text(Math.round(d) + "m", sp[0], sp[1] - lift + ih / 2 + 9, Math.max(9, (k == 1 ? 12 : 15) * s), Theme.WHITE, Canvas.Face.SANS_BOLD, Canvas.CENTER);
            } else {
                float[] e = edge(c, p, sp, w, h, Math.max(iw, ih) * .8f + 6, delta);
                canvas.image(INDICATOR[k], e[0] - iw / 2, e[1] - ih / 2, iw, ih, alpha);
                // arrow just outside the icon, pointing the way
                float aw = ARROW_SIZE[k][0] * s * INDICATOR_SCALE[k], ah = ARROW_SIZE[k][1] * s * INDICATOR_SCALE[k];
                float r = Math.max(iw, ih) / 2 + Math.max(aw, ah) / 2;
                float deg = (float) Math.toDegrees(e[2]);
                canvas.imageRotated(ARROW[k], e[0] + (float) Math.cos(e[2]) * r, e[1] + (float) Math.sin(e[2]) * r, aw, ah, deg - ARROW_BASE[k], alpha);
            }
        }
        KyudichiClient.pings.removeIf(pm -> now - pm.at() > 3000);
        for (var pm : KyudichiClient.pings) {
            var pg = pm.ping();
            Vec3d p = new Vec3d(pg.x(), pg.y(), pg.z());
            if (pg.target() != null) { var e = KyudichiClient.player(pg.target()); if (e != null) p = e.getLerpedPos(delta).add(0, e.getHeight() + .6, 0); }
            long age = now - pm.at();
            float alpha = age > 2400 ? 1 - (age - 2400) / 600f : 1;
            float pop = age < 180 ? 1.4f - .4f * age / 180f : 1;
            String icon = "magiccodex:textures/gui/kyudichi/ping/" + PING_ICONS[pg.kind()] + ".png";
            float[] sp = AimView.toScreen(p);
            float size = 26 * pop;
            if (sp != null && onScreen(sp, w, h, 20)) {
                canvas.texture(icon, sp[0] - size / 2, sp[1] - 10 - size / 2, size, size, alpha);
                canvas.text(pg.nick(), sp[0], sp[1] + 10, 10, ((Math.round(alpha * 255)) << 24) | 0xE7CE91, Canvas.Face.SANS_BOLD, Canvas.CENTER);
            } else {
                float[] e = edge(c, p, sp, w, h, 34, delta);
                canvas.texture(icon, e[0] - 13, e[1] - 13, 26, 26, alpha);
            }
        }
    }

    private static boolean onScreen(float[] sp, float w, float h, float m) { return sp[0] >= m && sp[0] <= w - m && sp[1] >= m && sp[1] <= h - m; }

    /** Screen-edge point (and angle in [2]) in the direction of a world point; works for points behind the camera. */
    private static float[] edge(MinecraftClient c, Vec3d world, float[] sp, float w, float h, float inset, float delta) {
        double dx, dy;
        if (sp != null) { dx = sp[0] - w / 2.0; dy = sp[1] - h / 2.0; }
        else {
            Vec3d f = c.player.getRotationVec(delta);
            if (c.options.getPerspective().isFrontView()) f = f.multiply(-1);
            Vec3d right = f.crossProduct(new Vec3d(0, 1, 0));
            if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
            right = right.normalize();
            Vec3d up = right.crossProduct(f).normalize();
            Vec3d rel = world.subtract(AimView.camera());
            dx = rel.dotProduct(right); dy = -rel.dotProduct(up);
            if (Math.abs(dx) < 1e-3 && Math.abs(dy) < 1e-3) dy = 1;
        }
        double len = Math.hypot(dx, dy); if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
        dx /= len; dy /= len;
        double rx = w / 2.0 - inset, ry = h / 2.0 - inset;
        double t = 1 / Math.sqrt((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry));
        return new float[]{(float) (w / 2.0 + dx * t), (float) (h / 2.0 + dy * t), (float) Math.atan2(dy, dx)};
    }

    // ================================================================== ping preview (Ctrl): magnet ring, direction menu
    private static void magnetAndWheel(DrawContext ctx, MinecraftClient c, float s, long now) {
        if (!(c.currentScreen instanceof HudCursorScreen) || KyudichiClient.spectator()) return;
        double mx = HudPointer.x(), my = HudPointer.y();
        var g = KyudichiClient.gesture;
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(0, 0, 30);
            if (g == null || KyudichiClient.direction(mx - g.x(), my - g.y()) < 0) {
                var t = KyudichiClient.Magnet.find(g == null ? mx : g.x(), g == null ? my : g.y());
                if (t != null) HudMesh.arc(ctx, t.sx(), t.sy(), 13 + (float) Math.sin(now / 120.0), 2f, 0, (float) (Math.PI * 2), 0xFFE7CE91);
            }
            if (g != null) {
                int dir = KyudichiClient.direction(mx - g.x(), my - g.y());
                int state = switch (dir) { case 3 -> 1; case 4 -> 2; case 5 -> 3; case 6 -> 4; default -> 0; };
                images.drawCentered(ctx, "magiccodex:textures/gui/kyudichi/ping/wheel_" + WHEEL[state] + ".png", (float) g.x(), (float) g.y(), Math.round(255 * s), 0xF2FFFFFF);
            }
        } finally { ctx.getMatrices().pop(); }
    }
}
