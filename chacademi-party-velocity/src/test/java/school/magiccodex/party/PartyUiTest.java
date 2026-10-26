package school.magiccodex.party;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyUiTest {
    /** magic-codex-fabric PartyUiTest 의 같은 상수와 맞아야 한다 (클라이언트 디코더와 바이트 형식 고정). */
    static final String GOLDEN="0101010802000000000000000100000000000000020006ecb0a8ecb9b400002030313233343536373839616263646566303132333435363738396162636465660103000000000000000300000000000000040006eba3a8eb8298030000000001000000000000000500000000000000060006ed9598eba6b0ff00000000";

    @Test void encodesTheSharedStateFormat(){
        var skin=new PartyUi.Skin("0123456789abcdef0123456789abcdef",true);
        byte[] bytes=PartyUi.state(true,8,
            List.of(new PartyUi.Person(new UUID(1,2),"차카",0,skin,PartyUi.P_ONLINE|PartyUi.P_LEADER),new PartyUi.Person(new UUID(3,4),"루나",3,PartyUi.Skin.NONE,0)),
            List.of(new PartyUi.Person(new UUID(5,6),"하린",-1,PartyUi.Skin.NONE,0)));
        assertEquals(GOLDEN,HexFormat.of().formatHex(bytes));
    }
    @Test void decodesRequestsStrictly()throws Exception{
        var r=PartyUi.decode(HexFormat.of().parseHex("010300000000000000010000000000000002"));
        assertEquals(PartyUi.INVITE,r.action());assertEquals(new UUID(1,2),r.target());
        assertNull(PartyUi.decode(new byte[]{1,PartyUi.OPEN}).target());
        assertThrows(java.io.IOException.class,()->PartyUi.decode(new byte[]{1,PartyUi.INVITE}),"target missing");
        assertThrows(java.io.IOException.class,()->PartyUi.decode(new byte[]{1,PartyUi.OPEN,0}),"trailing");
        assertThrows(java.io.IOException.class,()->PartyUi.decode(new byte[]{1,9}),"unknown action");
        assertThrows(java.io.IOException.class,()->PartyUi.decode(new byte[]{2,1}),"version");
    }
    @Test void readsOnlyMojangSkinHashes(){
        String json="{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90\",\"metadata\":{\"model\":\"slim\"}}}}";
        var skin=PartyUi.skin(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",skin.hash());assertTrue(skin.slim());
        String other="{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/texture/a1b2c3d4e5f60718293a4b5c6d7e8f90\"}}}";
        assertEquals(PartyUi.Skin.NONE,PartyUi.skin(Base64.getEncoder().encodeToString(other.getBytes(StandardCharsets.UTF_8))));
        assertEquals(PartyUi.Skin.NONE,PartyUi.skin("not base64"));
    }
    @Test void stateIsSentOnlyWhenItChanges(){
        var ui=new PartyUi();var id=UUID.randomUUID();byte[] a={1,1,0},b={1,1,1};
        assertNotNull(ui.stateDue(id,a,0));assertNull(ui.stateDue(id,a,5000));assertNotNull(ui.stateDue(id,b,5001));
        ui.resend(id,6000);assertNull(ui.stateDue(id,b,5500),"held after a server switch");assertNotNull(ui.stateDue(id,b,6000));
        assertTrue(ui.candidatesAllowed(id,0));assertFalse(ui.candidatesAllowed(id,100));assertTrue(ui.candidatesAllowed(id,PartyUi.CANDIDATE_INTERVAL));
    }
}
