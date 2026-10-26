package school.magiccodex.paper;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.protocol.ManaProtocol;
import static org.junit.jupiter.api.Assertions.*;

class ManaTest {
    @TempDir Path temp;
    @Test void regenerationClampsAndZeroRegenerationStaysStill(){
        var a=new ManaAccount(96,100,5);a.regenerate();assertEquals(100,a.current);
        a.baseRegen=0;a.current=40;a.regenerate();assertEquals(40,a.current);
        a.baseMaximum=0;a.normalize();assertEquals(0,a.current);a.regenerate();assertEquals(0,a.current);
    }
    @Test void equipmentModifiersReplaceRatherThanStackAndClampAfterRemoval(){
        var a=new ManaAccount(100,100,5);a.modifier("wand:equipped",100,2);a.modifier("wand:equipped",100,2);
        assertEquals(200,a.snapshot().maximum());assertEquals(7,a.snapshot().regeneration());
        a.add(100);a.removeModifier("wand:equipped");assertEquals(100,a.current);assertEquals(5,a.snapshot().regeneration());
    }
    @Test void invalidNumbersCannotCreateManaOrPoisonSnapshots(){
        var a=new ManaAccount(50,100,5);
        for(double v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,1_000_001})assertThrows(IllegalArgumentException.class,()->a.consume(v));
        assertEquals(50,a.current);assertFalse(a.consume(51));assertEquals(50,a.current);assertTrue(a.consume(50));assertEquals(0,a.current);
    }
    @Test void authoritativeCostPermissionAndCooldownAreEnforced(){
        var a=new ManaAccount(100,100,5);var spell=new ManaSpells.Spell("test","cast test","magic.test",60,10000);var calls=new AtomicInteger();
        assertEquals(ManaProtocol.LOCKED,ManaCasting.attempt(a,spell,false,0,()->{calls.incrementAndGet();return true;}).status());
        assertEquals(100,a.current);assertEquals(0,calls.get());
        assertEquals(ManaProtocol.OK,ManaCasting.attempt(a,spell,true,100,()->{calls.incrementAndGet();return true;}).status());
        assertEquals(40,a.current);assertEquals(1,calls.get());
        assertEquals(ManaProtocol.COOLDOWN,ManaCasting.attempt(a,spell,true,101,()->true).status());
        assertEquals(ManaProtocol.EMPTY,ManaCasting.attempt(a,spell,true,10100,()->true).status());
        assertEquals(40,a.current);
    }
    @Test void failedOrThrowingCommandsRefundWithoutStartingCooldown(){
        var a=new ManaAccount(100,100,5);var spell=new ManaSpells.Spell("test","cast test","magic.test",60,10000);
        assertEquals(ManaProtocol.FAILED,ManaCasting.attempt(a,spell,true,0,()->false).status());assertEquals(100,a.current);assertEquals(0,a.remaining("test",0));
        assertThrows(IllegalStateException.class,()->ManaCasting.attempt(a,spell,true,0,()->{throw new IllegalStateException();}));assertEquals(100,a.current);
        assertEquals(ManaProtocol.UNKNOWN,ManaCasting.attempt(a,null,true,0,()->true).status());
    }
    @Test void protocolRoundTripsFractionalStatsAndStrictlyBoundsPackets(){
        var r=new ManaProtocol.Response(3,ManaProtocol.OK,1000,new ManaProtocol.Snapshot(12.5,120.5,2.75));
        assertEquals(r,ManaProtocol.decode(ManaProtocol.encode(r)));
        assertEquals(new ManaProtocol.Request(7,"wind_basket"),ManaProtocol.decodeRequest(ManaProtocol.cast(7,"wind_basket")));
        assertEquals(0,ManaProtocol.decodeRequest(ManaProtocol.subscribe()).sequence());
        for(int length:new int[]{0,3,5,12,77,1000})assertThrows(IllegalArgumentException.class,()->ManaProtocol.decodeRequest(new byte[length]));
        var corrupt=ManaProtocol.encode(r);ByteBuffer.wrap(corrupt).putDouble(17,Double.NaN);assertThrows(IllegalArgumentException.class,()->ManaProtocol.decode(corrupt));
        assertThrows(IllegalArgumentException.class,()->ManaProtocol.cast(1,"../../op player"));
    }
    @Test void catalogRejectsInvalidCostsAndDuplicateCommandsAtomically()throws Exception{
        var file=temp.resolve("spells.yml");
        String source="spells:\n  test:\n    command: cast test\n    permission: magic.test\n    mana-cost: 10\n    cooldown-seconds: 5\n";
        Files.writeString(file,source);assertEquals(10,ManaSpells.load(file.toFile()).byId.get("test").cost());
        Files.writeString(file,source.replace("mana-cost: 10","mana-cost: .NaN"));assertThrows(Exception.class,()->ManaSpells.load(file.toFile()));
        Files.writeString(file,source+source.substring("spells:\n".length()).replace("  test:","  second:"));assertThrows(Exception.class,()->ManaSpells.load(file.toFile()));
    }
    @Test void catalogContainsReviewedCommandsFromTheIntegrated329SpellPack()throws Exception{
        var spells=ManaSpells.load(Path.of("src/main/resources/mana-spells.yml").toFile());
        assertEquals(300,spells.byId.size());assertEquals(300,spells.discoveryPermissions.size()); // 2026-10-25: +63 new +12 life, 175 drowsiness on; 2026-10-26: 꺼진 12개 기획에서 삭제
        assertEquals(0,spells.byId.get("spatial_leap").cost());
        assertEquals(0,spells.byId.get("spatial_leap").cooldown());
        assertEquals(spells.byId.size(),spells.byCommand.size());
        assertEquals(800,spells.byId.get("solar_judgment").cost());
        assertEquals(300_000,spells.byId.get("solar_judgment").cooldown());
        assertFalse(spells.byId.containsKey("automatic_supply"));
        assertNotNull(spells.byId.get("rainbow_collar"));
        assertFalse(spells.byCommand.containsKey("패시브"));assertFalse(spells.byCommand.containsKey(""));
    }
    @Test void acceptsThreeHundredSpellEntriesAndRejectsAnOversizedCatalog()throws Exception{
        var file=temp.resolve("catalog300.yml");var source=new StringBuilder("spells:\n");
        for(int i=0;i<300;i++)source.append("  spell_").append(i).append(":\n    command: cast spell_").append(i).append("\n    permission: magic.learned.spell_").append(i).append("\n    mana-cost: 10\n    cooldown-seconds: 5\n");
        Files.writeString(file,source);assertEquals(300,ManaSpells.load(file.toFile()).byId.size());
        for(int i=300;i<=school.magiccodex.protocol.PermissionProtocol.MAX_PERMISSIONS;i++)source.append("  spell_").append(i).append(":\n    enabled: false\n");
        Files.writeString(file,source);assertThrows(Exception.class,()->ManaSpells.load(file.toFile()));
    }
    @Test void supportsApprovedFractionalCooldownAndUnambiguousNames()throws Exception{
        var file=temp.resolve("fractional.yml");
        Files.writeString(file,"spells:\n  gale_dash:\n    name: 바람 걸음\n    command: 마법 gale_dash\n    permission: magic.learned.gale_dash\n    mana-cost: 10\n    cooldown-seconds: 1.5\n");
        var catalog=ManaSpells.load(file.toFile());
        assertEquals(1500,catalog.byId.get("gale_dash").cooldown());
        assertEquals("gale_dash",catalog.resolve("바람 걸음"));
        assertEquals("gale_dash",catalog.resolve("GALE_DASH"));
    }
}
