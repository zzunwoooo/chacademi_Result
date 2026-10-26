package school.magiccodex.protocol;
import java.io.*;import java.util.*;
/** v3: authoritative nickname readiness (empty = pending) and neutral unassigned-player panel (style 5). */
public final class NameplateProtocol {
 public static final String CHANNEL="magiccodex:nameplates";
 public static final int MAX_BYTES=30000,MAX_ENTRIES=32;
 public record TitleRun(String text,int color){public TitleRun{NameplateProtocol.text(text,48,false);if(color<0||color>0xffffff)throw new IllegalArgumentException("title color");}}
 public record Entry(int entity,UUID uuid,int style,String name,List<TitleRun> titles){public Entry{if(entity<0||uuid==null||style<0||style>5||titles==null||titles.size()>2)throw new IllegalArgumentException("entry");text(name,48,true);titles=List.copyOf(titles);}}
 public static void text(String s,int max,boolean blank){
  if(s==null||(!blank&&s.isBlank())||s.codePointCount(0,s.length())>max||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7||c=='<'||c=='>')||!validSurrogates(s))throw new IllegalArgumentException("이름은 1~48자이며 서식·제어 문자는 사용할 수 없어요");
 }
 private static boolean validSurrogates(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
 public static byte[] encode(List<Entry> entries){
  if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("count");try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(3);d.writeInt(entries.size());for(var e:entries){d.writeInt(e.entity);d.writeLong(e.uuid.getMostSignificantBits());d.writeLong(e.uuid.getLeastSignificantBits());d.writeByte(e.style);d.writeUTF(e.name);d.writeByte(e.titles.size());for(var t:e.titles){d.writeUTF(t.text);d.writeInt(t.color);}}byte[] out=b.toByteArray();if(out.length>MAX_BYTES)throw new IllegalArgumentException("size");return out;}catch(IOException ex){throw new IllegalArgumentException(ex);}
 }
 public static List<Entry> decode(byte[] bytes){
  if(bytes==null||bytes.length<8||bytes.length>MAX_BYTES)throw new IllegalArgumentException("size");try{var d=new DataInputStream(new ByteArrayInputStream(bytes));if(d.readInt()!=3)throw new IllegalArgumentException("version");int n=d.readInt();if(n<0||n>MAX_ENTRIES)throw new IllegalArgumentException("count");var out=new ArrayList<Entry>();var ids=new HashSet<Integer>();for(int i=0;i<n;i++){int entity=d.readInt();UUID uuid=new UUID(d.readLong(),d.readLong());int style=d.readUnsignedByte();String name=d.readUTF();int count=d.readUnsignedByte();if(count>2)throw new IllegalArgumentException("titles");var runs=new ArrayList<TitleRun>();for(int t=0;t<count;t++)runs.add(new TitleRun(d.readUTF(),d.readInt()));var e=new Entry(entity,uuid,style,name,runs);if(!ids.add(e.entity))throw new IllegalArgumentException("duplicate");out.add(e);}if(d.available()!=0)throw new IllegalArgumentException("trailing");return List.copyOf(out);}catch(IOException ex){throw new IllegalArgumentException(ex);}
 }
}
