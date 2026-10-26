package school.magiccodex.client.kui;

import java.util.List;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 마법 선택 창 (spell_select_popup; 1536×1024). Top: the six match slots (key under the icon, flight marked 고정).
 * Below: learned spells 8×3 per page (wheel scrolls rows). Click a slot, then a spell; drag slot onto slot to swap.
 */
public final class SpellPickView {
    private SpellPickView() {}
    public static final float W = 1536, H = 1024;
    public static final int COLS = 8, ROWS = 3;

    /** detail = 재사용·마나 한 줄, description = 마법 설명 (도감 문구, 없으면 ""). */
    public record Cell(String id, String icon, String name, String detail, String description) {
        public Cell(String id, String icon, String name, String detail) { this(id, icon, name, detail, ""); }
    }
    /** listening: slot whose match key is being chosen (-1 none). */
    public record Data(List<Cell> equipped, List<String> keys, int flightSlot, List<Cell> catalog, int selected, int scrollRow,
                       int dragFrom, String notice, int listening) {
        public Data(List<Cell> equipped, List<String> keys, int flightSlot, List<Cell> catalog, int selected, int scrollRow, int dragFrom, String notice) {
            this(equipped, keys, flightSlot, catalog, selected, scrollRow, dragFrom, notice, -1);
        }
    }

    public static final String CLOSE = "close", CONFIRM = "confirm";
    public static String slotId(int i) { return "slot:" + i; }
    public static String spellId(int i) { return "spell:" + i; }
    /** The key label under a slot: click = choose that slot's match key, right click = back to the normal keys. */
    public static String keyId(int i) { return "key:" + i; }

    public static float slotX(int i) { return 254 + i * 176; }
    public static final float SLOT_Y = 215;

    /** Word wrap (spaces first, otherwise by character — Korean has long runs). The last line gets "…" when cut. */
    static List<String> wrap(Pen p, String text, float size, float max, int maxLines) {
        var out = new java.util.ArrayList<String>();
        if (text == null || text.isBlank()) return out;
        for (String para : text.replace("\r", "").split("\n")) {
            String rest = para.strip();
            while (!rest.isEmpty()) {
                if (out.size() == maxLines) { String last = out.remove(maxLines - 1); while (!last.isEmpty() && p.width(last + "…", size, Face.SANS) > max) last = last.substring(0, last.length() - 1); out.add(last + "…"); return out; }
                int cut = rest.length();
                while (cut > 1 && p.width(rest.substring(0, cut), size, Face.SANS) > max) cut--;
                if (cut < rest.length()) { int sp = rest.lastIndexOf(' ', cut); if (sp > cut / 2) cut = sp; }
                out.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).strip();
            }
        }
        return out;
    }

    public static void paint(Pen p, Data d) {
        p.image("skills/panel", 120, 29, 1298, 951);
        p.text("마법 세팅", 768, 112, 40, Theme.GOLD, Face.TITLE, Canvas.CENTER);
        p.button(CLOSE, "skills/close", 1330, 68, 47, 45, true);
        for (int i = 0; i < 6; i++) {
            float x = slotX(i), y = SLOT_Y;
            boolean sel = i == d.selected();
            p.image(sel ? "skills/equipped_slot_selected" : "skills/equipped_slot", x, y, 151, 152);
            p.hit(slotId(i), x, y, 151, 152);
            Cell c = i < d.equipped().size() ? d.equipped().get(i) : null;
            if (c != null && c.icon() != null && i != d.dragFrom()) p.c.texture(c.icon(), x + 32.5f, y + 16, 86, 86, 1);
            String key = i < d.keys().size() ? d.keys().get(i) : "";
            p.hit(keyId(i), x + 20, y + 104, 111, 34);
            if (i == d.listening()) p.text("키 입력…", x + 75.5f, y + 120, 16, Theme.GOLD, Face.SANS_BOLD, Canvas.CENTER);
            else if (!key.isEmpty()) p.text(key, x + 75.5f, y + 120, 16, p.over(x + 20, y + 104, 111, 34) ? Theme.GOLD : Theme.CREAM, Face.SANS_BOLD, Canvas.CENTER);
            else p.text("키 지정", x + 75.5f, y + 120, 14, Theme.MUTED, Face.SANS, Canvas.CENTER);
            if (i == d.flightSlot()) p.text("고정", x + 128, y + 24, 14, Theme.TEAL, Face.SANS_BOLD, Canvas.RIGHT);
        }
        p.text(d.listening() >= 0 ? "쓸 키를 누르세요  ·  Esc 취소" : "칸 아래 키 이름을 누르면 경기 키를 바꿀 수 있어요  ·  키 이름 우클릭: 원래 키로",
                176, 812, 15, d.listening() >= 0 ? Theme.GOLD : Theme.MUTED, Face.SANS, Canvas.LEFT);
        Cell hover = null; float hx = 0, hy = 0;
        for (int k = 0; k < COLS * ROWS; k++) {
            int idx = (d.scrollRow() + k / COLS) * COLS + k % COLS;
            float x = 174 + (k % COLS) * 150.7f, y = 416 + (k / COLS) * 133.5f;
            Cell c = idx < d.catalog().size() ? d.catalog().get(idx) : null;
            boolean equipped = c != null && d.equipped().stream().anyMatch(e -> e != null && e.id().equals(c.id()));
            p.image(equipped ? "skills/catalog_slot_selected" : "skills/catalog_slot", x, y, 136, 123);
            if (c == null) continue;
            p.hit(spellId(idx), x, y, 136, 123);
            p.c.texture(c.icon(), x + 32, y + 12, 72, 72, equipped ? .45f : 1);
            p.fit(c.name(), x + 68, y + 100, 15, 112, equipped ? Theme.MUTED : Theme.CREAM, Face.SERIF, Canvas.CENTER);
            if (p.over(x, y, 136, 123)) { hover = c; hx = x; hy = y; }
        }
        int rows = (d.catalog().size() + COLS - 1) / COLS;
        if (rows > ROWS) p.text((d.scrollRow() + 1) + " / " + (rows - ROWS + 1) + "  ·  휠로 넘기기", 1360, 812, 15, Theme.MUTED, Face.SANS, Canvas.RIGHT);
        p.button(CONFIRM, "skills/confirm", 481, 831, 578, 105, true);
        p.text("확인", 770, 883, 36, Theme.CREAM, Face.TITLE, Canvas.CENTER);
        if (d.notice() != null && !d.notice().isEmpty()) p.text(d.notice(), 768, 175, 18, 0xFFFFB0A8, Face.SANS, Canvas.CENTER);
        if (hover != null) {
            // owner 2026-10-20: the tooltip also shows the spell description (wrapped, up to 5 lines)
            List<String> desc = wrap(p, hover.description(), 15, 300, 5);
            float tw = Math.max(186, Math.max(p.width(hover.name(), 20, Face.SERIF), p.width(hover.detail(), 15, Face.SANS)) + 36);
            for (String line : desc) tw = Math.max(tw, p.width(line, 15, Face.SANS) + 36);
            float th = 80 + (desc.isEmpty() ? 0 : 10 + desc.size() * 21);
            float tx = hx + 146 + tw > 1400 ? hx - tw - 10 : hx + 146, ty = Math.min(hy + 22, 980 - th);
            p.image("skills/tooltip_background", tx, ty, tw, th);
            p.text(hover.name(), tx + 18, ty + 28, 20, Theme.GOLD, Face.SERIF, Canvas.LEFT);
            p.text(hover.detail(), tx + 18, ty + 56, 15, Theme.TEAL, Face.SANS, Canvas.LEFT);
            for (int i = 0; i < desc.size(); i++) p.text(desc.get(i), tx + 18, ty + 87 + i * 21, 15, Theme.CREAM, Face.SANS, Canvas.LEFT);
        }
        if (d.dragFrom() >= 0 && d.dragFrom() < d.equipped().size() && d.equipped().get(d.dragFrom()) != null)
            p.c.texture(d.equipped().get(d.dragFrom()).icon(), p.mx - 45, p.my - 45, 90, 90, .85f);
    }
}
