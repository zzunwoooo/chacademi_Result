package school.magiccodex.paper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BagSnapshotBindingTest {
    private final UUID owner=UUID.randomUUID(), snapshot=UUID.randomUUID(), head=UUID.randomUUID();
    private BagSnapshotBinding value(){return new BagSnapshotBinding(owner,snapshot,head,12,2,BagSnapshotBinding.hash("inventory with exact metadata"));}
    @Test void installedApiSerializerRoundTripsIdentityAndPageGeneration(){var codec=new BagSnapshotBinding.Codec();assertEquals(value(),codec.deserialize(codec.serialize(value())));}
    @Test void matchingInventoryAndSnapshotAreAccepted(){assertDoesNotThrow(()->value().verify(owner,snapshot,"inventory with exact metadata"));}
    @Test void mixedInventoryCaptureIsRejected(){assertThrows(IllegalStateException.class,()->value().verify(owner,snapshot,"old inventory"));}
    @Test void samePageSequenceFromDifferentSnapshotCannotBeReused(){assertThrows(IllegalStateException.class,()->value().verify(owner,UUID.randomUUID(),"inventory with exact metadata"));}
    @Test void anotherPlayersBindingCannotBeApplied(){assertThrows(IllegalStateException.class,()->value().verify(UUID.randomUUID(),snapshot,"inventory with exact metadata"));}
    @Test void invalidOrUnversionedPayloadsFailClosed(){var c=new BagSnapshotBinding.Codec();for(String bad:new String[]{"","0|x","1|bad","x".repeat(257)})assertThrows(RuntimeException.class,()->c.deserialize(bad));assertThrows(IllegalArgumentException.class,()->new BagSnapshotBinding(owner,snapshot,head,-1,1,BagSnapshotBinding.hash("i")));}
}
