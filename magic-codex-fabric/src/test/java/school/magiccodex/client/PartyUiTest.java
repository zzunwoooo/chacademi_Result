package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PartyUiProtocol;
import static org.junit.jupiter.api.Assertions.*;

class PartyUiTest {
    /** chacademi-party-velocity PartyUiTest 의 같은 상수 (프록시 인코더와 바이트 형식 고정). */
    static final String GOLDEN="0101010802000000000000000100000000000000020006ecb0a8ecb9b400002030313233343536373839616263646566303132333435363738396162636465660103000000000000000300000000000000040006eba3a8eb8298030000000001000000000000000500000000000000060006ed9598eba6b0ff00000000";

    @Test void decodesTheProxyState(){
        var m=PartyUiProtocol.decode(HexFormat.of().parseHex(GOLDEN));
        assertEquals(PartyUiProtocol.K_STATE,m.kind());var s=m.state();
        assertTrue(s.open());assertEquals(8,s.capacity());assertEquals(2,s.members().size());
        var leader=s.members().getFirst();
        assertEquals("차카",leader.nickname());assertTrue(leader.leader()&&leader.online());assertTrue(leader.slim());
        assertEquals("0123456789abcdef0123456789abcdef",leader.skin());
        assertFalse(s.members().get(1).online());assertEquals("하린",s.invites().getFirst().nickname());
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN),PartyUiProtocol.encode(m));
    }
    @Test void requestsCarryTargetsOnlyWhereNeeded(){
        assertEquals("010300000000000000010000000000000002",HexFormat.of().formatHex(PartyUiProtocol.request(PartyUiProtocol.INVITE,new UUID(1,2))));
        assertEquals("0106",HexFormat.of().formatHex(PartyUiProtocol.request(PartyUiProtocol.LEAVE,null)));
        assertThrows(IllegalArgumentException.class,()->PartyUiProtocol.request(PartyUiProtocol.KICK,null));
        assertThrows(IllegalArgumentException.class,()->PartyUiProtocol.request(PartyUiProtocol.OPEN,new UUID(1,1)));
    }
    @Test void rejectsMalformedPackets(){
        assertThrows(IllegalArgumentException.class,()->PartyUiProtocol.decode(new byte[]{1,9}));
        assertThrows(IllegalArgumentException.class,()->PartyUiProtocol.decode(HexFormat.of().parseHex(GOLDEN+"00")));
        assertThrows(IllegalArgumentException.class,()->PartyUiProtocol.decode(Arrays.copyOf(HexFormat.of().parseHex(GOLDEN),40)));
    }
}
