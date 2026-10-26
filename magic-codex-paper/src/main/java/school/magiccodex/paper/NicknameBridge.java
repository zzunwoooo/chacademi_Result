package school.magiccodex.paper;

import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.NicknameProtocol;
import school.magiccodex.protocol.NicknameProtocol.*;

/** Connection identity owns every operation. Session UUID is never supplied by a client.
 * /최초닉네임설정: marks a player as needing a first nickname (kept in first-nickname.yml so a crash or logout
 * re-opens it on the next join), pushes an undismissable screen, and runs first-nickname.on-complete after the save.
 * 두 서버 공유: 닉네임 행은 접속 시 + 45초마다 DB에서 다시 읽는다. 최초 설정 완료 여부의 기준은 DB의 first_done이며
 * (first-nickname.yml은 이 서버의 "창을 다시 열 대상" 목록일 뿐), on-complete는 first_done을 0→1로 바꾼 서버에서 한 번만 실행된다.
 * 메인 스레드는 DB·파일 작업을 기다리지 않는다: 대기 목록 파일은 메모리 상태를 먼저 바꾸고 IO 스레드가 뒤따라 기록한다(실패 시 재시도). */
final class NicknameBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;private final DisplayNames names;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-nickname-io");t.setDaemon(true);return t;});
    private final Map<UUID,NicknameStore.State> profiles=new ConcurrentHashMap<>();
    private final Map<UUID,NicknameSession> sessions=new HashMap<>();
    private final Set<UUID> busy=new HashSet<>();
    private final List<NicknameExpansion> expansions=new ArrayList<>();
    private final NicknameStore store;private long serial;private volatile boolean closed;
    private final FirstNicknameState firstState;private final java.io.File firstFile;
    private static final long REFRESH_TICKS=900L; // 45초: 다른 서버에서 바뀐 닉네임 반영 주기
    private static final String DUPLICATE="이미 사용 중인 닉네임입니다. 다른 닉네임을 입력해 주세요.";
    private final Set<UUID> firstBusy=new HashSet<>();
    private volatile Set<UUID> firstLatest=Set.of();
    private boolean refreshing,refreshFailed;

    NicknameBridge(MagicCodexBridge plugin,DisplayNames names)throws Exception{
        this.plugin=plugin;this.names=names;
        try{var settings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());store=io.submit(()->new NicknameStore(plugin.getDataFolder().toPath().resolve("friends.db"),settings)).get(10,TimeUnit.SECONDS);profiles.putAll(io.submit(store::all).get(10,TimeUnit.SECONDS));}catch(Exception e){io.shutdownNow();throw e;}
        for(String warning:store.warnings())plugin.getLogger().warning("[닉네임] "+warning);
        firstFile=new java.io.File(plugin.getDataFolder(),"first-nickname.yml");
        Set<UUID> initial;try{initial=io.submit(this::loadFirst).get(10,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
        // 시작 시 한 번만 기다린다. 이후 기록은 비동기(queueFirst)라 메인 스레드를 막지 않는다.
        firstLatest=Set.copyOf(initial);firstState=new FirstNicknameState(initial,this::queueFirst);
        Bukkit.getScheduler().runTaskTimer(plugin,this::refresh,REFRESH_TICKS,REFRESH_TICKS);
        // 명령은 허브가 만들어진 뒤 StatsBridge 생성자에서 registerCommands()로 붙인다 (DisplayNames가 허브보다 먼저 만들어짐).
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,NicknameProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,NicknameProtocol.RESPONSE);Bukkit.getPluginManager().registerEvents(this,plugin);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){var manager=me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance().getLocalExpansionManager();for(String id:List.of("magiccodex","user")){if(manager.getExpansion(id)!=null)continue;var expansion=new NicknameExpansion(this,id,plugin.getDescription().getVersion());if(expansion.register())expansions.add(expansion);}}
    }
    private final Map<UUID,Long> displayReads=new HashMap<>();private final Set<UUID> displayReading=new HashSet<>();
    /** Display-only asynchronous refresh; never creates/grants/renames a nickname. Main-thread entry point. */
    void requestStored(UUID id){long now=System.currentTimeMillis();if(closed||busy.contains(id)||displayReading.contains(id)||displayReads.getOrDefault(id,0L)>now)return;
        displayReads.put(id,now+5000);displayReading.add(id);if(displayReads.size()>4096)displayReads.entrySet().removeIf(e->e.getValue()<now);
        try{io.execute(()->{NicknameStore.State loaded=null;boolean ok=false;try{loaded=store.load(id);ok=true;}catch(Exception ignored){}var state=loaded;boolean success=ok;main(()->{displayReading.remove(id);if(success&&!busy.contains(id))apply(id,state);});});}catch(RejectedExecutionException e){displayReading.remove(id);}
    }
    String stored(UUID id){var state=profiles.get(id);return state==null?"":state.nickname();}
    /** 저장된 닉네임으로 주인 찾기 (접속하지 않은 유저 포함, 대소문자·전각 무시). 없으면 null. 메인 스레드. */
    UUID ownerOf(String nickname){
        String key;try{key=NicknameStore.key(nickname.strip());}catch(IllegalArgumentException e){return null;}
        UUID found=null;
        for(var e:profiles.entrySet()){String saved=e.getValue().nickname();if(saved==null||saved.isEmpty())continue;String k;try{k=NicknameStore.key(saved);}catch(IllegalArgumentException ignored){continue;}if(k.equals(key)){if(found!=null)return null;found=e.getKey();}}
        return found;
    }
    /** 자동완성용: 저장된 닉네임 전체 (접속하지 않은 유저 포함). */
    java.util.List<String> nicknames(){var out=new java.util.ArrayList<String>(profiles.size());for(var s:profiles.values())if(s.nickname()!=null&&!s.nickname().isEmpty())out.add(s.nickname());return out;}
    String placeholder(OfflinePlayer p){String value=stored(p.getUniqueId());return value.isEmpty()?(p.getName()==null?"":p.getName()):value;}
    /** nickname/account 자리 표시자 값. 그 밖의 키는 null. */
    String answer(OfflinePlayer p,String params){if(p==null)return "";return params.equals("nickname")?placeholder(p):params.equals("account")?(p.getName()==null?"":p.getName()):null;}
    /** "magiccodex" 식별자는 닉네임 확장이 소유하고, 마나 자리 표시자는 여기로 넘겨받은 함수가 답한다. 소유하지 못했으면 false. */
    boolean delegatePlaceholders(java.util.function.BiFunction<OfflinePlayer,String,String> delegate){
        for(var expansion:expansions)if(expansion.getIdentifier().equals("magiccodex")){expansion.delegate(delegate);return true;}
        return false;
    }
    /** DB에서 읽은 행을 캐시에 반영 (메인 스레드). 더 오래된 revision으로는 덮어쓰지 않는다. */
    private void apply(UUID id,NicknameStore.State state){
        var old=profiles.get(id);
        if(state==null){if(old!=null){profiles.remove(id);names.invalidate(id);}return;}
        if(old!=null&&old.revision()>state.revision())return;
        if(!state.equals(old)){profiles.put(id,state);names.invalidate(id);}
    }
    /** 다른 서버에서 바뀐 닉네임을 반영: 쿼리 한 번, IO 스레드. 실패하면 마지막 캐시를 그대로 쓴다. */
    private void refresh(){
        if(closed||refreshing)return;refreshing=true;
        try{io.execute(()->{
            Map<UUID,NicknameStore.State> all=null;String failure=null;
            try{all=store.all();}catch(Exception e){failure=String.valueOf(e.getMessage());}
            final var snapshot=all;final String error=failure;
            main(()->{
                refreshing=false;
                if(snapshot==null){if(!refreshFailed){refreshFailed=true;plugin.getLogger().warning("닉네임 새로고침 실패 (마지막 값 유지, 계속 재시도): "+error);}return;}
                refreshFailed=false;snapshot.forEach((id,state)->{if(!busy.contains(id))apply(id,state);});
            });
        });}catch(RejectedExecutionException e){refreshing=false;}
    }
    private void main(Runnable task){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{if(!closed)task.run();});}
    private boolean current(Player p,NicknameSession session){return !closed&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p&&sessions.get(p.getUniqueId())==session;}
    private String title(Player p,String side){if(!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))return "";String value=me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p,"%chacademiatitle_"+side+"%");if(value.contains("%"))return "";value=org.bukkit.ChatColor.stripColor(value).replaceAll("[\\p{Cntrl}\\p{Cf}]","");return value.length()>64?value.substring(0,64):value;}
    private void reply(Player p,NicknameSession s,long seq,int kind,String message){var state=profiles.get(p.getUniqueId());var r=new Response(kind,seq,s.token(),state==null?0:state.revision(),p.getUniqueId(),p.getName(),names.name(p),title(p,"prefix"),title(p,"suffix"),message);if(p.getListeningPluginChannels().contains(NicknameProtocol.RESPONSE))p.sendPluginMessage(plugin,NicknameProtocol.RESPONSE,NicknameProtocol.encode(r));}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(!channel.equals(NicknameProtocol.REQUEST)||closed)return;Request r;try{r=NicknameProtocol.request(bytes);}catch(IllegalArgumentException e){return;}UUID id=p.getUniqueId();
        if(r.action()==NicknameProtocol.OPEN){
            if(r.session()!=0||busy.contains(id))return;var old=sessions.get(id);if(old!=null&&old.connection()==p&&r.sequence()<=old.sequence())return;
            var s=new NicknameSession(id,p,++serial,r.sequence());sessions.put(id,s);busy.add(id);
            io.execute(()->{try{var state=store.load(id);main(()->{busy.remove(id);if(!current(p,s))return;if(state==null)profiles.remove(id);else profiles.put(id,state);names.invalidate(id);reply(p,s,r.sequence(),NicknameProtocol.SNAPSHOT,"");});}catch(Exception e){main(()->{busy.remove(id);if(current(p,s))reply(p,s,r.sequence(),NicknameProtocol.NOTICE,"닉네임을 불러오지 못했습니다. 다시 시도해 주세요.");});}});return;
        }
        var old=sessions.get(id);if(old==null||!old.accepts(id,p,r))return;
        if(r.action()==NicknameProtocol.CLOSE){sessions.remove(id);return;}if(busy.contains(id))return;
        var s=new NicknameSession(id,p,old.token(),r.sequence());sessions.put(id,s);
        String key;try{NicknameProtocol.validate(r.nickname());key=NicknameStore.key(r.nickname());}catch(IllegalArgumentException e){reply(p,s,r.sequence(),NicknameProtocol.NOTICE,e.getMessage());return;}
        // 접속 중인 다른 플레이어의 계정 이름과 같은 닉네임은 사칭이 되므로 막는다 (닉네임을 정한 적 있는 계정은 저장소가 DB에서 한 번 더 확인).
        for(Player other:Bukkit.getOnlinePlayers())if(other!=p&&!other.getUniqueId().equals(id)&&other.getName().toLowerCase(Locale.ROOT).equals(key)){reply(p,s,r.sequence(),NicknameProtocol.NOTICE,DUPLICATE);return;}
        var before=profiles.get(id);long revision=before==null?0:before.revision();if(revision!=r.revision()){reply(p,s,r.sequence(),NicknameProtocol.SNAPSHOT,"닉네임 정보가 바뀌었습니다. 확인 후 다시 저장해 주세요.");return;}
        busy.add(id);String account=p.getName();
        io.execute(()->{try{var result=store.save(id,account,r.nickname(),revision);main(()->{
            busy.remove(id);var state=result.state();if(state!=null)profiles.put(id,state);names.invalidate(id);
            boolean saved=result.outcome()==NicknameStore.Outcome.SAVED;
            if(!current(p,s))return;
            // 중복은 NOTICE: 클라이언트가 입력한 글자를 지우지 않고 안내만 보여 준다.
            if(result.outcome()==NicknameStore.Outcome.DUPLICATE)reply(p,s,r.sequence(),NicknameProtocol.NOTICE,DUPLICATE);
            else reply(p,s,r.sequence(),NicknameProtocol.SNAPSHOT,saved?"닉네임을 저장했습니다.":"닉네임 정보가 바뀌었습니다. 확인 후 다시 저장해 주세요.");
            if(saved)firstSaved(p,s);
        });}catch(Exception e){main(()->{busy.remove(id);if(current(p,s))reply(p,s,r.sequence(),NicknameProtocol.NOTICE,"저장하지 못했습니다. 다시 시도해 주세요.");});}});
    }
    @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());}

    // ------------------------------------------------------------------ 최초 닉네임
    private Set<UUID> loadFirst() throws Exception {
        Set<UUID> pending=new HashSet<>();if(!firstFile.isFile())return pending;
        var y=new org.bukkit.configuration.file.YamlConfiguration();y.load(firstFile);
        for(String raw:y.getStringList("pending"))pending.add(UUID.fromString(raw));
        return pending;
    }
    private void saveFirst(Set<UUID> pending) throws Exception {
        var y=new org.bukkit.configuration.file.YamlConfiguration();
        y.set("pending",pending.stream().map(UUID::toString).sorted().toList());
        var target=firstFile.toPath();java.nio.file.Files.createDirectories(target.getParent());
        var temporary=java.nio.file.Files.createTempFile(target.getParent(),"first-nickname-",".tmp");
        try {
            java.nio.file.Files.writeString(temporary,y.saveToString(),java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {java.nio.file.Files.deleteIfExists(temporary);}
    }
    /** 대기 목록 기록 예약 (메인 스레드에서 호출, 기다리지 않음). IO 스레드는 순서대로 실행하므로 마지막 상태가 파일에 남는다. */
    private void queueFirst(Set<UUID> next){firstLatest=next;writeFirst(0);}
    private void writeFirst(int attempt){
        try{io.execute(()->{
            try{saveFirst(firstLatest);}
            catch(Exception e){
                plugin.getLogger().warning("first-nickname.yml 저장 실패 ("+(attempt+1)+"번째, 메모리 상태는 유지): "+e.getMessage());
                // 5초 뒤 최신 상태로 다시 기록 (최대 5회). 그래도 실패하면 재시작 후 창이 자동으로 다시 열리지 않을 수 있다.
                if(attempt<5&&!closed&&plugin.isEnabled())try{Bukkit.getScheduler().runTaskLater(plugin,()->writeFirst(attempt+1),100L);}catch(RuntimeException ignored){}
            }
        });}catch(RejectedExecutionException ignored){}
    }
    private void clearFirst(UUID id){try{firstState.complete(id,()->true,()->{});}catch(Exception ignored){}}
    boolean firstPending(UUID id){return firstState.pending(id);}
    /** 대기 등록 후 창을 연다; repeated commands do not create multiple pending entries. */
    void startFirst(Player p){
        try{firstState.start(p.getUniqueId());pushFirst(p,0);}
        catch(Exception e){plugin.getLogger().warning("Could not persist first nickname state: "+e);CodexCommands.warn(p,"nickname","닉네임 설정 상태를 저장하지 못했어요. 잠시 후 다시 해 주세요");}
    }
    /** The client registers its channels a moment after join; retry for ~15 s. */
    private void pushFirst(Player p,int attempt){
        if(closed||!p.isOnline()||!firstState.pending(p.getUniqueId()))return;
        if(p.getListeningPluginChannels().contains(NicknameProtocol.RESPONSE)){push(p,NicknameProtocol.FIRST);return;}
        if(attempt<15)Bukkit.getScheduler().runTaskLater(plugin,()->pushFirst(p,attempt+1),20L);
        else CodexCommands.warn(p,"nickname","닉네임 설정 창을 열지 못했어요. MagicCodex 모드가 필요해요");
    }
    private void push(Player p,int kind){
        var state=profiles.get(p.getUniqueId());
        var r=new Response(kind,0,0,state==null?0:state.revision(),p.getUniqueId(),p.getName(),names.name(p),"","","");
        if(p.getListeningPluginChannels().contains(NicknameProtocol.RESPONSE))p.sendPluginMessage(plugin,NicknameProtocol.RESPONSE,NicknameProtocol.encode(r));
    }
    private void firstSaved(Player p,NicknameSession session){
        UUID id=p.getUniqueId();
        if(!firstState.pending(id))return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!current(p,session)||!firstState.pending(id)||!firstBusy.add(id))return;
            FirstNicknameCommands.Plan plan;
            try {
                var config=plugin.getConfig();
                plan=FirstNicknameCommands.plan(config.isSet("first-nickname.on-complete")?config.getStringList("first-nickname.on-complete"):null,p.getName(),id,names.name(p));
            } catch(IllegalArgumentException e) { firstBusy.remove(id);plugin.getLogger().warning("config.yml first-nickname.on-complete 오류: "+e.getMessage());CodexCommands.warn(p,"nickname","닉네임 다음 단계를 열지 못했어요. 관리자에게 알려 주세요");return; }
            // Release the mandatory nickname screen so the dialogue can actually open.
            // This UI message does NOT write database first_done.
            push(p,NicknameProtocol.FIRST_DONE);
            CompletableFuture<Boolean> opened=requestFirstDialogue(p,plan.dialogue());
            var gate=new FirstNicknameAck();
            opened.whenComplete((accepted,error)->main(()->{
                if(!gate.accept(error==null&&Boolean.TRUE.equals(accepted),!closed&&p.isOnline()&&Bukkit.getPlayer(id)==p&&firstState.pending(id))){
                    firstBusy.remove(id);
                    if(p.isOnline()&&Bukkit.getPlayer(id)==p&&firstState.pending(id)){
                        plugin.getLogger().warning("최초 닉네임 후속 대화를 열지 못함: "+p.getName()+" (ChacademyStory·대화 파일·모드 확인)");
                        CodexCommands.warn(p,"nickname","다음 대화를 열지 못했어요. 닉네임을 다시 저장해 주세요");
                        pushFirst(p,0);
                    }
                    return;
                }
                try{io.execute(()->{
                    boolean transitioned;
                    try{transitioned=store.completeFirst(id);}
                    catch(Exception e){main(()->{firstBusy.remove(id);plugin.getLogger().warning("Could not complete first nickname state.");if(p.isOnline()&&Bukkit.getPlayer(id)==p)pushFirst(p,0);});return;}
                    main(()->{
                        firstBusy.remove(id);
                        var cached=profiles.get(id);if(cached!=null&&!cached.firstDone())profiles.put(id,new NicknameStore.State(cached.account(),cached.nickname(),cached.revision(),true));
                        try{firstState.complete(id,()->true,()->{
                            if(transitioned&&p.isOnline()&&Bukkit.getPlayer(id)==p)
                                plan.after().forEach(c->Bukkit.dispatchCommand(Bukkit.getConsoleSender(),c));
                        });}catch(Exception e){plugin.getLogger().warning("Could not complete first nickname state.");}
                    });
                });}catch(RejectedExecutionException e){firstBusy.remove(id);}
            }));
        },10L);
    }
    @SuppressWarnings("unchecked")
    private CompletableFuture<Boolean> requestFirstDialogue(Player p,String id){
        if(id==null)return CompletableFuture.completedFuture(true);
        var story=Bukkit.getPluginManager().getPlugin("ChacademyStory");
        if(story==null||!story.isEnabled())return CompletableFuture.completedFuture(false);
        try {
            Object result=story.getClass().getMethod("requestNicknameDialogue",Player.class,String.class).invoke(story,p,id);
            if(result instanceof CompletableFuture<?> f)return (CompletableFuture<Boolean>)f;
        }catch(ReflectiveOperationException|RuntimeException e){plugin.getLogger().warning("ChacademyStory opening acknowledgement unavailable; first nickname remains pending.");}
        return CompletableFuture.completedFuture(false);
    }
    /** 접속 시 이 플레이어의 행을 DB에서 읽어 캐시에 반영한다 (다른 서버에서 정한 닉네임). 최초 설정 대기 중이면 그 뒤에 창을 연다. */
    @EventHandler public void joinFirst(PlayerJoinEvent e){
        Player p=e.getPlayer();UUID id=p.getUniqueId();
        try{io.execute(()->{
            NicknameStore.State loaded=null;boolean ok=false;
            try{loaded=store.load(id);ok=true;}catch(Exception ignored){}
            final var state=loaded;final boolean success=ok;
            main(()->{
                if(success&&!busy.contains(id))apply(id,state);
                if(!p.isOnline()||!firstState.pending(id))return;
                // 다른 서버에서 이미 최초 설정을 마쳤다면 이 서버의 대기만 지운다 (창·스토리 다시 열지 않음).
                var now=profiles.get(id);if(success&&now!=null&&now.firstDone()){clearFirst(id);return;}
                Bukkit.getScheduler().runTaskLater(plugin,()->pushFirst(p,0),40L);
            });
        });}catch(RejectedExecutionException ignored){}
    }
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    /** /유저관리 <닉네임> 닉네임 <창열기|다시정하기|설정 <새닉네임>> (예전 /최초닉네임설정 &lt;플레이어&gt; [강제]). */
    void registerCommands(){
        if(!CodexCommands.ready(plugin,"닉네임"))return;
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("닉네임")
            .then(Commands.literal("창열기").executes(ctx->{openFirst(CodexCommands.sender(ctx),ChacaApi.targetOnline(ctx),false);return 1;}))
            .then(Commands.literal("다시정하기").executes(ctx->{openFirst(CodexCommands.sender(ctx),ChacaApi.targetOnline(ctx),true);return 1;}))
            .then(Commands.literal("설정").then(Commands.argument("새닉네임",ChacaApi.word(List::of)).executes(ctx->{
                adminSet(CodexCommands.sender(ctx),ChacaApi.target(ctx),ctx.getArgument("새닉네임",String.class));return 1;
            }))),"최초 닉네임 창 열기·다시 정하기·관리자 지정","닉네임 창열기");
        // 스토리 1-1 대화 끝(endings.wake)이 플레이어 명령으로 부른다. 본인용: 아직 최초 닉네임을 안 정했을 때만 열림.
        ChacaApi.playerCommand(plugin,Commands.literal("최초닉네임설정").executes(ctx->{var self=CodexCommands.self(ctx);openFirst(self,self,false);return 1;}),
            "최초 닉네임 정하기 (스토리 진행용)",List.of());
    }
    /** 최초 닉네임 창. force=true 이면 이미 마친 유저도 처음부터 다시 정하게 한다. */
    private void openFirst(org.bukkit.command.CommandSender sender,Player target,boolean force){
        UUID id=target.getUniqueId();String who=names.name(target);
        // 이미 대기 중이면 창만 다시 연다.
        if(firstState.pending(id)&&!force){pushFirst(target,0);ChacaApi.ok(sender,who+" 님에게 닉네임 창을 다시 열었어요");return;}
        if(!firstBusy.add(id)){ChacaApi.fail(sender,"처리 중이에요. 잠시 후 다시 해 주세요",null);return;}
        // 판단 기준은 캐시가 아니라 DB 행 (다른 서버에서 이미 정했을 수 있다). 조회는 IO 스레드에서 한다.
        try{io.execute(()->{
            NicknameStore.State loaded=null;boolean ok=false;
            try{if(force)store.reopenFirst(id);loaded=store.load(id);ok=true;}catch(Exception e){plugin.getLogger().warning("최초 닉네임 상태 읽기 실패 ("+id+"): "+e);}
            final var state=loaded;final boolean success=ok;
            main(()->{
                firstBusy.remove(id);
                if(success&&!busy.contains(id))apply(id,state);
                if(!target.isOnline()){ChacaApi.fail(sender,who+" 님이 나갔어요",null);return;}
                // DB를 읽지 못하면 마지막 캐시로 판단한다 (스토리 진행이 DB 순간 장애로 멈추지 않도록).
                var known=success?state:profiles.get(id);
                if(!force&&known!=null&&known.firstDone()){ChacaApi.fail(sender,who+" 님은 이미 최초 닉네임을 정했어요. 처음부터 다시 하려면 '다시정하기'",null);return;}
                startFirst(target);
                ChacaApi.ok(sender,who+" 님에게 "+(force?"닉네임 다시 정하기 창을":"최초 닉네임 창을")+" 열었어요");
            });
        });}catch(RejectedExecutionException e){firstBusy.remove(id);}
    }
    /** 관리자가 닉네임을 직접 정한다. 형식·중복 검사는 유저 저장과 같다 (DB UNIQUE 포함). */
    private void adminSet(org.bukkit.command.CommandSender sender,UUID id,String nickname)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        String key;
        try{NicknameProtocol.validate(nickname);key=NicknameStore.key(nickname);}catch(IllegalArgumentException e){throw ChacaApi.error(e.getMessage());}
        for(Player other:Bukkit.getOnlinePlayers())if(!other.getUniqueId().equals(id)&&other.getName().toLowerCase(Locale.ROOT).equals(key))throw ChacaApi.error("이미 사용 중인 닉네임이에요");
        if(busy.contains(id))throw ChacaApi.error("그 유저의 닉네임을 저장하는 중이에요. 잠시 후 다시 해 주세요");
        var offline=Bukkit.getOfflinePlayer(id);var cached=profiles.get(id);
        String account=offline.getName()!=null?offline.getName():cached!=null?cached.account():null;
        if(account==null||account.isBlank())throw ChacaApi.error("계정 이름을 알 수 없는 유저예요");
        busy.add(id);
        try{io.execute(()->{
            NicknameStore.Saved result=null;Exception failure=null;
            try{var now=store.load(id);result=store.save(id,account,nickname,now==null?0:now.revision());}catch(Exception e){failure=e;}
            final var saved=result;final var error=failure;
            main(()->{
                busy.remove(id);
                if(error!=null){ChacaApi.fail(sender,"닉네임을 저장하지 못했어요",error);return;}
                if(saved.state()!=null)profiles.put(id,saved.state());names.invalidate(id);
                switch(saved.outcome()){
                    case SAVED->{ChacaApi.ok(sender,"닉네임을 "+nickname+"(으)로 정했어요");plugin.getLogger().info("NICKNAME ADMIN "+sender.getName()+" set "+id+" -> "+nickname);}
                    case DUPLICATE->ChacaApi.fail(sender,"이미 사용 중인 닉네임이에요",null);
                    default->ChacaApi.fail(sender,"닉네임이 방금 바뀌었어요. 다시 해 주세요",null);
                }
            });
        });}catch(RejectedExecutionException e){busy.remove(id);throw ChacaApi.error("서버가 종료 중이에요");}
    }
    @EventHandler(ignoreCancelled=true) public void inspect(PlayerInteractEntityEvent e){
        if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||!e.getPlayer().isSneaking()||!(e.getRightClicked() instanceof Player target)||target.hasMetadata("NPC"))return;
        Player viewer=e.getPlayer();if(viewer==target||!viewer.canSee(target))return;
        var stats=StatsBridge.active;if(stats!=null){e.setCancelled(true);try{stats.open(viewer,target);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException ex){CodexCommands.warn(viewer,"stats-open",ex.getRawMessage().getString());}}
    }
    @EventHandler public void disabled(PluginDisableEvent e){if(e.getPlugin()==plugin)close();}
    @Override public void close(){
        if(closed)return;closed=true;sessions.clear();busy.clear();firstBusy.clear();for(var expansion:expansions)expansion.unregister();
        // 대기 중인 파일 기록을 마친 뒤 연결을 닫는다 (종료 시에만 잠깐 기다림).
        io.execute(()->{try{store.close();}catch(Exception ignored){}});io.shutdown();
        try{io.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
