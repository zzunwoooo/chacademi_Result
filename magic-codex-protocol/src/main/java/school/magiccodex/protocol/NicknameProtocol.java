package school.magiccodex.protocol;

import java.io.*;
import java.util.UUID;

/** A request carries no target UUID: only the authenticated sender can change their name. */
public final class NicknameProtocol {
    public static final String REQUEST="magiccodex:nickname_request", RESPONSE="magiccodex:nickname_response";
    public static final int OPEN=1,SAVE=2,CLOSE=3,SNAPSHOT=1,NOTICE=2,MAX_BYTES=1024,MAX_NAME=16;
    /** Server push (sequence 0): FIRST = open the mandatory first-nickname screen, FIRST_DONE = it was saved, close it. */
    public static final int FIRST=3,FIRST_DONE=4;
    private static final int VERSION=0x4E494301;
    public record Request(int action,long sequence,long session,long revision,String nickname){}
    public record Response(int kind,long sequence,long session,long revision,UUID owner,String account,String nickname,String prefix,String suffix,String message){}
    public static String validate(String value){
        if(value==null||value.isEmpty()||value.length()>MAX_NAME||!value.matches("[\\p{L}\\p{N}_-]{1,16}"))
            throw new IllegalArgumentException("닉네임은 1~16자의 한글·영문 등 문자, 숫자, _ 또는 -로 입력해 주세요.");
        return value;
    }
    private interface Writer{void write(DataOutputStream d)throws IOException;}
    private static byte[] write(Writer writer){try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(VERSION);writer.write(d);d.flush();if(b.size()>MAX_BYTES)throw new IOException("size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream read(byte[] bytes)throws IOException{if(bytes==null||bytes.length<4||bytes.length>MAX_BYTES)throw new IOException("size");var d=new DataInputStream(new ByteArrayInputStream(bytes));if(d.readInt()!=VERSION)throw new IOException("version");return d;}
    private static String str(DataInputStream d,int limit)throws IOException{var s=d.readUTF();if(s.length()>limit)throw new IOException("text");return s;}
    private static void str(DataOutputStream d,String text,int limit)throws IOException{if(text==null||text.length()>limit)throw new IOException("text");d.writeUTF(text);}
    private static void requestFields(Request r){if(r.action()<OPEN||r.action()>CLOSE||r.sequence()<=0||r.session()<0||r.revision()<0||r.nickname()==null||r.nickname().length()>MAX_NAME)throw new IllegalArgumentException("request");}
    public static byte[] encode(Request r){requestFields(r);return write(d->{d.writeByte(r.action());d.writeLong(r.sequence());d.writeLong(r.session());d.writeLong(r.revision());str(d,r.nickname(),MAX_NAME);});}
    public static Request request(byte[] bytes){try{var d=read(bytes);var r=new Request(d.readUnsignedByte(),d.readLong(),d.readLong(),d.readLong(),str(d,MAX_NAME));requestFields(r);if(d.available()!=0)throw new IOException("trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static void responseFields(Response r){if(r.kind()<SNAPSHOT||r.kind()>FIRST_DONE||r.sequence()<0||r.session()<0||r.revision()<0||r.owner()==null)throw new IllegalArgumentException("response");}
    public static byte[] encode(Response r){responseFields(r);return write(d->{d.writeByte(r.kind());d.writeLong(r.sequence());d.writeLong(r.session());d.writeLong(r.revision());d.writeLong(r.owner().getMostSignificantBits());d.writeLong(r.owner().getLeastSignificantBits());str(d,r.account(),16);str(d,r.nickname(),MAX_NAME);str(d,r.prefix(),64);str(d,r.suffix(),64);str(d,r.message(),160);});}
    public static Response response(byte[] bytes){try{var d=read(bytes);var r=new Response(d.readUnsignedByte(),d.readLong(),d.readLong(),d.readLong(),new UUID(d.readLong(),d.readLong()),str(d,16),str(d,MAX_NAME),str(d,64),str(d,64),str(d,160));responseFields(r);if(d.available()!=0)throw new IOException("trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
