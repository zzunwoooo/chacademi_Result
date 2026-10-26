package school.chacademia.magicmythic;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * chameteor - A16 server-authoritative falling spell (falling_star). Replaces "delay N + chahit".
 *  The landing point is fixed at cast: the ground under the aim point (first collision surface within ground=20
 *  blocks below), or the aim point itself when there is no ground. Blocks on the way never stop it (owner decision 3).
 *  charge=68 (ticks before the fall), speed=0.6 (blocks/tick), skyx/skyy/skyz = sky start offset: must equal the
 *  PortableVFX binding's meteor phase (delay-ticks, speed, offset) so the visual meteor lands on this tick.
 *  On the landing tick, once: the chahit options (r max knock up ignite effect status mult flat ...) around the
 *  landing point, then VFX external:impact there. The VFX cast is told the landing point at cast (external:land_at).
 *  Caster logout/death/world change or plugin disable: no damage, the VFX cast is cancelled.
 */
final class MeteorMech extends Mech {
    private final HitMech hit;
    MeteorMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); hit = new HitMech(plugin, p); }

    @Override boolean run(Ctx c) {
        Location aim = c.at();
        if (aim == null || aim.getWorld() == null || !plugin.protection().pair(c.caster(), aim)) return false;
        Location landing = landing(aim, Math.clamp(p.d("ground", 20), 1, 64));
        if (!plugin.protection().pair(c.caster(), landing)) return false;
        double distance = new Vector(p.d("skyx", -3), p.d("skyy", 24), p.d("skyz", 19)).length();
        var fall = new MeteorFall(Math.clamp(p.i("charge", 68), 0, 200), distance, Math.clamp(p.d("speed", 0.6), 0.05, 8));
        String spell = p.s("spell", "");
        Player owner = c.caster() instanceof Player pl ? pl : null;
        if (owner != null && !spell.isBlank()) Links.vfx(owner, spell, "external:land_at", landing, null);
        var world = c.caster().getWorld();
        return plugin.ticker().add(new Ticker.Tick() {
            @Override public String kind() { return "meteor"; }
            @Override public boolean tick() {
                if (fall.finished()) return false;
                if (!c.caster().isValid() || c.caster().isDead() || c.caster().getWorld() != world) { cancel(); return false; }
                if (!fall.tick()) return true;
                // landing tick: damage exactly once, centred on the point chosen at cast
                hit.run(new Ctx(c.data(), c.caster(), null, landing.clone(), c.damage()));
                if (owner != null && !spell.isBlank()) Links.vfx(owner, spell, "external:impact", landing, null);
                return false;
            }
            @Override public void stop() { if (!fall.finished()) cancel(); }
            private void cancel() {
                fall.cancel();
                if (owner != null && owner.isOnline() && !spell.isBlank()) Links.vfx(owner, spell, "external:cancel", null, null);
            }
        });
    }

    /** Ground under the aim point (collision shapes, fluids ignored), or the aim point when nothing is within `depth`. */
    static Location landing(Location aim, double depth) {
        var world = aim.getWorld();
        if (world == null || !world.isChunkLoaded(aim.getBlockX() >> 4, aim.getBlockZ() >> 4)) return aim.clone();
        Location from = aim.clone().add(0, 0.05, 0);
        var ground = world.rayTraceBlocks(from, new Vector(0, -1, 0), depth, FluidCollisionMode.NEVER, true);
        return ground == null ? aim.clone() : ground.getHitPosition().toLocation(world);
    }
}
