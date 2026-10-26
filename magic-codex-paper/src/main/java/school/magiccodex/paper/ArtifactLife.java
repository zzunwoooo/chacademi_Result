package school.magiccodex.paper;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Everyday artifacts: gathering, food, items, small sounds/visuals. Vanilla events only (no other plugin needed).
 * Survival only where it matters (creative players never get extra drops or refunds).
 */
final class ArtifactLife implements Listener {
    private final ArtifactEffects fx;
    private final NamespacedKey biomesKey, keptKey;
    /** weather_vane: world -> the weather-change tick we already warned about, per player. */
    private final Map<UUID, Long> warned = new HashMap<>();
    private final Map<UUID, String> lastBiome = new HashMap<>();

    ArtifactLife(ArtifactEffects fx) {
        this.fx = fx;
        biomesKey = new NamespacedKey(fx.plugin, "artifact_biomes");
        keptKey = new NamespacedKey(fx.plugin, "artifact_kept_item");
    }

    private boolean has(Player p, String id) { return fx.has(p, id); }
    private boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }
    private boolean chance(double c) { return fx.random.nextDouble() < c; }

    // ------------------------------------------------------------------ periodic

    void second(Player p, long now) {
        if (has(p, "traveler_notebook")) traveler(p);
        if (has(p, "weather_vane")) weatherVane(p);
    }

    void fast(Player p) {
        // 털목도리: 가루눈 속에서 어는 속도 -50% (얼음이 1틱에 1씩 차므로 2틱마다 1씩 덜어 냄)
        if (has(p, "fur_scarf") && p.getFreezeTicks() > 0 && p.getLocation().getBlock().getType() == Material.POWDER_SNOW) p.setFreezeTicks(Math.max(0, p.getFreezeTicks() - 1));
    }

    private void traveler(Player p) {
        String biome = p.getLocation().getBlock().getBiome().getKey().toString();
        if (biome.equals(lastBiome.put(p.getUniqueId(), biome))) return;
        var data = p.getPersistentDataContainer();
        String seen = data.getOrDefault(biomesKey, PersistentDataType.STRING, "");
        var set = new HashSet<>(Arrays.asList(seen.split(",")));
        if (set.contains(biome) || set.size() > 400) return;
        set.add(biome); set.remove("");
        data.set(biomesKey, PersistentDataType.STRING, String.join(",", set));
        fx.plugin.markPlayerStateDirty(p); // shared with the other server (codex_player_state.artifact_biomes)
        p.giveExp(30);
        ArtifactEffects.notice(p, "travel_journal", "여행자의 수첩", "처음 와 보는 곳! (경험치 +30)");
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, .8f, 1.2f);
    }

    private void weatherVane(Player p) {
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || w.hasStorm() || Boolean.FALSE.equals(w.getGameRuleValue(GameRule.DO_WEATHER_CYCLE))) return;
        int left = w.getClearWeatherDuration() > 0 ? w.getClearWeatherDuration() : w.getWeatherDuration();
        if (left <= 0 || left > 1200) return;
        long startsAt = w.getFullTime() + left;
        Long prev = warned.get(p.getUniqueId());
        if (prev != null && Math.abs(prev - startsAt) < 200) return;
        warned.put(p.getUniqueId(), startsAt);
        ArtifactEffects.notice(p, "weather_vane", "풍향계", "곧(약 " + Math.max(1, left / 20) + "초 뒤) 비가 올 것 같아요");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, .6f, .8f);
    }

    // ------------------------------------------------------------------ gathering

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (!fx.wearsAny(p) || !survival(p) || e.getItems().isEmpty()) return;
        Material type = e.getBlockState().getType();
        double c = 0;
        if (has(p, "farmer_straw_hat") && (Tag.CROPS.isTagged(type) || type == Material.NETHER_WART || type == Material.COCOA)
                && e.getBlockState().getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) c = 0.05;
        else if (has(p, "miner_lantern") && type.name().endsWith("_ORE") && e.getItems().stream().noneMatch(i -> i.getItemStack().getType() == type)) c = 0.03; // not silk touch
        else if (has(p, "lumberjack_bandana") && Tag.LOGS.isTagged(type)) c = 0.05;
        else if (has(p, "gardener_gloves") && (Tag.SMALL_FLOWERS.isTagged(type) || Set.of(Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.PITCHER_PLANT).contains(type))) c = 0.10;
        if (c > 0 && chance(c)) {
            var pick = e.getItems().get(fx.random.nextInt(e.getItems().size())).getItemStack().clone();
            pick.setAmount(1);
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), pick);
            return;
        }
        // 다람쥐 도토리: 나뭇잎을 부술 때 묘목·사과 추가 확률 5%
        if (has(p, "squirrel_acorn") && Tag.LEAVES.isTagged(type) && chance(0.05)) {
            Material sapling = Material.matchMaterial(type.name().replace("_LEAVES", "_SAPLING"));
            boolean apple = (type == Material.OAK_LEAVES || type == Material.DARK_OAK_LEAVES) && chance(0.5);
            Material drop = apple || sapling == null || !sapling.isItem() ? Material.APPLE : sapling;
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), new ItemStack(drop));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !has(p, "fishing_bobber") || !(e.getCaught() instanceof Item item) || !chance(0.05)) return;
        var extra = item.getItemStack().clone(); extra.setAmount(1);
        ArtifactActions.give(p, extra);
        ArtifactEffects.notice(p, "fishing_float", "낚시찌", "한 마리 더!");
    }

    @EventHandler(ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !has(p, "egg_basket") || !survival(p)) return;
        var stack = e.getItem().getItemStack();
        if (stack.getType() != Material.EGG || !chance(0.10)) return;
        Bukkit.getScheduler().runTask(fx.plugin, () -> { if (p.isOnline()) ArtifactActions.give(p, new ItemStack(Material.EGG)); });
    }

    @EventHandler(ignoreCancelled = true)
    public void craft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !has(p, "carpenter_pencil") || !survival(p)) return;
        var result = e.getRecipe().getResult();
        if (!(Tag.PLANKS.isTagged(result.getType()) || result.getType() == Material.STICK) || !chance(0.05)) return;
        Bukkit.getScheduler().runTask(fx.plugin, () -> { if (p.isOnline()) ArtifactActions.give(p, new ItemStack(result.getType())); });
    }

    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!fx.wearsAny(p) || p.getGameMode() != GameMode.SURVIVAL) return;
        Material type = e.getBlockPlaced().getType();
        double c = 0;
        if (has(p, "mason_trowel") && (Tag.DIRT.isTagged(type) || Tag.BASE_STONE_OVERWORLD.isTagged(type) || type == Material.COBBLESTONE || type == Material.COBBLED_DEEPSLATE)) c = 0.03;
        if (has(p, "lamplighter_matches") && (type == Material.TORCH || type == Material.WALL_TORCH || type == Material.SOUL_TORCH || type == Material.SOUL_WALL_TORCH)) c = 0.10;
        if (c <= 0 || !chance(c)) return;
        var refund = e.getItemInHand().clone(); refund.setAmount(1);
        Bukkit.getScheduler().runTask(fx.plugin, () -> { if (p.isOnline()) ArtifactActions.give(p, refund); });
    }

    @EventHandler(ignoreCancelled = true)
    public void anvil(PrepareAnvilEvent e) {
        if (!(e.getView().getPlayer() instanceof Player p) || !has(p, "artisan_hammer")) return;
        int cost = e.getView().getRepairCost();
        if (cost > 1) e.getView().setRepairCost(Math.max(1, (int) Math.round(cost * 0.8)));
    }

    @EventHandler(ignoreCancelled = true)
    public void durability(PlayerItemDamageEvent e) {
        Player p = e.getPlayer();
        if (!fx.wearsAny(p)) return;
        Material type = e.getItem().getType();
        if (has(p, "blacksmith_amulet") && tool(type)) { e.setCancelled(true); return; }
    }

    static boolean tool(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || m == Material.SHEARS || m == Material.FISHING_ROD || m == Material.FLINT_AND_STEEL || m == Material.BRUSH;
    }

    // ------------------------------------------------------------------ food / drink

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void eat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!fx.wearsAny(p)) return;
        Material type = e.getItem().getType();
        boolean food = type.isEdible();
        Bukkit.getScheduler().runTask(fx.plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            if (food && has(p, "chef_apron")) p.setFoodLevel(Math.min(20, p.getFoodLevel() + 1));
            if (food && has(p, "veteran_chef_pot")) p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, 0, true, true));
            if (type == Material.MILK_BUCKET && has(p, "milk_bottle")) ArtifactEffects.heal(p, 1);
            if ((type == Material.COOKIE || type == Material.PUMPKIN_PIE) && has(p, "candy_pouch")) fx.timed(p, "candy_pouch", 5_000, 0.10, 0);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void cake(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !has(p, "candy_pouch")) return;
        if (!Tag.CANDLE_CAKES.isTagged(e.getClickedBlock().getType()) && e.getClickedBlock().getType() != Material.CAKE) return;
        if (p.getFoodLevel() < 20 || p.getGameMode() == GameMode.CREATIVE) fx.timed(p, "candy_pouch", 5_000, 0.10, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void potion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityPotionEffectEvent.Cause.POTION_DRINK || !has(p, "potion_belt")) return;
        var next = e.getNewEffect();
        if (next == null || next.getType().isInstant() || next.isInfinite()) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(fx.plugin, () -> {
            if (p.isOnline()) p.addPotionEffect(new PotionEffect(next.getType(), (int) Math.round(next.getDuration() * 1.1), next.getAmplifier(), next.isAmbient(), next.hasParticles(), next.hasIcon()));
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void exp(PlayerExpChangeEvent e) {
        Player p = e.getPlayer();
        if (e.getAmount() <= 0 || !has(p, "exp_pouch")) return;
        var r = fx.run(p);
        double v = e.getAmount() * 1.05 + r.expCarry;
        int whole = (int) v; r.expCarry = v - whole;
        e.setAmount(whole);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void kill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || e.getEntity() instanceof Player || !fx.wearsAny(p)) return;
        Location at = e.getEntity().getLocation();
        // 별 모으는 병: 밤에 하늘이 보이는 곳에서 처치하면 경험치 +10%
        long time = at.getWorld().getTime();
        if (has(p, "star_collector_jar") && time >= 13000 && time < 23000 && at.getWorld().getEnvironment() == World.Environment.NORMAL
                && at.getBlockY() >= at.getWorld().getHighestBlockYAt(at) && e.getDroppedExp() > 0)
            e.setDroppedExp((int) Math.ceil(e.getDroppedExp() * 1.1));
        // 행운의 동전: 1% 확률로 보유금 1000원
        if (has(p, "lucky_coin") && e.getEntity() instanceof Mob && chance(0.01) && deposit(p, 1000)) {
            at.getWorld().spawnParticle(Particle.WAX_ON, at.clone().add(0, 1, 0), 20, .3, .5, .3, 0);
            at.getWorld().playSound(at, Sound.BLOCK_CHAIN_PLACE, 1f, 1.8f);
            at.getWorld().playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
            CmdKit.notify(p, "shop", "artifact:lucky_coin", "행운의 동전", "+1000원", 2000);
        }
    }

    /** Vault (reflection, same as ShopBridge). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean deposit(Player p, double amount) {
        try {
            for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
                if (!type.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
                var reg = Bukkit.getServicesManager().getRegistration((Class) type);
                if (reg == null) return false;
                Object response = type.getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(reg.getProvider(), p, amount);
                boolean ok = (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
                if (ok && amount >= AuditLog.moneyThreshold()) AuditLog.log(p, AuditLog.돈, "아티팩트 행운의 동전 지급 +" + amount, "출처", "행운의 동전", "금액", amount);
                return ok;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            fx.plugin.getLogger().warning("행운의 동전 지급 실패: " + e.getClass().getSimpleName());
        }
        return false;
    }

    // ------------------------------------------------------------------ death / respawn / sleep

    @EventHandler(priority = EventPriority.HIGHEST)
    public void died(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!has(p, "sturdy_strap") || e.getKeepInventory() || e.getDrops().isEmpty()) return;
        // 튼튼한 끈: 떨어질 아이템 하나를 지켜 두었다가 부활하면 돌려줌 (나가도 남도록 PDC에 저장)
        var pick = e.getDrops().get(fx.random.nextInt(e.getDrops().size()));
        e.getDrops().remove(pick);
        p.getPersistentDataContainer().set(keptKey, PersistentDataType.BYTE_ARRAY, pick.serializeAsBytes());
        fx.plugin.savePlayerState(p); // shared state: the item must not be lost or duplicated by a server switch
    }

    @EventHandler public void respawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(fx.plugin, () -> {
            if (!p.isOnline()) return;
            returnKept(p);
            if (has(p, "revival_boots")) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 0, true, true));
        }, 2);
    }

    /** On join the shared state may still be loading: the item is returned from {@link ArtifactEffects#arrived} (PlayerStats after-arrival) instead. */
    @EventHandler public void joined(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(fx.plugin, () -> { if (e.getPlayer().isOnline() && fx.plugin.playerStateReady(e.getPlayer())) returnKept(e.getPlayer()); }, 40);
    }

    /** 관리자 보관품돌려주기: 상태 준비만 보고 바로 돌려준다. 돌려준 아이템 이름, 없으면 null. */
    String adminReturnKept(Player p) {
        byte[] bytes = p.getPersistentDataContainer().get(keptKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return null;
        ItemStack item = ItemStack.deserializeBytes(bytes);
        p.getPersistentDataContainer().remove(keptKey);
        fx.plugin.savePlayerState(p);
        ArtifactActions.give(p, item);
        fx.plugin.getLogger().info("ARTIFACT ADMIN returned kept item player=" + p.getUniqueId() + " item=" + item.getType());
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.displayName());
    }

    void returnKept(Player p) {
        if (p.isDead() || !fx.plugin.playerStateReady(p)) return;
        byte[] bytes = p.getPersistentDataContainer().get(keptKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return;
        p.getPersistentDataContainer().remove(keptKey);
        fx.plugin.savePlayerState(p);
        try {
            ArtifactActions.give(p, ItemStack.deserializeBytes(bytes));
            ArtifactEffects.notice(p, "sturdy_string", "튼튼한 끈", "아이템 하나를 지켜 주었어요");
        } catch (RuntimeException err) { fx.plugin.getLogger().warning("튼튼한 끈 아이템 복원 실패: " + p.getName()); }
    }

    @EventHandler public void wake(PlayerBedLeaveEvent e) {
        Player p = e.getPlayer();
        long time = p.getWorld().getTime();
        if (!has(p, "inn_key") || time > 2000) return; // only after actually sleeping through the night
        p.setFoodLevel(Math.min(20, p.getFoodLevel() + 4));
        ArtifactEffects.notice(p, "inn_key", "여관 열쇠", "푹 잤어요 (배고픔 2칸 회복)");
    }

    // ------------------------------------------------------------------ small things

    @EventHandler(ignoreCancelled = true)
    public void throwItem(PlayerDropItemEvent e) {
        if (!has(e.getPlayer(), "mail_bag")) return;
        var item = e.getItemDrop();
        item.setVelocity(item.getVelocity().multiply(2));
    }

    @EventHandler(ignoreCancelled = true)
    public void jump(PlayerJumpEvent e) {
        if (has(e.getPlayer(), "frog_crown")) e.getPlayer().getWorld().playSound(e.getPlayer().getLocation(), Sound.ENTITY_FROG_AMBIENT, .7f, 1.2f);
    }

    @EventHandler public void level(PlayerLevelChangeEvent e) {
        if (e.getNewLevel() > e.getOldLevel() && has(e.getPlayer(), "konpeito_crown"))
            ArtifactActions.burst(e.getPlayer().getLocation().add(0, 2.6, 0));
    }

    void quit(UUID id) { warned.remove(id); lastBiome.remove(id); }
}
