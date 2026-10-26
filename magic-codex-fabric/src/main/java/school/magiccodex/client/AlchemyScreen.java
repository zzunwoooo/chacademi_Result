package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.AlchemyLayout.Art;
import school.magiccodex.client.AlchemyLayout.Box;
import school.magiccodex.protocol.AlchemyProtocol;
import school.magiccodex.protocol.AlchemyProtocol.Stack;
import school.magiccodex.protocol.AlchemyRules;

/**
 * 연금술 window (opened by the server: /연금술). GPT's approved alchemy UI PNGs (textures/gui/alchemy), MagicCodex fonts.
 *
 * Left: the alchemy circle with six ingredient slots on its beams. Clicking a slot opens a picker with the category 재료
 * items in the inventory (the server decides which those are); right-click empties a slot, the mouse wheel changes the
 * amount. Order never matters. 합성 asks the server, which either crafts (experience + ingredients), discovers a new
 * recipe, hints that the mix is close, or reports nothing. Right: discovered recipes (search, "can craft now" filter),
 * undiscovered ones as locked rows; clicking a recipe fills the slots with its ingredients.
 */
final class AlchemyScreen extends Screen {
    static final Identifier PANEL = tex("panel"), CIRCLE = tex("circle");
    static final Identifier TITLE = Identifier.of("magiccodex", "title"), BOLD = Identifier.of("magiccodex", "hud_bold"),
            LABEL = Identifier.of("magiccodex", "label"), BODY = Identifier.of("magiccodex", "body");
    static final int GOLD = 0xFFE7C77E, TITLE_GOLD = 0xFFF2D9A0, IVORY = 0xFFF3E9D3, TEXT = 0xFFE4E9EF, MUTED = 0xFF9AA8BB,
            DIM = 0xFF6B7A90, WARN = 0xFFFF9E9E, EXP = 0xFF8EE07A, CYAN = 0xFF9FE6F5;

    static Identifier tex(String name) { return Identifier.of("magiccodex", "textures/gui/alchemy/" + name + ".png"); }

    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private String[] slotKeys = new String[6];
    private int[] slotAmounts = new int[6];
    private int picker = -1, scroll;
    private boolean craftableOnly, searchFocused;
    private TextFieldWidget search;
    private String query = "";
    private long pendingSeq, pendingDeadline, openedAt;
    private String message = "";
    private int messageColor = TEXT;
    private long messageAt;
    // last result effect
    private int fx = -1;
    private long fxAt;
    private String fxResult = "";
    private int fxAmount;
    private String flashRecipe = "";

    AlchemyScreen() { super(Text.literal("연금술")); }

    @Override protected void init() {
        AlchemyClient.query();
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 24, Text.literal("레시피 검색"));
        search.setMaxLength(20);
        search.setText(query);
        search.setChangedListener(v -> { query = v; scroll = 0; });
        if (openedAt == 0) { openedAt = Util.getMeasuringTimeMs(); sound(SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1.3f, .45f); }
        resizeSlots();
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { AlchemyClient.closed(); }

    private void resizeSlots() {
        int n = AlchemyClient.slotCount();
        if (slotKeys.length != n) { slotKeys = Arrays.copyOf(slotKeys, n); slotAmounts = Arrays.copyOf(slotAmounts, n); if (picker >= n) picker = -1; }
    }

    // ------------------------------------------------------------------ model

    private List<Stack> stacks() {
        var list = new ArrayList<Stack>();
        for (int i = 0; i < slotKeys.length; i++) if (slotKeys[i] != null && slotAmounts[i] > 0) list.add(new Stack(slotKeys[i], slotAmounts[i]));
        return list;
    }
    private int filled() { int n = 0; for (String k : slotKeys) if (k != null) n++; return n; }

    /** The discovered recipe the slots match exactly, or null (an undiscovered one is never known here). */
    private AlchemyProtocol.Recipe match() {
        var want = AlchemyRules.merge(stacks());
        if (want.isEmpty()) return null;
        for (var r : AlchemyClient.recipes()) if (AlchemyRules.matches(AlchemyRules.merge(r.ingredients()), want)) return r;
        return null;
    }

    private long playerExp() {
        var p = client == null ? null : client.player;
        return p == null ? 0 : AlchemyRules.total(p.experienceLevel, p.experienceProgress);
    }

    private boolean craftable(AlchemyProtocol.Recipe r) {
        for (var e : AlchemyRules.merge(r.ingredients()).entrySet()) if (AlchemyClient.owned(e.getKey()) < e.getValue()) return false;
        return playerExp() >= r.exp();
    }

    /** Amount of this key in the other slots (so one key can never be put in for more than the player carries). */
    private int usedElsewhere(String key, int except) {
        int n = 0;
        for (int i = 0; i < slotKeys.length; i++) if (i != except && key.equals(slotKeys[i])) n += slotAmounts[i];
        return n;
    }

    /** Recipe list rows: discovered (filtered by search / craftable) then locked placeholders. null = a locked row. */
    private List<AlchemyProtocol.Recipe> rows() {
        String q = query.strip().toLowerCase(Locale.ROOT);
        var list = new ArrayList<AlchemyProtocol.Recipe>();
        for (var r : AlchemyClient.recipes()) {
            if (!q.isEmpty() && !r.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            if (craftableOnly && !craftable(r)) continue;
            list.add(r);
        }
        // Locked rows only without a filter: a search or the craftable filter never shows them.
        if (q.isEmpty() && !craftableOnly) for (int i = 0; i < AlchemyClient.locked(); i++) list.add(null);
        return list;
    }

    // ------------------------------------------------------------------ server

    void onResult(AlchemyProtocol.Result r) {
        if (r.sequence() != pendingSeq) return;
        pendingSeq = 0;
        long now = Util.getMeasuringTimeMs();
        fx = r.outcome(); fxAt = now; fxResult = r.result(); fxAmount = r.amount();
        int color = switch (r.outcome()) {
            case AlchemyProtocol.DISCOVERED, AlchemyProtocol.DISCOVERED_NO_EXP -> TITLE_GOLD;
            case AlchemyProtocol.CRAFTED -> IVORY;
            case AlchemyProtocol.NEAR, AlchemyProtocol.NEAR_AMOUNT -> CYAN;
            case AlchemyProtocol.NOTHING -> MUTED;
            default -> WARN;
        };
        say(r.notice(), color);
        switch (r.outcome()) {
            case AlchemyProtocol.DISCOVERED, AlchemyProtocol.DISCOVERED_NO_EXP -> {
                flashRecipe = r.recipeId();
                sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.15f, .55f);
                sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.4f, .5f);
                if (r.outcome() == AlchemyProtocol.DISCOVERED) sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, .9f, .5f);
            }
            case AlchemyProtocol.CRAFTED -> { sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, .55f); sound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.3f, .5f); }
            case AlchemyProtocol.NEAR, AlchemyProtocol.NEAR_AMOUNT -> { sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, .7f, .7f); sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.6f, .25f); }
            case AlchemyProtocol.NOTHING -> sound(SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.5f, .35f);
            default -> sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f);
        }
    }

    private void say(String text, int color) { message = text == null ? "" : text; messageColor = color; messageAt = Util.getMeasuringTimeMs(); }

    private void synthesize() {
        if (pendingSeq != 0) return;
        if (filled() == 0) { say("재료를 넣어 주세요.", WARN); sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .4f); return; }
        // A known recipe without enough experience cannot even be tried (the server refuses it too).
        var known = match();
        if (known != null && playerExp() < known.exp()) { say("경험치가 부족합니다. (필요 " + known.exp() + ")", WARN); sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .4f); return; }
        long seq = AlchemyClient.craft(stacks());
        if (seq == 0) { say("서버에 연결되어 있지 않습니다.", WARN); return; }
        pendingSeq = seq; pendingDeadline = Util.getMeasuringTimeMs() + 5000; fx = -1;
        say("", TEXT);
        sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, .45f);
    }

    @Override public void tick() {
        if (client == null || client.player == null || !client.player.isAlive()) { close(); return; }
        resizeSlots();
        if (pendingSeq != 0 && Util.getMeasuringTimeMs() > pendingDeadline) { pendingSeq = 0; say("서버 응답이 늦습니다. 다시 시도해 주세요.", WARN); AlchemyClient.query(); }
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        c.fill(0, 0, width, height, 0x88070D13);
        long now = Util.getMeasuringTimeMs();
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            images.drawTexture(c, PANEL, 0, 0, 0, 0, 1672, 941, 1672, 941);
            header(c, mx, my);
            boolean modal = picker >= 0;
            var match = match();
            circle(c, now, modal ? -1 : mx, modal ? -1 : my, match);
            bottom(c, now, modal ? -1 : mx, modal ? -1 : my, match);
            var rows = rows();
            scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - AlchemyLayout.ROWS));
            recipes(c, now, modal ? -1 : mx, modal ? -1 : my, rows, match);
            if (modal) picker(c, mx, my);
            else tooltips(c, mx, my, rows);
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    private void header(DrawContext c, double mx, double my) {
        text(c, "연금술", AlchemyLayout.TITLE_X, AlchemyLayout.TITLE_Y, 40, TITLE_GOLD, TITLE, true);
        var x = AlchemyLayout.CLOSE;
        // the X is part of the panel art: only a soft highlight on hover
        if (x.contains(mx, my)) HudMesh.disk(c, x.cx(), x.cy(), 22, 0x30FFE3A8);
    }

    // ---- circle, slots, result ring

    private void circle(DrawContext c, long now, double mx, double my, AlchemyProtocol.Recipe match) {
        var images = UiResources.images();
        float t = (now - openedAt) / 1000f, fxT = (now - fxAt) / 1000f;
        int n = slotKeys.length, filled = filled();
        boolean charging = pendingSeq != 0;
        float cx = AlchemyLayout.CIRCLE_X, cy = AlchemyLayout.CIRCLE_Y, size = AlchemyLayout.CIRCLE_SIZE;
        int x0 = Math.round(cx - size / 2), y0 = Math.round(cy - size / 2), s = Math.round(size);

        // dimmed when the last try did nothing
        float dim = fx == AlchemyProtocol.NOTHING && fxT < .7f ? .45f * (1 - fxT / .7f) : 0;
        images.drawTexture(c, CIRCLE, x0, y0, 0, 0, s, s, 1024, 1024, 1024, 1024, argb(1 - dim, 0xFFFFFF));

        // additive glow: idle breathing, more ingredients = brighter, charging while the server decides
        float glow = .07f + .035f * (float) Math.sin(t * 1.7) + .03f * filled + (charging ? .22f + .08f * (float) Math.sin(t * 14) : 0);
        int glowColor = 0x8FE3FF;
        if (fx == AlchemyProtocol.NEAR || fx == AlchemyProtocol.NEAR_AMOUNT) {
            // flicker: "something almost reacted"
            if (fxT < 1.3f) glow += (Math.sin(fxT * 38) > 0 ? .32f : .06f) * (1 - fxT / 1.3f);
        } else if (fx == AlchemyProtocol.CRAFTED) {
            if (fxT < 1f) { glow += .75f * (1 - fxT); glowColor = 0xD8F6FF; }
        } else if (fx == AlchemyProtocol.DISCOVERED || fx == AlchemyProtocol.DISCOVERED_NO_EXP) {
            if (fxT < 1.8f) { glow += .95f * (1 - fxT / 1.8f); glowColor = 0xFFD98A; }
        }
        if (glow > .01f) images.drawTextureAdditive(c, CIRCLE, x0, y0, 0, 0, s, s, 1024, 1024, 1024, 1024, argb(Math.min(1, glow), glowColor));

        // beams from filled slots into the centre
        for (int i = 0; i < n; i++) {
            if (slotKeys[i] == null) continue;
            var b = AlchemyLayout.slot(i, n); float[] e = AlchemyLayout.beamEnd(i, n);
            float pulse = .45f + .2f * (float) Math.sin(t * 3 + i) + (charging ? .35f : 0);
            if ((fx == AlchemyProtocol.NEAR || fx == AlchemyProtocol.NEAR_AMOUNT) && fxT < 1.3f && Math.sin(fxT * 38 + i) < 0) pulse *= .3f;
            HudMesh.line(c, b.cx(), b.cy(), e[0], e[1], 7, argb(Math.min(1, pulse) * .28f, 0x7FDFFF));
            HudMesh.line(c, b.cx(), b.cy(), e[0], e[1], 2.2f, argb(Math.min(1, pulse), 0xC8F3FF));
        }

        // orbiting sparkles (faster while charging)
        float speed = charging ? 70 : 9;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(t * speed + i * 45);
            float tw = .5f + .5f * (float) Math.sin(t * 2.3 + i * 1.7);
            HudMesh.star(c, cx + (float) Math.cos(a) * 268, cy + (float) Math.sin(a) * 268, 5 + 2 * tw, argb(.35f + .45f * tw, 0xF2D9A0));
        }
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-t * speed * 1.6 + i * 60);
            HudMesh.star(c, cx + (float) Math.cos(a) * 104, cy + (float) Math.sin(a) * 104, 4, argb(.55f, 0xBDEFFF));
        }

        // discovery: gold rings expanding + sparks; craft: one cyan ring
        if ((fx == AlchemyProtocol.DISCOVERED || fx == AlchemyProtocol.DISCOVERED_NO_EXP) && fxT < 2.2f) {
            for (int k = 0; k < 3; k++) {
                float u = (fxT - k * .22f) / 1.3f;
                if (u <= 0 || u >= 1) continue;
                float ease = 1 - (1 - u) * (1 - u);
                HudMesh.arc(c, cx, cy, 90 + ease * 230, 5 * (1 - u) + 1, 0, (float) (Math.PI * 2), argb((1 - u) * .9f, 0xFFD98A));
            }
            for (int k = 0; k < 14; k++) {
                float u = fxT / 1.6f;
                if (u >= 1) break;
                double a = Math.toRadians(k * (360.0 / 14) + 9);
                float r = 70 + (1 - (1 - u) * (1 - u)) * 260;
                HudMesh.star(c, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 9 * (1 - u) + 2, argb(1 - u, 0xFFE9B0));
            }
        } else if (fx == AlchemyProtocol.CRAFTED && fxT < 1f) {
            float ease = 1 - (1 - fxT) * (1 - fxT);
            HudMesh.arc(c, cx, cy, 90 + ease * 200, 4 * (1 - fxT) + 1, 0, (float) (Math.PI * 2), argb((1 - fxT) * .85f, 0xC8F3FF));
        }

        // slots
        for (int i = 0; i < n; i++) slot(c, i, AlchemyLayout.slot(i, n), mx, my, now);

        // result ring + content
        drawArt(c, AlchemyLayout.RING, AlchemyLayout.RESULT, 0xFFFFFFFF);
        boolean showResult = (fx == AlchemyProtocol.CRAFTED || fx == AlchemyProtocol.DISCOVERED) && fxT < 2.6f && !fxResult.isEmpty();
        if (showResult) {
            float pop = fxT < .25f ? .6f + 1.9f * fxT : fxT < .45f ? 1.075f - (fxT - .25f) * .375f : 1f;
            item(c, AlchemyClient.icon(fxResult), cx, cy, AlchemyLayout.RESULT_ITEM * pop);
            if (fxAmount > 1) count(c, "×" + fxAmount, cx + 44, cy + 40, IVORY);
        } else if (match != null) {
            item(c, AlchemyClient.icon(match.result()), cx, cy, AlchemyLayout.RESULT_ITEM);
            // dim the preview: it is what would come out, not something made yet
            c.getMatrices().push(); c.getMatrices().translate(0, 0, 150);
            HudMesh.disk(c, cx, cy, 46, 0x660A1426);
            c.getMatrices().pop();
            if (match.resultAmount() > 1) count(c, "×" + match.resultAmount(), cx + 44, cy + 40, IVORY);
        } else if (filled > 0) {
            float pulse = .45f + .25f * (float) Math.sin(t * 2.4);
            text(c, "?", cx, cy + 2, 58, argb(pulse, 0xBDEFFF), TITLE, true);
        }
    }

    private void slot(DrawContext c, int i, Box box, double mx, double my, long now) {
        boolean over = box.contains(mx, my);
        drawArt(c, over || picker == i ? AlchemyLayout.SLOT_HOVER : AlchemyLayout.SLOT, box, 0xFFFFFFFF);
        String key = slotKeys[i];
        if (key == null) {
            // empty: a faint plus says "click"
            int col = over ? 0xCCE7C77E : 0x557F96B5;
            HudMesh.line(c, box.cx() - 11, box.cy(), box.cx() + 11, box.cy(), 2.4f, col);
            HudMesh.line(c, box.cx(), box.cy() - 11, box.cx(), box.cy() + 11, 2.4f, col);
            return;
        }
        item(c, AlchemyClient.icon(key), box.cx(), box.cy() - 2, 56);
        int owned = AlchemyClient.owned(key) - usedElsewhere(key, i);
        count(c, "×" + slotAmounts[i], box.right() - 22, box.bottom() - 24, owned < slotAmounts[i] ? WARN : IVORY);
    }

    // ---- message, experience, button

    private void bottom(DrawContext c, long now, double mx, double my, AlchemyProtocol.Recipe match) {
        float x = AlchemyLayout.CIRCLE_X;
        if (pendingSeq != 0) text(c, "마법진이 반응을 살피고 있다…", x, AlchemyLayout.MESSAGE_Y, 20, argb(.55f + .3f * (float) Math.sin(now / 160.0), 0xBDEFFF), BODY, true);
        else if (!message.isEmpty()) {
            float in = Math.min(1, (now - messageAt) / 180f);
            boolean big = messageColor == TITLE_GOLD;
            float size = fit(message, big ? 24 : 20, big ? BOLD : BODY, 840);
            text(c, message, x, AlchemyLayout.MESSAGE_Y, size, argb(in * ((messageColor >>> 24) / 255f), messageColor & 0xFFFFFF), big ? BOLD : BODY, true);
        }
        // ● 소모 경험치 30 / 보유 경험치 1000
        long have = playerExp();
        String cost; int costColor;
        if (filled() == 0) { cost = "0"; costColor = MUTED; }
        else if (match == null) { cost = "???"; costColor = MUTED; }
        else { cost = Integer.toString(match.exp()); costColor = have >= match.exp() ? EXP : WARN; }
        String[] parts = {"소모 경험치 ", cost, "  /  보유 경험치 ", Long.toString(have)};
        int[] colors = {TEXT, costColor, TEXT, EXP};
        Identifier[] faces = {LABEL, BOLD, LABEL, BOLD};
        float size = 21, total = 26;
        for (int i = 0; i < parts.length; i++) total += UiResources.text().width(parts[i], size, faces[i]);
        float left = x - total / 2, y = AlchemyLayout.EXP_Y;
        HudMesh.disk(c, left + 8, y, 8, 0xFF4FAE3A); HudMesh.disk(c, left + 8, y, 5.5f, 0xFF8EE07A); HudMesh.disk(c, left + 6, y - 2.5f, 2.2f, 0xEEE9FFD8);
        left += 26;
        for (int i = 0; i < parts.length; i++) { text(c, parts[i], left, y, size, colors[i], faces[i], false); left += UiResources.text().width(parts[i], size, faces[i]); }

        var b = AlchemyLayout.SYNTH;
        boolean enabled = pendingSeq == 0 && filled() > 0 && (match == null || have >= match.exp()), over = enabled && b.contains(mx, my);
        drawArt(c, over ? AlchemyLayout.BUTTON_HOVER : AlchemyLayout.BUTTON, b, enabled ? 0xFFFFFFFF : 0xFF8A93A3);
        text(c, pendingSeq != 0 ? "합성 중…" : "합성", b.cx(), b.cy() + 1, 31, enabled ? (over ? 0xFFFFF1CC : TITLE_GOLD) : 0xFF8A97A8, TITLE, true);
    }

    // ---- recipe list

    private void recipes(DrawContext c, long now, double mx, double my, List<AlchemyProtocol.Recipe> rows, AlchemyProtocol.Recipe match) {
        var right = AlchemyLayout.RIGHT;
        // header: — ✦ 레시피 ✦ —
        float hy = AlchemyLayout.HEADER_Y, tw = UiResources.text().width("레시피", 34, TITLE);
        text(c, "레시피", right.cx(), hy, 34, TITLE_GOLD, TITLE, true);
        for (int side = -1; side <= 1; side += 2) {
            float inner = right.cx() + side * (tw / 2 + 22), outer = right.cx() + side * (tw / 2 + 150);
            HudMesh.line(c, Math.min(inner, outer), hy, Math.max(inner, outer), hy, 1.6f, 0xCCC9A55C);
            HudMesh.star(c, inner, hy, 6, GOLD);
        }
        // search
        var s = AlchemyLayout.SEARCH_BOX;
        nine(c, AlchemyLayout.SEARCH, 30, s, searchFocused ? 0xFFFFFFFF : s.contains(mx, my) ? 0xFFF4F0E6 : 0xFFDCE0E8);
        float ix = s.x() + 30, iy = s.cy() - 1;
        HudMesh.arc(c, ix, iy, 9, 2.4f, 0, (float) (Math.PI * 2), 0xFFC9D2DE);
        HudMesh.line(c, ix + 6.5f, iy + 6.5f, ix + 13, iy + 13, 2.6f, 0xFFC9D2DE);
        float textX = s.x() + 56;
        if (query.isEmpty() && !searchFocused) text(c, "레시피 검색", textX, s.cy(), 20, MUTED, BODY, false);
        else {
            String shown = tail(query, 20, BODY, s.w() - 80);
            text(c, shown, textX, s.cy(), 20, TEXT, BODY, false);
            if (searchFocused && now / 500 % 2 == 0) {
                float cursor = textX + UiResources.text().width(shown, 20, BODY) + 2;
                c.fill((int) cursor, (int) s.cy() - 12, (int) cursor + 2, (int) s.cy() + 12, TEXT);
            }
        }
        // craftable-only checkbox + discovered count
        var cb = AlchemyLayout.CHECK;
        boolean overCheck = AlchemyLayout.CHECK_HIT.contains(mx, my);
        if (craftableOnly) drawArt(c, AlchemyLayout.CHECK_ON, cb, 0xFFFFFFFF);
        else drawArt(c, AlchemyLayout.CHECK_OFF, cb, overCheck ? 0xFFFFFFFF : 0xFFD5DAE2);
        text(c, "보유 재료로 제작 가능", AlchemyLayout.CHECK_LABEL_X, cb.cy(), 19, overCheck ? 0xFFF1EADB : 0xFFD7DCE3, LABEL, false);
        int known = AlchemyClient.recipes().size(), all = known + AlchemyClient.locked();
        String countText = "발견 " + known + " / " + all;
        text(c, countText, AlchemyLayout.COUNT_RIGHT - UiResources.text().width(countText, 18, LABEL), cb.cy(), 18, MUTED, LABEL, false);

        // rows
        if (AlchemyClient.state() == null) text(c, "레시피를 불러오는 중입니다…", right.cx(), AlchemyLayout.LIST.cy(), 20, MUTED, LABEL, true);
        else if (rows.isEmpty()) text(c, all == 0 ? "아직 등록된 레시피가 없습니다." : "조건에 맞는 레시피가 없습니다.", right.cx(), AlchemyLayout.LIST.cy(), 20, MUTED, LABEL, true);
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index >= rows.size()) break;
            var r = rows.get(index); var box = AlchemyLayout.row(v);
            boolean over = box.contains(mx, my);
            if (r == null) {
                nine(c, AlchemyLayout.ROW_LOCKED, 34, box, 0xFFFFFFFF);
                drawArt(c, AlchemyLayout.LOCK, Box.centered(box.x() + 48, box.cy(), 26, 32), 0xFFFFFFFF);
                text(c, "???", box.x() + 92, box.cy(), 22, DIM, BOLD, false);
                continue;
            }
            boolean chosen = match != null && match.id().equals(r.id());
            nine(c, chosen ? AlchemyLayout.ROW_SELECTED : AlchemyLayout.ROW, 34, box, over || chosen ? 0xFFFFFFFF : 0xFFE0E4EC);
            if (r.id().equals(flashRecipe) && now - fxAt < 2400) {
                float u = (now - fxAt) / 2400f;
                c.fill(Math.round(box.x() + 8), Math.round(box.y() + 7), Math.round(box.right() - 8), Math.round(box.bottom() - 7), argb(.32f * (1 - u) * (.6f + .4f * (float) Math.sin(u * 30)), 0xFFD98A));
            }
            item(c, AlchemyClient.icon(r.result()), box.x() + 48, box.cy(), 44);
            boolean can = craftable(r);
            String exp = "경험치 " + r.exp();
            float expW = UiResources.text().width(exp, 16, LABEL);
            String name = r.name();
            float size = fit(name, 22, BOLD, box.w() - 92 - expW - 40);
            text(c, name, box.x() + 92, box.cy(), size, can ? IVORY : 0xFFC4CBD6, BOLD, false);
            text(c, exp, box.right() - 24 - expW, box.cy(), 16, playerExp() >= r.exp() ? MUTED : 0xFFC98989, LABEL, false);
        }
        // scrollbar
        if (rows.size() > AlchemyLayout.ROWS) {
            var bar = AlchemyLayout.SCROLL;
            c.fill(Math.round(bar.x()), Math.round(bar.y()), Math.round(bar.right()), Math.round(bar.bottom()), 0x403A667F);
            float thumb = Math.max(30, bar.h() * AlchemyLayout.ROWS / rows.size());
            float py = bar.y() + (bar.h() - thumb) * scroll / (rows.size() - AlchemyLayout.ROWS);
            c.fill(Math.round(bar.x()), Math.round(py), Math.round(bar.right()), Math.round(py + thumb), 0xFFC9A55C);
        }
        // footer
        float fy = AlchemyLayout.FOOTER_LINE_Y;
        HudMesh.line(c, right.cx() - 190, fy, right.cx() - 12, fy, 1.2f, 0x88C9A55C);
        HudMesh.line(c, right.cx() + 12, fy, right.cx() + 190, fy, 1.2f, 0x88C9A55C);
        HudMesh.star(c, right.cx(), fy, 5, GOLD);
        String hint = "알맞은 재료를 조합해 새로운 레시피를 발견하세요.";
        text(c, hint, right.cx(), AlchemyLayout.FOOTER_Y, fit(hint, 17, BODY, 440), 0xFFB9C3D0, BODY, true);
    }

    // ---- material picker

    private void picker(DrawContext c, double mx, double my) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(0, 0, 300);
            var left = AlchemyLayout.LEFT;
            c.fill(Math.round(left.x()), Math.round(left.y()), Math.round(left.right()), Math.round(left.bottom()), 0xA0050A14);
            var p = AlchemyLayout.PICKER;
            c.fill(Math.round(p.x()), Math.round(p.y()), Math.round(p.right()), Math.round(p.bottom()), 0xF50E1B2E);
            outline(c, p, 1.6f, 0xFFC9A55C);
            HudMesh.line(c, p.x() + 18, p.y() + 66, p.right() - 18, p.y() + 66, 1, 0x66C9A55C);
            text(c, "재료 선택", p.cx(), AlchemyLayout.PICK_TITLE_Y, 28, TITLE_GOLD, TITLE, true);
            var x = AlchemyLayout.PICK_CLOSE; int xc = x.contains(mx, my) ? 0xFFFFE3A8 : GOLD; float r = 11;
            HudMesh.line(c, x.cx() - r, x.cy() - r, x.cx() + r, x.cy() + r, 3, xc); HudMesh.line(c, x.cx() - r, x.cy() + r, x.cx() + r, x.cy() - r, 3, xc);
            var list = AlchemyClient.selectable();
            if (list.isEmpty()) {
                text(c, "가방에 재료 아이템이 없습니다.", p.cx(), p.cy() - 10, 22, MUTED, LABEL, true);
                text(c, "재료 아이템만 넣을 수 있습니다.", p.cx(), p.cy() + 24, 17, DIM, BODY, true);
            }
            int max = AlchemyLayout.PICK_COLUMNS * AlchemyLayout.PICK_ROWS;
            for (int i = 0; i < Math.min(max, list.size()); i++) {
                var mat = list.get(i); var cell = AlchemyLayout.pickCell(i);
                int left2 = mat.owned() - usedElsewhere(mat.key(), picker);
                boolean usable = left2 > 0 || mat.key().equals(slotKeys[picker]), over = cell.contains(mx, my) && usable;
                drawArt(c, over ? AlchemyLayout.SLOT_HOVER : AlchemyLayout.SLOT, cell, usable ? 0xFFFFFFFF : 0xFF707888);
                item(c, AlchemyClient.icon(mat.key()), cell.cx(), cell.cy() - 2, 42);
                count(c, Integer.toString(Math.max(0, left2)), cell.right() - 14, cell.bottom() - 17, usable ? IVORY : DIM);
                if (!usable) { m.push(); m.translate(0, 0, 160); c.fill(Math.round(cell.x() + 4), Math.round(cell.y() + 4), Math.round(cell.right() - 4), Math.round(cell.bottom() - 4), 0x880A1220); m.pop(); }
            }
            String hint = "클릭: 넣기  ·  칸 위에서 휠: 수량  ·  칸 우클릭: 빼기";
            text(c, hint, p.cx(), AlchemyLayout.PICK_HINT_Y, fit(hint, 17, LABEL, p.w() - 40), MUTED, LABEL, true);
            for (int i = 0; i < Math.min(max, list.size()); i++) if (AlchemyLayout.pickCell(i).contains(mx, my)) {
                var mat = list.get(i);
                tooltip(c, List.of(AlchemyClient.name(mat.key()), "보유 " + mat.owned() + "개"), mx, my);
            }
        } finally { m.pop(); }
    }

    // ---- tooltips

    private void tooltips(DrawContext c, double mx, double my, List<AlchemyProtocol.Recipe> rows) {
        int n = slotKeys.length;
        for (int i = 0; i < n; i++) if (AlchemyLayout.slot(i, n).contains(mx, my)) {
            if (slotKeys[i] == null) tooltip(c, List.of("재료 넣기", "클릭해서 가방의 재료를 고릅니다"), mx, my);
            else tooltip(c, List.of(AlchemyClient.name(slotKeys[i]) + " ×" + slotAmounts[i], "보유 " + AlchemyClient.owned(slotKeys[i]) + "개", "휠: 수량  ·  우클릭: 빼기  ·  클릭: 바꾸기"), mx, my);
            return;
        }
        if (AlchemyLayout.RESULT.contains(mx, my)) {
            var match = match();
            if (match != null) tooltip(c, List.of(match.name() + (match.resultAmount() > 1 ? " ×" + match.resultAmount() : ""), "소모 경험치 " + match.exp()), mx, my);
            return;
        }
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index >= rows.size() || !AlchemyLayout.row(v).contains(mx, my)) continue;
            var r = rows.get(index);
            if (r == null) { tooltip(c, List.of("아직 발견하지 못한 레시피", "알맞은 재료를 조합하면 발견됩니다"), mx, my); return; }
            var lines = new ArrayList<String>();
            lines.add(r.name() + (r.resultAmount() > 1 ? " ×" + r.resultAmount() : ""));
            for (var e : AlchemyRules.merge(r.ingredients()).entrySet())
                lines.add("· " + AlchemyClient.name(e.getKey()) + " ×" + e.getValue() + "  (보유 " + AlchemyClient.owned(e.getKey()) + ")");
            lines.add("소모 경험치 " + r.exp());
            lines.add("클릭: 재료 칸에 채우기");
            tooltip(c, lines, mx, my);
            return;
        }
    }

    static void tooltip(DrawContext c, List<String> lines, double mx, double my) {
        var t = UiResources.text();
        float w = 0;
        for (int i = 0; i < lines.size(); i++) w = Math.max(w, t.width(lines.get(i), i == 0 ? 20 : 17, i == 0 ? BOLD : LABEL));
        w = Math.min(560, w + 32);
        float h = 22 + 28 + (lines.size() - 1) * 25;
        float x = (float) mx + 18, y = (float) my + 14;
        if (x + w > AlchemyLayout.W - 20) x = (float) mx - 18 - w;
        if (y + h > AlchemyLayout.H - 10) y = AlchemyLayout.H - 10 - h;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(0, 0, 450);
            var box = new Box(x, y, w, h);
            c.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), 0xF5102437);
            outline(c, box, 1.2f, 0xCCC9A55C);
            float ty = y + 11 + 14;
            for (int i = 0; i < lines.size(); i++) {
                boolean first = i == 0;
                String line = lines.get(i);
                float size = fit(line, first ? 20 : 17, first ? BOLD : LABEL, w - 32);
                text(c, line, x + 16, ty, size, first ? TITLE_GOLD : TEXT, first ? BOLD : LABEL, false);
                ty += first ? 28 : 25;
            }
        } finally { m.pop(); }
    }

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double x, double y, int button) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        if (picker >= 0) { pickerClick(mx, my, button); return true; }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && AlchemyLayout.CLOSE.contains(mx, my)) { click(); close(); return true; }
        boolean wasFocused = searchFocused;
        searchFocused = button == GLFW.GLFW_MOUSE_BUTTON_LEFT && AlchemyLayout.SEARCH_BOX.contains(mx, my);
        if (search != null) search.setFocused(searchFocused);
        if (searchFocused) { if (!wasFocused) click(); return true; }
        int n = slotKeys.length;
        for (int i = 0; i < n; i++) if (AlchemyLayout.slot(i, n).contains(mx, my)) {
            if (pendingSeq != 0) return true;
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { if (slotKeys[i] != null) { slotKeys[i] = null; slotAmounts[i] = 0; fx = -1; sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.4f, .4f); } }
            else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { picker = i; sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, .6f); }
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (AlchemyLayout.SYNTH.contains(mx, my)) { synthesize(); return true; }
        if (AlchemyLayout.CHECK_HIT.contains(mx, my)) { craftableOnly = !craftableOnly; scroll = 0; click(); return true; }
        var rows = rows();
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index < rows.size() && AlchemyLayout.row(v).contains(mx, my)) {
                var r = rows.get(index);
                if (r != null && pendingSeq == 0) fill(r);
                else if (r == null) sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.2f, .25f);
                return true;
            }
        }
        return true;
    }

    /** Put a known recipe's ingredients into the slots (amounts as the recipe needs, even when the player has fewer). */
    private void fill(AlchemyProtocol.Recipe r) {
        Arrays.fill(slotKeys, null); Arrays.fill(slotAmounts, 0);
        int i = 0;
        for (var e : AlchemyRules.merge(r.ingredients()).entrySet()) {
            if (i >= slotKeys.length) break;
            slotKeys[i] = e.getKey(); slotAmounts[i] = Math.min(AlchemyProtocol.MAX_AMOUNT, e.getValue()); i++;
        }
        fx = -1; say("", TEXT);
        sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, .5f);
    }

    private void pickerClick(double mx, double my, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || AlchemyLayout.PICK_CLOSE.contains(mx, my) || !AlchemyLayout.PICKER.contains(mx, my)) { picker = -1; click(); return; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        var list = AlchemyClient.selectable();
        int max = AlchemyLayout.PICK_COLUMNS * AlchemyLayout.PICK_ROWS;
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (!AlchemyLayout.pickCell(i).contains(mx, my)) continue;
            var mat = list.get(i);
            int available = mat.owned() - usedElsewhere(mat.key(), picker);
            if (mat.key().equals(slotKeys[picker])) {
                if (slotAmounts[picker] < Math.min(AlchemyProtocol.MAX_AMOUNT, available)) slotAmounts[picker]++;
            } else {
                if (available <= 0) { sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .8f, .3f); return; }
                slotKeys[picker] = mat.key(); slotAmounts[picker] = 1;
            }
            fx = -1;
            sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f, .45f);
            picker = -1;
            return;
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0 || picker >= 0) return true;
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        int n = slotKeys.length, step = vertical > 0 ? 1 : -1;
        for (int i = 0; i < n; i++) if (AlchemyLayout.slot(i, n).contains(mx, my)) {
            if (slotKeys[i] == null || pendingSeq != 0) return true;
            int cap = Math.min(AlchemyProtocol.MAX_AMOUNT, AlchemyClient.owned(slotKeys[i]) - usedElsewhere(slotKeys[i], i));
            int next = step > 0 ? Math.min(slotAmounts[i] + 1, Math.max(slotAmounts[i], cap)) : Math.max(1, slotAmounts[i] - 1);
            if (next != slotAmounts[i]) { slotAmounts[i] = next; fx = -1; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f + .1f * step, .15f); }
            return true;
        }
        if (AlchemyLayout.LIST.contains(mx, my)) scroll = Math.max(0, scroll - step);
        return true;
    }

    @Override public boolean charTyped(char chr, int modifiers) {
        return searchFocused && search != null ? search.charTyped(chr, modifiers) : super.charTyped(chr, modifiers);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (picker >= 0 && keyCode == GLFW.GLFW_KEY_ESCAPE) { picker = -1; return true; }
        if (searchFocused && search != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) { searchFocused = false; search.setFocused(false); return true; }
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ pieces

    private void click() { sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f); }
    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }

    static int argb(float alpha, int rgb) { return (Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24) | (rgb & 0xFFFFFF); }

    static void text(DrawContext c, String value, float x, float y, float size, int color, Identifier face, boolean centered) {
        if (value != null && !value.isBlank() && (color >>> 24) > 3) UiResources.text().draw(c, value, x, y, size, color, face, centered);
    }
    /** Largest size up to `size` at which the text fits `max` wide. */
    static float fit(String value, float size, Identifier face, float max) {
        float w = UiResources.text().width(value, size, face);
        return w <= max ? size : Math.max(10, size * max / w);
    }
    /** The end of a long search text (what is being typed stays visible). */
    static String tail(String value, float size, Identifier face, float max) {
        String v = value;
        while (v.length() > 1 && UiResources.text().width(v, size, face) > max) v = v.substring(1);
        return v;
    }
    /** Amount badge: bold with an outline, right-aligned at x, above items. */
    static void count(DrawContext c, String value, float right, float cy, int color) {
        var m = c.getMatrices(); m.push();
        try {
            m.translate(0, 0, 200);
            float w = UiResources.text().width(value, 19, BOLD);
            UiResources.text().drawOutline(c, value, right - w, cy, 19, color, BOLD, false);
        } finally { m.pop(); }
    }
    static void item(DrawContext c, ItemStack stack, float cx, float cy, float size) {
        if (stack == null || stack.isEmpty() || size < 2) return;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(cx - size / 2, cy - size / 2, 100);
            m.scale(size / 16f, size / 16f, 1);
            c.drawItem(stack, 0, 0);
        } finally { m.pop(); }
    }
    static void outline(DrawContext c, Box b, float t, int color) {
        HudMesh.line(c, b.x(), b.y(), b.right(), b.y(), t, color);
        HudMesh.line(c, b.x(), b.bottom(), b.right(), b.bottom(), t, color);
        HudMesh.line(c, b.x(), b.y(), b.x(), b.bottom(), t, color);
        HudMesh.line(c, b.right(), b.y(), b.right(), b.bottom(), t, color);
    }

    /** Whole art scaled so its frame box lands exactly on `target` (glow margins spill outside, as drawn). */
    static void drawArt(DrawContext c, Art a, Box target, int tint) {
        float sx = target.w() / a.frameW(), sy = target.h() / a.frameH();
        int x = Math.round(target.x() - a.frameX() * sx), y = Math.round(target.y() - a.frameY() * sy);
        int w = Math.round(a.width() * sx), h = Math.round(a.height() * sy);
        UiResources.images().drawTexture(c, tex(a.name()), x, y, 0, 0, w, h, a.width(), a.height(), a.width(), a.height(), tint);
    }

    /**
     * Nine-slice (same rule as the artifact codex): the frame box lands on `cell`, corners keep the scale of the
     * height so a wide row or search field does not stretch its ornaments.
     */
    static void nine(DrawContext c, Art a, int corner, Box cell, int tint) {
        int tw = a.width(), th = a.height(), fl = a.frameX(), ft = a.frameY(), fr = a.frameX() + a.frameW(), fb = a.frameY() + a.frameH();
        float s = cell.h() / (fb - ft);
        int[] sx = {0, fl + corner, fr - corner, tw};
        int[] sy = {0, ft + corner, fb - corner, th};
        float x0 = cell.x() - fl * s, y0 = cell.y() - ft * s;
        float x3 = cell.right() + (tw - fr) * s, y3 = cell.bottom() + (th - fb) * s;
        float[] dx = {x0, x0 + sx[1] * s, x3 - (tw - sx[2]) * s, x3};
        float[] dy = {y0, y0 + sy[1] * s, y3 - (th - sy[2]) * s, y3};
        if (dx[2] < dx[1]) { float mid = (dx[1] + dx[2]) / 2; dx[1] = dx[2] = mid; }
        if (dy[2] < dy[1]) { float mid = (dy[1] + dy[2]) / 2; dy[1] = dy[2] = mid; }
        var images = UiResources.images(); var id = tex(a.name());
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int u = sx[col], v = sy[row], sw = sx[col + 1] - u, sh = sy[row + 1] - v;
            int x = Math.round(dx[col]), y = Math.round(dy[row]);
            int w = Math.round(dx[col + 1]) - x, h = Math.round(dy[row + 1]) - y;
            if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) continue;
            images.drawTexture(c, id, x, y, u, v, w, h, sw, sh, tw, th, tint);
        }
    }
}
