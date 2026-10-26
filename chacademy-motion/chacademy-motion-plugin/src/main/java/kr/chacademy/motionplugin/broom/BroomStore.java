package kr.chacademy.motionplugin.broom;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어별 빗자루 기록: 고른(가진) 빗자루, 누적 비행 시간(초).
 * 파일: plugins/ChacademyMotion/broom-players.yml. 메인 스레드에서만 쓴다.
 */
final class BroomStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private boolean dirty;

    static final class Entry {
        String broom = "";
        long seconds;
    }

    BroomStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "broom-players.yml");
        load();
    }

    private void load() {
        entries.clear();
        if (!file.exists()) return;
        var yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                var e = new Entry();
                e.broom = yml.getString(key + ".broom", "");
                e.seconds = Math.max(0, yml.getLong(key + ".seconds", 0));
                entries.put(UUID.fromString(key), e);
            } catch (IllegalArgumentException ignored) {
                // UUID 가 아닌 키는 건너뜀
            }
        }
    }

    Entry get(UUID id) {
        return entries.computeIfAbsent(id, k -> new Entry());
    }

    /** 고른 빗자루 ID. 없으면 빈 문자열. */
    String broom(UUID id) {
        var e = entries.get(id);
        return e == null ? "" : e.broom;
    }

    void setBroom(UUID id, String broom) {
        get(id).broom = broom == null ? "" : broom;
        dirty = true;
    }

    long seconds(UUID id) {
        var e = entries.get(id);
        return e == null ? 0 : e.seconds;
    }

    void addSecond(UUID id) {
        get(id).seconds++;
        dirty = true;
    }

    void setSeconds(UUID id, long seconds) {
        get(id).seconds = Math.max(0, seconds);
        dirty = true;
    }

    void saveIfDirty() {
        if (!dirty) return;
        var yml = new YamlConfiguration();
        for (var e : entries.entrySet()) {
            if (e.getValue().broom.isEmpty() && e.getValue().seconds == 0) continue;
            yml.set(e.getKey() + ".broom", e.getValue().broom);
            yml.set(e.getKey() + ".seconds", e.getValue().seconds);
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("broom-players.yml 저장 실패: " + ex.getMessage());
        }
    }
}
