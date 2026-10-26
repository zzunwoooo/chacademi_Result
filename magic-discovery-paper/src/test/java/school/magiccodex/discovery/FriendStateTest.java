package school.magiccodex.discovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** 오너 결정 4 (2026-10-21): 수호자의 서약 "친구 20명" = 현재 등록된 서로 다른 친구 수 (state.friends, 누적 아님). */
class FriendStateTest {
    @Test void guardianOathUsesCurrentFriendCount() throws Exception {
        var d = new Definitions(Path.of("src/main/resources/discoveries.yml").toFile());
        var oath = d.spells.get("guardian_oath");
        assertEquals(Map.of("state.friends", 20d), oath.requirements());
        assertTrue(d.stateOnly.contains(oath));
        assertFalse(Definitions.meets(oath, Map.of("state.friends", 19d)));
        assertTrue(Definitions.meets(oath, Map.of("state.friends", 20d)));
        // unfriend → refriend republishes the same count: never above the real number of friends
        assertFalse(Definitions.meets(oath, Map.of("state.friends", 19d, "friend.made", 40d)));
    }

    @Test void legacyFriendMadeKeyReadsAsState(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("discoveries.yml");
        Files.writeString(f, """
                spells:
                  guardian_oath:
                    name: "수호자의 서약"
                    permission: "magic.learned.guardian_oath"
                    icon: "magiccodex:textures/spells/expansion_2/guardian_oath.png"
                    requirements:
                      "friend.made": 20
                    chance: 1.0
                    prerequisites: []
                """);
        var d = new Definitions(f.toFile());
        assertEquals(Map.of("state.friends", 20d), d.spells.get("guardian_oath").requirements());
        assertTrue(d.index.containsKey("state.friends"));
        assertFalse(d.index.containsKey("friend.made"));
    }
}
