package school.chacademia.housing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;

/** 접속 중인 플레이어의 스킨 해시 (textures.minecraft.net/texture/<해시>). 다른 서버·오프라인 얼굴을 그릴 때 쓴다. */
final class Skins {
    record Skin(String hash, boolean slim) { static final Skin NONE = new Skin("", false); }
    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"https?://textures\\.minecraft\\.net/texture/([0-9a-f]{32,64})\"");
    private static final Pattern SLIM = Pattern.compile("\"model\"\\s*:\\s*\"slim\"");

    static Skin of(Player p) {
        try {
            for (var property : p.getPlayerProfile().getProperties())
                if (property.getName().equals("textures")) return parse(property.getValue());
        } catch (RuntimeException ignored) { }
        return Skin.NONE;
    }

    /** base64 textures 값 → 해시·slim. 모르는 형식이면 NONE. */
    static Skin parse(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int skin = json.indexOf("\"SKIN\"");
            if (skin < 0) return Skin.NONE;
            int cape = json.indexOf("\"CAPE\"", skin);
            String part = cape < 0 ? json.substring(skin) : json.substring(skin, cape);
            var m = URL.matcher(part);
            return m.find() ? new Skin(m.group(1), SLIM.matcher(part).find()) : Skin.NONE;
        } catch (IllegalArgumentException bad) { return Skin.NONE; }
    }

    private Skins() {}
}
