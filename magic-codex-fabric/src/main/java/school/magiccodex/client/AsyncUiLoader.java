package school.magiccodex.client;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Bounded background work; ownership of completed native buffers transfers exactly once. */
final class AsyncUiLoader<K,V> implements AutoCloseable {
    interface Decoder<K,V>{V load(K key)throws Exception;}
    record Result<V>(V value,Exception error){}
    private final class Job{V value;Exception error;boolean ready;}
    private final Map<K,Job> jobs=new LinkedHashMap<>();
    private final Executor executor;private final Decoder<K,V> decoder;private final Consumer<V> dispose;
    private final int limit;private boolean closed;
    AsyncUiLoader(Executor executor,int limit,Decoder<K,V> decoder,Consumer<V> dispose){this.executor=executor;this.limit=limit;this.decoder=decoder;this.dispose=dispose;}
    synchronized void request(K key){
        if(closed||jobs.containsKey(key)||jobs.size()>=limit)return;
        var job=new Job();jobs.put(key,job);
        executor.execute(()->{
            synchronized(this){if(closed)return;}
            V value=null;Exception error=null;
            try{value=decoder.load(key);}catch(Exception e){error=e;}
            synchronized(this){
                if(closed){if(value!=null)dispose.accept(value);return;}
                job.value=value;job.error=error;job.ready=true;
            }
        });
    }
    synchronized Map.Entry<K,Result<V>> takeReady(){
        var it=jobs.entrySet().iterator();
        while(it.hasNext()){var entry=it.next();var job=entry.getValue();if(job.ready){it.remove();return Map.entry(entry.getKey(),new Result<>(job.value,job.error));}}
        return null;
    }
    synchronized int size(){return jobs.size();}
    @Override public synchronized void close(){closed=true;for(var job:jobs.values())if(job.ready&&job.value!=null)dispose.accept(job.value);jobs.clear();}
}
