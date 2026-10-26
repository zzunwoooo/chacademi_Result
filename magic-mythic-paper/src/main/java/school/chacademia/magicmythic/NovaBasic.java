package school.chacademia.magicmythic;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** chanova types 325~335 (기초). Rows: tools/nova/basic.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md. */
final class NovaBasic {
    private NovaBasic() {}

    static void register(Map<String, NovaMech.Impl> m) {
        // 325 불씨 튕기기 (SparkFlick): 3 embers fanned ±0.3 rad, 0.10/0.13/0.16 s after the cast, 6 blocks; every ember hits once
        m.put("spark_flick", n -> {
            double speed = n.d("speed", 0.9), range = n.d("range", 6), yaw = n.d("yaw", 0.3);
            int[] at = {2, 3, 3};
            for (int k = 0; k < 3; k++) {
                double a = (k - 1) * yaw;
                n.later(at[k], () -> {
                    Vector dir = n.look().rotateAroundY(-a);
                    n.shotFrom(n.hand(), dir, speed, range, "spark_flick").radius(0.5)
                        .hit((t, p) -> { if (n.hit(t, n.dmg())) n.fx("spark_flick_hit", p); return false; })
                        .end((p, wall) -> { if (wall) n.fx("spark_flick_hit", p); })
                        .fire();
                });
            }
            return true;
        });

        // 326 작은 불숨 (EmberPuff): 3 m / ±30° cone, breath front leaves the mouth at +0.10 s and moves 9 m/s -> each enemy hit at
        // 0.10 + d/9 s: damage + burn 3 s + ember_puff_burn at the feet
        m.put("ember_puff", n -> {
            String st = "burn:" + n.i("ticks", 60);
            wave(n, 2, n.d("r", 3), n.d("half", 30), 9, e -> {
                if (n.hit(e, n.dmg())) { n.status(e, st); n.fx("ember_puff_burn", e.getLocation()); }
            });
            return true;
        });

        // 327 물살 베기 (TideSlash): 3 m half circle in front, blade crosses at +0.10 s -> damage + ~1 block push away from the caster
        m.put("tide_slash", n -> {
            double r = n.d("r", 3), push = n.d("push", 0.45);
            Location from = n.pl.getLocation(); Vector f = n.flat();
            n.later(2, () -> {
                for (LivingEntity e : cone(n, r, 90)) {
                    if (!n.hit(e, n.dmg())) continue;
                    n.knock(e, from, f, push, 0.15);
                    n.fx("tide_slash_hit", Nova.chest(e));
                }
            });
            return true;
        });

        // 328 얼음 조각 (IceShard): 2 shards at +0.12 / +0.27 s; every shard hits once; both shards on the same enemy -> slow 3 s + frostbite
        m.put("ice_shard", n -> {
            double speed = n.d("speed", 1.2), range = n.d("range", 16);
            String st = "slow:" + n.i("ticks", 60);
            Map<UUID, Integer> got = new HashMap<>();
            int[] at = {2, 5};
            for (int k = 0; k < 2; k++) {
                double side = k == 0 ? -0.08 : 0.08;
                n.later(at[k], () -> {
                    Vector right = n.flat().crossProduct(new Vector(0, 1, 0)).multiply(-1);
                    Location from = n.hand().add(n.look().multiply(0.16)).add(right.multiply(side)).add(0, -side, 0);
                    boolean[] hitOne = {false};
                    n.shotFrom(from, n.look(), speed, range, "ice_shard").radius(0.45)
                        .hit((t, p) -> {
                            hitOne[0] = true;
                            if (n.hit(t, n.dmg())) {
                                n.fx("ice_shard_hit", p);
                                if (got.merge(t.getUniqueId(), 1, Integer::sum) == 2) { n.status(t, st); n.fx("ice_shard_frostbite", t.getLocation()); }
                            }
                            return false;
                        })
                        .end((p, wall) -> { if (!hitOne[0]) n.fx("ice_shard_hit", p); })
                        .fire();
                });
            }
            return true;
        });

        // 329 바람 송곳 (WindNeedle): launch at +0.12 s, 24 blocks at 3 blocks/tick, pierces every enemy once (low damage), end burst
        m.put("wind_needle", n -> {
            double speed = n.d("speed", 3), range = n.d("range", 24);
            n.later(2, () -> n.shotFrom(n.hand(), n.look(), speed, range, "wind_needle").radius(0.4)
                .hit((t, p) -> { if (n.hit(t, n.dmg())) n.fx("wind_needle_pierce", p); return true; })
                .end((p, wall) -> n.fx("wind_needle_end", p))
                .fire());
            return true;
        });

        // 330 작은 회오리 (MiniTwister): ground-hugging twister from 0.9 m ahead at +0.15 s, 0.18 blocks/tick for 8 blocks,
        // passes through enemies (r 0.75): each once -> damage + small lift
        m.put("mini_twister", n -> {
            double speed = n.d("speed", 0.18), range = n.d("range", 8), up = n.d("up", 0.45);
            n.later(3, () -> {
                Vector f = n.flat();
                Location from = Nova.ground(n.pl.getLocation().add(f.clone().multiply(0.9)).add(0, 1, 0)).add(0, 0.05, 0);
                n.shotFrom(from, f, speed, range, "projectile").radius(0.75).ground()
                    .hit((t, p) -> {
                        if (n.hit(t, n.dmg())) { n.knock(t, p, f, 0.05, up); n.fx("lift", t.getLocation()); }
                        return true;
                    })
                    .end((p, wall) -> n.fx("end", p))
                    .fire();
            });
            return true;
        });

        // 331 그림자 송곳니 (ShadowFang): fangs at the target's feet at +0.08 s, eruption 0.20 s later -> damage if the target is
        // still on the pool (r 1.8, so it can be side-stepped)
        m.put("shadow_fang", n -> {
            LivingEntity t = pick(n, n.d("range", 12), 6);   // range: spells.py #80 1.7배 (route 와 같게)
            if (t == null) return false;
            double r = n.d("r", 1.8);
            n.later(2, () -> {
                if (!t.isValid() || t.isDead() || t.getWorld() != n.pl.getWorld()) return;
                Location at = Nova.ground(t.getLocation().add(0, 0.5, 0));
                n.fx("shadow_fang", at);
                n.later(4, () -> { if (onSpot(t, at, r, 3)) n.hit(t, n.dmg()); });
            });
            return true;
        });

        // 332 어둠 구슬 (DuskOrb): slow homing orb (0.18 blocks/tick) from the hand at +0.15 s, follows the target for 6 s;
        // first enemy touched -> damage + magic_weakness 3 s; a block stops it (hit burst); 6 s without contact -> fizzle
        m.put("dusk_orb", n -> {
            LivingEntity t = pick(n, n.d("range", 16), 10);
            if (t == null) return false;
            double speed = n.d("speed", 0.18), turn = n.d("turn", 0.15);
            int life = n.i("life", 120);
            String st = "magic_weakness:" + n.i("ticks", 60);
            n.later(3, () -> {
                Vector dir = Nova.chest(t).toVector().subtract(n.hand().toVector());
                if (dir.lengthSquared() < 1e-4) dir = n.look();
                boolean[] touched = {false};
                var s = n.shotFrom(n.hand(), dir, speed, speed * life, "dusk_orb").radius(0.6).homing(t, turn)
                    .hit((e, p) -> {
                        touched[0] = true;
                        if (n.hit(e, n.dmg())) n.status(e, st);
                        n.fx("dusk_orb_hit", p);
                        return false;
                    })
                    .end((p, wall) -> { if (!touched[0]) n.fx(wall ? "dusk_orb_hit" : "dusk_orb_fizzle", p); });
                s.maxLife = life + 5;
                s.fire();
            });
            return true;
        });

        // 333 프리즘 탄 (PrismShot): bolt at +0.18 s (20 blocks); on its first enemy hit it refracts from the hit point into
        // 2 smaller bolts aimed at the 2 nearest OTHER enemies within 6 blocks (60 % damage each)
        m.put("prism_shot", n -> {
            double speed = n.d("speed", 1.4), range = n.d("range", 20), r = n.d("split_r", 6), mult = n.d("split", 0.6);
            n.later(4, () -> {
                boolean[] done = {false};
                n.shotFrom(n.hand(), n.look(), speed, range, "prism_shot").radius(0.5)
                    .hit((t, p) -> {
                        done[0] = true;
                        n.hit(t, n.dmg());
                        n.fx("prism_shot_hit", p);
                        int k = 0;
                        for (LivingEntity o : n.near(p, r, 6)) {
                            if (o == t || k >= 2) continue;
                            if (!o.hasLineOfSight(t)) continue;
                            k++;
                            Vector to = Nova.chest(o).toVector().subtract(p.toVector());
                            boolean[] sd = {false};
                            n.shotFrom(p, to, 1.1, r + 3, "prism_shot_split").radius(0.45).homing(o, 0.3)
                                .hit((e, q) -> {
                                    if (e == t) return true;
                                    sd[0] = true;
                                    n.hit(e, n.dmg(mult));
                                    n.fx("prism_shot_split_hit", q);
                                    return false;
                                })
                                .end((q, wall) -> { if (!sd[0]) n.fx("prism_shot_split_hit", q); })
                                .fire();
                        }
                        return false;
                    })
                    .end((p, wall) -> { if (!done[0]) n.fx("prism_shot_hit", p); })
                    .fire();
            });
            return true;
        });

        // 334 모래 한 줌 (SandFlick): 3.5 m / ±30° cone, sand leaves the hand at +0.12 s and moves 9 m/s -> weak damage + blindness 3 s
        m.put("sand_flick", n -> {
            String st = "blindness:" + n.i("ticks", 60);
            wave(n, 2, n.d("r", 3.5), n.d("half", 30), 9, e -> {
                if (n.hit(e, n.dmg())) { n.status(e, st); n.fx("sand_flick_eyes", e.getLocation()); }
            });
            return true;
        });

        // 335 햇살 꽂기 (SunlanceDrop): sigil over the target's feet at +0.05 s, beam reaches the ground 0.43 s later -> damage
        // if the target is still inside the target ring (r 1.3, so it can be dodged)
        m.put("sunlance_drop", n -> {
            LivingEntity t = pick(n, n.d("range", 16), 8);
            if (t == null) return false;
            double r = n.d("r", 1.3);
            n.later(1, () -> {
                if (!t.isValid() || t.isDead() || t.getWorld() != n.pl.getWorld()) return;
                Location at = Nova.ground(t.getLocation().add(0, 0.5, 0));
                n.fx("sunlance_drop", at);
                n.later(9, () -> { if (onSpot(t, at, r, 4)) n.hit(t, n.dmg()); });
            });
            return true;
        });
    }

    static void enable(MagicMythicPlugin plugin) {}

    // ------------------------------------------------------------------ helpers (basic tier only)
    /** Enemies in a horizontal cone in front of the caster (feet origin, reach r, half angle in degrees), in sight. */
    private static List<LivingEntity> cone(Nova n, double r, double halfDeg) {
        Location o = n.pl.getLocation();
        Vector f = n.flat();
        var out = new ArrayList<LivingEntity>();
        for (LivingEntity e : n.near(o.clone().add(0, 1, 0), r + 1.5, 16)) {
            Vector to = e.getLocation().toVector().subtract(o.toVector());
            double dy = to.getY(); to.setY(0);
            double d = to.length(), half = e.getWidth() / 2;
            if (d - half > r || dy < -1.5 || dy > 2.5) continue;
            if (d > half + 0.2) {
                double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(f)))));
                if (ang - Math.toDegrees(Math.atan2(half, d)) > halfDeg) continue;
            }
            if (!n.pl.hasLineOfSight(e)) continue;
            out.add(e);
        }
        return out;
    }

    /** Cone check at `start` ticks, then each enemy is hit when the front (speed m/s) reaches it. */
    private static void wave(Nova n, int start, double r, double halfDeg, double speed, Consumer<LivingEntity> hit) {
        n.later(start, () -> {
            Location o = n.pl.getLocation();
            for (LivingEntity e : cone(n, r, halfDeg)) {
                double d = Math.hypot(e.getLocation().getX() - o.getX(), e.getLocation().getZ() - o.getZ());
                int dt = (int) Math.round(d / speed * 20);
                n.later(Math.max(0, dt - 1), () -> hit.accept(e));
            }
        });
    }

    /** ENTITY spells: the Bridge's target (or the enemy nearest the aim point); null (warned + mana back) when none in range. */
    private static LivingEntity pick(Nova n, double range, double mana) {
        LivingEntity t = n.target();
        if (t == null || !n.enemy(t)) {
            var l = n.near(n.aim(), 2.5, 1);
            t = l.isEmpty() ? null : l.get(0);
        }
        if (t == null || t.getWorld() != n.pl.getWorld() || t.getLocation().distanceSquared(n.pl.getLocation()) > (range + 2) * (range + 2)) {
            n.warn("마법을 걸 대상이 없어요");
            n.refund(mana);
            return null;
        }
        return t;
    }

    /** Target still alive and standing on the marked ground spot (horizontal r, up to `h` blocks above it). */
    private static boolean onSpot(LivingEntity t, Location at, double r, double h) {
        if (!t.isValid() || t.isDead() || t.getWorld() != at.getWorld()) return false;
        Location l = t.getLocation();
        double dy = l.getY() - at.getY();
        return Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ()) <= r + t.getWidth() / 2 && dy >= -1 && dy <= h;
    }
}
