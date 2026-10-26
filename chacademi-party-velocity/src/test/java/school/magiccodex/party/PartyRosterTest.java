package school.magiccodex.party;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyRosterTest {
    /** magic-codex-paper PartyRosterTest 의 같은 상수와 맞아야 한다 (백엔드 디코더와 바이트 형식 고정). */
    static final String GOLDEN="010200000000000000010000000000000002010000000000000003000000000000000400000000000000050000000000000006";

    @Test void encodesTheSharedWireFormat(){
        var party=new PartyBook.Party(new UUID(1,2),new UUID(5,6),List.of(new UUID(3,4),new UUID(5,6)));
        assertEquals(GOLDEN,HexFormat.of().formatHex(PartyRoster.encode(party)));
        assertArrayEquals(PartyRoster.EMPTY,PartyRoster.encode(null));
    }
    @Test void sendsOnChangeAndHeartbeatOnly(){
        var roster=new PartyRoster();var id=UUID.randomUUID();
        byte[] a=PartyRoster.encode(new PartyBook.Party(new UUID(1,2),id,List.of(id)));
        assertNotNull(roster.due(id,a,0));
        assertNull(roster.due(id,a,1000),"unchanged");
        assertNotNull(roster.due(id,a,PartyRoster.HEARTBEAT),"heartbeat");
        assertNotNull(roster.due(id,PartyRoster.EMPTY,PartyRoster.HEARTBEAT+1),"left the party");
        assertNull(roster.due(id,PartyRoster.EMPTY,PartyRoster.HEARTBEAT*3),"empty is sent once");
        roster.resend(id,100_000);
        assertNull(roster.due(id,a,99_000),"held after a server switch");
        assertNotNull(roster.due(id,a,100_000));
    }
}
