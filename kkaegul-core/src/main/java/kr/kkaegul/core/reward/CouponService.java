package kr.kkaegul.core.reward;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.item.MailService;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠폰 (차카데미 /쿠폰 과 같은 방식).
 *  - /쿠폰관리 만들기 <이름>: 상자 창이 열리고, 보상 아이템을 넣고 닫으면 저장 (넣은 아이템은 쿠폰으로 들어감)
 *  - /쿠폰관리 편집 <이름> / 목록 / 삭제 <이름>
 *  - /쿠폰 <이름>: 한 사람당 한 번, 보상은 우편함으로
 * 저장: plugins/KkaegulCore/coupons.yml (coupons.<이름>.items = 아이템 Base64 목록, uses = 쓴 사람 UUID)
 */
public final class CouponService implements Listener {
    private final KkaegulCore plugin;
    private final File file;
    private YamlConfiguration yml;

    /** 쿠폰 보상 편집 창 */
    private static final class Holder implements InventoryHolder {
        final String name; Inventory inv;
        Holder(String name) { this.name = name; }
        @Override public Inventory getInventory() { return inv; }
    }

    public CouponService(KkaegulCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "coupons.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void save() {
        try { yml.save(file); } catch (IOException e) { plugin.getLogger().warning("쿠폰 저장 실패: " + e); }
    }

    private static boolean validName(String n) { return n != null && n.matches("\\S{1,32}"); }
    private ConfigurationSection sec(String name) { return yml.getConfigurationSection("coupons." + name); }
    public List<String> names() {
        ConfigurationSection s = yml.getConfigurationSection("coupons");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private void ok(Player p, String msg) { p.sendMessage(Txt.color(Txt.notice("&f" + msg))); }

    // ───────── 관리자 ─────────
    public void admin(Player p, String[] a) {
        String sub = a.length > 0 ? a[0] : "";
        String name = a.length > 1 ? a[1] : "";
        switch (sub) {
            case "만들기", "create" -> {
                if (!validName(name)) { plugin.ui().error(p, "&c쿠폰 이름은 공백 없이 1~32자예요"); return; }
                if (sec(name) != null) { plugin.ui().error(p, "&c이미 있는 쿠폰이에요. /쿠폰관리 편집 " + name); return; }
                openEditor(p, name, List.of());
            }
            case "편집", "edit" -> {
                ConfigurationSection s = sec(name);
                if (s == null) { plugin.ui().error(p, "&c없는 쿠폰이에요"); return; }
                openEditor(p, name, decodeAll(s.getStringList("items")));
            }
            case "목록", "list" -> {
                List<String> ns = names();
                if (ns.isEmpty()) { ok(p, "&7쿠폰이 없어요. /쿠폰관리 만들기 <이름>"); return; }
                ok(p, "&e[쿠폰] &f" + ns.size() + "개");
                for (String n : ns) {
                    ConfigurationSection s = sec(n);
                    p.sendMessage(Txt.color(" &f" + n + " &7· 보상 " + s.getStringList("items").size() + "칸 · 사용 " + s.getStringList("uses").size() + "명"));
                }
            }
            case "삭제", "delete" -> {
                if (sec(name) == null) { plugin.ui().error(p, "&c없는 쿠폰이에요"); return; }
                yml.set("coupons." + name, null);
                save();
                ok(p, "쿠폰 " + name + " 을(를) 지웠어요 &7(사용 기록도 함께)");
            }
            default -> {
                ok(p, "&e/쿠폰관리 만들기 <이름> &7- 상자 창에 보상 아이템을 넣고 닫으면 저장");
                ok(p, "&e/쿠폰관리 편집 <이름> &7- 쿠폰 보상 아이템 고치기");
                ok(p, "&e/쿠폰관리 목록 &7- 쿠폰 목록 · 보상 개수 · 사용한 사람 수");
                ok(p, "&e/쿠폰관리 삭제 <이름> &7- 쿠폰 지우기 (사용 기록도 함께)");
                ok(p, "&7유저는 &f/쿠폰 <이름> &7(한 사람당 한 번, 우편으로 받아요)");
            }
        }
    }

    private void openEditor(Player p, String name, List<ItemStack> items) {
        Holder h = new Holder(name);
        h.inv = Bukkit.createInventory(h, 54, "쿠폰 보상: " + name);
        for (ItemStack it : items) h.inv.addItem(it);
        p.openInventory(h.inv);
        ok(p, "&7보상 아이템을 넣고 창을 닫으면 저장돼요 (넣은 아이템은 쿠폰으로 들어가요)");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || !(e.getPlayer() instanceof Player p)) return;
        List<String> enc = new ArrayList<>();
        for (ItemStack it : h.inv.getContents()) if (it != null && !it.getType().isAir()) enc.add(MailService.encode(it));
        if (enc.isEmpty()) {
            if (sec(h.name) == null) { ok(p, "&7아이템이 없어 쿠폰 " + h.name + " 을(를) 저장하지 않았어요"); return; }
            plugin.ui().error(p, "&c보상이 비어 있어요. 지우려면 /쿠폰관리 삭제 " + h.name);
            return;
        }
        String path = "coupons." + h.name;
        yml.set(path + ".items", enc);
        yml.set(path + ".by", plugin.displayName(p));
        yml.set(path + ".at", System.currentTimeMillis());
        if (!yml.contains(path + ".uses")) yml.set(path + ".uses", new ArrayList<String>());
        save();
        ok(p, "쿠폰 &e" + h.name + " &f저장 (보상 " + enc.size() + "칸) &7· 유저는 /쿠폰 " + h.name);
    }

    // ───────── 유저 ─────────
    public void use(Player p, String name) {
        if (name == null || name.isEmpty()) { plugin.ui().error(p, "&c/쿠폰 <쿠폰 이름>"); return; }
        ConfigurationSection s = sec(name);
        if (s == null) { plugin.ui().error(p, "&c없는 쿠폰이에요"); return; }
        List<String> uses = new ArrayList<>(s.getStringList("uses"));
        String id = p.getUniqueId().toString();
        if (uses.contains(id)) { plugin.ui().error(p, "&c이미 사용한 쿠폰이에요"); return; }
        List<ItemStack> items = decodeAll(s.getStringList("items"));
        if (items.isEmpty()) { plugin.ui().error(p, "&c쿠폰을 쓰지 못했어요"); return; }
        uses.add(id);
        s.set("uses", uses);
        save();
        for (ItemStack it : items) plugin.mail().send(p.getUniqueId(), it, "쿠폰 「" + name + "」 보상이에요.");
        plugin.ui().center(p, "&a쿠폰을 사용했어요", "&f보상이 우편함으로 왔어요");
        plugin.getLogger().info("쿠폰 사용: " + name + " → " + plugin.displayName(p));
    }

    private static List<ItemStack> decodeAll(List<String> enc) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : enc) { ItemStack it = MailService.decode(s); if (it != null) out.add(it); }
        return out;
    }
}
