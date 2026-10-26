package school.chacademia.tornado;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobDespawnEvent;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.bukkit.MythicBukkit;
import school.magiccodex.paper.ClimateService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class TornadoPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Tornado> active = new HashMap<>();
    private final Set<Material> breakable = new HashSet<>();
    private BukkitTask task;
    private String mobId;
    private Set<String> allowedWorlds;
    private int maxActive;
    private SpawnGuard spawnGuard;
    private BukkitTask randomTask;
    private SeasonalSpellMechanics seasonalSkills;
    private long nextRandomSpawn;
    private final List<RecentSpawn> recentSpawns=new ArrayList<>();
    private record RecentSpawn(Location location,long expires){}
    private static SpawnGuard.Spot spot(Location l){return new SpawnGuard.Spot(l.getWorld().getUID(),l.getX(),l.getZ());}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        FileConfiguration c = getConfig();
        mobId = c.getString("mythic-mob-id", "ChAcademiaTornado");
        allowedWorlds = new HashSet<>(c.getStringList("allowed-worlds"));
        maxActive = Math.max(1, c.getInt("max-active", 2));
        spawnGuard=new SpawnGuard(maxActive,Math.max(40,c.getDouble("random-spawn.duplicate-radius",192)));
        for (String name : c.getStringList("blocks.allowed-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isBlock() && !material.isAir()) breakable.add(material);
            else getLogger().warning("Ignoring unknown block material: " + name);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        nextRandomSpawn=System.currentTimeMillis()+Math.max(0,c.getLong("random-spawn.global-cooldown-seconds",600))*1000;
        long interval=Math.clamp(c.getLong("random-spawn.check-interval-seconds",60),5,3600)*20;
        randomTask=Bukkit.getScheduler().runTaskTimer(this,this::tryRandomSpawn,interval,interval);
        if(Bukkit.getPluginManager().isPluginEnabled("MagicCodexBridge")){
            seasonalSkills=new SeasonalSpellMechanics(this);Bukkit.getPluginManager().registerEvents(seasonalSkills,this);
        }
        if (ChacaLink.ready()) {
            try { registerCommands(); }
            catch (RuntimeException e) { getLogger().log(java.util.logging.Level.WARNING, "명령어 허브에 /월드관리 토네이도 갈래를 붙이지 못했습니다", e); }
        } else getLogger().warning("MagicCodexBridge 명령어 허브가 없어 /월드관리 토네이도 명령을 등록하지 않았습니다 (회오리는 그대로 동작).");
        getLogger().info("Tornado controller ready for MythicMob " + mobId);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if(randomTask!=null)randomTask.cancel();
        if(seasonalSkills!=null)seasonalSkills.close();
        for (Tornado tornado : List.copyOf(active.values())) tornado.stop(true);
        active.clear();
        if(spawnGuard!=null)spawnGuard.clear();recentSpawns.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(MythicMobSpawnEvent event) {
        if (!mobId.equals(event.getMobType().getInternalName())) return;
        Entity entity = event.getEntity();
        if(!allowedWorlds.contains(entity.getWorld().getName())||!spawnGuard.reserve(entity.getUniqueId(),spot(entity.getLocation()))){event.setCancelled(true);return;}
        Bukkit.getScheduler().runTask(this, () -> {
            if (!(entity instanceof ArmorStand stand) || !stand.isValid()||event.isCancelled()){spawnGuard.remove(entity.getUniqueId());return;}
            stand.setPersistent(false);
            active.put(stand.getUniqueId(), new Tornado(stand));
        });
    }

    @EventHandler
    public void onDespawn(MythicMobDespawnEvent event) {
        spawnGuard.remove(event.getEntity().getUniqueId());
        Tornado tornado = active.remove(event.getEntity().getUniqueId());
        if (tornado != null) tornado.stop(false);
    }

    @EventHandler
    public void onDeath(MythicMobDeathEvent event) {
        spawnGuard.remove(event.getEntity().getUniqueId());
        Tornado tornado = active.remove(event.getEntity().getUniqueId());
        if (tornado != null) tornado.stop(false);
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Tornado>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Tornado tornado = iterator.next().getValue();
            if (!tornado.tick()) {
                iterator.remove();
                spawnGuard.remove(tornado.stand.getUniqueId());
                tornado.stop(true);
            }
        }
    }

    /** /월드관리 토네이도 &lt;보기|멈추기&gt; (예전 /chatornado status|stop). */
    private void registerCommands() {
        ChacaLink.branch(this, ChacaLink.월드관리, io.papermc.paper.command.brigadier.Commands.literal("토네이도")
            .then(io.papermc.paper.command.brigadier.Commands.literal("보기").executes(ctx -> {
                var climate = climate();
                ChacaLink.lines(ctx.getSource().getSender(), List.of("── 토네이도 ──",
                    " 활성 회오리: " + active.size() + "/" + maxActive,
                    " 계절: " + (climate == null ? "연동 없음" : climate.season().label) + " · 가을 자동 소환 " + (getConfig().getBoolean("random-spawn.enabled", true) ? "켜짐" : "꺼짐")));
                return 1;
            }))
            .then(io.papermc.paper.command.brigadier.Commands.literal("멈추기").executes(ctx -> {
                var sender = ctx.getSource().getSender();
                Tornado chosen = null;
                double nearest = Double.MAX_VALUE;
                for (Tornado tornado : active.values()) {
                    if (sender instanceof Player p && !p.getWorld().equals(tornado.world)) continue;
                    double distance = sender instanceof Player player ? player.getLocation().distanceSquared(tornado.stand.getLocation()) : 0;
                    if (distance < nearest) { nearest = distance; chosen = tornado; }
                }
                if (chosen == null) throw ChacaLink.error(sender instanceof Player ? "이 월드에 활성 회오리가 없어요" : "활성 회오리가 없어요");
                active.remove(chosen.stand.getUniqueId());
                spawnGuard.remove(chosen.stand.getUniqueId());
                chosen.stop(true);
                ChacaLink.ok(sender, "가장 가까운 회오리를 멈췄어요 (남은 " + active.size() + "개)");
                return 1;
            })),
            "회오리 현황 보기·가장 가까운 회오리 멈추기", "토네이도 보기");
    }

    boolean isUnprotected(Block block) {
        try {
            var regions = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .createQuery().getApplicableRegions(BukkitAdapter.adapt(block.getLocation()));
            if (regions.isVirtual()) return false;
            if(!regions.testState((com.sk89q.worldguard.protection.association.RegionAssociable)null,com.sk89q.worldguard.protection.flags.Flags.BLOCK_BREAK,com.sk89q.worldguard.protection.flags.Flags.BUILD))return false;
            for (ProtectedRegion region : regions) {
                if (!"__global__".equals(region.getId())) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            getLogger().warning("WorldGuard query failed; block destruction skipped: " + exception.getMessage());
            return false;
        }
    }

    /**
     * 마법 381 계절 예보 (MagicCodexBridge LifeSpells 가 리플렉션으로 부름, 메인 스레드): 이 서버의 회오리 이벤트 안내 한 줄.
     * 회오리는 가을에만, 바깥에 있는 플레이어 근처에 무작위로 생긴다 (tryRandomSpawn): 정해진 시각은 없고 쿨타임만 알려 준다.
     */
    public String seasonForecast(){
        var c=getConfig();
        String now=!active.isEmpty()?"지금 회오리 "+active.size()+"개가 돌아다니고 있어요 · ":"";
        if(!c.getBoolean("random-spawn.enabled",true))return now+"이 서버에는 회오리가 저절로 생기지 않아요";
        var climate=climate();
        if(climate==null)return now+"계절 정보를 읽지 못했어요";
        if(climate.season()!=ClimateService.Season.AUTUMN)return now+"회오리는 가을에만 나타나요";
        if(spawnGuard.full())return now+"회오리가 많아서 당분간 새로 생기지 않아요";
        long left=nextRandomSpawn-System.currentTimeMillis();
        return now+(left>0?"약 "+Math.max(1,(left+59_999)/60_000)+"분 뒤부터 바깥에서 회오리가 나타날 수 있어요":"지금 바깥에 있으면 언제든 회오리가 나타날 수 있어요");
    }

    ClimateService climate(){return Bukkit.getPluginManager().isPluginEnabled("MagicCodexBridge")?Bukkit.getServicesManager().load(ClimateService.class):null;}
    private void tryRandomSpawn(){
        var c=getConfig();if(!c.getBoolean("random-spawn.enabled",true)||spawnGuard.full())return;
        var climate=climate();if(climate==null||climate.season()!=ClimateService.Season.AUTUMN)return;
        long now=System.currentTimeMillis();if(now<nextRandomSpawn)return;
        if(ThreadLocalRandom.current().nextDouble()>=Math.clamp(c.getDouble("random-spawn.chance-per-check",0.06),0,1))return;
        recentSpawns.removeIf(r->r.expires<=now);
        // One draw per server check, not one draw per player: crowds do not multiply the probability.
        Player selected=null;int eligible=0;
        for(Player p:Bukkit.getOnlinePlayers()){
            if(p.isDead()||(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)||!allowedWorlds.contains(p.getWorld().getName())||!climate.enabledIn(p.getWorld()))continue;
            var at=p.getLocation();
            if(spawnGuard.nearby(spot(at))||recentlySpawned(at)||!isUnprotected(at.getBlock()))continue;
            if(at.getY()<p.getWorld().getHighestBlockYAt(at.getBlockX(),at.getBlockZ(),HeightMap.MOTION_BLOCKING_NO_LEAVES)-4)continue;
            if(ThreadLocalRandom.current().nextInt(++eligible)==0)selected=p;
        }
        if(selected==null)return;
        Location spawn=findSpawn(selected);if(spawn==null)return;
        try{
            var result=MythicBukkit.inst().getMobManager().spawnMob(mobId,spawn,1);
            if(result==null||!spawnGuard.contains(result.getUniqueId()))return;
            nextRandomSpawn=now+Math.max(0,c.getLong("random-spawn.global-cooldown-seconds",600))*1000;
            recentSpawns.add(new RecentSpawn(spawn.clone(),now+Math.max(0,c.getLong("random-spawn.area-cooldown-seconds",1800))*1000));
            getLogger().info("가을 회오리 소환: "+spawn.getWorld().getName()+" "+spawn.getBlockX()+","+spawn.getBlockY()+","+spawn.getBlockZ());
        }catch(RuntimeException e){nextRandomSpawn=now+60000;getLogger().warning("회오리 소환 실패: "+e.getMessage());}
    }
    private boolean recentlySpawned(Location location){
        double radius=Math.max(40,getConfig().getDouble("random-spawn.duplicate-radius",192));
        return recentSpawns.stream().anyMatch(r->r.location.getWorld().equals(location.getWorld())&&Math.pow(r.location.getX()-location.getX(),2)+Math.pow(r.location.getZ()-location.getZ(),2)<=radius*radius);
    }
    private Location findSpawn(Player player){
        var c=getConfig();var random=ThreadLocalRandom.current();var at=player.getLocation();World world=player.getWorld();
        double min=Math.clamp(c.getDouble("random-spawn.minimum-distance",48),25,128),max=Math.clamp(c.getDouble("random-spawn.maximum-distance",80),min+1,192);
        for(int i=0;i<8;i++){
            double angle=random.nextDouble(Math.PI*2),radius=random.nextDouble(min,max);int x=(int)Math.floor(at.getX()+Math.cos(angle)*radius),z=(int)Math.floor(at.getZ()+Math.sin(angle)*radius);
            if(!world.isChunkLoaded(x>>4,z>>4))continue;
            Block ground=world.getHighestBlockAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Location spawn=ground.getLocation().add(0.5,1.0,0.5);
            if(!ground.getType().isSolid()||Math.abs(spawn.getY()-at.getY())>24||!world.getWorldBorder().isInside(spawn)||!isUnprotected(ground)||spawnGuard.nearby(spot(spawn))||recentlySpawned(spawn))continue;
            if(world.getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(spawn)<30*30))continue;
            return spawn;
        }return null;
    }

    private final class Tornado {
        private final ArmorStand stand;
        private final World world;
        private final Location origin;
        private final List<Debris> debris = new ArrayList<>();
        private final Set<UUID> affected = new HashSet<>();
        private final int duration;
        private final double speed;
        private final double roamRadius;
        private final int pullEvery;
        private final double pullRadius;
        private final double pullHeight;
        private final int breakEvery;
        private final int maxDestroyed;
        private final int maxDebris;
        private double heading;
        private double baseY;
        private int age;
        private int destroyed;

        Tornado(ArmorStand stand) {
            this.stand = stand;
            this.world = stand.getWorld();
            this.origin = stand.getLocation().clone();
            FileConfiguration c = getConfig();
            this.duration = Math.max(40, c.getInt("duration-ticks", 3600));
            this.speed = Math.max(0.01, c.getDouble("movement.blocks-per-second", 4.2)) / 20.0;
            this.roamRadius = Math.max(10, c.getDouble("movement.roam-radius", 90));
            this.pullEvery = Math.max(1, c.getInt("pull.every-ticks", 2));
            this.pullRadius = Math.max(1, c.getDouble("pull.radius", 20));
            this.pullHeight = Math.max(1, c.getDouble("pull.vertical-radius", 22));
            this.breakEvery = Math.max(1, c.getInt("blocks.every-ticks", 10));
            this.maxDestroyed = Math.max(0, c.getInt("blocks.max-destroyed-per-tornado", 240));
            this.maxDebris = Math.max(0, c.getInt("debris.max-active-per-tornado", 14));
            this.heading = ThreadLocalRandom.current().nextDouble(-Math.PI, Math.PI);
            this.baseY = origin.getY();
        }

        boolean tick() {
            if (!stand.isValid() || ++age >= duration) return false;
            move();
            if (age % pullEvery == 0) pullNearby();
            if (age % breakEvery == 0 && getConfig().getBoolean("blocks.enabled", true)) breakTerrain();
            if (age % 2 == 0) tickDebris();
            return true;
        }

        void move() {
            FileConfiguration c = getConfig();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (age % Math.max(1, c.getInt("movement.turn-every-ticks", 45)) == 0) {
                heading += random.nextDouble(-1, 1) * c.getDouble("movement.turn-radians", 0.45);
            }
            Location current = stand.getLocation();
            double homeX = origin.getX() - current.getX();
            double homeZ = origin.getZ() - current.getZ();
            if (homeX * homeX + homeZ * homeZ > roamRadius * roamRadius) {
                double target = Math.atan2(homeZ, homeX);
                heading += Math.atan2(Math.sin(target - heading), Math.cos(target - heading)) * 0.07;
            }
            double x = current.getX() + Math.cos(heading) * speed;
            double z = current.getZ() + Math.sin(heading) * speed;
            int chunkX = ((int) Math.floor(x)) >> 4;
            int chunkZ = ((int) Math.floor(z)) >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                heading += Math.PI / 2;
                return;
            }
            if (age % Math.max(1, c.getInt("movement.ground-follow-ticks", 5)) == 0) {
                baseY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z), HeightMap.WORLD_SURFACE) + 0.15;
            }
            double y = current.getY() + Math.max(-0.25, Math.min(0.25, baseY - current.getY()));
            Location next = new Location(world, x, y, z, current.getYaw(), current.getPitch());
            if (!world.getWorldBorder().isInside(next)) { heading += Math.PI; return; }
            stand.teleport(next);
            spawnGuard.move(stand.getUniqueId(),spot(next));
        }

        void pullNearby() {
            FileConfiguration c = getConfig();
            Location center = stand.getLocation();
            double inward = c.getDouble("pull.inward", 0.66);
            double swirl = c.getDouble("pull.swirl", 0.34);
            double lift = c.getDouble("pull.lift", 0.38);
            double holdY = center.getY() + c.getDouble("pull.hold-height", 11);
            double maxSpeed = c.getDouble("pull.max-speed", 1.05);
            int limit = Math.max(1, c.getInt("pull.max-entities-per-scan", 48));
            int scanned = 0;
            for (Entity entity : world.getNearbyEntities(center, pullRadius, pullHeight, pullRadius)) {
                if (!(entity instanceof LivingEntity) && !(entity instanceof Item)) continue;
                if (entity instanceof ArmorStand) continue;
                if (entity instanceof Player player && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) continue;
                if (scanned++ >= limit) break;
                Location loc = entity.getLocation();
                double dx = center.getX() - loc.getX();
                double dz = center.getZ() - loc.getZ();
                double distance = Math.hypot(dx, dz);
                if (distance > pullRadius || Math.abs(loc.getY() - center.getY()) > pullHeight) continue;
                double nx = distance < 0.1 ? 0 : dx / distance;
                double nz = distance < 0.1 ? 0 : dz / distance;
                double strength = inward * (0.75 + 0.25 * distance / pullRadius) * Math.min(1, distance / 3.0);
                double y = loc.getY() < holdY - 2 ? lift
                        : Math.max(-0.22, Math.min(lift, (holdY - loc.getY()) * 0.16));
                Vector velocity = new Vector(nx * strength - nz * swirl, y, nz * strength + nx * swirl);
                if (velocity.lengthSquared() > maxSpeed * maxSpeed) velocity.normalize().multiply(maxSpeed);
                entity.setVelocity(velocity);
                if (entity instanceof LivingEntity living) {
                    living.setFallDistance(0);
                    if (affected.add(living.getUniqueId()) || age % 40 == 0) {
                        living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 90, 0, true, false, false));
                    }
                }
            }
        }

        void breakTerrain() {
            if (destroyed >= maxDestroyed) return;
            FileConfiguration c = getConfig();
            int probes = Math.max(1, c.getInt("blocks.probes-per-pass", 3));
            double radius = Math.max(1, c.getDouble("blocks.radius", 6));
            Location center = stand.getLocation();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < probes && destroyed < maxDestroyed; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double r = random.nextDouble(1.5, radius);
                int x = (int) Math.floor(center.getX() + Math.cos(angle) * r);
                int z = (int) Math.floor(center.getZ() + Math.sin(angle) * r);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block block = world.getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                if (!breakable.contains(block.getType()) || block.getState() instanceof TileState) continue;
                if (Math.abs(block.getY() - center.getY()) > 5 || !isUnprotected(block)) continue;
                var data = block.getBlockData().clone();
                Location debrisStart = block.getLocation().add(0.5, 0.5, 0.5);
                block.setType(Material.AIR, false);
                destroyed++;
                world.spawnParticle(Particle.BLOCK, debrisStart, 5, 0.2, 0.2, 0.2, 0.05, data);
                if (debris.size() < maxDebris) spawnDebris(debrisStart, data);
                break;
            }
        }

        void spawnDebris(Location position, org.bukkit.block.data.BlockData data) {
            float scale = (float) Math.max(0.1, getConfig().getDouble("debris.scale", 0.45));
            BlockDisplay display = world.spawn(position, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setPersistent(false);
                d.setTeleportDuration(2);
                d.setTransformation(new Transformation(new Vector3f(-scale / 2, -scale / 2, -scale / 2),
                        new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            });
            debris.add(new Debris(display, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                    Math.max(10, getConfig().getInt("debris.duration-ticks", 55))));
        }

        void tickDebris() {
            Location center = stand.getLocation();
            Iterator<Debris> iterator = debris.iterator();
            while (iterator.hasNext()) {
                Debris bit = iterator.next();
                if (!bit.display.isValid() || bit.age >= bit.duration) {
                    bit.display.remove();
                    iterator.remove();
                    continue;
                }
                bit.age += 2;
                double progress = Math.min(1, (double) bit.age / bit.duration);
                double angle = bit.angle + bit.age * 0.20;
                double radius = 3.5 + progress * 2.5;
                Location to = center.clone().add(Math.cos(angle) * radius, 1.0 + progress * 10.0, Math.sin(angle) * radius);
                bit.display.teleport(to);
            }
        }

        void stop(boolean removeStand) {
            for (Debris bit : debris) if (bit.display.isValid()) bit.display.remove();
            debris.clear();
            affected.clear();
            if (removeStand && stand.isValid()) stand.remove();
        }
    }

    private static final class Debris {
        final BlockDisplay display;
        final double angle;
        final int duration;
        int age;

        Debris(BlockDisplay display, double angle, int duration) {
            this.display = display;
            this.angle = angle;
            this.duration = duration;
        }
    }
}
