package school.magiccodex.paper;
import java.util.*;import org.bukkit.*;import org.bukkit.command.CommandSender;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.PlayerQuitEvent;import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.JournalProtocol;import school.magiccodex.protocol.JournalProtocol.*;
/** Display records only; each owner retains its existing ledger and reward transaction boundaries. */
final class JournalBridge implements PluginMessageListener,Listener {
 private final MagicCodexBridge plugin;
 private record Pending(UUID target,CommandSender sender,String operation,String id,long until){}
 private final Map<String,Pending> pending=new HashMap<>();private final Map<UUID,List<Entry>> storyCatalog=new HashMap<>();private final Map<UUID,Long> limits=new HashMap<>();
 private static final class Catalog{String token="";int part,total;long start;final List<Entry> rows=new ArrayList<>();}
 private final Map<UUID,Catalog> catalogs=new HashMap<>();private final Set<UUID> loading=new HashSet<>();
 JournalBridge(MagicCodexBridge p){plugin=p;Bukkit.getMessenger().registerIncomingPluginChannel(p,JournalProtocol.QUERY,this);Bukkit.getMessenger().registerOutgoingPluginChannel(p,JournalProtocol.REPLY);Bukkit.getPluginManager().registerEvents(this,p);
  Bukkit.getScheduler().runTaskTimer(p,()->{long now=System.currentTimeMillis();pending.entrySet().removeIf(e->{if(e.getValue().until<now){plugin.getLogger().warning("STORY JOURNAL client ack timeout player="+e.getValue().target+" id="+e.getValue().id);CmdKit.userFail(e.getValue().sender,"대상 클라이언트가 응답하지 않아 기록 변경을 확인하지 못했어요");return true;}return false;});catalogs.entrySet().removeIf(e->now-e.getValue().start>10000);},100,100);
 }
 @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();adminRows.remove(id);adminRefresh.remove(id);adminLoading.remove(id);storyCatalog.remove(id);catalogs.remove(id);limits.remove(id);loading.remove(id);pending.entrySet().removeIf(x->x.getValue().target.equals(id));}
 static void send(MagicCodexBridge plugin,Player p,Message m){if(p.isOnline()&&p.getListeningPluginChannels().contains(JournalProtocol.REPLY))p.sendPluginMessage(plugin,JournalProtocol.REPLY,JournalProtocol.encode(m));}
 static void notify(MagicCodexBridge plugin,Player p,String title,String description){send(plugin,p,new Message(4,"","quest","grant:"+UUID.randomUUID(),JournalProtocol.cut(title,140),0,1,List.of(new Entry("quest","notice","",JournalProtocol.cut(description,500),"active",true,"",""))));}
 public void onPluginMessageReceived(String channel,Player player,byte[] bytes){if(!channel.equals(JournalProtocol.QUERY))return;try{
  Message m=JournalProtocol.decode(bytes);UUID id=player.getUniqueId();long now=System.currentTimeMillis();
  if(m.kind()==0){if(loading.contains(id)||now<limits.getOrDefault(id,0L)||!player.hasPermission("magiccodex.quests"))return;limits.put(id,now+1000);loading.add(id);
   plugin.questBridge().journalRows(player,quests->plugin.dialogueBridge().journalRows(player,dialogues->{loading.remove(id);if(!player.isOnline())return;var all=new ArrayList<Entry>(quests);all.addAll(dialogues);all.sort(Comparator.comparing(Entry::key));all.addAll(storyRows(player));var rows=all.stream().limit(JournalProtocol.MAX).toList();for(int i=0;i<Math.max(1,rows.size());i+=JournalProtocol.CHUNK)send(plugin,player,new Message(1,m.token(),"","","",i/JournalProtocol.CHUNK,rows.size(),rows.subList(Math.min(i,rows.size()),Math.min(i+JournalProtocol.CHUNK,rows.size()))));}));
  }else if(m.kind()==3){var request=pending.get(m.token());if(request==null||!request.target.equals(id)||request.until<now||!request.id.equals(m.id())||!request.operation.equals(m.operation()))return;pending.remove(m.token());if(m.text().startsWith("ERROR")){plugin.getLogger().warning("STORY JOURNAL client refused: "+m.text());CmdKit.userFail(request.sender,"퀘스트 기록을 바꾸지 못했어요. 대상의 대사 파일과 진행 상태를 확인해 주세요");}else CmdKit.ok(request.sender,"퀘스트 기록을 바꿨어요 (보상·호감도 수령 기록은 그대로)");plugin.getLogger().info("STORY JOURNAL admin="+request.sender.getName()+" player="+id+" id="+m.id()+" operation="+m.operation()+" clientAck=true");
  }else if(m.kind()==5){if(true)return;/* 2026-10-25: 메인 스토리는 서버 단계 기록(storyRows)만 쓴다 — 런처 기록은 무시 */var c=catalogs.computeIfAbsent(id,x->new Catalog());if(m.part()==0){if(now-c.start<1000)return;c.token=m.token();c.part=0;c.total=m.total();c.rows.clear();c.start=now;}
   if(!c.token.equals(m.token())||c.part!=m.part()||c.total!=m.total()||c.rows.size()+m.entries().size()>JournalProtocol.MAX||m.entries().stream().anyMatch(e->!e.source().equals("story")))return;
   for(var e:m.entries())c.rows.add(new Entry("story",e.id(),e.title(),"",e.state(),e.main(),"",""));c.part++;if(c.rows.size()==c.total)storyCatalog.put(id,List.copyOf(c.rows));
  }
 }catch(IllegalArgumentException ignored){}}
 /** 메인 스토리 = ChacademyStory 서버 단계 기록 (끝낸 단계 claimed · 지금 단계 active), 단계 순서 그대로. 장 이름은 group 에 담는다. */
 @SuppressWarnings("unchecked")
 List<Entry> storyRows(Player p){
  var story=Bukkit.getPluginManager().getPlugin("ChacademyStory");if(story==null||!story.isEnabled())return List.of();
  try{var raw=(List<Map<String,String>>)story.getClass().getMethod("journalRows",UUID.class).invoke(story,p.getUniqueId());var out=new ArrayList<Entry>();
   for(var m:raw){try{out.add(new Entry("story",m.get("id"),JournalProtocol.cut(m.getOrDefault("title",""),140),JournalProtocol.cut(m.getOrDefault("description",""),2000),m.getOrDefault("state","active"),true,JournalProtocol.cut(m.getOrDefault("details",""),1200),"",JournalProtocol.cut(m.getOrDefault("chapter",""),40)));}catch(IllegalArgumentException ignored){}}
   return out;
  }catch(ReflectiveOperationException|ClassCastException|LinkageError e){return List.of();}
 }
 Map<String,String> catalog(Player target){var rows=new TreeMap<String,String>();rows.putAll(plugin.questBridge().journalCatalog());rows.putAll(plugin.dialogueBridge().journalCatalog());for(var e:storyRows(target))rows.put(e.key(),e.title());return rows;}
 private final Map<UUID,List<Entry>> adminRows=new HashMap<>();private final Map<UUID,Long> adminRefresh=new HashMap<>();private final Set<UUID> adminLoading=new HashSet<>();
 private void readAdminRows(Player target,java.util.function.Consumer<List<Entry>> callback){
  plugin.questBridge().journalRows(target,quests->plugin.dialogueBridge().journalRows(target,dialogues->{
   var map=new TreeMap<String,Entry>();for(var e:plugin.questBridge().journalDefinitions())map.put(e.key(),e);
   plugin.dialogueBridge().journalCatalog().forEach((key,title)->{String id=key.substring(key.indexOf(':')+1);map.put(key,new Entry("dialogue",id,title,"","available",true,"",""));});
   for(var e:quests)map.put(e.key(),e);for(var e:dialogues)map.put(e.key(),e);for(var e:storyRows(target))map.put(e.key(),e);
   var result=List.copyOf(map.values());adminRows.put(target.getUniqueId(),result);callback.accept(result);
  }));
 }
 List<String> choices(Player target,String category,String operation){
  if(!Set.of("메인","일일").contains(category)||!OPERATIONS.contains(operation))return List.of();UUID id=target.getUniqueId();long now=System.currentTimeMillis();
  if(now>=adminRefresh.getOrDefault(id,0L)&&adminLoading.add(id)){adminRefresh.put(id,now+1000);readAdminRows(target,rows->adminLoading.remove(id));}
  return JournalAdminRules.choices(JournalAdminRules.candidates(adminRows.getOrDefault(id,List.of()),category.equals("메인"),operation)).stream().map(AdminCommandRules::compact).distinct().toList();
 }
 /** /유저관리 <닉네임> 퀘스트 <메인|일일> <보기|주기|진행|완료|초기화> [퀘스트]. 빈 퀘스트 + 보기 = 목록. */
 void categoryCommand(CommandSender sender,Player target,String category,String operation,String quest){
  if(!Set.of("메인","일일").contains(category)||!OPERATIONS.contains(operation))throw new IllegalArgumentException("operation");
  String who=plugin.names().name(target);
  readAdminRows(target,rows->{try{
   if(!target.isOnline()){CmdKit.userFail(sender,who+" 님이 접속 중이 아니에요");return;}
   var candidates=JournalAdminRules.candidates(rows,category.equals("메인"),operation);
   if(quest.isEmpty()){var out=new ArrayList<String>();out.add("── "+who+" · "+category+" 퀘스트 ──");if(candidates.isEmpty())out.add("(기록 없음)");else candidates.forEach(e->out.add(JournalAdminRules.name(e)+" / "+JournalAdminRules.state(e.state())));CmdKit.lines(sender,out);return;}
   var e=JournalAdminRules.resolve(candidates,quest);
   if(e==null){CmdKit.userFail(sender,"이 상태에서 "+operation+"할 수 있는 퀘스트가 아니거나 이름이 겹쳐요: "+quest);return;}
   if(operation.equals("보기")){CmdKit.lines(sender,List.of(JournalAdminRules.name(e)+" / "+JournalAdminRules.state(e.state())));return;}
   command(sender,target,operation,e.key());adminRows.remove(target.getUniqueId());adminRefresh.remove(target.getUniqueId());
  }catch(IllegalArgumentException failure){CmdKit.userFail(sender,failure.getMessage()==null?"퀘스트 기록을 바꾸지 못했어요":failure.getMessage());}});
 }
 static final List<String> OPERATIONS=List.of("보기","주기","진행","완료","초기화");
 /** 기록 변경 (OP 검사는 명령 허브가 한다). key = quest:ID · dialogue:ID · story:ID. */
 void command(CommandSender sender,Player target,String operationName,String key){
  if(!OPERATIONS.contains(operationName))throw new IllegalArgumentException("보기·주기·진행·완료·초기화 중 하나여야 해요");String resolved=AdminCommandRules.resolve(catalog(target),key);if(resolved==null)throw new IllegalArgumentException("해당 퀘스트가 없거나 이름이 겹쳐요. 대상의 일지를 새로 열고 다시 해 주세요");
  String operation=switch(operationName){case "보기"->"query";case "주기"->"grant";case "진행"->"progress";case "완료"->"complete";default->"reset";};String[] ref=resolved.split(":",2);
  if(ref[0].equals("quest")){plugin.questBridge().journalEdit(sender,target,ref[1],operation);return;}if(ref[0].equals("dialogue")){plugin.dialogueBridge().journalEdit(sender,target,ref[1],operation);return;}
  if(ref[0].equals("story"))throw new IllegalArgumentException("메인 스토리는 /유저관리 <닉네임> 스토리진행 단계 <단계> 로 옮겨 주세요 (퀘스트창은 서버 단계 기록을 그대로 보여 줘요)");
  if(!target.getListeningPluginChannels().contains(JournalProtocol.REPLY))throw new IllegalArgumentException("대상의 스토리·UI 모드를 업데이트해야 해요");if(pending.size()>=64)throw new IllegalArgumentException("퀘스트 요청이 많아요. 잠시 뒤 다시 해 주세요");String token=UUID.randomUUID().toString();pending.put(token,new Pending(target.getUniqueId(),sender,operation,ref[1],System.currentTimeMillis()+15000));send(plugin,target,new Message(2,token,operation,ref[1],"",0,0,List.of()));
 }
}
