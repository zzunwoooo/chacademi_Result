package school.chacademia.magicmythic;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

/** chanova types 356~367 (상급). Rows: tools/nova/high.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md. */
final class NovaHigh {
    private NovaHigh() {}

    /** 362 천공 강하: caster uuid -> server tick until which fall damage is cancelled. */
    private static final Map<UUID, Integer> NO_FALL = new HashMap<>();
    private static int clock;

    static void register(Map<String, NovaMech.Impl> m) {
        // 356 홍염 나선 (CrimsonSpiral): launch flash at 0.35 s, piercing helix 18 blocks; per enemy 3 hits at +0/+0.12/+0.24 s, 3rd = burn 3 s
        m.put("crimson_spiral", n -> {
            double speed = n.d("speed", 1.8), range = n.d("range", 18);
            n.later(6, () -> {
                Vector dir = n.look();
                Location from = n.pl.getLocation().add(0, 1.35, 0).add(n.flat());
                n.shotFrom(from, dir, speed, range, "crimson_spiral").radius(0.8)
                    .hit((t, p) -> {
                        double d = n.dmg();
                        if (!n.hit(t, d)) return true;
                        n.fx("crimson_spiral_pierce", Nova.chest(t), t);
                        n.later(1, () -> n.hit(t, d));
                        n.later(4, () -> { if (n.hit(t, d)) { n.status(t, "burn:60"); n.fx("crimson_spiral_burn", t.getLocation(), t); } });
                        return true;
                    })
                    .end((p, wall) -> n.fx("crimson_spiral_end", p))
                    .fire();
            });
            return true;
        });

        // 357 화산탄 세례 (VolcanicBarrage): 6 rocks; mark k at 0.05+0.13k s, rock lands 1.2 s after its mark (r 2 damage + slow 3 s)
        m.put("volcanic_barrage", n -> {
            Location c = Nova.ground(n.aim().add(0, 1.5, 0));
            double r = n.d("radius", 6), hitR = n.d("hit", 2.0);
            Vector back = n.pl.getLocation().toVector().subtract(c.toVector()).setY(0);
            back = back.lengthSquared() < 1e-4 ? n.flat().multiply(-1) : back.normalize();
            final Vector backDir = back;
            var rnd = ThreadLocalRandom.current();
            int[] markAt = {1, 4, 6, 9, 11, 14};
            for (int k = 0; k < 6; k++) {
                double a = Math.toRadians(k * 60 + rnd.nextDouble(-20, 20));
                double d = k == 0 ? rnd.nextDouble(0, 1.2) : rnd.nextDouble(2.0, r - 0.4);
                Location land = Nova.ground(c.clone().add(Math.cos(a) * d, 2.5, Math.sin(a) * d));
                n.later(markAt[k] - 1, () -> {
                    n.fx("mark", land);
                    n.later(10, () -> {
                        Location from = land.clone().add(backDir.clone().multiply(3.5)).add(0, 14, 0);
                        Vector v = land.toVector().subtract(from.toVector());
                        GameplayVisual vis = n.shot("rock", from, v);
                        n.loop(13, age -> {
                            if (vis != null) vis.move(from.clone().add(v.clone().multiply((age + 1) / 13.0)), v);
                            return age < 12;
                        }, () -> {
                            if (vis != null) vis.close();
                            n.fx("impact", land);
                            for (LivingEntity e : n.near(land.clone().add(0, 0.5, 0), hitR)) if (n.hit(e, n.dmg())) n.status(e, "slow:60");
                        });
                    });
                });
            }
            return true;
        });

        // 358 작열의 표적 (ScorchLock): 6 s lock, sight/range ray test every 5 ticks (2 misses in a row = lost);
        // success -> trigger, spear hits at +0.43 s; failure -> cancel visuals + half mana back
        m.put("scorch_lock", n -> {
            LivingEntity t = n.target();
            if (t == null) return false;
            double max = n.d("range", 25), mana = n.d("mana", 250);
            if (!sees(n.pl, t, max)) { n.warn("대상이 보이지 않아요"); n.refund(mana); return false; }
            n.fx("lock", Nova.chest(t), t);
            int[] lost = {0};
            boolean[] over = {false};
            Runnable fail = () -> {
                if (over[0]) return;
                over[0] = true;
                if (!n.pl.isOnline()) return;
                n.fx("break", t.isValid() ? Nova.chest(t) : n.pl.getEyeLocation(), t);
                n.refund(mana / 2);
                n.info("조준을 놓쳤어요. 마나 절반을 돌려받았어요");
            };
            n.loop(120, age -> {
                if (!t.isValid() || t.isDead() || n.pl.isDead()) { fail.run(); return false; }
                if (age % 5 == 0) {
                    if (sees(n.pl, t, max)) lost[0] = 0;
                    else if (++lost[0] >= 2) { fail.run(); return false; }
                }
                if (age >= 119) {
                    over[0] = true;
                    n.fx("fire", Nova.chest(t), t);
                    n.later(8, () -> { if (t.isValid() && !t.isDead()) n.hit(t, n.dmg()); });
                    return false;
                }
                return true;
            }, fail);
            return true;
        });

        // 359 빙결 지대 (FreezeField): r 5 for 6 s; an enemy that has spent 3 s inside (total) is stunned 1 s once, shatter damage at +0.9 s
        m.put("freeze_field", n -> {
            Location c = Nova.ground(n.aim().add(0, 1.5, 0));
            double r = n.d("radius", 5);
            int need = n.i("stay", 60);
            Map<UUID, Integer> inside = new HashMap<>();
            Set<UUID> frozen = new HashSet<>();
            n.loop(120, age -> {
                if (age % 5 != 0) return true;
                for (LivingEntity e : n.near(c.clone().add(0, 1, 0), r + 2)) {
                    Location l = e.getLocation();
                    double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), dy = l.getY() - c.getY();
                    if (dx * dx + dz * dz > r * r || dy < -1.5 || dy > 3) continue;
                    UUID id = e.getUniqueId();
                    if (frozen.contains(id) || inside.merge(id, 5, Integer::sum) < need) continue;
                    frozen.add(id);
                    if (!n.hit(e, 0)) continue;
                    n.status(e, "stun:20");
                    n.fx("freeze_field_freeze", l, e);
                    n.later(17, () -> n.hit(e, n.dmg()));
                }
                return true;
            }, null);
            return true;
        });

        // 360 썰물 (EbbMark): bolt at 0.22 s; on hit the target's feet are marked, 3 s later it is swept back there (0.35 s) and hit
        m.put("ebb_mark", n -> {
            LivingEntity t0 = n.target();
            if (t0 == null) return false;
            n.later(3, () -> {
                Location from = n.hand();
                Vector dir = Nova.chest(t0).toVector().subtract(from.toVector());
                boolean[] marked = {false};
                n.shotFrom(from, dir.lengthSquared() < 1e-4 ? n.look() : dir, n.d("speed", 1.2), 26, "bolt").radius(0.6).homing(t0, 0.35)
                    .hit((t, p) -> { marked[0] = true; ebbMark(n, t, p); return false; })
                    .end((p, wall) -> { if (!marked[0]) n.fx("markHit", p); })
                    .fire();
            });
            return true;
        });

        // 361 얼음 뱀 (IceSerpent): launch at 0.3 s, head 0.55 above the ground on an S path (±1.4), 20 blocks, pierces;
        // per enemy 3 hits at +0/+0.15/+0.3 s + slow 3 s
        m.put("ice_serpent", n -> {
            double amp = n.d("amp", 0.9), wave = n.d("wave", 10), speed = n.d("speed", 0.6), range = n.d("range", 20);
            n.later(5, () -> {
                Vector base = n.flat();
                Location start = Nova.ground(n.pl.getLocation().add(base).add(0, 0.6, 0)).add(0, 0.55, 0);
                Nova.Shot[] ref = new Nova.Shot[1];
                ref[0] = n.shotFrom(start, base, speed, range, "ice_serpent").radius(1.2).ghost()
                    .hit((t, p) -> {
                        double d = n.dmg();
                        if (!n.hit(t, d)) return true;
                        n.status(t, "slow:60");
                        n.fx("ice_serpent_hit", t.getLocation());
                        n.later(2, () -> n.hit(t, d));
                        n.later(5, () -> n.hit(t, d));
                        return true;
                    })
                    .step((p, d) -> {
                        Nova.Shot s = ref[0];
                        double feet = s.pos.getY() - 0.55;
                        double g = Telegraphs.ground(new Location(s.pos.getWorld(), s.pos.getX(), feet + 1.2, s.pos.getZ()));
                        if (g - feet > 1.1 || feet - g > 3 || !Nova.passable(s.pos)) { s.range = s.travelled; return; }
                        s.pos.setY(g + 0.55);
                        s.dir = base.clone().rotateAroundY(amp * Math.cos(2 * Math.PI * s.travelled / wave));
                    })
                    .end((p, wall) -> n.fx("ice_serpent_end", Nova.ground(p)))
                    .fire();
            });
            return true;
        });

        // 362 천공 강하 (SkyDive): rise 7 blocks, hang, dive to the aim point (≤ 12 blocks); landing r 3 damage + gravity 3 s; no fall damage
        m.put("sky_dive", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 260), maxDist = n.d("dist", 12), r = n.d("radius", 3);
            if (BroomLink.riding(pl)) { n.warn("빗자루 위에서는 쓸 수 없어요"); n.refund(mana); return false; }
            Location from = pl.getLocation();
            Vector h = n.aim().toVector().subtract(from.toVector()).setY(0);
            if (h.length() > maxDist) h.normalize().multiply(maxDist);
            Location raw = from.clone().add(h);
            Location land = Nova.stand(Nova.ground(raw.clone().add(0, 3, 0)));
            if (land == null || !n.plugin.protection().at(land) || !n.plugin.protection().at(from)) {
                n.warn("내려앉을 자리가 없어요"); n.refund(mana); return false;
            }
            land.setX(raw.getX()); land.setZ(raw.getZ());
            var ceil = pl.getWorld().rayTraceBlocks(pl.getEyeLocation(), new Vector(0, 1, 0), 7.3, FluidCollisionMode.NEVER, true);
            double rise = ceil == null ? 7 : Math.max(0, ceil.getHitPosition().getY() - pl.getEyeLocation().getY() - 0.4);
            double topY = from.getY() + rise;
            NO_FALL.put(pl.getUniqueId(), clock + 120);
            n.fx("mark", land);
            int[] st = {rise < 1.5 ? 1 : 0, 0};       // state (0 rise, 1 hang, 2 dive), ticks in state
            boolean[] landed = {false};
            Location[] last = {from.clone()};
            Runnable touch = () -> {
                if (landed[0] || !pl.isOnline()) return;
                landed[0] = true;
                pl.setVelocity(new Vector(0, -0.1, 0));
                pl.setFallDistance(0);
                NO_FALL.put(pl.getUniqueId(), clock + 40);
                Location at = Nova.ground(pl.getLocation().add(0, 0.5, 0));
                n.fx("land", at);
                for (LivingEntity e : n.near(at.clone().add(0, 0.6, 0), r)) if (n.hit(e, n.dmg())) n.status(e, "gravity:60");
            };
            n.loop(80, age -> {
                if (pl.isDead()) return false;
                pl.setFallDistance(0);
                Location now = pl.getLocation();
                int s = st[0], k = st[1]++;
                if (s == 0) {
                    double dy = topY - now.getY();
                    if (dy < 0.4 || k >= 14) { st[0] = 1; st[1] = 0; pl.setVelocity(new Vector(0, 0.04, 0)); }
                    else pl.setVelocity(new Vector(0, Math.clamp(dy * 0.45, 0.3, 1.4), 0));
                } else if (s == 1) {
                    pl.setVelocity(new Vector(0, 0.04, 0));
                    if (k >= 1) { st[0] = 2; st[1] = 0; n.fx("dive", now, pl); }
                } else {
                    Vector to = land.toVector().add(new Vector(0, 0.1, 0)).subtract(now.toVector());
                    double dist = to.length();
                    boolean stuck = k > 3 && now.distanceSquared(last[0]) < 0.02;
                    if (dist < 1.0 || (k > 2 && pl.isOnGround()) || stuck || k > 30) { touch.run(); return false; }
                    pl.setVelocity(to.multiply(Math.min(1.7, dist) / dist));
                }
                last[0] = now;
                return true;
            }, touch);
            return true;
        });

        // 363 뇌운 (Thundercloud): cloud follows the target 6 s, bolts at 1.5/3.0/4.5/6.0 s on the ground under it (r 1.5)
        m.put("thundercloud", n -> {
            LivingEntity t = n.target();
            if (t == null) return false;
            n.fx("cloud", t.getLocation(), t);
            for (int k = 1; k <= 4; k++) {
                n.later(30 * k - 1, () -> {
                    if (!t.isValid() || t.isDead() || t.getWorld() != n.pl.getWorld()) return;
                    Location g = Nova.ground(t.getLocation().add(0, 0.5, 0));
                    n.fx("strike", g);
                    for (LivingEntity e : n.near(g.clone().add(0, 1, 0), n.d("radius", 1.5))) n.hit(e, n.dmg());
                });
            }
            return true;
        });

        // 364 운명의 실 (FateLink): target + nearest other enemy within 8 (else one on the aim ray); 6 s; > 8 blocks apart = snap (damage + stun 1 s both)
        m.put("fate_link", n -> {
            LivingEntity a = n.target();
            if (a == null) return false;
            double snapAt = n.d("snap", 8), mana = n.d("mana", 240);
            LivingEntity b0 = null;
            for (LivingEntity e : n.near(a.getLocation(), snapAt, 8)) if (e != a) { b0 = e; break; }
            if (b0 == null) {
                var ray = n.pl.getWorld().rayTraceEntities(n.pl.getEyeLocation(), n.look(), n.d("range", 16), 0.6, e -> e != a && n.enemy(e));
                if (ray != null && ray.getHitEntity() instanceof LivingEntity le) b0 = le;
            }
            if (b0 == null) { n.warn("함께 묶을 다른 적이 근처에 없어요"); n.refund(mana); return false; }
            LivingEntity b = b0;
            n.fx("mark", a.getLocation(), a);
            n.fx("mark", b.getLocation(), b);
            n.fx("form", Nova.chest(a), b);
            int[] lv = {0, 0};                 // tension level, tick of the last bind
            Location[] anchor = {Nova.chest(a)};
            boolean[] over = {false};
            n.loop(120, age -> {
                if (!a.isValid() || a.isDead() || !b.isValid() || b.isDead() || a.getWorld() != b.getWorld()) { over[0] = true; n.fx("fade", anchor[0], b.isValid() ? b : null); return false; }
                Location ca = Nova.chest(a), cb = Nova.chest(b);
                double d = ca.distance(cb);
                if (d > snapAt) {
                    over[0] = true;
                    n.fx("snap", ca, b);
                    n.fx("snapMid", ca.clone().add(cb).multiply(0.5));
                    n.fx("snapHit", a.getLocation(), a);
                    n.fx("snapHit", b.getLocation(), b);
                    n.later(1, () -> { for (LivingEntity e : List.of(a, b)) if (n.hit(e, n.dmg())) n.status(e, "stun:20"); });
                    return false;
                }
                if (age >= 119) { over[0] = true; n.fx("fade", ca, b); return false; }
                if (age < 9) return true;
                int old = lv[0];
                int want = old >= 2 ? (d < 7.0 ? (d < 5.8 ? 0 : 1) : 2) : old == 1 ? (d >= 7.2 ? 2 : d < 5.8 ? 0 : 1) : (d >= 7.2 ? 2 : d >= 6 ? 1 : 0);
                boolean moved = age - lv[1] >= 10 && anchor[0].distanceSquared(ca) > 1.0;
                if (age == 9 || want < old || moved) {
                    anchor[0] = ca; lv[1] = age;
                    n.fx("bind", ca, b);
                    if (want >= 1) n.fx("taut", ca, b);
                    if (want >= 2) n.fx("strain", ca, b);
                } else if (want > old) {
                    if (old < 1) n.fx("taut", anchor[0], b);
                    if (want >= 2) n.fx("strain", anchor[0], b);
                }
                lv[0] = want;
                return true;
            }, () -> { if (!over[0]) n.fx("fade", anchor[0], b.isValid() ? b : null); });
            return true;
        });

        // 365 빛의 감옥 (PrismCage): cage at the target's feet; at 1.5 s the target still inside (r 2.6) takes damage + stun 1 s
        m.put("prism_cage", n -> {
            LivingEntity t = n.target();
            if (t == null) return false;
            Location c = t.getLocation();
            double r = n.d("radius", 2.6);
            n.fx("cage", c, t);
            n.later(29, () -> {
                if (!t.isValid() || t.isDead() || t.getWorld() != c.getWorld()) return;
                Location l = t.getLocation();
                double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), dy = l.getY() - c.getY();
                if (dx * dx + dz * dz > r * r || Math.abs(dy) > 2.5) return;
                if (n.hit(t, n.dmg())) { n.status(t, "stun:20"); n.fx("stun", l, t); }
            });
            return true;
        });

        // 366 망령의 행진 (WraithMarch): lane x ±1, z 0.6..15 along the caster's facing; leader front z = 1 + 7.5 (t - 0.42),
        // column covers 0.8 s behind it; every enemy once: damage + fear 1 s
        m.put("wraith_march", n -> {
            Location o = n.pl.getLocation();
            Vector f = n.flat(), side = new Vector(f.getZ(), 0, -f.getX());
            Location mid = o.clone().add(f.clone().multiply(8)).add(0, 1, 0);
            double len = n.d("length", 15), half = n.d("width", 2) / 2;
            Set<UUID> done = new HashSet<>();
            n.loop(70, age -> {
                if (age < 8 || age % 2 != 0) return true;
                double sec = age / 20.0, front = Math.min(len, 1.0 + 7.5 * (sec - 0.42)), tail = 1.0 + 7.5 * (sec - 0.42) - 6.0;
                if (tail > len) return false;
                for (LivingEntity e : n.near(mid, len / 2 + 2.5)) {
                    if (done.contains(e.getUniqueId())) continue;
                    Vector v = e.getLocation().toVector().subtract(o.toVector());
                    double z = v.dot(f), x = Math.abs(v.dot(side)), dy = v.getY();
                    if (z < 0.6 || z > len + 0.2 || z > front || z < tail || x > half + e.getWidth() / 2 || dy < -2.5 || dy > 2.5) continue;
                    done.add(e.getUniqueId());
                    if (n.hit(e, n.dmg())) { n.status(e, "fear:20"); n.fx("wraith_march_fear", e.getLocation()); }
                }
                return true;
            }, null);
            return true;
        });

        // 367 단층 붕괴 (FaultRift): corridor x ±1.25, z 0.8..16.2 along the caster's facing; collapse at 1.4 s:
        // enemies standing on it (feet near the ground) take damage + movement lock 3 s
        m.put("fault_rift", n -> {
            Location o = n.pl.getLocation();
            Vector f = n.flat(), side = new Vector(f.getZ(), 0, -f.getX());
            double half = n.d("width", 2.5) / 2, z0 = 0.8, z1 = n.d("length", 16.2);
            n.later(27, () -> {
                Location mid = o.clone().add(f.clone().multiply((z0 + z1) / 2)).add(0, 1, 0);
                for (LivingEntity e : n.near(mid, (z1 - z0) / 2 + 3)) {
                    Location l = e.getLocation();
                    Vector v = l.toVector().subtract(o.toVector());
                    double z = v.dot(f), x = Math.abs(v.dot(side)), dy = v.getY();
                    if (z < z0 || z > z1 || x > half + e.getWidth() / 2 || dy < -3 || dy > 3) continue;
                    if (l.getY() - Telegraphs.ground(l.clone().add(0, 0.4, 0)) > 0.7) continue;   // in the air: not on the crack
                    if (n.hit(e, n.dmg())) { n.status(e, "movement_lock:60"); n.fx("fault_rift_pin", l); }
                }
            });
            return true;
        });
    }

    /** 360: the bolt hit `t` at `p` — mark its feet, 3 s later sweep it back there. */
    private static void ebbMark(Nova n, LivingEntity t, Location p) {
        if (!n.hit(t, 0)) { n.fx("markHit", p); return; }
        n.fx("markHit", p, t);
        Location mark = Nova.ground(t.getLocation().add(0, 0.5, 0));
        mark.setYaw(0); mark.setPitch(0);
        Location tetherEnd = mark.clone().add(0, 1, 0);
        n.fx("anchor", mark);
        n.fx("brand", t.getLocation(), t);
        n.fx("tether", tetherEnd, t);
        n.later(59, () -> {
            if (!t.isValid() || t.isDead() || t.getWorld() != mark.getWorld()) return;
            n.fx("tide", tetherEnd, t);
            boolean[] done = {false};
            Runnable arrive = () -> {
                if (done[0]) return;
                done[0] = true;
                if (!t.isValid() || t.isDead()) return;
                Vector dir = mark.toVector().subtract(t.getLocation().toVector());
                n.fx("ebb", mark);
                n.hit(t, n.dmg());
                t.setVelocity(dir.lengthSquared() > 0.01 ? dir.multiply(0.15) : new Vector());
            };
            double far = t.getLocation().distance(mark);
            if (n.boss(t) || far < 0.8 || far > 30 || !n.plugin.protection().at(mark)) { arrive.run(); return; }
            n.loop(7, age -> {
                if (!t.isValid() || t.isDead()) return false;
                Vector to = mark.toVector().add(new Vector(0, 0.1, 0)).subtract(t.getLocation().toVector());
                double len = to.length();
                if (len < 0.7 || age >= 6) return false;
                Vector v = to.multiply(1.0 / Math.max(1, 7 - age));
                if (v.length() > 2.5) v.normalize().multiply(2.5);
                t.setVelocity(v.add(new Vector(0, 0.08, 0)));
                t.setFallDistance(0);
                return true;
            }, arrive);
        });
    }

    /** 358: caster eyes see the target's eyes or chest within `max` blocks. */
    private static boolean sees(Player pl, LivingEntity t, double max) {
        if (t.getWorld() != pl.getWorld()) return false;
        Location eye = pl.getEyeLocation();
        for (Location to : new Location[]{t.getEyeLocation(), Nova.chest(t)}) {
            Vector d = to.toVector().subtract(eye.toVector());
            double len = d.length();
            if (len > max) return false;
            if (len < 0.5) return true;
            if (pl.getWorld().rayTraceBlocks(eye, d.normalize(), len, FluidCollisionMode.NEVER, true) == null) return true;
        }
        return false;
    }

    static void enable(MagicMythicPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new Hooks(), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { clock++; if ((clock & 255) == 0) NO_FALL.values().removeIf(t -> t < clock); }, 1, 1);
    }

    static final class Hooks implements Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void fall(EntityDamageEvent e) {
            if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player)) return;
            Integer until = NO_FALL.remove(e.getEntity().getUniqueId());
            if (until != null && until >= clock) e.setCancelled(true);
        }
        @EventHandler public void quit(PlayerQuitEvent e) { NO_FALL.remove(e.getPlayer().getUniqueId()); }
    }
}
