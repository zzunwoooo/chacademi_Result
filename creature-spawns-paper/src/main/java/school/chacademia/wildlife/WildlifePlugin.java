package school.chacademia.wildlife;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.*;
import io.lumine.mythic.api.mobs.entities.SpawnReason;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import school.magiccodex.paper.ClimateService;

public final class WildlifePlugin extends JavaPlugin implements Listener {
    public final StructureIndex structures=new StructureIndex();
    /** config.yml에서 스폰 후보마다 읽던 값을 로드/리로드 때 한 번만 읽어 둔다. */
    private record Excluded(String world,double x,double z,double radius) {}
    private record Settings(boolean enabled,Set<String> worlds,double playerMinSquared,double playerMaxSquared,int areaCap,int shinyDenominator,List<Excluded> excluded,Map<String,Double> rarityScale) {}
    private Map<String,Habitat> habitats=Map.of();
    private BiomeCompatibility biomeCompatibility=new BiomeCompatibility(Map.of());
    private final Map<String,Long> naturalChecks=new TreeMap<>();
    private long naturalSpawned,lastNaturalSpawnLog;
    private MainThreadSpawnGate naturalGate;
    private Settings settings=new Settings(false,Set.of(),0,0,0,1,List.of(),Map.of());
    private final PopulationCache population=new PopulationCache();
    // 메인 스레드 전용 임시 좌표: 비교할 때마다 Location을 새로 만들지 않는다.
    private final Location scratch=new Location(null,0,0,0);
    private NamespacedKey naturalKey;
    private boolean groupSpawning;
    private ChunkMobLimit chunkLimit;
    private WildSpawner.Settings spawnerSettings=WildSpawner.Settings.OFF;
    private long lastCleanupLog;
    private final CropAwakening crops=new CropAwakening(this);
    private final WildSpawner spawner=new WildSpawner(this);
    private final WildCombat combat=new WildCombat(this);
    private long lastSpawnerWarn;
    @Override public void onEnable() {
        naturalGate=new MainThreadSpawnGate(Bukkit::isPrimaryThread,action->{var task=Bukkit.getScheduler().runTask(this,action);return task::cancel;},16,250);
        saveDefaultConfig(); reloadSettings(); reloadHabitats(); naturalKey=new NamespacedKey(this,"natural");
        spawner.apply(spawnerSettings);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(new NoHuskHunger(),this);
        combat.reload(getConfig().getConfigurationSection("combat"));
        getServer().getPluginManager().registerEvents(combat,this);
        Bukkit.getScheduler().runTaskTimer(this,combat::regenTick,43,40); // 타이밍 조정: 시작 위상을 cleanup·TargetHud와 엇갈림
        // 구조물 색인은 지연 방식이다: 청크 로드 때가 아니라 구조물 조건이 있는 서식지가 물을 때만 계산한다.
        getServer().getPluginManager().registerEvents(structures,this);
        crops.reload(); getServer().getPluginManager().registerEvents(crops,this);
        if(ChacaLink.ready()){
            try{new WildlifeCommands(this,crops).register();}
            catch(RuntimeException e){getLogger().log(java.util.logging.Level.WARNING,"명령어 허브에 야생몹 명령을 붙이지 못했습니다",e);}
        }else getLogger().warning("MagicCodexBridge 명령어 허브가 없어 야생몹 관리·테스트 명령을 등록하지 않았습니다 (스폰은 그대로 동작).");
        // Handles entities loaded after restart too; only our marked natural mobs are eligible.
        Bukkit.getScheduler().runTaskTimer(this,this::cleanup,211,200); // 타이밍 조정: regenTick·TargetHud와 같은 틱에 겹치지 않게
    }
    /** excluded-areas가 없거나 항목이 잘못돼도 스폰 검사에서 NPE가 나지 않도록 여기서 걸러 둔다. */
    /** rarity-model-scale: 등급별 모델 크기. 없으면 3성 1.35배(오너 2026-10-23), 나머지 1. */
    private Map<String,Double> rarityScale(org.bukkit.configuration.ConfigurationSection c) {
        var out=new HashMap<String,Double>(Map.of("3",1.35));
        var s=c.getConfigurationSection("rarity-model-scale");
        if(s!=null) for(String key:s.getKeys(false)) {
            String r=key.equals("보스")?"boss":key.toLowerCase(Locale.ROOT);
            double v=s.getDouble(key,1);
            if(Habitat.RARITIES.contains(r)&&v>=0.3&&v<=3) out.put(r,v); else getLogger().warning("rarity-model-scale."+key+" 값을 무시합니다: "+v);
        }
        return Map.copyOf(out);
    }
    /** 자연 스폰 몹의 등급에 맞는 모델 크기. */
    double scaleFor(String mythicType) {
        Habitat h=habitats.get(mythicType.substring(8).replaceFirst("_shiny$",""));
        return h==null?1:settings.rarityScale().getOrDefault(h.rarity(),1d);
    }
    void reloadSettings() {
        var c=getConfig(); var excluded=new ArrayList<Excluded>(); var areas=c.getConfigurationSection("excluded-areas");
        if(areas!=null) for(String area:areas.getKeys(false)) {
            var a=areas.getConfigurationSection(area);
            if(a==null||a.getString("world")==null) { getLogger().warning("excluded-areas."+area+" 항목을 건너뜁니다(world 누락)."); continue; }
            excluded.add(new Excluded(a.getString("world"),a.getDouble("x"),a.getDouble("z"),a.getDouble("radius")));
        }
        double min=c.getDouble("player-min-distance",24),max=c.getDouble("player-max-distance",80);
        Object configuredCap=c.get("chunk-cap",ChunkMobLimit.DEFAULT_CAP);
        if(!(configuredCap instanceof Integer cap)) throw new IllegalArgumentException("chunk-cap must be a positive integer");
        var nextChunkLimit=new ChunkMobLimit(cap);
        settings=new Settings(c.getBoolean("enabled",true),Set.copyOf(c.getStringList("worlds")),min*min,max*max,
            c.getInt("area-cap",24),Math.max(1,c.getInt("shiny-denominator",2048)),List.copyOf(excluded),rarityScale(c));
        var spawnerWarnings=new ArrayList<String>();
        spawnerSettings=WildSpawner.Settings.parse(c.getConfigurationSection("spawner"),spawnerWarnings);
        if(!spawnerWarnings.isEmpty()) getLogger().warning("config.yml spawner 값을 기본값으로 대체: "+String.join(", ",spawnerWarnings));
        chunkLimit=nextChunkLimit;
        population.clear();
    }
    /** 모든 서식지를 불변 Habitat으로 한 번에 해석한다. 하나라도 필수 값이 잘못되면 예외를 던지고 기존 설정을 유지한다. */
    void reloadHabitats() {
        var compatibilityFile=new File(getDataFolder(),"biome-compatibility.yml");
        if(!compatibilityFile.exists())saveResource("biome-compatibility.yml",false);
        var aliases=new YamlConfiguration();
        try{aliases.load(compatibilityFile);}catch(Exception error){throw new IllegalArgumentException("biome-compatibility.yml error",error);}
        var nextCompatibility=BiomeCompatibility.parse(aliases.getConfigurationSection("vanilla-biomes"),aliases.getConfigurationSection("terralith-biomes"));
        if(aliases.getConfigurationSection("terralith-biomes")==null) getLogger().warning("biome-compatibility.yml에 terralith-biomes가 없습니다 → yellowstone 등 서식지 없는 Terralith 바이옴에서는 몹이 안 나와요. 레포 최신 파일로 교체하세요.");
        if(!new File(getDataFolder(),"habitats.yml").exists()) saveResource("habitats.yml",false);
        var next=new YamlConfiguration();
        try { next.load(new File(getDataFolder(),"habitats.yml")); }
        catch(Exception e) { throw new IllegalArgumentException("habitats.yml 오류",e); }
        var entries=next.getConfigurationSection("habitats");
        if(entries==null||entries.getKeys(false).isEmpty()) throw new IllegalArgumentException("habitats 누락");
        var parsed=new LinkedHashMap<String,Habitat>(); var warnings=new ArrayList<String>();
        // 운영 서버의 habitats.yml에 rarity가 아직 없으면 플러그인에 들어 있는 기본 파일의 등급을 쓴다.
        var defaultRarity=new HashMap<String,String>();
        try(var in=getResource("habitats.yml")) {
            if(in!=null) {
                var bundled=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getConfigurationSection("habitats");
                if(bundled!=null) for(String id:bundled.getKeys(false)) { String r=bundled.getString(id+".rarity"); if(r!=null) defaultRarity.put(id,r); }
            }
        } catch(java.io.IOException ignored) {}
        for(String id:entries.getKeys(false)) parsed.put(id,Habitat.parse(id,entries.getConfigurationSection(id),warnings,defaultRarity));
        habitats=Collections.unmodifiableMap(parsed); biomeCompatibility=nextCompatibility;
        if(naturalGate!=null)naturalGate.invalidate();
        if(!warnings.isEmpty()) getLogger().warning("habitats.yml 값 "+warnings.size()+"건을 기본값으로 대체했습니다: "+String.join(", ",warnings.subList(0,Math.min(10,warnings.size())))+(warnings.size()>10?" …":""));
        structures.clear(); population.clear(); spawner.invalidate();
    }
    @EventHandler public void condition(MythicConditionLoadEvent e) {
        if(e.getConditionName().equalsIgnoreCase("cahabitat")) e.register(new HabitatCondition(this,e.getContainer().getLine(),e.getConfig().getString("id","")));
    }
    public Set<String> ids() { return habitats.keySet(); }
    static String seasonId(Object value,String id) {
        if(!(value instanceof String text)) throw new IllegalArgumentException("잘못된 계절: "+id);
        return switch(text.toLowerCase(Locale.ROOT).trim()) {
            case "봄","spring" -> "spring";
            case "여름","summer" -> "summer";
            case "가을","autumn","fall" -> "autumn";
            case "겨울","winter" -> "winter";
            default -> throw new IllegalArgumentException("계절은 봄·여름·가을·겨울 중 지정하세요: "+id+" / "+text);
        };
    }
    private String seasonCheck(Set<String> allowed,World world) {
        if(allowed.isEmpty()) return "OK";
        if(!Bukkit.getPluginManager().isPluginEnabled("MagicCodexBridge")) return "계절 정보 없음";
        var climate=Bukkit.getServicesManager().load(ClimateService.class);
        if(climate==null || !climate.enabledIn(world)) return "계절 정보 없음";
        return allowed.contains(climate.season().id()) ? "OK" : "출현 계절 아님";
    }
    private static final Set<Material> BAD_FLOORS=Set.of(Material.MAGMA_BLOCK,Material.CACTUS,Material.CAMPFIRE,Material.SOUL_CAMPFIRE);
    public String check(String id,Location l,boolean population) {
        return check(id,l,population,null,false);
    }
    /** 자체 스폰 루프 전용: 청크 개체 수는 호출 측에서 이미 확인함. */
    String checkChunkCounted(String id,Location l) {
        return check(id,l,true,null,true);
    }
    private String check(String id,Location l,boolean population,UUID pendingEntity) {
        return check(id,l,population,pendingEntity,false);
    }
    private String check(String id,Location l,boolean population,UUID pendingEntity,boolean chunkCounted) {
        if(!Bukkit.isPrimaryThread()) return "메인 스레드 외 호출";
        Habitat h=habitats.get(id);
        if(h==null) return "등록되지 않은 몹";
        World w=l.getWorld(); Settings cfg=settings;
        if(w==null||!cfg.enabled()||!cfg.worlds().contains(w.getName())) return "허용 월드 아님";
        int x=l.getBlockX(),y=l.getBlockY(),z=l.getBlockZ();
        if(!w.isChunkLoaded(x>>4,z>>4)) return "청크 미로드";
        if(!w.getWorldBorder().isInside(l)) return "월드 경계";
        if(y<h.minY()||y>h.maxY()) return "높이";
        String biome=w.getBiome(x,y,z).getKey().toString();
        if(!biomeCompatibility.matches(h.biomes(),biome)) return "바이옴";
        String season=seasonCheck(h.seasons(),w);
        if(!season.equals("OK")) return season;
        long t=w.getTime();
        if(h.time()==Habitat.DAY&&(t>12000) || h.time()==Habitat.NIGHT&&(t<13000||t>23000)) return "시간대";
        if(h.thunder()&&!w.isThundering()) return "뇌우 아님";
        if(h.fullMoon()&&Math.floorMod(w.getFullTime()/24000,8)!=0) return "보름달 아님";
        // 싼 검사(보호 구역·청크·개체 수, 개체 수는 캐시)를 블록 검사보다 먼저 한다:
        // 주변이 이미 꽉 찬 평상시에는 블록을 읽기 전에 바로 실패한다.
        for(Excluded a:cfg.excluded()) {
            if(w.getName().equals(a.world())&&Math.hypot(l.getX()-a.x(),l.getZ()-a.z())<a.radius()) return "보호 구역";
        }
        if(warded(w,l)) return "결계 향초";
        // Fresh loaded-chunk count: do not reuse a TTL population snapshot here.
        // 자체 스폰 루프는 시도 앞부분에서 이미 같은 청크를 셌으므로 건너뛴다(한 시도에 한 번만 셈).
        if(!chunkCounted&&chunkLimit.full(w.getChunkAt(x>>4,z>>4),pendingEntity,uuid ->
                MythicBukkit.inst().getMobManager().getActiveMob(uuid)
                    .map(m -> m.getType().getInternalName()).orElse(null))) return "청크 개체 제한";
        if(population) {
            double nearest=Double.POSITIVE_INFINITY;
            // 관전 모드만 제외(크리에이티브도 포함).
            for(Player p:w.getPlayers()) if(p.getGameMode()!=GameMode.SPECTATOR) nearest=Math.min(nearest,p.getLocation(scratch).distanceSquared(l));
            if(nearest<cfg.playerMinSquared()) return "플레이어 너무 가까움";
            if(nearest>cfg.playerMaxSquared()) return "활성 플레이어 없음";
            // 후보마다 주변 엔티티를 훑지 않고, 격자 칸별로 약 40틱 동안 공유하는 스캔 결과에서 센다.
            int[] counts=this.population.count(w,l,"CA_Wild_"+id,h.capRadius());
            if(counts[0]>=h.cap()) return "동종 개체 제한";
            if(counts[1]>=cfg.areaCap()) return "지역 개체 제한";
        }
        boolean water=h.water();
        int height=h.clearanceHeight(),r=h.clearanceRadius();
        if(!w.isChunkLoaded((x-r)>>4,(z-r)>>4)||!w.isChunkLoaded((x+r)>>4,(z+r)>>4)
           ||!w.isChunkLoaded((x-r)>>4,(z+r)>>4)||!w.isChunkLoaded((x+r)>>4,(z-r)>>4)) return "주변 청크 미로드";
        for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) for(int dy=0;dy<height;dy++) {
            Block b=w.getBlockAt(x+dx,y+dy,z+dz);
            if(water ? b.getType()!=Material.WATER : !b.isPassable()||b.isLiquid()) return "몸체 공간 부족";
        }
        if(!water) {
            Material floor=w.getBlockAt(x,y-1,z).getType();
            if(!floor.isSolid()||floor.name().contains("LEAVES")||BAD_FLOORS.contains(floor)) return "바닥";
            if(h.naturalGround()&&!naturalGround(floor)) return "자연 지면 아님";
            for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++)
                if(!w.getBlockAt(x+dx,y-1,z+dz).getType().isSolid()) return "발판 부족";
        }
        boolean cave=BiomeCompatibility.cave(biome);
        if(!cave && !water && w.getBlockAt(x,y,z).getLightFromSky()==0) return "지상 조건";
        if(cave&&w.getBlockAt(x,y,z).getLightFromBlocks()>h.maxBlockLight()) return "동굴 조명";
        if(h.nearWater()&&!waterNearby(w,x,y,z)) return "물가 아님";
        // 구조물 색인은 structures를 지정한 서식지만 건드린다(지연 계산, 로드된 청크만).
        if(!h.structures().isEmpty()&&structures.nearby(l,h.structures(),h.structureRadius()).isEmpty()) return "구조물 없음";
        return "OK";
    }
    /**
     * 마법 결계 향초 (ChAcademiaMagicMythic NovaLife3): 월드 메타데이터 chacademia_spawn_ward 의 Predicate&lt;Location&gt; 가
     * true 면 그 자리는 스폰 금지. 컴파일 의존 없음, 연동 쪽 오류는 무시(스폰 허용).
     */
    static final String SPAWN_WARD_KEY="chacademia_spawn_ward";
    @SuppressWarnings("unchecked")
    static boolean warded(World w,Location l) {
        if(!w.hasMetadata(SPAWN_WARD_KEY)) return false;
        for(org.bukkit.metadata.MetadataValue v:w.getMetadata(SPAWN_WARD_KEY)) {
            if(!(v.value() instanceof java.util.function.Predicate<?> p)) continue;
            try { if(((java.util.function.Predicate<Location>)p).test(l)) return true; } catch(RuntimeException ignored) {}
        }
        return false;
    }
    static boolean naturalGround(Material m) {
        String n=m.name();return n.contains("DIRT")||n.contains("GRASS")||n.contains("SAND")||n.contains("STONE")||n.contains("GRAVEL")||n.contains("SNOW")||n.contains("ICE")||n.contains("MOSS")||n.contains("TERRACOTTA")||n.contains("PODZOL")||n.contains("MYCELIUM")||n.contains("CLAY")||n.contains("MUD")||n.contains("CALCITE")||n.contains("TUFF")||n.contains("BASALT")||n.contains("DEEPSLATE");
    }
    boolean waterNearby(World w,int x,int y,int z) {
        for(int dx=-5;dx<=5;dx++) for(int dz=-5;dz<=5;dz++) {
            if(!w.isChunkLoaded((x+dx)>>4,(z+dz)>>4)) continue;
            for(int dy=-2;dy<=1;dy++) if(w.getBlockAt(x+dx,y+dy,z+dz).getType()==Material.WATER) return true;
        }
        return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(MythicMobSpawnEvent e) {
        if(e.getSpawnReason()!=SpawnReason.NATURAL||!e.getMobType().getInternalName().startsWith("CA_Wild_")) return;
        String type=e.getMobType().getInternalName();
        String id=type.substring(8).replaceFirst("_shiny$","");
        if(!habitats.containsKey(id)) return;
        // 기존 지역·종별·플레이어 거리 제한은 여기서 다시 보지 않는다(설계 의도):
        // 우두머리는 WildSpawner의 check(...,true)를(예전엔 RandomSpawn cahabitat), 무리는 group()의 check(...,true)를 이미 통과했고,
        // 이 시점에는 방금 만들어진 자기 자신이 세어져 제한 직전 스폰이 스스로 취소될 수 있다.
        if(!check(id,e.getLocation(),false,e.getEntity().getUniqueId()).equals("OK")) { e.setCancelled();return; }
        e.getEntity().getPersistentDataContainer().set(naturalKey,PersistentDataType.LONG,System.currentTimeMillis());
        // 같은 틱·같은 스캔 주기 안의 다음 후보가 이 개체를 세도록 살아 있는 스캔 결과에 바로 더한다.
        Location at=e.getLocation(); if(at.getWorld()!=null) population.noteSpawn(at.getWorld(),type,at);
        double scale=scaleFor(type); Entity spawnedEntity=e.getEntity();
        if(Math.abs(scale-1)>1e-3) Bukkit.getScheduler().runTaskLater(this,()->{ if(spawnedEntity.isValid()) MobScale.apply(spawnedEntity,scale); },2);
        if(groupSpawning) return;
        Entity leader=e.getEntity();
        Bukkit.getScheduler().runTask(this,()-> {
            if(!leader.isValid()||leader.isDead())return;
            naturalSpawned++;
            long now=System.currentTimeMillis();
            if(naturalSpawned<=3||now-lastNaturalSpawnLog>=60000){
                lastNaturalSpawnLog=now;var spawnedAt=leader.getLocation();
                getLogger().info("Natural wildlife confirmed total="+naturalSpawned+" id="+type+" uuid="+leader.getUniqueId()+" world="+spawnedAt.getWorld().getName()+" x="+spawnedAt.getBlockX()+" y="+spawnedAt.getBlockY()+" z="+spawnedAt.getBlockZ()+" biome="+spawnedAt.getWorld().getBiome(spawnedAt).getKey());
            }
            group(id,leader.getLocation());
        });
    }
    public int group(String id,Location origin) {
        Habitat h=habitats.get(id); World w=origin.getWorld();
        if(h==null||w==null) return 0;
        var random=ThreadLocalRandom.current();
        int count=random.nextInt(h.groupMin(),h.groupMax()+1),spawned=0;
        groupSpawning=true;
        try {
            for(int i=1;i<count;i++) for(int attempt=0;attempt<8;attempt++) {
                Location l=origin.clone().add(random.nextInt(-6,7)+.5,0,random.nextInt(-6,7)+.5);
                int x=l.getBlockX(),y=l.getBlockY(),z=l.getBlockZ();
                if(!w.isChunkLoaded(x>>4,z>>4)) continue;
                if(h.water()) l.add(0,random.nextInt(-1,2),0);
                else {
                    boolean found=false;
                    for(int dy=3;dy>=-3;dy--) if(w.getBlockAt(x,y+dy-1,z).getType().isSolid()&&w.getBlockAt(x,y+dy,z).isPassable()) {l.add(0,dy,0);found=true;break;}
                    if(!found) continue;
                }
                if(!check(id,l,true).equals("OK")) continue;
                boolean shiny=random.nextInt(settings.shinyDenominator())==0;
                var type=MythicBukkit.inst().getMobManager().getMythicMob("CA_Wild_"+id+(shiny?"_shiny":""));
                if(type.isPresent()) {var mob=type.get().spawn(BukkitAdapter.adapt(l),1,SpawnReason.NATURAL);if(mob!=null)spawned++;}
                break;
            }
        } finally {groupSpawning=false;}
        return spawned;
    }
    /** 10초마다: 60초 넘게 근처(128블록)에 플레이어가 없는 자연 스폰 개체를 지운다. 한 개체의 오류가 전체 정리를 멈추지 않는다. */
    void cleanup() {
        long now=System.currentTimeMillis(); int failures=0; Throwable firstFailure=null;
        // 월드별 플레이어 좌표를 이번 정리에서 한 번만 모은다.
        Map<World,double[]> players=new HashMap<>();
        for(var mob:new ArrayList<>(MythicBukkit.inst().getMobManager().getActiveMobs())) {
            try {
                if(mob==null||mob.getEntity()==null) continue;
                Entity e=mob.getEntity().getBukkitEntity();
                if(e==null||!e.isValid()) continue;
                Long born=e.getPersistentDataContainer().get(naturalKey,PersistentDataType.LONG);
                if(born==null||now-born<60000||e.getScoreboardTags().contains("chacademia_pet")) continue;
                World w=e.getWorld();
                double[] at=players.get(w);
                if(at==null) {
                    var list=w.getPlayers(); at=new double[list.size()*3]; int n=0;
                    for(Player p:list) { p.getLocation(scratch); at[n++]=scratch.getX(); at[n++]=scratch.getY(); at[n++]=scratch.getZ(); }
                    players.put(w,at);
                }
                e.getLocation(scratch); double x=scratch.getX(),y=scratch.getY(),z=scratch.getZ(); boolean near=false;
                for(int n=0;n<at.length;n+=3) { double dx=at[n]-x,dy=at[n+1]-y,dz=at[n+2]-z; if(dx*dx+dy*dy+dz*dz<128*128) { near=true; break; } }
                if(!near) e.remove();
                else { double scale=scaleFor(mob.getType().getInternalName()); if(Math.abs(scale-1)>1e-3) MobScale.apply(e,scale); }
            } catch(RuntimeException|LinkageError error) { failures++; if(firstFailure==null) firstFailure=error; }
        }
        if(failures>0&&now-lastCleanupLog>=60000) {
            lastCleanupLog=now;
            getLogger().log(java.util.logging.Level.WARNING,"야생 개체 정리 중 "+failures+"건 실패(나머지는 계속 처리, 로그는 1분에 한 번)",firstFailure);
        }
    }
    boolean naturalCheck(String id,io.lumine.mythic.api.adapters.AbstractLocation location){
        var gate=naturalGate;
        return gate!=null&&gate.check(()->{
            // Mythic 5.9 evaluates RandomSpawner conditions asynchronously even with SYNC_ONLY.
            // Adaptation and every Bukkit/world access occur only inside this main-thread action.
            String result=check(id,BukkitAdapter.adapt(location),true);
            naturalChecks.merge(result,1L,Long::sum);
            return result.equals("OK");
        });
    }
    List<String> globalDiagnostics(){
        var out=new ArrayList<String>(spawner.diagnostics());
        out.add(combat.diagnostic());
        out.add(" 자연 스폰 확정(무리 우두머리)="+naturalSpawned);
        if(!naturalChecks.isEmpty()) out.add(" (구) 미몹 랜덤스폰 조건 호출="+naturalChecks+" ← 0이 아니면 RandomSpawns 파일이 아직 남아 있음");
        return out;
    }
    @Override public void onDisable(){combat.restoreAll();spawner.stop();if(naturalGate!=null)naturalGate.close();crops.close();}
    // ── 자체 스폰 루프(WildSpawner)용 ──
    Set<String> spawnWorlds(){return settings.enabled()?settings.worlds():Set.of();}
    double playerMinDistance(){return Math.sqrt(settings.playerMinSquared());}
    double playerMaxDistance(){return Math.sqrt(settings.playerMaxSquared());}
    int shinyDenominator(){return settings.shinyDenominator();}
    Collection<Habitat> habitatList(){return habitats.values();}
    boolean biomeMatches(Habitat h,String biome){return biomeCompatibility.matches(h.biomes(),biome);}
    boolean chunkFull(Chunk chunk){
        return chunkLimit.full(chunk,null,uuid->MythicBukkit.inst().getMobManager().getActiveMob(uuid).map(m->m.getType().getInternalName()).orElse(null));
    }
    /** check()와 같은 시간대·계절 규칙. 추첨 전에 걸러서 지금 나올 수 없는 종이 확률을 먹지 않게 한다. */
    boolean timeAndSeasonOk(Habitat h,World w){
        long t=w.getTime();
        if(h.time()==Habitat.DAY&&(t>12000) || h.time()==Habitat.NIGHT&&(t<13000||t>23000)) return false;
        if(h.thunder()&&!w.isThundering()) return false;
        if(h.fullMoon()&&Math.floorMod(w.getFullTime()/24000,8)!=0) return false;
        return seasonCheck(h.seasons(),w).equals("OK");
    }
    /** 확률 명령용: 이 자리 기준 청크·지역 야생 몹 수. */
    String crowdLine(Location l){
        World w=l.getWorld();
        int chunk=chunkLimit.count(w.getChunkAt(l.getBlockX()>>4,l.getBlockZ()>>4),null,uuid->MythicBukkit.inst().getMobManager().getActiveMob(uuid).map(m->m.getType().getInternalName()).orElse(null));
        int area=population.count(w,l,"",0)[1];
        return " 이 청크 야생 몹 "+chunk+"/"+chunkLimit.cap()+" · 주변 지역 "+area+"/"+settings.areaCap()
            +(chunk>=chunkLimit.cap()||area>=settings.areaCap()?" (꽉 차서 지금은 새로 안 나옴)":"");
    }
    List<String> chanceAt(Player p){return spawner.chanceAt(p);}
    /** 주변(수평 radius) 야생 몹의 묶음(family) 목록. 개체 수 캐시를 재사용한다. */
    Set<String> familiesNear(Location l,double radius){
        if(radius<=0||l.getWorld()==null) return Set.of();
        var out=new HashSet<String>();
        for(String type:population.typesNear(l.getWorld(),l,radius)){
            Habitat h=habitats.get(type.substring(8).replaceFirst("_shiny$",""));
            if(h!=null) out.add(h.family());
        }
        return out;
    }
    String displayName(String id){Habitat h=habitats.get(id);return h==null||h.name()==null||h.name().isBlank()?id:h.name();}
    void warnOnce(String message,Throwable error){
        long now=System.currentTimeMillis();
        if(now-lastSpawnerWarn<60000)return;
        lastSpawnerWarn=now;getLogger().log(java.util.logging.Level.WARNING,message+" (로그는 1분에 한 번)",error);
    }
    /** Read-only, bounded sampling of already loaded chunks. Never teleports or spawns. */
    List<String> diagnose(Player player){
        var out=new ArrayList<String>();
        var at=player.getLocation();var world=at.getWorld();var biome=world.getBiome(at).getKey().toString();
        out.add(" 위치 "+world.getName()+" "+at.getBlockX()+" "+at.getBlockY()+" "+at.getBlockZ()+" · 바이옴 "+biome+" · 모드 "+player.getGameMode()+" · 시간 "+world.getTime());
        out.add(" doMobSpawning="+world.getGameRuleValue(GameRule.DO_MOB_SPAWNING)+" · 청크 제한 "+chunkLimit.cap()+" · 지역 제한 "+settings.areaCap());
        out.addAll(globalDiagnostics());
        var outcomes=new TreeMap<String,Integer>();var eligible=new TreeSet<String>();int checked=0;
        for(int step=0;step<8;step++){
            double angle=step*Math.PI/4;int x=at.getBlockX()+(int)Math.round(Math.cos(angle)*48),z=at.getBlockZ()+(int)Math.round(Math.sin(angle)*48);
            if(!world.isChunkLoaded(x>>4,z>>4))continue;
            int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
            var sample=new Location(world,x+.5,y,z+.5);String actual=world.getBiome(sample).getKey().toString();int species=0;
            for(var row:habitats.entrySet()){
                if(!biomeCompatibility.matches(row.getValue().biomes(),actual))continue;
                if(species++>=16)break;
                String result=check(row.getKey(),sample,true);outcomes.merge(result,1,Integer::sum);checked++;
                if(result.equals("OK"))eligible.add(row.getKey());
            }
        }
        out.add(" 주변 48칸 표본 "+checked+"회 · 결과 "+outcomes+" · 나올 수 있는 몹 "+eligible);
        return out;
    }
    /** /서버관리 새로고침 야생: 실패하면 예외(이전 설정 유지). */
    void reloadAll(){reloadConfig();reloadSettings();reloadHabitats();crops.reload();spawner.apply(spawnerSettings);combat.reload(getConfig().getConfigurationSection("combat"));}
    String statusLine(){return "야생몹 "+getDescription().getVersion()+" · "+(settings.enabled()?"켜짐":"꺼짐")+" · 자체 스폰 "+(spawnerSettings.enabled()?"켜짐":"꺼짐")+" · 청크 제한 "+chunkLimit.cap()+" · 지역 제한 "+settings.areaCap()+" · 서식지 "+habitats.size()+"종";}
    StructureIndex structureIndex(){return structures;}
}
