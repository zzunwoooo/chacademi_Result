package kr.chacademi.dungeon;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import school.chacademia.dungeon.protocol.DungeonProtocol;

// [기능:파티던전 시작]
/**
 * 파티 던전 핑 (MagicCodex 큐디치 핑과 같은 조작·그림, 그림은 MagicCodex 모드 안의 것을 빌려 씀).
 * Ctrl 을 누르면 열리는 포인터 화면(HudCursorScreen)에서:
 * <ul>
 *   <li>클릭 = 그 자리 핑. 화면에서 60px(1080p 기준) 안의 파티원 → 아군(1), 적 몹 → 적(2), 없으면 보는 곳 위치(0)</li>
 *   <li>18px 이상 끌기 = 방향 메뉴: 위 경고(3) · 오른쪽 가는 중(4) · 아래 도움(5) · 왼쪽 힐 줘(6). 끄는 동안 휠 그림</li>
 * </ul>
 * 받은 핑은 3초 동안 HUD 에 (그림 + 찍은 사람 닉네임), 화면 밖이면 가장자리에. 최대 12개, 같은 사람이 0.4초 안에 또 찍으면 바꿈.
 * 월드 → 화면 변환은 이번 프레임의 카메라·투영 행렬을 월드 그리기 끝에 받아 둔 값으로.
 */
final class PartyPing {
    private PartyPing() {}

    private static final String DIR = "textures/gui/kyudichi/ping/";
    private static final String[] ICONS = {"location", "ally", "enemy", "warning", "on_my_way", "help", "heal_me"};
    private static final String[] WHEEL = {"idle", "warning", "on_my_way", "help", "heal_me"};
    private static final int WHEEL_RADIUS = 18, MAX = 12;
    private static final long LIFE = 3000;
    private record Gesture(double x, double y) {}
    private record Shown(DungeonProtocol.Ping ping, long at) {}
    private record Target(int kind, UUID id, Vec3 pos) {}
    private static Gesture gesture;
    private static final List<Shown> PINGS = new ArrayList<>();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean valid;

    static boolean enabled() { var r = DungeonClient.run; return r.active() && r.party(); }

    static void clear() { PINGS.clear(); gesture = null; valid = false; }

    static void cancel() { gesture = null; }

    // ---- 카메라

    static void capture(WorldRenderContext ctx) {
        Matrix4f projection = ctx.projectionMatrix(), view = ctx.positionMatrix();
        if (projection == null || view == null || ctx.camera() == null) { valid = false; return; }
        VIEW_PROJECTION.set(projection).mul(view);
        INVERSE.set(VIEW_PROJECTION).invert();
        camera = ctx.camera().getPosition();
        valid = Float.isFinite(INVERSE.m00());
    }

    /** 월드 점 → GUI 좌표 (카메라 뒤면 null). */
    static float[] toScreen(Vec3 world) {
        var mc = Minecraft.getInstance();
        if (!valid || mc.level == null) return null;
        var c = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1).mul(VIEW_PROJECTION);
        if (c.w <= 0.05f) return null;
        var w = mc.getWindow();
        return new float[]{(c.x / c.w + 1) / 2 * w.getGuiScaledWidth(), (1 - c.y / c.w) / 2 * w.getGuiScaledHeight()};
    }

    /** GUI 좌표 → 카메라에서 나가는 방향. */
    private static Vec3 ray(double sx, double sy) {
        var w = Minecraft.getInstance().getWindow();
        float nx = (float) (sx / w.getGuiScaledWidth() * 2 - 1), ny = (float) (1 - sy / w.getGuiScaledHeight() * 2);
        var near = new Vector4f(nx, ny, -1, 1).mul(INVERSE);
        var far = new Vector4f(nx, ny, 1, 1).mul(INVERSE);
        near.div(near.w);
        far.div(far.w);
        return new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
    }

    /** 커서가 가리키는 곳 (블록 160칸까지, 없으면 허공 48칸). */
    private static Vec3 worldPoint(double sx, double sy) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !valid) return null;
        Vec3 dir = ray(sx, sy);
        var hit = mc.level.clip(new ClipContext(camera, camera.add(dir.scale(160)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? camera.add(dir.scale(48)) : hit.getLocation();
    }

    /** 커서 근처(60px@1080p) 파티원 → 아군, 적 몹 → 적. 파티원이 먼저. */
    private static Target magnet(double sx, double sy) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !valid) return null;
        float radius = 60f * mc.getWindow().getGuiScaledHeight() / 1080f;
        Target best = null;
        double bestD = radius * radius;
        int bestPri = 9;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !(e instanceof LivingEntity living) || !living.isAlive() || e.distanceToSqr(mc.player) > 96 * 96) continue;
            int kind;
            if (e instanceof Player p) { if (p.isSpectator()) continue; kind = 1; }
            else if (e instanceof Enemy) kind = 2;
            else continue;
            Vec3 pos = e.position().add(0, e.getBbHeight() * 0.6, 0);
            float[] s = toScreen(pos);
            if (s == null) continue;
            double d = (s[0] - sx) * (s[0] - sx) + (s[1] - sy) * (s[1] - sy);
            int pri = kind == 1 ? 0 : 1;
            if (d <= radius * radius && (pri < bestPri || pri == bestPri && d < bestD)) { best = new Target(kind, e.getUUID(), pos); bestD = d; bestPri = pri; }
        }
        return best;
    }

    // ---- 조작 (Ctrl 포인터 화면)

    static boolean press(Screen screen, double x, double y, int button) {
        if (button != 0 || !enabled() || HudLink.editing() || !"school.magiccodex.client.HudCursorScreen".equals(screen.getClass().getName())) return false;
        gesture = new Gesture(x, y);
        return true;
    }

    static boolean release(double x, double y, int button) {
        var g = gesture;
        gesture = null;
        if (g == null || button != 0 || !enabled()) return false;
        int dir = direction(x - g.x(), y - g.y());
        if (dir >= 0) send(dir, null, worldPoint(g.x(), g.y()));
        else {
            var t = magnet(g.x(), g.y());
            if (t != null) send(t.kind(), t.id(), t.pos());
            else send(0, null, worldPoint(g.x(), g.y()));
        }
        return true;
    }

    /** 끈 방향 → 핑 종류, 짧으면 -1 (클릭). */
    private static int direction(double dx, double dy) {
        if (dx * dx + dy * dy < WHEEL_RADIUS * WHEEL_RADIUS) return -1;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 4 : 6;
        return dy < 0 ? 3 : 5;
    }

    private static void send(int kind, UUID target, Vec3 pos) {
        if (pos == null) return;
        var json = new JsonObject();
        json.addProperty("k", kind);
        json.addProperty("x", (float) pos.x);
        json.addProperty("y", (float) pos.y);
        json.addProperty("z", (float) pos.z);
        if (target != null) json.addProperty("t", target.toString());
        Net.send(DungeonProtocol.C_PING, 0, json.toString());
    }

    /** 끄는 동안 방향 휠 (누른 자리 가운데). */
    static void wheel(Screen screen, GuiGraphics g, int mx, int my) {
        var ge = gesture;
        if (ge == null || !enabled()) return;
        int state = switch (direction(mx - ge.x(), my - ge.y())) { case 3 -> 1; case 4 -> 2; case 5 -> 3; case 6 -> 4; default -> 0; };
        float size = 255 * Math.min(g.guiWidth() / 1672f, g.guiHeight() / 941f);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Art.image(g, Art.mc(DIR + "wheel_" + WHEEL[state] + ".png"), (float) ge.x() - size / 2, (float) ge.y() - size / 2, size, size, 0xF2FFFFFF);
        g.pose().popPose();
    }

    // ---- 받은 핑

    static void receive(DungeonProtocol.Ping p) {
        if (p.kind() < 0 || p.kind() > DungeonProtocol.PING_MAX) return;
        long now = DungeonClient.now();
        PINGS.removeIf(s -> s.ping().sender().equals(p.sender()) && now - s.at() < 400);
        PINGS.add(new Shown(p, now));
        while (PINGS.size() > MAX) PINGS.remove(0);
    }

    static void hud(GuiGraphics g, float delta) {
        if (PINGS.isEmpty()) return;
        if (!enabled()) { PINGS.clear(); return; }
        long now = DungeonClient.now();
        PINGS.removeIf(s -> now - s.at() > LIFE);
        float w = g.guiWidth(), h = g.guiHeight();
        for (var s : PINGS) {
            var p = s.ping();
            Vec3 pos = new Vec3(p.x(), p.y(), p.z());
            if (p.target() != null) {
                var e = entity(p.target());
                if (e != null) pos = e.getPosition(delta).add(0, e.getBbHeight() + 0.6, 0);
            }
            long age = now - s.at();
            float alpha = age > 2400 ? 1 - (age - 2400) / 600f : 1, pop = age < 180 ? 1.4f - 0.4f * age / 180f : 1;
            int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
            var tex = Art.mc(DIR + ICONS[p.kind()] + ".png");
            float[] sp = toScreen(pos);
            float size = 26 * pop;
            if (sp != null && sp[0] >= 20 && sp[0] <= w - 20 && sp[1] >= 20 && sp[1] <= h - 20) {
                Art.image(g, tex, sp[0] - size / 2, sp[1] - 10 - size / 2, size, size, (a << 24) | 0xFFFFFF);
                Art.textCenter(g, p.nick(), sp[0], sp[1] + 10, 8, (a << 24) | 0xE7CE91);
            } else {
                float[] e = edge(pos, sp, w, h, 34);
                Art.image(g, tex, e[0] - 13, e[1] - 13, 26, 26, (a << 24) | 0xFFFFFF);
            }
        }
    }

    private static Entity entity(UUID id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var player = level.getPlayerByUUID(id);
        if (player != null) return player;
        for (Entity e : level.entitiesForRendering()) if (id.equals(e.getUUID())) return e;
        return null;
    }

    /** 화면 밖 핑: 그쪽 방향의 화면 가장자리 (카메라 뒤도). */
    private static float[] edge(Vec3 world, float[] sp, float w, float h, float inset) {
        double dx, dy;
        if (sp != null) { dx = sp[0] - w / 2.0; dy = sp[1] - h / 2.0; }
        else {
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 rel = world.subtract(camera);
            var left = cam.getLeftVector();
            var up = cam.getUpVector();
            dx = -(rel.x * left.x() + rel.y * left.y() + rel.z * left.z());
            dy = -(rel.x * up.x() + rel.y * up.y() + rel.z * up.z());
            if (Math.abs(dx) < 1e-3 && Math.abs(dy) < 1e-3) dy = 1;
        }
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
        dx /= len; dy /= len;
        double rx = w / 2.0 - inset, ry = h / 2.0 - inset;
        double t = 1 / Math.sqrt((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry));
        return new float[]{(float) (w / 2.0 + dx * t), (float) (h / 2.0 + dy * t)};
    }
}
// [기능:파티던전 끝]
