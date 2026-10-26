package dev.portablevfx.client.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One bounded GPU stage per scheduled handoff, without recursion on inline executors. */
final class GpuWarmup {
    private GpuWarmup() { }
    static CompletableFuture<Void> run(BooleanSupplier step,Executor renderExecutor,BooleanSupplier current) {
        var result=new CompletableFuture<Void>();
        final class Pump implements Runnable {
            @Override public void run() {
                if(result.isDone())return;
                try {
                    if(!current.getAsBoolean()||step.getAsBoolean()){result.complete(null);return;}
                    // The timer invokes the supplied render executor from another thread, so
                    // Minecraft queues the next bounded stage rather than recursing inline.
                    CompletableFuture.delayedExecutor(1,TimeUnit.MILLISECONDS,renderExecutor).execute(this);
                }catch(Throwable error){result.completeExceptionally(error);}
            }
        }
        try{renderExecutor.execute(new Pump());}catch(Throwable error){result.completeExceptionally(error);}
        return result;
    }
}
