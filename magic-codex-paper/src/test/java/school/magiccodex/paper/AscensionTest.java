package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.AscensionProtocol;
import school.magiccodex.protocol.AscensionProtocol.*;

class AscensionTest {
    @Test void doubleClickReplayAndWrongPlayerCannotAdvance(){
        var g=new AscensionGate();var a=UUID.randomUUID();var b=UUID.randomUUID();
        var o=g.open(a,4,1000);
        assertFalse(g.consume(b,o.token(),4,true,2000));
        assertFalse(g.consume(a,o.token()+1,4,true,2000));
        assertTrue(g.consume(a,o.token(),4,true,2000));
        assertFalse(g.consume(a,o.token(),5,true,2001));
    }
    @Test void rechecksPermissionRankExpiryAndCap(){
        var g=new AscensionGate();var id=UUID.randomUUID();
        var revoked=g.open(id,4,0);assertFalse(g.consume(id,revoked.token(),4,false,100));
        assertFalse(g.consume(id,revoked.token(),4,true,101));
        var expired=g.open(id,4,0);assertFalse(g.consume(id,expired.token(),4,true,60000));
        var changed=g.open(id,4,0);assertFalse(g.consume(id,changed.token(),5,true,100));
        var capped=g.open(id,9,0);assertFalse(g.consume(id,capped.token(),9,true,100));
        var old=g.open(id,3,0);var newest=g.open(id,3,100);
        assertFalse(g.consume(id,old.token(),3,true,101));assertTrue(g.consume(id,newest.token(),3,true,102));
    }
    @Test void strictVersionedPayloadsRoundTripAndRejectMalformedData(){
        var q=new Request(AscensionProtocol.CLAIM,7);assertEquals(q,AscensionProtocol.decodeRequest(AscensionProtocol.encodeRequest(q)));
        var r=new Response(AscensionProtocol.SUCCESS,7,4,5,true,"");byte[] b=AscensionProtocol.encodeResponse(r);assertEquals(r,AscensionProtocol.decodeResponse(b));
        for(int n=0;n<b.length;n++){var cut=Arrays.copyOf(b,n);assertThrows(IllegalArgumentException.class,()->AscensionProtocol.decodeResponse(cut));}
        assertThrows(IllegalArgumentException.class,()->AscensionProtocol.decodeResponse(Arrays.copyOf(b,b.length+1)));
        assertThrows(IllegalArgumentException.class,()->AscensionProtocol.encodeResponse(new Response(2,1,4,9,true,"")));
        assertThrows(IllegalArgumentException.class,()->AscensionProtocol.encodeRequest(new Request(0,8)));
        assertThrows(IllegalArgumentException.class,()->AscensionProtocol.encodeRequest(new Request(1,0)));
        b[0]=0;assertThrows(IllegalArgumentException.class,()->AscensionProtocol.decodeResponse(b));
    }
}
