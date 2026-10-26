package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 파티 창 ({@code chacademi:party_ui}, 클라이언트 ↔ Velocity).
 * Velocity 파티 플러그인의 {@code PartyUi}와 바이트 단위로 같아야 한다 (양쪽 PartyUiTest 의 GOLDEN 고정).
 *
 * <pre>
 * 클라이언트 → 프록시: u8 version(1) · u8 action [· i64 msb · i64 lsb]
 *   1 상태 요청, 2 초대 후보 요청, 3 초대(대상), 4 수락(초대한 사람), 5 거절(초대한 사람), 6 나가기, 7 위임(대상), 8 강퇴(대상)
 * 프록시 → 클라이언트: u8 version(1) · u8 kind · ...
 *   1 STATE      : u8 flags(1=창 열기) · u8 capacity · u8 n · n×Person · u8 m · m×Person (나에게 온 초대)
 *   2 CANDIDATES : u16 n · n×Person (flags 1 = 이미 초대함)
 *   3 INVITE     : Person (초대 알림 카드)
 *   4 RESULT     : u8 ok · UTF message
 *   Person = i64 msb · i64 lsb · UTF nickname · i8 house · UTF skin("" 또는 hex 해시) · u8 slim · u8 flags(파티원: 1 온라인, 2 파티장)
 * </pre>
 */
public final class PartyUiProtocol {
    public static final String CHANNEL="chacademi:party_ui";
    public static final int VERSION=1,MAX_BYTES=32_000,MAX_CANDIDATES=256,MAX_INVITES=16;
    public static final int OPEN=1,CANDIDATES=2,INVITE=3,ACCEPT=4,DENY=5,LEAVE=6,PROMOTE=7,KICK=8;
    public static final int K_STATE=1,K_CANDIDATES=2,K_INVITE=3,K_RESULT=4;
    public static final int ONLINE=1,LEADER=2,INVITED=1;
    private PartyUiProtocol(){}

    public record Person(UUID id,String nickname,int house,String skin,boolean slim,int flags){
        public Person{
            Objects.requireNonNull(id);
            if(!PartyHudProtocol.validName(nickname))throw new IllegalArgumentException("Invalid nickname");
            if(house< -1||house>3)throw new IllegalArgumentException("Invalid house");
            if(skin==null||!skin.isEmpty()&&!validHash(skin))throw new IllegalArgumentException("Invalid skin");
        }
        public boolean online(){return (flags&ONLINE)!=0;}
        public boolean leader(){return (flags&LEADER)!=0;}
        public boolean invited(){return (flags&INVITED)!=0;}
    }
    public record State(boolean open,int capacity,List<Person> members,List<Person> invites){
        public State{members=List.copyOf(members);invites=List.copyOf(invites);}
        public static final State EMPTY=new State(false,8,List.of(),List.of());
        public boolean inParty(){return !members.isEmpty();}
        public Person leader(){return members.stream().filter(Person::leader).findFirst().orElse(null);}
    }
    /** 프록시가 보낸 것 하나. kind 에 따라 state / people / person / ok·message 중 하나가 채워진다. */
    public record Message(int kind,State state,List<Person> people,Person person,boolean ok,String message){}

    static boolean validHash(String hash){return hash.length()>=32&&hash.length()<=64&&hash.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f');}

    public static byte[] request(int action,UUID target){
        boolean needsTarget=action==INVITE||action==ACCEPT||action==DENY||action==PROMOTE||action==KICK;
        if(action<OPEN||action>KICK||needsTarget!=(target!=null))throw new IllegalArgumentException("Invalid party UI request");
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeByte(VERSION);out.writeByte(action);
            if(target!=null){out.writeLong(target.getMostSignificantBits());out.writeLong(target.getLeastSignificantBits());}
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }

    /** @throws IllegalArgumentException 형식이 맞지 않으면 (패킷 전체를 버린다) */
    public static Message decode(byte[] data){
        if(data==null||data.length<2||data.length>MAX_BYTES)throw new IllegalArgumentException("Invalid party UI size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readUnsignedByte()!=VERSION)throw new IllegalArgumentException("Unknown party UI version");
            int kind=in.readUnsignedByte();Message result;
            switch(kind){
                case K_STATE->{
                    boolean open=(in.readUnsignedByte()&1)!=0;int capacity=in.readUnsignedByte();
                    var members=people(in,in.readUnsignedByte(),64);var invites=people(in,in.readUnsignedByte(),MAX_INVITES);
                    result=new Message(kind,new State(open,capacity,members,invites),List.of(),null,true,"");
                }
                case K_CANDIDATES->result=new Message(kind,null,people(in,in.readUnsignedShort(),MAX_CANDIDATES),null,true,"");
                case K_INVITE->result=new Message(kind,null,List.of(),person(in),true,"");
                case K_RESULT->{boolean ok=in.readUnsignedByte()!=0;String text=in.readUTF();if(text.length()>200)throw new IllegalArgumentException("Message too long");result=new Message(kind,null,List.of(),null,ok,text);}
                default->throw new IllegalArgumentException("Unknown party UI kind");
            }
            if(in.available()!=0)throw new IllegalArgumentException("Trailing party UI bytes");
            return result;
        }catch(IOException e){throw new IllegalArgumentException("Truncated party UI packet",e);}
    }
    private static List<Person> people(DataInputStream in,int count,int max)throws IOException{
        if(count>max)throw new IllegalArgumentException("Too many people");
        var list=new ArrayList<Person>(count);var seen=new HashSet<UUID>();
        for(int i=0;i<count;i++){var p=person(in);if(!seen.add(p.id()))throw new IllegalArgumentException("Duplicate person");list.add(p);}
        return List.copyOf(list);
    }
    private static Person person(DataInputStream in)throws IOException{
        var id=new UUID(in.readLong(),in.readLong());String name=in.readUTF();int house=in.readByte();
        String skin=in.readUTF();boolean slim=in.readUnsignedByte()!=0;int flags=in.readUnsignedByte();
        return new Person(id,name,house,skin,slim,flags);
    }

    /** 테스트·모의 서버용: 프록시와 같은 형식으로 쓴다. */
    public static byte[] encode(Message m){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(VERSION);out.writeByte(m.kind());
            switch(m.kind()){
                case K_STATE->{var s=m.state();out.writeByte(s.open()?1:0);out.writeByte(s.capacity());out.writeByte(s.members().size());for(var p:s.members())write(out,p);out.writeByte(s.invites().size());for(var p:s.invites())write(out,p);}
                case K_CANDIDATES->{out.writeShort(m.people().size());for(var p:m.people())write(out,p);}
                case K_INVITE->write(out,m.person());
                case K_RESULT->{out.writeByte(m.ok()?1:0);out.writeUTF(m.message());}
                default->throw new IllegalArgumentException("kind");
            }
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static void write(DataOutputStream out,Person p)throws IOException{
        out.writeLong(p.id().getMostSignificantBits());out.writeLong(p.id().getLeastSignificantBits());
        out.writeUTF(p.nickname());out.writeByte(p.house());out.writeUTF(p.skin());out.writeByte(p.slim()?1:0);out.writeByte(p.flags());
    }
}
