package kr.chacademy.portrait.core;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {
    private YamlConfiguration config() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            var c = new YamlConfiguration();
            c.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return c;
        }
    }
    private PromptBuilder.ImageRequest request(List<byte[]> refs, byte[] skin) throws Exception {
        var c = config();
        return PromptBuilder.imageRequest(refs, skin, c.getString("prompt.base"),
                c.getString("prompt.appearance"), c.getString("prompt.request"), "", "");
    }
    @Test void singleReferenceMatchesActualSkinAttachment() throws Exception {
        byte[] ref={1}, skin={9};
        var r=request(List.of(ref),skin);
        assertSame(ref,r.images().get(0)); assertSame(skin,r.images().get(1));
        assertTrue(r.prompt().startsWith("Image 2 is the CHARACTER IDENTITY source"));
        assertTrue(r.prompt().contains("ART STYLE ONLY references: Image 1"));
        assertFalse(r.prompt().contains("{skin_index}"));
    }
    @Test void multipleReferencesKeepSkinLastAndResolveItsIndex() throws Exception {
        byte[] a={1},b={2},c={3},skin={9};
        var r=request(List.of(a,b,c),skin);
        assertEquals(4,r.images().size()); assertSame(a,r.images().get(0));
        assertSame(b,r.images().get(1)); assertSame(c,r.images().get(2)); assertSame(skin,r.images().get(3));
        assertTrue(r.prompt().startsWith("Image 4 is the CHARACTER IDENTITY source"));
        assertTrue(r.prompt().contains("ART STYLE ONLY references: Images 1 through 3"));
        assertFalse(r.prompt().contains("{style_images}"));
    }
    @Test void packagedPromptKeepsSkinIdentityAboveStyleAndFixedFraming() throws Exception {
        var p=request(List.of(new byte[]{1}),new byte[]{9}).prompt();
        assertTrue(p.contains("skin is the ONLY source"));
        assertTrue(p.contains("colors assigned to each body part"));
        assertTrue(p.contains("must NEVER replace or shift"));
        assertTrue(p.contains("face, hair, clothing, accessories, pose, or identity"));
        assertTrue(p.contains("skin takes priority"));
        assertTrue(p.contains("upper-body portrait from the head to the waist"));
        assertFalse(p.contains("STYLE and COMPOSITION"));
    }
    @Test void describeAndOptionalRequestCannotRedesignIdentity() throws Exception {
        var c=config();
        assertTrue(c.getString("prompt.describe").contains("never infer the real user's gender or age"));
        assertTrue(c.getString("prompt.describe").contains("Omit uncertain elements"));
        var p=PromptBuilder.imageRequest(List.of(new byte[]{1}),new byte[]{9},c.getString("prompt.base"),
                c.getString("prompt.appearance"),c.getString("prompt.request"),"blue hair","smile").prompt();
        assertTrue(p.contains("defer to the skin image: blue hair"));
        assertTrue(p.contains("must not change the skin identity"));
        assertTrue(p.contains("\"smile\""));
    }
}