package school.magiccodex.party;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyHudTest {
    /** magic-codex-fabric PartyHudTest 의 같은 상수와 맞아야 한다 (클라이언트 디코더와 바이트 형식 고정). */
    static final String GOLDEN="0102000000000000000100000000000000020006ecb0a8ecb9b400074178000041a0000042a4000042c80000000000000000000300000000000000040006eba3a8eb82980300";

    @Test void encodesTheSharedWireFormat(){
        var bytes=PartyHud.encode(List.of(
            new PartyHud.Member(new UUID(1,2),"차카",0,true,true,new PartyHud.Vitals(15.5f,20,82,100,null,0)),
            new PartyHud.Member(new UUID(3,4),"루나",3,false,false,null)));
        assertEquals(GOLDEN,HexFormat.of().formatHex(bytes));
    }
    @Test void vitalsOnlyFromTheTrustedActiveConnection()throws Exception{
        var hud=new PartyHud(Set.of("school","wild"));var id=UUID.randomUUID();Object school=new Object(),wild=new Object();
        byte[] data=vitals(10,20,50,100);
        assertFalse(hud.accept(id,school,wild,"school",true,data,0),"stale connection");
        assertFalse(hud.accept(id,school,school,"lobby",true,data,0),"untrusted server");
        assertFalse(hud.accept(id,school,school,"school",false,data,0),"client-sent");
        assertTrue(hud.accept(id,school,school,"school",true,data,0));
        assertNotNull(hud.vitals(id,school,1000));
        assertNull(hud.vitals(id,wild,1000),"after a server switch the old server's values are not shown");
        assertNull(hud.vitals(id,school,PartyHud.VITALS_TTL+1),"expires");
    }
    @Test void rejectsImpossibleVitals(){
        var hud=new PartyHud(Set.of("school"));Object s=new Object();var id=UUID.randomUUID();
        assertFalse(hud.accept(id,s,s,"school",true,vitals(30,20,0,0),0));
        assertFalse(hud.accept(id,s,s,"school",true,vitals(10,0,0,0),0));
        assertFalse(hud.accept(id,s,s,"school",true,vitals(Float.NaN,20,0,0),0));
        assertFalse(hud.accept(id,s,s,"school",true,vitals(10,20,150,100),0));
        assertFalse(hud.accept(id,s,s,"school",true,new byte[16],0));
        assertTrue(hud.accept(id,s,s,"school",true,vitals(10,20,-1,-1),0),"unknown mana");
    }
    @Test void sendsChangesHeartbeatsAndEmptyOnce(){
        var hud=new PartyHud(Set.of("school"));var a=UUID.randomUUID();byte[] p={1,1,2};
        assertNotNull(hud.due(a,p,0));assertNull(hud.due(a,p,100));assertNotNull(hud.due(a,new byte[]{1,1,3},200));
        assertNotNull(hud.due(a,new byte[]{1,1,3},200+PartyHud.HEARTBEAT));
        assertNotNull(hud.due(a,PartyHud.EMPTY,9000),"left the party");assertNull(hud.due(a,PartyHud.EMPTY,99_999),"no empty heartbeat");
        hud.resend(a,100_000);assertNull(hud.due(a,PartyHud.EMPTY,99_999));assertNotNull(hud.due(a,PartyHud.EMPTY,100_000));
    }
    @Test void unsafeNamesAreReplaced(){
        assertEquals("알 수 없음",PartyHud.safeName("§c빨강"));assertEquals("알 수 없음",PartyHud.safeName(" "));assertEquals("차카",PartyHud.safeName("차카"));
    }
    private static byte[] vitals(float h,float mh,float m,float mm){
        return java.nio.ByteBuffer.allocate(17).put((byte)1).putFloat(h).putFloat(mh).putFloat(m).putFloat(mm).array();
    }
}
