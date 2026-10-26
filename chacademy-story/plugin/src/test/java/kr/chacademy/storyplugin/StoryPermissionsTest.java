package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class StoryPermissionsTest {
    /** 관리 명령은 MagicCodexBridge 허브(OP 검사)로만 등록한다: 자체 Bukkit 명령·권한 노드가 없어야 하고 허브가 먼저 켜져야 한다. */
    @Test void commandsLiveOnlyInTheHub(){
        var in=getClass().getResourceAsStream("/plugin.yml");assertNotNull(in);
        var yaml=YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));
        assertNull(yaml.getConfigurationSection("commands"));
        assertNull(yaml.getConfigurationSection("permissions"));
        assertTrue(yaml.getStringList("softdepend").contains("MagicCodexBridge"));
    }
}
