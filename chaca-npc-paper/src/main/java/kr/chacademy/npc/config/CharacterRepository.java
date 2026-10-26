package kr.chacademy.npc.config;

import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.ScheduleEntry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * plugins/ChacaNPC/npcs/*.yml 캐릭터 파일을 읽는다.
 */
public final class CharacterRepository {

    private final File folder;
    private final Logger log;
    private volatile Map<String, CharacterSheet> byId = Map.of();

    public CharacterRepository(File folder, Logger log) {
        this.folder = folder;
        this.log = log;
    }

    public void load() {
        Map<String, CharacterSheet> map = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files != null) {
            for (File f : files) {
                try {
                    CharacterSheet c = read(YamlConfiguration.loadConfiguration(f), f.getName());
                    if (map.containsKey(c.id())) {
                        log.warning("[ChacaNPC] 캐릭터 id 중복: " + c.id() + " (" + f.getName() + ") — 건너뜀");
                        continue;
                    }
                    map.put(c.id(), c);
                } catch (Exception ex) {
                    log.warning("[ChacaNPC] 캐릭터 파일 읽기 실패: " + f.getName() + " — " + ex.getMessage());
                }
            }
        }
        int gifted = applyGiftFile(map, new File(folder.getParentFile(), "gift-likes.yml"), log);
        byId = map;
        log.info("[ChacaNPC] 캐릭터 " + map.size() + "명 불러옴" + (gifted > 0 ? " (선물 취향 gift-likes.yml " + gifted + "명)" : ""));
    }

    public CharacterSheet get(String id) {
        return id == null ? null : byId.get(id);
    }

    public Collection<CharacterSheet> all() {
        return byId.values();
    }

    /**
     * 호감도 선물 공통 파일 (#58, 오너 기획 2026-10-25 "호감도 선물 100"): plugins/ChacaNPC/gift-likes.yml
     * <pre>
     * npcs:
     *   ella: [item:gift_archmage_statue, item:gift_moonwell_water, ...]
     * </pre>
     * 여기 적힌 NPC 는 캐릭터 파일의 gift_likes 대신 이 목록을 좋아하는 선물(liked)로 쓴다 (loved 는 비움, disliked 는 캐릭터 파일 것 유지).
     * 적히지 않은 NPC 는 캐릭터 파일 gift_likes 그대로. 없는 NPC id 는 경고만.
     */
    static int applyGiftFile(Map<String, CharacterSheet> map, File file, Logger log) {
        if (!file.isFile()) return 0;
        ConfigurationSection npcs = YamlConfiguration.loadConfiguration(file).getConfigurationSection("npcs");
        if (npcs == null) return 0;
        int n = 0;
        for (String id : npcs.getKeys(false)) {
            CharacterSheet c = map.get(id);
            if (c == null) { log.warning("[ChacaNPC] gift-likes.yml: 없는 NPC id " + id + " — 건너뜀"); continue; }
            List<String> liked = new ArrayList<>();
            for (String it : npcs.getStringList(id)) liked.add(it.strip().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", ""));
            Map<String, List<String>> likes = new HashMap<>();
            likes.put("liked", List.copyOf(liked));
            likes.put("loved", List.of());
            likes.put("disliked", c.giftLikes() == null ? List.of() : c.giftLikes().getOrDefault("disliked", List.of()));
            map.put(id, c.withGiftLikes(likes));
            n++;
        }
        return n;
    }

    private static Map<String, List<String>> giftLikes(YamlConfiguration y) {
        Map<String, List<String>> out = new HashMap<>();
        ConfigurationSection g = y.getConfigurationSection("gift_likes");
        if (g != null) {
            for (String k : List.of("loved", "liked", "disliked")) {
                List<String> items = new ArrayList<>();
                for (String it : g.getStringList(k)) {
                    items.add(it.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", ""));
                }
                out.put(k, items);
            }
        }
        return out;
    }

    static CharacterSheet read(YamlConfiguration y, String fileName) {
        String id = y.getString("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id가 없습니다");
        }
        String name = y.getString("name", id);
        ConfigurationSection p = y.getConfigurationSection("persona");
        CharacterSheet.Persona persona = new CharacterSheet.Persona(
                p == null ? null : p.getString("role"),
                p == null ? null : p.getString("personality"),
                p == null ? null : p.getString("speech"),
                p == null ? List.of() : p.getStringList("likes"),
                p == null ? List.of() : p.getStringList("dislikes"),
                p == null ? List.of() : p.getStringList("knows"),
                p == null ? List.of() : p.getStringList("never_knows"),
                p == null ? List.of() : p.getStringList("sample_lines"));

        List<ScheduleEntry> schedule = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("schedule")) {
            Object place = m.get("place");
            if (place == null) {
                continue;
            }
            Object activity = m.get("activity");
            schedule.add(new ScheduleEntry(ScheduleEntry.parseTime(m.get("time")), place.toString(),
                    activity == null ? null : activity.toString()));
        }

        Map<String, CharacterSheet.Relation> relations = new LinkedHashMap<>();
        ConfigurationSection rel = y.getConfigurationSection("relations");
        if (rel != null) {
            for (String other : rel.getKeys(false)) {
                ConfigurationSection r = rel.getConfigurationSection(other);
                if (r != null) {
                    relations.put(other, new CharacterSheet.Relation(r.getString("type", "friend"),
                            Math.max(0, Math.min(1, r.getDouble("gossip", 0.5)))));
                } else {
                    relations.put(other, new CharacterSheet.Relation(rel.getString(other, "friend"), 0.5));
                }
            }
        }

        Map<String, List<String>> fallback = new HashMap<>();
        ConfigurationSection fb = y.getConfigurationSection("fallback_lines");
        if (fb != null) {
            for (String k : fb.getKeys(false)) {
                fallback.put(k, fb.getStringList(k));
            }
        }

        Map<Integer, List<String>> byStage = new HashMap<>();
        ConfigurationSection st = y.getConfigurationSection("sample_lines_by_stage");
        if (st != null) {
            for (String k : st.getKeys(false)) {
                try {
                    byStage.put(Integer.parseInt(k), st.getStringList(k));
                } catch (NumberFormatException ignored) {
                    // 숫자가 아닌 키는 무시
                }
            }
        }

        return new CharacterSheet(
                id,
                name,
                y.getString("type", "sub"),
                y.getString("gender", ""),
                y.getString("skin", ""),
                y.getString("color", null),
                y.getString("portrait", ""),
                y.getBoolean("romanceable", false),
                y.getBoolean("open-on-right-click", true),
                persona,
                schedule,
                y.getStringList("quests"),
                y.getStringList("hint_topics"),
                relations,
                fallback,
                byStage,
                giftLikes(y),
                buttons(id, y));
    }

    /**
     * buttons: 이 NPC 전용 추천 버튼 (최대 3개). 없으면 config.yml dialogue.buttons 공통 버튼.
     * <pre>
     * buttons:
     *   - { id: daily, label: "교수님, 요즘 어떠세요?" }
     *   - { id: help,  label: "제가 도울 일이 있을까요?" }
     *   - { id: magic, label: "마법 이야기 들려주세요" }
     *   - { id: shop,  label: "물건 좀 볼 수 있어요?", action: "shop:pipi" }   ← 대사 대신 상점(MagicCodexBridge)을 연다
     * </pre>
     * id 는 소문자·숫자·_·- 1~32자 (같은 NPC 안에서 겹치지 않게). 미리 만드는 대답(캐시)은 NPC·버튼 id 별로 따로 쌓인다.
     */
    private static List<kr.chacademy.npc.core.Defs.Button> buttons(String id, YamlConfiguration y) {
        Logger log = Logger.getLogger("ChacaNPC");
        List<kr.chacademy.npc.core.Defs.Button> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<?, ?> m : y.getMapList("buttons")) {
            Object bid = m.get("id"), label = m.get("label"), action = m.get("action");
            if (bid == null || label == null) continue;
            String b = bid.toString().strip(), l = label.toString().strip();
            if (!b.matches("[a-z0-9_-]{1,32}") || l.isEmpty() || !seen.add(b)) {
                log.warning("[ChacaNPC] " + id + " 의 버튼 " + b + " 을(를) 건너뜀 (id 형식·빈 글·중복)");
                continue;
            }
            if (out.size() >= 3) {
                log.warning("[ChacaNPC] " + id + " 의 버튼은 3개까지만 씁니다 (나머지 무시)");
                break;
            }
            var button = new kr.chacademy.npc.core.Defs.Button(b, l, action == null ? "" : action.toString());
            if (!button.aiReply() && button.shop() == null && button.lesson() == null) {
                log.warning("[ChacaNPC] " + id + " 의 버튼 " + b + " 의 action 을 알 수 없음 (" + button.action() + ") — 일반 대사 버튼으로 씁니다");
                button = new kr.chacademy.npc.core.Defs.Button(b, l);
            }
            out.add(button);
        }
        return List.copyOf(out);
    }
}
