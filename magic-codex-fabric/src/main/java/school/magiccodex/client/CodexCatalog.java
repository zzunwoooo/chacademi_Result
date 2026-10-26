package school.magiccodex.client;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local YAML metadata, combined with permission results from the current server. */
public final class CodexCatalog {
    private CodexCatalog() {}
    private static final Logger LOG = LoggerFactory.getLogger("magiccodex");
    private static final SpellYamlLoader LOADER = new SpellYamlLoader();
    public static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("magiccodex/spells");
    private static final PermissionView PERMISSIONS = new PermissionView();
    private static String loadError = "";
    public static PermissionView permissions() { return PERMISSIONS; }
    public static List<CodexData.Spell> spells() { return PERMISSIONS.spells(); }
    public static String status() { return loadError.isEmpty() ? PermissionClient.status() : loadError; }
    public static void refreshScreen() {
        if (MinecraftClient.getInstance().currentScreen instanceof CodexScreen screen) screen.refreshCatalog();
    }
    public static void initialize() {
        try { LOADER.installDefaults(DIRECTORY); }
        catch (Exception error) { LOG.error("기본 마법 파일 생성 실패", error); }
        reload(false);
    }
    public static int reload(boolean notify) {
        var result = LOADER.load(DIRECTORY);
        if (!result.success()) {
            loadError = "YAML 오류 · /화면 새로고침으로 확인";
            result.errors().forEach(LOG::error);
            if (notify) {
                message("YAML 오류 " + result.errors().size() + "개: 기존 마법 목록을 유지합니다.");
                result.errors().stream().limit(3).forEach(error -> message(error.replace('\n', ' ')));
                message("자세한 내용: logs/latest.log");
            }
            return 0;
        }
        if (notify) PermissionClient.close();
        PERMISSIONS.replace(result.spells());
        loadError = "";
        refreshScreen();
        LOG.info("Loaded {} local spells from {}", spells().size(), DIRECTORY);
        if (notify) message("마법 " + spells().size() + "개를 다시 읽었습니다.");
        return 1;
    }
    private static void message(String value) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("[마법 도감] " + value), false);
    }
}
