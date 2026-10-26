package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import school.magiccodex.protocol.TravelProtocol;

/**
 * 귀환권 (2026-10-25, 오픈 메모 #14). Right click: one ticket is used and the player goes to the spawn of the school
 * world (return-ticket.yml server/world), through the map's move path (TravelBridge: ticket, proxy transfer, arrival
 * checks, the existing limits — match, death, spectator, loading, travel.yml rules). 전투 중(메모 #91, CombatTag ·
 * combat-lock.yml)이면 귀환권을 쓰기 전에 막는다 (아이템 그대로, 알림창 "전투 중이라 이동할 수 없어요 · N초 뒤 가능").
 *
 * <p>The ticket is taken (and the player file saved) before the move starts. A move that fails gives it back once: the
 * player online here → inventory (overflow at the feet), otherwise → mail.
 */
final class ReturnTicketBridge implements Listener, TravelBridge.ReturnTickets {
    private final MagicCodexBridge plugin;
    private final TravelBridge travel;
    private final ItemsAdderItems itemsAdder;
    private final NamespacedKey key;
    private String item = "item:home_ticket", name = "귀환권", server = "school", world = "world";
    /** The ticket taken for a move started here (given back as it was when that move fails). */
    private final Map<UUID, ItemStack> taken = new HashMap<>();

    ReturnTicketBridge(MagicCodexBridge plugin, TravelBridge travel) throws Exception {
        this.plugin = plugin;
        this.travel = travel;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.key = new NamespacedKey(plugin, "return_ticket");
        reload();
        travel.returnTickets(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ChacaApi.reload(plugin, "귀환권", "return-ticket.yml (귀환권 아이템·도착 서버·세계)", this::reload);
    }

    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), "return-ticket.yml");
        if (!file.exists()) plugin.saveResource("return-ticket.yml", false);
        var y = new YamlConfiguration();
        y.load(file);
        String i = y.getString("item", item).strip(), n = y.getString("item-name", name).strip(), s = y.getString("server", server).strip(), w = y.getString("world", world).strip();
        if (!i.isEmpty() && !i.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("return-ticket.yml item: namespace:id (예: item:home_ticket)");
        if (!TravelProtocol.validId(s)) throw new IllegalArgumentException("return-ticket.yml server: travel.yml servers 의 서버 ID");
        if (!TravelProtocol.validWorld(w)) throw new IllegalArgumentException("return-ticket.yml world 형식");
        if (n.isEmpty() || n.length() > 30) n = "귀환권";
        item = i; name = n; server = s; world = w;
    }

    private boolean isTicket(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        var meta = it.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        return !item.isEmpty() && item.equals(itemsAdder.idOf(it));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player p = event.getPlayer();
        ItemStack held = p.getInventory().getItem(hand);
        if (!isTicket(held)) return;
        event.setCancelled(true);
        UUID id = p.getUniqueId();
        if (taken.containsKey(id)) { CmdKit.notify(p, "warning", "return-ticket", "이미 이동하는 중이에요", "", 1500); return; }
        var combat = plugin.combatTag();
        if (combat != null && combat.refuse(p) != null) return;   // 메모 #91: 전투 중 — 아이템을 쓰기 전에 막음 (알림은 CombatTag)
        // Take one first (saved to disk), then start the move; a refusal right away gives it straight back.
        ItemStack one = held.clone(); one.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
        taken.put(id, one);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("귀환권: 사용 전 저장 실패 " + p.getName() + " (" + e + ")"); }
        String refusal = travel.travelReturn(p, server, world);
        if (refusal != null) {
            taken.remove(id);
            give(p, one);
            CmdKit.notify(p, "warning", "return-ticket", "지금은 " + name + "을 쓸 수 없어요", refusal, 1500);
            return;
        }
        plugin.getLogger().info("귀환권 사용: " + p.getName() + " → " + server + "/" + world);
    }

    @Override public void arrived(Player p) {
        taken.remove(p.getUniqueId());
        CmdKit.notify(p, "system", "return-ticket", "귀환했어요", "", 0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .5f, 1.4f);
    }

    @Override public void refund(UUID id, String why) {
        ItemStack one = taken.remove(id);
        if (one == null) one = item.isEmpty() ? null : itemsAdder.create(item, 1);
        if (one == null) { plugin.getLogger().severe("귀환권 반환 실패: 아이템을 만들 수 없어요 (" + item + ") player=" + id + " — 직접 지급 필요"); return; }
        Player p = Bukkit.getPlayer(id);
        plugin.getLogger().info("귀환권 반환: " + id + " (" + why + ")");
        // 공통 지급 함수 (2026-10-26): 접속 중이면 넣을 수 있는 칸 → 남으면 우편, 아니면 우편.
        var delivery = RewardDelivery.get();
        if (delivery != null) {
            delivery.give(id, List.of(one), "return-ticket-refund:" + id + ":" + UUID.randomUUID(), name + " 반환", "귀환하지 못해 " + name + "을 돌려드려요.", null);
            if (p != null && p.isOnline()) CmdKit.notify(p, "warning", "return-ticket", "귀환하지 못했어요", name + "을 돌려드렸어요" + (why == null || why.isBlank() ? "" : " · " + why), 0);
            return;
        }
        if (p != null && p.isOnline()) {
            give(p, one);
            CmdKit.notify(p, "warning", "return-ticket", "귀환하지 못했어요", name + "을 돌려드렸어요" + (why == null || why.isBlank() ? "" : " · " + why), 0);
            return;
        }
        MailService mail = Bukkit.getServicesManager().load(MailService.class);
        if (mail == null) { plugin.getLogger().severe("귀환권 반환 실패: 우편 기능 없음 player=" + id + " — 직접 지급 필요"); return; }
        try {
            mail.sendSystem("return-ticket-refund:" + id + ":" + UUID.randomUUID(), id, name + " 반환", "귀환하지 못해 " + name + "을 돌려드려요.", List.of(one))
                    .whenComplete((mailId, error) -> { if (error != null) plugin.getLogger().severe("귀환권 반환 우편 실패 player=" + id + ": " + error); });
        } catch (RuntimeException e) { plugin.getLogger().severe("귀환권 반환 우편 실패 player=" + id + ": " + e); }
    }

    private static void give(Player p, ItemStack it) {
        for (var left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** Leaving (often: on the way to the school server) — a later failure is settled there or by mail with a new ticket item. */
    @EventHandler public void quit(PlayerQuitEvent e) { taken.remove(e.getPlayer().getUniqueId()); }
}
