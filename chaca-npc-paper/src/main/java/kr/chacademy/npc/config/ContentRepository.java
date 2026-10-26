package kr.chacademy.npc.config;

import kr.chacademy.npc.core.Defs.HintDef;
import kr.chacademy.npc.core.Defs.QuestDef;
import kr.chacademy.npc.core.TextFilter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * quests.yml, hints.yml, filter.yml, prompts/*.txt 를 읽는다.
 */
public final class ContentRepository {

    private final File dataFolder;
    private final Logger log;

    private volatile Map<String, QuestDef> quests = Map.of();
    private volatile Map<String, HintDef> hints = Map.of();
    private volatile TextFilter filter;
    private volatile String commonPrompt = "";
    private volatile String ambientPrompt = "";
    private volatile String vibePrompt = "";

    public ContentRepository(File dataFolder, Logger log) {
        this.dataFolder = dataFolder;
        this.log = log;
    }

    public void load() {
        Map<String, QuestDef> q = new LinkedHashMap<>();
        YamlConfiguration qy = YamlConfiguration.loadConfiguration(new File(dataFolder, "quests.yml"));
        ConfigurationSection qs = qy.getConfigurationSection("quests");
        if (qs != null) {
            for (String id : qs.getKeys(false)) {
                ConfigurationSection s = qs.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                q.put(id, new QuestDef(id, s.getString("title", id), s.getString("summary", ""),
                        s.getInt("min-score", 0), s.getStringList("accept-commands")));
            }
        }
        quests = q;

        Map<String, HintDef> h = new LinkedHashMap<>();
        YamlConfiguration hy = YamlConfiguration.loadConfiguration(new File(dataFolder, "hints.yml"));
        ConfigurationSection hs = hy.getConfigurationSection("hints");
        if (hs != null) {
            for (String id : hs.getKeys(false)) {
                ConfigurationSection s = hs.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                h.put(id, new HintDef(id, s.getString("spell", id), s.getString("topic", ""),
                        s.getString("materials", ""), Math.max(1, Math.min(3, s.getInt("difficulty", 2))),
                        s.getString("vague", "")));
            }
        }
        hints = h;

        YamlConfiguration fy = YamlConfiguration.loadConfiguration(new File(dataFolder, "filter.yml"));
        filter = new TextFilter(fy.getStringList("banned-words"), fy.getStringList("jailbreak-phrases"),
                fy.getStringList("jailbreak-regex"), fy.getStringList("meta-words"),
                fy.getStringList("romance-banned"),
                // 예전 filter.yml 에는 이 목록이 없다 → null 이면 코드의 기본 목록을 쓴다
                fy.contains("chatter-banned") ? fy.getStringList("chatter-banned") : null);

        commonPrompt = readText("prompts/common.txt");
        ambientPrompt = readText("prompts/ambient.txt");
        vibePrompt = readText("prompts/vibe.txt");
        log.info("[ChacaNPC] 퀘스트 " + q.size() + "개, 힌트 " + h.size() + "개 불러옴");
    }

    private String readText(String path) {
        File f = new File(dataFolder, path);
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warning("[ChacaNPC] " + path + " 읽기 실패: " + ex.getMessage());
            return "";
        }
    }

    public QuestDef quest(String id) {
        return quests.get(id);
    }

    public Collection<HintDef> hints() {
        return hints.values();
    }

    public TextFilter filter() {
        return filter;
    }

    public String commonPrompt() {
        return commonPrompt;
    }

    public String ambientPrompt() {
        return ambientPrompt;
    }

    public String vibePrompt() {
        return vibePrompt;
    }
}
