package school.chacademia.tornado;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnGuardTest {
    @Test void sameTickReservationAndHorizontalRadius(){
        var gate=new SpawnGuard(2,192);UUID world=UUID.randomUUID(),id=UUID.randomUUID();
        assertTrue(gate.reserve(id,new SpawnGuard.Spot(world,0,0)));
        assertFalse(gate.reserve(UUID.randomUUID(),new SpawnGuard.Spot(world,192,0)));
        assertTrue(gate.reserve(UUID.randomUUID(),new SpawnGuard.Spot(world,193,0)));assertTrue(gate.full());
    }
    @Test void movementRemovalAndWorldIsolation(){
        var g=new SpawnGuard(2,192);UUID w=UUID.randomUUID(),id=UUID.randomUUID();g.reserve(id,new SpawnGuard.Spot(w,0,0));g.move(id,new SpawnGuard.Spot(w,500,0));
        assertFalse(g.nearby(new SpawnGuard.Spot(w,0,0)));assertTrue(g.nearby(new SpawnGuard.Spot(w,500,0)));
        assertFalse(g.nearby(new SpawnGuard.Spot(UUID.randomUUID(),500,0)));g.remove(id);assertFalse(g.nearby(new SpawnGuard.Spot(w,500,0)));
    }
}
