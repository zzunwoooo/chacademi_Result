package school.chacademia.wildlife;

import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * 개체 수 제한용 주변 엔티티 스캔을 격자 칸(32블록)마다 약 40틱에 한 번만 수행한다.
 * 같은 칸의 모든 스폰 후보·무리 검사가 한 번의 스캔 결과(야생 몹 위치 스냅샷)를 나눠 쓴다.
 * 메인 스레드 전용.
 */
final class PopulationCache {
    private static final long TTL_NANOS=2_000_000_000L;
    private static final int SHIFT=5,HALF=16;
    private static final double REACH_XZ=96,REACH_Y=64;
    private record Key(UUID world,int x,int y,int z) {}
    private record Wild(String type,double x,double y,double z) {}
    private record Scan(long expires,double cx,double cy,double cz,List<Wild> mobs) {}
    private final Map<Key,Scan> scans=new HashMap<>();

    /** {동종 수(range 이내), 전체 야생 몹 수(l 기준 ±96/±64/±96)} — 기존 getNearbyEntities(l,96,64,96) 판정과 같은 범위. */
    int[] count(World w,Location l,String type,double range) {
        long now=System.nanoTime();
        Key key=new Key(w.getUID(),l.getBlockX()>>SHIFT,l.getBlockY()>>SHIFT,l.getBlockZ()>>SHIFT);
        Scan scan=scans.get(key);
        if(scan==null||now-scan.expires()>=0) {
            if(scans.size()>=256) { scans.values().removeIf(v->now-v.expires()>=0); if(scans.size()>=1024) scans.clear(); }
            scan=scan(w,key,now); scans.put(key,scan);
        }
        int same=0,total=0; double x=l.getX(),y=l.getY(),z=l.getZ(),rangeSquared=range*range; String shiny=type+"_shiny";
        for(Wild mob:scan.mobs()) {
            double dx=mob.x()-x,dy=mob.y()-y,dz=mob.z()-z;
            if(Math.abs(dx)>REACH_XZ||Math.abs(dy)>REACH_Y||Math.abs(dz)>REACH_XZ) continue;
            total++;
            if((mob.type().equals(type)||mob.type().equals(shiny))&&dx*dx+dy*dy+dz*dz<rangeSquared) same++;
        }
        return new int[]{same,total};
    }

    private Scan scan(World w,Key key,long now) {
        double cx=(key.x()<<SHIFT)+HALF,cy=(key.y()<<SHIFT)+HALF,cz=(key.z()<<SHIFT)+HALF;
        var mobs=new ArrayList<Wild>(); Location at=new Location(w,cx,cy,cz);
        // 칸 안 어느 지점에서 물어도 ±96/±64 범위가 모두 들어오도록 반 칸만큼 넓게 훑는다.
        for(Entity e:w.getNearbyEntities(at,REACH_XZ+HALF,REACH_Y+HALF,REACH_XZ+HALF)) {
            if(!(e instanceof LivingEntity)||e.getScoreboardTags().contains("chacademia_pet")) continue;
            var mob=MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId());
            if(mob.isEmpty()) continue;
            String type=mob.get().getType().getInternalName();
            if(!type.startsWith("CA_Wild_")) continue;
            e.getLocation(at); mobs.add(new Wild(type,at.getX(),at.getY(),at.getZ()));
        }
        return new Scan(now+TTL_NANOS,cx,cy,cz,mobs);
    }

    /** 방금 스폰을 허용한 야생 몹을 살아 있는 스캔 결과에 바로 더한다: 같은 틱의 무리 스폰이 제한을 넘지 않게 한다. */
    void noteSpawn(World w,String type,Location l) {
        long now=System.nanoTime(); UUID world=w.getUID(); double x=l.getX(),y=l.getY(),z=l.getZ();
        for(var entry:scans.entrySet()) {
            Scan scan=entry.getValue();
            if(!entry.getKey().world().equals(world)||now-scan.expires()>=0) continue;
            if(Math.abs(x-scan.cx())<=REACH_XZ+HALF&&Math.abs(y-scan.cy())<=REACH_Y+HALF&&Math.abs(z-scan.cz())<=REACH_XZ+HALF) scan.mobs().add(new Wild(type,x,y,z));
        }
    }

    /** 인접 청크 비슷한 몹 판정용: l 기준 수평 radius 안(세로 ±16)의 야생 몹 미몹 이름. count()와 같은 캐시 스캔을 쓴다. */
    Set<String> typesNear(World w,Location l,double radius) {
        count(w,l,"",0); // 캐시 스캔 준비(없거나 만료면 새로)
        Scan scan=scans.get(new Key(w.getUID(),l.getBlockX()>>SHIFT,l.getBlockY()>>SHIFT,l.getBlockZ()>>SHIFT));
        if(scan==null) return Set.of();
        var out=new HashSet<String>(); double x=l.getX(),y=l.getY(),z=l.getZ(),r2=radius*radius;
        for(Wild mob:scan.mobs()) {
            double dx=mob.x()-x,dz=mob.z()-z;
            if(Math.abs(mob.y()-y)<=16&&dx*dx+dz*dz<=r2) out.add(mob.type());
        }
        return out;
    }

    void clear() { scans.clear(); }
}
