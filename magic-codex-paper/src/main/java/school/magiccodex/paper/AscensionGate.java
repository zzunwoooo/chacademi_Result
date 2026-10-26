package school.magiccodex.paper;

import java.util.*;

/** One offer per player, consumed before mutation. No polling or rank supplied by clients. */
final class AscensionGate {
    record Offer(long token,int from,long expires){}
    private final Map<UUID,Offer> offers=new HashMap<>();
    private long serial=1;
    Offer open(UUID id,int current,long now){var o=new Offer(serial++,current,now+60000);offers.put(id,o);return o;}
    boolean consume(UUID id,long token,int current,boolean permission,long now){
        var o=offers.get(id);
        if(o==null||o.token()!=token)return false;
        offers.remove(id);
        return o.expires()>now&&o.from()==current&&current>=1&&current<9&&permission;
    }
    void remove(UUID id){offers.remove(id);}
    void clear(){offers.clear();}
}
