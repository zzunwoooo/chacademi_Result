package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.TitleProtocol;

final class TitleBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;private final TitleStore store;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-title-io");t.setDaemon(true);return t;});
    private volatile Map<String,TitleDefinition> catalog=Map.of();
    private record Cached(TitleStore.State state,String nickname){}
    private record Session(Player player,String token,long until){}
    private record ViewRequest(Player player,boolean open,long sequence){}
    private final Map<UUID,Cached> cache=new ConcurrentHashMap<>();
    private final Map<UUID,Session> sessions=new HashMap<>();private final Map<UUID,Long> limits=new HashMap<>();
    private final Map<UUID,ViewRequest> deferred=new HashMap<>();
    private final Set<UUID> busy=new HashSet<>();private int pending;private volatile boolean closing;private TitleExpansion expansion;
    /** 획득 경로 설정 (title-awards.yml·player-presets.yml). settled: 이번 접속에서 장부 확인을 마친 키 (같은 키로 DB를 다시 부르지 않게). */
    private volatile TitleAwards awards=TitleAwards.EMPTY;private final Map<UUID,Set<String>> settled=new HashMap<>();
    TitleBridge(MagicCodexBridge plugin)throws Exception{
        this.plugin=plugin;for(String f:List.of("titles.yml","title-awards.yml","player-presets.yml"))if(!new File(plugin.getDataFolder(),f).exists())plugin.saveResource(f,false);reload();
        try{var settings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());store=io.submit(()->new TitleStore(settings,plugin.getDataFolder().toPath().resolve("titles.db"))).get(15,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
        Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getMessenger().registerIncomingPluginChannel(plugin,TitleProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,TitleProtocol.RESPONSE);
        CmdKit.register(plugin,"/칭호·/유저관리 칭호·/컨텐츠관리 칭호",this::commands);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){expansion=new TitleExpansion(this,plugin.getDescription().getVersion());if(!expansion.register())plugin.getLogger().warning("chacademiatitle 플레이스홀더 등록 실패");}
        Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers()){var old=cache.get(p.getUniqueId());if(old!=null)cache.put(p.getUniqueId(),new Cached(old.state(),plugin.names().name(p)));}long now=System.currentTimeMillis();sessions.values().removeIf(s->s.until()<now);limits.values().removeIf(t->t<now);},40,40);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers())if(!busy.contains(p.getUniqueId()))load(p,false,0,false);},600,600);
        for(Player p:Bukkit.getOnlinePlayers())load(p,false,0,false);
    }
    private Set<String> defaults(){return catalog.values().stream().filter(d->d.enabled()&&d.initial()).map(TitleDefinition::id).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private void reload()throws Exception{
        var y=new YamlConfiguration();y.load(new File(plugin.getDataFolder(),"titles.yml"));var root=y.getConfigurationSection("titles");if(root==null)throw new IllegalArgumentException("titles.yml 에 titles: 가 없어요");if(root.getKeys(false).size()>TitleProtocol.MAX_TITLES)throw new IllegalArgumentException("칭호가 너무 많아요 (최대 "+TitleProtocol.MAX_TITLES+"개)");var next=new LinkedHashMap<String,TitleDefinition>();
        for(String id:root.getKeys(false)){var s=root.getConfigurationSection(id);if(s==null)throw new IllegalArgumentException(id);int side=switch(s.getString("side","")){case "prefix"->0;case "suffix"->1;default->throw new IllegalArgumentException("칭호 side 확인: "+id);};String hex=s.getString("color",side==0?"#96E4EC":"#DEC58E");if(!hex.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("칭호 color 확인: "+id);next.put(id,new TitleDefinition(id,side,s.getString("name",id),Integer.parseInt(hex.substring(1),16),s.getBoolean("enabled",true),s.getBoolean("initial",false)));}
        TitleAwards nextAwards;
        try{var awardsFile=new YamlConfiguration();awardsFile.load(new File(plugin.getDataFolder(),"title-awards.yml"));var presetFile=new YamlConfiguration();presetFile.load(new File(plugin.getDataFolder(),"player-presets.yml"));nextAwards=TitleAwards.parse(awardsFile,presetFile);}
        catch(Exception error){
            // 시작할 때 획득 경로 파일 오류로 칭호(와 플러그인 전체)가 꺼지지 않게: 지급만 멈추고 알린다. 새로고침 때는 실패로 돌려 이전 설정을 유지.
            if(catalog.isEmpty()){plugin.getLogger().severe("title-awards.yml·player-presets.yml 읽기 실패 — 칭호 자동 지급·사전 설정을 멈춥니다: "+error.getMessage());nextAwards=TitleAwards.EMPTY;}
            else throw error;
        }
        npcTitles(next,nextAwards);
        catalog=Collections.unmodifiableMap(next);awards=nextAwards;settled.clear();
        var missing=new TreeSet<String>();for(String id:nextAwards.referencedTitles())if(!next.containsKey(id))missing.add(id);
        if(!missing.isEmpty())plugin.getLogger().warning("title-awards.yml·player-presets.yml 이 titles.yml 에 없는 칭호를 가리켜요 (지급 안 함): "+missing);
    }
    /**
     * NPC별 자동 접두 칭호 (오너 결정 2026-10-25: 호감도 30 → "리아의"). ChacaNPC 의 npcs/*.yml 에서 id·name 을 읽어 prefix_npc_<id> 를 만든다.
     * titles.yml 에 같은 ID 가 있으면 그쪽을 쓴다. ChacaNPC 가 없는 서버(야생·던전)도 폴더가 있으면 읽고, 없으면 건너뛴다 (그 서버에선 NPC 칭호를 주지 않음 → 학교에서 줌).
     */
    private void npcTitles(Map<String,TitleDefinition> next,TitleAwards a){
        if(a.npcFormat.isEmpty())return;
        var chaca=Bukkit.getPluginManager().getPlugin("ChacaNPC");
        File folder=chaca!=null?new File(chaca.getDataFolder(),"npcs"):new File(plugin.getDataFolder().getParentFile(),"ChacaNPC/npcs");
        File[] files=folder.listFiles((d,n)->n.endsWith(".yml"));if(files==null)return;Arrays.sort(files);int made=0;
        for(File file:files){
            try{var y=YamlConfiguration.loadConfiguration(file);String npc=y.getString("id","").trim(),name=y.getString("name","").trim();if(npc.isEmpty()||name.isEmpty())continue;
                String id=a.npcTitle(npc);if(id==null||!id.startsWith(TitleAwards.NPC_PREFIX)||next.containsKey(id))continue;
                next.put(id,new TitleDefinition(id,0,a.npcTitleName(name),a.npcColor,true,false));made++;}
            catch(IllegalArgumentException bad){plugin.getLogger().warning("NPC 칭호를 만들지 못했어요 ("+file.getName()+"): 이름·ID 확인");}
        }
        if(made>0)plugin.getLogger().info("NPC 호감도 칭호 "+made+"개 준비 (prefix_npc_*)");
    }
    private boolean current(Player p){return !closing&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p;}
    private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,r);}
    private <T>void work(Callable<T> job,Consumer<T> done,Runnable fail){
        if(closing||pending>=128){fail.run();return;}pending++;
        io.execute(()->{try{T value=job.call();main(()->{pending--;done.accept(value);});}catch(Exception e){plugin.getLogger().warning("칭호 DB 처리 실패: "+e.getClass().getSimpleName()+": "+e.getMessage());main(()->{pending--;fail.run();});}});
    }
    private void load(Player p,boolean open,long sequence,boolean reply){
        UUID id=p.getUniqueId();if(!current(p))return;if(busy.contains(id)){if(reply)deferred.put(id,new ViewRequest(p,open,sequence));return;}busy.add(id);var defaults=defaults();
        work(()->store.load(id,defaults),state->{if(current(p)){busy.remove(id);cache.put(id,new Cached(state,plugin.names().name(p)));var next=deferred.remove(id);if(next!=null&&next.player()==p)reply(p,next.open(),next.sequence(),"");else if(reply)reply(p,open,sequence,"");}},()->{if(current(p)){busy.remove(id);var next=deferred.remove(id);if(reply||next!=null)CmdKit.notify(p,"warning","title-load","칭호 정보를 불러오지 못했어요","잠시 뒤 다시 열어 주세요",3000);}});
    }
    @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer(),false,0,false);var p=e.getPlayer();Bukkit.getScheduler().runTaskLater(plugin,()->{if(current(p))applyPreset(p);},60);}
    @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();cache.remove(id);settled.remove(id);busy.remove(id);sessions.remove(id);limits.remove(id);deferred.remove(id);}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(!TitleProtocol.REQUEST.equals(channel)||!current(p)||!p.hasPermission("magiccodex.titles"))return;
        TitleProtocol.Request r;try{r=TitleProtocol.request(bytes);}catch(IllegalArgumentException e){return;}
        UUID id=p.getUniqueId();if(r.action()==TitleProtocol.CLOSE){var s=sessions.get(id);if(s!=null&&s.token().equals(r.token()))sessions.remove(id);return;}
        long now=System.currentTimeMillis();if(now<limits.getOrDefault(id,0L))return;limits.put(id,now+200);
        if(r.action()==TitleProtocol.OPEN){load(p,false,r.sequence(),true);return;}
        if(busy.contains(id))return;
        var session=sessions.get(id);if(session==null||session.player()!=p||session.until()<now||!session.token().equals(r.token())){CmdKit.notify(p,"warning","title-session","칭호창을 다시 열어 주세요","",2000);return;}
        if(p.isDead()||p.getGameMode()==GameMode.SPECTATOR){reply(p,false,r.sequence(),"지금은 변경할 수 없습니다.");return;}
        var snapshot=catalog;var defaults=defaults();busy.add(id);
        work(()->store.select(id,r.revision(),r.prefix(),r.suffix(),snapshot,defaults),result->{if(current(p)){busy.remove(id);cache.put(id,new Cached(result.state(),plugin.names().name(p)));reply(p,false,r.sequence(),result.applied()?"칭호를 적용했습니다.":"보유 칭호가 변경되었습니다. 다시 선택해 주세요.");var next=deferred.remove(id);if(next!=null&&next.player()==p)reply(p,next.open(),next.sequence(),"");if(result.applied())p.playSound(p.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.35f,1.3f);}},()->{if(current(p)){busy.remove(id);reply(p,false,r.sequence(),"저장하지 못했습니다. 다시 시도해 주세요.");var next=deferred.remove(id);if(next!=null&&next.player()==p)reply(p,next.open(),next.sequence(),"저장하지 못했습니다.");}});
    }
    private String selected(TitleStore.State s,String id,int side){var d=catalog.get(id);return d!=null&&d.enabled()&&d.side()==side&&s.owned().contains(id)?id:"";}
    private void reply(Player p,boolean open,long sequence,String message){
        var cached=cache.get(p.getUniqueId());if(cached==null||!current(p))return;
        if(!p.getListeningPluginChannels().contains(TitleProtocol.RESPONSE)){if(open)CmdKit.notify(p,"warning","title-mod","칭호창을 열 수 없어요","차카데미 모드를 업데이트해 주세요",3000);return;}
        var old=sessions.get(p.getUniqueId());String token=old!=null&&old.player()==p&&old.until()>System.currentTimeMillis()?old.token():UUID.randomUUID().toString();sessions.put(p.getUniqueId(),new Session(p,token,System.currentTimeMillis()+90000));
        var s=cached.state();var entries=catalog.values().stream().filter(d->d.enabled()&&s.owned().contains(d.id())).map(d->new TitleProtocol.Entry(d.id(),d.side(),d.name(),d.color())).toList();
        p.sendPluginMessage(plugin,TitleProtocol.RESPONSE,TitleProtocol.encode(new TitleProtocol.Response(open,sequence,token,s.revision(),plugin.names().name(p),selected(s,s.prefix(),0),selected(s,s.suffix(),1),message,entries)));
    }
    /** Read-only view of the same validated equipped slots used by placeholders and the title screen. */
    List<TitleDefinition> nameplateTitles(UUID id){
        var c=cache.get(id);if(c==null)return List.of();var state=c.state();var out=new ArrayList<TitleDefinition>();
        for(var slot:List.of(Map.entry(state.prefix(),0),Map.entry(state.suffix(),1))){String valid=selected(state,slot.getKey(),slot.getValue());var definition=catalog.get(valid);if(definition!=null)out.add(definition);}
        return List.copyOf(out);
    }
    TitleDefinition nameplateDefinition(String id){var d=catalog.get(id);return d!=null&&d.enabled()?d:null;}
    String placeholder(UUID id,String field){var c=cache.get(id);if(c==null)return "";var s=c.state();var prefix=catalog.get(selected(s,s.prefix(),0));var suffix=catalog.get(selected(s,s.suffix(),1));String a=prefix==null?"":prefix.name(),b=suffix==null?"":suffix.name();return switch(field){case "prefix"->a;case "suffix"->b;case "nickname"->c.nickname();case "full"->TitleDefinition.composed(a,c.nickname(),b);case "prefix_id"->prefix==null?"":prefix.id();case "suffix_id"->suffix==null?"":suffix.id();case "full_colored"->TitleDefinition.composed(prefix==null?"":colored(prefix),"§r"+c.nickname(),suffix==null?"":colored(suffix))+"§r";default->null;};}
    private static String colored(TitleDefinition d){var out=new StringBuilder("§x");for(char c:String.format(Locale.ROOT,"%06x",d.color()).toCharArray())out.append('§').append(c);return out.append(d.name()).append("§r").toString();}
    /** Trusted server-thread hook; return callback after durable commit. */
    void grant(UUID id,String title,boolean remove,Consumer<Boolean> done){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Use server thread");if(!AdminCommandRules.titleAllowed(catalog.get(title),remove)){done.accept(false);return;}var defaults=defaults();
        work(()->remove?store.revoke(id,title,defaults):store.grant(id,title,defaults),state->{Player p=Bukkit.getPlayer(id);if(p!=null&&current(p)){cache.put(id,new Cached(state,plugin.names().name(p)));if(sessions.containsKey(id))reply(p,false,0,remove?"칭호가 회수되었습니다.":"새 칭호를 획득했습니다.");}
            var d=catalog.get(title);AuditLog.log(id,AuditLog.칭호,(remove?"칭호 회수 · ":"칭호 지급 · ")+(d==null?title:d.name()),"칭호",title,"이름",d==null?title:d.name(),"경로",remove?"회수":"지급");done.accept(true);},()->done.accept(false));
    }
    // ------------------------------------------------------------------ 획득 경로 (2026-10-25, 메모 #21·#89)
    /** 서버 스레드. 마법 발견(MagicDiscovery가 발견 직후·접속 로드 때 부름). 설정에 없는 마법은 DB를 부르지 않는다. */
    void discovered(UUID id,Collection<String> spells){award(id,awards.discoveryKeys(spells));}
    /** 서버 스레드. NPC 호감도가 바뀌었거나 접속 때 불러온 점수. */
    void affinity(UUID id,Map<String,Integer> scores){award(id,awards.affinityKeys(scores));}
    /** 서버 스레드. 물고기를 잡아 수조로 보냈을 때. */
    void fish(UUID id,String fish,double cm){award(id,awards.fishKeys(fish,cm));}
    /**
     * 장부(codex_title_awards)에 처음 적힌 키의 칭호만 준다: 학교·야생이 같은 DB면 어디서 받든 한 번, 회수한 칭호도 다시 주지 않는다.
     * 오프라인이어도 UUID로 저장한다 (알림만 생략). 실패한 키는 settled 에 넣지 않아 다음 사건·다음 접속 때 다시 시도한다.
     */
    private void award(UUID id,Map<String,String> keyed){
        // 다른 기능(낚시·호감도) 처리 중에 예외를 던지지 않는다: 서버 스레드가 아니면 지급을 건너뛰고 다음 사건·접속 때 다시 맞춘다.
        if(!Bukkit.isPrimaryThread()||closing||keyed.isEmpty())return;
        var done=settled.get(id);var wanted=new LinkedHashMap<String,String>();var snapshot=catalog;
        keyed.forEach((key,title)->{var d=snapshot.get(title);if(d!=null&&d.enabled()&&(done==null||!done.contains(key)))wanted.put(key,title);});
        if(wanted.isEmpty())return;var defaults=defaults();
        work(()->store.award(id,wanted,defaults),result->{
            Player p=Bukkit.getPlayer(id);
            if(p!=null&&current(p)){settled.computeIfAbsent(id,k->new HashSet<>()).addAll(wanted.keySet());cache.put(id,new Cached(result.state(),plugin.names().name(p)));
                for(String t:result.granted()){var d=snapshot.get(t);CmdKit.notify(p,"shop","title-award:"+t,"새 칭호를 얻었어요",d==null?t:d.name(),0);}
                if(!result.granted().isEmpty()){if(sessions.containsKey(id))reply(p,false,0,"새 칭호를 획득했습니다.");p.playSound(p.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.35f,1.3f);}}
            if(!result.granted().isEmpty())plugin.getLogger().info("칭호 획득: "+id+" "+result.granted()+" ← "+wanted.keySet());
            for(String t:result.granted()){var d=snapshot.get(t);AuditLog.log(id,AuditLog.칭호,"칭호 획득 · "+(d==null?t:d.name()),"칭호",t,"이름",d==null?t:d.name(),"경로",String.join(",",wanted.entrySet().stream().filter(e->e.getValue().equals(t)).map(Map.Entry::getKey).toList()));}
        },()->{});
    }
    /**
     * player-presets.yml (메모 #89): 접속 3초 뒤 한 번. 기숙사(미리 정함 = 분류 거울 창시자 연출)·사전 해금 마법은 적용이 끝난 뒤 장부에 적고,
     * 칭호는 장부와 함께 준다. 적용 중 서버가 꺼져도 다음 접속 때 같은 값으로 다시 적용(멱등)하고, 적용이 끝난 항목은 다시 하지 않는다.
     */
    private void applyPreset(Player p){
        var preset=awards.preset(p.getUniqueId(),p.getName());if(preset==null)return;UUID id=p.getUniqueId();
        var titleKeys=new LinkedHashMap<String,String>();for(String t:preset.titles())titleKeys.put("preset.title."+t,t);award(id,titleKeys);
        var keys=new ArrayList<String>();if(preset.house()>=0)keys.add("preset.house");for(String s:preset.spells())keys.add("preset.spell."+s);if(keys.isEmpty())return;
        work(()->store.unmarked(id,keys),todo->{
            if(!current(p))return;
            if(todo.contains("preset.house")){var school=plugin.schoolBridge();
                if(school==null)plugin.getLogger().warning("사전 설정 기숙사: 학교 기능이 꺼져 있어 "+p.getName()+" 님에게 적용하지 못했어요 (다음 접속 때 다시 시도)");
                else school.adminHouse(p,preset.house(),error->{if(error==null)mark(id,"preset.house");else plugin.getLogger().warning("사전 설정 기숙사 적용 실패 ("+p.getName()+"): "+error);});}
            for(String s:preset.spells())if(todo.contains("preset.spell."+s))DiscoveryLink.adminSetLearned(id,s,true,error->{if(error==null)mark(id,"preset.spell."+s);else plugin.getLogger().warning("사전 해금 마법 "+s+" 적용 실패 ("+p.getName()+"): "+error);});
        },()->{});
    }
    private void mark(UUID id,String key){work(()->{store.mark(id,key);return true;},ok->plugin.getLogger().info("사전 설정 적용: "+id+" "+key),()->plugin.getLogger().warning("사전 설정 적용 기록 실패 (다음 접속 때 같은 값으로 다시 적용): "+id+" "+key));}
    Map<String,String> registeredTitles(boolean remove){
        var result=new LinkedHashMap<String,String>();catalog.values().stream().filter(d->AdminCommandRules.titleAllowed(d,remove)).forEach(d->result.put(d.id(),d.name()));return Map.copyOf(result);
    }
    // ------------------------------------------------------------------ 명령 (2026-10-20)

    private void commands(){
        ChacaApi.playerCommand(plugin,CmdKit.literal("칭호").executes(CmdKit.run(ctx->{Player p=CmdKit.self(ctx);if(p.hasPermission("magiccodex.titles"))load(p,true,0,true);}))
                .then(CmdKit.literal("수령").executes(CmdKit.run(ctx->claimDiscovery(CmdKit.self(ctx))))),"칭호창 열기 · 수령: 마법 발견 칭호 보상 받기",List.of());
        var title=CmdKit.word("칭호",()->AdminCommandRules.catalogChoices(registeredTitles(false)));
        var revoke=CmdKit.word("칭호",()->AdminCommandRules.catalogChoices(registeredTitles(true)));
        var equip=CmdKit.literal("장착");
        for(int side=0;side<2;side++){
            int s=side;
            equip.then(CmdKit.literal(side==0?"앞":"뒤")
                    .then(CmdKit.literal("비우기").executes(CmdKit.run(ctx->equip(ctx,s,""))))
                    .then(CmdKit.word("칭호",()->AdminCommandRules.catalogChoices(sideTitles(s))).executes(CmdKit.run(ctx->equip(ctx,s,CmdKit.pick(sideTitles(s),CmdKit.text(ctx,"칭호"),"칭호"))))));
        }
        var user=CmdKit.literal("칭호")
                .then(CmdKit.literal("보기").executes(CmdKit.run(this::show)))
                .then(CmdKit.literal("주기").then(title.executes(CmdKit.run(ctx->change(ctx,false)))))
                .then(CmdKit.literal("회수").then(revoke.executes(CmdKit.run(ctx->change(ctx,true)))))
                .then(equip);
        ChacaApi.branch(plugin,ChacaApi.유저관리,user,"칭호 보기·주기·회수·장착","칭호 보기");
        ChacaApi.branch(plugin,ChacaApi.컨텐츠관리,CmdKit.literal("칭호").then(CmdKit.literal("목록").executes(CmdKit.run(ctx->{
            var out=new ArrayList<String>();out.add("── 칭호 목록 ("+catalog.size()+"개) ──");
            for(var d:catalog.values())out.add((d.side()==0?"[앞] ":"[뒤] ")+d.name()+"  ("+d.id()+")"+(d.enabled()?"":" · 꺼짐")+(d.initial()?" · 기본 지급":""));
            CmdKit.lines(CmdKit.sender(ctx),out);
        }))),"등록된 칭호 목록","칭호 목록");
        ChacaApi.reload(plugin,"칭호","titles.yml (칭호 목록)·title-awards.yml (획득 경로)·player-presets.yml (사전 설정)",()->{reload();for(Player p:Bukkit.getOnlinePlayers())if(sessions.containsKey(p.getUniqueId()))reply(p,false,0,"");});
        ChacaApi.summary(plugin,"칭호",id->{var c=cache.get(id);if(c==null)return "";var st=c.state();var a=catalog.get(selected(st,st.prefix(),0));var b=catalog.get(selected(st,st.suffix(),1));return "보유 "+st.owned().size()+"개 · 앞 "+(a==null?"없음":a.name())+" · 뒤 "+(b==null?"없음":b.name());});
    }
    /** /칭호 수령: MagicDiscovery 플러그인의 public claimTitles(Player)를 부른다. */
    private void claimDiscovery(Player p)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var discovery=Bukkit.getPluginManager().getPlugin("MagicDiscovery");
        if(discovery==null||!discovery.isEnabled())throw ChacaApi.error("지금은 칭호 보상을 받을 수 없어요");
        java.lang.reflect.Method claim;
        try{claim=discovery.getClass().getMethod("claimTitles",Player.class);}
        catch(NoSuchMethodException missing){plugin.getLogger().warning("MagicDiscovery.claimTitles(Player) 가 없습니다. MagicDiscovery를 업데이트하세요.");throw ChacaApi.error("지금은 칭호 보상을 받을 수 없어요");}
        try{claim.invoke(discovery,p);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("MagicDiscovery.claimTitles failed",error.getCause()==null?error:error.getCause());}
    }
    private Map<String,String> sideTitles(int side){var out=new LinkedHashMap<String,String>();catalog.values().stream().filter(d->d.enabled()&&d.side()==side).forEach(d->out.put(d.id(),d.name()));return out;}
    private void show(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        UUID id=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);String who=CmdKit.name(id);var defaults=defaults();var snapshot=catalog;
        work(()->store.load(id,defaults),st->{
            var out=new ArrayList<String>();out.add("── "+who+" 칭호 ──");
            var a=snapshot.get(st.prefix());var b=snapshot.get(st.suffix());
            out.add("장착: 앞 "+(a==null?"없음":a.name())+" · 뒤 "+(b==null?"없음":b.name()));
            var owned=new ArrayList<String>();for(String t:new TreeSet<>(st.owned())){var d=snapshot.get(t);owned.add(d==null?t:d.name());}
            out.add("보유 "+owned.size()+"개: "+(owned.isEmpty()?"없음":String.join(", ",owned)));
            CmdKit.lines(sender,out);
        },()->CmdKit.fail(sender,"칭호 정보를 불러오지 못했어요",null));
    }
    private void change(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,boolean remove)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        UUID id=ChacaApi.target(ctx);var registered=registeredTitles(remove);String title=CmdKit.pick(registered,CmdKit.text(ctx,"칭호"),remove?"칭호":"사용 중인 칭호");
        var sender=CmdKit.sender(ctx);String who=CmdKit.name(id);
        grant(id,title,remove,ok->{if(ok)CmdKit.ok(sender,who+" 님"+(remove?"의 ":"에게 ")+registered.get(title)+" 칭호를 "+(remove?"회수했어요":"주었어요"));else CmdKit.fail(sender,"칭호를 저장하지 못했어요",null);});
    }
    /** 관리자 장착: 보유한 칭호만, 빈 문자열 = 비우기. 다른 쪽 칸은 그대로. */
    private void equip(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,int side,String title)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        UUID id=ChacaApi.target(ctx);var sender=CmdKit.sender(ctx);String who=CmdKit.name(id);var snapshot=catalog;var defaults=defaults();
        work(()->{var st=store.load(id,defaults);if(!title.isEmpty()&&!st.owned().contains(title))return null;
            return store.select(id,st.revision(),side==0?title:st.prefix(),side==1?title:st.suffix(),snapshot,defaults);},result->{
            if(result==null){CmdKit.userFail(sender,who+" 님이 가진 칭호가 아니에요");return;}
            if(!result.applied()){CmdKit.userFail(sender,"칭호가 방금 바뀌었어요. 다시 해 주세요");return;}
            Player p=Bukkit.getPlayer(id);if(p!=null&&current(p)){cache.put(id,new Cached(result.state(),plugin.names().name(p)));if(sessions.containsKey(id))reply(p,false,0,"");}
            var d=snapshot.get(title);CmdKit.ok(sender,who+" 님 "+(side==0?"앞":"뒤")+" 칭호 "+(d==null?"비움":"= "+d.name()));
        },()->CmdKit.fail(sender,"칭호를 저장하지 못했어요",null));
    }
    @Override public void close(){closing=true;if(expansion!=null)expansion.unregister();io.execute(()->{try{store.close();}catch(Exception e){plugin.getLogger().warning("칭호 저장소 종료 실패");}});io.shutdown();try{if(!io.awaitTermination(20,TimeUnit.SECONDS))plugin.getLogger().severe("칭호 DB 종료 대기 초과");}catch(InterruptedException e){Thread.currentThread().interrupt();}cache.clear();sessions.clear();busy.clear();settled.clear();}
}
