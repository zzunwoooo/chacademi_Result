package dev.portablevfx.paper.internal;

import java.util.function.Function;
import org.bukkit.NamespacedKey;

/** Effect requests may contain either Bukkit world names or canonical dimension keys. */
public final class WorldLookup {
    private WorldLookup() { }
    public static <T> T resolve(String world,Function<String,T> byName,Function<NamespacedKey,T> byKey){
        if(world==null||world.isBlank())return null;
        if(world.indexOf(':')<0){
            T named=byName.apply(world);
            if(named!=null)return named;
        }
        NamespacedKey key=NamespacedKey.fromString(world);
        return key==null?null:byKey.apply(key);
    }
}
