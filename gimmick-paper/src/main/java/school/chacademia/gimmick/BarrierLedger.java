package school.chacademia.gimmick;
import java.io.IOException;
import java.util.*;
/** Write-ahead ownership journal; unknown/unloaded blocks retain their recovery record. */
final class BarrierLedger {
 interface Store {void save(Map<String,Set<String>> records)throws IOException;}
 interface Blocks {boolean available(String key);boolean air(String key);void place(String key);void clearOwnedBarrier(String key);}
 private final Store store;private final Blocks blocks;private final Map<String,Set<String>> records=new LinkedHashMap<>();
 BarrierLedger(Store store,Blocks blocks){this.store=store;this.blocks=blocks;}
 void load(Map<String,Set<String>> saved){records.clear();saved.forEach((k,v)->records.put(k,Set.copyOf(v)));}
 Set<String> owners(){return Set.copyOf(records.keySet());}
 Map<String,Set<String>> snapshot(){return Map.copyOf(records);}
 boolean reconcile(String owner,Set<String> wanted){
  var keep=new LinkedHashSet<String>();var add=new LinkedHashSet<String>();
  for(String key:records.getOrDefault(owner,Set.of())){
   if(wanted.contains(key)||!blocks.available(key))keep.add(key);else blocks.clearOwnedBarrier(key);
  }
  for(String key:wanted)if(blocks.available(key)&&blocks.air(key)){keep.add(key);add.add(key);}
  var next=new LinkedHashMap<>(records);if(keep.isEmpty())next.remove(owner);else next.put(owner,Set.copyOf(keep));
  if(next.equals(records)&&add.isEmpty())return true;
  try{store.save(Map.copyOf(next));}catch(IOException e){return false;}
  records.clear();records.putAll(next);
  for(String key:add)blocks.place(key);
  return true;
 }
}
