package school.magiccodex.protocol;
import java.io.*;
import java.util.*;

/** Bounded server-owned mailbox wire format. No target UUID or client item data in requests. */
public final class MailboxProtocol {
 public static final String REQUEST="magiccodex:mailbox_request", RESPONSE="magiccodex:mailbox_response";
 public static final int MAX_BYTES=60000, OPEN=0, DETAIL=1, CLAIM=2, CLAIM_ALL=3, DELETE_CLAIMED=4, CLOSE=5;
 public record Request(int action,long sequence,long session,String mail,int page){}
 public record Entry(String id,String title,long created,int remaining,int total){}
 public record Attachment(int index,String name,int amount,boolean claimed,byte[] preview){}
 public record Response(long sequence,long session,String message,int page,boolean more,List<Entry> entries,String selected,String body,List<Attachment> attachments){}
 private interface Write {void run(DataOutputStream out)throws IOException;}
 private static byte[] pack(Write f){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeInt(0x4D424F31);f.run(o);o.flush();if(b.size()>MAX_BYTES)throw new IllegalArgumentException("mailbox size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static byte[] encode(Request r){return pack(o->{o.writeByte(r.action);o.writeLong(r.sequence);o.writeLong(r.session);o.writeUTF(r.mail);o.writeInt(r.page);});}
 public static byte[] encode(Response r){return pack(o->{o.writeLong(r.sequence);o.writeLong(r.session);o.writeUTF(r.message);o.writeInt(r.page);o.writeBoolean(r.more);o.writeInt(r.entries.size());for(var e:r.entries){o.writeUTF(e.id);o.writeUTF(e.title);o.writeLong(e.created);o.writeInt(e.remaining);o.writeInt(e.total);}o.writeUTF(r.selected);o.writeUTF(r.body);o.writeInt(r.attachments.size());for(var a:r.attachments){o.writeInt(a.index);o.writeUTF(a.name);o.writeInt(a.amount);o.writeBoolean(a.claimed);o.writeInt(a.preview.length);o.write(a.preview);}});}
 private static DataInputStream input(byte[] b)throws IOException{if(b.length<4||b.length>MAX_BYTES)throw new IOException("size");var in=new DataInputStream(new ByteArrayInputStream(b));if(in.readInt()!=0x4D424F31)throw new IOException("version");return in;}
 private static int bound(int v,int max)throws IOException{if(v<0||v>max)throw new IOException("bound");return v;}
 private static String str(DataInputStream in,int max)throws IOException{var s=in.readUTF();if(s.length()>max)throw new IOException("text");return s;}
 public static Request request(byte[] b){try{var in=input(b);var r=new Request(bound(in.readUnsignedByte(),CLOSE),in.readLong(),in.readLong(),str(in,36),bound(in.readInt(),100000));if(in.available()!=0||r.sequence<=0)throw new IOException("trailing");if(!r.mail.isEmpty())UUID.fromString(r.mail);return r;}catch(IOException|RuntimeException e){throw new IllegalArgumentException("invalid mailbox request",e);}}
 public static Response response(byte[] b){try{var in=input(b);long seq=in.readLong(),session=in.readLong();String msg=str(in,240);int page=bound(in.readInt(),100000);boolean more=in.readBoolean();var es=new ArrayList<Entry>();int n=bound(in.readInt(),20);for(int i=0;i<n;i++)es.add(new Entry(str(in,36),str(in,80),in.readLong(),bound(in.readInt(),9),bound(in.readInt(),9)));String selected=str(in,36),body=str(in,2000);var as=new ArrayList<Attachment>();n=bound(in.readInt(),9);for(int i=0;i<n;i++){int index=bound(in.readInt(),8);String name=str(in,120);int amount=bound(in.readInt(),99);boolean claimed=in.readBoolean();int size=bound(in.readInt(),3072);byte[] data=in.readNBytes(size);if(data.length!=size)throw new IOException("short preview");as.add(new Attachment(index,name,amount,claimed,data));}if(in.available()!=0)throw new IOException("trailing");return new Response(seq,session,msg,page,more,List.copyOf(es),selected,body,List.copyOf(as));}catch(IOException|RuntimeException e){throw new IllegalArgumentException("invalid mailbox response",e);}}
}
