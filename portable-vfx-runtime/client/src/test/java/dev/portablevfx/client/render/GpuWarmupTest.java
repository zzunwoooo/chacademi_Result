package dev.portablevfx.client.render;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GpuWarmupTest {
    @Test void thousandsOfStagesOnInlineExecutorDoNotRecurseOrPublishBeforeCompletion()throws Exception {
        var steps=new AtomicInteger();var future=GpuWarmup.run(()->steps.incrementAndGet()==3000,Runnable::run,()->true);
        assertFalse(future.isDone());future.get(15,TimeUnit.SECONDS);assertEquals(3000,steps.get());
    }
    @Test void staleReloadStopsWithoutExecutingLaterStages()throws Exception {
        var steps=new AtomicInteger();GpuWarmup.run(()->{steps.incrementAndGet();return false;},Runnable::run,()->steps.get()<3)
                .get(5,TimeUnit.SECONDS);assertEquals(3,steps.get());
    }
}
