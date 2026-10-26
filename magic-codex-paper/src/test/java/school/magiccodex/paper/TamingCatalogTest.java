package school.magiccodex.paper;

import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.protocol.ManaProtocol;
import static org.junit.jupiter.api.Assertions.*;

/** Pure catalog/transaction regressions: no running Bukkit server or capture mocks required. */
class TamingCatalogTest {
    @TempDir Path temp;
    private static ManaSpells catalog()throws Exception{
        return ManaSpells.load(Path.of("src/main/resources/mana-spells.yml").toFile());
    }
    private static YamlConfiguration yaml(Path path)throws Exception{
        var yaml=new YamlConfiguration();yaml.load(path.toFile());return yaml;
    }
    @Test void catalog149AndAllCompatibilityAliasesResolveOnePaidSpell()throws Exception{
        var catalog=catalog();var spell=catalog.forCast(TamingSpell.ID);
        assertNotNull(spell);assertEquals("rainbow_collar",spell.id());
        assertEquals("교화",spell.command());assertEquals(TamingSpell.PERMISSION,spell.permission());
        assertEquals(150,spell.cost());assertEquals(60000,spell.cooldown());
        assertSame(spell,catalog.byCommand.get(ManaSpells.normalize("/교화")));
        assertSame(spell,catalog.byCommand.get(ManaSpells.normalize("/MagicCodexBridge:교화")));
        assertSame(spell,catalog.forCast("taming"));
        for(String alias:Set.of("rainbow_collar","taming","교화","무지개 목줄"))
            assertEquals(TamingSpell.ID,catalog.resolve(alias),alias);
        assertFalse(catalog.byId.containsKey("taming"));
        assertEquals(300,catalog.registeredCatalog.size()); // 2026-10-25: +63 new +12 life spells, 2026-10-26: 꺼진 12개 삭제
        assertEquals("교화",catalog.registeredCatalog.get(TamingSpell.ID).get("name"));
    }
    @Test void aliasesCannotChargeTwiceOrAcquireIndependentCooldowns()throws Exception{
        var catalog=catalog();var account=new ManaAccount(1000,1000,0);var dispatches=new AtomicInteger();
        var first=ManaCasting.attempt(account,catalog.forCast("rainbow_collar"),true,1000,()->{dispatches.incrementAndGet();return true;});
        assertEquals(ManaProtocol.OK,first.status());assertEquals(60000,first.cooldownMillis());
        for(String id:new String[]{"taming","rainbow_collar"})
            assertEquals(ManaProtocol.COOLDOWN,ManaCasting.attempt(account,catalog.forCast(id),true,1001,()->{dispatches.incrementAndGet();return true;}).status());
        assertEquals(1,dispatches.get());assertEquals(850,account.current);
        assertEquals(Set.of(TamingSpell.ID),account.cooldowns.keySet());
    }
    @Test void rejectedStartRefundsCanonicalCostAndAllowsRetryViaOtherAlias()throws Exception{
        var catalog=catalog();var account=new ManaAccount(150,150,0);
        assertEquals(ManaProtocol.FAILED,ManaCasting.attempt(account,catalog.forCast("taming"),true,1000,()->false).status());
        assertEquals(150,account.current);assertTrue(account.cooldowns.isEmpty());
        assertEquals(ManaProtocol.OK,ManaCasting.attempt(account,catalog.forCast("rainbow_collar"),true,1001,()->true).status());
        assertEquals(0,account.current);
    }
    @Test void publicFeaturePermissionDoesNotAutoTeachCatalog149()throws Exception{
        var spell=catalog().forCast(TamingSpell.ID);
        assertFalse(TamingSpell.permitted(spell,Set.of("magiccodex.taming")::contains));
        assertFalse(TamingSpell.permitted(spell,Set.of(TamingSpell.PERMISSION)::contains));
        assertTrue(TamingSpell.permitted(spell,Set.of("magiccodex.taming",TamingSpell.PERMISSION)::contains));
        assertFalse(TamingSpell.permitted(null,p->true));
        assertFalse(TamingSpell.permitted(new ManaSpells.Spell("taming","교화","magiccodex.taming",15,8000),p->true));
    }
    @Test void disabledCanonicalEntryCannotBeBypassedThroughLegacyId()throws Exception{
        var path=temp.resolve("disabled.yml");
        Files.writeString(path,"spells:\n  rainbow_collar:\n    name: 교화\n    enabled: false\n    command: 교화\n    permission: magic.learned.rainbow_collar\n    mana-cost: 150\n    cooldown-seconds: 60\n");
        var catalog=ManaSpells.load(path.toFile());
        assertNull(catalog.forCast("rainbow_collar"));assertNull(catalog.forCast("taming"));
        assertNull(catalog.resolve("taming"));assertNull(catalog.resolve("교화"));
        assertFalse(TamingSpell.permitted(catalog.forCast("taming"),p->true));
    }
    @Test void legacyEntryCannotCreateASecondCatalogSpell()throws Exception{
        String source=Files.readString(Path.of("src/main/resources/mana-spells.yml"));
        var path=temp.resolve("duplicate.yml");
        Files.writeString(path,source+"\n  taming:\n    name: 교화\n    enabled: true\n    command: legacy_taming\n    permission: magiccodex.taming\n    mana-cost: 15\n    cooldown-seconds: 8\n");
        assertThrows(IllegalArgumentException.class,()->ManaSpells.load(path.toFile()));
    }
    @Test void clientAndServerMetadataKeep149WithinThe323Catalog()throws Exception{
        var defaults=Path.of("../magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells");
        var index=Files.readAllLines(defaults.resolve("index.txt")).stream().filter(s->!s.isBlank()).toList();
        assertEquals(300,index.size());assertEquals(1,index.stream().filter("233_rainbow_collar"::equals).count()); // 2026-10-25 renumber
        assertFalse(index.contains("taming"));
        var client=yaml(defaults.resolve("233_rainbow_collar.yml"));
        assertEquals(TamingSpell.ID,client.getString("id"));assertEquals("교화",client.getString("name"));
        assertEquals(233,client.getInt("order"));assertEquals(TamingSpell.PERMISSION,client.getString("permission"));
        assertEquals("교화",client.getString("cast.command"));assertEquals(150,client.getInt("cast.mana-cost"));assertEquals(60,client.getInt("cast.cooldown-seconds"));
        assertEquals("몬스터 교화 실습 수행하기",client.getString("discovery.description"));
        assertEquals("빛의 고리가 닫히자, 낯선 숨결이 내 이름에 답했다. 처음 만난 짐승의 눈동자에서 오래된 약속의 흔적이 잠깐 빛났다.",client.getString("research"));
        var encounter=yaml(Path.of("src/main/resources/taming.yml"));
        assertEquals(12,encounter.getInt("range"));assertEquals(4000,encounter.getInt("cast-milliseconds"));
        assertFalse(encounter.contains("mana-cost"));assertFalse(encounter.contains("cooldown-seconds"));
        var plugin=yaml(Path.of("src/main/resources/plugin.yml"));
        assertFalse(plugin.contains("commands.교화"),"교화 is now owned by the Brigadier hub");
        var bridge=Files.readString(Path.of("src/main/java/school/magiccodex/paper/TamingBridge.java"));
        assertTrue(bridge.contains("TamingSpell.permitted(spell,p::hasPermission)"),"the shared casting transaction must retain learned permission enforcement");
    }
}
