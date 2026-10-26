package school.magiccodex.paper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.bukkit.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.*;

/**
 * Main-thread sessions, one bounded IO queue, cached immutable catalogs. No per-tick DB polling.
 *
 * 선택지의 외부 동작(quest/event/command/custom)은 진행 상태(story/flag)와 한 트랜잭션으로 기록한 뒤 실행한다.
 * 기록 상태와 자동/관리자 처리 규칙은 DialogueStore 주석 참고. 요약:
 *  - 유저가 나갔거나 서버가 꺼져 실행하지 못한 동작(queued)은 다음 접속(준비된 뒤) 또는 다음 대화 열기 때 이어서 실행한다.
 *  - 실패한 동작(review)·실행 여부를 알 수 없는 동작(running, 옛 pending)만 관리자 확인으로 가며, 그 대화만 막는다.
 */
final class DialogueBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-dialogue-io");t.setDaemon(true);return t;});
    private final DialogueStore store;private final Path directory;
    private Map<String,DialogueDefinition> catalog=Map.of();private Map<String,List<String>> bindings=Map.of();
    /** 대화 ID -> 저장된 revision. 카탈로그를 읽을 때 DB 값으로 함께 채운다(클릭마다 YAML 인코딩+SHA-256 을 하지 않는다). */
    private Map<String,String> revisions=Map.of();private String signature="";
    /** 실행 중인 외부 동작 묶음 (token -> 묶음). remaining 은 "아직 실행하지 않은 동작"이며 메인 스레드에서만 바뀐다. */
    private final Map<String,Run> runs=new HashMap<>();
    private static final class Run {
        /** armed: DB 기록이 running 인 것이 확인됨. IO 스레드(queued->running 성공 시)에서도 쓰므로 volatile. */
        final UUID player;final String key,token;final ArrayList<String> remaining;volatile boolean armed;boolean eventInFlight;
        Run(UUID player,String key,String token,List<String> actions,boolean armed){this.player=player;this.key=key;this.token=token;remaining=new ArrayList<>(actions);this.armed=armed;}
    }
    /** running 으로 기록을 요청했지만 아직 결과를 받지 못한 선택 (유저 -> effect_key). 종료 시 실행 전인 것이 확실하므로 queued 로 되돌린다. */
    private final Map<UUID,String> arming=new HashMap<>();
    /** 관리자가 방금 audit 로 본 토큰 (탭 완성용). */
    private final Map<String,List<String>> listed=new HashMap<>();
    private static final String REVIEW="이 대화의 이전 실행 기록은 관리자 확인이 필요합니다. 관리자에게 문의해 주세요.";
    private final Map<UUID,Session> sessions=new HashMap<>();private final Map<UUID,Long> limits=new HashMap<>();private final Set<UUID> busy=new HashSet<>();
    private final Map<String,BiConsumer<Player,String>> handlers=new HashMap<>();
    private final Map<String,BiPredicate<Player,String>> conditions=new HashMap<>();
    void registerCondition(String id,BiPredicate<Player,String> predicate){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("server thread required");DialogueDefinition.id(id);conditions.put(id,Objects.requireNonNull(predicate));}
    private volatile boolean closing;private boolean refreshing;private int pending;
    /** 외부 플러그인(ChacaNPC)이 클릭을 직접 처리하는 Citizens NPC id. 그 NPC는 아래 Citizens 처리에서 제외한다. */
    private volatile java.util.function.IntPredicate claimed=id->false;
    void claim(java.util.function.IntPredicate predicate){claimed=predicate==null?id->false:predicate;}
    boolean claimed(int id){try{return claimed.test(id);}catch(RuntimeException e){return false;}}
    private static final class Session {
        final String token=UUID.randomUUID().toString();final DialogueDefinition definition;final boolean preview;final Entity anchor;boolean journal;
        /** 코드가 만든 선택 창 (수업 고르기 등, openMenu). 다음 장면이 없는 선택지를 고르면 부른다. */
        java.util.function.BiFunction<Player,String,String> pick;
        /** 이 세션을 연 시점의 공개본 revision (미리보기·기록 보기는 빈 값). */
        final String revision;
        DialogueStore.State state;String node;int sequence;long expires=System.currentTimeMillis()+300000;
        Session(DialogueDefinition d,DialogueStore.State s,boolean preview,Entity anchor,String revision){definition=d;state=s;this.preview=preview;this.anchor=anchor;this.revision=revision;node=d.start();}
    }
    DialogueBridge(MagicCodexBridge plugin)throws Exception{
        this.plugin=plugin;directory=plugin.getDataFolder().toPath().resolve("dialogues");Files.createDirectories(directory);
        if(!Files.exists(directory.resolve("elena.yml")))plugin.saveResource("dialogues/elena.yml",false);
        if(!Files.exists(directory.resolve("arden.yml")))plugin.saveResource("dialogues/arden.yml",false);
        var settings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());
        try{store=io.submit(()->new DialogueStore(settings,plugin.getDataFolder().toPath().resolve("dialogue.db"))).get(15,TimeUnit.SECONDS);
            var initial=io.submit(()->{var map=store.catalog();if(map.isEmpty()){for(String id:List.of("elena","arden"))store.edit(id,"",read(id));}return store.snapshot(null);}).get(15,TimeUnit.SECONDS);install(initial);
        }catch(Exception e){io.shutdownNow();throw e;}
        for(String channel:List.of(DialogueProtocol.REQUEST,DialogueAdminProtocol.REQUEST))Bukkit.getMessenger().registerIncomingPluginChannel(plugin,channel,this);
        for(String channel:List.of(DialogueProtocol.RESPONSE))Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,channel);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        registerCommands();
        citizens();
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            sessions.entrySet().removeIf(e->System.currentTimeMillis()>e.getValue().expires);
            // id=revision 목록만 비교해서, 바뀐 때에만 정의 전체를 다시 읽고 푼다.
            if(!refreshing&&!closing){refreshing=true;String known=signature;work(()->store.snapshot(known),v->{refreshing=false;if(v!=null)install(v);},m->refreshing=false);}
        },200,200);
        for(Player p:Bukkit.getOnlinePlayers())resumeLater(p,0);
    }
    void register(String id,BiConsumer<Player,String> handler){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("server thread required");DialogueDefinition.id(id);handlers.put(id,Objects.requireNonNull(handler));}
    private void install(DialogueStore.Snapshot snapshot){var definitions=snapshot.definitions();catalog=definitions;revisions=snapshot.revisions();signature=snapshot.signature();var index=new HashMap<String,List<String>>();definitions.values().stream().sorted(Comparator.comparing(DialogueDefinition::id)).filter(DialogueDefinition::enabled).forEach(d->d.npcs().forEach(n->index.computeIfAbsent(n,k->new ArrayList<>()).add(d.id())));bindings=Map.copyOf(index);}
    private DialogueDefinition read(String id)throws Exception{DialogueDefinition.id(id);return DialogueDefinition.decode(id,Files.readString(directory.resolve(id+".yml")));}
    private void export(DialogueDefinition d)throws Exception{Path file=directory.resolve(d.id()+".yml"),tmp=directory.resolve(d.id()+".yml.tmp");if(Files.exists(file))Files.copy(file,directory.resolve(d.id()+".yml.bak"),StandardCopyOption.REPLACE_EXISTING);Files.writeString(tmp,d.encode());try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}
    @SuppressWarnings("unchecked") private void citizens(){var dep=Bukkit.getPluginManager().getPlugin("Citizens");if(dep==null)return;try{Class<? extends Event> type=(Class<? extends Event>)Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent",true,dep.getClass().getClassLoader());Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(l,event)->{try{Player p=(Player)event.getClass().getMethod("getClicker").invoke(event);Object npc=event.getClass().getMethod("getNPC").invoke(event);Entity e=(Entity)npc.getClass().getMethod("getEntity").invoke(npc);int nid=((Number)npc.getClass().getMethod("getId").invoke(npc)).intValue();if(claimed(nid))return;String id="citizens:"+nid;bound(p,id,e);}catch(Exception ex){plugin.getLogger().warning("Dialogue NPC hook: "+ex.getClass().getSimpleName());}},plugin,true);}catch(Exception e){plugin.getLogger().warning("Dialogue Citizens hook unavailable: "+e.getClass().getSimpleName());}}
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEntityEvent e){if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||e.getRightClicked().hasMetadata("NPC"))return;for(String tag:e.getRightClicked().getScoreboardTags())if(bindings.containsKey("tag:"+tag)){bound(e.getPlayer(),"tag:"+tag,e.getRightClicked());break;}}
    private void bound(Player p,String key,Entity anchor){if(!near(p,anchor))return;for(String id:bindings.getOrDefault(key,List.of())){var d=catalog.get(id);if(d!=null&&(d.permission().isEmpty()||p.hasPermission(d.permission()))){open(p,d,false,anchor);return;}}}
    @EventHandler public void quit(PlayerQuitEvent e){listed.remove(e.getPlayer().getName());sessions.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());/* busy remains until queued work finishes */}
    private boolean ready(Player p){return p.isOnline()&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&plugin.playerStateReady(p);}
    private boolean current(Player p){return p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p;}
    private boolean near(Player p,Entity e){return e==null||(e.isValid()&&p.getWorld().equals(e.getWorld())&&p.getLocation().distanceSquared(e.getLocation())<=64);}
    private boolean gate(Player p){UUID id=p.getUniqueId();long now=System.currentTimeMillis();if(closing||busy.contains(id)||now<limits.getOrDefault(id,0L))return false;limits.put(id,now+150);return true;}
    private void open(Player p,DialogueDefinition d,boolean preview,Entity anchor){
        if(!p.hasPermission("magiccodex.dialogue")||!ready(p)||!gate(p))return;
        if(!p.getListeningPluginChannels().contains(DialogueProtocol.RESPONSE)){CodexCommands.warn(p,"dialogue","대화를 열려면 최신 MagicCodex 모드가 필요해요");return;}
        if(!preview&&(!d.enabled()||(!d.permission().isEmpty()&&!p.hasPermission(d.permission()))))return;
        UUID id=p.getUniqueId();busy.add(id);sessions.remove(id);String revision=preview?"":revisions.getOrDefault(d.id(),"");
        work(id,()->new Opening(store.state(id),preview?List.<DialogueStore.Effect>of():store.effects(id)),o->{
            var blocked=new HashMap<String,String>();
            // 이전에 끝내지 못한 동작을 먼저 이어서 실행한다. 그래도 남은 것이 이 대화의 것이면 이 대화만 막는다.
            resume(p,o.effects(),0,blocked,()->{
                busy.remove(id);if(!current(p)||!ready(p)||!near(p,anchor))return;
                if(blocked.containsKey(d.id())){CodexCommands.warn(p,"dialogue",blocked.get(d.id()));return;}
                var s=new Session(d,o.state(),preview,anchor,revision);if(!allowed(p,s,d.nodes().get(s.node).conditions())){CodexCommands.warn(p,"dialogue","아직 이 대화를 시작할 수 없어요");return;}sessions.put(id,s);send(p,s,"");
            });
        },m->{busy.remove(id);if(current(p))CodexCommands.warn(p,"dialogue",m);});
    }
    private record Opening(DialogueStore.State state,List<DialogueStore.Effect> effects){}
    /**
     * 외부 진입점(ChacaNPC)용: 이 Citizens NPC에 묶인 고정 대화 중 지금 열 수 있는 첫 대화를 연다.
     * 권한·공개 여부·시작 노드 조건·진행 상태 검사는 open()과 같다. 처리 중(busy)이면 NONE이 아니라 BUSY.
     */
    java.util.concurrent.CompletableFuture<String> openBoundStory(Player p,int npcId,Entity anchor){
        var shop=Bukkit.getServicesManager().load(ShopBridge.class);
        if(shop!=null){String status=shop.openBound(p,npcId,anchor);if(!status.equals("NONE"))return java.util.concurrent.CompletableFuture.completedFuture(status);}
        var f=new java.util.concurrent.CompletableFuture<String>();
        var candidates=bindings.getOrDefault("citizens:"+npcId,List.of()).stream().map(catalog::get)
                .filter(d->d!=null&&d.enabled()&&(d.permission().isEmpty()||p.hasPermission(d.permission()))).toList();
        if(candidates.isEmpty()||!p.hasPermission("magiccodex.dialogue")){f.complete("NONE");return f;}
        UUID id=p.getUniqueId();
        if(closing||!ready(p)||busy.contains(id)||!near(p,anchor)){f.complete("BUSY");return f;}
        if(!p.getListeningPluginChannels().contains(DialogueProtocol.RESPONSE)){CodexCommands.warn(p,"dialogue","대화를 열려면 최신 MagicCodex 모드가 필요해요");f.complete("BLOCKED");return f;}
        if(!gate(p)){f.complete("BUSY");return f;}
        busy.add(id);sessions.remove(id);var known=revisions;
        work(id,()->new Opening(store.state(id),store.effects(id)),o->{
            var blocked=new HashMap<String,String>();
            resume(p,o.effects(),0,blocked,()->{
                busy.remove(id);
                if(!current(p)||!ready(p)||!near(p,anchor)){f.complete("BUSY");return;}
                String held=null;
                for(var d:candidates){
                    if(blocked.containsKey(d.id())){if(held==null)held=blocked.get(d.id());continue;}
                    var s=new Session(d,o.state(),false,anchor,known.getOrDefault(d.id(),""));if(allowed(p,s,d.nodes().get(s.node).conditions())){sessions.put(id,s);send(p,s,"");f.complete("OPENED");return;}
                }
                if(held!=null){CodexCommands.warn(p,"dialogue",held);f.complete("BLOCKED");}else f.complete("NONE");
            });
        },m->{busy.remove(id);if(current(p))CodexCommands.warn(p,"dialogue",m);f.complete("BLOCKED");});
        return f;
    }
    /** 메인 퀘스트 진행 요약 (story.* 상태 값). */
    java.util.concurrent.CompletableFuture<String> storySummary(UUID uid){
        var f=new java.util.concurrent.CompletableFuture<String>();
        work(()->store.state(uid),state->{
            var parts=new ArrayList<String>();
            state.values().entrySet().stream().filter(e->e.getKey().startsWith("story.")).sorted(Map.Entry.comparingByKey()).forEach(e->{
                String sid=e.getKey().substring(6);
                var d=catalog.values().stream().filter(v->v.storyId().equals(sid)).sorted(Comparator.comparing(DialogueDefinition::id)).findFirst().orElse(null);
                parts.add((d==null?sid:d.storyTitle())+": "+(d!=null&&d.stages().containsKey(e.getValue())?d.stages().get(e.getValue()):e.getValue()));
            });
            f.complete(String.join(", ",parts));
        },m->f.complete(""));
        return f;
    }
    private boolean allowed(Player p,Session s,List<String> conditions){return s.preview||DialogueDefinition.matches(conditions,s.state.values(),p::hasPermission,(key,arg)->{if(key.startsWith("quest:"))return plugin.dialogueQuestCondition(p,key.substring(6),arg);var handler=this.conditions.get(key.substring(7));try{return handler!=null&&handler.test(p,arg);}catch(Exception e){return false;}});}
    private void send(Player p,Session s,String message){if(!current(p))return;var n=s.definition.nodes().get(s.node);var cs=n.choices().stream().filter(c->allowed(p,s,c.conditions())).map(c->new DialogueProtocol.Choice(c.id(),c.text())).toList();p.sendPluginMessage(plugin,DialogueProtocol.RESPONSE,DialogueProtocol.encode(new DialogueProtocol.Response(s.token,s.sequence,false,s.preview,(s.definition.storyId().isEmpty()?"":"[메인 퀘스트] ")+s.definition.title(),n.speaker(),n.portrait(),n.text(),cs,message)));}
    private void end(Player p,Session s,String message){sessions.remove(p.getUniqueId(),s);if(current(p))p.sendPluginMessage(plugin,DialogueProtocol.RESPONSE,DialogueProtocol.encode(new DialogueProtocol.Response(s.token,s.sequence,true,s.preview,"","","","",List.of(),message)));}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(channel.equals(DialogueAdminProtocol.REQUEST))return; // 옛 대화 편집기 요청: 없앤 기능이라 무시 (/대화관리 파일반영·파일로내보내기)
        if(!channel.equals(DialogueProtocol.REQUEST)||!p.hasPermission("magiccodex.dialogue"))return;
        try{var r=DialogueProtocol.request(bytes);var s=sessions.get(p.getUniqueId());if(s==null||!s.token.equals(r.session())||s.sequence!=r.sequence())return;
            if(r.close()){sessions.remove(p.getUniqueId(),s);return;}
            if(!gate(p))return;
            if(!ready(p)||!near(p,s.anchor)||System.currentTimeMillis()>s.expires){end(p,s,"대화를 다시 시작해 주세요.");return;}
            if(s.preview&&!p.hasPermission("magiccodex.dialogue.admin")){end(p,s,"");return;}
            if(!s.preview&&!s.journal){var published=catalog.get(s.definition.id());if(published==null||!published.enabled()||!revisions.getOrDefault(published.id(),"").equals(s.revision)||(!published.permission().isEmpty()&&!p.hasPermission(published.permission()))){end(p,s,"대화가 수정되었습니다. 다시 열어 주세요.");return;}}
            var n=s.definition.nodes().get(s.node);var choice=n.choices().stream().filter(c->c.id().equals(r.choice())).findFirst().orElse(null);
            if(choice==null||!allowed(p,s,n.conditions())||!allowed(p,s,choice.conditions())){send(p,s,"선택 조건을 확인해 주세요.");return;}
            if(s.pick!=null&&choice.next().isEmpty()){String m;try{m=s.pick.apply(p,choice.id());}catch(RuntimeException e){plugin.getLogger().warning("선택 창 처리 오류: "+e);m="";}if(m==null||m.isEmpty())end(p,s,"");else send(p,s,m);return;}
            if(s.preview||s.journal){advance(p,s,choice);return;}
            for(String a:choice.actions())if(a.startsWith("custom ")&&!handlers.containsKey(a.split(" ",3)[1])){send(p,s,"이 대화의 동작을 관리자에게 확인해 주세요.");return;}
            // (a) 받을 수 없는 의뢰가 들어 있으면 진행 상태를 바꾸기 전에 멈추고 이유를 알려 준다.
            for(String a:choice.actions())if(a.startsWith("quest ")){String why=questRefusal(p,a.split(" ",3)[1]);if(!why.isEmpty()){send(p,s,why);return;}}
            UUID uid=p.getUniqueId();busy.add(uid);String key=s.definition.id()+":"+s.node+":"+choice.id();
            String first=choice.actions().stream().filter(a->!a.startsWith("flag ")&&!a.startsWith("story ")).findFirst().orElse("");boolean arm=!first.isEmpty()&&!first.startsWith("quest ");
            if(arm)arming.put(uid,key);
            work(uid,()->store.transition(uid,s.state,key,choice.actions(),arm),tx->{
                arming.remove(uid);s.state=tx.state();
                if(tx.receipt().isEmpty()){busy.remove(uid);if(current(p)&&sessions.get(uid)==s)advance(p,s,choice);return;}
                var run=new Run(uid,key,tx.receipt(),tx.external(),arm);runs.put(run.token,run);
                step(p,run,m->{busy.remove(uid);if(!current(p))return;if(!m.isEmpty())end(p,s,m);else if(sessions.get(uid)==s)advance(p,s,choice);});
            },m->{arming.remove(uid);busy.remove(uid);end(p,s,m);});
        }catch(IllegalArgumentException ignored){}
    }
    private String questRefusal(Player p,String quest){var q=plugin.questBridge();return q==null?"의뢰 기능을 사용할 수 없습니다.":q.acceptRefusal(p,quest);}
    private void record(Run run,String note){plugin.getLogger().warning("DIALOGUE EFFECT "+note+" player="+run.player+" key="+run.key+" token="+run.token+" remaining="+run.remaining);}
    /**
     * 남은 동작을 순서대로 실행한다. finished 에는 전부 끝났으면 빈 문자열, 아니면 유저에게 보여 줄 안내가 온다.
     * quest 는 멱등이라 기록 상태와 무관하게 실행하고, event/command/custom 은 running 이 기록된 뒤에만 실행한다.
     */
    private void step(Player p,Run run,Consumer<String> finished){
        UUID uid=run.player;
        if(run.remaining.isEmpty()){runs.remove(run.token);must(uid,()->store.move(uid,run.token,List.of("queued","running"),"done",List.of()),ok->finished.accept(""),m->finished.accept(""));return;}
        if(!current(p)||!ready(p)){park(run,"queued",()->finished.accept("접속 상태가 바뀌어 남은 처리는 다음에 이어서 합니다."));return;}
        String action=run.remaining.get(0);String[] a=action.split(" ",3);
        if(a[0].equals("quest")){plugin.acceptDialogueQuest(p,a[1],ok->guard(uid,()->{
            if(ok){run.remaining.remove(0);step(p,run,finished);return;}
            String why=current(p)?questRefusal(p,a[1]):"";park(run,"queued",()->finished.accept((why.isEmpty()?"의뢰를 지금 받을 수 없습니다.":why)+" 조건이 풀리면 다시 말을 걸어 주세요."));}));return;}
        if(!run.armed){
            // 이 서버에 없는 custom 처리기가 남아 있으면 실행을 시작하지 않는다(기록은 queued 그대로).
            for(String next:run.remaining)if(next.startsWith("custom ")&&!handlers.containsKey(next.split(" ",3)[1])){runs.remove(run.token);finished.accept("이 대화의 남은 처리는 이 서버에서 할 수 없습니다. 원래 서버에서 다시 말을 걸어 주세요.");return;}
            var snapshot=List.copyOf(run.remaining);
            must(uid,()->{boolean won=store.move(uid,run.token,List.of("queued"),"running",snapshot);if(won)run.armed=true;return won;},ok->{
                if(!ok){runs.remove(run.token);finished.accept("대화 실행 기록이 다른 곳에서 바뀌었습니다. 대화를 다시 열어 주세요.");return;}
                step(p,run,finished);
            },m->park(run,"queued",()->finished.accept(m)));
            return;
        }
        if(a[0].equals("event")){run.eventInFlight=true;plugin.dialogueQuestEvent(p,a[1],ok->guard(uid,()->{
            run.eventInFlight=false;
            // false 는 의뢰 저장소가 기록을 거절/롤백한 경우다: 반영되지 않았으므로 다음에 다시 시도한다.
            if(!ok){park(run,"queued",()->finished.accept("이벤트 기록이 지연되고 있습니다. 잠시 뒤 다시 말을 걸어 주세요."));return;}
            run.remaining.remove(0);checkpoint(p,run,finished);}));return;}
        // command/custom 은 같은 틱에 이어서 실행한다. 실패하면 그 동작부터 남겨 관리자 확인으로 보낸다.
        while(!run.remaining.isEmpty()){
            String next=run.remaining.get(0);String[] b=next.split(" ",3);if(!b[0].equals("command")&&!b[0].equals("custom"))break;
            try{
                if(b[0].equals("command")){if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),next.substring(8).replace("{player}",p.getName()).replace("{uuid}",p.getUniqueId().toString())))throw new IllegalStateException("등록되지 않은 명령어");}
                else{var handler=handlers.get(b[1]);if(handler==null)throw new IllegalStateException("등록되지 않은 처리기");handler.accept(p,b[2]);}
            }catch(Exception e){
                record(run,"FAILED ("+e.getClass().getSimpleName()+": "+e.getMessage()+") action=["+next+"]");
                park(run,"review",()->finished.accept("대화 동작을 실행하지 못했습니다. 관리자에게 문의해 주세요."));return;
            }
            run.remaining.remove(0);
        }
        try{p.saveData();}catch(RuntimeException e){plugin.getLogger().warning("Dialogue saveData: "+e.getClass().getSimpleName());}
        checkpoint(p,run,finished);
    }
    /** 실행한 만큼을 기록한다. 이 기록이 실패하면 running 으로 남아(실행은 했음) 관리자 확인 대상이 된다. */
    private void checkpoint(Player p,Run run,Consumer<String> finished){
        UUID uid=run.player;boolean done=run.remaining.isEmpty();var snapshot=List.copyOf(run.remaining);if(done)runs.remove(run.token);
        must(uid,()->store.move(uid,run.token,List.of("running"),done?"done":"running",snapshot),ok->{
            if(done){finished.accept("");return;}
            if(!ok){runs.remove(run.token);record(run,"CHECKPOINT LOST");finished.accept(REVIEW);return;}
            step(p,run,finished);
        },m->{runs.remove(run.token);record(run,"CHECKPOINT FAILED (실행은 했으나 기록 실패)");finished.accept(done?"":REVIEW);});
    }
    /**
     * 실행을 멈추고 남은 동작과 상태를 기록한다. queued: 다음에 자동으로 이어서 실행. review: 관리자 확인.
     * 이 묶음이 알고 있는 상태(armed 면 running, 아니면 queued)에서만 바꾼다: 유저가 서버를 옮겨 다른 서버가 이미 이어서 실행 중(running)인 기록을
     * 이 서버의 늦은 응답이 queued 로 되돌려 같은 명령어가 두 번 실행되는 일을 막는다.
     */
    private void park(Run run,String status,Runnable then){
        runs.remove(run.token);var snapshot=List.copyOf(run.remaining);UUID uid=run.player;
        must(uid,()->store.move(uid,run.token,List.of(run.armed?"running":"queued"),status,snapshot),ok->then.run(),m->{record(run,"PARK FAILED status="+status);then.run();});
    }
    /** 끝내지 못한 동작(queued)을 차례로 이어서 실행한다. 끝내지 못했거나 관리자 확인이 필요한 대화는 blocked(대화 ID -> 안내)에 담는다. */
    private void resume(Player p,List<DialogueStore.Effect> effects,int index,Map<String,String> blocked,Runnable then){
        if(index>=effects.size()){then.run();return;}
        var e=effects.get(index);String scope=e.dialogue();Runnable next=()->resume(p,effects,index+1,blocked,then);
        if(!e.status().equals("queued")||runs.containsKey(e.token())){blocked.putIfAbsent(scope,REVIEW);next.run();return;}
        if(blocked.containsKey(scope)||!current(p)||!ready(p)){blocked.putIfAbsent(scope,"이 대화의 이전 처리가 아직 끝나지 않았습니다. 잠시 뒤 다시 말을 걸어 주세요.");next.run();return;}
        var run=new Run(p.getUniqueId(),e.key(),e.token(),e.actions(),false);runs.put(run.token,run);
        step(p,run,m->{if(!m.isEmpty())blocked.putIfAbsent(scope,m);next.run();});
    }
    /** 접속 뒤 준비가 끝나면(캐릭터 정보 로드) 끝내지 못한 동작을 조용히 이어서 실행한다. */
    private void resumeLater(Player p,int attempt){
        if(closing)return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(closing||!current(p))return;UUID id=p.getUniqueId();
            if(!ready(p)||busy.contains(id)||sessions.containsKey(id)){if(attempt<8)resumeLater(p,attempt+1);return;}
            busy.add(id);work(id,()->store.effects(id),list->resume(p,list,0,new HashMap<>(),()->busy.remove(id)),m->busy.remove(id));
        },100L);
    }
    @EventHandler public void join(PlayerJoinEvent e){resumeLater(e.getPlayer(),0);}
    private void advance(Player p,Session s,DialogueDefinition.Choice c){if(c.next().isEmpty()){end(p,s,"");return;}s.node=c.next();s.sequence++;s.expires=System.currentTimeMillis()+300000;if(!allowed(p,s,s.definition.nodes().get(s.node).conditions())){end(p,s,"");return;}send(p,s,"");}
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    private Collection<String> dialogueIds(){return new TreeSet<>(catalog.keySet());}
    private String dialogueArg(CommandContext<CommandSourceStack> ctx)throws CommandSyntaxException{
        String id=ctx.getArgument("대화",String.class);
        try{DialogueDefinition.id(id);}catch(IllegalArgumentException e){throw ChacaApi.error("대화 ID는 영문 소문자·숫자·_·- 로 입력해 주세요");}
        return id;
    }
    private Consumer<String> failTo(CommandSender sender){return m->ChacaApi.fail(sender,m==null||m.isBlank()?"명령어 사용에 실패했습니다":m,null);}
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"대화"))return;
        ChacaApi.reload(plugin,"대화","MagicCodex 공용 대화 목록을 DB에서 다시 읽기 (파일 반영은 /대화관리 파일반영)",this::refresh);
        // /메인퀘스트 (누구나)
        ChacaApi.playerCommand(plugin,Commands.literal("메인퀘스트").executes(ctx->{journal(CodexCommands.self(ctx));return Command.SINGLE_SUCCESS;}),"메인 퀘스트 기록 열기",List.of());
        // /대화관리 파일반영 <대화> · 파일로내보내기 <대화> · 열기 <닉네임> <대화>
        ChacaApi.branch(plugin,ChacaApi.대화관리,Commands.literal("파일반영").then(Commands.argument("대화",ChacaApi.word(this::fileIds)).executes(ctx->{
            String id=dialogueArg(ctx);var sender=CodexCommands.sender(ctx);String expected=revisions.getOrDefault(id,"");
            if(!Files.exists(directory.resolve(id+".yml")))throw ChacaApi.error("dialogues/"+id+".yml 파일이 없어요");
            work(()->store.edit(id,expected,read(id)),ok->{
                if(ok){ChacaApi.ok(sender,id+" 대화 파일을 반영했어요");refresh();}
                else ChacaApi.fail(sender,"다른 곳에서 먼저 바뀌었어요. 파일로내보내기로 확인한 뒤 다시 해 주세요",null);
            },failTo(sender));return Command.SINGLE_SUCCESS;
        })),"MagicCodex 대화 YAML 파일을 공용 대화에 반영","파일반영 ");
        ChacaApi.branch(plugin,ChacaApi.대화관리,Commands.literal("파일로내보내기").then(Commands.argument("대화",ChacaApi.word(this::dialogueIds)).executes(ctx->{
            String id=dialogueArg(ctx);var sender=CodexCommands.sender(ctx);
            work(()->{var d=store.catalog().get(id);if(d==null)throw new IllegalArgumentException(id+" 대화가 없어요");export(d);return true;},
                ok->ChacaApi.ok(sender,id+" 대화를 dialogues/"+id+".yml 로 내보냈어요 (이전 파일은 .bak)"),failTo(sender));return Command.SINGLE_SUCCESS;
        })),"공용 대화를 YAML 파일로 내보내기","파일로내보내기 ");
        ChacaApi.branch(plugin,ChacaApi.대화관리,Commands.literal("열기").then(Commands.argument("닉네임",ChacaApi.player())
            .then(Commands.argument("대화",ChacaApi.word(this::dialogueIds)).executes(ctx->{
                Player p=ChacaApi.resolveOnline(ctx,"닉네임");String id=dialogueArg(ctx);var d=catalog.get(id);
                if(d==null)throw ChacaApi.error(id+" 대화가 없어요");
                if(!p.getListeningPluginChannels().contains(DialogueProtocol.RESPONSE))throw ChacaApi.error(ChacaApi.name(p.getUniqueId())+" 님에게 최신 MagicCodex 모드가 없어요");
                open(p,d,false,null);ChacaApi.ok(CodexCommands.sender(ctx),ChacaApi.name(p.getUniqueId())+" 님에게 "+d.title()+" 대화를 열었어요");return Command.SINGLE_SUCCESS;
            }))),"유저에게 MagicCodex 대화 열기 (조건·권한은 그대로 확인)","열기 ");

        // /유저관리 <닉네임> 대화기록 <보기|처리 <기록> <다시실행|완료처리>>
        var resolve=Commands.argument("기록",ChacaApi.word(()->{var all=new ArrayList<String>();listed.values().forEach(all::addAll);return all;}));
        for(String how:List.of("다시실행","완료처리"))resolve.then(Commands.literal(how).executes(ctx->{resolveEffect(ctx,how.equals("다시실행"));return Command.SINGLE_SUCCESS;}));
        resolve.executes(ctx->{
            UUID uid=ChacaApi.target(ctx);String token=tokenArg(ctx);var sender=CodexCommands.sender(ctx);
            work(()->store.effects(uid),rows->{var e=rows.stream().filter(v->v.token().equals(token)).findFirst().orElse(null);
                if(e==null){ChacaApi.fail(sender,"끝나지 않은 기록 중에 그 기록이 없어요. '보기'로 확인해 주세요",null);return;}
                var out=new ArrayList<String>();out.add("── "+e.key()+" ──");out.add(" 상태: "+statusText(e));for(String action:e.actions())out.add("  남은 동작: "+action);
                out.add(" 끝에 '다시실행'(남은 동작 실행) 또는 '완료처리'(실행하지 않고 닫기)를 붙여 주세요");ChacaApi.lines(sender,out);
            },failTo(sender));return Command.SINGLE_SUCCESS;
        });
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("대화기록")
            .then(Commands.literal("보기").executes(ctx->{
                UUID uid=ChacaApi.target(ctx);var sender=CodexCommands.sender(ctx);
                work(()->store.effects(uid),rows->{
                    listed.put(sender.getName(),rows.stream().map(DialogueStore.Effect::token).toList());
                    var out=new ArrayList<String>();out.add("── "+ChacaApi.name(uid)+" 님의 끝나지 않은 대화 동작 "+rows.size()+"건 ──");
                    for(var e:rows){out.add(" "+e.token()+" · "+e.key()+" · "+statusText(e));for(String action:e.actions())out.add("   남은 동작: "+action);}
                    if(!rows.isEmpty())out.add(" 처리: /유저관리 "+ChacaApi.name(uid)+" 대화기록 처리 <기록> <다시실행|완료처리>");
                    ChacaApi.lines(sender,out);
                },failTo(sender));return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("처리").then(resolve)),"MagicCodex 대화 효과 기록 보기·처리","대화기록 보기");
    }
    /** 대화관리 파일반영 자동완성: dialogues/ 폴더의 yml 파일 이름. */
    private Collection<String> fileIds(){
        var out=new TreeSet<String>();
        try(var list=Files.list(directory)){list.forEach(f->{String n=f.getFileName().toString();if(n.endsWith(".yml"))out.add(n.substring(0,n.length()-4));});}catch(IOException ignored){}
        return out;
    }
    private static String tokenArg(CommandContext<CommandSourceStack> ctx)throws CommandSyntaxException{
        try{return UUID.fromString(ctx.getArgument("기록",String.class)).toString();}catch(IllegalArgumentException e){throw ChacaApi.error("기록 번호는 '보기'에 나온 긴 번호를 그대로 넣어 주세요");}
    }
    private void resolveEffect(CommandContext<CommandSourceStack> ctx,boolean rerun)throws CommandSyntaxException{
        UUID uid=ChacaApi.target(ctx);String token=tokenArg(ctx);var sender=CodexCommands.sender(ctx);String admin=sender.getName();
        if(runs.containsKey(token)||busy.contains(uid))throw ChacaApi.error("지금 처리 중인 기록이에요. 잠시 뒤 다시 해 주세요");
        work(()->{
            var before=store.effects(uid).stream().filter(e->e.token().equals(token)).findFirst().orElseThrow(()->new IllegalArgumentException("끝나지 않은 기록 중에 그 기록이 없어요. '보기'로 확인해 주세요"));
            if(!rerun)store.finish(uid,token);
            else if(!before.status().equals("queued")&&!store.move(uid,token,List.of("review","running","pending"),"queued",null))throw new IllegalStateException("기록이 방금 바뀌었어요. '보기'로 다시 확인해 주세요");
            return before;
        },before->{
            plugin.getLogger().warning("DIALOGUE ADMIN admin="+admin+" player="+uid+" key="+before.key()+" token="+token+" action="+(rerun?"rerun":"done")+" from="+before.status()+" actions="+before.actions());
            if(!rerun){ChacaApi.ok(sender,"실행하지 않고 완료로 닫았어요 (남은 동작 "+before.actions().size()+"개)");return;}
            Player online=Bukkit.getPlayer(uid);
            if(online==null||!current(online)||!ready(online)||busy.contains(uid)||sessions.containsKey(uid)){ChacaApi.ok(sender,"다시 실행 대기로 바꿨어요. 유저가 다음에 접속하거나 대화를 열 때 실행해요");return;}
            busy.add(uid);work(uid,()->store.effects(uid),list->{var blocked=new HashMap<String,String>();resume(online,list,0,blocked,()->{busy.remove(uid);
                if(blocked.containsKey(before.dialogue()))ChacaApi.fail(sender,"다시 실행했지만 끝나지 않았어요: "+blocked.get(before.dialogue()),null);else ChacaApi.ok(sender,"남은 동작을 다시 실행해 끝냈어요");});},m->{busy.remove(uid);ChacaApi.fail(sender,m,null);});
        },failTo(sender));
    }
    private String statusText(DialogueStore.Effect e){return switch(e.status()){case "queued"->runs.containsKey(e.token())?"실행 중":"대기 — 유저 접속·대화 열기 때 자동 실행";case "running"->runs.containsKey(e.token())?"실행 중":"실행 여부 불명 — 실행 도중 서버가 멈춤 (관리자 확인)";case "review"->"동작 실패 — 맨 위 동작부터 실행되지 않음 (관리자 확인)";case "pending"->"옛 기록 — 실행 여부 불명 (관리자 확인)";default->e.status();};}
    Map<String,String> journalCatalog(){var result=new TreeMap<String,String>();catalog.values().stream().filter(d->!d.storyId().isEmpty()).forEach(d->result.put("dialogue:"+d.storyId(),d.storyTitle()));return result;}
    void journalRows(Player p,java.util.function.Consumer<List<school.magiccodex.protocol.JournalProtocol.Entry>> callback){work(()->store.state(p.getUniqueId()),state->{
        var rows=new ArrayList<school.magiccodex.protocol.JournalProtocol.Entry>();var ids=new TreeSet<String>();for(String key:state.values().keySet())if(key.startsWith("story."))ids.add(key.substring(6));else if(key.startsWith("journal."))ids.add(key.substring(8));
        for(String id:ids){var d=catalog.values().stream().filter(v->v.storyId().equals(id)).sorted(Comparator.comparing(DialogueDefinition::id)).findFirst().orElse(null);String stage=state.values().getOrDefault("story."+id,"");String status=state.values().getOrDefault("journal."+id,"active");if(status.equals("hidden"))continue;
            rows.add(new school.magiccodex.protocol.JournalProtocol.Entry("dialogue",id,d==null?id:d.storyTitle(),d==null?stage:d.stages().getOrDefault(stage,stage),status,true,"",""));}callback.accept(List.copyOf(rows));},m->{plugin.getLogger().warning("대화 기록 읽기 실패 ("+p.getName()+"): "+m);callback.accept(List.of());});}
    void journalEdit(CommandSender sender,Player target,String id,String operation){if(!journalCatalog().containsKey("dialogue:"+id))throw new IllegalArgumentException("등록된 대화 퀘스트를 찾을 수 없습니다.");
        if(operation.equals("query")){journalRows(target,rows->ChacaApi.lines(sender,rows.stream().filter(e->e.id().equals(id)).map(e->" "+e.key()+" / "+e.state()+" / "+e.description()).toList()));return;}
        work(()->{store.journalEdit(target.getUniqueId(),id,operation);return true;},ok->{ChacaApi.ok(sender,"퀘스트 기록을 바꿨어요 (이야기 단계·보상·호감도 수령 기록은 그대로)");if(operation.equals("grant"))JournalBridge.notify(plugin,target,journalCatalog().get("dialogue:"+id),"");plugin.getLogger().info("DIALOGUE JOURNAL admin="+sender.getName()+" player="+target.getUniqueId()+" id="+id+" operation="+operation);},m->ChacaApi.fail(sender,m,null));
    }
    private void journal(Player p){if(!ready(p)||!gate(p))return;if(p.getListeningPluginChannels().contains(school.magiccodex.protocol.JournalProtocol.REPLY)){JournalBridge.send(plugin,p,new school.magiccodex.protocol.JournalProtocol.Message(6,"","","","",0,0,List.of()));return;}work(()->store.state(p.getUniqueId()),state->{
        if(!current(p))return;
        var rows=state.values().entrySet().stream().filter(e->e.getKey().startsWith("story.")).sorted(Map.Entry.comparingByKey()).map(e->{String id=e.getKey().substring(6);var d=catalog.values().stream().filter(v->v.storyId().equals(id)).sorted(Comparator.comparing(DialogueDefinition::id)).findFirst().orElse(null);return "[메인 퀘스트] "+(d==null?id:d.storyTitle())+"\n"+(d==null?e.getValue():d.stages().getOrDefault(e.getValue(),e.getValue()));}).toList();
        int pages=Math.max(1,(rows.size()+3)/4);var nodes=new LinkedHashMap<String,DialogueDefinition.Node>();
        for(int page=0;page<pages;page++){var choices=new ArrayList<DialogueDefinition.Choice>();if(page>0)choices.add(new DialogueDefinition.Choice("previous","이전 이야기","p"+(page-1),List.of(),List.of()));if(page+1<pages)choices.add(new DialogueDefinition.Choice("next","다음 이야기","p"+(page+1),List.of(),List.of()));String text=rows.isEmpty()?"아직 시작한 이야기가 없습니다.":String.join("\n\n",rows.subList(page*4,Math.min(rows.size(),page*4+4)));nodes.put("p"+page,new DialogueDefinition.Node("이야기 기록","",text,List.of(),List.copyOf(choices)));}
        var d=new DialogueDefinition("journal","메인 퀘스트","p0",true,"",List.of(),Map.copyOf(nodes),"","",Map.of());var session=new Session(d,state,false,null,"");session.journal=true;sessions.put(p.getUniqueId(),session);send(p,session,"");
    },m->CodexCommands.warn(p,"dialogue",m));}
    /**
     * 코드가 만든 선택 창 (2026-10-26, 수업 고르기). 기존 대화창 그대로, 진행 기록·동작 없이 선택만 받는다.
     * pick(플레이어, 선택지 id): 빈 문자열/null = 창을 닫음, 그 밖 = 그 문구를 아래에 띄우고 창은 그대로.
     * 다음 장면(next)이 있는 선택지(페이지 넘기기 등)는 pick 을 부르지 않고 그 장면으로 간다. 장면당 선택지 6개까지.
     */
    void openMenu(Player p,Entity anchor,String title,String start,Map<String,DialogueDefinition.Node> nodes,java.util.function.BiFunction<Player,String,String> pick){
        if(!ready(p)||!gate(p))return;
        if(!p.getListeningPluginChannels().contains(DialogueProtocol.RESPONSE)){CodexCommands.warn(p,"dialogue","대화를 열려면 최신 MagicCodex 모드가 필요해요");return;}
        work(()->store.state(p.getUniqueId()),state->{
            if(!current(p)||!near(p,anchor))return;
            var d=new DialogueDefinition("menu",title,start,true,"",List.of(),Map.copyOf(nodes),"","",Map.of());
            var session=new Session(d,state,false,anchor,"");session.journal=true;session.pick=pick;sessions.put(p.getUniqueId(),session);send(p,session,"");
        },m->CodexCommands.warn(p,"dialogue",m));
    }
    private void refresh(){work(()->store.snapshot(null),this::install,m->plugin.getLogger().warning(m));}

    private interface Job<T>{T run()throws Exception;}
    /** 메인 스레드 후속 처리는 여기를 거친다: 예외가 나도 busy 가 풀리고, 실행 중이던 묶음은 남은 동작 그대로 기록해 다음에 이어서 실행한다. */
    private void guard(UUID owner,Runnable r){
        try{r.run();}catch(Throwable t){
            plugin.getLogger().log(Level.SEVERE,"Dialogue continuation failed"+(owner==null?"":" for "+owner),t);
            if(owner!=null){busy.remove(owner);arming.remove(owner);for(var run:new ArrayList<>(runs.values()))if(run.player.equals(owner)&&!run.eventInFlight)park(run,"queued",()->{});}
        }
    }
    private <T>void work(Job<T> job,Consumer<T> done,Consumer<String> fail){work(null,job,done,fail);}
    /** 새 요청: 밀려 있으면 아무 것도 바꾸지 않고 거절한다. */
    private <T>void work(UUID owner,Job<T> job,Consumer<T> done,Consumer<String> fail){if(closing||pending>=256){fail.accept("대화 처리가 밀리고 있습니다. 잠시 후 다시 시도해 주세요.");return;}submit(owner,job,done,fail);}
    /** 이미 시작한 동작의 후속 기록: 대기 수 한도로 거절하지 않는다. */
    private <T>void must(UUID owner,Job<T> job,Consumer<T> done,Consumer<String> fail){if(closing){fail.accept("서버가 종료 중입니다.");return;}submit(owner,job,done,fail);}
    private <T>void submit(UUID owner,Job<T> job,Consumer<T> done,Consumer<String> fail){pending++;io.execute(()->{
        T result;try{result=job.run();}catch(Exception e){plugin.getLogger().warning("Dialogue storage: "+e.getClass().getSimpleName()+": "+e.getMessage());String m=e instanceof IllegalArgumentException||e instanceof IllegalStateException?String.valueOf(e.getMessage()):"대화 저장을 확인할 수 없습니다. 다시 시도해 주세요.";main(()->{pending--;guard(owner,()->fail.accept(m));});return;}
        main(()->{pending--;guard(owner,()->done.accept(result));});});}
    private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closing)r.run();});}
    @Override public void close(){closing=true;
        // 여기부터 메인 스레드 후속 처리는 실행되지 않는다. 실행 중이던 묶음은 "아직 실행하지 않은 동작"이 정확히 알려져 있으므로 그대로 기록해 다음 접속 때 이어서 실행한다.
        // (event 응답을 기다리던 묶음만 running 으로 남겨 관리자 확인 대상으로 둔다.)
        for(var run:runs.values())if(!run.eventInFlight){var remaining=List.copyOf(run.remaining);try{io.execute(()->{try{store.move(run.player,run.token,List.of(run.armed?"running":"queued"),remaining.isEmpty()?"done":"queued",remaining);}catch(Exception e){plugin.getLogger().warning("Dialogue shutdown park failed: "+run.token);}});}catch(RejectedExecutionException ignored){}}
        for(var entry:arming.entrySet())try{io.execute(()->{try{store.requeue(entry.getKey(),entry.getValue());}catch(Exception e){plugin.getLogger().warning("Dialogue shutdown requeue failed: "+entry.getValue());}});}catch(RejectedExecutionException ignored){}
        runs.clear();arming.clear();sessions.clear();io.shutdown();try{if(io.awaitTermination(15,TimeUnit.SECONDS))store.close();else plugin.getLogger().warning("Dialogue worker shutdown timeout");}catch(Exception e){plugin.getLogger().warning("Dialogue close failed");}HandlerList.unregisterAll(this);}
}
