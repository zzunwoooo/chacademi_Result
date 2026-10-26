package school.chacademia.wildlife;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.api.mobs.entities.SpawnReason;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.mockito.ArgumentCaptor;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class CropAwakeningTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<MythicBukkit> mythic;
    private MockedStatic<BukkitAdapter> adapter;
    private MythicMob mythicMob;
    private World world;
    private BukkitScheduler scheduler;
    private YamlConfiguration config;
    private CropAwakening crops;
    private Player player;
    private Block block;
    private Ageable age;

    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);
        mythic=mockStatic(MythicBukkit.class);
        adapter=mockStatic(BukkitAdapter.class);
        scheduler=mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        var api=mock(MythicBukkit.class,RETURNS_DEEP_STUBS);
        mythic.when(MythicBukkit::inst).thenReturn(api);
        mythicMob=mock(MythicMob.class,RETURNS_DEEP_STUBS);
        when(api.getMobManager().getMythicMob(anyString())).thenReturn(Optional.of(mythicMob));
        var plugin=mock(WildlifePlugin.class);
        config=new YamlConfiguration();
        config.set("crop-awakening.chance",1.0);
        config.set("crop-awakening.max-per-player",3);
        config.set("crop-awakening.crops.WHEAT","wheat");
        config.set("crop-awakening.crops.PUMPKIN","pumpkin");
        when(plugin.getConfig()).thenReturn(config);
        crops=new CropAwakening(plugin);
        crops.reload();
        player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenAnswer(call -> new Location(world,4,64,4));
        block=mock(Block.class);
        when(block.getType()).thenReturn(Material.WHEAT);
        world=mock(World.class);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenAnswer(call -> new Location(world,1,64,1));
        age=mock(Ageable.class);
        when(age.getMaximumAge()).thenReturn(7);
        when(age.getAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(age);
    }
    @AfterEach void closeMocks() { adapter.close(); mythic.close(); bukkit.close(); }
    private BlockBreakEvent harvest() {
        var event=new BlockBreakEvent(block,player);
        crops.harvest(event);
        return event;
    }
    @Test void sameTickHarvestsReserveOnlyThreeSpawns() {
        for(int i=0;i<4;i++) assertTrue(harvest().isDropItems());
        verify(scheduler,times(3)).runTask(any(org.bukkit.plugin.Plugin.class),any(Runnable.class));
    }
    @Test void zeroChanceKeepsDropsAndSchedulesNothing() {
        config.set("crop-awakening.chance",0.0);
        assertTrue(harvest().isDropItems());
        verifyNoInteractions(scheduler);
    }
    @Test void immatureCropIsIgnored() {
        when(age.getAge()).thenReturn(6);
        assertTrue(harvest().isDropItems());
        verifyNoInteractions(scheduler);
    }
    @Test void creativeIsIgnored() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertTrue(harvest().isDropItems());
        verifyNoInteractions(scheduler);
    }
    @Test void excludedWorldIsIgnored() {
        config.set("crop-awakening.worlds",List.of("school"));
        assertTrue(harvest().isDropItems());
        verifyNoInteractions(scheduler);
    }
    @Test void placedPumpkinWithoutAttachedStemIsIgnored() {
        when(block.getType()).thenReturn(Material.PUMPKIN);
        when(block.getBlockData()).thenReturn(mock(Directional.class));
        var neighbor=mock(Block.class);
        when(neighbor.getType()).thenReturn(Material.AIR);
        when(block.getRelative(any(BlockFace.class))).thenReturn(neighbor);
        assertTrue(harvest().isDropItems());
        verifyNoInteractions(scheduler);
    }
    @Test void attachedStemMustFaceHarvestedPumpkin() {
        when(block.getType()).thenReturn(Material.PUMPKIN);
        when(block.getBlockData()).thenReturn(mock(Directional.class));
        var neighbor=mock(Block.class);
        when(neighbor.getType()).thenReturn(Material.ATTACHED_PUMPKIN_STEM);
        var facing=mock(Directional.class);
        when(facing.getFacing()).thenReturn(BlockFace.SOUTH);
        when(neighbor.getBlockData()).thenReturn(facing);
        when(block.getRelative(any(BlockFace.class))).thenReturn(neighbor);
        assertTrue(harvest().isDropItems());
        verify(scheduler).runTask(any(org.bukkit.plugin.Plugin.class),any(Runnable.class));
    }
    @Test void schedulingFailureReleasesReservation() {
        when(scheduler.runTask(any(org.bukkit.plugin.Plugin.class),any(Runnable.class)))
            .thenThrow(new IllegalStateException("scheduler stopped")).thenReturn(null);
        assertThrows(IllegalStateException.class,this::harvest);
        for(int i=0;i<3;i++) assertTrue(harvest().isDropItems());
        verify(scheduler,times(4)).runTask(any(org.bukkit.plugin.Plugin.class),any(Runnable.class));
    }

    // ------------------------------------------------------------ 수확물 보존 (2026-10-21)
    private ItemStack stack;
    private List<Item> dropItems() {
        stack=mock(ItemStack.class);when(stack.clone()).thenReturn(stack);
        var item=mock(Item.class);when(item.getItemStack()).thenReturn(stack);
        return new ArrayList<>(List.of(item));
    }
    private BlockDropItemEvent dropEvent(List<Item> items) {
        var event=mock(BlockDropItemEvent.class);when(event.getBlock()).thenReturn(block);when(event.getItems()).thenReturn(items);return event;
    }
    private Runnable nextTick() {
        var task=ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler,atLeastOnce()).runTask(any(org.bukkit.plugin.Plugin.class),task.capture());
        return task.getValue();
    }
    @Test void defaultKeepsOriginalDropsAndMobIsBonus() {
        assertTrue(harvest().isDropItems());
        var items=dropItems();
        crops.drops(dropEvent(items));
        assertEquals(1,items.size());
    }
    @Test void cancelModeReturnsDropsExactlyOnceWhenOwnerLeftBeforeSpawn() {
        config.set("crop-awakening.cancel-drops",true);
        assertTrue(harvest().isDropItems());
        var items=dropItems();
        crops.drops(dropEvent(items));
        assertTrue(items.isEmpty());
        when(player.isOnline()).thenReturn(false);
        nextTick().run();
        verify(world,times(1)).dropItemNaturally(any(Location.class),eq(stack));
    }
    @Test void cancelModeReturnsDropsWhenSpawnFails() {
        config.set("crop-awakening.cancel-drops",true);
        when(mythicMob.spawn(nullable(AbstractLocation.class),anyDouble(),any(SpawnReason.class))).thenReturn(null);
        harvest();
        var items=dropItems();
        crops.drops(dropEvent(items));
        nextTick().run();
        verify(world,times(1)).dropItemNaturally(any(Location.class),eq(stack));
    }
    @Test void cancelModeMobDeathReturnsDropsOnceAndLifetimeAddsNothing() {
        config.set("crop-awakening.cancel-drops",true);
        var ent=mock(Entity.class);var id=UUID.randomUUID();
        when(ent.getUniqueId()).thenReturn(id);when(ent.getWorld()).thenReturn(world);when(ent.getLocation()).thenAnswer(call -> new Location(world,2,64,2));
        when(mythicMob.spawn(nullable(AbstractLocation.class),anyDouble(),any(SpawnReason.class)).getEntity().getBukkitEntity()).thenReturn(ent);
        harvest();
        var items=dropItems();
        crops.drops(dropEvent(items));
        nextTick().run();
        verify(world,never()).dropItemNaturally(any(Location.class),any(ItemStack.class));
        var dead=mock(org.bukkit.entity.LivingEntity.class);when(dead.getUniqueId()).thenReturn(id);when(dead.getLocation()).thenAnswer(call -> new Location(world,2,64,2));
        var death=mock(EntityDeathEvent.class);when(death.getEntity()).thenReturn(dead);crops.death(death);
        verify(world,times(1)).dropItemNaturally(any(Location.class),eq(stack));
        var life=ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(any(org.bukkit.plugin.Plugin.class),life.capture(),anyLong());
        when(ent.isValid()).thenReturn(false);
        life.getValue().run();
        verify(world,times(1)).dropItemNaturally(any(Location.class),eq(stack));
    }
}
