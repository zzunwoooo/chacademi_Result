package school.magiccodex.client.kui;

import java.util.UUID;

/**
 * Drawing surface for the 큐디치 views. Two implementations: the game (KyudichiCanvas, DrawContext) and a plain Java2D one
 * used to render preview PNGs for layout checks against the reference images (same fonts, same coordinates).
 * Coordinates are the view's design pixels (the reference canvas, e.g. 1672×941).
 */
public interface Canvas {
    enum Face { TITLE, SERIF, SANS, SANS_BOLD }
    int LEFT = 0, CENTER = 1, RIGHT = 2;

    /** A UI part (see {@link Parts}) stretched into the rectangle. */
    void image(String part, float x, float y, float w, float h, float alpha);
    /** A UI part centred on (cx, cy) and rotated clockwise. */
    void imageRotated(String part, float cx, float cy, float w, float h, float degrees, float alpha);
    /** Any other texture by resource id ("magiccodex:textures/…png"), e.g. a spell icon. */
    void texture(String resource, float x, float y, float w, float h, float alpha);
    /** A player's face (skin hash may be empty: the client then uses what it knows, else the default skin). */
    void head(UUID id, String skin, float x, float y, float size);
    /** A round skin face (face + hat layer) centred on (cx, cy). */
    void avatar(UUID id, String skin, float cx, float cy, float radius);
    /** The broom model / picture, centred. Returns false when nothing could be drawn. */
    boolean broom(String id, float cx, float cy, float w, float h);
    /** Text; y is the vertical centre of the ink. */
    void text(String s, float x, float y, float size, int argb, Face face, int align);
    float width(String s, float size, Face face);
    void fill(float x, float y, float w, float h, int argb);
    /** Ring segment, fractions clockwise from twelve o'clock. inner 0 = pie. */
    void arc(float cx, float cy, float inner, float outer, float start, float end, int argb);
    void push(float tx, float ty, float scale);
    void pop();
    /** Clip to a rectangle (design pixels of the current transform) until {@link #unclip()}. */
    void clip(float x, float y, float w, float h);
    void unclip();
}
