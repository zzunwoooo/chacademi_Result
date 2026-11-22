package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;

/**
 * 100개 장난 "인벤토리 칸 봉인": 몇 초 동안 인벤토리 칸 몇 개를 봉인한다 (그 칸에 든 물건은 잠시 보관하고 칸에는 봉인 표시를 둔다).
 * 시간이 끝나면 봉인이 풀리며 보관한 물건이 원래 칸으로 돌아온다. 시간 관리·화면 표시는 Effects(효과 id "seal")가 한다.
 *
 * 봉인 표시는 방벽 아이템이고, 옮기기·버리기·놓기·손 바꾸기가 막혀 있다. 봉인 표시마다 원래 칸 번호가 적혀 있어서
 * 핫바 셔플처럼 칸이 섞여도 풀 때 각자 제 물건이 돌아온다.
 * 지키는 것: 나감·서버 종료(Effects 가 풀어 줌), 죽음(떨어지는 물건을 원래 물건으로 바꿔 줌), 서버가 갑자기 꺼짐(플레이어 자료에 적어 두고 접속 때 되돌림).
 */
final class Seal implements Listener {
    private final DiamondPlugin pl;
    private final NamespacedKey markKey, storeKey;
    private final Map<UUID, Map<Integer, ItemStack>> held = new HashMap<>();     // 사람 → (원래 칸 번호 → 보관한 물건, 빈칸이면 null)

    Seal(DiamondPlugin pl) {
        this.pl = pl;
        this.markKey = new NamespacedKey(pl, "sealed_slot");
        this.storeKey = new NamespacedKey(pl, "sealed_items");
    }

    boolean sealed(Player p) { return held.containsKey(p.getUniqueId()); }

    /** 칸 count 개를 봉인한다 (가방 36칸 중에서, 이미 봉인돼 있으면 그대로) */
    void seal(Player p, int count) {
        if (sealed(p)) return;
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(i);
        Collections.shuffle(slots);
        Map<Integer, ItemStack> keep = new HashMap<>();
        for (int slot : slots.subList(0, Math.max(1, Math.min(36, count)))) {
            ItemStack it = inv.getItem(slot);
            keep.put(slot, it == null || it.getType().isAir() ? null : it.clone());
            inv.setItem(slot, mark(slot));
        }
        held.put(p.getUniqueId(), keep);
        persist(p, keep);
        pl.net.toast(p, "인벤토리 칸 " + keep.size() + "개가 봉인되었습니다.", false);
    }

    /** 봉인을 푼다: 봉인 표시가 있는 자리에 원래 물건을 돌려놓는다 */
    void unseal(Player p) {
        Map<Integer, ItemStack> keep = held.remove(p.getUniqueId());
        p.getPersistentDataContainer().remove(storeKey);
        if (keep == null) return;
        restore(p, keep);
        pl.net.toast(p, "인벤토리 봉인이 풀렸습니다.", true);
    }

    private void restore(Player p, Map<Integer, ItemStack> keep) {
        Map<Integer, ItemStack> left = new HashMap<>(keep);
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            Integer from = slotOf(c[i]);
            if (from == null) continue;
            inv.setItem(i, left.remove(from));            // 섞였어도 표시에 적힌 원래 칸의 물건을 그 자리에
        }
        if (isMark(p.getItemOnCursor())) p.setItemOnCursor(null);
        for (ItemStack it : left.values()) if (it != null) Items.give(p, it);      // 표시가 사라졌던 칸의 물건은 빈자리에(가득 차면 발밑에)
    }

    // ── 봉인 표시 ─────────────────────────────────────────────
    private ItemStack mark(int slot) {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("봉인된 칸", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("시간이 지나면 풀리고, 들어 있던 물건이 돌아옵니다", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(markKey, PersistentDataType.INTEGER, slot);
        it.setItemMeta(m);
        return it;
    }

    private Integer slotOf(ItemStack it) {
        if (it == null || it.getType() != Material.BARRIER || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(markKey, PersistentDataType.INTEGER);
    }

    private boolean isMark(ItemStack it) { return slotOf(it) != null; }

    // ── 서버가 갑자기 꺼져도 잃지 않게: 플레이어 자료에 적어 둔다 ──────────
    private void persist(Player p, Map<Integer, ItemStack> keep) {
        try (ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b)) {
            out.writeInt(keep.size());
            for (Map.Entry<Integer, ItemStack> e : keep.entrySet()) {
                out.writeInt(e.getKey());
                byte[] raw = e.getValue() == null ? new byte[0] : e.getValue().serializeAsBytes();
                out.writeInt(raw.length);
                out.write(raw);
            }
            out.flush();
            p.getPersistentDataContainer().set(storeKey, PersistentDataType.BYTE_ARRAY, b.toByteArray());
        } catch (IOException e) {
            pl.getLogger().warning("봉인한 물건 기록 실패 (" + p.getName() + "): " + e);
        }
    }

    private static Map<Integer, ItemStack> read(byte[] data) throws IOException {
        Map<Integer, ItemStack> keep = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int slot = in.readInt(), len = in.readInt();
                byte[] raw = new byte[len];
                in.readFully(raw);
                keep.put(slot, len == 0 ? null : ItemStack.deserializeBytes(raw));
            }
        }
        return keep;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        byte[] data = p.getPersistentDataContainer().get(storeKey, PersistentDataType.BYTE_ARRAY);
        p.getPersistentDataContainer().remove(storeKey);
        try {
            Map<Integer, ItemStack> keep = data == null ? new HashMap<>() : read(data);
            restore(p, keep);                                  // 기록이 없어도 남은 봉인 표시는 치운다
        } catch (IOException | RuntimeException ex) {
            pl.getLogger().warning("봉인한 물건을 되돌리지 못함 (" + p.getName() + "): " + ex);
        }
    }

    // ── 죽으면: 떨어지는 봉인 표시를 원래 물건으로 바꾸고, 인벤토리도 원래대로 (인벤세이브권으로 지킬 때를 위해) ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Map<Integer, ItemStack> keep = held.remove(p.getUniqueId());
        p.getPersistentDataContainer().remove(storeKey);
        if (keep == null) return;
        for (ListIterator<ItemStack> it = e.getDrops().listIterator(); it.hasNext(); ) {
            Integer from = slotOf(it.next());
            if (from == null) continue;
            ItemStack orig = keep.get(from);
            if (orig == null) it.remove(); else it.set(orig.clone());
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) { Integer from = slotOf(c[i]); if (from != null) inv.setItem(i, keep.get(from)); }
        pl.effects.end(p, Effects.SEAL);
    }

    // ── 봉인 표시는 움직일 수 없다 ─────────────────────────────────
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (isMark(e.getCurrentItem()) || isMark(e.getCursor())) { e.setCancelled(true); return; }
        if (e.getHotbarButton() >= 0 && isMark(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (isMark(e.getOldCursor())) { e.setCancelled(true); return; }
        for (int raw : e.getRawSlots()) if (isMark(e.getView().getItem(raw))) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (isMark(e.getItemDrop().getItemStack())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isMark(e.getItemInHand())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (isMark(e.getMainHandItem()) || isMark(e.getOffHandItem())) e.setCancelled(true); }
}
