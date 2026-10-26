package school.magiccodex.paper;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.QuestProtocol;
import school.magiccodex.protocol.QuestAdminProtocol;
import static school.magiccodex.paper.QuestDefinition.*;

final class QuestBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;private final File file;private final QuestStore store;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-quest-io");t.setDaemon(true);return t;});
    private Map<String,QuestDefinition> catalog=Map.of();private final Map<UUID,List<QuestStore.Entry>> cache=new HashMap<>();
    private final Map<UUID,Set<String>> completed=new HashMap<>();
    private final Set<UUID> busy=new HashSet<>();private final Map<UUID,Long> limits=new HashMap<>(),viewers=new HashMap<>();
    private final Map<UUID,Integer> tabs=new HashMap<>();private final Map<UUID,Integer> pages=new HashMap<>();private String server;private int pending;private volatile boolean closing;private boolean refreshing;
    /** 공용 카탈로그의 변경 번호(codex_quest_lock 의 catalog 행). 바뀐 때에만 정의 전체를 다시 읽는다. */
    private long catalogRevision=-1;
    /** 처치·이벤트 진행도는 메모리에 모았다가 FLUSH_TICKS 마다(또는 퇴장·종료·보상 수령·목록 열기 때) 유저당 한 트랜잭션으로 기록한다. 버리지 않는다. */
    private static final long FLUSH_TICKS=60;
    private record DeltaKey(Type type,String target,String world){}
    private static final class Batch {final Map<DeltaKey,Integer> counts=new LinkedHashMap<>();long since;Batch(long since){this.since=since;}}
    private final Map<UUID,Batch> deltas=new HashMap<>();private final Map<UUID,Integer> flushing=new HashMap<>();private final Map<UUID,Long> notices=new HashMap<>();
    QuestBridge(MagicCodexBridge plugin)throws Exception{
        this.plugin=plugin;file=new File(plugin.getDataFolder(),"quests.yml");if(!file.exists())plugin.saveResource("quests.yml",false);
        var local=read();var settings=school.magiccodex.database.DatabaseSettings.load(plugin.getDataFolder().toPath().resolve("database.properties"));
        try{store=io.submit(()->new QuestStore(settings,plugin.getDataFolder().toPath().resolve("quests.db"))).get(15,TimeUnit.SECONDS);io.submit(()->{store.seed(local);return null;}).get(15,TimeUnit.SECONDS);catalog=io.submit(store::catalog).get(15,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
        Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getMessenger().registerIncomingPluginChannel(plugin,QuestProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,QuestProtocol.RESPONSE);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,QuestAdminProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,QuestAdminProtocol.RESPONSE);
        registerCommands();
        hook("Citizens","net.citizensnpcs.api.event.NPCRightClickEvent",event->{Player p=(Player)invoke(event,"getClicker");Object npc=invoke(event,"getNPC");Entity e=(Entity)invoke(npc,"getEntity");if(e!=null&&e.getWorld().equals(p.getWorld())&&e.getLocation().distanceSquared(p.getLocation())<=36)event(p,Type.NPC,String.valueOf(invoke(npc,"getId")),1);});
        hook("MythicMobs","io.lumine.mythic.bukkit.events.MythicMobDeathEvent",event->{Object killer=invoke(event,"getKiller");if(killer instanceof Player p&&invoke(event,"getEntity") instanceof Entity e&&!e.getScoreboardTags().contains("chacademia_pet"))event(p,Type.MYTHIC_KILL,(String)invoke(invoke(event,"getMobType"),"getInternalName"),1);});
        Bukkit.getScheduler().runTaskTimer(plugin,()->{if(!refreshing&&!closing){refreshing=true;long known=catalogRevision;work(()->{long revision=store.catalogRevision();return revision==known?null:new Published(revision,store.catalog());},v->{refreshing=false;if(v!=null){catalog=v.catalog();catalogRevision=v.revision();}},m->refreshing=false);}for(Player p:Bukkit.getOnlinePlayers())if(viewers.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis())load(p,false,"");},100,100);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers()){var entries=cache.get(p.getUniqueId());if(entries==null||entries.stream().anyMatch(e->e.status().equals("active")&&e.quest().objectives().stream().anyMatch(g->g.type()==Type.BIOME)))event(p,Type.BIOME,p.getWorld().getBiome(p.getLocation()).getKey().toString(),1);}},40,40);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{for(UUID id:new ArrayList<>(deltas.keySet()))flush(id,false);},FLUSH_TICKS,FLUSH_TICKS);
        for(Player p:Bukkit.getOnlinePlayers())load(p,false,"");
    }
    private record Published(long revision,Map<String,QuestDefinition> catalog){}
    boolean dialogueCondition(Player p,String id,String status){return cache.getOrDefault(p.getUniqueId(),List.of()).stream().anyMatch(e->e.quest().id().equals(id)&&e.current(System.currentTimeMillis())&&(status.equals("completed")?e.completions()>0:e.status().equals("active")));}
    /** 외부(ChacaNPC) 제안 가능 여부: 존재·공개·권한, 그리고 진행 중이 아니고 일회성 의뢰는 완료 전. 캐시만 본다(메인 스레드). */
    boolean npcOfferable(Player p,String id){
        var q=catalog.get(id);long now=System.currentTimeMillis();
        if(q==null||!q.available(now)||!QuestStore.unlocked(q,completed.getOrDefault(p.getUniqueId(),Set.of()))||(!q.permission().isEmpty()&&!p.hasPermission(q.permission()))||!p.hasPermission("magiccodex.quests"))return false;
        return cache.getOrDefault(p.getUniqueId(),List.of()).stream().noneMatch(e->e.quest().id().equals(id)&&e.current(now)&&(e.status().equals("active")||(!q.daily()&&e.completions()>0)));
    }
    /**
     * 대화의 quest 동작을 실행하기 전에 확인한다(캐시만 봄, 메인 스레드). 받을 수 있거나 이미 진행 중이면 빈 문자열,
     * 아니면 유저에게 보여 줄 이유. QuestStore.accept 의 조건과 같은 순서다.
     */
    String acceptRefusal(Player p,String id){
        var q=catalog.get(id);UUID uid=p.getUniqueId();long now=System.currentTimeMillis();
        if(q==null||!q.available(now))return "지금은 받을 수 없는 의뢰입니다.";
        if(!p.hasPermission("magiccodex.quests")||(!q.permission().isEmpty()&&!p.hasPermission(q.permission())))return "이 의뢰를 받을 권한이 없습니다.";
        var entries=cache.get(uid);if(entries==null)return "의뢰 정보를 불러오는 중입니다. 잠시 뒤 다시 시도해 주세요.";
        if(entries.stream().anyMatch(e->e.quest().id().equals(id)&&e.current(now)&&e.status().equals("active")))return "";
        if(entries.stream().anyMatch(e->e.quest().id().equals(id)&&e.status().equals("paying")))return "이 의뢰의 보상 처리가 아직 끝나지 않았습니다.";
        if(!QuestStore.unlocked(q,completed.getOrDefault(uid,Set.of())))return "먼저 끝내야 하는 의뢰가 있습니다.";
        if(!q.main()&&QuestStore.activeSubquests(entries,now)>=3)return "진행 중인 의뢰가 3개입니다. 하나를 완료하거나 포기한 뒤 다시 시도해 주세요.";
        String cycle=q.cycle(now);
        if(entries.stream().anyMatch(e->e.quest().id().equals(id)&&e.cycle().equals(cycle)&&e.completions()>=q.completionLimit()))return q.daily()?"오늘은 이 의뢰를 더 받을 수 없습니다.":"이미 완료한 의뢰입니다.";
        return "";
    }
    /** 한 번이라도 완료(보상까지)한 의뢰인지 (접속 때 읽은 캐시만 봄, 메인 스레드). NPC 호감도 상한 퀘스트 복구(#100)용. */
    boolean completedEver(Player p,String id){return completed.getOrDefault(p.getUniqueId(),Set.of()).contains(id);}
    /** 정보창 모험 기록: 진행 중 퀘스트 수 · 추적 퀘스트(진행 중 메인 퀘스트, 없으면 처음 받은 진행 중 퀘스트)와 목표 합계. 아직 못 불러왔으면 null. */
    record Summary(int active,String tracked,int current,int required){}
    Summary summary(Player p){
        var entries=cache.get(p.getUniqueId());if(entries==null)return null;long now=System.currentTimeMillis();
        var active=entries.stream().filter(e->e.current(now)&&e.status().equals("active")).toList();
        var pick=active.stream().filter(e->e.quest().main()).findFirst().orElse(active.isEmpty()?null:active.get(0));
        if(pick==null)return new Summary(active.size(),"",0,0);
        long cur=0,req=0;for(int i=0;i<pick.progress().length&&i<pick.quest().objectives().size();i++){int need=Math.max(0,pick.quest().objectives().get(i).amount());req+=need;cur+=Math.min(need,Math.max(0,pick.progress()[i]));}
        String title=pick.quest().title();if(title.length()>64)title=title.substring(0,64);
        return new Summary(active.size(),title,(int)Math.min(cur,1_000_000),(int)Math.min(req,1_000_000));
    }
    int activeCount(Player p){long now=System.currentTimeMillis();return QuestStore.activeSubquests(cache.getOrDefault(p.getUniqueId(),List.of()),now);}
    String title(String id){var q=catalog.get(id);return q==null?"":q.title();}
    /** 길잡이 별 (2026-10-26 메모 #93): 진행 중 의뢰에서 아직 못 채운 "NPC" 목표의 Citizens 번호 (이 서버 것만). 캐시만 본다, 메인 스레드. */
    int[] npcTargets(Player p){
        long now=System.currentTimeMillis();var out=new LinkedHashSet<Integer>();
        for(var e:cache.getOrDefault(p.getUniqueId(),List.of())){
            if(!e.status().equals("active")||!e.current(now))continue;
            var goals=e.quest().objectives();
            for(int i=0;i<e.progress().length&&i<goals.size();i++){
                var g=goals.get(i);
                if(g.type()!=Type.NPC||e.progress()[i]>=g.amount()||(!g.server().isEmpty()&&!g.server().equals(server)))continue;
                try{out.add(Integer.parseInt(g.target().trim()));}catch(NumberFormatException ignored){}
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
    void acceptFromDialogue(Player p,String id,Consumer<Boolean> done){
        var q=catalog.get(id);UUID uid=p.getUniqueId();long now=System.currentTimeMillis();
        if(q==null||!q.available(now)||(!q.permission().isEmpty()&&!p.hasPermission(q.permission()))||!p.hasPermission("magiccodex.quests")){done.accept(false);return;}
        flush(uid,true);/* 수락 전에 모인 진행도가 새 의뢰에 들어가지 않게 먼저 기록한다 */
        work(()->{var all=store.entries(uid);if(all.stream().anyMatch(e->e.quest().id().equals(id)&&e.current(now)&&e.status().equals("active")))return 1;return store.accept(uid,q,now)?2:0;},result->{if(result==2)AuditLog.log(uid,AuditLog.퀘스트,"의뢰 수락 (대화) · "+q.title(),"의뢰",q.id(),"제목",q.title(),"경로","대화");if(current(p)){load(p,false,"");if(result==2)JournalBridge.notify(plugin,p,q.title(),q.description());}done.accept(result>0);},m->done.accept(false));
    }
    void eventFromDialogue(Player p,String id,Consumer<Boolean> done){
        UUID uid=p.getUniqueId();String world=p.getWorld().getName(),sid=server;long now=System.currentTimeMillis();
        flush(uid,true);
        work(()->{store.advance(uid,Type.EVENT,id,sid,world,1,now);return store.entries(uid);},v->{if(current(p)){var later=deltas.get(uid);if(later!=null)for(var e:later.counts.entrySet())apply(v,e.getKey().type(),e.getKey().target(),e.getKey().world(),e.getValue(),System.currentTimeMillis());cache.put(uid,v);}done.accept(true);},m->done.accept(false));
    }
    private interface Checked{void run(Object e)throws Exception;}
    @SuppressWarnings("unchecked") private void hook(String owner,String cls,Checked callback){var dep=Bukkit.getPluginManager().getPlugin(owner);if(dep==null||!dep.isEnabled())return;try{Class<? extends Event> type=(Class<? extends Event>)Class.forName(cls,true,dep.getClass().getClassLoader());Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(l,e)->{try{callback.run(e);}catch(Exception ex){plugin.getLogger().warning("Quest hook: "+ex.getClass().getSimpleName());}},plugin,true);}catch(Exception e){plugin.getLogger().warning("의뢰 "+owner+" 연결 실패: "+e.getClass().getSimpleName());}}
    private static Object invoke(Object o,String method)throws Exception{return o.getClass().getMethod(method).invoke(o);}
    private Map<String,QuestDefinition> read()throws Exception{
        var y=new YamlConfiguration();y.load(file);String sid=y.getString("server-id","school");if(!sid.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("server-id");
        var result=new TreeMap<String,QuestDefinition>();var root=y.getConfigurationSection("quests");if(root==null)throw new IllegalArgumentException("quests 항목 없음");if(root.getKeys(false).size()>QuestAdminProtocol.MAX_ENTRIES)throw new IllegalArgumentException("최대 "+QuestAdminProtocol.MAX_ENTRIES+"개 의뢰");
        for(String id:root.getKeys(false)){var s=root.getConfigurationSection(id);var goals=new ArrayList<Objective>();for(var m:s.getMapList("objectives")){
            Type t=Type.valueOf(String.valueOf(m.get("type")).toUpperCase(Locale.ROOT));String target=String.valueOf(m.get("target"));int amount=Integer.parseInt(String.valueOf(m.get("amount")));
            if(t==Type.SUBMIT)submissionItem(target);
            if(t==Type.KILL&&!Arrays.stream(EntityType.values()).anyMatch(e->e.name().equals(target)&&e.isAlive()))throw new IllegalArgumentException(id+" 대상 오류");
            goals.add(new Objective(t,target,amount,String.valueOf(m.containsKey("label")?m.get("label"):target),String.valueOf(m.containsKey("server")?m.get("server"):""),String.valueOf(m.containsKey("world")?m.get("world"):"")));
        }
        var q=new QuestDefinition(id,s.getString("title",id),s.getString("description",""),s.getString("permission",""),s.getBoolean("daily",true),s.getBoolean("enabled",true),goals,s.getString("reward.label",""),s.getStringList("reward.commands"),s.getStringList("reward.items"),s.getString("rank","F").toUpperCase(Locale.ROOT),s.getInt("completion-limit",1),parseTime(s.getString("opens-at","")),s.getDouble("reward.money",0),s.getInt("reward.house-points",0),s.getBoolean("main",false),s.getStringList("requires"));
        for(String item:q.items())if(Material.matchMaterial(item.split(":")[0])==null)throw new IllegalArgumentException("보상 아이템 오류");if(q.encode().length()>60000)throw new IllegalArgumentException("의뢰 데이터가 너무 큽니다: "+id);result.put(id,q);}
        var npcs=new HashMap<String,String>();for(String id:result.keySet()){String n=root.getString(id+".npc","").trim();if(!n.isEmpty()){if(n.length()>30)throw new IllegalArgumentException(id+" npc 이름이 너무 깁니다");npcs.put(id,n);}}npcGroups=Map.copyOf(npcs);
        server=sid;draftIds=Set.copyOf(result.keySet());return Map.copyOf(result);
    }
    @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer(),false,"");}
    @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();flush(id,true);notices.remove(id);cache.remove(id);completed.remove(id);busy.remove(id);limits.remove(id);viewers.remove(id);pages.remove(id);tabs.remove(id);adminLimits.remove(id);adminBusy.remove(id);}
    @EventHandler(priority=EventPriority.MONITOR) public void death(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p!=null&&!(e.getEntity() instanceof Player)&&!e.getEntity().hasMetadata("NPC")&&!e.getEntity().getScoreboardTags().contains("chacademia_pet"))event(p,Type.KILL,e.getEntityType().name(),1);}
    /** Called only by trusted server integrations (never client progress packets). */
    void event(Player p,Type type,String target,int amount){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Use the server thread");if(closing||!p.isOnline()||p.isDead()||p.getGameMode()==GameMode.SPECTATOR||amount<1||amount>100000||type==Type.SUBMIT)return;
        UUID id=p.getUniqueId();String world=p.getWorld().getName();long time=System.currentTimeMillis();
        var entries=cache.get(id);if(entries!=null&&entries.stream().noneMatch(e->tracks(e,type,target,world)))return;
        // DB 에는 바로 쓰지 않는다. 화면용 캐시에는 즉시 반영하고(목표 수치에서 멈춤), 기록은 flush 가 묶어서 한다.
        deltas.computeIfAbsent(id,k->new Batch(time)).counts.merge(new DeltaKey(type,target,world),amount,(a,b)->(int)Math.min(1_000_000L,(long)a+b));
        if(entries!=null&&apply(entries,type,target,world,amount,time)&&viewers.getOrDefault(id,0L)>time)reply(p,false,"");
    }
    /** 캐시에 진행도를 더한다(목표 수치까지만). 바뀐 것이 있으면 true. */
    private boolean apply(List<QuestStore.Entry> entries,Type type,String target,String world,int amount,long now){
        boolean changed=false;if(type==Type.SUBMIT)return false;
        for(var e:entries){if(!e.status().equals("active")||!e.current(now))continue;for(int i=0;i<e.progress().length;i++){var g=e.quest().objectives().get(i);if(g.matches(type,target,server,world)){int v=(int)Math.min(g.amount(),(long)e.progress()[i]+amount);if(v!=e.progress()[i]){e.progress()[i]=v;changed=true;}}}}
        return changed;
    }
    /**
     * 모아 둔 진행도를 한 트랜잭션으로 기록한다. force=false(주기 기록)는 그 유저의 기록이 진행 중이면 다음 주기로 미룬다.
     * 실패하면 수치를 다시 모아 두고 다음 주기에 재시도한다. 대기 수 한도(pending)와 무관하게 접수한다(유저당 묶음 하나라 유저 수로 제한됨).
     */
    private void flush(UUID id,boolean force){
        var batch=deltas.get(id);if(batch==null||closing||(!force&&flushing.containsKey(id)))return;
        deltas.remove(id);flushing.merge(id,1,Integer::sum);
        var list=batch.counts.entrySet().stream().map(e->new QuestStore.Delta(e.getKey().type(),e.getKey().target(),e.getKey().world(),e.getValue())).toList();String sid=server;long since=batch.since;
        // 기록(advanceAll)이 끝난 뒤의 다시 읽기 실패는 기록 실패가 아니다: 수치를 다시 모아 두면 두 번 반영되므로 캐시 갱신만 건너뛴다.
        submit(()->{store.advanceAll(id,list,sid,since);try{return store.entries(id);}catch(Exception e){return null;}},v->{
            flushing.computeIfPresent(id,(k,n)->n>1?n-1:null);Player p=Bukkit.getPlayer(id);if(v==null||p==null||!current(p))return;
            // 기록하는 사이에 새로 모인 수치는 아직 DB에 없으므로 화면용 캐시에 다시 더해 둔다.
            var later=deltas.get(id);if(later!=null)for(var e:later.counts.entrySet())apply(v,e.getKey().type(),e.getKey().target(),e.getKey().world(),e.getValue(),System.currentTimeMillis());
            cache.put(id,v);if(viewers.getOrDefault(id,0L)>System.currentTimeMillis())reply(p,false,"");
        },m->{
            flushing.computeIfPresent(id,(k,n)->n>1?n-1:null);
            var back=deltas.computeIfAbsent(id,k->new Batch(since));back.since=Math.min(back.since,since);for(var d:list)back.counts.merge(new DeltaKey(d.type(),d.target(),d.world()),d.amount(),(a,b)->(int)Math.min(1_000_000L,(long)a+b));
            long now=System.currentTimeMillis();Player p=Bukkit.getPlayer(id);
            if(p!=null&&current(p)&&now>=notices.getOrDefault(id,0L)){notices.put(id,now+30000);CodexCommands.notify(p,"system","quest-delay","의뢰 진행도 저장이 늦어지고 있어요. 진행도는 보관 중이고 자동으로 다시 저장해요",30000);}
        });
    }
    private boolean tracks(QuestStore.Entry e,Type type,String target,String world){if(!e.status().equals("active")||!e.current(System.currentTimeMillis()))return false;for(int i=0;i<e.progress().length;i++){var g=e.quest().objectives().get(i);if(e.progress()[i]<g.amount()&&g.matches(type,target,server,world))return true;}return false;}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){if(QuestAdminProtocol.REQUEST.equals(channel)){adminPacket(p,bytes);return;}if(!QuestProtocol.REQUEST.equals(channel))return;try{var r=QuestProtocol.request(bytes);tabs.put(p.getUniqueId(),r.tab());request(p,r.action(),r.id(),r.page(),false);}catch(IllegalArgumentException ignored){}}
    private void request(Player p,int action,String id,int page,boolean open){
        UUID uid=p.getUniqueId();long now=System.currentTimeMillis();if(!p.hasPermission("magiccodex.quests")||now<limits.getOrDefault(uid,0L)||busy.contains(uid))return;
        limits.put(uid,now+250);pages.put(uid,page);viewers.put(uid,now+15000);
        if(action==0){load(p,open,"");return;}if(p.isDead()||p.getGameMode()==GameMode.SPECTATOR||!plugin.playerStateReady(p)){reply(p,false,"지금은 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");return;}
        if(action==1){var q=catalog.get(id);if(q==null||!q.available(now)||(!q.permission().isEmpty()&&!p.hasPermission(q.permission()))){reply(p,false,"수락 조건을 충족하지 않습니다.");return;}busy.add(uid);flush(uid,true);work(uid,()->store.accept(uid,q,now),ok->{busy.remove(uid);if(ok){JournalBridge.notify(plugin,p,q.title(),q.description());AuditLog.log(uid,AuditLog.퀘스트,"의뢰 수락 · "+q.title(),"의뢰",q.id(),"제목",q.title(),"경로","게시판");}load(p,false,ok?"의뢰를 수락했습니다.":"수락 한도 또는 클리어 가능 횟수를 확인해 주세요.");},m->{busy.remove(uid);reply(p,false,m);});}
        if(action==3){busy.add(uid);flush(uid,true);work(uid,()->store.abandon(uid,id,now),ok->{busy.remove(uid);load(p,false,ok?"의뢰를 포기했습니다.":"포기할 수 없는 의뢰입니다.");},m->{busy.remove(uid);reply(p,false,m);});}
        if(action==2)claim(p,id);
    }
    private void claim(Player p,String id){UUID uid=p.getUniqueId();var e=cache.getOrDefault(uid,List.of()).stream().filter(v->v.quest().id().equals(id)&&v.current(System.currentTimeMillis())&&v.status().equals("active")).findFirst().orElse(null);
        if(e==null||!e.complete()){reply(p,false,"의뢰 목표를 먼저 완료해 주세요.");return;}
        int rewardHouse=plugin.questHouse(p);if(e.quest().housePoints()>0&&rewardHouse<0){reply(p,false,"기숙사 소속을 먼저 정해 주세요.");return;}
        if(e.quest().money()>0&&economy()==null){reply(p,false,"보상 지급 준비가 되지 않았습니다. 관리자에게 문의해 주세요.");return;}
        if(plan(p,e.quest())==null){reply(p,false,"제출 아이템·제출 장소 또는 보상 공간을 확인해 주세요.");return;}
        // 모아 둔 진행도를 먼저 기록한다(IO 는 한 줄이라 예약보다 반드시 앞선다).
        busy.add(uid);flush(uid,true);work(uid,()->store.reserve(uid,id,e.cycle(),System.currentTimeMillis()),reserved->{
            if(reserved==null){busy.remove(uid);load(p,false,"이미 처리했거나 완료할 수 없는 의뢰입니다.");return;}
            ItemStack[] contents=current(p)&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&plugin.playerStateReady(p)?plan(p,reserved.quest()):null;
            if(contents==null){submit(uid,()->{store.finish(uid,reserved,false);return true;},v->{busy.remove(uid);if(current(p))load(p,false,"조건이 변경되어 취소했습니다.");},m->{busy.remove(uid);});return;}
            pay(p,reserved,0,rewardHouse,contents,()->{busy.remove(uid);if(current(p)){p.playSound(p.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,.45f,1.4f);load(p,false,"의뢰 보상을 받았습니다.");}},m->{busy.remove(uid);if(current(p))load(p,false,m);});
        },m->{busy.remove(uid);reply(p,false,m);});
    }
    private static String stageText(int stage){
        if((stage&QuestStore.RECORDED)==0)return "지급 단계 기록 없음 — 지급 도중 서버가 멈췄을 수 있습니다. 로그의 QUEST DELIVERED/QUEST REVIEW 줄과 유저 인벤토리·잔액을 확인하세요 (다시지급 불가: 완료처리 또는 되돌리기)";
        var given=new ArrayList<String>();var left=new ArrayList<String>();String[] names={"아이템(제출·보상)","돈","보상 명령어","기숙사 점수"};
        for(int i=0;i<4;i++)((stage&(1<<i))!=0?given:left).add(names[i]);
        return "지급함: "+(given.isEmpty()?"없음":String.join(", ",given))+" / 남음: "+(left.isEmpty()?"없음":String.join(", ",left))+" (다시지급은 남은 것만 실행)";
    }
    /**
     * 보상 지급. already 에 없는 단계만 실행한다: 아이템(제출 차감+보상 아이템) -> 돈 -> 보상 명령어 -> 기숙사 점수(토큰으로 멱등) -> 완료 기록.
     * 보상 명령어는 실패해도 경고만 남기고 완료한다. 아이템·돈 단계가 실패하면 어디까지 줬는지 기록하고 paying 으로 남긴다(관리자 retry 는 남은 단계만 실행).
     */
    private void pay(Player p,QuestStore.Entry reserved,int already,int rewardHouse,ItemStack[] contents,Runnable ok,Consumer<String> held){
        UUID uid=p.getUniqueId();var q=reserved.quest();String ref=uid+" "+q.id()+" "+reserved.cycle()+" "+reserved.token();int stage=already&15;
        try{
            // Reservation is durable BEFORE inventory or command effects. Ambiguous crashes stay in review, never automatic retry.
            if((stage&QuestStore.ITEMS)==0){p.getInventory().setStorageContents(contents);stage|=QuestStore.ITEMS;/* 인벤토리는 이미 바뀌었다: 저장(saveData)이 실패해도 retry 가 아이템을 다시 주지 않게 먼저 표시한다 */p.saveData();}
            if((stage&QuestStore.MONEY)==0){if(q.money()>0)deposit(p,q.money());stage|=QuestStore.MONEY;}
            if((stage&QuestStore.COMMANDS)==0){
                for(String command:q.commands()){
                    String line=command.replace("{player}",p.getName()).replace("{uuid}",uid.toString());
                    try{if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),line))plugin.getLogger().warning("QUEST REWARD COMMAND FAILED quest="+q.id()+" player="+p.getName()+" ("+uid+") command=["+line+"]: 등록되지 않은 명령어");}
                    catch(RuntimeException ex){plugin.getLogger().log(Level.WARNING,"QUEST REWARD COMMAND FAILED quest="+q.id()+" player="+p.getName()+" ("+uid+") command=["+line+"]",ex);}
                }
                stage|=QuestStore.COMMANDS;
            }
            p.saveData();
        }catch(Exception ex){
            int reached=stage;plugin.getLogger().log(Level.SEVERE,"QUEST REVIEW "+ref+" 지급 중단 — "+stageText(reached|QuestStore.RECORDED),ex);
            submit(uid,()->{store.stage(uid,reserved,reached|QuestStore.RECORDED);return true;},v->held.accept("보상 처리 확인이 필요합니다. 관리자에게 문의해 주세요."),m->held.accept("보상 처리 확인이 필요합니다. 관리자에게 문의해 주세요."));return;
        }
        int reached=stage;plugin.getLogger().info("QUEST DELIVERED "+ref);
        Runnable finish=()->submit(uid,()->{store.finish(uid,reserved,true);return true;},v->{fireCompleted(uid,q.id());AuditStats.add(uid,"퀘스트완료",1);AuditLog.log(uid,AuditLog.퀘스트,"의뢰 완료·보상 · "+q.title()+(q.money()>0?" · 돈 +"+q.money():"")+(q.housePoints()>0?" · 기숙사 +"+q.housePoints():""),"의뢰",q.id(),"제목",q.title(),"주기",reserved.cycle(),"돈",q.money()>0?q.money():null,"기숙사점수",q.housePoints()>0?q.housePoints():null,"아이템",q.items().isEmpty()?null:String.join(", ",q.items()),"명령",q.commands().isEmpty()?null:String.join(" | ",q.commands()),"토큰",reserved.token());ok.run();},m->{
            plugin.getLogger().severe("QUEST REVIEW "+ref+" 보상은 모두 지급했으나 완료 기록에 실패했습니다. /유저관리 "+p.getName()+" 의뢰기록 처리 "+q.id()+" "+reserved.cycle()+" 완료처리 로 닫으세요.");
            submit(uid,()->{store.stage(uid,reserved,15|QuestStore.RECORDED);return true;},v->{},x->{});held.accept("보상 기록 확인이 필요합니다. 관리자에게 문의해 주세요.");});
        if(q.housePoints()>0&&(reached&QuestStore.HOUSE)==0)plugin.questHouseReward(reserved.token(),rewardHouse,q.housePoints(),finish,m->{
            plugin.getLogger().severe("QUEST REVIEW "+ref+" 기숙사 점수 지급 실패 — "+stageText(reached|QuestStore.RECORDED));
            submit(uid,()->{store.stage(uid,reserved,reached|QuestStore.RECORDED);return true;},v->{},x->{});held.accept("기숙사 점수 지급 확인이 필요합니다. 관리자에게 문의해 주세요.");});
        else finish.run();
    }
    /** 보상 명령어의 첫 단어가 이 서버에 등록되어 있는지 확인한다. 다른 서버 전용 명령어일 수 있으므로 막지 않고 경고만 한다. */
    private List<String> commandWarnings(Collection<QuestDefinition> quests){
        var out=new ArrayList<String>();
        for(var q:quests)for(String command:q.commands()){String label=command.split(" ",2)[0];if(out.size()<10&&Bukkit.getCommandMap().getCommand(label)==null)out.add("주의: 의뢰 "+q.id()+" 의 보상 명령 '"+label+"' 을 이 서버에서 찾을 수 없어요. 다른 서버 전용이 아니면 오타를 확인해 주세요");}
        return out;
    }
    private ItemStack[] plan(Player p,QuestDefinition q){
        // 공통 지급 칸 판정 (RewardDelivery, 2026-10-26): 아공간 배낭 쪽을 넘기는 중이거나 잠긴 쪽을 보고 있으면 27칸(9~35)에서 제출 차감·보상 넣기를
        // 하지 않는다(넘김이 끝날 때 덮여 복제·유실). 이 경우는 지급하지 않고 "보상 공간 확인"으로 거절한다 (잠시 뒤 다시 완료하면 됨).
        if(!RewardDelivery.mainUsable(plugin,p))return null;
        ItemStack[] slots=Arrays.stream(p.getInventory().getStorageContents()).map(s->s==null?null:s.clone()).toArray(ItemStack[]::new);
        for(var g:q.objectives())if(g.type()==Type.SUBMIT){if(!g.matches(Type.SUBMIT,g.target(),server,p.getWorld().getName()))return null;ItemStack required=submissionItem(g.target());int left=g.amount();for(int i=0;i<slots.length&&left>0;i++){var s=slots[i];if(s!=null&&plugin.coreSimilar(s,required)){int n=Math.min(left,s.getAmount());left-=n;s.setAmount(s.getAmount()-n);if(s.getAmount()==0)slots[i]=null;}}if(left>0)return null;}
        for(String spec:q.items()){String[] parts=spec.split(":");ItemStack reward=new ItemStack(Objects.requireNonNull(Material.matchMaterial(parts[0])));int left=Integer.parseInt(parts[1]);for(int i=0;i<slots.length&&left>0;i++)if(slots[i]!=null&&slots[i].isSimilar(reward)){int n=Math.min(left,slots[i].getMaxStackSize()-slots[i].getAmount());slots[i].setAmount(slots[i].getAmount()+n);left-=n;}for(int i=0;i<slots.length&&left>0;i++)if(slots[i]==null||slots[i].isEmpty()){int n=Math.min(left,reward.getMaxStackSize());slots[i]=reward.clone();slots[i].setAmount(n);left-=n;}if(left>0)return null;}
        return slots;
    }
    static ItemStack submissionItem(String target){ItemStack item=target.startsWith("stack:")?ItemStack.deserializeBytes(Base64.getDecoder().decode(target.substring(6))):new ItemStack(Objects.requireNonNull(Material.matchMaterial(target),"제출 아이템 종류 오류"));if(item.isEmpty())throw new IllegalArgumentException("빈 아이템");item.setAmount(1);return item;}
    List<school.magiccodex.protocol.JournalProtocol.Entry> journalDefinitions(){return catalog.values().stream().map(q->new school.magiccodex.protocol.JournalProtocol.Entry("quest",q.id(),q.title(),q.description(),"available",q.main(),"","",npcGroup(q.id()))).toList();}
    /** quests.yml 의 npc: 키(표시 전용). 일지에서 "NPC <이름>" 카테고리로 묶인다. */
    private volatile Map<String,String> npcGroups=Map.of();
    /** 의뢰 완료(보상 지급·완료 기록까지 끝남) 알림. 메인 스레드. NPC 호감도 퀘스트(#39·#100)가 다음 상한을 여는 데 쓴다. */
    private final List<java.util.function.BiConsumer<UUID,String>> completedHooks=new java.util.concurrent.CopyOnWriteArrayList<>();
    void onCompleted(java.util.function.BiConsumer<UUID,String> hook){if(hook!=null)completedHooks.add(hook);}
    private void fireCompleted(UUID player,String id){for(var h:completedHooks)try{h.accept(player,id);}catch(RuntimeException ex){plugin.getLogger().warning("Quest completed hook: "+ex.getClass().getSimpleName());}}
    String npcGroup(String id){String n=npcGroups.get(id);return n==null?"":"NPC "+n;}
    Map<String,String> journalCatalog(){var map=new TreeMap<String,String>();catalog.values().forEach(q->map.put("quest:"+q.id(),q.title()));return map;}
    void journalRows(Player player,java.util.function.Consumer<List<school.magiccodex.protocol.JournalProtocol.Entry>> callback){
        flush(player.getUniqueId(),true);work(()->store.journalEntries(player.getUniqueId()),entries->{var rows=new ArrayList<school.magiccodex.protocol.JournalProtocol.Entry>();
            for(var e:entries){if(e.status().equals("ready")||(!e.current(System.currentTimeMillis())&&e.status().equals("active")))continue;var q=e.quest();String details=java.util.stream.IntStream.range(0,q.objectives().size()).mapToObj(i->q.objectives().get(i).label()+" "+e.progress()[i]+" / "+q.objectives().get(i).amount()).collect(java.util.stream.Collectors.joining("\n"));
                rows.add(new school.magiccodex.protocol.JournalProtocol.Entry("quest",q.id(),q.title(),q.description(),e.status(),q.main(),school.magiccodex.protocol.JournalProtocol.cut(details,1200),school.magiccodex.protocol.JournalProtocol.cut(rewardText(q),500),npcGroup(q.id())));}
            callback.accept(List.copyOf(rows));},m->{plugin.getLogger().warning("의뢰 기록 읽기 실패 ("+player.getName()+"): "+m);callback.accept(List.of());});
    }
    void journalEdit(CommandSender sender,Player target,String id,String operation){
        var q=catalog.get(id);for(var e:cache.getOrDefault(target.getUniqueId(),List.of()))if(e.quest().id().equals(id)&&e.current(System.currentTimeMillis()))q=e.quest();
        if(q==null)throw new IllegalArgumentException("등록된 퀘스트를 찾을 수 없습니다.");final var definition=q;
        if(operation.equals("query")){journalRows(target,rows->ChacaApi.lines(sender,rows.stream().filter(e->e.id().equals(id)).map(e->" "+e.key()+" / "+e.title()+" / "+e.state()+" / "+e.details()).toList()));return;}
        if(busy.contains(target.getUniqueId()))throw new IllegalArgumentException("보상 처리가 진행 중입니다. 잠시 후 다시 시도해 주세요.");flush(target.getUniqueId(),true);
        work(()->{store.adminRecord(target.getUniqueId(),definition,operation,System.currentTimeMillis());return true;},ok->{load(target,false,"");ChacaApi.ok(sender,"퀘스트 기록을 바꿨어요 (보상 지급·수령 기록은 그대로)");if(operation.equals("grant"))JournalBridge.notify(plugin,target,definition.title(),definition.description());plugin.getLogger().info("QUEST JOURNAL admin="+sender.getName()+" player="+target.getUniqueId()+" id="+id+" operation="+operation);},m->ChacaApi.fail(sender,m,null));
    }
    private record Journal(List<QuestStore.Entry> entries,Set<String> completed){}
    private void load(Player p,boolean open,String message){UUID id=p.getUniqueId();flush(id,true);work(()->new Journal(store.entries(id),store.completed(id)),v->{if(current(p)){var later=deltas.get(id);if(later!=null)for(var e:later.counts.entrySet())apply(v.entries(),e.getKey().type(),e.getKey().target(),e.getKey().world(),e.getValue(),System.currentTimeMillis());cache.put(id,v.entries());completed.put(id,v.completed());if(open)viewers.put(id,System.currentTimeMillis()+15000);reply(p,open,message);}},m->{if(current(p))CodexCommands.warn(p,"quest",m);});}
    private void reply(Player p,boolean open,String message){if(!current(p))return;if(!p.getListeningPluginChannels().contains(QuestProtocol.RESPONSE)){if(open)CodexCommands.warn(p,"quest","의뢰 게시판을 열려면 최신 MagicCodex 모드가 필요해요");else if(!message.isEmpty())CodexCommands.notify(p,"system","quest",message,1500);return;}
        long now=System.currentTimeMillis();var all=new TreeMap<String,QuestDefinition>();catalog.values().stream().filter(q->q.available(now)&&QuestStore.unlocked(q,completed.getOrDefault(p.getUniqueId(),Set.of()))&&(q.permission().isEmpty()||p.hasPermission(q.permission()))).forEach(q->all.put(q.id(),q));var states=new HashMap<String,QuestStore.Entry>();
        for(var e:cache.getOrDefault(p.getUniqueId(),List.of()))if((e.current(now)&&(e.status().equals("active")||all.containsKey(e.quest().id())))||e.status().equals("paying")){states.put(e.quest().id(),e);if(e.status().equals("active")||e.status().equals("paying"))all.put(e.quest().id(),e.quest());}
        int tab=tabs.getOrDefault(p.getUniqueId(),0);
        var list=all.values().stream().filter(q->tab==tabFor(displayState(q,states.get(q.id())))).sorted(Comparator.comparing(QuestDefinition::main).reversed().thenComparing(QuestDefinition::id)).toList();int page=Math.clamp(pages.getOrDefault(p.getUniqueId(),0),0,Math.max(0,(list.size()-1)/6));pages.put(p.getUniqueId(),page);var cards=new ArrayList<QuestProtocol.Card>();
        for(var q:list.subList(Math.min(page*6,list.size()),Math.min(page*6+6,list.size()))){var e=states.get(q.id());String state=displayState(q,e);var goals=new ArrayList<QuestProtocol.Goal>();for(int i=0;i<q.objectives().size();i++){var g=q.objectives().get(i);int count=e==null||!state.equals("active")?0:e.progress()[i];if(g.type()==Type.SUBMIT){count=0;var required=submissionItem(g.target());for(var item:p.getInventory().getStorageContents())if(item!=null&&plugin.coreSimilar(item,required))count+=item.getAmount();}if(state.equals("claimed"))count=g.amount();goals.add(new QuestProtocol.Goal(g.label(),Math.min(g.amount(),count),g.amount()));}cards.add(new QuestProtocol.Card(q.id(),q.title(),q.description(),rewardText(q),state,goals,q.rank(),e==null?0:e.completions(),q.completionLimit(),q.main()));}
        try{p.sendPluginMessage(plugin,QuestProtocol.RESPONSE,QuestProtocol.encode(new QuestProtocol.Response(open,page,Math.min(list.size(),QuestProtocol.MAX_TOTAL),message,cards,Math.max(0,3-QuestStore.activeSubquests(cache.getOrDefault(p.getUniqueId(),List.of()),now)))));}catch(IllegalArgumentException ex){CodexCommands.warn(p,"quest","의뢰 게시판을 열지 못했어요. 관리자에게 알려 주세요");plugin.getLogger().warning("Quest board encode: "+ex.getMessage());}
    }

    static long parseTime(String value){if(value.isBlank()||value.equals("0"))return 0;return java.time.LocalDateTime.parse(value.trim().replace(' ','T')).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();}
    static String timeText(long value){return value==0?"":java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime().withNano(0).toString().replace('T',' ');}
    static String displayState(QuestDefinition q,QuestStore.Entry e){if(e==null)return "available";if(Set.of("active","paying").contains(e.status()))return e.status();return e.completions()>=q.completionLimit()?"claimed":"available";}
    static int tabFor(String state){return switch(state){case "claimed"->1;case "active","paying"->2;default->0;};}
    private static String rewardText(QuestDefinition q){var labels=new ArrayList<String>();if(q.housePoints()>0)labels.add("기숙사 점수  +"+q.housePoints());if(q.money()>0)labels.add("돈  +"+new java.text.DecimalFormat("#,##0.##").format(q.money()));if(!q.items().isEmpty())labels.add("아이템  "+String.join(", ",q.items()).replace(":"," × "));if(!q.rewardLabel().isBlank())labels.add(q.rewardLabel());return String.join("\n",labels);}
    private record Economy(Object provider,Class<?> type){}
    @SuppressWarnings({"rawtypes","unchecked"}) private Economy economy(){for(Class<?> type:Bukkit.getServicesManager().getKnownServices())if(type.getName().equals("net.milkbowl.vault.economy.Economy")){var r=Bukkit.getServicesManager().getRegistration((Class)type);if(r!=null)return new Economy(r.getProvider(),type);}return null;}
    private void deposit(Player p,double money)throws Exception{var e=economy();if(e==null)throw new IllegalStateException("Vault economy unavailable");Object response=e.type().getMethod("depositPlayer",OfflinePlayer.class,double.class).invoke(e.provider(),p,money);if(!Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response)))throw new IllegalStateException("Vault deposit rejected");}
    private final Map<UUID,Long> adminLimits=new HashMap<>();private final Set<UUID> adminBusy=new HashSet<>();
    private void adminPacket(Player p,byte[] bytes){
        if(!p.hasPermission("magiccodex.quests.admin"))return;long now=System.currentTimeMillis();UUID uid=p.getUniqueId();if(now<adminLimits.getOrDefault(uid,0L)||adminBusy.contains(uid))return;adminLimits.put(uid,now+300);
        try{var r=QuestAdminProtocol.request(bytes);if(r.action()==0){adminReply(p,"",null);return;}if(r.action()==1){adminReply(p,"",r.id());return;}
            if(r.id().isBlank())throw new IllegalArgumentException("의뢰 ID를 입력하세요");var q=r.action()==2?QuestAdminDocument.parse(r.id(),r.fields()):null;
            // Bound the return document before committing, not after a successful save.
            if(q!=null)QuestAdminProtocol.encode(new QuestAdminProtocol.Response("",List.of(),q.id(),"",QuestAdminDocument.fields(q)));
            adminBusy.add(uid);work(()->store.edit(r.id(),r.revision(),q),ok->{adminBusy.remove(uid);if(!current(p))return;if(!ok){adminReply(p,"다른 곳에서 수정했습니다. 다시 열어 확인해 주세요.",null);return;}try{saveDraft(r.id(),q);}catch(Exception ex){plugin.getLogger().warning("Quest draft export: "+ex.getClass().getSimpleName());}adminReply(p,q==null?"의뢰를 삭제했습니다.":"저장하여 게시판에 반영했습니다.",q==null?null:q.id());if(q!=null){var w=commandWarnings(List.of(q));if(!w.isEmpty())ChacaApi.lines(p,w);}},m->{adminBusy.remove(uid);if(current(p))adminReply(p,m,null);});
        }catch(Exception e){plugin.getLogger().info("Quest admin input rejected ("+p.getName()+"): "+e);adminReply(p,"입력을 확인해 주세요"+(e instanceof IllegalArgumentException&&e.getMessage()!=null&&!e.getMessage().contains("Exception")?": "+e.getMessage():""),null);}
    }
    private void adminReply(Player p,String message,String id){if(!p.hasPermission("magiccodex.quests.admin")||!p.getListeningPluginChannels().contains(QuestAdminProtocol.RESPONSE))return;
        work(store::catalog,map->{catalog=map;if(!current(p)||!p.hasPermission("magiccodex.quests.admin"))return;var rows=map.values().stream().sorted(Comparator.comparing(QuestDefinition::id)).limit(QuestAdminProtocol.MAX_ENTRIES).map(q->new QuestAdminProtocol.Summary(q.id(),q.title(),q.rank(),!q.enabled()?"비공개":q.opensAt()>System.currentTimeMillis()?"예약 "+timeText(q.opensAt()):"공개")).toList();var q=id==null?null:map.get(id);String note=map.size()>rows.size()&&message.isEmpty()?"목록은 앞의 "+rows.size()+"개만 표시합니다. 나머지는 quests.yml에서 수정해 주세요.":message;try{p.sendPluginMessage(plugin,QuestAdminProtocol.RESPONSE,QuestAdminProtocol.encode(new QuestAdminProtocol.Response(note,rows,q==null?"":q.id(),q==null?"":QuestStore.fingerprint(q),q==null?Map.of():QuestAdminDocument.fields(q))));}catch(IllegalArgumentException ex){plugin.getLogger().warning("Quest admin encode: "+ex.getMessage());CodexCommands.warn(p,"quest","편집 화면에 담기에 너무 커요. quests.yml에서 고친 뒤 파일반영 해 주세요");}},m->CodexCommands.warn(p,"quest",m));
    }
    private void saveDraft(String id,QuestDefinition q)throws Exception{var y=new YamlConfiguration();y.load(file);String path="quests."+id;y.set(path,null);if(q!=null){var f=QuestAdminDocument.fields(q);for(var e:f.entrySet())if(!e.getKey().startsWith("goal")){Object v=switch(e.getKey()){case "daily","enabled","main"->Boolean.parseBoolean(e.getValue());case "requires"->q.requires();case "completion-limit","reward.house-points"->Integer.parseInt(e.getValue());case "reward.money"->Double.parseDouble(e.getValue());case "reward.items"->q.items();case "reward.commands"->q.commands();default->e.getValue();};y.set(path+"."+e.getKey(),v);}y.set(path+".objectives",q.objectives().stream().map(g->Map.of("type",g.type().name(),"target",g.target(),"amount",g.amount(),"label",g.label(),"server",g.server(),"world",g.world())).toList());}y.save(file);}
    private boolean current(Player p){return p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p;}
    private interface Job<T>{T run()throws Exception;}
    /** 메인 스레드 후속 처리는 여기를 거친다: 예외가 나도 busy 가 풀린다(보상 지급 기록은 paying 그대로 남아 관리자 audit 에 보인다). */
    private void guard(UUID owner,Runnable r){try{r.run();}catch(Throwable t){plugin.getLogger().log(Level.SEVERE,"Quest continuation failed"+(owner==null?"":" for "+owner),t);if(owner!=null)busy.remove(owner);}}
    private <T>void work(Job<T> job,Consumer<T> done,Consumer<String> fail){work(null,job,done,fail);}
    /** 새 요청: 밀려 있으면 아무 것도 바꾸지 않고 거절한다. */
    private <T>void work(UUID owner,Job<T> job,Consumer<T> done,Consumer<String> fail){if(closing||pending>=512){fail.accept("의뢰 처리가 밀리고 있습니다. 잠시 후 다시 시도해 주세요.");return;}submit(owner,job,done,fail);}
    private <T>void submit(Job<T> job,Consumer<T> done,Consumer<String> fail){submit(null,job,done,fail);}
    /** 이미 시작한 처리의 후속 기록과 진행도 묶음 기록: 대기 수 한도로 거절하지 않는다. */
    private <T>void submit(UUID owner,Job<T> job,Consumer<T> done,Consumer<String> fail){
        if(closing){fail.accept("서버가 종료 중입니다.");return;}
        pending++;io.execute(()->{
            T value;try{value=job.run();}catch(Exception ex){plugin.getLogger().warning("Quest storage: "+ex);String m=ex instanceof IllegalArgumentException||ex instanceof IllegalStateException?String.valueOf(ex.getMessage()):"의뢰 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.";main(()->{pending--;guard(owner,()->fail.accept(m));});return;}
            main(()->{pending--;guard(owner,()->done.accept(value));});});
    }
    private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closing)r.run();});}
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    /** /퀘스트관리 설정 의 한글 항목 → quests.yml 키. */
    static final Map<String,String> FIELDS=new LinkedHashMap<>();
    /** /퀘스트관리 목표 의 한글 종류 → 목표 타입. */
    static final Map<String,Type> GOALS=new LinkedHashMap<>();
    static {
        String[][] f={{"제목","title"},{"설명","description"},{"일일","daily"},{"사용","enabled"},{"권한","permission"},{"보상이름","reward.label"},{"등급","rank"},
            {"완료제한","completion-limit"},{"열리는시간","opens-at"},{"보상돈","reward.money"},{"보상기숙사점수","reward.house-points"}};
        for(var e:f)FIELDS.put(e[0],e[1]);
        GOALS.put("처치",Type.KILL);GOALS.put("미몹처치",Type.MYTHIC_KILL);GOALS.put("제출",Type.SUBMIT);GOALS.put("NPC",Type.NPC);GOALS.put("바이옴",Type.BIOME);GOALS.put("이벤트",Type.EVENT);
    }
    /** quests.yml 초안에만 있는 ID까지 자동완성에 보이도록 (만들기·파일 읽기 때 갱신). */
    private volatile Set<String> draftIds=Set.of();
    private Collection<String> questIds(){var out=new TreeSet<String>(catalog.keySet());out.addAll(draftIds);return out;}
    private Consumer<String> failTo(CommandSender sender){return m->ChacaApi.fail(sender,m==null||m.isBlank()?"명령어 사용에 실패했습니다":m,null);}
    private static String questArg(CommandContext<CommandSourceStack> ctx)throws CommandSyntaxException{
        String id=ctx.getArgument("퀘스트",String.class);if(!id.matches("[a-z0-9_-]{1,48}"))throw ChacaApi.error("퀘스트 ID는 영문 소문자·숫자·_·- 로 입력해 주세요");return id;
    }
    /** 관리자 /의뢰기록 보기 에서 본 검토 대기 기록 (보낸 사람 → "퀘스트 주기"), 처리 자동완성용. */
    private final Map<String,List<String[]>> audited=new HashMap<>();
    /** /테스트 의뢰 이벤트 자동완성: 등록된 이벤트 목표. */
    List<String> eventIds(){var out=new TreeSet<String>();for(var q:catalog.values())for(var g:q.objectives())if(g.type()==Type.EVENT)out.add(g.target());return List.copyOf(out);}
    /** /테스트 의뢰 이벤트: 신뢰된 서버 경로와 같은 진행. */
    void testEvent(Player p,String id,int amount){event(p,Type.EVENT,id,amount);}

    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"퀘스트"))return;
        ChacaApi.reload(plugin,"퀘스트","quests.yml 확인(server-id)과 공용 의뢰 목록 다시 읽기 (파일 내용 반영은 /퀘스트관리 파일반영)",()->{
            read();work(()->new Published(store.catalogRevision(),store.catalog()),v->{catalog=v.catalog();catalogRevision=v.revision();},m->plugin.getLogger().warning("의뢰 목록 다시 읽기 실패: "+m));
        });
        // /의뢰 (누구나): 의뢰 게시판
        ChacaApi.playerCommand(plugin,Commands.literal("의뢰").executes(ctx->{
            var p=CodexCommands.self(ctx);
            if(!p.getListeningPluginChannels().contains(QuestProtocol.RESPONSE))throw ChacaApi.error("의뢰 게시판을 열려면 최신 MagicCodex 모드가 필요해요");
            request(p,0,"",0,true);return Command.SINGLE_SUCCESS;
        }),"의뢰 게시판 열기",List.of());

        String root=ChacaApi.퀘스트관리;
        ChacaApi.branch(plugin,root,Commands.literal("편집").executes(ctx->{
            var p=CodexCommands.self(ctx);if(!p.getListeningPluginChannels().contains(QuestAdminProtocol.RESPONSE))throw ChacaApi.error("편집 화면을 열려면 최신 MagicCodex 모드가 필요해요");
            adminReply(p,"",null);return Command.SINGLE_SUCCESS;
        }),"의뢰 편집 화면 열기 (저장하면 바로 공용 반영)","편집");
        ChacaApi.branch(plugin,root,Commands.literal("목록").executes(ctx->{
            var rows=new ArrayList<String>();long now=System.currentTimeMillis();
            for(var q:new TreeMap<>(catalog).values())rows.add(" "+q.id()+" · "+q.title()+" · "+q.rank()+"등급 · "+(!q.enabled()?"닫힘":q.opensAt()>now?"예약 "+timeText(q.opensAt()):"열림")+(q.daily()?" · 일일":"")+(q.main()?" · 메인":""));
            var drafts=new TreeSet<>(draftIds);drafts.removeAll(catalog.keySet());if(!drafts.isEmpty())rows.add(" 초안에만 있음(파일반영 전): "+String.join(", ",drafts));
            if(rows.isEmpty())rows.add(" 등록된 의뢰가 없어요");
            CodexCommands.lines(CodexCommands.sender(ctx),"── 의뢰 "+catalog.size()+"개 ──",rows);return Command.SINGLE_SUCCESS;
        }),"공용 의뢰 목록","목록");
        ChacaApi.branch(plugin,root,Commands.literal("만들기").then(Commands.argument("퀘스트",ChacaApi.word(List::of)).executes(ctx->{
            draftEdit(CodexCommands.sender(ctx),questArg(ctx),"create",null);return Command.SINGLE_SUCCESS;
        })),"새 의뢰 초안 만들기 (닫힌 상태)","만들기 ");

        var set=Commands.argument("퀘스트",ChacaApi.word(this::questIds));
        for(var field:FIELDS.entrySet())set.then(Commands.literal(field.getKey()).then(Commands.argument("값",StringArgumentType.greedyString()).executes(ctx->{
            draftEdit(CodexCommands.sender(ctx),questArg(ctx),"set",new String[]{field.getValue(),StringArgumentType.getString(ctx,"값")});return Command.SINGLE_SUCCESS;
        })));
        ChacaApi.branch(plugin,root,Commands.literal("설정").then(set),"의뢰 항목 고치기 (초안)","설정 ");

        var goalType=Commands.argument("번호",IntegerArgumentType.integer(1,6));
        for(var g:GOALS.entrySet())goalType.then(Commands.literal(g.getKey()).then(Commands.argument("대상",ChacaApi.word(()->g.getValue()==Type.KILL?Arrays.stream(EntityType.values()).filter(EntityType::isAlive).map(Enum::name).sorted().toList():List.of()))
            .then(Commands.argument("수량",IntegerArgumentType.integer(1,100000)).then(Commands.argument("문구",StringArgumentType.greedyString()).executes(ctx->{
                draftEdit(CodexCommands.sender(ctx),questArg(ctx),"goal",new String[]{Integer.toString(IntegerArgumentType.getInteger(ctx,"번호")),g.getValue().name(),ctx.getArgument("대상",String.class),
                    Integer.toString(IntegerArgumentType.getInteger(ctx,"수량")),StringArgumentType.getString(ctx,"문구")});return Command.SINGLE_SUCCESS;
            })))));
        ChacaApi.branch(plugin,root,Commands.literal("목표").then(Commands.argument("퀘스트",ChacaApi.word(this::questIds)).then(goalType)),"의뢰 목표 정하기 (초안)","목표 ");

        ChacaApi.branch(plugin,root,Commands.literal("제출아이템").then(Commands.argument("퀘스트",ChacaApi.word(this::questIds)).then(Commands.argument("목표번호",IntegerArgumentType.integer(1,6))
            .then(Commands.argument("수량",IntegerArgumentType.integer(1,100000)).executes(ctx->{
                var p=CodexCommands.self(ctx);var item=p.getInventory().getItemInMainHand().clone();if(item.isEmpty())throw ChacaApi.error("제출받을 아이템을 주 손에 들어 주세요");
                draftEdit(p,questArg(ctx),"submit",new String[]{Integer.toString(IntegerArgumentType.getInteger(ctx,"목표번호")),Integer.toString(IntegerArgumentType.getInteger(ctx,"수량")),stack(item)});return Command.SINGLE_SUCCESS;
            })))),"손에 든 아이템을 제출 목표로 (초안)","제출아이템 ");
        ChacaApi.branch(plugin,root,Commands.literal("보상아이템").then(Commands.argument("퀘스트",ChacaApi.word(this::questIds)).executes(ctx->{
            var p=CodexCommands.self(ctx);var item=p.getInventory().getItemInMainHand();if(item.isEmpty())throw ChacaApi.error("보상으로 줄 아이템을 주 손에 들어 주세요");
            if(item.hasItemMeta()&&!item.isSimilar(new ItemStack(item.getType())))ChacaApi.lines(p,List.of("주의: 의뢰 보상 아이템은 종류와 개수만 저장돼요 (이름·마법 부여 등은 빠짐)"));
            draftEdit(p,questArg(ctx),"rewarditem",new String[]{item.getType().name()+":"+Math.min(999,item.getAmount())});return Command.SINGLE_SUCCESS;
        })),"손에 든 아이템(종류·개수)을 보상으로 (초안)","보상아이템 ");
        ChacaApi.branch(plugin,root,Commands.literal("보상명령").then(Commands.argument("퀘스트",ChacaApi.word(this::questIds)).then(Commands.argument("명령",StringArgumentType.greedyString()).executes(ctx->{
            String command=StringArgumentType.getString(ctx,"명령").strip();if(command.startsWith("/"))command=command.substring(1);
            draftEdit(CodexCommands.sender(ctx),questArg(ctx),"rewardcommand",new String[]{command});return Command.SINGLE_SUCCESS;
        }))),"보상 명령 (콘솔로 실행, {player} {uuid}) (초안)","보상명령 ");

        for(String action:List.of("열기","닫기"))ChacaApi.branch(plugin,root,Commands.literal(action).then(Commands.argument("퀘스트",ChacaApi.word(()->catalog.keySet())).executes(ctx->{
            release(CodexCommands.sender(ctx),List.of(questArg(ctx)),0,action.equals("열기"));return Command.SINGLE_SUCCESS;
        })),action.equals("열기")?"의뢰를 바로 공개 (공용)":"새 수락 닫기 (공용)",action+" ");
        ChacaApi.branch(plugin,root,Commands.literal("주기설정").then(Commands.argument("간격",LongArgumentType.longArg(1,31536000)).then(Commands.argument("퀘스트",StringArgumentType.greedyString()).suggests((ctx,b)->{
            String rem=b.getRemaining();int cut=rem.lastIndexOf(' ')+1;var sub=b.createOffset(b.getStart()+cut);String typed=rem.substring(cut);
            for(String id:catalog.keySet())if(id.startsWith(typed))sub.suggest(id);return sub.buildFuture();
        }).executes(ctx->{
            var ids=List.of(StringArgumentType.getString(ctx,"퀘스트").strip().split("\\s+"));
            release(CodexCommands.sender(ctx),ids,LongArgumentType.getLong(ctx,"간격")*1000,true);return Command.SINGLE_SUCCESS;
        }))),"적은 순서대로 간격(초)마다 하나씩 공개 예약","주기설정 3600 ");
        ChacaApi.branch(plugin,root,Commands.literal("삭제").then(CodexCommands.confirmed(Commands.argument("퀘스트",ChacaApi.word(this::questIds)),ctx->{
            draftEdit(CodexCommands.sender(ctx),questArg(ctx),"delete",null);return Command.SINGLE_SUCCESS;
        })),"의뢰 초안 지우기 (파일반영 때 공용에서도 빠짐)","삭제 ");
        ChacaApi.branch(plugin,root,Commands.literal("파일로내보내기").executes(ctx->{exportFile(CodexCommands.sender(ctx));return Command.SINGLE_SUCCESS;}),"공용 의뢰를 quests.yml 로 내보내기 (이전 파일은 백업)","파일로내보내기");
        ChacaApi.branch(plugin,root,Commands.literal("파일반영").executes(ctx->{
            var sender=CodexCommands.sender(ctx);Map<String,QuestDefinition> next;
            try{next=read();}catch(Exception e){plugin.getLogger().warning("quests.yml 검증 실패: "+e);throw ChacaApi.error("quests.yml에 문제가 있어 반영하지 않았어요 (자세한 내용은 콘솔)");}
            work(()->{store.publish(next);return new Published(store.catalogRevision(),store.catalog());},v->{
                catalog=v.catalog();catalogRevision=v.revision();ChacaApi.ok(sender,"의뢰 "+v.catalog().size()+"개를 공용에 반영했어요 (수락한 의뢰는 기존 조건 유지)");
                var warnings=commandWarnings(v.catalog().values());if(!warnings.isEmpty())ChacaApi.lines(sender,warnings);
            },failTo(sender));return Command.SINGLE_SUCCESS;
        }),"quests.yml 초안을 검증해 공용에 반영","파일반영");

        // /유저관리 <닉네임> 의뢰기록 <보기|처리 <퀘스트> <주기> <완료처리|다시지급|되돌리기>>
        var resolve=Commands.argument("퀘스트",ChacaApi.word(()->{var s=new TreeSet<String>();audited.values().forEach(l->l.forEach(e->s.add(e[0])));return s;}))
            .then(Commands.argument("주기",ChacaApi.word(()->{var s=new TreeSet<String>();audited.values().forEach(l->l.forEach(e->s.add(e[1])));return s;}))
                .then(Commands.literal("완료처리").executes(ctx->{resolve(ctx,"delivered");return Command.SINGLE_SUCCESS;}))
                .then(Commands.literal("다시지급").executes(ctx->{resolve(ctx,"retry");return Command.SINGLE_SUCCESS;}))
                .then(Commands.literal("되돌리기").executes(ctx->{resolve(ctx,"reset");return Command.SINGLE_SUCCESS;})));
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("의뢰기록")
            .then(Commands.literal("보기").executes(ctx->{
                UUID id=ChacaApi.target(ctx);var sender=CodexCommands.sender(ctx);
                work(()->store.entries(id),v->{
                    var rows=v.stream().filter(e->e.status().equals("paying")).toList();
                    audited.put(sender.getName(),rows.stream().map(e->new String[]{e.quest().id(),e.cycle()}).toList());
                    var out=new ArrayList<String>();out.add("── "+ChacaApi.name(id)+" 님의 보상 확인 대기 "+rows.size()+"건 ──");
                    for(var e:rows){out.add(" "+e.quest().id()+" "+e.cycle()+" · "+e.quest().title());out.add("   → "+stageText(e.stage()));}
                    if(!rows.isEmpty())out.add(" 처리: /유저관리 "+ChacaApi.name(id)+" 의뢰기록 처리 <퀘스트> <주기> <완료처리|다시지급|되돌리기>");
                    ChacaApi.lines(sender,out);
                },failTo(sender));return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("처리").then(resolve)),"의뢰 보상 확인 대기 보기·처리","의뢰기록 보기");
    }
    private static String stack(ItemStack item){item.setAmount(1);return "stack:"+Base64.getEncoder().encodeToString(item.serializeAsBytes());}
    /** quests.yml 초안 고치기 (메인 스레드에서 파일을 쓰는 예전 방식 그대로). 공용 반영은 파일반영. */
    private void draftEdit(CommandSender s,String id,String action,String[] a)throws CommandSyntaxException{
        try{
            var y=new YamlConfiguration();y.load(file);String path="quests."+id;
            if(action.equals("create")){
                if(y.contains(path)||catalog.containsKey(id))throw ChacaApi.error("이미 있는 퀘스트 ID예요");
                y.set(path+".title",id);y.set(path+".description","");y.set(path+".enabled",false);y.set(path+".daily",true);y.set(path+".objectives",List.of(Map.of("type","KILL","target","ZOMBIE","amount",10,"label","좀비 처치")));y.set(path+".reward.label","에메랄드 3개");y.set(path+".reward.items",List.of("EMERALD:3"));
            }else{
                if(!y.contains(path))throw ChacaApi.error(catalog.containsKey(id)?"quests.yml 초안에 없는 의뢰예요. 먼저 파일로내보내기를 해 주세요":"없는 퀘스트예요");
                switch(action){
                    case "delete"->y.set(path,null);
                    case "set"->{
                        String key=a[0],v=a[1].strip();Object value;
                        switch(key){
                            case "daily","enabled"->{var b=bool(v);if(b==null)throw ChacaApi.error("켜기 또는 끄기로 입력해 주세요");value=b;}
                            case "completion-limit","reward.house-points"->{try{value=Integer.parseInt(v);}catch(NumberFormatException e){throw ChacaApi.error("숫자를 입력해 주세요");}}
                            case "reward.money"->{try{value=Double.parseDouble(v);}catch(NumberFormatException e){throw ChacaApi.error("숫자를 입력해 주세요");}}
                            case "rank"->{value=v.toUpperCase(Locale.ROOT);if(!((String)value).matches("[A-F]"))throw ChacaApi.error("등급은 A~F 중 하나예요");}
                            case "opens-at"->{if(v.equals("없음")||v.equals("0"))v="";else try{parseTime(v);}catch(RuntimeException e){throw ChacaApi.error("시간은 2026-10-20 18:00 처럼 입력해 주세요 (없애려면 '없음')");}value=v;}
                            default->value=v;
                        }
                        y.set(path+"."+key,value);
                    }
                    case "goal"->{
                        int index=Integer.parseInt(a[0])-1;var goals=new ArrayList<Map<?,?>>(y.getMapList(path+".objectives"));
                        if(index>goals.size())throw ChacaApi.error("목표는 "+(goals.size()+1)+"번까지 차례로 정해 주세요");
                        Type t=Type.valueOf(a[1]);if(t==Type.KILL&&Arrays.stream(EntityType.values()).noneMatch(e->e.name().equals(a[2])&&e.isAlive()))throw ChacaApi.error("처치 대상은 몹 종류(예: ZOMBIE)로 입력해 주세요");
                        if(t==Type.SUBMIT&&Material.matchMaterial(a[2])==null)throw ChacaApi.error("제출 대상은 아이템 종류(예: WHEAT)로 입력하거나 제출아이템 명령을 써 주세요");
                        var g=Map.of("type",t.name(),"target",a[2],"amount",Integer.parseInt(a[3]),"label",a[4]);
                        if(index==goals.size())goals.add(g);else goals.set(index,g);y.set(path+".objectives",goals);
                    }
                    case "submit"->{
                        var goals=new ArrayList<Map<?,?>>(y.getMapList(path+".objectives"));int index=Integer.parseInt(a[0])-1;
                        if(index<0||index>=goals.size())throw ChacaApi.error("목표 "+(index+1)+"번이 없어요 (지금 "+goals.size()+"개)");
                        if(a[2].length()>16000)throw ChacaApi.error("아이템 정보가 너무 커요");
                        goals.set(index,Map.of("type","SUBMIT","target",a[2],"amount",Integer.parseInt(a[1]),"label","지정 아이템 제출"));y.set(path+".objectives",goals);
                    }
                    case "rewarditem"->y.set(path+".reward.items",List.of(a[0]));
                    case "rewardcommand"->y.set(path+".reward.commands",List.of(a[0]));
                    default->throw new IllegalArgumentException(action);
                }
            }
            // 고친 것은 초안이다. 파일반영이 전체를 검증해 공용에 한 번에 반영한다.
            y.save(file);var ids=new HashSet<>(draftIds);if(action.equals("delete"))ids.remove(id);else ids.add(id);draftIds=Set.copyOf(ids);
            ChacaApi.ok(s,id+" 초안을 저장했어요 · /퀘스트관리 파일반영 으로 공용에 반영해 주세요");
            if(action.equals("rewardcommand")){String label=a[0].split(" ",2)[0];if(Bukkit.getCommandMap().getCommand(label)==null)ChacaApi.lines(s,List.of("주의: '"+label+"' 명령을 이 서버에서 찾을 수 없어요. 다른 서버 전용이 아니면 오타를 확인해 주세요"));}
        }catch(CommandSyntaxException e){throw e;}
        catch(Exception e){plugin.getLogger().warning("quests.yml 초안 저장 실패 ("+id+" "+action+"): "+e);throw ChacaApi.error("quests.yml 초안을 저장하지 못했어요 (자세한 내용은 콘솔)");}
    }
    private static Boolean bool(String v){return switch(v){case "켜기","예","네","true","사용"->true;case "끄기","아니오","false","미사용"->false;default->null;};}
    private void release(CommandSender sender,List<String> ids,long spacing,boolean enabled){
        long start=System.currentTimeMillis();
        work(()->store.release(ids,start,spacing,enabled),map->{catalog=map;for(String id:ids)try{saveDraft(id,map.get(id));}catch(Exception ex){plugin.getLogger().warning("Quest draft export failed: "+ex);}
            ChacaApi.ok(sender,spacing>0?ids.size()+"개 의뢰를 "+(spacing/1000)+"초 간격으로 차례로 공개하도록 예약했어요":enabled?"의뢰를 공개했어요":"새 수락을 닫았어요");},failTo(sender));
    }
    private void exportFile(CommandSender s){
        work(store::catalog,v->{try{var y=new YamlConfiguration();y.load(file);java.nio.file.Files.copy(file.toPath(),file.toPath().resolveSibling("quests-before-pull-"+System.currentTimeMillis()+".yml"));y.set("quests",null);for(var q:v.values()){String path="quests."+q.id();y.set(path+".rank",q.rank());y.set(path+".main",q.main());y.set(path+".requires",q.requires());y.set(path+".completion-limit",q.completionLimit());y.set(path+".opens-at",timeText(q.opensAt()));y.set(path+".reward.money",q.money());y.set(path+".reward.house-points",q.housePoints());y.set(path+".title",q.title());y.set(path+".description",q.description());y.set(path+".permission",q.permission());y.set(path+".enabled",q.enabled());y.set(path+".daily",q.daily());y.set(path+".objectives",q.objectives().stream().map(g->Map.of("type",g.type().name(),"target",g.target(),"amount",g.amount(),"label",g.label(),"server",g.server(),"world",g.world())).toList());y.set(path+".reward.label",q.rewardLabel());y.set(path+".reward.items",q.items());y.set(path+".reward.commands",q.commands());}y.save(file);draftIds=Set.copyOf(v.keySet());ChacaApi.ok(s,"공용 의뢰 "+v.size()+"개를 quests.yml 로 내보냈어요 (이전 파일은 백업)");}catch(Exception ex){ChacaApi.fail(s,"quests.yml 로 내보내지 못했어요",ex);}},failTo(s));
    }
    private void resolve(CommandContext<CommandSourceStack> ctx,String action)throws CommandSyntaxException{
        UUID id=ChacaApi.target(ctx);var s=CodexCommands.sender(ctx);String quest=ctx.getArgument("퀘스트",String.class),cycle=ctx.getArgument("주기",String.class),admin=s.getName();
        if(busy.contains(id))throw ChacaApi.error("이 유저의 의뢰를 지금 처리하는 중이에요. 잠시 뒤 다시 해 주세요");
        if(!action.equals("retry")){
            work(()->{store.resolve(id,quest,cycle,action.equals("delivered"));return true;},v->{if(action.equals("delivered"))fireCompleted(id,quest);
                plugin.getLogger().warning("QUEST ADMIN admin="+admin+" player="+id+" quest="+quest+" cycle="+cycle+" action="+action);
                ChacaApi.ok(s,action.equals("delivered")?"지급 완료로 닫았어요 (더 주지 않음)":"보상 받기 전으로 되돌렸어요. 유저가 처음부터 다시 받을 수 있어요");
                Player online=Bukkit.getPlayer(id);if(online!=null&&current(online))load(online,false,"");
            },failTo(s));return;
        }
        // 다시지급: 기록(stage)에 없는 단계만 실행한다. 유저가 이 서버에 있어야 한다.
        Player t=Bukkit.getPlayer(id);if(t==null||!current(t)||t.isDead()||t.getGameMode()==GameMode.SPECTATOR||!plugin.playerStateReady(t))throw ChacaApi.error("다시지급은 유저가 이 서버에 접속해 보상을 받을 수 있을 때만 할 수 있어요");
        busy.add(id);work(id,()->store.paying(id,quest,cycle),e->{
            if(e==null){busy.remove(id);ChacaApi.fail(s,"확인 대기 기록이 없어요",null);return;}
            if((e.stage()&QuestStore.RECORDED)==0){busy.remove(id);ChacaApi.fail(s,"지급 단계 기록이 없어 다시지급할 수 없어요. 받은 것을 확인한 뒤 완료처리 또는 되돌리기를 써 주세요",null);return;}
            if(!current(t)||t.isDead()||!plugin.playerStateReady(t)){busy.remove(id);ChacaApi.fail(s,"유저 상태가 바뀌었어요. 다시 해 주세요",null);return;}
            int house=plugin.questHouse(t);
            if(e.quest().housePoints()>0&&(e.stage()&QuestStore.HOUSE)==0&&house<0){busy.remove(id);ChacaApi.fail(s,"유저의 기숙사가 없어 기숙사 점수를 줄 수 없어요",null);return;}
            if(e.quest().money()>0&&(e.stage()&QuestStore.MONEY)==0&&economy()==null){busy.remove(id);ChacaApi.fail(s,"경제 연결(Vault)이 없어 돈을 줄 수 없어요",null);return;}
            ItemStack[] contents=(e.stage()&QuestStore.ITEMS)!=0?null:plan(t,e.quest());
            if((e.stage()&QuestStore.ITEMS)==0&&contents==null){busy.remove(id);ChacaApi.fail(s,"유저의 제출 아이템·장소 또는 인벤토리 공간이 맞지 않아요",null);return;}
            plugin.getLogger().warning("QUEST ADMIN admin="+admin+" player="+id+" quest="+quest+" cycle="+cycle+" action=retry token="+e.token()+" — "+stageText(e.stage()));
            pay(t,e,e.stage(),house,contents,()->{busy.remove(id);ChacaApi.ok(s,"남은 보상을 주고 완료 처리했어요");if(current(t))load(t,false,"의뢰 보상을 받았습니다.");},m->{busy.remove(id);ChacaApi.fail(s,"다시 멈췄어요. '보기'로 확인해 주세요: "+m,null);});
        },m->{busy.remove(id);ChacaApi.fail(s,m,null);});
    }
    @Override public void close(){
        // 모아 둔 진행도를 마지막으로 기록한다. (closing 을 올리기 전에 IO 대기열에 넣고, 아래에서 끝날 때까지 기다린다.)
        for(var entry:deltas.entrySet()){
            UUID id=entry.getKey();String sid=server;long since=entry.getValue().since;
            var list=entry.getValue().counts.entrySet().stream().map(e->new QuestStore.Delta(e.getKey().type(),e.getKey().target(),e.getKey().world(),e.getValue())).toList();
            try{io.execute(()->{try{store.advanceAll(id,list,sid,since);}catch(Exception e){plugin.getLogger().severe("Quest progress not saved at shutdown for "+id+": "+list+" ("+e+")");}});}catch(RejectedExecutionException ignored){}
        }
        deltas.clear();closing=true;io.shutdown();try{if(!io.awaitTermination(15,TimeUnit.SECONDS)){plugin.getLogger().severe("Quest worker shutdown timeout; pending payout records require review");return;}store.close();}catch(Exception e){plugin.getLogger().warning("Quest close: "+e);}HandlerList.unregisterAll(this);}
}
