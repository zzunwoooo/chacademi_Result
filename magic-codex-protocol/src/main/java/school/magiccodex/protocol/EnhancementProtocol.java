package school.magiccodex.protocol;
import java.io.*;import java.util.*;
public final class EnhancementProtocol {
 public static final String REQUEST="magiccodex:enhance_request",RESPONSE="magiccodex:enhance_response";
 public static final int MAX_BYTES=4096,START=0,HIT=1,CLOSE=2,NEXT=3;
 public record Request(long token,int action,int node){public Request{if(token<=0||action<0||action>3||node<0||node>9)throw new IllegalArgumentException();}}
 // state 0 offer, 1 running, 2 success, 3 failure, 4 refused.
 // kind: 0 마법봉 (before/gains = 지능·추가 마나·마법 가속), 1 장신구 (전투 스펙 2026-10-26: 지능·최대 마나·초당 마나 회복).
 public static final int WAND=0,ACCESSORY=1;
 public record Response(long token,int state,int nodes,int attempts,int limit,int hitMask,int missMask,int window,int lead,String name,String message,double cost,double balance,double base,double bonus,List<Double> before,List<Double> gains,List<Integer> targets,int affinity,int kind){
  public Response(long token,int state,int nodes,int attempts,int limit,int hitMask,int missMask,int window,int lead,String name,String message,double cost,double balance,double base,double bonus,List<Double> before,List<Double> gains,List<Integer> targets,int affinity){this(token,state,nodes,attempts,limit,hitMask,missMask,window,lead,name,message,cost,balance,base,bonus,before,gains,targets,affinity,WAND);}
  public Response(long token,int state,int nodes,int attempts,int limit,int hitMask,int missMask,int window,int lead,String name,String message,double cost,double balance,double base,double bonus,List<Double> before,List<Double> gains,List<Integer> targets){this(token,state,nodes,attempts,limit,hitMask,missMask,window,lead,name,message,cost,balance,base,bonus,before,gains,targets,0,WAND);}
  public Response{before=List.copyOf(before);gains=List.copyOf(gains);targets=List.copyOf(targets);
   if(kind<0||kind>1||affinity<0||affinity>2||token<=0||state<0||state>4||nodes<1||nodes>10||attempts<0||limit<1||limit>100||attempts>100||hitMask<0||hitMask>1023||missMask<0||missMask>1023||window<30||window>500||lead<0||lead>3000||name.length()>80||message.length()>180||before.size()!=3||gains.size()!=3||targets.size()!=nodes)throw new IllegalArgumentException();
   if(!Double.isFinite(cost)||cost<0||cost>1e12||!Double.isFinite(balance)||balance< -1||!Double.isFinite(base)||base<0||base>1||!Double.isFinite(bonus)||bonus<0||bonus>1)throw new IllegalArgumentException();
   for(double n:before)if(!Double.isFinite(n)||n<0||n>1000000)throw new IllegalArgumentException();for(double n:gains)if(!Double.isFinite(n)||n<0||n>1000000)throw new IllegalArgumentException();int last=0;for(int n:targets){if(n<=last||n>60000)throw new IllegalArgumentException();last=n;}
  }
 }
 public static byte[] request(Request r){return write(d->{d.writeLong(r.token);d.writeByte(r.action);d.writeByte(r.node);});}
 public static Request request(byte[] b){return read(b,d->new Request(d.readLong(),d.readUnsignedByte(),d.readUnsignedByte()));}
 public static byte[] response(Response r){return write(d->{d.writeByte(r.kind==WAND?2:3);d.writeLong(r.token);for(int v:new int[]{r.state,r.nodes,r.attempts,r.limit,r.hitMask,r.missMask,r.window,r.lead})d.writeInt(v);d.writeUTF(r.name);d.writeUTF(r.message);for(double v:new double[]{r.cost,r.balance,r.base,r.bonus})d.writeDouble(v);for(double v:r.before)d.writeDouble(v);for(double v:r.gains)d.writeDouble(v);for(int t:r.targets)d.writeInt(t);d.writeByte(r.affinity);if(r.kind!=WAND)d.writeByte(r.kind);});}
 public static Response response(byte[] b){return read(b,d->{int version=d.readUnsignedByte();if(version<1||version>3)throw new IOException();long token=d.readLong();int[] v=new int[8];for(int i=0;i<8;i++)v[i]=d.readInt();String name=d.readUTF(),msg=d.readUTF();double cost=d.readDouble(),balance=d.readDouble(),base=d.readDouble(),bonus=d.readDouble();List<Double> before=new ArrayList<>(),gain=new ArrayList<>();for(int i=0;i<3;i++)before.add(d.readDouble());for(int i=0;i<3;i++)gain.add(d.readDouble());if(v[1]<1||v[1]>10)throw new IOException();List<Integer> targets=new ArrayList<>();for(int i=0;i<v[1];i++)targets.add(d.readInt());return new Response(token,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],name,msg,cost,balance,base,bonus,before,gain,targets,version>=2?d.readUnsignedByte():0,version==3?d.readUnsignedByte():WAND);});}
 private interface Writer{void go(DataOutputStream d)throws IOException;}private interface Reader<T>{T go(DataInputStream d)throws IOException;}
 private static byte[] write(Writer w){try{var b=new ByteArrayOutputStream();w.go(new DataOutputStream(b));if(b.size()>MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 private static <T>T read(byte[] b,Reader<T> r){try{if(b.length>MAX_BYTES)throw new IOException();var d=new DataInputStream(new ByteArrayInputStream(b));T v=r.go(d);if(d.available()!=0)throw new IOException();return v;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
