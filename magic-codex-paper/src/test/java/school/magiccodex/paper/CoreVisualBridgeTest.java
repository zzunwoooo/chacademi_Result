package school.magiccodex.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CoreVisualBridgeTest {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PersistentDataContainer container(Map<NamespacedKey,Object> values) {
        var data=mock(PersistentDataContainer.class);
        when(data.get(any(),any())).thenAnswer(i->values.get(i.getArgument(0)));
        when(data.getKeys()).thenAnswer(i->Set.copyOf(values.keySet()));
        doAnswer(i->{values.remove(i.getArgument(0));return null;}).when(data).remove(any());
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(2));return null;}).when(data).set(any(),any(),any());
        doAnswer(i->{PersistentDataContainer target=i.getArgument(0);for(var e:values.entrySet()){Object v=e.getValue();if(v instanceof String s)target.set(e.getKey(),PersistentDataType.STRING,s);else if(v instanceof Integer n)target.set(e.getKey(),PersistentDataType.INTEGER,n);else if(v instanceof Double n)target.set(e.getKey(),PersistentDataType.DOUBLE,n);else throw new IllegalArgumentException("Unsupported test PDC value");}return null;}).when(data).copyTo(any(),eq(true));
        return data;
    }
    private static NamespacedKey k(String key){return new NamespacedKey("magiccodexbridge",key);}
    @Test void stateAppearanceMatchesExistingAppraisalStates(){
        assertEquals("item:core_unidentified",CoreVisualBridge.appearanceId(0));
        assertEquals("item:core_identified",CoreVisualBridge.appearanceId(1));
        assertEquals("item:core_broken",CoreVisualBridge.appearanceId(2));
        assertNull(CoreVisualBridge.appearanceId(-1));assertNull(CoreVisualBridge.appearanceId(3));
    }
    @Test void allStatesPreserveIdentityStatsOtherPluginDataAndQuantity(){
        for(int state=0;state<3;state++){
            var plugin=mock(MagicCodexBridge.class);when(plugin.getName()).thenReturn("MagicCodexBridge");
            var items=mock(ItemsAdderItems.class);var bridge=new CoreVisualBridge(plugin,items);
            Map<NamespacedKey,Object> original=new HashMap<>();
            original.put(k("core_id"),"b3d25379-2026-4c5c-a957-a87a9547c094");original.put(k("core_state"),state);original.put(k("core_nodes"),7);
            original.put(k("core_base_success"),.47d);original.put(k("core_starcatch_bonus"),.12d);original.put(k("core_stat_gain"),3.5d);original.put(k("core_affinity"),2);
            original.put(new NamespacedKey("anotherplugin","owner"),"keep");
            original.put(new NamespacedKey("itemsadder","id"),"old");
            var preserved=new HashMap<>(original);var outputData=container(preserved);
            var source=mock(ItemStack.class);var output=mock(ItemStack.class);var sourceMeta=mock(ItemMeta.class);var outputMeta=mock(ItemMeta.class);
            when(source.hasItemMeta()).thenReturn(true);when(source.getItemMeta()).thenReturn(sourceMeta);var sourceData=container(original);when(sourceMeta.getPersistentDataContainer()).thenReturn(sourceData);
            when(source.getType()).thenReturn(Material.AMETHYST_SHARD);when(source.getAmount()).thenReturn(3);when(source.clone()).thenReturn(output);
            when(output.getItemMeta()).thenReturn(outputMeta);when(outputMeta.getPersistentDataContainer()).thenReturn(outputData);when(output.getAmount()).thenReturn(3);
            var template=mock(ItemStack.class);var templateMeta=mock(ItemMeta.class);var id=CoreVisualBridge.appearanceId(state);
            when(template.getType()).thenReturn(Material.AMETHYST_SHARD);when(template.hasItemMeta()).thenReturn(true);when(template.getItemMeta()).thenReturn(templateMeta);
            when(templateMeta.hasCustomModelData()).thenReturn(true);when(templateMeta.getCustomModelData()).thenReturn(11000+state);
            var templateData=container(new HashMap<>(Map.of(new NamespacedKey("itemsadder","id"),id)));when(templateMeta.getPersistentDataContainer()).thenReturn(templateData);
            when(items.create(id,1)).thenReturn(template);when(items.idOf(output)).thenReturn(id);
            assertSame(output,bridge.appearance(source));
            for(var entry:original.entrySet())if(!entry.getKey().getNamespace().equals("itemsadder"))assertEquals(entry.getValue(),preserved.get(entry.getKey()));
            assertEquals(id,preserved.get(new NamespacedKey("itemsadder","id")));assertEquals("old",original.get(new NamespacedKey("itemsadder","id")));
            assertEquals(3,output.getAmount());verify(output,never()).setAmount(anyInt());verify(outputMeta).setCustomModelData(11000+state);
            verify(outputMeta,never()).displayName(any());verify(outputMeta,never()).lore(anyList());
            when(items.create(id,1)).thenReturn(null);assertSame(source,bridge.appearance(source));
        }
    }
    @Test void plainResourceItemNeverReceivesFunctionalData(){
        var plugin=mock(MagicCodexBridge.class);when(plugin.getName()).thenReturn("MagicCodexBridge");
        var items=mock(ItemsAdderItems.class);var source=mock(ItemStack.class);var meta=mock(ItemMeta.class);
        when(source.hasItemMeta()).thenReturn(true);when(source.getItemMeta()).thenReturn(meta);var emptyData=container(new HashMap<>());when(meta.getPersistentDataContainer()).thenReturn(emptyData);
        assertSame(source,new CoreVisualBridge(plugin,items).appearance(source));verifyNoInteractions(items);verify(source,never()).setItemMeta(any());
    }
}
