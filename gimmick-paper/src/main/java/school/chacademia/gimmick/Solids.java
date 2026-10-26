package school.chacademia.gimmick;
import java.io.File;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
/**
 * Leftover cleanup only (2026-10-24): puzzle nodes with a `solid` box are gone. Barriers such nodes recorded in solids.yml are
 * taken back once their chunk is loaded; nothing new is ever placed here.
 */
final class Solids implements Listener {
 private final GimmickPlugin plugin;private final File file;private final BarrierLedger ledger;
 Solids(GimmickPlugin plugin){this.plugin=plugin;file=new File(plugin.getDataFolder(),"solids.yml");
  ledger=new BarrierLedger(records->{var yaml=new YamlConfiguration();records.forEach((k,v)->yaml.set(k,new ArrayList<>(v)));AtomicYaml.save(yaml,file);},new BarrierLedger.Blocks(){
   public boolean available(String key){return block(key)!=null;}
   public boolean air(String key){return false;}
   public void place(String key){}
   public void clearOwnedBarrier(String key){Block b=block(key);if(b!=null&&b.getType()==Material.BARRIER)b.setType(Material.AIR,false);}
  });
 }
 void load(){if(!file.exists())return;var yaml=AtomicYaml.load(file);var records=new LinkedHashMap<String,Set<String>>();
  for(String owner:yaml.getKeys(false)){var keys=new LinkedHashSet<>(yaml.getStringList(owner));for(String key:keys){String[] parts=key.split(":");if(parts.length!=4)throw new IllegalStateException("Malformed solids journal; preserved");for(int i=1;i<4;i++)Integer.parseInt(parts[i]);}records.put(owner,keys);}ledger.load(records);cleanup();
 }
 private void cleanup(){for(String id:ledger.owners())if(!ledger.reconcile(id,Set.of()))plugin.getLogger().severe("solids.yml write failed; recovery records retained");}
 @EventHandler public void worldLoad(WorldLoadEvent event){cleanup();}
 @EventHandler public void chunkLoad(ChunkLoadEvent event){cleanup();}
 private Block block(String key){String[] f=key.split(":");if(f.length!=4)return null;World w=Bukkit.getWorld(f[0]);if(w==null)return null;
  int x=Integer.parseInt(f[1]),y=Integer.parseInt(f[2]),z=Integer.parseInt(f[3]);if(!w.isChunkLoaded(x>>4,z>>4))return null;return w.getBlockAt(x,y,z);}
}
