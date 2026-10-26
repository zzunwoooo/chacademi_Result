package school.chacademia.pets;
import com.google.gson.Gson;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PetConnectionValidationTest {
 final Gson gson=new Gson();
 Object plain(Object v){if(v instanceof ConfigurationSection s){var m=new LinkedHashMap<String,Object>();for(String k:s.getKeys(false))m.put(k,plain(s.get(k)));return m;}if(v instanceof List<?> l)return l.stream().map(this::plain).toList();return v;}
 @SuppressWarnings("unchecked")
 @Test @EnabledIfEnvironmentVariable(named="CHACA_PET_REVIEW_DIR",matches=".+")
 void preparedOverlayClosesAllRegisteredLinksWithoutChangingExistingOwnershipIds()throws Exception{
  Path folder=Path.of(System.getenv("CHACA_PET_REVIEW_DIR"));Map<String,Object> audit=gson.fromJson(Files.readString(folder.resolve("catalog-audit.json")),Map.class);Map<String,Object> plan=gson.fromJson(Files.readString(folder.resolve("catalog-prepared.json")),Map.class);
  for(var raw:(List<Map<String,Object>>)audit.get("servers")){
   String server=(String)raw.get("server");var profiles=new LinkedHashMap<>((Map<String,Object>)raw.get("profiles"));var pets=new LinkedHashMap<>((Map<String,Object>)raw.get("pets"));var mobs=new LinkedHashMap<>((Map<String,Object>)raw.get("mobs"));int oldProfileCount=profiles.size();
   for(var item:(List<Map<String,Object>>)plan.get("files")){
    String target=((String)item.get("target")).replace('\\','/');if(!target.contains("/network/"+server+"/"))continue;var file=Path.of((String)item.get("prepared"));var y=YamlConfiguration.loadConfiguration(file.toFile());
    if(target.contains("/MCPets/Pets/")){String id=y.getString("Id");if(item.get("before")!=null){assertTrue(pets.containsKey(id));var original=YamlConfiguration.loadConfiguration(Path.of((String)item.get("target")).toFile());assertTrue(original.getBoolean("Mountable"));assertFalse(y.getBoolean("Mountable"));for(String key:original.getKeys(true))if(!original.isConfigurationSection(key)&&!key.equals("Mountable"))assertEquals(original.get(key),y.get(key),id+":"+key);}else assertFalse(pets.containsKey(id));pets.put(id,plain(y));}
    else if(target.contains("/MythicMobs/mobs/")){for(String id:y.getKeys(false)){assertFalse(mobs.containsKey(id));mobs.put(id,plain(y.get(id)));}}
    else if(target.contains("/taming.d/")){var section=y.getConfigurationSection("targets");for(String id:section.getKeys(false)){assertFalse(profiles.containsKey(id));profiles.put(id,plain(section.get(id)));}}
   }
   assertEquals(oldProfileCount+3,profiles.size());assertEquals(452,pets.size());
   for(var e:profiles.entrySet()){
    var profile=(Map<String,Object>)e.getValue();String pet=(String)profile.get("pet-id");assertTrue(pets.containsKey(pet),server+":"+e.getKey()+" pet");String mythic=(String)profile.getOrDefault("mythic-id","");if(!mythic.isBlank())assertTrue(mobs.containsKey(mythic),server+":"+mythic);
    if(!Boolean.FALSE.equals(profile.get("roll-shiny")))assertTrue(pets.containsKey(pet+"_shiny"),server+":"+e.getKey()+" shiny");
   }
   for(var pet:pets.entrySet()){var value=(Map<String,Object>)pet.getValue();assertTrue(mobs.containsKey((String)value.get("MythicMob")),server+":"+pet.getKey());assertNotEquals(Boolean.TRUE,value.get("Mountable"),server+":"+pet.getKey()+" riding");}
   for(String type:List.of("CAVE_SPIDER","WITHER_SKELETON","GIANT")){
    var alias=(Map<String,Object>)profiles.get("vanilla_override_"+type.toLowerCase(Locale.ROOT));assertEquals(type,alias.get("mythic-id"));var original=((Map<String,Object>)raw.get("profiles")).values().stream().map(x->(Map<String,Object>)x).filter(x->type.equals(x.get("vanilla-type"))).findFirst().orElseThrow();
    for(var field:original.entrySet())if(!Set.of("mythic-id","vanilla-type").contains(field.getKey()))assertEquals(field.getValue(),alias.get(field.getKey()),server+":"+type+":"+field.getKey());
   }
  }
 }
}
