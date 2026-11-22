package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Husk;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 몬스터 습격 (100개 장난): 대상 주변에 할로윈 몬스터 몇 마리를 불러 그 사람만 노리게 한다.
 * 모델 = blueprints 의 dmob_* (할로윈 펫 묶음을 몬스터용으로 바꾼 것, tools/modelgen/NwMobs.java).
 * 받침 몹은 보이지 않는 허스크. 원래 AI 를 전부 지우고 여기서 움직임·근접 공격·스킬을 직접 돌린다.
 *  - 원거리: 거리를 벌리며 쏜다 (호박 폭탄·독사과·불꽃탄·포자탄·깃털·팝콘·저주 표식·뿌리 묶기)
 *  - 근접: 붙어서 때리고 돌진·내려찍기·도약·혀/붕대 당기기
 *  - 암살자: 사라졌다가 등 뒤에 나타나 찌르고 빠진다, 또는 몸을 관통하며 베고 지나간다
 * 모든 스킬은 빨간 예고(장판·선)가 먼저 보여서 피할 수 있다. 피해는 대상 한 사람에게만 들어간다.
 * 시간이 끝나거나 대상이 죽거나 나가면 전부 연기와 함께 사라진다. 잡아도 떨어뜨리는 것은 없다.
 */
final class Monsters implements Listener {
    enum Role { RANGED, MELEE, ASSASSIN }
    enum Skill { LOB, BOLT, VOLLEY, MARK, ROOT, CHARGE, DASH, SLAM, LEAP, BLINK, PULL }

    /** 몬스터 종류: 모델 id(dmob_ 뒤) · 이름 · 역할 · 스킬 · 체력 · 이동 속도 · 모델 배율 · 스킬 간격(틱) · 대표 색 2개 */
    record Type(String id, String name, Role role, Skill skill, double hp, double speed, double scale, int cooldown, Color c1, Color c2) { }

    static final List<Type> TYPES = List.of(
            new Type("pumpkin", "호박 폭탄병", Role.RANGED, Skill.LOB, 18, 0.24, 1.5, 90, rgb(255, 140, 20), rgb(255, 220, 80)),
            new Type("apple", "독사과", Role.RANGED, Skill.LOB, 16, 0.25, 1.4, 90, rgb(120, 220, 60), rgb(200, 40, 40)),
            new Type("candle", "촛불 유령", Role.RANGED, Skill.BOLT, 16, 0.24, 1.5, 70, rgb(255, 170, 40), rgb(255, 240, 150)),
            new Type("mushroom", "포자 버섯", Role.RANGED, Skill.BOLT, 18, 0.22, 1.5, 80, rgb(170, 80, 220), rgb(120, 220, 90)),
            new Type("raven", "보랏빛 까마귀", Role.RANGED, Skill.VOLLEY, 14, 0.30, 1.4, 70, rgb(110, 70, 190), rgb(200, 190, 230)),
            new Type("corn", "팝콘 옥수수", Role.RANGED, Skill.VOLLEY, 18, 0.24, 1.5, 90, rgb(255, 245, 200), rgb(255, 200, 40)),
            new Type("scarecrow", "저주 허수아비", Role.RANGED, Skill.MARK, 22, 0.22, 1.6, 100, rgb(90, 40, 140), rgb(120, 255, 230)),
            new Type("leashy", "숲의 정령", Role.RANGED, Skill.ROOT, 22, 0.23, 1.5, 100, rgb(70, 170, 60), rgb(120, 80, 40)),
            new Type("beatle", "푸른 풍뎅이", Role.MELEE, Skill.CHARGE, 26, 0.27, 1.5, 90, rgb(110, 170, 230), rgb(240, 130, 200)),
            new Type("nut", "도토리 굴렁쇠", Role.MELEE, Skill.CHARGE, 22, 0.28, 1.4, 80, rgb(160, 105, 50), rgb(225, 195, 145)),
            new Type("fire", "불꽃 수레", Role.MELEE, Skill.CHARGE, 22, 0.30, 1.4, 80, rgb(255, 90, 20), rgb(255, 200, 60)),
            new Type("statue", "나무 토템", Role.MELEE, Skill.SLAM, 40, 0.20, 1.6, 100, rgb(120, 85, 55), rgb(140, 255, 90)),
            new Type("slime", "무덤 슬라임", Role.MELEE, Skill.SLAM, 20, 0.27, 1.5, 70, rgb(90, 210, 90), rgb(50, 130, 50)),
            new Type("zombie", "굶주린 좀비", Role.MELEE, Skill.LEAP, 26, 0.26, 1.5, 80, rgb(100, 170, 80), rgb(120, 90, 60)),
            new Type("frog", "혀 개구리", Role.MELEE, Skill.PULL, 22, 0.26, 1.5, 90, rgb(255, 120, 160), rgb(90, 180, 80)),
            new Type("mummy", "붕대 미라", Role.MELEE, Skill.PULL, 24, 0.24, 1.5, 90, rgb(235, 220, 175), rgb(140, 120, 80)),
            new Type("cat", "검은 고양이", Role.ASSASSIN, Skill.BLINK, 16, 0.33, 1.4, 80, rgb(35, 20, 45), rgb(175, 70, 255)),
            new Type("rabbit", "칼날 토끼", Role.ASSASSIN, Skill.BLINK, 14, 0.35, 1.4, 60, rgb(70, 55, 60), rgb(230, 40, 60)),
            new Type("clown", "장난꾸러기 광대", Role.ASSASSIN, Skill.BLINK, 18, 0.30, 1.5, 80, rgb(255, 60, 60), rgb(60, 140, 255)),
            new Type("fox", "그림자 여우", Role.ASSASSIN, Skill.DASH, 16, 0.34, 1.4, 70, rgb(255, 130, 30), rgb(255, 255, 255)));

    private static final Color WARN = rgb(255, 35, 35);

    final class Mob {
        final Type type;
        final Husk base;
        final Models.Prop prop;
        final UUID target;
        int busy, skillCd, meleeCd, repath;
        int side = rnd().nextBoolean() ? 1 : -1;

        Mob(Type type, Husk base, Models.Prop prop, UUID target) {
            this.type = type; this.base = base; this.prop = prop; this.target = target;
            this.skillCd = 40 + rnd().nextInt(40);
        }

        Location eye() { return base.getLocation().add(0, 0.9 * type.scale * 0.8, 0); }
    }

    private final DiamondPlugin pl;
    private final Map<UUID, Mob> mobs = new HashMap<>();       // 받침 몹 UUID → 몬스터

    Monsters(DiamondPlugin pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::onTick, 1L, 1L);
    }

    static Color rgb(int r, int g, int b) { return Color.fromRGB(r, g, b); }
    private static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    static Type type(String idOrName) {
        for (Type t : TYPES) if (t.id.equalsIgnoreCase(idOrName) || t.name.equals(idOrName) || t.name.replace(' ', '_').equals(idOrName)) return t;
        return null;
    }

    static List<String> typeNames() {
        List<String> out = new ArrayList<>();
        for (Type t : TYPES) out.add(t.name.replace(' ', '_'));
        return out;
    }

    // ── 부르기·치우기 ──────────────────────────────────────
    /** 무작위로 몇 마리 (설정 effects.monster-min ~ monster-max, 종류는 겹치지 않게) */
    void summon(Player victim) {
        int min = Math.max(1, pl.getConfig().getInt("effects.monster-min", 3));
        int max = Math.max(min, pl.getConfig().getInt("effects.monster-max", 5));
        int n = min + rnd().nextInt(max - min + 1);
        List<Type> pool = new ArrayList<>(TYPES);
        Collections.shuffle(pool);
        for (int i = 0; i < n && i < pool.size(); i++) {
            Type t = pool.get(i);
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (victim.isOnline() && !victim.isDead()) spawn(victim, t); }, i * 6L);
        }
        victim.playSound(victim.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.8f);
    }

    /** 한 종류를 한 마리 부른다 (시험 명령에서도 씀) */
    void spawn(Player victim, Type t) {
        Location at = spot(victim.getLocation(), 5, 9);
        World w = at.getWorld();
        Husk h = w.spawn(at, Husk.class, z -> {
            z.setAdult();
            z.setSilent(true);
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.setCanPickupItems(false);
            z.getEquipment().clear();
            z.customName(Component.text(t.name));
            z.setCustomNameVisible(false);
            attr(z, Attribute.MAX_HEALTH, t.hp);
            z.setHealth(t.hp);
            attr(z, Attribute.MOVEMENT_SPEED, t.speed);
            attr(z, Attribute.FOLLOW_RANGE, 48);
            attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
            attr(z, Attribute.KNOCKBACK_RESISTANCE, t.id.equals("statue") ? 0.8 : 0.15);
            attr(z, Attribute.STEP_HEIGHT, 1.0);
        });
        try { Bukkit.getMobGoals().removeAllGoals(h); } catch (Throwable ignored) { }
        Models.Prop prop = pl.models.attach(h, "dmob_" + t.id, null);
        prop.scale(t.scale);
        prop.once("appear");
        Mob m = new Mob(t, h, prop, victim.getUniqueId());
        m.busy = 8;
        mobs.put(h.getUniqueId(), m);
        Location c = at.clone().add(0, 0.8, 0);
        w.spawnParticle(Particle.LARGE_SMOKE, c, 18, 0.35, 0.5, 0.35, 0.02);
        w.spawnParticle(Particle.DUST, c, 30, 0.45, 0.6, 0.45, 0, new Particle.DustOptions(t.c1, 1.4f));
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 20, 0.3, 0.5, 0.3, 0.05);
        w.playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.6f, 1.6f);
    }

    /** 대상 한 사람의 몬스터를 전부 연기와 함께 치운다 */
    void clear(Player victim) { clear(victim.getUniqueId()); }

    private void clear(UUID victim) {
        for (Mob m : new ArrayList<>(mobs.values())) if (m.target.equals(victim)) vanish(m);
    }

    int count(UUID victim) {
        int n = 0;
        for (Mob m : mobs.values()) if (m.target.equals(victim)) n++;
        return n;
    }

    private void vanish(Mob m) {
        mobs.remove(m.base.getUniqueId());
        if (m.base.isValid()) {
            Location c = m.base.getLocation().add(0, 0.7, 0);
            c.getWorld().spawnParticle(Particle.POOF, c, 20, 0.3, 0.4, 0.3, 0.03);
            c.getWorld().spawnParticle(Particle.DUST, c, 20, 0.35, 0.5, 0.35, 0, new Particle.DustOptions(m.type.c1, 1.2f));
            c.getWorld().playSound(c, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.4f);
        }
        m.prop.remove();
    }

    /** 서버가 꺼질 때 */
    void shutdown() {
        for (Mob m : new ArrayList<>(mobs.values())) { mobs.remove(m.base.getUniqueId()); m.prop.remove(); }
    }

    private void lastOneGone(UUID victim) {
        if (count(victim) > 0) return;
        Player p = Bukkit.getPlayer(victim);
        if (p != null) pl.effects.end(p, Effects.MONSTERS);
    }

    // ── 매 틱: 움직임·근접 공격·스킬 고르기 ──────────────────
    private void onTick() {
        for (Mob m : new ArrayList<>(mobs.values())) {
            if (!m.base.isValid()) { mobs.remove(m.base.getUniqueId()); m.prop.remove(); lastOneGone(m.target); continue; }
            Player t = Bukkit.getPlayer(m.target);
            if (t == null || t.isDead() || !t.getWorld().equals(m.base.getWorld())) { vanish(m); lastOneGone(m.target); continue; }
            double d = m.base.getLocation().distance(t.getLocation());
            if (d > 36) {                                       // 너무 멀어졌으면 다시 근처로
                Location near = spot(t.getLocation(), 6, 9);
                m.base.teleport(near);
                m.prop.once("appear");
                near.getWorld().spawnParticle(Particle.LARGE_SMOKE, near.clone().add(0, 0.7, 0), 12, 0.3, 0.4, 0.3, 0.02);
                continue;
            }
            if (m.busy > 0) { m.busy--; continue; }
            m.base.lookAt(t);
            if (m.meleeCd > 0) m.meleeCd--;
            if (m.skillCd > 0) m.skillCd--;

            if (m.skillCd <= 0 && canSkill(m, t, d)) {
                m.skillCd = m.type.cooldown + rnd().nextInt(30);
                m.base.getPathfinder().stopPathfinding();
                skill(m, t);
                continue;
            }
            if (d < 1.9 + m.type.scale * 0.2 && m.meleeCd <= 0) { melee(m, t); continue; }
            if (--m.repath <= 0) { move(m, t, d); m.repath = m.type.role == Role.RANGED ? 12 : 6; }
        }
    }

    private boolean canSkill(Mob m, Player t, double d) {
        boolean see = m.base.hasLineOfSight(t);
        return switch (m.type.skill) {
            case LOB, MARK, ROOT -> d >= 3 && d <= 16;
            case BOLT, VOLLEY -> see && d >= 3 && d <= 16;
            case CHARGE -> see && d >= 4 && d <= 14;
            case DASH -> see && d >= 3 && d <= 12;
            case SLAM -> d <= 4.5;
            case LEAP -> d >= 3 && d <= 9;
            case BLINK -> d <= 16;
            case PULL -> see && d >= 4 && d <= 12;
        };
    }

    private void move(Mob m, Player t, double d) {
        if (m.type.role != Role.RANGED) { m.base.getPathfinder().moveTo(t, 1.0); return; }
        Location me = m.base.getLocation(), tl = t.getLocation();
        Vector away = me.toVector().subtract(tl.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        away.normalize();
        Location goal;
        if (d < 6) goal = tl.clone().add(away.multiply(9));
        else if (d > 12) { m.base.getPathfinder().moveTo(t, 1.0); return; }
        else {                                                  // 적당한 거리: 옆으로 돌며 견제
            if (rnd().nextInt(4) == 0) m.side = -m.side;
            goal = tl.clone().add(away.rotateAroundY(Math.toRadians(30 * m.side)).multiply(d));
        }
        m.base.getPathfinder().moveTo(ground(goal), 1.0);
    }

    private void melee(Mob m, Player t) {
        m.meleeCd = 22;
        m.busy = 6;
        m.prop.once("attack");
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!alive(m) || t.isDead() || m.base.getLocation().distance(t.getLocation()) > 2.6 + m.type.scale * 0.2) return;
            double dmg = m.type.id.equals("statue") ? 5 : m.type.role == Role.RANGED ? 2 : 3;
            hurt(m, t, dmg);
            Location c = t.getLocation().add(0, 1, 0);
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c, 1, 0, 0, 0, 0);
            c.getWorld().spawnParticle(Particle.DUST, c, 8, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(m.type.c1, 1f));
            c.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.2f);
        }, 5L);
    }

    private boolean alive(Mob m) { return m.base.isValid() && mobs.containsKey(m.base.getUniqueId()); }

    private void hurt(Mob m, Player t, double dmg) {
        if (t.isDead() || !t.isOnline()) return;
        t.damage(dmg, m.base);
    }

    private void skill(Mob m, Player t) {
        switch (m.type.skill) {
            case LOB -> lob(m, t);
            case BOLT -> bolt(m, t);
            case VOLLEY -> volley(m, t);
            case MARK -> mark(m, t);
            case ROOT -> root(m, t);
            case CHARGE -> charge(m, t, 14, 0.85, 14, false);
            case DASH -> charge(m, t, 10, 1.25, 10, true);
            case SLAM -> slam(m, t);
            case LEAP -> leap(m, t);
            case BLINK -> blink(m, t);
            case PULL -> pull(m, t);
        }
    }

    /** n 틱 동안 매 틱 step(i) 를 부른다. 몬스터가 죽으면 멈춘다. step 이 false 를 돌려주면 그 자리에서 끝 */
    private void run(Mob m, int ticks, java.util.function.IntPredicate step) {
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if ((m != null && !alive(m)) || i > ticks || !step.test(i)) { cancel(); return; }
                i++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    // ── 원거리 스킬 ─────────────────────────────────────────
    /** 호박 폭탄·독사과: 포물선으로 던진다. 떨어질 자리에 빨간 장판이 먼저 깔린다 */
    private void lob(Mob m, Player t) {
        boolean pumpkin = m.type.id.equals("pumpkin");
        m.busy = 18;
        m.prop.once("cast");
        run(m, 9, i -> {
            if (i < 9) return true;
            Location from = m.eye(), to = ground(t.getLocation());
            double r = pumpkin ? 3.0 : 2.8;
            throwArc(from, to, 24, new ItemStack(pumpkin ? Material.JACK_O_LANTERN : Material.APPLE), r,
                    p -> {
                        if (pumpkin) { p.getWorld().spawnParticle(Particle.FLAME, p, 2, 0.05, 0.05, 0.05, 0.01); p.getWorld().spawnParticle(Particle.SMOKE, p, 1, 0, 0, 0, 0); }
                        else p.getWorld().spawnParticle(Particle.DUST, p, 3, 0.08, 0.08, 0.08, 0, new Particle.DustOptions(m.type.c1, 1.1f));
                    },
                    land -> {
                        World w = land.getWorld();
                        if (pumpkin) {
                            w.spawnParticle(Particle.EXPLOSION, land.clone().add(0, 0.5, 0), 4, 0.8, 0.3, 0.8, 0);
                            w.spawnParticle(Particle.FLAME, land.clone().add(0, 0.3, 0), 60, 0.3, 0.2, 0.3, 0.18);
                            w.spawnParticle(Particle.LAVA, land, 10, 0.6, 0.2, 0.6, 0);
                            w.spawnParticle(Particle.DUST, land.clone().add(0, 0.4, 0), 40, 1.2, 0.4, 1.2, 0, new Particle.DustOptions(m.type.c1, 1.6f));
                            w.playSound(land, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
                            if (within(t, land, r)) { hurt(m, t, 5); push(t, land, 0.9, 0.5); }
                        } else {
                            w.playSound(land, Sound.ENTITY_SPLASH_POTION_BREAK, 1f, 0.8f);
                            w.spawnParticle(Particle.DUST, land.clone().add(0, 0.4, 0), 50, 1.0, 0.3, 1.0, 0, new Particle.DustOptions(m.type.c1, 1.5f));
                            if (within(t, land, r)) { hurt(m, t, 2); t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0)); }
                            cloud(m, t, land, r, 60, m.type.c1, m.type.c2);
                        }
                    });
            t.getWorld().playSound(from, Sound.ENTITY_WITCH_THROW, 1f, 0.8f);
            return false;
        });
    }

    /** 촛불 유령: 불꽃탄 3발 연사 / 포자 버섯: 터지면 독 포자 구름이 남는 포자탄 */
    private void bolt(Mob m, Player t) {
        boolean candle = m.type.id.equals("candle");
        m.busy = candle ? 22 : 16;
        m.prop.once("cast");
        run(m, 20, i -> {
            if (candle ? (i == 9 || i == 14 || i == 19) : i == 9) {
                Location from = m.eye();
                Vector dir = t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).normalize();
                if (candle) {
                    from.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.3f);
                    shoot(t, from, dir, 0.6, 45, 0.06,
                            p -> { p.getWorld().spawnParticle(Particle.FLAME, p, 3, 0.06, 0.06, 0.06, 0.005); p.getWorld().spawnParticle(Particle.DUST, p, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(m.type.c2, 1.2f)); },
                            (p, hit) -> {
                                p.getWorld().spawnParticle(Particle.FLAME, p, 20, 0.15, 0.15, 0.15, 0.08);
                                p.getWorld().spawnParticle(Particle.SMOKE, p, 6, 0.1, 0.1, 0.1, 0.02);
                                p.getWorld().playSound(p, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
                                if (hit) { hurt(m, t, 3); t.setFireTicks(Math.max(t.getFireTicks(), 50)); }
                            });
                } else {
                    from.getWorld().playSound(from, Sound.BLOCK_SPORE_BLOSSOM_PLACE, 1f, 0.6f);
                    shoot(t, from, dir, 0.5, 50, 0.08,
                            p -> { p.getWorld().spawnParticle(Particle.DUST, p, 4, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(m.type.c1, 1.4f)); p.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, p, 1, 0.1, 0.1, 0.1, 0); },
                            (p, hit) -> {
                                p.getWorld().playSound(p, Sound.BLOCK_MOSS_BREAK, 1f, 0.6f);
                                p.getWorld().spawnParticle(Particle.DUST, p, 40, 0.8, 0.5, 0.8, 0, new Particle.DustOptions(m.type.c2, 1.5f));
                                if (hit || within(t, p, 2.5)) { hurt(m, t, 2); t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0)); }
                                cloud(m, t, ground(p), 2.5, 50, m.type.c1, m.type.c2);
                            });
                }
            }
            return true;
        });
    }

    /** 그림자 까마귀: 깃털 5개를 부채꼴로 / 팝콘 옥수수: 팝콘 6알을 대상 주변에 흩뿌려 터뜨린다 */
    private void volley(Mob m, Player t) {
        boolean raven = m.type.id.equals("raven");
        m.busy = 18;
        m.prop.once("cast");
        run(m, 9, i -> {
            if (i < 9) return true;
            Location from = m.eye();
            if (raven) {
                from.getWorld().playSound(from, Sound.ENTITY_PHANTOM_FLAP, 1f, 1.4f);
                Vector base = t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).normalize();
                for (int a = -24; a <= 24; a += 12) {
                    shoot(t, from.clone(), base.clone().rotateAroundY(Math.toRadians(a)), 0.85, 28, 0,
                            p -> { p.getWorld().spawnParticle(Particle.DUST, p, 2, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(m.type.c1, 1.0f)); if (rnd().nextInt(3) == 0) p.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(m.type.c2, 0.7f)); },
                            (p, hit) -> {
                                p.getWorld().spawnParticle(Particle.DUST, p, 8, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(m.type.c2, 0.9f));
                                if (hit) hurt(m, t, 2);
                            });
                }
            } else {
                from.getWorld().playSound(from, Sound.BLOCK_LAVA_POP, 1f, 1.4f);
                for (int k = 0; k < 6; k++) {
                    Location to = ground(k == 0 ? t.getLocation() : t.getLocation().add(rnd().nextDouble(-3.2, 3.2), 0, rnd().nextDouble(-3.2, 3.2)));
                    throwArc(from.clone(), to, 18 + rnd().nextInt(10), null, 1.3,
                            p -> p.getWorld().spawnParticle(Particle.DUST, p, 3, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(m.type.c1, 1.3f)),
                            land -> {
                                land.getWorld().spawnParticle(Particle.DUST, land.clone().add(0, 0.3, 0), 25, 0.5, 0.3, 0.5, 0, new Particle.DustOptions(m.type.c1, 1.5f));
                                land.getWorld().spawnParticle(Particle.DUST, land.clone().add(0, 0.3, 0), 10, 0.4, 0.3, 0.4, 0, new Particle.DustOptions(m.type.c2, 1.2f));
                                land.getWorld().spawnParticle(Particle.FIREWORK, land.clone().add(0, 0.3, 0), 6, 0.1, 0.1, 0.1, 0.12);
                                land.getWorld().playSound(land, Sound.BLOCK_LAVA_POP, 1.2f, 1.8f);
                                if (within(t, land, 1.4)) hurt(m, t, 2);
                            });
                }
            }
            return false;
        });
    }

    /** 저주 허수아비: 대상 발밑에 보라색 표식, 1초 뒤 영혼이 솟구친다 (벗어나면 무사) */
    private void mark(Mob m, Player t) {
        m.busy = 14;
        m.prop.once("cast");
        final Location[] c = new Location[1];
        final double r = 2.4;
        run(m, 30, i -> {
            if (i == 8) { c[0] = ground(t.getLocation()); c[0].getWorld().playSound(c[0], Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1f, 0.7f); }
            if (c[0] == null) return true;
            int k = i - 8;
            if (k < 20) {
                if (k % 2 == 0) { ring(c[0], r, 40, m.type.c1, 1.3f); ring(c[0], r * k / 20.0, 24, WARN, 1.0f); }
                if (k % 4 == 0) c[0].getWorld().spawnParticle(Particle.SOUL, c[0].clone().add(0, 0.2, 0), 3, r * 0.5, 0, r * 0.5, 0.01);
                return true;
            }
            World w = c[0].getWorld();
            for (double y = 0; y < 3; y += 0.25) w.spawnParticle(Particle.SCULK_SOUL, c[0].clone().add(0, y, 0), 3, r * 0.35, 0.05, r * 0.35, 0.01);
            w.spawnParticle(Particle.LARGE_SMOKE, c[0].clone().add(0, 0.5, 0), 25, r * 0.4, 0.4, r * 0.4, 0.03);
            w.spawnParticle(Particle.DUST, c[0].clone().add(0, 1, 0), 40, r * 0.5, 1, r * 0.5, 0, new Particle.DustOptions(m.type.c2, 1.3f));
            w.playSound(c[0], Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.4f);
            if (within(t, c[0], r)) {
                hurt(m, t, 4);
                t.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0));
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            }
            return false;
        });
    }

    /** 숲의 정령: 대상 발밑에 초록 장판, 0.8초 뒤 뿌리가 솟아 묶는다 */
    private void root(Mob m, Player t) {
        m.busy = 14;
        m.prop.once("cast");
        final Location[] c = new Location[1];
        final double r = 2.0;
        BlockData rootBlock = Material.ROOTED_DIRT.createBlockData(), leaf = Material.AZALEA_LEAVES.createBlockData();
        run(m, 26, i -> {
            if (i == 8) { c[0] = ground(t.getLocation()); c[0].getWorld().playSound(c[0], Sound.BLOCK_ROOTS_PLACE, 1f, 0.6f); }
            if (c[0] == null) return true;
            int k = i - 8;
            if (k < 16) {
                if (k % 2 == 0) { ring(c[0], r, 36, m.type.c1, 1.3f); ring(c[0], r * k / 16.0, 22, WARN, 1.0f); }
                return true;
            }
            World w = c[0].getWorld();
            for (int n = 0; n < 9; n++) {
                Location p = c[0].clone().add(rnd().nextDouble(-r, r) * 0.8, 0, rnd().nextDouble(-r, r) * 0.8);
                for (double y = 0; y < 1.6; y += 0.2) w.spawnParticle(Particle.BLOCK, p.clone().add(0, y, 0), 2, 0.05, 0.05, 0.05, 0, y < 1 ? rootBlock : leaf);
            }
            w.spawnParticle(Particle.DUST, c[0].clone().add(0, 0.6, 0), 40, r * 0.5, 0.5, r * 0.5, 0, new Particle.DustOptions(m.type.c1, 1.4f));
            w.spawnParticle(Particle.HAPPY_VILLAGER, c[0].clone().add(0, 0.5, 0), 12, r * 0.5, 0.4, r * 0.5, 0);
            w.playSound(c[0], Sound.BLOCK_ROOTED_DIRT_BREAK, 1.2f, 0.6f);
            w.playSound(c[0], Sound.BLOCK_AZALEA_LEAVES_BREAK, 1f, 0.7f);
            if (within(t, c[0], r)) {
                hurt(m, t, 3);
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 4));
            }
            return false;
        });
    }

    // ── 근접 스킬 ───────────────────────────────────────────
    /**
     * 돌진(장수풍뎅이·도토리·불꽃 수레) / 관통 베기(그림자 여우).
     * 앞으로 숙여 힘을 모으는 동안 바닥에 빨간 돌진선이 보인다 (마지막 0.3초는 방향 고정 → 옆으로 피하면 됨)
     */
    private void charge(Mob m, Player t, int windup, double speed, int dashTicks, boolean through) {
        String id = m.type.id;
        m.busy = windup + dashTicks + 12;
        m.prop.once("ready");
        final Vector[] dir = {flat(t.getLocation().toVector().subtract(m.base.getLocation().toVector()))};
        final boolean[] hit = {false}, done = {false};
        m.base.getWorld().playSound(m.base.getLocation(), id.equals("fox") ? Sound.ENTITY_FOX_SCREECH : Sound.ENTITY_RAVAGER_ROAR, 0.7f, id.equals("fox") ? 1.2f : 1.6f);
        run(m, windup + dashTicks + 1, i -> {
            Location me = m.base.getLocation();
            if (i < windup) {
                if (i < windup - 6) dir[0] = flat(t.getLocation().toVector().subtract(me.toVector()));
                face(m, dir[0]);
                m.base.setVelocity(new Vector(0, m.base.getVelocity().getY(), 0));
                if (i % 2 == 0) {
                    double len = Math.min(16, me.distance(t.getLocation()) + 3);
                    Vector side = new Vector(-dir[0].getZ(), 0, dir[0].getX()).multiply(0.45);
                    Location s = me.clone().add(0, 0.15, 0);
                    line(s.clone().add(side), s.clone().add(side).add(dir[0].clone().multiply(len)), WARN, 0.4, 1.0f);
                    line(s.clone().subtract(side), s.clone().subtract(side).add(dir[0].clone().multiply(len)), WARN, 0.4, 1.0f);
                    line(s, s.clone().add(dir[0].clone().multiply(len * (i + 1) / windup)), m.type.c1, 0.3, 1.2f);
                }
                return true;
            }
            if (i == windup) {
                m.prop.stopAnim("ready");
                m.prop.once("attack");
                me.getWorld().playSound(me, id.equals("fox") ? Sound.ENTITY_PLAYER_ATTACK_SWEEP : Sound.ENTITY_HORSE_GALLOP, 1f, id.equals("fox") ? 1.4f : 0.8f);
            }
            if (done[0]) return false;
            m.base.setVelocity(dir[0].clone().multiply(speed).setY(Math.min(0, m.base.getVelocity().getY())));
            Location c = me.clone().add(0, 0.5, 0);
            switch (id) {
                case "fire" -> { c.getWorld().spawnParticle(Particle.FLAME, c, 6, 0.3, 0.2, 0.3, 0.02); c.getWorld().spawnParticle(Particle.LAVA, c, 1, 0.2, 0.1, 0.2, 0); }
                case "fox" -> { c.getWorld().spawnParticle(Particle.DUST, c, 6, 0.25, 0.3, 0.25, 0, new Particle.DustOptions(m.type.c1, 1.2f)); c.getWorld().spawnParticle(Particle.END_ROD, c, 1, 0.1, 0.2, 0.1, 0.01); }
                case "nut" -> { c.getWorld().spawnParticle(Particle.CLOUD, me.clone().add(0, 0.1, 0), 3, 0.2, 0.05, 0.2, 0.01); c.getWorld().spawnParticle(Particle.DUST, c, 3, 0.25, 0.2, 0.25, 0, new Particle.DustOptions(m.type.c1, 1.1f)); }
                default -> { c.getWorld().spawnParticle(Particle.CRIT, c, 4, 0.3, 0.2, 0.3, 0.05); c.getWorld().spawnParticle(Particle.DUST, c, 3, 0.3, 0.2, 0.3, 0, new Particle.DustOptions(m.type.c1, 1.2f)); }
            }
            if (!hit[0] && me.distance(t.getLocation()) < 1.7) {
                hit[0] = true;
                Location hc = t.getLocation().add(0, 1, 0);
                if (id.equals("fox")) {
                    hurt(m, t, 5);
                    hc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, hc, 3, 0.3, 0.2, 0.3, 0);
                    hc.getWorld().spawnParticle(Particle.CRIT, hc, 15, 0.3, 0.4, 0.3, 0.3);
                    hc.getWorld().playSound(hc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.2f);
                } else {
                    hurt(m, t, id.equals("beatle") ? 6 : 4);
                    if (id.equals("fire")) t.setFireTicks(Math.max(t.getFireTicks(), 80));
                    t.setVelocity(dir[0].clone().multiply(id.equals("beatle") ? 1.4 : 1.0).setY(0.5));
                    hc.getWorld().spawnParticle(Particle.EXPLOSION, hc, 1, 0, 0, 0, 0);
                    hc.getWorld().playSound(hc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.8f);
                }
                if (!through) { done[0] = true; return false; }
            }
            // 벽에 부딪히면 멈추고 잠깐 어질어질 (그 틈에 때릴 수 있다)
            Block ahead = me.clone().add(dir[0].clone().multiply(0.9)).add(0, 0.6, 0).getBlock();
            if (!ahead.isPassable() && i > windup + 1) {
                done[0] = true;
                m.busy = 30;
                me.getWorld().spawnParticle(Particle.BLOCK, me.clone().add(dir[0]).add(0, 0.8, 0), 20, 0.3, 0.3, 0.3, 0, ahead.getBlockData());
                me.getWorld().playSound(me, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 0.7f);
                stars(m, 30);
                return false;
            }
            return true;
        });
    }

    /** 돌 석상·무덤 슬라임: 주변에 빨간 원이 차오른 뒤 뛰어올라 쾅 (충격파) */
    private void slam(Mob m, Player t) {
        boolean statue = m.type.id.equals("statue");
        double r = statue ? 4.0 : 3.0;
        m.busy = 26;
        m.prop.once("ready");
        final Location c = ground(statue ? m.base.getLocation() : m.base.getLocation().add(flat(t.getLocation().toVector().subtract(m.base.getLocation().toVector())).multiply(Math.min(3, Math.max(0, m.base.getLocation().distance(t.getLocation()) - 1)))));
        BlockData under = c.clone().subtract(0, 1, 0).getBlock().getBlockData();
        m.base.getWorld().playSound(m.base.getLocation(), statue ? Sound.BLOCK_GRINDSTONE_USE : Sound.ENTITY_SLIME_SQUISH, 1f, 0.6f);
        run(m, 24, i -> {
            if (i < 10) { if (i % 2 == 0) { ring(c, r, 44, WARN, 1.2f); ring(c, r * (i + 1) / 10.0, 30, m.type.c1, 1.1f); } return true; }
            if (i == 10) {
                m.prop.stopAnim("ready");
                m.prop.once("slam");
                if (!statue) {
                    Vector v = c.toVector().subtract(m.base.getLocation().toVector()).setY(0).multiply(0.12);
                    m.base.setVelocity(v.setY(0.55));
                }
            }
            if (i < 17) return true;
            int k = i - 17;
            World w = c.getWorld();
            if (k == 0) {
                w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 0.3, 0), statue ? 3 : 1, 0.5, 0.1, 0.5, 0);
                w.playSound(c, statue ? Sound.BLOCK_ANVIL_LAND : Sound.ENTITY_SLIME_JUMP, 1f, statue ? 0.5f : 0.6f);
                w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.7f);
                if (statue) w.spawnParticle(Particle.DUST, c.clone().add(0, 0.2, 0), 30, 1, 0.1, 1, 0, new Particle.DustOptions(m.type.c2, 1.6f));
                else w.spawnParticle(Particle.ITEM_SLIME, c.clone().add(0, 0.3, 0), 40, 1, 0.2, 1, 0.1);
                if (within(t, c, r) && t.getLocation().getY() - c.getY() < 1.8) {
                    hurt(m, t, statue ? 6 : 3);
                    push(t, c, statue ? 0.8 : 0.5, 0.6);
                    if (statue) t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
                }
            }
            if (k <= 6) {                                       // 바깥으로 퍼지는 충격파
                double rr = r * (k + 1) / 7.0;
                for (int n = 0; n < 32; n++) {
                    double a = Math.PI * 2 * n / 32;
                    Location p = c.clone().add(Math.cos(a) * rr, 0.15, Math.sin(a) * rr);
                    w.spawnParticle(Particle.BLOCK, p, 1, 0.05, 0.05, 0.05, 0, under.getMaterial().isAir() ? Material.DIRT.createBlockData() : under);
                    if (n % 2 == 0) w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(m.type.c1, 1.3f));
                }
            }
            return k < 6;
        });
    }

    /** 굶주린 좀비: 대상 자리에 빨간 원 → 높이 뛰어 덮친다 (배고픔) */
    private void leap(Mob m, Player t) {
        m.busy = 34;
        m.prop.once("ready");
        final Location[] land = {ground(t.getLocation())};
        final double r = 2.2;
        final boolean[] flying = {false};
        m.base.getWorld().playSound(m.base.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 0.6f);
        run(m, 34, i -> {
            if (i < 10) {
                if (i < 6) land[0] = ground(t.getLocation());
                face(m, flat(land[0].toVector().subtract(m.base.getLocation().toVector())));
                if (i % 2 == 0) { ring(land[0], r, 36, WARN, 1.2f); ring(land[0], r * (i + 1) / 10.0, 24, m.type.c1, 1.0f); }
                return true;
            }
            if (i == 10) {
                m.prop.stopAnim("ready");
                m.prop.once("attack");
                Vector h = land[0].toVector().subtract(m.base.getLocation().toVector()).setY(0);
                m.base.setVelocity(h.multiply(1 / 8.2).setY(0.62));
                m.base.getWorld().playSound(m.base.getLocation(), Sound.ENTITY_RAVAGER_STEP, 1f, 0.8f);
                flying[0] = true;
                return true;
            }
            if (i % 2 == 0) ring(land[0], r, 30, WARN, 1.0f);
            m.base.getWorld().spawnParticle(Particle.DUST, m.base.getLocation().add(0, 0.6, 0), 3, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(m.type.c1, 1.1f));
            if (i > 14 && (m.base.isOnGround() || i >= 33)) {
                Location c = m.base.getLocation();
                World w = c.getWorld();
                BlockData under = c.clone().subtract(0, 1, 0).getBlock().getBlockData();
                w.spawnParticle(Particle.BLOCK, c.clone().add(0, 0.2, 0), 40, 1, 0.1, 1, 0, under.getMaterial().isAir() ? Material.DIRT.createBlockData() : under);
                w.spawnParticle(Particle.DUST, c.clone().add(0, 0.4, 0), 25, 1, 0.3, 1, 0, new Particle.DustOptions(m.type.c2, 1.4f));
                w.playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 1.2f);
                if (within(t, c, r)) {
                    hurt(m, t, 4);
                    t.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 100, 1));
                }
                return false;
            }
            return true;
        });
    }

    /** 혀 개구리·붕대 미라: 혀/붕대를 쏴서 맞으면 끌어당긴다 (끝을 보고 피하면 그냥 돌아간다) */
    private void pull(Mob m, Player t) {
        boolean frog = m.type.id.equals("frog");
        m.busy = 30;
        m.prop.once("cast");
        final Location[] tip = new Location[1];
        final Vector[] dir = new Vector[1];
        final int[] state = {0, 0};            // 0 날아감, 1 걸림, 2 돌아옴 / 걸린 틱
        run(m, 40, i -> {
            if (i < 8) return true;
            Location from = m.eye();
            if (i == 8) {
                tip[0] = from.clone();
                dir[0] = t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).normalize();
                from.getWorld().playSound(from, frog ? Sound.ENTITY_FROG_TONGUE : Sound.ENTITY_LEASH_KNOT_PLACE, 1f, frog ? 0.8f : 1.2f);
            }
            if (state[0] == 0) {
                tip[0].add(dir[0].clone().multiply(1.1));
                if (tip[0].distance(t.getLocation().add(0, 1, 0)) < 1.2) {
                    state[0] = 1;
                    t.getWorld().playSound(t.getLocation(), frog ? Sound.ENTITY_SLIME_ATTACK : Sound.ENTITY_LEASH_KNOT_BREAK, 1f, 0.8f);
                } else if (tip[0].distance(from) > 13 || !tip[0].getBlock().isPassable()) state[0] = 2;
            }
            if (state[0] == 1) {
                tip[0] = t.getLocation().add(0, 1, 0);
                if (from.distance(tip[0]) > 2.2) t.setVelocity(from.toVector().subtract(tip[0].toVector()).normalize().multiply(0.8).setY(0.25));
                if (++state[1] >= 8 || from.distance(tip[0]) <= 2.2) {
                    hurt(m, t, 2);
                    if (frog) m.meleeCd = 0;
                    else t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                    m.busy = 2;
                    return false;
                }
            }
            if (state[0] == 2) {
                Vector back = from.toVector().subtract(tip[0].toVector());
                if (back.length() < 1.3) return false;
                tip[0].add(back.normalize().multiply(1.3));
            }
            line(from, tip[0], m.type.c1, 0.18, frog ? 1.0f : 1.2f);
            if (!frog) line(from.clone().add(0, 0.08, 0), tip[0].clone().add(0, 0.08, 0), m.type.c2, 0.5, 0.7f);
            tip[0].getWorld().spawnParticle(Particle.DUST, tip[0], 3, 0.06, 0.06, 0.06, 0, new Particle.DustOptions(m.type.c1, 1.6f));
            return true;
        });
    }

    // ── 암살자 스킬 ─────────────────────────────────────────
    /** 검은 고양이·칼날 토끼·광대: 사라졌다가 등 뒤에 나타나 (머리 위 빨간 표시 0.4초 뒤) 찌르고 빠진다 */
    private void blink(Mob m, Player t) {
        String id = m.type.id;
        m.busy = 34;
        m.prop.once("vanish");
        puff(m, m.base.getLocation().add(0, 0.7, 0));
        m.base.getWorld().playSound(m.base.getLocation(), id.equals("cat") ? Sound.ENTITY_CAT_HISS : id.equals("rabbit") ? Sound.ENTITY_RABBIT_JUMP : Sound.ENTITY_WITCH_CELEBRATE, 1f, 1.3f);
        run(m, 30, i -> {
            if (i == 7) {
                Vector back = flat(t.getLocation().getDirection()).multiply(-1.8);
                Location spot = safe(t.getLocation().add(back));
                if (spot == null) spot = safe(t.getLocation().add(back.rotateAroundY(Math.PI / 2)));
                if (spot == null) spot = m.base.getLocation();
                spot.setDirection(t.getLocation().toVector().subtract(spot.toVector()));
                m.base.teleport(spot);
                m.prop.stopAnim("vanish");
                m.prop.once("appear");
                puff(m, spot.clone().add(0, 0.7, 0));
                spot.getWorld().playSound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
            }
            if (i >= 7 && i < 15 && i % 2 == 1)                    // 머리 위 빨간 경고
                m.base.getWorld().spawnParticle(Particle.DUST, m.base.getLocation().add(0, 1.2 + m.type.scale * 0.5, 0), 6, 0.08, 0.15, 0.08, 0, new Particle.DustOptions(WARN, 1.3f));
            if (i == 15) {
                m.base.lookAt(t);
                m.prop.once("attack");
                if (m.base.getLocation().distance(t.getLocation()) < 3.2) {
                    Location hc = t.getLocation().add(0, 1, 0);
                    World w = hc.getWorld();
                    w.spawnParticle(Particle.SWEEP_ATTACK, hc, 2, 0.2, 0.1, 0.2, 0);
                    w.spawnParticle(Particle.CRIT, hc, 18, 0.3, 0.4, 0.3, 0.35);
                    w.playSound(hc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.1f);
                    switch (id) {
                        case "cat" -> { hurt(m, t, 6); t.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0)); }
                        case "rabbit" -> hurt(m, t, 4);
                        default -> {                                // 광대: 시야를 휙 돌려 놓는다
                            hurt(m, t, 4);
                            Location l = t.getLocation();
                            l.setYaw(l.getYaw() + 180);
                            t.teleport(l);
                            confetti(hc);
                        }
                    }
                }
            }
            if (i == 19 && !id.equals("clown")) {                   // 치고 빠지기
                Vector away = flat(m.base.getLocation().toVector().subtract(t.getLocation().toVector())).multiply(0.9).setY(0.35);
                m.base.setVelocity(away);
            }
            return true;
        });
    }

    // ── 연출 도우미 ─────────────────────────────────────────
    /** 직선으로 날아가는 탄 (homing 만큼 대상 쪽으로 휜다). 대상에 맞거나 막히거나 시간이 다 되면 끝 */
    private void shoot(Player t, Location from, Vector dir, double speed, int maxTicks, double homing, Consumer<Location> trail, java.util.function.BiConsumer<Location, Boolean> end) {
        final Location p = from.clone();
        final Vector d = dir.clone().normalize();
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (i++ > maxTicks || !t.isOnline()) { cancel(); return; }
                if (homing > 0 && !t.isDead() && t.getWorld().equals(p.getWorld())) {
                    Vector want = t.getLocation().add(0, 1, 0).toVector().subtract(p.toVector()).normalize();
                    d.multiply(1 - homing).add(want.multiply(homing)).normalize();
                }
                for (int s = 0; s < 2; s++) {
                    p.add(d.clone().multiply(speed / 2));
                    if (t.getWorld().equals(p.getWorld()) && !t.isDead() && p.distance(t.getLocation().add(0, 0.9, 0)) < 0.9) { end.accept(p, true); cancel(); return; }
                    if (!p.getBlock().isPassable()) { end.accept(p, false); cancel(); return; }
                }
                trail.accept(p);
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    /** 포물선으로 던지기. 떨어질 자리에 빨간 장판(반지름 r)이 차오른다. item 이 있으면 그 물건이 날아간다 */
    private void throwArc(Location from, Location to, int flight, ItemStack item, double r, Consumer<Location> trail, Consumer<Location> land) {
        final ItemDisplay disp = item == null ? null : from.getWorld().spawn(from, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        });
        final double h = 2.5 + from.distance(to) * 0.15;
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                i++;
                double s = Math.min(1, i / (double) flight);
                Location p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(s)).add(0, 4 * h * s * (1 - s), 0);
                if (disp != null && disp.isValid()) { p.setYaw(i * 30f); disp.teleport(p); }
                trail.accept(p);
                if (i % 2 == 0) { ring(to, r, (int) (14 * r), WARN, 1.0f); ring(to, r * s, (int) (10 * r), WARN, 0.8f); }
                if (i >= flight) {
                    if (disp != null) disp.remove();
                    land.accept(to);
                    cancel();
                }
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    /** 바닥에 남는 독구름: 안에 있으면 독이 계속 걸린다 */
    private void cloud(Mob m, Player t, Location c, double r, int ticks, Color a, Color b) {
        run(null, ticks, i -> {
            if (i % 4 == 0) {
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(0, 0.4, 0), 12, r * 0.45, 0.25, r * 0.45, 0, new Particle.DustOptions(i % 8 == 0 ? a : b, 1.5f));
                ring(c, r, 20, a, 1.0f);
            }
            if (i % 20 == 10 && within(t, c, r)) t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 0));
            return true;
        });
    }

    private void puff(Mob m, Location c) {
        World w = c.getWorld();
        switch (m.type.id) {
            case "cat" -> { w.spawnParticle(Particle.SQUID_INK, c, 14, 0.25, 0.35, 0.25, 0.02); w.spawnParticle(Particle.DUST, c, 16, 0.3, 0.4, 0.3, 0, new Particle.DustOptions(m.type.c2, 1.2f)); }
            case "clown" -> confetti(c);
            default -> { w.spawnParticle(Particle.CLOUD, c, 14, 0.25, 0.3, 0.25, 0.03); w.spawnParticle(Particle.DUST, c, 12, 0.3, 0.4, 0.3, 0, new Particle.DustOptions(m.type.c2, 1.2f)); }
        }
    }

    private void confetti(Location c) {
        Color[] cs = {rgb(255, 60, 60), rgb(60, 140, 255), rgb(255, 220, 40), rgb(80, 220, 100), rgb(220, 90, 255)};
        for (Color col : cs) c.getWorld().spawnParticle(Particle.DUST, c, 8, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(col, 1.1f));
        c.getWorld().spawnParticle(Particle.FIREWORK, c, 8, 0.1, 0.1, 0.1, 0.1);
        c.getWorld().playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.7f, 1.4f);
    }

    /** 벽에 부딪혀 어지러울 때 머리 위에서 도는 별 */
    private void stars(Mob m, int ticks) {
        run(m, ticks, i -> {
            if (i % 2 == 0) {
                Location c = m.base.getLocation().add(0, 1.0 + m.type.scale * 0.5, 0);
                for (int n = 0; n < 3; n++) {
                    double a = i * 0.35 + n * Math.PI * 2 / 3;
                    c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 0.45, 0, Math.sin(a) * 0.45), 1, 0, 0, 0, 0, new Particle.DustOptions(rgb(255, 230, 60), 1.0f));
                }
            }
            return true;
        });
    }

    private static void ring(Location c, double r, int pts, Color col, float size) {
        if (r < 0.1) return;
        Particle.DustOptions o = new Particle.DustOptions(col, size);
        for (int n = 0; n < pts; n++) {
            double a = Math.PI * 2 * n / pts;
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.12, Math.sin(a) * r), 1, 0, 0, 0, 0, o);
        }
    }

    private static void line(Location a, Location b, Color col, double step, float size) {
        Vector v = b.toVector().subtract(a.toVector());
        double len = v.length();
        if (len < 0.01) return;
        v.normalize().multiply(step);
        Particle.DustOptions o = new Particle.DustOptions(col, size);
        Location p = a.clone();
        for (double s = 0; s <= len; s += step) { a.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, o); p.add(v); }
    }

    private static boolean within(Player t, Location c, double r) {
        if (t.isDead() || !t.getWorld().equals(c.getWorld())) return false;
        Location l = t.getLocation();
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
        return dx * dx + dz * dz <= r * r && Math.abs(l.getY() - c.getY()) < 2.5;
    }

    private static void push(Player t, Location from, double power, double up) {
        Vector v = flat(t.getLocation().toVector().subtract(from.toVector())).multiply(power).setY(up);
        t.setVelocity(v);
    }

    private static Vector flat(Vector v) {
        Vector f = v.clone().setY(0);
        return f.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : f.normalize();
    }

    private static void face(Mob m, Vector dir) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        m.base.setRotation(yaw, 0);
        m.base.setBodyYaw(yaw);
    }

    private static void attr(Husk h, Attribute a, double v) {
        AttributeInstance i = h.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** 그 근처의 발 디딜 곳 (위아래 몇 칸 안에서) */
    private static Location ground(Location l) {
        Location b = l.clone();
        b.setY(Math.floor(b.getY()));
        for (int dy = 0; dy <= 4; dy++) {
            Location down = b.clone().subtract(0, dy, 0);
            if (!down.clone().subtract(0, 1, 0).getBlock().isPassable() && down.getBlock().isPassable()) return down;
        }
        return b;
    }

    /** 서 있을 수 있는 자리면 그 자리 (발·머리 비어 있고 밑이 단단), 아니면 null */
    private static Location safe(Location l) {
        for (int dy : new int[]{0, 1, -1}) {
            Location p = l.clone().add(0, dy, 0);
            p.setY(Math.floor(p.getY()));
            if (p.getBlock().isPassable() && p.clone().add(0, 1, 0).getBlock().isPassable() && !p.clone().subtract(0, 1, 0).getBlock().isPassable()) return p;
        }
        return null;
    }

    /** 대상 주변 min~max 칸 안에서 설 수 있는 곳 */
    private static Location spot(Location c, double min, double max) {
        for (int n = 0; n < 16; n++) {
            double a = rnd().nextDouble(Math.PI * 2), d = rnd().nextDouble(min, max);
            Location p = c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy = 3; dy >= -4; dy--) {
                Location s = safe(p.clone().add(0, dy, 0));
                if (s != null) return s.add(0.5, 0, 0.5);
            }
        }
        return c.clone().add(1, 0, 0);
    }

    // ── 이벤트 ─────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        Mob m = mobs.remove(e.getEntity().getUniqueId());
        if (m == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Location c = e.getEntity().getLocation().add(0, 0.7, 0);
        c.getWorld().spawnParticle(Particle.POOF, c, 25, 0.3, 0.4, 0.3, 0.05);
        c.getWorld().spawnParticle(Particle.DUST, c, 30, 0.4, 0.5, 0.4, 0, new Particle.DustOptions(m.type.c1, 1.5f));
        c.getWorld().playSound(c, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.8f);
        Bukkit.getScheduler().runTask(pl, () -> { m.prop.remove(); lastOneGone(m.target); });
    }

    /** 떨어짐·불·질식·익사로는 다치지 않는다 (사람에게 맞아야만) */
    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!mobs.containsKey(e.getEntity().getUniqueId())) return;
        switch (e.getCause()) {
            case FALL, FIRE, FIRE_TICK, LAVA, HOT_FLOOR, SUFFOCATION, DROWNING, CONTACT, CRAMMING, FREEZE -> e.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Mob m = mobs.get(e.getEntity().getUniqueId());
        if (m != null && (e.getTarget() == null || !e.getTarget().getUniqueId().equals(m.target))) e.setCancelled(true);
    }

    /** 대상이 죽으면 몬스터는 물러간다 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (count(p.getUniqueId()) == 0) return;
        Bukkit.getScheduler().runTask(pl, () -> pl.effects.end(p, Effects.MONSTERS));
    }
}
