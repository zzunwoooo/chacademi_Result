package school.magiccodex.paper;

import java.util.*;

/** One local participant in the shared CAS protocol; neither local WAL nor a save callback grants ownership. */
final class BagSyncSession {
    enum Phase { VERIFYING, READY, SWITCHING, SAVING, SEALED, BLOCKED }
    private final UUID owner, lease=UUID.randomUUID();
    private Phase phase=Phase.VERIFYING;
    private BagSnapshotBinding expected;
    private UUID head, operation, latestCapture;
    private long sequence;
    private int active;
    private boolean captureRelease;
    BagSyncSession(UUID owner) {this.owner=Objects.requireNonNull(owner);}
    synchronized Phase phase(){return phase;}
    UUID lease(){return lease;}
    synchronized void initializeClean(UUID initialHead){
        if(phase!=Phase.VERIFYING||head!=null)throw new IllegalStateException("Session was already initialized");
        head=Objects.requireNonNull(initialHead);sequence=0;active=1;phase=Phase.READY;
    }
    synchronized void awaitLocalApply(BagSnapshotBinding binding){
        if(phase!=Phase.READY||!owner.equals(binding.owner())||!head.equals(binding.head())||sequence!=binding.sequence()||active!=binding.active())throw new IllegalStateException("Clean snapshot differs from created state");
        expected=binding;phase=Phase.VERIFYING;
    }
    synchronized void verifiedIncoming(BagSnapshotBinding binding, UUID centralHead, long centralSequence, int centralActive) {
        if(phase!=Phase.VERIFYING || !owner.equals(binding.owner()) || !centralHead.equals(binding.head()) || centralSequence!=binding.sequence() || centralActive!=binding.active())
            throw new IllegalStateException("Incoming bag snapshot is not the central state");
        expected=binding;head=centralHead;sequence=centralSequence;active=centralActive;
    }
    synchronized void complete(BagSnapshotBinding applied, String appliedInventory) {
        if(phase!=Phase.VERIFYING || expected==null || !expected.equals(applied))throw new IllegalStateException("No matching verified snapshot");
        applied.verify(owner,expected.snapshot(),appliedInventory);
        phase=Phase.READY;expected=null;
    }
    synchronized UUID beginSwitch(boolean huskLocked) {
        if(phase!=Phase.READY || huskLocked)throw new IllegalStateException("Bag session is not ready");
        phase=Phase.SWITCHING;return operation=UUID.randomUUID();
    }
    // Only invoke after durable WAL + central CAS + player file commit. Failure keeps the session blocked.
    synchronized void committedSwitch(UUID op, UUID nextHead, long nextSequence, int nextActive) {
        if(phase!=Phase.SWITCHING || !Objects.equals(operation,op) || nextHead.equals(head) || nextSequence<=sequence || nextActive<1 || nextActive>3)
            throw new IllegalStateException("Stale or incomplete bag switch commit");
        head=nextHead;sequence=nextSequence;active=nextActive;operation=null;phase=Phase.READY;
    }
    synchronized void seal() {
        if(phase==Phase.READY)phase=Phase.SEALED;
        else if(phase!=Phase.SEALED)phase=Phase.BLOCKED;
    }
    synchronized BagSnapshotBinding capture(UUID snapshot, String capturedInventory, String sameTickInventory) {
        if(phase!=Phase.READY && phase!=Phase.SEALED)throw new IllegalStateException("Cannot capture during synchronization/switch");
        if(!capturedInventory.equals(sameTickInventory))throw new IllegalStateException("Inventory changed during native snapshot capture");
        captureRelease=phase==Phase.SEALED;phase=Phase.SAVING;latestCapture=snapshot;
        return new BagSnapshotBinding(owner,snapshot,head,sequence,active,BagSnapshotBinding.hash(capturedInventory));
    }
    synchronized boolean nativeCommitVerified(BagSnapshotBinding binding, UUID databaseSnapshot, String databaseInventory, UUID redisSnapshot, String redisInventory) {
        if(!Objects.equals(latestCapture,binding.snapshot()) || !Objects.equals(head,binding.head()) || sequence!=binding.sequence() || active!=binding.active())return false;
        binding.verify(owner,databaseSnapshot,databaseInventory);
        binding.verify(owner,redisSnapshot,redisInventory);
        return phase==Phase.SAVING && captureRelease; // Store must still CAS the exact lease+head before releasing it.
    }
    synchronized void finishCapture(boolean success){
        if(!success){phase=Phase.BLOCKED;return;}
        if(phase!=Phase.SAVING)throw new IllegalStateException("Capture phase changed");
        phase=captureRelease?Phase.SEALED:Phase.READY;
    }
    synchronized void block(){phase=Phase.BLOCKED;}
}
