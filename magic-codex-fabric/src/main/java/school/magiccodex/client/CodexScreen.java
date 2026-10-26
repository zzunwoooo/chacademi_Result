package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import java.util.List;




import school.magiccodex.client.CodexData.Category;
import school.magiccodex.client.CodexData.Spell;
import school.magiccodex.client.CodexHitboxes.Rect;
import school.magiccodex.client.CodexState.Filter;
import school.magiccodex.client.CodexSprites.Sprite;
import school.magiccodex.mixin.TextFieldAccessor;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.minecraft.util.math.RotationAxis;

/** Local catalog with authoritative server permission and donation state. */
public final class CodexScreen extends Screen {
    private static final Identifier BACKGROUND = id("textures/gui/codex_base.png");
    private static final Identifier SELECTION_FRAME = id("textures/gui/card_selected.png");
    private static final Identifier TITLE_FONT = id("title");
    private static final Identifier HEADING_FONT = id("heading");
    private static final Identifier SECTION_FONT = id("section");
    private static final Identifier BODY_FONT = id("body");
    private static final Identifier LABEL_FONT = id("label");
    private static final int IVORY = 0xFFF0E3C8;
    private static final int GOLD = 0xFFD0B98C;
    private static final int INK = 0xFF34342F;
    private static final int MUTED = 0xFF676051;
    private final CodexState state;
    private final boolean catalogBacked;
    private HudTextureCache images;
    private TextFieldWidget search;
    private CodexTypography typography;
    private double hoverX, hoverY;
    private String notice = "";
    private SoundCue lastSound;
    private long lastSoundTime;
    private final long openedAt = Util.getMeasuringTimeMs();
    private long readySince = -1;
    private record HoverText(String title,String text,Rect area) {}
    private record WrapKey(String text,float width,float size) {}
    private final java.util.Map<WrapKey,List<String>> wrappedText=new java.util.HashMap<>();
    private final java.util.List<HoverText> hoverTexts=new java.util.ArrayList<>();
    private HoverText activeText;
    private long hoverStarted;
    private int tooltipScroll,tooltipMaxScroll;
    private final UiResources.Entrance entrance=new UiResources.Entrance();
    private int resourceGeneration=-1;
    private static final java.util.concurrent.ExecutorService TEXT_LAYOUT=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"magiccodex-ui-paragraphs");t.setDaemon(true);return t;});
    private AsyncUiLoader<WrapKey,List<String>> pendingWrap;

    private enum SoundCue {
        BUTTON(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, 0.28f),
        CARD(SoundEvents.UI_BUTTON_CLICK.value(), 1.25f, 0.24f),
        PAGE(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.1f, 0.42f),
        UNAVAILABLE(SoundEvents.UI_BUTTON_CLICK.value(), 0.72f, 0.20f),
        CLOSE(SoundEvents.ITEM_BOOK_PUT, 0.95f, 0.35f);

        final SoundEvent event;
        final float pitch, volume;

        SoundCue(SoundEvent event, float pitch, float volume) {
            this.event = event;
            this.pitch = pitch;
            this.volume = volume;
        }
    }

    public void schoolNotice(String message){notice=message;}
    @Override public void tick(){if(catalogBacked&&state.selected()!=null)SchoolClient.lookup(state.selected().id(),this);}
    public CodexState state() { return state; }
    public boolean isCatalogBacked() { return catalogBacked; }
    private boolean awaitingPermissions() { return catalogBacked && !PermissionClient.catalogConfirmed(); }

    public CodexScreen() {
        this(CodexCatalog.spells(), true);
    }

    /** Explicit fixture data for development rendering; normal screens always read the catalog. */
    public CodexScreen(List<Spell> spells) {
        this(spells, false);
    }

    private CodexScreen(List<Spell> spells, boolean catalogBacked) {
        super(Text.literal("마법 도감"));
        this.state = new CodexState(spells);
        this.catalogBacked = catalogBacked;
    }

    public void refreshCatalog() {
        if (!catalogBacked) return;
        state.replaceSpells(CodexCatalog.spells());

    }

    private static Identifier id(String path) {
        return Identifier.of("magiccodex", path);
    }

    @Override
    protected void init() {
        refreshCatalog();
        attachResources();
        wrappedText.clear();activeText=null;

        // Reuse vanilla editing/clipboard/selection, with our own aligned font drawing.
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 24, Text.literal("마법 검색"));
        search.setMaxLength(16);
        search.setText(state.query());
        search.setChangedListener(value -> { state.setQuery(value); notice = ""; });
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if(typography==null||images==null||resourceGeneration!=UiResources.generation())attachResources();
        typography.beginFrame();
        images.beginFrame();
        context.fill(0, 0, width, height, 0x88070D13);
        CodexLayout layout = CodexLayout.codexFit(width, height);
        hoverX = layout.localX(mouseX);
        hoverY = layout.localY(mouseY);
        hoverTexts.clear();
        context.getMatrices().push();
        try {
            context.getMatrices().translate(layout.x(), layout.y(), 0);
            context.getMatrices().scale(layout.scale(), layout.scale(), 1);
            images.drawTexture(context, BACKGROUND, 0, 0, 0, 0,
                    CodexLayout.WIDTH, CodexLayout.HEIGHT, CodexLayout.WIDTH, CodexLayout.HEIGHT);
            recomposeBackground(context);

            label(context, "마법 도감", 239, 83, 40, IVORY, TITLE_FONT);
            label(context, "잊힌 마법의 기록", 255, 122, 20, GOLD, HEADING_FONT);
            Rect keys = KeySettingsLayout.ENTRY;
            context.fill(keys.x(), keys.y(), keys.x()+keys.width(), keys.y()+keys.height(), hovering(keys)?0xAA28534F:0xBB142B39);
            context.drawBorder(keys.x(), keys.y(), keys.width(), keys.height(), hovering(keys)?0xFF8AD7C9:0x998E815F);
            centered(context, "키 설정", keys.centerX(), keys.centerY(), 23, IVORY, LABEL_FONT);
            boolean loading = awaitingPermissions();
            String progress = loading
                    ? "마법 기록 불러오는 중"
                    : "발견한 마법  " + state.discoveredCount() + " / " + state.total();
            label(context, progress, 1180, 91, 20, IVORY, LABEL_FONT);
            context.fill(1179, 115, 1179 + (int) (276 * state.discoveredCount() / Math.max(1, state.total())), 125, 0xFF578A83);
            if (loading) {
                readySince = -1;
                renderLoading(context, 1f);
            } else {
                renderCategories(context);
                renderSearch(context);
                renderFilters(context);
                renderCards(context);
                renderDetails(context);
                renderNavigation(context);
                renderTextTooltip(context);
                if (catalogBacked) {
                    if (readySince < 0) readySince = Util.getMeasuringTimeMs();
                    float opacity = 1f - (Util.getMeasuringTimeMs() - readySince) / 180f;
                    if (opacity > 0) renderLoading(context, opacity);
                }
            }
        } finally {
            context.getMatrices().pop();
            images.endFrame();
        }
        entrance.draw(context,width,height);
    }
    private void attachResources(){
        typography=UiResources.text();images=UiResources.images();resourceGeneration=UiResources.generation();wrappedText.clear();
        if(pendingWrap!=null)pendingWrap.close();
        var fontSource=typography;
        pendingWrap=new AsyncUiLoader<>(TEXT_LAYOUT,4,key->fontSource.wrapText(key.text(),key.width(),key.size()),ignored->{});
    }

    private boolean hovering(Rect rect) { return rect.contains(hoverX, hoverY); }

    /** Cover the unconfirmed card state; only show the actual collection after a valid reply. */
    private void renderLoading(DrawContext context, float opacity) {
        int alpha = Math.clamp(Math.round(238 * opacity), 0, 255);
        context.fill(124, 156, 1545, 882, (alpha << 24) | 0x091824);
        long elapsed = Util.getMeasuringTimeMs() - openedAt;
        boolean unavailable = elapsed > 1500 && !PermissionClient.bridgeAvailable();
        boolean delayed = elapsed > 8000;
        int color = (Math.clamp(Math.round(255 * opacity), 0, 255) << 24) | (IVORY & 0xFFFFFF);
        int muted = (Math.clamp(Math.round(220 * opacity), 0, 255) << 24) | (GOLD & 0xFFFFFF);
        context.getMatrices().push();
        context.getMatrices().translate(836, 454, 0);
        if (!unavailable && !delayed)
            context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees((Util.getMeasuringTimeMs() % 4000) * 0.09f));
        spriteCentered(context, CodexSprites.emblem(0), 0, 0, 78, 78, color);
        context.getMatrices().pop();
        String title = unavailable ? "학교와 연결되지 않았습니다" : delayed ? "기록을 기다리고 있습니다" : "마법 기록을 불러오는 중";
        String description = unavailable ? "서버의 도감 연동 상태를 확인해 주세요." : delayed
                ? "응답이 늦어지고 있어요. 잠시 후 다시 열어 주세요." : "잠시만 기다려 주세요.";
        centered(context, title, 836, 533, 27, color, HEADING_FONT);
        centered(context, description, 836, 574, 20, muted, BODY_FONT);
        centered(context, "ESC 닫기", 836, 816, 18, muted, BODY_FONT);
        if (hovering(CodexHitboxes.CLOSE)) {
            Rect close = CodexHitboxes.CLOSE;
            context.drawBorder(close.x(), close.y(), close.width(), close.height(), GOLD);
        }
    }

    /** Reposition existing artwork at draw time, preserving the original PNG and decorative borders. */
    private void recomposeBackground(DrawContext context) {
        // Lower the school crest 32px. Remap the entire interior strip so no old emblem remains.
        baseSlice(context, new Rect(124, 650, 202, 40), new Rect(124, 650, 202, 72));
        baseSlice(context, new Rect(124, 690, 202, 142), new Rect(124, 722, 202, 142));
        baseSlice(context, new Rect(124, 832, 202, 47), new Rect(124, 864, 202, 15));

        // Lower both the empty progress track and its fill, not just its label.
        baseSlice(context, new Rect(1170, 54, 298, 38), new Rect(1170, 54, 298, 56));
        baseSlice(context, new Rect(1170, 92, 298, 21), new Rect(1170, 110, 298, 21));
        baseSlice(context, new Rect(1170, 113, 298, 34), new Rect(1170, 131, 298, 16));
    }

    private void baseSlice(DrawContext context, Rect source, Rect destination) {
        images.drawTexture(context, BACKGROUND, destination.x(), destination.y(),
                source.x(), source.y(), destination.width(), destination.height(), source.width(), source.height(),
                CodexLayout.WIDTH, CodexLayout.HEIGHT);
    }

    private void renderCategories(DrawContext context) {
        for (Category category : Category.values()) {
            Rect box = CodexHitboxes.category(category.ordinal());
            boolean selected = state.category() == category;
            if (selected || hovering(box)) {
                context.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), selected ? 0x99337872 : 0x334E7873);
                if (selected) context.fill(box.x(), box.y(), box.x() + 2, box.y() + box.height(), 0xFF70B8AE);
            }
            spriteCentered(context, CodexSprites.emblem(category.ordinal()), 166, box.centerY(), 39, 39,
                    selected ? 0xFFFFFFFF : 0xBBD9D8C6);
            label(context, category.label, 205, box.centerY(), 21, selected ? IVORY : 0xFFE9E3D4, HEADING_FONT);
        }
    }

    private void renderSearch(DrawContext context) {
        boolean focused = search != null && search.isFocused();
        if (focused || hovering(CodexHitboxes.SEARCH)) context.fill(368, 217, 710, 219, focused ? 0xFF478D86 : GOLD);
        if (state.query().isEmpty() && !focused) {
            label(context, "마법 검색", 403, 200, 20, MUTED, BODY_FONT);
            return;
        }
        if (search == null) return;
        int cursor = search.getCursor();
        int anchor = ((TextFieldAccessor) search).magiccodex$selectionEnd();
        if (focused && cursor != anchor) {
            int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
            int left = 403 + (int) textWidth(state.query().substring(0, start), searchFontSize(), BODY_FONT);
            int right = 403 + (int) textWidth(state.query().substring(0, end), searchFontSize(), BODY_FONT);
            context.fill(left, 187, right, 213, 0x55588F88);
        }
        label(context, state.query(), 403, 200, searchFontSize(), INK, BODY_FONT);
        if (focused && Util.getMeasuringTimeMs() / 500 % 2 == 0) {
            int x = 403 + (int) textWidth(state.query().substring(0, cursor), searchFontSize(), BODY_FONT);
            context.fill(x, 189, x + 1, 212, INK);
        }
    }

    private void renderFilters(DrawContext context) {
        String[] names = {"전체", "발견", "미발견"};
        for (Filter filter : Filter.values()) {
            Rect box = CodexHitboxes.filter(filter.ordinal());
            boolean selected = state.filter() == filter;
            // Reuse the original selected/unselected capsule artwork, including its edge pixels.
            int sourceX = selected ? 735 : 827;
            int sourceWidth = selected ? 83 : 87;
            images.drawTexture(context, BACKGROUND, box.x() - 2, box.y() - 2,
                    sourceX - 2, 178, box.width() + 4, 44, sourceWidth + 4, 44,
                    CodexLayout.WIDTH, CodexLayout.HEIGHT);
            if (hovering(box)) rounded(context, new Rect(box.x() + 2, box.y() + 2, box.width() - 4, box.height() - 4), 0x16FFFFFF);
            centered(context, names[filter.ordinal()], box.centerX(), box.centerY(), 20, selected ? IVORY : INK, LABEL_FONT);
        }
    }

    private void renderCards(DrawContext context) {
        List<Spell> spells = state.visible();
        for (int slot = 0; slot < 9; slot++) {
            Rect box = CodexHitboxes.card(slot);
            // Include the original card's surrounding parchment in each adjoining tile.
            // This covers the old uneven grid without pasting a different paper panel behind it.
            baseSlice(context, new Rect(349, 227, 222, 205),
                    new Rect(box.x() - 8, box.y() - 8, 222, 200));
            if (slot >= spells.size()) {
                continue;
            }
            Spell spell = spells.get(slot);
            boolean selected = spell.equals(state.selected());
            spellArt(context, spell, box.centerX(), box.y() + CodexComposition.ART_CENTER_Y, CodexComposition.ART_SIZE);
            spriteCentered(context, CodexSprites.emblem(spell.category().ordinal()), box.x() + 24, box.y() + 22,
                    27, 27, spell.discovered() ? 0xBBD2DCC4 : 0x557F898C);
            if (!spell.discovered()) spriteCentered(context, CodexSprites.LOCK,
                    box.centerX(), box.y() + 82, 22, 29, 0xFFECE6D5);
            if (selected || hovering(box)) selectionFrame(context, box, selected ? 0xFFFFFFFF : 0x70FFFFFF);
            float nameY = box.y() + CodexComposition.NAME_CENTER_Y;
            centered(context, ellipsize(spell.name(), 180, CodexComposition.NAME_SIZE, HEADING_FONT),
                    box.centerX(), nameY, CodexComposition.NAME_SIZE, INK, HEADING_FONT);
            capsule(context, spell.discovered() ? CodexSprites.DISCOVERED : CodexSprites.UNDISCOVERED,
                    Math.round(box.centerX() - 40), box.y() + CodexComposition.BADGE_TOP, 80, CodexComposition.BADGE_HEIGHT);
            centered(context, !spell.permissionKnown() ? "미확인" : spell.discovered() ? "발견" : "미발견", box.centerX(),
                    box.y() + CodexComposition.BADGE_TOP + CodexComposition.BADGE_HEIGHT / 2f,
                    CodexComposition.BADGE_TEXT_SIZE, IVORY, LABEL_FONT);
        }
        if (spells.isEmpty()) {
            context.fill(379, 480, 990, 563, 0xEEEEE0C5);
            centered(context, state.total() == 0 ? "등록된 마법이 없습니다" : "검색 결과가 없습니다", 680, 508, 24, INK, HEADING_FONT);
            centered(context, state.total() == 0 ? "마법 YAML을 확인하고 /화면 새로고침을 입력하세요."
                    : "검색어나 선택한 분류를 바꿔 보세요.", 680, 541, 20, MUTED, BODY_FONT);
        }
    }

    private void renderDetails(DrawContext context) {
        Spell spell = state.selected();
        if (spell == null) {
            spriteCentered(context, CodexSprites.MEDALLION, 1289, 310, 260, 260, 0x33FFFFFF);
            spriteCentered(context, CodexSprites.emblem(0), 1289, 310, 92, 92, 0xAAAF956B);
            centered(context, "마법을 선택하세요", 1289, 464, 24, INK, HEADING_FONT);
            centered(context, "카드를 누르면 마법의 기록을 볼 수 있습니다.", 1289, 497, 20, MUTED, BODY_FONT);
        } else {
            spriteCentered(context, CodexSprites.MEDALLION, 1289, 309, 330, 330,
                    spell.discovered() ? 0xD9FFFFFF : 0x88C9C7C6);
            spellArt(context, spell, 1289, 304, 247);
            if (!spell.discovered()) spriteCentered(context, CodexSprites.LOCK, 1289, 351, 32, 43, 0xFFF1E7CC);
            String cooldown = "재사용 " + java.math.BigDecimal.valueOf(spell.cooldown()).stripTrailingZeros().toPlainString() + "초";
            label(context, "소모 마나 " + spell.manaCost(), 1076, 196, 18, 0xFF496B7A, LABEL_FONT);
            label(context, cooldown, 1504 - textWidth(cooldown, 18, LABEL_FONT), 196, 18, MUTED, LABEL_FONT);
            float titleSize = Math.max(21, Math.min(28, 28 * 244 / Math.max(244, textWidth(spell.name(), 28, HEADING_FONT))));
            label(context, ellipsize(spell.name(), 244, titleSize, HEADING_FONT), 1076, 464, titleSize, INK, HEADING_FONT);
            hoverTexts.add(new HoverText("마법 이름",spell.name(),new Rect(1076,447,244,34)));
            capsule(context, CodexSprites.TAG, 1368, 450, 136, 28);
            String categoryTag=spell.category().label;
            float tagSize=Math.min(16,16*118/Math.max(118,textWidth(categoryTag,16,LABEL_FONT)));
            centered(context,categoryTag,1436,464,tagSize,IVORY,LABEL_FONT);
            detailParagraph(context,"마법 효과",spell.description(),1076,497,428,21,27,2);
        }
        label(context, "발견 조건", 1076, 580, 22, INK, SECTION_FONT);
        label(context, "연구 기록", 1076, 664, 22, INK, SECTION_FONT);
        if (spell != null) {
            if (spell.permissionKnown() && spell.discovered()) {
                spriteCentered(context, CodexSprites.CHECKED, 1088, 608, 24, 24, 0xFFFFFFFF);
                detailParagraph(context,"발견 조건",spell.condition(),1112,608,392,20,22,2);
            } else {
                label(context, "습득 후 확인할 수 있습니다.", 1076, 608, 20, MUTED, BODY_FONT);
            }
            spriteCentered(context, CodexSprites.emblem(7), 1088, 692, 26, 26, 0xFF978568);
            detailParagraph(context,"연구 기록",spell.research().replaceAll("([1-9])서클","클래스 $1"),1112,692,392,20,24,2);
        } else {
            label(context, "선택한 마법의 발견 조건이 표시됩니다.", 1076, 620, 19, MUTED, BODY_FONT);
            label(context, "선택한 마법의 연구 기록이 표시됩니다.", 1076, 708, 19, MUTED, BODY_FONT);
        }
        String donationLabel = "학교에 기증";
        float iconSize = 39, iconGap = 10;
        float groupWidth = iconSize + iconGap + textWidth(donationLabel, 25, HEADING_FONT);
        float groupLeft = CodexHitboxes.DONATE.centerX() - groupWidth / 2;
        spriteCentered(context, CodexSprites.emblem(0), groupLeft + iconSize / 2, 777, 39, 39, 0xFFFFFFFF);
        label(context, donationLabel, groupLeft + iconSize + iconGap, 777, 25, IVORY, HEADING_FONT);
        String donor=spell==null?"마법을 선택해 주세요.":"최초 기증자 · "+SchoolClient.donor(spell.id());
        centered(context, donor, 1290, 804, 16, 0xFFD3DFD4, BODY_FONT);
        String footer = !notice.isEmpty() ? notice : catalogBacked && !CodexCatalog.status().isEmpty()
                ? CodexCatalog.status() : "ESC 닫기  ·  / 명령어";
        centered(context, ellipsize(footer,428,17,BODY_FONT), 1290, 849, 17, MUTED, BODY_FONT);
    }

    private void renderNavigation(DrawContext context) {
        centered(context, (state.page() + 1) + " / " + state.pages(), 680, 853, 22, INK, LABEL_FONT);
        for (int i = 0; i < 2; i++) {
            Rect box = i == 0 ? CodexHitboxes.PREVIOUS : CodexHitboxes.NEXT;
            boolean enabled = i == 0 ? state.page() > 0 : state.page() + 1 < state.pages();
            if (!enabled) context.fill(box.x() + 2, box.y() + 2, box.x() + box.width() - 2, box.y() + box.height() - 2, 0x99ECDDCA);
            else if (hovering(box)) context.drawBorder(box.x(), box.y(), box.width(), box.height(), 0xFF477D73);
        }
        if (hovering(CodexHitboxes.CLOSE)) {
            Rect box = CodexHitboxes.CLOSE;
            context.drawBorder(box.x(), box.y(), box.width(), box.height(), GOLD);
        }
    }

    private void spellArt(DrawContext context, Spell spell, float centerX, float centerY, int size) {
        int tint = spell.discovered() ? 0xFFFFFFFF : 0x887B8085;
        if (!images.drawCentered(context, spell.icon(), centerX, centerY, size, tint)) spriteCentered(context, CodexSprites.emblem(spell.category().ordinal()), centerX, centerY, size, size, tint);
    }

    private void spriteCentered(DrawContext context, Sprite sprite, float x, float y, int width, int height, int color) {
        sprite(context, sprite, Math.round(x - width / 2f), Math.round(y - height / 2f), width, height, color);
    }

    private void sprite(DrawContext context, Sprite sprite, int x, int y, int width, int height, int color) {
        images.drawTexture(context, id("textures/gui/" + sprite.file()), x, y,
                sprite.x(), sprite.y(), width, height, sprite.width(), sprite.height(),
                sprite.textureWidth(), sprite.textureHeight(), color);
    }

    /** Keep the rounded ends circular when fitting the very wide source capsules. */
    private void capsule(DrawContext context, Sprite sprite, int x, int y, int width, int height) {
        int sourceCap = sprite.height() / 2;
        int cap = Math.min(width / 2, height / 2);
        Identifier texture = id("textures/gui/" + sprite.file());
        images.drawTexture(context, texture, x, y, sprite.x(), sprite.y(),
                cap, height, sourceCap, sprite.height(), sprite.textureWidth(), sprite.textureHeight());
        images.drawTexture(context, texture, x + cap, y, sprite.x() + sourceCap, sprite.y(),
                width - cap * 2, height, sprite.width() - sourceCap * 2, sprite.height(), sprite.textureWidth(), sprite.textureHeight());
        images.drawTexture(context, texture, x + width - cap, y,
                sprite.x() + sprite.width() - sourceCap, sprite.y(), cap, height, sourceCap, sprite.height(),
                sprite.textureWidth(), sprite.textureHeight());
    }

    private void selectionFrame(DrawContext context, Rect box, int color) {
        images.drawTexture(context, SELECTION_FRAME, box.x() - 3, box.y() - 3,
                74, 108, box.width() + 6, box.height() + 6, 1106, 1038, 1254, 1254, color);
    }

    private void rounded(DrawContext context, Rect box, int color) {
        double radius = box.height() / 2.0;
        for (int row = 0; row < box.height(); row++) {
            double dy = Math.abs(row + 0.5 - radius);
            int inset = (int) Math.ceil(radius - Math.sqrt(radius * radius - dy * dy));
            context.fill(box.x() + inset, box.y() + row, box.x() + box.width() - inset, box.y() + row + 1, color);
        }
    }

    private float textWidth(String value, float size, Identifier font) {
        return typography.width(value, size, font);
    }

    private float searchFontSize() {
        float width = textWidth(state.query(), 20, BODY_FONT);
        return width > 300 ? 20 * 300 / width : 20;
    }

    private String ellipsize(String value, float width, float size, Identifier font) {
        if (textWidth(value, size, font) <= width) return value;
        while (!value.isEmpty() && textWidth(value + "…", size, font) > width)
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        return value + "…";
    }

    private void paragraph(DrawContext context, String value, float x, float y, float maxWidth,
                           float size, float lineHeight, int maxLines) {
        var lines=CodexTextLayout.preview(wrap(value,maxWidth,size),maxLines,maxWidth,s->textWidth(s,size,BODY_FONT));
        for(int line=0;line<lines.size();line++)label(context,lines.get(line),x,y+line*lineHeight,size,INK,BODY_FONT);
    }

    private List<String> wrap(String value,float width,float size) {
        if(wrappedText.size()>64)wrappedText.clear();
        java.util.Map.Entry<WrapKey,AsyncUiLoader.Result<List<String>>> ready;
        while((ready=pendingWrap.takeReady())!=null){
            var result=ready.getValue();wrappedText.put(ready.getKey(),result.error()==null?result.value():List.of("기록을 표시할 수 없습니다."));
        }
        var key=new WrapKey(value,width,size);
        if(value.length()>256 && !wrappedText.containsKey(key)){
            pendingWrap.request(key);return List.of("내용을 불러오는 중…");
        }
        return wrappedText.computeIfAbsent(key,
                ignored->CodexTextLayout.wrap(value,width,s->textWidth(s,size,BODY_FONT)));
    }

    private void detailParagraph(DrawContext context,String title,String value,int x,int y,int width,
                                 int size,int lineHeight,int maxLines) {
        paragraph(context,value,x,y,width,size,lineHeight,maxLines);
        if(!value.isBlank())hoverTexts.add(new HoverText(title,value,new Rect(x,y-size/2-3,width,(maxLines-1)*lineHeight+size+6)));
    }

    private void renderTextTooltip(DrawContext context) {
        HoverText target=hoverTexts.stream().filter(t->hovering(t.area())).findFirst().orElse(null);
        if(!java.util.Objects.equals(target,activeText)) {
            activeText=target;hoverStarted=Util.getMeasuringTimeMs();tooltipScroll=tooltipMaxScroll=0;
        }
        if(target==null || Util.getMeasuringTimeMs()-hoverStarted<250)return;
        var lines=wrap(target.text(),440,23);
        int visible=Math.min(18,lines.size());
        tooltipMaxScroll=Math.max(0,lines.size()-visible);
        tooltipScroll=Math.clamp(tooltipScroll,0,tooltipMaxScroll);
        int h=64+visible*29+(tooltipMaxScroll>0?30:0);
        // Beside the detail column, so the source text stays under the cursor. Bounds stay inside the UI.
        int x=target.area().x()-500,y=Math.clamp(target.area().y()-20,165,875-h);
        context.getMatrices().push();
        try {
            context.getMatrices().translate(0,0,300);
            context.fill(x+5,y+5,x+485,y+h+5,0x55000000);
            context.fill(x,y,x+480,y+h,0xF5112530);
            context.drawBorder(x,y,480,h,0xFF779D9C);
            label(context,target.title(),x+20,y+24,21,GOLD,LABEL_FONT);
            context.fill(x+20,y+43,x+460,y+44,0x555EA9AC);
            for(int i=0;i<visible;i++)label(context,lines.get(tooltipScroll+i),x+20,y+65+i*29,23,IVORY,BODY_FONT);
            if(tooltipMaxScroll>0)label(context,"마우스 휠로 더 보기  ·  "+(tooltipScroll+1)+"–"+(tooltipScroll+visible)+" / "+lines.size(),x+20,y+h-18,17,0xFF9DBDBF,BODY_FONT);
        } finally {context.getMatrices().pop();}
    }

    private void label(DrawContext context, String value, float x, float y,
                       float size, int color, Identifier font) {
        drawLabel(context, value, x, y, size, color, font, false);
    }

    private void centered(DrawContext context, String value, float x, float y,
                          float size, int color, Identifier font) {
        drawLabel(context, value, x, y, size, color, font, true);
    }

    private void drawLabel(DrawContext context, String value, float x, float y,
                           float size, int color, Identifier font, boolean centered) {
        typography.draw(context, value, x, y, size, color, font, centered);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        CodexLayout layout = CodexLayout.codexFit(width, height);
        double x = layout.localX(mouseX), y = layout.localY(mouseY);
        if (CodexHitboxes.CLOSE.contains(x, y)) { close(); return true; }
        if (KeySettingsLayout.ENTRY.contains(x, y)) {
            playSound(SoundCue.BUTTON);
            client.setScreen(new KeySettingsScreen(this));
            return true;
        }
        if (awaitingPermissions()) return true;
        if (CodexHitboxes.SEARCH.contains(x, y)) {
            if (!search.isFocused()) playSound(SoundCue.BUTTON);
            search.setFocused(true);
            int cursor = 0;
            for (int i = 0; i < state.query().length();) {
                int next = i + Character.charCount(state.query().codePointAt(i));
                float midpoint = (textWidth(state.query().substring(0, i), searchFontSize(), BODY_FONT)
                        + textWidth(state.query().substring(0, next), searchFontSize(), BODY_FONT)) / 2;
                if (x - 403 < midpoint) break;
                cursor = next;
                i = next;
            }
            search.setCursor(cursor, hasShiftDown());
            return true;
        }
        search.setFocused(false);
        for (Category category : Category.values()) {
            if (CodexHitboxes.category(category.ordinal()).contains(x, y)) {
                if (state.category() != category) {
                    state.setCategory(category); notice = ""; playSound(SoundCue.BUTTON);
                }
                return true;
            }
        }
        for (Filter filter : Filter.values()) {
            if (CodexHitboxes.filter(filter.ordinal()).contains(x, y)) {
                if (state.filter() != filter) {
                    state.setFilter(filter); notice = ""; playSound(SoundCue.BUTTON);
                }
                return true;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (CodexHitboxes.card(slot).contains(x, y)) {
                Spell previous = state.selected();
                if (state.selectSlot(slot)) {
                    notice = "";
                    if (!state.selected().equals(previous)) playSound(SoundCue.CARD);
                }
                return true;
            }
        }
        if (CodexHitboxes.PREVIOUS.contains(x, y)) { turnPage(-1); return true; }
        if (CodexHitboxes.NEXT.contains(x, y)) { turnPage(1); return true; }
        if (CodexHitboxes.DONATE.contains(x, y)) {
            SchoolClient.donate(state.selected(),this);
            playSound(SoundCue.BUTTON);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (awaitingPermissions()) return false;
        CodexLayout layout = CodexLayout.codexFit(width, height);
        if(activeText!=null && activeText.area().contains(layout.localX(mouseX),layout.localY(mouseY))
                && tooltipMaxScroll>0 && verticalAmount!=0) {
            tooltipScroll=Math.clamp(tooltipScroll+(verticalAmount<0?3:-3),0,tooltipMaxScroll);
            return true;
        }
        if (new Rect(350, 230, 665, 595).contains(layout.localX(mouseX), layout.localY(mouseY)) && verticalAmount != 0) {
            turnPage(verticalAmount < 0 ? 1 : -1);
            return true;
        }
        return false;
    }

    private void turnPage(int direction) {
        if (state.changePage(direction)) { notice = ""; playSound(SoundCue.PAGE); }
    }

    private void playSound(SoundCue cue) {
        if (client == null) return;
        long now = Util.getMeasuringTimeMs();
        // Avoid overlapping copies when a wheel or repeated click fires rapidly.
        if (cue == lastSound && now - lastSoundTime < 100) return;
        lastSound = cue;
        lastSoundTime = now;
        client.getSoundManager().play(PositionedSoundInstance.master(cue.event, cue.pitch, cue.volume));
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (awaitingPermissions()) return false;
        return search != null && search.isFocused() ? search.charTyped(chr, modifiers) : super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (awaitingPermissions() && keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (awaitingPermissions() && search != null) search.setFocused(false);
        if (search != null && search.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                search.setFocused(false);
                return true;
            }
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (client != null && (keyCode == GLFW.GLFW_KEY_SLASH
                || client.options.commandKey.matchesKey(keyCode, scanCode))) {
            MagicCodexClient.requestChat("/");
            return true;
        }
        if (client != null && client.options.chatKey.matchesKey(keyCode, scanCode)) {
            MagicCodexClient.requestChat("");
            return true;
        }
        if (MagicCodexClient.matchesOpenKey(keyCode, scanCode)) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        playSound(SoundCue.CLOSE);
        MagicCodexClient.dismiss();
        super.close();
    }

    @Override
    public void removed() {
        if(pendingWrap!=null){pendingWrap.close();pendingWrap=null;}
        typography = null;
        invalidateImageCache();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void invalidateImageCache() { images=null;typography=null; }
    public void verifyImageRenderer() {
        if (images == null || images.size() == 0) throw new IllegalStateException("No codex images");
        images.verifyGpuState();
    }
}
