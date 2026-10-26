package school.magiccodex.paper;

import java.util.*;

/** Exact normal-save handoff. A callback alone is never a durability or ownership proof. */
final class BagCommitFlow {
    record Proof(UUID snapshot,String inventory,BagSnapshotBinding binding) { }
    interface Port {
        boolean reserve() throws Exception;
        void writeDatabase() throws Exception;
        Proof readDatabase() throws Exception;
        void writeRedis() throws Exception;
        Proof peekRedis() throws Exception; // non-consuming GET, never HuskSync.getUserData()
        boolean acknowledgeCas(boolean release) throws Exception;
        void releaseCheckout() throws Exception;
        void unlock();
        void blocked(String stage,Exception failure);
    }
    static boolean run(BagSnapshotBinding binding,boolean release,boolean unlockAfter,Port port) {
        String stage="reserve";
        try {
            if(!port.reserve())throw new IllegalStateException("Capture fence lost");
            stage="database-write";port.writeDatabase();
            stage="database-readback";verify(binding,port.readDatabase());
            stage="redis-write";port.writeRedis();
            stage="redis-readback";verify(binding,port.peekRedis());
            stage="shared-cas";if(!port.acknowledgeCas(release))throw new IllegalStateException("Shared ownership CAS lost");
            if(release){stage="checkout-release";port.releaseCheckout();}
            if(unlockAfter)port.unlock();
            return true;
        } catch(Exception ex){port.blocked(stage,ex);return false;}
    }
    static void verify(BagSnapshotBinding expected,Proof proof) {
        if(proof==null || !expected.equals(proof.binding()))throw new IllegalStateException("Missing or mismatched bag proof");
        expected.verify(expected.owner(),proof.snapshot(),proof.inventory());
    }
    private BagCommitFlow(){}
}
