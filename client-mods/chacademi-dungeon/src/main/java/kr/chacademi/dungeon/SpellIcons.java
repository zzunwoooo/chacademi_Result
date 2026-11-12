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
 * 탑 카드의 마법 아이콘·설명: 마법도감(MagicCodex) 모드에 든 마법 정의(assets/magiccodex/default-spells/*.yml)를 읽는다.
 * 아이콘 원본은 1254px 이라 256px 로 줄여 올린다 (Art.mcSmall). MagicCodex 가 없거나 못 찾으면 false/null → 카드는 아이템 그림·설명 없음.
 */
final class SpellIcons {
    private SpellIcons() {}

    /** [기능:탑마법설명] 마우스를 올렸을 때 보여 줄 것. */
    record Info(String name, String icon, String description, int mana, int cooldown) {}

    private static Map<String, Info> infos;

    static boolean draw(GuiGraphics g, String spell, float x, float y, float size, int tint) {
        var info = info(spell);
        if (info == null || info.icon().isEmpty()) return false;
        var tex = Art.mcSmall(info.icon(), 256);
        if (tex.isEmpty()) return false;
        Art.image(g, tex, x, y, size, size, tint);
        return true;
    }

    static void reset() { infos = null; }

    static Info info(String spell) {
        if (infos == null) {
            var map = new HashMap<String, Info>();
            try {
                var found = Minecraft.getInstance().getResourceManager().listResources("default-spells", l -> l.getPath().endsWith(".yml"));
                for (var e : found.entrySet()) {
                    if (!e.getKey().getNamespace().equals("magiccodex")) continue;
                    String id = null, name = "", icon = "", desc = "", key = "";
                    int mana = -1, cd = -1;
                    try (var r = new BufferedReader(new InputStreamReader(e.getValue().open(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            if (!line.startsWith(" ") && line.contains(":")) {
                                key = line.substring(0, line.indexOf(':'));
                                String v = clean(line.substring(line.indexOf(':') + 1));
                                switch (key) {
                                    case "id" -> id = v;
                                    case "name" -> name = v;
                                    case "icon" -> icon = v.startsWith("magiccodex:") ? v.substring("magiccodex:".length()) : "";
                                    case "description" -> desc = v;
                                    default -> {}
                                }
                            } else if (key.equals("description") && line.startsWith("  ") && !line.trim().contains(": ")) desc += " " + line.trim();   // 접힌 긴 설명
                            else if (key.equals("cast")) {
                                String t = line.trim();
                                if (t.startsWith("mana-cost:")) mana = num(t.substring(10));
                                else if (t.startsWith("cooldown-seconds:")) cd = num(t.substring(17));
                            }
                        }
                    } catch (Exception ignored) { }
                    if (id != null) map.put(id, new Info(name, icon, desc, mana, cd));
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("마법 정보 읽기 실패: {}", e.toString()); }
            infos = map;
        }
        return infos.get(spell);
    }

    private static int num(String v) { try { return (int) Math.round(Double.parseDouble(clean(v))); } catch (NumberFormatException e) { return -1; } }

    private static String clean(String v) { return v.trim().replace("\"", "").replace("'", ""); }
}
// [기능:탑아이콘 끝]
