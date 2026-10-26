package school.magiccodex.paper;
import java.util.*;
import java.util.function.BiConsumer;
import net.william278.husksync.HuskSync;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.user.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BagLockstepSyncerTest {
 private final HuskSync husk=mock(HuskSync.class,RETURNS_DEEP_STUBS);
 private final BukkitHuskSyncAPI api=mock(BukkitHuskSyncAPI.class);
 private final BagHuskCoordinator bags=mock(BagHuskCoordinator.class);
 private BagLockstepSyncer hook(){when(api.getPlugin()).thenReturn(husk);return new BagLockstepSyncer(api,bags);}
 @Test void disconnectUsesCoordinatorAndDoesNotScheduleTheNativeAsyncCapture(){var u=mock(OnlineUser.class);when(bags.save(u,DataSnapshot.SaveCause.DISCONNECT)).thenReturn(true);hook().syncSaveUserData(u);verify(bags).save(u,DataSnapshot.SaveCause.DISCONNECT);verify(husk,never()).runAsync(any(Runnable.class));}
 @Test void shutdownCannotDependOnDataSaveEvent(){assertFalse(DataSnapshot.SaveCause.SERVER_SHUTDOWN.fireDataSaveEvent());var u=mock(OnlineUser.class);when(bags.save(u,DataSnapshot.SaveCause.SERVER_SHUTDOWN)).thenReturn(true);hook().saveCurrentUserData(u,DataSnapshot.SaveCause.SERVER_SHUTDOWN);verify(bags).save(u,DataSnapshot.SaveCause.SERVER_SHUTDOWN);}
 @Test void prevalidatedSnapshotPersistsExactlyTheSameObjectWithoutFiringAnotherEvent(){var packed=mock(DataSnapshot.Packed.class);UUID id=UUID.randomUUID();when(packed.getId()).thenReturn(id);when(bags.prevalidatedSnapshot(id)).thenReturn(true);var u=mock(User.class);BiConsumer<User,DataSnapshot.Packed> callback=mock(BiConsumer.class);hook().saveData(u,packed,callback);verify(husk.getDatabase()).addSnapshot(same(u),same(packed));verify(callback).accept(same(u),same(packed));verify(husk,never()).fireEvent(any(),any());}
 @Test void nativeTerminateDrainsBeforeClosingOurExecutorAndBeforeClearingCheckout(){
  var calls=new ArrayList<String>();
  var redis=new net.william278.husksync.redis.RedisManager(husk){@Override public void clearUsersCheckedOutOnServer(){calls.add("nativeCheckoutClear");}};
  doReturn(redis).when(husk).getRedisManager();
  doAnswer(i->{calls.add("drain");return null;}).when(bags).beforeBridgeDisable();
  doAnswer(i->{calls.add("close");return null;}).when(bags).terminate();
  hook().terminate();assertEquals(List.of("drain","close","nativeCheckoutClear"),calls);
 }
 @Test void cleanFirstUserIsCapturedByCoordinatorBeforeNativeCompleteAndUnlock(){var u=mock(OnlineUser.class);when(bags.bootstrap(u)).thenReturn(true);hook().setUserFromDatabase(u);verify(bags).bootstrap(u);verify(u,never()).completeSync(anyBoolean(),any(),any());}
 @Test void ordinaryAutosaveUsesTheSameCoordinatorBoundary(){var u=mock(OnlineUser.class);when(bags.save(u,DataSnapshot.SaveCause.WORLD_SAVE)).thenReturn(true);hook().saveCurrentUserData(u,DataSnapshot.SaveCause.WORLD_SAVE);verify(bags).save(u,DataSnapshot.SaveCause.WORLD_SAVE);}
}
