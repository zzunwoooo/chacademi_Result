package school.chacademia.tower;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/**
 * 증강 카드 고르기 창 (상자 창). 카드 3장은 11·13·15 칸, 다시 뽑기는 22 칸.
 * 클라 모드(chacademi-dungeon 0.6+)가 있으면 TowerClient 화면, 없으면 이 창.
 */
final class CardMenu implements InventoryHolder {
    static final int[] CARD_SLOTS = {11, 13, 15};
    static final int REROLL_SLOT = 22;

    private final Inventory inv;

    CardMenu(Settings s, Run run) {
        inv = Bukkit.createInventory(this, 27, Component.text((run.firstPick ? "시작 마법 선택" : (run.floor - 1) + "층 증강 선택") + (run.picks.size() > 0 ? " (" + (run.picks.size() + 1) + "번 남음)" : "")));
        var glass = named(new ItemStack(Material.BLACK_STAINED_GLASS_PANE), Component.text(" "), List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);
        for (int i = 0; i < run.offer.size() && i < 3; i++) inv.setItem(CARD_SLOTS[i], icon(s, run.offer.get(i)));
        if (run.rerolls > 0)
            inv.setItem(REROLL_SLOT, named(new ItemStack(Material.ENDER_EYE), Component.text("다시 뽑기", NamedTextColor.AQUA),
                    List.of(Component.text("카드 3장을 새로 뽑아요. 남은 횟수 " + run.rerolls + "회", NamedTextColor.GRAY))));
        long left = Math.max(0, (run.phaseEndsAt - System.currentTimeMillis()) / 1000);
        inv.setItem(4, named(new ItemStack(Material.CLOCK), Component.text(run.firstPick ? "처음 쓸 마법을 골라요" : (run.floor - 1) + "층을 깼어요", NamedTextColor.WHITE),
                List.of(Component.text(left + "초 안에 고르지 않으면 첫 번째 카드가 골라져요.", NamedTextColor.GRAY))));
    }

    @Override public Inventory getInventory() { return inv; }

    void open(Player p) { p.openInventory(inv); }

    /** 누른 칸 → 카드 번호 (0~2), 다시 뽑기 = -2, 그 밖 = -1. */
    static int slotToChoice(int slot) {
        for (int i = 0; i < CARD_SLOTS.length; i++) if (CARD_SLOTS[i] == slot) return i;
        return slot == REROLL_SLOT ? -2 : -1;
    }

    static ItemStack icon(Settings s, Cards.Card c) {
        var g = s.grades.get(Math.clamp(c.grade(), 0, s.grades.size() - 1));
        var color = TextColor.color(g.color());
        var lore = new ArrayList<Component>();
        lore.add(Component.text("[" + g.name() + "]", color));
        for (String line : Cards.describe(c)) lore.add(Component.text(line, NamedTextColor.WHITE));
        lore.add(Component.empty());
        lore.add(Component.text("눌러서 고르기", NamedTextColor.GRAY));
        String title = c.type() == Cards.Type.SPELL || c.type() == Cards.Type.ARTIFACT ? c.spellName() : c.type().title;
        var item = named(new ItemStack(c.type().icon), Component.text(title, color).decorate(TextDecoration.BOLD), lore);
        if (c.grade() >= 2) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return item;
    }

    static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        item.editMeta(m -> {
            m.displayName(name.decoration(TextDecoration.ITALIC, false));
            m.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
            m.addItemFlags(ItemFlag.values());
        });
        return item;
    }
}
