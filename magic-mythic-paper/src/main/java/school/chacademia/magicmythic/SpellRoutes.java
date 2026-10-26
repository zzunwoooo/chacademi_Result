package school.chacademia.magicmythic;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * plugins/MagicCodexBridge/spell-runtime.yml 의 마법 라우트를 명령 칸에서 쓰기 위한 읽기 전용 캐시.
 * 명령 칸에는 한글 이름(띄어쓰기는 _)을 먼저 보이고, 마법 ID도 값으로 받는다. 파일이 바뀌면 다시 읽는다.
 */
final class SpellRoutes {
    private final File file;
    private long stamp = Long.MIN_VALUE;
    private ConfigurationSection spells;
    private Map<String, String> byName = Map.of();
    private Map<String, String> labels = Map.of();

    SpellRoutes(File file) { this.file = file; }

    File file() { return file; }
    boolean available() { return file.isFile(); }

    private void refresh() {
        long now = file.isFile() ? file.lastModified() : -1;
        if (now == stamp) return;
        stamp = now;
        spells = now < 0 ? null : YamlConfiguration.loadConfiguration(file).getConfigurationSection("spells");
        var names = new LinkedHashMap<String, String>();
        var shown = new LinkedHashMap<String, String>();
        if (spells != null) for (String id : spells.getKeys(false)) {
            String name = spells.getString(id + ".name", id);
            shown.put(id, name);
            String key = name.trim().replace(' ', '_');
            if (!key.isEmpty()) names.putIfAbsent(key, id);
        }
        byName = names;
        labels = shown;
    }

    /** 입력(한글 이름·ID) → 마법 ID. 모르면 입력 그대로. */
    String resolve(String input) {
        refresh();
        if (spells != null && spells.isConfigurationSection(input)) return input;
        String id = byName.get(input.trim());
        return id != null ? id : input.toLowerCase(Locale.ROOT);
    }

    ConfigurationSection route(String input) {
        refresh();
        return spells == null ? null : spells.getConfigurationSection(resolve(input));
    }

    String label(String id) { refresh(); return labels.getOrDefault(id, id); }

    /** 자동완성 후보: 한글 이름 먼저, 그다음 ID. */
    List<String> suggestions() {
        refresh();
        var out = new ArrayList<String>(byName.keySet());
        if (spells != null) out.addAll(spells.getKeys(false));
        return out;
    }
}
