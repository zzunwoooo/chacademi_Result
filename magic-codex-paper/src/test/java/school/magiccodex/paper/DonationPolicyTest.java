package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.protocol.SchoolProtocol.Donation;
import static org.junit.jupiter.api.Assertions.*;
class DonationPolicyTest {
    @TempDir Path dir;
    private Donation row(String spell){return new Donation(spell,"Test",UUID.randomUUID(),"User",0,1);}
    @Test void blockAppliesBeforeFirstDonationAndSurvivesResetAndRestart()throws Exception{
        Path file=dir.resolve("policy.db");try(var s=new SchoolStore(file)){s.setBlocked("wind",true);assertThrows(SchoolStore.DonationBlocked.class,()->s.donate(row("wind"),5));assertEquals(0L,s.snapshot().scores().getFirst());s.reset("all");assertTrue(s.snapshot().blocked().contains("wind"));}
        try(var s=new SchoolStore(file)){assertThrows(SchoolStore.DonationBlocked.class,()->s.donate(row("wind"),5));s.setBlocked("wind",false);assertTrue(s.donate(row("wind"),5));s.setBlocked("wind",true);assertEquals(1,s.snapshot().records().size());assertEquals(5L,s.snapshot().scores().getFirst());}
    }
    @Test void anotherConnectionCannotDonateAfterCommittedBlockEvenWithOldSnapshot()throws Exception{
        Path file=dir.resolve("shared.db");try(var a=new SchoolStore(file);var b=new SchoolStore(file)){assertTrue(b.snapshot().blocked().isEmpty());a.setBlocked("wind",true);assertThrows(SchoolStore.DonationBlocked.class,()->b.donate(row("wind"),5));assertTrue(b.snapshot().records().isEmpty());}
    }
    @Test void concurrentDonorsUseOneRecordAndOneScoreAward()throws Exception{
        Path file=dir.resolve("race.db");try(var a=new SchoolStore(file);var b=new SchoolStore(file)){
            var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try{var one=pool.submit(()->{gate.await();return a.donate(row("wind"),5);});var two=pool.submit(()->{gate.await();return b.donate(row("wind"),5);});gate.countDown();assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));assertEquals(5L,a.snapshot().scores().getFirst());}
            finally{pool.shutdownNow();}
        }
    }
}
