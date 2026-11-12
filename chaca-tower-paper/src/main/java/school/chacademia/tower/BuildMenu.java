package school.chacademia.tower;

// [기능:탑내증강 시작]
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
import org.bukkit.inventory.ItemStack;

/**
 * 내 증강 창 (상자 창). 클라 모드가 없는 사람용 — 모드가 있으면 TowerClient 가 카드 그림 화면을 연다.
 * 위 5줄(45칸)에 얻은 것, 맨 아랫줄에 이전(45) · 돌아가기(49) · 다음(53).
 */
final class BuildMenu implements InventoryHolder {
    static final int PER_PAGE = 45, PREV_SLOT = 45, BACK_SLOT = 49, NEXT_SLOT = 53;

    private final Inventory inv;
    private final int page, pages;
    /** 카드 고르는 중에 열었는지 (닫으면 카드 창으로 돌아간다). */
    final boolean fromCards;

    BuildMenu(Settings s, Run run, int page, boolean fromCards) {
        var list = Cards.owned(s, run);
        this.pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.clamp(page, 0, pages - 1);
        this.fromCards = fromCards;
        inv = Bukkit.createInventory(this, 54, Component.text("내 증강 · " + run.floor + "층" + (pages > 1 ? " (" + (this.page + 1) + "/" + pages + ")" : "")));
        for (int i = 0; i < PER_PAGE; i++) {
            int at = this.page * PER_PAGE + i;
            if (at >= list.size()) break;
            inv.setItem(i, icon(s, list.get(at)));
        }
        var glass = CardMenu.named(new ItemStack(Material.BLACK_STAINED_GLASS_PANE), Component.text(" "), List.of());
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);
        if (this.page > 0) inv.setItem(PREV_SLOT, CardMenu.named(new ItemStack(Material.ARROW), Component.text("이전", NamedTextColor.WHITE), List.of()));
        if (this.page < pages - 1) inv.setItem(NEXT_SLOT, CardMenu.named(new ItemStack(Material.ARROW), Component.text("다음", NamedTextColor.WHITE), List.of()));
        inv.setItem(BACK_SLOT, CardMenu.named(new ItemStack(Material.BARRIER), Component.text(fromCards ? "카드로 돌아가기" : "닫기", NamedTextColor.WHITE), List.of()));
    }

    @Override public Inventory getInventory() { return inv; }

    void open(Player p) { p.openInventory(inv); }

    /** 누른 칸이 쪽 넘기기면 갈 쪽 번호, 아니면 -1. */
    int pageFor(int slot) {
        if (slot == PREV_SLOT && page > 0) return page - 1;
        if (slot == NEXT_SLOT && page < pages - 1) return page + 1;
        return -1;
    }

    private static ItemStack icon(Settings s, Cards.Owned o) {
        var g = s.grades.get(Math.clamp(o.grade(), 0, s.grades.size() - 1));
        var color = TextColor.color(g.color());
        var lore = new ArrayList<Component>();
        lore.add(Component.text("[" + g.name() + "]" + (o.count() > 1 ? " " + o.count() + "번 고름" : ""), color));
        for (String line : o.lines()) lore.add(Component.text(line, NamedTextColor.WHITE));
        var item = CardMenu.named(new ItemStack(o.item(), Math.clamp(o.count(), 1, o.item().getMaxStackSize())), Component.text(o.name(), color).decorate(TextDecoration.BOLD), lore);
        if (o.grade() >= 2) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return item;
    }
}
// [기능:탑내증강 끝]
