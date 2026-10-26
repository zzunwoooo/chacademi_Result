package dev.portablevfx.paper.internal.spell;

import dev.portablevfx.paper.api.*;
import dev.portablevfx.protocol.EffectBasis;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogMeteorCastTest {
    @Test void actualCatalogLaunchesFromTargetAndHitsGroundThenClearsLocalCore() throws Exception {
        UUID owner=UUID.randomUUID(),worldId=UUID.randomUUID();
        World world=proxy(World.class,(method,args)->switch(method){
            case "getUID" -> worldId;
            case "getKey" -> NamespacedKey.minecraft("test");
            case "isChunkLoaded" -> true;
            case "getMinHeight" -> -64;
            case "rayTrace" -> null; // Looking horizontally into air.
            case "rayTraceBlocks" -> { Vector direction=(Vector)args[1];Location from=(Location)args[0];
                yield direction.equals(new Vector(0,-1,0))?new RayTraceResult(new Vector(from.getX(),64,from.getZ())):null; }
            default -> throw new AssertionError(method);
        });
        Player player=proxy(Player.class,(method,args)->switch(method){
            case "getUniqueId" -> owner;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world,0,64,0,0,0);
            case "getEyeLocation" -> new Location(world,0,65.6,0,0,0);
            case "isDead" -> false;
            case "isOnline" -> true;
            default -> throw new AssertionError(method);
        });
        Server server=proxy(Server.class,(method,args)->switch(method){
            case "getPluginManager" -> proxy(org.bukkit.plugin.PluginManager.class,(m,a)-> { if(m.equals("getPlugin")) return null; throw new AssertionError(m); });
            case "isPrimaryThread" -> true;
            case "getPlayer" -> player;
            default -> throw new AssertionError(method);
        });
        List<EffectRequest> played=new ArrayList<>();Map<String,UUID> handles=new HashMap<>();
        List<EffectBasis> bases=new ArrayList<>();List<UUID> cleared=new ArrayList<>();
        PortableVfxService service=proxy(PortableVfxService.class,(method,args)->switch(method){
            case "owner" -> {assertNotNull(args[0]);assertNotNull(args[1]);yield null;}
            case "supportsCatalogCast" -> true;
            case "startCast","startFollowCast" -> {EffectRequest r=(EffectRequest)args[0];played.add(r);UUID handle=UUID.randomUUID();handles.put(r.effectId(),handle);if(args[1] instanceof EffectBasis b)bases.add(b);yield new PlayResult(handle,1,0,0);}
            case "updateCast" -> 1;
            case "finishCast" -> {if(args.length>1&&Boolean.TRUE.equals(args[1]))cleared.add((UUID)args[0]);yield true;}
            case "stop" -> true;
            default -> throw new AssertionError(method);
        });
        var serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object previous=serverField.get(null);serverField.set(null,server);
        try {
            var yaml=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/spell-bindings.yml"))));
            var engine=new CatalogVisualEngine(service,CatalogVisualEngine.read(yaml.getConfigurationSection("bindings")));
            assertEquals(true,engine.cast(player,"falling_star"));
            for(int i=0;i<150;i++)engine.tick();
            var meteor=played.stream().filter(r->r.effectId().endsWith("/meteor")).findFirst().orElseThrow();
            assertEquals(-3,meteor.x(),1e-8);assertEquals(88,meteor.y(),1e-8);assertEquals(39,meteor.z(),1e-8);
            assertTrue(bases.stream().anyMatch(b->b.forwardY()<-.5),"meteor must descend");
            // A16: the falling body ends at the target, but the impact belongs to the gameplay server (chameteor).
            assertTrue(cleared.contains(handles.get("claude:fallingstar/meteor")));
            assertEquals(0,played.stream().filter(r->r.effectId().equals("claude:fallingstar/impact")).count(),"no visual-only impact");
            assertEquals(1,engine.trigger(owner,"falling_star","external:impact",new Location(world,0,64,20),null));
            var impact=played.stream().filter(r->r.effectId().equals("claude:fallingstar/impact")).findFirst().orElseThrow();
            assertEquals(0,impact.x(),1e-8);assertEquals(64,impact.y(),1e-8);assertEquals(20,impact.z(),1e-8);
            assertEquals(1,played.stream().filter(r->r.effectId().equals("claude:fallingstar/impact")).count());
            // The server's landing point (external:land_at at cast) moves the mark and the falling body there.
            played.clear();
            var fresh=new CatalogVisualEngine(service,CatalogVisualEngine.read(yaml.getConfigurationSection("bindings")));
            assertEquals(true,fresh.cast(player,"falling_star"));
            assertEquals(1,fresh.trigger(owner,"falling_star","external:land_at",new Location(world,5,70,25),null));
            for(int i=0;i<80;i++)fresh.tick();
            var mark=played.stream().filter(r->r.effectId().equals("claude:fallingstar/mark")).findFirst().orElseThrow();
            assertEquals(5,mark.x(),1e-8);assertEquals(70,mark.y(),1e-8);assertEquals(25,mark.z(),1e-8);
            var moved=played.stream().filter(r->r.effectId().endsWith("/meteor")).findFirst().orElseThrow();
            assertEquals(2,moved.x(),1e-8);assertEquals(94,moved.y(),1e-8);assertEquals(44,moved.z(),1e-8);
            // A missed generic projectile must clear its long-lived local core at range exhaustion too.
            var shortRange=new org.bukkit.configuration.MemoryConfiguration();
            var section=shortRange.createSection("test");section.set("range",1);section.set("speed",1);
            section.set("phases",List.of(Map.of("effect","claude:test/projectile","role","projectile","anchor","projectile","duration-ticks",600)));
            var miss=new CatalogVisualEngine(service,CatalogVisualEngine.read(shortRange));
            assertEquals(true,miss.cast(player,"test"));miss.tick();miss.tick();
            assertTrue(cleared.contains(handles.get("claude:test/projectile")),"missed projectile must not leave a 30-second local core");
        } finally {serverField.set(null,previous);}
    }
    private interface Call {Object invoke(String method,Object[] args);}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("toString"))return type.getSimpleName();
        return call.invoke(m.getName(),a);
    }));}
}
