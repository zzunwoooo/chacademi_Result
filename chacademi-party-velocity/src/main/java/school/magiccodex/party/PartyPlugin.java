package school.magiccodex.party;

import com.google.inject.Inject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One authoritative party owner per Velocity. Never intercepts signed public chat. */
public final class PartyPlugin {
    private static final MinecraftChannelIdentifier IDENTITY=MinecraftChannelIdentifier.from("chacademi:party_identity");
    private static final MinecraftChannelIdentifier DISCOVERY=MinecraftChannelIdentifier.from("chacademi:party_discovery");
    private static final MinecraftChannelIdentifier VITALS=MinecraftChannelIdentifier.from("chacademi:party_vitals");
    private static final MinecraftChannelIdentifier HUD=MinecraftChannelIdentifier.from("chacademi:party_hud");
    /** 파티 명단 → 게임 서버 (아티팩트·마법의 파티 판정). 프록시만 보낸다. */
    private static final MinecraftChannelIdentifier ROSTER=MinecraftChannelIdentifier.from("chacademi:party_roster");
    /** 파티 창 ↔ 클라이언트 (PartyUi). 클라이언트가 보낸 요청은 이 프록시가 처리하고 게임 서버로 넘기지 않는다. */
    private static final MinecraftChannelIdentifier UI=MinecraftChannelIdentifier.from("chacademi:party_ui");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    static ThreadPoolExecutor newWorker(){return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->{var t=new Thread(r,"Chacademi-party");t.setDaemon(true);return t;});}
    private final ThreadPoolExecutor worker=newWorker();
    private NetworkChat network;
    private PartyHud hud;
    private final PartyRoster roster=new PartyRoster();
    private final PartyUi ui=new PartyUi();
    /** 마지막으로 본 스킨 (오프라인 파티원 얼굴용). 작업 스레드 전용. */
    private final Map<UUID,PartyUi.Skin> skins=new HashMap<>();
    private final Map<UUID,Long> nextChat=new HashMap<>(),nextCommand=new HashMap<>();
    private final Set<String> trusted=new HashSet<>();
    /** 채팅 접두 아이콘용 학교·야생 서버 이름 (party.properties school-server · wild-server). 그 밖의 채팅 서버는 [아레나]. */
    private String schoolServer="school",wildServer="wild1";
    private PartyBook book;
    private long membershipChangedAt=System.currentTimeMillis();
    private PartyStore store;
    private volatile boolean ready;
    private KyudichiQueue kyudichi;
    private TravelRelay travel;
    /** 자동완성용 접속자 한글 닉네임 (작업 스레드가 0.25초마다 갱신, 명령 스레드가 읽는다). */
    private volatile List<String> onlineNames=List.of();

    @Inject public PartyPlugin(ProxyServer proxy,Logger logger,@DataDirectory Path directory){this.proxy=proxy;this.logger=logger;this.directory=directory;}
    @Subscribe public void initialize(ProxyInitializeEvent event){
        try{
            Files.createDirectories(directory);var path=directory.resolve("party.properties");var properties=new Properties();
            if(Files.exists(path)){try(var reader=Files.newBufferedReader(path)){properties.load(reader);}}
            else {properties.setProperty("max-members","8");properties.setProperty("trusted-servers","school,wild1,wild2,arena");properties.setProperty("chat-servers","school,wild1,wild2,arena");properties.setProperty("school-server","school");properties.setProperty("wild-server","wild1");try(var writer=Files.newBufferedWriter(path)){properties.store(writer,"Single Velocity authority; restart after editing. trusted-servers/chat-servers = Velocity server names (the arena name must match kyudichi.properties arena-server).");}}
            int capacity=Integer.parseInt(properties.getProperty("max-members","8"));
            // 2026-10-21 (C02): the arena is trusted by default (identity · party HUD · chat work there); existing files keep their own list
            for(var s:properties.getProperty("trusted-servers","school,wild1,wild2,arena").split(","))if(!s.isBlank())trusted.add(s.strip());
            var chatServers=new HashSet<String>();
            for(var s:properties.getProperty("chat-servers","school,wild1,wild2,arena").split(","))if(!s.isBlank())chatServers.add(s.strip());
            schoolServer=properties.getProperty("school-server","school").strip();wildServer=properties.getProperty("wild-server","wild1").strip();
            for(var s:chatServers)if(!trusted.contains(s))logger.warn("party.properties: chat-servers 의 "+s+" 가 trusted-servers 에 없어 그 서버에서는 전체·기숙사 채팅이 안 됩니다");
            network=new NetworkChat(trusted,chatServers);hud=new PartyHud(trusted);
            store=new PartyStore(directory.resolve("parties.json"));book=store.load(capacity);ready=true;
        }catch(Exception e){logger.error("Party initialization failed; commands remain unavailable. Stored state preserved.",e);}
        proxy.getChannelRegistrar().register(IDENTITY,DISCOVERY,VITALS,HUD,ROSTER,UI);
        // 파티 위젯: 0.25초마다 바뀐 파티만 보낸다 (변화 없으면 3초마다 한 번).
        proxy.getScheduler().buildTask(this,()->enqueue(this::flushHud)).repeat(250,TimeUnit.MILLISECONDS).schedule();
        var manager=proxy.getCommandManager();
        var party=new BrigadierCommand(partyCommand());
        manager.register(manager.metaBuilder(party).plugin(this).build(),party);
        var chat=new BrigadierCommand(chatCommand());
        manager.register(manager.metaBuilder(chat).plugin(this).build(),chat);
        // 큐디치 대기열: 같은 작업 스레드에서 파티 정보를 읽는다.
        kyudichi=new KyudichiQueue(proxy,logger,new KyudichiQueue.Host(){
            @Override public boolean enqueue(Runnable task){return PartyPlugin.this.enqueue(task);}
            @Override public PartyBook.Party party(UUID member){return ready?book.party(member):null;}
            // MagicCodex 한글 닉네임 (identity, then the remembered one); the account name only when neither is known
            @Override public String name(UUID id){return person(id,0).nickname();}
            @Override public void notice(Player p,String text){PartyPlugin.this.notice(p,text);}
            @Override public boolean trusted(String server){return trusted.contains(server);}
            @Override public int house(UUID id){return person(id,0).house();}
            @Override public String skin(UUID id){return person(id,0).skin().hash();}
            @Override public List<String> partyView(UUID target){return PartyPlugin.this.partyView(target);}
            @Override public String partyRemove(UUID target)throws IOException{return PartyPlugin.this.partyRemove(target);}
            @Override public String partyDisband(UUID target)throws IOException{return PartyPlugin.this.partyDisband(target);}
            @Override public List<String> onlineNames(){return onlineNames;}
        },this);
        try{kyudichi.initialize(directory);}catch(RuntimeException e){logger.error("Kyudichi queue initialization failed; queue unavailable",e);}
        // 지도 이동: 게임 서버가 확인·기록한 이동 표대로 서버만 옮긴다 (TravelRelay).
        try{
            travel=new TravelRelay(proxy,logger,new TravelRelay.Host(){
                @Override public boolean enqueue(Runnable task){return PartyPlugin.this.enqueue(task);}
                @Override public boolean trusted(String server){return trusted.contains(server);}
                @Override public boolean blocked(UUID player){return kyudichi!=null&&kyudichi.travelBlocked(player);}
            },this);
            travel.initialize(directory);
            proxy.getEventManager().register(this,travel);
        }catch(RuntimeException e){logger.error("Travel relay initialization failed; map travel between servers unavailable",e);travel=null;}
        // 튜토리얼 서버 분산 (tutorial.properties, 서버 2개 이상일 때만 동작 — 1개·비움이면 예전과 같음)
        try{var tutorial=new TutorialRouter(proxy,logger,PartyPlugin.this::enqueue,this);tutorial.initialize(directory);if(tutorial.enabled())proxy.getEventManager().register(this,tutorial);}
        catch(RuntimeException e){logger.error("Tutorial router initialization failed; tutorial servers are not balanced",e);}
        // C12: 통합 채팅 뮤트는 Velocity 권한 chacademi.chat.muted 뿐 — 권한 플러그인이 없으면 아무도 뮤트되지 않는다
        if(!proxy.getPluginManager().isLoaded("luckperms"))logger.warn("Velocity 에 LuckPerms 가 없어 통합 채팅(전체·기숙사) 뮤트 권한 chacademi.chat.muted 가 동작하지 않습니다");
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event){
        ready=false;if(kyudichi!=null)kyudichi.shutdown();worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))logger.warn("Party worker still draining during shutdown");}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    @Subscribe public void disconnect(DisconnectEvent event){
        UUID id=event.getPlayer().getUniqueId();if(travel!=null)travel.disconnect(id);enqueue(()->{if(network!=null)network.disconnect(id);if(hud!=null)hud.disconnect(id);roster.disconnect(id);ui.disconnect(id);nextChat.remove(id);nextCommand.remove(id);});
        // Disconnect/server transfer does not change durable membership.
    }
    @Subscribe public void identity(PluginMessageEvent event){
        if(!event.getIdentifier().equals(IDENTITY))return;
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // Never forward client spoofing.
        if(!(event.getSource() instanceof ServerConnection source)||!(event.getTarget() instanceof Player target))return;
        if(!trusted.contains(source.getServerInfo().getName())||source.getPlayer()!=target||target.getCurrentServer().orElse(null)!=source)return;
        byte[] data=event.getData();if(data.length>256)return;
        enqueue(()->{if(network!=null&&proxy.getPlayer(target.getUniqueId()).orElse(null)==target){
            long now=System.currentTimeMillis();if(network.accept(target.getUniqueId(),source,target.getCurrentServer().orElse(null),source.getServerInfo().getName(),true,data,now)&&kyudichi!=null){try{String original=NetworkChat.decode(data).registeredNickname();if(!original.isBlank())kyudichi.rememberName(target.getUniqueId(),original);}catch(IOException impossible){throw new IllegalStateException(impossible);}}}});
    }
    @Subscribe public void vitals(PluginMessageEvent event){
        if(!event.getIdentifier().equals(VITALS))return;
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // 클라이언트가 보낸 값은 받지 않는다.
        if(!(event.getSource() instanceof ServerConnection source)||!(event.getTarget() instanceof Player target))return;
        if(!trusted.contains(source.getServerInfo().getName())||source.getPlayer()!=target||target.getCurrentServer().orElse(null)!=source)return;
        byte[] data=event.getData().clone();if(data.length!=17)return;
        enqueue(()->{if(hud!=null&&proxy.getPlayer(target.getUniqueId()).orElse(null)==target)
            hud.accept(target.getUniqueId(),source,target.getCurrentServer().orElse(null),source.getServerInfo().getName(),true,data,System.currentTimeMillis());});
    }
    @Subscribe public void hudSpoof(PluginMessageEvent event){
        // 위젯 채널은 프록시만 클라이언트에게 보낸다. 어느 쪽에서 온 것이든 전달하지 않는다.
        if(event.getIdentifier().equals(HUD)||event.getIdentifier().equals(ROSTER))event.setResult(PluginMessageEvent.ForwardResult.handled());
    }
    @Subscribe public void serverSwitched(ServerPostConnectEvent event){
        UUID id=event.getPlayer().getUniqueId();long notBefore=System.currentTimeMillis()+1000;
        enqueue(()->{if(hud!=null)hud.resend(id,notBefore);roster.resend(id,notBefore);ui.resend(id,notBefore);});
    }
    private void flushHud(){
        var names=new ArrayList<String>();for(Player player:proxy.getAllPlayers())names.add(name(player.getUniqueId()));onlineNames=List.copyOf(names);
        if(!ready||hud==null)return;long now=System.currentTimeMillis();
        var byParty=new HashMap<UUID,byte[]>();
        for(Player player:proxy.getAllPlayers()){
            // 서버 이동 중(구성 단계)에는 보내지 않는다. 다음 주기에 다시 판단한다.
            if(player.getProtocolState()!=ProtocolState.PLAY||player.getCurrentServer().isEmpty())continue;
            var party=book.party(player.getUniqueId());
            byte[] payload=party==null?PartyHud.EMPTY:byParty.computeIfAbsent(party.id(),k->PartyHud.encode(hudMembers(party,now)));
            var out=hud.due(player.getUniqueId(),payload,now);
            if(out!=null)player.sendPluginMessage(HUD,out);
            // 같은 파티 명단을 플레이어가 있는 게임 서버에도 보낸다 (신뢰 서버만).
            // 파티 창 상태: 바뀌었을 때만 보낸다.
            var state=ui.stateDue(player.getUniqueId(),uiState(player.getUniqueId(),party,false,now),now);
            if(state!=null)player.sendPluginMessage(UI,state);
            var server=player.getCurrentServer().get();
            if(trusted.contains(server.getServerInfo().getName())){
                var list=roster.due(player.getUniqueId(),PartyRoster.encode(party),now);
                if(list!=null)server.sendPluginMessage(ROSTER,list);
            }
        }
    }
    private List<PartyHud.Member> hudMembers(PartyBook.Party party,long now){
        var list=new ArrayList<PartyHud.Member>();
        for(UUID id:party.members()){
            var online=proxy.getPlayer(id).orElse(null);
            var server=online==null?null:online.getCurrentServer().orElse(null);
            var identity=server==null||network==null?null:network.identity(id,server,now);
            if(identity!=null)hud.remember(id,identity.nickname(),identity.house());
            String remembered=hud.rememberedName(id);
            String nickname=identity!=null?identity.nickname():remembered!=null?remembered:online!=null?online.getUsername():"오프라인 파티원";
            int house=identity!=null?identity.house():hud.rememberedHouse(id);
            list.add(new PartyHud.Member(id,nickname,house,online!=null,party.leader().equals(id),server==null?null:hud.vitals(id,server,now)));
        }
        return list;
    }
    @Subscribe public void discoveryParty(PluginMessageEvent event){
        if(!event.getIdentifier().equals(DISCOVERY))return;event.setResult(PluginMessageEvent.ForwardResult.handled());
        if(!ready||!(event.getSource() instanceof ServerConnection source)||!(event.getTarget() instanceof Player player))return;
        if(!trusted.contains(source.getServerInfo().getName())||source.getPlayer()!=player||player.getCurrentServer().orElse(null)!=source)return;
        byte[] request=event.getData().clone();if(request.length!=25||request[0]!=1)return;
        enqueue(()->{if(player.getCurrentServer().orElse(null)!=source)return;try{
            var input=new DataInputStream(new ByteArrayInputStream(request));input.skipNBytes(17);long observedAt=input.readLong(),now=System.currentTimeMillis();
            var party=book.party(player.getUniqueId());var members=party==null||observedAt<=membershipChangedAt||observedAt>now||now-observedAt>2000?List.<UUID>of():party.members();
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.write(request,0,17);out.writeByte(members.size());for(UUID id:members){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}source.sendPluginMessage(DISCOVERY,bytes.toByteArray());
        }catch(IOException impossible){throw new IllegalStateException(impossible);}});
    }
    private boolean enqueue(Runnable task){try{worker.execute(task);return true;}catch(RejectedExecutionException e){return false;}}
    /** 표시 이름: 서버가 알려 준 한글 닉네임 → 기억해 둔 닉네임 → 계정 이름. 오프라인이면 UUID 대신 기억한 닉네임 (#94). */
    private String name(UUID id){
        var online=proxy.getPlayer(id);
        var identity=online.isEmpty()||network==null?null:network.identity(id,online.get().getCurrentServer().orElse(null),System.currentTimeMillis());
        if(identity!=null)return identity.nickname();
        String remembered=hud==null?null:hud.rememberedName(id);
        if(remembered!=null&&!remembered.isBlank())return remembered;
        return online.isPresent()?online.get().getUsername():"파티원";
    }
    private UUID resolve(String text,boolean onlineOnly){
        try{UUID id=UUID.fromString(text);if(!onlineOnly||proxy.getPlayer(id).isPresent())return id;}catch(IllegalArgumentException ignored){}
        var account=proxy.getPlayer(text);if(account.isPresent())return account.get().getUniqueId();
        var candidates=proxy.getAllPlayers().stream().filter(p->name(p.getUniqueId()).equals(text)).toList();
        if(candidates.size()!=1)throw new IllegalArgumentException("접속한 계정명 또는 중복되지 않는 한글 닉네임을 입력해 주세요. 오프라인 파티원은 목록의 UUID로 지정할 수 있습니다.");
        return candidates.getFirst().getUniqueId();
    }
    private void notice(Player p,String text){p.sendMessage(Component.text("[시스템] ",NamedTextColor.GOLD).append(icon("\uE113")).append(Component.text(" "+text,NamedTextColor.YELLOW)));}
    private Component icon(String glyph){return Component.text(glyph).font(Key.key("magiccodex:chat"));}
    private void broadcast(Collection<UUID> members,String message){for(UUID id:members)proxy.getPlayer(id).ifPresent(p->notice(p,message));}
    private void commit(PartyBook next)throws IOException{store.save(next);membershipChangedAt=System.currentTimeMillis();book=next;}
    // ---------- 명령어 (Velocity Brigadier) ----------
    /** /파티 권한: chacademi.party 가 명시적으로 false 인 사람만 막는다 (기본 허용). */
    boolean partyAllowed(CommandSource source){return !(source instanceof Player p)||p.getPermissionValue("chacademi.party")!=Tristate.FALSE;}
    private interface PartyTask{String run(Player p,long now)throws IOException;}
    private static LiteralArgumentBuilder<CommandSource> literal(String name){return LiteralArgumentBuilder.<CommandSource>literal(name);}
    private static RequiredArgumentBuilder<CommandSource,String> text(String name){return RequiredArgumentBuilder.<CommandSource,String>argument(name,StringArgumentType.greedyString());}
    private static String arg(CommandContext<CommandSource> ctx,String name){return StringArgumentType.getString(ctx,name).strip();}
    /** 닉네임 칸 (마지막 칸): 접속자 한글 닉네임. */
    private RequiredArgumentBuilder<CommandSource,String> nickname(){return text("닉네임").suggests(this::suggestNames);}
    private CompletableFuture<Suggestions> suggestNames(CommandContext<CommandSource> ctx,SuggestionsBuilder builder){
        String typed=builder.getRemaining().toLowerCase(Locale.ROOT);int count=0;
        for(String n:onlineNames)if(n.toLowerCase(Locale.ROOT).startsWith(typed)){builder.suggest(n);if(++count>=40)break;}
        return builder.buildFuture();
    }
    /** 파티 동작을 작업 스레드에서 실행하고 결과 문구를 알린다. */
    private int party(CommandContext<CommandSource> ctx,PartyTask task){
        if(!(ctx.getSource() instanceof Player p)){ctx.getSource().sendMessage(Component.text("플레이어 전용 명령입니다."));return 0;}
        if(!ready){notice(p,"파티 서비스를 사용할 수 없습니다.");return 0;}
        if(!enqueue(()->{
            if(proxy.getPlayer(p.getUniqueId()).orElse(null)!=p)return;
            try{
                long now=System.currentTimeMillis();
                if(now<nextCommand.getOrDefault(p.getUniqueId(),0L))throw new IllegalArgumentException("잠시 후 다시 시도해 주세요.");
                nextCommand.put(p.getUniqueId(),now+150);
                String message=task.run(p,now);if(message!=null)notice(p,message);
            }catch(IllegalArgumentException e){notice(p,e.getMessage());}
            catch(Exception e){logger.error("Party operation failed; no success acknowledgment sent",e);notice(p,"파티 정보를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");}
        }))notice(p,"파티 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        return Command.SINGLE_SUCCESS;
    }
    /** /파티 [초대 <닉네임>|수락 [닉네임]|거절 [닉네임]|탈퇴|위임 <닉네임>|강퇴 <닉네임>|해산|목록|채팅 <내용>] — 인자 없으면 파티 창. */
    LiteralArgumentBuilder<CommandSource> partyCommand(){
        return literal("파티").requires(this::partyAllowed)
                .executes(ctx->party(ctx,(p,now)->{openUi(p,now);return null;}))
                // 파티가 없으면 먼저 파티를 만들고(내가 파티장) 초대한다 (파티 창과 같음).
                .then(literal("초대").then(nickname().executes(ctx->{String n=arg(ctx,"닉네임");return party(ctx,(p,now)->act(p,"invite",resolve(n,true),now,true));})))
                .then(literal("수락").executes(ctx->party(ctx,(p,now)->act(p,"accept",null,now,false)))
                        .then(nickname().executes(ctx->{String n=arg(ctx,"닉네임");return party(ctx,(p,now)->act(p,"accept",resolve(n,false),now,false));})))
                .then(literal("거절").executes(ctx->party(ctx,(p,now)->act(p,"deny",null,now,false)))
                        .then(nickname().executes(ctx->{String n=arg(ctx,"닉네임");return party(ctx,(p,now)->act(p,"deny",resolve(n,false),now,false));})))
                .then(literal("탈퇴").executes(ctx->party(ctx,(p,now)->act(p,"leave",null,now,false))))
                .then(literal("위임").then(nickname().executes(ctx->{String n=arg(ctx,"닉네임");return party(ctx,(p,now)->act(p,"promote",resolve(n,false),now,false));})))
                .then(literal("강퇴").then(nickname().executes(ctx->{String n=arg(ctx,"닉네임");return party(ctx,(p,now)->act(p,"kick",resolve(n,false),now,false));})))
                .then(literal("해산").executes(ctx->party(ctx,(p,now)->act(p,"disband",null,now,false))))
                .then(literal("목록").executes(ctx->party(ctx,(p,now)->{
                    var party=book.require(p.getUniqueId());notice(p,"파티원 "+party.members().size()+"/"+book.capacity);
                    for(UUID id:party.members())notice(p,(party.leader().equals(id)?"★ ":"")+name(id)+(proxy.getPlayer(id).isPresent()?"":" (오프라인)"));
                    return null;
                })))
                .then(literal("채팅").then(text("내용").executes(ctx->{String t=arg(ctx,"내용");return party(ctx,(p,now)->{partyChat(p,t,now);return null;});})));
    }
    private void partyChat(Player p,String raw,long now){
        UUID actor=p.getUniqueId();
        String text=PartyBook.message(raw);
        var members=book.recipients(actor);
        NetworkChat.claimChat(nextChat,actor,now);
        var message=Component.text("[파티] ",NamedTextColor.AQUA).append(icon("\uE112")).append(Component.text(" "+name(actor)+" : ",NamedTextColor.AQUA)).append(Component.text(text,NamedTextColor.WHITE));
        for(UUID id:members)proxy.getPlayer(id).ifPresent(member->member.sendMessage(message));
    }
    private boolean chatAllowed(Player p,NetworkChat.Channel channel){
        return p.getPermissionValue("chacademi.chat.muted")!=Tristate.TRUE
                &&p.getPermissionValue("chacademi.chat.send")!=Tristate.FALSE
                &&p.getPermissionValue(channel==NetworkChat.Channel.GLOBAL?"chacademi.chat.global":"chacademi.chat.house")!=Tristate.FALSE;
    }
    private Map<UUID,NetworkChat.Connection> onlineConnections(){
        var online=new LinkedHashMap<UUID,NetworkChat.Connection>();
        for(Player player:proxy.getAllPlayers())player.getCurrentServer().ifPresent(s->online.put(player.getUniqueId(),new NetworkChat.Connection(s,s.getServerInfo().getName())));
        return online;
    }
    /** /채팅 <전체|기숙사> <내용> (채팅 모드의 전체·기숙사 탭이 보내는 명령). */
    LiteralArgumentBuilder<CommandSource> chatCommand(){
        var root=literal("채팅");
        for(String channel:List.of("전체","기숙사"))root.then(literal(channel).then(text("내용").executes(ctx->{
            if(!(ctx.getSource() instanceof Player player)){ctx.getSource().sendMessage(Component.text("플레이어 전용 명령입니다."));return 0;}
            String text=arg(ctx,"내용");
            if(!ready||network==null){notice(player,"통합 채팅을 사용할 수 없습니다.");return 0;}
            if(!enqueue(()->{
                if(proxy.getPlayer(player.getUniqueId()).orElse(null)!=player)return;
                try{networkMessage(player,channel,text);}catch(IllegalArgumentException e){notice(player,e.getMessage());}
                catch(RuntimeException e){logger.error("Network chat failed; no fallback broadcast",e);notice(player,"통합 채팅을 보내지 못했습니다.");}
            }))notice(player,"채팅 요청이 많습니다. 잠시 후 다시 보내 주세요.");
            return Command.SINGLE_SUCCESS;
        })));
        return root;
    }
    private void networkMessage(Player player,String channelName,String raw){
        NetworkChat.Channel channel=switch(channelName){
            case "전체"->NetworkChat.Channel.GLOBAL;
            case "기숙사"->NetworkChat.Channel.HOUSE;
            default->throw new IllegalArgumentException("채널은 전체 또는 기숙사입니다.");
        };
        if(!chatAllowed(player,channel))throw new IllegalArgumentException("현재 이 채널에 채팅을 보낼 수 없습니다.");
        UUID actor=player.getUniqueId();long now=System.currentTimeMillis();
        if(now<nextCommand.getOrDefault(actor,0L))throw new IllegalArgumentException("잠시 후 다시 시도해 주세요.");
        nextCommand.put(actor,now+150);
        String text=PartyBook.message(raw);
        var online=onlineConnections();var sender=network.sender(actor,online,now);var recipients=network.recipients(channel,actor,online,now);
        NetworkChat.claimChat(nextChat,actor,now);
        String tag=channel==NetworkChat.Channel.GLOBAL?"[전체] ":"[기숙사] ";
        // C12: this chat never passes the Paper chat event, so the arena's spectator isolation is applied here —
        // a spectator (on the arena, not in a match) does not reach players who are in a match; and every message is logged on the proxy.
        boolean bystander=kyudichi!=null&&kyudichi.arenaBystander(player);
        logger.info("[통합채팅] "+tag.strip()+" "+sender.nickname()+" ("+player.getUsername()+") @"+sender.server()+(bystander?" 관전/연습":"")+": "+text);
        var message=Component.text(tag,NamedTextColor.AQUA)
                .append(sender.server().equals(schoolServer)?icon("\uE114"):sender.server().equals(wildServer)?icon("\uE115"):Component.text("[아레나]",NamedTextColor.GRAY))
                .append(Component.text(" ")).append(icon(channel==NetworkChat.Channel.GLOBAL?"\uE110":"\uE111"))
                .append(Component.text(" "+sender.nickname()+" : ",NamedTextColor.AQUA))
                .append(Component.text(text,NamedTextColor.WHITE));
        for(UUID id:recipients){if(bystander&&!id.equals(actor)&&kyudichi.inMatch(id))continue;proxy.getPlayer(id).ifPresent(target->{
            // A transfer during audience construction must not leak a house message to the new connection.
            var original=online.get(id);long deliveryNow=System.currentTimeMillis();
            if(original==null||target.getCurrentServer().orElse(null)!=original.source()||player.getCurrentServer().orElse(null)!=sender.source())return;
            var currentSender=network.identity(actor,sender.source(),deliveryNow);if(currentSender==null)return;
            if(channel==NetworkChat.Channel.HOUSE){var currentTarget=network.identity(id,original.source(),deliveryNow);if(currentTarget==null||currentSender.house()<0||currentTarget.house()!=currentSender.house())return;}
            target.sendMessage(message);
        });}
    }

    // ---------- 관리 요청 (경기장 서버 /유저관리 <닉네임> 파티 …, KyudichiQueue 를 거쳐 작업 스레드에서) ----------
    /** 오프라인이어도 기억해 둔 한글 닉네임. */
    private String display(UUID id){return person(id,0).nickname();}
    private List<String> partyView(UUID target){
        if(!ready)throw new IllegalArgumentException("파티 서비스를 사용할 수 없어요");
        var party=book.party(target);
        if(party==null)return List.of(display(target)+" 님은 파티가 없어요");
        var lines=new ArrayList<String>();
        lines.add("── "+display(target)+" 님의 파티 "+party.members().size()+"/"+book.capacity+" ──");
        for(UUID id:party.members())lines.add((party.leader().equals(id)?"★ ":"  ")+display(id)+(proxy.getPlayer(id).isPresent()?"":" (오프라인)"));
        return lines;
    }
    private String partyRemove(UUID target)throws IOException{
        if(!ready)throw new IllegalArgumentException("파티 서비스를 사용할 수 없어요");
        if(book.party(target)==null)throw new IllegalArgumentException(display(target)+" 님은 파티가 없어요");
        var next=book.copy();var old=next.leave(target);commit(next);
        broadcast(old.members(),display(target)+" 님이 관리자에 의해 파티에서 빠졌습니다.");
        var after=book.parties.get(old.id());if(after!=null&&old.leader().equals(target))broadcast(after.members(),display(after.leader())+" 님이 파티장이 되었습니다.");
        return display(target)+" 님을 파티에서 뺐어요";
    }
    private String partyDisband(UUID target)throws IOException{
        if(!ready)throw new IllegalArgumentException("파티 서비스를 사용할 수 없어요");
        var party=book.party(target);
        if(party==null)throw new IllegalArgumentException(display(target)+" 님은 파티가 없어요");
        var next=book.copy();var old=next.disband(party.leader());commit(next);
        broadcast(old.members(),"관리자가 파티를 해산했습니다.");
        return display(target)+" 님의 파티를 해산했어요 ("+old.members().size()+"명)";
    }

    /**
     * 명령어와 파티 창이 함께 쓰는 실제 동작. 실패하면 IllegalArgumentException(사용자에게 보일 문구).
     * @return 실행한 사람에게 보여 줄 문구
     */
    private String act(Player p,String action,UUID target,long now,boolean fromUi)throws IOException{
        UUID actor=p.getUniqueId();var next=book.copy();
        switch(action){
            case "create"->{next.create(actor);commit(next);return "파티를 만들었습니다.";}
            case "invite"->{
                if(target==null||proxy.getPlayer(target).isEmpty())throw new IllegalArgumentException("접속 중인 플레이어만 초대할 수 있습니다.");
                // 파티 창에서는 파티가 없어도 초대할 수 있다: 먼저 파티를 만들고(내가 파티장) 초대한다.
                boolean created=false;
                if(fromUi&&next.party(actor)==null){next.create(actor);created=true;}
                next.invite(actor,target,now);
                if(created)commit(next);else book=next;
                var inviter=person(actor,0);
                proxy.getPlayer(target).ifPresent(t->{
                    notice(t,name(actor)+" 님이 파티에 초대했어요. 알림이나 /파티 창에서 수락할 수 있어요. (2분)");
                    t.sendPluginMessage(UI,PartyUi.invite(inviter));
                });
                return (created?"파티를 만들고 ":"")+name(target)+" 님을 초대했어요.";
            }
            case "accept"->{var party=next.accept(actor,target,now);commit(next);broadcast(party.members(),name(actor)+" 님이 파티에 참가했습니다.");return "파티에 참가했어요.";}
            case "deny"->{var invite=next.deny(actor,target,now);book=next;proxy.getPlayer(invite.inviter()).ifPresent(t->notice(t,name(actor)+" 님이 초대를 거절했습니다."));return "초대를 거절했어요.";}
            case "leave"->{var old=next.leave(actor);commit(next);broadcast(old.members(),name(actor)+" 님이 파티에서 나갔습니다.");var after=book.parties.get(old.id());if(after!=null&&old.leader().equals(actor))broadcast(after.members(),name(after.leader())+" 님이 파티장이 되었습니다.");return "파티에서 나왔어요.";}
            case "promote"->{var party=next.promote(actor,target);commit(next);broadcast(party.members(),name(target)+" 님에게 파티장을 위임했습니다.");return name(target)+" 님이 파티장이 되었어요.";}
            case "kick"->{var old=next.kick(actor,target);commit(next);broadcast(old.members(),name(target)+" 님이 파티에서 제외되었습니다.");return name(target)+" 님을 내보냈어요.";}
            case "disband"->{var old=next.disband(actor);commit(next);broadcast(old.members(),"파티가 해산되었습니다.");return "파티를 해산했어요.";}
            default->throw new IllegalArgumentException("알 수 없는 파티 명령입니다.");
        }
    }

    // ---------- 파티 창 ----------
    @Subscribe public void uiRequest(PluginMessageEvent event){
        if(!event.getIdentifier().equals(UI))return;
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // 어느 쪽에서 온 것이든 게임 서버·클라이언트로 넘기지 않는다.
        if(!(event.getSource() instanceof Player p))return; // 클라이언트가 보낸 요청만 받는다.
        byte[] data=event.getData().clone();
        if(!enqueue(()->{
            if(proxy.getPlayer(p.getUniqueId()).orElse(null)!=p)return;
            long now=System.currentTimeMillis();
            PartyUi.Request request;
            try{request=PartyUi.decode(data);}catch(IOException malformed){return;}
            if(!ready){p.sendPluginMessage(UI,PartyUi.result(false,"파티 서비스를 사용할 수 없습니다."));return;}
            if(p.getPermissionValue("chacademi.party")==Tristate.FALSE){p.sendPluginMessage(UI,PartyUi.result(false,"파티 기능을 사용할 권한이 없습니다."));return;}
            try{
                switch(request.action()){
                    case PartyUi.OPEN->{var payload=uiState(p.getUniqueId(),book.party(p.getUniqueId()),false,now);ui.sentNow(p.getUniqueId(),payload);p.sendPluginMessage(UI,payload);}
                    case PartyUi.CANDIDATES->{if(ui.candidatesAllowed(p.getUniqueId(),now))p.sendPluginMessage(UI,PartyUi.candidates(candidates(p.getUniqueId(),now)));}
                    default->{
                        if(now<nextCommand.getOrDefault(p.getUniqueId(),0L))throw new IllegalArgumentException("잠시 후 다시 시도해 주세요.");
                        nextCommand.put(p.getUniqueId(),now+150);
                        String action=switch(request.action()){case PartyUi.INVITE->"invite";case PartyUi.ACCEPT->"accept";case PartyUi.DENY->"deny";case PartyUi.LEAVE->"leave";case PartyUi.PROMOTE->"promote";default->"kick";};
                        String message=act(p,action,request.target(),now,true);
                        // 바뀐 상태를 결과보다 먼저 보내 창이 바로 맞춰지게 한다.
                        var payload=uiState(p.getUniqueId(),book.party(p.getUniqueId()),false,now);ui.sentNow(p.getUniqueId(),payload);p.sendPluginMessage(UI,payload);
                        if(request.action()==PartyUi.INVITE)p.sendPluginMessage(UI,PartyUi.candidates(candidates(p.getUniqueId(),now)));
                        p.sendPluginMessage(UI,PartyUi.result(true,message));
                    }
                }
            }catch(IllegalArgumentException e){p.sendPluginMessage(UI,PartyUi.result(false,e.getMessage()));}
            catch(Exception e){logger.error("Party UI operation failed; no success acknowledgment sent",e);p.sendPluginMessage(UI,PartyUi.result(false,"파티 정보를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."));}
        }))p.sendPluginMessage(UI,PartyUi.result(false,"파티 요청이 많습니다. 잠시 후 다시 시도해 주세요."));
    }
    private void openUi(Player p,long now){
        var party=book.party(p.getUniqueId());
        ui.sentNow(p.getUniqueId(),uiState(p.getUniqueId(),party,false,now));
        p.sendPluginMessage(UI,uiState(p.getUniqueId(),party,true,now));
    }
    private byte[] uiState(UUID viewer,PartyBook.Party party,boolean open,long now){
        var members=new ArrayList<PartyUi.Person>();
        if(party!=null)for(UUID id:party.members())members.add(person(id,(proxy.getPlayer(id).isPresent()?PartyUi.P_ONLINE:0)|(party.leader().equals(id)?PartyUi.P_LEADER:0)));
        var invites=new ArrayList<PartyUi.Person>();
        book.expire(now);
        for(var invite:book.invites)if(invite.target().equals(viewer))invites.add(person(invite.inviter(),0));
        return PartyUi.state(open,book.capacity,members,invites);
    }
    /** 초대할 수 있는 사람: 학교·야생·아레나(신뢰 서버)에 접속 중이고 파티가 없는 다른 플레이어. */
    private List<PartyUi.Person> candidates(UUID viewer,long now){
        book.expire(now);var mine=book.party(viewer);var list=new ArrayList<PartyUi.Person>();
        for(Player player:proxy.getAllPlayers()){
            UUID id=player.getUniqueId();if(id.equals(viewer)||book.party(id)!=null)continue;
            var server=player.getCurrentServer().orElse(null);if(server==null||!trusted.contains(server.getServerInfo().getName()))continue;
            boolean invited=mine!=null&&book.invites.stream().anyMatch(i->i.party().equals(mine.id())&&i.target().equals(id));
            list.add(person(id,invited?PartyUi.P_INVITED:0));
        }
        list.sort(Comparator.comparing(PartyUi.Person::nickname,String.CASE_INSENSITIVE_ORDER));
        return list.size()>PartyUi.MAX_CANDIDATES?list.subList(0,PartyUi.MAX_CANDIDATES):list;
    }
    private PartyUi.Person person(UUID id,int flags){
        var online=proxy.getPlayer(id).orElse(null);long now=System.currentTimeMillis();
        var identity=online==null||network==null?null:network.identity(id,online.getCurrentServer().orElse(null),now);
        if(identity!=null&&hud!=null)hud.remember(id,identity.nickname(),identity.house());
        String remembered=hud==null?null:hud.rememberedName(id);
        String nickname=identity!=null?identity.nickname():remembered!=null?remembered:online!=null?online.getUsername():"오프라인 파티원";
        int house=identity!=null?identity.house():hud==null?-1:hud.rememberedHouse(id);
        if(online!=null){
            for(var property:online.getGameProfile().getProperties())
                if(property.getName().equals("textures")){var skin=PartyUi.skin(property.getValue());if(!skin.hash().isEmpty())skins.put(id,skin);break;}
        }
        return new PartyUi.Person(id,nickname,house,skins.getOrDefault(id,PartyUi.Skin.NONE),flags);
    }
}
