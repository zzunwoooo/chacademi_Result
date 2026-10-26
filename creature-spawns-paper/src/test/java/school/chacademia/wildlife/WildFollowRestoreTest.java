package school.chacademia.wildlife;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WildFollowRestoreTest {
    MockedStatic<io.papermc.paper.registry.RegistryAccess> registry;
    @BeforeEach void registrySetup() {
        registry=mockStatic(io.papermc.paper.registry.RegistryAccess.class);
        var access=mock(io.papermc.paper.registry.RegistryAccess.class, call -> java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{org.bukkit.Registry.class},(proxy,method,args)->null));
        registry.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
    }
    @AfterEach void registryCleanup() { registry.close(); }

    static final class Fixture {
        final WildlifePlugin plugin=mock(WildlifePlugin.class);
        final LivingEntity mob=mock(LivingEntity.class);
        final AttributeInstance follow=mock(AttributeInstance.class);
        final PersistentDataContainer pdc=mock(PersistentDataContainer.class);
        final Map<NamespacedKey,Double> values=new HashMap<>();
        final WildCombat combat;
        final NamespacedKey original,raised;
        final Map<UUID,LivingEntity> tracked;
        final UUID id=UUID.randomUUID();
        double base=96;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            when(plugin.getName()).thenReturn("WildlifeTest");
            combat=new WildCombat(plugin);
            original=(NamespacedKey)field("followOriginal").get(combat);
            raised=(NamespacedKey)field("followRaised").get(combat);
            tracked=(Map<UUID,LivingEntity>)field("raised").get(combat);
            values.put(original,16d);values.put(raised,96d);tracked.put(id,mob);
            when(mob.getUniqueId()).thenReturn(id);when(mob.isValid()).thenReturn(true);
            when(mob.getPersistentDataContainer()).thenReturn(pdc);
            when(mob.getAttribute(Attribute.FOLLOW_RANGE)).thenReturn(follow);
            when(follow.getBaseValue()).thenAnswer(i->base);
            doAnswer(i->{base=i.getArgument(0);return null;}).when(follow).setBaseValue(anyDouble());
            when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.DOUBLE))).thenAnswer(i->values.get(i.getArgument(0)));
            doAnswer(i->{values.remove(i.getArgument(0));return null;}).when(pdc).remove(any(NamespacedKey.class));
        }
        void pass() throws Exception { method("restorePass",long.class).invoke(combat,System.currentTimeMillis()); }
        void restore() throws Exception { method("restoreFollow",LivingEntity.class).invoke(combat,mob); }
        void pending(){assertTrue(tracked.containsKey(id));assertEquals(16d,values.get(original));assertEquals(96d,values.get(raised));}
        void complete(){assertFalse(tracked.containsKey(id));assertTrue(values.isEmpty());}
    }
    static Field field(String name)throws Exception{var f=WildCombat.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static Method method(String name,Class<?>...args)throws Exception{var m=WildCombat.class.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    @Test void restoreFailureKeepsOriginalAndRegistryThenRetries() throws Exception {
        var f=new Fixture();doThrow(new IllegalStateException("temporary")).doAnswer(i->{f.base=i.getArgument(0);return null;}).when(f.follow).setBaseValue(16d);
        f.pass();f.pending();assertEquals(96,f.base);
        f.pass();f.complete();assertEquals(16,f.base);assertEquals(1L,field("followRestored").getLong(f.combat));
    }
    @Test void attributeLookupFailureRetainsRetryInformation() throws Exception {
        var f=new Fixture();when(f.mob.getAttribute(Attribute.FOLLOW_RANGE)).thenThrow(new IllegalStateException("temporary")).thenReturn(f.follow);
        f.pass();f.pending();f.pass();f.complete();assertEquals(16,f.base);
    }
    @Test void baseLookupFailureRetainsRetryInformation() throws Exception {
        var f=new Fixture();when(f.follow.getBaseValue()).thenThrow(new IllegalStateException("temporary")).thenAnswer(i->f.base);
        f.pass();f.pending();f.pass();f.complete();assertEquals(16,f.base);
    }
    @Test void unavailableAttributeRetainsRecordUntilAvailable() throws Exception {
        var f=new Fixture();when(f.mob.getAttribute(Attribute.FOLLOW_RANGE)).thenReturn(null).thenReturn(f.follow);
        f.pass();f.pending();f.pass();f.complete();assertEquals(16,f.base);
    }
    @Test void externalValueChangeAbandonsOwnershipWithoutOverwriting() throws Exception {
        var f=new Fixture();f.base=48;f.pass();f.complete();assertEquals(48,f.base);verify(f.follow,never()).setBaseValue(anyDouble());
    }
    @Test void loadDiscoveredEntityRegistersForRetryBeforeFailure() throws Exception {
        var f=new Fixture();f.tracked.clear();doThrow(new IllegalStateException("temporary")).when(f.follow).setBaseValue(16d);
        assertThrows(InvocationTargetException.class,f::restore);f.pending();
    }
    @Test void disableRestoreFailureKeepsPersistentAndMemoryRecords() throws Exception {
        var f=new Fixture();doThrow(new IllegalStateException("temporary")).when(f.follow).setBaseValue(16d);
        f.combat.restoreAll();f.pending();
    }
}
