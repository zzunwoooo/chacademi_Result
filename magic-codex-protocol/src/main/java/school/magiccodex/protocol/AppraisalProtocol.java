package school.magiccodex.protocol;
import java.io.*;
public final class AppraisalProtocol {
 public static final String REQUEST="magiccodex:core_request",RESPONSE="magiccodex:core_response";
 public static final int MAX_BYTES=2048;
 public record Request(long token){}
 // state: 0 unidentified, 1 identified, 2 broken. result means play the committed result.
 public record Response(long token,int state,int nodes,boolean result,boolean repaired,double cost,double balance,int materials,String message,java.util.List<Double> chances){
  public Response { chances=java.util.List.copyOf(chances); }
  public Response(long token,int state,int nodes,boolean result,boolean repaired,double cost,double balance,int materials,String message){this(token,state,nodes,result,repaired,cost,balance,materials,message,java.util.List.of());}
 }
 public static byte[] encodeRequest(Request r){if(r.token()<=0)throw new IllegalArgumentException();return write(d->d.writeLong(r.token()));}
 public static Request decodeRequest(byte[] b){if(b.length!=8)throw new IllegalArgumentException();try{var r=new Request(new DataInputStream(new ByteArrayInputStream(b)).readLong());encodeRequest(r);return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static byte[] encodeResponse(Response r){
  if(r.token()<0||r.state()<0||r.state()>2||r.nodes()<0||r.nodes()>10||!Double.isFinite(r.cost())||r.cost()<0||!Double.isFinite(r.balance())||r.balance()<-1||r.materials()<0||r.materials()>64||r.message()==null||r.message().length()>240)throw new IllegalArgumentException();
  if(!r.chances().isEmpty()&&r.chances().size()!=10)throw new IllegalArgumentException();
  for(double p:r.chances())if(!Double.isFinite(p)||p<0||p>1)throw new IllegalArgumentException();
  return write(d->{d.writeLong(r.token());d.writeByte(r.state());d.writeByte(r.nodes());d.writeBoolean(r.result());d.writeBoolean(r.repaired());d.writeDouble(r.cost());d.writeDouble(r.balance());d.writeInt(r.materials());d.writeUTF(r.message());if(!r.chances().isEmpty())for(double p:r.chances())d.writeDouble(p);});
 }
 public static Response decodeResponse(byte[] b){if(b.length>MAX_BYTES)throw new IllegalArgumentException();try{var d=new DataInputStream(new ByteArrayInputStream(b));var r=new Response(d.readLong(),d.readUnsignedByte(),d.readUnsignedByte(),d.readBoolean(),d.readBoolean(),d.readDouble(),d.readDouble(),d.readInt(),d.readUTF());if(d.available()==80){var ps=new java.util.ArrayList<Double>();for(int i=0;i<10;i++)ps.add(d.readDouble());r=new Response(r.token(),r.state(),r.nodes(),r.result(),r.repaired(),r.cost(),r.balance(),r.materials(),r.message(),ps);}
encodeResponse(r);if(d.available()!=0)throw new IOException();return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
 private interface Writer{void run(DataOutputStream d)throws IOException;}
 private static byte[] write(Writer w){try{var b=new ByteArrayOutputStream();w.run(new DataOutputStream(b));return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 private AppraisalProtocol(){}
}
