package dev.portablevfx.paper.internal.spell;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Fixed target-ground trajectory. Never uses caster pitch as the flight direction. */
final class TargetedMeteorPath {
    private TargetedMeteorPath() {}
    static Location groundBelow(Location aim) {
        World world=aim.getWorld();
        if(world==null||!world.isChunkLoaded(aim.getBlockX()>>4,aim.getBlockZ()>>4))return null;
        Location from=aim.clone().add(0,.05,0);
        double distance=Math.min(128,from.getY()-world.getMinHeight());
        if(distance<=0)return null;
        RayTraceResult hit=world.rayTraceBlocks(from,new Vector(0,-1,0),distance,FluidCollisionMode.NEVER,true);
        return hit==null?null:hit.getHitPosition().toLocation(world);
    }
    static Vector direction(Location launch,Location target) {
        Vector delta=target.toVector().subtract(launch.toVector());
        if(delta.lengthSquared()<1e-12)throw new IllegalArgumentException("Meteor launch must differ from target");
        return delta.normalize();
    }
}
