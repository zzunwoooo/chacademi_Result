package school.magiccodex.party;

import java.io.*;
import java.util.*;

/**
 * 파티 명단을 게임 서버(백엔드)로 보낸다: {@code chacademi:party_roster}. 백엔드 플러그인(MagicCodexBridge)이 받아
 * 아티팩트·마법의 파티 판정에 쓴다. 플러그인의 직렬 작업 스레드에서만 사용한다.
 * 형식 (magic-codex-paper PartyRoster 와 같아야 함, PartyRosterTest 고정):
 *   byte 1(버전), byte n(인원, 0이면 파티 없음) [, long×2 파티 id, byte 파티장 순번, n × long×2 파티원 UUID]
 * 바뀌면 바로, 그대로면 5초마다 한 번 보낸다 (파티가 없으면 처음 한 번만).
 */
final class PartyRoster {
    static final int VERSION=1,MAX_MEMBERS=64;
    static final long HEARTBEAT=5_000;
    static final byte[] EMPTY={VERSION,0};
    private record Sent(byte[] payload,long at){}
    private final Map<UUID,Sent> sent=new HashMap<>();
    private final Map<UUID,Long> holdUntil=new HashMap<>();

    static byte[] encode(PartyBook.Party party){
        if(party==null)return EMPTY;
        int n=Math.min(party.members().size(),MAX_MEMBERS);
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeByte(VERSION);out.writeByte(n);
            out.writeLong(party.id().getMostSignificantBits());out.writeLong(party.id().getLeastSignificantBits());
            out.writeByte(Math.max(0,party.members().indexOf(party.leader())));
            for(int i=0;i<n;i++){UUID id=party.members().get(i);out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    void disconnect(UUID player){sent.remove(player);holdUntil.remove(player);}
    void resend(UUID player,long notBefore){sent.remove(player);holdUntil.put(player,notBefore);}
    /** @return 보낼 바이트, 보낼 필요가 없으면 null */
    byte[] due(UUID player,byte[] payload,long now){
        var hold=holdUntil.get(player);
        if(hold!=null){if(now<hold)return null;holdUntil.remove(player);}
        var prev=sent.get(player);
        boolean empty=payload.length==EMPTY.length;
        if(prev!=null&&Arrays.equals(prev.payload(),payload)&&(empty||now-prev.at()<HEARTBEAT))return null;
        sent.put(player,new Sent(payload,now));return payload;
    }
}
