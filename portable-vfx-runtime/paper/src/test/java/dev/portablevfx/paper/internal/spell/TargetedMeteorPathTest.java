package dev.portablevfx.paper.internal.spell;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetedMeteorPathTest {
    @Test void skySpawnAimsAtFixedGroundRatherThanCasterGaze() {
        Location target=new Location(null,14,64,20);
        Location launch=target.clone().add(-3,24,19);
        Vector direction=TargetedMeteorPath.direction(launch,target);
        assertTrue(direction.getY()<0);
        assertEquals(1,direction.length(),1e-12);
        double distance=launch.toVector().distance(target.toVector());
        assertTrue(distance>20,"20-block target range must not truncate sky travel");
        Location landed=launch.clone().add(direction.clone().multiply(distance));
        assertEquals(target.toVector(),landed.toVector());
        // Aim pitch does not enter this computation. Each step approaches the same target.
        Location position=launch.clone();int ticks=0;
        while(position.toVector().distance(target.toVector())>1e-6){double step=Math.min(.6,position.toVector().distance(target.toVector()));position.add(TargetedMeteorPath.direction(position,target).multiply(step));ticks++;}
        assertEquals((int)Math.ceil(distance/.6),ticks);
        assertEquals(target.getY(),position.getY(),1e-9);
    }
    @Test void airOrWallAimProjectsDownAndMissingGroundRejects() {
        List<Object[]> rays=new ArrayList<>();
        World world=world(true,new Vector(10,64,15),rays);
        Location ground=TargetedMeteorPath.groundBelow(new Location(world,10,79,15));
        assertEquals(new Vector(10,64,15),ground.toVector());
        assertEquals(new Vector(0,-1,0),rays.getFirst()[1]);
        assertEquals(79.05,((Location)rays.getFirst()[0]).getY(),1e-10);
        assertNull(TargetedMeteorPath.groundBelow(new Location(world(true,null,new ArrayList<>()),10,79,15)));
    }
    @Test void unloadedTargetDoesNotTraceOrLoadChunk() {
        List<Object[]> rays=new ArrayList<>();
        assertNull(TargetedMeteorPath.groundBelow(new Location(world(false,new Vector(),rays),10,79,15)));
        assertTrue(rays.isEmpty());
    }
    private static World world(boolean loaded,Vector hit,List<Object[]> rays) {
        return (World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(proxy,method,args)->switch(method.getName()) {
            case "isChunkLoaded" -> loaded;
            case "getMinHeight" -> -64;
            case "rayTraceBlocks" -> { rays.add(args);yield hit==null?null:new RayTraceResult(hit); }
            default -> throw new AssertionError("Unexpected world operation: "+method);
        });
    }
}
