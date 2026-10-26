package school.magiccodex.paper;

import java.util.*;
import school.magiccodex.protocol.TemperatureProtocol;
import school.magiccodex.protocol.TemperatureProtocol.Snapshot;

/** Main-thread memory-only values; repeated requests cannot force extra packets. */
final class TemperatureFeed {
    private static final class Session {long expires,lastSent;Snapshot last;}
    private final Map<UUID,Float> values=new HashMap<>();
    private final Map<UUID,Float> computed=new HashMap<>();
    private final Map<UUID,Session> sessions=new HashMap<>();
    private float fallback;
    TemperatureFeed(float fallback){fallback(fallback);}
    void fallback(float value){TemperatureProtocol.validate(value);fallback=rounded(value);}
    private static float rounded(float v){return Math.round(v*10)/10f;}
    float get(UUID id){return values.getOrDefault(id,computed.getOrDefault(id,fallback));}
    void computed(UUID id,float value){TemperatureProtocol.validate(value);computed.put(id,rounded(value));}
    void clearComputed(UUID id){computed.remove(id);}
    void set(UUID id,float value){TemperatureProtocol.validate(value);values.put(id,rounded(value));}
    void reset(UUID id){values.remove(id);}
    boolean subscribe(UUID id,long now){
        boolean fresh=!sessions.containsKey(id);
        sessions.computeIfAbsent(id,k->new Session()).expires=now+60000;
        return fresh;
    }
    Snapshot poll(UUID id,long now){
        var s=sessions.get(id);if(s==null)return null;
        if(now>=s.expires){sessions.remove(id);return null;}
        var value=new Snapshot(true,get(id));
        if(value.equals(s.last)&&now-s.lastSent<15000)return null;
        s.last=value;s.lastSent=now;return value;
    }
    List<UUID> subscribers(){return List.copyOf(sessions.keySet());}
    void remove(UUID id){sessions.remove(id);values.remove(id);computed.remove(id);}
    void clear(){sessions.clear();values.clear();computed.clear();}
}
