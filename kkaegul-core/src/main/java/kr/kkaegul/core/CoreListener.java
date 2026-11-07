package kr.kkaegul.core;

import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.gacha.Pickaxe;
import kr.kkaegul.core.minigame.DangerousInviteGame;
import kr.kkaegul.core.minigame.Minigame;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 코어 이벤트 모음 */
public final class CoreListener implements Listener {
    private final KkaegulCore plugin;

    public CoreListener(KkaegulCore plugin) { this.plugin = plugin; }

    // ───────── 접속 ─────────
    /** 바닐라·에센셜 귓속말(/msg /tell /w /r …)도 우리 귓속말로: 한글 닉만 보이게 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWhisperCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage(), fixed = Txt.jamo(raw);   // 명령어 안의 자모도 합침 (/닉네임 ㅈㅜㄴㅇㅜ)
        if (fixed != null && !fixed.equals(raw)) e.setMessage(fixed);
        String msg = e.getMessage();
        if (msg == null || msg.length() < 2) return;
        String[] a = msg.substring(1).trim().split("\\s+");
        String label = a[0].toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        boolean reply = java.util.Set.of("r", "reply", "er", "ereply").contains(label);
        boolean whisper = java.util.Set.of("msg", "tell", "w", "m", "t", "whisper", "pm", "emsg", "etell", "ewhisper", "dm").contains(label);
        if (!reply && !whisper) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (reply) {
            if (a.length < 2) { plugin.ui().error(p, "&c/답 <할 말>"); return; }
            plugin.chat().reply(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
        } else {
            if (a.length < 3) { plugin.ui().error(p, "&c/귓 <닉네임> <할 말>"); return; }
            plugin.chat().whisper(p, a[1], String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
        }
    }

    /** 감옥에 있는 사람에게 명령어 텔포(/tpa 등) 금지 (OP 제외) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleportJail(org.bukkit.event.player.PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null || p.isOp() || plugin.data().get(p).inJail) return;
        // 명령어 텔포(/tpa 등)만 막음 — 플러그인 이동(시작 위치 등)은 그대로
        if (e.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND) return;
        boolean nearJailed = false;
        for (Player o : org.bukkit.Bukkit.getOnlinePlayers())
            if (o != p && plugin.data().get(o).inJail && o.getWorld().equals(e.getTo().getWorld()) && o.getLocation().distanceSquared(e.getTo()) < 16) { nearJailed = true; break; }
        if (nearJailed) {
            e.setCancelled(true);
            plugin.ui().error(p, "&c감옥 안으로는 이동할 수 없어요.");
        }
    }

    /** 플러그인 순간이동(이동 메뉴·NPC·야생·감옥·괴롭히기 등)마다 출발·도착 자리에서 순간이동 소리 */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportSound(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN || e.getTo() == null) return;
        org.bukkit.Location f = e.getFrom(), t = e.getTo();
        boolean far = f.getWorld() != t.getWorld() || f.distanceSquared(t) > 64;
        if (far) plugin.ui().teleportSound(e.getPlayer(), f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data().get(p);
        boolean first = !d.profileDone && !p.hasPlayedBefore();
        if (d.nickname != null && !d.nickname.equals(Txt.jamo(d.nickname))) { d.nickname = Txt.jamo(d.nickname); d.markDirty(); }   // 예전에 자모로 저장된 닉네임 고치기
        plugin.titles().applyName(p);
        if (!d.stash.isEmpty()) {   // 위험한 초대 도중 나갔거나 서버가 꺼졌으면: 원래 자리 + 맡긴 아이템
            Location back = kr.kkaegul.core.util.Locs.fromStr(d.stashBack);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || plugin.minigames().busy(p)) return;
                if (back != null) p.teleport(back);
                kr.kkaegul.core.minigame.DangerousInviteGame.unstash(plugin, p);
            }, 20L);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (d.inJail) plugin.jail().forceInside(p);
            else if (first) {
                Location start = plugin.places().get("start");
                if (start != null) p.teleport(start);
            }
            plugin.api().flushPending(p);
            plugin.hud().refresh(p);
            plugin.nickname().pushAll();
            if (!d.profileDone && plugin.getConfig().getBoolean("nickname.force-on-join", true) && plugin.ui().hasRenderer(p))
                plugin.nickname().open(p, true);
            else if (!d.profileDone && plugin.getConfig().getBoolean("profile.prompt-on-join", true)) plugin.msg(p, "starter.welcome");
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Minigame g = plugin.minigames().gameOf(p);
        if (g != null) g.quit(p);
        plugin.mine().onQuit(p);
        plugin.wasd().cancel(p);
        plugin.frogJump().quit(p);
        plugin.nickname().pushLater();
    }

    // ───────── 감옥 / 고정 ─────────
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) plugin.jail().onSquat(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        if (plugin.harass().isFrozen(p)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            return;
        }
        Minigame mg = plugin.minigames().gameOf(p);
        if (mg != null && !mg.allowMove(p, to)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            return;
        }
        if (plugin.jail().outOfBounds(p, to)) {
            e.setCancelled(true);
            plugin.jail().forceInside(p);
            plugin.ui().actionbar(p, "&c감옥에서 운동부터 하세요!");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data().get(e.getPlayer());
        Location l = d.inJail ? plugin.places().get("jail") : null;
        // 위험한 초대에서 죽은 뒤: 게임이 끝났으면 원래 자리로 + 맡긴 아이템 돌려줌
        if (l == null && !d.stash.isEmpty() && !plugin.minigames().busy(e.getPlayer())) {
            Location back = kr.kkaegul.core.util.Locs.fromStr(d.stashBack);
            if (back != null) l = back;
            Player p = e.getPlayer();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) kr.kkaegul.core.minigame.DangerousInviteGame.unstash(plugin, p); }, 2L);
        }
        if (l != null) e.setRespawnLocation(l);
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        plugin.wasd().onInput(e.getPlayer(), e.getInput());
    }

    // ───────── 채팅 (거탐 퀴즈 + 칭호) ─────────
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String fixed = Txt.jamo(e.getMessage());   // 따로 들어온 자모 합치기 (ㄸㅏㅇ → 땅)
        if (!fixed.equals(e.getMessage())) e.setMessage(fixed);
        if (plugin.npcs().starter().awaitingName(e.getPlayer())) {
            e.setCancelled(true);
            plugin.npcs().starter().onNameChat(e.getPlayer(), e.getMessage());
            return;
        }
        if (plugin.quiz().onChat(e.getPlayer(), e.getMessage())) {
            e.setCancelled(true);
            return;
        }
        if (plugin.getConfig().getBoolean("titles.chat-format", true)) {
            String name = plugin.titles().chatName(e.getPlayer()).replace("%", "%%");
            e.setFormat(name + Txt.color("&7: &f") + "%2$s");
        }
    }

    // ───────── NPC / 블록 상호작용 ─────────
    @EventHandler(priority = EventPriority.LOW)
    public void onEntityClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity t = e.getRightClicked();
        String action = plugin.npcs().boundAction(t);
        if (action == null) return;
        e.setCancelled(true);
        if (!plugin.npcs().run(e.getPlayer(), action))
            plugin.ui().error(e.getPlayer(), "&f{e:error} &c알 수 없는 NPC 동작: " + action);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && plugin.frogJump().isMachine(b)) { e.setCancelled(true); plugin.frogJump().open(e.getPlayer()); return; }
        String gm = e.getAction() == Action.RIGHT_CLICK_BLOCK ? plugin.places().machineAt("gacha-machine", b) : null;
        if (gm != null) { e.setCancelled(true); plugin.npcs().run(e.getPlayer(), "gacha-" + gm); return; }
        Minigame g = plugin.minigames().gameOf(e.getPlayer());
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && g != null && g.onInteractBlock(e.getPlayer(), b)) { e.setCancelled(true); return; }
        if (plugin.minigames().protects(b)) e.setCancelled(true); // 참가자 아닌 사람이 보물상자 열기 방지
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Minigame g = plugin.minigames().gameOf(e.getPlayer());
        if (g != null) g.onSwing(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Minigame g = plugin.minigames().gameOf(p);
        if (g instanceof DangerousInviteGame dg && dg.seated.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ───────── 데미지 / 사망 ─────────
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && plugin.harass().noFall.remove(p.getUniqueId())) e.setCancelled(true);
        if (plugin.data().get(p).inJail && plugin.getConfig().getBoolean("jail.invulnerable", true)) e.setCancelled(true);
    }

    /** 가챠 파괴 방지권: 곡괭이가 닳아서 부서지기 직전에도 1장 써서 지킴 (내구도 가득 채움) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(org.bukkit.event.player.PlayerItemDamageEvent e) {
        org.bukkit.inventory.ItemStack it = e.getItem();
        if (it == null || !kr.kkaegul.core.gacha.Pickaxe.isPickaxe(it)) return;
        int max = it.getType().getMaxDurability();
        if (max <= 0 || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm)) return;
        if (dm.getDamage() + e.getDamage() < max) return;
        if (!plugin.gacha().consumeProtect(e.getPlayer())) return;
        e.setCancelled(true);
        dm.setDamage(0);
        it.setItemMeta(dm);
        plugin.ui().fx(e.getPlayer(), "gacha_protect", kr.kkaegul.core.util.Json.obj().put("machine", "wear"));
        e.getPlayer().sendMessage(kr.kkaegul.core.util.Txt.color(kr.kkaegul.core.util.Txt.notice("&f파괴 방지권으로 곡괭이를 지켰어요! &7(내구도 회복)")));
    }

    /** 안전 구역(로비·마을)에서는 PK 금지 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player a ? a
                : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player a2 ? a2 : null;
        if (attacker == null || attacker.equals(victim)) return;
        if (plugin.safeZone(victim.getLocation()) || plugin.safeZone(attacker.getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        boolean saved = plugin.harass().keepInv.remove(p.getUniqueId());
        if (!saved && plugin.getConfig().getBoolean("safe-zones.keep-inventory", true) && plugin.safeZone(p.getLocation())) saved = true;   // 마을·로비 = 기본 인벤세이브
        if (!saved && plugin.getConfig().getBoolean("items.inven_save_ticket.keep-inventory", true)
                && kr.kkaegul.core.item.ItemUseListener.consumeFromInventory(plugin, p, kr.kkaegul.core.item.CustomItem.INVEN_SAVE_TICKET)) {
            saved = true;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.ui().center(p, "&e인벤세이브권 사용!", "&f아이템을 지켰어요"), 20L);
        }
        if (saved) {
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    // ───────── 채굴 ─────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Material type = b.getType();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (plugin.minigames().protects(b)) { e.setCancelled(true); return; }

        String region = plugin.mine().regionAt(b.getLocation());
        if (region != null && !plugin.mine().toolAllowed(region, hand == null ? Material.AIR : hand.getType())) {
            e.setCancelled(true);
            plugin.ui().actionbar(p, "&c이 광산은 " + String.join(", ", plugin.getConfig().getStringList("mine.regions." + region + ".allowed-tools")) + " 로만 캘 수 있어요");
            return;
        }

        List<String> diamondOres = plugin.getConfig().getStringList("mining.diamond-ores");
        if (diamondOres.isEmpty()) diamondOres = List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE");
        if (diamondOres.contains(type.name()) && Pickaxe.isPickaxe(hand) && hand.getEnchantmentLevel(Enchantment.SILK_TOUCH) == 0) {
            e.setDropItems(false);
            PlayerData d = plugin.data().get(p);
            int fortune = plugin.gacha().pickaxe().get(hand, "fortune") + plugin.npcs().fortune().bonus(p.getUniqueId(), "fortune");
            fortune = Math.max(0, Math.min(fortune, plugin.getConfig().getInt("mining.fortune-cap", 1_000_000)));
            double scale = plugin.getConfig().getDouble("mining.fortune-scale", 1.0);
            long drops = 1 + (long) Math.floor(Rng.r().nextDouble() * (fortune + 1) * scale);
            int extraMin = plugin.gauge().bonusDiamondMin(d), extraMax = plugin.gauge().bonusDiamondMax(d);
            if (extraMax > 0) drops += Rng.range(extraMin, extraMax);
            drops = Math.min(drops, plugin.getConfig().getLong("mining.max-drops-per-ore", 64));
            Location at = b.getLocation().add(0.5, 0.5, 0.5);
            dropMany(at, Material.DIAMOND, drops);
            if (Rng.chance(plugin.gauge().blockChance(d))) {
                at.getWorld().dropItemNaturally(at, new ItemStack(Material.DIAMOND_BLOCK, 1));
                plugin.ui().actionbar(p, "&b운빨! 다이아 블록 드랍");
            }
        }
        if (region == null) plugin.mine().onOreMined(p, type);
    }

    private void dropMany(Location at, Material m, long amount) {
        while (amount > 0) {
            int n = (int) Math.min(64, amount);
            at.getWorld().dropItemNaturally(at, new ItemStack(m, n));
            amount -= n;
        }
    }
}
