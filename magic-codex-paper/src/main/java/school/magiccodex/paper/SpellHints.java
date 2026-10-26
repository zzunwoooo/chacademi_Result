package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 마법별 힌트 파일 spell-hints.yml (오너 결정 2026-10-26: "마법별로 다 만들어 두고 파일 하나 만들어 놓고 불러오자").
 * 마법 힌트권(1·2·3성), 깨달음의 빛(221, 2성 문장), ChacaNPC NPC 힌트(hints.yml 에 목격담이 없을 때 2성 문장)가 모두 여기서 읽는다.
 * <pre>
 * spells:
 *   light_leap:
 *     1: "그 바람은 무척 빠르고 통통 튀어요…"            1성 (막연)
 *     2: "통통 튀는 바람 같은 속도로 달려 보면 …"         2성
 * </pre>
 * 3성은 파일이 아니라 발견 조건 문구 + 지금 진행도 ({@link #third}). 파일·마법이 없으면 예전 자동 문장(LifeSpells)으로.
 * 파일은 처음 켤 때 데이터 폴더에 복사(없을 때만), /서버관리 새로고침 힌트 로 다시 읽는다.
 *
 * <p>힌트 후보 조건표 = MagicDiscovery 데이터 폴더의 discoveries.yml + manual-conditions.yml (수동 조건이 같은 마법을 덮어씀,
 * MagicDiscovery Definitions.manual 과 같은 규칙). 파일이 바뀌면 다시 읽는다.
 */
final class SpellHints {
    static final String FILE = "spell-hints.yml";

    /** 발견 조건 한 개. requirements = 파일 순서 그대로 (키 → 목표값). manual = 책·수업·스토리로만 얻는 조건 (진행 카운터 없음). */
    record Condition(String spell, String permission, String text, Map<String, Double> requirements, boolean manual) {}

    private final MagicCodexBridge plugin;
    private volatile Map<String, Map<Integer, String>> lines = Map.of();
    private List<Condition> conditions = List.of();
    private String conditionStamp = "";

    SpellHints(MagicCodexBridge plugin) {
        this.plugin = plugin;
        try {
            if (!new File(plugin.getDataFolder(), FILE).exists()) {
                boolean bundled;
                try (var in = plugin.getResource(FILE)) { bundled = in != null; }
                if (bundled) plugin.saveResource(FILE, false);   // jar 에 내용 파일이 있을 때만 (없으면 자동 문장)
            }
            reload();
        } catch (Exception e) { plugin.getLogger().warning(FILE + " 읽기 실패 — 마법 힌트는 자동 문장으로: " + e.getMessage()); }
    }

    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) { lines = Map.of(); plugin.getLogger().info(FILE + " 없음 — 마법 힌트는 자동 문장으로"); return; }
        var y = new YamlConfiguration();
        y.load(file);
        lines = parse(y);
        plugin.getLogger().info("마법별 힌트 " + lines.size() + "개 마법 준비");
    }

    static Map<String, Map<Integer, String>> parse(ConfigurationSection y) {
        var root = y == null ? null : y.getConfigurationSection("spells");
        if (root == null) return Map.of();
        var out = new HashMap<String, Map<Integer, String>>();
        for (String id : root.getKeys(false)) {
            var sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            var stars = new HashMap<Integer, String>();
            for (int star = 1; star <= 2; star++) {
                Object raw = sec.get(String.valueOf(star));
                String s = raw instanceof String str ? str.strip() : null;
                if (s != null && !s.isEmpty()) stars.put(star, s);
            }
            if (!stars.isEmpty()) out.put(id, Map.copyOf(stars));
        }
        return Map.copyOf(out);
    }

    /** 파일의 1성·2성 문장, 없으면 null. */
    String line(String spell, int star) {
        var m = lines.get(spell);
        return m == null ? null : m.get(star);
    }

    // ------------------------------------------------------------------ 발견 조건표 (discoveries.yml + manual-conditions.yml)

    /** 지금 조건표 (MagicDiscovery 가 없으면 빈 목록). 두 파일의 수정 시각·크기가 바뀌면 다시 읽는다. */
    synchronized List<Condition> conditions() {
        var md = Bukkit.getPluginManager().getPlugin("MagicDiscovery");
        if (md == null) return List.of();
        var main = new File(md.getDataFolder(), "discoveries.yml");
        var manual = new File(md.getDataFolder(), "manual-conditions.yml");
        String stamp = main.lastModified() + ":" + main.length() + "/" + manual.lastModified() + ":" + manual.length();
        if (stamp.equals(conditionStamp)) return conditions;
        conditionStamp = stamp;
        try {
            conditions = parseConditions(main.isFile() ? YamlConfiguration.loadConfiguration(main) : null,
                    manual.isFile() ? YamlConfiguration.loadConfiguration(manual) : null);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("마법 힌트: discoveries.yml / manual-conditions.yml 을 읽지 못했어요 (" + e.getMessage() + ")");
            conditions = List.of();
        }
        return conditions;
    }

    /** 한 마법의 조건 (없으면 null). */
    Condition condition(String spell) {
        for (var c : conditions()) if (c.spell().equals(spell)) return c;
        return null;
    }

    static List<Condition> parseConditions(ConfigurationSection discoveries, ConfigurationSection manual) {
        var out = new LinkedHashMap<String, Condition>();
        read(discoveries, false, out);
        read(manual, true, out);
        return List.copyOf(out.values());
    }

    private static void read(ConfigurationSection y, boolean manual, Map<String, Condition> out) {
        var root = y == null ? null : y.getConfigurationSection("spells");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            String text = s.getString("condition-text", "");
            if (text == null || text.isBlank()) continue;
            var req = new LinkedHashMap<String, Double>();
            var rs = s.getConfigurationSection("requirements");
            if (rs != null) for (String key : rs.getKeys(true)) {
                if (rs.isConfigurationSection(key)) continue;
                double v = rs.getDouble(key, 0);
                if (v > 0) req.put(key.equals("friend.made") ? "state.friends" : key, v);   // MagicDiscovery Definitions 와 같은 이름 바꾸기
            }
            boolean isManual = manual || !req.isEmpty() && req.keySet().stream().allMatch(k -> k.startsWith("trace.condition."));
            out.put(id, new Condition(id, s.getString("permission", "magic.learned." + id), text.strip(), Collections.unmodifiableMap(req), isManual));
        }
    }

    // ------------------------------------------------------------------ 3성 문장 (순수, JUnit)

    /** 조건 문구 속 "숫자 + 단위" (1,000블록 · 30번 · 5마리 …). */
    static final Pattern COUNT = Pattern.compile("(\\d[\\d,]*)\\s*(개|번|회|마리|명|블록|바퀴|종|칸|초|분)");

    static String stripCondition(String condition) { return condition == null ? "" : condition.strip().replaceAll("[.。!]+$", "").strip(); }

    /**
     * 3성: "카드가 또렷하게 속삭여요… 「조건」 현재 {닉네임}님은 N회 달성했어요."
     * current = MagicDiscovery 의 지금 값 (null = 모름 · 수동 조건이면 무시). 진행도를 말할 수 없으면 예전 끝맺음 "그러면 새 마법을 깨칠 수 있을 거예요."
     */
    static String third(Condition c, String nickname, Map<String, Double> current) {
        String head = "카드가 또렷하게 속삭여요… 「" + stripCondition(c.text()) + "」 ";
        String progress = c.manual() ? null : progress(c.text(), nickname, c.requirements(), current);
        return head + (progress != null ? progress : "그러면 새 마법을 깨칠 수 있을 거예요.");
    }

    /**
     * 진행도 한 문장, 말할 수 없으면 null (값 없음·조건 키 일부 모름).
     * 조건 문구의 "숫자+단위" 개수가 조건 키 개수와 같으면 순서대로 짝지어 문구 단위로 환산한다 (1,000블록 = sprint.centimeters 100000 처럼 단위가 달라도).
     * 키 1개: "N회", 여러 개: "N/M회 · N/M마리". 짝이 안 맞으면 원래 값 "N / 목표".
     */
    static String progress(String condition, String nickname, Map<String, Double> targets, Map<String, Double> current) {
        if (current == null || targets == null || targets.isEmpty()) return null;
        for (String key : targets.keySet()) if (!current.containsKey(key)) return null;
        var counts = new ArrayList<long[]>();
        var units = new ArrayList<String>();
        Matcher m = COUNT.matcher(stripCondition(condition));
        while (m.find()) {
            try { counts.add(new long[]{Long.parseLong(m.group(1).replace(",", ""))}); } catch (NumberFormatException e) { return null; }
            units.add(m.group(2).equals("번") ? "회" : m.group(2));
        }
        String who = "현재 " + (nickname == null || nickname.isBlank() ? "" : nickname.strip() + "님은 ");
        var keys = new ArrayList<>(targets.keySet());
        var parts = new ArrayList<String>();
        boolean paired = counts.size() == keys.size();
        for (int i = 0; i < keys.size(); i++) {
            double target = targets.get(keys.get(i)), now = Math.max(0, current.get(keys.get(i)));
            if (paired) {
                long n = counts.get(i)[0];
                long shown = target > 0 ? Math.min(n, (long) Math.floor(now * n / target + 1e-9)) : 0;
                parts.add(keys.size() == 1 ? fmt(shown) + units.get(i) : fmt(shown) + "/" + fmt(n) + units.get(i));
            } else parts.add(keys.size() == 1 ? fmt((long) Math.floor(now)) + " / " + fmt((long) Math.ceil(target)) : fmt((long) Math.floor(now)) + "/" + fmt((long) Math.ceil(target)));
        }
        return who + String.join(" · ", parts) + " 달성했어요.";
    }

    private static String fmt(long n) { return String.format(Locale.ROOT, "%,d", n); }
}
