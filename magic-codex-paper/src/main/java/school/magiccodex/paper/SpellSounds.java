package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * 마법 효과음 (오픈 전 메모 #47, 오너 결정 2026-10-25): 마인크래프트 기본 사운드를 겹치고(층) 시간차를 둬서 마법마다 효과음을 만든다.
 * 서버가 재생하므로 주변 사람도 듣는다 (예전 클라 시전음은 본인만 들렸음 → 클라 시전음은 끔).
 * <pre>
 * spell-sounds.yml
 *   spells:
 *     ember_bolt:
 *       cast: [ {sound: entity.blaze.shoot, volume: 0.8, pitch: 1.2, delay: 0}, ... ]   시전 성공 순간 (시전자 위치)
 *       hit:  [ ... ]   맞힌 순간 (맞은 자리) — VFX 사건 hit·contact·impact·ground_hit·arrive·absorb 등
 *       end:  [ ... ]   끝날 때 (사라진 자리) — end·dismiss·cancel·fizzle·miss·block_contact 등
 *       &lt;사건 이름&gt;: [ ... ]   그 밖의 VFX 사건(예: summon, laser_fire)은 이름 그대로 적으면 그 사건에만
 * </pre>
 * sound = 마인크래프트 사운드 이름 (minecraft: 생략 가능), volume 0.05~3 (1 = 16블록 거리까지), pitch 0.5~2, delay = 틱(0~60).
 * 같은 사람·마법·사건은 2틱 안에 다시 울리지 않는다 (여러 대상을 한 번에 맞히는 마법이 소리를 겹쳐 키우지 않게).
 */
final class SpellSounds {
    record Layer(String sound, float volume, float pitch, int delay) {}

    static final Set<String> HIT = Set.of("hit", "contact", "impact", "ground_hit", "ground_final", "arrive", "absorb", "relay", "laser_final", "strike", "explode", "burst");
    static final Set<String> END = Set.of("end", "dismiss", "cancel", "fizzle", "miss", "block_contact", "laser_end", "expire", "finish");
    static final int MAX_LAYERS = 8;

    private final MagicCodexBridge plugin;
    private volatile Map<String, Map<String, List<Layer>>> cues = Map.of();
    private final Map<String, Long> last = new HashMap<>();

    SpellSounds(MagicCodexBridge plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "spell-sounds.yml").exists()) plugin.saveResource("spell-sounds.yml", false);
        try { reload(); } catch (Exception e) { plugin.getLogger().warning("spell-sounds.yml 읽기 실패 — 마법 효과음 꺼짐: " + e.getMessage()); }
    }

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(new File(plugin.getDataFolder(), "spell-sounds.yml"));
        cues = parse(y);
        plugin.getLogger().info("마법 효과음 " + cues.size() + "개 마법 준비");
    }

    static Map<String, Map<String, List<Layer>>> parse(YamlConfiguration y) {
        var out = new HashMap<String, Map<String, List<Layer>>>();
        var root = y.getConfigurationSection("spells");
        if (root == null) return Map.of();
        for (String spell : root.getKeys(false)) {
            var s = root.getConfigurationSection(spell);
            if (s == null) continue;
            var events = new HashMap<String, List<Layer>>();
            for (String event : s.getKeys(false)) {
                var layers = new ArrayList<Layer>();
                for (var raw : s.getMapList(event)) {
                    if (layers.size() >= MAX_LAYERS) break;
                    Object name = raw.get("sound");
                    if (!(name instanceof String n) || n.isBlank()) continue;
                    String key = n.strip().toLowerCase(Locale.ROOT);
                    if (key.startsWith("minecraft:")) key = key.substring(10);
                    if (!key.matches("[a-z0-9_.\\-/]{1,96}")) throw new IllegalArgumentException(spell + "." + event + ": 사운드 이름 확인 (" + n + ")");
                    layers.add(new Layer(key, (float) Math.clamp(num(raw.get("volume"), 1), 0.05, 3), (float) Math.clamp(num(raw.get("pitch"), 1), 0.5, 2),
                        (int) Math.clamp(Math.round(num(raw.get("delay"), 0)), 0, 60)));
                }
                if (!layers.isEmpty()) events.put(event.toLowerCase(Locale.ROOT), List.copyOf(layers));
            }
            if (!events.isEmpty()) out.put(spell, Map.copyOf(events));
        }
        return Map.copyOf(out);
    }

    private static double num(Object o, double fallback) { return o instanceof Number n ? n.doubleValue() : fallback; }

    /** VFX 사건 이름(external:hit 등) → 이 마법에 적힌 키. 정확한 이름이 우선, 없으면 hit/end 묶음. 없으면 null. */
    static String key(Map<String, List<Layer>> events, String event) {
        if (events == null || event == null) return null;
        String e = event.toLowerCase(Locale.ROOT);
        int colon = e.indexOf(':');
        if (colon >= 0) e = e.substring(colon + 1);
        if (events.containsKey(e)) return e;
        if (HIT.contains(e) && events.containsKey("hit")) return "hit";
        if (END.contains(e) && events.containsKey("end")) return "end";
        return null;
    }

    /** 서버 스레드. caster 는 null 이어도 된다 (그때는 쿨 없이). */
    void play(Player caster, String spell, String event, Location at) {
        if (spell == null || at == null || at.getWorld() == null) return;
        var events = cues.get(spell);
        String k = key(events, event);
        if (k == null) return;
        long now = Bukkit.getCurrentTick();
        if (caster != null) {
            String id = caster.getUniqueId() + "|" + spell + "|" + k;
            Long before = last.get(id);
            if (before != null && now - before < 2) return;
            last.put(id, now);
            if (last.size() > 4096) last.clear();
        }
        Location where = at.clone();
        for (var layer : events.get(k)) {
            Runnable r = () -> { if (where.getWorld() != null) where.getWorld().playSound(where, layer.sound(), SoundCategory.PLAYERS, layer.volume(), layer.pitch()); };
            if (layer.delay() <= 0) r.run(); else Bukkit.getScheduler().runTaskLater(plugin, r, layer.delay());
        }
    }

    Set<String> spells() { return new TreeSet<>(cues.keySet()); }
    Set<String> events(String spell) { var e = cues.get(spell); return e == null ? Set.of() : new TreeSet<>(e.keySet()); }
}
