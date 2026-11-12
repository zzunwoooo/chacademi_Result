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
    record Info(String name, String icon, String description, int mana, int cooldown, String category) {}

    private static Map<String, Info> infos;

    /**
     * 마법도감과 똑같이: 도감이 쓰는 마법 목록(CastingState)의 아이콘 → 몹 스킬·기믹 마법 보정 → 마법 정의 파일 아이콘 →
     * 그래도 없으면 도감처럼 원소 문양 (아이콘 그림이 아예 없는 마법 75종, 11/12 확인).
     */
    static boolean draw(GuiGraphics g, String spell, float x, float y, float size, int tint) {
        var info = info(spell);
        String icon = codexIcon(spell);
        if ((icon == null || icon.isBlank()) && info != null) icon = info.icon();
        if (icon != null && !icon.isBlank()) {
            String path = icon.startsWith("magiccodex:") ? icon.substring("magiccodex:".length()) : icon;
            if (!path.contains(":") && path.matches("[a-z0-9_./-]+")) {   // 다른 이름공간·이상한 글자는 건너뜀 (읽다 터지지 않게)
                var tex = Art.mcSmall(path, 256);
                if (tex.isPresent()) { Art.image(g, tex, x, y, size, size, tint); return true; }
            }
        }
        return emblem(g, spell, info, x, y, size, tint);
    }

    // ── 마법도감 연결 (리플렉션, 없으면 조용히 건너뜀) ──
    private static boolean looked;
    private static java.lang.reflect.Method state, spellOf, iconOf, categoryOf, mobResolve, gimmickResolve, emblemOf, sFile, sTw, sTh, sX, sY, sW, sH;

    private static void look() {
        if (looked) return;
        looked = true;
        // 하나씩 따로 (옛 마법도감에 한 가지가 없어도 나머지는 씀)
        try {
            state = Class.forName("school.magiccodex.client.CastingClient").getMethod("state");
            spellOf = Class.forName("school.magiccodex.client.CastingState").getMethod("spell", String.class);
            var sp = Class.forName("school.magiccodex.client.CodexData$Spell");
            iconOf = sp.getMethod("icon");
            categoryOf = sp.getMethod("category");
        } catch (Throwable e) { state = null; org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("마법도감 목록 연결 실패: {}", e.toString()); }
        try { mobResolve = Class.forName("school.magiccodex.client.MobSpellIcons").getDeclaredMethod("resolve", String.class, String.class); mobResolve.setAccessible(true); } catch (Throwable e) { mobResolve = null; }
        try { gimmickResolve = Class.forName("school.magiccodex.client.GimmickSpellIcons").getDeclaredMethod("resolve", String.class, String.class); gimmickResolve.setAccessible(true); } catch (Throwable e) { gimmickResolve = null; }
        try {
            emblemOf = Class.forName("school.magiccodex.client.CodexSprites").getMethod("emblem", int.class);
            var s = Class.forName("school.magiccodex.client.CodexSprites$Sprite");
            sFile = s.getMethod("file"); sTw = s.getMethod("textureWidth"); sTh = s.getMethod("textureHeight");
            sX = s.getMethod("x"); sY = s.getMethod("y"); sW = s.getMethod("width"); sH = s.getMethod("height");
        } catch (Throwable e) { emblemOf = null; }
    }

    private static Object codexSpell(String id) {
        look();
        try { return state == null ? null : spellOf.invoke(state.invoke(null), id); } catch (Throwable e) { return null; }
    }

    private static String codexIcon(String id) {
        Object sp = codexSpell(id);
        String icon = null;
        try { if (sp != null) icon = (String) iconOf.invoke(sp); } catch (Throwable ignored) { }
        try { if (mobResolve != null) icon = (String) mobResolve.invoke(null, id, icon); } catch (Throwable ignored) { }
        try { if (gimmickResolve != null) icon = (String) gimmickResolve.invoke(null, id, icon); } catch (Throwable ignored) { }
        return icon;
    }

    private static boolean emblem(GuiGraphics g, String id, Info info, float x, float y, float size, int tint) {
        look();
        if (emblemOf == null) return false;
        try {
            int cat = -1;
            Object sp = codexSpell(id);
            if (sp != null) cat = ((Enum<?>) categoryOf.invoke(sp)).ordinal();
            if (cat < 0 && info != null) cat = switch (info.category()) { case "wind" -> 1; case "fire" -> 2; case "water" -> 3; case "earth" -> 4; case "light" -> 5; case "dark" -> 6; default -> 0; };
            Object s = emblemOf.invoke(null, Math.max(0, cat));
            var tex = Art.mc("textures/gui/" + sFile.invoke(s));
            if (tex.isEmpty()) return false;
            float tw = (int) sTw.invoke(s), th = (int) sTh.invoke(s);
            float u0 = (int) sX.invoke(s) / tw, v0 = (int) sY.invoke(s) / th, u1 = u0 + (int) sW.invoke(s) / tw, v1 = v0 + (int) sH.invoke(s) / th;
            float w = (int) sW.invoke(s), h = (int) sH.invoke(s), k = size / Math.max(w, h);
            float dw = w * k, dh = h * k;
            Art.quad(g, tex.get(), x + (size - dw) / 2, y + (size - dh) / 2, x + (size + dw) / 2, y + (size + dh) / 2, u0, v0, u1, v1, tint);
            return true;
        } catch (Throwable e) { return false; }
    }

    static void reset() { infos = null; }

    static Info info(String spell) {
        if (infos == null) {
            var map = new HashMap<String, Info>();
            try {
                var found = Minecraft.getInstance().getResourceManager().listResources("default-spells", l -> l.getPath().endsWith(".yml"));
                for (var e : found.entrySet()) {
                    if (!e.getKey().getNamespace().equals("magiccodex")) continue;
                    String id = null, name = "", icon = "", desc = "", key = "", cat = "";
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
                                    case "category" -> cat = v;
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
                    if (id != null) map.put(id, new Info(name, icon, desc, mana, cd, cat));
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
