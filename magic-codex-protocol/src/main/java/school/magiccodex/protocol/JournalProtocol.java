package school.magiccodex.protocol;
import java.io.*;import java.util.*;
/** Display-only records; never transports commands, rewards or affinity writes. */
public final class JournalProtocol {
 public static final String QUERY="magiccodex:journal_query",REPLY="magiccodex:journal_reply";
 public static final int MAX=600,CHUNK=2,BYTES=30000;
 public record Entry(String source,String id,String title,String description,String state,boolean main,String details,String reward,String group){
  /** group: 일지 카테고리 이름(예: NPC 퀘스트의 NPC 이름). 빈 값이면 기본 분류. */
  public Entry(String source,String id,String title,String description,String state,boolean main,String details,String reward){this(source,id,title,description,state,main,details,reward,"");}
  public Entry{group=group==null?"":group;if(group.length()>40||!Set.of("quest","dialogue","story").contains(source)||!id.matches("[a-z0-9_-]{1,64}")||title.length()>140||description.length()>2000||details.length()>1200||reward.length()>500||!Set.of("active","claimed","available","hidden","paying").contains(state))throw new IllegalArgumentException("Journal entry");}
  public String key(){return source+":"+id;}
 }
 public record Message(int kind,String token,String operation,String id,String text,int part,int total,List<Entry> entries){}
 public static byte[] encode(Message m){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);validate(m);o.writeInt(2);o.writeByte(m.kind);o.writeUTF(m.token);o.writeUTF(m.operation);o.writeUTF(m.id);o.writeUTF(m.text);o.writeInt(m.part);o.writeInt(m.total);o.writeInt(m.entries.size());for(var e:m.entries){o.writeUTF(e.source);o.writeUTF(e.id);o.writeUTF(e.title);o.writeUTF(e.description);o.writeUTF(e.state);o.writeBoolean(e.main);o.writeUTF(e.details);o.writeUTF(e.reward);o.writeUTF(e.group);}if(b.size()>BYTES)throw new IllegalArgumentException("Journal packet");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static Message decode(byte[] bytes){if(bytes.length>BYTES)throw new IllegalArgumentException();try{var i=new DataInputStream(new ByteArrayInputStream(bytes));int ver=i.readInt();if(ver!=1&&ver!=2)throw new IOException();int kind=i.readUnsignedByte();String token=i.readUTF(),op=i.readUTF(),id=i.readUTF(),text=i.readUTF();int part=i.readInt(),total=i.readInt(),n=i.readInt();if(n<0||n>CHUNK)throw new IOException();var rows=new ArrayList<Entry>();for(int k=0;k<n;k++)rows.add(new Entry(i.readUTF(),i.readUTF(),i.readUTF(),i.readUTF(),i.readUTF(),i.readBoolean(),i.readUTF(),i.readUTF(),ver>=2?i.readUTF():""));var m=new Message(kind,token,op,id,text,part,total,List.copyOf(rows));validate(m);if(i.available()!=0)throw new IOException();return m;}catch(IOException e){throw new IllegalArgumentException(e);}}
 private static void validate(Message m){if(m.kind<0||m.kind>6||m.token.length()>40||m.operation.length()>20||m.id.length()>80||m.text.length()>500||m.total<0||m.total>MAX||m.part<0||m.part>MAX/CHUNK||m.entries.size()>CHUNK)throw new IllegalArgumentException("Journal packet");}
 public static String cut(String text,int n){return text==null?"":text.length()<=n?text:text.substring(0,n);}
}
