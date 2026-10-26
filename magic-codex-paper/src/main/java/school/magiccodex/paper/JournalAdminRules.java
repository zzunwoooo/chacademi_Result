package school.magiccodex.paper;
import java.util.*;
import school.magiccodex.protocol.JournalProtocol.Entry;
final class JournalAdminRules {
 static boolean eligible(Entry e,String operation){return switch(operation){case "완료","진행"->e.state().equals("active");case "주기"->Set.of("available","hidden").contains(e.state());case "초기화"->!Set.of("available","hidden","paying").contains(e.state());default->true;};}
 static String name(Entry e){return e.title().isBlank()?e.id():e.title();}
 static List<Entry> candidates(Collection<Entry> rows,boolean main,String operation){return rows.stream().filter(e->e.main()==main&&eligible(e,operation)).toList();}
 static Entry resolve(Collection<Entry> rows,String input){String key=input.strip(),compact=AdminCommandRules.compact(key);var found=rows.stream().filter(e->name(e).equalsIgnoreCase(key)||e.id().equalsIgnoreCase(key)).toList();if(found.isEmpty())found=rows.stream().filter(e->AdminCommandRules.compact(name(e)).equalsIgnoreCase(compact)).toList();return found.size()==1?found.getFirst():null;}
 static List<String> choices(Collection<Entry> rows){return rows.stream().map(JournalAdminRules::name).distinct().sorted().toList();}
 static String state(String state){return switch(state){case "active"->"진행 중";case "claimed"->"완료";case "paying"->"처리 중";default->"미진행";};}
}
