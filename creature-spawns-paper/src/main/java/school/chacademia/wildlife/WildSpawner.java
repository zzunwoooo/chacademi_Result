package school.chacademia.wildlife;

import io.lumine.mythic.api.mobs.entities.SpawnReason;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 차카데미 자체 야생 스폰 루프 (MythicMobs RandomSpawns 대체).
 * <p>
 * 대상: 관전 모드를 뺀 모든 플레이어(생존·모험·크리에이티브).
 * 매 틱 플레이어 일부만 처리해서 부하를 고르게 나눈다: 플레이어 한 명은 period-seconds마다 한 번 차례가 오고,
 * 차례마다 attempts-per-player번 시도한다. 한 틱의 전체 시도는 max-attempts-per-tick을 넘지 않는다.
 * <p>
 * 시도 1번 = 플레이어 주변 링(player-min-distance ~ player-max-distance) 안의 로드된 청크 한 지점 →
 * 청크 상한 확인 → 지면/수중/동굴 높이 결정 → 그 바이옴에 사는 종 중 가벼운 조건(지상·수중, 높이, 시간, 계절)을 먼저 거른 뒤
 * 등급 가중치로 한 종 추첨 → 기존 서식지 전체 검사(WildlifePlugin.check, 개체 상한 포함) → 미몹 몹으로 소환.
 * 소환은 SpawnReason.NATURAL이라 기존 스폰 이벤트 재검사·자연 표시·무리 추가·디스폰 정리를 그대로 탄다.
 * 청크 로드·생성, 구조물 탐색은 하지 않는다. 메인 스레드 전용.
 */
final class WildSpawner {
    record Settings(boolean enabled,int periodTicks,int attemptsPerPlayer,int maxAttemptsPerTick,Map<String,Double> rarityWeights,Map<String,Double> rarityChance,double similarWeight,double similarRadius) {
        static final Settings OFF=new Settings(false,100,3,40,Map.of(),Map.of(),0.2,24);
        static Settings parse(ConfigurationSection s,List<String> warnings) {
            if(s==null) return new Settings(true,100,3,40,defaultWeights(),defaultChance(),0.2,24);
            double seconds=s.getDouble("period-seconds",5);
            if(seconds<0.5) { warnings.add("spawner.period-seconds "+seconds+" → 0.5"); seconds=0.5; }
            int per=s.getInt("attempts-per-player",3), max=s.getInt("max-attempts-per-tick",40);
            if(per<1) { warnings.add("spawner.attempts-per-player → 1"); per=1; }
            if(max<1) { warnings.add("spawner.max-attempts-per-tick → 1"); max=1; }
            var weights=new HashMap<>(defaultWeights());
            var w=s.getConfigurationSection("rarity-weights");
            if(w!=null) for(String key:w.getKeys(false)) {
                String rarity=key.equals("보스")?"boss":key.toLowerCase(Locale.ROOT);
                if(!Habitat.RARITIES.contains(rarity)) { warnings.add("spawner.rarity-weights."+key+" (알 수 없는 등급) 무시"); continue; }
                double value=w.getDouble(key);
                if(value<0) { warnings.add("spawner.rarity-weights."+key+" → 0"); value=0; }
                weights.put(rarity,value);
            }
            var chance=new HashMap<>(defaultChance());
            var cs=s.getConfigurationSection("rarity-attempt-chance");
            if(cs!=null) for(String key:cs.getKeys(false)) {
                String rarity=key.equals("보스")?"boss":key.toLowerCase(Locale.ROOT);
                if(!Habitat.RARITIES.contains(rarity)) { warnings.add("spawner.rarity-attempt-chance."+key+" (알 수 없는 등급) 무시"); continue; }
                double value=cs.getDouble(key);
                if(value<0||value>1) { warnings.add("spawner.rarity-attempt-chance."+key+" "+value+" → 0~1로 자름"); value=Math.clamp(value,0,1); }
                chance.put(rarity,value);
            }
            double similar=s.getDouble("similar-nearby-weight",0.2), radius=s.getDouble("similar-nearby-radius",24);
            if(similar<0||similar>1) { warnings.add("spawner.similar-nearby-weight "+similar+" → 0.2"); similar=0.2; }
            if(radius<0||radius>96) { warnings.add("spawner.similar-nearby-radius "+radius+" → 24"); radius=24; }
            return new Settings(s.getBoolean("enabled",true),(int)Math.round(seconds*20),per,max,Map.copyOf(weights),Map.copyOf(chance),similar,radius);
        }
        /** 예전 RandomSpawns 시도확률(4.5% / 1.8% / 0.4% / 0.06%)을 그대로 비율로 쓴다. */
        static Map<String,Double> defaultWeights() { return Map.of("1",4.5,"2",1.8,"3",0.4,"boss",0.06); }
        /** 뽑힌 뒤 한 번 더 굴리는 등급별 통과 확률: 3성·보스만 사는 바이옴에서도 시도당 소환이 드물게. */
        static Map<String,Double> defaultChance() { return Map.of("1",1.0,"2",0.5,"3",0.08,"boss",0.01); }
    }

    private final WildlifePlugin plugin;
    private Settings settings=Settings.OFF;
    private BukkitTask task;
    private long tick;
    private final Map<String,List<Habitat>> byBiome=new HashMap<>();
    private final Map<String,Long> outcomes=new TreeMap<>();
    private long attempts,spawned;

    WildSpawner(WildlifePlugin plugin) { this.plugin=plugin; }

    void apply(Settings next) {
        settings=next; byBiome.clear();
        if(task!=null) { task.cancel(); task=null; }
        if(next.enabled()) task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,40,1);
    }
    /** 서식지·바이옴 호환표가 바뀌면 바이옴별 후보 목록을 다시 만든다. */
    void invalidate() { byBiome.clear(); }
    void stop() { if(task!=null) task.cancel(); task=null; }
    Settings settings() { return settings; }

    private void tick() {
        tick++;
        int period=settings.periodTicks(), budget=settings.maxAttemptsPerTick();
        for(String name:plugin.spawnWorlds()) {
            World w=Bukkit.getWorld(name);
            if(w==null) continue;
            for(Player p:w.getPlayers()) {
                if(budget<=0) return;
                if(p.getGameMode()==GameMode.SPECTATOR) continue; // 크리에이티브도 스폰 대상
                // 플레이어마다 다른 틱에 차례가 오게 엔티티 번호로 흩는다.
                if(Math.floorMod(p.getEntityId(),period)!=Math.floorMod(tick,period)) continue;
                for(int i=0;i<settings.attemptsPerPlayer()&&budget>0;i++,budget--) {
                    String result;
                    try { result=attempt(p); }
                    catch(RuntimeException|LinkageError error) {
                        result="오류"; plugin.warnOnce("야생 스폰 시도 중 오류",error);
                    }
                    attempts++; outcomes.merge(result,1L,Long::sum);
                }
            }
        }
    }

    private String attempt(Player p) {
        var random=ThreadLocalRandom.current();
        World w=p.getWorld(); Location at=p.getLocation();
        double min=plugin.playerMinDistance(), max=plugin.playerMaxDistance();
        double angle=random.nextDouble(Math.PI*2), dist=min+random.nextDouble()*Math.max(0,max-min);
        int x=(int)Math.floor(at.getX()+Math.cos(angle)*dist), z=(int)Math.floor(at.getZ()+Math.sin(angle)*dist);
        if(!w.isChunkLoaded(x>>4,z>>4)) return "청크 미로드";
        if(plugin.chunkFull(w.getChunkAt(x>>4,z>>4))) return "청크 개체 제한";
        // 플레이어가 지표보다 6칸 넘게 아래(동굴)에 있으면 그 높이 근처 동굴 바닥을, 아니면 지표(물이면 물속)를 고른다.
        // (하늘빛으로 판단하면 지붕 있는 집 안도 동굴로 오판하므로 지표 높이로 본다.)
        boolean underground=at.getBlockY()<w.getHighestBlockYAt(at.getBlockX(),at.getBlockZ(),HeightMap.MOTION_BLOCKING_NO_LEAVES)-6;
        int y; boolean waterPoint;
        if(underground) {
            y=caveFloor(w,x,at.getBlockY(),z,random);
            if(y==Integer.MIN_VALUE) return "동굴 바닥 없음";
            waterPoint=false;
        } else {
            int top=w.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block surface=w.getBlockAt(x,top,z);
            if(surface.getType()==Material.WATER) { y=top-random.nextInt(0,3); waterPoint=true; }
            else { y=top+1; waterPoint=false; }
        }
        Location point=new Location(w,x+.5,y,z+.5);
        String biome=w.getBiome(x,y,z).getKey().toString();
        var nearby=plugin.familiesNear(point,settings.similarRadius());
        var candidates=new ArrayList<Habitat>(); var weights=new ArrayList<Double>(); double total=0;
        for(Habitat h:candidates(biome)) {
            if(h.water()!=waterPoint||y<h.minY()||y>h.maxY()||!plugin.timeAndSeasonOk(h,w)) continue;
            double weight=weight(h,nearby);
            if(weight<=0) continue;
            candidates.add(h); weights.add(weight); total+=weight;
        }
        if(candidates.isEmpty()) return "나올 종 없음";
        double roll=random.nextDouble(total); Habitat pick=candidates.get(candidates.size()-1);
        for(int i=0;i<candidates.size();i++) {
            roll-=weights.get(i);
            if(roll<0) { pick=candidates.get(i); break; }
        }
        // 청크 개체 수는 위에서 이미 셌으므로 다시 세지 않는다.
        if(random.nextDouble()>=settings.rarityChance().getOrDefault(pick.rarity(),1d)) return "등급 판정 실패("+pick.rarity()+")";
        String result=plugin.checkChunkCounted(pick.id(),point);
        if(!result.equals("OK")) return result;
        boolean shiny=random.nextInt(plugin.shinyDenominator())==0;
        String type="CA_Wild_"+pick.id()+(shiny?"_shiny":"");
        var mob=MythicBukkit.inst().getMobManager().getMythicMob(type);
        if(mob.isEmpty()) return "미몹 정의 없음 "+type;
        var active=mob.get().spawn(BukkitAdapter.adapt(point),1,SpawnReason.NATURAL);
        if(active==null||active.getEntity()==null||!active.getEntity().getBukkitEntity().isValid()) return "소환 취소";
        spawned++;
        return "OK";
    }

    /** 등급(또는 종별 weight) 가중치. 주변(인접 청크)에 같은 묶음 몹이 이미 있으면 similar-nearby-weight배로 낮춘다. */
    private double weight(Habitat h,Set<String> nearbyFamilies) {
        double weight=h.weight()>0?h.weight():settings.rarityWeights().getOrDefault(h.rarity(),0d);
        return nearbyFamilies.contains(h.family())?weight*settings.similarWeight():weight;
    }

    /** 플레이어 높이 ±16칸에서 '단단한 바닥 + 위로 빈 공간 2칸 + 하늘빛 0'인 칸을 모아 하나 고른다. 없으면 MIN_VALUE. */
    private static int caveFloor(World w,int x,int baseY,int z,ThreadLocalRandom random) {
        int lo=Math.max(w.getMinHeight()+1,baseY-16), hi=Math.min(w.getMaxHeight()-3,baseY+16);
        int[] found=new int[8]; int n=0;
        for(int y=hi;y>=lo&&n<found.length;y--) {
            Block feet=w.getBlockAt(x,y,z);
            if(!feet.isPassable()||feet.isLiquid()||feet.getLightFromSky()!=0) continue;
            if(!w.getBlockAt(x,y-1,z).getType().isSolid()) continue;
            Block head=w.getBlockAt(x,y+1,z);
            if(!head.isPassable()||head.isLiquid()) continue;
            found[n++]=y;
        }
        return n==0?Integer.MIN_VALUE:found[random.nextInt(n)];
    }

    private List<Habitat> candidates(String biome) {
        return byBiome.computeIfAbsent(biome,key->{
            var list=new ArrayList<Habitat>();
            for(Habitat h:plugin.habitatList()) if(plugin.biomeMatches(h,key)) list.add(h);
            return List.copyOf(list);
        });
    }

    /**
     * /월드관리 야생몹 확률: 이 자리에 스폰 시도가 걸렸을 때 어떤 종이 몇 % 확률로 뽑히는지 + 각 종이 여기서 실제로 나올 수 있는지.
     * 읽기 전용(소환·청크 로드 없음). 플레이어 거리 조건은 '본인이 서 있는 자리'라 빼고 본다.
     */
    List<String> chanceAt(Player p) {
        var out=new ArrayList<String>();
        World w=p.getWorld(); Location at=p.getLocation();
        int x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        boolean waterPoint=w.getBlockAt(x,y,z).getType()==Material.WATER;
        String biome=w.getBiome(x,y,z).getKey().toString();
        out.add(" 위치 "+w.getName()+" "+x+" "+y+" "+z+" · 바이옴 "+biome+" · "+(waterPoint?"물속":"지상")+" · 시간 "+w.getTime());
        if(!plugin.spawnWorlds().contains(w.getName())) { out.add(" 이 월드는 야생 스폰 월드가 아니에요 (config worlds)"); return out; }
        out.add(plugin.crowdLine(at));
        var nearby=plugin.familiesNear(at,settings.similarRadius());
        var rows=new ArrayList<Object[]>(); double total=0; int other=0;
        for(Habitat h:candidates(biome)) {
            if(h.water()!=waterPoint) { other++; continue; }
            String why=y<h.minY()||y>h.maxY()?"높이":!plugin.timeAndSeasonOk(h,w)?"시간·계절·날씨":null;
            double weight=weight(h,nearby);
            if(why==null&&weight<=0) why="가중치 0";
            if(why==null) total+=weight;
            rows.add(new Object[]{h,weight,why});
        }
        if(rows.isEmpty()) { out.add(" 이 바이옴"+(waterPoint?"(물속)":"(지상)")+"에 사는 야생 몹이 없어요"+(other>0?" ("+(waterPoint?"지상":"물속")+" "+other+"종은 있음)":"")); return out; }
        final double sum=total;
        rows.sort((a,b)->{
            boolean oa=a[2]==null, ob=b[2]==null;
            if(oa!=ob) return oa?-1:1;
            return Double.compare((double)b[1],(double)a[1]);
        });
        out.add(" 스폰 시도가 여기 걸리면 뽑힐 확률 · 괄호는 등급 판정까지 친 시도당 소환 확률 (변색종은 그중 1/"+plugin.shinyDenominator()+"):");
        for(Object[] r:rows) {
            Habitat h=(Habitat)r[0]; double weight=(double)r[1]; String why=(String)r[2];
            String grade=h.rarity().equals("boss")?"보스":h.rarity()+"성";
            String name=plugin.displayName(h.id());
            if(why!=null) { out.add("  ✖ "+name+" ("+grade+") 지금 안 나옴: "+why); continue; }
            String here=plugin.check(h.id(),at,false);
            double pass=settings.rarityChance().getOrDefault(h.rarity(),1d);
            String pct=String.format(Locale.ROOT,pass<1?"%.1f%%(시도당 %.2f%%)":"%.1f%%",weight*100/sum,weight*100/sum*pass);
            out.add("  "+(here.equals("OK")?"✔ ":"△ ")+pct+" "+name+" ("+grade+")"+(nearby.contains(h.family())?" ↓근처에 비슷한 몹":"")+" · 이 자리 "+(here.equals("OK")?"가능":"불가: "+here));
        }
        if(other>0) out.add(" ("+(waterPoint?"지상":"물속")+"형 "+other+"종은 "+(waterPoint?"물 밖":"물속")+"에서 확인하세요)");
        out.add(" ※ 실제 스폰은 플레이어에게서 "+(int)plugin.playerMinDistance()+"~"+(int)plugin.playerMaxDistance()+"칸 떨어진 곳에서만 일어나요");
        return out;
    }

    List<String> diagnostics() {
        var s=settings;
        return List.of(" 자체 스폰 루프 "+(s.enabled()&&task!=null?"켜짐":"꺼짐")+" · 주기 "+(s.periodTicks()/20.0)+"초 · 플레이어당 "+s.attemptsPerPlayer()+"회 · 틱당 최대 "+s.maxAttemptsPerTick()+"회 · 등급 가중치 "+new TreeMap<>(s.rarityWeights())+" · 등급 통과 "+new TreeMap<>(s.rarityChance())+" · 근처 비슷한 몹 ×"+s.similarWeight()+"("+(int)s.similarRadius()+"칸)",
            " 자체 스폰 시도="+attempts+" 소환="+spawned+" 결과="+outcomes);
    }
}
