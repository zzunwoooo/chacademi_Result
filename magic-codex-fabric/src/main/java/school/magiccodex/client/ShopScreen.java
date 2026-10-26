package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.*;
import java.util.*;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import org.lwjgl.glfw.GLFW;

import school.magiccodex.protocol.ShopProtocol;
import school.magiccodex.protocol.ShopProtocol.*;

/**
 * NPC shop (reference: 일반 NPC 상점 12칸 UI v2, 4x3 cards + pages). Layout, art and hit testing live in
 * {@link ShopView}; this class owns the server data, filter/sort/page/selection/quantity state and the single
 * trade path ({@link #trade()}) the footer button goes through.
 * Protocol, prices, validation and sounds are unchanged.
 */
public final class ShopScreen extends SocialScreen {
    private static final Identifier FACE_TITLE = Identifier.of("magiccodex", "title"), FACE_SECTION = Identifier.of("magiccodex", "section"),
            FACE_BODY = Identifier.of("magiccodex", "body");
    private static final Map<Identifier, float[]> SIZES = new HashMap<>();

    private Response data;
    private Input search, quantity;
    private String selected = "", sentSearch = "";
    private ShopView.Tab tab = ShopView.Tab.ALL;
    private ShopView.Sort sort = ShopView.Sort.DEFAULT;
    private boolean selling, pressed, sortOpen;
    private int page;
    private double lastMouseX = -1, lastMouseY = -1;
    private long searchAt;
    private Map<String, ItemStack> icons = Map.of();
    private Map<String, List<String>> lore = Map.of();

    ShopScreen() { super("상점"); }

    @Override SocialLayout.Fit fit() {
        float s = Math.min(width * .98f / ShopView.W, height * .98f / ShopView.H);
        return new SocialLayout.Fit((width - ShopView.W * s) / 2, (height - ShopView.H * s) / 2, s);
    }

    @Override protected void init() {
        super.init();
        search = new Input("아이템 검색", 80, search == null ? "" : search.text());
        quantity = new Input("수량", 9, quantity == null ? "1" : quantity.text());
        quantity.widget.setTextPredicate(v -> v.matches("[0-9]{0,9}"));
    }

    void receive(Response r) {
        data = r;
        busy = false;
        notice(r.message());
        var map = new HashMap<String, ItemStack>();
        var text = new HashMap<String, List<String>>();
        for (var p : r.products()) {
            var stack = decode(p.preview());
            map.put(p.id(), stack);
            text.put(p.id(), describe(stack));
            if (p.exchange()) map.put("cost:" + p.id(), decode(p.costPreview()));   // 교환 상품: 가격 자리에 그릴 비용 아이템
        }
        icons = Map.copyOf(map);
        lore = Map.copyOf(text);
        normalize(false);
    }
    void failed(String m) { busy = false; notice(m); }
    /**
     * Shared notification for a completed trade (existing SharedNotifications card, item icon on the left).
     * Captured at request time so it still fires when the shop was closed before the reply arrived.
     */
    private Runnable receipt(Product p, boolean sell, int count, String sum) {
        String item = name(p), price = p.exchange() && !sell ? costName(p) + " ×" + ShopView.money(sum) : ShopView.money(sum) + " G";
        ItemStack icon = icons.getOrDefault(p.id(), ItemStack.EMPTY).copy();
        String id = "shop:" + UUID.randomUUID();
        return sell
                ? () -> SharedNotifications.item(id, item + " ×" + count + " 판매 완료", price + " 획득 · 보유 골드에 더해졌습니다.", icon)
                : () -> SharedNotifications.item(id, item + " ×" + count + (p.exchange() ? " 교환 완료" : " 구매 완료"), "우편함으로 보냈습니다 · " + price + " 사용", icon);
    }
    void tradeSucceeded() {
        notice = "";   // the success card replaces the in-shop "완료" toast; failures still use the toast
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.25f, .30f));
    }

    private ItemStack decode(byte[] b) {
        if (b.length == 0 || client.world == null) return ItemStack.EMPTY;
        try {
            return ItemStack.fromNbt(client.world.getRegistryManager(),
                    NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }
    /**
     * Row description from the item's own lore. As in the MagicCodex tooltip, a first lore line
     * followed by more lines is the item-type label, so the description starts after it.
     * Resource-pack glyphs (private-use characters) are removed because the UI font cannot draw them.
     */
    private static List<String> describe(ItemStack stack) {
        var component = stack.isEmpty() ? null : stack.get(DataComponentTypes.LORE);
        if (component == null) return List.of();
        var lines = new ArrayList<String>();
        for (Text t : component.lines()) {
            String s = ShopView.clean(t.getString());
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.size() > 1) lines.removeFirst();
        return List.copyOf(lines.subList(0, Math.min(2, lines.size())));
    }
    private String name(Product p) {
        var stack = icons.getOrDefault(p.id(), ItemStack.EMPTY);
        return stack.isEmpty() ? p.name() : ItemTooltipRenderer.localName(stack, stack.getName()).getString();
    }
    /** 교환 상품의 비용 아이템 이름 (견본이 있으면 그 아이템 이름, 없으면 서버가 보낸 이름). 보통 상품은 "". */
    private String costName(Product p) {
        if (!p.exchange()) return "";
        var stack = icons.getOrDefault("cost:" + p.id(), ItemStack.EMPTY);
        String n = stack.isEmpty() ? p.costName() : ItemTooltipRenderer.localName(stack, stack.getName()).getString();
        return n.isBlank() ? "교환 아이템" : n;
    }

    // ---- filter / selection ----------------------------------------------------------------------------------
    private boolean shows(Product p) {
        return switch (tab) { case BUY -> !p.buy().isEmpty(); case SELL -> !p.sell().isEmpty(); case ALL -> !p.buy().isEmpty() || !p.sell().isEmpty(); };
    }
    private List<Product> visible() {
        if (data == null) return List.of();
        String q = search == null ? "" : search.text().toLowerCase(Locale.ROOT);
        var list = data.products().stream().filter(p -> shows(p)
                && (name(p).toLowerCase(Locale.ROOT).contains(q) || p.name().toLowerCase(Locale.ROOT).contains(q))).toList();
        // 기본순 = the server order (category / sort set by the admin); the others only reorder on screen.
        Comparator<Product> order = switch (sort) {
            case DEFAULT -> null;
            case PRICE_LOW -> Comparator.<Product, BigDecimal>comparing(this::shownPrice);
            case PRICE_HIGH -> Comparator.<Product, BigDecimal>comparing(this::shownPrice).reversed();
            case NAME -> Comparator.<Product, String>comparing(this::name);
        };
        return order == null ? list : list.stream().sorted(order).toList();
    }
    /** The price a card shows in the current tab (same rule as ShopView.cardSells). */
    private BigDecimal shownPrice(Product p) {
        String v = tab == ShopView.Tab.SELL || p.buy().isEmpty() ? p.sell() : p.buy();
        try { return new BigDecimal(v); } catch (RuntimeException e) { return BigDecimal.ZERO; }
    }
    private Product selected() {
        return data == null ? null : visible().stream().filter(p -> p.id().equals(selected)).findFirst().orElse(null);
    }
    /** Trade direction the 전체 tab gives a product when it is picked without a specific button. */
    private static boolean defaultSelling(Product p) { return p.buy().isEmpty(); }
    private boolean tradable(Product p, boolean sell) { return !(sell ? p.sell() : p.buy()).isEmpty(); }

    /**
     * Re-validates after data, search or tab changes: the selection must be a visible product with
     * a trade direction it supports, the quantity resets when either changes, and the page stays in range.
     */
    private void normalize(boolean resetPage) {
        var list = visible();
        String before = selected;
        boolean sellBefore = selling;
        var current = selected();
        if (current == null) { current = list.isEmpty() ? null : list.getFirst(); selected = current == null ? "" : current.id(); }
        if (tab == ShopView.Tab.BUY) selling = false;
        else if (tab == ShopView.Tab.SELL) selling = true;
        else if (current != null && !tradable(current, selling)) selling = defaultSelling(current);
        if (quantity != null && (!selected.equals(before) || selling != sellBefore)) quantity.widget.setText("1");
        if (resetPage) page = 0;
        page = Math.clamp(page, 0, Math.max(0, (list.size() - 1) / ShopView.PER_PAGE));
    }
    private void choose(Product p, boolean sell) {
        boolean changed = !p.id().equals(selected) || sell != selling;
        selected = p.id();
        selling = sell;
        if (changed) quantity.widget.setText("1");
    }

    /** Leaving the number box: empty/0 becomes 1, more than possible becomes the maximum. */
    private void commitQuantity() {
        quantity.focus(false);
        int max = possible();
        int n;
        try { n = Integer.parseInt(quantity.text()); } catch (NumberFormatException e) { n = 0; }
        int fixed = n < 1 ? 1 : max > 0 ? Math.min(n, max) : n;
        if (!Integer.toString(fixed).equals(quantity.text())) quantity.widget.setText(Integer.toString(fixed));
    }
    private int number() { try { return ShopProtocol.quantity(quantity.text()); } catch (Exception e) { return 0; } }
    private int possible(Product p, boolean sell) {
        if (p == null || data == null || !tradable(p, sell)) return 0;
        var icon = icons.getOrDefault(p.id(), ItemStack.EMPTY);
        int max = Math.min(576, (icon.isEmpty() ? 64 : icon.getMaxCount()) * 9);
        if (sell) return Math.min(max, p.owned());
        if (p.exchange()) return Math.min(max, p.costHeld() / p.costCount());   // 교환: 가진 비용 아이템으로 살 수 있는 만큼
        try {
            var price = new BigDecimal(p.buy());
            if (price.signum() == 0) return max;
            if (data.balance().isEmpty()) return 0;
            return Math.min(max, new BigDecimal(data.balance()).divide(price, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(max)).intValue());
        } catch (Exception e) { return 0; }
    }
    private int possible() { return possible(selected(), selling); }
    private String total() {
        var p = selected();
        if (p == null || number() == 0 || !tradable(p, selling)) return "";
        try { return new BigDecimal(selling ? p.sell() : p.buy()).multiply(BigDecimal.valueOf(number())).stripTrailingZeros().toPlainString(); }
        catch (Exception e) { return ""; }
    }

    // ---- requests --------------------------------------------------------------------------------------------
    /** The only place a BUY/SELL request is made (footer button, row button on the chosen row). */
    private void trade() {
        if (data == null || busy) return;
        var p = selected();
        if (p == null || number() == 0 || number() > possible()) { notice("가능 수량 안에서 숫자를 입력해 주세요."); return; }
        sound(1.1f, .16f);
        send(selling ? ShopProtocol.SELL : ShopProtocol.BUY, p, receipt(p, selling, number(), total()));
    }
    private void send(int action, Product p) { send(action, p, null); }
    private void send(int action, Product p, Runnable onSuccess) {
        if (data == null || busy) return;
        busy = ShopClient.request(this, new Request(action, 1, data.session(), data.shop(), data.revision(),
                p == null ? "" : p.id(), quantity.text(), UUID.randomUUID().toString(), search.text()), onSuccess);
        if (!busy && action != ShopProtocol.CLOSE) notice("상점 서버 연결 또는 처리 상태를 확인해 주세요.");
    }

    @Override public void tick() {
        super.tick();
        if (search != null && !search.text().equals(sentSearch) && !busy && data != null) {
            long now = Util.getMeasuringTimeMs();
            if (searchAt == 0) searchAt = now + 450;
            if (now >= searchAt) {
                sentSearch = search.text();
                searchAt = 0;
                normalize(true);
                if (sentSearch.isEmpty() || visible().isEmpty()) send(ShopProtocol.OPEN, null);
            }
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------------
    private ShopView.Model model(double mx, double my) {
        var products = visible();
        var rows = new ArrayList<ShopView.Row>(products.size());
        for (var p : products)
            rows.add(new ShopView.Row(p.id(), name(p), lore.getOrDefault(p.id(), List.of()), p.buy(), p.sell(), p.owned(),
                    possible(p, false) > 0, possible(p, true) > 0, null, costName(p)));

        var all = data == null ? List.<Product>of() : data.products();
        boolean[] tabs = {!all.isEmpty(), all.stream().anyMatch(p -> !p.buy().isEmpty()), all.stream().anyMatch(p -> !p.sell().isEmpty())};
        tabs[tab.ordinal()] = true;
        String empty = data == null ? "상점 정보를 불러오는 중입니다" : all.isEmpty() ? "판매 중인 상품이 없습니다"
                : !search.text().isBlank() ? "검색 결과가 없습니다"
                : tab == ShopView.Tab.SELL ? "판매할 수 있는 상품이 없습니다" : "구매할 수 있는 상품이 없습니다";
        var portrait = portrait();
        float[] size = portrait == null ? null : size(portrait);
        int max = possible(), n = number();
        long age = Util.getMeasuringTimeMs() - noticeAt;
        float alpha = notice.isEmpty() || age > 5000 ? 0 : age > 4500 ? (5000 - age) / 500f : 1;
        var f = fit();
        return new ShopView.Model(data == null ? "상점" : data.title(), data == null || data.role().isBlank() ? "상점 주인" : data.role(),
                data == null || data.npcName().isBlank() ? "상점" : data.npcName(), size != null, size == null ? 1 : size[0] / size[1],
                data == null ? "" : data.balance(), tab, tabs, rows, page, selected, selling, empty, max, total(),
                n > 1, n < max, selected() != null && n > 0 && n <= max, canSwitch(), sort, sortOpen, busy, notice, alpha, mx, my, pressed,
                (float) f.y(height));
    }
    /** The footer 판매/구매 button: only in 전체 when the chosen product also trades the other way. */
    private boolean canSwitch() {
        var p = selected();
        return p != null && tab == ShopView.Tab.ALL && tradable(p, !selling);
    }
    private Identifier portrait() {
        if (data == null || data.portrait().isBlank()) return null;
        var id = Identifier.tryParse("magiccodex:textures/gui/dialogue/" + data.portrait() + ".png");
        return id != null && client.getResourceManager().getResource(id).isPresent() ? id : null;
    }
    /** PNG width/height from the IHDR header (cached), so any illustration keeps its own ratio. */
    private float[] size(Identifier id) {
        return SIZES.computeIfAbsent(id, k -> {
            try (InputStream in = client.getResourceManager().getResourceOrThrow(k).getInputStream()) {
                byte[] h = in.readNBytes(24);
                if (h.length < 24 || h[12] != 'I' || h[13] != 'H' || h[14] != 'D' || h[15] != 'R') return new float[]{1024, 1536};
                int w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
                int t = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
                return w > 0 && t > 0 ? new float[]{w, t} : new float[]{1024, 1536};
            } catch (Exception e) { return new float[]{1024, 1536}; }
        });
    }

    private final class GameCanvas implements ShopView.Canvas {
        final DrawContext c;
        GameCanvas(DrawContext c) { this.c = c; }
        Identifier face(ShopView.Face f) {
            return switch (f) { case TITLE -> FACE_TITLE; case SECTION -> FACE_SECTION; case BODY -> FACE_BODY; case LABEL -> BODY; case BOLD -> BOLD; };
        }
        public void image(String tex, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint) {
            int x0 = Math.round(x), y0 = Math.round(y);
            images.drawTexture(c, Identifier.of("magiccodex", tex), x0, y0, u, v, Math.round(x + w) - x0, Math.round(y + h) - y0, sw, sh, tw, th, tint);
        }
        public void line(float ax, float ay, float bx, float by, float thickness, int color) { HudMesh.line(c, ax, ay, bx, by, thickness, color); }
        public void gradient(float x, float y, float w, float h, int top, int bottom) { HudMesh.vertical(c, x, y, w, h, top, bottom); }
        public void chamfer(float x, float y, float w, float h, float r, int color) { ShopScreen.this.chamfer(c, x, y, w, h, r, color); }
        public void text(String s, float x, float cy, float size, int color, ShopView.Face f, boolean centered) {
            type.draw(c, s, x, cy, size, color, face(f), centered);
        }
        public float width(String s, float size, ShopView.Face f) { return type.width(s, size, face(f)); }
        public void fill(float x, float y, float w, float h, int color) { HudMesh.quad(c, x, y, w, h, color, color); }
        public void item(ShopView.Row r, float x, float y, float size) {
            var stack = icons.getOrDefault(r.id(), ItemStack.EMPTY);
            if (stack.isEmpty()) return;
            // Hi-res pack icons go through ItemIconTextures (smooth, mipmapped); others are the item's own model.
            c.getMatrices().push();
            c.getMatrices().translate(x, y, 20);
            c.getMatrices().scale(size / 16f, size / 16f, 1);
            c.drawItem(stack, 0, 0);
            c.getMatrices().pop();
        }
        public void portrait(float x, float y, float w, float h) {
            var id = ShopScreen.this.portrait();
            if (id == null) return;
            float[] s = size(id);
            int x0 = Math.round(x), y0 = Math.round(y);
            images.drawTexture(c, id, x0, y0, 0, 0, Math.round(x + w) - x0, Math.round(y + h) - y0, (int) s[0], (int) s[1], (int) s[0], (int) s[1], 0xFFFFFFFF);
        }
        public void searchField(float x, float y, float w, float h) { search.draw(c, x, y, w, h, "아이템 검색"); }
        public void quantityField(float x, float y, float w, float h) {
            // Direct entry: the number box is a text field (digits only). Hover/focus get a soft highlight,
            // and a click selects the whole number so typing replaces it.
            boolean focus = quantity.focus(), hover = in(lastMouseX, lastMouseY, x, y, w, h);
            if (focus || hover) HudMesh.quad(c, x + 2, y + 4, w - 4, h - 8, focus ? 0x3392E7F2 : 0x1FE7CE91, focus ? 0x3392E7F2 : 0x1FE7CE91);
            String v = quantity.text();
            int cursor = quantity.widget.getCursor(), anchor = ((school.magiccodex.mixin.TextFieldAccessor) quantity.widget).magiccodex$selectionEnd();
            if (focus && cursor != anchor && !v.isEmpty()) {
                float size = Math.min(29, 29 * (w - 24) / Math.max(1, type.width(v, 29, BOLD)));
                float left = x + (w - type.width(v, size, BOLD)) / 2;
                int lo = Math.min(cursor, anchor), hi = Math.max(cursor, anchor);
                float a = type.width(v.substring(0, lo), size, BOLD), b = type.width(v.substring(0, hi), size, BOLD);
                HudMesh.quad(c, left + a, y + 8, b - a, h - 16, 0x8865A5C5, 0x8865A5C5);
            }
            quantity.drawCentered(c, x, y, w, h, "1", WHITE);
        }
        public void pushLayer(float z) { c.getMatrices().push(); c.getMatrices().translate(0, 0, z); }
        public void popLayer() { c.getMatrices().pop(); }
    }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var f = fit();
        double mx = f.x(mouseX), my = f.y(mouseY);
        ItemStack hovered = ItemStack.EMPTY;
        lastMouseX = mx;
        lastMouseY = my;
        // Blur + darken the world behind the shop (vanilla menu blur; strength follows the
        // player's "Menu Background Blur" option).
        renderBackground(c, mouseX, mouseY, delta);
        start(c);
        try {
            var m = model(mx, my);
            ShopView.draw(new GameCanvas(c), m);
            var hit = ShopView.hit(m, mx, my);
            if (hit.kind() == ShopView.Kind.ICON) hovered = icons.getOrDefault(m.rows().get(hit.index()).id(), ItemStack.EMPTY);
            else if (m.chosen() != null && !m.sortOpen() && ShopView.in(mx, my, ShopView.footIconBox()))
                hovered = icons.getOrDefault(m.chosen().id(), ItemStack.EMPTY);
        } finally { end(c); }
        // Native item-tooltip entrypoint uses the existing rich MagicCodex renderer and Shift+wheel scrolling.
        if (!hovered.isEmpty() && entrance.ready()) c.drawItemTooltip(textRenderer, hovered, mouseX, mouseY);
    }

    // ---- input -----------------------------------------------------------------------------------------------
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        pressed = true;
        var f = fit();
        double mx = f.x(x), my = f.y(y);
        var m = model(mx, my);
        var hit = ShopView.hit(m, mx, my);
        if (hit.kind() != ShopView.Kind.SEARCH) search.focus(false);
        if (hit.kind() != ShopView.Kind.QUANTITY && quantity.focus()) commitQuantity();
        if (sortOpen) {                           // the open list takes the click: pick an order or just close
            sortOpen = false;
            if (hit.kind() == ShopView.Kind.SORT_OPTION) { sort = ShopView.Sort.values()[hit.index()]; normalize(true); }
            sound(1.1f, .14f);
            return true;
        }
        if (busy) return true;
        var list = visible();
        switch (hit.kind()) {
            case TAB -> {
                var next = ShopView.Tab.values()[hit.index()];
                if (m.tabEnabled()[hit.index()] && next != tab) { tab = next; normalize(true); sound(1.1f, .16f); }
            }
            case SEARCH -> search.click(mx, my);
            case SORT -> { sortOpen = true; sound(1.1f, .14f); }
            case CARD, ICON -> {
                var p = list.get(hit.index());
                boolean sell = tab == ShopView.Tab.SELL || tab == ShopView.Tab.ALL && (p.id().equals(selected) ? tradable(p, selling) ? selling : defaultSelling(p) : defaultSelling(p));
                choose(p, sell);
                sound(1.1f, .14f);
            }
            case PREV -> { if (page > 0) { page--; sound(1.1f, .14f); } }
            case NEXT -> { if (page < m.pages() - 1) { page++; sound(1.1f, .14f); } }
            case MINUS -> { if (number() > 1) { quantity.widget.setText(Integer.toString(number() - 1)); sound(1.1f, .14f); } }
            case PLUS -> { if (number() < possible()) { quantity.widget.setText(Integer.toString(Math.max(1, number() + 1))); sound(1.1f, .14f); } }
            case QUANTITY -> {
                if (!quantity.focus()) {          // first click: focus and select all, so typing replaces the number
                    quantity.focus(true);
                    quantity.widget.setCursorToEnd(false);
                    quantity.widget.setSelectionEnd(0);
                } else quantity.click(Math.clamp(mx, quantity.x, quantity.x + quantity.w - .1), Math.clamp(my, quantity.y, quantity.y + quantity.h - .1));
            }
            case SWITCH -> { var p = selected(); if (m.switchOk() && p != null) { choose(p, !selling); sound(1.1f, .14f); } }
            case TRADE -> trade();
            default -> {}
        }
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) pressed = false;
        return super.mouseReleased(x, y, button);
    }
    /** Wheel over the cards turns pages (one notch = one page). */
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (sortOpen || vertical == 0) return true;
        int last = Math.max(0, (visible().size() - 1) / ShopView.PER_PAGE);
        page = Math.clamp(page - (int) Math.signum(vertical), 0, last);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && sortOpen) { sortOpen = false; return true; }
        if ((key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && (search.focus() || quantity.focus())) {
            search.focus(false);
            if (quantity.focus()) commitQuantity();
            return true;
        }
        // A focused field consumes every key so letters typed into it never reach other key handlers.
        if (search.focus()) { search.key(key, scan, mods); return true; }
        if (quantity.focus()) { quantity.key(key, scan, mods); return true; }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char c, int mods) {
        return search.focus() && search.typed(c, mods) || quantity.focus() && quantity.typed(c, mods) || super.charTyped(c, mods);
    }
    @Override public void close() {
        if (data != null && !busy) send(ShopProtocol.CLOSE, null);
        ShopClient.detach(this);
        client.setScreen(null);
    }
}
