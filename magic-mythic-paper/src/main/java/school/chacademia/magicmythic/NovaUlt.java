package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

/**
 * chanova types 368~373 (궁극). Rows: tools/nova/ult.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md.
 * Chants (368 / 369 / 371) are chachannel lines whose then-skill (CHA_<id>_x) casts the chanova type here;
 * a broken chant sends external:struct_cancel (ChannelMech), which the rows use to stop the chant visuals.
 */
final class NovaUlt {
    private NovaUlt() {}

    // ------------------------------------------------------------------ 368 state
    /** Live 염제 강림 forms (caster -> form). */
    private static final Map<UUID, Flame> FORM = new HashMap<>();
    /** Own 368 cooldown (epoch ms): starts when the form ends (the Bridge cooldown is cleared while the form lasts). */
    private static final Map<UUID, Long> FLAME_CD = new HashMap<>();
    private static final Listener HOOK = new Listener() {};
    private static boolean hooked;
    private static MagicMythicPlugin plugin;

    private static final class Flame {
        final Nova n; int shots; int lastShot = -100;
        Flame(Nova n) { this.n = n; }
    }

    static void register(Map<String, NovaMech.Impl> m) {
        // 368 염제 강림 (FlameEmperor): chachannel 3 s → this (then-skill): 8 s form. Burning trail where the caster walked
        // (burn 3 s on enemies stepping on it, ~1.7 s per trail point, no fire blocks); each press of this spell key during the
        // form = big fireball (max 4, intercepted in MagicCastAttemptEvent so no mana / cooldown / new visual cast);
        // form end = r 6 blast at end + 0.35 s. The 600 s cooldown starts when the form ends.
        m.put("flame_emperor", n -> {
            UUID id = n.pl.getUniqueId();
            if (FORM.containsKey(id)) return false;
            hook();
            Flame f = new Flame(n);
            FORM.put(id, f);
            n.fx("flame_emperor", n.pl.getLocation(), n.pl);
            if (hooked) n.clearCooldown();   // the key must reach the server again (fireballs); our own cooldown starts at the end
            int ticks = Math.clamp(n.i("ticks", 160), 20, 400), life = n.i("trail_life", 34);
            ArrayDeque<Object[]> trail = new ArrayDeque<>();           // {Location, expiry age}
            Map<UUID, Integer> burnt = new HashMap<>();
            Location[] last = {n.pl.getLocation()};
            n.loop(ticks, age -> {
                if (n.pl.isDead() || FORM.get(id) != f) return false;
                Location now = n.pl.getLocation();
                if (age % 3 == 0) {
                    if (now.getWorld() != last[0].getWorld()) return false;
                    if (now.distanceSquared(last[0]) > 0.49) { trail.addLast(new Object[]{Nova.ground(now), age + life}); last[0] = now.clone(); }
                    while (!trail.isEmpty() && ((int) trail.peekFirst()[1] < age || trail.size() > 48)) trail.pollFirst();
                }
                if (age % 5 == 0 && !trail.isEmpty()) {
                    for (LivingEntity e : n.near(now, 16, 24)) {
                        Integer lb = burnt.get(e.getUniqueId());
                        if (lb != null && age - lb < 20) continue;
                        Location el = e.getLocation();
                        for (Object[] t : trail) {
                            Location tl = (Location) t[0];
                            double dx = tl.getX() - el.getX(), dz = tl.getZ() - el.getZ(), dy = el.getY() - tl.getY();
                            if (dx * dx + dz * dz <= 1.2 && dy > -0.6 && dy < 1.6) { n.status(e, "burn:60"); burnt.put(e.getUniqueId(), age); break; }
                        }
                    }
                }
                return true;
            }, () -> flameEnd(n, f));
            return true;
        });

        // 369 레비아탄 (LeviathanRise): chant line: water circle at the aim point (zoneChant) at t = 0.
        m.put("leviathan_rise_mark", n -> { n.fx("zoneChant", Nova.ground(n.aim())); return true; });
        // then-skill after the 5 s chant: strike; swallow (+0.4 s) = 35% hit + slow 3 s; crash (+1.40 s) = full hit + stun 1 s; drain at +3.2 s.
        m.put("leviathan_rise", n -> {
            Location at = Nova.ground(n.aim());
            double r = n.d("r", 10);
            n.fx("strike", at);
            n.later(8, () -> { for (LivingEntity e : n.near(at, r)) { n.hit(e, n.dmg(0.35)); n.status(e, "slow:60"); } });
            n.later(28, () -> {
                for (LivingEntity e : n.near(at, r)) {
                    if (n.hit(e, n.dmg())) n.status(e, "stun:20");
                    n.fx("struck", e.getLocation());
                }
            });
            n.later(64, () -> n.fx("drain", at));
            return true;
        });

        // 370 하늘의 눈 (HeavenCyclone): 10 s storm r 8 fixed at the caster's feet. Enemies inside rise to +4 blocks over ~1.5 s and are
        // held there by velocity (players: gravity = no flying / broom); end at 10 s, pushed down at end + 0.4 s, slam damage at end + 0.55 s.
        m.put("heaven_cyclone", n -> {
            Location c0 = n.pl.getLocation().clone();
            double r = n.d("r", 8), lift = n.d("lift", 4);
            int ticks = Math.clamp(n.i("ticks", 200), 40, 400);
            Map<UUID, LivingEntity> held = new LinkedHashMap<>();
            Map<UUID, double[]> base = new HashMap<>();                // {ground y, age at catch}
            n.later(24, () -> n.fx("sustain", c0));
            n.loop(ticks + 11, age -> {
                if (age < ticks) {
                    if (age % 5 == 0) for (LivingEntity e : n.near(c0, r, 16)) {
                        if (held.containsKey(e.getUniqueId()) || n.boss(e)) continue;
                        held.put(e.getUniqueId(), e);
                        base.put(e.getUniqueId(), new double[]{Nova.ground(e.getLocation()).getY(), age});
                        n.fx("lift", e.getLocation(), e);
                    }
                    for (var it = held.values().iterator(); it.hasNext(); ) {
                        LivingEntity e = it.next();
                        Location el = e.getLocation();
                        double dx = el.getX() - c0.getX(), dz = el.getZ() - c0.getZ();
                        if (!e.isValid() || e.isDead() || el.getWorld() != c0.getWorld() || dx * dx + dz * dz > (r + 2) * (r + 2) || !n.enemy(e)) { it.remove(); continue; }
                        double[] b = base.get(e.getUniqueId());
                        double h = lift * Math.min(1, (age - b[1]) / 30.0);
                        double vy = Math.clamp((b[0] + h - el.getY()) * 0.35, -0.35, 0.45);
                        Vector v = e.getVelocity();
                        // slow swirl around the eye, sideways speed damped so they stay inside
                        Vector swirl = new Vector(-dz, 0, dx);
                        if (swirl.lengthSquared() > 1e-4) swirl.normalize().multiply(0.06);
                        e.setVelocity(new Vector(v.getX() * 0.5 + swirl.getX(), vy, v.getZ() * 0.5 + swirl.getZ()));
                        e.setFallDistance(0);
                        if (e instanceof Player && age % 20 == 0) n.status(e, "gravity:30");
                    }
                    return true;
                }
                if (age == ticks) n.fx("end", c0);
                if (age == ticks + 8) for (LivingEntity e : held.values()) if (e.isValid() && !e.isDead()) { e.setFallDistance(0); e.setVelocity(new Vector(0, -2.2, 0)); }
                if (age >= ticks + 10) {
                    Set<LivingEntity> all = new LinkedHashSet<>(n.near(c0, r + 1, 32));
                    for (LivingEntity e : held.values()) if (e.isValid() && !e.isDead() && n.enemy(e)) all.add(e);
                    for (LivingEntity e : all) {
                        e.setFallDistance(0);
                        n.hit(e, n.dmg());
                        n.fx("slam", Nova.ground(e.getLocation()));
                    }
                    return false;
                }
                return true;
            }, null);
            return true;
        });

        // 371 심연의 문 (AbyssGate): chant line: range ring + seam at the aim point (mark) at t = 0.
        m.put("abyss_gate_mark", n -> { n.fx("mark", Nova.ground(n.aim())); return true; });
        // then-skill after the 3 s chant: gate opens (begin, sustain at +1.4 s); enemies in r 8 are dragged to the gate
        // for 8 s; doors close at +8.0 s (end), SLAM at end + 0.45 s = big hit + sleep 1 s.
        m.put("abyss_gate", n -> {
            Location at = Nova.ground(n.aim());
            Location core = at.clone().add(0, 3.6, 0);
            double r = n.d("r", 8), pull = n.d("pull", 0.16);
            int open = Math.clamp(n.i("ticks", 160), 40, 400);
            Set<UUID> linked = new HashSet<>();
            n.fx("begin", at);
            n.later(28, () -> n.fx("sustain", at));
            n.loop(open + 9, age -> {
                if (age < open && age % 2 == 0) for (LivingEntity e : n.near(at, r, 24)) {
                    if (linked.add(e.getUniqueId())) n.fx("pull", core, e);
                    Vector to = at.toVector().subtract(e.getLocation().toVector()).setY(0);
                    double d = to.length();
                    if (d < 1.5) continue;
                    double s = n.boss(e) ? pull * 0.3 : pull;
                    Vector v = e.getVelocity();
                    e.setVelocity(new Vector(v.getX() * 0.3, v.getY(), v.getZ() * 0.3).add(to.multiply(s / d)));
                }
                if (age == open) n.fx("end", at);
                if (age >= open + 9) {
                    for (LivingEntity e : n.near(at, r, 32)) {
                        if (n.hit(e, n.dmg())) n.status(e, "sleep:20");
                        n.fx("sleep", e.getLocation());
                    }
                    return false;
                }
                return true;
            }, null);
            return true;
        });

        // 372 오로라 (AuroraVeil): support ultimate, 12 s, r 10 fixed at the caster's feet. Allies inside (caster + party):
        // heal pulse every 2 s at cast + 2.3 s (in sync with the visual) and immunity to new debuffs while inside;
        // enemies inside: magic_weakness (refreshed while inside, lasts 1.5 s after leaving).
        m.put("aurora_veil", n -> {
            Location c0 = n.pl.getLocation().clone();
            double r = n.d("r", 10);
            int ticks = Math.clamp(n.i("ticks", 240), 40, 400);
            Set<UUID> shown = new HashSet<>(), mine = new HashSet<>();
            var guards = n.plugin.guards();
            n.later(30, () -> n.fx("aurora_veil", c0));
            n.loop(ticks, age -> {
                if (age % 10 == 0) {
                    for (Player a : n.allies(c0, r, 32)) {
                        if (shown.add(a.getUniqueId())) n.fx("aurora_veil_ally", a.getLocation(), a);
                        // never shorten someone else's immunity (dawn_ritual): only add / refresh our own
                        if (guards != null && (mine.contains(a.getUniqueId()) || !guards.has(a, Guards.Type.IMMUNE))) {
                            guards.add(a, new Guards.Guard(Guards.Type.IMMUNE, guards.now() + 15, 0, 0, 0, 0, 0, 0, n.pl.getUniqueId(), n.spell));
                            mine.add(a.getUniqueId());
                        }
                    }
                    for (LivingEntity e : n.near(c0, r, 32)) {
                        n.status(e, "magic_weakness:30");
                        if (shown.add(e.getUniqueId())) n.fx("aurora_veil_weaken", e.getLocation(), e);
                    }
                }
                if (age >= 46 && (age - 46) % 40 == 0)
                    for (Player a : n.allies(c0, r, 32)) n.plugin.targets().heal(n.pl, a, n.dmg(), "");
                return true;
            }, () -> n.fx("aurora_veil_end", c0));
            return true;
        });

        // 373 타이탄의 발걸음 (TitanStride): 3 stomps along the caster's facing at 3.5 / 7 / 11 blocks. Each foot = 1 s amber warning
        // (the impact system starts 1 s before its hit): hits at +1.0 s (r 2.2, 60%), +1.8 s (r 2.2, 60%), +2.8 s (r 3.5, 100% + stun 1 s).
        m.put("titan_stride", n -> {
            Vector f = n.flat();
            Location o = n.pl.getLocation();
            double[] dist = {3.5, 7, 11}, rad = {2.2, 2.2, 3.5}, mult = {0.6, 0.6, 1};
            int[] start = {0, 16, 36};
            String[] sys = {"titan_stride_step_l", "titan_stride_step_r", "titan_stride_final"};
            for (int k = 0; k < 3; k++) {
                int i = k;
                Location p = Nova.ground(o.clone().add(f.clone().multiply(dist[i])).add(0, 1.5, 0));
                p.setYaw(o.getYaw());
                n.later(start[i], () -> n.fx(sys[i], p));
                n.later(start[i] + 20, () -> {
                    for (LivingEntity e : n.near(p, rad[i])) if (n.hit(e, n.dmg(mult[i])) && i == 2) n.status(e, "stun:20");
                });
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ 368 helpers
    private static void flameEnd(Nova n, Flame f) {
        UUID id = n.pl.getUniqueId();
        if (!FORM.remove(id, f)) return;
        long now = System.currentTimeMillis();
        FLAME_CD.values().removeIf(t -> t < now);
        FLAME_CD.put(id, now + Math.round(n.d("cooldown", 600) * 1000));
        if (!n.pl.isOnline()) return;
        Location at = n.pl.getLocation();
        n.fx("flame_emperor_end", at, n.pl);
        if (n.pl.isDead()) return;
        double r = n.d("r", 6);
        n.later(7, () -> { for (LivingEntity e : n.near(at, r)) { n.hit(e, n.dmg()); n.status(e, "burn:60"); } });
    }

    /** One press of the 368 key during the form. Null = let the Bridge handle the cast; "" = refused silently; text = refused with it. */
    private static String recast(Player p) {
        Flame f = FORM.get(p.getUniqueId());
        if (f != null) {
            Nova n = f.n;
            int max = n.i("shots", 4);
            if (f.shots >= max) return "화염탄을 모두 썼어요";
            int tick = Bukkit.getCurrentTick();
            if (tick - f.lastShot < 6) return "";
            f.lastShot = tick; f.shots++;
            double splash = n.d("splash", 2.5), share = n.d("shot_mult", 0.5);
            n.shotFrom(n.hand(), n.look(), n.d("speed", 1.3), n.d("shot_range", 32), "flame_emperor_fireball").radius(0.7)
                .end((at, wall) -> {
                    n.fx("flame_emperor_fireball_hit", at);
                    for (LivingEntity e : n.near(at, splash)) { n.hit(e, n.dmg(share)); n.status(e, "burn:60"); }
                })
                .fire();
            n.info("화염탄 " + (max - f.shots) + "발 남음");
            return "";
        }
        Long until = FLAME_CD.get(p.getUniqueId());
        if (until == null) return null;
        long left = until - System.currentTimeMillis();
        if (left <= 0) { FLAME_CD.remove(p.getUniqueId()); return null; }
        return "재사용 대기 " + (int) Math.ceil(left / 1000.0) + "초";
    }

    /** MagicCastAttemptEvent (MagicCodexBridge, by reflection like StatusEffects): 368 recasts never reach the Bridge charge. */
    private static void hook() {
        if (hooked || plugin == null) return;
        var bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) return;
        try {
            Class<? extends Event> type = Class.forName("school.magiccodex.paper.MagicCastAttemptEvent", true, bridge.getClass().getClassLoader()).asSubclass(Event.class);
            Method player = type.getMethod("getPlayer"), spell = type.getMethod("getSpellId"),
                cancel = type.getMethod("setCancelled", boolean.class), message = type.getMethod("setMessage", String.class);
            // HIGHEST + ignoreCancelled: a stunned / disarmed caster (StatusEffects, HIGH) is refused before we fire
            Bukkit.getPluginManager().registerEvent(type, HOOK, EventPriority.HIGHEST, (l, ev) -> {
                if (!type.isInstance(ev) || FORM.isEmpty() && FLAME_CD.isEmpty()) return;
                try {
                    if (!"flame_emperor".equals(spell.invoke(ev)) || !(player.invoke(ev) instanceof Player p)) return;
                    String why = recast(p);
                    if (why == null) return;
                    cancel.invoke(ev, true);
                    if (!why.isEmpty()) message.invoke(ev, why);
                } catch (ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().warning("염제 강림 재시전 처리 실패: " + ex); }
            }, plugin, true);
            hooked = true;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ex) {
            plugin.getLogger().warning("MagicCastAttemptEvent 없음: 염제 강림 화염탄 재시전이 동작하지 않음 (MagicCodexBridge 업데이트 필요)");
        }
    }

    static void enable(MagicMythicPlugin p) {
        plugin = p;
        hooked = false;   // a reload unregistered the old listener
        FORM.clear();
        Bukkit.getScheduler().runTask(p, NovaUlt::hook);
    }
}
