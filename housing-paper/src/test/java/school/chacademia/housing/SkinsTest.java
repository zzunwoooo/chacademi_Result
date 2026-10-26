package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SkinsTest {
    static String b64(String json) { return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)); }

    @Test void parsesHashAndSlim() {
        String hash = "a".repeat(64);
        var s = Skins.parse(b64("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\",\"metadata\":{\"model\":\"slim\"}}}}"));
        assertEquals(hash, s.hash());
        assertTrue(s.slim());
        var classic = Skins.parse(b64("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"},\"CAPE\":{\"url\":\"http://textures.minecraft.net/texture/" + "b".repeat(64) + "\"}}}"));
        assertEquals(hash, classic.hash());
        assertFalse(classic.slim());
    }

    @Test void rejectsForeignHosts() {
        assertEquals(Skins.Skin.NONE, Skins.parse(b64("{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/texture/" + "a".repeat(64) + "\"}}}")));
        assertEquals(Skins.Skin.NONE, Skins.parse("not base64!"));
    }
}
