package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * chanova types 336~345 (중급 1). Rows: tools/nova/mid.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md.
 * Visual events that the NOTES start "at the cast" but need a place / entity the catalog cast cannot know are sent one tick
 * later ({@link Nova#later} 0), so the catalog cast is surely running.
 */
final class NovaMid {
    private NovaMid() {}

    // 339: mob -> {caster, until tick} while it is lured by a heat haze decoy
    private static final Map<UUID, Object[]> LURED = new HashMap<>();
    // 342: ally -> token of the newest crystal coat (an older coat loop stops quietly)
    private static final Map<UUID, Object> COATS = new HashMap<>();
    // 345: caster -> {step, window end tick}; caster -> own cooldown end tick (after a lapsed window)
    private static final Map<UUID, int[]> COMBO = new HashMap<>();
    private static final Map<UUID, Long> COMBO_CD = new HashMap<>();

    private static long now() { return Bukkit.getCurrentTick(); }
    /** Right of the horizontal facing f (Minecraft: facing south +Z, right = west -X). */
    private static Vector right(Vector f) { return new Vector(-f.getZ(), 0, f.getX()); }
    private static double hdist(Location a, Location b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }
    /** Enemy inside a horizontal cone (half angle deg) of radius r in front of `from`. */
    private static boolean inCone(Location from, Vector f, LivingEntity t, double r, double halfDeg) {
        Vector v = t.getLocation().toVector().subtract(from.toVector()).setY(0);
        double d = v.length();
        if (d > r + t.getWidth() * 0.5 || Math.abs(t.getLocation().getY() - from.getY()) > 2.5) return false;
        if (d < 0.8) return true;
        return v.normalize().dot(f) >= Math.cos(Math.toRadians(halfDeg));
    }

    static void register(Map<String, NovaMech.Impl> m) {
        // 336 화염 원반 (FlameWheel): wheel forms 1 m ahead, launched at +0.30 s, rolls 12 blocks on the ground (0.75 b/t), pierces;
        // first block hit = one bounce (flame_wheel_bounce + a new wheel along the reflected direction for the remaining range)
        m.put("flame_wheel", n -> {
            double speed = n.d("speed", 0.75), range = n.d("range", 12);
            Vector f = n.flat();
            Location start = Nova.ground(n.pl.getLocation().add(f.clone().multiply(1.0)));
            n.later(6, () -> wheel(n, start, f, speed, range, true));
            return true;
        });

        // 337 분화선 (EruptionLine): 4 pillars at 2.5 / 4.6 / 6.7 / 8.8 blocks ahead, r 1.2, erupt at +0.45 / 0.70 / 0.95 / 1.20 s
        m.put("eruption_line", n -> {
            Vector f = n.flat();
            Location feet = n.pl.getLocation();
            double wall = Nova.reach(feet.clone().add(0, 0.5, 0), f, 10).distance(feet.clone().add(0, 0.5, 0));
            double r = n.d("r", 1.2), up = n.d("up", 0.5);
            double[] z = {2.5, 4.6, 6.7, 8.8}; int[] at = {9, 14, 19, 24};
            Set<UUID> hit = new HashSet<>();
            for (int k = 0; k < 4; k++) {
                if (z[k] > wall + 0.6) break; // the crack stops at a wall
                Location c = Nova.ground(feet.clone().add(f.clone().multiply(z[k])).add(0, 0.5, 0));
                n.later(at[k], () -> {
                    for (LivingEntity t : n.near(c.clone().add(0, 1, 0), r + 1.5)) {
                        if (hit.contains(t.getUniqueId()) || hdist(t.getLocation(), c) > r + t.getWidth() * 0.5
                            || t.getLocation().getY() < c.getY() - 1 || t.getLocation().getY() > c.getY() + 2.5) continue;
                        if (!n.hit(t, n.dmg())) continue;
                        hit.add(t.getUniqueId());
                        n.knock(t, c, f, 0, up);
                        n.fx("eruption_line_hit", t.getLocation());
                    }
                });
            }
            return true;
        });

        // 338 화염 투창 (FlameJavelin): the client charged in the aim preview (chacademia:spell_charge, 0..1; none = full).
        // Range 10 -> 30, speed 1.2 -> 2.0 b/t, damage x0.5 -> x1.5; explosion r 1.5 (r 2.5 + hit_full at full charge) after +0.16 / +0.24 s
        m.put("flame_javelin", n -> {
            double ch = ChargeInput.take(n.pl, "flame_javelin");
            if (ch < 0) ch = 1;
            final double charge = Math.clamp(ch, 0, 1);
            boolean full = charge >= 0.999;
            double range = n.d("min", 10) + (n.d("max", 30) - n.d("min", 10)) * charge;
            double speed = 1.2 + 0.8 * charge, mult = 0.5 + charge;
            double r = full ? n.d("rfull", 2.5) : n.d("r", 1.5);
            int delay = full ? 5 : 3;
            String hitFx = full ? "flame_javelin_hit_full" : "flame_javelin_hit";
            Vector f = n.flat();
            Location tip = n.pl.getLocation().add(right(f).multiply(0.42)).add(0, 1.68, 0).add(f.clone().multiply(0.83));
            if (!Nova.passable(tip)) tip = n.pl.getEyeLocation();
            n.shotFrom(tip, n.look(), speed, range, "flame_javelin").radius(0.45).gravity(n.d("gravity", 0.012))
                .hit((t, p) -> false)
                .end((p, wall) -> {
                    n.fx(hitFx, p);
                    n.later(delay, () -> {
                        for (LivingEntity t : n.near(p, r)) n.hit(t, n.dmg(mult));
                    });
                })
                .fire();
            return true;
        });

        // 339 아지랑이 (HeatHaze): slide 2 blocks sideways (strafe key side, else right; not on a broom), a decoy for 3 s at the old
        // feet that monsters chase instead of the caster, then it pops (r 2.5, damage + 화상 3초)
        m.put("heat_haze", n -> {
            Player pl = n.pl;
            Location decoy = pl.getLocation();
            Vector f = n.flat(), side = right(f);
            try { var in = pl.getCurrentInput(); if (in.isLeft() && !in.isRight()) side.multiply(-1); } catch (LinkageError | RuntimeException old) {}
            if (!BroomLink.riding(pl)) {
                double room = Nova.reach(decoy.clone().add(0, 0.5, 0), side, 2.2).distance(decoy.clone().add(0, 0.5, 0));
                if (room > 0.6) {
                    boolean ground = pl.isOnGround();
                    double v = ground ? 0.9 : 0.2;
                    pl.setVelocity(side.clone().multiply(v * Math.min(1, room / 2)).setY(ground ? 0.05 : Math.max(-0.1, pl.getVelocity().getY())));
                }
            }
            int ticks = n.i("ticks", 60); double lure = n.d("lure", 10), r = n.d("r", 2.5);
            UUID me = pl.getUniqueId();
            long until = now() + ticks;
            n.loop(ticks, age -> {
                if (age % 10 != 0) return true;
                for (LivingEntity e : n.near(decoy, lure)) {
                    if (!(e instanceof Mob mob) || e instanceof Player || n.boss(e)) continue;
                    LivingEntity cur = mob.getTarget();
                    if (cur != null && !cur.getUniqueId().equals(me)) continue;
                    LURED.put(mob.getUniqueId(), new Object[]{me, until});
                    mob.setTarget(null);
                    mob.getPathfinder().moveTo(decoy, 1.0);
                }
                return true;
            }, () -> {
                LURED.values().removeIf(v -> v[0].equals(me) && (long) v[1] <= now() + 1);
                if (!pl.isOnline()) return;
                n.fx("heat_haze_pop", decoy);
                for (LivingEntity t : n.near(decoy.clone().add(0, 0.9, 0), r)) if (n.hit(t, n.dmg())) n.status(t, "burn:60");
            });
            return true;
        });

        // 340 서리 길 (FrostPath): road x ±1, z 0.4~8.4 from the cast feet for 5 s (on from +0.5 s). Enemies on it: 둔화 3초 (again
        // when it runs out); the caster on it: +30 % speed (frost_path_skate while on it)
        m.put("frost_path", n -> {
            Player pl = n.pl;
            Location o = pl.getLocation(); Vector f = n.flat(), rt = right(f);
            o.setPitch(0);
            int ticks = n.i("ticks", 100); double haste = n.d("haste", 0.3);
            Location mid = o.clone().add(f.clone().multiply(4.4)).add(0, 0.5, 0);
            Map<UUID, Integer> slowed = new HashMap<>();
            boolean[] on = {false};
            n.later(12, () -> n.fx("frost_path", o));
            n.loop(ticks, age -> {
                if (age < 10 || age % 4 != 0) return true;
                boolean in = onRoad(o, f, rt, pl.getLocation());
                if (in != on[0]) {
                    on[0] = in;
                    if (in) {
                        BuffMech.apply(n.plugin, pl, "frost_path", List.<Object[]>of(new Object[]{Attribute.MOVEMENT_SPEED, haste, AttributeModifier.Operation.ADD_SCALAR}), ticks - age + 1);
                        n.fx("frost_path_skate", pl.getLocation(), pl);
                    } else { BuffMech.clear(pl, "frost_path"); n.fx("frost_path_skate_off", pl.getLocation(), pl); }
                }
                for (LivingEntity t : n.near(mid, 5.5)) {
                    if (!onRoad(o, f, rt, t.getLocation())) continue;
                    Integer last = slowed.get(t.getUniqueId());
                    if (last != null && age - last < 60) continue;
                    if (!n.hit(t, 0)) continue;
                    slowed.put(t.getUniqueId(), age);
                    n.status(t, "slow:60");
                    n.fx("frost_path_chill", t.getLocation(), t);
                }
                return true;
            }, () -> {
                if (on[0]) { BuffMech.clear(pl, "frost_path"); if (pl.isOnline()) n.fx("frost_path_skate_off", pl.getLocation(), pl); }
                if (pl.isOnline()) n.fx("frost_path_end", o);
            });
            return true;
        });

        // 341 수류 탄환 (HydroBurst): 1.5 s channel, 8 water bolts at +0.20 + k·0.17 s along the current look (14 blocks, 1.25 b/t),
        // each hit = damage + small push; caster speed -50 % while channelling; stun/sleep/fear/disarm cuts it
        m.put("hydro_burst", n -> {
            Player pl = n.pl;
            int[] at = {4, 7, 11, 14, 18, 21, 24, 28};
            double speed = n.d("speed", 1.25), range = n.d("range", 14), push = n.d("knock", 0.25);
            BuffMech.apply(n.plugin, pl, "hydro_burst", List.<Object[]>of(new Object[]{Attribute.MOVEMENT_SPEED, -0.5, AttributeModifier.Operation.MULTIPLY_SCALAR_1}), 30);
            int[] next = {0};
            n.loop(30, age -> {
                if (pl.isDead() || n.has(pl, Status.STUN) || n.has(pl, Status.SLEEP) || n.has(pl, Status.FEAR) || n.has(pl, Status.DISARM)) {
                    n.fx("channel_cut", pl.getLocation(), pl);
                    return false;
                }
                while (next[0] < at.length && age >= at[next[0]]) {
                    next[0]++;
                    Vector dir = n.look();
                    Location muzzle = pl.getEyeLocation().add(0, -0.35, 0).add(dir.clone().multiply(0.95));
                    if (!Nova.passable(muzzle)) muzzle = pl.getEyeLocation();
                    n.shotFrom(muzzle, dir, speed, range, "bolt").radius(0.45)
                        .hit((t, p) -> { if (n.hit(t, n.dmg())) { n.knock(t, pl.getLocation(), dir, push, 0.05); n.fx("splash", p); } return false; })
                        .end((p, wall) -> { if (wall) n.fx("splash", p); })
                        .fire();
                }
                return next[0] < at.length || age < 30;
            }, () -> BuffMech.clear(pl, "hydro_burst"));
            return true;
        });

        // 342 결정 갑옷 (CrystalCoat): the ally looked at within 15 blocks (else self) gets an ice shield (temporary hearts) for 4 s.
        // Shield used up = shatter: 둔화 3초 to enemies within 3 blocks of the ally. Time out = quiet fade.
        m.put("crystal_coat", n -> {
            Player pl = n.pl;
            Player ally = aimedAlly(n, n.d("range", 15));
            Player who = ally == null ? pl : ally;
            if (!n.plugin.protection().pair(pl, who.getLocation())) return false;
            double amount = n.dmg() > 0 ? n.dmg() : n.d("amount", 8);
            int ticks = n.i("ticks", 80);
            double other = Math.max(0, who.getAbsorptionAmount());
            TempAbsorb.give(n.plugin, who, "crystal_coat", amount, ticks);
            double floor = Math.max(0, Math.min(who.getAbsorptionAmount(), other + amount) - amount);
            Object token = new Object();
            COATS.put(who.getUniqueId(), token);
            n.later(0, () -> {
                if (who != pl) n.fx("crystal_coat_link", Nova.chest(who), who);
                n.fx("crystal_coat_begin", who.getLocation(), who);
            });
            n.later(10, () -> { if (COATS.get(who.getUniqueId()) == token && who.isValid()) n.fx("crystal_coat", who.getLocation(), who); });
            double[] last = {who.getAbsorptionAmount()}; int[] lastFx = {-10}; boolean[] broke = {false};
            n.loop(ticks, age -> {
                if (COATS.get(who.getUniqueId()) != token) return false;
                if (!who.isValid() || who.isDead() || !who.isOnline()) return false;
                double abs = who.getAbsorptionAmount();
                if (abs <= floor + 0.01 && age > 0 && age < ticks - 2) { broke[0] = true; return false; }
                if (abs < last[0] - 0.01 && age - lastFx[0] >= 4) {
                    lastFx[0] = age;
                    n.fx("crystal_coat_absorb", Nova.chest(who), who);
                }
                last[0] = abs;
                return true;
            }, () -> {
                if (!COATS.remove(who.getUniqueId(), token)) return;
                if (!broke[0]) { if (who.isValid() && pl.isOnline()) n.fx("crystal_coat_fade", who.getLocation(), who); return; }
                TempAbsorb.clear(who, new NamespacedKey("chacademiamagicmythic", "absorb_crystal_coat"));
                n.fx("crystal_coat_shatter", who.getLocation(), who);
                for (LivingEntity t : n.near(who.getLocation(), n.d("r", 3))) {
                    if (!n.hit(t, 0)) continue;
                    n.status(t, "slow:60");
                    n.fx("crystal_coat_chill", t.getLocation(), t);
                }
            });
            if (who != pl) n.info(who.getName() + "에게 결정 갑옷을 씌웠어요");
            return true;
        });

        // 343 얼음 꽃 (IceBloom): flower on the aimed ground, bursts at +1.5 s into 6 shard lines (60° apart, line 0 = facing),
        // 6 blocks long, racing 20 b/s: an enemy within 0.9 of a line is hit when the shard reaches it (1.5 + d/20 s)
        m.put("ice_bloom", n -> {
            Location c = Nova.ground(n.aim().add(0, 0.5, 0));
            if (!n.plugin.protection().pair(n.pl, c)) return false;
            Vector f = n.flat(); double len = n.d("len", 6), w = n.d("w", 0.9);
            n.later(30, () -> {
                for (LivingEntity t : n.near(c.clone().add(0, 1, 0), len + 1.5)) {
                    Location tl = t.getLocation();
                    if (tl.getY() < c.getY() - 1.5 || tl.getY() > c.getY() + 2.5) continue;
                    Vector v = tl.toVector().subtract(c.toVector()).setY(0);
                    double d = v.length(), best = Double.MAX_VALUE;
                    if (d > 0.6) {
                        for (int k = 0; k < 6; k++) {
                            Vector line = f.clone().rotateAroundY(-k * Math.PI / 3);
                            double along = v.dot(line);
                            if (along < 0 || along > len + 0.3) continue;
                            best = Math.min(best, Math.sqrt(Math.max(0, d * d - along * along)));
                        }
                        if (best > w + t.getWidth() * 0.5) continue;
                    }
                    n.later((int) Math.round(Math.min(d, len)), () -> {
                        if (n.hit(t, n.dmg())) n.fx("ice_bloom_hit", t.getLocation());
                    });
                }
            });
            return true;
        });

        // 344 진공 폭발 (VacuumBurst): r 4 on the aimed ground, enemies pulled to the centre for 0.8 s (not bosses), then the burst
        // (damage + a small push outward)
        m.put("vacuum_burst", n -> {
            Location c = Nova.ground(n.aim().add(0, 0.5, 0));
            if (!n.plugin.protection().pair(n.pl, c)) return false;
            double r = n.d("r", 4), pull = n.d("pull", 0.35);
            Set<UUID> ok = new HashSet<>(), no = new HashSet<>();
            n.loop(16, age -> {
                if (age >= 16) return false;
                if (age % 2 != 0) return true;
                for (LivingEntity t : n.near(c.clone().add(0, 1, 0), r + 0.5)) {
                    if (n.boss(t) || no.contains(t.getUniqueId())) continue;
                    if (!ok.contains(t.getUniqueId())) { if (!n.hit(t, 0)) { no.add(t.getUniqueId()); continue; } ok.add(t.getUniqueId()); }
                    Vector v = c.toVector().subtract(t.getLocation().toVector()).setY(0);
                    if (v.length() > 0.6) t.setVelocity(v.normalize().multiply(Math.min(pull, v.length() * 0.5)).setY(0.04));
                }
                return true;
            }, () -> {
                if (!n.pl.isOnline()) return;
                for (LivingEntity t : n.near(c.clone().add(0, 1.2, 0), r)) if (n.hit(t, n.dmg())) n.knock(t, c, n.flat(), n.d("knock", 0.45), 0.2);
            });
            return true;
        });

        // 345 질풍 연격 (GaleCombo): recast within 2 s = next step (slash, slash, push). Mana only for step 1 (steps 2/3 refunded),
        // no cooldown between steps (Links.clearCooldown); the real cooldown after step 3 (Bridge) or when the window lapses (own).
        m.put("gale_combo", n -> {
            Player pl = n.pl; UUID id = pl.getUniqueId(); long t0 = now();
            double mana = n.d("mana", 30); int cd = (int) Math.round(n.d("cooldown", 10) * 20);
            Long own = COMBO_CD.get(id);
            if (own != null && own > t0) {
                n.warn("재사용 대기 " + (int) Math.ceil((own - t0) / 20.0) + "초");
                n.refund(mana); n.clearCooldown();
                return false;
            }
            COMBO_CD.remove(id);
            int[] prev = COMBO.get(id);
            int step = prev != null && prev[1] >= t0 ? prev[0] + 1 : 1;
            if (step > 1) n.refund(mana);
            Vector f = n.flat();
            if (step < 3) {
                int[] mine = {step, (int) (t0 + 40)};
                COMBO.put(id, mine);
                n.clearCooldown();
                Bukkit.getScheduler().runTaskLater(n.plugin, () -> {
                    if (COMBO.get(id) != mine) return;
                    COMBO.remove(id);
                    if (pl.isOnline()) COMBO_CD.put(id, now() + cd);
                }, 41);
            } else COMBO.remove(id);
            String sys = step == 1 ? "gale_combo_slash1" : step == 2 ? "gale_combo_slash2" : "gale_combo_push3";
            n.later(0, () -> n.fx(sys, pl.getLocation(), pl));
            boolean push = step == 3;
            n.later(push ? 2 : 1, () -> {
                Location from = pl.getLocation();
                double r = push ? n.d("push_r", 4.5) : n.d("r", 3.5);
                for (LivingEntity t : n.near(from.clone().add(0, 1, 0), r + 1)) {
                    if (!inCone(from, f, t, r, 65)) continue;
                    if (!n.hit(t, push ? n.dmg(n.d("push_mult", 1.4)) : n.dmg())) continue;
                    n.knock(t, from, f, push ? n.d("knock", 1.3) : 0.15, push ? 0.25 : 0);
                    n.fx(push ? "gale_combo_push_hit" : "gale_combo_hit", Nova.chest(t));
                }
            });
            return true;
        });
    }

    // ------------------------------------------------------------------ 336 wheel
    private static void wheel(Nova n, Location start, Vector dir, double speed, double range, boolean canBounce) {
        GameplayVisual[] vis = {n.shot("flame_wheel", start.clone().add(0, 0.7, 0), dir)};
        Nova.Shot[] me = new Nova.Shot[1];
        me[0] = n.shotFrom(start, dir, speed, range, null).ground().radius(0.9)
            .hit((t, p) -> { if (n.hit(t, n.dmg())) n.fx("flame_wheel_pierce", p.clone().add(0, 0.7, 0)); return true; })
            .step((p, d) -> { if (vis[0] != null) vis[0].move(p.clone().add(0, 0.7, 0), d); })
            .end((p, wall) -> {
                if (vis[0] != null) { vis[0].move(p.clone().add(0, 0.7, 0), me[0].dir); vis[0].close(); vis[0] = null; }
                double left = range - me[0].travelled;
                if (wall && canBounce && left > 0.8 && n.pl.isOnline()) {
                    Vector d = me[0].dir.clone().setY(0);
                    if (d.lengthSquared() < 1e-6) d = dir.clone().setY(0);
                    d.normalize();
                    Vector normal = d.clone().multiply(-1);
                    var ray = p.getWorld().rayTraceBlocks(p.clone().subtract(d.clone().multiply(0.3)), d, 1.2, FluidCollisionMode.NEVER, true);
                    if (ray != null && ray.getHitBlockFace() != null && ray.getHitBlockFace() != BlockFace.UP && ray.getHitBlockFace() != BlockFace.DOWN)
                        normal = ray.getHitBlockFace().getDirection().setY(0).normalize();
                    Vector out = d.clone().subtract(normal.clone().multiply(2 * d.dot(normal))).setY(0);
                    if (out.lengthSquared() < 1e-6) out = d.clone().multiply(-1);
                    out.normalize();
                    n.fx("flame_wheel_bounce", p.clone().add(0, 0.7, 0));
                    Location back = p.clone().add(out.clone().multiply(0.15));
                    wheel(n, back, out, speed, left, false);
                } else n.fx("flame_wheel_end", p.clone().add(0, 0.7, 0));
            });
        me[0].fire();
    }

    // ------------------------------------------------------------------ 340 road
    private static boolean onRoad(Location o, Vector f, Vector rt, Location at) {
        if (at.getWorld() != o.getWorld()) return false;
        Vector v = at.toVector().subtract(o.toVector());
        double dy = v.getY(); v.setY(0);
        double z = v.dot(f), x = v.dot(rt);
        return Math.abs(x) <= 1.0 && z >= 0.4 && z <= 8.4 && dy > -1.5 && dy < 1.5;
    }

    // ------------------------------------------------------------------ 342 ally pick (as AllyMech.aimedAlly)
    private static Player aimedAlly(Nova n, double range) {
        Player pl = n.pl;
        var eye = pl.getEyeLocation();
        var hit = pl.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.8,
            e -> e instanceof Player && !e.equals(pl) && n.plugin.targets().ally(pl, e));
        if (hit == null || !(hit.getHitEntity() instanceof Player ally)) return null;
        var block = pl.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(eye.toVector()) < hit.getHitPosition().distanceSquared(eye.toVector())) return null;
        return ally;
    }

    static void enable(MagicMythicPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            // 339: a lured monster may not pick the caster again while the decoy stands
            @EventHandler(ignoreCancelled = true)
            public void target(EntityTargetEvent e) {
                Object[] v = LURED.get(e.getEntity().getUniqueId());
                if (v == null) return;
                if ((long) v[1] < now()) { LURED.remove(e.getEntity().getUniqueId()); return; }
                if (e.getTarget() != null && e.getTarget().getUniqueId().equals(v[0])) e.setCancelled(true);
            }
            @EventHandler
            public void quit(PlayerQuitEvent e) {
                UUID id = e.getPlayer().getUniqueId();
                COMBO.remove(id); COMBO_CD.remove(id); COATS.remove(id);
                LURED.values().removeIf(v -> v[0].equals(id));
                long t = now();
                COMBO_CD.values().removeIf(x -> x < t);
            }
        }, plugin);
    }
}
