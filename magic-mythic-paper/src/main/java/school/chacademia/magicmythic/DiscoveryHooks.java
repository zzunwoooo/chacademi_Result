package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Acquisition (획득 조건) counters only this plugin can see, sent to MagicDiscovery's DiscoveryService.signal.
 * Keys must also be defined in magic-discovery-paper discoveries.yml (unknown keys are ignored there).
 *   kill.spell.<id>     - a kill whose last hit (within 3 s) was this spell   (128 산맥의 등: pebble_toss x30)
 *   kill.light_undead   - undead killed by light-element magic                 (147 태양의 심판 x300)
 *   cleanse.poison|weakness|slowness - removed by purifying_wave               (141 여명의 의식)
 *   heal.magic          - health restored by spell healing (anyone)             (164 성좌의 대리인 x600)
 *   heal.magic_other    - health restored on other players                      (157 마력 전선 x100)
 *   mana.exactly_zero   - current mana hit exactly 0                            (161 경험의 환전)
 *   cast.six_elements   - spells of all six elements within 30 s               (163 별빛 재현)
 *   kill.mob.<family>   - a player killed one of our wild mobs (CA_Wild_*, any colour / shiny, any weapon)
 *                         (300~323 몹 기술 습득 마법: x1 + chance 0.05 = 5% per kill; families = tools/mob_spells.py FAMILIES)
 * 2026-10-26 발견 조건 v2 (docs/plans/discovery-conditions-v2.md):
 *   hit.spell.<id> · hit.element.<원소>   - 마법 한 번이 적에게 실제로 들어감 (몬스터·플레이어)
 *   hit.far · hit.far.<원소>             - 그 적이 시전자에게서 20블록 넘게 떨어져 있었음
 *   kill.element.<원소>                  - 마지막 타격(3초 안)이 그 원소 마법인 몬스터 처치
 *   kill.one_hit.<원소>                  - 체력이 가득 찬 몬스터를 그 마법 한 번으로 처치 (크로스 애로우)
 *   kill.fire_low_health                 - 시전자 체력 하트 1칸(2) 이하에서 화염 마법으로 몬스터 처치 (홍련의 심장)
 *   kill.crop_mob                        - 살아난 작물(CA_Crop_*) 처치 (작물 붙잡기·수확제의 종)
 *   처치 귀속: 이제 피해를 넣기 직전에 기록하므로 한 방에 죽인 몬스터도 그 마법 처치로 센다 (예전엔 두 번째 타격부터).
 */
final class DiscoveryHooks implements Listener {
    record Last(UUID caster, String spell, String element, long at, boolean full) {}
    private static final Map<UUID, Last> LAST = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> ELEMENTS = new HashMap<>();
    static final Set<String> SIX = Set.of("wind", "fire", "water", "earth", "light", "dark");
    private final MagicMythicPlugin plugin;

    DiscoveryHooks(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 4, 4);
    }

    // ------------------------------------------------------------------ inputs from mechanics
    /** 마법 피해를 넣기 직전 (MagicProtection.damage): 이 타격으로 죽어도 그 마법 처치로 세도록 먼저 적어 둔다. 돌려준 값은 실패 시 되돌릴 이전 기록. */
    static Last pre(Player caster, LivingEntity target, SpellTrace.Now now, boolean full) {
        if (now == null || now.spell().isBlank()) return null;
        Last before = LAST.put(target.getUniqueId(), new Last(caster.getUniqueId(), now.spell(), now.element(), System.currentTimeMillis(), full));
        if (LAST.size() > 4096) { long cut = System.currentTimeMillis() - 3000; LAST.values().removeIf(l -> l.at() < cut); }
        return before;
    }

    /** 피해를 넣은 뒤: 들어갔으면 적중을 세고, 막혔으면 기록을 되돌린다. */
    static void post(Player caster, LivingEntity target, SpellTrace.Now now, boolean ok, Last before) {
        if (now == null || now.spell().isBlank()) return;
        if (!ok) {
            Last mine = LAST.get(target.getUniqueId());
            if (mine != null && mine.caster().equals(caster.getUniqueId()) && mine.spell().equals(now.spell())) {
                if (before == null) LAST.remove(target.getUniqueId()); else LAST.put(target.getUniqueId(), before);
            }
            return;
        }
        if (target.equals(caster) || !(target instanceof org.bukkit.entity.Enemy || target instanceof Player)) return;
        signal(caster, "hit.spell." + now.spell(), 1);
        if (SIX.contains(now.element())) signal(caster, "hit.element." + now.element(), 1);
        if (caster.getWorld() == target.getWorld() && caster.getLocation().distanceSquared(target.getLocation()) > 400) {
            signal(caster, "hit.far", 1);
            if (SIX.contains(now.element())) signal(caster, "hit.far." + now.element(), 1);
        }
    }

    static void cast(Player caster, SpellTrace.Now now) {
        if (now == null || !SIX.contains(now.element())) return;
        long t = System.currentTimeMillis();
        var mine = ELEMENTS.computeIfAbsent(caster.getUniqueId(), k -> new HashMap<>());
        mine.put(now.element(), t);
        if (sixWithin(mine, t, 30_000)) { mine.clear(); signal(caster, "cast.six_elements", 1); }
    }
    static boolean sixWithin(Map<String, Long> times, long now, long window) {
        int n = 0;
        for (String e : SIX) { Long at = times.get(e); if (at != null && now - at <= window) n++; }
        return n == SIX.size();
    }

    static void healed(LivingEntity caster, LivingEntity target, double amount) {
        if (amount <= 0 || !(caster instanceof Player pl)) return;
        signal(pl, "heal.magic", amount);
        if (!target.equals(caster) && target instanceof Player) signal(pl, "heal.magic_other", amount);
    }

    static void cleansed(LivingEntity caster, String spell, String effectKey) {
        if (caster instanceof Player pl && "purifying_wave".equals(spell)) signal(pl, "cleanse." + effectKey, 1);
    }

    // ------------------------------------------------------------------ kills
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        mobKill(e);
        Last last = LAST.remove(e.getEntity().getUniqueId());
        if (last == null || System.currentTimeMillis() - last.at() > 3000) return;
        Player caster = Bukkit.getPlayer(last.caster());
        if (caster == null) return;
        signal(caster, "kill.spell." + last.spell(), 1);
        if ("light".equals(last.element()) && Targets.undead(e.getEntity())) signal(caster, "kill.light_undead", 1);
        if (!(e.getEntity() instanceof org.bukkit.entity.Enemy) || !SIX.contains(last.element())) return;
        signal(caster, "kill.element." + last.element(), 1);
        if (last.full()) signal(caster, "kill.one_hit." + last.element(), 1);
        if ("fire".equals(last.element()) && caster.getHealth() <= 2.0) signal(caster, "kill.fire_low_health", 1);
    }

    /** CA_Wild_ name prefix (after "CA_Wild_") -> family key. Longest prefixes first is not needed: none overlap. */
    static final Map<String, String> MOB_FAMILIES = Map.ofEntries(
        Map.entry("kitsune_", "kitsune"), Map.entry("phoenix_", "phoenix"), Map.entry("glacialcore", "glacialcore"),
        Map.entry("basilisk_", "basilisk"), Map.entry("lostasset_mob3_herbthief", "herbthief"), Map.entry("stag_", "stag"),
        Map.entry("mf_warden_mage", "warden_mage"), Map.entry("mf_warden_knight", "warden_knight"), Map.entry("mf_warden_archer", "warden_archer"),
        Map.entry("phantom_", "phantom"), Map.entry("ncr_mage_skeleton", "skeleton_mage"), Map.entry("sky_serpent_", "sky_serpent"),
        Map.entry("lostasset_mob2_", "fairy"), Map.entry("horse_pegasus", "pegasus"), Map.entry("hippogryph_", "hippogryph"),
        Map.entry("sabertooth_", "sabertooth"), Map.entry("manticore_", "manticore"), Map.entry("golem_", "golem"), Map.entry("wyvern_", "wyvern"));
    static String mobFamily(String mythicType) {
        if (mythicType == null || !mythicType.startsWith("CA_Wild_")) return null;
        String rest = mythicType.substring("CA_Wild_".length());
        for (var f : MOB_FAMILIES.entrySet()) if (rest.startsWith(f.getKey())) return f.getValue();
        return null;
    }
    private static void mobKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        String type = Targets.mythicType(e.getEntity());
        String family = mobFamily(type);
        if (family != null) signal(killer, "kill.mob." + family, 1);
        if (type != null && type.startsWith("CA_Crop_")) signal(killer, "kill.crop_mob", 1);
    }

    // ------------------------------------------------------------------ mana 0 (polled; the Bridge has no mana event)
    private final Set<UUID> empty = new HashSet<>();
    private void poll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Double mana = Links.mana(p);
            if (mana == null) continue;
            if (mana > 1e-9) { empty.remove(p.getUniqueId()); continue; }
            if (empty.add(p.getUniqueId())) signal(p, "mana.exactly_zero", 1); // once per time it reaches 0
        }
    }

    @EventHandler public void quit(PlayerQuitEvent e) { ELEMENTS.remove(e.getPlayer().getUniqueId()); empty.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ DiscoveryService
    private static Object service; private static Method signal;
    static boolean signal(Player p, String key, double amount) {
        var plugin = Bukkit.getPluginManager().getPlugin("MagicDiscovery");
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            if (service == null || signal == null) {
                Class<?> type = Class.forName("school.magiccodex.discovery.DiscoveryService", false, plugin.getClass().getClassLoader());
                service = Bukkit.getServicesManager().load(type);
                signal = type.getMethod("signal", UUID.class, String.class, double.class);
            }
            return service != null && Boolean.TRUE.equals(signal.invoke(service, p.getUniqueId(), key, amount));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { service = null; signal = null; return false; }
    }
    static void reset() { service = null; signal = null; LAST.clear(); ELEMENTS.clear(); }
}
