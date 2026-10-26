package school.magiccodex.paper;

import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * 변신 가면 (shapeshift_mask, 사용 키, 그룹 D 2026-10-26): 보유한 펫 하나의 모습으로 30초간 변신.
 * 방법: 펫의 ModelEngine 모델을 플레이어 몸에 씌우고(ModeledEntity) 원래 몸은 숨긴다. 서버가 매 틱 하는 일 없음.
 * 모델 찾는 순서: 지금 소환 중인 내 펫의 모델 → shapeshift.yml 의 models.&lt;펫 ID&gt; → 펫의 MythicMobs 몹 Skills 에 있는 model{mid=...}.
 * 30초 뒤·사망·세계 이동·로그아웃(서버 이동 포함)·플러그인 종료 때 반드시 원상 복구. 접속 때 남은 표시(PDC)가 있으면 정리.
 * 큐디치·결투 경기 중, 빗자루 비행 중에는 쓸 수 없다 (실패 → 쿨타임 없음).
 */
final class ArtifactShapeshift implements Listener {
    static final String ID = "shapeshift_mask";
    static final long TICKS = 600;
    private static final Pattern MODEL = Pattern.compile("(?i)model\\s*\\{[^}]*?\\b(?:mid|m|modelid|model)\\s*=\\s*([a-z0-9_\\-.]+)");
    private final ArtifactEffects fx;
    private final MagicCodexBridge plugin;
    private final NamespacedKey key;
    private record Active(String model, boolean created, int task) {}
    private final Map<UUID, Active> active = new HashMap<>();

    ArtifactShapeshift(ArtifactEffects fx) {
        this.fx = fx; this.plugin = fx.plugin; key = new NamespacedKey(plugin, "shapeshift_model");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    static boolean available() {
        var me = Bukkit.getPluginManager().getPlugin("ModelEngine");
        return me != null && me.isEnabled();
    }

    /** ArtifactActions 의 사용 키. "!" 로 시작 = 실패 (쿨타임 없음). */
    String use(Player p) {
        if (!available()) return "!변신에 필요한 ModelEngine 이 서버에 없어요.";
        if (active.containsKey(p.getUniqueId())) return "!이미 변신 중이에요.";
        if (GeneralPresetBridge.combat(p.getUniqueId())) return "!경기 중에는 변신할 수 없어요.";
        if (riding(p)) return "!빗자루를 타는 중에는 변신할 수 없어요.";
        var pets = plugin.pets();
        if (pets == null) return "!펫 정보를 불러오지 못했어요.";
        var owned = pets.owned(p);
        if (owned.isEmpty()) return "!보유한 펫이 없어 변신할 수 없어요.";
        // 1) 소환 중인 내 펫
        for (Entity e : p.getNearbyEntities(32, 16, 32)) {
            if (!pets.ownedBy(e, p.getUniqueId())) continue;
            String model = Meg.modelOf(e);
            if (model != null && apply(p, model)) return "펫의 모습으로 30초간 변신했어요.";
        }
        // 2) 보유 펫 중 무작위 (모델을 찾을 수 있는 것)
        var list = new ArrayList<>(owned); Collections.shuffle(list, fx.random);
        var overrides = overrides();
        for (var pet : list) {
            String model = overrides.get(pet.id());
            if (model == null && pet.mythic() != null) model = Meg.mythicModel(pet.mythic(), MODEL);
            if (model != null && apply(p, model)) return pet.name() + "의 모습으로 30초간 변신했어요.";
        }
        return "!변신할 펫 모델을 찾지 못했어요. (관리자: shapeshift.yml 확인)";
    }

    /** plugins/MagicCodexBridge/shapeshift.yml: models: { 펫ID: ModelEngine 모델 ID } (자동으로 못 찾는 펫만). */
    private Map<String, String> overrides() {
        var file = plugin.getDataFolder().toPath().resolve("shapeshift.yml");
        try {
            if (!Files.exists(file)) Files.writeString(file, "# 변신 가면: 펫 ID(MCPets) -> ModelEngine 모델 ID. 자동으로 못 찾는 펫만 적으면 된다.\nmodels: {}\n");
            var y = YamlConfiguration.loadConfiguration(file.toFile());
            var sec = y.getConfigurationSection("models");
            var out = new HashMap<String, String>();
            if (sec != null) for (String k : sec.getKeys(false)) { String v = sec.getString(k); if (v != null && !v.isBlank()) out.put(k, v.trim()); }
            return out;
        } catch (java.io.IOException | RuntimeException e) { return Map.of(); }
    }

    private boolean riding(Player p) {
        var m = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (m == null || !m.isEnabled()) return false;
        try { return Boolean.TRUE.equals(m.getClass().getMethod("isRidingBroom", Player.class).invoke(m, p)); }
        catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private boolean apply(Player p, String model) {
        Boolean created;
        try { created = Meg.disguise(p, model); }
        catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("변신 가면 실패 (" + model + "): " + e); return false; }
        if (created == null) return false;
        p.getPersistentDataContainer().set(key, PersistentDataType.STRING, model + (created ? "|1" : "|0"));
        int task = Bukkit.getScheduler().runTaskLater(plugin, () -> restore(p, true), TICKS).getTaskId();
        active.put(p.getUniqueId(), new Active(model, created, task));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 24, .4, .6, .4, .02);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.2f);
        return true;
    }

    void restore(Player p, boolean effect) {
        var a = active.remove(p.getUniqueId());
        if (a == null) return;
        Bukkit.getScheduler().cancelTask(a.task());
        try { Meg.undisguise(p, a.model(), a.created()); }
        catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("변신 가면 복구 오류: " + e); }
        p.getPersistentDataContainer().remove(key);
        if (effect && p.isOnline()) {
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 24, .4, .6, .4, .02);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, .8f);
            ArtifactEffects.notice(p, "shapeshift_end", "변신 가면", "원래 모습으로 돌아왔어요");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void died(PlayerDeathEvent e) { restore(e.getEntity(), false); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { restore(e.getPlayer(), true); }
    @EventHandler(priority = EventPriority.LOWEST) public void quit(PlayerQuitEvent e) { restore(e.getPlayer(), false); }
    /** 서버가 꺼지는 등으로 복구 못 한 흔적이 남았으면 접속 때 정리. */
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String left = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (left == null) return;
        p.getPersistentDataContainer().remove(key);
        if (!available()) return;
        String[] parts = left.split("\\|", 2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            try { Meg.undisguise(p, parts[0], parts.length > 1 && parts[1].equals("1")); } catch (RuntimeException | LinkageError ignored) { }
        }, 20);
    }

    void close() {
        for (UUID id : List.copyOf(active.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) restore(p, false); }
        active.clear();
        HandlerList.unregisterAll(this);
    }

    /** ModelEngine 4 / MythicMobs 직접 호출 (이 클래스는 ModelEngine 이 있을 때만 불린다: available()). */
    private static final class Meg {
        /** @return true = 새 ModeledEntity 를 만들었음, false = 이미 있던 것에 모델만 더함, null = 모델 없음 */
        static Boolean disguise(Player p, String modelId) {
            var model = com.ticxo.modelengine.api.ModelEngineAPI.createActiveModel(modelId);
            if (model == null) return null;
            var modeled = com.ticxo.modelengine.api.ModelEngineAPI.getModeledEntity(p.getUniqueId());
            boolean created = modeled == null;
            if (created) modeled = com.ticxo.modelengine.api.ModelEngineAPI.createModeledEntity(p);
            try {
                modeled.addModel(model, true);
                modeled.setBaseEntityVisible(false);
            } catch (RuntimeException e) {
                model.destroy();
                if (created) { modeled.destroy(); com.ticxo.modelengine.api.ModelEngineAPI.removeModeledEntity(p.getUniqueId()); }
                throw e;
            }
            return created;
        }
        static void undisguise(Player p, String modelId, boolean created) {
            var modeled = com.ticxo.modelengine.api.ModelEngineAPI.getModeledEntity(p.getUniqueId());
            if (modeled == null) return;
            var model = modeled.getModels().get(modelId);
            modeled.removeModel(modelId);
            if (model != null) model.destroy();
            modeled.setBaseEntityVisible(true);
            if (created || modeled.getModels().isEmpty()) { modeled.destroy(); com.ticxo.modelengine.api.ModelEngineAPI.removeModeledEntity(p.getUniqueId()); }
        }
        static String modelOf(Entity e) {
            var modeled = com.ticxo.modelengine.api.ModelEngineAPI.getModeledEntity(e.getUniqueId());
            if (modeled == null || modeled.getModels().isEmpty()) return null;
            return modeled.getModels().keySet().iterator().next();
        }
        static String mythicModel(String mob, Pattern pattern) {
            try {
                var mm = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().getMythicMob(mob).orElse(null);
                if (mm == null) return null;
                for (String line : mm.getConfig().getStringList("Skills")) {
                    var m = pattern.matcher(line);
                    if (m.find()) return m.group(1);
                }
            } catch (RuntimeException | LinkageError ignored) { }
            return null;
        }
    }
}
