package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.ManaProtocol;
import school.magiccodex.protocol.ManaProtocol.*;

final class ManaBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    final ManaService mana;
    private ManaSpells spells;
    private final GeneralPresetBridge presets;
    java.util.function.Consumer<Player> windCast;
    /** 아공간 수조 (fish_trap, 2026-10-20): opens the tank window; false = not opened, no mana spent. */
    java.util.function.Predicate<Player> fishTrapCast;
    Response castWindForFriend(Player p){var result=cast(p,0,spells.byId.get("wind_message"),()->true);send(p,result);return result;}
    /**
     * 공간 도약 (spatial_leap): casting it saves the current location as the personal point (TravelBridge). Owner
     * 2026-10-24: 위치 지정은 마나·쿨 없음 — mana-spells.yml gives the spell mana-cost 0 / cooldown 0, so this cast charges
     * nothing. Must answer synchronously: true = accepted.
     */
    java.util.function.Predicate<Player> spatialLeapCast;
    static final String SPATIAL_LEAP="spatial_leap";
    /** Cooldown key of moving to the personal point (its own timer: it does not lock the spell itself). */
    static final String PERSONAL_TRAVEL="spatial_leap_travel";
    /**
     * 지도에서 개인 지점으로 이동 (owner 2026-10-24: 이동시에만 마나·쿨). The same mana transaction a cast uses (spell learned,
     * cooldown, mana) with the travel.yml cost/cooldown under its own cooldown key; not a cast (no discovery cast
     * counter, no artifact on-cast effects). Returns a ManaProtocol status (OK = charged); null = the spell is unknown.
     */
    Integer chargePersonalTravel(Player p,double cost,int cooldownSeconds){
        var spell=spells.byId.get(SPATIAL_LEAP);if(spell==null)return null;
        UUID id=p.getUniqueId();
        if(!plugin.playerStateReady(p)||casting.contains(id))return ManaProtocol.FAILED;
        var a=mana.account(id);
        var travel=new ManaSpells.Spell(PERSONAL_TRAVEL,spell.command(),spell.permission(),cost,cooldownSeconds*1000);
        var r=ManaCasting.attempt(a,travel,!p.isDead()&&p.hasPermission(spell.permission()),System.currentTimeMillis(),()->true);
        mana.publish(id);
        if(r.status()==ManaProtocol.OK){mana.save(p);plugin.markPlayerStateDirty(p);}
        var snap=mana.snapshot(id);if(snap.isPresent())send(p,new Response(0,ManaProtocol.SNAPSHOT,0,snap.get()));
        return r.status();
    }
    /** Seconds left before the personal point can be used again (for the message). */
    long personalTravelCooldown(Player p){try{return mana.cooldownRemaining(p.getUniqueId(),PERSONAL_TRAVEL);}catch(IllegalArgumentException notLoaded){return 0;}}
    /** Gives back one personal-point move (it failed afterwards): mana back and its cooldown cleared. */
    void refundPersonalTravel(Player p,double cost){
        if(!p.isOnline())return;
        try{mana.add(p.getUniqueId(),cost);mana.clearCooldown(p.getUniqueId(),PERSONAL_TRAVEL);}catch(IllegalArgumentException notLoaded){return;}
        mana.save(p);plugin.markPlayerStateDirty(p);
        var a=mana.snapshot(p.getUniqueId());if(a.isPresent())send(p,new Response(0,ManaProtocol.SNAPSHOT,0,a.get()));
    }
    List<String> discoveryPermissions(){var result=new ArrayList<>(spells.discoveryPermissions);if(!result.contains("magiccodex.taming"))result.add("magiccodex.taming");return List.copyOf(result);}
    /** MythicMobs 실행 경로(SpellRuntime)가 켜져 있으면 그 새로고침·경로 수. MythicMobs 클래스를 여기서 직접 부르지 않으려고 따로 둔다. */
    record Runtime(ChacaApi.ThrowingRunnable reload,java.util.function.IntSupplier routes,Object owner){}
    static volatile Runtime runtime;
    private ManaExpansion expansion;private boolean expansionRegistered;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Set<UUID> casting=new HashSet<>();
    private int ticks;
    private static class Session {
        long expires,nextSubscribe,lastSequence,window;int requests;Snapshot last;
        boolean allow(long now){if(now-window>=1000){window=now;requests=0;}return ++requests<=8;}
    }
    private static long now(){return System.nanoTime()/1_000_000;}
    ManaBridge(MagicCodexBridge plugin)throws Exception{
        this.plugin=plugin;mana=new ManaService(plugin);presets=new GeneralPresetBridge(plugin,mana,casting::contains);
        if(!new File(plugin.getDataFolder(),"mana-spells.yml").exists())plugin.saveResource("mana-spells.yml",false);
        spells=ManaSpells.load(new File(plugin.getDataFolder(),"mana-spells.yml"));
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,ManaProtocol.REQUEST,this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,ManaProtocol.RESPONSE);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,ManaProtocol.COOLDOWN_RESET);
        mana.cooldownCleared=(id,spell)->{var p=Bukkit.getPlayer(id);if(p!=null)p.sendPluginMessage(plugin,ManaProtocol.COOLDOWN_RESET,ManaProtocol.cooldownReset(spell));};
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getServicesManager().register(ManaService.class,mana,plugin,ServicePriority.Normal);
        registerAdmin();
        if(ChacaApi.ready()){review=new SpellReviewRunner(plugin,this);CmdKit.register(plugin,"/테스트 마법 자동시전",review::register);}
        for(Player p:Bukkit.getOnlinePlayers())mana.join(p);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){
            // 닉네임 확장이 이미 "magiccodex" 식별자를 소유하면 그쪽에 위임한다. 같은 식별자를 다시 등록하면 PlaceholderAPI가 기존 확장을 내린다.
            expansion=new ManaExpansion(mana);
            if(plugin.names()==null||!plugin.names().delegatePlaceholders(expansion::onRequest)){
                // 닉네임 확장이 식별자를 갖지 못한 경우: 이 확장이 등록하고 nickname/account도 대신 답한다.
                if(plugin.names()!=null)expansion.fallback(plugin.names()::placeholder);
                expansionRegistered=expansion.register();
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }
    private void tick(){
        mana.tick();long time=now();
        sessions.entrySet().removeIf(e->e.getValue().expires<time);
        for(var e:sessions.entrySet()){
            var p=Bukkit.getPlayer(e.getKey());if(p==null)continue;
            var s=mana.snapshot(e.getKey()).orElse(null);
            if(s!=null&&!s.equals(e.getValue().last))send(p,new Response(0,ManaProtocol.SNAPSHOT,0,s));
        }
        if(++ticks%60==0)mana.saveDirty();
    }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){mana.join(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){mana.quit(e.getPlayer());sessions.remove(e.getPlayer().getUniqueId());casting.remove(e.getPlayer().getUniqueId());}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] data){
        if(!channel.equals(ManaProtocol.REQUEST))return;
        final Request r;try{r=ManaProtocol.decodeRequest(data);}catch(IllegalArgumentException e){return;}
        Session session=sessions.computeIfAbsent(p.getUniqueId(),id->new Session());long time=now();
        if(!session.allow(time))return;
        if(r.sequence()==0){
            if(time<session.nextSubscribe)return;session.nextSubscribe=time+1000;session.expires=time+65000;
            send(p,new Response(0,ManaProtocol.SNAPSHOT,0,mana.account(p.getUniqueId()).snapshot()));
        }else{
            if(r.sequence()<=session.lastSequence)return;session.lastSequence=r.sequence();session.expires=time+65000;
            send(p,cast(p,r.sequence(),spells.forCast(r.spell())));
        }
    }
    private void send(Player p,Response response){
        if(!p.getListeningPluginChannels().contains(ManaProtocol.RESPONSE))return;
        p.sendPluginMessage(plugin,ManaProtocol.RESPONSE,ManaProtocol.encode(response));
        var session=sessions.get(p.getUniqueId());if(session!=null)session.last=response.mana();
    }
    private Response cast(Player p,long sequence,ManaSpells.Spell spell){
        return cast(p,sequence,spell,null);
    }
    Map<String,Map<String,String>> registeredSpellCatalog(){return spells.registeredCatalog;}
    boolean isCatalogMode(){return spells.catalogMode;}
    boolean requiresCatalogVisual(String id){return spells.catalogMode&&!TamingSpell.matches(id)&&spells.byId.containsKey(id);}
    ManaSpells.Spell tamingSpell(){return spells.forCast(TamingSpell.ID);}
    /** /테스트 마법 자동시전: 활성 마법 ID (mana-spells.yml 순서), 그 항목, 같은 시전 경로의 결과 상태(알림·전송 포함). */
    List<String> reviewOrder(){return spells.order;}
    ManaSpells.Spell reviewSpell(String id){return spells.forCast(id);}
    int castForReview(Player player,String id){
        var response=cast(player,0,spells.forCast(id));send(player,response);return response.status();
    }
    boolean castRegistered(Player player,String id){
        var spell=spells.forCast(id);var response=cast(player,0,spell);send(player,response);
        if(response.status()!=ManaProtocol.OK&&response.status()!=ManaProtocol.UNAVAILABLE&&!TamingSpell.matches(id))CodexCommands.warn(player,"magic-fail",message(response.status()));
        return response.status()==ManaProtocol.OK;
    }
    private Response cast(Player p,long sequence,ManaSpells.Spell spell,java.util.function.BooleanSupplier internal){
        UUID id=p.getUniqueId();var a=mana.account(id);
        if(!plugin.playerStateReady(p))return new Response(sequence,ManaProtocol.FAILED,0,a.snapshot());
        if(casting.contains(id))return new Response(sequence,ManaProtocol.FAILED,0,a.snapshot());
        // Taming owns target validation, locks, the one mana transaction and persisted result.
        // Never wrap this route in ManaCasting.attempt or the same cast would be charged twice.
        if(spell!=null&&TamingSpell.matches(spell.id())){
            var result=plugin.castTamingResult(p);
            return new Response(sequence,result.status(),result.cooldownMillis(),a.snapshot());
        }
        if(spell!=null&&internal==null){
            var attempt=new MagicCastAttemptEvent(p,spell.id());
            Bukkit.getPluginManager().callEvent(attempt);
            if(attempt.isCancelled()){
                if(attempt.getMessage()!=null&&!attempt.getMessage().isBlank())CodexCommands.warn(p,"magic-refused",attempt.getMessage());
                return new Response(sequence,ManaProtocol.UNAVAILABLE,0,a.snapshot());
            }
        }
        if(spell!=null&&internal==null&&BroomCastGuard.endFlight(p,spell.id()))return new Response(sequence,ManaProtocol.OK,0,a.snapshot());
        casting.add(id);
        ManaCasting.Result result;
        try {
            boolean permitted=spell!=null&&!p.isDead()&&p.getGameMode()!=org.bukkit.GameMode.SPECTATOR&&p.hasPermission(spell.permission());
            result=ManaCasting.attempt(a,spell,permitted,System.currentTimeMillis(),spell!=null&&BroomCastGuard.blocked(p,spell.id()),()->{
                mana.publish(id);
                if(internal!=null)return internal.getAsBoolean();
                if(!MagicProtectionLink.cast(p)){CodexCommands.warn(p,"magic-region","이 구역에서는 마법을 사용할 수 없어요");return false;}
                if(spell.id().equals("wind_message")&&windCast!=null){
                    if(!p.hasPermission("magiccodex.friends")||!p.getListeningPluginChannels().contains(school.magiccodex.protocol.SocialProtocol.RESPONSE))return false;
                    Boolean visual=CatalogVfxLink.cast(plugin,p,spell.id());
                    if(Boolean.FALSE.equals(visual))return false;
                    windCast.accept(p);return true;
                }
                if(spell.id().equals("fish_trap"))return fishTrapCast!=null&&fishTrapCast.test(p);
                if(spell.id().equals(SPATIAL_LEAP))return spatialLeapCast!=null&&spatialLeapCast.test(p);
                Boolean handled=plugin.runtimeCast(p,spell.id());
                if(handled!=null)return handled;
                String label=spell.command().split("\\s+",2)[0];
                return Bukkit.getCommandMap().getCommand(label)!=null&&p.performCommand(spell.command());
            });
        }catch(RuntimeException error){
            plugin.getLogger().warning("마법 명령 실패: "+(spell==null?"unknown":spell.id())+" ("+error.getClass().getSimpleName()+")");
            result=new ManaCasting.Result(ManaProtocol.FAILED,0);
        }finally{casting.remove(id);}
        if(result.status()==ManaProtocol.OK)result=plugin.artifactCooldown(p,spell,result); // 단 하나의 서 (충전)
        mana.publish(id);
        if(result.status()==ManaProtocol.OK){mana.save(p);plugin.markPlayerStateDirty(p);DiscoveryLink.cast(p.getUniqueId(),spell.id(),spell.cost());plugin.artifactCast(p,spell.id(),spell.cost());plugin.spellSound(p,spell.id(),"cast",p.getLocation());}
        return new Response(sequence,result.status(),result.cooldownMillis(),a.snapshot());
    }
    /** The same exact configured command typed into chat also goes through the mana guard. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void command(PlayerCommandPreprocessEvent e){
        String normalized=ManaSpells.normalize(e.getMessage());
        var spell=spells.byCommand.get(normalized);
        if(spell==null){
            // Do not let ignored trailing arguments turn a known paid spell into a free cast.
            boolean paidPrefix=spells.byCommand.keySet().stream().anyMatch(c->normalized.startsWith(c+" "));
            if(paidPrefix){e.setCancelled(true);CodexCommands.warn(e.getPlayer(),"magic-args","마법 이름 뒤에 다른 글자를 붙일 수 없어요");}
            return;
        }
        e.setCancelled(true);
        if(!sessions.computeIfAbsent(e.getPlayer().getUniqueId(),id->new Session()).allow(now()))return;
        var r=cast(e.getPlayer(),0,spell);send(e.getPlayer(),r);
        if(r.status()!=ManaProtocol.OK&&r.status()!=ManaProtocol.UNAVAILABLE&&!TamingSpell.matches(spell.id()))CodexCommands.warn(e.getPlayer(),"magic-fail",message(r.status()));
    }
    static String message(int status){return switch(status){
        case ManaProtocol.EMPTY->"마나가 부족합니다.";case ManaProtocol.COOLDOWN->"아직 재사용 대기 중입니다.";
        case ManaProtocol.LOCKED->"이 마법을 사용할 수 없습니다.";case ManaProtocol.UNKNOWN->"서버에 등록되지 않았거나 비활성화된 마법입니다.";
        default->"마법 명령을 실행하지 못했습니다. 마나는 차감하지 않았습니다.";
    };}
    /**
     * /마법 &lt;마법&gt; (누구나). mana-spells.yml 의 command: "마법 &lt;id&gt;" 그대로 친 경우는 위 PlayerCommandPreprocess 가 먼저 받아
     * 같은 마나 경로로 시전한다(클라이언트 마법 모드도 이 문자열을 보냄). 여기는 한글 마법 이름·별칭으로 친 경우.
     */
    void bindSpellCommand(){
        if(!CodexCommands.ready(plugin,"/마법"))return;
        ChacaApi.playerCommand(plugin,Commands.literal("마법").executes(ctx->{throw ChacaApi.error("마법 이름을 입력해 주세요");})
            .then(Commands.argument("마법",StringArgumentType.greedyString()).suggests((ctx,b)->{
                String typed=b.getRemaining().replace(" ","").toLowerCase(Locale.ROOT);var sender=ctx.getSource().getSender();
                var names=new TreeSet<String>();
                for(var spell:spells.byId.values()){
                    if(!sender.hasPermission(spell.permission()))continue;
                    var info=spells.registeredCatalog.get(spell.id());String name=info==null?"":info.getOrDefault("name","").replace(" ","");
                    if(!name.isEmpty()&&spells.resolve(name)!=null)names.add(name);
                }
                int n=0;for(String name:names)if(name.toLowerCase(Locale.ROOT).startsWith(typed)){b.suggest(name);if(++n>=60)break;}
                if(n<60)for(var spell:spells.byId.values())if(sender.hasPermission(spell.permission())&&spell.id().startsWith(typed)){b.suggest(spell.id());if(++n>=60)break;}
                return b.buildFuture();
            }).executes(ctx->{
                var player=CodexCommands.self(ctx);
                String id=spells.resolve(StringArgumentType.getString(ctx,"마법"));
                if(id==null)throw ChacaApi.error("서버에 등록되지 않았거나 쓸 수 없는 마법이에요");
                castRegistered(player,id);return Command.SINGLE_SUCCESS;
            })),"마법 쓰기",List.of());
    }
    /** mana-spells.yml (마법 비용·쿨타임·이름) + MythicMobs 실행 경로 spell-runtime.yml 까지 한 번에. */
    void reloadSpells()throws Exception{
        var next=ManaSpells.load(new File(plugin.getDataFolder(),"mana-spells.yml"));
        var r=runtime;if(r!=null)r.reload().run();
        spells=next;
    }
    private void registerAdmin(){
        if(!CodexCommands.ready(plugin,"마나 관리"))return;
        ChacaApi.reload(plugin,"마법","마법 비용·쿨타임(mana-spells.yml)과 실행 경로(spell-runtime.yml)",this::reloadSpells);
        ChacaApi.reload(plugin,"마나","기본 마나 설정(config.yml mana.*), 유저 수치는 유지",plugin::reloadConfig);
        ChacaApi.status(plugin,"MythicMobs 실행 경로",()->{var r=runtime;return r!=null?"정상 · "+r.routes().getAsInt()+"개 경로":Bukkit.getPluginManager().isPluginEnabled("MythicMobs")?"대기 · 실행 경로 없음 (예전 명령 경로 사용)":"없음 · MythicMobs가 꺼져 있음 (예전 명령 경로 사용)";});
        ChacaApi.status(plugin,"마법 목록",()->spells.byId.size()+"종"+(spells.catalogMode?" (도감 모드)":""));
        // /유저관리 <닉네임> 마법쿨타임 <보기|초기화>
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("마법쿨타임")
            .then(Commands.literal("보기").executes(ctx->{
                var p=ChacaApi.targetOnline(ctx);var a=mana.account(p.getUniqueId());long now=System.currentTimeMillis();
                var rows=new ArrayList<String>();
                a.cooldowns.entrySet().stream().filter(e->e.getValue()>now).sorted(Map.Entry.comparingByValue()).forEach(e->rows.add(" "+spellName(e.getKey())+" ("+e.getKey()+") · "+CodexCommands.seconds(e.getValue()-now)+" 남음"));
                if(rows.isEmpty())rows.add(" 기다리는 마법이 없어요");
                CodexCommands.lines(CodexCommands.sender(ctx),"── "+ChacaApi.name(p.getUniqueId())+" 님의 마법 쿨타임 ──",rows);return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("초기화").executes(ctx->{
                var p=ChacaApi.targetOnline(ctx);UUID id=p.getUniqueId();int n=0;
                for(String spell:List.copyOf(mana.account(id).cooldowns.keySet()))if(mana.clearCooldown(id,spell))n++;
                mana.save(p);plugin.markPlayerStateDirty(p);
                ChacaApi.ok(CodexCommands.sender(ctx),ChacaApi.name(id)+" 님의 마법 쿨타임 "+n+"개를 초기화했어요");return Command.SINGLE_SUCCESS;
            })),"마법 쿨타임 보기·초기화","마법쿨타임 보기");
    }
    private String spellName(String id){var info=spells.registeredCatalog.get(id);return info==null?id:info.getOrDefault("name",id);}
    private SpellReviewRunner review;
    public void close(){if(review!=null)review.close();presets.close();if(expansion!=null){if(expansionRegistered)expansion.unregister();else if(plugin.names()!=null)plugin.names().delegatePlaceholders(null);}mana.close();Bukkit.getServicesManager().unregister(ManaService.class,mana);sessions.clear();}
}
