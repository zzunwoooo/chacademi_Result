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

    private NamespacedKey equippedKey() { return key("artifact_equipped"); }
    /** 장신구 4칸: 브리지 EquipmentBridge 가 플레이어 PDC accessory_slot_0..3 (ItemStack 바이트) 에 둔다 — 가방이 아니라서 따로 맡긴다. */
    private NamespacedKey accessoryKey(int i) { return key("accessory_slot_" + i); }
    private NamespacedKey key(String name) {
        var b = bridge();
        return b == null ? null : new NamespacedKey(b, name);
    }

    /** 장신구·아티팩트를 뺀 뒤 브리지 능력치(체력·마나·지능 보정)를 다시 계산하게 한다 (public refreshStats). */
    private void refreshStats(Player p) {
        var b = bridge();
        if (b == null) return;
        try { b.getClass().getMethod("refreshStats", Player.class).invoke(b, p); }
        catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("능력치 다시 계산 실패: " + e); }
    }

    /** "artifact=<장착 목록>;acc0=<base64>;acc1=..;acc2=..;acc3=.." (없는 칸은 빈 값). */
    @Override public String snapshot(Player p) {
        var pdc = p.getPersistentDataContainer();
        var key = equippedKey();
        var sb = new StringBuilder("artifact=").append(key == null ? "" : pdc.getOrDefault(key, PersistentDataType.STRING, ""));
        for (int i = 0; i < 4; i++) {
            var k = accessoryKey(i);
            byte[] b = k == null ? null : pdc.get(k, PersistentDataType.BYTE_ARRAY);
            sb.append(";acc").append(i).append("=").append(b == null ? "" : java.util.Base64.getEncoder().encodeToString(b));
        }
        return sb.toString();
    }

    @Override public void enterTower(Player p) {
        dropAttachment(p);
        var att = p.addAttachment(plugin);
        for (var sp : spells().values()) att.setPermission(sp.permission(), false);
        attachments.put(p.getUniqueId(), att);
        var key = equippedKey();
        if (key != null) p.getPersistentDataContainer().remove(key);
        for (int i = 0; i < 4; i++) { var k = accessoryKey(i); if (k != null) p.getPersistentDataContainer().remove(k); }
        refreshStats(p);
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

    @Override public void clearMana(Player p) { unpinMana(p); }

    @Override public boolean restore(Player p, String state) {
        boolean ok = bridge() != null;   // 브리지가 꺼져 있으면 PDC 열쇠를 못 만들어 장신구·아티팩트를 못 돌려놓는다
        dropAttachment(p);
        unpinMana(p);
        var pdc = p.getPersistentDataContainer();
        if (state != null && state.startsWith("artifact=")) {
            for (String part : state.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String name = part.substring(0, eq), value = part.substring(eq + 1);
                if (name.equals("artifact")) {
                    var key = equippedKey();
                    if (key == null) continue;
                    if (value.isBlank()) pdc.remove(key); else pdc.set(key, PersistentDataType.STRING, value);
                } else if (name.startsWith("acc")) {
                    var key = accessoryKey(Integer.parseInt(name.substring(3)));
                    if (key == null) continue;
                    if (value.isBlank()) pdc.remove(key); else pdc.set(key, PersistentDataType.BYTE_ARRAY, java.util.Base64.getDecoder().decode(value));
                }
            }
        }
        refreshStats(p);
        refresh(p);
        return ok;
    }

    // ── 마나: ManaService(브리지 mana.mana, public 클래스) 의 수정자 하나로 최대치·회복을 탑 값에 맞춘다 (다른 보너스가 남아 있어도 결과 고정)
    private static final String MANA_KEY = "chacatower:tower";
    private final Map<UUID, double[]> manaOurs = new HashMap<>();
    private Object manaService;
    private java.lang.reflect.Method manaSnapshot, manaSet, manaRemove;
    private boolean manaLooked;

    private Object mana() {
        var b = bridge();
        if (b == null) return null;
        if (manaLooked) return manaService;
        manaLooked = true;
        try {
            var f = b.getClass().getDeclaredField("mana"); f.setAccessible(true);
            var mb = f.get(b);
            var f2 = mb.getClass().getDeclaredField("mana"); f2.setAccessible(true);
            manaService = f2.get(mb);
            var c = manaService.getClass();
            manaSnapshot = c.getMethod("snapshot", UUID.class);
            manaSet = c.getMethod("setModifier", UUID.class, String.class, double.class, double.class);
            manaRemove = c.getMethod("removeModifier", UUID.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("마나 연결 실패 (탑 안 마나 고정 안 됨): " + e); manaService = null; }
        return manaService;
    }

    @Override public void pinMana(Player p, double max, double regen) {
        var ms = mana();
        if (ms == null) return;
        var id = p.getUniqueId();
        var ours = manaOurs.computeIfAbsent(id, k -> new double[2]);
        try {
            for (int i = 0; i < 4; i++) {
                var snap = ((java.util.Optional<?>) manaSnapshot.invoke(ms, id)).orElse(null);
                if (snap == null) return;
                double curMax = ((Number) snap.getClass().getMethod("maximum").invoke(snap)).doubleValue();
                double curRegen = ((Number) snap.getClass().getMethod("regeneration").invoke(snap)).doubleValue();
                if (Math.abs(curMax - max) < 1e-6 && Math.abs(curRegen - regen) < 1e-6 && (ours[0] != 0 || ours[1] != 0 || i > 0)) return;
                ours[0] += max - curMax;
                ours[1] += regen - curRegen;
                manaSet.invoke(ms, id, MANA_KEY, ours[0], ours[1]);
            }
        } catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("마나 고정 실패: " + e); }
    }

    private void unpinMana(Player p) {
        manaOurs.remove(p.getUniqueId());
        var ms = mana();
        if (ms == null) return;
        try { manaRemove.invoke(ms, p.getUniqueId(), MANA_KEY); }
        catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("마나 되돌리기 실패: " + e); }
    }

    // ── 탑 안 영구 습득 막기: 들어갈 때 LuckPerms 에 있는 magic.learned.* true 노드를 기억, 나갈 때 새로 생긴 것을 뗀다 (콘솔 lp 명령)
    private final Map<UUID, java.util.Set<String>> learnedAtEntry = new HashMap<>();

    /** LuckPerms 유저의 영구 노드 중 magic.learned.* = true (리플렉션, LP 없으면 빈 목록). */
    private java.util.Set<String> learnedNow(Player p) {
        var out = new java.util.HashSet<String>();
        try {
            var provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            var lp = provider.getMethod("get").invoke(null);
            var um = lp.getClass().getMethod("getUserManager").invoke(lp);
            var user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
            if (user == null) return out;
            var nodes = (java.util.Collection<?>) user.getClass().getMethod("getNodes").invoke(user);
            for (var n : nodes) {
                String key = (String) n.getClass().getMethod("getKey").invoke(n);
                boolean value = (Boolean) n.getClass().getMethod("getValue").invoke(n);
                if (value && key.startsWith("magic.learned.")) out.add(key);
            }
        } catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("배운 마법 목록 읽기 실패: " + e); return null; }
        return out;
    }

    @Override public void rememberLearned(Player p) {
        var now = learnedNow(p);
        if (now != null) learnedAtEntry.put(p.getUniqueId(), now);
    }

    @Override public void unlearnNew(Player p) {
        var before = learnedAtEntry.remove(p.getUniqueId());
        if (before == null) return;
        var now = learnedNow(p);
        if (now == null) return;
        for (String perm : now) {
            if (before.contains(perm)) continue;
            String id = perm.substring("magic.learned.".length());
            plugin.getLogger().warning("탑 안에서 영구 습득이 생겨 되돌림: " + p.getName() + " " + id);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + p.getUniqueId() + " permission unset " + perm);
            forgetInDiscovery(p, id);
        }
    }

    /** 발견 플러그인(MagicDiscovery) 의 습득 기록도 되돌린다: DiscoveryService.adminSetLearned(uuid, spell, false, done). 없으면 lp 만. */
    private void forgetInDiscovery(Player p, String spell) {
        var disc = Bukkit.getPluginManager().getPlugin("MagicDiscovery");
        if (disc == null || !disc.isEnabled()) return;
        try {
            Class<?> type = Class.forName("school.magiccodex.discovery.DiscoveryService", false, disc.getClass().getClassLoader());
            Object service = Bukkit.getServicesManager().load(type);
            if (service == null) return;
            java.util.function.Consumer<String> done = err -> { if (err != null) plugin.getLogger().warning("발견 기록 되돌리기 실패 " + spell + ": " + err); };
            type.getMethod("adminSetLearned", UUID.class, String.class, boolean.class, java.util.function.Consumer.class).invoke(service, p.getUniqueId(), spell, false, done);
        } catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("발견 플러그인 연결 실패: " + e); }
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
