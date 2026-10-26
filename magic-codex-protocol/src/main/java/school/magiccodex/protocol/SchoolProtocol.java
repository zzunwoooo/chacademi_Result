package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Bounded school standings, latest donations and per-spell attribution. */
public final class SchoolProtocol {
    public static final String REQUEST="magiccodex:school_request",RESPONSE="magiccodex:school_response";
    public static final int MAX_BYTES=24000,LIST=1,LOOKUP=2,DONATE=3,IDENTITY=4,PAGE_SIZE=20;
    public static final List<String> HOUSES=List.of("아르케온","루미나","베스티아즈","노크세르");
    private static final int VERSION=0x53434801;
    public record Request(int action,long sequence,int page,String spell){}
    public record Donation(String spell,String spellName,UUID donor,String nickname,int house,long time){}
    public record Response(int action,long sequence,long revision,int page,int total,String spell,String message,String nickname,String dormitory,List<Long> scores,List<Donation> records){
        public Response {scores=List.copyOf(scores);records=List.copyOf(records);}
    }
    private interface Writer{void write(DataOutputStream d)throws IOException;}
    private static byte[] out(Writer w){try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(VERSION);w.write(d);if(b.size()>MAX_BYTES)throw new IOException("Size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream in(byte[] b)throws IOException{if(b.length<4||b.length>MAX_BYTES)throw new IOException("Size");var d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=VERSION)throw new IOException("Version");return d;}
    private static void str(DataOutputStream d,String s,int max)throws IOException{if(s==null||s.length()>max)throw new IOException("String");d.writeUTF(s);}
    private static String str(DataInputStream d,int max)throws IOException{String s=d.readUTF();if(s.length()>max)throw new IOException("String");return s;}
    private static void valid(Request r)throws IOException{if(r.action()<1||r.action()>4||r.sequence()<1||r.page()<0||r.page()>204||!r.spell().matches("[a-z0-9_-]{0,64}"))throw new IOException("Request");if((r.action()==LOOKUP||r.action()==DONATE)&&r.spell().isEmpty())throw new IOException("Spell");}
    public static byte[] encode(Request r){return out(d->{valid(r);d.writeByte(r.action());d.writeLong(r.sequence());d.writeInt(r.page());str(d,r.spell(),64);});}
    public static Request request(byte[] b){try{var d=in(b);var r=new Request(d.readUnsignedByte(),d.readLong(),d.readInt(),str(d,64));valid(r);if(d.available()!=0)throw new IOException("Trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static byte[] encode(Response r){return out(d->{
        if(r.action()<1||r.action()>4||r.sequence()<0||r.revision()<0||r.total()<0||r.total()>4096||r.page()<0||r.page()>204||r.scores().size()!=4||r.records().size()>PAGE_SIZE)throw new IOException("Response");
        d.writeByte(r.action());d.writeLong(r.sequence());d.writeLong(r.revision());d.writeInt(r.page());d.writeInt(r.total());str(d,r.spell(),64);str(d,r.message(),160);str(d,r.nickname(),16);str(d,r.dormitory(),64);
        for(long s:r.scores())d.writeLong(s);
        d.writeByte(r.records().size());for(var e:r.records()){if(e.house()<0||e.house()>3||e.time()<0)throw new IOException("Record");str(d,e.spell(),64);str(d,e.spellName(),100);d.writeLong(e.donor().getMostSignificantBits());d.writeLong(e.donor().getLeastSignificantBits());str(d,e.nickname(),16);d.writeByte(e.house());d.writeLong(e.time());}
    });}
    public static Response response(byte[] b){try{var d=in(b);int action=d.readUnsignedByte();long seq=d.readLong(),rev=d.readLong();int page=d.readInt(),total=d.readInt();String spell=str(d,64),message=str(d,160),name=str(d,16),dorm=str(d,64);var scores=new ArrayList<Long>();for(int i=0;i<4;i++)scores.add(d.readLong());int count=d.readUnsignedByte();if(count>PAGE_SIZE)throw new IOException("Count");var records=new ArrayList<Donation>();for(int i=0;i<count;i++)records.add(new Donation(str(d,64),str(d,100),new UUID(d.readLong(),d.readLong()),str(d,16),d.readUnsignedByte(),d.readLong()));var r=new Response(action,seq,rev,page,total,spell,message,name,dorm,scores,records);encode(r);if(d.available()!=0)throw new IOException("Trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
