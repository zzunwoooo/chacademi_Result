package kr.chacademy.storyplugin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Main-thread-only, idempotent acknowledgement of actual UI opening. */
final class DialogueAcks {
    private record Key(UUID player, String id) {}
    private final Map<Key, CompletableFuture<Boolean>> pending = new HashMap<>();
    CompletableFuture<Boolean> pending(UUID player,String id) { return pending.get(new Key(player,id)); }
    CompletableFuture<Boolean> begin(UUID player,String id) { return pending.computeIfAbsent(new Key(player,id), k -> new CompletableFuture<>()); }
    void accept(UUID player,String id) { complete(new Key(player,id),true); }
    void reject(UUID player,String id) { complete(new Key(player,id),false); }
    private void complete(Key key,boolean result) { var f=pending.remove(key); if(f!=null)f.complete(result); }
    void disconnect(UUID player) { for(var k:List.copyOf(pending.keySet()))if(k.player().equals(player))complete(k,false); }
    void clear() { for(var k:List.copyOf(pending.keySet()))complete(k,false); }
}
