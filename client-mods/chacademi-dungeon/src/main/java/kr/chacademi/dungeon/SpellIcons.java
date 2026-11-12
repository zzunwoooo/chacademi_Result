package kr.chacademi.dungeon;

// [기능:탑아이콘 시작]
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 탑 카드의 마법 아이콘: 마법도감(MagicCodex) 모드에 든 마법 정의(assets/magiccodex/default-spells/*.yml 의 id·icon)를 읽어
 * 그 마법의 진짜 아이콘을 그린다. 원본 그림은 1254px 이라 256px 로 줄여 올린다 (Art.mcSmall).
 * MagicCodex 가 없거나 못 찾으면 false → 카드는 아이템 그림으로.
 */
final class SpellIcons {
    private SpellIcons() {}

    private static Map<String, String> paths;

    static boolean draw(GuiGraphics g, String spell, float x, float y, float size, int tint) {
        String path = path(spell);
        if (path == null) return false;
        var tex = Art.mcSmall(path, 256);
        if (tex.isEmpty()) return false;
        Art.image(g, tex, x, y, size, size, tint);
        return true;
    }

    static void reset() { paths = null; }

    private static String path(String spell) {
        if (paths == null) {
            var map = new HashMap<String, String>();
            try {
                var found = Minecraft.getInstance().getResourceManager().listResources("default-spells", l -> l.getPath().endsWith(".yml"));
                for (var e : found.entrySet()) {
                    if (!e.getKey().getNamespace().equals("magiccodex")) continue;
                    String id = null, icon = null;
                    try (var r = new BufferedReader(new InputStreamReader(e.getValue().open(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null && (id == null || icon == null); ) {
                            if (line.startsWith("id:")) id = clean(line.substring(3));
                            else if (line.startsWith("icon:")) icon = clean(line.substring(5));
                        }
                    } catch (Exception ignored) { }
                    if (id == null || icon == null || !icon.startsWith("magiccodex:")) continue;
                    map.put(id, icon.substring("magiccodex:".length()));
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("마법 아이콘 목록 읽기 실패: {}", e.toString()); }
            paths = map;
        }
        return paths.get(spell);
    }

    private static String clean(String v) { return v.trim().replace("\"", "").replace("'", ""); }
}
// [기능:탑아이콘 끝]
