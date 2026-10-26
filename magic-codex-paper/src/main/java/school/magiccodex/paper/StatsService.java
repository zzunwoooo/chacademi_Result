package school.magiccodex.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional public fields; unavailable fields stay blank instead of inventing values. */
public final class StatsService {
    public record Additional(Integer circle,String dormitory,Double power,Double popularity){
        public Additional {
            if(circle!=null&&(circle<1||circle>9))throw new IllegalArgumentException("Circle 1..9");
            if(dormitory!=null&&dormitory.length()>64)throw new IllegalArgumentException("Dormitory too long");
            for(Double v:new Double[]{power,popularity})if(v!=null&&(!Double.isFinite(v)||Math.abs(v)>1e9))throw new IllegalArgumentException("Invalid stat");
        }
    }
    private final Map<UUID,Additional> values=new ConcurrentHashMap<>();
    public Additional get(UUID id){return values.getOrDefault(id,new Additional(null,null,null,null));}
    public void set(UUID id,Additional value){ManaService.main();values.put(id,Objects.requireNonNull(value));}
    public void remove(UUID id){ManaService.main();values.remove(id);}
    void clear(){values.clear();}
}
