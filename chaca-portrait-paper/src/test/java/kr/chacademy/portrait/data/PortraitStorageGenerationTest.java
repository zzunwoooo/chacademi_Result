package kr.chacademy.portrait.data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class PortraitStorageGenerationTest {
    @TempDir Path dir;
    @Test void olderGenerationCannotOverwriteOrReleaseNewerClaim()throws Exception {
        var db=new Database(dir,Logger.getAnonymousLogger());
        try {db.call(()->{
            var s=new PortraitStorage(db);s.init();UUID id=UUID.randomUUID();
            long old=s.claimGeneration(id,"school",60000);
            assertTrue(s.commitResult(id,null,"old",new byte[]{1},"model","admin","",0,"2026-10-10","school",old));
            s.releaseGeneration(id,"school",old);
            long next=s.claimGeneration(id,"school",60000);assertTrue(next>old);
            assertTrue(s.commitResult(id,null,"new",new byte[]{2},"model","admin","",0,"2026-10-10","school",next));
            assertFalse(s.commitResult(id,null,"late",new byte[]{3},"model","admin","",0,"2026-10-10","school",old));
            s.releaseGeneration(id,"school",old);
            assertEquals(0,s.claimGeneration(id,"wild",60000));
            assertEquals("new",s.portrait(id).sha());assertArrayEquals(new byte[]{2},s.portrait(id).png());
            return null;
        }).get();}finally{db.close();}
    }
    @Test void failedRerollCommitLeavesExistingPortrait()throws Exception {
        var db=new Database(dir,Logger.getAnonymousLogger());
        try{db.call(()->{var s=new PortraitStorage(db);s.init();UUID id=UUID.randomUUID();
            s.savePortrait(id,"old",new byte[]{1},"model","admin","",0);
            long generation=s.claimGeneration(id,"school",60000);
            assertFalse(s.commitResult(id,"missing-token","bad",new byte[]{2},"model","reroll","",0,"2026-10-10","school",generation));
            assertEquals("old",s.sha(id));return null;
        }).get();}finally{db.close();}
    }
}
