package school.magiccodex.paper;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.NicknameProtocol;

class NicknameSessionTest {
    @Test void rejectOtherOwnerStolenTokenOldConnectionAndReplay(){
        UUID owner=UUID.randomUUID();Object connection=new Object();var s=new NicknameSession(owner,connection,28,5);
        var request=new NicknameProtocol.Request(NicknameProtocol.SAVE,6,28,1,"별빛");
        assertTrue(s.accepts(owner,connection,request));
        assertFalse(s.accepts(UUID.randomUUID(),connection,request));
        assertFalse(s.accepts(owner,new Object(),request));
        assertFalse(s.accepts(owner,connection,new NicknameProtocol.Request(2,6,27,1,"별빛")));
        assertFalse(s.accepts(owner,connection,new NicknameProtocol.Request(2,5,28,1,"별빛")));
    }
}
