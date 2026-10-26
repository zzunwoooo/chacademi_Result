package school.chacademia.magicmythic;

import java.util.*;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * chanova types of the 12 extra 생활·편의 spells (2026-10-25 late, /home/claude/vfx/spells12.md) — the 10 that live here
 * (scale_appraisal / enlighten_hint are MagicCodexBridge LifeSpells). Rows: tools/nova/life3.py.
 *  - ward_candle: vanilla natural hostile spawns are cancelled here (CreatureSpawnEvent); ChAcademiaWildlife (creature-spawns-paper)
 *    asks every world's Bukkit metadata {@value State#WARD_KEY} (a Predicate&lt;Location&gt;, true = blocked) in its habitat check.
 *  - sweet_sleep: Links.regen(id, "sweet_sleep", 1.2) for 10 min after a real sleep; the `wake` visual is played straight through
 *    PortableVfxService.follow (the wait can be 30 min, longer than a catalog cast may hold).
 * All state is in memory (a restart / server switch forgets it).
 */
final class NovaLife3 {
    private NovaLife3() {}

    static void register(Map<String, NovaMech.Impl> m) {
        // 사뿐한 발걸음 (LightStep): 10 min — farmland I step / land on stays farmland, cobweb / powder snow / berry bush don't hold me;
        // `step` at the feet at most every 0.5 s while walking on the ground
        m.put("light_step", n -> {
            Player pl = n.pl;
            UUID id = pl.getUniqueId();
            int ticks = n.i("ticks", 12000);
            long until = now() + ticks;
            int gen = State.stepGen.merge(id, 1, Integer::sum);
            State.step.put(id, until);
            n.info("10분 동안 발걸음이 가벼워져요");
            Location[] last = {pl.getLocation()};
            n.loop(ticks, age -> {
                if (!Objects.equals(State.stepGen.get(id), gen)) return false;
                if (age % 10 != 0) return true;
                Location at = pl.getLocation();
                boolean moved = at.getWorld() == last[0].getWorld()
                    && Math.pow(at.getX() - last[0].getX(), 2) + Math.pow(at.getZ() - last[0].getZ(), 2) > 0.25;
                @SuppressWarnings("deprecation") boolean ground = pl.isOnGround();
                if (moved && ground && !pl.isFlying() && pl.getVehicle() == null) n.fx("step", at);
                last[0] = at;
                return true;
            }, null);
            return true;
        });

        // 몰이 바람 (HerdingBreeze): up to 12 farm animals within 12 (not leashed, not someone else's pet, not custom mobs) follow me 30 s.
        // The Bridge cooldown is cleared while they follow so the key can be pressed again: a second press releases them for free;
        // the real cooldown (ours) starts when the herd ends, like 168 shadow_double.
        m.put("herding_breeze", n -> {
            Player pl = n.pl;
            UUID id = pl.getUniqueId();
            double mana = n.d("mana", 30), r = n.d("r", 12);
            int ticks = n.i("ticks", 600), max = n.i("max", 12);
            Herd old = State.herds.get(id);
            if (old != null) {
                old.release();
                n.refund(mana); n.clearCooldown();
                n.info("동물들을 풀어 줬어요");
                return true;
            }
            Long cd = State.herdCooldown.get(id);
            if (cd != null && cd > now()) {
                n.warn("재사용 대기 " + (int) Math.ceil((cd - now()) / 20.0) + "초");
                n.refund(mana); n.clearCooldown();
                return false;
            }
            Location c = pl.getLocation();
            List<Mob> found = new ArrayList<>();
            for (Entity e : pl.getNearbyEntities(r, r, r)) {
                if (!(e instanceof Mob mob) || !herdable(n, mob) || e.getLocation().distanceSquared(c) > r * r) continue;
                found.add(mob);
            }
            if (found.isEmpty()) { n.warn("주변에 데려갈 동물이 없어요"); n.refund(mana); n.clearCooldown(); return false; }
            found.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(c)));
            if (found.size() > max) found = new ArrayList<>(found.subList(0, max));
            Herd h = new Herd(id, found, Math.round(n.d("cooldown", 60) * 20));
            State.herds.put(id, h);
            for (Mob mob : found) { State.herded.add(mob.getUniqueId()); n.fx("lead", mob.getLocation(), mob); }
            n.clearCooldown();
            n.info(found.size() + "마리가 30초 동안 따라와요 (다시 쓰면 풀어 줘요)");
            double speed = n.d("speed", 1.25);
            n.loop(ticks, age -> {
                if (h.done) return false;
                if (age >= ticks) return false;
                if (age % 10 != 0) return true;
                Location at = pl.getLocation();
                h.mobs.removeIf(mob -> {
                    boolean gone = !mob.isValid() || mob.isDead() || mob.isLeashed() || mob.getWorld() != at.getWorld()
                        || mob.getLocation().distanceSquared(at) > 30 * 30;
                    if (gone) { State.herded.remove(mob.getUniqueId()); if (mob.isValid()) mob.getPathfinder().stopPathfinding(); }
                    return gone;
                });
                if (h.mobs.isEmpty()) return false;
                for (Mob mob : h.mobs) {
                    if (mob.getLocation().distanceSquared(at) > 9) mob.getPathfinder().moveTo(at, speed);
                    else mob.getPathfinder().stopPathfinding();
                }
                return true;
            }, h::release);
            return true;
        });

        // 모닥불 쉼터 (CampfireRest): 60 s at the cast point (no block). Every 2 s every party member (me included) within 5 who has not
        // dealt or taken entity damage in the last 5 s: +1 health, +1 food; `warm` on each one it helped
        m.put("campfire_rest", n -> {
            Location at = n.pl.getLocation();
            int ticks = n.i("ticks", 1200), every = n.i("every", 40), calm = n.i("calm", 100);
            double r = n.d("r", 5), heal = n.d("heal", 1);
            int food = n.i("food", 1);
            n.info("60초 동안 모닥불 곁에서 쉴 수 있어요");
            n.loop(ticks, age -> {
                if (age >= ticks) return false;
                if (age == 0 || age % every != 0) return true;
                for (Player p : n.allies(at, r, 16)) {
                    if (p.isDead() || now() - State.combat.getOrDefault(p.getUniqueId(), Long.MIN_VALUE / 2) < calm) continue;
                    boolean helped = false;
                    if (Targets.heal(n.pl, p, heal) > 0) helped = true;   // 회복봉인 / max health handled there
                    if (p.getFoodLevel() < 20) { p.setFoodLevel(Math.min(20, p.getFoodLevel() + food)); helped = true; }
                    if (helped) n.fx("warm", p.getLocation(), p);
                }
                return true;
            }, null);
            return true;
        });

        // 결계 향초 (WardCandle): 10 min, radius 24 around the cast point: no natural hostile spawns (vanilla + 차카데미 야생). One per player.
        m.put("ward_candle", n -> {
            Location at = n.pl.getLocation();
            int ticks = n.i("ticks", 12000);
            double r = n.d("r", 24);
            State.wards.put(n.pl.getUniqueId(), new Ward(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), r * r, now() + ticks));
            State.publishWard(n.plugin, at.getWorld());
            n.info("10분 동안 반경 24칸에 몬스터가 생겨나지 않아요");
            return true;
        });

        // 물병 가득 (BottleFill): every plain glass bottle in the inventory becomes a water bottle (refused when there is none)
        m.put("bottle_fill", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 15);
            var inv = pl.getInventory();
            int total = 0;
            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType() != Material.GLASS_BOTTLE || it.hasItemMeta()) continue;
                total += it.getAmount();
                inv.setItem(slot, water());
                for (int k = 1; k < it.getAmount(); k++)
                    for (ItemStack left : inv.addItem(water()).values()) pl.getWorld().dropItemNaturally(pl.getLocation(), left);
            }
            if (total == 0) { n.warn("빈 유리병이 없어요"); n.refund(mana); n.clearCooldown(); return false; }
            n.info("빈 병 " + total + "개에 물을 채웠어요");
            return true;
        });

        // 작물 살피기 (CropSurvey): crops within 12 (h ±4, loaded chunks only, one read): counts per crop + % fully grown;
        // `ripe` above up to 40 nearest fully grown crops when the sprout wave (1.5 s over 12 m) reaches them
        m.put("crop_survey", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 15);
            int r = n.i("r", 12), h = n.i("h", 4), marks = n.i("marks", 40);
            Location c = pl.getLocation();
            World w = c.getWorld();
            int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
            Map<Material, int[]> count = new EnumMap<>(Material.class);
            List<Block> ripe = new ArrayList<>();
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(w.getMinHeight(), cy - h); y <= Math.min(w.getMaxHeight() - 1, cy + h); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!CROP_NAMES.containsKey(b.getType()) || !(b.getBlockData() instanceof Ageable a)) continue;
                    int[] k = count.computeIfAbsent(b.getType(), t -> new int[2]);
                    k[0]++;
                    if (a.getAge() >= a.getMaximumAge()) { k[1]++; ripe.add(b); }
                }
            }
            if (count.isEmpty()) { n.warn("주변 12칸에 작물이 없어요"); n.refund(mana); n.clearCooldown(); return false; }
            int all = 0, grown = 0;
            List<String> lines = new ArrayList<>();
            lines.add("── 작물 살피기 (반경 " + r + ") ──");
            for (var e : count.entrySet()) {
                int[] k = e.getValue(); all += k[0]; grown += k[1];
                lines.add(" " + CROP_NAMES.get(e.getKey()) + " " + k[0] + "개 · 다 자람 " + k[1] + "개 (" + Math.round(k[1] * 100.0 / k[0]) + "%)");
            }
            n.info("작물 " + all + "개 중 " + grown + "개가 다 자랐어요");
            for (String l : lines) pl.sendMessage(l);
            ripe.sort(Comparator.comparingDouble(b -> b.getLocation().add(.5, 0, .5).distanceSquared(c)));
            for (int i = 0; i < Math.min(marks, ripe.size()); i++) {
                Block b = ripe.get(i);
                Location top = b.getLocation().add(.5, .9, .5);
                int delay = (int) Math.round(Math.min(1, Math.sqrt(top.distanceSquared(c)) / r) * 30);
                n.later(delay, () -> n.fx("ripe", top));
            }
            return true;
        });

        // 되심기 손길 (ReplantTouch): 60 s — a fully grown wheat / carrot / potato / beetroot / nether wart I break gives one seed of
        // its own drops back to the ground: replanted at age 0 one tick later (soil still there), `sprout` there (≤ 4 a second)
        m.put("replant_touch", n -> {
            int ticks = n.i("ticks", 1200);
            State.replant.put(n.pl.getUniqueId(), new Replant(n, now() + ticks));
            n.info("60초 동안 다 자란 작물을 거두면 그 자리에 다시 심어요");
            return true;
        });

        // 기억의 등불 (MemoryLantern): direction and distance to where I last died (same world); `beam` + `guide` there
        m.put("memory_lantern", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 20);
            Location d = State.deaths.get(pl.getUniqueId());
            if (d == null || d.getWorld() == null) { n.warn("아직 쓰러진 기록이 없어요"); n.refund(mana); n.clearCooldown(); return false; }
            if (d.getWorld() != pl.getWorld()) { n.warn("마지막으로 쓰러진 곳은 다른 세계예요"); n.refund(mana); n.clearCooldown(); return false; }
            Location at = pl.getLocation();
            double dx = d.getX() - at.getX(), dz = d.getZ() - at.getZ();
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            String where = dist < 3 ? "바로 여기" : compass(dx, dz) + " " + dist + "칸 (" + relative(at.getYaw(), dx, dz) + ")";
            n.info("마지막으로 쓰러진 곳: " + where);
            pl.sendMessage("[기억의 등불] 마지막으로 쓰러진 곳: " + where + " · 좌표 " + d.getBlockX() + ", " + d.getBlockY() + ", " + d.getBlockZ());
            n.fx("beam", d);
            n.fx("guide", d);
            return true;
        });

        // 그림자 금고 (ShadowVault): my ender chest, anywhere (opens when the vault door is open, +0.3 s)
        m.put("shadow_vault", n -> {
            Player pl = n.pl;
            n.later(6, () -> { if (!pl.isDead()) pl.openInventory(pl.getEnderChest()); });
            return true;
        });

        // 단잠의 주문 (SweetSleep): the next real sleep within 30 min → 10 min mana regeneration x1.2 (refused while armed or active)
        m.put("sweet_sleep", n -> {
            Player pl = n.pl;
            UUID id = pl.getUniqueId();
            double mana = n.d("mana", 30);
            if (State.sleepArmed.getOrDefault(id, 0L) > now() || State.sleepBuff.containsKey(id)) {
                n.warn("이미 단잠의 주문이 걸려 있어요"); n.refund(mana); n.clearCooldown(); return false;
            }
            State.sleepArmed.put(id, now() + n.i("wait", 36000));
            State.sleepTicks.put(id, n.i("ticks", 12000));
            State.sleepMult.put(id, n.d("regen", 1.2));
            n.info("30분 안에 침대에서 자고 일어나면 10분 동안 마나가 더 빨리 차요");
            return true;
        });
    }

    static void enable(MagicMythicPlugin plugin) {
        State.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new State(), plugin);
    }

    // ------------------------------------------------------------------ helpers

    static long now() { return Bukkit.getCurrentTick(); }

    static final Map<Material, String> CROP_NAMES = new EnumMap<>(Map.of(
        Material.WHEAT, "밀", Material.CARROTS, "당근", Material.POTATOES, "감자", Material.BEETROOTS, "비트",
        Material.NETHER_WART, "네더 사마귀", Material.COCOA, "코코아", Material.SWEET_BERRY_BUSH, "달콤한 열매 덤불",
        Material.TORCHFLOWER_CROP, "횃불꽃", Material.PITCHER_CROP, "벌레잡이풀"));
    /** 되심기: crop block → the seed item its drops contain. */
    static final Map<Material, Material> SEEDS = new EnumMap<>(Map.of(
        Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO,
        Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART));

    private static ItemStack water() {
        ItemStack it = new ItemStack(Material.POTION);
        if (it.getItemMeta() instanceof PotionMeta pm) { pm.setBasePotionType(PotionType.WATER); it.setItemMeta(pm); }
        return it;
    }

    /** Farm animals only: no custom (Mythic / NPC) mobs, nothing leashed, no pet of another player, not already herded. */
    private static boolean herdable(Nova n, Mob mob) {
        if (!(mob instanceof Cow || mob instanceof Sheep || mob instanceof Pig || mob instanceof Chicken || mob instanceof AbstractHorse
            || mob instanceof Rabbit || mob instanceof Goat)) return false;
        if (!mob.isValid() || mob.isDead() || mob.isLeashed() || mob.hasMetadata("NPC") || State.herded.contains(mob.getUniqueId())) return false;
        if (mob instanceof Tameable t && t.getOwnerUniqueId() != null && !t.getOwnerUniqueId().equals(n.pl.getUniqueId())) return false;
        if (Targets.mythicType(mob) != null) return false;
        return n.plugin.protection().at(mob.getLocation());
    }

    private static final String[] DIRS = {"남쪽", "남서쪽", "서쪽", "북서쪽", "북쪽", "북동쪽", "동쪽", "남동쪽"};
    /** World direction of (dx, dz): Minecraft +z = south, +x = east. */
    static String compass(double dx, double dz) {
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));   // the yaw a player would face to look there
        return DIRS[Math.floorMod((int) Math.round(yaw / 45.0), 8)];
    }
    /** Where (dx, dz) is from the player's facing. */
    static String relative(float facing, double dx, double dz) {
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double d = ((yaw - facing) % 360 + 540) % 360 - 180;   // -180..180, + = to the right
        double a = Math.abs(d);
        if (a <= 30) return "앞쪽";
        if (a >= 150) return "뒤쪽";
        String side = d > 0 ? "오른쪽" : "왼쪽";
        return a < 70 ? side + " 앞" : a > 110 ? side + " 뒤" : side;
    }

    /** A PortableVFX system (claude:pack/system) following the player, outside any catalog cast (sweet_sleep `wake`). */
    static void follow(Player p, String id, int ticks) {
        var pv = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (pv == null || !pv.isEnabled() || !p.isValid()) return;
        try {
            ClassLoader loader = pv.getClass().getClassLoader();
            Class<?> api = Class.forName("dev.portablevfx.paper.api.PortableVfxService", true, loader);
            Class<?> req = Class.forName("dev.portablevfx.paper.api.EffectRequest", true, loader);
            var reg = Bukkit.getServicesManager().getRegistration(api);
            if (reg == null) return;
            Object service = reg.getProvider();
            Location at = p.getLocation();
            Object request = req.getConstructor(String.class, String.class, double.class, double.class, double.class, float.class, int.class,
                    float.class, float.class, float.class, int.class, float.class, double.class)
                .newInstance(id, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), 1f, ticks, 0f, 0f, 0f, 0xFFFFFF, 1f, 64d);
            Object result = api.getMethod("follow", req, UUID.class).invoke(service, request, p.getUniqueId());
            try {
                UUID handle = (UUID) result.getClass().getMethod("handle").invoke(result);
                if (handle != null) api.getMethod("owner", UUID.class, UUID.class).invoke(service, handle, p.getUniqueId());
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    // ------------------------------------------------------------------ state

    record Ward(String world, double x, double y, double z, double r2, long until) {
        boolean covers(Location l, long now) {
            if (until <= now || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= r2;
        }
    }
    record Replant(Nova n, long until) {}

    static final class Herd {
        final UUID owner; final List<Mob> mobs; final long cooldown; boolean done;
        Herd(UUID owner, List<Mob> mobs, long cooldown) { this.owner = owner; this.mobs = new ArrayList<>(mobs); this.cooldown = cooldown; }
        void release() {
            if (done) return;
            done = true;
            for (Mob mob : mobs) { State.herded.remove(mob.getUniqueId()); if (mob.isValid()) mob.getPathfinder().stopPathfinding(); }
            mobs.clear();
            if (State.herds.get(owner) == this) State.herds.remove(owner);
            State.herdCooldown.put(owner, now() + cooldown);
        }
    }

    static final class State implements Listener {
        static final String WARD_KEY = "chacademia_spawn_ward";
        static MagicMythicPlugin plugin;
        static final Map<UUID, Long> step = new HashMap<>();
        static final Map<UUID, Integer> stepGen = new HashMap<>();
        static final Map<UUID, Herd> herds = new HashMap<>();
        static final Map<UUID, Long> herdCooldown = new HashMap<>();
        static final Set<UUID> herded = new HashSet<>();
        static final Map<UUID, Long> combat = new HashMap<>();
        static final Map<UUID, Ward> wards = new HashMap<>();
        static final Map<UUID, Replant> replant = new HashMap<>();
        static final Map<UUID, Long> sproutShown = new HashMap<>();
        static final Map<UUID, Location> deaths = new HashMap<>();
        static final Map<UUID, Long> sleepArmed = new HashMap<>();
        static final Map<UUID, Integer> sleepTicks = new HashMap<>();
        static final Map<UUID, Double> sleepMult = new HashMap<>();
        static final Map<UUID, Long> sleepEnter = new HashMap<>();
        /** player → token of the running 10 min buff (a later buff replaces it). */
        static final Map<UUID, Object> sleepBuff = new HashMap<>();
        private static final Set<String> publishedWorlds = new HashSet<>();

        static boolean stepping(Player p) { Long t = step.get(p.getUniqueId()); return t != null && t > now(); }

        /** True when a live ward candle covers this point (also read by ChAcademiaWildlife through world metadata). */
        static boolean warded(Location l) {
            if (wards.isEmpty() || plugin == null || !plugin.isEnabled()) return false;
            long now = now();
            wards.values().removeIf(w -> w.until() <= now);
            for (Ward w : wards.values()) if (w.covers(l, now)) return true;
            return false;
        }
        static void publishWard(MagicMythicPlugin plugin, World w) {
            if (w == null || !publishedWorlds.add(w.getName()) && w.hasMetadata(WARD_KEY)) return;
            Predicate<Location> blocked = State::warded;
            w.setMetadata(WARD_KEY, new FixedMetadataValue(plugin, blocked));
        }

        // ---- 사뿐한 발걸음
        @EventHandler(priority = EventPriority.HIGH)
        public void trample(PlayerInteractEvent e) {
            if (e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.FARMLAND) return;
            if (stepping(e.getPlayer())) { e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); e.setCancelled(true); }
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void trampleChange(EntityChangeBlockEvent e) {
            if (e.getEntity() instanceof Player p && e.getBlock().getType() == Material.FARMLAND && e.getTo() == Material.DIRT && stepping(p)) e.setCancelled(true);
        }
        @EventHandler(ignoreCancelled = true)
        public void inside(io.papermc.paper.event.entity.EntityInsideBlockEvent e) {
            if (step.isEmpty() || !(e.getEntity() instanceof Player p)) return;
            Material t = e.getBlock().getType();
            if ((t == Material.COBWEB || t == Material.POWDER_SNOW || t == Material.SWEET_BERRY_BUSH) && stepping(p)) e.setCancelled(true);
        }

        // ---- 모닥불 쉼터: entity damage dealt or taken
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void fight(EntityDamageByEntityEvent e) {
            if (e.getEntity() instanceof Player v) combat.put(v.getUniqueId(), now());
            Entity d = e.getDamager();
            if (d instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Entity se) d = se; }
            if (d instanceof Player a && a != e.getEntity()) combat.put(a.getUniqueId(), now());
        }

        // ---- 결계 향초: vanilla natural hostile spawns
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void spawn(CreatureSpawnEvent e) {
            if (wards.isEmpty() || !(e.getEntity() instanceof Enemy)) return;
            switch (e.getSpawnReason()) {
                case NATURAL, DEFAULT, REINFORCEMENTS, PATROL -> { if (warded(e.getLocation())) e.setCancelled(true); }
                default -> {}
            }
        }

        // ---- 되심기 손길
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void drops(BlockDropItemEvent e) {
            if (replant.isEmpty()) return;
            Player p = e.getPlayer();
            Replant r = replant.get(p.getUniqueId());
            if (r == null) return;
            if (r.until() <= now()) { replant.remove(p.getUniqueId()); return; }
            BlockState st = e.getBlockState();
            Material crop = st.getType(), seed = SEEDS.get(crop);
            BlockData data = st.getBlockData();
            if (seed == null || !(data instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return;
            Item from = null;
            for (Item it : e.getItems()) { ItemStack s = it.getItemStack(); if (s.getType() == seed && !s.hasItemMeta() && s.getAmount() > 0) { from = it; break; } }
            if (from == null) return;
            ItemStack s = from.getItemStack();
            if (s.getAmount() <= 1) e.getItems().remove(from);
            else { s.setAmount(s.getAmount() - 1); from.setItemStack(s); }
            Block b = e.getBlock();
            Material soil = crop == Material.NETHER_WART ? Material.SOUL_SAND : Material.FARMLAND;
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (b.getType().isAir() && b.getRelative(0, -1, 0).getType() == soil && plugin.protection().at(b.getLocation())) {
                    b.setType(crop);
                    long last = sproutShown.getOrDefault(id, Long.MIN_VALUE / 2);
                    if (now() - last >= 5 && p.isOnline()) { sproutShown.put(id, now()); r.n().fx("sprout", b.getLocation().add(.5, 0, .5)); }
                } else {
                    b.getWorld().dropItemNaturally(b.getLocation().add(.5, .3, .5), new ItemStack(seed));
                }
            });
        }

        // ---- 기억의 등불
        @EventHandler(priority = EventPriority.MONITOR)
        public void death(PlayerDeathEvent e) { deaths.put(e.getEntity().getUniqueId(), e.getEntity().getLocation().clone()); }

        // ---- 단잠의 주문
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void bed(PlayerBedEnterEvent e) {
            if (e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) sleepEnter.put(e.getPlayer().getUniqueId(), e.getPlayer().getWorld().getFullTime());
        }
        @EventHandler(priority = EventPriority.MONITOR)
        public void wake(PlayerBedLeaveEvent e) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            Long enter = sleepEnter.remove(id);
            Long armed = sleepArmed.get(id);
            if (armed == null) return;
            if (armed <= now()) { sleepArmed.remove(id); return; }
            // a real sleep: the full 100 sleep ticks, or the night was skipped while in bed
            boolean slept = p.getSleepTicks() >= 100 || enter != null && p.getWorld().getFullTime() - enter >= 1000;
            if (!slept) return;
            sleepArmed.remove(id);
            int ticks = sleepTicks.getOrDefault(id, 12000);
            double mult = sleepMult.getOrDefault(id, 1.2);
            Object token = new Object();
            sleepBuff.put(id, token);
            Links.regen(id, "sweet_sleep", mult);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                follow(p, "claude:sweetsleep/wake", 40);
                ChacaLink.notify(p, "system", "magic-mythic:sweet_sleep", "푹 잤어요", "10분 동안 마나 회복 +" + Math.round((mult - 1) * 100) + "%", 3000);
            });
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sleepBuff.get(id) != token) return;
                sleepBuff.remove(id);
                Links.regen(id, "sweet_sleep", 1);
                if (p.isOnline()) ChacaLink.notify(p, "system", "magic-mythic:sweet_sleep_end", "단잠의 기운이 사라졌어요", "", 2000);
            }, ticks);
        }

        @EventHandler public void quit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            step.remove(id); stepGen.remove(id);
            Herd h = herds.get(id); if (h != null) h.release();
            combat.remove(id); replant.remove(id); sproutShown.remove(id);
            sleepArmed.remove(id); sleepTicks.remove(id); sleepMult.remove(id); sleepEnter.remove(id);
            if (sleepBuff.remove(id) != null) Links.regen(id, "sweet_sleep", 1);
            // deaths / wards stay (a ward is a place; the death point is asked again after a re-join on this server)
        }
    }
}
