package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.*;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import school.magiccodex.protocol.MileageProtocol;
import school.magiccodex.protocol.MileageProtocol.*;

/**
 * 마일리지 상점 window (2026-10-24). One screen, two modes: the player shop and the admin (OP) management window, both on
 * the owner's shop_panel.png. Layout/drawing/hit testing live in {@link MileageView}; this class owns the server data,
 * tab/page/selection, the input fields and the requests. Messages use the shared notification card (bottom right).
 */
public final class MileageScreen extends SocialScreen {
    private static final Identifier FACE_TITLE = Identifier.of("magiccodex", "title"), FACE_SECTION = Identifier.of("magiccodex", "section"),
            FACE_BODY = Identifier.of("magiccodex", "body");
    /** Purchase receipts by operation id: shown when the result arrives, even if the window was closed meanwhile. */
    private static final Map<Long, Runnable> RECEIPTS = new HashMap<>();

    private final boolean admin;
    private State data;
    private Map<String, ItemStack> icons = Map.of();
    private Input qty, name, desc, price;
    private int tab, page, editCategory;
    private boolean editEnabled = true, pressed;
    private String selected = "";
    private long pending, busyAt, armedAt;
    /** Admin inventory picker (owner 2026-10-24: 아이템은 내 인벤토리에서 선택). picked = chosen slot for a new product. */
    private boolean picker;
    private int picked = -1;
    private String autoName = "";

    MileageScreen(boolean admin) { super(admin ? "마일리지 상점 관리" : "마일리지 상점"); this.admin = admin; }
    boolean admin() { return admin; }

    @Override SocialLayout.Fit fit() {
        float s = Math.min(width * .98f / MileageView.W, height * .98f / MileageView.H);
        return new SocialLayout.Fit((width - MileageView.W * s) / 2, (height - MileageView.H * s) / 2, s);
    }
    @Override protected void init() {
        super.init();
        qty = new Input("수량", 2, qty == null ? "1" : qty.text());
        qty.widget.setTextPredicate(v -> v.matches("[0-9]{0,2}"));
        name = new Input("상품 이름", MileageProtocol.MAX_NAME, name == null ? "" : name.text());
        desc = new Input("설명", MileageProtocol.MAX_DESCRIPTION, desc == null ? "" : desc.text());
        price = new Input("가격", 10, price == null ? "0" : price.text());
        price.widget.setTextPredicate(v -> v.matches("[0-9]{0,10}"));
    }

    // ---- server data -----------------------------------------------------------------------------------------
    void onState(State s) {
        data = s;
        busy = false;
        var map = new HashMap<String, ItemStack>();
        for (var p : s.products()) map.put(p.id(), decode(p.preview()));
        icons = Map.copyOf(map);
        if (!s.notice().isEmpty()) SharedNotifications.offer("mileage:notice:" + Util.getMeasuringTimeMs(), "system", s.notice(), "");
        String before = selected;
        var want = s.select().isEmpty() ? null : product(s.select());
        if (want != null) { tab = want.category(); selected = want.id(); }
        else if (!selected.equals("#new") && product(selected) == null) selected = "";
        if (selected.isEmpty()) { var list = list(); if (!list.isEmpty() && !list.getFirst().add()) selected = list.getFirst().id(); }
        pageToSelection();
        boolean changed = !selected.equals(before);
        if (changed) qty.widget.setText("1");
        if (admin && (changed || !s.notice().isEmpty() && product(selected) != null)) loadEdit();
    }
    void onResult(Result r) {
        if (data != null) data = new State(data.kind(), data.admin(), r.balance(), data.products(), "", "");
        if (r.operation() != pending) return;
        pending = 0;
        busy = false;
        lateResult(r);
        if (r.ok()) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.25f, .30f));
        commitQuantity();
    }
    /** A purchase result: success card with the item (receipt captured at request time) or the failure reason. */
    static void lateResult(Result r) {
        Runnable receipt = RECEIPTS.remove(r.operation());
        if (r.ok()) { if (receipt != null) receipt.run(); if (!r.notice().isEmpty()) SharedNotifications.offer("mileage:late:" + r.operation(), "system", r.notice(), ""); }
        else if (receipt != null || !r.notice().isEmpty()) SharedNotifications.offer("mileage:fail:" + r.operation(), "warning", r.notice().isEmpty() ? "구매하지 못했어요" : r.notice(), "");
    }

    private ItemStack decode(byte[] b) {
        if (b.length == 0 || client.world == null) return ItemStack.EMPTY;
        try {
            return ItemStack.fromNbt(client.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }
    private Product product(String id) {
        if (data == null || id == null) return null;
        for (var p : data.products()) if (p.id().equals(id)) return p;
        return null;
    }

    // ---- list / paging ---------------------------------------------------------------------------------------
    private List<MileageView.Card> list() {
        var out = new ArrayList<MileageView.Card>();
        if (data != null) for (var p : data.products()) if (p.category() == tab) out.add(new MileageView.Card(p.id(), p.name(), MileageProtocol.amount(p.price()), p.enabled(), false));
        if (admin && data != null) out.add(new MileageView.Card("#new", "", "", true, true));
        return out;
    }
    private int pages() { return Math.max(1, (list().size() + MileageView.PER_PAGE - 1) / MileageView.PER_PAGE); }
    private List<MileageView.Card> pageCards() {
        var all = list();
        int from = Math.min(all.size(), page * MileageView.PER_PAGE);
        return all.subList(from, Math.min(all.size(), from + MileageView.PER_PAGE));
    }
    private void pageToSelection() {
        var all = list();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(selected)) { page = i / MileageView.PER_PAGE; return; }
        page = Math.clamp(page, 0, pages() - 1);
    }
    private void select(String id) {
        if (id.equals(selected)) return;
        selected = id;
        armedAt = 0;
        qty.widget.setText("1");
        if (admin) loadEdit();
    }
    private void switchTab(int t) {
        if (t == tab) return;
        tab = t; page = 0; selected = "";
        var all = list();
        if (!all.isEmpty() && !all.getFirst().add()) selected = all.getFirst().id();
        armedAt = 0;
        qty.widget.setText("1");
        if (admin) loadEdit();
    }

    // ---- shop: quantity / total ------------------------------------------------------------------------------
    private int maxQuantity(Product p) {
        if (p == null || data == null) return 0;
        var stack = icons.getOrDefault(p.id(), ItemStack.EMPTY);
        int max = Math.min(MileageProtocol.MAX_QUANTITY, 9 * (stack.isEmpty() ? 64 : Math.max(1, stack.getMaxCount())));
        if (p.price() > 0) max = (int) Math.min(max, data.balance() / p.price());
        return max;
    }
    private int number() { try { return Integer.parseInt(qty.text()); } catch (NumberFormatException e) { return 0; } }
    private void commitQuantity() {
        if (qty == null) return;
        qty.focus(false);
        int n = number(), max = maxQuantity(product(selected));
        int fixed = n < 1 ? 1 : max > 0 ? Math.min(n, max) : n;
        if (!Integer.toString(fixed).equals(qty.text())) qty.widget.setText(Integer.toString(fixed));
    }
    private void buy() {
        var p = product(selected);
        if (data == null || p == null || busy) return;
        int n = number(), max = maxQuantity(p);
        long total = MileageProtocol.total(p.price(), Math.max(1, n));
        if (n < 1) { warn("수량을 입력해 주세요"); return; }
        if (total < 0 || total > data.balance()) { warn("마일리지가 부족해요"); return; }
        if (n > max) { warn("한 번에 " + max + "개까지 살 수 있어요"); return; }
        long op = MileageClient.buy(p.id(), n, p.price());
        if (op == 0) { warn("서버에 연결할 수 없어요"); return; }
        sound(1.1f, .16f);
        busy = true; busyAt = Util.getMeasuringTimeMs(); pending = op;
        ItemStack icon = icons.getOrDefault(p.id(), ItemStack.EMPTY).copy();
        String title = p.name() + " ×" + n + " 구매 완료", body = "우편함으로 보냈어요 · 마일리지 " + MileageProtocol.amount(total) + " 사용";
        RECEIPTS.put(op, () -> SharedNotifications.item("mileage:" + op, title, body, icon));
        if (RECEIPTS.size() > 32) RECEIPTS.remove(RECEIPTS.keySet().iterator().next());
    }
    private static void warn(String m) { SharedNotifications.offer("mileage:warn:" + m, "warning", m, ""); }

    // ---- admin: editor ---------------------------------------------------------------------------------------
    private void loadEdit() {
        var p = product(selected);
        armedAt = 0;
        if (p != null) {
            name.widget.setText(p.name()); desc.widget.setText(p.description()); price.widget.setText(Long.toString(p.price()));
            editCategory = p.category(); editEnabled = p.enabled();
        } else if (selected.equals("#new")) {
            name.widget.setText(""); desc.widget.setText(""); price.widget.setText("0");
            editCategory = tab; editEnabled = true;
        }
        picked = -1; autoName = ""; picker = false;
        name.widget.setCursorToStart(false); desc.widget.setCursorToStart(false);
    }
    /** Player inventory slot 0..35 (0..8 hotbar, 9..35 main — the same numbers the server reads). */
    private ItemStack slotStack(int slot) {
        if (client.player == null || slot < 0 || slot >= MileageProtocol.SLOTS) return ItemStack.EMPTY;
        return client.player.getInventory().getStack(slot);
    }
    private void openPicker() { unfocus(); picker = true; sound(1.1f, .16f); }
    /** A slot was clicked in the picker: a new product takes it (name filled from the item if still untouched); an existing one is replaced now. */
    private void pick(int slot) {
        var stack = slotStack(slot);
        if (stack.isEmpty()) return;
        picker = false;
        if (product(selected) != null) { adminSend(MileageProtocol.Request.replace(selected, slot)); return; }
        picked = slot;
        sound(1.1f, .16f);
        if (name.text().isBlank() || name.text().equals(autoName)) {
            String n = ItemTooltipRenderer.localName(stack, stack.getName()).getString().strip();
            autoName = n.length() > MileageProtocol.MAX_NAME ? n.substring(0, MileageProtocol.MAX_NAME) : n;
            name.widget.setText(autoName);
            name.widget.setCursorToStart(false);
        }
    }
    private long priceValue() { try { return Long.parseLong(price.text()); } catch (NumberFormatException e) { return -1; } }
    private boolean editValid() { return !name.text().isBlank() && priceValue() >= 0 && priceValue() <= MileageProtocol.MAX_PRICE; }
    private void adminSend(MileageProtocol.Request r) {
        if (busy) return;
        if (!MileageClient.send(r)) { warn("서버에 연결할 수 없어요"); return; }
        busy = true; busyAt = Util.getMeasuringTimeMs();
        sound(1.1f, .16f);
    }
    private void save() {
        if (!editValid()) { warn(name.text().isBlank() ? "상품 이름을 입력해 주세요" : "가격은 0 ~ " + MileageProtocol.amount(MileageProtocol.MAX_PRICE) + " 사이로 입력해 주세요"); return; }
        boolean add = selected.equals("#new");
        if (add && slotStack(picked).isEmpty()) { picked = -1; warn("인벤토리에서 아이템을 골라 주세요"); openPicker(); return; }
        var e = new Edit(add ? "" : selected, editCategory, name.text().strip(), desc.text().strip(), priceValue(), editEnabled);
        adminSend(add ? MileageProtocol.Request.add(e, picked) : MileageProtocol.Request.save(e));
    }
    private void unfocus() { qty.focus(false); name.focus(false); desc.focus(false); price.focus(false); }

    @Override public void tick() {
        super.tick();
        if (busy && Util.getMeasuringTimeMs() - busyAt > 8000) { busy = false; if (pending != 0) { RECEIPTS.remove(pending); pending = 0; warn("응답이 늦어요. 우편함과 마일리지를 확인해 주세요"); } }
        if (armedAt != 0 && Util.getMeasuringTimeMs() - armedAt > 3000) armedAt = 0;
    }

    // ---- drawing ---------------------------------------------------------------------------------------------
    private MileageView.Model model(double mx, double my) {
        var p = product(selected);
        var cards = pageCards();
        String empty = data == null ? "불러오는 중이에요" : "판매 중인 상품이 없어요";
        if (!admin) {
            int n = number(), max = maxQuantity(p);
            long total = p == null ? -1 : MileageProtocol.total(p.price(), Math.max(1, n));
            boolean can = p != null && !busy && n >= 1 && n <= max && total >= 0 && total <= data.balance();
            return new MileageView.Model(false, data == null ? "-" : MileageProtocol.amount(data.balance()), 0, tab, cards, page, pages(), selected,
                    p == null ? "" : p.name(), p == null ? "" : p.description(), total < 0 ? "" : MileageProtocol.amount(total),
                    n > 1, p != null && n < max, can, busy ? "처리 중…" : "구매하기", 0, true, true, false, false, false, false, new boolean[0], -1, empty, busy, mx, my, pressed);
        }
        boolean[] filled = new boolean[MileageProtocol.SLOTS];
        for (int i = 0; i < filled.length; i++) filled[i] = !slotStack(i).isEmpty();
        boolean existing = p != null;
        int index = -1, size = 0;
        if (existing) { var all = list(); for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(selected)) index = i; size = all.size() - 1; }
        return new MileageView.Model(true, "", data == null ? 0 : data.products().size(), tab, cards, page, pages(), selected,
                "", "", "", false, false, !busy && (existing || selected.equals("#new") && picked >= 0) && editValid(),
                busy ? "처리 중…" : existing ? "저장하기" : "추가하기", editCategory, editEnabled, existing, armedAt != 0,
                existing && index > 0, existing && index >= 0 && index < size - 1, picker, filled, picked, empty, busy, mx, my, pressed);
    }

    private final class GameCanvas implements MileageView.Canvas {
        final DrawContext c;
        GameCanvas(DrawContext c) { this.c = c; }
        Identifier face(MileageView.Face f) {
            return switch (f) { case TITLE -> FACE_TITLE; case SECTION -> FACE_SECTION; case BODY -> FACE_BODY; case LABEL -> BODY; case BOLD -> BOLD; };
        }
        public void image(String tex, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint) {
            int x0 = Math.round(x), y0 = Math.round(y);
            images.drawTexture(c, Identifier.of("magiccodex", tex), x0, y0, u, v, Math.round(x + w) - x0, Math.round(y + h) - y0, sw, sh, tw, th, tint);
        }
        public void text(String s, float x, float cy, float size, int color, MileageView.Face f, boolean centered) { type.draw(c, s, x, cy, size, color, face(f), centered); }
        public float width(String s, float size, MileageView.Face f) { return type.width(s, size, face(f)); }
        public float[] ink(String s, float size, MileageView.Face f) { return type.ink(s, size, face(f)); }
        public void gradient(float x, float y, float w, float h, int left, int right) { HudMesh.quad(c, x, y, w, h, left, right); }
        public void line(float ax, float ay, float bx, float by, float t, int color) { HudMesh.line(c, ax, ay, bx, by, t, color); }
        public void polygon(float[] p, int color) {
            var m = c.getMatrices().peek().getPositionMatrix();
            float cx = 0, cy = 0; int n = p.length / 2;
            for (int i = 0; i < n; i++) { cx += p[2 * i]; cy += p[2 * i + 1]; }
            float fx = cx / n, fy = cy / n;
            c.draw(q -> {
                var v = q.getBuffer(RenderLayer.getGui());
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    v.vertex(m, fx, fy, 0).color(color); v.vertex(m, p[2 * j], p[2 * j + 1], 0).color(color);
                    v.vertex(m, p[2 * i], p[2 * i + 1], 0).color(color); v.vertex(m, fx, fy, 0).color(color);
                }
            });
        }
        public void item(String key, float x, float y, float size) {
            var stack = key.startsWith("#slot:") ? slotStack(Integer.parseInt(key.substring(6))) : icons.getOrDefault(key, ItemStack.EMPTY);
            if (stack.isEmpty()) return;
            c.getMatrices().push();
            c.getMatrices().translate(x, y, 20);
            c.getMatrices().scale(size / 16f, size / 16f, 1);
            c.drawItem(stack, 0, 0);
            c.getMatrices().pop();
        }
        public void field(String key, float x, float y, float w, float h) {
            switch (key) {
                case "qty" -> qty.drawCentered(c, x, y, w, h, "1", WHITE);
                case "name" -> name.draw(c, x, y, w, h, "상품 이름");
                case "desc" -> desc.draw(c, x, y, w, h, "설명 (선택)");
                case "price" -> price.draw(c, x, y, w, h, "0");
                default -> {}
            }
        }
        public void pushLayer(float z) { c.getMatrices().push(); c.getMatrices().translate(0, 0, z); }
        public void popLayer() { c.getMatrices().pop(); }
    }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var f = fit();
        double mx = f.x(mouseX), my = f.y(mouseY);
        ItemStack hovered = ItemStack.EMPTY;
        renderBackground(c, mouseX, mouseY, delta);
        start(c);
        try {
            var m = model(mx, my);
            MileageView.draw(new GameCanvas(c), m);
            var hit = MileageView.hit(m, mx, my);
            if (hit.kind() == MileageView.Kind.CARD) {
                float s = MileageView.CARD_ICON, x = MileageView.cardX(hit.index()) + MileageView.CARD_W / 2 - s / 2, y = MileageView.cardY(hit.index()) + MileageView.CARD_ICON_Y;
                var card = m.cards().get(hit.index());
                if (!card.add() && MileageView.in(mx, my, x, y, s, s)) hovered = icons.getOrDefault(card.id(), ItemStack.EMPTY);
            }
            if (hit.kind() == MileageView.Kind.PICK) hovered = slotStack(hit.index());
        } finally { end(c); }
        if (!hovered.isEmpty() && entrance.ready()) c.drawItemTooltip(textRenderer, hovered, mouseX, mouseY);
    }

    // ---- input -----------------------------------------------------------------------------------------------
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        pressed = true;
        var f = fit();
        double mx = f.x(x), my = f.y(y);
        var m = model(mx, my);
        var hit = MileageView.hit(m, mx, my);
        if (hit.kind() == MileageView.Kind.CLOSE) { sound(1.1f, .16f); close(); return true; }
        if (hit.kind() != MileageView.Kind.QUANTITY && qty.focus()) commitQuantity();
        if (hit.kind() != MileageView.Kind.NAME) name.focus(false);
        if (hit.kind() != MileageView.Kind.DESC) desc.focus(false);
        if (hit.kind() != MileageView.Kind.PRICE) price.focus(false);
        if (hit.kind() != MileageView.Kind.DELETE) armedAt = 0;
        switch (hit.kind()) {
            case REFRESH -> { if (!busy) { sound(1.1f, .14f); MileageClient.send(MileageProtocol.Request.of(admin ? MileageProtocol.ADMIN_OPEN : MileageProtocol.OPEN)); } }
            case TAB -> { if (hit.index() != tab) { switchTab(hit.index()); sound(1.1f, .16f); } }
            case CARD -> {
                var card = m.cards().get(hit.index());
                select(card.id());
                if (card.add()) openPicker(); else sound(1.1f, .14f);
            }
            case PICK -> pick(hit.index());
            case PICK_CLOSE -> { picker = false; sound(1.1f, .14f); }
            case PREV -> { if (page > 0) { page--; sound(1.1f, .14f); } }
            case NEXT -> { if (page < pages() - 1) { page++; sound(1.1f, .14f); } }
            case MINUS -> { if (number() > 1) { qty.widget.setText(Integer.toString(number() - 1)); sound(1.1f, .14f); } }
            case PLUS -> { if (number() < maxQuantity(product(selected))) { qty.widget.setText(Integer.toString(Math.max(1, number() + 1))); sound(1.1f, .14f); } }
            case QUANTITY -> {
                if (!qty.focus()) { qty.focus(true); qty.widget.setCursorToEnd(false); qty.widget.setSelectionEnd(0); }
            }
            case ACTION -> { if (admin) save(); else buy(); }
            case NAME -> name.click(mx, my);
            case DESC -> desc.click(mx, my);
            case PRICE -> price.click(mx, my);
            case CATEGORY -> { editCategory = hit.index(); sound(1.1f, .14f); }
            case ENABLED -> { editEnabled = hit.index() == 0; sound(1.1f, .14f); }
            case MOVE -> {
                if (product(selected) != null && (hit.index() == 0 ? m.moveEarlier() : m.moveLater()))
                    adminSend(MileageProtocol.Request.move(selected, hit.index() == 1));
            }
            case REPLACE -> { if (!busy && (product(selected) != null || selected.equals("#new"))) openPicker(); }
            case DELETE -> {
                if (product(selected) == null) break;
                if (armedAt == 0) { armedAt = Util.getMeasuringTimeMs(); sound(.8f, .16f); }
                else { armedAt = 0; adminSend(MileageProtocol.Request.delete(selected)); }
            }
            default -> {}
        }
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) pressed = false;
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (picker) return true;
        int next = Math.clamp(page - (int) Math.signum(vertical), 0, pages() - 1);
        if (next != page) { page = next; sound(1.1f, .12f); }
        return true;
    }
    private Input focused() {
        for (var i : new Input[]{qty, name, desc, price}) if (i != null && i.focus()) return i;
        return null;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (picker && key == GLFW.GLFW_KEY_ESCAPE) { picker = false; return true; }
        var f = focused();
        if (f != null && (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            if (f == qty) commitQuantity(); else unfocus();
            return true;
        }
        if (f != null && key == GLFW.GLFW_KEY_TAB && admin) {   // 이름 → 설명 → 가격
            unfocus();
            (f == name ? desc : f == desc ? price : name).focus(true);
            return true;
        }
        if (f != null) { f.key(key, scan, mods); return true; }   // a focused field eats every key (no shortcut leaks)
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char ch, int mods) {
        var f = focused();
        return f != null && f.typed(ch, mods) || super.charTyped(ch, mods);
    }
    @Override public void close() {
        MileageClient.close();
        client.setScreen(null);
    }
}
