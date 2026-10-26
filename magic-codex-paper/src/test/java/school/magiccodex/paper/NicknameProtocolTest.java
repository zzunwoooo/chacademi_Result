package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.NicknameProtocol;

class NicknameProtocolTest {
    @Test void plainMultilingualNamesAndLimits(){
        for(String name:List.of("차카","Player_01","별빛-연구가","abcdefghijklmnop"))assertEquals(name,NicknameProtocol.validate(name));
        for(String name:List.of("","abcdefghijklmnopq","a b","§c운영자","<admin>","a\n","a\u200B","😀","' OR 1=1"))assertThrows(IllegalArgumentException.class,()->NicknameProtocol.validate(name));
    }
    @Test void protocolIsBoundedAndHasNoClientTarget(){
        var r=new NicknameProtocol.Request(NicknameProtocol.SAVE,3,25,7,"별빛");assertEquals(r,NicknameProtocol.request(NicknameProtocol.encode(r)));
        assertTrue(Arrays.stream(NicknameProtocol.Request.class.getRecordComponents()).noneMatch(c->c.getType()==UUID.class));
        byte[] valid=NicknameProtocol.encode(r);assertThrows(IllegalArgumentException.class,()->NicknameProtocol.request(Arrays.copyOf(valid,valid.length+1)));
        valid[0]^=1;assertThrows(IllegalArgumentException.class,()->NicknameProtocol.request(valid));
        assertThrows(IllegalArgumentException.class,()->NicknameProtocol.request(new byte[NicknameProtocol.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->NicknameProtocol.encode(new NicknameProtocol.Request(4,1,0,0,"")));
    }
    @Test void firstNicknamePushRoundTrip(){for(int kind:new int[]{NicknameProtocol.FIRST,NicknameProtocol.FIRST_DONE}){var r=new NicknameProtocol.Response(kind,0,0,0,UUID.randomUUID(),"Account","Account","","","");assertEquals(r,NicknameProtocol.response(NicknameProtocol.encode(r)));}
        assertThrows(IllegalArgumentException.class,()->NicknameProtocol.encode(new NicknameProtocol.Response(5,0,0,0,UUID.randomUUID(),"a","a","","","")));}
    @Test void identityAndPreviewRoundTrip(){var r=new NicknameProtocol.Response(1,2,3,4,UUID.randomUUID(),"Account","별빛","수습","연구가","");assertEquals(r,NicknameProtocol.response(NicknameProtocol.encode(r)));}
}
