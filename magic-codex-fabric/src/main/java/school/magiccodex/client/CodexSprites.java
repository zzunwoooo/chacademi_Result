package school.magiccodex.client;

import java.util.List;

/** Source rectangles preserve original generated PNGs; no resized copies or baked text. */
public final class CodexSprites {
    private CodexSprites() {}
    public record Sprite(String file, int textureWidth, int textureHeight, int x, int y, int width, int height) {}
    public static final List<String> SPELL_IDS = List.of(
            "magical_flame", "feather_step", "water_drop", "stone_skin", "starlight", "shadow_veil",
            "ember_ring", "wind_bell", "mist", "root_bond", "moon_beam", "night_echo",
            "hearth", "updraft", "ripple", "crystal", "dawn", "ink_whisper");
    public static final Sprite MEDALLION = new Sprite("detail_medallion.png", 1254, 1254, 0, 0, 1254, 1254);
    // Standalone alpha textures: extracting these from the base also copied its opaque parchment corners.
    // Keep four transparent source pixels around the visible bounds to avoid clipping antialiased edges.
    public static final Sprite DISCOVERED = new Sprite("badge_discovered.png", 2172, 724, 298, 233, 1576, 258);
    public static final Sprite UNDISCOVERED = new Sprite("badge_undiscovered.png", 2172, 724, 286, 229, 1601, 266);
    public static final Sprite TAG = new Sprite("tag_category.png", 2172, 724, 298, 233, 1576, 258);
    public static final Sprite CHECKED = new Sprite("checkbox_checked.png", 1254, 1254, 189, 192, 877, 868);
    public static final Sprite UNCHECKED = new Sprite("checkbox_unchecked.png", 1254, 1254, 189, 193, 875, 866);
    public static final Sprite LOCK = new Sprite("icon_locked.png", 1254, 1254, 358, 257, 538, 715);

    public static Sprite spell(String id) {
        int index = SPELL_IDS.indexOf(id);
        return index < 0 ? null : cell("spell_atlas.png", 6, 3, index, 8);
    }

    /** 0..6 are category enum order; 7 is the research book. */
    public static Sprite emblem(int index) {
        if (index < 0 || index >= 8) throw new IndexOutOfBoundsException(index);
        return cell("category_atlas.png", 4, 2, index, 36);
    }

    private static Sprite cell(String file, int columns, int rows, int index, int inset) {
        int left = index % columns * 1774 / columns + inset;
        int top = index / columns * 887 / rows + inset;
        int right = (index % columns + 1) * 1774 / columns - inset;
        int bottom = (index / columns + 1) * 887 / rows - inset;
        return new Sprite(file, 1774, 887, left, top, right - left, bottom - top);
    }
}
