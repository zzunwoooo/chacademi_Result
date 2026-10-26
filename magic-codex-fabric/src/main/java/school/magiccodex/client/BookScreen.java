package school.magiccodex.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.BookProtocol;

/**
 * 책 읽기 창 (2026-10-23). The owner's PNG set textures/gui/book (panel, previous, next, close); the title, the page text
 * and the page number are drawn with the MagicCodex fonts (CodexTypography: rasterised at the real pixel size, so they stay
 * sharp at every GUI scale / resolution). The whole canvas is scaled as one piece ({@link BookLayout#fit}), so the
 * proportions never change. The panel is drawn translucent (hologram, {@link BookLayout#PANEL_ALPHA}).
 *
 * The pages are drawn slightly closed (trapezoids), so every text line is sheared to the paper's slope at its height
 * ({@link BookLayout#slope}); the buttons and the page number under the book stay level.
 *
 * One page = one open spread (left + right side). Closing in any way (X, ESC, another screen) sends the server's token
 * once; the server decides whether anything runs.
 */
final class BookScreen extends Screen {
    private static final String ROOT = "textures/gui/book/";
    private static final Identifier TITLE = Identifier.of("magiccodex", "title"), BODY = Identifier.of("magiccodex", "heading");
    private static final int TITLE_COLOR = 0xEBD3A0, BODY_COLOR = 0xDCE3EE, LABEL_COLOR = 0xE9D9B0, GOLD_LINE = 0xD9B66E;

    private final BookProtocol.Open book;
    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private int page;
    private boolean closed;
    /** Layout per page, rebuilt when the fonts were reloaded (resource pack change). */
    private final Map<Integer, PageLayout> layouts = new HashMap<>();
    private int layoutGeneration = -1;

    private record PageLayout(BookLayout.Block title, float titleTop, float dividerY, BookLayout.Block left, float leftTop, BookLayout.Block right) {}

    BookScreen(BookProtocol.Open book) {
        super(Text.literal(book.title()));
        this.book = book;
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}
    @Override public void removed() {
        if (closed) return;
        closed = true;
        BookClient.close(book.token());
    }

    private int pages() { return book.pages().size(); }

    private void turn(int delta) {
        int next = Math.clamp(page + delta, 0, pages() - 1);
        if (next == page) return;
        page = next;
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.1f, .42f));
    }

    // ------------------------------------------------------------------ layout

    private PageLayout layout(int index) {
        if (layoutGeneration != UiResources.generation()) { layouts.clear(); layoutGeneration = UiResources.generation(); }
        return layouts.computeIfAbsent(index, i -> {
            var type = UiResources.text();
            BookLayout.Measure body = (t, s) -> type.width(t, s, BODY), title = (t, s) -> type.width(t, s, TITLE);
            var p = book.pages().get(i);
            BookLayout.Block head = null;
            float titleTop = BookLayout.TITLE_TOP, dividerY = 0, leftTop = BookLayout.BOX_TOP;
            if (i == 0) {
                head = BookLayout.title(book.title(), title);
                dividerY = titleTop + head.height() + BookLayout.DIVIDER_GAP;
                leftTop = dividerY + BookLayout.BODY_GAP;
            }
            var left = BookLayout.fit(p.left(), BookLayout.BOX_W, BookLayout.BOX_BOTTOM - leftTop, BookLayout.BODY_SIZE, BookLayout.BODY_MIN, BookLayout.LINE, body);
            var right = BookLayout.fit(p.right(), BookLayout.RIGHT_W, BookLayout.BOX_BOTTOM - BookLayout.BOX_TOP, BookLayout.BODY_SIZE, BookLayout.BODY_MIN, BookLayout.LINE, body);
            return new PageLayout(head, titleTop, dividerY, left, leftTop, right);
        });
    }

    // ------------------------------------------------------------------ input

    private static boolean in(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private static boolean inCircle(double mx, double my, float cx, float cy, float d) { double dx = mx - cx, dy = my - cy; return dx * dx + dy * dy <= d * d / 4; }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        var l = BookLayout.fit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        if (in(mx, my, BookLayout.CLOSE_X, BookLayout.CLOSE_Y, BookLayout.CLOSE_S, BookLayout.CLOSE_S)) {
            if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, .25f));
            close();
            return true;
        }
        if (page > 0 && inCircle(mx, my, BookLayout.PREV_CX, BookLayout.NAV_Y, BookLayout.NAV_S)) { turn(-1); return true; }
        if (page < pages() - 1 && inCircle(mx, my, BookLayout.NEXT_CX, BookLayout.NAV_Y, BookLayout.NAV_S)) { turn(1); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0) turn(vertical < 0 ? 1 : -1);
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN) { turn(1); return true; }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP) { turn(-1); return true; }
        return super.keyPressed(key, scan, mods);   // ESC closes (removed() sends the close)
    }

    // ------------------------------------------------------------------ render

    private static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF); }

    private static void image(DrawContext c, String name, float x, float y, float w, float h, int tw, int th, float alpha) {
        int x0 = Math.round(x), y0 = Math.round(y), w0 = Math.round(x + w) - x0, h0 = Math.round(y + h) - y0;
        UiResources.images().drawTexture(c, Identifier.of("magiccodex", ROOT + name + ".png"), x0, y0, 0, 0, w0, h0, tw, th, tw, th, argb(alpha, 0xFFFFFF));
    }

    /** Shear the matrix so things drawn level around height y follow the tilted paper of that page. */
    private static void tilt(DrawContext c, boolean right, float y) {
        float px = BookLayout.pivot(right), k = BookLayout.slope(right, y);
        var m = c.getMatrices();
        m.translate(px, y, 0);
        m.peek().getPositionMatrix().mul(new Matrix4f().m01(k));
        m.translate(-px, -y, 0);
    }

    private static void lines(DrawContext c, BookLayout.Block block, boolean right, float x, float top, int rgb, float alpha, Identifier face) {
        float y = top + block.size() / 2;
        for (String line : block.lines()) {
            if (!line.isBlank()) {
                c.getMatrices().push();
                try { tilt(c, right, y); UiResources.text().draw(c, line, x, y, block.size(), argb(alpha, rgb), face, false); }
                finally { c.getMatrices().pop(); }
            }
            y += block.lineHeight();
        }
    }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = BookLayout.fit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var type = UiResources.text();
        images.beginFrame(); type.beginFrame();
        c.fill(0, 0, width, height, 0x4D040A14);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            image(c, "book_panel", 0, 0, BookLayout.PANEL_W, BookLayout.PANEL_H, 1672, 941, BookLayout.PANEL_ALPHA);

            var p = layout(page);
            if (p.title() != null && !p.title().lines().isEmpty()) {
                float cx = BookLayout.LEFT_X + BookLayout.BOX_W / 2, y = p.titleTop() + p.title().size() / 2;
                for (String line : p.title().lines()) {
                    m.push();
                    try { tilt(c, false, y); type.draw(c, line, cx, y, p.title().size(), argb(1, TITLE_COLOR), TITLE, true); }
                    finally { m.pop(); }
                    y += p.title().lineHeight();
                }
                m.push();
                try { tilt(c, false, p.dividerY()); divider(c, cx, p.dividerY()); }
                finally { m.pop(); }
            }
            lines(c, p.left(), false, BookLayout.LEFT_X, p.leftTop(), BODY_COLOR, .94f, BODY);
            lines(c, p.right(), true, BookLayout.RIGHT_X, BookLayout.BOX_TOP, BODY_COLOR, .94f, BODY);

            boolean overClose = in(mx, my, BookLayout.CLOSE_X, BookLayout.CLOSE_Y, BookLayout.CLOSE_S, BookLayout.CLOSE_S);
            image(c, "close", BookLayout.CLOSE_X, BookLayout.CLOSE_Y, BookLayout.CLOSE_S, BookLayout.CLOSE_S, 256, 256, overClose ? 1 : BookLayout.BUTTON_ALPHA);
            nav(c, mx, my);
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    /** Thin gold rule with a small diamond under the title (as in the reference sheet). */
    private static void divider(DrawContext c, float cx, float y) {
        int color = argb(.55f, GOLD_LINE);
        float half = BookLayout.DIVIDER_W / 2;
        c.fill(Math.round(cx - half), Math.round(y - 1), Math.round(cx - 12), Math.round(y + 1), color);
        c.fill(Math.round(cx + 12), Math.round(y - 1), Math.round(cx + half), Math.round(y + 1), color);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(cx, y, 0); m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(45));
            c.fill(-5, -5, 5, 5, argb(.85f, GOLD_LINE));
        } finally { m.pop(); }
    }

    private void nav(DrawContext c, double mx, double my) {
        float s = BookLayout.NAV_S, y = BookLayout.NAV_Y;
        boolean canPrev = page > 0, canNext = page < pages() - 1;
        float prevA = !canPrev ? BookLayout.DISABLED_ALPHA : inCircle(mx, my, BookLayout.PREV_CX, y, s) ? 1 : BookLayout.BUTTON_ALPHA;
        float nextA = !canNext ? BookLayout.DISABLED_ALPHA : inCircle(mx, my, BookLayout.NEXT_CX, y, s) ? 1 : BookLayout.BUTTON_ALPHA;
        // thin connecting rules between the buttons and the page number (as in the reference sheet)
        String label = (page + 1) + " / " + pages();
        float lw = UiResources.text().width(label, BookLayout.LABEL_SIZE, TITLE);
        int rule = argb(.4f, GOLD_LINE);
        int ry = Math.round(y);
        c.fill(Math.round(BookLayout.PREV_CX + s / 2 + 14), ry - 1, Math.round(BookLayout.LABEL_CX - lw / 2 - 18), ry + 1, rule);
        c.fill(Math.round(BookLayout.LABEL_CX + lw / 2 + 18), ry - 1, Math.round(BookLayout.NEXT_CX - s / 2 - 14), ry + 1, rule);
        image(c, "page_previous", BookLayout.PREV_CX - s / 2, y - s / 2, s, s, 256, 256, prevA);
        image(c, "page_next", BookLayout.NEXT_CX - s / 2, y - s / 2, s, s, 256, 256, nextA);
        UiResources.text().draw(c, label, BookLayout.LABEL_CX, y, BookLayout.LABEL_SIZE, argb(.95f, LABEL_COLOR), TITLE, true);
    }
}
