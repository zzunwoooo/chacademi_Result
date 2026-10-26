package school.chacademia.wildlife;

import java.util.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.util.BoundingBox;

/**
 * 지연 색인: 청크 로드 때는 아무것도 하지 않는다. 스폰 검사가 실제로 구조물을 물을 때만
 * 이미 로드된 청크에 한해 그 청크의 구조물을 계산하고(빈 결과 포함) 언로드될 때까지 기억한다.
 * Never locate/generate chunks in a spawn condition.
 */
public final class StructureIndex implements Listener {
    record Key(UUID world, int x, int z) {}
    public record Site(String id, BoundingBox box) {}
    private final Map<Key,List<Site>> chunks = new HashMap<>();
    private static List<Site> compute(Chunk chunk) {
        var sites = new ArrayList<Site>();
        for (var s : chunk.getStructures()) {
            String id=s.getStructure().getKey().toString();
            if (id.startsWith("chacademia:") && !s.getPieces().isEmpty()) {
                // GeneratedStructure's box can include terrain-adaptation padding.
                // Measure proximity from the actual placed pieces instead.
                BoundingBox box=s.getPieces().iterator().next().getBoundingBox().clone();
                for(var piece:s.getPieces()) box.union(piece.getBoundingBox());
                sites.add(new Site(id,box));
            }
        }
        return List.copyOf(sites);
    }
    /** 미로드 청크는 계산하지도 캐시하지도 않는다(청크를 불러오지 않는다). */
    private List<Site> sites(World world,UUID worldId,int x,int z) {
        Key key=new Key(worldId,x,z);
        var cached=chunks.get(key);
        if (cached!=null) return cached;
        if (!world.isChunkLoaded(x,z)) return List.of();
        var sites=compute(world.getChunkAt(x,z));
        chunks.put(key,sites);
        return sites;
    }
    /** 설정 리로드 시 호출: /place 등으로 로드된 청크에 나중에 생긴 구조물을 다시 읽게 한다. */
    public void clear() { chunks.clear(); }
    @EventHandler(ignoreCancelled=true) public void unload(ChunkUnloadEvent e) { chunks.remove(new Key(e.getWorld().getUID(),e.getChunk().getX(),e.getChunk().getZ())); }
    @EventHandler public void unloadWorld(WorldUnloadEvent e) { chunks.keySet().removeIf(k->k.world.equals(e.getWorld().getUID())); }
    public List<Site> nearby(Location loc, Set<String> allowed, double radius) {
        Set<Site> result=new LinkedHashSet<>();
        World world=loc.getWorld(); if(world==null) return List.of(); UUID worldId=world.getUID();
        // Templates can extend from their start chunk; 128 covers the pack's maximum extent.
        int reach=(int)Math.ceil((radius+128)/16),cx=loc.getBlockX()>>4,cz=loc.getBlockZ()>>4;
        for(int x=cx-reach;x<=cx+reach;x++) for(int z=cz-reach;z<=cz+reach;z++) {
            for(var site:sites(world,worldId,x,z)) {
                if ((allowed.isEmpty()||allowed.contains(site.id)) && distanceSquared(loc,site.box)<=radius*radius
                    && loc.getY()>=site.box.getMinY()-12 && loc.getY()<=site.box.getMaxY()+24) result.add(site);
            }
        }
        return List.copyOf(result);
    }
    static double distanceSquared(Location l,BoundingBox b) {
        double dx=Math.max(Math.max(b.getMinX()-l.getX(),0),l.getX()-b.getMaxX());
        double dz=Math.max(Math.max(b.getMinZ()-l.getZ(),0),l.getZ()-b.getMaxZ());
        return dx*dx+dz*dz;
    }
}
