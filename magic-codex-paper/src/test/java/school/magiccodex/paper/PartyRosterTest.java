package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyRosterTest {
    /** chacademi-party-velocity PartyRosterTest 의 같은 상수 (프록시 인코더와 바이트 형식 고정). */
    static final String GOLDEN="010200000000000000010000000000000002010000000000000003000000000000000400000000000000050000000000000006";

    @Test void decodesTheProxyFormat() throws Exception {
        var party=PartyRoster.decode(HexFormat.of().parseHex(GOLDEN));
        assertEquals(new UUID(1,2),party.id());
        assertEquals(new UUID(5,6),party.leader());
        assertEquals(List.of(new UUID(3,4),new UUID(5,6)),party.members());
        assertNull(PartyRoster.decode(new byte[]{1,0}),"no party");
    }
    @Test void rejectsBrokenFrames(){
        byte[] good=HexFormat.of().parseHex(GOLDEN);
        assertThrows(Exception.class,()->PartyRoster.decode(new byte[]{2,0}),"version");
        assertThrows(Exception.class,()->PartyRoster.decode(Arrays.copyOf(good,good.length-1)),"short");
        assertThrows(Exception.class,()->PartyRoster.decode(Arrays.copyOf(good,good.length+1)),"trailing");
        byte[] badLeader=good.clone();badLeader[18]=2;
        assertThrows(Exception.class,()->PartyRoster.decode(badLeader),"leader index");
    }
}
