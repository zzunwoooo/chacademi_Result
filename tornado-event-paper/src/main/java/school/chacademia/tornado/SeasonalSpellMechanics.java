package school.chacademia.tornado;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.skills.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.paper.ClimateService;

/** Explicit spell-hit hooks: ordinary sword hits are never guessed to be elemental magic. */
final class SeasonalSpellMechanics implements Listener,AutoCloseable {
    private final TornadoPlugin plugin;
    private final NamespacedKey journal;
    private final Map<Block,Long> ice=new HashMap<>();
    private final Map<String,Long> effects=new HashMap<>();
    private final BukkitTask cleanup;
    private long ticks;
    SeasonalSpellMechanics(TornadoPlugin plugin){
        this.plugin=plugin;journal=new NamespacedKey(plugin,"temporary_season_ice");
        for(World w:Bukkit.getWorlds())for(Chunk chunk:w.getLoadedChunks())restoreChunk(chunk);
        cleanup=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            ticks+=10;effects.values().removeIf(until->until<=ticks);
            var iterator=ice.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(entry.getValue()<=ticks){restore(entry.getKey());iterator.remove();}}
        },10,10);
    }
    @EventHandler public void load(MythicMechanicLoadEvent event){
        String name=event.getMechanicName();
        if(!name.equalsIgnoreCase("chaclimateimpact")&&!name.equalsIgnoreCase("chaclimatedamage"))return;
        String element=event.getConfig().getString("element","water").toLowerCase(Locale.ROOT);
        double amount=event.getConfig().getDouble("amount",0);
        if(!Double.isFinite(amount)||amount<0||amount>1000000)return;
        event.register(new Hit(element,name.equalsIgnoreCase("chaclimatedamage")?amount:0));
    }
    private final class Hit implements ITargetedEntitySkill,ITargetedLocationSkill {
        private final String element;private final double amount;
        Hit(String element,double amount){this.element=element;this.amount=amount;}
        public ThreadSafetyLevel getThreadSafetyLevel(){return ThreadSafetyLevel.SYNC_ONLY;}
        public SkillResult castAtEntity(SkillMetadata data,AbstractEntity abstractTarget){
            Entity entity=abstractTarget.getBukkitEntity();if(!(entity instanceof LivingEntity living)||!living.isValid()||living.isDead())return SkillResult.INVALID_TARGET;
            var climate=plugin.climate();Entity source=data.getCaster().getEntity().getBukkitEntity();
            double multiplier=climate!=null&&climate.enabledIn(entity.getWorld())?climate.powerMultiplier(source,element):1;
            if(amount>0)living.damage(amount*multiplier,source);
            impact(entity.getLocation(),element,source);return SkillResult.SUCCESS;
        }
        public SkillResult castAtLocation(SkillMetadata data,AbstractLocation location){
            if(amount>0)return SkillResult.INVALID_TARGET;
            impact(BukkitAdapter.adapt(location),element,data.getCaster().getEntity().getBukkitEntity());return SkillResult.SUCCESS;
        }
    }
    private void impact(Location at,String element,Entity source){
        var climate=plugin.climate();if(!element.equals("water")||climate==null||!climate.enabledIn(at.getWorld()))return;
        if(climate.season()!=ClimateService.Season.SUMMER&&climate.season()!=ClimateService.Season.WINTER)return;
        String cell=at.getWorld().getUID()+":"+(at.getBlockX()>>2)+":"+(at.getBlockY()>>2)+":"+(at.getBlockZ()>>2);
        if(effects.getOrDefault(cell,0L)>ticks||effects.size()>=256)return;effects.put(cell,ticks+20);
        if(climate.season()==ClimateService.Season.SUMMER&&plugin.getConfig().getBoolean("seasonal-spells.steam",true)){
            at.getWorld().spawnParticle(Particle.CLOUD,at.clone().add(0,0.7,0),12,0.4,0.4,0.4,0.035);
            at.getWorld().playSound(at,Sound.BLOCK_FIRE_EXTINGUISH,0.35f,1.4f);
        }else if(climate.season()==ClimateService.Season.WINTER&&plugin.getConfig().getBoolean("seasonal-spells.winter-ice",true))freeze(at,source);
    }
    private void freeze(Location at,Entity source){
        int limit=Math.clamp(plugin.getConfig().getInt("seasonal-spells.max-ice-blocks",64),1,256),added=0;
        for(int dy=0;dy>=-2;dy--)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(ice.size()>=limit||added>=5)return;
            int x=at.getBlockX()+dx,z=at.getBlockZ()+dz,y=at.getBlockY()+dy;
            if(y<at.getWorld().getMinHeight()||y>=at.getWorld().getMaxHeight()-1||!at.getWorld().isChunkLoaded(x>>4,z>>4))continue;
            Block b=at.getWorld().getBlockAt(x,y,z);
            if(b.getType()!=Material.WATER||!(b.getBlockData() instanceof Levelled level)||level.getLevel()!=0||!b.getRelative(0,1,0).getType().isAir()||!plugin.isUnprotected(b))continue;
            var event=new EntityChangeBlockEvent(source,b,Material.FROSTED_ICE.createBlockData());Bukkit.getPluginManager().callEvent(event);if(event.isCancelled())continue;
            var chunk=b.getChunk();long[] old=chunk.getPersistentDataContainer().getOrDefault(journal,PersistentDataType.LONG_ARRAY,new long[0]);
            long[] next=Arrays.copyOf(old,old.length+1);next[old.length]=packed(b);chunk.getPersistentDataContainer().set(journal,PersistentDataType.LONG_ARRAY,next);
            b.setType(Material.FROSTED_ICE,false);ice.put(b,ticks+Math.clamp(plugin.getConfig().getInt("seasonal-spells.ice-duration-ticks",160),20,1200));added++;
        }
    }
    private long packed(Block b){return ((long)(b.getY()-b.getWorld().getMinHeight())<<8)|((b.getZ()&15)<<4)|(b.getX()&15);}
    private void restore(Block block){
        if(!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4))return;
        if(block.getType()==Material.FROSTED_ICE)block.setType(Material.WATER,false);
        var pdc=block.getChunk().getPersistentDataContainer();long[] values=pdc.getOrDefault(journal,PersistentDataType.LONG_ARRAY,new long[0]);
        long[] remaining=Arrays.stream(values).filter(v->v!=packed(block)).toArray();if(remaining.length==0)pdc.remove(journal);else pdc.set(journal,PersistentDataType.LONG_ARRAY,remaining);
    }
    private void restoreChunk(Chunk chunk){
        var pdc=chunk.getPersistentDataContainer();long[] values=pdc.get(journal,PersistentDataType.LONG_ARRAY);if(values==null)return;
        for(long value:values){int y=(int)(value>>8)+chunk.getWorld().getMinHeight();if(y<chunk.getWorld().getMinHeight()||y>=chunk.getWorld().getMaxHeight())continue;
            Block b=chunk.getBlock((int)(value&15),y,(int)((value>>4)&15));if(b.getType()==Material.FROSTED_ICE)b.setType(Material.WATER,false);ice.remove(b);
        }pdc.remove(journal);
    }
    @EventHandler public void loaded(ChunkLoadEvent event){restoreChunk(event.getChunk());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unloading(ChunkUnloadEvent event){restoreChunk(event.getChunk());}
    public void close(){cleanup.cancel();for(Block block:List.copyOf(ice.keySet()))restore(block);ice.clear();effects.clear();}
}
