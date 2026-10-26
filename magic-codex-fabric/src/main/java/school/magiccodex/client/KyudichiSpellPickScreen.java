package school.magiccodex.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.client.kui.SpellPickView;

/**
 * 마법 선택 창 (SpellPickView). Pick a slot, then a learned spell; drag slot onto slot to swap; right click empties a slot
 * (never the flight slot). Changes save at once (kyudichi-loadout.yml) and the bottom-right bar follows during the match.
 */
final class KyudichiSpellPickScreen extends KyudichiViewScreen {
    private final Screen parent;
    private int selected = 0, scroll, dragFrom = -1, listening = -1;
    private boolean dragging;
    private String notice = "";
    private long noticeAt;

    private final int mode;
    KyudichiSpellPickScreen(Screen parent) { this(parent, KyudichiClient.duel() ? KyudichiLoadout.DUEL : KyudichiLoadout.KYUDICHI); }
    /** mode: 0 큐디치 · 1 1:1 대전 (each has its own six, saved separately). */
    KyudichiSpellPickScreen(Screen parent, int mode) { super(mode == KyudichiLoadout.DUEL ? "1:1 대전 마법 세팅" : "큐디치 마법 세팅"); this.parent = parent; this.mode = mode; }
    private KyudichiLoadout loadout() { return KyudichiLoadout.forMode(mode); }

    @Override float designW() { return SpellPickView.W; }
    @Override float designH() { return SpellPickView.H; }

    /** Learned spells; 1:1 대전 leaves out flight (banned there). */
    private List<CodexData.Spell> learned() {
        var out = new ArrayList<CodexData.Spell>();
        for (var s : CodexCatalog.spells())
            // same rule as the key settings window (learned = discovered + permission known); the live spell files have no cast command
            if (s.discovered() && s.permissionKnown() && !(mode == KyudichiLoadout.DUEL && s.id().equals(KyudichiLoadout.FLIGHT))) out.add(s);
        out.sort(Comparator.comparingInt(CodexData.Spell::order));
        return out;
    }
    private static SpellPickView.Cell cell(CodexData.Spell s) {
        if (s == null) return null;
        String cd = s.cooldown() % 1 == 0 ? String.valueOf((long) s.cooldown()) : String.format("%.1f", s.cooldown());
        return new SpellPickView.Cell(s.id(), s.icon(), s.name(), "재사용 " + cd + "초" + (s.manaCost() > 0 ? " · 마나 " + s.manaCost() : ""),
                s.description() == null ? "" : s.description());
    }

    @Override void paint(Pen p) {
        var l = loadout();
        var equipped = new ArrayList<SpellPickView.Cell>();
        for (int i = 0; i < KyudichiLoadout.SIZE; i++) equipped.add(cell(CastingClient.state().spell(l.get(i))));
        var keys = new ArrayList<String>();
        for (var b : l.bindings(CastingClient.base().orderedSlots())) keys.add(b.keyCode() < 0 ? "" : KeySettingsLayout.name(b.keyCode()));
        var catalog = learned().stream().map(KyudichiSpellPickScreen::cell).toList();
        int rows = (catalog.size() + SpellPickView.COLS - 1) / SpellPickView.COLS;
        scroll = Math.clamp(scroll, 0, Math.max(0, rows - SpellPickView.ROWS));
        String n = net.minecraft.util.Util.getMeasuringTimeMs() - noticeAt < 3000 ? notice : "";
        if (n.isEmpty() && catalog.isEmpty()) n = PermissionClient.catalogConfirmed() ? "배운 마법이 없어요." : "배운 마법 목록을 불러오는 중… (" + CodexCatalog.status() + ")";
        SpellPickView.paint(p, new SpellPickView.Data(equipped, keys, l.flightSlot(), catalog, selected, scroll, dragging ? dragFrom : -1, n, listening));
    }

    private void say(String s) { notice = s; noticeAt = net.minecraft.util.Util.getMeasuringTimeMs(); }

    @Override void pressed(String id, float x, float y) {
        if (id.startsWith("slot:")) { dragFrom = Integer.parseInt(id.substring(5)); dragging = false; }
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragFrom >= 0) dragging = true;
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override void released(float x, float y) {
        if (dragging && dragFrom >= 0) {
            String id = hitAt(x, y);
            if (id != null && id.startsWith("slot:")) {
                int to = Integer.parseInt(id.substring(5));
                if (to != dragFrom) { loadout().swap(dragFrom, to); selected = to; changed(); sound(1.3f, .2f); }
            }
        }
        dragging = false; dragFrom = -1;
    }

    @Override void clicked(String id, int button) {
        var l = loadout();
        if (id.equals(SpellPickView.CLOSE) || id.equals(SpellPickView.CONFIRM)) { close(); return; }
        if (id.startsWith("key:")) {
            int i = Integer.parseInt(id.substring(4));
            if (button == 1) { listening = -1; if (l.customKeys()) { l.resetKeys(); changed(); say("경기 키를 원래 키로 되돌렸어요."); } return; }
            listening = listening == i ? -1 : i; selected = i;
            return;
        }
        listening = -1;
        if (id.startsWith("slot:")) {
            int i = Integer.parseInt(id.substring(5));
            if (button == 1) { String e = l.set(i, ""); if (e != null) say(e); else changed(); }
            else selected = i;
            return;
        }
        if (id.startsWith("spell:")) {
            var list = learned();
            int idx = Integer.parseInt(id.substring(6));
            if (idx >= list.size()) return;
            int target = selected >= 0 ? selected : l.spells().indexOf("");
            if (target < 0) { say("넣을 칸을 먼저 고르세요."); return; }
            String e = l.set(target, list.get(idx).id());
            if (e != null) say(e); else changed();
        }
    }

    /** Choosing a match key: the next key (or mouse side button) goes to the slot. */
    private boolean assignKey(int code) {
        if (listening < 0) return false;
        int slot = listening; listening = -1;
        String e = loadout().setKey(slot, code, CastingClient.base().orderedSlots());
        if (e != null) say(e); else { changed(); sound(1.5f, .25f); }
        return true;
    }
    @Override public boolean keyPressed(int code, int scan, int modifiers) {
        if (listening >= 0) {
            if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { listening = -1; return true; }
            return assignKey(code);
        }
        return super.keyPressed(code, scan, modifiers);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (listening >= 0 && KeySettingsLayout.mouseCode(button) >= 0) return assignKey(KeySettingsLayout.mouseCode(button));
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double h, double v) { scroll -= (int) Math.signum(v); return true; }

    private void changed() { loadout().save(); CastingClient.refreshBindings(); }

    @Override public void close() {
        changed();
        boolean back = parent instanceof KyudichiPrepareScreen && KyudichiClient.playing() && KyudichiClient.match.phase() == 0 || parent instanceof KyudichiLobbyScreen;
        client.setScreen(back ? parent : null);
    }
}
