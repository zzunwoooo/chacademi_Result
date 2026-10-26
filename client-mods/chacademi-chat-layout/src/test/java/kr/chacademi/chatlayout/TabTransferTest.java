package kr.chacademi.chatlayout;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class TabTransferTest {
    @Test void mergingPreservesObjectsAndBothExistingSelections(){
        Object a=new Object(),b=new Object(),c=new Object(),d=new Object();
        var source=List.of(a,b);var destination=List.of(c,d);
        var p=TabTransfer.plan(source,destination,b,a,d,1);
        assertSame(a,p.source().getFirst());assertSame(b,p.destination().get(1));assertSame(d,p.destination().get(p.destinationSelection()));
        assertEquals(List.of(a,b),source);assertEquals(List.of(c,d),destination);assertEquals(0,p.sourceSelection());
    }
    @Test void tearingOffUsesSameTabAndEmptiesLastSourceSafely(){
        Object a=new Object();var p=TabTransfer.plan(List.of(a),List.of(),a,a,null,0);
        assertTrue(p.source().isEmpty());assertEquals(-1,p.sourceSelection());assertSame(a,p.destination().getFirst());assertEquals(0,p.destinationSelection());
    }
    @Test void removingSelectedTabFallsBackToAdjacentWithoutTouchingOtherObjects(){
        Object a=new Object(),b=new Object(),c=new Object(),d=new Object();
        var p=TabTransfer.plan(List.of(a,b,c),List.of(d),b,b,d,1);
        assertSame(c,p.source().get(p.sourceSelection()));assertSame(d,p.destination().get(p.destinationSelection()));
    }
    @Test void equalValuedObjectsAreDistinguishedByIdentity(){
        String a=new String("same"),b=new String("same");Object target=new Object();
        var p=TabTransfer.plan(List.of(a,b),List.of(target),b,a,target,0);
        assertSame(a,p.source().getFirst());assertSame(b,p.destination().getFirst());
    }
    @Test void invalidPlansLeaveInputsUntouched(){
        Object a=new Object(),b=new Object();var source=new ArrayList<>(List.of(a));var dest=new ArrayList<>(List.of(b));
        assertThrows(IllegalArgumentException.class,()->TabTransfer.plan(source,source,a,a,a,0));
        assertThrows(IllegalArgumentException.class,()->TabTransfer.plan(source,dest,b,a,b,0));
        assertThrows(IndexOutOfBoundsException.class,()->TabTransfer.plan(source,dest,a,a,b,2));
        assertEquals(List.of(a),source);assertEquals(List.of(b),dest);
    }
}
