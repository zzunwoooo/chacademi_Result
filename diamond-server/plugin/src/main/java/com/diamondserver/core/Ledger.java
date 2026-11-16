package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 다이아 장부 규칙: 캔 다이아 → 보유 다이아, 제출하면 남은 목표가 줄고 0 이 되면 달성 */
final class Ledger {
    private final DiamondPlugin pl;

    Ledger(DiamondPlugin pl) { this.pl = pl; }

    /** 다이아(1)·다이아 블록(9)이면 다이아 개수, 아니면 0. 전용 아이템은 제외 */
    long diamondValue(ItemStack it) {
        if (it == null || pl.items.idOf(it) != null) return 0;
        if (it.getType() == Material.DIAMOND) return it.getAmount();
        if (it.getType() == Material.DIAMOND_BLOCK && pl.getConfig().getBoolean("wallet.absorb-blocks", true)) return it.getAmount() * 9L;
        return 0;
    }

    void addHeld(Player p, long n) {
        Store.PData d = pl.store.get(p);
        d.held = Math.max(0, d.held + n);
        d.dirty = true;
        pl.net.state(p);
    }

    /** 보유 다이아에서 n 개를 쓴다. 모자라면 false */
    boolean takeHeld(Player p, long n) {
        Store.PData d = pl.store.get(p);
        if (n < 0 || d.held < n) return false;
        d.held -= n;
        d.dirty = true;
        pl.net.state(p);
        return true;
    }

    /** 남은 목표를 delta 만큼 바꾼다 (0 아래로는 안 내려감). 0 이 되면 달성 처리 */
    void addGoal(Player p, long delta) {
        Store.PData d = pl.store.get(p);
        d.goal = Math.max(0, d.goal + delta);
        d.dirty = true;
        if (d.goal > 0) d.done = false;
        else if (!d.done) complete(p, d);
        pl.net.state(p);
    }

    /** 보유 다이아 n 개를 제출해 남은 목표를 줄인다. 실제로 제출한 개수를 돌려준다 */
    long submit(Player p, long n) {
        Store.PData d = pl.store.get(p);
        long real = Math.min(Math.min(n, d.held), d.goal);
        if (real <= 0) return 0;
        d.held -= real;
        d.dirty = true;
        addGoal(p, -real);
        return real;
    }

    private void complete(Player p, Store.PData d) {
        d.done = true;
        d.dirty = true;
        // 졸업: 알림만 띄운다 (모두에게 발표 창 + 알림 한 줄 + 소리). 그 뒤로 달라지는 것은 없다.
        String name = pl.nick(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            pl.net.announce(o, "icons/diamond_pile", "졸업", name + "님");
            pl.net.toast(o, name + "님이 목표 다이아를 모두 채워 졸업했습니다!", true);
            o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        if (!pl.net.hasMod(p)) p.showTitle(Title.title(Component.text("졸업", NamedTextColor.AQUA), Component.text("목표 다이아를 모두 채웠습니다", NamedTextColor.WHITE)));
    }

    /** 인벤토리의 다이아·다이아 블록을 전부 보유 다이아로 넣는다 */
    long depositAll(Player p) {
        long total = 0;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            long v = diamondValue(c[i]);
            if (v <= 0) continue;
            total += v;
            p.getInventory().setItem(i, null);
        }
        if (total > 0) addHeld(p, total);
        return total;
    }

    /** 보유 다이아 n 개를 아이템으로 꺼낸다. 인벤토리에 들어간 만큼만 꺼내고 그 개수를 돌려준다 */
    long withdraw(Player p, long n) {
        Store.PData d = pl.store.get(p);
        long want = Math.min(n, d.held);
        long given = 0;
        while (given < want) {
            int part = (int) Math.min(64, want - given);
            int left = 0;
            for (ItemStack rest : p.getInventory().addItem(new ItemStack(Material.DIAMOND, part)).values()) left += rest.getAmount();
            given += part - left;
            if (left > 0) break;
        }
        if (given > 0) {
            d.held -= given;
            d.dirty = true;
            pl.net.state(p);
        }
        return given;
    }
}
