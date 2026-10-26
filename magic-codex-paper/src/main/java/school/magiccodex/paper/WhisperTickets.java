package school.magiccodex.paper;

import java.util.*;
/** Single-use authorizations, issued only after a successful server-side cast. */
final class WhisperTickets {
    record Ticket(long token,UUID target,long expires){}
    private final Map<UUID,Ticket> values=new HashMap<>();
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    Ticket issue(UUID owner,UUID target,long now){long token;do{token=random.nextLong()&Long.MAX_VALUE;}while(token==0);var t=new Ticket(token,target,now+120000);values.put(owner,t);return t;}
    Ticket get(UUID owner,long now){var t=values.get(owner);if(t!=null&&t.expires()<=now){values.remove(owner);return null;}return t;}
    Ticket bind(UUID owner,UUID target,long now){var t=get(owner,now);if(t==null||(!t.target().equals(school.magiccodex.protocol.SocialProtocol.NONE)&&!t.target().equals(target)))return null;var bound=new Ticket(t.token(),target,t.expires());values.put(owner,bound);return bound;}
    boolean consume(UUID owner,UUID target,long token,long now){var t=get(owner,now);if(t==null||t.token()!=token||!t.target().equals(target))return false;values.remove(owner);return true;}
    void cancel(UUID owner,long token){var t=values.get(owner);if(t!=null&&t.token()==token)values.remove(owner);}
    void remove(UUID owner){values.remove(owner);}
    void expire(long now){values.values().removeIf(t->t.expires()<=now);}
    void clear(){values.clear();}
}
