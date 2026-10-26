package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class MagicCodexLinkTest {
    public static class Facade {
        String source;int amount;
        public int apiVersion(){return 1;}
        public String playerName(Player p){return "별빛";}
        public CompletableFuture<int[]> affinity(UUID p,String npc){return CompletableFuture.completedFuture(new int[]{37,1});}
        public CompletableFuture<int[]> addAffinity(UUID p,String npc,String source,int amount){
            this.source=source;this.amount=amount;return CompletableFuture.completedFuture(new int[]{39,1,2});
        }
    }
    public static class Unsupported extends Facade {public int apiVersion(){return 2;}}
    public static class Failed extends Facade {
        public CompletableFuture<int[]> affinity(UUID p,String npc){return CompletableFuture.failedFuture(new IllegalStateException());}
        public CompletableFuture<int[]> addAffinity(UUID p,String n,String s,int a){return CompletableFuture.completedFuture(new int[0]);}
    }
    @Test void apiOneNicknameAndAffinityUseExactSignatures() throws Exception {
        var link=new MagicCodexLink(Logger.getAnonymousLogger());var fake=new Facade();
        assertTrue(link.bind(Facade.class,fake));assertTrue(link.available());assertEquals("별빛",link.nickname(null));
        assertEquals(37,link.affinity(UUID.randomUUID(),"teacher").join());
        assertEquals(39,link.addAffinity(UUID.randomUUID(),"teacher",2).join());
        assertEquals("dialogue",fake.source);assertEquals(2,fake.amount);
    }
    @Test void unsupportedVersionDoesNotBind() throws Exception {
        var link=new MagicCodexLink(Logger.getAnonymousLogger());
        assertFalse(link.bind(Unsupported.class,new Unsupported()));assertFalse(link.available());
    }
    @Test void failedAndMalformedResponsesRemainSafe() throws Exception {
        var link=new MagicCodexLink(Logger.getAnonymousLogger());link.bind(Failed.class,new Failed());
        assertNull(link.affinity(UUID.randomUUID(),"teacher").join());
        assertNull(link.addAffinity(UUID.randomUUID(),"teacher",2).join());
    }
    public static class Resolver extends Facade {
        String input;
        public Player resolvePlayer(String input){this.input=input;return null;}
    }
    @Test void delegatesExactNicknameResolutionAndPreservesAmbiguousOrMissingResult()throws Exception {
        var link=new MagicCodexLink(Logger.getAnonymousLogger());var resolver=new Resolver();link.bind(Resolver.class,resolver);
        assertNull(link.resolvePlayer("한글 닉네임"));assertEquals("한글 닉네임",resolver.input);
        assertNull(link.resolvePlayer("UnknownAccount"));assertEquals("UnknownAccount",resolver.input);
        assertTrue(link.available());
    }
}
