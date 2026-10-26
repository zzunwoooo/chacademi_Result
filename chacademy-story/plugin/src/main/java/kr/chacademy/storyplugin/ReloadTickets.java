package kr.chacademy.storyplugin;
import java.util.*;
/** Results are bound to the requested target; duplicate or stale results cannot impersonate another reload. */
final class ReloadTickets<T> {
 record Ticket<T>(String token,T requester){}
 private final Map<UUID,Ticket<T>> pending=new HashMap<>();
 Ticket<T> begin(UUID target,T requester){var ticket=new Ticket<T>(UUID.randomUUID().toString(),requester);pending.put(target,ticket);return ticket;}
 T finish(UUID target,String token){var ticket=pending.get(target);if(ticket==null||!ticket.token().equals(token))return null;pending.remove(target);return ticket.requester();}
 void cancel(UUID target){pending.remove(target);}
 void clear(){pending.clear();}
}
