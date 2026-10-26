package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;import java.util.Map;import org.junit.jupiter.api.Test;import org.yaml.snakeyaml.Yaml;
class BootstrapTest {
 /** 2026-10-20 명령어 정리: 상점·우편 명령은 Brigadier(/상점관리·/유저관리)로 옮겼다. 게임 기능 권한(보유·이용 판정)은 그대로. */
 @Test void gameplayPermissionsMatchUtf8Descriptor(){try(var input=getClass().getResourceAsStream("/plugin.yml")){assertNotNull(input);Map<?,?> root=new Yaml().load(input);Map<?,?> permissions=(Map<?,?>)root.get("permissions");assertEquals(true,((Map<?,?>)permissions.get("magiccodex.mailbox")).get("default"));
  // 유저의 NPC 클릭 상점 경로는 magiccodex.shop(기본 허용)으로 유지한다.
  assertEquals(true,((Map<?,?>)permissions.get("magiccodex.shop")).get("default"));}catch(Exception e){throw new AssertionError(e);}}
}
