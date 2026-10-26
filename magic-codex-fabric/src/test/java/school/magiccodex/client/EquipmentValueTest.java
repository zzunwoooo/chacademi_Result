package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.EquipmentProtocol.Response;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentValueTest {
    @Test void unavailablePowerDoesNotUnboxNullBeforeFirstSnapshot(){
        EquipmentClient.data=null;
        assertNull(EquipmentClient.power(null));assertEquals(10,EquipmentClient.power(10.0));
        try{
            EquipmentClient.data=new Response(1,1,1,"","",0,0,0,0,List.of(),List.of());
            assertNull(EquipmentClient.power(null));
            EquipmentClient.data=new Response(1,1,1,"","",12,0,0,0,List.of(),List.of());
            assertEquals(12,EquipmentClient.power(null));assertEquals(22,EquipmentClient.power(10.0));
        }finally{EquipmentClient.data=null;}
    }
}
