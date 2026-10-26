package kr.chacademy.npc.config;

import kr.chacademy.npc.core.CharacterSheet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** #58 gift-likes.yml: 공통 파일이 캐릭터 파일 gift_likes 를 대신하고, 기본 파일은 85명 × 10개. */
class GiftLikesFileTest {
    @Test
    void commonFileReplacesCharacterLikes() throws Exception {
        var y = new YamlConfiguration();
        y.loadFromString("id: ella\nname: 엘라\ngift_likes:\n  loved: [lily_of_the_valley]\n  liked: [book]\n  disliked: [rotten_flesh]\n");
        var map = new LinkedHashMap<String, CharacterSheet>();
        map.put("ella", CharacterRepository.read(y, "ella.yml"));
        File f = Files.createTempFile("gift-likes", ".yml").toFile();
        Files.writeString(f.toPath(), "npcs:\n  ella: [ITEM:gift_moonwell_water]\n  nobody: [item:gift_x]\n");
        assertEquals(1, CharacterRepository.applyGiftFile(map, f, Logger.getLogger("t")));
        var likes = map.get("ella").giftLikes();
        assertEquals(List.of("item:gift_moonwell_water"), likes.get("liked"));
        assertEquals(List.of(), likes.get("loved"));
        assertEquals(List.of("rotten_flesh"), likes.get("disliked"));
        assertEquals(0, CharacterRepository.applyGiftFile(map, new File(f.getParentFile(), "missing-gift-likes.yml"), Logger.getLogger("t")));
    }

    @Test
    void shippedFileHas85NpcsWithTenEach() throws Exception {
        try (var in = getClass().getResourceAsStream("/gift-likes.yml")) {
            var y = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
            var npcs = y.getConfigurationSection("npcs");
            assertEquals(85, npcs.getKeys(false).size());
            var all = new HashSet<String>();
            for (String id : npcs.getKeys(false)) {
                var list = npcs.getStringList(id);
                assertEquals(10, list.size(), id);
                assertEquals(10, new HashSet<>(list).size(), id);
                all.addAll(list);
            }
            assertEquals(100, all.size());
        }
    }
}
