package school.magiccodex.paper;

import java.io.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.PartyHudProtocol;

/**
 * 이 서버에 있는 플레이어 본인의 체력·마나만 Velocity 파티 플러그인에 보낸다.
 * 파티 구성과 다른 서버 파티원 합치기는 Velocity가 맡는다 (서버 간 파티).
 * 값이 바뀌면 0.2초 안에, 바뀌지 않아도 2초마다 한 번 보낸다.
 */
final class PartyVitalsBridge implements AutoCloseable {
    static final String CHANNEL=PartyHudProtocol.VITALS_CHANNEL;
    static final int PERIOD=4,HEARTBEAT=40;
    private record Sent(int health,int maxHealth,int mana,int maxMana,int tick){}
    private final MagicCodexBridge plugin;
    private final ManaService mana;
    private final BukkitTask task;
    private final Map<UUID,Sent> last=new HashMap<>();
    private int tick;

    PartyVitalsBridge(MagicCodexBridge plugin,ManaService mana){
        this.plugin=plugin;this.mana=mana;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,PERIOD);
    }
    private void tick(){
        tick+=PERIOD;var online=new HashSet<UUID>();
        for(Player p:Bukkit.getOnlinePlayers()){
            UUID id=p.getUniqueId();online.add(id);
            var attribute=p.getAttribute(Attribute.MAX_HEALTH);
            double max=attribute==null?20:attribute.getValue();
            double health=p.isDead()?0:Math.min(p.getHealth(),max);
            var snapshot=mana==null?null:mana.snapshot(id).orElse(null);
            // 0.1 단위로 맞춰 미세한 재생 값 때문에 매 틱 보내지 않는다.
            var now=new Sent(tenths(health),tenths(max),snapshot==null?-10:tenths(snapshot.current()),snapshot==null?-10:tenths(snapshot.maximum()),tick);
            var prev=last.get(id);
            if(prev!=null&&prev.health==now.health&&prev.maxHealth==now.maxHealth&&prev.mana==now.mana&&prev.maxMana==now.maxMana&&tick-prev.tick<HEARTBEAT)continue;
            if(now.maxHealth<=0)continue;
            try{p.sendPluginMessage(plugin,CHANNEL,encode(now.health/10f,now.maxHealth/10f,now.mana/10f,now.maxMana/10f));last.put(id,now);}
            catch(RuntimeException ignored){/* 전송 실패는 다음 주기에 다시 시도한다. */}
        }
        last.keySet().retainAll(online);
    }
    private static int tenths(double v){return !Double.isFinite(v)?0:(int)Math.round(Math.clamp(v,-1,1_000_000)*10);}

    /** 17바이트: u8 1 · f32 health · f32 maxHealth · f32 mana · f32 maxMana (마나를 모르면 -1, -1). */
    static byte[] encode(float health,float maxHealth,float mana,float maxMana){
        try{
            var bytes=new ByteArrayOutputStream(17);var out=new DataOutputStream(bytes);
            out.writeByte(1);out.writeFloat(health);out.writeFloat(maxHealth);
            boolean manaKnown=maxMana>=0&&mana>=0;out.writeFloat(manaKnown?mana:-1);out.writeFloat(manaKnown?maxMana:-1);
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    @Override public void close(){task.cancel();last.clear();Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin,CHANNEL);}
}
