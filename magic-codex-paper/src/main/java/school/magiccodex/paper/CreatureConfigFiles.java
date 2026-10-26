package school.magiccodex.paper;

import java.io.File;
import java.util.Arrays;
import org.bukkit.configuration.file.YamlConfiguration;

/** Additive pack fragments. Existing settings are never overwritten by a pack. */
final class CreatureConfigFiles {
    static YamlConfiguration load(File directory,String base,String section)throws Exception {
        var result=new YamlConfiguration();result.load(new File(directory,base+".yml"));
        var files=new File(directory,base+".d").listFiles(f->f.isFile()&&f.getName().endsWith(".yml"));
        if(files==null)return result;
        Arrays.sort(files,java.util.Comparator.comparing(File::getName));
        for(var file:files){
            var extra=new YamlConfiguration();extra.load(file);var values=extra.getConfigurationSection(section);
            if(values==null)continue;
            for(var key:values.getKeys(false)){
                if(!key.matches("[a-zA-Z0-9_-]{1,96}"))throw new IllegalArgumentException("Invalid creature key: "+key);
                String path=section+"."+key;
                if(result.contains(path))throw new IllegalArgumentException("Duplicate creature key: "+key+" in "+file.getName());
                var child=values.getConfigurationSection(key);
                if(child==null)result.set(path,values.get(key));else result.createSection(path,child.getValues(false));
            }
        }
        return result;
    }
    private CreatureConfigFiles(){}
}
