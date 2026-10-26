package school.magiccodex.discovery;
import java.util.*;
import java.io.File;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.DiscoveryProtocol;
import school.magiccodex.database.DatabaseSettings;

public final class MagicDiscovery extends JavaPlugin implements Listener,PluginMessageListener {
    Definitions definitions;DiscoveryStore store;NativeConditions nativeConditions;ExpansionConditions expansionConditions;LifeConditions lifeConditions;TitleRewards rewards;
    final Map<UUID,Session> sessions=new HashMap<>();private DiscoveryService api;
    private record LearnedKey(UUID player,String spell){}
    private final Map<LearnedKey,Long> learnedVersions=new HashMap<>(),adminPending=new HashMap<>();
    private final LuckPermsGrant permissionWrites=new LuckPermsGrant();private final AdminSpellCatalog adminCatalog=new AdminSpellCatalog();private long nextLearnedVersion,prunedBelow;
    private boolean crossServer,recheckRunning;private long lastStorageLog;private int suppressedStorageLogs;
    private static final long[] LOAD_RETRY_TICKS={40,100,300};
    static final class Session {
        final UUID id;final Map<String,Double> progress=new HashMap<>();final Map<String,DiscoveryStore.Acquisition> acquired=new HashMap<>();final Set<String> pending=new HashSet<>();
        final LearnedSpells learned=new LearnedSpells();
        // 마지막 저장 이후 바뀐 키만 기록한다. 저장 실패 시 다시 채워져 다음 주기에 재시도된다.
        final Set<String> dirtyKeys=new HashSet<>();
        // inFlight: 지금 저장 중인 키(퇴장 시 결과를 못 받으므로 다시 쓴다). circleTouched: 로드 뒤 이 서버에서 state.circle을 받았는지.
        Set<String> inFlight=Set.of();boolean circleTouched;
        PermissionAttachment attachment;boolean loaded,saving,noticeReady;long nextHello;int acks;long ackWindow;String learnedSignature="";
        /** 배운 마법 수 (learned.<원소>) 캐시: 30초마다 또는 새로 배운 뒤에만 다시 센다 (2026-10-26 v2). */
        Map<String,Double> counts=Map.of();int countsTick=Integer.MIN_VALUE/2;
        Session(UUID id){this.id=id;}
    }
    static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Use server thread");}
    @Override public void onEnable(){
        try{
            saveDefaultConfig();if(!new File(getDataFolder(),"discoveries.yml").exists())saveResource("discoveries.yml",false);
            definitions=new Definitions(new File(getDataFolder(),"discoveries.yml"));
            if(!new File(getDataFolder(),"manual-conditions.yml").exists())saveResource("manual-conditions.yml",false);
            definitions.manual(new File(getDataFolder(),"manual-conditions.yml"));
            var databaseFile=getDataFolder().toPath().resolve("database.properties");var settings=DatabaseSettings.load(databaseFile);
            // 설정 파일이 없으면 조용히 SQLite가 되므로 시작할 때 한 줄로 남긴다.
            getLogger().info("DB 설정 파일 "+databaseFile.toAbsolutePath()+" : "+(java.nio.file.Files.exists(databaseFile)?"있음":"없음 → SQLite 기본값")+", 저장 모드="+(settings.mariaDb()?"MariaDB":"SQLite(discoveries.db)"));
            store=new DiscoveryStore(getDataFolder().toPath().resolve("discoveries.db"),settings);crossServer=settings.mariaDb();
            refreshAdminCatalog();rewards=new TitleRewards(this);nativeConditions=new NativeConditions(this);expansionConditions=new ExpansionConditions(this);lifeConditions=new LifeConditions(this);api=new DiscoveryService(this);
            Bukkit.getServicesManager().register(DiscoveryService.class,api,this,ServicePriority.Normal);
            Bukkit.getPluginManager().registerEvents(this,this);Bukkit.getPluginManager().registerEvents(nativeConditions,this);Bukkit.getPluginManager().registerEvents(expansionConditions,this);Bukkit.getPluginManager().registerEvents(lifeConditions,this);
            Bukkit.getMessenger().registerIncomingPluginChannel(this,DiscoveryProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(this,DiscoveryProtocol.RESPONSE);
            if(ChacaLink.ready()){try{new DiscoveryCommands(this).register();}catch(RuntimeException e){getLogger().log(java.util.logging.Level.WARNING,"명령어 허브에 마법 발견 명령을 붙이지 못했습니다",e);}}
            else getLogger().warning("MagicCodexBridge 명령어 허브가 없어 마법 발견 관리·테스트 명령을 등록하지 않았습니다 (발견 판정은 그대로 동작).");
            for(var p:Bukkit.getOnlinePlayers())load(p);
            long period=Math.clamp(getConfig().getInt("progress-save-seconds",5),1,60)*20L;
            Bukkit.getScheduler().runTaskTimer(this,()->{for(var s:List.copyOf(sessions.values())){save(s);if(s.loaded)checkMeta(s);}},period,period);
            Bukkit.getScheduler().runTaskTimer(this,this::minute,1200,1200);
            getLogger().info("마법 획득 조건 "+definitions.spells.size()+"종 준비. 신규 외부 시스템은 DiscoveryService/서버 명령어로 연결하세요.");
        }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"획득 시스템 시작 실패",e);Bukkit.getPluginManager().disablePlugin(this);}
    }
    private void load(Player p){var s=new Session(p.getUniqueId());sessions.put(s.id,s);load(p,s,0);}
    private void load(Player p,Session s,int attempt){
        store.load(s.id).whenComplete((data,error)->later(()->{
            if(sessions.get(s.id)!=s)return;
            if(error!=null){
                // 로드 실패로 세션이 영영 비어 있지 않도록 접속 중에는 2초/5초/15초(이후 15초) 간격으로 다시 읽는다.
                storageFailure("획득 데이터 로드 실패 — 접속 중 재시도합니다",error);
                if(p.isOnline())Bukkit.getScheduler().runTaskLater(this,()->{if(sessions.get(s.id)==s&&p.isOnline())load(p,s,attempt+1);},LOAD_RETRY_TICKS[Math.min(attempt,LOAD_RETRY_TICKS.length-1)]);
                return;
            }
            s.progress.putAll(data.progress());for(var a:data.acquired())s.acquired.put(a.spell(),a);s.learned.restore(data.learnedOverrides(),data.retryProgress());s.learnedSignature=data.learnedSignature();s.loaded=true;
            // Environmental states must be republished after reconnect; circle is persistent, temperature/regions are not.
            s.progress.put("state.mana_maximum",0d);s.progress.put("state.temperature",-100d);s.progress.put("state.flight_prohibited",0d);
            // 접속마다 주문 수만큼 권한을 쓰지 않도록 한 번에 모아 필요한 것만 반영한다.
            refreshAdminCatalog();var wanted=new LinkedHashMap<String,Boolean>();
            for(var spell:adminCatalog.permissionEntries().values())if(s.learned.learned(spell.id(),s.acquired.containsKey(spell.id()))||s.learned.retrying(spell.id()))wanted.put(spell.permission(),s.learned.learned(spell.id(),s.acquired.containsKey(spell.id())));
            for(String id:definitions.manual.keySet())wanted.put(Definitions.conditionPermission(id),s.progress.getOrDefault(Definitions.conditionKey(id),0d)>0);
            syncPermissions(s.id,wanted);
            nativeConditions.join(p);restoreServerState(p,s,0);checkMeta(s);if(s.noticeReady)sendPending(p,s);rewards.claim(p,s,false);
            // 마법 발견 칭호 (메모 #21): 예전에 발견한 마법·지급 도중 끊긴 칭호도 접속 때 맞춘다 (칭호 장부가 중복을 막음).
            discoveryTitles(s.id,s.acquired.keySet());
            // 서버 이동 직후에는 이전 서버의 마지막 저장이 아직 안 끝났을 수 있다. 몇 초 뒤 한 번 더 읽어 큰 값으로 합친다.
            if(crossServer)Bukkit.getScheduler().runTaskLater(this,()->{if(sessions.get(s.id)==s)refresh(s);},60);
        }));
    }
    /** Read already-loaded server snapshots once after discovery load; never query a DB or scan other players here. */
    private void restoreServerState(Player p,Session s,int attempt){
        if(sessions.get(s.id)!=s||!p.isOnline())return;
        var bridge=Bukkit.getPluginManager().getPlugin("MagicCodexBridge");if(bridge==null||!bridge.isEnabled())return;
        boolean manaReady=false;
        try{
            Class<?> type=Class.forName("school.magiccodex.paper.ManaService",false,bridge.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);
            if(service!=null){Object value=type.getMethod("snapshot",UUID.class).invoke(service,s.id);if(value instanceof Optional<?> optional&&optional.isPresent()){
                Object snapshot=optional.get();double maximum=((Number)snapshot.getClass().getMethod("maximum").invoke(snapshot)).doubleValue();state(s.id,"state.mana_maximum",maximum);manaReady=true;
            }}
        }catch(ReflectiveOperationException|RuntimeException error){getLogger().warning("마나 상태 연결 실패: "+error.getClass().getSimpleName());return;}
        if(!manaReady&&attempt<3)Bukkit.getScheduler().runTaskLater(this,()->restoreServerState(p,s,attempt+1),40);
    }
    private void refresh(Session s){
        store.load(s.id).whenComplete((data,error)->later(()->{
            if(sessions.get(s.id)!=s||!s.loaded)return;
            if(error!=null){storageFailure("획득 데이터 재확인 실패",error);return;}
            reconcile(s,data);
        }));
    }
    /** DB 스냅샷을 세션에 합친다: 카운터는 큰 값, 습득 상태는 다른 서버에서 바뀐 관리자 변경을 따른다. */
    private void reconcile(Session s,DiscoveryStore.Loaded data){
        data.progress().forEach((key,value)->{if(DiscoveryStore.monotonic(key)&&value>s.progress.getOrDefault(key,0d))s.progress.put(key,value);});
        // 클래스(state.circle)는 이 서버에서 아직 새 값을 받지 않았을 때만 이전 서버의 마지막 저장값을 따른다.
        Double circle=data.progress().get("state.circle");if(circle!=null&&!s.circleTouched&&!s.dirtyKeys.contains("state.circle"))s.progress.put("state.circle",circle);
        boolean added=false;for(var a:data.acquired())if(s.acquired.putIfAbsent(a.spell(),a)==null)added=true;
        var p=Bukkit.getPlayer(s.id);if(added&&p!=null){if(s.noticeReady)sendPending(p,s);rewards.claim(p,s,false);}var changes=new LinkedHashMap<String,Boolean>();boolean skipped=false;refreshAdminCatalog();
        for(var spell:adminCatalog.permissionEntries().values()){
            Boolean stored=data.learnedOverrides().get(spell.id());if(stored==null)continue;
            // 이 서버에서 진행 중인 획득·관리자 변경은 스냅샷보다 새로우므로 건드리지 않고 다음 주기에 다시 본다.
            if(s.pending.contains(spell.id())||adminPending.containsKey(new LearnedKey(s.id,spell.id()))){skipped=true;continue;}
            var baseline=stored?Map.<String,Double>of():data.retryProgress().getOrDefault(spell.id(),Map.of());
            if(s.learned.learned(spell.id(),s.acquired.containsKey(spell.id()))==stored&&(stored||s.learned.retrying(spell.id())&&s.learned.retryBaseline(spell.id()).equals(baseline)))continue;
            s.learned.set(spell.id(),stored,baseline);changes.put(spell.permission(),stored);
            if(!stored&&p!=null)nativeConditions.restart(p,spell.id());
        }
        for(String id:definitions.manual.keySet()){
            if(adminPending.containsKey(new LearnedKey(s.id,id))){skipped=true;continue;}
            String key=Definitions.conditionKey(id);double value=data.progress().getOrDefault(key,0d);
            if(s.progress.getOrDefault(key,0d)!=value){s.progress.put(key,value);changes.put(Definitions.conditionPermission(id),value>0);}
        }
        s.learnedSignature=skipped?null:data.learnedSignature();
        if(!changes.isEmpty()){s.countsTick=Integer.MIN_VALUE/2;syncPermissions(s.id,changes);checkMeta(s);}
    }
    /** 1분마다: 오래된 버전 기록 정리 + (MariaDB) 접속자 전체의 습득 상태 요약값을 한 번에 조회해 달라진 사람만 다시 읽는다. */
    private void minute(){
        long cutoff=prunedBelow;prunedBelow=nextLearnedVersion;
        learnedVersions.entrySet().removeIf(e->e.getValue()<=cutoff&&!sessions.containsKey(e.getKey().player())&&!adminPending.containsKey(e.getKey()));
        if(!crossServer||recheckRunning)return;
        var ids=new ArrayList<UUID>();for(var s:sessions.values())if(s.loaded)ids.add(s.id);if(ids.isEmpty())return;
        recheckRunning=true;
        store.learnedSignatures(ids).whenComplete((signatures,error)->later(()->{
            recheckRunning=false;if(error!=null){storageFailure("습득 상태 재확인 실패",error);return;}
            for(UUID id:ids){var s=sessions.get(id);if(s!=null&&s.loaded&&(!signatures.getOrDefault(id,"").equals(s.learnedSignature)||!definitions.manual.isEmpty()))refresh(s);}
        }));
    }
    void later(Runnable task){if(isEnabled())Bukkit.getScheduler().runTask(this,task);}
    void failure(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"획득 데이터 처리 실패 — 지급 완료로 처리하지 않습니다.",error);}
    /** DB 장애 중 반복되는 저장·로드 실패는 30초에 한 번만 남긴다. 어느 스레드에서든 호출 가능. */
    synchronized void storageFailure(String what,Throwable error){
        long now=System.nanoTime()/1_000_000;if(lastStorageLog!=0&&now-lastStorageLog<30_000){suppressedStorageLogs++;return;}
        Throwable cause=error;while(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();
        String line=what+(suppressedStorageLogs>0?" (그 사이 저장소 오류 "+suppressedStorageLogs+"건 생략)":"");
        // 연결 장애(SQLException)는 원인 한 줄만, 그 밖의 예외는 버그일 수 있으므로 스택까지 남긴다.
        if(cause instanceof java.sql.SQLException)getLogger().warning(line+": "+cause);else getLogger().log(java.util.logging.Level.WARNING,line,cause);
        lastStorageLog=now==0?1:now;suppressedStorageLogs=0;
    }
    /** 그 키를 세는 (수동 목록이 아닌) 마법이 있는지. 무거운 확인 전에 먼저 본다. */
    boolean counting(String key){return definitions.index.containsKey(key);}
    /** 판정에 쓸 값: learned.* 조건이 있는 마법이면 진행도에 배운 마법 수를 더한 사본, 아니면 진행도 그대로. */
    private Map<String,Double> values(Player p,Session s,Definitions.Spell spell){
        if(!definitions.hasCounts(spell))return s.progress;
        int now=Bukkit.getCurrentTick();
        if(now-s.countsTick>=600){
            var categories=new HashMap<String,String>();for(var sp:definitions.spells.values())categories.put(sp.id(),sp.category());
            var perms=new HashMap<String,String>();for(var sp:definitions.spells.values())perms.put(sp.id(),sp.permission());
            s.counts=Map.copyOf(ConditionRules.learnedCounts(categories,id->p.hasPermission(perms.get(id))));s.countsTick=now;
        }
        var view=new HashMap<>(s.progress);view.putAll(s.counts);return view;
    }
    Session session(Player p){var s=sessions.get(p.getUniqueId());return s!=null&&s.loaded?s:null;}
    boolean eligible(Player p){return p!=null&&p.isOnline()&&!p.isDead()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);}
    boolean needed(Player p,String id){var s=session(p);var spell=definitions.spells.get(id);return s!=null&&spell!=null&&!Definitions.PENDING.contains(id)&&!definitions.manual.containsKey(id)&&!s.learned.learned(id,s.acquired.containsKey(id))&&!s.pending.contains(id)&&!adminPending.containsKey(new LearnedKey(s.id,id))&&(s.learned.retrying(id)||!p.hasPermission(spell.permission()));}
    Map<String,String> registeredSpells(){main();refreshAdminCatalog();return adminCatalog.names();}
    private void refreshAdminCatalog(){
        Map<?,?> casts=null;var bridge=Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if(bridge!=null&&bridge.isEnabled())try{
            Object catalog=bridge.getClass().getMethod("registeredSpellCatalog").invoke(bridge);
            if(catalog instanceof Map<?,?> values)casts=values;
        }catch(NoSuchMethodException ignored){
            // Older bridge versions still support the original natural-discovery catalog.
        }catch(ReflectiveOperationException|RuntimeException error){failure(error);return;}
        adminCatalog.replace(definitions.spells,casts);
    }
    void adminSetLearned(UUID id,String spellId,boolean learned,Consumer<String> completion){
        main();Objects.requireNonNull(completion,"completion");refreshAdminCatalog();var spell=adminCatalog.get(spellId);
        if(id==null||spell==null){completion.accept("등록된 플레이어 UUID와 마법 ID를 확인해 주세요.");return;}
        if(!isEnabled()||store==null){completion.accept("마법 발견 시스템이 준비되지 않았습니다.");return;}
        var key=new LearnedKey(id,spellId);long version=++nextLearnedVersion;learnedVersions.put(key,version);adminPending.put(key,version);
        Set<String> required=definitions.spells.containsKey(spellId)?definitions.spells.get(spellId).requirements().keySet():Set.of();
        var current=sessions.get(id);var progress=current!=null&&current.loaded?durable(current,required):Map.<String,Double>of();
        store.setLearned(id,spellId,learned,required,progress).whenComplete((baseline,error)->later(()->{
            if(error!=null){failure(error);recoverAdminFailure(key,version,spell,completion);return;}
            if(!Objects.equals(learnedVersions.get(key),version)){completion.accept("더 최근의 관리자 변경이 적용되어 이 요청은 대체되었습니다.");return;}
            adminPending.remove(key,version);var session=sessions.get(id);if(session!=null){session.learned.set(spellId,learned,baseline);session.countsTick=Integer.MIN_VALUE/2;var player=Bukkit.getPlayer(id);if(!learned&&session.loaded&&player!=null)nativeConditions.restart(player,spellId);}
            syncPermission(id,spell.permission(),learned).whenComplete((ok,permissionError)->later(()->{
                if(permissionError!=null){failure(permissionError);completion.accept("습득 상태는 저장했지만 권한 동기화에 실패했습니다. 재접속하거나 다시 시도해 주세요.");return;}
                if(!Objects.equals(learnedVersions.get(key),version)){completion.accept("더 최근의 관리자 변경이 적용되어 이 요청은 대체되었습니다.");return;}
                audit(id,(learned?"마법 배우기(관리자·지급) · ":"마법 잊기(관리자) · ")+spell.name(),"마법",spellId,"이름",spell.name(),"경로",learned?"관리자배우기":"관리자잊기");
                completion.accept(null);
                var latest=sessions.get(id);if(learned&&latest!=null&&latest.loaded&&Objects.equals(learnedVersions.get(key),version))checkMeta(latest,true);
            }));
        }));
    }
    private void recoverAdminFailure(LearnedKey key,long version,AdminSpellCatalog.Entry spell,Consumer<String> completion){
        if(!Objects.equals(learnedVersions.get(key),version)){completion.accept("습득 상태를 저장하지 못했습니다. 서버 로그를 확인해 주세요.");return;}
        // An earlier acquisition may have committed while this failed admin request was pending.
        store.load(key.player()).whenComplete((data,error)->later(()->{
            if(Objects.equals(learnedVersions.get(key),version)){
                adminPending.remove(key,version);
                if(error==null){var session=sessions.get(key.player());var acquisition=data.acquired().stream().filter(a->a.spell().equals(key.spell())).findFirst();boolean learned=data.learnedOverrides().getOrDefault(key.spell(),acquisition.isPresent());
                    if(session!=null){acquisition.ifPresent(a->session.acquired.putIfAbsent(a.spell(),a));session.learned.set(key.spell(),learned,data.retryProgress().getOrDefault(key.spell(),Map.of()));}syncPermission(key.player(),spell.permission(),learned);
                }else failure(error);
            }
            completion.accept("습득 상태를 저장하지 못했습니다. 서버 로그를 확인한 뒤 다시 시도해 주세요.");
        }));
    }
    void adminCondition(UUID id,String spellId,String action,Consumer<String> done){
        main();var spell=definitions.manual.get(spellId);var s=sessions.get(id);var p=Bukkit.getPlayer(id);
        if(spell==null){done.accept("수동 대체 목록에 없는 마법입니다.");return;}
        if(s==null||!s.loaded||p==null){done.accept("접속 중이며 데이터 로딩이 끝난 유저가 필요합니다.");return;}
        String progressKey=Definitions.conditionKey(spellId),node=Definitions.conditionPermission(spellId);
        if(action.equals("조건조회")){done.accept(node+" = "+(s.progress.getOrDefault(progressKey,0d)>0)+" / 습득="+s.learned.learned(spellId,s.acquired.containsKey(spellId)));return;}
        if(!Set.of("조건지급","조건회수","조건초기화").contains(action)){done.accept("조건지급/조건회수/조건초기화/조건조회 중 선택하세요.");return;}
        var key=new LearnedKey(id,spellId);
        if(s.pending.contains(spellId)||adminPending.containsKey(key)){done.accept("해당 마법의 변경을 처리 중입니다.");return;}
        long version=++nextLearnedVersion;learnedVersions.put(key,version);adminPending.put(key,version);
        boolean grant=action.equals("조건지급"),reset=action.equals("조건초기화");
        CompletableFuture<DiscoveryStore.Award> operation=grant
            ?store.grantCondition(id,spellId,getConfig().getString("title-scope","server-first").equals("player-first"))
            :store.revokeCondition(id,spellId,reset).thenApply(ignored->null);
        operation.whenComplete((award,error)->later(()->{
            if(!Objects.equals(learnedVersions.get(key),version)){done.accept("더 최근 관리자 변경이 우선합니다.");return;}
            adminPending.remove(key,version);
            if(error!=null){storageFailure("수동 조건 저장 실패",error);done.accept("저장 실패: 조건 지급으로 처리하지 않았습니다. 다시 시도하세요.");return;}
            var current=sessions.get(id);var online=Bukkit.getPlayer(id);
            if(current!=null){current.countsTick=Integer.MIN_VALUE/2;current.progress.put(progressKey,grant?1d:0d);if(grant){if(award.acquisition()!=null)current.acquired.putIfAbsent(spellId,award.acquisition());current.learned.set(spellId,true,Map.of());}else if(reset)current.learned.set(spellId,false,Map.of());}
            var nodes=new LinkedHashMap<String,Boolean>();nodes.put(node,grant);if(grant||reset)nodes.put(spell.permission(),grant);
            syncPermissions(id,nodes).whenComplete((ignored,permissionError)->later(()->{
                if(permissionError!=null){done.accept("DB 저장 완료, 권한 동기화 실패. 재접속 또는 동일 명령으로 복구하세요.");return;}
                if(!Objects.equals(learnedVersions.get(key),version)){done.accept("더 최근 관리자 변경이 우선합니다.");return;}
                if(grant&&award.created()&&online!=null&&online.isOnline()&&current!=null){if(current.noticeReady)sendPending(online,current);else ChacaLink.notify(online,"shop","discovery:"+spellId,"새로운 마법 발견",spell.name(),0);rewards.claim(online,current,false);discoveryFirst(id,spellId);}
                if(grant&&award.acquisition()!=null)discoveryTitles(id,List.of(spellId));
                if(grant&&current!=null)checkMeta(current,true);
                audit(id,"발견 조건 "+action+" · "+spell.name(),"마법",spellId,"이름",spell.name(),"경로",action,"권한",node,"새발견",grant&&award.created()?"예":"아니오");
                done.accept(action+" 완료: "+spell.name()+" ("+node+")"+(grant&&!award.created()?" · 기존 발견 기록 유지, 알림·최초보상 추가 없음":""));
            }));
        }));
    }
    public boolean signal(UUID id,String key,double amount){
        main();if(!definitions.index.containsKey(key)||key.startsWith("state.")||!Double.isFinite(amount)||amount<=0||amount>1e9)return false;
        var s=sessions.get(id);var p=Bukkit.getPlayer(id);boolean death=key.startsWith("death.")&&p!=null&&p.isOnline()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);if(s==null||!s.loaded||!eligible(p)&&!death)return false;
        if(definitions.index.get(key).stream().noneMatch(sp->needed(p,sp.id())))return true;
        double cap=definitions.index.get(key).stream().mapToDouble(sp->sp.requirements().get(key)+s.learned.baseline(sp.id(),key)).max().orElse(1e9);
        double before=s.progress.getOrDefault(key,0d),after=Math.max(before,Math.min(cap,before+amount));s.progress.put(key,after);if(after!=before)s.dirtyKeys.add(key);
        for(var spell:definitions.index.get(key))if(s.learned.meets(spell,values(p,s,spell))&&spell.prerequisites().stream().allMatch(p::hasPermission)){
            // 서버 통계 #96: 조건은 채웠지만 확률(chance)에서 떨어진 시도 = "발견시도실패:<마법>", 통과 = acquire
            if(ThreadLocalRandom.current().nextDouble()<spell.chance())acquire(p,s,spell);else if(needed(p,spell.id()))ChacaLink.stat(id,"발견시도실패:"+spell.id(),1);}
        return true;
    }
    public boolean state(UUID id,String key,double value){return state(id,key,value,false);}
    boolean state(UUID id,String key,double value,boolean verifiedAction){
        main();if(!Set.of("state.circle","state.temperature","state.flight_prohibited","state.mana_maximum","state.friends","state.travel_waypoints").contains(key)||!Double.isFinite(value)||Math.abs(value)>1e6)return false;
        if(key.equals("state.circle")&&(value<1||value>9||value!=Math.floor(value)))return false;
        var s=sessions.get(id);if(s==null||!s.loaded)return false;Double previous=s.progress.put(key,value);if(key.equals("state.circle")){s.circleTouched=true;if(!Objects.equals(previous,value))s.dirtyKeys.add(key);}var p=Bukkit.getPlayer(id);
        if(p!=null)for(var spell:definitions.index.getOrDefault(key,List.of()))if(s.learned.meets(spell,values(p,s,spell),verifiedAction))acquire(p,s,spell);return true;
    }
    public void cast(UUID id,String spell,double spent){
        main();var s=sessions.get(id);var p=Bukkit.getPlayer(id);if(s==null||!s.loaded||!eligible(p)||spell==null||!spell.matches("[a-z0-9_-]{1,64}")||!Double.isFinite(spent)||spent<0)return;
        signal(id,"cast."+spell,1);if(spent>0)signal(id,"mana."+spell,spent);
        // 2026-10-26 v2 홍염 나선 "화염 마법 300번 사용": 성공한 시전 1번 = 그 마법 원소(discoveries.yml category) 1번
        var known=definitions.spells.get(spell);if(known!=null&&ConditionRules.ELEMENTS.contains(known.category()))signal(id,"cast.element."+known.category(),1);
        if(spell.equals("pressure_press")&&s.progress.getOrDefault("state.circle",0d)>=6)signal(id,"cast.pressure_press.circle6",1);
        if(spell.equals("flight")&&s.progress.getOrDefault("state.flight_prohibited",0d)>0)signal(id,"cast.flight.prohibited",1);
        if(spell.equals("light_leap")&&s.progress.getOrDefault("state.temperature",-100d)>=30)nativeConditions.armHotRotation(p);
        // A fresh verified action permits retrying already-satisfied state/meta conditions, even at circle 9.
        for(var definition:definitions.stateOnly)if(s.learned.retrying(definition.id())&&s.learned.meets(definition,s.progress,true))acquire(p,s,definition);
        checkMeta(s,true);
    }
    private void checkMeta(Session s){checkMeta(s,false);}
    private void checkMeta(Session s,boolean verifiedAction){var p=Bukkit.getPlayer(s.id);if(!eligible(p))return;
        for(var spell:definitions.meta)if(!spell.prerequisites().isEmpty()&&s.learned.canCheckMeta(spell,verifiedAction)&&spell.prerequisites().stream().allMatch(p::hasPermission))acquire(p,s,spell);
        // 2026-10-26 v2: "<원소> 마법 N종 습득" (learned.*). 아직 필요한 사람만 세고, 관리자가 잊게 한 마법은 실제 행동(시전·습득) 뒤에만 다시 본다.
        for(var spell:definitions.counted)if(s.learned.canCheckMeta(spell,verifiedAction)&&needed(p,spell.id())&&spell.prerequisites().stream().allMatch(p::hasPermission)&&s.learned.meets(spell,values(p,s,spell),verifiedAction))acquire(p,s,spell);
    }
    private void acquire(Player p,Session s,Definitions.Spell spell){
        if(!needed(p,spell.id())||!spell.prerequisites().stream().allMatch(p::hasPermission))return;s.pending.add(spell.id());var key=new LearnedKey(s.id,spell.id());long version=learnedVersions.getOrDefault(key,0L);
        store.acquire(s.id,spell.id(),durable(s,spell.requirements().keySet()),getConfig().getString("title-scope","server-first").equals("player-first")).whenComplete((award,error)->later(()->{
            s.pending.remove(spell.id());if(error!=null){storageFailure("마법 획득 저장 실패 — 획득 처리하지 않았고 조건을 다시 충족하면 재시도합니다",error);return;}if(sessions.get(s.id)!=s)return;
            s.acquired.putIfAbsent(spell.id(),award.acquisition());
            if(learnedVersions.getOrDefault(key,0L)!=version)return;
            s.learned.set(spell.id(),true,Map.of());syncPermission(s.id,spell.permission(),true);s.countsTick=Integer.MIN_VALUE/2;
            audit(s.id,(award.created()?"마법 발견 · ":"마법 다시 습득 · ")+spell.name(),"마법",spell.id(),"이름",spell.name(),"경로",award.created()?"발견":"다시습득","조건",String.join(",",spell.requirements().keySet()));
            if(award.created()){ChacaLink.stat(s.id,"마법발견",1);if(s.noticeReady)sendPending(p,s);else ChacaLink.notify(p,"shop","discovery:"+spell.id(),"새로운 마법 발견",spell.name(),0);rewards.claim(p,s,false);discoveryFirst(s.id,spell.id());}
            else ChacaLink.notify(p,"system","discovery-relearn:"+spell.id(),"마법을 다시 습득했어요",spell.name(),0);
            discoveryTitles(s.id,List.of(spell.id()));
            checkMeta(s,true);
        }));
    }
    /** 운영 기록 (메모 #92): 마법 종류 한 줄. MagicCodexBridge 가 없으면 조용히 건너뛴다. */
    private static void audit(UUID id,String summary,String... kv){
        var detail=new LinkedHashMap<String,String>();for(int i=0;i+1<kv.length;i+=2)if(kv[i+1]!=null)detail.put(kv[i],kv[i+1]);
        ChacaLink.audit(id,"마법",summary,detail);
    }
    /** MagicCodexBridge.discoveryTitles(UUID,Collection): 발견한 마법의 칭호 (title-awards.yml). 브리지가 없거나 옛 버전이면 조용히 건너뛴다. */
    private void discoveryTitles(UUID id,Collection<String> spells){
        if(spells.isEmpty())return;var bridge=Bukkit.getPluginManager().getPlugin("MagicCodexBridge");if(bridge==null||!bridge.isEnabled())return;
        try{bridge.getClass().getMethod("discoveryTitles",UUID.class,Collection.class).invoke(bridge,id,List.copyOf(spells));}
        catch(NoSuchMethodException old){/* 칭호 획득 경로가 없는 옛 MagicCodexBridge */}
        catch(ReflectiveOperationException|RuntimeException error){getLogger().warning("마법 발견 칭호 연결 실패: "+error.getClass().getSimpleName());}
    }
    /** MagicCodexBridge.discoveryFirst(UUID,String): 처음 발견한 마법 하나 (아티팩트 반응 일지 경험치). 옛 브리지면 조용히 건너뛴다. */
    private void discoveryFirst(UUID id,String spell){
        var bridge=Bukkit.getPluginManager().getPlugin("MagicCodexBridge");if(bridge==null||!bridge.isEnabled())return;
        try{bridge.getClass().getMethod("discoveryFirst",UUID.class,String.class).invoke(bridge,id,spell);}
        catch(NoSuchMethodException old){/* 반응 일지가 없는 옛 MagicCodexBridge */}
        catch(ReflectiveOperationException|RuntimeException error){getLogger().warning("반응 일지 연결 실패: "+error.getClass().getSimpleName());}
    }
    private CompletableFuture<Void> syncPermission(UUID id,String permission,boolean learned){return syncPermissions(id,Map.of(permission,learned));}
    /** 여러 권한을 한 번에 맞춘다: 이미 같은 값인 첨부 쓰기는 생략하고, LuckPerms는 바뀔 노드가 있을 때만 플레이어당 한 번 저장한다. */
    private CompletableFuture<Void> syncPermissions(UUID id,Map<String,Boolean> wanted){
        if(wanted.isEmpty())return CompletableFuture.completedFuture(null);
        var p=Bukkit.getPlayer(id);var s=sessions.get(id);
        if(p!=null&&p.isOnline()&&s!=null)for(var e:wanted.entrySet()){
            // setPermission은 매번 권한 재계산을 일으킨다. 첨부 값 또는 실제 권한이 이미 원하는 값이면 쓰지 않는다.
            Boolean attached=s.attachment==null?null:s.attachment.getPermissions().get(e.getKey());
            if(attached!=null?attached.booleanValue()==e.getValue().booleanValue():p.hasPermission(e.getKey())==e.getValue().booleanValue())continue;
            if(s.attachment==null)s.attachment=p.addAttachment(this);s.attachment.setPermission(e.getKey(),e.getValue());
        }
        if(Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))try{
            var result=permissionWrites.set(this,id,wanted);result.exceptionally(error->{failure(error);return null;});return result;
        }catch(LinkageError|RuntimeException error){failure(error);return CompletableFuture.failedFuture(error);}
        return CompletableFuture.completedFuture(null);
    }
    /** 획득·관리자 트랜잭션에 함께 쓸 진행도: 카운터 전부 + 바뀐 키 + 해당 주문의 조건 키. 바뀌지 않은 상태 값(다른 서버가 더 새로울 수 있음)은 덮어쓰지 않는다. */
    private Map<String,Double> durable(Session s,Set<String> required){var values=new HashMap<String,Double>();s.progress.forEach((key,value)->{if(DiscoveryStore.monotonic(key)||s.dirtyKeys.contains(key)||required.contains(key))values.put(key,value);});return values;}
    private Map<String,Double> takeDirty(Session s){var values=new HashMap<String,Double>();for(String key:s.dirtyKeys){Double value=s.progress.get(key);if(value!=null)values.put(key,value);}s.dirtyKeys.clear();return values;}
    private void save(Session s){
        if(!s.loaded||s.dirtyKeys.isEmpty()||s.saving)return;var values=takeDirty(s);s.saving=true;s.inFlight=Set.copyOf(values.keySet());
        store.save(s.id,values).whenComplete((ok,error)->later(()->{s.saving=false;s.inFlight=Set.of();if(error!=null){s.dirtyKeys.addAll(values.keySet());storageFailure("진행도 저장 실패 — 다음 주기에 다시 저장합니다",error);}}));
    }
    /** 세션이 사라진 뒤(퇴장·종료)의 마지막 저장. 저장 중이던 키도 결과를 알 수 없으므로 함께 다시 쓴다(병합 저장이라 중복 기록은 안전). */
    private Map<String,Double> takeFinal(Session s){if(s.saving)s.dirtyKeys.addAll(s.inFlight);return takeDirty(s);}
    /** 퇴장 저장 실패는 5초/10초/15초 뒤 최대 3번 다시 시도한다. 재시도는 큰 값 병합이 되는 카운터만 쓴다(그 사이 새로 바뀐 상태 값을 덮지 않도록). */
    private void finalSave(UUID id,Map<String,Double> values,int attempt){
        if(values.isEmpty())return;
        store.save(id,values).whenComplete((ok,error)->{
            if(error==null)return;storageFailure("퇴장 시 진행도 저장 실패"+(attempt<3?" — 잠시 뒤 재시도합니다":""),error);if(attempt>=3)return;
            var counters=new HashMap<String,Double>();values.forEach((key,value)->{if(DiscoveryStore.monotonic(key))counters.put(key,value);});
            later(()->Bukkit.getScheduler().runTaskLater(this,()->finalSave(id,counters,attempt+1),100L*(attempt+1)));
        });
    }
    void sendPending(Player p,Session s){if(!p.getListeningPluginChannels().contains(DiscoveryProtocol.RESPONSE))return;
        s.acquired.values().stream().filter(a->!a.notified()&&s.learned.learned(a.spell(),true)).sorted(Comparator.comparingLong(DiscoveryStore.Acquisition::token)).limit(8).forEach(a->{var spell=definitions.spells.get(a.spell());if(spell!=null)p.sendPluginMessage(this,DiscoveryProtocol.RESPONSE,DiscoveryProtocol.encode(new DiscoveryProtocol.Notice(a.token(),spell.id(),spell.name(),spell.icon(),a.first())));});
    }
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] data){
        if(!channel.equals(DiscoveryProtocol.REQUEST))return;long token;try{token=DiscoveryProtocol.request(data);}catch(IllegalArgumentException e){return;}
        var s=sessions.get(p.getUniqueId());if(s==null)return;long now=System.nanoTime()/1_000_000;
        if(token==0){if(now<s.nextHello)return;s.nextHello=now+5000;s.noticeReady=true;if(s.loaded)sendPending(p,s);return;}
        if(now-s.ackWindow>=1000){s.ackWindow=now;s.acks=0;}if(++s.acks>8)return;
        var match=s.acquired.values().stream().filter(a->a.token()==token&&!a.notified()).findFirst().orElse(null);if(match==null)return;
        store.acknowledge(s.id,token).whenComplete((ok,error)->later(()->{if(error!=null){failure(error);return;}var latest=s.acquired.get(match.spell());s.acquired.put(match.spell(),new DiscoveryStore.Acquisition(match.token(),match.spell(),match.first(),latest.reward(),true));}));
    }
    @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer());}
    @EventHandler public void respawn(PlayerRespawnEvent e){later(()->{var p=e.getPlayer();var s=session(p);if(s!=null)rewards.claim(p,s,false);});}
    @EventHandler public void quit(PlayerQuitEvent e){var s=sessions.remove(e.getPlayer().getUniqueId());if(s!=null&&s.loaded)finalSave(s.id,takeFinal(s),0);nativeConditions.quit(e.getPlayer());if(expansionConditions!=null)expansionConditions.quit(e.getPlayer());if(lifeConditions!=null)lifeConditions.quit(e.getPlayer());}
    /** 기존 /칭호수령: 받을 수 있는 마법 발견 칭호 아이템을 지급한다. MagicCodexBridge의 /칭호 수령이 리플렉션으로 부른다(메인 스레드). */
    public void claimTitles(Player p){
        if(p==null||!p.isOnline())return;
        var s=session(p);
        if(s==null){ChacaLink.notify(p,"warning","title-reward-loading","마법 발견 정보를 아직 불러오는 중이에요","잠시 뒤 다시 시도해 주세요",2000);return;}
        rewards.claim(p,s,true);
    }

    /** /서버관리 새로고침 마법발견: discoveries.yml·manual-conditions.yml·titles.yml·config.yml. 실패하면 예외(이전 설정 유지). */
    void reloadDefinitions()throws Exception{
        main();
        var next=new Definitions(new File(getDataFolder(),"discoveries.yml"));
        next.manual(new File(getDataFolder(),"manual-conditions.yml"));
        rewards.reload();
        reloadConfig();
        definitions=next;refreshAdminCatalog();
    }

    /** 진행도 키 후보 (자동완성). */
    List<String> progressKeys(){
        var out=new TreeSet<String>(definitions.index.keySet());
        for(String id:definitions.manual.keySet())out.add(Definitions.conditionKey(id));
        out.addAll(List.of("state.circle","state.temperature","state.flight_prohibited","state.mana_maximum","state.friends","state.travel_waypoints"));
        return List.copyOf(out);
    }

    /**
     * 마법 힌트(3성)용: 그 마법 조건 키마다 지금 값 (다시 배우는 중이면 기준값을 뺀 값). 메인 스레드에서 부르고 done 도 메인 스레드.
     * 접속·로드된 유저는 세션 값으로 바로, 아니면 DB 를 비동기로 읽는다 (그때 learned.* 는 권한으로 셀 수 없어 빠짐).
     * done(null) = 모르는 마법이거나 읽기 실패.
     */
    void hintProgress(UUID id,String spellId,Consumer<Map<String,Double>> done){
        main();var spell=definitions==null?null:definitions.spells.get(spellId);if(spell==null){done.accept(null);return;}
        var s=sessions.get(id);var p=Bukkit.getPlayer(id);
        if(s!=null&&s.loaded&&p!=null){done.accept(hintValues(spell,s.learned,values(p,s,spell)));return;}
        if(store==null){done.accept(null);return;}
        store.load(id).whenComplete((data,error)->later(()->{
            if(error!=null||data==null){done.accept(null);return;}
            var learned=new LearnedSpells();learned.restore(data.learnedOverrides(),data.retryProgress());
            var out=new LinkedHashMap<>(hintValues(spell,learned,data.progress()));out.keySet().removeIf(ConditionRules::learnedKey);done.accept(Map.copyOf(out));
        }));
    }
    private static Map<String,Double> hintValues(Definitions.Spell spell,LearnedSpells learned,Map<String,Double> values){
        var out=new LinkedHashMap<String,Double>();
        for(String key:spell.requirements().keySet()){double v=values.getOrDefault(key,0d);if(!ConditionRules.stateLike(key))v=Math.max(0,v-learned.baseline(spell.id(),key));out.put(key,v);}
        return Map.copyOf(out);
    }

    /** 접속 중이고 데이터가 로드된 유저의 진행도 (없으면 null). */
    Map<String,Double> loadedProgress(UUID id){var s=sessions.get(id);return s==null||!s.loaded?null:Map.copyOf(s.progress);}

    /** 진행도 보기: 접속 중이면 세션 값, 아니면 DB. done(map) 은 메인 스레드. */
    void adminProgress(UUID id,Consumer<Map<String,Double>> done,Consumer<Throwable> failed){
        main();var live=loadedProgress(id);if(live!=null){done.accept(live);return;}
        store.load(id).whenComplete((data,error)->later(()->{if(error!=null)failed.accept(error);else done.accept(data.progress());}));
    }

    /**
     * 진행도 설정 (관리자). 카운터를 낮추는 것도 그대로 덮어쓴다. 접속 중이면 세션에도 반영하고, 값을 올려 조건을 채우면 획득 판정을 한다.
     * state.* 키는 상태 이벤트(state)로 처리한다. done(null)=성공, 그 밖은 유저용 문구.
     */
    void adminSetProgress(UUID id,String key,double value,Consumer<String> done,Consumer<Throwable> failed){
        main();
        if(!key.matches("[a-z0-9_.-]{1,128}")||!Double.isFinite(value)||Math.abs(value)>1e9){done.accept("키 또는 값이 올바르지 않아요");return;}
        var s=sessions.get(id);boolean live=s!=null&&s.loaded;
        if(key.startsWith("state.")){
            if(!live){done.accept("상태 값은 이 서버에 접속 중인 유저만 바꿀 수 있어요");return;}
            done.accept(state(id,key,value,true)?null:"상태 키 또는 값이 올바르지 않아요 (state.circle은 1~9)");return;
        }
        if(live){s.progress.put(key,value);s.dirtyKeys.remove(key);}
        store.overwrite(id,key,value).whenComplete((ok,error)->later(()->{
            if(error!=null){failed.accept(error);return;}
            var current=sessions.get(id);var p=Bukkit.getPlayer(id);
            if(current!=null&&current.loaded&&p!=null)for(var spell:definitions.index.getOrDefault(key,List.of()))
                if(current.learned.meets(spell,values(p,current,spell))&&spell.prerequisites().stream().allMatch(p::hasPermission))acquire(p,current,spell);
            done.accept(null);
        }));
    }

    void adminReserved(CommandSender sender,UUID id,String name){rewards.adminReserved(sender,id,name);}
    void adminResolve(CommandSender sender,UUID id,String name,long token,boolean claimable)throws com.mojang.brigadier.exceptions.CommandSyntaxException{rewards.adminResolve(sender,id,name,token,claimable);}
    void configureTitle(Player p,String id)throws Exception{rewards.configure(p,id);}
    Set<String> spellIds(){return definitions.spells.keySet();}
    String spellName(String id){var spell=definitions.spells.get(id);return spell==null?id:spell.name();}
    /** 이벤트 키 후보 (state.* 제외). */
    List<String> eventKeys(){return definitions.index.keySet().stream().filter(key->!key.startsWith("state.")).sorted().toList();}

    @Override public void onDisable(){
        if(nativeConditions!=null)nativeConditions.close();if(expansionConditions!=null)expansionConditions.close();if(lifeConditions!=null)lifeConditions.close();Bukkit.getScheduler().cancelTasks(this);Bukkit.getServicesManager().unregisterAll(this);
        for(var s:sessions.values()){if(store!=null&&s.loaded)store.save(s.id,takeFinal(s)).exceptionally(error->{storageFailure("종료 시 진행도 저장 실패",error);return null;});if(s.attachment!=null){var p=Bukkit.getPlayer(s.id);if(p!=null)p.removeAttachment(s.attachment);}}
        sessions.clear();if(store!=null)try{store.close();}catch(Exception e){failure(e);}
    }
}
