package school.magiccodex.paper;

import java.util.*;
import school.magiccodex.protocol.WalletProtocol.Snapshot;

/** Main-thread fair queue with a hard per-pass economy-query budget and expiring leases. */
final class WalletSubscriptions {
    interface Access { boolean online(UUID id); Snapshot read(UUID id); void send(UUID id,Snapshot value); }
    private static final class Session {long expires,nextRead,lastSent;Snapshot last;}
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Deque<UUID> queue=new ArrayDeque<>();
    private final long interval;
    private final int budget;
    private final Deque<Long> recentReads=new ArrayDeque<>();
    WalletSubscriptions(long interval,int budget){this.interval=Math.max(250,interval);this.budget=Math.max(1,budget);}
    void subscribe(UUID id,long now){
        var session=sessions.get(id);
        if(session==null){session=new Session();sessions.put(id,session);queue.addLast(id);}
        // Repeated requests only extend a lease; never force extra balance reads or responses.
        session.expires=now+60000;
    }
    void remove(UUID id){sessions.remove(id);queue.remove(id);}
    void clear(){sessions.clear();queue.clear();recentReads.clear();}
    int size(){return sessions.size();}
    void process(long now,Access access){
        while(!recentReads.isEmpty() && now-recentReads.peekFirst()>=1000)recentReads.removeFirst();
        // Ten passes per second; spread Vault calls across ticks, with a sliding one-second cap.
        int remaining=queue.size(),reads=0,passBudget=(budget+9)/10;
        while(remaining-->0 && reads<passBudget && recentReads.size()<budget){
            UUID id=queue.removeFirst();var s=sessions.get(id);
            if(s==null)continue;
            if(now>=s.expires || !access.online(id)){sessions.remove(id);continue;}
            queue.addLast(id);
            if(now<s.nextRead)continue;
            reads++;recentReads.addLast(now);s.nextRead=now+interval;
            Snapshot value=access.read(id);
            if(!value.equals(s.last) || now-s.lastSent>=30000){
                access.send(id,value);s.last=value;s.lastSent=now;
            }
        }
    }
}
