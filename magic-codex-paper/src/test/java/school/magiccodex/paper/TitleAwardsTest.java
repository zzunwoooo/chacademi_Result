package school.magiccodex.paper;

import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.database.DatabaseSettings;

class TitleAwardsTest {
    @TempDir Path dir;
    static final DatabaseSettings SQLITE=new DatabaseSettings(false,"","","");
    private static YamlConfiguration yaml(String text)throws Exception{var y=new YamlConfiguration();y.loadFromString(text);return y;}

    @Test void ledgerGrantsOncePerKeyAcrossServersAndAfterRevoke()throws Exception{
        var p=UUID.randomUUID();var path=dir.resolve("titles.db");var defaults=Set.<String>of();
        try(var school=new TitleStore(SQLITE,path);var wild=new TitleStore(SQLITE,path)){
            var first=school.award(p,Map.of("affinity.any.30","suffix_affinity_friend"),defaults);
            assertEquals(List.of("suffix_affinity_friend"),first.granted());
            assertTrue(wild.award(p,Map.of("affinity.any.30","suffix_affinity_friend"),defaults).granted().isEmpty(),"other server must not grant twice");
            school.revoke(p,"suffix_affinity_friend",defaults);
            assertTrue(school.award(p,Map.of("affinity.any.30","suffix_affinity_friend"),defaults).granted().isEmpty(),"revoked title is not re-granted by the same path");
            assertFalse(school.load(p,defaults).owned().contains("suffix_affinity_friend"));
            assertEquals(Set.of("preset.house"),wild.unmarked(p,List.of("preset.house")));
            wild.mark(p,"preset.house");assertTrue(school.unmarked(p,List.of("preset.house")).isEmpty());
        }
    }

    @Test void configMapsEventsToKeys()throws Exception{
        var awards=TitleAwards.parse(yaml("""
            discovery:
              feather_step: prefix_light
            affinity:
              first-any: {30: suffix_affinity_friend, 60: suffix_affinity_bestie, 90: suffix_affinity_lover}
              npc-threshold: 30
              npc: {ria: prefix_npc_ria}
            fishing:
              big: {min-cm: 100, title: suffix_big}
              off: {min-cm: 0, title: suffix_none}
            """),yaml("""
            players:
              Junwoo: {house: 루미나, titles: [suffix_student], spells: [feather_step]}
            """));
        assertEquals(Map.of("discovery.feather_step","prefix_light"),awards.discoveryKeys(List.of("feather_step","wind_basket")));
        assertEquals(Map.of("affinity.any.30","suffix_affinity_friend","affinity.any.60","suffix_affinity_bestie","affinity.npc.ria","prefix_npc_ria"),awards.affinityKeys(Map.of("ria",65,"ella",10)));
        assertTrue(awards.affinityKeys(Map.of("ria",29)).isEmpty());
        assertEquals(Map.of("fishing.big","suffix_big"),awards.fishKeys("carp",100));assertTrue(awards.fishKeys("carp",99.9).isEmpty());
        var preset=awards.preset(UUID.randomUUID(),"junwoo");assertNotNull(preset);assertEquals(1,preset.house());assertEquals(List.of("feather_step"),preset.spells());
        assertThrows(IllegalArgumentException.class,()->TitleAwards.parse(yaml(""),yaml("players:\n  x: {house: 없는곳}\n")));
    }

    @Test void npcPrefixTitlesAreAutomaticUnlessOverridden()throws Exception{
        var awards=TitleAwards.parse(yaml("""
            affinity:
              npc-threshold: 30
              npc-prefix: {format: '{npc}의', color: '#96E4EC'}
              npc: {mina: prefix_custom_mina}
            """),yaml(""));
        assertEquals(Map.of("affinity.npc.ria","prefix_npc_ria","affinity.npc.mina","prefix_custom_mina"),awards.affinityKeys(Map.of("ria",30,"mina",40,"ella",29)));
        assertEquals("리아의",awards.npcTitleName("리아"));
        assertNull(awards.npcTitle("리아"),"Korean NPC IDs cannot become title IDs");
        assertThrows(IllegalArgumentException.class,()->TitleAwards.parse(yaml("affinity:\n  npc-prefix: {format: '의'}\n"),yaml("")));
        assertNull(TitleAwards.parse(yaml("affinity:\n  npc-prefix: {format: ''}\n"),yaml("")).npcTitle("ria"));
    }

    @Test void bundledFilesParseAndPointAtRegisteredTitles()throws Exception{
        var titles=new YamlConfiguration();try(var in=getClass().getClassLoader().getResourceAsStream("titles.yml")){titles.loadFromString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var awards=new YamlConfiguration();try(var in=getClass().getClassLoader().getResourceAsStream("title-awards.yml")){awards.loadFromString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var presets=new YamlConfiguration();try(var in=getClass().getClassLoader().getResourceAsStream("player-presets.yml")){presets.loadFromString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var parsed=TitleAwards.parse(awards,presets);
        for(String id:parsed.referencedTitles())assertTrue(titles.isConfigurationSection("titles."+id),id);
        assertTrue(titles.getConfigurationSection("titles").getKeys(false).size()<=school.magiccodex.protocol.TitleProtocol.MAX_TITLES);
        assertEquals(225,parsed.discovery.size(),"owner plan: one title per spell (2026-10-26: 꺼진 12개 삭제)");
    }
}
