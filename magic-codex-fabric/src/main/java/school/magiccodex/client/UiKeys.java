package school.magiccodex.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Properties;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * UI shortcut keys (owner 2026-10-17), edited in the 키 설정 screen next to the spell keys.
 * Defaults: P 파티창 · I 내 정보 · O 장비창 · K 마법도감 · L 친구창 · \ 키 설정 · 왼쪽 Ctrl 마우스 포인터 (HUD cursor) · Z 마법 모드 · X 아티팩트 · J 지도 · H 내 땅.
 * Stored in config/magiccodex/ui-keys.properties. A key is used by one thing only (one UI action or one spell).
 * 마법도감 · 내 정보 · 장비창 · 마법 모드 · 아티팩트 keep their vanilla KeyBinding (their handlers read it); this class sets
 * those bindings' keys. 파티창 · 친구창 · 키 설정 · 마우스 포인터 are handled here / in PlayerHudClient by key code.
 */
public final class UiKeys {
    private UiKeys() {}

    public enum Action {
        PARTY("파티창", "파티", GLFW.GLFW_KEY_P),
        STATS("내 정보", "내 정보", GLFW.GLFW_KEY_I),
        EQUIPMENT("장비창", "장비", GLFW.GLFW_KEY_O),
        CODEX("마법도감", "도감", GLFW.GLFW_KEY_K),
        FRIENDS("친구창", "친구", GLFW.GLFW_KEY_L),
        KEY_SETTINGS("키 설정", "키 설정", GLFW.GLFW_KEY_BACKSLASH),
        CURSOR("마우스 포인터", "포인터", GLFW.GLFW_KEY_LEFT_CONTROL),
        MAGIC_MODE("마법 모드", "마법 모드", GLFW.GLFW_KEY_Z),
        ARTIFACT("아티팩트 사용", "아티팩트", GLFW.GLFW_KEY_X),
        /** 지도 이동 창 (owner 2026-10-24: 기본 J). */
        MAP("지도", "지도", GLFW.GLFW_KEY_J),
        /** 내 땅 관리 창 (청크 점유 메모 #88, 2026-10-26: 기본 H — 바닐라·다른 UI 키와 안 겹침). 내 땅 안에서만 열린다. */
        LAND("내 땅 관리", "내 땅", GLFW.GLFW_KEY_H);
        public final String label, shortLabel; public final int defaultCode;
        Action(String label, String shortLabel, int defaultCode) { this.label = label; this.shortLabel = shortLabel; this.defaultCode = defaultCode; }
    }

    /** One editable set of UI keys (-1 = none). */
    public static final class Binds {
        private final EnumMap<Action, Integer> codes = new EnumMap<>(Action.class);
        public Binds() { for (Action a : Action.values()) codes.put(a, a.defaultCode); }
        public int get(Action a) { return codes.getOrDefault(a, -1); }
        public void set(Action a, int code) { codes.put(a, code); }
        public Action actionOf(int code) { if (code < 0) return null; for (Action a : Action.values()) if (get(a) == code) return a; return null; }
        public Binds copy() { var m = new Binds(); m.codes.putAll(codes); return m; }
        public boolean sameAs(Binds o) { return codes.equals(o.codes); }
        public void reset() { for (Action a : Action.values()) codes.put(a, a.defaultCode); }
    }

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("magiccodex/ui-keys.properties");
    private static Binds current = new Binds();
    private static final CodexShortcut SHORTCUT = new CodexShortcut();

    public static Binds current() { return current; }
    public static int code(Action a) { return current.get(a); }
    public static boolean isUiKey(int code) { return current.actionOf(code) != null; }
    /** Name of the HUD cursor key for hints ("Ctrl"), or "" when it is not set. */
    public static String cursorName() { int c = code(Action.CURSOR); return c < 0 ? "" : KeySettingsLayout.name(c); }
    /** "Ctrl 누른 채" style hint prefix, or the chat alternative when no cursor key is set. */
    public static String cursorHint() { String n = cursorName(); return n.isEmpty() ? "채팅창을 연 채" : n + " 키를 누른 채"; }

    static void initialize() {
        ClientLifecycleEvents.CLIENT_STARTED.register(c -> { load(); apply(c); });
    }

    /** First start (no file): the defaults, except a default key a saved spell already uses stays empty (the spell keeps it). */
    private static void load() {
        var m = new Binds();
        if (Files.exists(PATH)) {
            var p = new Properties();
            try (var r = Files.newBufferedReader(PATH)) {
                p.load(r);
                for (Action a : Action.values()) {
                    String v = p.getProperty(a.name());
                    if (v != null) m.set(a, Integer.parseInt(v.trim()));
                }
                // 나중에 생긴 단축키(예: 내 땅 H)의 기본 키를 이미 다른 UI 키나 저장된 마법이 쓰고 있으면 비워 둔다 (한 키 = 한 가지).
                SpellKeySettings spells = null;
                try { spells = SpellKeySettings.load(FabricLoader.getInstance().getConfigDir().resolve("magiccodex/key-settings.yml")); } catch (Exception ignored) { }
                for (Action a : Action.values()) {
                    if (p.getProperty(a.name()) != null) continue;
                    boolean taken = spells != null && spells.slotOfKey(a.defaultCode) >= 0;
                    for (Action o : Action.values()) if (o != a && m.get(o) == a.defaultCode) taken = true;
                    if (taken) m.set(a, -1);
                }
            } catch (IOException | RuntimeException bad) {
                org.slf4j.LoggerFactory.getLogger("magiccodex").warn("UI 키 설정 읽기 실패, 기본값 사용", bad);
                m = new Binds();
            }
        } else {
            try {
                var spells = SpellKeySettings.load(FabricLoader.getInstance().getConfigDir().resolve("magiccodex/key-settings.yml"));
                for (Action a : Action.values()) if (spells.slotOfKey(a.defaultCode) >= 0) m.set(a, -1);
            } catch (Exception ignored) { }
            try { save(m); } catch (IOException ignored) { }
        }
        current = m;
    }

    static void save(Binds m) throws IOException {
        var p = new Properties();
        p.setProperty("version", "1");
        for (Action a : Action.values()) p.setProperty(a.name(), String.valueOf(m.get(a)));
        Files.createDirectories(PATH.getParent());
        Path tmp = PATH.resolveSibling("ui-keys.properties.tmp");
        try (var w = Files.newBufferedWriter(tmp)) { p.store(w, "MagicCodex UI shortcut keys (GLFW key codes, -1 = none)"); }
        Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The key settings screen saved: keep, persist and push to the vanilla bindings. */
    static void commit(Binds m) throws IOException {
        save(m);
        current = m.copy();
        apply(MinecraftClient.getInstance());
    }

    private static void apply(MinecraftClient c) {
        bind(MagicCodexClient.binding(), code(Action.CODEX));
        bind(StatsClient.binding(), code(Action.STATS));
        bind(EquipmentClient.binding(), code(Action.EQUIPMENT));
        bind(CastingClient.binding(), code(Action.MAGIC_MODE));
        bind(ArtifactClient.binding(), code(Action.ARTIFACT));
        KeyBinding.updateKeysByCode();
        if (c != null && c.options != null) c.options.write();
    }
    private static void bind(KeyBinding b, int code) {
        if (b != null) b.setBoundKey(code < 0 ? InputUtil.UNKNOWN_KEY : InputUtil.Type.KEYSYM.createFromCode(code));
    }

    /** CodexKeyboardMixin: 파티창 · 친구창 · 키 설정 (the others have their own handlers). */
    public static boolean handleKey(MinecraftClient c, int key, int scan, int action) {
        Action a = current.actionOf(key);
        boolean mine = a == Action.PARTY || a == Action.FRIENDS || a == Action.KEY_SETTINGS || a == Action.MAP || a == Action.LAND && ChunkClaimClient.land.inside();
        boolean ready = c.world != null && c.player != null && c.currentScreen == null && c.getOverlay() == null && c.isWindowFocused();
        var result = SHORTCUT.handle(key, action, mine, ready);
        if (result == CodexShortcut.Result.OPEN) {
            MagicCodexClient.dismiss();
            switch (a) {
                case PARTY -> c.setScreen(new PartyScreen());
                case FRIENDS -> SocialClient.open();
                case KEY_SETTINGS -> c.setScreen(new KeySettingsScreen(null));
                case MAP -> TravelClient.open();
                case LAND -> ChunkClaimClient.open();
                default -> { }
            }
        }
        return result != CodexShortcut.Result.PASS;
    }
}
