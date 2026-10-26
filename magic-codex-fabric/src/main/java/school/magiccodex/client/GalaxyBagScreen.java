package school.magiccodex.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.GalaxyBagLayout.Element;
import school.magiccodex.client.GalaxyBagLayout.Fit;
import school.magiccodex.client.GalaxyBagLayout.Kind;

/**
 * 아공간 배낭 — the survival inventory (owner reference 2026-10-24).
 *
 * It is the vanilla player inventory with a different face: same {@link PlayerScreenHandler}, same slots, same
 * click/drag/shift/number-key/drop handling and server-checked crafting. Only drawing and "which slot is under
 * the mouse" change. The 2x2 crafting panel is hidden until the crafting button (below the equipment button)
 * opens it; it slides in beside the bag. The shirt button opens the MagicCodex equipment window.
 *
 * Pages: 27 main slots per page; 2..3 are opened with the 가방 확장권 item. Turning to an opened page asks the
 * server to swap the 27 main slots with that stored page (hotbar stays). Turning to a closed page only shows the
 * locked view locally (nothing is swapped).
 */
public final class GalaxyBagScreen extends HandledScreen<PlayerScreenHandler> implements RecipeBookProvider {
    private static final String DIR = "textures/gui/bag/";
    static final Identifier BAG = tex("bag_galaxy_border"), BAG_FILL = tex("bag_galaxy_fill"), PANEL = tex("craft_panel_border"),
            PANEL_FILL = tex("craft_panel_fill"), SHIRT = tex("equipment_shirt"), TABLE = tex("crafting_table"),
            BOOK = tex("recipe_book"), PADLOCK = tex("locked_page_padlock");
    static final int ICON_PNG = 256, PADLOCK_PNG = 384, BAG_FILL_W = 908, BAG_FILL_H = 433, PANEL_FILL_W = 554, PANEL_FILL_H = 710;
    private static Identifier tex(String name) { return Identifier.of("magiccodex", DIR + name + ".png"); }

    private static final Identifier SERIF = Identifier.of("magiccodex", "title"), BOLD = Identifier.of("magiccodex", "hud_bold"),
            MEDIUM = Identifier.of("magiccodex", "label"), BODY = Identifier.of("magiccodex", "body");

    // Colours sampled from the reference: navy-violet glass, lavender rings, white text.
    private static final int FILL = 0xE61E2152,   // 90%: barely see-through (owner 2026-10-24)
            RING = 0xB8C7B2FF, RING_HOVER = 0xFFEDE4FF, RING_DIM = 0x55C7B2FF,
            DOT = 0xE6F2EEFF, DOT_DIM = 0x66F2EEFF, TEXT = 0xFFF4F1FF, TEXT_SOFT = 0xD9DCD5F5, TEXT_HINT = 0x99C9C2E6,
            RULE = 0xA6C7B2FF, ACCENT = 0xFF8F6BFF, GLOW = 0x669C7BFF, CARD_FILL = 0x3A2A2470, CARD_RING = 0x99A891F0,
            CARD_SELECTED = 0xFFC9B6FF, VIOLET = 0xFF7C5CF0;

    /** Crafting panel and recipe section stay as the player left them (session only). */
    private static boolean panelWanted, recipesWanted = true, craftableOnlyWanted = true;

    private final Map<Long, Integer> slotsByPosition = new HashMap<>();
    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private final GalaxyRecipes recipes = new GalaxyRecipes();
    private Fit fit = new Fit(0, 0, 1);
    private long lastFrame;
    private float panel, recipeFade = 1;
    private int viewPage = 1;           // page shown; > unlocked means the locked view
    private int hoveredId = -1, hoveredCard = -1, selectedCard = -1;
    private boolean swallowRelease, searchFocused, recipesDirty = true;
    private String search = "";
    private int lastChangeCount = -1;
    private long lastGrid = Long.MIN_VALUE;
    private RecipeDisplay ghost;
    private long ghostAt;

    public GalaxyBagScreen(PlayerEntity player) {
        super(player.playerScreenHandler, player.getInventory(), Text.literal("아공간 배낭"));
        panel = panelWanted ? 1 : 0;
        recipes.craftableOnly(craftableOnlyWanted);
    }

    /** True when a vanilla survival inventory should be shown as this screen instead. */
    public static boolean replaces(MinecraftClient client, Screen screen) {
        return screen != null && screen.getClass() == InventoryScreen.class && client.player != null
                && client.interactionManager != null && !client.interactionManager.hasCreativeInventory();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        super.init();
        slotsByPosition.clear();
        for (Slot slot : handler.slots) slotsByPosition.putIfAbsent(key(slot.x, slot.y), slot.id);
        lastFrame = 0;
        focusedSlot = null;
        viewPage = BagPagesClient.active();
        BagPagesClient.helloIfNeeded();   // #101: never waits; the bag draws from the last state at once
        recipesDirty = true;
        updateFrame();
    }

    @Override
    public void handledScreenTick() {
        super.handledScreenTick();
        if (client == null || client.player == null) return;
        if (client.interactionManager != null && client.interactionManager.hasCreativeInventory()) {
            client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(client.player,
                    client.player.networkHandler.getEnabledFeatures(), client.options.getOperatorItemsTab().getValue()));
            return;
        }
        // Follow the server's page unless the player is looking at a locked page.
        if (viewPage <= BagPagesClient.unlocked() && !BagPagesClient.switching()) viewPage = BagPagesClient.active();
        if (viewPage > BagPagesClient.pages()) viewPage = BagPagesClient.active();
        // Ghost ends once something real is in the grid (vanilla clears it on the next slot change).
        if (ghost != null) for (int i = 0; i < 4; i++) if (handler.getSlot(GalaxyBagLayout.INPUT_START + i).hasStack()) { ghost = null; break; }
        // #101: the recipe list (whole recipe book x RecipeFinder) is rebuilt only when something it reads changed:
        // inventory, the 2x2 grid, or the server's recipe book (refreshRecipeBook). No more rebuild every 10 ticks.
        int changes = client.player.getInventory().getChangeCount();
        long grid = gridSignature();
        if (changes != lastChangeCount || grid != lastGrid) { lastChangeCount = changes; lastGrid = grid; recipesDirty = true; }
        if (panelWanted && recipesWanted && recipesDirty) {
            recipesDirty = false;
            recipes.rebuild(client, handler);
        }
    }

    /** Cheap fingerprint of the 2x2 grid (grid slots are not in the player inventory's change count). */
    private long gridSignature() {
        long h = 1;
        for (int i = 0; i < 4; i++) {
            ItemStack stack = handler.getSlot(GalaxyBagLayout.INPUT_START + i).getStack();
            h = h * 1_000_003L + (stack.isEmpty() ? 0 : System.identityHashCode(stack) * 131L + stack.getCount());
        }
        return h;
    }

    @Override public void refreshRecipeBook() { recipesDirty = true; }

    @Override
    public void onCraftFailed(RecipeDisplay display) {
        ghost = display;
        ghostAt = Util.getMeasuringTimeMs();
    }

    // ------------------------------------------------------------------ frame state

    private static long key(int x, int y) { return ((long) x << 32) ^ (y & 0xFFFFFFFFL); }

    private void updateFrame() {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        panel = GalaxyBagLayout.approach(panel, panelWanted, dt);
        recipeFade = recipesWanted ? Math.min(1, recipeFade + dt / 0.18f) : Math.max(0, recipeFade - dt / 0.18f);
        fit = GalaxyBagLayout.fit(width, height);
    }

    private boolean lockedView() { return viewPage > BagPagesClient.unlocked(); }

    private boolean visible(Element e) {
        return switch (e.kind()) {
            case MAIN -> !lockedView();
            case HOTBAR -> true;   // 핫바는 잠긴 쪽에서도 그대로 (2026-10-26 오너)
            case INPUT, RESULT -> GalaxyBagLayout.panelInteractive(panel);
        };
    }

    // ------------------------------------------------------------------ input mapping

    @Override
    protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
        Integer id = width == 16 && height == 16 ? slotsByPosition.get(key(x, y)) : null;
        if (id == null) return super.isPointWithinBounds(x, y, width, height, pointX, pointY);
        Element e = GalaxyBagLayout.element(id);
        if (e == null || !visible(e)) return false;
        return GalaxyBagLayout.hit(e, fit.localX(pointX), fit.localY(pointY), panel);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        double mx = fit.localX(mouseX), my = fit.localY(mouseY);
        return !GalaxyBagLayout.insideBag(mx, my, panel) && !GalaxyBagLayout.insidePanel(mx, my, panel);
    }

    private double bx(double mx) { return mx - GalaxyBagLayout.bagShift(panel); }
    private double px(double mx) { return mx - GalaxyBagLayout.panelShift(panel); }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = fit.localX(mouseX), my = fit.localY(mouseY), b = bx(mx), p = px(mx);
        boolean panelOn = GalaxyBagLayout.panelInteractive(panel);
        searchFocused = false;
        if (button == 0) {
            if (GalaxyBagLayout.circle(b, my, GalaxyBagLayout.EQUIP_X, GalaxyBagLayout.EQUIP_Y, GalaxyBagLayout.ROUND_BTN_R)) {
                click(1.1f);
                // Close this handler normally (grid items go back like vanilla), then open the equipment window.
                close();
                EquipmentClient.open();
                return true;
            }
            if (GalaxyBagLayout.circle(b, my, GalaxyBagLayout.CRAFT_BTN_X, GalaxyBagLayout.CRAFT_BTN_Y, GalaxyBagLayout.ROUND_BTN_R)) {
                return toggle(() -> { if (panelWanted) closePanel(); else { panelWanted = true; recipesDirty = true; } });
            }
            if (BagPagesClient.pages() > 1 && GalaxyBagLayout.circle(b, my, GalaxyBagLayout.PAGER_PREV_X, GalaxyBagLayout.PAGER_Y, GalaxyBagLayout.PAGER_R + 3))
                return toggle(() -> turnPage(-1));
            if (BagPagesClient.pages() > 1 && GalaxyBagLayout.circle(b, my, GalaxyBagLayout.PAGER_NEXT_X, GalaxyBagLayout.PAGER_Y, GalaxyBagLayout.PAGER_R + 3))
                return toggle(() -> turnPage(1));
            if (panelOn) {
                if (GalaxyBagLayout.circle(p, my, GalaxyBagLayout.CLOSE_X, GalaxyBagLayout.CLOSE_Y, GalaxyBagLayout.CLOSE_R + 2))
                    return toggle(this::closePanel);
                float half = GalaxyBagLayout.BOOK_SIZE / 2;
                if (GalaxyBagLayout.rect(p, my, GalaxyBagLayout.BOOK_X - half, GalaxyBagLayout.BOOK_Y - half, GalaxyBagLayout.BOOK_X + half, GalaxyBagLayout.BOOK_Y + half))
                    return toggle(() -> { recipesWanted = !recipesWanted; recipesDirty = true; });
                if (recipesWanted && recipeFade > 0.6f) {
                    if (GalaxyBagLayout.rect(p, my, GalaxyBagLayout.SEARCH_X0, GalaxyBagLayout.SEARCH_Y0, GalaxyBagLayout.SEARCH_X1, GalaxyBagLayout.SEARCH_Y1)) {
                        searchFocused = true; swallowRelease = true; return true;
                    }
                    if (GalaxyBagLayout.rect(p, my, GalaxyBagLayout.TOGGLE_X0 - 4, GalaxyBagLayout.TOGGLE_Y0 - 4, GalaxyBagLayout.TOGGLE_X1 + 4, GalaxyBagLayout.TOGGLE_Y1 + 4))
                        return toggle(() -> { craftableOnlyWanted = !craftableOnlyWanted; recipes.craftableOnly(craftableOnlyWanted); selectedCard = -1; });
                    if (recipes.pages() > 1 && GalaxyBagLayout.circle(p, my, GalaxyBagLayout.RECIPE_PREV_X, GalaxyBagLayout.RECIPE_PAGER_Y, GalaxyBagLayout.RECIPE_PAGER_R + 3))
                        return toggle(() -> { recipes.turn(-1); selectedCard = -1; });
                    if (recipes.pages() > 1 && GalaxyBagLayout.circle(p, my, GalaxyBagLayout.RECIPE_NEXT_X, GalaxyBagLayout.RECIPE_PAGER_Y, GalaxyBagLayout.RECIPE_PAGER_R + 3))
                        return toggle(() -> { recipes.turn(1); selectedCard = -1; });
                    int card = GalaxyBagLayout.card(mx, my, panel);
                    var c = recipes.at(card);
                    if (c != null && client != null && client.interactionManager != null && handler.getCursorStack().isEmpty()) {
                        selectedCard = recipes.page() * GalaxyBagLayout.CARDS_PER_PAGE + card;
                        ghost = null;
                        client.interactionManager.clickRecipe(handler.syncId, c.id(), hasShiftDown());
                        click(1.2f);
                        swallowRelease = true;
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean toggle(Runnable action) {
        action.run();
        click(1.15f);
        swallowRelease = true;
        focusedSlot = null;
        return true;
    }

    /** Closing the panel puts whatever is in the 2x2 grid back into the bag (vanilla shift-click), never hides it. */
    private void closePanel() {
        panelWanted = false;
        searchFocused = false;
        ghost = null;
        returningGrid = true;
        try {
            for (int i = 0; i < 4; i++) {
                Slot slot = handler.getSlot(GalaxyBagLayout.INPUT_START + i);
                if (slot.hasStack()) onMouseClick(slot, slot.id, 0, SlotActionType.QUICK_MOVE);
            }
        } finally { returningGrid = false; }
    }

    private boolean returningGrid;

    /**
     * 2026-10-26 오너: 잠긴 쪽에서는 Shift+클릭(빠른 이동)·두 번 클릭 모으기를 막는다 — 바닐라 규칙대로면 보이지 않는
     * 27칸(잠긴 쪽)으로 옮겨지거나 거기서 모여 아이템이 사라진 것처럼 보인다. 패널 닫을 때 2x2 되돌리기만 예외.
     */
    @Override
    protected void onMouseClick(Slot slot, int slotId, int button, SlotActionType actionType) {
        if (lockedView() && !returningGrid && (actionType == SlotActionType.QUICK_MOVE || actionType == SlotActionType.PICKUP_ALL)) return;
        super.onMouseClick(slot, slotId, button, actionType);
    }

    private void turnPage(int delta) {
        int pages = BagPagesClient.pages();
        int target = Math.clamp(viewPage + delta, 1, pages);
        if (target == viewPage) return;
        if (target > BagPagesClient.unlocked()) { viewPage = target; return; }   // locked: look only
        if (target == BagPagesClient.active()) { viewPage = target; return; }    // back from a locked view
        if (BagPagesClient.requestSwitch(target)) viewPage = target;
    }

    private void click(float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25f));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (swallowRelease) { swallowRelease = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        double mx = fit.localX(mouseX), my = fit.localY(mouseY);
        if (vertical != 0 && GalaxyBagLayout.panelInteractive(panel) && recipesWanted && GalaxyBagLayout.insidePanel(mx, my, panel)
                && my > GalaxyBagLayout.SECTION_RULE_Y && recipes.pages() > 1) {
            recipes.turn(vertical > 0 ? -1 : 1);
            selectedCard = -1;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { searchFocused = false; return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { if (!search.isEmpty()) setSearch(search.substring(0, search.offsetByCodePoints(search.length(), -1))); return true; }
            if (keyCode == GLFW.GLFW_KEY_V && hasControlDown() && client != null) { setSearch(search + client.keyboard.getClipboard().replaceAll("[\\r\\n\\t]", "")); return true; }
            return true;   // typing never closes the bag or drops items
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchFocused) {
            if (!Character.isISOControl(chr)) setSearch(search + chr);
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    private void setSearch(String value) {
        if (value.length() > 24) value = value.substring(0, 24);
        search = value;
        recipes.query(value);
        selectedCard = -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        updateFrame();
        double mx = fit.localX(mouseX), my = fit.localY(mouseY);
        hoveredId = -1;
        for (Element e : GalaxyBagLayout.elements())
            if (visible(e) && GalaxyBagLayout.hit(e, mx, my, panel)) { hoveredId = e.slotId(); break; }
        hoveredCard = GalaxyBagLayout.panelInteractive(panel) && recipesWanted && recipeFade > 0.6f ? GalaxyBagLayout.card(mx, my, panel) : -1;
        // #101: the bag's art and labels live in their own caches (UiResources.bagImages) so other screens never evict
        // them; the shared ones still start a frame for the item tooltip / stat icons drawn over the bag.
        var shared = UiResources.images();
        var images = UiResources.bagImages();
        var text = UiResources.bagText();
        shared.beginFrame();
        UiResources.text().beginFrame();
        images.beginFrame();
        text.beginFrame();
        try {
            super.render(c, mouseX, mouseY, delta);
            // 2026-10-26 오너 제보 "아이템에 마우스를 올려도 툴팁이 안 보인다": 바닐라 HandledScreen#render 는 툴팁을 그리지 않고
            // 각 화면(InventoryScreen 등)이 super.render 뒤에 drawMouseoverTooltip 을 직접 부른다. 이 화면은 처음(f088c59b)부터
            // 그 호출이 빠져 있었다. focusedSlot 은 isPointWithinBounds(배낭 배치·보이는 칸만) 로 정해지므로 배낭 27칸·핫바·
            // 제작 2x2·결과 칸 모두 해당. 그리기는 HandledItemTooltipMixin → ItemTooltipRenderer(등급 연출 툴팁),
            // 커서에 아이템을 들고 있으면 바닐라 규칙대로 안 띄운다(묶음 꾸러미만 예외).
            drawMouseoverTooltip(c, mouseX, mouseY);
            var card = recipes.at(hoveredCard);
            if (card != null && focusedSlot == null && handler.getCursorStack().isEmpty()) {
                c.getMatrices().push();
                c.getMatrices().translate(0, 0, 400);
                // Same tooltip look as slots (HandledItemTooltipMixin → ItemTooltipRenderer), vanilla as fallback.
                if (!ItemTooltipRenderer.render(c, textRenderer, card.result(), mouseX, mouseY, getTooltipFromItem(client, card.result())))
                    c.drawItemTooltip(textRenderer, card.result(), mouseX, mouseY);
                c.getMatrices().pop();
            }
        } finally {
            images.endFrame();
            shared.endFrame();
        }
        entrance.draw(c, width, height, images);
    }

    /** No grey vanilla darkening or blur: the bag floats over the world like the reference. */
    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        drawBackground(c, delta, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext c, float delta, int mouseX, int mouseY) {
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(fit.x(), fit.y(), 0);
            m.scale(fit.scale(), fit.scale(), 1);
            double mx = fit.localX(mouseX), my = fit.localY(mouseY);
            if (panel > 0.001f) drawPanel(c, mx, my);
            drawBag(c, mx, my);
        } finally { m.pop(); }
    }

    private void drawBag(DrawContext c, double mouseX, double my) {
        var m = c.getMatrices();
        float s = GalaxyBagLayout.bagShift(panel);
        double mx = mouseX - s;
        m.push();
        try {
            m.translate(s, 0, 0);
            image(c, BAG_FILL, GalaxyBagLayout.BAG_X, GalaxyBagLayout.BAG_Y, GalaxyBagLayout.BAG_W, GalaxyBagLayout.BAG_H, BAG_FILL_W, BAG_FILL_H, FILL);
            stars(c, BAG_STARS);
            image(c, BAG, GalaxyBagLayout.BAG_X, GalaxyBagLayout.BAG_Y, GalaxyBagLayout.BAG_W, GalaxyBagLayout.BAG_H, GalaxyBagLayout.BAG_PNG_W, GalaxyBagLayout.BAG_PNG_H, 0xFFFFFFFF);
            var text = UiResources.bagText();
            boolean locked = lockedView();
            for (int i = 0; i < 27; i++) {
                Element e = GalaxyBagLayout.element(GalaxyBagLayout.MAIN_START + i);
                boolean has = !locked && handler.getSlot(e.slotId()).hasStack();
                ring(c, e.cx(), e.cy(), e.radius(), locked ? RING_DIM : hoveredId == e.slotId() ? RING_HOVER : RING, hoveredId == e.slotId() ? 2.2f : 1.5f);
                if (!has) HudMesh.disk(c, e.cx(), e.cy(), 2.4f, locked ? DOT_DIM : DOT);
            }
            if (locked) {
                // 2026-10-26 오너: 잠긴 쪽에 "아공간 배낭" 제목을 그리지 않는다 (자물쇠·문구만).
                // 자물쇠·문구 묶음은 27칸 영역(핫바 제외)의 정중앙 — 글자 높이는 실제 글꼴 잉크로 재서 GalaxyBagLayout 이 배치.
                var lock = lockStack(text);
                float cx = lock.cx(), size = GalaxyBagLayout.LOCK_ICON;
                // Like the lock reference: the dim rings fade out behind the padlock and the two lines of text.
                float top = lock.top(), bottom = lock.bottom();
                for (int k = 0; k < 4; k++) {
                    float grow = 26 - k * 8;
                    roundRect(c, cx - 128 - grow, top - grow, 256 + grow * 2, bottom - top + grow * 2, 30 + grow, 0x401E2152);
                }
                image(c, PADLOCK, cx - size / 2, lock.iconY() - size / 2, size, size, PADLOCK_PNG, PADLOCK_PNG, 0xFFFFFFFF);
                text.draw(c, LOCK_TEXT, cx, lock.textY(), LOCK_TEXT_SIZE, TEXT, SERIF, true);
                text.draw(c, LOCK_HINT, cx, lock.hintY(), LOCK_HINT_SIZE, TEXT_SOFT, SERIF, true);
            }
            // 핫바 9칸은 쪽과 무관하게 그대로 (2026-10-26 오너: 잠긴 쪽에서도 보이고 쓸 수 있게).
            HudMesh.line(c, GalaxyBagLayout.DIVIDER_X0, GalaxyBagLayout.DIVIDER_Y, GalaxyBagLayout.DIVIDER_X1, GalaxyBagLayout.DIVIDER_Y, 1.4f, RULE);
            for (int i = 0; i < 9; i++) {
                Element e = GalaxyBagLayout.element(GalaxyBagLayout.HOTBAR_START + i);
                ring(c, e.cx(), e.cy(), e.radius(), hoveredId == e.slotId() ? RING_HOVER : RING, hoveredId == e.slotId() ? 2.2f : 1.5f);
                if (!handler.getSlot(e.slotId()).hasStack()) HudMesh.disk(c, e.cx(), e.cy(), 2.4f, DOT);
                text.draw(c, Integer.toString(i + 1), e.cx(), GalaxyBagLayout.HOTBAR_NUMBER_Y, 22, TEXT_SOFT, MEDIUM, true);
            }
            // Pager: shown page / pages. Arrows fade when there is nowhere to go.
            int pages = BagPagesClient.pages(), page = Math.min(viewPage, pages);
            boolean waiting = BagPagesClient.switching();
            pagerButton(c, GalaxyBagLayout.PAGER_PREV_X, GalaxyBagLayout.PAGER_Y, GalaxyBagLayout.PAGER_R, false, page > 1 && !waiting, mx, my);
            pagerButton(c, GalaxyBagLayout.PAGER_NEXT_X, GalaxyBagLayout.PAGER_Y, GalaxyBagLayout.PAGER_R, true, page < pages && !waiting, mx, my);
            text.draw(c, GalaxyBagLayout.pager(page, pages), GalaxyBagLayout.PAGER_LABEL_X, GalaxyBagLayout.PAGER_Y + 1, 28, waiting ? TEXT_HINT : TEXT, MEDIUM, true);
            // Round buttons: equipment window, crafting panel.
            roundButton(c, GalaxyBagLayout.EQUIP_X, GalaxyBagLayout.EQUIP_Y, SHIRT, false, mx, my);
            roundButton(c, GalaxyBagLayout.CRAFT_BTN_X, GalaxyBagLayout.CRAFT_BTN_Y, TABLE, panelWanted, mx, my);
        } finally { m.pop(); }
    }

    private static final String LOCK_TEXT = "잠긴 공간", LOCK_HINT = "가방 확장권으로 개방";
    private static final float LOCK_TEXT_SIZE = 30, LOCK_HINT_SIZE = 20;
    private GalaxyBagLayout.LockStack lockStack;

    /** 잠긴 쪽 묶음 배치 (글자 잉크 높이는 글꼴이 바뀌지 않는 한 같으니 한 번만 잰다). */
    private GalaxyBagLayout.LockStack lockStack(CodexTypography text) {
        if (lockStack == null) {
            float[] t = text.ink(LOCK_TEXT, LOCK_TEXT_SIZE, SERIF), h = text.ink(LOCK_HINT, LOCK_HINT_SIZE, SERIF);
            lockStack = GalaxyBagLayout.lockStack(t[1] - t[0], h[1] - h[0]);
        }
        return lockStack;
    }

    private void drawPanel(DrawContext c, double mouseX, double my) {
        var m = c.getMatrices();
        float s = GalaxyBagLayout.panelShift(panel), alpha = GalaxyBagLayout.ease(panel);
        double mx = mouseX - s;
        m.push();
        try {
            m.translate(s, 0, 0);
            image(c, PANEL_FILL, GalaxyBagLayout.PANEL_X, GalaxyBagLayout.PANEL_Y, GalaxyBagLayout.PANEL_W, GalaxyBagLayout.PANEL_H, PANEL_FILL_W, PANEL_FILL_H, fade(FILL, alpha));
            if (alpha > 0.5f) stars(c, PANEL_STARS);
            image(c, PANEL, GalaxyBagLayout.PANEL_X, GalaxyBagLayout.PANEL_Y, GalaxyBagLayout.PANEL_W, GalaxyBagLayout.PANEL_H, GalaxyBagLayout.PANEL_PNG_W, GalaxyBagLayout.PANEL_PNG_H, fade(0xFFFFFFFF, alpha));
            var text = UiResources.bagText();
            text.draw(c, "제작", GalaxyBagLayout.CRAFT_TITLE_X, GalaxyBagLayout.CRAFT_TITLE_Y, 32, fade(TEXT, alpha), BOLD, true);
            // Recipe book toggle: rounded square, lit while the recipe list is open.
            float h = GalaxyBagLayout.BOOK_SIZE / 2, bxc = GalaxyBagLayout.BOOK_X, byc = GalaxyBagLayout.BOOK_Y;
            boolean bookHover = GalaxyBagLayout.rect(mx, my, bxc - h, byc - h, bxc + h, byc + h);
            // Reference: dark square with a bright lavender frame and violet glow while the recipe list is open.
            roundRect(c, bxc - h, byc - h, 2 * h, 2 * h, 12, fade(0xFF2A2468, alpha));
            if (recipesWanted) {
                roundRectOutline(c, bxc - h - 4, byc - h - 4, 2 * h + 8, 2 * h + 8, 16, 5f, fade(0x667C5CF0, alpha));
                roundRectOutline(c, bxc - h - 1.5f, byc - h - 1.5f, 2 * h + 3, 2 * h + 3, 13.5f, 2.5f, fade(0xAA9C7BFF, alpha));
            }
            roundRectOutline(c, bxc - h, byc - h, 2 * h, 2 * h, 12, recipesWanted ? 3.2f : 1.8f, fade(recipesWanted ? 0xFFEDE6FF : bookHover ? RING_HOVER : RING, alpha));
            image(c, BOOK, bxc - 30, byc - 30, 60, 60, ICON_PNG, ICON_PNG, fade(0xFFFFFFFF, alpha));
            // Close.
            boolean closeHover = GalaxyBagLayout.circle(mx, my, GalaxyBagLayout.CLOSE_X, GalaxyBagLayout.CLOSE_Y, GalaxyBagLayout.CLOSE_R);
            ring(c, GalaxyBagLayout.CLOSE_X, GalaxyBagLayout.CLOSE_Y, GalaxyBagLayout.CLOSE_R, fade(closeHover ? RING_HOVER : 0xE6E9E2FF, alpha), 2f);
            float k = 7.5f;
            HudMesh.line(c, GalaxyBagLayout.CLOSE_X - k, GalaxyBagLayout.CLOSE_Y - k, GalaxyBagLayout.CLOSE_X + k, GalaxyBagLayout.CLOSE_Y + k, 3f, fade(TEXT, alpha));
            HudMesh.line(c, GalaxyBagLayout.CLOSE_X - k, GalaxyBagLayout.CLOSE_Y + k, GalaxyBagLayout.CLOSE_X + k, GalaxyBagLayout.CLOSE_Y - k, 3f, fade(TEXT, alpha));
            fadeRule(c, GalaxyBagLayout.TITLE_RULE_X0, GalaxyBagLayout.TITLE_RULE_X1, GalaxyBagLayout.TITLE_RULE_Y, alpha);
            // 2x2 input and result.
            for (int i = 0; i < 4; i++) {
                Element e = GalaxyBagLayout.element(GalaxyBagLayout.INPUT_START + i);
                boolean hover = hoveredId == e.slotId();
                ring(c, e.cx(), e.cy(), e.radius(), fade(hover ? RING_HOVER : 0xD9E2DBFF, alpha), hover ? 2.4f : 1.8f);
                if (!handler.getSlot(e.slotId()).hasStack() && ghostItem(i) == null) HudMesh.disk(c, e.cx(), e.cy(), 2.4f, fade(DOT, alpha));
            }
            arrow(c, alpha);
            boolean resultHover = hoveredId == GalaxyBagLayout.RESULT_SLOT;
            ring(c, GalaxyBagLayout.RESULT_X, GalaxyBagLayout.RESULT_Y, GalaxyBagLayout.RESULT_R, fade(resultHover ? RING_HOVER : 0xE6E2DBFF, alpha), resultHover ? 2.6f : 2f);
            HudMesh.line(c, GalaxyBagLayout.SECTION_RULE_X0, GalaxyBagLayout.SECTION_RULE_Y, GalaxyBagLayout.SECTION_RULE_X1, GalaxyBagLayout.SECTION_RULE_Y, 1.4f, fade(RULE, alpha));
            drawRecipeSection(c, mx, my, alpha * recipeFade);
        } finally { m.pop(); }
    }

    private void drawRecipeSection(DrawContext c, double mx, double my, float alpha) {
        if (alpha <= 0.01f) return;
        var text = UiResources.bagText();
        text.draw(c, "조합법", GalaxyBagLayout.RECIPE_TITLE_X, GalaxyBagLayout.RECIPE_TITLE_Y, 30, fade(TEXT, alpha), BOLD, true);
        // Search box.
        float x0 = GalaxyBagLayout.SEARCH_X0, y0 = GalaxyBagLayout.SEARCH_Y0, w = GalaxyBagLayout.SEARCH_X1 - x0, h = GalaxyBagLayout.SEARCH_Y1 - y0;
        roundRect(c, x0, y0, w, h, h / 2, fade(0x40140F3C, alpha));
        roundRectOutline(c, x0, y0, w, h, h / 2, searchFocused ? 2f : 1.5f, fade(searchFocused ? RING_HOVER : RING, alpha));
        float gx = x0 + 19, gy = y0 + h / 2 - 1;
        ring(c, gx, gy, 7, fade(0xFFD8CDF5, alpha), 2f);
        HudMesh.line(c, gx + 5, gy + 5, gx + 10.5f, gy + 10.5f, 2.4f, fade(0xFFD8CDF5, alpha));
        boolean caret = searchFocused && (Util.getMeasuringTimeMs() / 500) % 2 == 0;
        float tx = x0 + 34;
        if (search.isEmpty()) {
            if (!searchFocused) text.draw(c, "검색", tx, gy + 1, 19, fade(TEXT_HINT, alpha), BODY, false);
            else if (caret) HudMesh.line(c, tx + 1, gy - 9, tx + 1, gy + 10, 1.5f, fade(TEXT, alpha));
        } else {
            String shown = search;
            while (shown.length() > 1 && text.width(shown, 19, BODY) > w - 46) shown = shown.substring(1);
            text.draw(c, shown, tx, gy + 1, 19, fade(TEXT, alpha), BODY, false);
            if (caret) { float cw = text.width(shown, 19, BODY); HudMesh.line(c, tx + cw + 2, gy - 9, tx + cw + 2, gy + 10, 1.5f, fade(TEXT, alpha)); }
        }
        // Craftable-only toggle.
        text.draw(c, "제작 가능", GalaxyBagLayout.CRAFTABLE_LABEL_X, GalaxyBagLayout.CRAFTABLE_LABEL_Y, 19, fade(TEXT_SOFT, alpha), MEDIUM, true);
        boolean on = recipes.craftableOnly();
        float tx0 = GalaxyBagLayout.TOGGLE_X0, ty0 = GalaxyBagLayout.TOGGLE_Y0, tw = GalaxyBagLayout.TOGGLE_X1 - tx0, th = GalaxyBagLayout.TOGGLE_Y1 - ty0;
        roundRect(c, tx0, ty0, tw, th, th / 2, fade(on ? VIOLET : 0x66403A70, alpha));
        roundRectOutline(c, tx0, ty0, tw, th, th / 2, 1.4f, fade(on ? 0xFFB7A2FF : RING, alpha));
        HudMesh.disk(c, on ? tx0 + tw - th / 2 : tx0 + th / 2, ty0 + th / 2, th / 2 - 3.5f, fade(0xFFFFFFFF, alpha));
        // Cards.
        for (int i = 0; i < GalaxyBagLayout.CARDS_PER_PAGE; i++) {
            float cx = GalaxyBagLayout.CARD_X[i % 3], cy = GalaxyBagLayout.CARD_Y[i / 3];
            var card = recipes.at(i);
            boolean selected = card != null && selectedCard == recipes.page() * GalaxyBagLayout.CARDS_PER_PAGE + i;
            boolean hover = card != null && hoveredCard == i;
            roundRect(c, cx, cy, GalaxyBagLayout.CARD_W, GalaxyBagLayout.CARD_H, 11, fade(CARD_FILL, alpha));
            if (selected) roundRectOutline(c, cx - 3, cy - 3, GalaxyBagLayout.CARD_W + 6, GalaxyBagLayout.CARD_H + 6, 14, 3.5f, fade(0x449C7BFF, alpha));
            roundRectOutline(c, cx, cy, GalaxyBagLayout.CARD_W, GalaxyBagLayout.CARD_H, 11, selected ? 2.6f : hover ? 2f : 1.4f,
                    fade(selected ? CARD_SELECTED : hover ? RING_HOVER : CARD_RING, alpha));
        }
        // Recipe pager.
        int pages = recipes.pages(), page = recipes.page() + 1;
        pagerButton(c, GalaxyBagLayout.RECIPE_PREV_X, GalaxyBagLayout.RECIPE_PAGER_Y, GalaxyBagLayout.RECIPE_PAGER_R, false, pages > 1, mx, my, alpha);
        pagerButton(c, GalaxyBagLayout.RECIPE_NEXT_X, GalaxyBagLayout.RECIPE_PAGER_Y, GalaxyBagLayout.RECIPE_PAGER_R, true, pages > 1, mx, my, alpha);
        text.draw(c, GalaxyBagLayout.pager(page, pages), GalaxyBagLayout.RECIPE_LABEL_X, GalaxyBagLayout.RECIPE_PAGER_Y + 1, 23, fade(TEXT, alpha), MEDIUM, true);
    }

    /** Items, counts, durability and cooldown at the layout. Called inside HandledScreen's (x, y) translation. */
    @Override
    protected void drawSlots(DrawContext c) {
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(-x, -y, 0);
            m.translate(fit.x(), fit.y(), 0);
            m.scale(fit.scale(), fit.scale(), 1);
            ItemStack cursor = handler.getCursorStack();
            int heldButton = ((school.magiccodex.mixin.HandledScreenAccessor) (Object) this).magiccodex$heldButtonType();
            float panelAlpha = GalaxyBagLayout.ease(panel);
            for (Element e : GalaxyBagLayout.elements()) {
                boolean show = e.inPanel() ? panel > 0.001f : e.kind() == Kind.HOTBAR || !lockedView();
                if (!show) continue;
                Slot slot = handler.getSlot(e.slotId());
                ItemStack stack = slot.getStack();
                String amount = null;
                boolean preview = false;
                // Same drag-split preview as vanilla HandledScreen#drawSlot.
                if (cursorDragging && cursorDragSlots.contains(slot) && !cursor.isEmpty() && cursorDragSlots.size() > 1
                        && ScreenHandler.canInsertItemIntoSlot(slot, cursor, true) && handler.canInsertIntoSlot(slot)) {
                    int max = Math.min(cursor.getMaxCount(), slot.getMaxItemCount(cursor));
                    int count = ScreenHandler.calculateStackSize(cursorDragSlots, heldButton, cursor) + (stack.isEmpty() ? 0 : stack.getCount());
                    if (count > max) { count = max; amount = "§e" + max; }
                    stack = cursor.copyWithCount(count);
                    preview = true;
                }
                if (stack.isEmpty()) continue;
                float cx = e.cx() + GalaxyBagLayout.shiftOf(e, panel);
                float lift = hoveredId == e.slotId() ? 2 : 0;
                drawStack(c, stack, cx, e.cy() - lift, e.itemSize(), e.slotId(), amount, preview, countAnchor(e, cx), e.inPanel() ? panelAlpha : 1);
            }
            if (panel > 0.001f) {
                drawGhost(c);
                drawCards(c, panelAlpha * recipeFade);
            }
        } finally { m.pop(); }
    }

    private ItemStack ghostItem(int input) {
        if (ghost == null || client == null || client.world == null) return null;
        var stacks = GalaxyRecipes.ghost(ghost, GalaxyRecipes.context(client)).get(input);
        if (stacks.isEmpty()) return null;
        return stacks.get((int) Math.floorMod((Util.getMeasuringTimeMs() - ghostAt) / 1000, stacks.size()));
    }

    /** Recipe the server could not fill: faded ingredients in the empty input orbs and a faded result. */
    private void drawGhost(DrawContext c) {
        if (ghost == null || client == null || client.world == null) return;
        float s = GalaxyBagLayout.panelShift(panel);
        var context = GalaxyRecipes.context(client);
        var all = GalaxyRecipes.ghost(ghost, context);
        int index = (int) ((Util.getMeasuringTimeMs() - ghostAt) / 1000);
        for (int i = 0; i < 4; i++) {
            var stacks = all.get(i);
            Element e = GalaxyBagLayout.element(GalaxyBagLayout.INPUT_START + i);
            if (stacks.isEmpty() || handler.getSlot(e.slotId()).hasStack()) continue;
            ghostStack(c, stacks.get(Math.floorMod(index, stacks.size())), e.cx() + s, e.cy(), e.itemSize(), 0x8C3A1020);
        }
        if (!handler.getSlot(GalaxyBagLayout.RESULT_SLOT).hasStack()) {
            ItemStack result;
            try { result = ghost.result().getFirst(context); } catch (RuntimeException ex) { result = ItemStack.EMPTY; }
            if (!result.isEmpty()) ghostStack(c, result, GalaxyBagLayout.RESULT_X + s, GalaxyBagLayout.RESULT_Y, GalaxyBagLayout.RESULT_ITEM, 0x8C141A3A);
        }
    }

    private void ghostStack(DrawContext c, ItemStack stack, float cx, float cy, float size, int veil) {
        var m = c.getMatrices();
        m.push();
        try { if (!ExactItemIcons.draw(c, stack, cx - size / 2, cy - size / 2, size)) { m.translate(cx - size / 2, cy - size / 2, 0); m.scale(size / 16f, size / 16f, 1); c.drawItem(stack, 0, 0); } }
        finally { m.pop(); }
        m.push();
        try { m.translate(0, 0, 210); HudMesh.disk(c, cx, cy, size * 0.62f, veil); }
        finally { m.pop(); }
    }

    private void drawCards(DrawContext c, float alpha) {
        if (alpha <= 0.4f) return;
        float s = GalaxyBagLayout.panelShift(panel);
        for (int i = 0; i < GalaxyBagLayout.CARDS_PER_PAGE; i++) {
            var card = recipes.at(i);
            if (card == null) continue;
            float cx = GalaxyBagLayout.CARD_X[i % 3] + s + GalaxyBagLayout.CARD_W / 2, cy = GalaxyBagLayout.CARD_Y[i / 3] + GalaxyBagLayout.CARD_H / 2;
            float[] anchor = {cx + GalaxyBagLayout.CARD_W / 2 - 14, cy + GalaxyBagLayout.CARD_H / 2 - 17};
            drawStack(c, card.result(), cx, cy - 1, GalaxyBagLayout.CARD_ITEM, i, null, false, anchor, alpha);
            if (!card.craftable()) {
                var m = c.getMatrices();
                m.push();
                try {
                    m.translate(0, 0, 220);
                    roundRect(c, cx - GalaxyBagLayout.CARD_W / 2 + 1, cy - GalaxyBagLayout.CARD_H / 2 + 1, GalaxyBagLayout.CARD_W - 2, GalaxyBagLayout.CARD_H - 2, 10, 0x8C120E2C);
                } finally { m.pop(); }
            }
        }
    }

    /** Where the count's right edge and vertical centre sit for this element. */
    private static float[] countAnchor(Element e, float cx) {
        return switch (e.kind()) {
            case RESULT -> new float[]{cx + 40, e.cy() + 30};
            case INPUT -> new float[]{cx + 30, e.cy() + 22};
            case MAIN -> new float[]{cx + 33, e.cy() + 24};
            case HOTBAR -> new float[]{cx + 30, e.cy() + 21};
        };
    }

    private void drawStack(DrawContext c, ItemStack stack, float cx, float cy, float size, int seed, String amount, boolean preview, float[] countAnchor, float alpha) {
        if (alpha <= 0.4f) return;   // items pop in with the panel instead of half-transparent 3D models
        var m = c.getMatrices();
        float left = cx - size / 2, top = cy - size / 2;
        m.push();
        try {
            // High-res art: resampled once to the exact device pixel size and drawn 1:1 (ExactItemIcons).
            // Vanilla 16px / block items (and high-res art for the first frames) take the normal item draw.
            if (!ExactItemIcons.draw(c, stack, left, top, size)) {
                m.translate(left, top, 0);
                m.scale(size / 16f, size / 16f, 1);
                c.drawItem(stack, 0, 0, seed);
            }
        } finally { m.pop(); }
        m.push();
        try {
            m.translate(0, 0, 200);
            if (preview) roundRect(c, left, top, size, size, 6, 0x30FFFFFF);
            if (stack.isItemBarVisible()) {
                float w = size * 0.8f, x0 = cx - w / 2, y0 = top + size + 1;
                HudMesh.quad(c, x0, y0, w, 3, 0xFF0B0F1A, 0xFF0B0F1A);
                int bar = 0xFF000000 | stack.getItemBarColor();
                HudMesh.quad(c, x0, y0, w * stack.getItemBarStep() / 13f, 2, bar, bar);
            }
            if (client != null && client.player != null) {
                float cooldown = client.player.getItemCooldownManager().getCooldownProgress(stack, client.getRenderTickCounter().getTickDelta(true));
                if (cooldown > 0) HudMesh.quad(c, left, top + size * (1 - cooldown), size, size * cooldown, 0x66E8F2FF, 0x66E8F2FF);
            }
            String count = amount != null ? amount : GalaxyBagLayout.count(stack.getCount());
            if (!count.isEmpty()) {
                int color = TEXT;
                if (count.startsWith("§e")) { count = count.substring(2); color = 0xFFF6DD72; }
                var text = UiResources.bagText();
                float w = text.width(count, 26, BOLD);
                text.drawOutline(c, count, countAnchor[0] - w, countAnchor[1], 26, color, BOLD, false);
            }
        } finally { m.pop(); }
    }

    /** Hook from HandledScreenHooksMixin: the carried stack, drawn at the bag's item size. */
    public void drawCursorStack(DrawContext c, ItemStack stack, int localX, int localY, String amount) {
        if (stack.isEmpty()) return;
        float size = Math.max(16, GalaxyBagLayout.MAIN_ITEM * fit.scale());
        float cx = localX + 8, cy = localY + 8;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(0, 0, 232);
            if (!ExactItemIcons.draw(c, stack, cx - size / 2, cy - size / 2, size)) {
                m.translate(cx - size / 2, cy - size / 2, 0);
                m.scale(size / 16f, size / 16f, 1);
                c.drawItem(stack, 0, 0);
            }
        } finally { m.pop(); }
        String count = amount != null ? amount.replace("§e", "") : GalaxyBagLayout.count(stack.getCount());
        if (count.isEmpty()) return;
        m.push();
        try {
            m.translate(0, 0, 432);
            var text = UiResources.bagText();
            float textSize = Math.max(8, 26 * fit.scale());
            float w = text.width(count, textSize, BOLD);
            text.drawOutline(c, count, cx + size * 0.55f - w, cy + size * 0.38f, textSize, amount != null && amount.startsWith("§e") ? 0xFFF6DD72 : TEXT, BOLD, false);
        } finally { m.pop(); }
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
        // All labels are drawn with the codex fonts in drawBackground; vanilla "Crafting"/"Inventory" stay hidden.
    }

    // ------------------------------------------------------------------ shapes

    /** Faint fixed star dust inside the glass, as in the reference (x, y, radius, alpha) — same every frame. */
    private static final float[][] BAG_STARS = starField(7, 70, GalaxyBagLayout.BAG_LEFT + 60, GalaxyBagLayout.BAG_TOP + 40,
            GalaxyBagLayout.BAG_RIGHT - 60, GalaxyBagLayout.BAG_BOTTOM - 40, false),
            PANEL_STARS = starField(11, 26, GalaxyBagLayout.PANEL_LEFT + 24, GalaxyBagLayout.PANEL_TOP + 24,
            GalaxyBagLayout.PANEL_RIGHT - 24, GalaxyBagLayout.PANEL_BOTTOM - 24, true);

    private static float[][] starField(long seed, int count, float x0, float y0, float x1, float y1, boolean panel) {
        var random = new java.util.Random(seed);
        var out = new java.util.ArrayList<float[]>();
        while (out.size() < count) {
            float x = x0 + random.nextFloat() * (x1 - x0), y = y0 + random.nextFloat() * (y1 - y0);
            if (!panel && !GalaxyBagLayout.insideBag(x + GalaxyBagLayout.bagShift(1), y, 1)) continue;
            out.add(new float[]{x, y, 0.7f + random.nextFloat() * 0.9f, 0.18f + random.nextFloat() * 0.4f});
        }
        return out.toArray(float[][]::new);
    }

    private static void stars(DrawContext c, float[][] stars) {
        for (float[] s : stars) HudMesh.disk(c, s[0], s[1], s[2], (Math.round(s[3] * 255) << 24) | 0xDCD8FF);
    }

    private void roundButton(DrawContext c, float cx, float cy, Identifier icon, boolean lit, double mx, double my) {
        float r = GalaxyBagLayout.ROUND_BTN_R;
        boolean hover = GalaxyBagLayout.circle(mx, my, cx, cy, r);
        HudMesh.disk(c, cx, cy, r, 0x661A1546);
        if (lit) ring(c, cx, cy, r + 3, 0x559C7BFF, 4f);
        ring(c, cx, cy, r, hover || lit ? RING_HOVER : 0xE6E9E2FF, hover || lit ? 2.4f : 2f);
        float size = r * 1.62f;
        image(c, icon, cx - size / 2, cy - size / 2, size, size, ICON_PNG, ICON_PNG, 0xFFFFFFFF);
    }

    private void pagerButton(DrawContext c, float cx, float cy, float r, boolean next, boolean enabled, double mx, double my) {
        pagerButton(c, cx, cy, r, next, enabled, mx, my, 1);
    }

    private void pagerButton(DrawContext c, float cx, float cy, float r, boolean next, boolean enabled, double mx, double my, float alpha) {
        boolean hover = enabled && GalaxyBagLayout.circle(mx, my, cx, cy, r + 3);
        int color = fade(enabled ? hover ? RING_HOVER : 0xF0EEE9FF : 0x66EEE9FF, alpha);
        HudMesh.disk(c, cx, cy, r, fade(0x40140F3C, alpha));
        ring(c, cx, cy, r, color, hover ? 2.4f : 2f);
        float k = r * 0.3f, d = next ? 1 : -1;
        HudMesh.line(c, cx - d * k * 0.55f, cy - k, cx + d * k * 0.55f, cy, 3f, color);
        HudMesh.line(c, cx + d * k * 0.55f, cy, cx - d * k * 0.55f, cy + k, 3f, color);
    }

    /** Crafting arrow: lavender shaft fading in from the left, solid head. */
    private static void arrow(DrawContext c, float alpha) {
        float x0 = GalaxyBagLayout.ARROW_X - GalaxyBagLayout.ARROW_LEN / 2, x1 = GalaxyBagLayout.ARROW_X + GalaxyBagLayout.ARROW_LEN / 2, y = GalaxyBagLayout.ARROW_Y;
        float head = 20, shaft = 13;
        HudMesh.quad(c, x0, y - shaft / 2, x1 - head - x0, shaft, fade(0x00B49CFF, alpha), fade(0xF0C9B8FF, alpha));
        triangle(c, x1 - head, y - 20, x1 - head, y + 20, x1, y, fade(0xFFD2C4FF, alpha));
    }

    private static void triangle(DrawContext c, float ax, float ay, float bx, float by, float cx, float cy, int color) {
        var m = c.getMatrices().peek().getPositionMatrix();
        c.draw(p -> {
            var v = p.getBuffer(RenderLayer.getGui());
            v.vertex(m, ax, ay, 0).color(color); v.vertex(m, bx, by, 0).color(color);
            v.vertex(m, cx, cy, 0).color(color); v.vertex(m, cx, cy, 0).color(color);
        });
    }

    private static void ring(DrawContext c, float cx, float cy, float r, int color, float thickness) {
        HudMesh.arc(c, cx, cy, r, thickness, 0, (float) (Math.PI * 2), color);
    }

    private static void fadeRule(DrawContext c, float x0, float x1, float y, float alpha) {
        float mid = x0 + (x1 - x0) * 0.6f;
        HudMesh.quad(c, x0, y - 0.7f, mid - x0, 1.4f, fade(RULE, alpha), fade(RULE, alpha));
        HudMesh.quad(c, mid, y - 0.7f, x1 - mid, 1.4f, fade(RULE, alpha), fade(RULE & 0x00FFFFFF, alpha));
    }

    /** Points around a rounded rectangle (clockwise from the top-left corner's end). */
    private static float[] roundPoints(float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) / 2);
        final int seg = 6;
        float[] out = new float[(seg + 1) * 4 * 2];
        float[][] centers = {{x + w - r, y + r}, {x + w - r, y + h - r}, {x + r, y + h - r}, {x + r, y + r}};
        int k = 0;
        for (int corner = 0; corner < 4; corner++) {
            double start = -Math.PI / 2 + corner * Math.PI / 2;
            for (int i = 0; i <= seg; i++) {
                double a = start + i * (Math.PI / 2) / seg;
                out[k++] = centers[corner][0] + (float) Math.cos(a) * r;
                out[k++] = centers[corner][1] + (float) Math.sin(a) * r;
            }
        }
        return out;
    }

    static void roundRect(DrawContext c, float x, float y, float w, float h, float r, int color) {
        float[] pts = roundPoints(x, y, w, h, r);
        float cx = x + w / 2, cy = y + h / 2;
        var m = c.getMatrices().peek().getPositionMatrix();
        c.draw(p -> {
            var v = p.getBuffer(RenderLayer.getGui());
            int n = pts.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                // Same winding as HudMesh.disk (centre, next, current, centre) so the GUI layer never culls it.
                v.vertex(m, cx, cy, 0).color(color);
                v.vertex(m, pts[j * 2], pts[j * 2 + 1], 0).color(color);
                v.vertex(m, pts[i * 2], pts[i * 2 + 1], 0).color(color);
                v.vertex(m, cx, cy, 0).color(color);
            }
        });
    }

    static void roundRectOutline(DrawContext c, float x, float y, float w, float h, float r, float thickness, int color) {
        float[] pts = roundPoints(x, y, w, h, r);
        int n = pts.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            HudMesh.line(c, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], thickness, color);
        }
    }

    private static int fade(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static void image(DrawContext c, Identifier path, float x, float y, float w, float h, int sourceW, int sourceH, int tint) {
        if ((tint >>> 24) == 0) return;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(x, y, 0);
            // Draw at a fixed integer size and scale the remainder, so sliding stays sub-pixel smooth.
            int iw = Math.max(1, (int) Math.ceil(w)), ih = Math.max(1, (int) Math.ceil(h));
            m.scale(w / iw, h / ih, 1);
            UiResources.bagImages().drawTexture(c, path, 0, 0, 0, 0, iw, ih, sourceW, sourceH, sourceW, sourceH, tint);
        } finally { m.pop(); }
    }

    @Override public boolean shouldPause() { return false; }
}
