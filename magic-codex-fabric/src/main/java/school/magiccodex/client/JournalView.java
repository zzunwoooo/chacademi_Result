package school.magiccodex.client;
import java.util.*;import school.magiccodex.protocol.JournalProtocol.Entry;
public final class JournalView {
 public record Page(int index,int total,List<Entry> entries){}
 public static Page page(Collection<Entry> rows,boolean completed,boolean main,int requested){var unique=new TreeMap<String,Entry>();for(var e:rows)if(e.main()==main&&(completed?e.state().equals("claimed"):Set.of("active","paying").contains(e.state())))unique.put(e.key(),e);var all=new ArrayList<>(unique.values());int p=Math.clamp(requested,0,Math.max(0,(all.size()-1)/6));return new Page(p,all.size(),List.copyOf(all.subList(Math.min(p*6,all.size()),Math.min(p*6+6,all.size()))));}
}
