package school.chacademia.tornado;

import java.util.*;

/** Reservations include the spawn-event tick, before the entity controller is attached. */
final class SpawnGuard {
    record Spot(UUID world,double x,double z){}
    private final Map<UUID,Spot> spots=new HashMap<>();
    private final int maximum;
    private final double radiusSquared;
    SpawnGuard(int maximum,double radius){this.maximum=maximum;radiusSquared=radius*radius;}
    boolean nearby(Spot point){return spots.values().stream().anyMatch(p->p.world.equals(point.world)&&Math.pow(p.x-point.x,2)+Math.pow(p.z-point.z,2)<=radiusSquared);}
    boolean reserve(UUID id,Spot point){if(spots.containsKey(id)||spots.size()>=maximum||nearby(point))return false;spots.put(id,point);return true;}
    void move(UUID id,Spot point){if(spots.containsKey(id))spots.put(id,point);}
    boolean contains(UUID id){return spots.containsKey(id);}
    boolean full(){return spots.size()>=maximum;}
    void remove(UUID id){spots.remove(id);}
    void clear(){spots.clear();}
}
