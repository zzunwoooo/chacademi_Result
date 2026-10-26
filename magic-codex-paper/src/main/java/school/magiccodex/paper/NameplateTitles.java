package school.magiccodex.paper;
import java.util.*;import school.magiccodex.protocol.NameplateProtocol;
/** Presentation-only adapter. Never grants titles or inserts NPC UUIDs into player title tables. */
final class NameplateTitles {
 static final int NPC_COLOR=0xDEC58E;
 /** Resolve old IDs only through the authoritative catalog. Unknown IDs remain retained, never rendered literally. */
 static NameplateStore.Label literal(NameplateStore.Label label,TitleBridge service){
  String a=label.prefixText(),b=label.suffixText(),ai=label.prefixId(),bi=label.suffixId();
  if(service!=null){var d=service.nameplateDefinition(ai);if(d!=null&&d.side()==0){if(a.isEmpty())a=d.name();ai="";}d=service.nameplateDefinition(bi);if(d!=null&&d.side()==1){if(b.isEmpty())b=d.name();bi="";}}
  if(a.isEmpty()&&b.isEmpty()&&ai.isEmpty()&&bi.isEmpty())b=label.legacyTitle();
  return new NameplateStore.Label(label.name(),ai,bi,label.legacyTitle(),a,b);
 }
 static List<NameplateProtocol.TitleRun> npcRuns(NameplateStore.Label label,TitleBridge service){
  var value=literal(label,service);var out=new ArrayList<NameplateProtocol.TitleRun>();
  if(!value.prefixText().isBlank())out.add(new NameplateProtocol.TitleRun(value.prefixText(),NPC_COLOR));
  if(!value.suffixText().isBlank())out.add(new NameplateProtocol.TitleRun(value.suffixText(),NPC_COLOR));return List.copyOf(out);
 }
 static List<TitleDefinition> npc(NameplateStore.Label label,TitleBridge service){
  if(label==null||service==null)return List.of();var out=new ArrayList<TitleDefinition>();
  for(var slot:List.of(Map.entry(label.prefixId(),0),Map.entry(label.suffixId(),1))){var d=service.nameplateDefinition(slot.getKey());if(d!=null&&d.side()==slot.getValue())out.add(d);}return List.copyOf(out);
 }
 static List<NameplateProtocol.TitleRun> runs(List<TitleDefinition> definitions){
  // The equipped suffix owns the complete auxiliary line color; prefix-only keeps its own color.
  var suffix=definitions.stream().filter(d->d.side()==1).findFirst();
  return definitions.stream().map(d->new NameplateProtocol.TitleRun(d.name(),suffix.map(TitleDefinition::color).orElse(d.color()))).toList();
 }
 static String plainName(String authority,List<TitleDefinition> equipped){
  String result=authority.strip();for(var d:equipped){String value=d.name();if(result.startsWith(value+" "))result=result.substring(value.length()+1).stripLeading();else if(result.endsWith(" "+value))result=result.substring(0,result.length()-value.length()-1).stripTrailing();}
  return result.isBlank()?authority:result;
 }
 static String combined(List<TitleDefinition> definitions){String a="",b="";for(var d:definitions)if(d.side()==0)a=d.name();else b=d.name();return a+b;}
}
