package school.chacademia.wildlife;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Bounded async-to-main read-only checks. The game thread never waits for a worker. */
final class MainThreadSpawnGate implements AutoCloseable {
    interface Dispatcher { Runnable submit(Runnable action); }
    private static final class Request {
        final long generation,deadline;
        final CompletableFuture<Boolean> result=new CompletableFuture<>();
        final AtomicBoolean released=new AtomicBoolean();
        volatile Runnable cancel;
        Request(long generation,long deadline){this.generation=generation;this.deadline=deadline;}
    }
    private final BooleanSupplier main;
    private final Dispatcher dispatcher;
    private final LongSupplier clock;
    private final long timeoutNanos;
    private final Semaphore permits;
    private final Set<Request> pending=ConcurrentHashMap.newKeySet();
    private final AtomicLong generation=new AtomicLong(),submitted=new AtomicLong(),timedOut=new AtomicLong(),rejected=new AtomicLong(),failed=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();

    MainThreadSpawnGate(BooleanSupplier main,Dispatcher dispatcher,int limit,long timeoutMillis){this(main,dispatcher,limit,timeoutMillis,System::nanoTime);}
    MainThreadSpawnGate(BooleanSupplier main,Dispatcher dispatcher,int limit,long timeoutMillis,LongSupplier clock){
        if(limit<1||timeoutMillis<1)throw new IllegalArgumentException("Positive gate limits required");
        this.main=Objects.requireNonNull(main);this.dispatcher=Objects.requireNonNull(dispatcher);this.clock=Objects.requireNonNull(clock);
        this.permits=new Semaphore(limit);this.timeoutNanos=TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }
    boolean check(BooleanSupplier action){
        long epoch=generation.get();
        if(closed.get())return false;
        if(main.getAsBoolean())return evaluate(action)&&!closed.get()&&epoch==generation.get();
        if(!permits.tryAcquire()){rejected.incrementAndGet();return false;}
        var request=new Request(epoch,clock.getAsLong()+timeoutNanos);pending.add(request);
        try{
            if(closed.get()||epoch!=generation.get())return false;
            Runnable cancel=dispatcher.submit(()->{
                try{
                    if(request.result.isDone()||closed.get()||request.generation!=generation.get()||clock.getAsLong()-request.deadline>=0){request.result.complete(false);return;}
                    // Dispatcher mistakes must fail closed before touching world state.
                    if(!main.getAsBoolean()){failed.incrementAndGet();request.result.complete(false);return;}
                    boolean result=evaluate(action);
                    request.result.complete(result&&!closed.get()&&request.generation==generation.get());
                }finally{release(request);}
            });
            request.cancel=Objects.requireNonNull(cancel);submitted.incrementAndGet();
            if(request.result.isDone()||closed.get()||epoch!=generation.get())cancel(request);
            long left=request.deadline-clock.getAsLong();
            if(left<=0){timedOut.incrementAndGet();return false;}
            return request.result.get(left,TimeUnit.NANOSECONDS)&&!closed.get()&&epoch==generation.get();
        }catch(TimeoutException error){timedOut.incrementAndGet();return false;}
        catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
        catch(ExecutionException|RuntimeException|LinkageError error){failed.incrementAndGet();return false;}
        finally{cancel(request);release(request);}
    }
    private boolean evaluate(BooleanSupplier action){
        try{return action.getAsBoolean();}catch(RuntimeException|LinkageError error){failed.incrementAndGet();return false;}
    }
    private void cancel(Request request){
        request.result.complete(false);var cancel=request.cancel;
        if(cancel!=null)try{cancel.run();}catch(RuntimeException ignored){/* Already completed/cancelled scheduler task. */}
    }
    private void release(Request request){if(request.released.compareAndSet(false,true)){pending.remove(request);permits.release();}}
    void invalidate(){generation.incrementAndGet();for(var request:List.copyOf(pending)){cancel(request);release(request);}}
    int pendingCount(){return pending.size();}
    String diagnostic(){return "generation="+generation.get()+", pending="+pending.size()+", submitted="+submitted.get()+", timeout="+timedOut.get()+", rejected="+rejected.get()+", failed="+failed.get();}
    @Override public void close(){closed.set(true);invalidate();}
}
