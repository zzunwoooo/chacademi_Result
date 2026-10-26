package school.magiccodex.party;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 파티 창(클라이언트 UI)과 Velocity 사이의 {@code chacademi:party_ui} 형식. 플러그인의 직렬 작업 스레드에서만 사용한다.
 * 바이트 형식은 magic-codex-protocol 의 PartyUiProtocol 과 같아야 한다 (양쪽 PartyUiTest 의 GOLDEN 고정).
 *
 * <pre>
 * 클라이언트 → 프록시: u8 version(1) · u8 action [· i64 msb · i64 lsb (대상이 있는 동작)]
 *   action 1 상태 요청, 2 초대 후보 요청, 3 초대, 4 수락, 5 거절, 6 나가기, 7 위임, 8 강퇴
 * 프록시 → 클라이언트: u8 version(1) · u8 kind · ...
 *   1 STATE  : u8 flags(1=창 열기) · u8 capacity · u8 n · n×Person(u8 flags 1 온라인, 2 파티장) · u8 m · m×Person(u8 0) (받은 초대)
 *   2 CANDIDATES : u16 n · n×Person(u8 flags 1=이미 초대함)
 *   3 INVITE : Person(u8 0) (알림 카드)
 *   4 RESULT : u8 ok · UTF message
 *   Person = i64 msb · i64 lsb · UTF nickname(1..32) · i8 house(-1..3) · UTF skin(""|64 hex) · u8 slim · u8 flags
 * </pre>
 */
final class PartyUi {
    static final int VERSION=1,MAX_BYTES=32_000,MAX_CANDIDATES=256,MAX_INVITES=16;
    static final int OPEN=1,CANDIDATES=2,INVITE=3,ACCEPT=4,DENY=5,LEAVE=6,PROMOTE=7,KICK=8;
    static final int K_STATE=1,K_CANDIDATES=2,K_INVITE=3,K_RESULT=4;
    static final int P_ONLINE=1,P_LEADER=2,P_INVITED=1;
    static final long CANDIDATE_INTERVAL=900;

    record Skin(String hash,boolean slim){static final Skin NONE=new Skin("",false);}
    record Person(UUID id,String nickname,int house,Skin skin,int flags){}
    record Request(int action,UUID target){}

    private final Map<UUID,byte[]> sentState=new HashMap<>();
    private final Map<UUID,Long> holdUntil=new HashMap<>(),nextCandidates=new HashMap<>();

    static Request decode(byte[] data)throws IOException{
        if(data==null||data.length<2||data.length>18)throw new IOException("Party UI request size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readUnsignedByte()!=VERSION)throw new IOException("Party UI version");
            int action=in.readUnsignedByte();
            boolean target=action==INVITE||action==ACCEPT||action==DENY||action==PROMOTE||action==KICK;
            if(action<OPEN||action>KICK)throw new IOException("Party UI action");
            UUID id=target?new UUID(in.readLong(),in.readLong()):null;
            if(in.available()!=0)throw new IOException("Party UI trailing bytes");
            return new Request(action,id);
        }
    }

    static byte[] state(boolean open,int capacity,List<Person> members,List<Person> invites){
        return write(out->{
            out.writeByte(K_STATE);out.writeByte(open?1:0);out.writeByte(Math.clamp(capacity,0,64));
            int n=Math.min(members.size(),64);out.writeByte(n);for(int i=0;i<n;i++)person(out,members.get(i));
            int m=Math.min(invites.size(),MAX_INVITES);out.writeByte(m);for(int i=0;i<m;i++)person(out,invites.get(i));
        });
    }
    static byte[] candidates(List<Person> people){
        return write(out->{int n=Math.min(people.size(),MAX_CANDIDATES);out.writeByte(K_CANDIDATES);out.writeShort(n);for(int i=0;i<n;i++)person(out,people.get(i));});
    }
    static byte[] invite(Person inviter){return write(out->{out.writeByte(K_INVITE);person(out,inviter);});}
    static byte[] result(boolean ok,String message){
        String text=message==null?"":message.length()>200?message.substring(0,200):message;
        return write(out->{out.writeByte(K_RESULT);out.writeByte(ok?1:0);out.writeUTF(text);});
    }
    private interface Body{void write(DataOutputStream out)throws IOException;}
    private static byte[] write(Body body){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(VERSION);body.write(out);
            if(bytes.size()>MAX_BYTES)throw new IllegalStateException("Party UI packet too large");
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static void person(DataOutputStream out,Person p)throws IOException{
        out.writeLong(p.id().getMostSignificantBits());out.writeLong(p.id().getLeastSignificantBits());
        out.writeUTF(PartyHud.safeName(p.nickname()));out.writeByte(p.house()< -1||p.house()>3?-1:p.house());
        var skin=p.skin()==null?Skin.NONE:p.skin();
        out.writeUTF(validHash(skin.hash())?skin.hash():"");out.writeByte(skin.slim()?1:0);out.writeByte(p.flags()&0xFF);
    }
    static boolean validHash(String hash){return hash!=null&&hash.length()>=32&&hash.length()<=64&&hash.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f');}

    /**
     * Mojang 프로필의 textures 값(base64 JSON)에서 스킨 해시와 slim 여부만 꺼낸다.
     * 클라이언트는 이 해시로 textures.minecraft.net 주소를 다시 만들어 얼굴을 그린다 (서명이 필요 없는 표시용).
     */
    static Skin skin(String texturesValue){
        if(texturesValue==null||texturesValue.isBlank()||texturesValue.length()>8192)return Skin.NONE;
        try{
            var json=JsonParser.parseString(new String(Base64.getDecoder().decode(texturesValue.strip()),StandardCharsets.UTF_8)).getAsJsonObject();
            var skin=json.getAsJsonObject("textures").getAsJsonObject("SKIN");if(skin==null)return Skin.NONE;
            String url=skin.get("url").getAsString();int slash=url.lastIndexOf('/');
            if(!url.startsWith("http://textures.minecraft.net/texture/")&&!url.startsWith("https://textures.minecraft.net/texture/"))return Skin.NONE;
            String hash=url.substring(slash+1).toLowerCase(Locale.ROOT);
            boolean slim=skin.has("metadata")&&skin.getAsJsonObject("metadata").has("model")&&"slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            return validHash(hash)?new Skin(hash,slim):Skin.NONE;
        }catch(RuntimeException malformed){return Skin.NONE;}
    }

    /** 상태는 바뀌었을 때만 보낸다 (주기 전송 없음). 서버 이동 직후에는 잠시 기다렸다 다시 보낸다. */
    byte[] stateDue(UUID player,byte[] payload,long now){
        var hold=holdUntil.get(player);
        if(hold!=null){if(now<hold)return null;holdUntil.remove(player);}
        if(Arrays.equals(sentState.get(player),payload))return null;
        sentState.put(player,payload);return payload;
    }
    /** 창 열기 등 즉시 보낸 상태를 기록해 같은 내용을 다시 보내지 않게 한다. */
    void sentNow(UUID player,byte[] statePayloadWithoutOpen){sentState.put(player,statePayloadWithoutOpen);}
    boolean candidatesAllowed(UUID player,long now){
        if(now<nextCandidates.getOrDefault(player,0L))return false;
        nextCandidates.put(player,now+CANDIDATE_INTERVAL);return true;
    }
    void resend(UUID player,long notBefore){sentState.remove(player);holdUntil.put(player,notBefore);}
    void disconnect(UUID player){sentState.remove(player);holdUntil.remove(player);nextCandidates.remove(player);}
}
