package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * chaarcana{type=...} - the spells 168~214 of the 2026-10-19 final plan (차카데미 전체 마법 기획 238종) that need their own
 * logic. Numbers marked 미정 in the plan are placeholders (owner: "기존 체계 기준 임시값"), every one is a parameter here.
 * Visuals: each type sends external:* events to the caster's running cast of the spell (spell=id, PortableVFX
 * spell-bindings.yml, trajectory server-events). Event names = the VFX pack's system names.
 *
 *  double ticks=160 mana=75 cooldown=25 168 그림자 분신: leave a double; the next cast within ticks returns to it (mana refunded).
 *                                       No cooldown while it stands; it starts on return (Bridge) or when the double expires (here)
 *  echo range=6                         169 잔향 읽기: MagicEchoEvent for the aimed block/entity (puzzles answer)
 *  thread range=5 ticks=6000            170 봉인의 실: seal a door/chest the caster placed; others cannot open it
 *  swap range=15 hide=6                 172 그림자 교환: swap places with the target
 *  contract ratio=0.7 ticks=60 lock=300 heal=0.1  173 금서의 계약: pay 70% max mana; 3 s mana regen -> health, then 15 s no regen
 *  siphon r=3 ticks=60 period=10 mult=1 heal=0.5  174 생명 갈취: dark zone, drains enemies on it, heals the caster
 *  drowse speed=0.8 range=16 r=3 wait=200 root=60 175 졸음: a dark orb flies to the point, waits; first enemy in range is rooted
 *  spread r=6 max=5 keep=0.7            178 저주의 전가: the target's debuffs are copied to enemies around it (x0.7 time)
 *  discord ticks=120 r=10               179 마인드컨트롤: a normal monster attacks the monsters near it
 *  chain r=6 ticks=160 ratio=0.3        180 밤의 사슬: enemies in the area share direct damage
 *  defer ticks=120 after=100 ratio=0.5  181 고통 유예: half of the damage taken is postponed, then paid over 5 s
 *  ink                                  183 먹물 손끝: 64 dyes of the colour held in either hand
 *  nail ticks=300 dist=20 mult=1        184 그림자 못: damage once if the target gets more than dist blocks away
 *  lullaby count=3 ticks=200            185 밤의 자장가: 3 phantoms hunt the caster's enemies for 10 s
 *  loosen ticks=80                      186 손아귀 풀기: player = random hotbar slot / monster = 무장해제
 *  seal ticks=160                       188 회복 봉인: 회복봉인 on the target
 *  scales r=4 mult=1                    189 어둠의 저울: area burst, more damage the healthier the target is vs the caster
 *  cage r=6 ticks=140                   190 경고의 실: enemies inside the circle cannot leave it
 *  wound speed=0.7 range=20             192 상처 회계: a homing ink lump puts one random debuff on the target
 *  ghosttool ticks=600 reach=27         194 유령 도구: block reach +reach for ticks
 *  bell r=8 ticks=60                    195 공포의 종: fear on normal monsters around (players and bosses never)
 *  eclipse r=15 ticks=200 shards=6 speed=0.3 contact=20 mult=1 haste=0.3  196 월식의 눈 (after a 5 s chant)
 *  airbrake dist=6                      203 빗자루 차원이동: riding a broom, jump ~6 blocks ahead
 *  hushed ticks=400                     210 고요한 밑창: walking/running makes no sculk vibration
 *  thorns ticks=400                     211 가시의 예절: sweet berry bushes neither slow nor hurt the caster
 *  skipring r0=1 r1=7 ticks=40 mult=1   212 불꽃 줄넘기: a ring of fire runs out on the ground; jump over it
 *  droplet speed=0.6 range=10 r=2.5 ticks=60 mult=1  213 물방울 인계: a droplet flies to the point and lifts enemies there
 *  tideplane len=6 width=2 delay=20 turn=26 mult=1   214 접히는 수면: a water sheet folds over like a page
 */
final class ArcanaMech extends Mech {
    ArcanaMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    private String spell() { return p.s("spell", ""); }
    private void vfx(Player pl, String event, Location at, UUID target) { if (pl != null && !spell().isBlank()) Links.vfx(pl, "" + spell(), "external:" + event, at, target); }
    /** 할 수 없는 행동 → 경고 알림 (같은 문구는 2초 안에 다시 안 보냄). */
    private static void bar(Player pl, String text) { ChacaLink.notify(pl, "warning", "magic-mythic:" + text, text, "", 2000); }
    /** 결과 안내 → 정보 알림. */
    private static void info(Player pl, String text) { ChacaLink.notify(pl, "system", "magic-mythic:" + text, text, "", 1500); }
    private static Vector flat(Player pl) { double y = Math.toRadians(pl.getLocation().getYaw()); return new Vector(-Math.sin(y), 0, Math.cos(y)); }
    private static Location chest(Entity e) { return e.getLocation().add(0, e.getHeight() * 0.55, 0); }
    private State st() { return State.get(); }

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false; // player spells only
        var st = st(); if (st == null) return false;
        return switch (p.s("type", "").toLowerCase(Locale.ROOT)) {
            case "double" -> shadowDouble(pl);
            case "echo" -> echo(pl);
            case "thread" -> thread(pl);
            case "swap" -> swap(pl, c);
            case "contract" -> contract(pl);
            case "siphon" -> siphon(pl, c);
            case "drowse" -> drowse(pl, c);
            case "spread" -> spread(pl, c);
            case "discord" -> discord(pl, c);
            case "chain" -> chain(pl, c);
            case "defer" -> defer(pl);
            case "ink" -> ink(pl);
            case "nail" -> nail(pl, c);
            case "lullaby" -> lullaby(pl, c);
            case "loosen" -> loosen(pl, c);
            case "seal" -> healingSeal(pl, c);
            case "scales" -> scales(pl, c);
            case "cage" -> cage(pl, c);
            case "wound" -> wound(pl, c);
            case "ghosttool" -> ghostTool(pl);
            case "bell" -> bell(pl);
            case "eclipse" -> eclipse(pl, c);
            case "airbrake" -> airbrake(pl);
            case "hushed" -> { st.hushed.put(pl.getUniqueId(), st.now + p.i("ticks", 400)); yield true; }
            case "thorns" -> { st.thorns.put(pl.getUniqueId(), st.now + p.i("ticks", 400)); yield true; }
            case "skipring" -> skipring(pl, c);
            case "droplet" -> droplet(pl, c);
            case "tideplane" -> tideplane(pl, c);
            default -> false;
        };
    }

    // ================================================================== 168 그림자 분신
    private boolean shadowDouble(Player pl) {
        var st = st(); UUID id = pl.getUniqueId();
        Object[] d = st.doubles.get(id); // {Location, until}
        if (d != null && (long) d[1] >= st.now) {
            st.doubles.remove(id);
            Location back = ((Location) d[0]).clone();
            vfx(pl, "depart", pl.getLocation(), null);
            back.setYaw(pl.getLocation().getYaw()); back.setPitch(pl.getLocation().getPitch());
            if (back.getWorld() == pl.getWorld() && plugin.protection().at(back)) pl.teleport(back);
            vfx(pl, "return", back, null);
            // this second press was charged by the Bridge too: the return itself is free, the cooldown starts now
            double cost = p.d("mana", 0);
            if (cost > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, cost));
            return true;
        }
        Long cdUntil = st.doubleCooldown.get(id);
        if (cdUntil != null && cdUntil > st.now) {   // the double expired by itself: its cooldown is ours (the Bridge one was cleared)
            bar(pl, "재사용 대기 " + (int) Math.ceil((cdUntil - st.now) / 20.0) + "초");
            double cost = p.d("mana", 0);
            if (cost > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, cost));
            Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell()));
            return false;
        }
        int ticks = Math.clamp(p.i("ticks", 160), 20, 1200);
        Location at = pl.getLocation();
        Object[] mine = {at.clone(), st.now + ticks};
        st.doubles.put(id, mine);
        vfx(pl, "form", at, null);
        // no cooldown while the double stands (owner): let the key be pressed again
        Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (st.doubles.get(id) == mine) {
                st.doubles.remove(id);
                st.doubleCooldown.put(id, st.now + Math.round(p.d("cooldown", 25) * 20));
                if (pl.isOnline()) vfx(pl, "expire", (Location) mine[0], null);
            }
        }, ticks + 1);
        return true;
    }

    // ================================================================== 169 잔향 읽기
    private boolean echo(Player pl) {
        double range = p.d("range", 6);
        var hit = pl.getWorld().rayTrace(pl.getEyeLocation(), pl.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true, 0.3,
            e -> !e.equals(pl) && !(e instanceof Player));
        if (hit == null) { bar(pl, "읽을 물건을 바라봐 주세요."); return false; }
        Block b = hit.getHitBlock(); Entity e = hit.getHitEntity();
        Location bottom = e != null ? e.getLocation() : b.getLocation().add(0.5, 0, 0.5);
        var ev = new MagicEchoEvent(pl, b, e, bottom);
        Bukkit.getPluginManager().callEvent(ev);
        vfx(pl, "echo_reading", bottom, e == null ? null : e.getUniqueId());
        if (ev.getClue() != null) ChacaLink.notify(pl, "system", "magic-mythic:echo-clue", "남아 있는 기억", ev.getClue(), 1000);
        else if (!ev.isHandled()) info(pl, "남아 있는 기억이 없다.");
        return true;
    }

    // ================================================================== 170 봉인의 실
    private boolean thread(Player pl) {
        Block b = pl.getTargetBlockExact((int) Math.ceil(p.d("range", 5)), FluidCollisionMode.NEVER);
        if (b == null || !State.sealable(b)) { bar(pl, "직접 설치한 문이나 상자를 바라봐 주세요."); return false; }
        Block key = State.lockBlock(b);
        UUID placer = State.placer(key);
        if (placer == null || !placer.equals(pl.getUniqueId())) { bar(pl, "직접 설치한 문이나 상자에만 봉인을 걸 수 있어요."); return false; }
        var st = st(); String k = State.key(key);
        if (st.seals.containsKey(k)) { st.seals.remove(k); info(pl, "봉인을 풀었다."); return true; }
        st.seals.put(k, new Object[]{pl.getUniqueId(), st.now + Math.clamp(p.i("ticks", 6000), 20, 20 * 3600)});
        Location lock = key.getLocation().add(0.5, Tag.DOORS.isTagged(key.getType()) ? 0.95 : 0.5625, 0.5);
        vfx(pl, "thread", lock, null);
        String wrap = "wrap_chest";
        if (key.getBlockData() instanceof Door door) wrap = door.getHinge() == Door.Hinge.LEFT ? "wrap_door_r" : "wrap_door_l";
        vfx(pl, wrap, lock, null);
        info(pl, "봉인을 걸었다. 다시 걸면 풀린다.");
        return true;
    }

    // ================================================================== 172 그림자 교환
    private boolean swap(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity t) || t.equals(pl) || t.getWorld() != pl.getWorld()) return false;
        if (t instanceof Tameable tame && tame.getOwner() != null && !pl.getUniqueId().equals(tame.getOwner().getUniqueId())) { bar(pl, "다른 사람의 펫과는 바꿀 수 없어요."); return false; }
        if (pl.getLocation().distanceSquared(t.getLocation()) > Math.pow(p.d("range", 15) + 1, 2)) return false;
        if (!plugin.protection().at(t.getLocation()) || !plugin.targets().hurt(pl, t, 0)) return false;
        vfx(pl, "vanish", pl.getLocation(), null); vfx(pl, "vanish", t.getLocation(), t.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline() || !t.isValid() || t.getWorld() != pl.getWorld()) return;
            Location a = pl.getLocation(), b = t.getLocation();
            Location toB = b.clone(); toB.setYaw(a.getYaw()); toB.setPitch(a.getPitch());
            Location toA = a.clone(); toA.setYaw(b.getYaw()); toA.setPitch(b.getPitch());
            pl.teleport(toB); t.teleport(toA);
            vfx(pl, "appear", toB, null); vfx(pl, "appear", toA, t.getUniqueId());
        }, Math.clamp(p.i("hide", 6), 0, 20));
        return true;
    }

    // ================================================================== 173 금서의 계약
    private boolean contract(Player pl) {
        Double max = Links.maxMana(pl), cur = Links.mana(pl);
        if (max == null || cur == null) return false;
        double cost = max * Math.clamp(p.d("ratio", 0.7), 0, 1);
        if (cur < cost || !Links.consume(pl, cost)) { bar(pl, "마나가 부족해요 (최대 마나의 " + Math.round(p.d("ratio", 0.7) * 100) + "%)."); return false; }
        vfx(pl, "contract", pl.getLocation(), null);
        int conv = Math.clamp(p.i("ticks", 60), 1, 400), lock = Math.clamp(p.i("lock", 300), 1, 2400);
        double heal = p.d("heal", 0.1); // health per mana point that would have been regenerated
        vfx(pl, "conversion", pl.getLocation(), null);
        plugin.ticker().add(new Ticker.Tick() {
            int age; Double last = Links.mana(pl);
            @Override public boolean tick() {
                if (!pl.isOnline() || pl.isDead()) { Links.regen(pl.getUniqueId(), "forbidden_contract", 1); return false; }
                age++;
                if (age <= conv) {
                    Double now = Links.mana(pl);
                    if (now != null && last != null && now > last) {   // the regen of this tick goes to health instead
                        double gained = now - last;
                        if (Links.consume(pl, gained)) Targets.heal(pl, pl, gained * heal);
                        now = Links.mana(pl);
                    }
                    last = now;
                    if (age == conv) { vfx(pl, "conversion_end", pl.getLocation(), null); vfx(pl, "seal_lock", pl.getLocation(), null); Links.regen(pl.getUniqueId(), "forbidden_contract", 0); }
                    return true;
                }
                if (age >= conv + lock) { Links.regen(pl.getUniqueId(), "forbidden_contract", 1); vfx(pl, "seal_break", pl.getLocation(), null); return false; }
                return true;
            }
            @Override public void stop() { Links.regen(pl.getUniqueId(), "forbidden_contract", 1); }
        });
        return true;
    }

    // ================================================================== 174 생명 갈취
    private boolean siphon(Player pl, Ctx c) {
        Location at = c.at().clone(); double r = p.d("r", 3), healRatio = p.d("heal", 0.5), dmg = c.dmg(p);
        int ticks = Math.clamp(p.i("ticks", 60), 1, 400), period = Math.clamp(p.i("period", 10), 2, 40);
        String element = p.s("element", "");
        vfx(pl, "zone", at, null);
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (!pl.isOnline() || ++age > ticks) { if (pl.isOnline()) vfx(pl, "zone_end", at, null); return false; }
                if (age % period != 0) return true;
                for (LivingEntity t : plugin.targets().enemiesNear(pl, at, r, 16)) {
                    if (Math.abs(t.getLocation().getY() - at.getY()) > 2) continue;
                    double before = t.getHealth();
                    if (!plugin.targets().hurt(pl, t, dmg, element)) continue;
                    double dealt = Math.max(0, before - t.getHealth());
                    Targets.heal(pl, pl, (dealt > 0 ? dealt : dmg) * healRatio);
                    vfx(pl, "drain", chest(t), null);
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 175 졸음
    private boolean drowse(Player pl, Ctx c) {
        Location goal = c.at().clone().add(0, 0.6, 0); double speed = Math.clamp(p.d("speed", 0.8), 0.1, 3) * QuidditchCombat.speedFactor(pl), r = p.d("r", 3); // #33 큐디치 ×1.5
        int wait = Math.clamp(p.i("wait", 200), 1, 1200), root = Math.clamp(p.i("root", 60), 1, 200);
        Location start = pl.getEyeLocation().add(pl.getEyeLocation().getDirection().multiply(0.6)).add(0, -0.3, 0);
        vfx(pl, "orb", start, null);
        var orb = GameplayVisual.cast(pl.getUniqueId(), p.s("orbvfx", ""), start, goal.toVector().subtract(start.toVector()).normalize());
        plugin.ticker().add(new Ticker.Tick() {
            final Location pos = start.clone(); boolean armed; int age;
            @Override public void stop() { if (orb != null) orb.close(); }
            @Override public boolean tick() {
                if (!pl.isOnline() || pos.getWorld() != goal.getWorld()) return false;
                age++;
                if (!armed) {
                    Vector to = goal.toVector().subtract(pos.toVector());
                    if (to.length() <= speed || !pos.clone().add(to.clone().normalize().multiply(speed)).getBlock().isPassable()) {
                        if (to.length() <= speed) { pos.setX(goal.getX()); pos.setY(goal.getY()); pos.setZ(goal.getZ()); }
                        armed = true; age = 0; vfx(pl, "eye", pos.clone().add(0, -0.6, 0), null);
                    } else { Vector step = to.normalize().multiply(speed); pos.add(step); if (orb != null) orb.move(pos, step); }
                    if (age >= 200) { if (orb != null) orb.close(); return false; }
                    return true;
                }
                if (age > wait) { if (orb != null) orb.close(); vfx(pl, "eye_end", pos.clone().add(0, -0.6, 0), null); return false; }
                if (age % 2 != 0) return true;
                for (LivingEntity t : plugin.targets().enemiesNear(pl, pos.clone().add(0, -0.6, 0), r, 1)) {
                    if (!plugin.targets().hurt(pl, t, 0)) continue;
                    plugin.status().apply(pl, t, Status.ROOT, root, 0);
                    if (orb != null) orb.close();
                    vfx(pl, "eye_end", pos.clone().add(0, -0.6, 0), null);
                    vfx(pl, "latch", t.getLocation(), t.getUniqueId());
                    return false;
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 178 저주의 전가
    private boolean spread(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity from)) return false;
        var debuffs = plugin.status().active(from);
        var potions = new ArrayList<PotionEffect>();
        for (PotionEffect fx : from.getActivePotionEffects()) if (harmful(fx.getType()) && !fx.isInfinite()) potions.add(fx);
        if (debuffs.isEmpty() && potions.isEmpty()) { bar(pl, "옮길 디버프가 없어요."); return false; }
        double keep = Math.clamp(p.d("keep", 0.7), 0.05, 1);
        int n = 0;
        for (LivingEntity t : plugin.targets().enemiesNear(pl, from.getLocation(), p.d("r", 6), p.i("max", 5) + 1)) {
            if (t.equals(from) || !plugin.targets().hurt(pl, t, 0)) continue;
            int longest = 0;
            for (var en : debuffs.entrySet()) {
                int ticks = (int) Math.round(en.getValue()[0] * keep);
                if (ticks < 1 || en.getKey() == Status.MANA_BURN) continue;
                if (plugin.status().apply(pl, t, en.getKey(), ticks, en.getValue()[1] / 1000.0)) longest = Math.max(longest, ticks);
            }
            for (PotionEffect fx : potions) {
                int ticks = (int) Math.round(fx.getDuration() * keep);
                if (ticks < 1) continue;
                t.addPotionEffect(new PotionEffect(fx.getType(), ticks, fx.getAmplifier(), true, false, true));
                longest = Math.max(longest, ticks);
            }
            if (longest <= 0) continue;
            vfx(pl, "spread_g1", chest(from), t.getUniqueId());
            vfx(pl, "mark", t.getLocation(), t.getUniqueId());
            if (++n >= p.i("max", 5)) break;
        }
        if (n == 0) bar(pl, "주변에 옮길 적이 없어요.");
        return n > 0;
    }
    private static boolean harmful(PotionEffectType t) {
        return t.equals(PotionEffectType.POISON) || t.equals(PotionEffectType.WEAKNESS) || t.equals(PotionEffectType.SLOWNESS) || t.equals(PotionEffectType.BLINDNESS)
            || t.equals(PotionEffectType.WITHER) || t.equals(PotionEffectType.MINING_FATIGUE) || t.equals(PotionEffectType.NAUSEA) || t.equals(PotionEffectType.HUNGER) || t.equals(PotionEffectType.DARKNESS);
    }

    // ================================================================== 179 마인드컨트롤
    private boolean discord(Player pl, Ctx c) {
        if (!(c.target() instanceof Mob m) || m instanceof Player || plugin.targets().boss(m) || !(m instanceof Enemy)) { bar(pl, "보스가 아닌 일반 몬스터에게만 쓸 수 있어요."); return false; }
        if (!plugin.targets().hurt(pl, m, 0)) return false;
        var st = st(); int ticks = Math.clamp(p.i("ticks", 120), 1, 600);
        Object[] mine = {pl.getUniqueId(), st.now + ticks};
        st.discord.put(m.getUniqueId(), mine);
        m.setTarget(null);
        vfx(pl, "discord_begin", m.getLocation(), m.getUniqueId());
        double r = p.d("r", 10);
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (!m.isValid() || m.isDead() || ++age > ticks || st.discord.get(m.getUniqueId()) != mine) {
                    if (!st.discord.remove(m.getUniqueId(), mine)) return false;   // a newer cast took over
                    // #79: this line had been swallowed by the comment above, so the mark VFX never got its end phase.
                    if (m.isValid()) { m.setTarget(null); if (pl.isOnline()) vfx(pl, "discord_end", m.getLocation(), m.getUniqueId()); }
                    return false;
                }
                if (age % 10 == 1 && (m.getTarget() == null || !m.getTarget().isValid() || m.getTarget() instanceof Player)) {
                    LivingEntity best = null; double bd = Double.MAX_VALUE;
                    for (Entity e : m.getNearbyEntities(r, r, r)) {
                        if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || e.equals(m) || le.isDead()) continue;
                        double d = e.getLocation().distanceSquared(m.getLocation());
                        if (d < bd) { bd = d; best = le; }
                    }
                    State.forcing = true; try { m.setTarget(best); } finally { State.forcing = false; }
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 180 밤의 사슬
    private boolean chain(Player pl, Ctx c) {
        var list = new ArrayList<LivingEntity>();
        for (LivingEntity t : plugin.targets().enemiesNear(pl, c.at(), p.d("r", 6), 32)) if (!(t instanceof Player) && plugin.targets().hurt(pl, t, 0)) list.add(t);
        if (list.size() < 2) { bar(pl, "사슬로 이을 몬스터가 둘 이상 있어야 해요."); return false; }
        var st = st(); int ticks = Math.clamp(p.i("ticks", 160), 1, 1200);
        UUID group = UUID.randomUUID();
        var center = c.at();
        list.sort(Comparator.comparingDouble(e -> Math.atan2(e.getLocation().getZ() - center.getZ(), e.getLocation().getX() - center.getX())));
        for (LivingEntity t : list) {
            st.chains.put(t.getUniqueId(), new Object[]{group, pl.getUniqueId(), st.now + ticks, p.d("ratio", 0.3)});
            vfx(pl, "bind", t.getLocation(), t.getUniqueId());
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.size() == 2 && i == 1) break;
            LivingEntity a = list.get(i), b = list.get((i + 1) % list.size());
            vfx(pl, "chain", chest(a), b.getUniqueId());
        }
        st.chainGroups.put(group, new ArrayList<>(list.stream().map(Entity::getUniqueId).toList()));
        st.chainSpell.put(group, spell());
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (UUID id : st.chainGroups.getOrDefault(group, List.of())) st.chains.computeIfPresent(id, (k, v) -> group.equals(v[0]) ? null : v); st.chainGroups.remove(group); st.chainSpell.remove(group); }, ticks);
        return true;
    }

    // ================================================================== 181 고통 유예
    private boolean defer(Player pl) {
        var st = st();
        st.defer.put(pl.getUniqueId(), new double[]{st.now + Math.clamp(p.i("ticks", 120), 1, 600), 0, Math.clamp(p.d("ratio", 0.5), 0, 1), Math.clamp(p.i("after", 100), 20, 600), 0});
        st.deferSpell.put(pl.getUniqueId(), spell());
        vfx(pl, "cast", pl.getLocation(), null);
        return true;
    }

    // ================================================================== 183 먹물 손끝
    private boolean ink(Player pl) {
        ItemStack it = pl.getInventory().getItemInMainHand();
        if (!it.getType().name().endsWith("_DYE")) it = pl.getInventory().getItemInOffHand();
        if (!it.getType().name().endsWith("_DYE")) {
            bar(pl, "만들 색의 염료를 손에 들고 써 주세요.");
            double cost = p.d("mana", 0); if (cost > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, cost));
            Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell()));
            return false;
        }
        Material dye = it.getType();
        var left = pl.getInventory().addItem(new ItemStack(dye, 64));
        left.values().forEach(rest -> pl.getWorld().dropItemNaturally(pl.getLocation(), rest));
        vfx(pl, "ink_" + dye.name().substring(0, dye.name().length() - 4).toLowerCase(Locale.ROOT), pl.getLocation(), null);
        return true;
    }

    // ================================================================== 184 그림자 못
    private boolean nail(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity t) || !plugin.targets().hurt(pl, t, 0)) return false;
        int ticks = Math.clamp(p.i("ticks", 300), 1, 1200); double dist = p.d("dist", 20), dmg = c.dmg(p); String element = p.s("element", "");
        vfx(pl, "nail", t.getLocation(), t.getUniqueId());
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (!t.isValid() || t.isDead() || !pl.isOnline()) { if (pl.isOnline() && t.isValid()) vfx(pl, "nail_fade", t.getLocation(), t.getUniqueId()); return false; }
                if (++age > ticks) { vfx(pl, "nail_fade", t.getLocation(), t.getUniqueId()); return false; }
                if (age % 5 == 0 && (t.getWorld() != pl.getWorld() || t.getLocation().distanceSquared(pl.getLocation()) > dist * dist)) {
                    if (t.getWorld() == pl.getWorld()) plugin.targets().hurt(pl, t, dmg, element);
                    vfx(pl, "nail_burst", t.getLocation(), t.getUniqueId());
                    return false;
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 185 밤의 자장가
    private boolean lullaby(Player pl, Ctx c) {
        var st = st(); int count = Math.clamp(p.i("count", 3), 1, 6), ticks = Math.clamp(p.i("ticks", 200), 20, 1200);
        double range = p.d("range", 16);
        var spawned = new ArrayList<Phantom>();
        for (int i = 0; i < count; i++) {
            double a = Math.toRadians(pl.getLocation().getYaw()) + (i - (count - 1) / 2.0) * 0.9;
            Location at = pl.getLocation().add(-Math.sin(a) * 2.5, 3, Math.cos(a) * 2.5);
            if (!plugin.protection().at(at) || !at.getBlock().isPassable()) at = pl.getLocation().add(0, 2.5, 0);
            vfx(pl, "rift", at, null);
            Phantom ph = pl.getWorld().spawn(at, Phantom.class, x -> {
                x.setPersistent(false); x.setShouldBurnInDay(false); x.setSize(0); x.setSilent(false);
                x.addScoreboardTag("chacademia_summon"); x.setRemoveWhenFarAway(true);
                x.getPersistentDataContainer().set(State.SUMMON_OWNER, PersistentDataType.STRING, pl.getUniqueId().toString());
            });
            spawned.add(ph);
            st.summons.put(ph.getUniqueId(), pl.getUniqueId());
            st.summonSpell.put(ph.getUniqueId(), spell());
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (ph.isValid() && pl.isOnline()) vfx(pl, "trail", ph.getLocation(), ph.getUniqueId()); }, 8);
        }
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                age++;
                boolean any = false;
                for (Phantom ph : spawned) {
                    if (!ph.isValid()) continue;
                    any = true;
                    if (age >= ticks || !pl.isOnline() || ph.getWorld() != pl.getWorld()) {
                        if (pl.isOnline()) vfx(pl, "dismiss", ph.getLocation(), null);
                        st.summons.remove(ph.getUniqueId()); st.summonSpell.remove(ph.getUniqueId()); ph.remove(); continue;
                    }
                    if (age % 10 == 0 && (ph.getTarget() == null || !ph.getTarget().isValid() || ph.getTarget().isDead() || !plugin.targets().enemy(pl, ph.getTarget()))) {
                        var near = new ArrayList<>(plugin.targets().enemiesNear(pl, pl.getLocation(), range, 8));
                        near.removeIf(e -> st.summons.containsKey(e.getUniqueId()));
                        State.forcing = true; try { ph.setTarget(near.isEmpty() ? null : near.get(0)); } finally { State.forcing = false; }
                    }
                }
                return any && age <= ticks;
            }
        });
        return true;
    }

    // ================================================================== 186 손아귀 풀기
    private boolean loosen(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity t) || !plugin.targets().hurt(pl, t, 0)) return false;
        vfx(pl, "hand_ripple", t.getLocation(), t.getUniqueId());
        if (t instanceof Player other) {
            int now = other.getInventory().getHeldItemSlot(), next = now;
            while (next == now) next = java.util.concurrent.ThreadLocalRandom.current().nextInt(9);
            other.getInventory().setHeldItemSlot(next);
            return true;
        }
        int ticks = Math.clamp(p.i("ticks", 80), 1, 400);
        if (plugin.status().apply(pl, t, Status.DISARM, ticks, 0)) {
            vfx(pl, "mark", t.getLocation(), t.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (t.isValid() && pl.isOnline()) vfx(pl, "mark_break", t.getLocation(), t.getUniqueId()); }, ticks);
        }
        return true;
    }

    // ================================================================== 188 회복 봉인
    private boolean healingSeal(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity t) || !plugin.targets().hurt(pl, t, 0)) return false;
        int ticks = Math.clamp(p.i("ticks", 160), 1, 1200);
        if (!plugin.status().apply(pl, t, Status.RECOVERY_SEAL, ticks, 0)) return false;
        vfx(pl, "healing_seal", t.getLocation(), t.getUniqueId());
        return true;
    }

    // ================================================================== 189 어둠의 저울
    private boolean scales(Player pl, Ctx c) {
        Location at = c.at(); double dmg = c.dmg(p); String element = p.s("element", "");
        vfx(pl, "burst", at, null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline()) return;
            for (LivingEntity t : plugin.targets().enemiesNear(pl, at, p.d("r", 4), 16)) {
                double ratio = t.getHealth() / Math.max(1, pl.getHealth());
                // owner: target >= 2x the caster's current health -> 150 % total; below that +1 %..+50 % (interpolation 미정: linear 1x..2x)
                double bonus = ratio >= 2 ? 0.5 : 0.01 + 0.49 * Math.clamp(ratio - 1, 0, 1);
                if (!plugin.targets().hurt(pl, t, dmg * (1 + bonus), element)) continue;
                String hit = bonus <= 0.16 ? "hit_low" : bonus <= 0.33 ? "hit_mid" : "hit_high";
                vfx(pl, hit, chest(t), t.getUniqueId());
            }
        }, Math.clamp(p.i("delay", 8), 0, 40));
        return true;
    }

    // ================================================================== 190 경고의 실
    private boolean cage(Player pl, Ctx c) {
        Location center = (c.target() != null ? c.target().getLocation() : c.at()).clone();
        double r = p.d("r", 6); int ticks = Math.clamp(p.i("ticks", 140), 1, 600);
        var inside = new ArrayList<LivingEntity>();
        for (LivingEntity t : plugin.targets().enemiesNear(pl, center, r, 32)) if (plugin.targets().hurt(pl, t, 0)) inside.add(t);
        vfx(pl, "cage", center, null);
        var lastBreach = new HashMap<UUID, Integer>();
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (++age > ticks || !pl.isOnline()) { if (pl.isOnline()) vfx(pl, "cage_end", center, null); return false; }
                for (LivingEntity t : inside) {
                    if (!t.isValid() || t.isDead() || t.getWorld() != center.getWorld()) continue;
                    Location l = t.getLocation(); double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ(), d = Math.hypot(dx, dz);
                    if (d <= r - 0.3) continue;
                    double k = (r - 0.6) / Math.max(d, 1e-3);
                    Location back = l.clone(); back.setX(center.getX() + dx * k); back.setZ(center.getZ() + dz * k);
                    if (back.getBlock().isPassable() && back.clone().add(0, 1, 0).getBlock().isPassable()) t.teleport(back);
                    t.setVelocity(new Vector(-dx, 0, -dz).normalize().multiply(0.25));
                    int last = lastBreach.getOrDefault(t.getUniqueId(), -100);
                    if (age - last >= 10) { lastBreach.put(t.getUniqueId(), age); vfx(pl, "breach", center.clone().add(dx / d * r, 0, dz / d * r), null); }
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 192 상처 회계
    private static final Object[][] WOUNDS = {
        {Status.STUN, 20}, {Status.ROOT, 30}, {Status.DISARM, 40}, {Status.SLOW, 60}, {Status.BURN, 60}, {Status.RECOVERY_SEAL, 80},
        {Status.MAGIC_WEAKNESS, 100}, {Status.GRAVITY, 60}, {Status.MOVEMENT_LOCK, 60}, {Status.POISON, 80}, {Status.PHYSICAL_WEAKNESS, 100},
        {Status.BLINDNESS, 40}, {Status.POTION_SEAL, 100}, {Status.MANA_BURN, 1}, {Status.SLEEP, 40}, {Status.FEAR, 30}};
    private boolean wound(Player pl, Ctx c) {
        if (!(c.target() instanceof LivingEntity t) || !plugin.targets().hurt(pl, t, 0)) return false;
        vfx(pl, "gather", pl.getLocation(), null);
        double speed = Math.clamp(p.d("speed", 0.7), 0.1, 3) * QuidditchCombat.speedFactor(pl), range = p.d("range", 20); // #33
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline() || !t.isValid()) return;
            Location start = pl.getLocation().add(0, 1.35, 0).add(flat(pl).multiply(0.55));
            var visual = GameplayVisual.cast(pl.getUniqueId(), "claude:woundaccounting/projectile", start, chest(t).toVector().subtract(start.toVector()).normalize());
            plugin.ticker().add(new Ticker.Tick() {
                final Location pos = start.clone(); double travelled;
                @Override public boolean tick() {
                    if (!t.isValid() || t.isDead() || t.getWorld() != pos.getWorld() || travelled > range * 1.6) { if (visual != null) visual.close(); return false; }
                    Vector to = chest(t).toVector().subtract(pos.toVector());
                    if (to.length() <= speed + t.getWidth() / 2) {
                        if (visual != null) visual.close();
                        vfx(pl, "contact", chest(t), t.getUniqueId());
                        var pool = new ArrayList<>(Arrays.asList(WOUNDS)); Collections.shuffle(pool);
                        for (Object[] w : pool) if (plugin.status().apply(pl, t, (Status) w[0], (int) w[1], 0)) { vfx(pl, "absorb", t.getLocation(), t.getUniqueId()); break; }
                        return false;
                    }
                    Vector step = to.normalize().multiply(speed);
                    if (!pos.clone().add(step).getBlock().isPassable()) { if (visual != null) visual.close(); vfx(pl, "contact", pos, null); return false; }
                    pos.add(step); travelled += speed;
                    if (visual != null) visual.move(pos, step);
                    return true;
                }
                @Override public void stop() { if (visual != null) visual.close(); }
            });
        }, 8);
        return true;
    }

    // ================================================================== 194 유령 도구
    private boolean ghostTool(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 600), 1, 2400);
        BuffMech.apply(plugin, pl, "ghost_tool", List.<Object[]>of(new Object[]{Attribute.BLOCK_INTERACTION_RANGE, p.d("reach", 27), AttributeModifier.Operation.ADD_NUMBER}), ticks);
        st().ghost.put(pl.getUniqueId(), st().now + ticks);
        st().ghostSpell.put(pl.getUniqueId(), spell());
        return true;
    }

    // ================================================================== 195 공포의 종
    private boolean bell(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 60), 1, 400); double r = p.d("r", 8);
        Location at = pl.getLocation();
        for (LivingEntity t : plugin.targets().enemiesNear(pl, at, r, 32)) {
            if (t instanceof Player || plugin.targets().boss(t) || !(t instanceof Enemy) || !plugin.targets().hurt(pl, t, 0)) continue;
            double d = Math.hypot(t.getLocation().getX() - at.getX(), t.getLocation().getZ() - at.getZ());
            long delay = Math.round(20 * (0.30 + 0.85 * (1 - Math.sqrt(Math.max(0, 1 - Math.clamp((d / r - 0.07) / 0.93, 0, 1))))));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!t.isValid() || !pl.isOnline()) return;
                if (plugin.status().apply(pl, t, Status.FEAR, ticks, 0)) vfx(pl, "fear", t.getLocation(), t.getUniqueId());
            }, delay);
        }
        return true;
    }

    // ================================================================== 196 월식의 눈 (called by the chant's then= skill)
    private boolean eclipse(Player pl, Ctx c) {
        var st = st(); Location center = c.at().clone(); double r = p.d("r", 15), dmg = c.dmg(p); String element = p.s("element", "");
        int ticks = Math.clamp(p.i("ticks", 200), 1, 1200), shards = Math.clamp(p.i("shards", 6), 0, 16), contact = Math.clamp(p.i("contact", 20), 1, 200);
        double speed = Math.clamp(p.d("speed", 0.3), 0.05, 2), haste = p.d("haste", 0.3);
        vfx(pl, "eclipse", center, null);
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        var pos = new ArrayList<Location>(); var dir = new ArrayList<Vector>(); var vis = new ArrayList<GameplayVisual>();
        for (int i = 0; i < shards; i++) {
            double a = rnd.nextDouble(Math.PI * 2), d = Math.sqrt(rnd.nextDouble()) * r * 0.85;
            Location l = center.clone().add(Math.cos(a) * d, 1 + rnd.nextDouble(1.2), Math.sin(a) * d);
            Vector v = new Vector(rnd.nextDouble(-1, 1), 0, rnd.nextDouble(-1, 1)).normalize().multiply(speed);
            pos.add(l); dir.add(v);
            vis.add(GameplayVisual.cast(pl.getUniqueId(), "claude:eclipseeye/" + (i % 2 == 0 ? "shardMoon" : "shardFragment"), l, v));
        }
        var hitAt = new HashMap<UUID, Integer>();
        plugin.ticker().add(new Ticker.Tick() {
            int age; boolean hasted;
            @Override public boolean tick() {
                if (!pl.isOnline() || ++age > ticks) { end(); return false; }
                boolean in = pl.getWorld() == center.getWorld() && pl.getLocation().distanceSquared(center) <= r * r;
                if (in && !hasted) { hasted = true; BuffMech.apply(plugin, pl, "eclipse_eye", List.<Object[]>of(new Object[]{Attribute.MOVEMENT_SPEED, haste, AttributeModifier.Operation.ADD_SCALAR}), ticks - age + 1); vfx(pl, "haste", pl.getLocation(), null); }
                if (!in && hasted) { hasted = false; BuffMech.clear(pl, "eclipse_eye"); vfx(pl, "haste_end", pl.getLocation(), null); }
                if (in && age % 10 == 0) { Double max = Links.maxMana(pl); if (max != null) Links.add(pl, max); } // 마나 제한 해제: caster only, while inside
                for (int i = 0; i < pos.size(); i++) {
                    Location l = pos.get(i); Vector v = dir.get(i);
                    if (age % 20 == 0) v.rotateAroundY(rnd.nextDouble(-0.9, 0.9));
                    Location next = l.clone().add(v);
                    if (next.distanceSquared(center) > r * r * 0.8 || !next.getBlock().isPassable()) { v.multiply(-1); next = l.clone().add(v); }
                    l.setX(next.getX()); l.setY(next.getY()); l.setZ(next.getZ());
                    if (vis.get(i) != null) vis.get(i).move(l, v);
                    for (LivingEntity t : plugin.targets().enemiesNear(pl, l, 0.9, 4)) {
                        int last = hitAt.getOrDefault(t.getUniqueId(), -1000);
                        if (age - last < contact) continue;
                        hitAt.put(t.getUniqueId(), age);
                        if (plugin.targets().hurt(pl, t, dmg, element)) vfx(pl, "contact", chest(t), t.getUniqueId());
                    }
                }
                return true;
            }
            void end() {
                if (pl.isOnline()) vfx(pl, "eclipse_end", center, null);
                if (hasted) { hasted = false; BuffMech.clear(pl, "eclipse_eye"); if (pl.isOnline()) vfx(pl, "haste_end", pl.getLocation(), null); }
                for (int i = 0; i < vis.size(); i++) { if (vis.get(i) != null) vis.get(i).close(); if (pl.isOnline()) vfx(pl, "shardFade", pos.get(i), null); }
                vis.clear();
            }
            @Override public void stop() { if (!vis.isEmpty()) end(); }
        });
        return true;
    }

    // ================================================================== 203 빗자루 차원이동
    private boolean airbrake(Player pl) {
        if (!BroomLink.riding(pl)) { bar(pl, "빗자루를 타고 있을 때만 쓸 수 있어요."); return false; }
        Location from = pl.getLocation(); Vector dir = from.getDirection().normalize();
        double dist = p.d("dist", 6);
        var hit = pl.getWorld().rayTraceBlocks(pl.getEyeLocation(), dir, dist + 0.8, FluidCollisionMode.NEVER, true);
        double go = hit == null ? dist : Math.max(0, hit.getHitPosition().distance(pl.getEyeLocation().toVector()) - 0.8);
        Location to = from.clone().add(dir.clone().multiply(go));
        if (go < 0.5 || !to.isChunkLoaded() || !plugin.protection().at(to) || !to.getBlock().isPassable() || !to.clone().add(0, 1, 0).getBlock().isPassable()) { bar(pl, "앞쪽 공간이 막혀 있어요."); return false; }
        vfx(pl, "depart", from, null);
        var vel = pl.getVelocity();
        pl.teleport(to);
        pl.setVelocity(vel);
        vfx(pl, "arrive", to, null);
        return true;
    }

    // ================================================================== 212 불꽃 줄넘기
    private boolean skipring(Player pl, Ctx c) {
        Location center = pl.getLocation(); double r0 = p.d("r0", 1), r1 = p.d("r1", 7), dmg = c.dmg(p); int ticks = Math.clamp(p.i("ticks", 40), 1, 200);
        String element = p.s("element", ""); var hit = new HashSet<UUID>();
        vfx(pl, "ring", center, null);
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (!pl.isOnline() || ++age > ticks) return false;
                double rad = r0 + (r1 - r0) * Math.min(1, age / (double) ticks);
                for (LivingEntity t : plugin.targets().enemiesNear(pl, center, rad + 1.2, 32)) {
                    if (hit.contains(t.getUniqueId())) continue;
                    double d = Math.hypot(t.getLocation().getX() - center.getX(), t.getLocation().getZ() - center.getZ());
                    double half = t.getWidth() / 2;
                    if (d + half < rad - 0.25 || d - half > rad + 0.25) continue;            // inside / outside the ring band
                    if (t.getLocation().getY() > center.getY() + 0.4 || Math.abs(t.getLocation().getY() - center.getY()) > 1.0) continue; // jumped over / other level
                    hit.add(t.getUniqueId());
                    if (plugin.targets().hurt(pl, t, dmg, element)) vfx(pl, "hit", t.getLocation().add(0, 0.12, 0), t.getUniqueId());
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== 213 물방울 인계
    private boolean droplet(Player pl, Ctx c) {
        Location goal = c.at().clone(); double speed = Math.clamp(p.d("speed", 0.6), 0.1, 3) * QuidditchCombat.speedFactor(pl), r = p.d("r", 2.5), dmg = c.dmg(p); // #33
        int ticks = Math.clamp(p.i("ticks", 60), 1, 200); String element = p.s("element", "");
        vfx(pl, "form", pl.getLocation(), null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline()) return;
            Location start = pl.getLocation().add(0, 1.3, 0).add(flat(pl).multiply(0.62));
            Vector d0 = goal.toVector().subtract(start.toVector()); if (d0.lengthSquared() < 1e-4) d0 = pl.getLocation().getDirection();
            var visual = GameplayVisual.cast(pl.getUniqueId(), "claude:dropletrelay/projectile", start, d0.clone().normalize());
            plugin.ticker().add(new Ticker.Tick() {
                final Location pos = start.clone();
                @Override public boolean tick() {
                    if (!pl.isOnline() || pos.getWorld() != goal.getWorld()) { if (visual != null) visual.close(); return false; }
                    Vector to = goal.toVector().subtract(pos.toVector());
                    boolean arrived = to.length() <= speed || !pos.clone().add(to.clone().normalize().multiply(speed)).getBlock().isPassable();
                    if (!arrived) { pos.add(to.normalize().multiply(speed)); if (visual != null) visual.move(pos, to); return true; }
                    if (visual != null) visual.close();
                    vfx(pl, "arrive", pos, null);
                    for (LivingEntity t : plugin.targets().enemiesNear(pl, pos, r, 12)) {
                        if (!plugin.targets().hurt(pl, t, dmg, element)) continue;
                        t.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, ticks, 0, true, false, false));
                        vfx(pl, "capture", t.getLocation(), t.getUniqueId());
                        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (t.isValid() && pl.isOnline()) vfx(pl, "release", t.getLocation(), t.getUniqueId()); }, ticks + 5);
                    }
                    return false;
                }
                @Override public void stop() { if (visual != null) visual.close(); }
            });
        }, 5);
        return true;
    }

    // ================================================================== 214 접히는 수면
    private boolean tideplane(Player pl, Ctx c) {
        Location aim = c.at().clone();
        Vector toAim = aim.toVector().subtract(pl.getLocation().toVector()).setY(0);
        final Vector fwd = (toAim.lengthSquared() < 1e-4 ? flat(pl) : toAim).normalize();                                         // fold direction: away from the caster
        Vector right = new Vector(fwd.getZ(), 0, -fwd.getX());
        double half = p.d("len", 6) / 2, w = p.d("width", 2), dmg = c.dmg(p); String element = p.s("element", "");
        int delay = Math.clamp(p.i("delay", 20), 0, 100), turn = Math.clamp(p.i("turn", 26), 5, 100);
        Location hinge = aim.clone().add(fwd.clone().multiply(w / 2));   // sheet first lies on the caster's side of the hinge
        float yaw = (float) Math.toDegrees(Math.atan2(-fwd.getX(), fwd.getZ()));
        hinge.setYaw(yaw);
        vfx(pl, "sheet", hinge, null);
        var hit = new HashSet<UUID>();
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() {
                if (!pl.isOnline() || ++age > delay + turn) return false;
                if (age <= delay) return true;
                double theta = Math.PI * (age - delay) / (double) turn;
                for (LivingEntity t : plugin.targets().enemiesNear(pl, hinge, half + w + 1, 16)) {
                    if (hit.contains(t.getUniqueId())) continue;
                    Vector rel = t.getLocation().add(0, t.getHeight() * 0.5, 0).toVector().subtract(hinge.toVector());
                    double x = rel.dot(right), z = rel.dot(fwd), y = rel.getY();
                    if (Math.abs(x) > half + t.getWidth() / 2) continue;
                    double dist = Math.hypot(z, y), phi = Math.atan2(Math.max(0, y), -z);   // 0 = lying on the caster's side, pi = landed
                    if (dist > w + t.getWidth() / 2 || Math.abs(phi - theta) > 0.35) continue;
                    hit.add(t.getUniqueId());
                    if (plugin.targets().hurt(pl, t, dmg, element)) vfx(pl, "contact", chest(t), t.getUniqueId());
                }
                return true;
            }
        });
        return true;
    }

    // ================================================================== shared state + listeners
    static final class State implements Listener {
        private static State instance;
        static State get() { return instance; }
        static boolean forcing;
        static final NamespacedKey SUMMON_OWNER = new NamespacedKey("chacademiamagicmythic", "summon_owner");
        private static final NamespacedKey PLACED = new NamespacedKey("chacademiamagicmythic", "placed_by");
        final MagicMythicPlugin plugin;
        long now;
        final Map<UUID, Object[]> doubles = new HashMap<>();          // player -> {Location, until}
        final Map<UUID, Long> doubleCooldown = new HashMap<>();       // 168: cooldown after the double expired unused
        final Map<String, Object[]> seals = new HashMap<>();          // world:x:y:z -> {owner, until}
        final Map<UUID, Object[]> discord = new HashMap<>();          // mob -> {caster, until}
        final Map<UUID, Object[]> chains = new HashMap<>();           // mob -> {group, caster, until, ratio}
        final Map<UUID, List<UUID>> chainGroups = new HashMap<>();
        final Map<UUID, String> chainSpell = new HashMap<>();
        final Map<UUID, double[]> defer = new HashMap<>();            // player -> {until, stored, ratio, afterTicks, shards}
        final Map<UUID, String> deferSpell = new HashMap<>();
        final Map<UUID, UUID> summons = new HashMap<>();              // summon -> owner
        final Map<UUID, String> summonSpell = new HashMap<>();
        final Map<UUID, Long> ghost = new HashMap<>(), hushed = new HashMap<>(), thorns = new HashMap<>();
        final Map<UUID, String> ghostSpell = new HashMap<>();
        private final Map<UUID, Long> stepShown = new HashMap<>();
        private boolean relaying, paying;

        State(MagicMythicPlugin plugin) {
            this.plugin = plugin; instance = this;
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                now++;
                if ((now & 127) == 0) {
                    doubles.values().removeIf(v -> (long) v[1] < now);
                    seals.values().removeIf(v -> (long) v[1] < now);
                    discord.values().removeIf(v -> (long) v[1] < now);
                    ghost.values().removeIf(v -> v < now); hushed.values().removeIf(v -> v < now); thorns.values().removeIf(v -> v < now);
                }
                deferTick();
            }, 1, 1);
        }

        // ---- 170: who placed a door / chest (chunk PDC; only blocks placed while this plugin runs are known)
        static boolean sealable(Block b) { var t = b.getType(); return Tag.DOORS.isTagged(t) || t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL; }
        static Block lockBlock(Block b) {
            if (b.getBlockData() instanceof Door d && d.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) return b.getRelative(org.bukkit.block.BlockFace.DOWN);
            return b;
        }
        static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }
        private static NamespacedKey placedKey(Block b) { return new NamespacedKey("chacademiamagicmythic", "placed_" + (b.getX() & 15) + "_" + b.getY() + "_" + (b.getZ() & 15)); }
        static UUID placer(Block b) {
            String s = b.getChunk().getPersistentDataContainer().get(placedKey(b), PersistentDataType.STRING);
            try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void placed(org.bukkit.event.block.BlockPlaceEvent e) {
            Block b = lockBlock(e.getBlockPlaced());
            if (sealable(b)) b.getChunk().getPersistentDataContainer().set(placedKey(b), PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
            ghostShow(e.getPlayer(), e.getBlockPlaced(), "ghost_tool_place");
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void breakSealed(org.bukkit.event.block.BlockBreakEvent e) {
            if (seals.isEmpty()) return;
            Block b = lockBlock(e.getBlock());
            Object[] s = seals.get(key(b));
            if (s == null || (long) s[1] < now || e.getPlayer().getUniqueId().equals(s[0])) return;
            e.setCancelled(true);
            bar(e.getPlayer(), "봉인의 실로 묶여 있어 부술 수 없다.");
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void broken(org.bukkit.event.block.BlockBreakEvent e) {
            Block b = lockBlock(e.getBlock());
            if (sealable(b)) { b.getChunk().getPersistentDataContainer().remove(placedKey(b)); seals.remove(key(b)); }
            ghostShow(e.getPlayer(), e.getBlock(), "ghost_tool_break");
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void open(org.bukkit.event.player.PlayerInteractEvent e) {
            if (e.getClickedBlock() == null || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || seals.isEmpty()) return;
            Block b = lockBlock(e.getClickedBlock());
            Object[] s = seals.get(key(b));
            if (s == null && (b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST)) {
                for (var face : List.of(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
                    Block n = b.getRelative(face); if (n.getType() == b.getType() && (s = seals.get(key(n))) != null) break;
                }
            }
            if (s == null || (long) s[1] < now || e.getPlayer().getUniqueId().equals(s[0])) return;
            e.setCancelled(true);
            bar(e.getPlayer(), "봉인의 실로 묶여 있어 열 수 없다.");
        }

        // ---- 194: show the far block place / break
        private void ghostShow(Player pl, Block b, String system) {
            Long until = ghost.get(pl.getUniqueId());
            if (until == null || until < now || pl.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5)) <= 4.6) return;
            String spell = ghostSpell.getOrDefault(pl.getUniqueId(), "ghost_tool");
            Location at = b.getLocation().add(0.5, 0.5, 0.5);
            Links.vfx(pl, spell, "external:" + system, at, null);
            if (system.endsWith("break")) {
                var tool = pl.getInventory().getItemInMainHand().getType();
                String kind = Tag.ITEMS_PICKAXES.isTagged(tool) ? "pickaxe" : Tag.ITEMS_AXES.isTagged(tool) ? "axe" : Tag.ITEMS_SHOVELS.isTagged(tool) ? "shovel" : null;
                if (kind != null) Links.vfx(pl, spell, "external:ghost_tool_swing_" + kind, at, null);
            }
        }

        // ---- 179 / 185: forced targets stay
        @EventHandler(ignoreCancelled = true)
        public void target(org.bukkit.event.entity.EntityTargetEvent e) {
            if (forcing) return;
            UUID id = e.getEntity().getUniqueId();
            if (discord.containsKey(id)) { if (e.getTarget() instanceof Player) e.setCancelled(true); return; }
            UUID owner = summons.get(id);
            if (owner == null || e.getTarget() == null) return;
            Player o = Bukkit.getPlayer(owner);
            if (o == null || e.getTarget().getUniqueId().equals(owner) || summons.containsKey(e.getTarget().getUniqueId()) || !plugin.targets().enemy(o, e.getTarget())) e.setCancelled(true);
        }
        // ---- 185: phantom hits are the owner's (protection) + VFX; 180 relay; 181 defer
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void damaged(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
            UUID owner = summons.get(e.getDamager().getUniqueId());
            if (owner != null) {
                Player o = Bukkit.getPlayer(owner);
                if (o == null || !(e.getEntity() instanceof LivingEntity t) || !plugin.targets().enemy(o, t) || summons.containsKey(t.getUniqueId())) { e.setCancelled(true); return; }
                Links.vfx(o, summonSpell.getOrDefault(e.getDamager().getUniqueId(), "night_lullaby"), "external:hit", chest(t), null);
            }
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void relay(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
            if (relaying || chains.isEmpty() || !(e.getEntity() instanceof LivingEntity hitMob)) return;
            Object[] link = chains.get(hitMob.getUniqueId());
            if (link == null || (long) link[2] < now) return;
            Player caster = Bukkit.getPlayer((UUID) link[1]);
            double share = e.getFinalDamage() * (double) link[3];
            if (caster == null || share <= 0) return;
            relaying = true;
            try {
                for (UUID other : chainGroups.getOrDefault((UUID) link[0], List.of())) {
                    if (other.equals(hitMob.getUniqueId()) || !(Bukkit.getEntity(other) instanceof LivingEntity t) || t.isDead()) continue;
                    if (plugin.targets().hurt(caster, t, share, "dark")) Links.vfx(caster, chainSpell.getOrDefault((UUID) link[0], "night_chain"), "external:relay", chest(hitMob), t.getUniqueId());
                }
            } finally { relaying = false; }
        }
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void deferred(org.bukkit.event.entity.EntityDamageEvent e) {
            if (!(e.getEntity() instanceof Player pl)) return;
            double[] d = defer.get(pl.getUniqueId());
            if (d == null || d[0] < now || paying) return;
            double later = e.getDamage() * d[2];
            if (later <= 0) return;
            e.setDamage(e.getDamage() - later);
            d[1] += later;
            int slot = (int) (d[4] % 6) + 1; d[4]++;
            Links.vfx(pl, deferSpell.getOrDefault(pl.getUniqueId(), "pain_deferral"), "external:shard_slot" + slot, pl.getLocation(), null);
        }
        private void deferTick() {
            if (defer.isEmpty()) return;
            var it = defer.entrySet().iterator();
            while (it.hasNext()) {
                var en = it.next(); double[] d = en.getValue();
                if (d[0] >= now) continue;
                long after = (long) d[3], since = now - (long) d[0];
                Player pl = Bukkit.getPlayer(en.getKey());
                if (pl == null || since > after || d[1] <= 0) { it.remove(); continue; }
                if (since % 20 == 0) {   // one payment per second over `after` ticks
                    int left = (int) Math.max(1, (after - since) / 20 + 1);
                    double pay = d[1] / left; d[1] -= pay;
                    paying = true; try { pl.damage(pay); } finally { paying = false; }
                    int slot = (int) ((d[4] - left) % 6 + 6) % 6 + 1;
                    Links.vfx(pl, deferSpell.getOrDefault(pl.getUniqueId(), "pain_deferral"), d[4] >= left ? "external:absorb_slot" + slot : "external:pulse", pl.getLocation(), null);
                }
            }
        }

        // ---- 210 고요한 밑창: walking/running vibrations
        @EventHandler(ignoreCancelled = true)
        public void vibration(org.bukkit.event.block.BlockReceiveGameEvent e) {
            if (hushed.isEmpty() || !(e.getEntity() instanceof Player pl) || e.getEvent() != GameEvent.STEP) return;
            Long until = hushed.get(pl.getUniqueId());
            if (until == null || until < now) return;
            e.setCancelled(true);
        }
        @EventHandler(ignoreCancelled = true)
        public void move(org.bukkit.event.player.PlayerMoveEvent e) {
            if (hushed.isEmpty() && thorns.isEmpty()) return;
            Player pl = e.getPlayer();
            Long h = hushed.get(pl.getUniqueId());
            if (h != null && h >= now && pl.isOnGround() && e.getFrom().distanceSquared(e.getTo()) > 0.04) {
                Long last = stepShown.get(pl.getUniqueId());
                if (last == null || now - last >= 7) { stepShown.put(pl.getUniqueId(), now); Links.vfx(pl, "hushed_soles", "external:step", pl.getLocation(), null); }
            }
            Long t = thorns.get(pl.getUniqueId());
            if (t != null && t >= now) {
                Block in = e.getTo().getBlock();
                if (in.getType() == Material.SWEET_BERRY_BUSH) {
                    Long last = stepShown.get(pl.getUniqueId());
                    if (last == null || now - last >= 8) { stepShown.put(pl.getUniqueId(), now); Links.vfx(pl, "thorn_manners", "external:contact", in.getLocation().add(0.5, 0.6, 0.5), null); }
                }
            }
        }
        // ---- 211 가시의 예절: no berry-bush damage / slowdown
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void berryHurt(org.bukkit.event.entity.EntityDamageByBlockEvent e) {
            if (thorns.isEmpty() || !(e.getEntity() instanceof Player pl) || e.getDamager() == null || e.getDamager().getType() != Material.SWEET_BERRY_BUSH) return;
            Long t = thorns.get(pl.getUniqueId());
            if (t != null && t >= now) e.setCancelled(true);
        }
        @EventHandler(ignoreCancelled = true)
        public void berrySlow(io.papermc.paper.event.entity.EntityInsideBlockEvent e) {
            if (thorns.isEmpty() || !(e.getEntity() instanceof Player pl) || e.getBlock().getType() != Material.SWEET_BERRY_BUSH) return;
            Long t = thorns.get(pl.getUniqueId());
            if (t != null && t >= now) e.setCancelled(true);
        }

        @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            doubles.remove(id); defer.remove(id); deferSpell.remove(id); ghost.remove(id); hushed.remove(id); thorns.remove(id); stepShown.remove(id);
            for (var en : new ArrayList<>(summons.entrySet())) if (en.getValue().equals(id) && Bukkit.getEntity(en.getKey()) != null) { Bukkit.getEntity(en.getKey()).remove(); summons.remove(en.getKey()); summonSpell.remove(en.getKey()); }
        }
        @EventHandler public void summonDeath(org.bukkit.event.entity.EntityDeathEvent e) {
            if (summons.remove(e.getEntity().getUniqueId()) != null) { e.getDrops().clear(); e.setDroppedExp(0); summonSpell.remove(e.getEntity().getUniqueId()); }
            chains.remove(e.getEntity().getUniqueId());
        }

        void closeAll() {
            for (UUID id : new ArrayList<>(summons.keySet())) { Entity s = Bukkit.getEntity(id); if (s != null) s.remove(); }
            summons.clear();
            for (UUID id : new ArrayList<>(discord.keySet())) if (Bukkit.getEntity(id) instanceof Mob m) m.setTarget(null);
            discord.clear();
        }
    }
}
