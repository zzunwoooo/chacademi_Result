package school.magiccodex.paper;

import java.util.*;
import java.util.concurrent.*;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.*;
import net.william278.husksync.event.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Version-pinned 3.8.7 API boundary. Not enabled until the shared CAS store/migration is ready. */
final class BagHuskSnapshotLink implements Listener {
    static final Identifier ID=Identifier.from("magiccodexbridge","bag_page_state",Set.of(Identifier.Dependency.optional(Identifier.INVENTORY.getKey())));
    interface Authority {
        default boolean managedSnapshot(UUID owner, UUID snapshot) { return false; }
        // Must validate the central head/session using CAS; must never infer ownership from local WAL or wall time.
        CompletionStage<Boolean> verifyIncoming(BagSnapshotBinding binding);
        void complete(UUID owner, BagSnapshotBinding binding);
        default void complete(UUID owner,BagSnapshotBinding binding,String originalInventory){complete(owner,binding);}
        void block(UUID owner, String reason);
    }
    private final JavaPlugin plugin;
    private final BukkitHuskSyncAPI api;
    private final Authority authority;
    private final Map<UUID,Long> epochs=new HashMap<>();
    private final Map<UUID,BagSnapshotBinding> pending=new HashMap<>();
    private final Map<UUID,UUID> approved=new HashMap<>();
    private final Map<UUID,String> pendingOriginalInventory=new HashMap<>();
    BagHuskSnapshotLink(JavaPlugin plugin, BukkitHuskSyncAPI api, Authority authority) {
        this.plugin=plugin;this.api=api;this.authority=authority;
    }
    void approveLocalSnapshot(UUID owner,BagSnapshotBinding binding){
        pending.put(owner,binding);approved.put(owner,binding.snapshot());
    }
    void register() {
        var husk=plugin.getServer().getPluginManager().getPlugin("HuskSync");
        if(husk==null || !husk.isEnabled() || !husk.getPluginMeta().getVersion().startsWith("3.8.7"))
            throw new IllegalStateException("Shared bag requires enabled, validated HuskSync 3.8.7");
        api.registerDataSerializer(ID,new BagSnapshotBinding.Codec());
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private String inventory(DataSnapshot.Unpacked s) {
        Data inv=s.getData().get(Identifier.INVENTORY);
        if(inv==null)throw new IllegalStateException("Missing matching inventory block");
        return api.getDataSerializer(Identifier.INVENTORY).orElseThrow().serialize(inv);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void beforeSave(BukkitDataSaveEvent e) {
        UUID owner=e.getUser().getUuid();
        if(authority.managedSnapshot(owner,e.getData().getId())) return;
        if(DataSnapshot.SaveCause.DEATH.equals(e.getSaveCause())) return; // historical drops snapshot, not current-state mutation
        // Existing admin/API restore behavior remains native. Its bag is unavailable until reconciled.
        authority.block(owner,"External native save/update; shared bag requires reconciliation");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void beforeApply(BukkitPreSyncEvent e) {
        UUID owner=e.getUser().getUuid();UUID snapshot=e.getData().getId();
        if(snapshot.equals(approved.remove(owner))) return;
        String cause=e.getSaveCause().name();
        if(!Set.of("DISCONNECT","SERVER_SHUTDOWN","WORLD_SAVE","SAVE_COMMAND").contains(cause)) {
            authority.block(owner,"External native restore/update; shared bag requires reconciliation");return;
        }
        var preliminary=api.unpackSnapshot(e.getData());
        if(!(preliminary.getData().get(ID) instanceof BagSnapshotBinding)) {
            authority.block(owner,"No reviewed shared bag bootstrap; vanilla synchronization remains native");return;
        }
        // Deferred apply: HuskSync's lock stays held while the bag is verified. If verification fails, throws or
        // hangs, the player falls back to native sync (only the bag stays blocked) instead of staying locked.
        e.setCancelled(true);authority.block(owner,"Verifying shared bag snapshot");
        long epoch=epochs.merge(owner,1L,Long::sum);pending.remove(owner);pendingOriginalInventory.remove(owner);
        var packed=e.getData();
        try {
            var data=api.unpackSnapshot(packed);var raw=data.getData().get(ID);
            if(!(raw instanceof BagSnapshotBinding binding))throw new IllegalStateException("Snapshot requires reviewed bag bootstrap/migration");
            String originalInventory=BagInventoryProof.originalInventory(packed,api.getPlugin());
            binding.verify(owner,snapshot,originalInventory);
            if(!BagInventoryProof.equivalent(originalInventory,inventory(data)))throw new IllegalStateException("Decoded inventory differs beyond text color case");
            authority.verifyIncoming(binding).whenComplete((ok,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(!Objects.equals(epochs.get(owner),epoch))return;
                var user=api.getOnlineUser(owner).filter(u->!u.hasDisconnected()).orElse(null);
                if(user==null)return;
                if(error!=null || !Boolean.TRUE.equals(ok)){releaseNative(owner,packed,"bag ownership check failed");return;}
                epochs.merge(owner,1L,Long::sum); // decided: the timeout below must not fire
                pending.put(owner,binding);pendingOriginalInventory.put(owner,originalInventory);approved.put(owner,snapshot);
                user.applySnapshot(packed,DataSnapshot.UpdateCause.SYNCHRONIZED);
            }));
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                if(Objects.equals(epochs.get(owner),epoch))releaseNative(owner,packed,"bag verification timed out");
            },VERIFY_TIMEOUT_TICKS);
        } catch(RuntimeException ex){
            String reason="bag snapshot validation failed: "+ex.getClass().getSimpleName();
            plugin.getServer().getScheduler().runTask(plugin,()->{if(Objects.equals(epochs.get(owner),epoch))releaseNative(owner,packed,reason);});
        }
    }
    private static final long VERIFY_TIMEOUT_TICKS=20L*20;
    /**
     * Native fallback: an "External" block makes the coordinator stop owning this player's saves (native HuskSync
     * saves resume) and drops the bag binding, so only the bag stays blocked until reconciled. The stored native
     * snapshot is then applied as HuskSync would without the bag hook, which completes the sync and unlocks the player.
     */
    private void releaseNative(UUID owner,DataSnapshot.Packed packed,String reason){
        epochs.merge(owner,1L,Long::sum);pending.remove(owner);pendingOriginalInventory.remove(owner);
        authority.block(owner,"External fallback after "+reason+"; native sync continues, bag stays blocked");
        var user=api.getOnlineUser(owner).filter(u->!u.hasDisconnected()).orElse(null);
        if(user==null)return;
        approved.put(owner,packed.getId());
        user.applySnapshot(packed,DataSnapshot.UpdateCause.SYNCHRONIZED);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void afterApply(BukkitSyncCompleteEvent e) {
        UUID owner=e.getUser().getUuid();var expected=pending.remove(owner);String originalInventory=pendingOriginalInventory.remove(owner);
        // Mismatches go "External" too: a blocked non-external holder is re-locked on every later save.
        if(expected==null){authority.block(owner,"External fallback: no verified shared bag snapshot");return;}
        var actual=e.getUser().getCustomDataStore().get(ID);
        if(!expected.equals(actual)){authority.block(owner,"External fallback: applied bag snapshot identity differs");return;}
        authority.complete(owner,expected,originalInventory);
        // HuskSync performs its own unlock after this event. Bag input must also check OnlineUser.isLocked().
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        UUID owner=e.getPlayer().getUniqueId();epochs.merge(owner,1L,Long::sum);pending.remove(owner);pendingOriginalInventory.remove(owner);approved.remove(owner);
        // The LOCKSTEP hook already sealed a participant before the native quit callback.
        // Do not invalidate its immutable, in-flight handoff merely because this quit event finished.
    }
}
