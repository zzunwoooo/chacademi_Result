package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class CodexLayoutTest {
    @Test
    void everyCardKeepsActualKoreanGlyphsBetweenArtworkAndBadge() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/magiccodex/font/maruburi_semibold.ttf")) {
            var font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream);
            for (float pixelsPerUnit : new float[]{1280f / 1672, 1920f / 1672}) {
                for (int slot = 0; slot < 9; slot++) {
                    var card = CodexHitboxes.card(slot);
                    for (var spell : CodexData.previewSpells()) {
                        var glyph = CodexTextRasterizer.rasterize(font, spell.name(),
                                CodexComposition.NAME_SIZE * pixelsPerUnit);
                        float inkHeight = (glyph.getHeight() - 2 * CodexTextRasterizer.PADDING) / pixelsPerUnit;
                        float top = CodexComposition.NAME_CENTER_Y - inkHeight / 2;
                        float bottom = CodexComposition.NAME_CENTER_Y + inkHeight / 2;
                        assertTrue(top >= CodexComposition.IMAGE_BOTTOM + 6,
                                "Name invades artwork: slot " + slot + " / " + spell.name());
                        assertTrue(bottom + 4 <= CodexComposition.BADGE_TOP, "Name touches badge");
                        assertTrue(CodexComposition.BADGE_TOP + CodexComposition.BADGE_HEIGHT <= card.height() - 6,
                                "Badge touches bottom edge in slot " + slot);
                    }
                    assertTrue(CodexComposition.ART_CENTER_Y - CodexComposition.ART_SIZE / 2 >= 6);
                    assertTrue(CodexComposition.ART_CENTER_Y + CodexComposition.ART_SIZE / 2 <= CodexComposition.IMAGE_BOTTOM - 2);
                    if (slot % 3 < 2) assertTrue(card.x() + card.width() + 12 <= CodexHitboxes.card(slot + 1).x());
                    if (slot < 6) assertTrue(card.y() + card.height() + 12 <= CodexHitboxes.card(slot + 3).y());
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1920,1080", "2560,1440", "3440,1440", "1024,768", "800,600",
            "640,360", "427,240", "320,240", "320,180", "1080,1920"})
    void fitsAndCentersAtDifferentResolutionsAndGuiScales(int width, int height) {
        CodexLayout fit = CodexLayout.fit(width, height);
        float right = fit.x() + CodexLayout.WIDTH * fit.scale();
        float bottom = fit.y() + CodexLayout.HEIGHT * fit.scale();
        assertTrue(fit.scale() > 0);
        assertTrue(fit.x() >= -0.001f && fit.y() >= -0.001f);
        assertTrue(right <= width + 0.001f && bottom <= height + 0.001f);
        assertEquals(fit.x(), width - right, 0.001f);
        assertEquals(fit.y(), height - bottom, 0.001f);
        assertTrue(Math.abs(right - width) < 0.001f || Math.abs(bottom - height) < 0.001f);
    }

    @Test
    void rejectsInvalidViewport() {
        assertThrows(IllegalArgumentException.class, () -> CodexLayout.fit(0, 100));
        assertThrows(IllegalArgumentException.class, () -> CodexLayout.fit(100, -1));
    }

    @ParameterizedTest
    @CsvSource({"1920,1080", "3440,1440", "640,360", "427,240", "1080,1920"})
    void smallerCodexKeepsEveryCardClickableAndCentered(int width, int height) {
        var layout = CodexLayout.codexFit(width, height);
        assertEquals(CodexLayout.fit(width, height).scale() * 0.85f, layout.scale(), 0.0001f);
        assertEquals(width / 2f, layout.x() + CodexLayout.WIDTH * layout.scale() / 2, 0.001f);
        assertEquals(height / 2f, layout.y() + CodexLayout.HEIGHT * layout.scale() / 2, 0.001f);
        for (int i = 0; i < 9; i++) {
            var card = CodexHitboxes.card(i);
            assertTrue(card.contains(layout.localX(layout.x() + card.centerX() * layout.scale()),
                    layout.localY(layout.y() + card.centerY() * layout.scale())));
        }
        assertTrue(layout.localX(0) < 0 && layout.localY(0) < 0);
    }

    @ParameterizedTest
    @CsvSource({"1920,1080", "1919,1004", "3440,1440", "1024,768", "1080,1920", "427,240", "320,240"})
    void dialogueDimCoversFullViewportIncludingMargins(int width, int height) {
        float scale = Math.min(width / 1600f, height / 900f);
        var layout = new CodexLayout((width - 1600 * scale) / 2,
                (height - 900 * scale) / 2, scale);
        var bounds = layout.viewportBounds(width, height);
        assertTrue(layout.x() + bounds.left() * scale <= 0.001f);
        assertTrue(layout.y() + bounds.top() * scale <= 0.001f);
        assertTrue(layout.x() + bounds.right() * scale >= width - 0.001f);
        assertTrue(layout.y() + bounds.bottom() * scale >= height - 0.001f);
        assertEquals(800, layout.localX(width / 2d), 0.001);
        assertEquals(450, layout.localY(height / 2d), 0.001);
    }

    @Test
    void minimizedViewportDoesNotProduceInvalidDimBounds() {
        assertEquals(new CodexLayout.Bounds(0, 0, 0, 0), new CodexLayout(0, 0, 0).viewportBounds(0, 0));
    }
}
