package kr.chacademi.chatlayout;

import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

/** Keeps moved-tab scroll state pending while a native renderer is cold or geometry is changing. */
public final class PendingViewRestore<T> {
    public record Snapshot(int scroll,boolean newMessages){}
    private static final class Entry {
        final Snapshot snapshot;
        boolean refreshComplete;
        Entry(Snapshot snapshot){this.snapshot=snapshot;}
    }
    private final IdentityHashMap<T,Entry> pending=new IdentityHashMap<>();

    public static boolean cacheReady(int lineHeight,float scale){
        return lineHeight>0&&Float.isFinite(scale)&&scale>0;
    }
    public void capture(T key,int scroll,boolean newMessages){
        pending.put(key,new Entry(new Snapshot(scroll,newMessages)));
    }
    public boolean contains(T key){return pending.containsKey(key);}
    public void retainKeys(Set<T> live){
        Set<T> identities=Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(live);pending.keySet().removeIf(key->!identities.contains(key));
    }
    public void onRescale(T key,boolean ready,Consumer<Snapshot> restore){
        tryRestore(key,ready,restore);
    }
    public void onRefresh(T key,boolean refreshing,boolean ready,Consumer<Snapshot> restore){
        Entry entry=pending.get(key);
        if(entry==null||refreshing)return;
        entry.refreshComplete=true;
        tryRestore(key,ready,restore);
    }
    public void onGeometryReady(T key,boolean ready,Consumer<Snapshot> restore){
        tryRestore(key,ready,restore);
    }
    private void tryRestore(T key,boolean ready,Consumer<Snapshot> restore){
        Entry entry=pending.get(key);
        if(entry==null||!ready)return;
        // Consumption follows successful restoration, never a cold or reentrant refresh callback.
        restore.accept(entry.snapshot);
        if(entry.refreshComplete&&pending.get(key)==entry)pending.remove(key);
    }
}
