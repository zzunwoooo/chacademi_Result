package school.magiccodex.client;
import java.util.*;
import java.util.function.Function;
import school.magiccodex.protocol.JournalProtocol.Entry;
/** Independent category/chapter expansion; IDs remain internal stable selection keys. */
public final class JournalTree {
 public record Row(String key,String title,int depth,Entry entry){}
 public static List<Row> rows(Collection<Entry> entries,boolean completed,Function<String,String> chapter,Set<String> closed){
  var out=new ArrayList<Row>();
  for(boolean main:new boolean[]{true,false}){
   String group=main?"main":"daily";out.add(new Row(group,main?"메인 퀘스트":"일지",0,null));if(closed.contains(group))continue;
   // 메인: 서버가 보낸 순서(스토리 단계 순서) 그대로, 장 이름은 group(서버) → 없으면 예전 chapter. 일지: 키 순서.
   Map<String,List<Entry>> groups=main?new LinkedHashMap<>():new TreeMap<>();
   var stream=entries.stream().filter(e->e.main()==main&&(main||e.group().isBlank())&&(completed?e.state().equals("claimed"):Set.of("active","paying").contains(e.state())));
   (main?stream:stream.sorted(Comparator.comparing(Entry::key))).forEach(e->groups.computeIfAbsent(main?(e.group().isBlank()?chapter.apply(e.key()):e.group()):"",k->new ArrayList<>()).add(e));
   for(var g:groups.entrySet()){
    String key=group+":"+g.getKey();if(!g.getKey().isBlank()){out.add(new Row(key,g.getKey(),1,null));if(closed.contains(key))continue;}
    for(var e:g.getValue())out.add(new Row(e.key(),e.title(),g.getKey().isBlank()?1:2,e));
   }
  }
  // NPC 퀘스트: group 이 있는 일반 퀘스트는 "NPC <이름>" 카테고리로 따로 묶는다.
  var npc=new TreeMap<String,List<Entry>>();
  entries.stream().filter(e->!e.main()&&!e.group().isBlank()&&(completed?e.state().equals("claimed"):Set.of("active","paying").contains(e.state())))
   .sorted(Comparator.comparing(Entry::key)).forEach(e->npc.computeIfAbsent(e.group(),k->new ArrayList<>()).add(e));
  for(var g:npc.entrySet()){String key="npc:"+g.getKey();out.add(new Row(key,g.getKey(),0,null));if(closed.contains(key))continue;for(var e:g.getValue())out.add(new Row(e.key(),e.title(),1,e));}
  return List.copyOf(out);
 }
}
