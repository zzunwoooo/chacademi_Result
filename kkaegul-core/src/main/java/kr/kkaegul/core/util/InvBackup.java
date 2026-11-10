package kr.kkaegul.core.util;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 인벤토리 백업 (관리자 복구용). backup.minutes 마다 접속자 인벤토리(가방·갑옷·왼손)를 backups/<uuid>.yml 에 저장,
 * 최근 backup.keep 개만 보관 (바뀐 게 없으면 새로 저장 안 함). 1번 = 가장 최근.
 */
public final class InvBackup {
    private final KkaegulCore plugin;
    private final File dir;

    public record Snap(long at, List<String> items) {}

    public InvBackup(KkaegulCore plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "backups");
        long every = Math.max(1, plugin.getConfig().getLong("backup.minutes", 5)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) save(p); }, every, every);
    }

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private static List<String> encode(ItemStack[] items) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : items) out.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return out;
    }

    private static ItemStack[] decode(List<String> items) {
        ItemStack[] out = new ItemStack[items.size()];
        for (int i = 0; i < out.length; i++) {
            String s = items.get(i);
            try { out[i] = s == null || s.isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
            catch (RuntimeException e) { out[i] = null; }
        }
        return out;
    }

    /** 저장된 백업 (1번 = 가장 최근) */
    public List<Snap> list(UUID id) {
        List<Snap> out = new ArrayList<>();
        File f = file(id);
        if (!f.exists()) return out;
        for (Map<?, ?> m : YamlConfiguration.loadConfiguration(f).getMapList("snapshots")) {
            Object at = m.get("at"), items = m.get("items");
            if (!(at instanceof Number n) || !(items instanceof List<?> l)) continue;
            List<String> s = new ArrayList<>();
            for (Object o : l) s.add(o == null ? "" : o.toString());
            out.add(new Snap(n.longValue(), s));
        }
        return out;
    }

    /** 지금 인벤토리를 맨 앞에 저장 (직전 것과 같으면 안 함) */
    public void save(Player p) {
        List<String> items = encode(p.getInventory().getContents());
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        int keep = Math.max(1, plugin.getConfig().getInt("backup.keep", 12));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                List<Snap> l = list(id);
                if (!l.isEmpty() && l.get(0).items().equals(items)) return;
                l.add(0, new Snap(now, items));
                while (l.size() > keep) l.remove(l.size() - 1);
                YamlConfiguration y = new YamlConfiguration();
                List<Map<String, Object>> ms = new ArrayList<>();
                for (Snap s : l) { Map<String, Object> m = new LinkedHashMap<>(); m.put("at", s.at()); m.put("items", s.items()); ms.add(m); }
                y.set("snapshots", ms);
                try { dir.mkdirs(); y.save(file(id)); } catch (IOException e) { plugin.getLogger().warning("[백업] 저장 실패 " + id + ": " + e); }
            }
        });
    }

    public static String time(long at) { return new SimpleDateFormat("MM/dd HH:mm").format(new Date(at)); }

    /** 백업 n번(1부터)을 상자 창으로 (사본이라 꺼내도 백업은 그대로) */
    public boolean view(Player admin, UUID id, String name, int n) {
        List<Snap> l = list(id);
        if (n < 1 || n > l.size()) return false;
        Snap s = l.get(n - 1);
        Inventory inv = Bukkit.createInventory(null, 54, "백업 " + name + " " + n + "번 " + time(s.at()));
        ItemStack[] items = decode(s.items());
        for (int i = 0; i < items.length && i < 54; i++) if (items[i] != null) inv.setItem(i, items[i]);
        admin.openInventory(inv);
        return true;
    }

    /** 백업 n번으로 통째로 되돌림 (되돌리기 전 지금 인벤토리도 백업해 둠) */
    public boolean restore(Player target, int n) {
        List<Snap> l = list(target.getUniqueId());
        if (n < 1 || n > l.size()) return false;
        ItemStack[] items = decode(l.get(n - 1).items());
        save(target);
        ItemStack[] full = new ItemStack[target.getInventory().getContents().length];
        System.arraycopy(items, 0, full, 0, Math.min(items.length, full.length));
        target.getInventory().setContents(full);
        target.updateInventory();
        return true;
    }
}
