package school.chacademia.wildlife;

import io.lumine.mythic.api.mobs.entities.SpawnReason;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 살아나는 작물: 다 자란 바닐라 작물을 수확하면 확률로 작물 몹(CA_Crop_<id>)이 땅에서 기어나온다.
 * 서버 부담: 수확 1회당 맵 조회 1번 + 확률 1번, 몹 1마리당 지연 작업 1개(수명 끝 제거)뿐. 매 틱 작업 없음.
 * 수확물은 절대 잃지 않는다 (2026-10-21): 기본(cancel-drops: false)은 원래 드롭이 그대로 떨어지고 몹은 보너스.
 * cancel-drops: true 이면 원래 드롭 사본을 몹이 들고 있다가 소환 실패·수확자 접속 종료·청크 언로드·사망·수명 끝 중
 * 먼저 일어난 한 번에만 돌려준다 (서버 비정상 종료 30초 창은 개체가 비영속이라 예외).
 *
 * <p>다른 플러그인 연동 (2026-10-25, 마법 385 수확제의 종 · 387 작물 붙잡기). 컴파일 의존 없이 수확자 Player 의 Bukkit 메타데이터로:
 * <ul>
 *   <li>{@value #CHANCE_KEY}: 숫자 값 → 이 사람의 깨어남 확률 × 값 (여러 플러그인이 넣으면 가장 큰 값, 1~10)</li>
 *   <li>{@value #SPAWNED_KEY}: {@code java.util.function.Consumer<Entity>} 값 → 이 사람의 수확으로 작물 몹이 나온 그 틱에 몹을 넘겨 부름</li>
 * </ul>
 * 메타데이터를 넣은 플러그인이 기간이 끝나면 지운다.
 */
final class CropAwakening implements Listener {
    static final String CHANCE_KEY = "chacademia_crop_chance";
    static final String SPAWNED_KEY = "chacademia_crop_spawned";
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final WildlifePlugin plugin;
    private final Map<UUID, Set<UUID>> owned = new HashMap<>();
    private final Map<UUID, Integer> pending = new HashMap<>();
    /** 깨어남이 정해진 수확 블록 → 같은 틱 BlockDropItemEvent 에서 잡은 원래 드롭 사본. 다음 틱 소환 작업이 꺼낸다. */
    private final Map<Location, List<ItemStack>> breaking = new HashMap<>();
    /** 원래 드롭을 들고 있는 작물 몹. 꺼내는 쪽(remove)이 한 번만 돌려준다. */
    private record Held(UUID owner, Location origin, List<ItemStack> drops) {}
    private final Map<UUID, Held> held = new HashMap<>();
    private Map<Material, String> crops = Map.of();

    CropAwakening(WildlifePlugin plugin) { this.plugin = plugin; }

    void reload() {
        var config = plugin.getConfig();
        String path = "crop-awakening.crops";
        // JavaPlugin defaults supply scalar getters, but an absent section is materialized empty by
        // getConfigurationSection and getKeys(false) does not enumerate inherited defaults.
        // Check direct presence before requesting a section; explicit empty/custom maps remain authoritative.
        var section = config.isSet(path) ? config.getConfigurationSection(path)
            : config.getDefaults() == null ? null : config.getDefaults().getConfigurationSection(path);
        var next = new EnumMap<Material, String>(Material.class);
        if (section != null) for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) { plugin.getLogger().warning("crop-awakening: 알 수 없는 블록 " + key); continue; }
            next.put(m, section.getString(key));
        }
        crops = next;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void harvest(BlockBreakEvent e) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("crop-awakening.enabled", true)) return;
        Block b = e.getBlock();
        String id = crops.get(b.getType());
        if (id == null) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        var worlds = cfg.getStringList("crop-awakening.worlds");
        if (!worlds.isEmpty() && !worlds.contains(b.getWorld().getName())) return;
        if (!mature(b)) return;
        var random = ThreadLocalRandom.current();
        if (random.nextDouble() >= cfg.getDouble("crop-awakening.chance", 0.05) * chanceMultiplier(p)) return;

        UUID ownerId = p.getUniqueId();
        if (activeCount(ownerId) + pending.getOrDefault(ownerId, 0) >= cfg.getInt("crop-awakening.max-per-player", 3)) return;

        int denom = cfg.getInt("crop-awakening.shiny-denominator", cfg.getInt("shiny-denominator", 4096));
        boolean shiny = random.nextInt(Math.max(1, denom)) == 0;
        if (MythicBukkit.inst().getMobManager().getMythicMob("CA_Crop_" + id + (shiny ? "_shiny" : "")).isEmpty()) {
            plugin.getLogger().warning("crop-awakening: 미몹 없음 CA_Crop_" + id); return;
        }
        Location key = b.getLocation();
        Location at = key.clone().add(0.5, 0, 0.5);
        // cancel-drops: 드롭을 지우지 않고 BlockDropItemEvent 에서 사본으로 옮겨 둔다 (몹이 못 받으면 그대로 돌려줌)
        List<ItemStack> drops = new ArrayList<>();
        if (cfg.getBoolean("crop-awakening.cancel-drops", false)) breaking.put(key, drops);
        // 블록이 실제로 부서진 다음 틱에 소환
        pending.merge(ownerId, 1, Integer::sum);
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    breaking.remove(key);
                    String result = p.isOnline() && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                        ? spawn(p, id, shiny, at, drops, true) : "수확한 사람 없음 또는 청크 언로드";
                    if (!"OK".equals(result)) dropAt(at, drops);
                } finally { releasePending(ownerId); }
            });
        } catch (RuntimeException error) { breaking.remove(key); releasePending(ownerId); throw error; }
    }

    /** 깨어날 작물의 원래 드롭(다른 플러그인이 더한 것 포함)을 사본으로 옮기고 이번 드롭은 지운다. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drops(BlockDropItemEvent e) {
        if (breaking.isEmpty()) return;
        List<ItemStack> drops = breaking.get(e.getBlock().getLocation());
        if (drops == null) return;
        for (Item item : e.getItems()) drops.add(item.getItemStack().clone());
        e.getItems().clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        if (held.isEmpty()) return;
        Held h = held.remove(e.getEntity().getUniqueId());
        // 미몹 PreventOtherDrops 가 드롭 목록을 비울 수 있으니 목록에 넣지 않고 직접 떨어뜨린다
        if (h != null) dropAt(e.getEntity().getLocation(), h.drops());
    }

    @EventHandler
    public void unload(EntitiesUnloadEvent e) {
        if (held.isEmpty()) return;
        for (Entity x : e.getEntities()) {
            Held h = held.remove(x.getUniqueId());
            if (h == null) continue;
            // 닫히는 청크에 떨어뜨리지 않는다: 수확자 인벤토리(넘치면 발밑), 접속 중이 아니면 다음 틱에 원래 자리
            Player owner = Bukkit.getPlayer(h.owner());
            if (owner != null && owner.isOnline()) {
                var copies = h.drops().stream().map(ItemStack::clone).toArray(ItemStack[]::new);
                for (ItemStack left : owner.getInventory().addItem(copies).values()) owner.getWorld().dropItemNaturally(owner.getLocation(), left);
            } else Bukkit.getScheduler().runTask(plugin, () -> dropAt(h.origin(), h.drops()));
        }
    }

    /** 수확자가 나가면 그 사람의 작물 몹은 원래 드롭을 그 자리에 돌려주고 사라진다. */
    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Set<UUID> mine = owned.remove(e.getPlayer().getUniqueId());
        if (mine == null) return;
        for (UUID u : mine) {
            Entity x = Bukkit.getEntity(u);
            Held h = held.remove(u);
            if (h != null) dropAt(x != null ? x.getLocation() : h.origin(), h.drops());
            if (x != null && x.isValid()) x.remove();
        }
    }

    /** 플러그인 종료: 들고 있던 드롭을 전부 그 자리에 돌려준다 (비영속 몹은 어차피 사라짐). */
    void close() {
        for (var e : List.copyOf(held.entrySet())) {
            held.remove(e.getKey());
            Entity x = Bukkit.getEntity(e.getKey());
            dropAt(x != null && x.isValid() ? x.getLocation() : e.getValue().origin(), e.getValue().drops());
        }
    }

    private static void dropAt(Location where, List<ItemStack> drops) {
        if (drops.isEmpty() || where == null || where.getWorld() == null) return;
        World w = where.getWorld();
        if (!w.isChunkLoaded(where.getBlockX() >> 4, where.getBlockZ() >> 4)) w.getChunkAt(where);
        for (ItemStack stack : drops) w.dropItemNaturally(where, stack.clone());
    }

    /** 작물 몹 소환(수확·테스트 명령 공용). 수확한 사람을 바라보며 나오고, 수명이 끝나면 사라진다. */
    private void releasePending(UUID ownerId) {
        pending.computeIfPresent(ownerId, (key, value) -> value <= 1 ? null : value - 1);
    }

    private int activeCount(UUID ownerId) {
        Set<UUID> mine = owned.get(ownerId);
        if (mine == null) return 0;
        mine.removeIf(u -> { Entity x = Bukkit.getEntity(u); return x == null || !x.isValid() || x.isDead(); });
        if (mine.isEmpty()) owned.remove(ownerId);
        return mine.size();
    }

    String spawn(Player owner, String id, boolean shiny, Location at) { return spawn(owner, id, shiny, at, List.of(), false); }

    /**
     * drops: 이 몹이 대신 들고 있을 원래 수확물(사본). 성공("OK")일 때만 몹이 맡고, 실패면 호출한 쪽이 돌려준다.
     * harvested: 실제 수확으로 깨어남 → 수확자의 {@value #SPAWNED_KEY} 연동을 부른다 (테스트 명령은 부르지 않음).
     */
    String spawn(Player owner, String id, boolean shiny, Location at, List<ItemStack> drops, boolean harvested) {
        if (activeCount(owner.getUniqueId()) >= plugin.getConfig().getInt("crop-awakening.max-per-player", 3)) return "동시 작물 개체 제한";
        var type = MythicBukkit.inst().getMobManager().getMythicMob("CA_Crop_" + id + (shiny ? "_shiny" : ""));
        if (type.isEmpty()) return "미몹 없음: CA_Crop_" + id + (shiny ? "_shiny" : "");
        Location l = at.clone();
        Vector look = owner.getLocation().toVector().subtract(l.toVector()).setY(0);
        if (look.lengthSquared() > 1.0E-4) l.setDirection(look);
        var mob = type.get().spawn(BukkitAdapter.adapt(l), 1, SpawnReason.OTHER);
        if (mob == null) return "소환 실패";
        Entity ent = mob.getEntity().getBukkitEntity();
        ent.setPersistent(false);
        owned.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>()).add(ent.getUniqueId());
        if (!drops.isEmpty()) held.put(ent.getUniqueId(), new Held(owner.getUniqueId(), at.clone(), List.copyOf(drops)));
        long life = plugin.getConfig().getLong("crop-awakening.lifetime-seconds", 30) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Set<UUID> mine = owned.get(owner.getUniqueId());
            if (mine != null) {
                mine.remove(ent.getUniqueId());
                if (mine.isEmpty()) owned.remove(owner.getUniqueId());
            }
            Held h = held.remove(ent.getUniqueId());
            if (!ent.isValid() || ent.isDead()) { if (h != null) dropAt(ent.getLocation(), h.drops()); return; }
            ent.getWorld().spawnParticle(Particle.POOF, ent.getLocation().add(0, 0.4, 0), 10, 0.25, 0.25, 0.25, 0.02);
            if (h != null) dropAt(ent.getLocation(), h.drops());
            ent.remove();
        }, life);
        if (harvested) notifySpawned(owner, ent);
        return "OK";
    }

    /** 수확자에게 붙은 확률 배수 메타데이터 (없으면 1). */
    static double chanceMultiplier(Player p) {
        double m = 1;
        for (org.bukkit.metadata.MetadataValue v : p.getMetadata(CHANCE_KEY)) {
            if (!(v.value() instanceof Number n)) continue;
            double d = n.doubleValue();
            if (Double.isFinite(d) && d > m) m = Math.min(d, 10);
        }
        return m;
    }

    /** 수확자에게 붙은 "작물 몹 나옴" 연동을 부른다. 연동 쪽 오류는 수확·소환에 영향을 주지 않는다. */
    @SuppressWarnings("unchecked")
    private void notifySpawned(Player owner, Entity crop) {
        for (org.bukkit.metadata.MetadataValue v : owner.getMetadata(SPAWNED_KEY)) {
            if (!(v.value() instanceof java.util.function.Consumer<?> c)) continue;
            try { ((java.util.function.Consumer<Entity>) c).accept(crop); }
            catch (RuntimeException error) { plugin.getLogger().warning("crop-awakening: " + SPAWNED_KEY + " 연동 오류 (" + error + ")"); }
        }
    }

    Collection<String> ids() { return new TreeSet<>(crops.values()); }

    /** 다 자란 작물만. 호박·수박은 붙어 있는 줄기가 이 블록을 가리킬 때만(설치한 블록 반복 수확 방지). */
    private static boolean mature(Block b) {
        if (b.getBlockData() instanceof Ageable a) return a.getAge() >= a.getMaximumAge();
        Material stem = switch (b.getType()) {
            case PUMPKIN -> Material.ATTACHED_PUMPKIN_STEM;
            case MELON -> Material.ATTACHED_MELON_STEM;
            default -> null;
        };
        if (stem == null) return false;
        for (BlockFace f : SIDES) {
            Block s = b.getRelative(f);
            if (s.getType() == stem && s.getBlockData() instanceof Directional d && d.getFacing() == f.getOppositeFace()) return true;
        }
        return false;
    }
}
