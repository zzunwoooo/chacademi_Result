package school.magiccodex.paper;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class MagicHasteTest {
    @Test void diminishingCooldownNeverRoundsPositiveTimeToZero(){
        assertEquals(10000,MagicHaste.cooldown(10000,0));assertEquals(8000,MagicHaste.cooldown(10000,25));
        assertEquals(6667,MagicHaste.cooldown(10000,50));assertEquals(5000,MagicHaste.cooldown(10000,100));
        assertEquals(3334,MagicHaste.cooldown(10000,200));assertEquals(1,MagicHaste.cooldown(1,1000000));assertEquals(0,MagicHaste.cooldown(0,100));
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,1000001})assertThrows(IllegalArgumentException.class,()->MagicHaste.cooldown(1000,bad));
    }
    @Test void equipmentRecalculationReplacesBonusAndDoesNotChangeRunningCooldown(){
        var a=new ManaAccount(100,100,5);a.baseHaste=25;a.hasteModifier("magiccodex:equipment",75);a.hasteModifier("magiccodex:equipment",75);
        assertEquals(100,a.snapshot().haste());var spell=new ManaSpells.Spell("test","test","test",10,10000);
        var result=ManaCasting.attempt(a,spell,true,1000,()->true);assertEquals(5000,result.cooldownMillis());
        a.hasteModifier("magiccodex:equipment",0);assertEquals(25,a.snapshot().haste());
        assertEquals(ManaProtocol.COOLDOWN,ManaCasting.attempt(a,spell,true,2000,()->true).status());assertEquals(4000,a.remaining("test",2000));
        assertEquals(8000,ManaCasting.attempt(a,spell,true,6000,()->true).cooldownMillis());
    }
    @Test void rejectedCastDoesNotChargeOrStartReducedCooldown(){
        var a=new ManaAccount(100,100,5);a.baseHaste=100;
        var spell=new ManaSpells.Spell("test","test","test",10,10000);
        assertEquals(ManaProtocol.FAILED,ManaCasting.attempt(a,spell,true,1000,()->false).status());
        assertEquals(100,a.snapshot().current());assertEquals(0,a.remaining("test",1000));
    }
    @Test void networkIncludesHasteAndReadsLegacySnapshotAsZero(){
        var response=new ManaProtocol.Response(4,ManaProtocol.OK,5000,new ManaProtocol.Snapshot(90,100,5,100));
        assertEquals(response,ManaProtocol.decode(ManaProtocol.encode(response)));
        byte[] old=ByteBuffer.allocate(41).putInt(0x4D414E01).putLong(0).put((byte)0).putInt(0).putDouble(50).putDouble(100).putDouble(5).array();
        assertEquals(0,ManaProtocol.decode(old).mana().haste());
        assertThrows(IllegalArgumentException.class,()->ManaProtocol.decode(new byte[49]));
    }
}
