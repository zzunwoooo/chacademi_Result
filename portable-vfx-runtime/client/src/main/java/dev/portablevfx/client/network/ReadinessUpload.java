package dev.portablevfx.client.network;
import dev.portablevfx.protocol.CatalogReadinessV2;
import java.util.*;
/** One immutable generation in flight; a failed send never advances the cursor. */
final class ReadinessUpload {
    private UUID epoch;
    private long generation;
    private Set<String> sent, sending;
    private List<byte[]> frames=List.of();
    private int index;
    void epoch(UUID value){if(!Objects.equals(epoch,value)){epoch=Objects.requireNonNull(value);generation=0;invalidate();}}
    boolean hasEpoch(){return epoch!=null;}
    void disconnect(){epoch=null;generation=0;invalidate();}
    void invalidate(){sent=null;sending=null;frames=List.of();index=0;}
    int sentCount(){return sent==null?-1:sent.size();}
    byte[] peek(Set<String> ready){
        if(epoch==null)return null;
        if(sending==null||!sending.equals(ready)){
            if(sending==null&&ready.equals(sent))return null;
            if(generation==Long.MAX_VALUE)throw new IllegalArgumentException("Readiness generation exhausted");
            var snapshot=Set.copyOf(ready);var encoded=CatalogReadinessV2.encode(epoch,generation+1,snapshot);
            generation++;sending=snapshot;frames=encoded;index=0;
        }
        return index<frames.size()?frames.get(index):null;
    }
    void sent(){
        if(index>=frames.size())throw new IllegalStateException("No readiness frame pending");
        if(++index==frames.size()){sent=sending;sending=null;frames=List.of();index=0;}
    }
}
