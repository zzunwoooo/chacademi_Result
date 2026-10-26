package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.EquipmentProtocol;
import school.magiccodex.protocol.EquipmentProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentTest {
    @Test void requestsContainOnlyValidatedSlotIndexes(){
        var r=new Request(EquipmentProtocol.EQUIP,1,9,7,35);assertEquals(r,EquipmentProtocol.request(EquipmentProtocol.request(r)));
        assertThrows(IllegalArgumentException.class,()->new Request(1,1,0,8,0));
        assertThrows(IllegalArgumentException.class,()->new Request(1,1,0,4,36));
        assertThrows(IllegalArgumentException.class,()->new Request(1,0,0,4,0));
        byte[] bytes=EquipmentProtocol.request(r);assertThrows(IllegalArgumentException.class,()->EquipmentProtocol.request(Arrays.copyOf(bytes,bytes.length+1)));
    }
    private Response response(List<Item> items){return new Response(1,3,9,"별하","장착했습니다.",12,4,40,2,Collections.nCopies(8,new byte[512]),items);}
    @Test void maximumSnapshotFitsPluginMessageAndPreservesNames(){
        List<Item> items=new ArrayList<>();for(int i=0;i<36;i++)items.add(new Item(i,i%8,new byte[512]));
        byte[] bytes=EquipmentProtocol.response(response(items));assertTrue(bytes.length<30000);
        var decoded=EquipmentProtocol.response(bytes);assertEquals("별하",decoded.name());assertEquals(36,decoded.candidates().size());assertEquals(40,decoded.mana());
    }
    @Test void duplicateIndexesAndMalformedPayloadsRejected(){
        var item=new Item(1,4,new byte[1]);byte[] duplicates=EquipmentProtocol.response(response(List.of(item,item)));
        assertThrows(IllegalArgumentException.class,()->EquipmentProtocol.response(duplicates));
        assertThrows(IllegalArgumentException.class,()->EquipmentProtocol.response(new byte[30001]));
        assertThrows(IllegalArgumentException.class,()->new Item(0,4,new byte[513]));
    }
    @Test void equipmentManaRecalculationDoesNotStackOrRefill(){
        var a=new ManaAccount(40,100,5);a.modifier("magiccodex:equipment",40,2);a.modifier("magiccodex:equipment",40,2);
        assertEquals(140,a.snapshot().maximum());assertEquals(7,a.snapshot().regeneration());assertEquals(40,a.snapshot().current());
        a.removeModifier("magiccodex:equipment");assertEquals(100,a.snapshot().maximum());assertEquals(5,a.snapshot().regeneration());assertEquals(40,a.snapshot().current());
    }
}
