package school.magiccodex.protocol;
import java.io.*;import java.util.*;
import school.magiccodex.protocol.HousingProtocol.Person;
/** Additive channel; old housing packets and visit permissions are unchanged. */
public final class HousingSearchProtocol {
 public static final String CHANNEL="chacademi:housing_search";public static final int PAGE_SIZE=30,MAX_BYTES=16000;
 public record Query(long generation,int page,String text){public Query{if(generation<1||page<0||page>100000||text==null||text.length()>32||text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("query");}}
 public record User(Person person,boolean online,boolean friend,int size){}
 public record Page(long generation,int page,boolean more,List<User> users,String error){public Page{users=List.copyOf(users);if(users.size()>PAGE_SIZE||error==null||error.length()>200)throw new IllegalArgumentException("page");}}
 interface Writer{void write(DataOutputStream out)throws IOException;}
 static byte[] bytes(Writer w){try{var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeByte(1);w.write(out);if(b.size()>MAX_BYTES)throw new IllegalArgumentException("size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static byte[] encode(Query q){return bytes(o->{o.writeLong(q.generation());o.writeInt(q.page());o.writeUTF(q.text());});}
 public static Query query(byte[] b){try(var in=input(b)){var q=new Query(in.readLong(),in.readInt(),in.readUTF());if(in.available()!=0)throw new IOException();return q;}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static byte[] encode(Page p){return bytes(o->{o.writeLong(p.generation());o.writeInt(p.page());o.writeBoolean(p.more());o.writeUTF(p.error());o.writeByte(p.users().size());for(var u:p.users()){var x=u.person();o.writeLong(x.id().getMostSignificantBits());o.writeLong(x.id().getLeastSignificantBits());o.writeUTF(x.name());o.writeByte(x.house());o.writeUTF(x.skin());o.writeBoolean(x.slim());o.writeBoolean(u.online());o.writeBoolean(u.friend());o.writeByte(u.size());}});}
 public static Page page(byte[] b){try(var in=input(b)){long generation=in.readLong();int page=in.readInt();boolean more=in.readBoolean();String error=in.readUTF();int n=in.readUnsignedByte();if(n>PAGE_SIZE)throw new IOException();var users=new ArrayList<User>();var seen=new HashSet<UUID>();for(int i=0;i<n;i++){var id=new UUID(in.readLong(),in.readLong());if(!seen.add(id))throw new IOException();var person=new Person(id,in.readUTF(),in.readByte(),in.readUTF(),in.readBoolean());users.add(new User(person,in.readBoolean(),in.readBoolean(),in.readUnsignedByte()));}if(in.available()!=0)throw new IOException();return new Page(generation,page,more,users,error);}catch(IOException e){throw new IllegalArgumentException(e);}}
 static DataInputStream input(byte[] b)throws IOException{if(b.length>MAX_BYTES||b.length<1)throw new IOException();var in=new DataInputStream(new ByteArrayInputStream(b));if(in.readUnsignedByte()!=1)throw new IOException();return in;}
 private HousingSearchProtocol(){}
}
