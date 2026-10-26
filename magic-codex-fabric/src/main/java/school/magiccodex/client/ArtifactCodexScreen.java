package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.ArtifactCodexLayout.Rect;
import school.magiccodex.protocol.ArtifactProtocol;
import school.magiccodex.protocol.ArtifactProtocol.Entry;

/**
 * 아티팩트 도감 (approved simplified design: no bottom equip bar, no category chips, no acquisition line).
 * Frames are the approved PNGs in textures/gui/artifact; every word is drawn with the MagicCodex fonts through
 * CodexTypography. Icons come from artifacts.yml (blank until the icon art exists).
 * Data is server-owned (ArtifactClient); this screen only filters, pages and sends equip/unequip ids.
 */
final class ArtifactCodexScreen extends Screen {
    private static Identifier tex(String file) { return Identifier.of("magiccodex", "textures/gui/artifact/" + file); }
    private static final Identifier PANEL = tex("main_panel.png"), DETAIL = tex("detail_panel.png"), BUTTON = tex("button.png"),
            TOOLTIP = tex("tooltip.png"), LOCK = tex("lock.png"), COMPASS = tex("compass.png");
    private static final Identifier[] CARDS = {tex("card_common.png"), tex("card_rare.png"), tex("card_unique.png"), tex("card_legendary.png")};
    private static final Identifier TITLE = font("title"), BOLD = font("hud_bold"), LABEL = font("label"), BODY = font("body");
    private static Identifier font(String name) { return Identifier.of("magiccodex", name); }

    /** Card frame geometry in card_*.png (627 px): outer border box, plate (name) centre and art centre as fractions. */
    private record CardArt(int left, int top, int right, int bottom, float art, float plate) {}
    private static final CardArt[] CARD_ART = {
            new CardArt(48, 52, 578, 572, .406f, .887f),
            new CardArt(42, 58, 584, 564, .404f, .875f),
            new CardArt(44, 53, 582, 563, .412f, .893f),
            new CardArt(48, 59, 578, 554, .404f, .879f)};

    static final int[] GRADE_COLORS = {0xFFDCE3EA, 0xFF71E5F2, 0xFFD2A8FF, 0xFFFFD582};
    private static final int GOLD = 0xFFE7C77E, GOLD_LINE = 0xFFC9A55C, IVORY = 0xFFF3E9D3, TEXT = 0xFFE4E9EF, MUTED = 0xFF8F9CB0, DIM = 0xFF6B7A90;
    private static final String[] TAB_NAMES = {"전체", "일반", "고급", "유일", "전설"};
    private static final String[] SORT_NAMES = {"등급순", "이름순", "보유순"};

    private final Screen parent;
    private TextFieldWidget search;
    private String query = "";
    private int tab, sort, page;
    private boolean ownedOnly, sortOpen, searchFocused;
    private String selected = "";
    private String pending = "";
    private long pendingVersion, pendingDeadline, seenVersion = -1;
    private String notice = "";
    private final Map<String, List<String>> wraps = new HashMap<>();

    /** #108 하우징 아티팩트 진열장 구경 모드 (읽기 전용): 방 주인이 장착한 아티팩트만, 장착·해제 버튼 없음. null = 보통 도감. */
    private final List<String> showcase;
    private final String showcaseOwner;

    ArtifactCodexScreen(Screen parent) { this(parent, null, null); }
    /** #108 구경 모드. 디자인 납품 대기 — HousingShowcaseLayout 참고. */
    ArtifactCodexScreen(Screen parent, String owner, List<String> showcase) {
        super(Text.literal("아티팩트 도감")); this.parent = parent;
        this.showcase = showcase == null ? null : List.copyOf(showcase); this.showcaseOwner = owner == null ? "" : owner;
    }

    @Override protected void init() {
        ArtifactClient.query();
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 24, Text.literal("아티팩트 이름 검색"));
        search.setMaxLength(20);
        search.setText(query);
        search.setChangedListener(v -> { query = v; page = 0; });
    }

    // ------------------------------------------------------------------ data view

    private record Item(int index, Entry entry, boolean owned, boolean equipped) {}

    private List<Item> visible() {
        var catalog = ArtifactClient.catalog();
        var equipped = ArtifactClient.equipped();
        String q = query.strip().toLowerCase(Locale.ROOT);
        var list = new ArrayList<Item>();
        for (int i = 0; i < catalog.size(); i++) {
            Entry e = catalog.get(i);
            if (showcase != null) { // 구경 모드: 주인이 장착한 것만, 이름·그림은 보여 줌 (주인이 전시한 것)
                if (!showcase.contains(e.id()) || tab > 0 && e.grade() != tab - 1) continue;
                if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q)) continue;
                list.add(new Item(i, e, true, true));
                continue;
            }
            boolean owned = ArtifactClient.owned(i);
            if (tab > 0 && e.grade() != tab - 1) continue;
            if (ownedOnly && !owned) continue;
            // Locked names are hidden, so a search never reveals them.
            if (!q.isEmpty() && (!owned || !e.name().toLowerCase(Locale.ROOT).contains(q))) continue;
            list.add(new Item(i, e, owned, equipped.contains(e.id())));
        }
        Comparator<Item> order = switch (sort) {
            case 1 -> Comparator.comparing((Item it) -> it.owned() ? it.entry().name() : "￿").thenComparingInt(Item::index);
            case 2 -> Comparator.comparing((Item it) -> !it.owned()).thenComparingInt(Item::index);
            default -> Comparator.comparingInt(Item::index); // server order: grade, then order, then id
        };
        list.sort(order);
        return list;
    }

    private int pages(int count) { return Math.max(1, (count + ArtifactCodexLayout.PAGE_SIZE - 1) / ArtifactCodexLayout.PAGE_SIZE); }

    private Item selectedItem(List<Item> items) {
        for (Item it : items) if (it.entry().id().equals(selected)) return it;
        if (showcase != null) return null;
        var catalog = ArtifactClient.catalog();
        for (int i = 0; i < catalog.size(); i++) if (catalog.get(i).id().equals(selected))
            return new Item(i, catalog.get(i), ArtifactClient.owned(i), ArtifactClient.equipped().contains(selected));
        return null;
    }

    @Override public void tick() {
        if (client == null || client.player == null || !client.player.isAlive()) { close(); return; }
        if (ArtifactClient.version() != seenVersion) {
            seenVersion = ArtifactClient.version();
            if (!pending.isEmpty() && seenVersion != pendingVersion) { pending = ""; notice = ArtifactClient.notice(); }
        }
        if (!pending.isEmpty() && Util.getMeasuringTimeMs() > pendingDeadline) { pending = ""; notice = "서버 응답이 늦습니다. 다시 시도해 주세요."; ArtifactClient.query(); }
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        c.fill(0, 0, width, height, 0x88070D13);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            images.drawTexture(c, PANEL, 0, 0, 0, 0, 1672, 941, 1672, 941);
            header(c, mx, my);
            var items = visible();
            page = Math.clamp(page, 0, pages(items.size()) - 1);
            if (selected.isEmpty() && !items.isEmpty()) selected = items.get(0).entry().id();
            filters(c, mx, my, items);
            cards(c, mx, my, items);
            pager(c, mx, my, items.size());
            Item sel = selectedItem(items);
            detail(c, mx, my, sel);
            if (sortOpen) sortMenu(c, mx, my);
            if (sel != null && sel.owned() && !sel.entry().effect().isBlank() && ArtifactCodexLayout.DESCRIPTION_HOVER.contains(mx, my) && !sortOpen) tooltip(c, sel.entry());
        } finally { m.pop(); images.endFrame(); }
    }

    private void header(DrawContext c, double mx, double my) {
        var r = ArtifactCodexLayout.COMPASS;
        UiResources.images().drawTexture(c, COMPASS, r.x(), r.y(), 0, 0, r.w(), r.h(), 166, 168, 166, 168);
        String title = showcase == null ? "아티팩트 도감" : String.format(HousingShowcaseLayout.ARTIFACT_VIEW_TITLE, showcaseOwner);
        float size = 44, tw = UiResources.text().width(title, size, TITLE);
        float cx = ArtifactCodexLayout.TITLE_X, cy = ArtifactCodexLayout.TITLE_Y;
        text(c, title, cx, cy, size, 0xFFF2D9A0, TITLE, Align.CENTER);
        for (int side = -1; side <= 1; side += 2) {
            float inner = cx + side * (tw / 2 + 26), outer = cx + side * (tw / 2 + 150);
            HudMesh.line(c, Math.min(inner, outer), cy, Math.max(inner, outer), cy, 2f, 0xCCC9A55C);
            HudMesh.star(c, inner, cy, 6, GOLD);
        }
        var x = ArtifactCodexLayout.CLOSE;
        int color = x.contains(mx, my) ? 0xFFFFE3A8 : GOLD;
        float s = 15;
        HudMesh.line(c, x.cx() - s, x.cy() - s, x.cx() + s, x.cy() + s, 4, color);
        HudMesh.line(c, x.cx() - s, x.cy() + s, x.cx() + s, x.cy() - s, 4, color);
    }

    private void filters(DrawContext c, double mx, double my, List<Item> items) {
        // Search box
        var s = ArtifactCodexLayout.SEARCH;
        box(c, s, 0xCC0B1830, searchFocused ? 0xFF7FC9D6 : s.contains(mx, my) ? 0xFFB49A66 : 0xFF56657E, 1.5f);
        float iconX = s.x() + 30, iconY = s.cy() - 2;
        HudMesh.arc(c, iconX, iconY, 9, 2.4f, 0, (float) (Math.PI * 2), 0xFFC9D2DE);
        HudMesh.line(c, iconX + 6.5f, iconY + 6.5f, iconX + 13, iconY + 13, 2.6f, 0xFFC9D2DE);
        float textX = s.x() + 58, size = 20;
        if (query.isEmpty() && !searchFocused) text(c, "아티팩트 이름 검색", textX, s.cy(), size, MUTED, BODY, Align.LEFT);
        else {
            String shown = fit(query, size, BODY, s.w() - 76);
            text(c, shown, textX, s.cy(), size, TEXT, BODY, Align.LEFT);
            if (searchFocused && Util.getMeasuringTimeMs() / 500 % 2 == 0) {
                float cursor = textX + UiResources.text().width(shown, size, BODY) + 2;
                c.fill((int) cursor, (int) s.cy() - 12, (int) cursor + 2, (int) s.cy() + 12, TEXT);
            }
        }
        // Grade tabs
        for (int i = 0; i < 5; i++) {
            var r = ArtifactCodexLayout.tab(i);
            boolean on = tab == i, over = r.contains(mx, my);
            int accent = i == 0 ? GOLD : GRADE_COLORS[i - 1];
            box(c, r, on ? 0xFF1B2D4C : 0xCC0B1830, on ? accent : over ? blend(accent, 0xFF56657E, .55f) : blend(accent, 0xFF2E3D56, .45f), on ? 2.2f : 1.5f);
            if (on) HudMesh.line(c, r.x() + 10, r.bottom() - 4, r.right() - 10, r.bottom() - 4, 2, alpha(accent, .55f));
            text(c, TAB_NAMES[i], r.cx(), r.cy(), 22, on ? brighten(accent) : i == 0 ? 0xFFE8E2D2 : accent, BOLD, Align.CENTER);
        }
        // Collection count
        var all = ArtifactClient.catalog().size();
        String count = showcase == null ? "수집 " + ArtifactClient.ownedCount() + " / " + all : String.format(HousingShowcaseLayout.ARTIFACT_VIEW_COUNT, showcase.size());
        text(c, count, ArtifactCodexLayout.COUNT_RIGHT, ArtifactCodexLayout.ROW1_Y, 23, 0xFFE9E3D4, LABEL, Align.RIGHT);
        // Sort dropdown
        var so = ArtifactCodexLayout.SORT;
        box(c, so, 0xCC0B1830, sortOpen || so.contains(mx, my) ? 0xFFB49A66 : 0xFF56657E, 1.5f);
        text(c, SORT_NAMES[sort], so.x() + 18, so.cy(), 19, TEXT, LABEL, Align.LEFT);
        float ax = so.right() - 24, ay = so.cy();
        HudMesh.line(c, ax - 6, ay - 3, ax, ay + 3, 2, 0xFFC9D2DE); HudMesh.line(c, ax, ay + 3, ax + 6, ay - 3, 2, 0xFFC9D2DE);
        // Owned-only checkbox
        var cb = ArtifactCodexLayout.OWNED_BOX;
        boolean overBox = ArtifactCodexLayout.OWNED_HIT.contains(mx, my);
        box(c, cb, 0xCC0B1830, ownedOnly ? GOLD : overBox ? 0xFFB49A66 : 0xFF6E7C92, 1.5f);
        if (ownedOnly) {
            HudMesh.line(c, cb.x() + 6, cb.cy(), cb.x() + 11, cb.bottom() - 7, 2.6f, GOLD);
            HudMesh.line(c, cb.x() + 11, cb.bottom() - 7, cb.right() - 5, cb.y() + 7, 2.6f, GOLD);
        }
        text(c, "보유한 것만", cb.right() + 12, cb.cy(), 19, overBox ? 0xFFF1EADB : 0xFFD7DCE3, LABEL, Align.LEFT);
    }

    private void cards(DrawContext c, double mx, double my, List<Item> items) {
        if (!ArtifactClient.supported()) {
            text(c, ArtifactClient.channelOpen() ? "도감을 불러오는 중입니다…" : "이 서버는 아티팩트 도감을 지원하지 않습니다.",
                    584, 530, 22, MUTED, LABEL, Align.CENTER);
            return;
        }
        if (items.isEmpty()) {
            text(c, ArtifactClient.catalog().isEmpty() ? "등록된 아티팩트가 없습니다." : showcase != null && showcase.isEmpty() ? "장착한 아티팩트가 없습니다." : "조건에 맞는 아티팩트가 없습니다.", 584, 530, 22, MUTED, LABEL, Align.CENTER);
            return;
        }
        for (int slot = 0; slot < ArtifactCodexLayout.PAGE_SIZE; slot++) {
            int index = page * ArtifactCodexLayout.PAGE_SIZE + slot;
            if (index >= items.size()) break;
            Item it = items.get(index);
            var r = ArtifactCodexLayout.card(slot);
            int grade = it.entry().grade();
            CardArt art = CARD_ART[grade];
            boolean over = r.contains(mx, my) && !sortOpen, chosen = it.entry().id().equals(selected);
            if (chosen) glow(c, r, GRADE_COLORS[grade]);
            cardFrame(c, CARDS[grade], art, r, it.owned() ? 0xFFFFFFFF : 0xFFB9C0CC);
            float artY = r.y() + art.art() * r.h(), plateY = r.y() + art.plate() * r.h();
            if (it.owned()) icon(c, it.entry(), r.cx(), artY, Math.round(r.h() * .48f), 0xFFFFFFFF);
            else {
                c.fill(r.x() + 8, r.y() + 8, r.right() - 8, (int) (r.y() + art.plate() * r.h() - 22), 0x55060C18);
                UiResources.images().drawTexture(c, LOCK, Math.round(r.cx() - 23), Math.round(artY - 23), 0, 0, 46, 46, 256, 256, 256, 256);
            }
            String name = it.owned() ? it.entry().name() : "???";
            text(c, fit(name, 18, LABEL, r.w() - 30), r.cx(), plateY, 18, it.owned() ? 0xFFEDE6D6 : 0xFF9AA7B8, LABEL, Align.CENTER);
            if (it.equipped()) chip(c, r.x() + 14, r.y() + 14, "장착 중");
            if (over) c.fill(r.x() + 6, r.y() + 6, r.right() - 6, r.bottom() - 6, 0x14FFFFFF);
        }
    }

    private void pager(DrawContext c, double mx, double my, int count) {
        int pages = pages(count);
        boolean prev = page > 0, next = page + 1 < pages;
        chevron(c, ArtifactCodexLayout.PREVIOUS, -1, prev ? ArtifactCodexLayout.PREVIOUS.contains(mx, my) ? 0xFFFFE3A8 : GOLD : 0xFF4A5871);
        chevron(c, ArtifactCodexLayout.NEXT, 1, next ? ArtifactCodexLayout.NEXT.contains(mx, my) ? 0xFFFFE3A8 : GOLD : 0xFF4A5871);
        text(c, (page + 1) + " / " + pages, ArtifactCodexLayout.PAGE_X, ArtifactCodexLayout.PAGE_Y, 26, 0xFFE9E3D4, LABEL, Align.CENTER);
    }

    private void detail(DrawContext c, double mx, double my, Item sel) {
        nine(c, DETAIL, 512, 768, 27, 16, 485, 750, 60, ArtifactCodexLayout.DETAIL, 0xFFFFFFFF);
        if (sel == null) {
            text(c, "아티팩트를 선택해 주세요.", ArtifactCodexLayout.DETAIL.cx(), 520, 21, MUTED, LABEL, Align.CENTER);
            return;
        }
        Entry e = sel.entry();
        int gradeColor = GRADE_COLORS[e.grade()];
        if (sel.owned()) icon(c, e, ArtifactCodexLayout.ICON_X, ArtifactCodexLayout.ICON_Y, ArtifactCodexLayout.ICON_SIZE, 0xFFFFFFFF);
        else UiResources.images().drawTexture(c, LOCK, Math.round(ArtifactCodexLayout.ICON_X - 60), Math.round(ArtifactCodexLayout.ICON_Y - 60), 0, 0, 120, 120, 256, 256, 256, 256);
        String grade = ArtifactProtocol.GRADE_LABELS[e.grade()];
        float gradeSize = 27, gradeWidth = UiResources.text().width(grade, gradeSize, BOLD);
        text(c, grade, ArtifactCodexLayout.TEXT_RIGHT, ArtifactCodexLayout.NAME_Y, gradeSize, gradeColor, BOLD, Align.RIGHT);
        String name = sel.owned() ? e.name() : "???";
        text(c, fit(name, 34, TITLE, ArtifactCodexLayout.TEXT_WIDTH - gradeWidth - 20), ArtifactCodexLayout.TEXT_LEFT, ArtifactCodexLayout.NAME_Y, 34, IVORY, TITLE, Align.LEFT);
        divider(c, ArtifactCodexLayout.DIVIDER_Y);
        // Description only; hovering it shows the full effect (tooltip).
        String description = sel.owned() ? e.description() : "아직 발견하지 못한 아티팩트입니다.";
        paragraph(c, description, ArtifactCodexLayout.DESCRIPTION_Y, 22, ArtifactCodexLayout.DESCRIPTION_LINE, ArtifactCodexLayout.DESCRIPTION_LINES, 0xFFE4E9EF, BODY);
        boolean busy = !pending.isEmpty();
        if (showcase != null) return; // 구경 모드: 장착·해제 없음
        button(c, ArtifactCodexLayout.EQUIP, busy && !sel.equipped() ? "확인 중…" : sel.equipped() ? "장착 중" : "장착", sel.owned() && !sel.equipped() && !busy, mx, my);
        button(c, ArtifactCodexLayout.UNEQUIP, busy && sel.equipped() ? "확인 중…" : "해제", sel.equipped() && !busy, mx, my);
        if (!notice.isBlank()) text(c, fit(notice, 17, LABEL, ArtifactCodexLayout.DETAIL.w() - 40), ArtifactCodexLayout.DETAIL.cx(), ArtifactCodexLayout.NOTICE_Y, 17, 0xFFB4C7D7, LABEL, Align.CENTER);
    }

    private void sortMenu(DrawContext c, double mx, double my) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(0, 0, 400);
            var first = ArtifactCodexLayout.sortOption(0);
            var all = new Rect(first.x(), first.y(), first.w(), first.h() * SORT_NAMES.length);
            box(c, all, 0xF20B1830, 0xFFB49A66, 1.5f);
            for (int i = 0; i < SORT_NAMES.length; i++) {
                var r = ArtifactCodexLayout.sortOption(i);
                if (r.contains(mx, my)) c.fill(r.x() + 2, r.y() + 2, r.right() - 2, r.bottom() - 2, 0x2AFFFFFF);
                text(c, SORT_NAMES[i], r.x() + 18, r.cy(), 19, i == sort ? GOLD : TEXT, LABEL, Align.LEFT);
            }
        } finally { m.pop(); }
    }

    private void tooltip(DrawContext c, Entry e) {
        float width = ArtifactCodexLayout.TOOLTIP_WIDTH, pad = 24, size = 20, line = 30;
        var lines = wrap(e.effect(), width - pad * 2, size, BODY);
        String use = e.usable() ? "사용 키 [" + ArtifactClient.useKeyLabel() + "] · " + (e.cooldown() == 0 ? "재사용 대기 없음" : "재사용 대기 " + duration(e.cooldown())) : null;
        float height = 24 + 30 + lines.size() * line + (use == null ? 0 : line + 6) + 22;
        int x = Math.round(ArtifactCodexLayout.TOOLTIP_RIGHT - width), y = Math.round(ArtifactCodexLayout.TOOLTIP_TOP);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(0, 0, 500);
            nine(c, TOOLTIP, 992, 396, 9, 93, 981, 294, 45, new Rect(x, y, Math.round(width), Math.round(height)), 0xFFFFFFFF);
            text(c, "상세 효과", x + pad, y + 24 + 13, 21, GOLD, BOLD, Align.LEFT);
            for (int i = 0; i < lines.size(); i++) text(c, lines.get(i), x + pad, y + 24 + 30 + line / 2f + i * line, size, 0xFFE4E9EF, BODY, Align.LEFT);
            if (use != null) text(c, use, x + pad, y + 24 + 30 + 6 + line / 2f + lines.size() * line, 19, 0xFF71E5F2, LABEL, Align.LEFT);
        } finally { m.pop(); }
    }

    /** 90 -> "1분 30초", 1800 -> "30분". */
    static String duration(int seconds) {
        int h = seconds / 3600, mnt = seconds % 3600 / 60, sec = seconds % 60;
        var b = new StringBuilder();
        if (h > 0) b.append(h).append("시간");
        if (mnt > 0) b.append(b.isEmpty() ? "" : " ").append(mnt).append("분");
        if (sec > 0 || b.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(sec).append("초");
        return b.toString();
    }

    // ------------------------------------------------------------------ pieces

    private void paragraph(DrawContext c, String value, float firstCenterY, float size, float lineHeight, int maxLines, int color, Identifier face) {
        var lines = wrap(value, ArtifactCodexLayout.TEXT_WIDTH, size, face);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            String s = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) s = fit(s + "…", size, face, ArtifactCodexLayout.TEXT_WIDTH);
            text(c, s, ArtifactCodexLayout.TEXT_LEFT, firstCenterY + i * lineHeight, size, color, face, Align.LEFT);
        }
    }

    private void divider(DrawContext c, float y) {
        float left = ArtifactCodexLayout.TEXT_LEFT, right = ArtifactCodexLayout.TEXT_RIGHT, mid = (left + right) / 2;
        // 2 units (~1.3 px at the usual GUI scale): thinner lines fall between pixel centres and vanish in game.
        HudMesh.line(c, left, y, mid - 12, y, 2f, 0xCCC9A55C);
        HudMesh.line(c, mid + 12, y, right, y, 2f, 0xCCC9A55C);
        HudMesh.star(c, mid, y, 6, GOLD);
        HudMesh.star(c, left, y, 3.5f, GOLD_LINE);
        HudMesh.star(c, right, y, 3.5f, GOLD_LINE);
    }

    private void button(DrawContext c, Rect r, String label, boolean enabled, double mx, double my) {
        boolean over = enabled && r.contains(mx, my);
        nine(c, BUTTON, 1086, 362, 29, 78, 1057, 282, 100, r, enabled ? over ? 0xFFFFFFFF : 0xFFE2E6EC : 0xFF7D8796);
        if (over) c.fill(r.x() + 10, r.y() + 8, r.right() - 10, r.bottom() - 8, 0x18FFFFFF);
        text(c, label, r.cx(), r.cy(), 25, enabled ? 0xFFF1E3C2 : 0xFF8A97A8, BOLD, Align.CENTER);
    }

    private void chip(DrawContext c, float x, float y, String label) {
        float size = 13, w = UiResources.text().width(label, size, BOLD) + 18;
        HudMesh.capsule(c, x, y, w, 22, 0xE6182A45, 0xE6182A45);
        HudMesh.line(c, x + 10, y + 21, x + w - 10, y + 21, 2, 0xAAE7C77E);
        text(c, label, x + w / 2, y + 11, size, 0xFFF2D9A0, BOLD, Align.CENTER);
    }

    private void glow(DrawContext c, Rect r, int color) {
        for (int i = 0; i < 3; i++) {
            int g = 4 + i * 2;
            outline(c, r.x() - g, r.y() - g, r.w() + g * 2, r.h() + g * 2, 2, alpha(color, .22f - i * .07f));
        }
        outline(c, r.x() - 2, r.y() - 2, r.w() + 4, r.h() + 4, 1.6f, alpha(color, .8f));
    }

    private void chevron(DrawContext c, Rect r, int dir, int color) {
        float cx = r.cx(), cy = r.cy(), s = 9;
        HudMesh.line(c, cx + dir * -s / 2, cy - s, cx + dir * s / 2, cy, 3, color);
        HudMesh.line(c, cx + dir * s / 2, cy, cx + dir * -s / 2, cy + s, 3, color);
    }

    private void icon(DrawContext c, Entry e, float x, float y, int size, int tint) {
        // Blank by design until the icon art exists (empty icon path draws nothing).
        if (!e.icon().isBlank()) UiResources.images().drawCentered(c, e.icon(), x, y, size, tint);
    }

    private static void box(DrawContext c, Rect r, int fill, int border, float thickness) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), fill);
        outline(c, r.x(), r.y(), r.w(), r.h(), thickness, border);
    }

    private static void outline(DrawContext c, float x, float y, float w, float h, float t, int color) {
        HudMesh.line(c, x, y + t / 2, x + w, y + t / 2, t, color);
        HudMesh.line(c, x, y + h - t / 2, x + w, y + h - t / 2, t, color);
        HudMesh.line(c, x + t / 2, y + t, x + t / 2, y + h - t, t, color);
        HudMesh.line(c, x + w - t / 2, y + t, x + w - t / 2, y + h - t, t, color);
    }

    /** Card frame: the border box of the art maps exactly onto `cell`; corners keep their shape, ornaments overflow. */
    private void cardFrame(DrawContext c, Identifier texture, CardArt a, Rect cell, int tint) {
        nine(c, texture, 627, 627, a.left(), a.top(), a.right(), a.bottom(), 70, cell, tint);
    }

    /**
     * Nine-slice draw. (fl, ft, fr, fb) is the frame's border box inside the texture; it lands exactly on `cell`.
     * Vertical scale is cell height / frame height; corners (frame edge + `corner` px) keep that scale, the middle
     * stretches. Anything outside the frame box (gems, shadows) is drawn outside the cell at the same scale.
     */
    private static void nine(DrawContext c, Identifier texture, int tw, int th, int fl, int ft, int fr, int fb, int corner, Rect cell, int tint) {
        float s = cell.h() / (float) (fb - ft);
        int[] sx = {0, fl + corner, fr - corner, tw};
        int[] sy = {0, ft + corner, fb - corner, th};
        float x0 = cell.x() - fl * s, y0 = cell.y() - ft * s;
        float x3 = cell.right() + (tw - fr) * s, y3 = cell.bottom() + (th - fb) * s;
        float[] dx = {x0, x0 + sx[1] * s, x3 - (tw - sx[2]) * s, x3};
        float[] dy = {y0, y0 + sy[1] * s, y3 - (th - sy[2]) * s, y3};
        if (dx[2] < dx[1]) { float mid = (dx[1] + dx[2]) / 2; dx[1] = dx[2] = mid; }
        if (dy[2] < dy[1]) { float mid = (dy[1] + dy[2]) / 2; dy[1] = dy[2] = mid; }
        var images = UiResources.images();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int u = sx[col], v = sy[row], sw = sx[col + 1] - u, sh = sy[row + 1] - v;
            int x = Math.round(dx[col]), y = Math.round(dy[row]);
            int w = Math.round(dx[col + 1]) - x, h = Math.round(dy[row + 1]) - y;
            if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) continue;
            images.drawTexture(c, texture, x, y, u, v, w, h, sw, sh, tw, th, tint);
        }
    }

    private enum Align { LEFT, CENTER, RIGHT }

    /** All text goes through the MagicCodex fonts. y is the vertical centre of the line. */
    private static void text(DrawContext c, String value, float x, float y, float size, int color, Identifier face, Align align) {
        if (value == null || value.isBlank()) return;
        var t = UiResources.text();
        switch (align) {
            case LEFT -> t.draw(c, value, x, y, size, color, face, false);
            case CENTER -> t.draw(c, value, x, y, size, color, face, true);
            case RIGHT -> t.draw(c, value, x - t.width(value, size, face), y, size, color, face, false);
        }
    }

    private static String fit(String value, float size, Identifier face, float max) {
        var t = UiResources.text();
        if (t.width(value, size, face) <= max) return value;
        String s = value;
        while (s.length() > 1 && t.width(s + "…", size, face) > max) s = s.substring(0, s.offsetByCodePoints(s.length(), -1));
        return s + "…";
    }

    /** Word wrap that also breaks long Korean runs; explicit newlines are kept. Cached per text/width/size. */
    private List<String> wrap(String value, float max, float size, Identifier face) {
        String key = value + "\u0000" + max + "\u0000" + size + "\u0000" + face;
        var cached = wraps.get(key);
        if (cached != null) return cached;
        var t = UiResources.text();
        var out = new ArrayList<String>();
        for (String paragraph : value.split("\n", -1)) {
            var line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (t.width(candidate, size, face) <= max) { line.setLength(0); line.append(candidate); continue; }
                if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                String rest = word;
                while (t.width(rest, size, face) > max && rest.length() > 1) {
                    int cut = rest.length();
                    while (cut > 1 && t.width(rest.substring(0, cut), size, face) > max) cut = rest.offsetByCodePoints(cut, -1);
                    out.add(rest.substring(0, cut)); rest = rest.substring(cut);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        if (wraps.size() > 256) wraps.clear();
        wraps.put(key, out);
        return out;
    }

    private static int alpha(int argb, float a) { return (Math.round(Math.clamp(a, 0, 1) * 255) << 24) | (argb & 0xFFFFFF); }
    private static int brighten(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return 0xFF000000 | ((r + (255 - r) / 4) << 16) | ((g + (255 - g) / 4) << 8) | (b + (255 - b) / 4);
    }
    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
        int g = Math.round(((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
        int bl = Math.round((a & 255) * t + (b & 255) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ input

    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f)); }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        if (sortOpen) {
            for (int i = 0; i < SORT_NAMES.length; i++) if (ArtifactCodexLayout.sortOption(i).contains(mx, my)) { sort = i; page = 0; click(); }
            sortOpen = false;
            return true;
        }
        if (ArtifactCodexLayout.CLOSE.contains(mx, my)) { close(); return true; }
        searchFocused = ArtifactCodexLayout.SEARCH.contains(mx, my);
        if (search != null) search.setFocused(searchFocused);
        if (searchFocused) return true;
        for (int i = 0; i < 5; i++) if (ArtifactCodexLayout.tab(i).contains(mx, my)) { if (tab != i) { tab = i; page = 0; click(); } return true; }
        if (ArtifactCodexLayout.SORT.contains(mx, my)) { sortOpen = true; click(); return true; }
        if (ArtifactCodexLayout.OWNED_HIT.contains(mx, my)) { ownedOnly = !ownedOnly; page = 0; click(); return true; }
        var items = visible();
        if (ArtifactCodexLayout.PREVIOUS.contains(mx, my)) { if (page > 0) { page--; click(); } return true; }
        if (ArtifactCodexLayout.NEXT.contains(mx, my)) { if (page + 1 < pages(items.size())) { page++; click(); } return true; }
        for (int slot = 0; slot < ArtifactCodexLayout.PAGE_SIZE; slot++) {
            int index = page * ArtifactCodexLayout.PAGE_SIZE + slot;
            if (index < items.size() && ArtifactCodexLayout.card(slot).contains(mx, my)) {
                selected = items.get(index).entry().id(); notice = ""; click(); return true;
            }
        }
        Item sel = selectedItem(items);
        if (sel != null && pending.isEmpty() && showcase == null) {
            if (ArtifactCodexLayout.EQUIP.contains(mx, my) && sel.owned() && !sel.equipped()) request(sel, true);
            else if (ArtifactCodexLayout.UNEQUIP.contains(mx, my) && sel.equipped()) request(sel, false);
        }
        return true;
    }

    private void request(Item sel, boolean equip) {
        boolean sent = equip ? ArtifactClient.equip(sel.entry().id()) : ArtifactClient.unequip(sel.entry().id());
        if (!sent) { notice = "서버에 연결할 수 없습니다."; return; }
        pending = sel.entry().id(); pendingVersion = ArtifactClient.version(); pendingDeadline = Util.getMeasuringTimeMs() + 5000; notice = ""; click();
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0 || sortOpen) return true;
        int pages = pages(visible().size());
        page = Math.clamp(page + (vertical < 0 ? 1 : -1), 0, pages - 1);
        return true;
    }

    @Override public boolean charTyped(char chr, int modifiers) {
        return searchFocused && search != null ? search.charTyped(chr, modifiers) : super.charTyped(chr, modifiers);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (sortOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) { sortOpen = false; return true; }
        if (searchFocused && search != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) { searchFocused = false; search.setFocused(false); return true; }
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
