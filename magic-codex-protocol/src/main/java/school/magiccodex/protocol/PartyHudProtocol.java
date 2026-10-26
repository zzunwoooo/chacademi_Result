package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 파티 위젯 데이터 (Velocity -> 클라이언트, {@code chacademi:party_hud}).
 * Velocity 파티 플러그인의 {@code PartyHud.encode}와 바이트 단위로 같아야 한다 (테스트로 고정).
 *
 * <pre>
 * u8 version(1) · u8 count(0..16)
 * 반복: i64 msb · i64 lsb · UTF nickname(1..32) · i8 house(-1..3) · u8 flags
 *       flags&1 online, &2 leader, &4 vitals 있음 -> f32 health · f32 maxHealth · f32 mana · f32 maxMana
 * </pre>
 * 마나를 모르면 mana/maxMana 는 -1.
 */
public final class PartyHudProtocol {
    public static final String CHANNEL="chacademi:party_hud";
    public static final int VERSION=1,MAX_MEMBERS=16,MAX_BYTES=4096;
    public static final int ONLINE=1,LEADER=2,VITALS=4;
    private PartyHudProtocol(){}

    public record Member(UUID id,String nickname,int house,boolean online,boolean leader,boolean known,
                         float health,float maxHealth,float mana,float maxMana) {
        public Member {
            Objects.requireNonNull(id);
            if(!validName(nickname))throw new IllegalArgumentException("Invalid nickname");
            if(house< -1||house>3)throw new IllegalArgumentException("Invalid house");
            if(known&&!validVitals(health,maxHealth,mana,maxMana))throw new IllegalArgumentException("Invalid vitals");
            if(!known){health=maxHealth=0;mana=maxMana=-1;}
        }
        public boolean manaKnown(){return known&&maxMana>0;}
    }

    static boolean validName(String n){
        return n!=null&&!n.isBlank()&&n.length()<=32&&n.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7);
    }
    static boolean validVitals(float health,float maxHealth,float mana,float maxMana){
        if(!Float.isFinite(health)||!Float.isFinite(maxHealth)||!Float.isFinite(mana)||!Float.isFinite(maxMana))return false;
        if(maxHealth<=0||maxHealth>1_000_000||health<0||health>maxHealth+0.05f)return false;
        if(maxMana==-1)return mana==-1;
        return maxMana>=0&&maxMana<=1_000_000&&mana>=0&&mana<=maxMana+0.05f;
    }

    public static byte[] encode(List<Member> members){
        if(members.size()>MAX_MEMBERS)throw new IllegalArgumentException("Too many members");
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeByte(VERSION);out.writeByte(members.size());
            for(var m:members){
                out.writeLong(m.id().getMostSignificantBits());out.writeLong(m.id().getLeastSignificantBits());
                out.writeUTF(m.nickname());out.writeByte(m.house());
                out.writeByte((m.online()?ONLINE:0)|(m.leader()?LEADER:0)|(m.known()?VITALS:0));
                if(m.known()){out.writeFloat(m.health());out.writeFloat(m.maxHealth());out.writeFloat(m.mana());out.writeFloat(m.maxMana());}
            }
            if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("Party HUD packet too large");
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }

    /** @throws IllegalArgumentException 형식이 맞지 않으면 (패킷 전체를 버린다) */
    public static List<Member> decode(byte[] data){
        if(data==null||data.length<2||data.length>MAX_BYTES)throw new IllegalArgumentException("Invalid party HUD size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readUnsignedByte()!=VERSION)throw new IllegalArgumentException("Unknown party HUD version");
            int count=in.readUnsignedByte();if(count>MAX_MEMBERS)throw new IllegalArgumentException("Too many members");
            var result=new ArrayList<Member>(count);var seen=new HashSet<UUID>();
            for(int i=0;i<count;i++){
                var id=new UUID(in.readLong(),in.readLong());if(!seen.add(id))throw new IllegalArgumentException("Duplicate member");
                String name=in.readUTF();int house=in.readByte();int flags=in.readUnsignedByte();
                if((flags&~(ONLINE|LEADER|VITALS))!=0)throw new IllegalArgumentException("Unknown flags");
                boolean known=(flags&VITALS)!=0;
                float h=0,mh=0,m=-1,mm=-1;
                if(known){h=in.readFloat();mh=in.readFloat();m=in.readFloat();mm=in.readFloat();}
                result.add(new Member(id,name,house,(flags&ONLINE)!=0,(flags&LEADER)!=0,known,h,mh,m,mm));
            }
            if(in.available()!=0)throw new IllegalArgumentException("Trailing party HUD bytes");
            return List.copyOf(result);
        }catch(IOException e){throw new IllegalArgumentException("Truncated party HUD packet",e);}
    }

    /** Paper -> Velocity ({@code chacademi:party_vitals}): u8 version(1) · f32 health · f32 maxHealth · f32 mana · f32 maxMana. */
    public static final String VITALS_CHANNEL="chacademi:party_vitals";
}
