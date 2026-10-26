package school.magiccodex.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 큐디치 경기용 마법 6칸 (오너 결정):
 *  - 경기 중에만 우측 아래 6칸을 이 세팅으로 덮어쓴다. 키는 원래 칸의 키 그대로 (칸 순서대로).
 *    오너 2026-10-20: 마법 세팅 창에서 칸마다 키도 따로 정할 수 있다 ({@link #setKey}). 정한 키가 있으면 그 키가 우선.
 *  - 다음 경기에도 유지 (config/magiccodex/kyudichi-loadout.yml).
 *  - 한 칸은 반드시 비행({@value #FLIGHT}). 자리는 자유, 빼거나 다른 마법으로 바꿀 수는 없다.
 * 결투장 (2026-10-18): 모드별로 따로 저장. 1:1 대전({@code kyudichi-duel-loadout.yml})은 비행 금지라 비행 칸이 없다.
 * Pure state + file; {@link CastingClient} decides when it is applied.
 */
public final class KyudichiLoadout {
    public static final String FLIGHT = "flight";
    public static final int SIZE = SpellKeySettings.LIMIT;
    public static final int KYUDICHI = 0, DUEL = 1;
    private static Path path(int mode){return FabricLoader.getInstance().getConfigDir().resolve(mode==DUEL?"magiccodex/kyudichi-duel-loadout.yml":"magiccodex/kyudichi-loadout.yml");}
    private final List<String> spells = new ArrayList<>(Collections.nCopies(SIZE, ""));
    /** 칸별 경기 키 (-1 = 원래 칸의 키). */
    private final int[] keys = filled();
    private static int[] filled() { int[] k = new int[SIZE]; java.util.Arrays.fill(k, -1); return k; }
    private final int mode;

    private static final KyudichiLoadout[] current = new KyudichiLoadout[2];

    KyudichiLoadout() { this(KYUDICHI); }
    KyudichiLoadout(int mode) { this.mode = mode; }

    /** The loadout of the match being played (1:1 when the match says so), else 큐디치. */
    public static KyudichiLoadout get() { return forMode(KyudichiClient.duel() ? DUEL : KYUDICHI); }

    /** Loaded once per mode; the first time it starts from the normal six (in HUD order), with flight added for 큐디치. */
    public static KyudichiLoadout forMode(int mode) {
        int m = mode == DUEL ? DUEL : KYUDICHI;
        if (current[m] == null) {
            var l = new KyudichiLoadout(m);
            try { if (!l.load(path(m))) l.seed(CastingClient.legacyCombatSeed().orderedSlots().stream().map(SpellKeySettings.Binding::spellId).toList()); }
            catch (IOException | RuntimeException e) { l.seed(CastingClient.legacyCombatSeed().orderedSlots().stream().map(SpellKeySettings.Binding::spellId).toList()); }
            current[m] = l;
        }
        return current[m];
    }
    public int mode() { return mode; }
    public boolean duel() { return mode == DUEL; }

    public List<String> spells() { return List.copyOf(spells); }
    public String get(int slot) { return spells.get(slot); }
    public int flightSlot() { return spells.indexOf(FLIGHT); }

    /** Fills from a list (extra / duplicate ids dropped) and makes sure flight is in. */
    void seed(List<String> from) {
        Collections.fill(spells, "");
        int i = 0;
        for (String id : from) { if (i >= SIZE) break; if (id == null || id.isEmpty() || spells.contains(id) || duel() && id.equals(FLIGHT)) { i++; continue; } spells.set(i++, id); }
        ensureFlight();
    }
    void ensureFlight() {
        if (duel() || spells.contains(FLIGHT)) return;
        int empty = spells.indexOf("");
        spells.set(empty >= 0 ? empty : SIZE - 1, FLIGHT);
    }

    /**
     * Puts a spell in a slot. Flight can only move: choosing flight for another slot swaps it there,
     * and the flight slot itself cannot take another spell. A spell already elsewhere moves (swap).
     * @return error message or null
     */
    public String set(int slot, String id) {
        if (id == null) id = "";
        if (!id.isEmpty() && !id.matches("[a-z0-9_-]{1,64}")) return "올바르지 않은 마법이에요.";
        if (duel() && id.equals(FLIGHT)) return "1:1 대전에서는 비행을 쓸 수 없어요.";
        String old = spells.get(slot);
        if (old.equals(FLIGHT) && !id.equals(FLIGHT)) return "비행 칸은 비울 수 없어요. 자리를 바꾸려면 다른 칸으로 끌어 놓으세요.";
        int other = id.isEmpty() ? -1 : spells.indexOf(id);
        if (other >= 0 && other != slot) spells.set(other, old);
        spells.set(slot, id);
        return null;
    }

    /** Drag: the spells swap, the keys stay with the positions. */
    public void swap(int a, int b) { if (a != b) Collections.swap(spells, a, b); }

    /** True when this mode uses keys chosen in the spell-pick window. */
    public boolean customKeys() { for (int k : keys) if (k >= 0) return true; return false; }

    /**
     * Sets the match key of a slot. The first change copies the six keys in use now, so the others stay as they were;
     * a key already on another slot moves there (swap).
     * @return error message or null
     */
    public String setKey(int slot, int code, List<SpellKeySettings.Binding> normal) {
        if (!KeySettingsLayout.allowed(code)) return KeySettingsLayout.reservedShortcut(code) ? KeySettingsLayout.RESERVED_MESSAGE : "이 키는 지정할 수 없어요.";
        if (UiKeys.isUiKey(code)) return "UI 단축키로 쓰는 키예요. 다른 키를 고르세요.";
        if (code == CastingClient.toggleCode()) return "마법 모드 켜기/끄기 키예요. 다른 키를 고르세요.";
        if (!customKeys()) { var now = bindings(normal); for (int i = 0; i < SIZE; i++) keys[i] = now.get(i).keyCode(); }
        for (int i = 0; i < SIZE; i++) if (i != slot && keys[i] == code) keys[i] = keys[slot];
        keys[slot] = code;
        return null;
    }

    /** One-time migration of legacy inherited keys. Existing explicit duel/Quidditch overrides win. */
    boolean freezeNormalKeys(List<SpellKeySettings.Binding> normal){return freezeNormalKeys(normal,UiKeys::isUiKey);}
    boolean freezeNormalKeys(List<SpellKeySettings.Binding> normal,java.util.function.IntPredicate reserved){if(customKeys())return false;var inherited=bindings(normal,reserved);for(int i=0;i<SIZE;i++)keys[i]=inherited.get(i).keyCode();return true;}
    /** Back to the legacy first-preset keys, captured independently on refresh. */
    public void resetKeys() { java.util.Arrays.fill(keys, -1); }

    /** Match bindings: slot i = this spell with the key of the normal slot i (HUD order). */
    List<SpellKeySettings.Binding> bindings(List<SpellKeySettings.Binding> normal) {return bindings(normal,UiKeys::isUiKey);}
    List<SpellKeySettings.Binding> bindings(List<SpellKeySettings.Binding> normal,java.util.function.IntPredicate reserved) {
        var out = new ArrayList<SpellKeySettings.Binding>(SIZE);
        var used = new java.util.HashSet<Integer>();
        boolean custom = customKeys();
        if (custom) { for (int k : keys) if (k >= 0) used.add(k); }
        else for (var b : normal) if (!b.empty() && KeySettingsLayout.allowed(b.keyCode())) used.add(b.keyCode());
        var spare = new ArrayList<Integer>();
        for (int code : SPARE_KEYS) if (KeySettingsLayout.allowed(code) && !used.contains(code) && !reserved.test(code)) spare.add(code);
        for (int i = 0; i < SIZE; i++) {
            String id = spells.get(i);
            int key = custom ? (KeySettingsLayout.allowed(keys[i]) && !reserved.test(keys[i]) ? keys[i] : -1)
                    : i < normal.size() && KeySettingsLayout.allowed(normal.get(i).keyCode()) ? normal.get(i).keyCode() : -1;
            if (id.isEmpty()) { out.add(new SpellKeySettings.Binding("", key)); continue; }
            // A normal slot without a key (fewer than six spells registered) borrows a free key so every match spell can be cast.
            if (key < 0 && !spare.isEmpty()) key = spare.remove(0);
            out.add(new SpellKeySettings.Binding(id, key));
        }
        return List.copyOf(out);
    }
    /** Q E R T G V B — tried in this order for slots whose normal key is missing (C is 앉기/빗자루 하강 since 2026-10-17). */
    static final int[] SPARE_KEYS = {81, 69, 82, 84, 71, 86, 66};

    boolean load(Path path) throws IOException {
        if (!Files.exists(path)) return false;
        if (Files.size(path) > 4096) throw new IOException("too large");
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        try (var r = Files.newBufferedReader(path)) {
            Object root = new Yaml(new SafeConstructor(options)).load(r);
            if (!(root instanceof Map<?, ?> map) || !Integer.valueOf(1).equals(map.get("version")) || !(map.get("spells") instanceof List<?> list) || list.size() != SIZE)
                throw new IOException("format");
            var ids = new ArrayList<String>();
            for (Object o : list) ids.add(o instanceof String s && s.matches("[a-z0-9_-]{0,64}") ? s : "");
            seed(ids);
            resetKeys();
            if (map.get("keys") instanceof List<?> kl && kl.size() == SIZE) {
                for (int i = 0; i < SIZE; i++) keys[i] = kl.get(i) instanceof Integer n && KeySettingsLayout.allowed(n) ? n : -1;
            }
            return true;
        }
    }

    public void save() {
        var root = new LinkedHashMap<String, Object>(); root.put("version", 1); root.put("spells", List.copyOf(spells));
        if (customKeys()) { var kl = new ArrayList<Integer>(); for (int k : keys) kl.add(k); root.put("keys", kl); }
        var options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Path target = path(mode).toAbsolutePath(); Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "kyudichi-loadout-", ".tmp");
            try { Files.writeString(temp, new Yaml(options).dump(root)); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { org.slf4j.LoggerFactory.getLogger("magiccodex").warn("큐디치 마법 세팅 저장 실패", e); }
    }
}
