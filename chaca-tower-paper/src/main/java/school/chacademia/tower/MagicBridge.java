package school.chacademia.tower;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * MagicCodexBridge 연결. 배운 마법 = 권한 magic.learned.&lt;id&gt; (시전 검사도 hasPermission).
 * 탑 안에서는 권한 덧붙임(attachment, 디스크에 안 남음)으로 원래 마법을 모두 false, 탑에서 배운 것만 true.
 * 마법창은 브리지가 10초마다 권한을 다시 보내 저절로 바뀐다 (바로 바꾸려고 markDirty 도 부름).
 * 서버가 꺼지면 덧붙임은 사라지므로 원래 마법은 그대로 남는다.
 * 장착한 아티팩트(PDC magiccodexbridge:artifact_equipped)는 맡겼다가 돌려놓는다. 지능은 브리지 값을 건드리지 않고
 * 탑 안 피해를 "탑 지능" 기준으로 다시 맞춘다 ({@link #realPower}).
 */
final class MagicBridge implements Magic {
    private record Spell(String id, String name, String permission, int circle, boolean combat) {}

    private final TowerPlugin plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private Map<String, Spell> spells = Map.of();
    private long loadedAt;

    MagicBridge(TowerPlugin plugin) { this.plugin = plugin; }

    private Plugin bridge() {
        var b = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        return b != null && b.isEnabled() ? b : null;
    }

    @Override public boolean ready() { return bridge() != null && !spells().isEmpty(); }

    /** mana-spells.yml (이름·권한·마나) + spell-runtime.yml (유틸 여부·피해) 를 읽는다. 1분마다 새로. */
    private Map<String, Spell> spells() {
        if (!spells.isEmpty() && System.currentTimeMillis() - loadedAt < 60_000) return spells;
        var dir = new File(plugin.getDataFolder().getParentFile(), "MagicCodexBridge");
        var mana = YamlConfiguration.loadConfiguration(new File(dir, "mana-spells.yml")).getConfigurationSection("spells");
        var runtime = YamlConfiguration.loadConfiguration(new File(dir, "spell-runtime.yml")).getConfigurationSection("spells");
        var out = new java.util.LinkedHashMap<String, Spell>();
        if (mana != null) for (String id : mana.getKeys(false)) {
            if (!mana.getBoolean(id + ".enabled", true)) continue;
            String perm = mana.getString(id + ".permission", "magic.learned." + id);
            int cost = mana.getInt(id + ".mana-cost", 0);
            boolean combat = runtime != null && runtime.contains(id) && !runtime.getBoolean(id + ".utility", false)
                    && (runtime.getDouble(id + ".base-damage", 0) > 0 || runtime.getDouble(id + ".power-ratio", 0) > 0);
            out.put(id, new Spell(id, mana.getString(id + ".name", id), perm, circleOf(cost), combat));
        }
        spells = out;
        loadedAt = System.currentTimeMillis();
        return spells;
    }

    /** 서클은 따로 적힌 곳이 없어 마나 비용으로 나눈다 (공격 마법 279개 기준 대략 고르게). */
    static int circleOf(int manaCost) {
        if (manaCost <= 10) return 1;
        if (manaCost <= 30) return 2;
        if (manaCost <= 60) return 3;
        if (manaCost <= 150) return 4;
        return 5;
    }

    @Override public List<SpellInfo> combatSpells(Settings s) {
        var out = new ArrayList<SpellInfo>();
        for (var sp : spells().values())
            if (sp.combat() && !s.excludedSpells.contains(sp.id())) out.add(new SpellInfo(sp.id(), sp.name(), sp.circle()));
        return out;
    }

    @Override public List<SpellInfo> basicSpells(Settings s) {
        var out = new ArrayList<SpellInfo>();
        var all = spells();
        if (!s.basicSpells.isEmpty()) {
            for (String id : s.basicSpells) { var sp = all.get(id); if (sp != null) out.add(new SpellInfo(sp.id(), sp.name(), sp.circle())); }
            return out;
        }
        for (var sp : all.values())
            if (sp.combat() && sp.circle() == 1 && !s.excludedSpells.contains(sp.id())) out.add(new SpellInfo(sp.id(), sp.name(), sp.circle()));
        return out;
    }

    private NamespacedKey equippedKey() {
        var b = bridge();
        return b == null ? null : new NamespacedKey(b, "artifact_equipped");
    }

    @Override public String snapshot(Player p) {
        var key = equippedKey();
        String eq = key == null ? "" : p.getPersistentDataContainer().getOrDefault(key, PersistentDataType.STRING, "");
        return "artifact=" + eq;
    }

    @Override public void enterTower(Player p) {
        dropAttachment(p);
        var att = p.addAttachment(plugin);
        for (var sp : spells().values()) att.setPermission(sp.permission(), false);
        attachments.put(p.getUniqueId(), att);
        var key = equippedKey();
        if (key != null) p.getPersistentDataContainer().remove(key);
        refresh(p);
    }

    @Override public boolean learn(Player p, String spell) {
        var sp = spells().get(spell);
        var att = attachments.get(p.getUniqueId());
        if (sp == null || att == null) return false;
        att.setPermission(sp.permission(), true);
        refresh(p);
        return true;
    }

    @Override public void addIntelligence(Player p, int amount) {}

    @Override public void restore(Player p, String state) {
        dropAttachment(p);
        if (state != null && state.startsWith("artifact=")) {
            String eq = state.substring("artifact=".length());
            var key = equippedKey();
            if (key != null) {
                if (eq.isBlank()) p.getPersistentDataContainer().remove(key);
                else p.getPersistentDataContainer().set(key, PersistentDataType.STRING, eq);
            }
        }
        refresh(p);
    }

    private void dropAttachment(Player p) {
        var old = attachments.remove(p.getUniqueId());
        if (old != null) try { p.removeAttachment(old); } catch (IllegalArgumentException ignored) {}
    }

    /** 브리지에게 이 사람 권한을 다시 보내라고 (private markDirty). 실패해도 10초 안에 저절로 바뀐다. */
    private void refresh(Player p) {
        var b = bridge();
        if (b == null) return;
        try {
            var m = b.getClass().getDeclaredMethod("markDirty", UUID.class);
            m.setAccessible(true);
            m.invoke(b, p.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        p.updateCommands();
    }

    private java.lang.reflect.Field petsField;
    private java.lang.reflect.Method despawnMcPets, companions, unequip;
    private boolean petsLooked;

    /** PetBridge.despawnMcPets(Player) + PetCompanions.unequip(Player, String) — 둘 다 브리지 안 비공개라 리플렉션. 없으면 조용히 넘어간다. */
     public void dismissPets(Player p) {
        var b = bridge();
        if (b == null) return;
        try {
            if (!petsLooked) {
                petsLooked = true;
                petsField = b.getClass().getDeclaredField("pets");
                petsField.setAccessible(true);
                var petBridge = petsField.getType();
                despawnMcPets = petBridge.getDeclaredMethod("despawnMcPets", Player.class);
                despawnMcPets.setAccessible(true);
                companions = petBridge.getDeclaredMethod("companions");
                companions.setAccessible(true);
                unequip = companions.getReturnType().getDeclaredMethod("unequip", Player.class, String.class);
                unequip.setAccessible(true);
            }
            var pets = petsField == null ? null : petsField.get(b);
            if (pets == null) return;
            despawnMcPets.invoke(pets, p);
            var comp = companions.invoke(pets);
            if (comp != null) unequip.invoke(comp, p, "탑 안에서는 펫을 꺼낼 수 없어요");
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (petsField != null) plugin.getLogger().warning("펫 들여보내기 실패: " + e);
            petsField = null;
        }
    }

    /** 브리지가 계산한 지금 지능 (마법봉·장신구·발견 보너스 포함). 없으면 0. */
    @Override public double realPower(Player p) {
        var b = bridge();
        if (b == null) return 0;
        try { return ((Number) b.getClass().getMethod("magicPower", Player.class).invoke(b, p)).doubleValue(); }
        catch (ReflectiveOperationException | RuntimeException e) { return 0; }
    }

}
