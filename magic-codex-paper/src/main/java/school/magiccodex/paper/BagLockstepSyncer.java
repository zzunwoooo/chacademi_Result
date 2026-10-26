package school.magiccodex.paper;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.sync.LockstepDataSyncer;
import net.william278.husksync.user.OnlineUser;
import net.william278.husksync.user.User;
import java.util.function.BiConsumer;
/** Owns only the native LOCKSTEP extension point; all non-enrolled users retain the native path. */
final class BagLockstepSyncer extends LockstepDataSyncer {
 private final BagHuskCoordinator bags;
 BagLockstepSyncer(BukkitHuskSyncAPI api,BagHuskCoordinator bags){super(api.getPlugin());this.bags=bags;}
 @Override protected void setUserFromDatabase(OnlineUser user){
  if(getDatabase().getLatestSnapshot(user).isEmpty()&&bags.bootstrap(user))return;
  super.setUserFromDatabase(user);
 }
 @Override public void syncApplyUserData(OnlineUser user){bags.joining(user.getUuid());super.syncApplyUserData(user);}
 @Override public void syncSaveUserData(OnlineUser user){if(!bags.save(user,DataSnapshot.SaveCause.DISCONNECT))super.syncSaveUserData(user);}
 @Override public void saveCurrentUserData(OnlineUser user,DataSnapshot.SaveCause cause){if(!bags.save(user,cause))super.saveCurrentUserData(user,cause);}
 @Override public void saveData(User user,DataSnapshot.Packed snapshot,BiConsumer<User,DataSnapshot.Packed> after){
  if(bags.prevalidatedSnapshot(snapshot.getId())){
   getDatabase().addSnapshot(user,snapshot);
   if(after!=null)after.accept(user,snapshot);
  }else if(user instanceof OnlineUser online && snapshot.getSaveCause().equals(DataSnapshot.SaveCause.SAVE_COMMAND) && bags.save(online,snapshot.getSaveCause(),after)){
   // Direct normal/manual save entry point is captured by the same native boundary.
  }else super.saveData(user,snapshot,after);
 }
 @Override public void terminate(){
  // BukkitHuskSync.onDisable calls terminate BEFORE handlePluginDisable's SERVER_SHUTDOWN saves.
  // Drain while native DB/Redis and our CAS executor are still open; later owned saves are idempotent no-ops.
  bags.beforeBridgeDisable();
  try{bags.terminate();}finally{super.terminate();}
 }
}
