package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Small, bounded messages. The authenticated connection is always the acting player. */
public final class SocialProtocol {
    public static final String REQUEST="magiccodex:social_request", RESPONSE="magiccodex:social_response";
    public static final int MAX_BYTES=16000, LIMIT=50, TEXT_LIMIT=240;
    public static final int LIST=1, ADD=2, REMOVE=3, WHISPER=4, SEND=5, CLOSE=6, CANCEL=7;
    /** 친구 신청 응답. target = 신청을 보낸 사람(ACCEPT/DECLINE) 또는 내가 신청한 사람(WITHDRAW). */
    public static final int ACCEPT=8, DECLINE=9, WITHDRAW=10;
    public static final int SNAPSHOT=1, NOTICE=2, COMPOSE=3, SENT=4, RECEIVED=5, OPEN=6;
    /** FRIEND_REQUEST: 새 신청 알림(target/name/dormitory = 보낸 사람). INCOMING/OUTGOING: 받은/보낸 대기 목록 전체(entries). */
    public static final int FRIEND_REQUEST=7, INCOMING=8, OUTGOING=9;
    public static final UUID NONE=new UUID(0,0);
    /** v2: 친구 신청(동의) 흐름. v1 상대와는 서로 해석하지 않고 조용히 버린다. */
    private static final int VERSION=0x534F4302, LEGACY=0x534F4301;
    private static final int ACTIONS=10, KINDS=9;
    /** 이전 버전(v1) 모드/플러그인이 보낸 패킷인지 — 업데이트 안내용. */
    public static boolean legacy(byte[] b){return b!=null&&b.length>=4&&b.length<=MAX_BYTES&&(((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255))==LEGACY;}
    private static boolean valid(Request r){
        if(r.action()<1||r.action()>ACTIONS||r.sequence()<=0||r.ticket()<0||r.target()==null||r.text()==null)return false;
        return r.action()<ACCEPT||(!r.target().equals(NONE)&&r.ticket()==0&&r.text().isEmpty());
    }
    private static boolean valid(int kind,long seq,UUID target,long ticket,String name,int cooldown,int size){
        if(kind<1||kind>KINDS||seq<0||ticket<0||cooldown<0||cooldown>86400000||size>LIMIT)return false;
        if(kind==FRIEND_REQUEST)return !target.equals(NONE)&&!name.isBlank()&&size==0;
        return true;
    }
    public record Entry(UUID id,String name,String dormitory,boolean online){}
    public record Request(int action,long sequence,UUID target,long ticket,String text){}
    public record Response(int kind,long sequence,UUID target,long ticket,String name,String dormitory,String text,int cooldown,List<Entry> entries){
        public Response{entries=List.copyOf(entries);}
    }
    public static String cleanMessage(String s){
        if(s==null||s.isBlank()||s.length()>TEXT_LIMIT||s.codePoints().anyMatch(c->Character.isISOControl(c)||c==0xA7||Character.getType(c)==Character.FORMAT))throw new IllegalArgumentException("Invalid message");
        return s.strip();
    }
    private interface Writer{void write(DataOutputStream d)throws IOException;}
    private static byte[] out(Writer w){try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(VERSION);w.write(d);d.flush();if(b.size()>MAX_BYTES)throw new IOException("Size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream in(byte[] b)throws IOException{if(b.length<4||b.length>MAX_BYTES)throw new IOException("Size");var d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=VERSION)throw new IOException("Version");return d;}
    private static void uuid(DataOutputStream d,UUID id)throws IOException{d.writeLong(id.getMostSignificantBits());d.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream d)throws IOException{return new UUID(d.readLong(),d.readLong());}
    private static void str(DataOutputStream d,String s,int max)throws IOException{if(s==null||s.length()>max)throw new IOException("String");d.writeUTF(s);}
    private static String str(DataInputStream d,int max)throws IOException{var s=d.readUTF();if(s.length()>max)throw new IOException("String");return s;}
    public static byte[] encode(Request r){if(!valid(r))throw new IllegalArgumentException("Request");return out(d->{d.writeByte(r.action());d.writeLong(r.sequence());uuid(d,r.target());d.writeLong(r.ticket());str(d,r.text(),TEXT_LIMIT);});}
    public static Request request(byte[] bytes){try{var d=in(bytes);var r=new Request(d.readUnsignedByte(),d.readLong(),uuid(d),d.readLong(),str(d,TEXT_LIMIT));if(d.available()!=0||!valid(r))throw new IOException("Request");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static byte[] encode(Response r){return out(d->{if(r.target()==null||r.name()==null||!valid(r.kind(),r.sequence(),r.target(),r.ticket(),r.name(),r.cooldown(),r.entries().size()))throw new IOException("Response");d.writeByte(r.kind());d.writeLong(r.sequence());uuid(d,r.target());d.writeLong(r.ticket());str(d,r.name(),16);str(d,r.dormitory(),64);str(d,r.text(),TEXT_LIMIT);d.writeInt(r.cooldown());d.writeByte(r.entries().size());for(var e:r.entries()){uuid(d,e.id());str(d,e.name(),16);str(d,e.dormitory(),64);d.writeBoolean(e.online());}});}
    public static Response response(byte[] bytes){try{var d=in(bytes);int kind=d.readUnsignedByte();long seq=d.readLong();UUID target=uuid(d);long ticket=d.readLong();String name=str(d,16),dorm=str(d,64),text=str(d,TEXT_LIMIT);int cd=d.readInt(),size=d.readUnsignedByte();if(!valid(kind,seq,target,ticket,name,cd,size))throw new IOException("Response");var entries=new ArrayList<Entry>();var ids=new HashSet<UUID>();for(int i=0;i<size;i++){var e=new Entry(uuid(d),str(d,16),str(d,64),d.readBoolean());if(!ids.add(e.id()))throw new IOException("Duplicate");entries.add(e);}if(d.available()!=0)throw new IOException("Trailing bytes");return new Response(kind,seq,target,ticket,name,dorm,text,cd,entries);}catch(IOException e){throw new IllegalArgumentException(e);}}
}
