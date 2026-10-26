package school.magiccodex.paper;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyVitalsBridgeTest {
    /** Velocity PartyHud.decodeVitals 가 받는 17바이트 형식. */
    @Test void encodesVelocityVitalsFormat(){
        assertEquals("0141a0000041a00000bf800000bf800000",HexFormat.of().formatHex(PartyVitalsBridge.encode(20,20,-1,-1)));
        assertEquals("01417800004"+"1a0000042a4000042c80000",HexFormat.of().formatHex(PartyVitalsBridge.encode(15.5f,20,82,100)));
    }
    @Test void partialManaIsSentAsUnknown(){
        assertEquals("bf800000bf800000",HexFormat.of().formatHex(PartyVitalsBridge.encode(1,20,5,-1)).substring(18));
    }
}
