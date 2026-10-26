package school.magiccodex.paper;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Old fitted PDC parts must survive the transition to the shared gear row. */
class FishingGearMigrationTest {
    private final Map<NamespacedKey,Object> values = new HashMap<>();
    private Plugin plugin;
    private ItemStack rod;
    private ItemMeta meta;
    private PersistentDataContainer data;
    private FishingGearStore store;
    private ItemsAdderItems itemsAdder;
    private FishingGear gear;
    private FishingConfig.Gear rodDef, floatDef, lineDef, baitDef;

    @BeforeEach void legacyRod() {
        plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("MagicCodexBridge");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        rod = mock(ItemStack.class); meta = mock(ItemMeta.class); data = mock(PersistentDataContainer.class);
        when(rod.getType()).thenReturn(Material.FISHING_ROD);
        when(rod.getAmount()).thenReturn(1);
        when(rod.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class),eq(PersistentDataType.STRING)))
                .thenAnswer(i -> values.get(i.getArgument(0)));
        when(data.get(any(NamespacedKey.class),eq(PersistentDataType.INTEGER)))
                .thenAnswer(i -> values.get(i.getArgument(0)));
        when(data.has(any(NamespacedKey.class),eq(PersistentDataType.STRING)))
                .thenAnswer(i -> values.get(i.getArgument(0)) instanceof String);
        doAnswer(i -> { values.put(i.getArgument(0),i.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
        store = mock(FishingGearStore.class); itemsAdder = mock(ItemsAdderItems.class);
        var config = new FishingConfig();
        rodDef = definition(0,"legacy_rod",90,false);
        floatDef = definition(1,"legacy_float",60,false);
        lineDef = definition(2,"legacy_line",80,false);
        baitDef = definition(3,"legacy_bait",0,false);
        FishingConfig.Gear[] fitted = {rodDef,floatDef,lineDef,baitDef};
        for (int k=0;k<4;k++) {
            config.fallback[k] = definition(k,"default_"+k,40,true);
            config.gear.set(k,Map.of(config.fallback[k].id(),config.fallback[k],fitted[k].id(),fitted[k]));
        }
        values.put(key("fishing_kind"),0); values.put(key("fishing_id"),rodDef.id()); values.put(key("fishing_dur"),43);
        values.put(key("fishing_float"),floatDef.id()); values.put(key("fishing_float_dur"),17);
        values.put(key("fishing_line"),lineDef.id()); values.put(key("fishing_line_dur"),22);
        values.put(key("fishing_bait"),baitDef.id()); values.put(key("fishing_bait_count"),13);
        gear = new FishingGear(plugin,config,itemsAdder,store);
    }
    private NamespacedKey key(String name) { return new NamespacedKey(plugin,name); }
    private static FishingConfig.Gear definition(int kind,String id,int durability,boolean fallback) {
        return new FishingConfig.Gear(kind,id,id,0,10,1,durability,"","","",fallback,"");
    }

    @Test void readingOldRodPreservesAllFittedPartsAndTheirRemainingValues() {
        var loadout = gear.read(rod);
        assertEquals(rodDef,loadout.rod().gear()); assertEquals(43,loadout.rod().value()); assertFalse(loadout.rod().fallback());
        assertEquals(floatDef,loadout.flt().gear()); assertEquals(17,loadout.flt().value()); assertFalse(loadout.flt().fallback());
        assertEquals(lineDef,loadout.line().gear()); assertEquals(22,loadout.line().value()); assertFalse(loadout.line().fallback());
        assertEquals(baitDef,loadout.bait().gear()); assertEquals(13,loadout.bait().value()); assertFalse(loadout.bait().fallback());
        verify(store,never()).put(any());
    }

    @Test void firstDurabilityWriteMigratesFittedPartsInsteadOfCreatingAnEmptyRodRow() {
        gear.setDurability(rod,39);
        var captured = ArgumentCaptor.forClass(FishingGearStore.Row.class); verify(store).put(captured.capture());
        var row = captured.getValue();
        assertEquals(0,row.kind()); assertEquals(rodDef.id(),row.gearId()); assertEquals(39,row.durability());
        assertEquals(floatDef.id(),row.floatId()); assertEquals(17,row.floatDur());
        assertEquals(lineDef.id(),row.lineId()); assertEquals(22,row.lineDur());
        assertEquals(baitDef.id(),row.baitId()); assertEquals(13,row.baitCount());
        assertEquals(UUID.fromString(row.uid()).toString(),values.get(key("fishing_uid")));
        assertEquals(floatDef.id(),values.get(key("fishing_float")));
        assertEquals(lineDef.id(),values.get(key("fishing_line")));
        assertEquals(baitDef.id(),values.get(key("fishing_bait")));
        verify(itemsAdder,never()).create(anyString(),anyInt());
    }
}
