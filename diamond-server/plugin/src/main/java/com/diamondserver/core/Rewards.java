package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 300개 다이아 연출 중 "자리에서 벌어지는 것" 4종: 다이아 소나기 · 다이아 분수 · 다이아 피냐타 · 운석우.
 * (미다스의 손 · 거인화는 시간제 효과라 Effects 에 있다.)
 * 떨어지는 다이아는 대상만 주울 수 있고, 주우면 보유 다이아로 들어간다.
 */
final class Rewards implements Listener {
    private final DiamondPlugin pl;

    /** 진행 중인 피냐타 */
    private static final class Pinata {
        UUID owner; Models.Prop prop; int hits, need, reward; long endTick; boolean done;
    }
    private final Map<UUID, Pinata> pinatas = new HashMap<>();     // 받침 몹 UUID → 피냐타
    private long tick;

    Rewards(DiamondPlugin pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, () -> tick++, 1L, 1L);
    }

    private static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    /** 다이아 아이템을 떨어뜨린다 (대상만 주울 수 있음) */
    private static void drop(Location at, int amount, Vector velocity, UUID owner) {
        if (amount <= 0) return;
        Item it = at.getWorld().dropItem(at, new ItemStack(Material.DIAMOND, amount));
        it.setOwner(owner);
        it.setPickupDelay(10);
        it.setVelocity(velocity);
    }

    /** 머리 위로 비어 있는 높이를 최대 max 칸까지 찾는다 */
    private static double freeAbove(Location feet, int max) {
        int free = 0;
        for (int i = 2; i <= max; i++) {
            if (!feet.clone().add(0, i, 0).getBlock().isPassable()) break;
            free = i;
        }
        return Math.max(2, free);
    }

    // ── 다이아 소나기: 머리 위 구름에서 total 개가 5초간 쏟아진다 ──────────
    void rain(Player p, int total) {
        final UUID id = p.getUniqueId();
        Location base = p.getLocation();
        base.setPitch(0);
        final Location cloud = base.clone().add(0, freeAbove(base, 6), 0);
        final Models.Prop prop = pl.models.spawn(cloud, "diamond_cloud", "spawn");
        p.playSound(p.getLocation(), Sound.WEATHER_RAIN, 1f, 1f);
        final int ticks = 100, each = 2, drops = ticks / each;
        new BukkitRunnable() {
            int t = 0, given = 0, n = 0;
            @Override public void run() {
                if (t == 20) prop.play("rain");
                if (t >= 20 && t < 20 + ticks && (t - 20) % each == 0) {
                    n++;
                    int amt = n >= drops ? total - given : Math.max(1, total / drops + (rnd().nextBoolean() ? 1 : 0));
                    amt = Math.min(amt, total - given);
                    given += amt;
                    Location at = cloud.clone().add(rnd().nextDouble(-1.5, 1.5), -0.5, rnd().nextDouble(-1.5, 1.5));
                    drop(at, amt, new Vector(0, -0.1, 0), id);
                    at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
                }
                if (t == 20 + ticks) prop.play("vanish");
                if (t >= 20 + ticks + 20) { prop.remove(); cancel(); }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    // ── 다이아 분수: 발밑 분수대에서 total 개가 솟구친다 ─────────────────
    void fountain(Player p, int total) {
        final UUID id = p.getUniqueId();
        final Location at = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        final Models.Prop prop = pl.models.spawn(at, "diamond_fountain", "spawn");
        final int ticks = 60, each = 2, drops = ticks / each;
        new BukkitRunnable() {
            int t = 0, given = 0, n = 0;
            @Override public void run() {
                if (t == 24) { prop.play("burst"); at.getWorld().playSound(at, Sound.ENTITY_GENERIC_SPLASH, 1f, 1.2f); }
                if (t == 36) prop.play("idle");
                if (t >= 24 && t < 24 + ticks && (t - 24) % each == 0) {
                    n++;
                    int amt = n >= drops ? total - given : Math.max(1, total / drops + (rnd().nextBoolean() ? 1 : 0));
                    amt = Math.min(amt, total - given);
                    given += amt;
                    drop(at.clone().add(0, 1.2, 0), amt, new Vector(rnd().nextDouble(-0.15, 0.15), rnd().nextDouble(0.45, 0.7), rnd().nextDouble(-0.15, 0.15)), id);
                    at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f);
                }
                if (t == 24 + ticks) prop.play("vanish");
                if (t >= 24 + ticks + 24) { prop.remove(); cancel(); }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    // ── 다이아 피냐타: 30초 안에 50대 때리면 터지며 reward 개, 실패하면 0개 ──────
    void pinata(Player p, int reward) {
        int need = Math.max(1, pl.getConfig().getInt("effects.pinata-hits", 50));
        int seconds = Math.max(5, pl.getConfig().getInt("effects.pinata-seconds", 30));
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        Location at = p.getLocation().add(dir.normalize().multiply(2.5));
        if (!at.getBlock().isPassable()) at = p.getLocation();
        at.setYaw(p.getLocation().getYaw() + 180);
        at.setPitch(0);
        Husk mob = at.getWorld().spawn(at, Husk.class, h -> {
            h.setAI(false);
            h.setSilent(true);
            h.setGravity(false);
            h.setPersistent(false);
            h.setRemoveWhenFarAway(false);
            h.setInvisible(true);
            h.setAdult();
            h.setCanPickupItems(false);
            if (h.getEquipment() != null) h.getEquipment().clear();
        });
        Pinata pn = new Pinata();
        pn.owner = p.getUniqueId();
        pn.prop = pl.models.attach(mob, "diamond_pinata", "spawn");
        pn.need = need;
        pn.reward = reward;
        pn.endTick = tick + seconds * 20L;
        pinatas.put(mob.getUniqueId(), pn);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (!pn.done) pn.prop.play("idle"); }, 20L);
        final UUID mobId = mob.getUniqueId();
        new BukkitRunnable() {
            @Override public void run() {
                Pinata cur = pinatas.get(mobId);
                if (cur == null || cur.done) { cancel(); return; }
                Player owner = Bukkit.getPlayer(cur.owner);
                long left = (cur.endTick - tick + 19) / 20;
                if (owner != null) owner.sendActionBar(Component.text("피냐타 " + cur.hits + "/" + cur.need + " · " + Math.max(0, left) + "초", NamedTextColor.WHITE));
                if (tick >= cur.endTick || !cur.prop.alive()) {           // 시간 초과: 도망
                    cur.done = true;
                    pinatas.remove(mobId);
                    cur.prop.play("escape");
                    cur.prop.removeLater(40);
                    for (Player o : Bukkit.getOnlinePlayers()) pl.net.toast(o, "피냐타를 터뜨리지 못했습니다.", false);
                    cancel();
                }
            }
        }.runTaskTimer(pl, 10L, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        Pinata pn = pinatas.get(e.getEntity().getUniqueId());
        if (pn == null) return;
        e.setCancelled(true);
        if (pn.done || !(e.getDamager() instanceof Player hitter)) return;
        pn.hits++;
        Location at = e.getEntity().getLocation().add(0, 1, 0);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.8f + pn.hits * 0.02f);
        at.getWorld().spawnParticle(Particle.CRIT, at, 6, 0.4, 0.4, 0.4, 0.1);
        if (pn.hits < pn.need) { pn.prop.play("hit"); return; }
        pn.done = true;
        pinatas.remove(e.getEntity().getUniqueId());
        pn.prop.play("burst");
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 0.5, 0.5, 0.5, 0);
        int left = pn.reward;
        while (left > 0) {
            int amt = Math.min(left, rnd().nextInt(3, 7));
            left -= amt;
            drop(at, amt, new Vector(rnd().nextDouble(-0.3, 0.3), rnd().nextDouble(0.2, 0.5), rnd().nextDouble(-0.3, 0.3)), pn.owner);
        }
        pn.prop.removeLater(8);
        Player owner = Bukkit.getPlayer(pn.owner);
        String name = owner == null ? pl.nick(hitter) : pl.nick(owner);
        for (Player o : Bukkit.getOnlinePlayers()) pl.net.toast(o, name + "님의 피냐타가 터졌습니다! 다이아 " + pn.reward + "개", true);
    }

    /** 피냐타 받침 몹은 다른 피해(낙하·불 등)를 받지 않는다 */
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent) && pinatas.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    // ── 운석우: 운석 count 개가 주변에 떨어지고, 떨어진 자리 반경 2~3칸의 블럭 일부가 다이아 광석으로 ──
    void meteor(Player p, int count) {
        int perMeteor = Math.max(1, pl.getConfig().getInt("effects.meteor-ore-blocks", 33));
        Location c = p.getLocation();
        for (int i = 0; i < count; i++) {
            double ang = rnd().nextDouble(0, Math.PI * 2), r = rnd().nextDouble(4, 8);
            Location spot = c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
            final Location impact = ground(spot);
            Bukkit.getScheduler().runTaskLater(pl, () -> fall(impact, perMeteor), i * 15L);
        }
    }

    /** 그 자리 근처의 바닥(위 3칸 ~ 아래 10칸 사이에서 처음 만나는 단단한 블럭 위) */
    private static Location ground(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ(), y0 = l.getBlockY() + 3;
        for (int y = y0; y >= Math.max(w.getMinHeight(), y0 - 13); y--)
            if (w.getBlockAt(x, y, z).getType().isSolid() && w.getBlockAt(x, y + 1, z).isPassable())
                return new Location(w, x + 0.5, y + 1, z + 0.5);
        return new Location(w, x + 0.5, l.getBlockY(), z + 0.5);
    }

    private void fall(Location impact, int oreBlocks) {
        final double height = 14;
        final int ticks = 30;
        final Models.Prop prop = pl.models.spawn(impact.clone().add(0, height, 0), "diamond_meteor", "walk");
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t < ticks) {
                    Location at = impact.clone().add(0, height * (1 - (t + 1) / (double) ticks), 0);
                    prop.move(at);
                    at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.8, 0), 4, 0.3, 0.3, 0.3, 0.01);
                } else if (t == ticks) {
                    prop.play("attack");
                    World w = impact.getWorld();
                    w.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1);
                    crater(impact, oreBlocks);
                } else if (t == ticks + 12) {
                    prop.play("death");
                } else if (t >= ticks + 32) {
                    prop.remove();
                    cancel();
                }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    /** 떨어진 자리 반경 2.5칸 안의 단단한 블럭 중 최대 max 개를 다이아 광석으로 */
    private static void crater(Location c, int max) {
        List<Block> cand = new ArrayList<>();
        Block center = c.getBlock();
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dy * dy + dz * dz > 6.25) continue;
            Block b = center.getRelative(dx, dy, dz);
            Material t = b.getType();
            if (!t.isSolid() || !t.isOccluding() || t == Material.BEDROCK || t.name().endsWith("_ORE")) continue;
            if (b.getState() instanceof TileState) continue;
            cand.add(b);
        }
        Collections.shuffle(cand);
        for (int i = 0; i < cand.size() && i < max; i++) {
            Block b = cand.get(i);
            b.setType(b.getY() < 0 || b.getType().name().contains("DEEPSLATE") ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE, false);
        }
    }

    /** 서버가 꺼질 때: 남은 피냐타를 치운다 */
    void shutdown() {
        for (Pinata pn : new ArrayList<>(pinatas.values())) pn.prop.remove();
        pinatas.clear();
    }

    boolean isPinata(Entity e) { return pinatas.containsKey(e.getUniqueId()); }
}
