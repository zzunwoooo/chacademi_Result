package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.StatusProtocol.Status;

/** chanova types 346~355 (중급 2). Rows: tools/nova/mid2.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md. */
final class NovaMid2 {
    private NovaMid2() {}

    static final String EYE_TAG = "chacademia_storm_eye", DOLL_TAG = "chacademia_hex_doll";
    private static MagicMythicPlugin plugin;

    // ------------------------------------------------------------------ small helpers
    /** True right of a horizontal facing (x east, z south). */
    private static Vector right(Vector f) { return new Vector(-f.getZ(), 0, f.getX()); }
    /** Caster frame point: feet + right·r + up·u + facing·f. */
    private static Location local(Player pl, double r, double u, double f) {
        Vector fw = Nova.flat(pl.getLocation().getDirection());
        return pl.getLocation().add(right(fw).multiply(r)).add(0, u, 0).add(fw.multiply(f));
    }
    /** Refused cast: message, mana back, cooldown cleared. */
    private static boolean refuse(Nova n, String msg) {
        n.warn(msg); n.refund(n.d("mana", 0)); n.clearCooldown(); return false;
    }
    private static boolean apply(Nova n, LivingEntity t, Status s, int ticks, double power) {
        var st = n.plugin.status();
        return st != null && t != null && t.isValid() && !t.isDead() && st.apply(n.pl, t, s, ticks, power);
    }
    private static boolean onBroom(Nova n) { return BroomLink.riding(n.pl); }
    /** Keeps the player's current look when moving them. */
    private static boolean move(Player pl, Location to) {
        Location at = to.clone(); at.setYaw(pl.getLocation().getYaw()); at.setPitch(pl.getLocation().getPitch());
        boolean ok = pl.teleport(at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (ok) pl.setFallDistance(0);
        return ok;
    }
    /** Walkable ground path along a horizontal dir (0.25 steps, 1-block step up, ≤3 down), stops at walls / protection. */
    private static List<Location> path(MagicMythicPlugin plugin, Location start, Vector dir, double dist) {
        List<Location> pts = new ArrayList<>();
        Location cur = start.clone();
        for (double d = 0.25; d <= dist + 1e-6; d += 0.25) {
            Location probe = start.clone().add(dir.clone().multiply(d)); probe.setY(cur.getY() + 1.0);
            if (!probe.isChunkLoaded()) break;
            Location s = Nova.stand(probe);
            if (s == null || s.getY() - cur.getY() > 1.01 || cur.getY() - s.getY() > 3.01 || !plugin.protection().at(s)) break;
            cur = s; pts.add(s.clone());
        }
        return pts;
    }

    static void register(Map<String, NovaMech.Impl> m) {
        // 346 숨 막힘 (AirChoke): thread reaches the throat at 0.2 s → disarm 3 s; sustain from 0.25 s; release when the disarm ends
        m.put("air_choke", n -> {
            LivingEntity t = n.target();
            if (t == null || !n.enemy(t)) return refuse(n, "대상을 찾지 못했어요");
            int dis = n.i("ticks", 60); double keep = n.d("keep", 20);
            n.fx("air_choke_cast", t.getLocation(), t);
            n.fx("air_choke_form", t.getLocation(), t);
            boolean[] on = {false}, ended = {false};
            Runnable release = () -> {
                if (ended[0]) return; ended[0] = true;
                if (t.isValid() && !t.isDead()) n.fx("air_choke_release", t.getLocation(), t);
            };
            n.loop(dis + 8, age -> {
                if (!t.isValid() || t.isDead()) { ended[0] = true; return false; }
                if (t.getWorld() != n.pl.getWorld() || t.getLocation().distanceSquared(n.pl.getLocation()) > keep * keep) {
                    if (on[0]) n.clear(t, Status.DISARM);   // 거리를 벗어나면 숨 막힘이 풀림
                    release.run(); return false;
                }
                if (age == 4) { on[0] = apply(n, t, Status.DISARM, dis, 0); if (!on[0]) { release.run(); return false; } }
                if (age == 5) n.fx("air_choke", t.getLocation(), t);
                if (age > 6 && !n.has(t, Status.DISARM)) { release.run(); return false; }  // 해제(정화) / 끝
                if (age >= 4 + dis) { release.run(); return false; }
                return true;
            }, release);
            return true;
        });

        // 347 폭풍의 눈 (StormEye): 4 s barrier; hostile vanilla projectiles entering it orbit the caster, flung outward at end + 0.1 s
        m.put("storm_eye", n -> {
            Player pl = n.pl;
            double r = n.d("radius", 1.6), catchR = n.d("catch", 2.4), scan = catchR + 4;
            int hold = n.i("ticks", 80), max = n.i("max", 16);
            List<Caught> held = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            boolean[] done = {false}, endSent = {false};
            n.later(8, () -> { if (!pl.isDead()) n.fx("storm_eye", pl.getLocation(), pl); });
            n.loop(hold + 4, age -> {
                if (pl.isDead() || !pl.isValid()) return false;
                Location c = pl.getLocation().add(0, 1.0, 0);
                if (age < hold) {
                    if (held.size() < max) {
                        for (Entity e : pl.getWorld().getNearbyEntities(c, scan, scan, scan)) {
                            if (!(e instanceof Projectile pr) || !pr.isValid() || seen.contains(pr.getUniqueId()) || !catchable(n, pr)) continue;
                            Vector v = pr.getVelocity(), rel = pr.getLocation().toVector().subtract(c.toVector());
                            double dist = rel.length(), vv = v.lengthSquared();
                            if (vv < 0.01) continue;
                            // inside the barrier, or crossing it during its next move (fast arrows move up to ~3 blocks / tick)
                            double u = Math.clamp(-rel.dot(v) / vv, 0, 1);
                            if (dist > catchR && rel.clone().add(v.clone().multiply(u)).length() > catchR) continue;
                            seen.add(pr.getUniqueId()); HELD.add(pr.getUniqueId());
                            held.add(new Caught(pr, Math.atan2(rel.getZ(), rel.getX()), Math.clamp(rel.getY(), -0.6, 0.8), pr.hasGravity(), v.length()));
                            pr.setGravity(false); pr.setVelocity(new Vector()); pr.addScoreboardTag(EYE_TAG);
                            Vector out = dist < 1e-3 ? Nova.flat(v.clone().multiply(-1)) : rel.clone().normalize();
                            n.fx("storm_eye_catch", c.clone().add(out.multiply(r)), null);
                            if (held.size() >= max) break;
                        }
                    }
                    for (Iterator<Caught> it = held.iterator(); it.hasNext(); ) {
                        Caught k = it.next();
                        if (!k.pr.isValid()) { HELD.remove(k.pr.getUniqueId()); it.remove(); continue; }
                        k.ang += 0.32;
                        Location at = c.clone().add(Math.cos(k.ang) * r, k.h, Math.sin(k.ang) * r);
                        k.pr.setVelocity(new Vector());
                        k.pr.teleport(at);
                    }
                    return true;
                }
                if (age == hold) { endSent[0] = true; n.fx("storm_eye_end", pl.getLocation(), pl); }
                if (age >= hold + 2) { fling(pl, c, r, held); done[0] = true; return false; }
                return true;
            }, () -> {
                if (!done[0]) drop(held);                        // caster left / died / plugin stopped: just let them fall
                if (!endSent[0] && pl.isOnline() && !pl.isDead()) n.fx("storm_eye_end", pl.getLocation(), pl);
            });
            return true;
        });

        // 348 그림자 늪 (ShadowMire): r 3 swamp 4 s at the aimed ground; enemies inside: movement_lock 3 s (once each) + slow while inside
        m.put("shadow_mire", n -> {
            Location c = Nova.ground(n.aim());
            if (!n.plugin.protection().pair(n.pl, c)) return refuse(n, "여기에는 늪을 만들 수 없어요");
            double r = n.d("radius", 3); int life = n.i("ticks", 80), lock = n.i("lock", 60);
            Set<UUID> locked = new HashSet<>();
            n.fx("shadow_mire_begin", c);
            n.later(11, () -> n.fx("shadow_mire", c));
            n.loop(life, age -> {
                if (age < 6 || age % 5 != 0) return true;              // pool spreads to r 3 by ~0.35 s
                for (LivingEntity e : n.near(c, r + 1.5)) {
                    Location f = e.getLocation();
                    double dx = f.getX() - c.getX(), dz = f.getZ() - c.getZ();
                    if (dx * dx + dz * dz > r * r || Math.abs(f.getY() - c.getY()) > 1.5) continue;
                    apply(n, e, Status.SLOW, 30, n.d("slow", 0.3));
                    if (locked.add(e.getUniqueId()) && apply(n, e, Status.MOVEMENT_LOCK, lock, 0)) n.fx("shadow_mire_root", f, e);
                }
                return true;
            }, () -> n.fx("shadow_mire_end", c));
            return true;
        });

        // 349 악몽 (DreadDream): asleep / feared target → maw bites at 0.62 s (big damage, sleep + fear removed); otherwise fear 1 s at 0.36 s
        m.put("dread_dream", n -> {
            LivingEntity t = n.target();
            if (t == null || !n.enemy(t)) return refuse(n, "대상을 찾지 못했어요");
            boolean big = n.has(t, Status.SLEEP) || n.has(t, Status.FEAR);
            n.fx("dread_dream_thread", Nova.chest(t), t);
            n.later(3, () -> {
                if (!t.isValid() || t.isDead()) return;
                n.fx(big ? "dread_dream_nightmare" : "dread_dream_fear", t.getLocation(), t);
            });
            if (big) n.later(12, () -> { n.clear(t, Status.SLEEP, Status.FEAR); n.hit(t, n.dmg()); });
            else n.later(7, () -> apply(n, t, Status.FEAR, n.i("fear", 20), 0));
            return true;
        });

        // 350 그림자 걸음 (ShadowStep): 5 blocks forward in 0.25 s through enemies (not walls), blind 3 s to each enemy passed
        m.put("shadow_step", n -> {
            Player pl = n.pl;
            if (onBroom(n)) return refuse(n, "빗자루 위에서는 쓸 수 없어요");
            Location start = pl.getLocation(); Vector dir = n.flat();
            List<Location> pts = path(n.plugin, start, dir, n.d("dist", 5));
            if (pts.size() < 4) return refuse(n, "앞쪽이 막혀 있어요");
            int dash = n.i("dash", 5), per = (int) Math.ceil(pts.size() / (double) dash);
            Set<UUID> blinded = new HashSet<>();
            n.fx("shadow_step_depart", start);
            n.later(1, () -> {
                n.fx("shadow_step", pl.getLocation(), pl);
                pl.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, dash + 2, 0, false, false, false));
            });
            int[] idx = {0};
            boolean[] arrived = {false};
            Runnable arrive = () -> {
                if (arrived[0]) return; arrived[0] = true;
                pl.setVelocity(new Vector());
                n.fx("shadow_step_arrive", pl.getLocation());
            };
            n.loop(dash + 3, age -> {
                if (age < 1) return true;
                int from = idx[0], to = Math.min(pts.size(), from + per);
                for (int k = from; k < to; k++) {
                    Location p = pts.get(k).clone().add(0, 0.9, 0);
                    for (LivingEntity e : n.near(p, 1.4)) {
                        if (Targets.closestDistance(e, p) > 0.8 || !blinded.add(e.getUniqueId())) continue;
                        if (apply(n, e, Status.BLINDNESS, n.i("blind", 60), 0)) n.fx("shadow_step_blind", e.getLocation(), e);
                    }
                }
                idx[0] = to;
                if (!move(pl, pts.get(to - 1))) { arrive.run(); return false; }
                if (to >= pts.size()) { arrive.run(); return false; }
                return true;
            }, arrive);
            return true;
        });

        // 351 광륜 투척 (HaloThrow): release at 0.45 s; first enemy: damage + slow 3 s, the ring bounces once to the nearest other enemy
        m.put("halo_throw", n -> {
            double speed = n.d("speed", 1.2), range = n.d("range", 20), bounce = n.d("bounce", 8);
            n.later(9, () -> {
                Location from = local(n.pl, 0.36, 1.6, 0.8);
                Vector dir = n.aim().toVector().subtract(from.toVector());
                if (dir.lengthSquared() < 1 || dir.angle(n.look()) > 0.6) dir = n.look();
                boolean[] hit = {false};
                n.shotFrom(from, dir, speed, range, "halo_throw").radius(0.7)
                    .hit((t, p) -> {
                        hit[0] = true;
                        if (n.hit(t, n.dmg())) apply(n, t, Status.SLOW, n.i("slow", 60), 0.3);
                        n.fx("halo_throw_hit", p);
                        n.fx("halo_throw_slow", t.getLocation(), t);
                        LivingEntity next = null;
                        for (LivingEntity e : n.near(p, bounce, 8)) if (e != t) { next = e; break; }
                        if (next != null) {
                            LivingEntity nx = next;
                            Vector to = Nova.chest(nx).toVector().subtract(p.toVector());
                            boolean[] hit2 = {false};
                            n.shotFrom(p, to, speed, to.length() + 3, "halo_throw").radius(0.7).homing(nx, 0.35)
                                .hit((t2, p2) -> {
                                    if (t2 == t) return true;                 // passes the first target
                                    hit2[0] = true;
                                    n.hit(t2, n.dmg(n.d("bounce_mult", 0.8)));
                                    n.fx("halo_throw_end", p2);
                                    return false;
                                })
                                .end((p2, wall) -> { if (!hit2[0]) n.fx("halo_throw_end", p2); })
                                .fire();
                        }
                        return false;
                    })
                    .end((p, wall) -> { if (!hit[0]) n.fx("halo_throw_end", p); })
                    .fire();
            });
            return true;
        });

        // 352 섬광 도약 (FlashBlink): blink 6 blocks along the look (safe stand point), blind 3 s in r 3 at the departure. Not on a broom.
        m.put("flash_blink", n -> {
            Player pl = n.pl;
            if (onBroom(n)) return refuse(n, "빗자루 위에서는 쓸 수 없어요");
            Location from = pl.getLocation(), eye = pl.getEyeLocation(); Vector look = n.look();
            double dist = Math.min(n.d("dist", 6), Nova.reach(eye, look, n.d("dist", 6)).distance(eye));
            for (double d = 1; d <= dist; d += 1) if (!n.plugin.protection().at(eye.clone().add(look.clone().multiply(d)))) { dist = d - 1; break; }
            Location to = null;
            for (double d = dist; d >= 1.5; d -= 0.5) {
                Location feet = eye.clone().add(look.clone().multiply(d)).subtract(0, pl.getEyeHeight(), 0);
                if (!feet.isChunkLoaded()) continue;
                Location s = Nova.stand(feet);
                if (s == null || !n.plugin.protection().at(s)) continue;
                s.setX(feet.getX()); s.setZ(feet.getZ());
                // the body must fit there: no wall between the eye line and the new head
                Location head = s.clone().add(0, pl.getEyeHeight(), 0);
                Vector seg = head.toVector().subtract(eye.toVector());
                if (seg.lengthSquared() > 1e-4 && pl.getWorld().rayTraceBlocks(eye, seg, seg.length(), org.bukkit.FluidCollisionMode.NEVER, true) != null) continue;
                to = s; break;
            }
            if (to == null) return refuse(n, "앞쪽에 설 자리가 없어요");
            if (!move(pl, to)) return refuse(n, "지금은 순간이동할 수 없어요");
            n.fx("depart", from);
            for (LivingEntity e : n.near(from.clone().add(0, 0.9, 0), n.d("radius", 3)))
                if (apply(n, e, Status.BLINDNESS, n.i("blind", 60), 0)) n.fx("blind", e.getLocation(), e);
            n.fx("streak", from.clone().add(0, 1.0, 0));
            n.fx("arrive", pl.getLocation(), pl);
            return true;
        });

        // 353 바위 돌진 (BoulderCharge): armor 0.45 s, then a 6-block charge (~0.9 b/tick); first enemy: damage + stun 1 s + knock, charge stops
        m.put("boulder_charge", n -> {
            Player pl = n.pl;
            if (onBroom(n)) return refuse(n, "빗자루 위에서는 쓸 수 없어요");
            int wind = n.i("windup", 9); double dist = n.d("dist", 6), speed = n.d("speed", 0.9);
            boolean[] ended = {false};
            n.later(wind, () -> {
                if (pl.isDead()) return;
                Location start = pl.getLocation(); Vector dir = n.flat();
                n.fx("boulder_charge_dash", start, pl);
                Location[] last = {start.clone()};
                Runnable end = () -> {
                    if (ended[0]) return; ended[0] = true;
                    if (pl.isOnline()) { pl.setVelocity(dir.clone().multiply(0.15)); n.fx("boulder_charge_end", pl.getLocation(), pl); }
                };
                n.loop(14, age -> {
                    if (pl.isDead()) return false;
                    Location at = pl.getLocation(), body = at.clone().add(0, 0.9, 0);
                    pl.setFallDistance(0);
                    for (LivingEntity e : n.near(body, 2.2)) {
                        if (Targets.closestDistance(e, body.clone().add(dir.clone().multiply(0.4))) > 0.9) continue;
                        n.hit(e, n.dmg());
                        if (apply(n, e, Status.STUN, n.i("stun", 20), 0)) n.fx("boulder_charge_stun", e.getLocation(), e);
                        n.knock(e, at, dir, n.d("knock", 1.4), 0.35);
                        n.fx("boulder_charge_hit", Nova.ground(at.clone().add(dir.clone().multiply(0.55))));
                        return false;
                    }
                    double went = Math.hypot(at.getX() - start.getX(), at.getZ() - start.getZ());
                    if (went >= dist - 0.2) return false;
                    if (!n.plugin.protection().at(at.clone().add(dir.clone().multiply(1.2)))) return false;
                    double moved = Math.hypot(at.getX() - last[0].getX(), at.getZ() - last[0].getZ());
                    if (age > 2 && moved < 0.15) return false;        // ran into a wall
                    last[0] = at.clone();
                    double step = Math.min(speed, dist - went);
                    pl.setVelocity(dir.clone().multiply(step).setY(Math.clamp(pl.getVelocity().getY(), -0.6, 0.1)));
                    return true;
                }, end);
            });
            return true;
        });

        // 354 공기 포탄 (AirCannon): 3 shells at 0.40 / 0.65 / 0.90 s; each hit: damage + small push; all 3 on one target → gravity 3 s
        m.put("air_cannon", n -> {
            double speed = n.d("speed", 1.5), range = n.d("range", 20);
            Map<UUID, Integer> hits = new HashMap<>();
            int[] at = {8, 13, 18};
            for (int k = 0; k < 3; k++) {
                n.later(at[k], () -> {
                    Location from = local(n.pl, 0.3, 1.38, 0.95);
                    n.shotFrom(from, n.look(), speed, range, "projectile").radius(0.6)
                        .hit((t, p) -> {
                            if (n.hit(t, n.dmg())) {
                                n.knock(t, n.pl.getLocation(), n.look(), n.d("knock", 0.35), 0.1);
                                if (hits.merge(t.getUniqueId(), 1, Integer::sum) == 3 && apply(n, t, Status.GRAVITY, n.i("gravity", 60), 0))
                                    n.fx("heavy", t.getLocation());
                            }
                            n.fx("impact", p);
                            return false;
                        })
                        .end((p, wall) -> { if (wall) n.fx("impact", p); })
                        .fire();
                });
            }
            return true;
        });

        // 355 저주 인형 (HexDoll): doll 4 s next to the target; caster/ally hits on it → 50 % to the target as spell damage (+0.2 s)
        m.put("hex_doll", n -> {
            LivingEntity t = n.target();
            if (t == null || !n.enemy(t)) return refuse(n, "대상을 찾지 못했어요");
            Location feet = dollSpot(n, t);
            if (feet == null) return refuse(n, "인형을 세울 자리가 없어요");
            int life = n.i("ticks", 80);
            Doll d = new Doll(n, t, feet);
            n.fx("dollIn", feet);
            n.fx("form", d.chest(), t);
            n.later(10, () -> { if (!d.ending) { n.fx("doll", feet); n.fx("bind", d.chest(), t); } });
            n.loop(life, age -> {
                if (age == 6) d.spawn();
                if (!t.isValid() || t.isDead() || t.getWorld() != feet.getWorld()) return false;
                if (age > 6 && (d.stand == null || !d.stand.isValid())) return false;
                return true;
            }, d::end);
            return true;
        });
    }

    // ================================================================== 347 storm eye state
    private static final class Caught {
        final Projectile pr; double ang; final double h, speed; final boolean grav;
        Caught(Projectile pr, double ang, double h, boolean grav, double speed) { this.pr = pr; this.ang = ang; this.h = h; this.grav = grav; this.speed = speed; }
    }
    /** Projectiles held in some barrier right now. */
    static final Set<UUID> HELD = new HashSet<>();
    /** Flung projectile → caster (and the original thrower of a trident, so it goes back to its owner after landing). */
    private record Flung(UUID caster, UUID original, long until) {}
    static final Map<UUID, Flung> FLUNG = new HashMap<>();

    private static boolean catchable(Nova n, Projectile pr) {
        if (pr instanceof EnderPearl || pr instanceof FishHook || pr instanceof Firework) return false;
        if (pr instanceof AbstractArrow a && (a.isInBlock() || a.isOnGround())) return false;
        if (HELD.contains(pr.getUniqueId()) || !n.plugin.protection().pair(n.pl, pr.getLocation())) return false;
        ProjectileSource src = pr.getShooter();
        if (!(src instanceof Entity shooter)) return true;                 // dispensers
        if (shooter.getUniqueId().equals(n.pl.getUniqueId())) return false;
        if (shooter instanceof Player) return n.enemy(shooter);            // allies' shots pass
        return !n.plugin.targets().excluded(shooter);
    }

    private static void fling(Player pl, Location c, double r, List<Caught> held) {
        long now = Bukkit.getCurrentTick();
        FLUNG.values().removeIf(f -> f.until < now);
        for (Caught k : held) {
            Projectile pr = k.pr;
            HELD.remove(pr.getUniqueId());
            if (!pr.isValid()) continue;
            pr.removeScoreboardTag(EYE_TAG);
            if (pr instanceof ThrownPotion) { pr.remove(); continue; }      // caught potions just burst in the wind
            Vector dir = new Vector(Math.cos(k.ang), 0.08, Math.sin(k.ang)).normalize();
            double speed = Math.clamp(k.speed, 1.2, 2.5);
            UUID orig = pr instanceof Trident && pr.getShooter() instanceof Entity o ? o.getUniqueId() : null;
            pr.teleport(c.clone().add(dir.clone().multiply(r + 0.4)));
            pr.setShooter(pl);
            if (pr instanceof AbstractArrow a && !(pr instanceof Trident)) a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            pr.setGravity(k.grav);
            if (pr instanceof Fireball fb) fb.setDirection(dir.clone());
            pr.setVelocity(dir.multiply(speed));
            FLUNG.put(pr.getUniqueId(), new Flung(pl.getUniqueId(), orig, now + 200));
        }
        held.clear();
    }

    private static void drop(List<Caught> held) {
        for (Caught k : held) {
            HELD.remove(k.pr.getUniqueId());
            if (!k.pr.isValid()) continue;
            k.pr.removeScoreboardTag(EYE_TAG);
            k.pr.setGravity(k.grav);
            k.pr.setVelocity(new Vector(0, -0.1, 0));
        }
        held.clear();
    }

    // ================================================================== 355 hex doll state
    static final Map<UUID, Doll> DOLLS = new HashMap<>();

    private static Location dollSpot(Nova n, LivingEntity t) {
        Location tl = t.getLocation();
        Vector away = Nova.flat(tl.toVector().subtract(n.pl.getLocation().toVector())), side = right(away);
        for (Vector off : List.of(side.clone().multiply(1.4), side.clone().multiply(-1.4), away.clone().multiply(-1.4))) {
            Location c = tl.clone().add(off).add(0, 1, 0);
            if (!c.isChunkLoaded()) continue;
            Location s = Nova.stand(c);
            if (s == null || Math.abs(s.getY() - tl.getY()) > 2 || !n.plugin.protection().at(s)) continue;
            s.setX(c.getX()); s.setZ(c.getZ());
            s.setYaw(n.pl.getLocation().getYaw()); s.setPitch(0);
            return s;
        }
        return null;
    }

    static final class Doll {
        final Nova n; final LivingEntity target; final Location feet;
        ArmorStand stand; boolean ending;
        final Map<UUID, Integer> lastHit = new HashMap<>();
        Doll(Nova n, LivingEntity target, Location feet) { this.n = n; this.target = target; this.feet = feet; }
        Location chest() { return feet.clone().add(0, 0.5, 0); }

        void spawn() {
            if (ending || feet.getWorld() == null) return;
            stand = feet.getWorld().spawn(feet, ArmorStand.class, a -> {
                a.setInvisible(true); a.setGravity(false); a.setBasePlate(false); a.setArms(false);
                a.setPersistent(false); a.setSilent(true); a.setCanPickupItems(false);
                a.addScoreboardTag(DOLL_TAG);
            });
            DOLLS.put(stand.getUniqueId(), this);
        }

        /** A hit on the doll by `who` for `amount` (the event is already cancelled). */
        void struck(Player who, double amount) {
            if (ending || !target.isValid() || target.isDead() || amount <= 0) return;
            Player caster = n.pl;
            if (!who.getUniqueId().equals(caster.getUniqueId()) && !n.plugin.targets().ally(caster, who)) return;
            int now = Bukkit.getCurrentTick();
            Integer last = lastHit.get(who.getUniqueId());
            if (last != null && now - last < 5) return;                  // sweep / multishot spam
            lastHit.put(who.getUniqueId(), now);
            double share = Math.min(amount * n.d("share", 0.5), n.d("cap", 12));
            n.fx("strike", chest(), target);
            n.later(4, () -> { if (!ending) n.hit(target, share); });
        }

        void end() {
            if (ending) return;
            ending = true;
            if (stand != null) { DOLLS.remove(stand.getUniqueId()); if (stand.isValid()) stand.remove(); }
            if (n.pl.isOnline()) {
                n.fx("dollOut", feet);
                if (target.isValid() && !target.isDead()) n.fx("fade", chest(), target);
            }
        }
    }

    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    static void removeDolls() {
        for (Doll d : new ArrayList<>(DOLLS.values())) d.end();
        DOLLS.clear();
        for (World w : Bukkit.getWorlds())
            for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class)) if (a.getScoreboardTags().contains(DOLL_TAG)) a.remove();
    }

    // ================================================================== listeners
    static final class L implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void dollHurt(EntityDamageEvent e) {
            Entity v = e.getEntity();
            Doll d = DOLLS.get(v.getUniqueId());
            if (d == null) { if (v instanceof ArmorStand && v.getScoreboardTags().contains(DOLL_TAG)) e.setCancelled(true); return; }
            double amount = e.getDamage();
            e.setCancelled(true);                                           // the doll never breaks / drops
            if (e instanceof EntityDamageByEntityEvent be) { Player a = attacker(be.getDamager()); if (a != null) d.struck(a, amount); }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void dollTouch(PlayerArmorStandManipulateEvent e) {
            if (e.getRightClicked().getScoreboardTags().contains(DOLL_TAG)) e.setCancelled(true);
        }

        /** Held / flung projectiles never hurt their barrier's caster or the caster's allies. */
        @EventHandler(priority = EventPriority.LOWEST)
        public void eyeShot(EntityDamageByEntityEvent e) {
            Entity dmg = e.getDamager();
            if (!(dmg instanceof Projectile)) return;
            if (HELD.contains(dmg.getUniqueId())) { e.setCancelled(true); return; }
            Flung f = FLUNG.get(dmg.getUniqueId());
            if (f == null) return;
            if (e.getEntity().getUniqueId().equals(f.caster())) { e.setCancelled(true); return; }
            Player caster = Bukkit.getPlayer(f.caster());
            if (caster != null && plugin != null && plugin.targets().ally(caster, e.getEntity())) e.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void eyeLand(ProjectileHitEvent e) {
            Flung f = FLUNG.get(e.getEntity().getUniqueId());
            if (f == null || f.original() == null || plugin == null) return;
            Projectile pr = e.getEntity();
            Bukkit.getScheduler().runTask(plugin, () -> {        // a caught trident goes back to its owner once it lands
                if (!pr.isValid()) return;
                Player o = Bukkit.getPlayer(f.original());
                if (o != null) pr.setShooter(o);
                FLUNG.remove(pr.getUniqueId());
            });
        }

        @EventHandler
        public void disable(PluginDisableEvent e) {
            if (e.getPlugin() != plugin) return;
            removeDolls();
            HELD.clear(); FLUNG.clear();
        }
    }

    private static MagicMythicPlugin listening;
    static void enable(MagicMythicPlugin p) {
        plugin = p;
        removeDolls();                                                       // leftovers of a crash / reload
        HELD.clear(); FLUNG.clear();
        if (listening != p) { p.getServer().getPluginManager().registerEvents(new L(), p); listening = p; }
    }
}
