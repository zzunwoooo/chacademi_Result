package school.magiccodex.paper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;

// [기능:alchemy-overhaul 시작] 바닐라 아이템 한국어 이름 (2026-10-31, 새 파일)
/**
 * 서버에는 한국어 번역이 없어서 이름 없는 바닐라 아이템이 "golden carrot" 처럼 영어로 나왔다 (연금술 부족 재료 문구,
 * 편집기가 이름을 비운 레시피에 채운 이름, 연금 수첩). jar 안 vanilla-names-ko.txt (Minecraft 1.21.4 ko_kr.json 의
 * item./block.minecraft.&lt;id&gt;) 로 바꾼다. 표에 없으면 예전처럼 영어.
 */
final class KoNames {
    private KoNames() {}
    private static volatile Map<String, String> names;

    static String of(Material material) {
        String id = material.getKey().getKey();
        String ko = table().get(id);
        return ko != null ? ko : english(id);
    }

    /** 예전 서버가 쓰던 영어 이름 모양 ("golden_carrot" → "golden carrot"). */
    static String english(String id) { return id.replace('_', ' '); }

    /** "minecraft:blue_ice" 결과 레시피 이름이 예전 영어 모양 그대로면 한국어로, 아니면 그대로. */
    static String recipeName(String name, String resultKey) {
        if (resultKey == null || !resultKey.startsWith("minecraft:")) return name;
        String id = resultKey.substring("minecraft:".length());
        if (!name.equalsIgnoreCase(english(id))) return name;
        String ko = table().get(id);
        return ko != null ? ko : name;
    }

    private static Map<String, String> table() {
        var t = names;
        if (t != null) return t;
        var map = new HashMap<String, String>();
        try (var in = KoNames.class.getClassLoader().getResourceAsStream("vanilla-names-ko.txt")) {
            if (in != null) {
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) map.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (IOException ignored) { }
        names = t = Map.copyOf(map);
        return t;
    }
}
// [기능:alchemy-overhaul 끝]
