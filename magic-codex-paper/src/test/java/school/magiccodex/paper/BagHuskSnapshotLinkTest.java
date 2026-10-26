package school.magiccodex.paper;
import java.util.*;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.event.*;
import net.william278.husksync.user.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class BagHuskSnapshotLinkTest {
 final UUID owner=UUID.randomUUID(),snapshot=UUID.randomUUID();
 final BagHuskSnapshotLink.Authority authority=mock(BagHuskSnapshotLink.Authority.class);
 final BukkitHuskSyncAPI api=mock(BukkitHuskSyncAPI.class);
 BagHuskSnapshotLink link(){return new BagHuskSnapshotLink(mock(JavaPlugin.class),api,authority);}
 @Test void managedNativeSaveDoesNotRecaptureOrFireASecondCommitObserver(){var e=mock(BukkitDataSaveEvent.class);var u=mock(User.class);var p=mock(DataSnapshot.Packed.class);when(u.getUuid()).thenReturn(owner);when(e.getUser()).thenReturn(u);when(e.getData()).thenReturn(p);when(p.getId()).thenReturn(snapshot);when(authority.managedSnapshot(owner,snapshot)).thenReturn(true);link().beforeSave(e);verify(authority,never()).block(any(),any());verify(e,never()).setCancelled(true);}
 @Test void externalSaveBlocksOnlyBagAndDoesNotCancelNativeApiRestore(){var e=mock(BukkitDataSaveEvent.class);var u=mock(User.class);var p=mock(DataSnapshot.Packed.class);when(u.getUuid()).thenReturn(owner);when(e.getUser()).thenReturn(u);when(e.getData()).thenReturn(p);when(p.getId()).thenReturn(snapshot);link().beforeSave(e);verify(authority).block(eq(owner),contains("External"));verify(e,never()).setCancelled(true);}
 @Test void externalApiApplyRemainsNativeButCannotMakeBagReady(){var e=mock(BukkitPreSyncEvent.class);var u=mock(OnlineUser.class);var p=mock(DataSnapshot.Packed.class);when(u.getUuid()).thenReturn(owner);when(e.getUser()).thenReturn(u);when(e.getData()).thenReturn(p);when(p.getId()).thenReturn(snapshot);when(e.getSaveCause()).thenReturn(DataSnapshot.SaveCause.API);link().beforeApply(e);verify(e,never()).setCancelled(true);verify(authority).block(eq(owner),contains("External"));verify(authority,never()).complete(any(),any());}
}
