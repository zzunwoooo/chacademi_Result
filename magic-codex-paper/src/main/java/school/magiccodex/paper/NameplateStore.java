package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import org.bukkit.configuration.file.YamlConfiguration;import school.magiccodex.protocol.*;
final class NameplateStore {
 record Label(String name,String prefixId,String suffixId,String legacyTitle,String prefixText,String suffixText){
  Label(String name,String prefixId,String suffixId){this(name,prefixId,suffixId,"");}
  Label(String name,String prefixId,String suffixId,String legacyTitle){this(name,prefixId,suffixId,legacyTitle,"","");}
  Label{NameplateProtocol.text(name,48,false);NameplateProtocol.text(legacyTitle,24,true);NameplateProtocol.text(prefixText,48,true);NameplateProtocol.text(suffixText,48,true);for(String id:List.of(prefixId,suffixId))if(!id.isEmpty()&&!TitleProtocol.id(id))throw new IllegalArgumentException("Invalid title ID");}
 }
 static Map<String,Label> load(Path path){
  var result=new HashMap<String,Label>();var config=new YamlConfiguration();if(Files.exists(path))try{config.load(path.toFile());}catch(Exception ex){throw new IllegalArgumentException("Could not load nameplate storage",ex);}var section=config.getConfigurationSection("labels");
  if(section!=null)for(String key:section.getKeys(false))result.put(key,new Label(section.getString(key+".name",""),section.getString(key+".prefix-id",""),section.getString(key+".suffix-id",""),section.getString(key+".legacy-title",section.getString(key+".title","")),section.getString(key+".prefix-text",""),section.getString(key+".suffix-text","")));return Map.copyOf(result);
 }
 static void save(Path path,Map<String,Label> data)throws Exception{
  var y=new YamlConfiguration();y.set("version",3);for(var e:data.entrySet()){if(!e.getKey().matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("unsafe identity");var d=e.getValue();y.set("labels."+e.getKey()+".name",d.name);y.set("labels."+e.getKey()+".prefix-id",d.prefixId);y.set("labels."+e.getKey()+".suffix-id",d.suffixId);y.set("labels."+e.getKey()+".prefix-text",d.prefixText);y.set("labels."+e.getKey()+".suffix-text",d.suffixText);if(!d.legacyTitle.isEmpty())y.set("labels."+e.getKey()+".legacy-title",d.legacyTitle);}
  Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.createDirectories(path.getParent());Files.writeString(tmp,y.saveToString());Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
 }
}
