package school.magiccodex.protocol;

import java.io.*;

/** Small versioned messages; the client never supplies a desired rank. */
public final class AscensionProtocol {
    public static final String REQUEST="magiccodex:ascend_request", RESPONSE="magiccodex:ascend_response";
    public static final int HELLO=0, CLAIM=1, SNAPSHOT=0, OFFER=1, SUCCESS=2, DENIED=3, MAX_BYTES=600;
    private static final int MAGIC=0x41534301;
    public record Request(int action,long token){}
    public record Response(int action,long token,int from,int to,boolean allowed,String message){}
    public static byte[] encodeRequest(Request r){
        if(r.action()<0||r.action()>1||r.token()<0||(r.action()==HELLO?r.token()!=0:r.token()==0))throw new IllegalArgumentException();
        return write(d->{d.writeInt(MAGIC);d.writeByte(r.action());d.writeLong(r.token());});
    }
    public static Request decodeRequest(byte[] b){try{
        if(b.length!=13)throw new IOException();var d=input(b);var r=new Request(d.readUnsignedByte(),d.readLong());encodeRequest(r);return r;
    }catch(IOException e){throw new IllegalArgumentException(e);}}
    public static byte[] encodeResponse(Response r){
        if(r.action()<0||r.action()>3||r.token()<0||r.from()<1||r.from()>9||r.to()<1||r.to()>9||r.message()==null||r.message().length()>160
            ||((r.action()==OFFER||r.action()==SUCCESS)&&r.token()==0)
            ||(r.action()==SUCCESS&&(r.to()!=r.from()+1||!r.allowed())))throw new IllegalArgumentException();
        return write(d->{d.writeInt(MAGIC);d.writeByte(r.action());d.writeLong(r.token());d.writeByte(r.from());d.writeByte(r.to());d.writeBoolean(r.allowed());d.writeUTF(r.message());});
    }
    public static Response decodeResponse(byte[] b){try{
        if(b.length<18||b.length>MAX_BYTES)throw new IOException();var d=input(b);
        var r=new Response(d.readUnsignedByte(),d.readLong(),d.readUnsignedByte(),d.readUnsignedByte(),d.readBoolean(),d.readUTF());
        encodeResponse(r);if(d.available()!=0)throw new IOException();return r;
    }catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream input(byte[] b)throws IOException{var d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=MAGIC)throw new IOException();return d;}
    private interface Writer{void write(DataOutputStream d)throws IOException;}
    private static byte[] write(Writer w){try{var b=new ByteArrayOutputStream();w.write(new DataOutputStream(b));return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private AscensionProtocol(){}
}
