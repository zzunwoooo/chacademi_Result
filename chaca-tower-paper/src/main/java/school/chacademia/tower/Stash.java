package school.chacademia.tower;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * 입장 때 맡긴 것 (소지품 전부·갑옷·보조손·경험치·체력·배고픔·효과 + 마법 상태 글자) 을 파일로 보관.
 * 파일이 있는 동안은 "아직 돌려주지 않음" — 서버가 꺼져도 다음 접속 때 돌려준다.
 * 저장은 바로 디스크에 쓴다 (임시 파일 → 바꿔치기).
 */
final class Stash {
    private final File dir;
    private final Logger log;

    Stash(File dataFolder, Logger log) {
        this.dir = new File(dataFolder, "stash");
        this.log = log;
        dir.mkdirs();
    }

    boolean has(UUID id) { return file(id).exists(); }

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    /** 맡기기. 실패하면 false (그럼 입장하지 않는다). magicState = Magic.snapshot 결과. */
    boolean store(Player p, String magicState) {
        // 아직 돌려주지 않은 맡김이 있으면 덮어쓰지 않는다 (원래 물건 기록이 사라짐)
        if (has(p.getUniqueId())) { log.severe("맡기기 거부: 이전 맡김이 남아 있음 " + p.getName()); return false; }
        var y = new YamlConfiguration();
        y.set("name", p.getName());
        y.set("at", System.currentTimeMillis());
        y.set("items", encode(p.getInventory().getContents()));
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        var effects = new ArrayList<String>();
        for (var e : p.getActivePotionEffects())
            effects.add(e.getType().getKey().getKey() + "," + e.getDuration() + "," + e.getAmplifier());
        y.set("effects", effects);
        y.set("magic", magicState == null ? "" : magicState);
        try {
            var tmp = new File(dir, p.getUniqueId() + ".yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), file(p.getUniqueId()).toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            log.severe("맡기기 저장 실패 " + p.getName() + ": " + e);
            return false;
        }
    }

    /** 맡긴 마법 상태 (돌려주기 전에 읽음). */
    String magicState(UUID id) {
        if (!has(id)) return null;
        return YamlConfiguration.loadConfiguration(file(id)).getString("magic", "");
    }

    /** 돌려주기: 지금 들고 있는 것(탑에서 얻은 것)은 지우고 맡긴 것으로. 성공하면 파일을 지운다. */
    boolean restore(Player p) {
        var f = file(p.getUniqueId());
        if (!f.exists()) return false;
        var y = YamlConfiguration.loadConfiguration(f);
        try {
            var items = decode(y.getStringList("items"));
            p.getInventory().clear();
            p.getInventory().setContents(items);
            p.setLevel(y.getInt("level"));
            p.setExp((float) y.getDouble("exp"));
            for (var e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            for (String line : y.getStringList("effects")) {
                String[] a = line.split(",");
                var type = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(a[0]));
                if (type != null) p.addPotionEffect(new PotionEffect(type, Integer.parseInt(a[1]), Integer.parseInt(a[2])));
            }
            var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(Math.clamp(y.getDouble("health", 20), 1, max == null ? 20 : max.getValue()));
            p.setFoodLevel(y.getInt("food", 20));
            p.setSaturation((float) y.getDouble("saturation", 5));
            p.updateInventory();
        } catch (RuntimeException e) {
            log.severe("돌려주기 실패 " + p.getName() + " (파일은 남겨 둠 " + f.getName() + "): " + e);
            return false;
        }
        // 지우지 않고 이름만 바꿔 하루 정도 남겨 둔다 (문제 생기면 꺼내 볼 수 있게)
        var done = new File(dir, "returned");
        done.mkdirs();
        if (!f.renameTo(new File(done, p.getUniqueId() + "-" + System.currentTimeMillis() + ".yml"))) f.delete();
        cleanReturned(done);
        return true;
    }


    // ── 미뤄 둔 장신구·아티팩트 (브리지가 꺼져 있어 못 돌려놓은 경우): stash/magic/<uuid>.txt 에 글자만 남겨 두고 다음 접속 때 다시 시도
    private File magicFile(UUID id) { return new File(new File(dir, "magic"), id + ".txt"); }

    void keepMagic(UUID id, String state) {
        if (state == null || state.isEmpty()) return;
        try {
            var f = magicFile(id);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), state);
        } catch (Exception e) { log.severe("장신구·아티팩트 기록 남기기 실패 " + id + ": " + e + " — 값: " + state); }
    }

    String keptMagic(UUID id) {
        var f = magicFile(id);
        if (!f.exists()) return null;
        try { return Files.readString(f.toPath()); } catch (Exception e) { log.warning("장신구·아티팩트 기록 읽기 실패 " + id + ": " + e); return null; }
    }

    void dropMagic(UUID id) { magicFile(id).delete(); }

    /** 맡김 파일을 쓰지 않고 보관만 (가방에 이미 진짜 소지품이 있을 때). 필요하면 운영자가 stash/conflict 에서 꺼내 본다. */
    void conflict(UUID id) {
        var f = file(id);
        if (!f.exists()) return;
        var keep = new File(dir, "conflict");
        keep.mkdirs();
        if (!f.renameTo(new File(keep, id + "-" + System.currentTimeMillis() + ".yml"))) log.severe("맡김 파일 보관 실패 (그대로 남음): " + f.getName());
    }
    /** 돌려준 지 3일 지난 사본은 지운다. */
    private static void cleanReturned(File done) {
        var files = done.listFiles();
        if (files == null) return;
        long cut = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (var f : files) if (f.lastModified() < cut) f.delete();
    }

    private static List<String> encode(ItemStack[] items) {
        var out = new ArrayList<String>();
        for (var it : items) out.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return out;
    }

    private static ItemStack[] decode(List<String> lines) {
        var out = new ItemStack[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            out[i] = s == null || s.isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        }
        return out;
    }
}
