package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Listeners implements Listener {
    private final DiamondPlugin pl;

    Listeners(DiamondPlugin pl) { this.pl = pl; }

    // ── 접속·퇴장 ────────────────────────────────────────────
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pl.store.get(p);                   // 처음 온 사람은 여기서 목표가 정해진다
        pl.nicks.apply(p);                 // 채팅·탭 목록 이름을 닉네임으로
        // 접속 글은 한글 닉네임으로. 아직 닉네임이 없으면 접속 글을 내지 않고, 닉네임을 정했을 때 알린다
        e.joinMessage(pl.nicks.has(p) ? Component.text(pl.nick(p) + "님이 접속했습니다.", NamedTextColor.YELLOW) : null);
        // 모드 없이 들어온 사람은 5초 뒤 닉네임 명령어를 안내한다 (모드가 있으면 창이 뜬다)
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && !pl.net.hasMod(p)) pl.nicks.promptIfNeeded(p); }, 100L);
        // 접속해 있지 않을 때 들어온 후원은 3초 뒤(화면이 준비된 뒤) 줄에 넣는다
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) pl.events.onJoin(p); }, 60L);
        // 받지 않은 우편이 있으면 5초 뒤 알려 준다
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            int n = pl.mail.count(p.getUniqueId());
            if (n > 0) pl.net.toast(p, "받지 않은 우편이 " + n + "통 있습니다. (우편함: H 키 또는 /우편함)", true);
        }, 100L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(pl.nicks.has(e.getPlayer()) ? Component.text(pl.nick(e.getPlayer()) + "님이 나갔습니다.", NamedTextColor.YELLOW) : null);
        UUID id = e.getPlayer().getUniqueId();
        pl.net.forget(id);
        pl.mail.unload(id);
        pl.store.unload(id);
    }

    /**
     * 일반 플레이어가 / 를 쳤을 때 뜨는 명령어 목록에는 이 서버의 한글 명령만 보이게 한다.
     * (다른 플러그인의 영어 명령은 목록에서만 숨긴다. 쓰는 것을 막지는 않는다. 관리자에게는 전부 보인다.)
     */
    @EventHandler
    public void onCommandList(org.bukkit.event.player.PlayerCommandSendEvent e) {
        if (!pl.getConfig().getBoolean("commands.hide-others", true)) return;
        if (e.getPlayer().hasPermission("diamondserver.admin")) return;
        java.util.Set<String> keep = new java.util.HashSet<>(pl.getConfig().getStringList("commands.visible"));
        if (keep.isEmpty()) keep.addAll(List.of("명령어", "도움말", "상점", "우편함", "제출", "인출", "입금", "목표", "닉네임"));
        e.getCommands().removeIf(c -> !keep.contains(c));
    }

    /** 모드가 채널을 등록하면 바로 상태를 보낸다 (모드의 hello 가 늦거나 빠져도 화면이 채워지게) */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (Net.CH.equals(e.getChannel())) pl.net.greet(e.getPlayer());
    }

    // ── 캔 다이아는 인벤토리 대신 보유 다이아로 ──────────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.player.PlayerAttemptPickupItemEvent e) {
        // 이 이벤트는 인벤토리에 빈 칸이 없어도 불린다 (가득 찬 채로 광질해도 다이아는 보유 다이아로 들어와야 한다)
        Player p = e.getPlayer();
        org.bukkit.entity.Item item = e.getItem();
        if (item.isDead()) return;
        UUID owner = item.getOwner();
        if (owner != null && !owner.equals(p.getUniqueId())) return;
        long v = pl.ledger.diamondValue(item.getItemStack());
        if (v <= 0) return;
        e.setCancelled(true);
        e.setFlyAtPlayer(false);
        item.remove();
        pl.ledger.addHeld(p, v);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.6f);
    }

    // ── 인벤세이브권: 죽을 때 1장 자동 사용 → 아이템·레벨 유지 ──────
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        e.deathMessage(Component.text(pl.nick(p) + "님이 사망했습니다.", NamedTextColor.WHITE));     // 사망 글도 한글 닉네임으로
        // 죽으면 남은 목표가 늘어난다 (11/22 오너). 4444 즉사는 이미 벌칙이라 빼고, 졸업한 사람도 뺀다
        long penalty = pl.getConfig().getLong("death.goal-penalty", 30);
        boolean reaped = pl.pranks.consumeReaped(p.getUniqueId());
        if (penalty > 0 && !reaped && !pl.store.get(p).done) {
            pl.ledger.addGoal(p, penalty);
            for (Player o : Bukkit.getOnlinePlayers()) pl.net.toast(o, pl.nick(p) + "님이 사망해 남은 목표가 " + penalty + "개 늘었습니다.", false);
        }
        if (e.getKeepInventory() || !pl.items.takeOne(p, Items.INVEN_SAVE)) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        pl.net.toast(p, pl.items.nameOf(Items.INVEN_SAVE) + " 1장을 사용해 아이템을 지켰습니다.", true);
    }

    // ── 귀환권·텔레포트권: 손에 들고 우클릭 ───────────────────────
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = pl.items.idOf(e.getItem());
        if (id == null) return;
        Player p = e.getPlayer();
        if (id.equals(Items.RETURN)) {
            e.setCancelled(true);
            Location to = pl.spawn();
            if (!pl.items.takeOne(p, Items.RETURN)) return;
            p.teleport(to);
            p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
            pl.net.toast(p, "시작 지점으로 돌아왔습니다.", true);
        } else if (id.equals(Items.TELEPORT)) {
            e.setCancelled(true);
            openPicker(p);
        } else if (id.equals(Items.INVEN_SAVE)) {
            e.setCancelled(true);
            pl.net.toast(p, "가지고 있으면 죽을 때 자동으로 사용됩니다.", true);
        }
    }

    /** 텔레포트권으로 이동할 사람을 고르는 창 */
    static final class Picker implements InventoryHolder {
        final List<UUID> targets = new ArrayList<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void openPicker(Player p) {
        Picker h = new Picker();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) h.targets.add(o.getUniqueId());
        if (h.targets.isEmpty()) { pl.net.toast(p, "이동할 수 있는 사람이 없습니다.", false); return; }
        int size = Math.min(54, ((h.targets.size() - 1) / 9 + 1) * 9);
        h.inv = Bukkit.createInventory(h, size, Component.text("이동할 사람을 고르세요"));
        for (int i = 0; i < h.targets.size() && i < size; i++) {
            Player o = Bukkit.getPlayer(h.targets.get(i));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwningPlayer(o);
            m.displayName(Component.text(pl.nick(o), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            head.setItemMeta(m);
            h.inv.setItem(i, head);
        }
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Picker h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= h.targets.size()) return;
        Player to = Bukkit.getPlayer(h.targets.get(slot));
        p.closeInventory();
        if (to == null) { pl.net.toast(p, "그 사람은 지금 접속해 있지 않습니다.", false); return; }
        if (!pl.items.takeOne(p, Items.TELEPORT)) { pl.net.toast(p, pl.items.nameOf(Items.TELEPORT) + "이 없습니다.", false); return; }
        p.teleport(to.getLocation());
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
        pl.net.toast(p, pl.nick(to) + "님에게 이동했습니다.", true);
        pl.net.toast(to, pl.nick(p) + "님이 텔레포트권으로 이동해 왔습니다.", true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Picker) e.setCancelled(true);
    }
}
