package com.diamondserver.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 우편함. 다이아나 아이템을 우편으로 보내 두면, 받는 사람이 우편함에서 꺼내 간다 (접속해 있지 않아도 쌓인다).
 *  - 들어오는 곳: 관리자가 보낸 선물(/다이아 우편), 인벤토리가 가득 차서 못 받은 물건
 *  - 꺼내는 곳: 우편함 화면(모드) 또는 /우편함 받기
 * 파일: plugins/DiamondCore/mail/<UUID>.yml
 */
final class Mail {
    /**
     * 우편 한 통. kind = diamonds(보유 다이아) / item(전용 아이템 id) / material(게임 아이템 종류)
     *  / stack(인챈트 등이 붙은 게임 아이템: key 에 아이템 1개를 통째로 적어 둔다 → 꺼낼 때 그대로 나온다)
     * label = 화면에 쓸 한글 이름 (상점 상품 이름). 비어 있으면 모드가 게임의 아이템 이름으로 보여 준다.
     */
    static final class Letter {
        String id, title, from, kind, key, label = "";
        int amount;
        long time;
    }

    private final DiamondPlugin pl;
    private final File dir;
    private final Map<UUID, List<Letter>> cache = new HashMap<>();

    Mail(DiamondPlugin pl) {
        this.pl = pl;
        this.dir = new File(pl.getDataFolder(), "mail");
        dir.mkdirs();
    }

    private List<Letter> box(UUID id) {
        return cache.computeIfAbsent(id, k -> {
            List<Letter> out = new ArrayList<>();
            File f = new File(dir, k + ".yml");
            if (f.isFile()) for (Map<?, ?> m : YamlConfiguration.loadConfiguration(f).getMapList("mail")) {
                try {
                    Letter l = new Letter();
                    l.id = String.valueOf(m.get("id"));
                    l.title = String.valueOf(m.get("title"));
                    l.from = String.valueOf(m.get("from"));
                    l.kind = String.valueOf(m.get("kind"));
                    l.key = String.valueOf(m.get("key"));
                    l.label = m.get("label") == null ? "" : String.valueOf(m.get("label"));
                    l.amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
                    l.time = m.get("time") instanceof Number n ? n.longValue() : 0;
                    out.add(l);
                } catch (RuntimeException ignored) { }
            }
            return out;
        });
    }

    private void save(UUID id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Letter l : box(id)) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.id); m.put("title", l.title); m.put("from", l.from); m.put("kind", l.kind);
            m.put("key", l.key); m.put("label", l.label == null ? "" : l.label); m.put("amount", l.amount); m.put("time", l.time);
            rows.add(m);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("mail", rows);
        try { y.save(new File(dir, id + ".yml")); } catch (IOException e) { pl.getLogger().warning("우편 저장 실패 " + id + ": " + e.getMessage()); }
    }

    /** 접속을 끊은 사람의 우편함은 메모리에서 내린다 (파일에는 이미 저장돼 있다) */
    void unload(UUID id) { cache.remove(id); }

    List<Letter> list(UUID id) { return box(id); }

    int count(UUID id) { return box(id).size(); }

    /** 우편을 보낸다. 받는 사람이 접속해 있으면 화면도 바로 고친다 */
    void send(UUID to, String title, String from, String kind, String key, int amount) { send(to, title, from, kind, key, amount, ""); }

    void send(UUID to, String title, String from, String kind, String key, int amount, String label) {
        if (amount <= 0) return;
        Letter l = new Letter();
        l.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        l.title = title; l.from = from; l.kind = kind; l.key = key; l.amount = amount; l.label = label == null ? "" : label;
        l.time = System.currentTimeMillis();
        box(to).add(l);
        save(to);
        Player p = Bukkit.getPlayer(to);
        if (p != null) {
            pl.net.mail(p);
            pl.net.toast(p, "우편이 도착했습니다: " + title, true);
        } else cache.remove(to);
    }

    /** 다이아를 우편으로 */
    void sendDiamonds(UUID to, String title, String from, int amount) { send(to, title, from, "diamonds", "", amount); }

    /** 아이템 한 묶음을 우편으로 (전용 아이템이면 그 id 로, 아니면 게임 아이템 종류로) */
    void sendStack(UUID to, String title, String from, ItemStack it) { sendStack(to, title, from, it, ""); }

    /** label = 화면에 쓸 한글 이름 (모르면 빈 글자) */
    void sendStack(UUID to, String title, String from, ItemStack it, String label) {
        if (it == null || it.getType().isAir()) return;
        String custom = pl.items.idOf(it);
        if (custom != null) { send(to, title, from, "item", custom, it.getAmount()); return; }
        ItemStack one = it.clone();
        one.setAmount(1);
        if (one.equals(new ItemStack(it.getType()))) send(to, title, from, "material", it.getType().name(), it.getAmount(), label);
        else send(to, title, from, "stack", java.util.Base64.getEncoder().encodeToString(one.serializeAsBytes()), it.getAmount(), label);   // 인챈트북 등: 통째로 보관
    }

    /** 우편에 든 게임 아이템 1개 (material·stack 종류만). 읽을 수 없으면 null */
    private ItemStack stackOf(Letter l) {
        try {
            if (l.kind.equals("stack")) return ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(l.key));
            Material m = Material.matchMaterial(l.key);
            return m == null || !m.isItem() ? null : new ItemStack(m);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 화면에 쓸 내용물 이름. 한글 이름을 모르는 게임 아이템은 {item:아이디} 로 적어 보내고, 모드가 게임의 아이템 이름으로 바꾼다 */
    String contentName(Letter l) {
        return switch (l.kind) {
            case "diamonds" -> "다이아 " + l.amount + "개";
            case "item" -> pl.items.nameOf(l.key) + " ×" + l.amount;
            default -> (l.label != null && !l.label.isEmpty() ? l.label : "{item:" + icon(l)[1] + "}") + " ×" + l.amount;
        };
    }

    /** 화면에 쓸 그림: {받은 그림 이름(없으면 빈 글자), 게임 아이템 id} */
    String[] icon(Letter l) {
        if (l.kind.equals("diamonds")) return new String[]{"icons/diamond", "minecraft:diamond"};
        if (l.kind.equals("item")) return new String[]{pl.getConfig().getString("items." + l.key + ".art", ""), pl.items.materialOf(l.key).getKey().toString()};
        ItemStack it = stackOf(l);
        return new String[]{"", (it == null ? Material.PAPER : it.getType()).getKey().toString()};
    }

    /** 우편 한 통을 꺼낸다. 다 꺼냈으면 true (인벤토리가 모자라면 들어간 만큼만 꺼내고 나머지는 남는다) */
    private boolean take(Player p, Letter l) {
        if (l.kind.equals("diamonds")) {
            pl.ledger.addHeld(p, l.amount);
            return true;
        }
        ItemStack it;
        if (l.kind.equals("item")) {
            if (!Items.IDS.contains(l.key)) return true;                     // 없어진 아이템이면 그냥 지운다
            it = pl.items.make(l.key, 1);
        } else {
            it = stackOf(l);
            if (it == null) return true;
        }
        int left = l.amount, max = Math.max(1, it.getMaxStackSize());
        while (left > 0) {
            ItemStack part = it.clone();
            part.setAmount(Math.min(max, left));
            int rest = 0;
            for (ItemStack r : p.getInventory().addItem(part).values()) rest += r.getAmount();
            left -= part.getAmount() - rest;
            if (rest > 0) break;
        }
        l.amount = left;
        return left <= 0;
    }

    /** 한 통 받기 */
    void claim(Player p, String id) {
        List<Letter> box = box(p.getUniqueId());
        for (Letter l : new ArrayList<>(box)) {
            if (!l.id.equals(id)) continue;
            String what = contentName(l);
            if (take(p, l)) { box.remove(l); pl.net.toast(p, "우편을 받았습니다: " + what, true); }
            else pl.net.toast(p, "인벤토리에 빈 칸이 모자라 일부만 받았습니다.", false);
            save(p.getUniqueId());
            pl.net.mail(p);
            return;
        }
        pl.net.toast(p, "이미 받은 우편입니다.", false);
        pl.net.mail(p);
    }

    /** 모두 받기 */
    void claimAll(Player p) {
        List<Letter> box = box(p.getUniqueId());
        if (box.isEmpty()) { pl.net.toast(p, "받을 우편이 없습니다.", false); return; }
        int got = 0;
        for (Letter l : new ArrayList<>(box)) if (take(p, l)) { box.remove(l); got++; }
        save(p.getUniqueId());
        pl.net.mail(p);
        pl.net.toast(p, box.isEmpty() ? "우편 " + got + "통을 모두 받았습니다."
                : "우편 " + got + "통을 받았습니다. 인벤토리에 빈 칸이 모자라 " + box.size() + "통이 남았습니다.", box.isEmpty());
    }

    /** 물건을 준다. 인벤토리에 안 들어간 것은 우편함으로 보낸다 (바닥에 떨어뜨리지 않는다) */
    void giveOrMail(Player p, ItemStack it, String title) { giveOrMail(p, it, title, ""); }

    void giveOrMail(Player p, ItemStack it, String title, String label) {
        int left = 0;
        ItemStack sample = null;
        for (ItemStack r : p.getInventory().addItem(it).values()) { left += r.getAmount(); sample = r; }
        if (left > 0 && sample != null) {
            ItemStack rest = sample.clone();
            rest.setAmount(left);
            sendStack(p.getUniqueId(), title, "보관", rest, label);
        }
    }
}
