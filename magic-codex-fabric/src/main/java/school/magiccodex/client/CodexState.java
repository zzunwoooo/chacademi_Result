package school.magiccodex.client;

import java.util.List;
import java.util.Locale;
import school.magiccodex.client.CodexData.Category;
import school.magiccodex.client.CodexData.Spell;

/** Pure screen state, independent of Minecraft and server authority. */
public final class CodexState {
    public enum Filter { ALL, DISCOVERED, UNDISCOVERED }
    public static final int PAGE_SIZE = 9;
    private List<Spell> spells;
    private Category category = Category.ALL;
    private Filter filter = Filter.ALL;
    private String query = "";
    private int page;
    private Spell selected;

    public CodexState(List<Spell> spells) { this.spells = List.copyOf(spells); }
    public void replaceSpells(List<Spell> updated) {
        String selectedId = selected == null ? null : selected.id();
        spells = List.copyOf(updated);
        page = Math.min(page, pages() - 1);
        selected = visible().stream().filter(s -> s.id().equals(selectedId)).findFirst().orElse(null);
    }
    public Category category() { return category; }
    public Filter filter() { return filter; }
    public String query() { return query; }
    public int page() { return page; }
    public Spell selected() { return selected; }
    public int total() { return spells.size(); }
    public long discoveredCount() { return spells.stream().filter(Spell::discovered).count(); }

    public List<Spell> filtered() {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return spells.stream()
                .filter(s -> category == Category.ALL || s.category() == category)
                .filter(s -> filter == Filter.ALL || s.permissionKnown() && s.discovered() == (filter == Filter.DISCOVERED))
                .filter(s -> (s.name() + " " + s.description()).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    public int pages() { return Math.max(1, (filtered().size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    public List<Spell> visible() { return filtered().stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).toList(); }
    public void setCategory(Category value) { if (category != value) { category = value; reset(); } }
    public void setFilter(Filter value) { if (filter != value) { filter = value; reset(); } }
    public void setQuery(String value) { if (!query.equals(value)) { query = value; reset(); } }
    private void reset() { page = 0; selected = null; }
    public boolean changePage(int direction) {
        int target = Math.clamp(page + direction, 0, pages() - 1);
        if (target == page) return false;
        page = target;
        selected = null;
        return true;
    }
    public boolean selectSlot(int slot) {
        List<Spell> current = visible();
        if (slot < 0 || slot >= current.size()) return false;
        selected = current.get(slot);
        return true;
    }
}
