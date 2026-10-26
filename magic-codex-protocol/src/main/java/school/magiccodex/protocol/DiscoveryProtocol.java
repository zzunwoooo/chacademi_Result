package school.magiccodex.protocol;
import java.io.*;
public final class DiscoveryProtocol {
    public static final String REQUEST="magiccodex:discovery_ack",RESPONSE="magiccodex:discovery";
    private static final int MAGIC=0x44495301;
    public record Notice(long token,String spell,String name,String icon,boolean first){}
    public static byte[] request(long token){try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(MAGIC);d.writeLong(token);return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static long request(byte[] b){try{if(b.length!=12)throw new IOException();var d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=MAGIC)throw new IOException();long t=d.readLong();if(t<0)throw new IOException();return t;}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static byte[] encode(Notice n){try{validate(n);var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(MAGIC);d.writeLong(n.token());d.writeUTF(n.spell());d.writeUTF(n.name());d.writeUTF(n.icon());d.writeBoolean(n.first());return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static void validate(Notice n)throws IOException{if(n.token()<=0||!n.spell().matches("[a-z0-9_-]{1,64}")||n.name().length()>80||!n.icon().matches("[a-z0-9_.-]+:[a-z0-9_./-]{1,180}")||n.icon().contains(".."))throw new IOException("Invalid notice");}
    public static Notice decode(byte[] b){try{if(b.length<16||b.length>700)throw new IOException();var d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=MAGIC)throw new IOException();var n=new Notice(d.readLong(),d.readUTF(),d.readUTF(),d.readUTF(),d.readBoolean());validate(n);if(d.available()!=0)throw new IOException();return n;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
