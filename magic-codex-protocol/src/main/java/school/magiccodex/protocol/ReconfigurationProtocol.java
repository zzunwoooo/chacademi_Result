package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

public final class ReconfigurationProtocol {
    public static final String REQUEST="magiccodex:reconfig_request", RESPONSE="magiccodex:reconfig_response";
    public static final int MAX_BYTES=4096, APPLY=0, SELECT=1, CLOSE=2;
    public record Request(long token,int action,int mode) {
        public Request { if(token<=0||action<0||action>2||mode<0||mode>2)throw new IllegalArgumentException(); }
    }
    // state: offer / committed / refused. Values always originate on the server.
    // kind (장신구 재구성, 오너 2026-10-26): 0 마법봉 (before/after = 지능·추가 마나·마법 가속),
    // 1 장신구 (지능·최대 마나·초당 마나 회복 = EnhancementBridge 장신구 강화와 같은 세 값). 모드 2(코어 성향)에서는
    // 대상이 코어이고 kind 는 성향 표시(세 번째 능력치 이름)만 정한다.
    // 바이트: kind 0 은 예전과 똑같은 버전 1 (옛 클라 그대로 읽음), kind 1 만 버전 2 + 끝에 kind 1바이트.
    public static final int WAND=0, ACCESSORY=1;
    public record Response(long token,int mode,int state,int slot,String name,String message,int credits,
                           int nodes,int affinity,int nextAffinity,int attempts,int successes,int limit,
                           int nextAttempts,List<Double> before,List<Double> after,boolean available,int kind) {
        /** 예전 생성자 = 마법봉. */
        public Response(long token,int mode,int state,int slot,String name,String message,int credits,
                        int nodes,int affinity,int nextAffinity,int attempts,int successes,int limit,
                        int nextAttempts,List<Double> before,List<Double> after,boolean available) {
            this(token,mode,state,slot,name,message,credits,nodes,affinity,nextAffinity,attempts,successes,limit,nextAttempts,before,after,available,WAND);
        }
        public Response {
            before=List.copyOf(before);after=List.copyOf(after);
            if(kind<0||kind>1)throw new IllegalArgumentException();
            if(token<=0||mode<0||mode>2||state<0||state>2||slot< -1||slot>35||name.length()>80||message.length()>180||credits<0||credits>1000000||nodes<0||nodes>10||affinity<0||affinity>2||nextAffinity< -1||nextAffinity>2||attempts<0||attempts>100||successes<0||successes>attempts||limit<1||limit>100||nextAttempts<0||nextAttempts>100||before.size()!=3||after.size()!=3)throw new IllegalArgumentException();
            for(var list:List.of(before,after))for(double v:list)if(!Double.isFinite(v)||v<0||v>1000000)throw new IllegalArgumentException();
        }
    }
    public static byte[] request(Request r){return write(d->{d.writeLong(r.token);d.writeByte(r.action);d.writeByte(r.mode);});}
    public static Request request(byte[] b){return read(b,d->new Request(d.readLong(),d.readUnsignedByte(),d.readUnsignedByte()));}
    public static byte[] response(Response r){return write(d->{d.writeByte(r.kind==WAND?1:2);d.writeLong(r.token);for(int v:new int[]{r.mode,r.state,r.slot,r.credits,r.nodes,r.affinity,r.nextAffinity,r.attempts,r.successes,r.limit,r.nextAttempts})d.writeInt(v);d.writeUTF(r.name);d.writeUTF(r.message);for(double v:r.before)d.writeDouble(v);for(double v:r.after)d.writeDouble(v);d.writeBoolean(r.available);if(r.kind!=WAND)d.writeByte(r.kind);});}
    public static Response response(byte[] b){return read(b,d->{int version=d.readUnsignedByte();if(version<1||version>2)throw new IOException();long token=d.readLong();int[] v=new int[11];for(int i=0;i<v.length;i++)v[i]=d.readInt();String name=d.readUTF(),msg=d.readUTF();List<Double> a=new ArrayList<>(),z=new ArrayList<>();for(int i=0;i<3;i++)a.add(d.readDouble());for(int i=0;i<3;i++)z.add(d.readDouble());return new Response(token,v[0],v[1],v[2],name,msg,v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],a,z,d.readBoolean(),version==2?d.readUnsignedByte():WAND);});}
    private interface Writer{void go(DataOutputStream d)throws IOException;}
    private interface Reader<T>{T go(DataInputStream d)throws IOException;}
    private static byte[] write(Writer w){try{var b=new ByteArrayOutputStream();w.go(new DataOutputStream(b));if(b.size()>MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static <T>T read(byte[] b,Reader<T> r){try{if(b.length>MAX_BYTES)throw new IOException();var d=new DataInputStream(new ByteArrayInputStream(b));T v=r.go(d);if(d.available()!=0)throw new IOException();return v;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
