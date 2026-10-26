package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.logging.Logger;import org.bukkit.*;import org.bukkit.block.*;import org.bukkit.block.data.BlockData;
class RecoveryTest {
 @TempDir Path dir;
 BlockData air=ProtectionTest.mock(BlockData.class,(m,a)->m.equals("getAsString")?"minecraft:air":null);
 @Test void journalExistsBeforePlacementAndUnloadedWorldSurvivesRestart() throws Exception {
  Path file=dir.resolve("temp-blocks.yml");Material[] type={Material.AIR};World[] loaded={null};Block[] block={null};
  World world=ProtectionTest.mock(World.class,(m,a)->m.equals("getName")?"test":m.equals("getBlockAt")?block[0]:null);
  block[0]=ProtectionTest.mock(Block.class,(m,a)->switch(m){case "getWorld"->world;case "getX","getY","getZ"->0;case "getType"->type[0];case "getBlockData"->air;case "setType"->{assertTrue(Files.exists(file));type[0]=(Material)a[0];yield null;}case "setBlockData"->{type[0]=Material.AIR;yield null;}default->null;});
  var store=new TempBlocks(file.toFile(),Logger.getAnonymousLogger(),n->loaded[0],s->air);
  assertTrue(store.place(block[0],Material.BARRIER));assertEquals(Material.BARRIER,type[0]);
  var restarted=new TempBlocks(file.toFile(),Logger.getAnonymousLogger(),n->loaded[0],s->air);restarted.restoreLeftovers();assertEquals(1,restarted.count());assertTrue(Files.readString(file).contains("BARRIER"));
  loaded[0]=world;restarted.worldLoad(new org.bukkit.event.world.WorldLoadEvent(world));assertEquals(Material.AIR,type[0]);assertEquals(0,restarted.count());
 }
 @Test void failedJournalDoesNotPlaceBlock() throws Exception {
  Path parent=dir.resolve("not-a-directory");Files.writeString(parent,"occupied");
  World world=ProtectionTest.mock(World.class,(m,a)->m.equals("getName")?"test":null);
  Block block=ProtectionTest.mock(Block.class,(m,a)->switch(m){case "getWorld"->world;case "getX","getY","getZ"->0;case "getType"->Material.AIR;case "getBlockData"->air;case "setType"->throw new AssertionError("unrecorded placement");default->null;});
  var store=new TempBlocks(parent.resolve("journal.yml").toFile(),Logger.getAnonymousLogger(),n->world,s->air);assertFalse(store.place(block,Material.BARRIER));assertEquals(0,store.count());
 }
 @Test void batchPlatformWritesJournalOnceBeforePlacingAndKeepsUnrestorable() throws Exception {
  Path file=dir.resolve("batch.yml");World[] loaded={null};
  java.util.List<Block> blocks=new java.util.ArrayList<>();Material[] types=new Material[3];
  World world=ProtectionTest.mock(World.class,(m,a)->switch(m){case "getName"->"test";case "getBlockAt"->blocks.get((Integer)a[0]);default->null;});
  for(int i=0;i<3;i++){int idx=i;types[i]=Material.AIR;blocks.add(ProtectionTest.mock(Block.class,(m,a)->switch(m){case "getWorld"->world;case "getX"->idx;case "getY","getZ"->0;case "getType"->types[idx];case "getBlockData"->air;case "setType"->{assertTrue(Files.exists(file),"journal must exist before any block is placed");types[idx]=(Material)a[0];yield null;}case "setBlockData"->{types[idx]=Material.AIR;yield null;}default->null;}));}
  types[2]=Material.STONE; // not free: skipped
  var store=new TempBlocks(file.toFile(),Logger.getAnonymousLogger(),n->loaded[0],s->air);
  var placed=store.placeAll(blocks,Material.BARRIER);
  assertEquals(2,placed.size());assertEquals(2,store.count());assertEquals(Material.BARRIER,types[0]);assertEquals(Material.STONE,types[2]);
  store.removeAll(placed);assertEquals(2,store.count(),"world not loaded: records stay journaled");assertEquals(Material.BARRIER,types[1]);
  loaded[0]=world;store.removeAll(placed);assertEquals(0,store.count());assertEquals(Material.AIR,types[0]);assertEquals(Material.AIR,types[1]);
  assertFalse(Files.readString(file).contains("BARRIER"));
 }
 @Test void failedBatchJournalPlacesNothing() throws Exception {
  Path parent=dir.resolve("occupied");Files.writeString(parent,"x");
  World world=ProtectionTest.mock(World.class,(m,a)->m.equals("getName")?"test":null);
  Block block=ProtectionTest.mock(Block.class,(m,a)->switch(m){case "getWorld"->world;case "getX","getY","getZ"->0;case "getType"->Material.AIR;case "getBlockData"->air;case "setType"->throw new AssertionError("unrecorded placement");default->null;});
  var store=new TempBlocks(parent.resolve("journal.yml").toFile(),Logger.getAnonymousLogger(),n->world,s->air);
  assertTrue(store.placeAll(java.util.List.of(block),Material.BARRIER).isEmpty());assertEquals(0,store.count());
 }
 @Test void journalRejectsInvalidEntries(){assertNull(TempBlocks.Entry.parse("bad"));assertNull(TempBlocks.Entry.parse("world|x|1|2|AIR|minecraft:air"));var e=new TempBlocks.Entry("world",1,2,3,Material.BARRIER,"minecraft:air");assertEquals(e,TempBlocks.Entry.parse(e.line()));}
 @Test void prisonRestoresOriginalAwarenessAndDoesNotOverwriteRepeatedFreeze(){
  for(boolean original:new boolean[]{false,true}){
   java.util.Map<Object,Object> saved=new java.util.HashMap<>();boolean[] aware={original};
   var pdc=ProtectionTest.mock(org.bukkit.persistence.PersistentDataContainer.class,(m,a)->switch(m){case "has"->saved.containsKey(a[0]);case "get"->saved.get(a[0]);case "set"->{saved.put(a[0],a[2]);yield null;}case "remove"->{saved.remove(a[0]);yield null;}default->null;});
   var mob=ProtectionTest.mock(org.bukkit.entity.Mob.class,(m,a)->switch(m){case "getPersistentDataContainer"->pdc;case "isAware"->aware[0];case "setAware"->{aware[0]=(Boolean)a[0];yield null;}default->null;});
   var key=new NamespacedKey("test","prison");Marks.freezeMob(mob,key);Marks.freezeMob(mob,key);assertFalse(aware[0]);Marks.recoverMob(mob,key);assertEquals(original,aware[0]);assertTrue(saved.isEmpty());
  }
 }
}
