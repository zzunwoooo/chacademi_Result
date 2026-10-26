package school.magiccodex.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackagedResourcesTest {
    @Test void arcaneHudFrameHasAnEmptyCenterAndTransparentExterior() throws Exception {
        try(var stream=getClass().getResourceAsStream("/assets/magiccodex/textures/gui/hud_arcane_slot.png")) {
            assertNotNull(stream);
            var image=ImageIO.read(stream);
            assertEquals(1254,image.getWidth()); assertEquals(1254,image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());
            assertEquals(0,image.getRGB(627,627)>>>24);
            assertEquals(0,image.getRGB(0,0)>>>24);
            for(int y=0;y<1254;y+=8) for(int x=0;x<1254;x+=8) {
                double distance=Math.hypot(x-627,y-627);
                // The bottom star extends inward to radius 389. Allow one alpha quantization level
                // in otherwise empty regions; an opaque matte or checkerboard still fails.
                if(distance<370 || distance>550) assertTrue((image.getRGB(x,y)>>>24)<=1,"HUD frame must not include backdrop pixels");
            }
        }
    }
    @Test
    void badgesAndCategoryTagsDoNotCopyOpaquePaperIntoTheirRoundedCorners() throws Exception {
        for (var sprite : java.util.List.of(CodexSprites.DISCOVERED, CodexSprites.UNDISCOVERED, CodexSprites.TAG)) {
            assertNotEquals("codex_base.png", sprite.file(), "Floating capsules must have standalone alpha");
            try (var stream = getClass().getResourceAsStream("/assets/magiccodex/textures/gui/" + sprite.file())) {
                assertNotNull(stream);
                var image = ImageIO.read(stream);
                int corner = sprite.height() / 12;
                for (int y = 0; y < corner; y++) for (int x = 0; x < corner; x++) {
                    for (int sampleX : new int[]{sprite.x() + x, sprite.x() + sprite.width() - 1 - x})
                        for (int sampleY : new int[]{sprite.y() + y, sprite.y() + sprite.height() - 1 - y})
                            assertEquals(0, image.getRGB(sampleX, sampleY) >>> 24,
                                    sprite.file() + " contains an opaque rectangle around its capsule");
                }
                assertTrue((image.getRGB(sprite.x() + sprite.width() / 2,
                        sprite.y() + sprite.height() / 2) >>> 24) > 200, "Badge fill must remain visible");
            }
        }
    }

    @Test
    void everyPreviewSpellHasDistinctNonemptyArtworkAndAllSpritesFitTheirTextures() throws Exception {
        var sprites = new java.util.ArrayList<CodexSprites.Sprite>();
        var spellRects = new java.util.HashSet<CodexSprites.Sprite>();
        for (var spell : CodexData.previewSpells()) {
            var sprite = CodexSprites.spell(spell.id());
            assertNotNull(sprite, spell.id());
            assertTrue(spellRects.add(sprite), "Each spell needs its own illustration: " + spell.id());
            sprites.add(sprite);
        }
        for (int i = 0; i < 8; i++) sprites.add(CodexSprites.emblem(i));
        sprites.addAll(java.util.List.of(CodexSprites.MEDALLION, CodexSprites.DISCOVERED,
                CodexSprites.UNDISCOVERED, CodexSprites.TAG, CodexSprites.CHECKED,
                CodexSprites.UNCHECKED, CodexSprites.LOCK));
        var images = new java.util.HashMap<String, java.awt.image.BufferedImage>();
        for (var sprite : sprites) {
            if (!images.containsKey(sprite.file())) {
                try (var stream = getClass().getResourceAsStream("/assets/magiccodex/textures/gui/" + sprite.file())) {
                    assertNotNull(stream, sprite.file());
                    images.put(sprite.file(), ImageIO.read(stream));
                }
            }
            var image = images.get(sprite.file());
            assertEquals(sprite.textureWidth(), image.getWidth(), sprite.file());
            assertEquals(sprite.textureHeight(), image.getHeight(), sprite.file());
            assertTrue(image.getColorModel().hasAlpha(), sprite.file());
            assertEquals(0, image.getRGB(0, 0) >>> 24, sprite.file());
            assertTrue(sprite.x() >= 0 && sprite.y() >= 0);
            assertTrue(sprite.x() + sprite.width() <= image.getWidth());
            assertTrue(sprite.y() + sprite.height() <= image.getHeight());
            int visible = 0;
            for (int y = sprite.y(); y < sprite.y() + sprite.height(); y += 4)
                for (int x = sprite.x(); x < sprite.x() + sprite.width(); x += 4)
                    if ((image.getRGB(x, y) >>> 24) > 100) visible++;
            assertTrue(visible > sprite.width() * sprite.height() / 160, sprite.file() + " empty sprite");
        }
    }

    @Test
    void backgroundHasExpectedCoordinatesAndRealTransparency() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/assets/magiccodex/textures/gui/codex_base.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            assertEquals(CodexLayout.WIDTH, image.getWidth());
            assertEquals(CodexLayout.HEIGHT, image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            assertTrue((image.getRGB(800, 600) >>> 24) > 240);
        }
    }

    @Test
    void allFourFontsAreActualTrueTypeFiles() throws Exception {
        for (String name : new String[]{"maruburi_bold", "maruburi_semibold", "pretendard_regular", "pretendard_medium"}) {
            try (InputStream stream = getClass().getResourceAsStream("/assets/magiccodex/font/" + name + ".ttf")) {
                assertNotNull(stream, name);
                assertEquals(0x00010000, ByteBuffer.wrap(stream.readNBytes(4)).getInt(), name);
            }
        }
    }
}
