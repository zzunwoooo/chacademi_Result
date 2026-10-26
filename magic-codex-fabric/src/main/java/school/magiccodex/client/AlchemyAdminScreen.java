package school.magiccodex.client;

import static school.magiccodex.client.AlchemyScreen.*;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.AlchemyLayout.Box;
import school.magiccodex.protocol.AlchemyAdminProtocol;
import school.magiccodex.protocol.AlchemyAdminProtocol.Row;
import school.magiccodex.protocol.AlchemyProtocol;
import school.magiccodex.protocol.AlchemyProtocol.Stack;

/**
 * 연금술 레시피 관리 (operators, /연금술관리). Same art and fonts as the alchemy window: the recipe list on the right,
 * the selected recipe on the left drawn as it will look to players (ingredients round the circle, result in the ring).
 * Click a slot or the ring to pick an item from your own inventory (copied, never taken); wheel = amount, right click =
 * empty. Type the name and the experience, tick "레시피 사용", press 저장: the server writes alchemy.yml and applies it.
 */
final class AlchemyAdminScreen extends Screen {
    private static final int RESULT_PICK = 100;

    private TextFieldWidget search, name, exp;
    private int focus;                         // 0 none, 1 search, 2 name, 3 exp
    private String query = "";
    // draft
    private String editingId = "", resultKey = "";
    private int resultAmount = 1, order;
    private boolean enabled = true;
    private String[] keys = new String[6];
    private int[] amounts = new int[6];
    private Row baseline;
    // ui
    private int picker = -1, scroll;
    private boolean deleteArmed, leaveArmed;
    private String leaveTarget;
    private long pendingSeq, pendingDeadline, seenVersion = -1;
    private String pendingSelect;
    private String message = "";
    private int messageColor = TEXT;
    private boolean first = true;

    AlchemyAdminScreen() { super(Text.literal("연금술 레시피 관리")); }

    @Override protected void init() {
        search = field(search, 20, null);
        name = field(name, AlchemyProtocol.MAX_NAME, null);
        exp = field(exp, 7, s -> s.matches("\\d{0,7}"));
        if (first) { first = false; newDraft(); exp.setText("0"); sound(SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1.3f, .4f); }
    }
    private TextFieldWidget field(TextFieldWidget old, int max, java.util.function.Predicate<String> filter) {
        var f = new TextFieldWidget(textRenderer, 0, 0, 300, 24, Text.literal(""));
        f.setMaxLength(max);
        if (filter != null) f.setTextPredicate(filter);
        if (old != null) f.setText(old.getText());
        return f;
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { AlchemyAdminClient.closed(); }

    // ------------------------------------------------------------------ draft

    private void resize() {
        int n = AlchemyAdminClient.slots();
        if (keys.length != n) { keys = Arrays.copyOf(keys, n); amounts = Arrays.copyOf(amounts, n); if (picker >= n && picker != RESULT_PICK) picker = -1; }
    }

    private void newDraft() {
        editingId = ""; resultKey = ""; resultAmount = 1; enabled = true; order = 0;
        Arrays.fill(keys, null); Arrays.fill(amounts, 0);
        if (name != null) name.setText(""); if (exp != null) exp.setText("0");
        deleteArmed = false; leaveArmed = false; baseline = draft();
    }

    private void load(Row r) {
        newDraft();
        editingId = r.id(); resultKey = r.result(); resultAmount = r.amount(); enabled = r.enabled(); order = r.order();
        name.setText(r.name()); exp.setText(Integer.toString(r.exp()));
        int i = 0;
        for (Stack s : r.ingredients()) if (i < keys.length) { keys[i] = s.key(); amounts[i] = s.amount(); i++; }
        baseline = draft();
    }

    private int expValue() { try { return exp == null || exp.getText().isEmpty() ? 0 : Math.min(AlchemyProtocol.MAX_EXP, Integer.parseInt(exp.getText())); } catch (NumberFormatException e) { return 0; } }

    private Row draft() {
        var list = new ArrayList<Stack>();
        for (int i = 0; i < keys.length; i++) if (keys[i] != null && amounts[i] > 0) list.add(new Stack(keys[i], amounts[i]));
        return new Row(editingId, name == null ? "" : name.getText(), resultKey, resultAmount, expValue(), enabled, order, list);
    }
    private boolean dirty() { return baseline != null && !baseline.equals(draft()); }

    private Row row(String id) { for (Row r : AlchemyAdminClient.rows()) if (r.id().equals(id)) return r; return null; }

    private List<Row> rows() {
        String q = query.strip().toLowerCase(Locale.ROOT);
        var list = new ArrayList<Row>();
        for (Row r : AlchemyAdminClient.rows()) if (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q)) list.add(r);
        return list;
    }

    // ------------------------------------------------------------------ server

    void onResult(AlchemyAdminProtocol.Result r) {
        if (r.sequence() != pendingSeq) return;
        pendingSeq = 0;
        say(r.message(), r.ok() ? TITLE_GOLD : WARN);
        if (r.ok()) {
            if (r.id().isEmpty()) pendingSelect = "";       // deleted: start a new draft once the list updates
            else pendingSelect = r.id();
            sound(r.id().isEmpty() ? SoundEvents.BLOCK_BEACON_DEACTIVATE : SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f, .4f);
        } else sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f);
    }

    private void say(String text, int color) { message = text == null ? "" : text; messageColor = color; }

    @Override public void tick() {
        if (client == null || client.player == null || !client.player.isAlive()) { close(); return; }
        resize();
        if (AlchemyAdminClient.version() != seenVersion) {
            seenVersion = AlchemyAdminClient.version();
            if (pendingSelect != null) {
                Row r = pendingSelect.isEmpty() ? null : row(pendingSelect);
                if (r != null) load(r); else newDraft();
                pendingSelect = null;
            }
        }
        if (pendingSeq != 0 && Util.getMeasuringTimeMs() > pendingDeadline) { pendingSeq = 0; say("서버 응답이 늦습니다. 다시 시도해 주세요.", WARN); AlchemyAdminClient.query(); }
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); UiResources.text().beginFrame(); images.beginFrame();
        c.fill(0, 0, width, height, 0x88070D13);
        long now = Util.getMeasuringTimeMs();
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            images.drawTexture(c, PANEL, 0, 0, 0, 0, 1672, 941, 1672, 941);
            text(c, "레시피 관리", AlchemyLayout.TITLE_X, AlchemyLayout.TITLE_Y, 34, TITLE_GOLD, TITLE, true);
            if (AlchemyLayout.CLOSE.contains(mx, my)) HudMesh.disk(c, AlchemyLayout.CLOSE.cx(), AlchemyLayout.CLOSE.cy(), 22, 0x30FFE3A8);
            boolean modal = picker >= 0;
            double hx = modal ? -1 : mx, hy = modal ? -1 : my;
            left(c, now, hx, hy);
            var rows = rows();
            scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - AlchemyLayout.ROWS));
            right(c, now, hx, hy, rows);
            if (modal) picker(c, mx, my);
            else tooltips(c, mx, my, rows);
        } finally { m.pop(); images.endFrame(); }
    }

    private void drawField(DrawContext c, Box box, TextFieldWidget f, boolean focused, String placeholder, double mx, double my, long now, float textX) {
        nine(c, AlchemyLayout.SEARCH, 30, box, focused ? 0xFFFFFFFF : box.contains(mx, my) ? 0xFFF4F0E6 : 0xFFDCE0E8);
        String value = f.getText();
        if (value.isEmpty() && !focused) text(c, placeholder, textX, box.cy(), 20, MUTED, BODY, false);
        else {
            String shown = tail(value, 20, BODY, box.right() - textX - 22);
            text(c, shown, textX, box.cy(), 20, TEXT, BODY, false);
            if (focused && now / 500 % 2 == 0) {
                float cursor = textX + UiResources.text().width(shown, 20, BODY) + 2;
                c.fill((int) cursor, (int) box.cy() - 12, (int) cursor + 2, (int) box.cy() + 12, TEXT);
            }
        }
    }

    private void left(DrawContext c, long now, double mx, double my) {
        var images = UiResources.images();
        // name row
        text(c, "이름", AlchemyAdminLayout.LABEL_X, AlchemyAdminLayout.NAME_Y, 21, IVORY, BOLD, false);
        drawField(c, AlchemyAdminLayout.NAME_BOX, name, focus == 2, "비우면 결과 아이템 이름", mx, my, now, AlchemyAdminLayout.NAME_BOX.x() + 22);
        String idText = editingId.isEmpty() ? "새 레시피" : editingId;
        text(c, idText, AlchemyAdminLayout.ID_RIGHT - UiResources.text().width(idText, 16, LABEL), AlchemyAdminLayout.NAME_Y, 16, editingId.isEmpty() ? CYAN : MUTED, LABEL, false);

        // circle + slots + result (as players will see it)
        float cx = AlchemyAdminLayout.CIRCLE_X, cy = AlchemyAdminLayout.CIRCLE_Y, size = AlchemyAdminLayout.CIRCLE_SIZE;
        int x0 = Math.round(cx - size / 2), y0 = Math.round(cy - size / 2), s = Math.round(size);
        images.drawTexture(c, CIRCLE, x0, y0, 0, 0, s, s, 1024, 1024, 1024, 1024, enabled ? 0xFFFFFFFF : 0xFF8890A0);
        images.drawTextureAdditive(c, CIRCLE, x0, y0, 0, 0, s, s, 1024, 1024, 1024, 1024, argb(.08f + .03f * (float) Math.sin(now / 600.0), 0x8FE3FF));
        int n = keys.length;
        for (int i = 0; i < n; i++) {
            var b = AlchemyAdminLayout.slot(i, n);
            boolean over = b.contains(mx, my);
            drawArt(c, over || picker == i ? AlchemyLayout.SLOT_HOVER : AlchemyLayout.SLOT, b, 0xFFFFFFFF);
            if (keys[i] == null) {
                int col = over ? 0xCCE7C77E : 0x557F96B5;
                HudMesh.line(c, b.cx() - 10, b.cy(), b.cx() + 10, b.cy(), 2.4f, col); HudMesh.line(c, b.cx(), b.cy() - 10, b.cx(), b.cy() + 10, 2.4f, col);
            } else {
                item(c, AlchemyAdminClient.icon(keys[i]), b.cx(), b.cy() - 2, 50);
                count(c, "×" + amounts[i], b.right() - 20, b.bottom() - 21, IVORY);
                var held = AlchemyAdminClient.held(keys[i]);
                if (held != null && !held.material()) HudMesh.star(c, b.x() + 24, b.y() + 24, 7, 0xFFFFD36A);   // will be registered as a material
            }
        }
        var ring = AlchemyAdminLayout.RESULT;
        boolean overRing = ring.contains(mx, my);
        drawArt(c, AlchemyLayout.RING, ring, overRing || picker == RESULT_PICK ? 0xFFFFFFFF : 0xFFE8ECF2);
        if (resultKey.isEmpty()) text(c, "결과", ring.cx(), ring.cy(), 22, overRing ? GOLD : 0xAA9FD8E8, BOLD, true);
        else {
            item(c, AlchemyAdminClient.icon(resultKey), ring.cx(), ring.cy(), 64);
            count(c, "×" + resultAmount, ring.cx() + 40, ring.cy() + 36, IVORY);
        }

        // experience + use
        float ry = AlchemyAdminLayout.ROW_Y;
        text(c, "소모 경험치", AlchemyAdminLayout.LABEL_X, ry, 20, IVORY, BOLD, false);
        drawField(c, AlchemyAdminLayout.EXP_BOX, exp, focus == 3, "0", mx, my, now, AlchemyAdminLayout.EXP_BOX.x() + 18);
        text(c, "포인트 (레벨 아님)", AlchemyAdminLayout.EXP_HINT_X, ry, 16, MUTED, LABEL, false);
        var cb = AlchemyAdminLayout.CHECK;
        boolean overCheck = AlchemyAdminLayout.CHECK_HIT.contains(mx, my);
        drawArt(c, enabled ? AlchemyLayout.CHECK_ON : AlchemyLayout.CHECK_OFF, cb, enabled || overCheck ? 0xFFFFFFFF : 0xFFD5DAE2);
        text(c, enabled ? "레시피 사용 중" : "레시피 꺼짐", AlchemyAdminLayout.CHECK_LABEL_X, cb.cy(), 19, enabled ? 0xFFD7DCE3 : MUTED, LABEL, false);

        // message
        String line = pendingSeq != 0 ? "서버에 저장하는 중…" : !message.isEmpty() ? message
                : leaveArmed ? "저장하지 않은 변경이 있습니다. 한 번 더 누르면 이동합니다."
                : dirty() ? "변경 사항이 있습니다. 저장을 눌러야 적용됩니다." : "슬롯·고리를 클릭해 내 인벤토리에서 아이템을 고르세요 · 휠: 개수 · 우클릭: 비우기";
        int color = pendingSeq != 0 ? CYAN : !message.isEmpty() ? messageColor : leaveArmed ? WARN : dirty() ? GOLD : MUTED;
        text(c, line, AlchemyAdminLayout.CIRCLE_X, AlchemyAdminLayout.MESSAGE_Y, fit(line, 18, BODY, 820), color, BODY, true);

        // buttons
        button(c, AlchemyAdminLayout.SAVE, "저장", pendingSeq == 0, mx, my, 0xFFFFFFFF);
        if (!editingId.isEmpty()) button(c, AlchemyAdminLayout.DELETE, deleteArmed ? "정말 삭제" : "삭제", pendingSeq == 0, mx, my, deleteArmed ? 0xFFFF9A9A : 0xFFE8B4B4);
    }

    private void button(DrawContext c, Box b, String label, boolean on, double mx, double my, int tint) {
        boolean over = on && b.contains(mx, my);
        drawArt(c, over ? AlchemyLayout.BUTTON_HOVER : AlchemyLayout.BUTTON, b, on ? tint : 0xFF8A93A3);
        text(c, label, b.cx(), b.cy() + 1, fit(label, 27, TITLE, b.w() - 50), on ? (over ? 0xFFFFF1CC : TITLE_GOLD) : 0xFF8A97A8, TITLE, true);
    }

    private void right(DrawContext c, long now, double mx, double my, List<Row> rows) {
        var right = AlchemyLayout.RIGHT;
        float hy = AlchemyLayout.HEADER_Y, tw = UiResources.text().width("레시피 목록", 32, TITLE);
        text(c, "레시피 목록", right.cx(), hy, 32, TITLE_GOLD, TITLE, true);
        for (int side = -1; side <= 1; side += 2) {
            float inner = right.cx() + side * (tw / 2 + 20), outer = right.cx() + side * (tw / 2 + 120);
            HudMesh.line(c, Math.min(inner, outer), hy, Math.max(inner, outer), hy, 1.6f, 0xCCC9A55C);
            HudMesh.star(c, inner, hy, 6, GOLD);
        }
        var sb = AlchemyLayout.SEARCH_BOX;
        drawField(c, sb, search, focus == 1, "레시피 검색", mx, my, now, sb.x() + 56);
        float ix = sb.x() + 30, iy = sb.cy() - 1;
        HudMesh.arc(c, ix, iy, 9, 2.4f, 0, (float) (Math.PI * 2), 0xFFC9D2DE);
        HudMesh.line(c, ix + 6.5f, iy + 6.5f, ix + 13, iy + 13, 2.6f, 0xFFC9D2DE);
        var all = AlchemyAdminClient.rows();
        long on = all.stream().filter(Row::enabled).count();
        text(c, "레시피 " + all.size() + "개 · 사용 " + on, AlchemyAdminLayout.COUNT.x(), AlchemyAdminLayout.COUNT.cy(), 18, MUTED, LABEL, false);
        var nb = AlchemyAdminLayout.NEW;
        boolean overNew = nb.contains(mx, my);
        drawArt(c, overNew ? AlchemyLayout.BUTTON_HOVER : AlchemyLayout.BUTTON, nb, 0xFFFFFFFF);
        text(c, "+ 새 레시피", nb.cx(), nb.cy() + 1, 19, overNew ? 0xFFFFF1CC : TITLE_GOLD, BOLD, true);

        if (AlchemyAdminClient.state() == null) text(c, "불러오는 중입니다…", right.cx(), AlchemyLayout.LIST.cy(), 20, MUTED, LABEL, true);
        else if (rows.isEmpty()) text(c, all.isEmpty() ? "아직 레시피가 없습니다. '+ 새 레시피'로 시작하세요." : "검색 결과가 없습니다.", right.cx(), AlchemyLayout.LIST.cy(), fit("아직 레시피가 없습니다. '+ 새 레시피'로 시작하세요.", 18, LABEL, 420), MUTED, LABEL, true);
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index >= rows.size()) break;
            Row r = rows.get(index); var box = AlchemyLayout.row(v);
            boolean over = box.contains(mx, my), chosen = r.id().equals(editingId);
            nine(c, chosen ? AlchemyLayout.ROW_SELECTED : r.enabled() ? AlchemyLayout.ROW : AlchemyLayout.ROW_LOCKED, 34, box, over || chosen ? 0xFFFFFFFF : 0xFFE0E4EC);
            item(c, AlchemyAdminClient.icon(r.result()), box.x() + 48, box.cy(), 44);
            String tail = r.enabled() ? "경험치 " + r.exp() : "꺼짐";
            float tailW = UiResources.text().width(tail, 16, LABEL);
            text(c, r.name(), box.x() + 92, box.cy(), fit(r.name(), 22, BOLD, box.w() - 92 - tailW - 40), r.enabled() ? IVORY : 0xFF9AA4B2, BOLD, false);
            text(c, tail, box.right() - 24 - tailW, box.cy(), 16, r.enabled() ? MUTED : 0xFFC98989, LABEL, false);
        }
        if (rows.size() > AlchemyLayout.ROWS) {
            var bar = AlchemyLayout.SCROLL;
            c.fill(Math.round(bar.x()), Math.round(bar.y()), Math.round(bar.right()), Math.round(bar.bottom()), 0x403A667F);
            float thumb = Math.max(30, bar.h() * AlchemyLayout.ROWS / rows.size());
            float py = bar.y() + (bar.h() - thumb) * scroll / (rows.size() - AlchemyLayout.ROWS);
            c.fill(Math.round(bar.x()), Math.round(py), Math.round(bar.right()), Math.round(py + thumb), 0xFFC9A55C);
        }
        float fy = AlchemyLayout.FOOTER_LINE_Y;
        HudMesh.line(c, right.cx() - 190, fy, right.cx() - 12, fy, 1.2f, 0x88C9A55C);
        HudMesh.line(c, right.cx() + 12, fy, right.cx() + 190, fy, 1.2f, 0x88C9A55C);
        HudMesh.star(c, right.cx(), fy, 5, GOLD);
        String hint = "레시피를 누르면 왼쪽에서 편집합니다";
        text(c, hint, right.cx(), AlchemyLayout.FOOTER_Y, fit(hint, 17, BODY, 440), 0xFFB9C3D0, BODY, true);
    }

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
            text(c, picker == RESULT_PICK ? "결과 아이템 선택" : "재료 아이템 선택", p.cx(), AlchemyLayout.PICK_TITLE_Y, 28, TITLE_GOLD, TITLE, true);
            var x = AlchemyLayout.PICK_CLOSE; int xc = x.contains(mx, my) ? 0xFFFFE3A8 : GOLD; float r = 11;
            HudMesh.line(c, x.cx() - r, x.cy() - r, x.cx() + r, x.cy() + r, 3, xc); HudMesh.line(c, x.cx() - r, x.cy() + r, x.cx() + r, x.cy() - r, 3, xc);
            var list = AlchemyAdminClient.inventory();
            if (list.isEmpty()) {
                text(c, "인벤토리가 비어 있습니다.", p.cx(), p.cy() - 10, 22, MUTED, LABEL, true);
                text(c, "레시피에 쓸 아이템을 인벤토리에 넣고 다시 열어 주세요.", p.cx(), p.cy() + 24, 17, DIM, BODY, true);
            }
            int max = AlchemyLayout.PICK_COLUMNS * AlchemyLayout.PICK_ROWS;
            for (int i = 0; i < Math.min(max, list.size()); i++) {
                var h = list.get(i); var cell = AlchemyLayout.pickCell(i);
                boolean over = cell.contains(mx, my);
                drawArt(c, over ? AlchemyLayout.SLOT_HOVER : AlchemyLayout.SLOT, cell, 0xFFFFFFFF);
                item(c, AlchemyAdminClient.icon(h.key()), cell.cx(), cell.cy() - 2, 42);
                if (picker != RESULT_PICK && !h.material()) HudMesh.star(c, cell.x() + 13, cell.y() + 13, 6, 0xFFFFD36A);
            }
            String hint = picker == RESULT_PICK ? "클릭: 결과로 정하기  ·  아이템은 복사만 되고 빠지지 않아요"
                    : "클릭: 넣기  ·  ★ 표시는 '재료'가 아닌 아이템 (저장하면 재료로 자동 등록)";
            text(c, hint, p.cx(), AlchemyLayout.PICK_HINT_Y, fit(hint, 17, LABEL, p.w() - 40), MUTED, LABEL, true);
            for (int i = 0; i < Math.min(max, list.size()); i++) if (AlchemyLayout.pickCell(i).contains(mx, my)) {
                var h = list.get(i);
                tooltip(c, List.of(AlchemyAdminClient.name(h.key()), h.key(), h.material() ? "재료 카테고리 아이템" : "재료 카테고리가 아님 (저장 시 재료로 등록)"), mx, my);
            }
        } finally { m.pop(); }
    }

    private void tooltips(DrawContext c, double mx, double my, List<Row> rows) {
        int n = keys.length;
        for (int i = 0; i < n; i++) if (AlchemyAdminLayout.slot(i, n).contains(mx, my)) {
            if (keys[i] == null) tooltip(c, List.of("재료 칸", "클릭해서 내 인벤토리의 아이템을 고르세요", "넣는 순서는 상관없어요"), mx, my);
            else tooltip(c, List.of(AlchemyAdminClient.name(keys[i]) + " ×" + amounts[i], keys[i], "휠: 개수  ·  우클릭: 비우기  ·  클릭: 바꾸기"), mx, my);
            return;
        }
        if (AlchemyAdminLayout.RESULT.contains(mx, my)) {
            if (resultKey.isEmpty()) tooltip(c, List.of("결과 아이템", "클릭해서 만들어질 아이템을 고르세요"), mx, my);
            else tooltip(c, List.of(AlchemyAdminClient.name(resultKey) + " ×" + resultAmount, resultKey, "휠: 개수 (최대 " + AlchemyAdminProtocol.MAX_RESULT_AMOUNT + ")  ·  클릭: 바꾸기"), mx, my);
            return;
        }
        if (AlchemyAdminLayout.CHECK_HIT.contains(mx, my)) { tooltip(c, List.of("레시피 사용", "끄면 게임에서 사라집니다 (지우지는 않아요)"), mx, my); return; }
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index >= rows.size() || !AlchemyLayout.row(v).contains(mx, my)) continue;
            Row r = rows.get(index);
            var lines = new ArrayList<String>();
            lines.add(r.name() + (r.amount() > 1 ? " ×" + r.amount() : "") + (r.enabled() ? "" : " (꺼짐)"));
            for (Stack s : r.ingredients()) lines.add("· " + AlchemyAdminClient.name(s.key()) + " ×" + s.amount());
            lines.add("소모 경험치 " + r.exp() + "  ·  " + r.id());
            tooltip(c, lines, mx, my);
            return;
        }
    }

    // ------------------------------------------------------------------ input

    private void setFocus(int f) {
        focus = f;
        search.setFocused(f == 1); name.setFocused(f == 2); exp.setFocused(f == 3);
    }
    private TextFieldWidget focused() { return focus == 1 ? search : focus == 2 ? name : focus == 3 ? exp : null; }

    @Override public boolean mouseClicked(double x, double y, int button) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        if (picker >= 0) { pickerClick(mx, my, button); return true; }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && AlchemyLayout.CLOSE.contains(mx, my)) { click(); close(); return true; }
        int f = button != GLFW.GLFW_MOUSE_BUTTON_LEFT ? 0 : AlchemyLayout.SEARCH_BOX.contains(mx, my) ? 1 : AlchemyAdminLayout.NAME_BOX.contains(mx, my) ? 2 : AlchemyAdminLayout.EXP_BOX.contains(mx, my) ? 3 : 0;
        setFocus(f);
        if (f != 0) { message = ""; return true; }
        int n = keys.length;
        for (int i = 0; i < n; i++) if (AlchemyAdminLayout.slot(i, n).contains(mx, my)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { if (keys[i] != null) { keys[i] = null; amounts[i] = 0; sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.4f, .4f); } }
            else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { picker = i; sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, .6f); }
            message = ""; return true;
        }
        if (AlchemyAdminLayout.RESULT.contains(mx, my)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { resultKey = ""; resultAmount = 1; }
            else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { picker = RESULT_PICK; sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, .6f); }
            message = ""; return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (AlchemyAdminLayout.CHECK_HIT.contains(mx, my)) { enabled = !enabled; message = ""; click(); return true; }
        if (AlchemyAdminLayout.SAVE.contains(mx, my)) { save(); return true; }
        if (!editingId.isEmpty() && AlchemyAdminLayout.DELETE.contains(mx, my)) {
            if (pendingSeq != 0) return true;
            if (!deleteArmed) { deleteArmed = true; say("한 번 더 누르면 '" + name.getText() + "' 레시피를 지웁니다.", WARN); sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1f, .4f); return true; }
            deleteArmed = false;
            long seq = AlchemyAdminClient.delete(editingId);
            if (seq == 0) say("서버에 연결되어 있지 않습니다.", WARN); else { pendingSeq = seq; pendingDeadline = Util.getMeasuringTimeMs() + 5000; message = ""; }
            return true;
        }
        deleteArmed = false;
        if (AlchemyAdminLayout.NEW.contains(mx, my)) { leave(""); return true; }
        var rows = rows();
        for (int v = 0; v < AlchemyLayout.ROWS; v++) {
            int index = scroll + v;
            if (index < rows.size() && AlchemyLayout.row(v).contains(mx, my)) { leave(rows.get(index).id()); return true; }
        }
        return true;
    }

    /** Switch to another recipe ("" = new). With unsaved changes the first click only warns. */
    private void leave(String target) {
        if (target.equals(editingId) && !target.isEmpty()) return;
        if (dirty() && !(leaveArmed && Objects.equals(leaveTarget, target))) {
            leaveArmed = true; leaveTarget = target; message = ""; sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1f, .4f); return;
        }
        leaveArmed = false; leaveTarget = null; message = "";
        Row r = target.isEmpty() ? null : row(target);
        if (r == null) newDraft(); else load(r);
        sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.1f, .5f);
    }

    private void save() {
        if (pendingSeq != 0) return;
        Row d = draft();
        if (d.result().isEmpty()) { say("결과 아이템을 정해 주세요. (가운데 고리를 클릭)", WARN); sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f); return; }
        if (d.ingredients().isEmpty()) { say("재료를 하나 이상 넣어 주세요.", WARN); sound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), .7f, .5f); return; }
        long seq = AlchemyAdminClient.save(d);
        if (seq == 0) { say("서버에 연결되어 있지 않습니다.", WARN); return; }
        pendingSeq = seq; pendingDeadline = Util.getMeasuringTimeMs() + 5000; message = ""; leaveArmed = false;
        sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, .4f);
    }

    private void pickerClick(double mx, double my, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || AlchemyLayout.PICK_CLOSE.contains(mx, my) || !AlchemyLayout.PICKER.contains(mx, my)) { picker = -1; click(); return; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        var list = AlchemyAdminClient.inventory();
        int max = AlchemyLayout.PICK_COLUMNS * AlchemyLayout.PICK_ROWS;
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (!AlchemyLayout.pickCell(i).contains(mx, my)) continue;
            String key = list.get(i).key();
            if (picker == RESULT_PICK) { resultKey = key; if (resultAmount < 1) resultAmount = 1; }
            else {
                for (int j = 0; j < keys.length; j++) if (j != picker && key.equals(keys[j])) {
                    say("이미 다른 칸에 있는 재료입니다. 그 칸에서 휠로 개수를 늘려 주세요.", WARN); picker = -1; return;
                }
                if (!key.equals(keys[picker])) { keys[picker] = key; amounts[picker] = 1; }
            }
            sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f, .45f);
            picker = -1; message = "";
            return;
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0 || picker >= 0) return true;
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        int step = vertical > 0 ? 1 : -1, n = keys.length;
        for (int i = 0; i < n; i++) if (AlchemyAdminLayout.slot(i, n).contains(mx, my)) {
            if (keys[i] != null) { amounts[i] = Math.clamp(amounts[i] + step, 1, AlchemyProtocol.MAX_AMOUNT); nudge(step); }
            return true;
        }
        if (AlchemyAdminLayout.RESULT.contains(mx, my)) {
            if (!resultKey.isEmpty()) { resultAmount = Math.clamp(resultAmount + step, 1, AlchemyAdminProtocol.MAX_RESULT_AMOUNT); nudge(step); }
            return true;
        }
        if (AlchemyLayout.LIST.contains(mx, my)) scroll = Math.max(0, scroll - step);
        return true;
    }
    private void nudge(int step) { message = ""; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f + .1f * step, .15f); }

    @Override public boolean charTyped(char chr, int modifiers) {
        var f = focused();
        if (f == null) return super.charTyped(chr, modifiers);
        boolean done = f.charTyped(chr, modifiers);
        if (f == search) { query = search.getText(); scroll = 0; }
        return done;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (picker >= 0 && keyCode == GLFW.GLFW_KEY_ESCAPE) { picker = -1; return true; }
        var f = focused();
        if (f != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) { setFocus(0); return true; }
            if (keyCode == GLFW.GLFW_KEY_TAB) { setFocus(focus == 2 ? 3 : 2); return true; }
            f.keyPressed(keyCode, scanCode, modifiers);
            if (f == search) { query = search.getText(); scroll = 0; }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void click() { sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f); }
    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }
}
