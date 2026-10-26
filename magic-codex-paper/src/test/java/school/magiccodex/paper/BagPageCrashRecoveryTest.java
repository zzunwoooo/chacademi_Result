package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BagPageCrashRecoveryTest {
    @TempDir Path dir;
    private static byte[] page(int item) {
        var slots = new ArrayList<byte[]>();
        for (int i=0;i<27;i++) slots.add(i==0 ? new byte[]{(byte)item} : new byte[0]);
        return BagPageCodec.encodePage(slots);
    }
    private static byte[][] after(byte[] outgoing) { return new byte[][]{outgoing,new byte[0],new byte[0]}; }
    @Test void crashAfterWalBeforePlayerSaveRetainsBothOriginalPages() throws Exception {
        var id=UUID.randomUUID();var j=new BagPageCodec.Journal(dir.resolve("wal"));
        byte[] original=page(11), target=page(22);
        j.save(id,"school",2,100,after(original),target);
        var reopened=new BagPageCodec.Journal(dir.resolve("wal")).read(id,"school");
        assertEquals(BagPageRecovery.Decision.RESTORE_TARGET,BagPageRecovery.decide(0,reopened.seq()));
        assertArrayEquals(original,reopened.pages()[0]);
        assertArrayEquals(target,reopened.incoming());
        assertEquals(2,reopened.active());
    }
    @Test void dbReplayBeforePlayerSaveCannotDiscardRecoveryTarget() throws Exception {
        var id=UUID.randomUUID();var j=new BagPageCodec.Journal(dir.resolve("wal"));
        byte[] original=page(11), target=page(22);
        j.save(id,"school",2,100,after(original),target);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("bag.db"))) {
            var sql=new BagPagesSql(c,false);sql.init();var e=j.read(id,"school");
            sql.save(id,"school",new BagPagesSql.Saved(e.active(),e.seq(),e.pages()));
            assertEquals(100,sql.load(id,"school").seq());
            // DB success is not a player-file commit. WAL is still the recovery source.
            j.acknowledge(id,"school",100);
            assertArrayEquals(target,j.read(id,"school").incoming());
            assertEquals(BagPageRecovery.Decision.RESTORE_TARGET,BagPageRecovery.decide(0,100));
        }
    }
    @Test void crashAfterPlayerSaveDoesNotOverwriteLaterInventoryChanges() throws Exception {
        var j=new BagPageCodec.Journal(dir.resolve("wal"));var id=UUID.randomUUID();
        j.save(id,"school",2,100,after(page(11)),page(22));
        byte[] current=page(33); // player changed the active inventory after the successful switch
        var decision=BagPageRecovery.decide(100,j.read(id,"school").seq());
        if(decision==BagPageRecovery.Decision.RESTORE_TARGET) current=j.read(id,"school").incoming();
        assertArrayEquals(page(33),current);
        assertEquals(BagPageRecovery.Decision.KEEP_CURRENT,decision);
    }
    @Test void secondSwitchSupersedesWalWithoutLosingTheFirstActivePage() throws Exception {
        var j=new BagPageCodec.Journal(dir.resolve("wal"));var id=UUID.randomUUID();
        j.save(id,"school",2,100,after(page(11)),page(22));
        j.save(id,"school",1,101,new byte[][]{new byte[0],page(23),new byte[0]},page(11));
        var e=j.read(id,"school");
        assertEquals(BagPageRecovery.Decision.RESTORE_TARGET,BagPageRecovery.decide(100,e.seq()));
        assertArrayEquals(page(11),e.incoming());assertArrayEquals(page(23),e.pages()[1]);
        assertEquals(1,j.pending().size());
    }
    @Test void emptyTargetIsRecoverableAndDifferentFromLegacyMissingSnapshot() throws Exception {
        var j=new BagPageCodec.Journal(dir.resolve("wal"));var id=UUID.randomUUID();
        j.save(id,"school",2,100,after(page(11)),new byte[0]);
        assertTrue(j.read(id,"school").recoverable());assertEquals(0,j.read(id,"school").incoming().length);
        j.save(id,"wild",2,100,after(page(11)));
        assertFalse(j.read(id,"wild").recoverable());
    }
    @Test void newerPlayerMarkerCannotBeMappedToStaleBagState() {
        assertThrows(IllegalStateException.class,()->BagPageRecovery.decide(101,100));
        assertThrows(IllegalStateException.class,()->BagPageRecovery.decide(-1,100));
    }
    @Test void staleReplayCannotOverwriteNewerDbPagesAndUnlockIsGlobal() throws Exception {
        var id=UUID.randomUUID();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("bag.db"))) {
            var sql=new BagPagesSql(c,false);sql.init();
            assertEquals(2,sql.unlockOne(id,3));assertEquals(3,sql.unlockOne(id,3));assertEquals(-1,sql.unlockOne(id,3));
            sql.save(id,"school",new BagPagesSql.Saved(2,101,after(page(11))));
            sql.save(id,"school",new BagPagesSql.Saved(1,100,after(page(99))));
            assertEquals(101,sql.load(id,"school").seq());assertArrayEquals(page(11),sql.load(id,"school").pages()[0]);
            assertTrue(c.getAutoCommit());
        }
    }
}
