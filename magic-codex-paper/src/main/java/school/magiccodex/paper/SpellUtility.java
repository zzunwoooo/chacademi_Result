package school.magiccodex.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

/** Bounded vanilla operations which MythicMobs cannot safely express as generic commands. */
final class SpellUtility implements AutoCloseable {
    private final MagicCodexBridge plugin;
    private List<FurnaceRecipe> recipes=List.of();
    private final Map<UUID,Pull> pulls=new HashMap<>();
    private final Map<Block,org.bukkit.block.data.BlockData> buttons=new HashMap<>();
    private org.bukkit.scheduler.BukkitTask pullTask;
    private record Pull(Player player,List<Item> items,long expires){}
    /** 생활 마법 374~386 중 Bridge 시스템 연동 9종 (2026-10-25). */
    private final LifeSpells life;
    SpellUtility(MagicCodexBridge plugin){this.plugin=plugin;life=new LifeSpells(plugin);}
    /** Before mana is charged (SpellRuntime.prepare): null = may cast, else the reason shown to the player (no mana, no cooldown). */
    String refusal(Player p,String id){return LifeSpells.IDS.contains(id)?life.refusal(p,id):null;}
    void reloadRecipes(){var list=new ArrayList<FurnaceRecipe>();Bukkit.recipeIterator().forEachRemaining(r->{if(r instanceof FurnaceRecipe f)list.add(f);});recipes=List.copyOf(list);}
    boolean apply(Player p,String id){if(!MagicProtectionLink.cast(p))return false;return switch(id){
        case "wind_basket"->gather(p);
        case "harvest_wind"->breakAround(p,p.getLocation().getBlock(),4,1,64,true);
        case "dust_duster"->breakAround(p,p.getLocation().getBlock(),3,2,3,false);
        case "leaf_cleanup"->{var b=p.getTargetBlockExact(12);yield b!=null&&breakLeaves(p,b);}
        case "gale_dash"->dash(p);
        case "wind_grooming"->shear(p);
        case "feather_rustle"->feather(p);
        case "hearth_touch"->cook(p,1);
        case "living_furnace"->cook(p,4);
        case "sponge_drying"->dry(p);
        case "boiling_armor"->repair(p);
        case "rekindle"->relight(p);
        case "wick_trimming"->extinguish(p);
        case "lava_suture"->lava(p);
        case "far_touch"->switchBlock(p);
        case "alchemy_preheat","water_glimpse","calm_ripple","treasure_scent","season_sense","pack_sort","heart_read","insight_flash","blessed_ribbon","scale_appraisal","enlighten_hint"->life.apply(p,id);
        default->false;
    };}
    private boolean gather(Player p){
        var items=p.getNearbyEntities(10,10,10).stream().filter(e->e instanceof Item).map(e->(Item)e)
            .filter(i->i.getLocation().distanceSquared(p.getLocation())<=100&&i.getPickupDelay()<=0&&(i.getOwner()==null||i.getOwner().equals(p.getUniqueId())))
            .sorted(Comparator.comparingDouble(i->i.getLocation().distanceSquared(p.getLocation()))).limit(64).toList();
        if(items.isEmpty())return false;pulls.put(p.getUniqueId(),new Pull(p,items,System.currentTimeMillis()+1800));
        if(pullTask==null)pullTask=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,2,2);return true;
    }
    private void tick(){
        long now=System.currentTimeMillis();var iterator=pulls.values().iterator();
        while(iterator.hasNext()){
            var pull=iterator.next();var p=pull.player();
            if(!MagicProtectionLink.cast(p)||now>pull.expires()||!p.isOnline()||p.isDead()){iterator.remove();continue;}
            for(Item item:pull.items())if(MagicProtectionLink.at(p,item.getLocation())&&item.isValid()&&item.getWorld().equals(p.getWorld())&&item.getPickupDelay()<=0&&(item.getOwner()==null||item.getOwner().equals(p.getUniqueId()))){
                Vector delta=p.getLocation().add(0,.35,0).toVector().subtract(item.getLocation().toVector());
                if(delta.lengthSquared()<=144&&delta.lengthSquared()>.08)item.setVelocity(delta.normalize().multiply(.65));
            }
        }
        if(pulls.isEmpty()&&pullTask!=null){pullTask.cancel();pullTask=null;}
    }
    private boolean dash(Player p){
        var input=p.getCurrentInput();int forward=(input.isForward()?1:0)-(input.isBackward()?1:0),side=(input.isRight()?1:0)-(input.isLeft()?1:0);
        double yaw=Math.toRadians(p.getLocation().getYaw());Vector ahead=new Vector(-Math.sin(yaw),0,Math.cos(yaw));
        Vector right=new Vector(-Math.cos(yaw),0,-Math.sin(yaw));Vector move=ahead.clone().multiply(forward).add(right.multiply(side));
        if(move.lengthSquared()<.1)move=ahead;
        move.normalize().multiply(1.15).setY(Math.max(.08,Math.min(.35,p.getVelocity().getY())));
        if(!MagicProtectionLink.at(p,p.getLocation().add(move)))return false;
        p.setVelocity(move);return true;
    }
    private boolean allowed(Player p,Block b){
        if(!MagicProtectionLink.at(p,b.getLocation()))return false;
        var original=b.getBlockData().clone();
        var event=new PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,p.getInventory().getItemInMainHand(),b,BlockFace.UP,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);return event.useInteractedBlock()!=Event.Result.DENY&&b.getBlockData().equals(original);
    }
    private boolean remove(Player p,Block b){
        if(!MagicProtectionLink.at(p,b.getLocation()))return false;
        Material original=b.getType();var event=new BlockBreakEvent(b,p);Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled()||b.getType()!=original)return false;
        var drops=event.isDropItems()?b.getDrops(p.getInventory().getItemInMainHand(),p):List.<ItemStack>of();
        b.setType(Material.AIR);for(ItemStack item:drops)b.getWorld().dropItemNaturally(b.getLocation().add(.5,.3,.5),item);
        return true;
    }
    private boolean breakAround(Player p,Block center,int radius,int vertical,int limit,boolean crops){
        int count=0;
        for(int y=-vertical;y<=vertical;y++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            Block b=center.getRelative(x,y,z);if(!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4))continue;
            if(crops){if(!Set.of(Material.WHEAT,Material.CARROTS,Material.POTATOES).contains(b.getType())||!(b.getBlockData() instanceof Ageable a)||a.getAge()!=a.getMaximumAge())continue;}
            else if(b.getType()!=Material.COBWEB)continue;
            if(remove(p,b)&&++count>=limit)return true;
        }
        return count>0;
    }
    private boolean breakLeaves(Player p,Block center){
        int count=0;for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
            Block b=center.getRelative(x,y,z);if(!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4)||!Tag.LEAVES.isTagged(b.getType()))continue;
            if(remove(p,b)&&++count>=32)return true;
        }return count>0;
    }
    private boolean shear(Player p){
        int count=0;for(Entity e:p.getNearbyEntities(6,3,6))if(MagicProtectionLink.touch(p,e)&&e instanceof Sheep sheep&&!sheep.isSheared()&&sheep.isAdult()){
            var drops=List.of(new ItemStack(Material.valueOf(sheep.getColor().name()+"_WOOL"),1+java.util.concurrent.ThreadLocalRandom.current().nextInt(3)));
            var event=new PlayerShearEntityEvent(p,sheep,new ItemStack(Material.SHEARS),EquipmentSlot.HAND,drops);Bukkit.getPluginManager().callEvent(event);
            if(event.isCancelled()||!sheep.isValid()||sheep.isSheared())continue;
            sheep.setSheared(true);for(ItemStack item:event.getDrops())sheep.getWorld().dropItemNaturally(sheep.getLocation(),item);
            if(++count==3)break;
        }return count>0;
    }
    private boolean feather(Player p){
        var ray=p.getWorld().rayTrace(p.getEyeLocation(),p.getEyeLocation().getDirection(),8,FluidCollisionMode.NEVER,true,.5,e->e instanceof Chicken c&&c.isAdult()&&MagicProtectionLink.touch(p,e));
        if(ray==null||!(ray.getHitEntity() instanceof Chicken chicken))return false;
        var event=new PlayerInteractEntityEvent(p,chicken,EquipmentSlot.HAND);Bukkit.getPluginManager().callEvent(event);if(event.isCancelled())return false;
        give(p,new ItemStack(Material.FEATHER));return true;
    }
    private void give(Player p,ItemStack item){for(ItemStack excess:p.getInventory().addItem(item).values())p.getWorld().dropItemNaturally(p.getLocation(),excess);}
    private boolean cook(Player p,int maximum){
        var hand=p.getInventory().getItemInMainHand();if(hand.getType().isAir()||hand.hasItemMeta())return false;
        for(var recipe:recipes)if(recipe.getInputChoice().test(hand)){
            int amount=Math.min(maximum,hand.getAmount());var output=recipe.getResult().clone();output.setAmount(output.getAmount()*amount);
            // Recipes are read-only; no XP or custom item identity is synthesized.
            hand.setAmount(hand.getAmount()-amount);p.getInventory().setItemInMainHand(hand.getAmount()==0?new ItemStack(Material.AIR):hand);give(p,output);return true;
        }return false;
    }
    private boolean dry(Player p){
        var hand=p.getInventory().getItemInMainHand();if(hand.getType()!=Material.WET_SPONGE||hand.hasItemMeta())return false;
        hand.setAmount(hand.getAmount()-1);p.getInventory().setItemInMainHand(hand.getAmount()==0?new ItemStack(Material.AIR):hand);give(p,new ItemStack(Material.SPONGE));return true;
    }
    private boolean repair(Player p){
        boolean changed=false;var armor=p.getInventory().getArmorContents();
        for(var item:armor)if(item!=null&&item.getItemMeta() instanceof Damageable meta&&meta.getDamage()>0){meta.setDamage(0);item.setItemMeta(meta);changed=true;}
        if(changed)p.getInventory().setArmorContents(armor);return changed;
    }
    private boolean relight(Player p){
        var b=p.getTargetBlockExact(12);if(b==null||!Tag.CAMPFIRES.isTagged(b.getType())&&!Tag.CANDLES.isTagged(b.getType())&&!Tag.CANDLE_CAKES.isTagged(b.getType()))return false;
        if(!(b.getBlockData() instanceof Lightable light)||light.isLit()||b.getBlockData() instanceof Waterlogged water&&water.isWaterlogged()||!allowed(p,b))return false;
        light.setLit(true);b.setBlockData(light);return true;
    }
    private boolean extinguish(Player p){
        var b=p.getTargetBlock(Set.of(Material.AIR,Material.CAVE_AIR,Material.VOID_AIR),12);if(b.getType()!=Material.FIRE&&b.getType()!=Material.SOUL_FIRE)return false;
        if(!allowed(p,b)||!canChange(p,b))return false;b.setType(Material.AIR);return true;
    }
    private boolean lava(Player p){
        var b=p.getTargetBlockExact(8,FluidCollisionMode.ALWAYS);if(b==null||b.getType()!=Material.LAVA||!allowed(p,b)||!canChange(p,b))return false;
        // Flowing lava is intentionally cooled too, as the description allows nearby lava without a source requirement.
        b.setType(Material.OBSIDIAN);return true;
    }
    private boolean switchBlock(Player p){
        var b=p.getTargetBlockExact(24);if(b==null||b.getType()!=Material.LEVER&&!Tag.BUTTONS.isTagged(b.getType()))return false;
        if(!(b.getBlockData() instanceof Powerable data)||!allowed(p,b))return false;
        if(b.getType()==Material.LEVER){data.setPowered(!data.isPowered());b.setBlockData(data);return true;}
        if(data.isPowered())return false;var original=b.getBlockData().clone();data.setPowered(true);b.setBlockData(data);buttons.put(b,original);
        var applied=data.clone();Bukkit.getScheduler().runTaskLater(plugin,()->{var restore=buttons.remove(b);if(restore!=null&&b.getBlockData().equals(applied))b.setBlockData(restore);},20);return true;
    }
    private boolean canChange(Player p,Block b){if(!MagicProtectionLink.at(p,b.getLocation()))return false;var before=b.getBlockData().clone();var event=new BlockBreakEvent(b,p);event.setDropItems(false);Bukkit.getPluginManager().callEvent(event);return !event.isCancelled()&&b.getBlockData().equals(before);}
    public void close(){life.close();if(pullTask!=null){pullTask.cancel();pullTask=null;}pulls.clear();for(var e:buttons.entrySet()){var powered=e.getValue().clone();((Powerable)powered).setPowered(true);if(e.getKey().getBlockData().equals(powered))e.getKey().setBlockData(e.getValue());}buttons.clear();}
}
