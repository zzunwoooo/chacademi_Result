package school.magiccodex.client;
import java.util.*;
import school.magiccodex.protocol.DiscoveryProtocol.Notice;
final class DiscoveryQueue {
    enum Result{ADDED,DUPLICATE,FINISHED,FULL}
    private final ArrayDeque<Notice> queued=new ArrayDeque<>();private final Set<Long> pending=new HashSet<>();private final LinkedHashSet<Long> done=new LinkedHashSet<>();
    Result offer(Notice n){if(done.contains(n.token()))return Result.FINISHED;if(pending.contains(n.token()))return Result.DUPLICATE;if(pending.size()>=16)return Result.FULL;pending.add(n.token());queued.add(n);return Result.ADDED;}
    Notice poll(){return queued.poll();}
    void finish(long token){pending.remove(token);done.add(token);while(done.size()>128)done.remove(done.iterator().next());}
    void clear(){queued.clear();pending.clear();done.clear();}
}
