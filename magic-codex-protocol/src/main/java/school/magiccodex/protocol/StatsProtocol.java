package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * Bounded public profile snapshots, separate from the viewer's personal mana channel.
 *
 * <p>v2 ({@link #REQUEST}/{@link #RESPONSE}): 옛 모드용, 바이트 그대로 유지.
 * <p>v3 ({@link #REQUEST3}/{@link #RESPONSE3}, 정보창 v4 · 2026-10-26): 별도 채널이라 옛 클라는 v3 를 보지 않는다.
 * 새 서버는 상대가 v3 응답 채널을 듣고 있으면 v3, 아니면 v2 로 보낸다. 새 클라는 서버가 v3 요청 채널을 열었을 때만 v3 로 요청한다.
 * v3 추가: 통합 칭호, 하우징 좋아요 합계, 퀘스트 요약(진행 중 수·추적 퀘스트), 장신구 3칸(반지·귀걸이·목걸이), 장착 아티팩트·빗자루 ID,
 * 인기도 상태(오늘 이 상대 조절했는지·내 해제권 개수), 인기도 내리기({@link #DOWN}), 내 정보 열기({@link #SELF}), 인기도 결과({@link #VOTED}).
 */
public final class StatsProtocol {
    public static final String REQUEST="magiccodex:stats_request", RESPONSE="magiccodex:stats_response";
    public static final String REQUEST3="magiccodex:stats_request3", RESPONSE3="magiccodex:stats_response3";
    public static final int MAX_BYTES=30000, OPEN=1, SNAPSHOT=2, NOTICE=3, GONE=4;
    /** v3 응답 종류: 인기도 올리기·내리기 결과 (문구 + {@link Vote}). */
    public static final int VOTED=5;
    public static final int MAX_BYTES3=64000;
    public static final int REFRESH=1, CLOSE=2, FRIEND=3, POPULARITY=4;
    /** v3 요청: 인기도 내리기 · 내 정보 (target = 나). POPULARITY(4) = 인기도 올리기. */
    public static final int DOWN=5, SELF=6;
    public static final int ACCESSORIES=3, MAX_TICKETS=999_999;
    private static final int VERSION=0x53544102, VERSION3=0x53544103;
    public record Request(int action,long session,UUID target){}
    public record Profile(UUID id,String name,int circle,String dormitory,Double power,Double popularity,
                          double health,double armor,Double mana,Double maximum,Double regeneration,
                          int learned,int total,String texture,String signature,List<byte[]> equipment,Double haste){
        public Profile(UUID id,String name,int circle,String dormitory,Double power,Double popularity,double health,double armor,Double mana,Double maximum,Double regeneration,int learned,int total,String texture,String signature,List<byte[]> equipment){this(id,name,circle,dormitory,power,popularity,health,armor,mana,maximum,regeneration,learned,total,texture,signature,equipment,null);}
    }
    public record Response(int kind,long session,UUID target,String message,Profile profile){}
    /**
     * v3 추가 값. null 은 "모름" (화면에 —), 0 으로 꾸미지 않는다.
     * title = 접두·접미 칭호를 합친 한 줄 ("" = 없음) · likes = 하우징 좋아요 합계 · activeQuests = 진행 중 퀘스트 수
     * · tracked = 추적 퀘스트 이름 ("" = 없음), trackedCurrent/trackedRequired = 목표 진행 · accessories = 반지·귀걸이·목걸이 아이템 바이트
     * · artifact/broom = 장착 아티팩트·빗자루 ID ("" = 없음) · voted = 보는 사람이 오늘 이 상대 인기도를 조절했는지 · tickets = 보는 사람의 인기도 제한 해제권 수.
     */
    public record Extra(String title,Integer likes,Integer activeQuests,String tracked,int trackedCurrent,int trackedRequired,
                        List<byte[]> accessories,String artifact,String broom,boolean voted,int tickets){}
    /** 인기도 결과 (응답 {@link #VOTED}): 새 점수 · 오늘 이 상대 조절했는지 · 내 해제권 수. */
    public record Vote(Double popularity,boolean voted,int tickets){}
    /** v3 응답: base.kind 가 OPEN/SNAPSHOT 이면 extra, VOTED 면 vote 가 있다. */
    public record Response3(Response base,Extra extra,Vote vote){}
    private StatsProtocol(){}
    private interface Writer {void write(DataOutputStream d)throws IOException;}
    private static byte[] encode(int version,int max,Writer writer){try{var bytes=new ByteArrayOutputStream();var d=new DataOutputStream(bytes);d.writeInt(version);writer.write(d);d.flush();if(bytes.size()>max)throw new IllegalArgumentException("Stats payload too large");return bytes.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream input(byte[] bytes,int version,int max)throws IOException{if(bytes.length<4||bytes.length>max)throw new IOException("Invalid size");var d=new DataInputStream(new ByteArrayInputStream(bytes));if(d.readInt()!=version)throw new IOException("Invalid version");return d;}
    private static void uuid(DataOutputStream d,UUID id)throws IOException{d.writeLong(id.getMostSignificantBits());d.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream d)throws IOException{return new UUID(d.readLong(),d.readLong());}
    private static void string(DataOutputStream d,String s,int max)throws IOException{if(s==null||s.length()>max)throw new IOException("Invalid string");d.writeUTF(s);}
    private static String string(DataInputStream d,int max)throws IOException{String s=d.readUTF();if(s.length()>max)throw new IOException("Invalid string");return s;}
    private static double finite(double v)throws IOException{if(!Double.isFinite(v)||Math.abs(v)>1e9)throw new IOException("Invalid number");return v;}
    private static void number(DataOutputStream d,Double v)throws IOException{d.writeBoolean(v!=null);if(v!=null)d.writeDouble(finite(v));}
    private static Double number(DataInputStream d)throws IOException{return d.readBoolean()?finite(d.readDouble()):null;}
    private static void integer(DataOutputStream d,Integer v)throws IOException{d.writeBoolean(v!=null);if(v!=null){if(v<0||v>1_000_000_000)throw new IOException("Invalid count");d.writeInt(v);}}
    private static Integer integer(DataInputStream d)throws IOException{if(!d.readBoolean())return null;int v=d.readInt();if(v<0||v>1_000_000_000)throw new IOException("Invalid count");return v;}
    private static void item(DataOutputStream d,byte[] item)throws IOException{if(item==null||item.length>3072)throw new IOException("Large item");d.writeShort(item.length);d.write(item);}
    private static byte[] item(DataInputStream d)throws IOException{int size=d.readUnsignedShort();if(size>3072||size>d.available())throw new IOException("Invalid item size");return d.readNBytes(size);}

    // ---------------------------------------------------------------- v2 (옛 모드)
    private static void requestValid(Request r,int maxAction)throws IOException{if(r.action()<1||r.action()>maxAction||r.session()<=0||r.target()==null)throw new IOException("Invalid request");}
    public static byte[] encodeRequest(Request r){return encode(VERSION,MAX_BYTES,d->{requestValid(r,4);d.writeByte(r.action());d.writeLong(r.session());uuid(d,r.target());});}
    public static Request decodeRequest(byte[] bytes){try{var d=input(bytes,VERSION,MAX_BYTES);var r=new Request(d.readUnsignedByte(),d.readLong(),uuid(d));requestValid(r,4);if(d.available()!=0)throw new IOException("Trailing bytes");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static void head(DataOutputStream d,Response r,int maxKind)throws IOException{
        if(r.kind()<1||r.kind()>maxKind||r.session()<=0)throw new IOException("Invalid response");
        d.writeByte(r.kind());d.writeLong(r.session());uuid(d,r.target());string(d,r.message(),160);
    }
    private static void profile(DataOutputStream d,Response r)throws IOException{
        var p=r.profile();validate(p);if(!p.id().equals(r.target()))throw new IOException("Wrong target");
        string(d,p.name(),16);d.writeByte(p.circle());string(d,p.dormitory(),64);number(d,p.power());number(d,p.popularity());
        d.writeDouble(finite(p.health()));d.writeDouble(finite(p.armor()));number(d,p.mana());number(d,p.maximum());number(d,p.regeneration());number(d,p.haste());
        d.writeInt(p.learned());d.writeInt(p.total());string(d,p.texture(),4096);string(d,p.signature(),2048);
        for(byte[] it:p.equipment())item(d,it);
    }
    private static Profile profile(DataInputStream d,UUID id)throws IOException{
        String name=string(d,16);int circle=d.readUnsignedByte();String dorm=string(d,64);Double power=number(d),popularity=number(d);double health=finite(d.readDouble()),armor=finite(d.readDouble());Double mana=number(d),max=number(d),regen=number(d),haste=number(d);int learned=d.readInt(),total=d.readInt();String texture=string(d,4096),signature=string(d,2048);var items=new ArrayList<byte[]>();
        for(int i=0;i<6;i++)items.add(item(d));
        var p=new Profile(id,name,circle,dorm,power,popularity,health,armor,mana,max,regen,learned,total,texture,signature,List.copyOf(items),haste);validate(p);return p;
    }
    public static byte[] encodeResponse(Response r){return encode(VERSION,MAX_BYTES,d->{
        head(d,r,4);
        if(r.kind()==OPEN||r.kind()==SNAPSHOT)profile(d,r);
    });}
    private static void validate(Profile p)throws IOException{if(p.haste()!=null&&(p.haste()<0||!Double.isFinite(p.haste())||p.haste()>MagicHaste.MAX))throw new IOException("Invalid haste");if(p.circle()<0||p.circle()>9||p.total()<0||p.total()>4096||p.learned()<0||p.learned()>p.total()||p.equipment().size()!=6)throw new IOException("Invalid profile");}
    public static Response decodeResponse(byte[] bytes){try{var d=input(bytes,VERSION,MAX_BYTES);int kind=d.readUnsignedByte();long session=d.readLong();UUID id=uuid(d);String message=string(d,160);Profile p=null;
        if(kind<1||kind>4||session<=0)throw new IOException("Invalid response");
        if(kind==OPEN||kind==SNAPSHOT)p=profile(d,id);
        if(d.available()!=0)throw new IOException("Trailing bytes");return new Response(kind,session,id,message,p);
    }catch(IOException e){throw new IllegalArgumentException(e);}}

    // ---------------------------------------------------------------- v3 (정보창 v4)
    public static byte[] encodeRequest3(Request r){return encode(VERSION3,MAX_BYTES,d->{requestValid(r,6);d.writeByte(r.action());d.writeLong(r.session());uuid(d,r.target());});}
    public static Request decodeRequest3(byte[] bytes){try{var d=input(bytes,VERSION3,MAX_BYTES);var r=new Request(d.readUnsignedByte(),d.readLong(),uuid(d));requestValid(r,6);if(d.available()!=0)throw new IOException("Trailing bytes");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static void validate(Extra e)throws IOException{
        if(e==null||e.accessories()==null||e.accessories().size()!=ACCESSORIES)throw new IOException("Invalid extra");
        if(e.trackedRequired()<0||e.trackedRequired()>1_000_000||e.trackedCurrent()<0||e.trackedCurrent()>e.trackedRequired())throw new IOException("Invalid quest");
        if(e.tickets()<0||e.tickets()>MAX_TICKETS)throw new IOException("Invalid tickets");
    }
    private static void tickets(int tickets)throws IOException{if(tickets<0||tickets>MAX_TICKETS)throw new IOException("Invalid tickets");}
    public static byte[] encodeResponse3(Response3 r){return encode(VERSION3,MAX_BYTES3,d->{
        var b=r.base();head(d,b,5);
        if(b.kind()==OPEN||b.kind()==SNAPSHOT){
            profile(d,b);var e=r.extra();validate(e);
            string(d,e.title(),96);integer(d,e.likes());integer(d,e.activeQuests());string(d,e.tracked(),64);d.writeInt(e.trackedCurrent());d.writeInt(e.trackedRequired());
            for(byte[] it:e.accessories())item(d,it);
            string(d,e.artifact(),64);string(d,e.broom(),64);d.writeBoolean(e.voted());d.writeInt(e.tickets());
        }else if(b.kind()==VOTED){
            var v=r.vote();if(v==null)throw new IOException("Missing vote");tickets(v.tickets());
            number(d,v.popularity());d.writeBoolean(v.voted());d.writeInt(v.tickets());
        }
    });}
    public static Response3 decodeResponse3(byte[] bytes){try{var d=input(bytes,VERSION3,MAX_BYTES3);int kind=d.readUnsignedByte();long session=d.readLong();UUID id=uuid(d);String message=string(d,160);
        if(kind<1||kind>5||session<=0)throw new IOException("Invalid response");
        Profile p=null;Extra extra=null;Vote vote=null;
        if(kind==OPEN||kind==SNAPSHOT){
            p=profile(d,id);
            String title=string(d,96);Integer likes=integer(d),quests=integer(d);String tracked=string(d,64);int cur=d.readInt(),req=d.readInt();
            var acc=new ArrayList<byte[]>();for(int i=0;i<ACCESSORIES;i++)acc.add(item(d));
            String artifact=string(d,64),broom=string(d,64);boolean voted=d.readBoolean();int tickets=d.readInt();
            extra=new Extra(title,likes,quests,tracked,cur,req,List.copyOf(acc),artifact,broom,voted,tickets);validate(extra);
        }else if(kind==VOTED){
            Double score=number(d);boolean voted=d.readBoolean();int tickets=d.readInt();tickets(tickets);vote=new Vote(score,voted,tickets);
        }
        if(d.available()!=0)throw new IOException("Trailing bytes");
        return new Response3(new Response(kind,session,id,message,p),extra,vote);
    }catch(IOException e){throw new IllegalArgumentException(e);}}
}
