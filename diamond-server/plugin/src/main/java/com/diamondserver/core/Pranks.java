package com.diamondserver.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모델을 쓰는 장난·연출: 다이아 도둑 까마귀 · 로켓 발사 · 모루 낙하 · 사신(즉사).
 * (강제 댄스 · 기절은 시간제 효과라 Effects 에 있다.)
 */
final class Pranks implements Listener {
    private final DiamondPlugin pl;
    private final NamespacedKey rocketKey, chestKey;

    /** 날아다니는 까마귀 */
    private static final class Crow {
        UUID victim; Models.Prop prop; long stolen; boolean fleeing, done;
    }
    private final Map<UUID, Crow> crows = new HashMap<>();            // 받침 몹 UUID → 까마귀
    private final Map<UUID, ItemStack> savedChest = new HashMap<>();  // 로켓: 겉날개를 입히기 전 가슴 칸 아이템
    private final java.util.Set<UUID> reaped = new java.util.HashSet<>();   // 방금 4444 즉사로 죽인 사람

    /** 이 사람이 방금 즉사 연출로 죽었는지 (한 번 묻고 나면 지운다) */
    boolean consumeReaped(UUID id) { return reaped.remove(id); }

    Pranks(DiamondPlugin pl) {
        this.pl = pl;
        this.rocketKey = new NamespacedKey(pl, "rocket_elytra");
        this.chestKey = new NamespacedKey(pl, "rocket_saved_chest");
    }

    private static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    private void toastAll(String text, boolean ok) {
        for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, text, ok);
    }

    // ── 다이아 도둑 까마귀 ────────────────────────────────────
    // 날아와서 보유 다이아 64개(모자라면 전부)를 물고 20초 동안 도망간다. 누구든 때려서 잡으면 되찾는다.
    void crow(Player victim) {
        final long want = Math.max(1, pl.getConfig().getLong("effects.crow-steal", 64));
        final int fleeTicks = Math.max(60, pl.getConfig().getInt("effects.crow-flee-seconds", 20) * 20);
        Location start = victim.getLocation().add(rnd().nextDouble(-5, 5), 3, rnd().nextDouble(-5, 5));
        if (!start.getBlock().isPassable()) start = victim.getLocation().add(0, 2, 0);
        Chicken mob = start.getWorld().spawn(start, Chicken.class, c -> {
            c.setAI(false);
            c.setSilent(true);
            c.setGravity(false);
            c.setPersistent(false);
            c.setRemoveWhenFarAway(false);
            c.setInvisible(true);
            c.setAdult();
        });
        final Crow crow = new Crow();
        crow.victim = victim.getUniqueId();
        crow.prop = pl.models.attach(mob, "diamond_crow", "walk");
        crows.put(mob.getUniqueId(), crow);
        final UUID mobId = mob.getUniqueId();
        victim.playSound(victim.getLocation(), Sound.ENTITY_PARROT_AMBIENT, 1f, 0.6f);

        new BukkitRunnable() {
            int t = 0;
            Vector dir = new Vector(1, 0, 0);
            @Override public void run() {
                Player v = Bukkit.getPlayer(crow.victim);
                if (crow.done) { end(); return; }
                if (v == null || !crow.prop.alive()) { giveBack(crow, mobId); end(); return; }   // 주인이 나갔거나 까마귀가 있던 곳이 내려갔다 → 쫓을 수 없으니 돌려준다
                Location at = crow.prop.loc();
                if (!crow.fleeing) {
                    // 1) 대상 머리 쪽으로 날아온다
                    Location goal = v.getLocation().add(0, 1.4, 0);
                    Vector to = goal.toVector().subtract(at.toVector());
                    double d = to.length();
                    if (d > 1.2 && t < 60) {
                        Location next = at.clone().add(to.normalize().multiply(Math.min(0.45, d)));
                        face(next, to);
                        crow.prop.move(next);
                    } else {
                        // 2) 낚아챈다
                        crow.prop.play("attack");
                        Store.PData pd = pl.store.get(v);
                        crow.stolen = Math.min(want, pd.held);
                        if (crow.stolen <= 0) {
                            toastAll("까마귀가 " + pl.nick(v) + "님에게 왔지만 훔칠 다이아가 없었습니다.", true);
                            crow.done = true;
                            crows.remove(mobId);
                            crow.prop.removeLater(20);
                            end();
                            return;
                        }
                        pl.ledger.takeHeld(v, crow.stolen);
                        v.playSound(v.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
                        toastAll("까마귀가 " + pl.nick(v) + "님의 다이아 " + crow.stolen + "개를 물고 달아납니다. 때려서 되찾으세요!", false);
                        crow.fleeing = true;
                        t = 0;
                        Vector away = at.toVector().subtract(v.getLocation().toVector()).setY(0);
                        dir = away.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : away.normalize();
                        Bukkit.getScheduler().runTaskLater(pl, () -> { if (!crow.done) crow.prop.play("flee"); }, 14L);
                    }
                } else {
                    // 3) 도망: 대상에게서 멀어지는 쪽으로, 막히면 방향을 튼다
                    if (t >= fleeTicks) { finish(crow, mobId, false); end(); return; }
                    if (t > 14) {
                        Vector away = at.toVector().subtract(v.getLocation().toVector()).setY(0);
                        if (away.lengthSquared() > 1e-4) dir = dir.multiply(0.8).add(away.normalize().multiply(0.2)).normalize();
                        Location next = null;
                        for (int k = 0; k < 8 && next == null; k++) {
                            Vector tryDir = dir.clone().rotateAroundY(Math.toRadians(k * 45 * (k % 2 == 0 ? 1 : -1)));
                            Location cand = at.clone().add(tryDir.clone().multiply(0.24));
                            cand.setY(hover(cand));
                            if (cand.getBlock().isPassable() && cand.clone().add(0, 0.6, 0).getBlock().isPassable()) { next = cand; dir = tryDir; }
                        }
                        if (next != null) { face(next, dir); crow.prop.move(next); }
                        if (t % 30 == 0) at.getWorld().playSound(at, Sound.ENTITY_PARROT_FLY, 0.8f, 0.7f);
                    }
                }
                t++;
            }
            private void end() { cancel(); }
        }.runTaskTimer(pl, 1L, 1L);
    }

    /** 까마귀가 떠 있을 높이: 바닥에서 1.5칸 위 (바닥을 못 찾으면 지금 높이) */
    private static double hover(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY(); y >= Math.max(w.getMinHeight(), l.getBlockY() - 6); y--)
            if (w.getBlockAt(x, y, z).getType().isSolid()) {
                double want = y + 2.5;
                return l.getY() + Math.max(-0.2, Math.min(0.2, want - l.getY()));     // 한 번에 조금씩만 오르내린다
            }
        return l.getY();
    }

    private static void face(Location l, Vector dir) {
        if (dir.lengthSquared() < 1e-6) return;
        l.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
        l.setPitch(0);
    }

    /** 까마귀가 잡히지 않은 채 사라져야 할 때: 훔친 다이아를 주인에게 돌려준다 (주인이 접속해 있지 않으면 장부에 바로) */
    private void giveBack(Crow crow, UUID mobId) {
        if (crow.done) return;
        crow.done = true;
        crows.remove(mobId);
        crow.prop.remove();
        if (crow.stolen <= 0) return;
        Player v = Bukkit.getPlayer(crow.victim);
        if (v != null) {
            pl.ledger.addHeld(v, crow.stolen);
            pl.net.toast(v, "까마귀가 사라져 다이아 " + crow.stolen + "개가 돌아왔습니다.", true);
        } else {
            Store.PData pd = pl.store.get(crow.victim);
            pd.held += crow.stolen;
            pd.dirty = true;
            pl.store.save(pd);
        }
    }

    /** 까마귀가 끝난다. caught = 잡혔는지 (잡히면 다이아를 돌려준다) */
    private void finish(Crow crow, UUID mobId, boolean caught) {
        if (crow.done) return;
        crow.done = true;
        crows.remove(mobId);
        Player v = Bukkit.getPlayer(crow.victim);
        String name = v == null ? "주인" : pl.nick(v);
        if (caught) {
            crow.prop.play("death");
            crow.prop.removeLater(24);
            if (v != null && crow.stolen > 0) pl.ledger.addHeld(v, crow.stolen);
            else if (crow.stolen > 0) {                       // 주인이 나갔으면 장부에 바로 넣는다
                Store.PData pd = pl.store.get(crow.victim);
                pd.held += crow.stolen;
                pd.dirty = true;
                pl.store.save(pd);
            }
            toastAll("까마귀를 잡았습니다! " + name + "님이 다이아 " + crow.stolen + "개를 되찾았습니다.", true);
        } else {
            crow.prop.remove();
            if (crow.stolen > 0) toastAll("까마귀가 " + name + "님의 다이아 " + crow.stolen + "개를 물고 사라졌습니다.", false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCrowHit(EntityDamageByEntityEvent e) {
        Crow crow = crows.get(e.getEntity().getUniqueId());
        if (crow == null) return;
        e.setCancelled(true);
        if (crow.fleeing && e.getDamager() instanceof Player) finish(crow, e.getEntity().getUniqueId(), true);
    }

    @EventHandler
    public void onCrowDamage(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent) && crows.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    // ── 로켓 발사 ────────────────────────────────────────────
    // 100칸 위로 쏘아 올리고, 꼭대기에서 겉날개를 입힌다. 땅에 닿으면 겉날개를 거두고 원래 가슴 칸 아이템을 돌려준다.
    // 머리 위가 막혀 있으면(지하) 먼저 지표면으로 올린 뒤 쏜다.
    void rocket(Player p) {
        final UUID id = p.getUniqueId();
        final double rise = Math.max(10, pl.getConfig().getDouble("effects.rocket-height", 100));
        World w = p.getWorld();
        Location from = p.getLocation();
        int top = w.getHighestBlockYAt(from);
        if (top > from.getBlockY() + 1 && !w.hasCeiling()) {    // 하늘이 막혀 있음 → 지표면으로 (네더처럼 천장이 있는 세계에서는 옮기지 않고 그 자리에서 쏜다)
            from = new Location(w, from.getX(), top + 1.0, from.getZ(), from.getYaw(), from.getPitch());
            p.teleport(from);
        }
        final double goalY = Math.min(w.getMaxHeight() - 4, from.getY() + rise);
        final Models.Prop prop = pl.models.spawn(under(from), "ride_rocket", "launch");
        p.playSound(from, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.8f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null || q.isDead()) { prop.remove(); cancel(); return; }
                if (t == 20) prop.play("fly");
                if (t >= 12) {                                    // 0.6초 예열 뒤 상승
                    q.setFallDistance(0);
                    q.setVelocity(new Vector(0, 1.5, 0));
                    q.getWorld().spawnParticle(Particle.FLAME, q.getLocation(), 6, 0.2, 0.1, 0.2, 0.02);
                }
                prop.move(under(q.getLocation()));
                boolean blocked = t > 20 && !q.getLocation().add(0, 2.2, 0).getBlock().isPassable();
                if (q.getLocation().getY() >= goalY || t > 160 || blocked) {
                    prop.remove();
                    giveElytra(q);
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    private static Location under(Location l) {
        Location u = l.clone().add(0, -1.2, 0);
        u.setPitch(0);
        return u;
    }

    private boolean isRocketElytra(ItemStack it) {
        return it != null && it.getType() == Material.ELYTRA && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(rocketKey, PersistentDataType.BYTE);
    }

    private void giveElytra(Player p) {
        final UUID id = p.getUniqueId();
        if (!savedChest.containsKey(id)) savedChest.put(id, p.getInventory().getChestplate());
        // 서버가 갑자기 꺼져도 원래 가슴 칸 아이템을 잃지 않게 플레이어 자료에도 적어 둔다 (접속할 때 onJoin 이 되돌린다)
        ItemStack keep = savedChest.get(id);
        p.getPersistentDataContainer().set(chestKey, PersistentDataType.BYTE_ARRAY, keep == null || keep.getType().isAir() ? new byte[0] : keep.serializeAsBytes());
        ItemStack el = new ItemStack(Material.ELYTRA);
        ItemMeta m = el.getItemMeta();
        m.setUnbreakable(true);
        m.getPersistentDataContainer().set(rocketKey, PersistentDataType.BYTE, (byte) 1);
        el.setItemMeta(m);
        p.getInventory().setChestplate(el);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0.2, 0));
        p.setGliding(true);
        pl.net.toast(p, "겉날개가 펴졌습니다. 내려가세요!", true);
        new BukkitRunnable() {
            int t = 0;
            @Override @SuppressWarnings("deprecation") public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null || !savedChest.containsKey(id)) { cancel(); return; }
                t++;
                boolean landed = t > 20 && (q.isOnGround() || q.isInWater() || q.isInLava());
                if (landed || q.isDead() || t > 20 * 180) { takeElytra(q); cancel(); }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    /** 겉날개를 거두고 원래 가슴 칸 아이템을 돌려준다 */
    private void takeElytra(Player p) {
        if (!savedChest.containsKey(p.getUniqueId())) return;
        ItemStack saved = savedChest.remove(p.getUniqueId());
        p.getPersistentDataContainer().remove(chestKey);
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (isRocketElytra(c[i])) p.getInventory().setItem(i, null);
        if (isRocketElytra(p.getInventory().getChestplate())) p.getInventory().setChestplate(null);
        if (saved != null && !saved.getType().isAir()) {
            if (p.getInventory().getChestplate() == null) p.getInventory().setChestplate(saved);
            else Items.give(p, saved);
        }
        p.setGliding(false);
    }

    @EventHandler
    public void onElytraClick(InventoryClickEvent e) {
        if (isRocketElytra(e.getCurrentItem()) || isRocketElytra(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onElytraDrop(PlayerDropItemEvent e) {
        if (isRocketElytra(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!savedChest.containsKey(p.getUniqueId())) return;
        e.getDrops().removeIf(this::isRocketElytra);
        ItemStack saved = savedChest.get(p.getUniqueId());
        takeElytra(p);                                          // 원래 아이템이 인벤토리로 돌아온다
        if (!e.getKeepInventory() && saved != null && !saved.getType().isAir()) e.getDrops().add(saved);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { takeElytra(e.getPlayer()); }

    /** 서버가 갑자기 꺼져서 남은 로켓 겉날개를 접속할 때 거두고, 적어 둔 원래 가슴 칸 아이템을 돌려준다 */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (isRocketElytra(c[i])) p.getInventory().setItem(i, null);
        byte[] raw = p.getPersistentDataContainer().get(chestKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null) return;
        p.getPersistentDataContainer().remove(chestKey);
        if (raw.length == 0) return;
        try {
            ItemStack saved = ItemStack.deserializeBytes(raw);
            if (p.getInventory().getChestplate() == null) p.getInventory().setChestplate(saved);
            else Items.give(p, saved);
        } catch (RuntimeException ex) {
            pl.getLogger().warning("로켓 전 가슴 칸 아이템을 되돌리지 못함 (" + p.getName() + "): " + ex);
        }
    }

    // ── UFO 납치 ─────────────────────────────────────────────
    // 머리 위에 UFO 가 나타나 빔으로 끌어올리고, 10초 동안 가둔 뒤(화면 암전) 반경 300칸 안의 아무 곳에 내려놓는다.
    // 단체 납치는 사람마다 이 함수를 한 번씩 부른다 (내려놓는 곳이 각자 다르다).
    private static final class Ufo { Location origin; boolean held; }
    private final Map<UUID, Ufo> ufos = new HashMap<>();

    void ufo(Player p) {
        final UUID id = p.getUniqueId();
        if (ufos.containsKey(id)) return;                       // 이미 납치 중
        final int hold = Math.max(3, pl.getConfig().getInt("effects.ufo-hold-seconds", 10));
        final int radius = Math.max(20, pl.getConfig().getInt("effects.ufo-radius", 300));
        final Ufo st = new Ufo();
        st.origin = p.getLocation();
        ufos.put(id, st);
        final Location at = st.origin.clone().add(0, hoverHeight(st.origin), 0);
        at.setPitch(0);
        final Models.Prop prop = pl.models.spawn(at, "farm_ufo_beam", "spawn");
        final World w = at.getWorld();
        w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.4f);
        final int liftStart = 20, liftEnd = 70, holdEnd = liftEnd + hold * 20;
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null || q.isDead() || !ufos.containsKey(id)) { prop.remove(); ufos.remove(id); cancel(); return; }
                if (t == liftStart) {
                    prop.play("beam");
                    q.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, liftEnd - liftStart, 2, false, false, false));
                    w.playSound(at, Sound.BLOCK_BEACON_AMBIENT, 2f, 1.6f);
                }
                if (t >= liftStart && t < liftEnd) {
                    // 빔 가운데로 끌어당기며 올린다
                    Location l = q.getLocation();
                    Vector pull = new Vector(at.getX() - l.getX(), 0, at.getZ() - l.getZ());
                    if (pull.lengthSquared() > 0.04) q.setVelocity(q.getVelocity().setX(pull.getX() * 0.15).setZ(pull.getZ() * 0.15));
                    q.setFallDistance(0);
                    w.spawnParticle(Particle.END_ROD, l.clone().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.01);
                    if (t % 10 == 0) w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.8f);
                }
                if (t == liftEnd) {
                    // 빨려 들어갔다: 가둔다 (제자리에 뜬 채 화면 암전, 다른 사람 눈에는 안 보임)
                    st.held = true;
                    q.removePotionEffect(PotionEffectType.LEVITATION);
                    q.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, hold * 20 + 40, 255, false, false, false));   // 255 = 오르지도 내리지도 않고 떠 있음
                    q.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, hold * 20 + 40, 0, false, false, false));
                    pl.effects.apply(q, Effects.BLACKOUT, "UFO 납치", "icons/ufo", hold);
                    w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.6f);
                    prop.play("leave");
                    prop.removeLater(22);
                }
                if (t > liftEnd && t < holdEnd) {
                    q.setFallDistance(0);
                    if (t % 2 == 0) q.setVelocity(new Vector(0, 0, 0));          // 갇혀 있는 동안은 제자리
                    if (t % 40 == 0) q.playSound(q.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1f, 0.6f);
                }
                if (t >= holdEnd) { cancel(); release(q, st, radius, 0); return; }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    /** UFO 를 세울 높이: 빔 길이(5칸)에 맞추되, 머리 위가 막혀 있으면 낮춘다 */
    private static double hoverHeight(Location feet) {
        int free = 0;
        for (int i = 1; i <= 6; i++) {
            if (!feet.clone().add(0, i, 0).getBlock().isPassable()) break;
            free = i;
        }
        return Math.max(2.5, Math.min(5.2, free + 0.2));
    }

    /** 내려놓을 땅을 찾는다 (발을 디딜 자리). 용암 위거나 찾지 못하면 null */
    private static Location landing(World w, int x, int z, int nearY) {
        if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, nearY, z + 0.5))) return null;
        if (!w.hasCeiling()) {
            int y = w.getHighestBlockYAt(x, z);
            if (y <= w.getMinHeight() || w.getBlockAt(x, y, z).getType() == Material.LAVA) return null;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        // 천장이 있는 세계(네더): 원래 높이 근처에서 서 있을 수 있는 빈 자리를 찾는다
        for (int y = Math.min(nearY + 30, 120); y > 32; y--) {
            if (w.getBlockAt(x, y - 1, z).getType().isSolid() && w.getBlockAt(x, y, z).isPassable() && !w.getBlockAt(x, y, z).isLiquid()
                    && w.getBlockAt(x, y + 1, z).isPassable()) return new Location(w, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** 가둬 둔 사람을 반경 안의 아무 곳에 내려놓는다 (그 자리의 지형을 불러온 뒤) */
    private void release(Player q, Ufo st, int radius, int attempt) {
        final UUID id = q.getUniqueId();
        final World w = st.origin.getWorld();
        double ang = rnd().nextDouble(Math.PI * 2), dist = radius * Math.sqrt(rnd().nextDouble(0.04, 1.0));   // 반경 안에 고르게, 너무 가까운 곳(반경의 20% 안)은 뺀다
        final int x = (int) Math.floor(st.origin.getX() + Math.cos(ang) * dist), z = (int) Math.floor(st.origin.getZ() + Math.sin(ang) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead() || !ufos.containsKey(id)) { ufos.remove(id); return; }
            Location ground = landing(w, x, z, st.origin.getBlockY());
            if (ground == null && attempt < 8) { release(p, st, radius, attempt + 1); return; }
            if (ground == null) ground = st.origin.clone();            // 내려놓을 곳을 끝내 못 찾으면 원래 자리
            double up = hoverHeight(ground);
            Location drop = ground.clone().add(0, Math.max(0, up - 1.8), 0);
            drop.setYaw(p.getLocation().getYaw());
            drop.setPitch(0);
            p.removePotionEffect(PotionEffectType.LEVITATION);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.teleport(drop);
            p.setFallDistance(0);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false));
            Location over = ground.clone().add(0, up, 0);
            over.setPitch(0);
            final Models.Prop ship = pl.models.spawn(over, "farm_ufo_beam", "beam");
            w.playSound(over, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 1.4f);
            Bukkit.getScheduler().runTaskLater(pl, () -> { ship.play("leave"); ship.removeLater(22); }, 40L);
            Bukkit.getScheduler().runTaskLater(pl, () -> ufos.remove(id), 60L);      // 내려오는 동안까지는 피해를 받지 않는다
            long far = Math.round(Math.hypot(ground.getX() - st.origin.getX(), ground.getZ() - st.origin.getZ()));
            toastAll(pl.nick(p) + "님이 UFO에 납치되어 " + far + "칸 떨어진 곳에 내려졌습니다.", false);
        });
    }

    /** 납치를 그만둔다 (나감·서버 종료): 걸어 둔 것을 풀고, 가둬 둔 상태였으면 원래 자리로 돌려놓는다 */
    private void cancelUfo(Player p) {
        Ufo st = ufos.remove(p.getUniqueId());
        if (st == null) return;
        p.removePotionEffect(PotionEffectType.LEVITATION);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (st.held) p.teleport(st.origin);
    }

    @EventHandler
    public void onUfoQuit(PlayerQuitEvent e) { cancelUfo(e.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUfoDeath(PlayerDeathEvent e) { ufos.remove(e.getEntity().getUniqueId()); }

    /** 납치되는 동안(끌려 올라감 · 갇힘 · 내려옴)에는 피해를 받지 않는다 */
    @EventHandler(ignoreCancelled = true)
    public void onUfoDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && ufos.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    // ── 모루 낙하 ────────────────────────────────────────────
    // 3초 뒤 지금 서 있는 자리에 큰 모루가 떨어진다. 그때 그 자리에 있으면 5초 기절.
    void anvil(Player p) {
        final UUID id = p.getUniqueId();
        final Location spot = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        final double height = 18;
        final int ticks = 60;
        final int stun = Math.max(1, pl.getConfig().getInt("effects.anvil-stun-seconds", 5));
        final Models.Prop prop = pl.models.spawn(spot.clone().add(0, height, 0), "giant_anvil", "fall");
        pl.net.toast(p, "3초 뒤 머리 위에서 모루가 떨어집니다. 피하세요!", false);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.6f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t < ticks) {
                    double k = (t + 1) / (double) ticks;
                    prop.move(spot.clone().add(0, height * (1 - k * k), 0));       // 점점 빨라지며 떨어진다
                } else if (t == ticks) {
                    prop.move(spot);
                    prop.play("impact");
                    World w = spot.getWorld();
                    w.playSound(spot, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.6f);
                    w.spawnParticle(Particle.CLOUD, spot.clone().add(0, 0.2, 0), 30, 1.2, 0.1, 1.2, 0.05);
                    Player q = Bukkit.getPlayer(id);
                    boolean hit = q != null && !q.isDead() && q.getWorld() == w
                            && Math.abs(q.getLocation().getY() - spot.getY()) < 3
                            && Math.hypot(q.getLocation().getX() - spot.getX(), q.getLocation().getZ() - spot.getZ()) < 1.8;
                    if (hit) {
                        pl.effects.apply(q, Effects.STUN, "기절", "icons/anvil", stun);
                        toastAll(pl.nick(q) + "님이 모루에 맞아 " + stun + "초 동안 기절했습니다.", false);
                    } else if (q != null) {
                        toastAll(pl.nick(q) + "님이 모루를 피했습니다.", true);
                    }
                } else if (t == ticks + 14) {
                    prop.play("vanish");
                } else if (t >= ticks + 38) {
                    prop.remove();
                    cancel();
                }
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    // ── 사신 (4444 즉사) ──────────────────────────────────────
    /** 사신이 앞에 나타나 낫을 휘두르고, 그 순간 즉사한다. 끝날 때까지 걸리는 시간(틱)을 돌려준다 */
    long reaper(Player p) { return reaper(p, null); }

    /** onGone = 낫이 닿기 전에 대상이 나갔을 때 할 일 (후원을 다시 미뤄 두기) */
    long reaper(Player p, Runnable onGone) {
        final UUID id = p.getUniqueId();
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location at = p.getLocation().add(dir.clone().multiply(2.2));
        if (!at.getBlock().isPassable()) at = p.getLocation();
        face(at, dir.clone().multiply(-1));                      // 대상을 바라본다
        final Models.Prop prop = pl.models.spawn(at, "grim_reaper", "spawn");
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.6f);
        Bukkit.getScheduler().runTaskLater(pl, () -> prop.play("attack"), 30L);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q == null) { if (onGone != null) onGone.run(); return; }
            if (!q.isDead()) {
                q.getWorld().strikeLightningEffect(q.getLocation());
                reaped.add(q.getUniqueId());                    // 즉사로 죽은 것은 사망 벌칙(목표 증가)에서 뺀다
                q.setHealth(0);
                Bukkit.getScheduler().runTaskLater(pl, () -> reaped.remove(id), 20L);   // 죽지 않았으면(무적 등) 표시를 지운다
                for (Player o : Bukkit.getOnlinePlayers()) pl.net.death(o, pl.nick(q));   // 화면 연출 (모드가 없는 사람은 아래 알림 글)
                for (Player o : Bukkit.getOnlinePlayers()) if (!pl.net.hasMod(o)) pl.net.toast(o, pl.nick(q) + "님이 즉사했습니다.", false);
            }
        }, 30L + 18L);
        Bukkit.getScheduler().runTaskLater(pl, () -> prop.play("leave"), 30L + 34L);
        prop.removeLater(30L + 34L + 26L);
        return 30L + 34L + 26L;
    }

    /** 서버가 꺼질 때: 까마귀를 치우고(훔친 다이아는 돌려줌) 겉날개를 거둔다 */
    void shutdown() {
        for (Map.Entry<UUID, Crow> e : new ArrayList<>(crows.entrySet())) {
            Crow c = e.getValue();
            Player v = Bukkit.getPlayer(c.victim);
            if (v != null && c.stolen > 0 && !c.done) pl.ledger.addHeld(v, c.stolen);
            c.done = true;
            c.prop.remove();
        }
        crows.clear();
        for (Player p : Bukkit.getOnlinePlayers()) { takeElytra(p); cancelUfo(p); }
    }
}
