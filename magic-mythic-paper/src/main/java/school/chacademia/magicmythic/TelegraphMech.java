package school.chacademia.magicmythic;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.TelegraphProtocol;
import school.magiccodex.protocol.TelegraphProtocol.Shape;
import school.magiccodex.protocol.TelegraphProtocol.Show;

/**
 * chatelegraph - enemy area attack with a red ground warning (예고 장판) first. Owner 2026-10-19: area attacks that
 * are not body strikes always warn; enemy warnings are red.
 *
 *   shape=circle|line|cone   at=target|caster (circle default target; line/cone start at the caster, aimed at the target)
 *   r=3 (circle/cone radius)  length=8 width=2 (line)  angle=60 (cone, full degrees)  ticks=20 (warning)
 *   flat=6 / mult (damage, Ctx.dmg)  element=fire  ignite=60  status=burn:60,slow:40:0.3 (Targets.sideEffects)
 *   hits=players|enemies (default players: mob skills hit players only)  height=3 (vertical reach above the ground)
 *   vfx=sealcircle/seal (PortableVFX id; "claude:" added when missing)  vfxlead=2 (ticks before the hit)
 *   vfxref=3 (authored radius: scale = r / vfxref)  tint=FFFFFF (colour-variant mobs)  cancel=true (caster death cancels)
 *   pulses=4 period=8 (sustained: extra hits every period ticks on the same shape, e.g. breath)
 *   knock=1.2 (outward / forward push)  up=0.8 (launch)  pull=1.5 (toward the caster) - applied to every target hit
 *   warn=false (no red warning is drawn; the hit still lands after ticks on the same shape - mob basic attacks)
 *   blink=true (the caster appears beside the warned spot when it fires, then hits: "몹이 나에게 순간이동")
 *     vfxout=pack/system (played where the caster vanished)
 *   tpme=true (every target hit is teleported in front of the caster, then knock/up/pull apply: "나를 몹 앞으로")
 *     vfxin=pack/system (played at the arrival spot)
 *
 * Shape, origin and yaw are fixed when the warning starts; the hit uses the same {@link Show}.
 */
final class TelegraphMech extends Mech {
    TelegraphMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        Show show = show(c);
        if (show == null) return false;
        var world = c.caster().getWorld();
        int ticks = show.ticks();
        String vfx = vfxId(p.s("vfx", ""));
        if (!vfx.isEmpty()) {
            int lead = Math.clamp(p.i("vfxlead", 2), 0, ticks);
            float scale = (float) Math.clamp(show.size() / Math.max(0.1, p.d("vfxref", show.size())), 0.25, 4);
            int tint = tint(p.s("tint", "FFFFFF"));
            var at = new Location(world, show.x(), show.y(), show.z());
            int id = show.id();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (plugin.telegraphs().live(id)) {
                    // Unit transforms retain the old playback path; resized areas use authored metre-input scaling.
                    if(Math.abs(scale-1f)<0.000001f)plugin.reactions().vfx().effect(vfx,at,1f,show.yaw(),40,tint);
                    else plugin.reactions().vfx().areaEffect(vfx,at,show.size(),show.yaw(),40,tint);
                }
            }, Math.max(1, ticks - lead));
        }
        double dmg = c.dmg(p);
        int pulses = Math.clamp(p.i("pulses", 1), 1, 20), period = Math.clamp(p.i("period", 8), 1, 40);
        return plugin.telegraphs().start(show, c.caster(), world, p.b("cancel", true), p.b("warn", true), s -> {
            if (p.b("blink", false)) blink(c.caster(), s);
            hit(c.caster(), s, dmg);
            // sustained skills (breath): more hits on the same shape, one delayed task each - nothing runs per tick
            for (int k = 1; k < pulses; k++) plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (c.caster().isValid() && !c.caster().isDead()) hit(c.caster(), s, dmg);
            }, (long) k * period);
        });
    }

    Show show(Ctx c) {
        Shape shape = Shape.of(p.s("shape", "circle"));
        if (shape == null) return null;
        LivingEntity caster = c.caster();
        boolean atTarget = p.s("at", shape == Shape.CIRCLE ? "target" : "caster").toLowerCase(Locale.ROOT).startsWith("t");
        Location origin = (atTarget ? c.at() : caster.getLocation()).clone();
        Location aim = c.target() != null ? c.target().getLocation() : c.at();
        float yaw = caster.getLocation().getYaw();
        double dx = aim.getX() - caster.getLocation().getX(), dz = aim.getZ() - caster.getLocation().getZ();
        if (!atTarget && dx * dx + dz * dz > 0.04) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float size = (float) Math.clamp(shape == Shape.LINE ? p.d("length", 8) : p.d("r", 3), 0.5, TelegraphProtocol.MAX_SIZE);
        float width = (float) Math.clamp(p.d("width", 2), 0.3, TelegraphProtocol.MAX_WIDTH);
        try {
            return new Show(plugin.telegraphs().id(), shape, origin.getX(), Telegraphs.ground(origin), origin.getZ(), yaw, size, width,
                (float) p.d("angle", 60), Math.clamp(p.i("ticks", 20), 1, TelegraphProtocol.MAX_TICKS), TelegraphProtocol.RED);
        } catch (IllegalArgumentException e) { return null; }
    }

    private void hit(LivingEntity caster, Show s, double dmg) {
        if (caster == null || !caster.isValid()) return;
        var world = caster.getWorld();
        var center = new Location(world, s.x(), s.y(), s.z());
        boolean playersOnly = !p.s("hits", "players").toLowerCase(Locale.ROOT).startsWith("e");
        double height = Math.clamp(p.d("height", 3), 0.5, 16);
        double reach = (s.shape() == Shape.LINE ? Math.hypot(s.size(), s.width() / 2) : s.size()) + 2;
        for (Entity e : world.getNearbyEntities(center, reach, height + 2, reach)) {
            if (!(e instanceof LivingEntity t) || (playersOnly && !(e instanceof Player))) continue;
            if (!plugin.targets().enemy(caster, t)) continue;
            double feet = t.getLocation().getY();
            if (feet < s.y() - 1 || feet > s.y() + height) continue;
            // 오너 2026-10-23: 실제 판정 = 그려진 예고 범위. 몸 폭 여유(pad) 없이 발 위치가 모양 안에 있어야 맞음.
            if (!TelegraphProtocol.inside(s, t.getLocation().getX(), t.getLocation().getZ(), 0)) continue;
            if (!plugin.targets().hurt(caster, t, dmg, p.s("element", ""))) continue; // 0 = protection probe only
            plugin.targets().sideEffects(caster, t, p);
            if (p.b("tpme", false)) pullTo(caster, t);
            push(caster, s, t);
        }
    }

    /** blink=true: put the caster 1.4 blocks from the warned spot on its own side, facing it (2-block clear space only). */
    private void blink(LivingEntity caster, Show s) {
        if (caster == null || !caster.isValid()) return;
        var from = caster.getLocation();
        double dx = from.getX() - s.x(), dz = from.getZ() - s.z(), d = Math.hypot(dx, dz);
        if (d < 1e-3) { dx = -s.fx(); dz = -s.fz(); d = 1; }
        var to = new Location(caster.getWorld(), s.x() + dx / d * 1.4, s.y(), s.z() + dz / d * 1.4);
        if (!clear(to)) to = new Location(caster.getWorld(), s.x(), s.y(), s.z());
        if (!clear(to)) return;
        to.setYaw((float) Math.toDegrees(Math.atan2(dx / d, -dz / d))); to.setPitch(0);
        String out = vfxId(p.s("vfxout", ""));
        if (!out.isEmpty()) plugin.reactions().vfx().effect(out, from, 1, from.getYaw(), 30, tint(p.s("tint", "FFFFFF")));
        caster.teleport(to);
    }

    /** tpme=true: bring a hit target 1.6 blocks in front of the caster, facing it. */
    private void pullTo(LivingEntity caster, LivingEntity t) {
        var c = caster.getLocation();
        var fwd = c.getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-4) fwd = new org.bukkit.util.Vector(0, 0, 1);
        fwd.normalize();
        var to = c.clone().add(fwd.multiply(Math.max(1.2, caster.getWidth() / 2 + 1.1)));
        to.setY(Telegraphs.ground(to.clone().add(0, 1, 0)));
        if (!clear(to)) return;
        to.setYaw(c.getYaw() + 180); to.setPitch(0);
        t.teleport(to);
        String in = vfxId(p.s("vfxin", ""));
        if (!in.isEmpty()) plugin.reactions().vfx().effect(in, to, 1, to.getYaw(), 30, tint(p.s("tint", "FFFFFF")));
    }

    private static boolean clear(Location at) {
        var b = at.getBlock();
        return b.isPassable() && b.getRelative(org.bukkit.block.BlockFace.UP).isPassable();
    }

    /** knock = outward from the shape origin (circle/cone: radial, line: along the forward), up = vertical, pull = toward the caster (blocks/tick-ish). */
    private void push(LivingEntity caster, Show s, LivingEntity t) {
        double knock = p.d("knock", 0), up = p.d("up", 0), pull = p.d("pull", 0);
        if (knock == 0 && up == 0 && pull == 0) return;
        var at = t.getLocation();
        double dx, dz;
        if (s.shape() != Shape.LINE) { dx = at.getX() - s.x(); dz = at.getZ() - s.z(); }
        else { dx = s.fx(); dz = s.fz(); }
        double len = Math.hypot(dx, dz); if (len < 1e-4) { dx = s.fx(); dz = s.fz(); len = 1; }
        var v = new org.bukkit.util.Vector(dx / len * knock, up, dz / len * knock);
        if (pull != 0) {
            var c = caster.getLocation(); double px = c.getX() - at.getX(), pz = c.getZ() - at.getZ(), pl = Math.hypot(px, pz);
            if (pl > 1e-4) v.add(new org.bukkit.util.Vector(px / pl * pull, 0.15 * Math.abs(pull), pz / pl * pull));
        }
        t.setVelocity(t.getVelocity().add(v));
    }

    static String vfxId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "claude:" + id;
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? id : "";
    }

    static int tint(String raw) {
        try { return Integer.parseInt(raw.trim().replace("#", ""), 16) & 0xFFFFFF; }
        catch (RuntimeException e) { return 0xFFFFFF; }
    }
}
