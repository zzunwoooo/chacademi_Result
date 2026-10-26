package school.magiccodex.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.protocol.SocialProtocol.*;
import school.magiccodex.protocol.ManaProtocol;
import school.magiccodex.database.DatabaseSettings;

/** Event driven social state. No global player polling or disk access in packet handlers. */
final class SocialBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final ManaBridge mana;
    private final StatsService stats;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-friends-io");t.setDaemon(true);return t;});
    private final FriendStore store;
    private final boolean shared;
    private final Map<UUID,Book> books=new HashMap<>();
    private final WhisperTickets tickets=new WhisperTickets();
    private final WhisperPeers conversations=new WhisperPeers();
    private volatile boolean closing;
    private boolean polling;
    /** incoming/outgoing: 대기 중인 친구 신청(받은/보낸). notified: 이번 접속에서 이미 알림을 보낸 신청자. */
    private static class Book {List<Entry> entries=List.of(),incoming=List.of(),outgoing=List.of();final Set<UUID> notified=new HashSet<>();boolean ready,busy,checkingSignal,loading,stale,warned;long sequence,nextAction,nextList,window;int requests;
        boolean allow(long n){if(n-window>=1000){window=n;requests=0;}return ++requests<=10;}}
    private static long now(){return System.nanoTime()/1_000_000;}
    /** DB에 기록되는 시각(신청 만료·거절 대기)은 서버 간에 공유되므로 벽시계를 쓴다. */
    private static long wall(){return System.currentTimeMillis();}
    private record Outcome(FriendStore.Result result,FriendStore.Snapshot book){}
    SocialBridge(MagicCodexBridge plugin,ManaBridge mana,StatsService stats)throws Exception{
        this.plugin=plugin;this.mana=mana;this.stats=stats;
        try{var settings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());shared=settings.mariaDb();store=io.submit(()->new FriendStore(plugin.getDataFolder().toPath().resolve("friends.db"),settings)).get(10,TimeUnit.SECONDS);}
        catch(Exception e){io.shutdownNow();throw e;}
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,SocialProtocol.REQUEST,this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,SocialProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        registerCommands();
        mana.windCast=this::castOpened;
        for(Player p:Bukkit.getOnlinePlayers())load(p);
        Bukkit.getScheduler().runTaskTimer(plugin,()->tickets.expire(now()),200,200);
        Bukkit.getScheduler().runTaskTimer(plugin,this::pulse,160,160);
        Bukkit.getScheduler().runTaskTimer(plugin,()->work(()->{store.purge(wall());return true;},v->{},e->{}),100,12000);
    }
    private interface Job<T>{T run()throws Exception;}
    private <T> void work(Job<T> task,Consumer<T> done,Consumer<Exception> fail){
        if(closing)return;
        io.execute(()->{try{T value=task.run();main(()->done.accept(value));}catch(Exception e){plugin.getLogger().warning("친구 저장 처리 실패: "+e);main(()->fail.accept(e));}});
    }
    private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closing)r.run();});}
    private void load(Player p){var b=new Book();books.put(p.getUniqueId(),b);reload(p,b,0);}
    /** 첫 로드. 실패하면 2·4·8·16·32초 뒤 다시 시도하고, 그래도 안 되면 다음 LIST 요청이 다시 불러온다. */
    private void reload(Player p,Book b,int attempt){
        UUID id=p.getUniqueId();if(b.loading||b.ready||books.get(id)!=b)return;b.loading=true;
        work(()->store.snapshot(id,wall()),s->{if(books.get(id)==b)loaded(p,b,s,"");},
            e->{if(books.get(id)!=b)return;b.loading=false;if(attempt<5)Bukkit.getScheduler().runTaskLater(plugin,()->reload(p,b,attempt+1),40L<<attempt);});
    }
    /** DB에서 다시 읽어 클라이언트에 반영한다. message==null이면 새로 생긴 친구를 알려 준다. */
    private void refresh(Player p,Book b,String message){
        UUID id=p.getUniqueId();if(b.loading){b.stale=true;return;}b.loading=true;
        work(()->store.snapshot(id,wall()),s->{if(books.get(id)==b)loaded(p,b,s,message);},e->{if(books.get(id)==b)b.loading=false;});
    }
    private void loaded(Player p,Book b,FriendStore.Snapshot s,String message){
        boolean first=!b.ready;var before=ids(b.entries);b.loading=false;apply(b,s);b.ready=true;String text=message==null?"":message;
        if(message==null&&!first)for(var e:b.entries)if(!before.contains(e.id())){text=shown(p,e).name()+"님과 친구가 되었습니다.";break;}
        if(!first)snapshot(p,0,text);
        lists(p,b);deliverFirstFriend(p,b,0);friendState(p,b,0);
        if(b.stale){b.stale=false;refresh(p,b,"");}
    }
    private static Set<UUID> ids(List<Entry> list){var set=new HashSet<UUID>();for(var e:list)set.add(e.id());return set;}
    private void apply(Book b,FriendStore.Snapshot s){b.entries=s.friends();b.incoming=s.incoming();b.outgoing=s.outgoing();b.notified.retainAll(ids(b.incoming));}
    /** 접속 중이면 현재 닉네임·기숙사, 아니면 저장된 값. */
    private Entry shown(Player viewer,Entry e){var target=Bukkit.getPlayer(e.id());return visible(viewer,target)?new Entry(e.id(),plugin.names().name(target),dorm(target),true):new Entry(e.id(),plugin.names().name(Bukkit.getOfflinePlayer(e.id()),e.name()),e.dormitory(),false);}
    private List<Entry> shown(Player viewer,List<Entry> list){var out=new ArrayList<Entry>(list.size());for(var e:list)out.add(shown(viewer,e));return out;}
    /** 받은/보낸 신청 목록 전체를 보내고, 아직 알리지 않은 신청은 팝업 알림으로 보낸다. */
    private void lists(Player p,Book b){
        packet(p,new Response(SocialProtocol.INCOMING,0,SocialProtocol.NONE,0,"","","",0,shown(p,b.incoming)));
        packet(p,new Response(SocialProtocol.OUTGOING,0,SocialProtocol.NONE,0,"","","",0,shown(p,b.outgoing)));
        announce(p,b);
    }
    /** 신청마다 접속당 한 번만 알린다. 클라이언트 채널이 아직 준비되지 않았으면 다음 pulse에서 다시 시도한다. */
    private void announce(Player p,Book b){
        if(!b.ready||b.incoming.isEmpty()||!p.isOnline()||!p.getListeningPluginChannels().contains(SocialProtocol.RESPONSE)||!p.hasPermission("magiccodex.friends"))return;
        for(var e:b.incoming)if(b.notified.add(e.id())){var s=shown(p,e);packet(p,new Response(SocialProtocol.FRIEND_REQUEST,0,e.id(),0,s.name().isBlank()?"알 수 없음":s.name(),s.dormitory(),"",0,List.of()));}
    }
    /** 8초마다: 미전달 알림 재시도 + (공유 DB 모드) 다른 서버에서 생긴 신청·친구 변화를 묶음 조회로 감지. */
    private void pulse(){
        if(closing)return;
        for(Player p:Bukkit.getOnlinePlayers()){var b=books.get(p.getUniqueId());if(b!=null)announce(p,b);}
        if(!shared||polling)return;
        var online=new ArrayList<UUID>();books.forEach((id,b)->{if(b.ready)online.add(id);});if(online.isEmpty())return;
        polling=true;
        work(()->store.poll(online,wall()),polled->{polling=false;
            for(UUID id:online){
                var b=books.get(id);Player p=Bukkit.getPlayer(id);if(b==null||p==null||!b.ready||b.busy||b.loading)continue;
                var in=polled.incoming().getOrDefault(id,List.of());
                if(!ids(in).equals(ids(b.incoming))){b.incoming=List.copyOf(in);b.notified.retainAll(ids(b.incoming));lists(p,b);}
                if(polled.friends().getOrDefault(id,0)!=b.entries.size())refresh(p,b,null);
            }
        },e->polling=false);
    }
    /** 같은 서버에 접속 중인 상대의 목록도 즉시 갱신한다. (다른 서버라면 pulse가 감지) */
    private void peer(UUID other,String message){Player t=Bukkit.getPlayer(other);var tb=books.get(other);if(t!=null&&tb!=null)refresh(t,tb,message);}
    private static String message(FriendStore.Result r){return switch(r){
        case SELF->"자신에게는 친구 신청을 보낼 수 없습니다.";
        case ALREADY_FRIENDS->"이미 친구입니다.";
        case DUPLICATE->"이미 친구 신청을 보냈습니다. 상대방의 수락을 기다려 주세요.";
        case COOLDOWN->"같은 상대에게는 잠시 후 다시 신청할 수 있습니다.";
        case LIMIT->"보낸 친구 신청이 너무 많습니다. 대기 중인 신청을 취소한 뒤 다시 시도해 주세요. (최대 "+FriendStore.REQUEST_LIMIT+"건)";
        case FRIEND_LIMIT->"친구는 최대 50명까지 등록할 수 있습니다.";
        case TARGET_FRIEND_LIMIT->"상대방의 친구 목록이 가득 찼습니다.";
        case MISSING->"이미 처리되었거나 만료된 친구 신청입니다.";
        default->"처리하지 못했습니다.";
    };}
    /** 친구 관계를 바꾸는 작업 공통 처리: 결과와 함께 최신 목록을 다시 읽어 반영한다. */
    private void mutate(Player p,Book b,long seq,Job<FriendStore.Result> job,Consumer<FriendStore.Result> done){
        UUID id=p.getUniqueId();b.busy=true;
        work(()->new Outcome(job.run(),store.snapshot(id,wall())),o->{if(books.get(id)!=b)return;b.busy=false;apply(b,o.book());done.accept(o.result());lists(p,b);friendState(p,b,0);},
            e->{if(books.get(id)==b){b.busy=false;notice(p,seq,"저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");}});
    }
    /**
     * 수호자의 서약(guardian_oath) "친구 20명" (오너 결정 4, 2026-10-21): 누적 +1 신호 대신 현재 등록된 서로 다른 친구 수를 상태값
     * state.friends 로 보낸다. 끊었다 다시 맺어도 늘지 않고, 같은 값을 다시 보내도 멱등이다. 목록을 새로 읽을 때마다(접속·변경·
     * 다른 서버 변화를 감지한 pulse) 보내므로 상대가 오프라인·다른 서버·사망 상태였어도 다음 로드 때 따라잡는다.
     * 발견 세션이 아직 안 불러와졌으면(false) 2초 간격으로 5번까지 다시 보낸다.
     */
    private void friendState(Player p,Book b,int retry){
        if(!p.isOnline()||books.get(p.getUniqueId())!=b||!b.ready)return;
        if(DiscoveryLink.state(p.getUniqueId(),"state.friends",ids(b.entries).size()))return;
        if(retry<5)Bukkit.getScheduler().runTaskLater(plugin,()->friendState(p,b,retry+1),40);
    }
    private void deliverFirstFriend(Player p,Book book,int retry){
        if(book.checkingSignal)return;book.checkingSignal=true;
        UUID id=p.getUniqueId();work(()->store.pendingSignal(id),pending->{
            if(!pending||books.get(id)!=book||!p.isOnline()){book.checkingSignal=false;return;}
            if(DiscoveryLink.signal(id,"friend.added",1))work(()->{store.signalDelivered(id);return true;},v->book.checkingSignal=false,e->book.checkingSignal=false);
            else {book.checkingSignal=false;if(retry<3)Bukkit.getScheduler().runTaskLater(plugin,()->{if(books.get(id)==book)deliverFirstFriend(p,book,retry+1);},40);}
        },e->book.checkingSignal=false);
    }
    private String dorm(Player p){return plugin.dorm(p);}
    private boolean visible(Player viewer,Player target){return target!=null&&target.isOnline()&&viewer.canSee(target);}
    private void packet(Player p,Response r){if(p.isOnline()&&p.getListeningPluginChannels().contains(SocialProtocol.RESPONSE))p.sendPluginMessage(plugin,SocialProtocol.RESPONSE,SocialProtocol.encode(r));}
    private void notice(Player p,long seq,String text){packet(p,new Response(SocialProtocol.NOTICE,seq,SocialProtocol.NONE,0,"","",text,0,List.of()));}
    private void snapshot(Player p,long seq,String message){
        var b=books.get(p.getUniqueId());if(b==null||!b.ready){notice(p,seq,"친구 목록을 불러오는 중입니다. 잠시 후 다시 시도해 주세요.");return;}
        var current=new ArrayList<Entry>();
        for(var e:b.entries)current.add(shown(p,e));
        packet(p,new Response(SocialProtocol.SNAPSHOT,seq,SocialProtocol.NONE,0,"","",message,0,current));
    }
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] data){
        if(!channel.equals(SocialProtocol.REQUEST))return;
        var b=books.get(p.getUniqueId());if(b==null||!b.allow(now()))return;
        Request r;try{r=SocialProtocol.request(data);}catch(IllegalArgumentException e){
            // 이전 버전 모드는 새 프로토콜을 해석하지 못한다: 접속당 한 번만 안내한다.
            if(!b.warned&&SocialProtocol.legacy(data)){b.warned=true;CodexCommands.warn(p,"friends-mod","친구 기능을 쓰려면 최신 MagicCodex 모드가 필요해요");}
            return;}
        if(r.sequence()<=b.sequence)return;b.sequence=r.sequence();
        long n=now();
        if(r.action()==SocialProtocol.CLOSE)return;
        if(r.action()==SocialProtocol.CANCEL){tickets.cancel(p.getUniqueId(),r.ticket());return;}
        if(!p.hasPermission("magiccodex.friends")){notice(p,r.sequence(),"친구 기능을 사용할 권한이 없습니다.");return;}
        if(r.action()==SocialProtocol.LIST){if(n<b.nextList){notice(p,r.sequence(),"잠시 후 다시 시도해 주세요.");return;}b.nextList=n+1000;
            if(shared||!b.ready){if(b.busy||b.loading){snapshot(p,r.sequence(),"");return;}b.loading=true;
                work(()->store.snapshot(p.getUniqueId(),wall()),s->{if(books.get(p.getUniqueId())==b){b.loading=false;apply(b,s);b.ready=true;snapshot(p,r.sequence(),"");lists(p,b);deliverFirstFriend(p,b,0);friendState(p,b,0);if(b.stale){b.stale=false;refresh(p,b,"");}}},e->{if(books.get(p.getUniqueId())==b)b.loading=false;notice(p,r.sequence(),"친구 목록을 불러오지 못했습니다.");});}
            else{snapshot(p,r.sequence(),"");lists(p,b);}return;}
        if(r.action()!=SocialProtocol.SEND&&r.action()!=SocialProtocol.WHISPER&&n<b.nextAction){notice(p,r.sequence(),"잠시 후 다시 시도해 주세요.");return;}b.nextAction=n+400;
        if(!b.ready||b.busy){notice(p,r.sequence(),"친구 목록을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.");return;}
        switch(r.action()){
            case SocialProtocol.ADD->{if(r.text().isBlank()||r.text().length()>16){notice(p,r.sequence(),"정확한 닉네임을 입력해 주세요.");return;}Player target=plugin.names().resolve(r.text());add(p,target,r.sequence());}
            case SocialProtocol.REMOVE->remove(p,r.target(),r.sequence());
            case SocialProtocol.WHISPER->whisper(p,r.target(),r.sequence());
            case SocialProtocol.SEND->sendMessage(p,r);
            case SocialProtocol.ACCEPT->accept(p,b,r.target(),r.sequence());
            case SocialProtocol.DECLINE->decline(p,b,r.target(),r.sequence());
            case SocialProtocol.WITHDRAW->withdraw(p,b,r.target(),r.sequence());
            default->{}
        }
    }
    /** 친구 추가는 신청으로 시작한다. 상대가 수락해야 서로의 목록에 등록된다. */
    private void add(Player owner,Player target,long seq){
        var b=books.get(owner.getUniqueId());
        if(b==null||!b.ready||b.busy){notice(owner,seq,"친구 목록을 처리하고 있습니다.");return;}
        if(!visible(owner,target)){notice(owner,seq,"접속 중인 플레이어를 찾을 수 없습니다.");return;}
        UUID id=owner.getUniqueId(),other=target.getUniqueId();
        if(id.equals(other)){notice(owner,seq,message(FriendStore.Result.SELF));return;}
        if(isFriend(owner,other)&&isFriend(target,id)){notice(owner,seq,message(FriendStore.Result.ALREADY_FRIENDS));return;}
        if(b.outgoing.stream().anyMatch(e->e.id().equals(other))){notice(owner,seq,message(FriendStore.Result.DUPLICATE));return;}
        var entry=new Entry(other,plugin.names().name(target),dorm(target),false);String name=plugin.names().name(owner),dorm=dorm(owner);long at=wall();
        mutate(owner,b,seq,()->store.request(id,name,dorm,entry,at),result->{
            switch(result){
                case SENT->{snapshot(owner,seq,entry.name()+"님에게 친구 신청을 보냈습니다.");peer(other,"");}
                case ACCEPTED->{snapshot(owner,seq,entry.name()+"님과 친구가 되었습니다.");deliverFirstFriend(owner,b,0);peer(other,name+"님과 친구가 되었습니다.");}
                default->notice(owner,seq,message(result));
            }
        });
    }
    private String requester(Player p,List<Entry> list,UUID other){for(var e:list)if(e.id().equals(other))return shown(p,e).name();return "상대방";}
    private void accept(Player p,Book b,UUID other,long seq){
        UUID id=p.getUniqueId();String name=plugin.names().name(p),dorm=dorm(p),from=requester(p,b.incoming,other);long at=wall();
        mutate(p,b,seq,()->store.accept(id,name,dorm,other,at),result->{
            if(result!=FriendStore.Result.ACCEPTED){notice(p,seq,message(result));return;}
            snapshot(p,seq,from+"님과 친구가 되었습니다.");deliverFirstFriend(p,b,0);peer(other,name+"님이 친구 신청을 수락했습니다.");
        });
    }
    private void decline(Player p,Book b,UUID other,long seq){
        UUID id=p.getUniqueId();long at=wall();
        mutate(p,b,seq,()->store.decline(id,other,at)?FriendStore.Result.DONE:FriendStore.Result.MISSING,result->{
            notice(p,seq,result==FriendStore.Result.DONE?"친구 신청을 거절했습니다.":message(result));if(result==FriendStore.Result.DONE)peer(other,"");
        });
    }
    private void withdraw(Player p,Book b,UUID other,long seq){
        UUID id=p.getUniqueId();long at=wall();
        mutate(p,b,seq,()->store.withdraw(id,other,at)?FriendStore.Result.DONE:FriendStore.Result.MISSING,result->{
            notice(p,seq,result==FriendStore.Result.DONE?"친구 신청을 취소했습니다.":message(result));if(result==FriendStore.Result.DONE)peer(other,"");
        });
    }
    /** 삭제는 양쪽 목록에서 함께 지운다. 이번 접속의 답장 권한도 회수한다. */
    private void remove(Player owner,UUID target,long seq){
        var b=books.get(owner.getUniqueId());if(b.entries.stream().noneMatch(e->e.id().equals(target))){notice(owner,seq,"친구 목록에 없는 플레이어입니다.");return;}
        UUID id=owner.getUniqueId();
        mutate(owner,b,seq,()->store.unfriend(id,target)?FriendStore.Result.DONE:FriendStore.Result.MISSING,result->{
            tickets.remove(id);conversations.forget(id,target);snapshot(owner,seq,"친구 목록에서 삭제했습니다.");peer(target,"");
        });
    }
    private boolean isFriend(Player p,UUID target){var b=books.get(p.getUniqueId());return b!=null&&b.ready&&b.entries.stream().anyMatch(e->e.id().equals(target));}
    /** 전언은 서로 친구일 때만 가능하다 (예전 방식의 한쪽 등록만으로는 불가). 이번 접속에서 전언을 주고받은 상대에게는 답장할 수 있다. */
    private boolean mutual(Player p,Player target){return conversations.contains(p.getUniqueId(),target.getUniqueId())||(isFriend(p,target.getUniqueId())&&isFriend(target,p.getUniqueId()));}
    private static final String NOT_MUTUAL="서로 친구인 상대에게만 전언을 보낼 수 있습니다. 상대방이 친구 신청을 수락해야 합니다.";
    private void whisper(Player p,UUID id,long seq){
        Player target=Bukkit.getPlayer(id);
        if((!isFriend(p,id)&&!conversations.contains(p.getUniqueId(),id))||!visible(p,target)){notice(p,seq,"접속 중인 친구에게만 전언을 보낼 수 있습니다.");return;}
        if(!mutual(p,target)){notice(p,seq,NOT_MUTUAL);return;}
        var ticket=tickets.bind(p.getUniqueId(),id,now());int cooldown=0;boolean fresh=ticket==null;
        if(ticket==null){
            var cast=mana.castWindForFriend(p);
            if(cast.status()!=ManaProtocol.OK){notice(p,seq,ManaBridge.message(cast.status()));return;}
            cooldown=cast.cooldownMillis();ticket=tickets.issue(p.getUniqueId(),id,now());
        }
        packet(p,new Response(SocialProtocol.COMPOSE,seq,id,ticket.token(),plugin.names().name(target),dorm(target),fresh?"cast":"",cooldown,List.of()));
    }
    private void sendMessage(Player sender,Request r){
        String text;try{text=SocialProtocol.cleanMessage(r.text());}catch(IllegalArgumentException e){notice(sender,r.sequence(),"전언은 1~240자의 일반 글자로 입력해 주세요.");return;}
        var target=Bukkit.getPlayer(r.target());
        if(sender.isDead()||(!isFriend(sender,r.target())&&!conversations.contains(sender.getUniqueId(),r.target()))||!visible(sender,target)){notice(sender,r.sequence(),"상대방이 접속 중인지 확인해 주세요.");return;}
        if(!mutual(sender,target)){notice(sender,r.sequence(),NOT_MUTUAL);return;}
        if(!tickets.consume(sender.getUniqueId(),r.target(),r.ticket(),now())){notice(sender,r.sequence(),"전언 시간이 만료되었습니다. 마법을 다시 사용해 주세요.");return;}
        // Literal text only: never dispatch a chat command or parse markup from the message.
        var line=Component.text("\uE101").font(net.kyori.adventure.key.Key.key("magiccodex","whisper"))
            .append(Component.text(" "+plugin.names().name(sender)+" : "+text,NamedTextColor.WHITE).font(net.kyori.adventure.key.Key.key("minecraft","default")));
        conversations.delivered(sender.getUniqueId(),target.getUniqueId());
        target.sendMessage(line);
        sender.sendMessage(line);
        packet(target,new Response(SocialProtocol.RECEIVED,0,sender.getUniqueId(),0,plugin.names().name(sender),dorm(sender),text,0,List.of()));
        packet(sender,new Response(SocialProtocol.SENT,r.sequence(),target.getUniqueId(),r.ticket(),plugin.names().name(target),dorm(target),"전언을 보냈습니다.",0,List.of()));
    }
    private void castOpened(Player p){tickets.issue(p.getUniqueId(),SocialProtocol.NONE,now());packet(p,new Response(SocialProtocol.OPEN,0,SocialProtocol.NONE,0,"","","전언을 보낼 친구를 선택해 주세요.",0,List.of()));}
    @EventHandler public void social(StatsSocialRequestEvent e){
        if(e.getAction()!=StatsSocialRequestEvent.Action.FRIEND)return;
        Player p=e.getViewer();var b=books.get(p.getUniqueId());
        if(!p.hasPermission("magiccodex.friends")){e.setResponse("친구 기능을 사용할 권한이 없습니다.");return;}
        if(b==null||now()<b.nextAction){e.setResponse("잠시 후 다시 시도해 주세요.");return;}b.nextAction=now()+400;
        Player target=e.getTarget();Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())add(p,target,0);});e.setResponse("친구 신청을 보내고 있습니다.");
    }
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"/친구"))return;
        ChacaApi.playerCommand(plugin,Commands.literal("친구").executes(ctx->{
            var p=CodexCommands.self(ctx);
            if(!p.getListeningPluginChannels().contains(SocialProtocol.RESPONSE))throw ChacaApi.error("최신 MagicCodex 모드가 필요해요");
            packet(p,new Response(SocialProtocol.OPEN,0,SocialProtocol.NONE,0,"","","",0,List.of()));return Command.SINGLE_SUCCESS;
        }),"친구창 열기",List.of());
        // /유저관리 <닉네임> 친구 <보기|끊기 <닉네임>|요청비우기>
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("친구")
            .then(Commands.literal("보기").executes(ctx->{
                UUID id=ChacaApi.target(ctx);var sender=CodexCommands.sender(ctx);
                work(()->store.snapshot(id,wall()),snap->{
                    var rows=new ArrayList<String>();
                    rows.add(" 친구 "+snap.friends().size()+"명");for(var e:snap.friends())rows.add("  · "+adminName(e)+(Bukkit.getPlayer(e.id())!=null?" (접속 중)":""));
                    rows.add(" 받은 신청 "+snap.incoming().size()+"건");for(var e:snap.incoming())rows.add("  · "+adminName(e));
                    rows.add(" 보낸 신청 "+snap.outgoing().size()+"건");for(var e:snap.outgoing())rows.add("  · "+adminName(e));
                    CodexCommands.lines(sender,"── "+ChacaApi.name(id)+" 님의 친구 ──",rows);
                },e->ChacaApi.fail(sender,"친구 목록을 읽지 못했어요",e));return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("끊기").then(Commands.argument("상대",ChacaApi.player()).executes(ctx->{
                UUID id=ChacaApi.target(ctx),other=ChacaApi.resolve(ctx,"상대");var sender=CodexCommands.sender(ctx);
                if(id.equals(other))throw ChacaApi.error("같은 유저예요");
                work(()->store.unfriend(id,other),done->{
                    if(!done){ChacaApi.fail(sender,ChacaApi.name(id)+" 님과 "+ChacaApi.name(other)+" 님은 친구가 아니에요",null);return;}
                    tickets.remove(id);tickets.remove(other);conversations.forget(id,other);conversations.forget(other,id);peer(id,"");peer(other,"");
                    ChacaApi.ok(sender,ChacaApi.name(id)+" 님과 "+ChacaApi.name(other)+" 님의 친구 관계를 끊었어요");
                },e->ChacaApi.fail(sender,"친구 관계를 바꾸지 못했어요",e));return Command.SINGLE_SUCCESS;
            })))
            .then(Commands.literal("요청비우기").executes(ctx->{
                UUID id=ChacaApi.target(ctx);var sender=CodexCommands.sender(ctx);
                work(()->store.clearRequests(id),n->{peer(id,"");for(Player p:Bukkit.getOnlinePlayers()){var b=books.get(p.getUniqueId());if(b!=null&&b.ready)refresh(p,b,"");}
                    ChacaApi.ok(sender,ChacaApi.name(id)+" 님의 친구 신청 "+n+"건을 지웠어요");},e->ChacaApi.fail(sender,"친구 신청을 지우지 못했어요",e));return Command.SINGLE_SUCCESS;
            })),"친구 보기·관계 끊기·신청 비우기","친구 보기");
    }
    private String adminName(Entry e){return plugin.names().name(Bukkit.getOfflinePlayer(e.id()),e.name());}
    @EventHandler public void join(PlayerJoinEvent e){load(e.getPlayer());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){
        Player p=e.getPlayer();UUID id=p.getUniqueId();String name=plugin.names().name(p),dorm=dorm(p);books.remove(id);tickets.remove(id);conversations.remove(id);
        work(()->{store.profile(id,name,dorm);return true;},v->{for(var b:books.values())b.entries=b.entries.stream().map(f->f.id().equals(id)?new Entry(id,name,dorm,false):f).toList();},error->{});
    }
    @Override public void close(){
        closing=true;mana.windCast=null;books.clear();tickets.clear();conversations.clear();
        io.submit(()->{try{store.close();}catch(Exception e){plugin.getLogger().warning(e.toString());}});io.shutdown();
        try{if(!io.awaitTermination(20,TimeUnit.SECONDS))plugin.getLogger().severe("친구 저장 작업 종료 대기시간을 초과했습니다.");}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
