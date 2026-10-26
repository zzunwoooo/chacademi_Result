package school.chacademia.wildlife;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
/** Explicit ecological aliases for vanilla terrain; never an allow-all or prefix match. */
final class BiomeCompatibility {
    private final Map<String,Set<String>> aliases;
    BiomeCompatibility(Map<String,Set<String>> aliases){this.aliases=Map.copyOf(aliases);}
    static BiomeCompatibility parse(ConfigurationSection section){return parse(section,null);}
    /** terralith: 자기 서식지가 없는 Terralith 바이옴(예: yellowstone)을 비슷한 바이옴의 서식지로 잇는 표. 없어도 됨. */
    static BiomeCompatibility parse(ConfigurationSection section,ConfigurationSection terralith){
        if(section==null)throw new IllegalArgumentException("vanilla-biomes section missing");
        var rows=new LinkedHashMap<String,Set<String>>();
        read(section,"minecraft:[a-z0-9_]+",rows);
        if(terralith!=null)read(terralith,"terralith:[a-z0-9_/]+",rows);
        return new BiomeCompatibility(rows);
    }
    private static void read(ConfigurationSection section,String keyPattern,Map<String,Set<String>> rows){
        for(String biome:section.getKeys(false)){
            Object raw=section.get(biome);
            if(!biome.matches(keyPattern)||rows.containsKey(biome)||!(raw instanceof List<?> list)||list.isEmpty()||list.size()>16)
                throw new IllegalArgumentException("Invalid vanilla biome compatibility: "+biome);
            var values=new LinkedHashSet<String>();
            for(Object value:list){
                if(!(value instanceof String id)||!id.matches("terralith:[a-z0-9_/]+")||!values.add(id))
                    throw new IllegalArgumentException("Invalid ecological counterpart: "+biome);
            }
            rows.put(biome,Set.copyOf(values));
        }
    }
    boolean matches(Set<String> habitat,String actual){
        if(habitat.contains(actual))return true;
        var equivalents=aliases.get(actual);
        return equivalents!=null&&equivalents.stream().anyMatch(habitat::contains);
    }
    static boolean cave(String biome){
        return biome.startsWith("terralith:cave/")||Set.of("minecraft:lush_caves","minecraft:dripstone_caves","minecraft:deep_dark").contains(biome);
    }
}
