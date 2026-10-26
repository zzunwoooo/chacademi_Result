package school.magiccodex.party;

import java.io.*;
import java.util.*;

/**
 * 파티 위젯 상태. 플러그인의 직렬 작업 스레드에서만 사용한다.
 * 백엔드가 보낸 본인 체력·마나(서버 소유 값만)를 모아 파티원 각자에게 {@code chacademi:party_hud}로 보낸다.
 * 바이트 형식은 magic-codex-protocol 의 PartyHudProtocol 과 같아야 한다 (PartyHudTest 고정).
 */
final class PartyHud {
    static final int VERSION=1,MAX_MEMBERS=16,MAX_BYTES=4096,ONLINE=1,LEADER=2,VITALS=4;
    static final long VITALS_TTL=6_000,HEARTBEAT=3_000;
    static final byte[] EMPTY={VERSION,0};
    record Vitals(float health,float maxHealth,float mana,float maxMana,Object source,long until){}
    record Member(UUID id,String nickname,int house,boolean online,boolean leader,Vitals vitals){}
    private record Remembered(String nickname,int house){}
    private record Sent(byte[] payload,long at){}
    private final Set<String> trusted;
    private final Map<UUID,Vitals> vitals=new HashMap<>();
    private final Map<UUID,Remembered> remembered=new HashMap<>();
    private final Map<UUID,Sent> sent=new HashMap<>();
    private final Map<UUID,Long> holdUntil=new HashMap<>();
    PartyHud(Set<String> trusted){this.trusted=Set.copyOf(trusted);}

    static Vitals decodeVitals(byte[] data,Object source,long until)throws IOException{
        if(data.length!=17)throw new IOException("Vitals size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readUnsignedByte()!=1)throw new IOException("Vitals version");
            float h=in.readFloat(),mh=in.readFloat(),m=in.readFloat(),mm=in.readFloat();
            if(!Float.isFinite(h)||!Float.isFinite(mh)||!Float.isFinite(m)||!Float.isFinite(mm))throw new IOException("Vitals values");
            if(mh<=0||mh>1_000_000||h<0||h>mh+0.05f)throw new IOException("Health values");
            if(!(mm==-1&&m==-1)&&(mm<0||mm>1_000_000||m<0||m>mm+0.05f))throw new IOException("Mana values");
            return new Vitals(h,mh,m,mm,source,until);
        }
    }
    /** identity 와 같은 신뢰 조건: 신뢰 서버가, 그 플레이어의 현재 연결로 보낸 것만 받는다. */
    boolean accept(UUID player,Object source,Object active,String server,boolean backendOwned,byte[] data,long now){
        if(!backendOwned||source==null||source!=active||!trusted.contains(server))return false;
        try{vitals.put(player,decodeVitals(data,source,now+VITALS_TTL));return true;}
        catch(IOException|RuntimeException e){return false;}
    }
    /** 서버를 옮기면 새 서버가 보내기 전까지 이전 서버 값을 쓰지 않는다. */
    Vitals vitals(UUID player,Object active,long now){
        var v=vitals.get(player);return v!=null&&v.source()==active&&v.until()>now?v:null;
    }
    void remember(UUID player,String nickname,int house){if(nickname!=null)remembered.put(player,new Remembered(nickname,house));}
    String rememberedName(UUID player){var r=remembered.get(player);return r==null?null:r.nickname();}
    int rememberedHouse(UUID player){var r=remembered.get(player);return r==null?-1:r.house();}
    void disconnect(UUID player){vitals.remove(player);sent.remove(player);holdUntil.remove(player);}
    /** 서버 이동 직후: 잠시 기다렸다가 처음부터 다시 보낸다 (클라이언트가 새 접속에서 상태를 비울 수 있음). */
    void resend(UUID player,long notBefore){sent.remove(player);holdUntil.put(player,notBefore);}

    /** @return 보낼 바이트, 보낼 필요가 없으면 null */
    byte[] due(UUID recipient,byte[] payload,long now){
        var hold=holdUntil.get(recipient);
        if(hold!=null){if(now<hold)return null;holdUntil.remove(recipient);}
        var prev=sent.get(recipient);
        boolean empty=payload.length==EMPTY.length;
        if(prev!=null&&Arrays.equals(prev.payload(),payload)&&(empty||now-prev.at()<HEARTBEAT))return null;
        // 파티가 없던 사람에게 빈 패킷을 처음 한 번만 보낸다 (계속 보내지 않음).
        sent.put(recipient,new Sent(payload,now));return payload;
    }

    static byte[] encode(List<Member> members){
        int count=Math.min(members.size(),MAX_MEMBERS);
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeByte(VERSION);out.writeByte(count);
            for(int i=0;i<count;i++){
                var m=members.get(i);var v=m.vitals();
                out.writeLong(m.id().getMostSignificantBits());out.writeLong(m.id().getLeastSignificantBits());
                out.writeUTF(safeName(m.nickname()));out.writeByte(m.house()< -1||m.house()>3?-1:m.house());
                out.writeByte((m.online()?ONLINE:0)|(m.leader()?LEADER:0)|(v!=null?VITALS:0));
                if(v!=null){out.writeFloat(v.health());out.writeFloat(v.maxHealth());out.writeFloat(v.mana());out.writeFloat(v.maxMana());}
            }
            if(bytes.size()>MAX_BYTES)throw new IllegalStateException("Party HUD packet too large");
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    static String safeName(String name){
        if(name==null||name.isBlank()||name.length()>32||name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))return "알 수 없음";
        return name;
    }
}
