package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncUiLoaderTest {
    @Test void deduplicatesAndBoundsWorkWithoutBlockingCaller(){
        var work=new ArrayDeque<Runnable>();var freed=new ArrayList<String>();
        var loader=new AsyncUiLoader<String,String>(work::add,2,k->k+" loaded",freed::add);
        loader.request("a");loader.request("a");loader.request("b");loader.request("c");
        assertEquals(2,work.size());assertNull(loader.takeReady());
        work.remove().run();var result=loader.takeReady();assertEquals("a loaded",result.getValue().value());
        loader.close();work.remove().run();assertTrue(freed.isEmpty());assertEquals(0,loader.size());
    }
    @Test void reloadDisposesUnconsumedBuffersAndSkipsQueuedWork(){
        var work=new ArrayDeque<Runnable>();var freed=new ArrayList<String>();
        var loader=new AsyncUiLoader<String,String>(work::add,3,k->k,freed::add);
        loader.request("a");loader.request("b");work.remove().run();loader.close();work.remove().run();
        assertEquals(List.of("a"),freed);assertNull(loader.takeReady());
    }
    @Test void reloadDuringDecodeDisposesLateCompletion(){
        var work=new ArrayDeque<Runnable>();var freed=new ArrayList<String>();
        var owner=new java.util.concurrent.atomic.AtomicReference<AsyncUiLoader<String,String>>();
        owner.set(new AsyncUiLoader<>(work::add,2,k->{owner.get().close();return k;},freed::add));
        owner.get().request("old");work.remove().run();assertEquals(List.of("old"),freed);
    }
    @Test void failureIsDeliveredOnceAndDoesNotBlockOtherResources(){
        var work=new ArrayDeque<Runnable>();
        var loader=new AsyncUiLoader<String,String>(work::add,2,k->{if(k.equals("bad"))throw new IllegalArgumentException();return k;},v->{});
        loader.request("bad");loader.request("good");work.forEach(Runnable::run);
        assertNotNull(loader.takeReady().getValue().error());assertEquals("good",loader.takeReady().getValue().value());assertNull(loader.takeReady());
    }
}
