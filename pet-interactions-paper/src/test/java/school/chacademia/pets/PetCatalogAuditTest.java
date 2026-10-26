package school.chacademia.pets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.bukkit.configuration.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
class PetCatalogAuditTest {
 Object plain(Object v){if(v instanceof ConfigurationSection s){var out=new LinkedHashMap<String,Object>();for(String k:s.getKeys(false))out.put(k,plain(s.get(k)));return out;}if(v instanceof List<?> l)return l.stream().map(this::plain).toList();return v;}
 @Test @EnabledIfEnvironmentVariable(named="CHACA_PET_REVIEW_DIR",matches=".+") void auditDefinitionCopiesOnly()throws Exception{
  var results=new ArrayList<Object>();
  for(String server:List.of("school","wild","arena")){
   Path root=Path.of("C:/Chacademi/network",server,"plugins");var row=new LinkedHashMap<String,Object>();row.put("server",server);var profiles=new LinkedHashMap<String,Object>();var sources=new LinkedHashMap<String,String>();
   var files=new ArrayList<Path>();files.add(root.resolve("MagicCodexBridge/taming.yml"));try(var walk=Files.walk(root.resolve("MagicCodexBridge/taming.d"))){walk.filter(f->f.toString().endsWith(".yml")).sorted().forEach(files::add);}
   for(var file:files){var targets=YamlConfiguration.loadConfiguration(file.toFile()).getConfigurationSection("targets");if(targets==null)continue;for(String id:targets.getKeys(false)){if(profiles.put(id,plain(targets.get(id)))!=null)throw new IllegalStateException("duplicate target "+id);sources.put(id,file.toString());}}
   row.put("profiles",profiles);row.put("profileSources",sources);var pets=new LinkedHashMap<String,Object>();var duplicates=new ArrayList<String>();
   try(var walk=Files.walk(root.resolve("MCPets/Pets"))){for(var file:walk.filter(f->f.toString().endsWith(".yml")).sorted().toList()){var y=YamlConfiguration.loadConfiguration(file.toFile());String id=y.getString("Id","");if(id.isBlank())continue;var pet=new LinkedHashMap<String,Object>();for(String k:List.of("Id","MythicMob","Permission","Mountable","AutoRide","MountType"))if(y.contains(k))pet.put(k,plain(y.get(k)));pet.put("source",file.toString());if(pets.put(id,pet)!=null)duplicates.add(id);}}
   row.put("pets",pets);row.put("duplicatePets",duplicates);var mobs=new LinkedHashMap<String,Object>();
   try(var walk=Files.walk(root.resolve("MythicMobs"))){for(var file:walk.filter(f->f.toString().endsWith(".yml")&&f.toString().toLowerCase(Locale.ROOT).matches(".*[\\\\/]mobs[\\\\/].*")).sorted().toList()){var y=YamlConfiguration.loadConfiguration(file.toFile());for(String id:y.getKeys(false)){if(!y.isConfigurationSection(id))continue;var mob=new LinkedHashMap<String,Object>();for(String k:List.of("Type","Display","Health","Damage","Options","Skills","Template"))if(y.contains(id+"."+k))mob.put(k,plain(y.get(id+"."+k)));mob.put("source",file.toString());mobs.put(id,mob);}}}
   row.put("mobs",mobs);var models=new ArrayList<String>();if(Files.isDirectory(root.resolve("ModelEngine/blueprints")))try(var walk=Files.walk(root.resolve("ModelEngine/blueprints"))){walk.filter(f->f.toString().endsWith(".bbmodel")).forEach(f->models.add(f.getFileName().toString().replaceFirst("\\.bbmodel$","")));}row.put("models",models);results.add(row);
  }
  var entities=new ArrayList<String>();for(EntityType type:EntityType.values())if(type.getEntityClass()!=null&&LivingEntity.class.isAssignableFrom(type.getEntityClass()))entities.add(type.name());
  var out=new LinkedHashMap<String,Object>();out.put("servers",results);out.put("livingEntityTypes",entities);Files.writeString(Path.of(System.getenv("CHACA_PET_REVIEW_DIR")).resolve("catalog-audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(out));
 }
}
