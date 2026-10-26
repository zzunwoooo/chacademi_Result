package kr.chacademy.storyplugin;

import java.util.*;

/** Server-issued, player-bound sessions. No commands or target identities arrive from the client. */
final class LocalEndingSessions {
    static final long TTL=3_600_000;
    record Session(String id,String token,long expires,List<String> commands,boolean ready) {}
    private final Map<UUID,Session> sessions=new HashMap<>();
    Session current(UUID player,long now){var s=sessions.get(player);if(s!=null&&now>=s.expires()){sessions.remove(player);return null;}return s;}
    Session begin(UUID player,String id,List<String> commands,long now){
        if(commands==null||!StorySafety.validId(id))return null;
        var old=current(player,now);if(old!=null)return old.id().equals(id)?old:null;
        var s=new Session(id,UUID.randomUUID().toString(),now+TTL,List.copyOf(commands),false);
        sessions.put(player,s);return s;
    }
    private Session match(UUID player,String id,String token,long now){var s=current(player,now);return s!=null&&s.id().equals(id)&&s.token().equals(token)?s:null;}
    boolean ready(UUID player,String id,String token,long now){var s=match(player,id,token,now);if(s==null)return false;sessions.put(player,new Session(s.id(),s.token(),s.expires(),s.commands(),true));return true;}
    List<String> finish(UUID player,String id,String token,String ending,long now){
        var s=match(player,id,token,now);
        if(s==null||!s.ready()||!StorySafety.validId(ending))return List.of();
        sessions.remove(player); // Consume before dispatch, including reentrant commands and duplicate packets.
        return s.commands();
    }
    boolean abort(UUID player,String id,String token,long now){if(match(player,id,token,now)==null)return false;sessions.remove(player);return true;}
    void cancel(UUID player){sessions.remove(player);}
    void clear(){sessions.clear();}
}
