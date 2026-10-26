package kr.chacademy.npc.appearance;

import kr.chacademi.npcmodel.protocol.NpcModelProtocol;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * plugins/ChacaNPC/npc-models.yml — NPC 외형(3D 모델) 설정. 이 파일만 읽고 쓴다 (DB·진행 데이터는 건드리지 않음).
 *
 * <pre>
 * server-id: school          # Citizens 번호는 서버마다 다르므로 개별 지정은 "서버ID:번호" 로 저장
 * models: [luka, ria]        # 클라이언트 models.json 에 있는 모델 ID (명령 자동완성·오타 검사용)
 * characters:                # 캐릭터 ID 기준 기본값 (NPC를 다시 만들어도 연결된 캐릭터를 따라감)
 *   luka: {model: luka, scale: 1.0}
 * npcs:                      # Citizens NPC 하나만 따로 (기본값보다 우선). model: none = 이 NPC는 원래 외형
 *   "school:12": {model: ria, scale: 1.05}
 * facing:                    # 모델 고정 방향 (마인크래프트 yaw 0~360, 0=남 90=서 180=북 270=동). 없으면 NPC 몸 방향을 따라감
 *   "school:12": 90
 * </pre>
 */
public final class AppearanceConfig {
    public static final String NONE = "none";

    public record Spec(String model, float scale) {
    }

    private final File file;
    private final Logger log;
    private String serverId = "school";
    private final Set<String> models = new LinkedHashSet<>();
    private final Map<String, Spec> characters = new LinkedHashMap<>();
    private final Map<String, Spec> npcs = new LinkedHashMap<>();
    private final Map<String, Float> facing = new LinkedHashMap<>();

    public AppearanceConfig(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public synchronized void load() {
        models.clear();
        characters.clear();
        npcs.clear();
        facing.clear();
        if (!file.exists()) {
            serverId = "school";
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            log.warning("[ChacaNPC] npc-models.yml 읽기 실패 — 3D 외형 없이 시작합니다: " + e.getMessage());
            return;
        }
        serverId = y.getString("server-id", "school").trim();
        for (String m : y.getStringList("models")) {
            if (NpcModelProtocol.MODEL_ID.matcher(m).matches()) {
                models.add(m);
            } else {
                log.warning("[ChacaNPC] npc-models.yml models: 형식이 틀린 ID 무시 '" + m + "'");
            }
        }
        readSection(y.getConfigurationSection("characters"), characters, "characters");
        readSection(y.getConfigurationSection("npcs"), npcs, "npcs");
        ConfigurationSection f = y.getConfigurationSection("facing");
        if (f != null) {
            for (String key : f.getKeys(false)) {
                if (!f.isDouble(key) && !f.isInt(key)) {
                    log.warning("[ChacaNPC] npc-models.yml facing." + key + ": 숫자가 아님 → 무시");
                    continue;
                }
                facing.put(key, normalizeYaw((float) f.getDouble(key)));
            }
        }
    }

    /** 아무 각도 → [0, 360). */
    public static float normalizeYaw(float yaw) {
        if (!Float.isFinite(yaw)) {
            return 0f;
        }
        float r = Math.round(yaw * 10) / 10f % 360f; // 소수 첫째 자리로 반올림한 뒤 감음 (359.97 → 0, 360 이 되지 않게)
        if (r < 0) {
            r += 360f;
        }
        return r >= 360f ? 0f : r;
    }

    private void readSection(ConfigurationSection sec, Map<String, Spec> into, String where) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String model = s.getString("model", "").trim();
            float scale = (float) s.getDouble("scale", 1.0);
            if (!model.equals(NONE) && !NpcModelProtocol.MODEL_ID.matcher(model).matches()) {
                log.warning("[ChacaNPC] npc-models.yml " + where + "." + key + ": 모델 ID 형식 오류 '" + model + "' → 무시");
                continue;
            }
            if (!(scale >= NpcModelProtocol.MIN_SCALE && scale <= NpcModelProtocol.MAX_SCALE)) {
                log.warning("[ChacaNPC] npc-models.yml " + where + "." + key + ": 크기 " + scale + " 범위 밖 → 1.0");
                scale = 1f;
            }
            into.put(key, new Spec(model, scale));
        }
    }

    /** 이 파일만 원자적으로 저장 (임시 파일 → 이동). */
    public synchronized void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("NPC 3D 외형 설정 (ChacaNPC). /NPC관리 외형 … 명령으로 바뀝니다.",
                "server-id: 이 서버 이름 — Citizens 번호는 서버마다 달라서 npcs 칸은 \"서버ID:번호\" 로 저장합니다.",
                "models: 클라이언트 models.json 에 등록된 모델 ID 목록 (자동완성·오타 검사용)"));
        y.set("server-id", serverId);
        y.set("models", new ArrayList<>(models));
        write(y, "characters", characters);
        write(y, "npcs", npcs);
        y.createSection("facing");
        for (Map.Entry<String, Float> e : facing.entrySet()) {
            y.set("facing." + e.getKey(), (double) e.getValue());
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), y.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void write(YamlConfiguration y, String root, Map<String, Spec> m) {
        y.createSection(root);
        for (Map.Entry<String, Spec> e : m.entrySet()) {
            y.set(root + "." + e.getKey() + ".model", e.getValue().model());
            y.set(root + "." + e.getKey() + ".scale", (double) e.getValue().scale());
        }
    }

    public synchronized String serverId() {
        return serverId;
    }

    public synchronized Set<String> models() {
        return Set.copyOf(models);
    }

    public synchronized boolean knowsModel(String id) {
        return models.contains(id);
    }

    public synchronized void addModel(String id) {
        models.add(id);
    }

    public synchronized void removeModel(String id) {
        models.remove(id);
    }

    public synchronized Spec character(String charId) {
        return charId == null ? null : characters.get(charId);
    }

    public synchronized Spec npc(int citizensId) {
        return npcs.get(serverId + ":" + citizensId);
    }

    public synchronized void setCharacter(String charId, Spec s) {
        if (s == null) {
            characters.remove(charId);
        } else {
            characters.put(charId, s);
        }
    }

    public synchronized void setNpc(int citizensId, Spec s) {
        if (s == null) {
            npcs.remove(serverId + ":" + citizensId);
        } else {
            npcs.put(serverId + ":" + citizensId, s);
        }
    }

    /** 이 NPC 모델의 고정 방향, 없으면 null. */
    public synchronized Float facing(int citizensId) {
        return facing.get(serverId + ":" + citizensId);
    }

    public synchronized void setFacing(int citizensId, Float yaw) {
        if (yaw == null) {
            facing.remove(serverId + ":" + citizensId);
        } else {
            facing.put(serverId + ":" + citizensId, normalizeYaw(yaw));
        }
    }

    public synchronized Map<String, Float> facingSpecs() {
        return Map.copyOf(facing);
    }

    public synchronized Map<String, Spec> characterSpecs() {
        return Map.copyOf(characters);
    }

    public synchronized Map<String, Spec> npcSpecs() {
        return Map.copyOf(npcs);
    }

    /** 최종 외형: 개별 NPC 지정 > 캐릭터 기본값. null = 원래 외형. */
    public synchronized Spec resolve(int citizensId, String charId) {
        Spec o = npc(citizensId);
        if (o != null) {
            return NONE.equals(o.model()) ? null : o;
        }
        Spec c = character(charId);
        return c == null || NONE.equals(c.model()) ? null : c;
    }
}
