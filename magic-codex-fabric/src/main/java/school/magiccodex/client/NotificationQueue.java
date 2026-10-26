package school.magiccodex.client;
import java.util.*;
/** Bounded newest-at-bottom stack; invisible time does not consume a notice. */
public final class NotificationQueue {
 public record Notice(String id,String kind,String title,String body,long duration){}
 public record Visible(Notice notice,long age,double slot,boolean announce){}
 private static final class Item{final Notice notice;long age;boolean announced;double position;Item(Notice n){notice=n;}}
 private final ArrayDeque<Item> entries=new ArrayDeque<>();private long last=-1;private boolean wasVisible;
 public void offer(Notice n){if(n.id()==null||n.id().length()>100||n.title().length()>140||n.body().length()>500||n.duration()<1000||n.duration()>30000)throw new IllegalArgumentException();if(entries.stream().anyMatch(e->e.notice.equals(n)))return;entries.removeIf(e->e.notice.id().equals(n.id()));while(entries.size()>=16)entries.removeFirst();entries.addLast(new Item(n));}
 public List<Visible> frame(long now,boolean visible,int capacity){long delta=last<0?0:Math.max(0,Math.min(30000,now-last));last=Math.max(last,now);if(!visible){wasVisible=false;return List.of();}if(!wasVisible)delta=0;wasVisible=true;int cap=Math.clamp(capacity,0,3);var all=new ArrayList<>(entries);int start=Math.max(0,all.size()-cap);var out=new ArrayList<Visible>();for(int i=all.size()-1;i>=start;i--){var item=all.get(i);item.age+=delta;if(item.age>=item.notice.duration()){entries.remove(item);continue;}item.position=out.size();boolean announce=!item.announced;item.announced=true;out.add(new Visible(item.notice,item.age,item.position,announce));}return List.copyOf(out);}
 public boolean offerIfAbsent(Notice n){if(entries.stream().anyMatch(e->e.notice.id().equals(n.id())))return false;offer(n);return true;}
 public boolean dismiss(String id){return entries.removeIf(e->e.notice.id().equals(id));} public int size(){return entries.size();}public void clear(){entries.clear();last=-1;wasVisible=false;}
}
