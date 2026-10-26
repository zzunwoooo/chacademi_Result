package school.magiccodex.paper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.*;

/** Immutable binding of bag ownership/page generation to one exact HuskSync inventory snapshot. */
record BagSnapshotBinding(UUID owner, UUID snapshot, UUID head, long sequence, int active, String inventoryHash) implements Data {
    BagSnapshotBinding {
        Objects.requireNonNull(owner); Objects.requireNonNull(snapshot); Objects.requireNonNull(head);
        if(sequence<0 || active<1 || active>3 || inventoryHash==null || !inventoryHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid bag snapshot binding");
    }
    void verify(UUID expectedOwner, UUID snapshotId, String inventory) {
        if(!owner.equals(expectedOwner) || !snapshot.equals(snapshotId) || !inventoryHash.equals(hash(inventory)))
            throw new IllegalStateException("Bag and inventory snapshot identities differ");
    }
    static String hash(String inventory) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inventory.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    // HuskSync installs custom data in the same application pass as inventory. Readiness is granted by the link
    // only after SyncComplete and central CAS validation; applying this value never writes vanilla inventory.
    @Override public void apply(UserDataHolder user, HuskSync plugin) { }
    static final class Codec implements Serializer<BagSnapshotBinding> {
        @Override public String serialize(BagSnapshotBinding b) {
            return "1|"+b.owner+"|"+b.snapshot+"|"+b.head+"|"+b.sequence+"|"+b.active+"|"+b.inventoryHash;
        }
        @Override public BagSnapshotBinding deserialize(String text) {
            if(text==null || text.length()>256) throw new IllegalArgumentException("Invalid bag binding size");
            String[] p=text.split("\\|",-1);
            if(p.length!=7 || !p[0].equals("1")) throw new IllegalArgumentException("Invalid bag binding version");
            return new BagSnapshotBinding(UUID.fromString(p[1]),UUID.fromString(p[2]),UUID.fromString(p[3]),Long.parseLong(p[4]),Integer.parseInt(p[5]),p[6]);
        }
    }
}
