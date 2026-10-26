package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class StorySafetyTest {
    @TempDir Path temp;
    @Test void persistsBeforeEffectAndRejectsDuplicateAfterReload() throws Exception {
        Path p=temp.resolve("progress.yml");Set<String> fired=new HashSet<>();AtomicInteger effects=new AtomicInteger();
        assertTrue(StorySafety.once(fired,"c_wake_1",()->{
            try {StorySafety.atomicWrite(p,"c_wake_1");return true;}catch(Exception e){throw new AssertionError(e);}
        },()->{assertTrue(Files.exists(p));effects.incrementAndGet();}));
        Set<String> reloaded=new HashSet<>(Set.of(Files.readString(p)));
        assertFalse(StorySafety.once(reloaded,"c_wake_1",()->fail("duplicate persisted"),effects::incrementAndGet));
        assertEquals(1,effects.get());
    }
    @Test void failedPersistenceDoesNotRunOrConsumeEvent() {
        Set<String> fired=new HashSet<>();AtomicInteger effects=new AtomicInteger();
        assertFalse(StorySafety.once(fired,"e_wake",()->false,effects::incrementAndGet));
        assertTrue(fired.isEmpty());assertEquals(0,effects.get());
        assertTrue(StorySafety.once(fired,"e_wake",()->true,effects::incrementAndGet));
        assertEquals(1,effects.get());
    }
    @Test void undeclaredLegacyAndPathEventsAreRejected() {
        assertTrue(StorySafety.declaredEvent("c_wake_1",Set.of("c_wake_1"),Set.of()));
        assertTrue(StorySafety.declaredEvent("e_wake",Set.of(),Set.of("e_wake")));
        for(String event:List.of("","forged","../end","affinity.end","x".repeat(65)))
            assertFalse(StorySafety.declaredEvent(event,Set.of("c_wake_1"),Set.of()));
    }
    @Test void atomicReplacementLeavesReadableLatestState() throws Exception {
        Path p=temp.resolve("progress.yml");StorySafety.atomicWrite(p,"old");StorySafety.atomicWrite(p,"new");
        assertEquals("new",Files.readString(p));
        try(var files=Files.list(temp)){assertEquals(1,files.count());}
    }
    @Test void failedAtomicReplacementPreservesExistingTarget() throws Exception {
        Path p=temp.resolve("progress.yml");Files.createDirectory(p);Files.writeString(p.resolve("keep"),"old");
        assertThrows(java.io.IOException.class,()->StorySafety.atomicWrite(p,"new"));
        assertEquals("old",Files.readString(p.resolve("keep")));
    }
    @Test void effectFailureRemainsClaimedRatherThanDuplicated() {
        Set<String> fired=new HashSet<>();
        assertThrows(IllegalStateException.class,()->StorySafety.once(fired,"e_wake",()->true,()->{throw new IllegalStateException();}));
        assertTrue(fired.contains("e_wake"));
        assertFalse(StorySafety.once(fired,"e_wake",()->true,()->fail("must not replay")));
    }
    @Test void nicknameForConsoleCommandsKeepsOnlyLettersDigitsUnderscoreHyphen() {
        assertEquals("별빛_01-a",StorySafety.safeNickname("별빛_01-a","Steve"));
        assertEquals("atypeplayerop",StorySafety.safeNickname("@a[type=player] \"; op\n","Steve"));
        assertEquals("Steve",StorySafety.safeNickname(" @[]{}\n","Steve"));
        assertEquals("Steve",StorySafety.safeNickname(null,"Steve"));
        assertEquals(StorySafety.NICKNAME_MAX,StorySafety.safeNickname("가".repeat(200),"Steve").length());
        for(String bad:List.of("a b","a\nb","a;b","a\"b","{player}","%x%"))
            assertTrue(StorySafety.safeNickname(bad,"Steve").matches("[\\p{L}\\p{N}_-]+"),bad);
    }
    @Test void tokenBucketAllowsBurstThenRefillsAtRate() {
        var b=new StorySafety.TokenBucket(20,40);long t=1_000_000_000L;int ok=0;
        for(int i=0;i<1000;i++)if(b.tryTake(t))ok++;
        assertEquals(40,ok);assertEquals(960,b.dropped);                // 한꺼번에 와도 40개까지만
        ok=0;for(int i=0;i<1000;i++)if(b.tryTake(t+500_000_000L))ok++;
        assertEquals(10,ok);                                            // 0.5초 뒤 10개
        ok=0;for(int i=0;i<1000;i++)if(b.tryTake(t+3_600_000_000_000L))ok++;
        assertEquals(40,ok);                                            // 오래 쉬어도 40개를 넘지 않음
    }
    @Test void sharedIdRuleMatchesFormatDocument() {
        for(String ok:List.of("a","ch1_wakeup","c_wake_1","e-2","x".repeat(64)))assertTrue(StorySafety.validId(ok),ok);
        for(String bad:List.of("","Wake","장면","a b","a.b","a/b","x".repeat(65)))assertFalse(StorySafety.validId(bad),bad);
        assertFalse(StorySafety.validId(null));
    }
}
