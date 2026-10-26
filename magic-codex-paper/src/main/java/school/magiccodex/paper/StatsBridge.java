package school.magiccodex.paper;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.StatsProtocol;
import school.magiccodex.protocol.StatsProtocol.*;

/**
 * 정보창 (내 정보 · 상대 정보) 서버 쪽. v2 채널은 옛 모드 그대로, v3 채널(정보창 v4, 2026-10-26)은 칭호·하우징 좋아요·퀘스트 요약·
 * 장신구·아티팩트·빗자루·인기도 상태를 더 보낸다. 인기도 올리기·내리기는 {@link PopularityBridge} (메모 #87).
 * 무거운 일은 하지 않는다: 하우징 좋아요·인기도 점수는 비동기로 읽어 캐시하고, 스냅샷은 캐시 값만 쓴다.
 */
final class StatsBridge implements PluginMessageListener,Listener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final ManaBridge mana;
    private final StatsService service=new StatsService();
    StatsService service(){return service;}
    /** 웅크리고 우클릭(NicknameBridge)이 스탯창을 여는 데 쓴다. */
    static volatile StatsBridge active;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<UUID,Long> nextOpen=new HashMap<>();
    private final Map<UUID,Learned> learned=new HashMap<>();
    /** 하우징 좋아요 합계 캐시 (ChacaHousing.likesOf, 비동기). value null = 모름. */
    private final Map<UUID,Likes> likes=new HashMap<>();
    private final Set<UUID> likesLoading=new HashSet<>();
    private PopularityBridge popularity;
    private long serial;
    private record Learned(long until,int count,List<String> catalog){}
    private record Likes(Integer value,long until){}
    private static class Session {final long token;final UUID target;long expires,nextRefresh,nextAction;Session(long token,UUID target,long now){this.token=token;this.target=target;expires=now+20000;nextRefresh=now+3000;}}
    private static long now(){return System.nanoTime()/1_000_000;}
    StatsBridge(MagicCodexBridge plugin,ManaBridge mana){
        this.plugin=plugin;this.mana=mana;active=this;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,StatsProtocol.REQUEST,this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,StatsProtocol.RESPONSE);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,StatsProtocol.REQUEST3,this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,StatsProtocol.RESPONSE3);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getServicesManager().register(StatsService.class,service,plugin,ServicePriority.Normal);
        if(CodexCommands.ready(plugin,"/스탯창")){
            ChacaApi.playerCommand(plugin,Commands.literal("스탯창").executes(ctx->{var p=CodexCommands.self(ctx);open(p,p);return Command.SINGLE_SUCCESS;})
                .then(Commands.argument("닉네임",ChacaApi.player()).executes(ctx->{var p=CodexCommands.self(ctx);open(p,ChacaApi.resolveOnline(ctx,"닉네임"));return Command.SINGLE_SUCCESS;})),
                "스탯창 열기 (닉네임을 넣으면 그 유저의 스탯창)",List.of());
        }
        // 닉네임 저장소(DisplayNames)는 허브보다 먼저 만들어지므로 그 명령은 여기서 붙인다.
        if(plugin.names()!=null)plugin.names().nicknames().registerCommands();
        // /테스트 화면 미리보기 (MagicCodexBridge.onEnable 을 고치지 않고 여기서 만든다)
        try{new PreviewSender(plugin,mana);}catch(RuntimeException|LinkageError error){plugin.getLogger().warning("미리보기 명령 준비 실패: "+error);}
        // 인기도 (메모 #87): 정보창 버튼과 같은 곳에서 만든다 (MagicCodexBridge.onEnable 을 고치지 않음)
        try{popularity=new PopularityBridge(plugin);}catch(Exception|LinkageError error){plugin.getLogger().severe("인기도 준비 실패 (popularity.yml·DB 확인): "+error);}
        Bukkit.getScheduler().runTaskTimer(plugin,()->{long n=now();sessions.values().removeIf(s->s.expires<n);learned.values().removeIf(s->s.until()<n);nextOpen.values().removeIf(t->t<n);likes.values().removeIf(l->l.until()<n);},200,200);
    }
    /** 스탯창 열기 (/스탯창, 웅크리고 우클릭). 실패는 유저가 고칠 수 있는 이유만 알린다. */
    void open(Player viewer,Player target)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        if(!viewer.hasPermission("magiccodex.stats.view"))throw ChacaApi.error("스탯창을 열 수 없어요");
        long n=now();if(nextOpen.getOrDefault(viewer.getUniqueId(),0L)>n)return;nextOpen.put(viewer.getUniqueId(),n+1000);
        if(!viewer.getListeningPluginChannels().contains(StatsProtocol.RESPONSE)&&!v3(viewer))throw ChacaApi.error("최신 MagicCodex 모드가 필요해요");
        if(!visible(viewer,target))throw ChacaApi.error("접속 중인 유저를 찾을 수 없어요");
        var session=new Session(++serial,target.getUniqueId(),n);sessions.put(viewer.getUniqueId(),session);
        if(popularity!=null)popularity.refresh(target.getUniqueId());
        snapshot(viewer,target,session,StatsProtocol.OPEN);
    }
    /** v3 내 정보 (단축키로 연 내 정보창이 서버 값을 받는다). 명령과 같은 1초 간격. */
    private void openSelf(Player viewer){
        if(!viewer.hasPermission("magiccodex.stats.view")||!v3(viewer))return;
        long n=now();if(nextOpen.getOrDefault(viewer.getUniqueId(),0L)>n)return;nextOpen.put(viewer.getUniqueId(),n+1000);
        var session=new Session(++serial,viewer.getUniqueId(),n);sessions.put(viewer.getUniqueId(),session);
        if(popularity!=null)popularity.refresh(viewer.getUniqueId());
        snapshot(viewer,viewer,session,StatsProtocol.OPEN);
    }
    private boolean visible(Player viewer,Player target){return target!=null&&target.isOnline()&&viewer.canSee(target);}
    private static boolean v3(Player p){return p.getListeningPluginChannels().contains(StatsProtocol.RESPONSE3);}
    @Override public void onPluginMessageReceived(String channel,Player viewer,byte[] bytes){
        boolean three=channel.equals(StatsProtocol.REQUEST3);
        if(!three&&!channel.equals(StatsProtocol.REQUEST))return;
        Request r;try{r=three?StatsProtocol.decodeRequest3(bytes):StatsProtocol.decodeRequest(bytes);}catch(IllegalArgumentException e){return;}
        if(r.action()==StatsProtocol.SELF){if(r.target().equals(viewer.getUniqueId()))openSelf(viewer);return;}
        var s=sessions.get(viewer.getUniqueId());long n=now();
        if(s==null||s.expires<n||s.token!=r.session()||!s.target.equals(r.target()))return;
        if(r.action()==StatsProtocol.CLOSE){sessions.remove(viewer.getUniqueId());return;}
        if(r.action()==StatsProtocol.REFRESH){if(n<s.nextRefresh)return;s.nextRefresh=n+3000;}
        else {if(n<s.nextAction)return;s.nextAction=n+1000;}
        Player target=Bukkit.getPlayer(s.target);
        if(!viewer.hasPermission("magiccodex.stats.view")||!visible(viewer,target)){
            deliver(viewer,new Response(StatsProtocol.GONE,s.token,s.target,"상대방 정보를 더 이상 볼 수 없습니다.",null));sessions.remove(viewer.getUniqueId());return;
        }
        if(r.action()==StatsProtocol.REFRESH){s.expires=n+20000;snapshot(viewer,target,s,StatsProtocol.SNAPSHOT);return;}
        if(r.action()==StatsProtocol.POPULARITY||r.action()==StatsProtocol.DOWN){vote(viewer,target,s,r.action()==StatsProtocol.POPULARITY);return;}
        String message;
        if(viewer.getUniqueId().equals(target.getUniqueId()))message="자신에게는 사용할 수 없습니다.";
        else {
            var event=new StatsSocialRequestEvent(viewer,target,StatsSocialRequestEvent.Action.FRIEND);
            Bukkit.getPluginManager().callEvent(event);
            message=event.getResponse()==null?"요청은 전달됐지만 해당 기능은 아직 준비 중입니다.":event.getResponse();
        }
        deliver(viewer,new Response(StatsProtocol.NOTICE,s.token,s.target,message,null));
    }
    /** 인기도 올리기(POPULARITY)·내리기(DOWN). 결과는 v3 면 VOTED(새 점수·오늘 조절함·해제권 수), 옛 모드면 문구만. */
    private void vote(Player viewer,Player target,Session s,boolean up){
        if(popularity==null){deliver(viewer,new Response(StatsProtocol.NOTICE,s.token,s.target,"인기도 기능이 준비되지 않았어요.",null));return;}
        popularity.vote(viewer,target,up,result->{
            if(!viewer.isOnline()||sessions.get(viewer.getUniqueId())!=s)return;
            String message=result.message()==null?"":result.message();if(message.length()>160)message=message.substring(0,160);
            if(v3(viewer))send3(viewer,new Response3(new Response(StatsProtocol.VOTED,s.token,s.target,message,null),null,new Vote(result.score(),result.voted(),result.tickets())));
            else send(viewer,new Response(StatsProtocol.NOTICE,s.token,s.target,message,null));
        });
    }
    private void snapshot(Player viewer,Player target,Session s,int kind){
        var extra=service.get(target.getUniqueId());var m=mana.mana.snapshot(target.getUniqueId()).orElse(null);
        var catalog=mana.discoveryPermissions();var known=learned.get(target.getUniqueId());long n=now();
        if(known==null||known.until()<n||!known.catalog().equals(catalog)){int count=0;for(String p:catalog)if(target.hasPermission(p))count++;known=new Learned(n+10000,count,catalog);learned.put(target.getUniqueId(),known);}
        String texture="",signature="";
        for(var property:target.getPlayerProfile().getProperties())if(property.getName().equals("textures")){texture=property.getValue();signature=property.getSignature()==null?"":property.getSignature();break;}
        if(texture.length()>4096||signature.length()>2048){texture="";signature="";}
        var eq=target.getEquipment();var items=new ArrayList<byte[]>();
        for(var item:new ItemStack[]{eq.getItemInMainHand(),eq.getItemInOffHand(),eq.getBoots(),eq.getLeggings(),eq.getChestplate(),eq.getHelmet()})items.add(item(item));
        var hp=target.getAttribute(Attribute.MAX_HEALTH);var armor=target.getAttribute(Attribute.ARMOR);
        Double popularityScore=popularity==null?null:popularity.score(target.getUniqueId());
        var profile=new Profile(target.getUniqueId(),plugin.names().name(target),plugin.circle(target),plugin.dorm(target),plugin.equipmentPower(target.getUniqueId(),extra.power()),popularityScore!=null?popularityScore:extra.popularity(),
            hp==null?20:hp.getValue(),armor==null?0:armor.getValue(),m==null?null:m.current(),m==null?null:m.maximum(),m==null?null:m.regeneration(),known.count(),catalog.size(),texture,signature,items,m==null?null:m.haste());
        var response=new Response(kind,s.token,s.target,"",profile);
        if(v3(viewer))send3(viewer,new Response3(response,extra(viewer,target),null));
        else send(viewer,response);
    }
    /** v3 추가 값. 모르는 값은 null/"" (꾸며 내지 않음). 모두 메모리 값 — DB 는 각자 비동기 캐시. */
    private Extra extra(Player viewer,Player target){
        UUID id=target.getUniqueId();
        String title="";var titles=plugin.titleService();
        if(titles!=null){String a=titles.placeholder(id,"prefix"),b=titles.placeholder(id,"suffix");title=((a==null?"":a)+" "+(b==null?"":b)).strip();}
        Integer quests=null;String tracked="";int current=0,required=0;var q=plugin.questBridge();
        if(q!=null){var sum=q.summary(target);if(sum!=null){quests=sum.active();tracked=sum.tracked();current=sum.current();required=sum.required();}}
        var accessories=new ArrayList<byte[]>();var equipment=plugin.equipmentBridge();
        for(int i=1;i<=StatsProtocol.ACCESSORIES;i++)accessories.add(item(equipment==null?null:equipment.accessoryItem(target,i)));
        String artifact="";var artifacts=plugin.artifactBridge();
        if(artifacts!=null){var list=artifacts.equipped(target);if(!list.isEmpty())artifact=list.get(0);}
        boolean voted=popularity!=null&&!viewer.getUniqueId().equals(id)&&popularity.voted(viewer.getUniqueId(),id);
        int tickets=popularity==null?0:popularity.tickets(viewer);
        return new Extra(cut(title,96),likes(id),quests,cut(tracked,64),current,required,List.copyOf(accessories),cut(artifact,64),cut(broom(target),64),voted,tickets);
    }
    private static String cut(String s,int max){return s==null?"":s.length()>max?s.substring(0,max):s;}
    private static byte[] item(ItemStack item){
        if(item==null||item.getType().isAir())return new byte[0];
        byte[] b=item.serializeAsBytes();
        if(b.length>3072)b=new ItemStack(item.getType()).serializeAsBytes();
        return b.length<=3072?b:new byte[0];
    }
    /** 장착 빗자루 ID (ChacademyMotion.equippedBroomId, kyudichi Hooks 와 같은 방식). 없으면 "". */
    private static String broom(Player p){
        var m=Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if(m==null||!m.isEnabled())return "";
        try{return m.getClass().getMethod("equippedBroomId",Player.class).invoke(m,p) instanceof String s?s:"";}
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){return "";}
    }
    /** 하우징 좋아요 합계: 캐시 값(모르면 null), 오래됐으면 ChacaHousing.likesOf 로 비동기 갱신 → 다음 새로고침에 보인다. */
    private Integer likes(UUID id){
        long n=now();var cached=likes.get(id);
        if((cached==null||cached.until()<n)&&likesLoading.add(id)){
            var housing=Bukkit.getPluginManager().getPlugin("ChacaHousing");
            java.util.function.Consumer<Integer> done=value->{likesLoading.remove(id);likes.put(id,new Likes(value,now()+(value==null?60000:30000)));};
            if(housing==null||!housing.isEnabled())done.accept(null);
            else try{housing.getClass().getMethod("likesOf",UUID.class,java.util.function.Consumer.class).invoke(housing,id,done);}
            catch(ReflectiveOperationException|RuntimeException|LinkageError e){done.accept(null);}
            cached=likes.get(id);
        }
        return cached==null?null:cached.value();
    }
    private void deliver(Player p,Response r){if(v3(p))send3(p,new Response3(r,null,null));else send(p,r);}
    private void send(Player p,Response r){if(p.getListeningPluginChannels().contains(StatsProtocol.RESPONSE))p.sendPluginMessage(plugin,StatsProtocol.RESPONSE,StatsProtocol.encodeResponse(r));}
    private void send3(Player p,Response3 r){
        try{p.sendPluginMessage(plugin,StatsProtocol.RESPONSE3,StatsProtocol.encodeResponse3(r));}
        catch(IllegalArgumentException e){plugin.getLogger().warning("정보창 응답을 만들지 못했어요: "+e.getMessage());}
    }
    @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();sessions.remove(id);nextOpen.remove(id);learned.remove(id);likes.remove(id);service.remove(id);
        var it=sessions.entrySet().iterator();while(it.hasNext()){var entry=it.next();if(entry.getValue().target.equals(id)){var p=Bukkit.getPlayer(entry.getKey());if(p!=null)deliver(p,new Response(StatsProtocol.GONE,entry.getValue().token,id,"상대방이 접속을 종료했습니다.",null));it.remove();}}
    }
    @Override public void close(){if(active==this)active=null;if(popularity!=null){popularity.close();popularity=null;}sessions.clear();nextOpen.clear();learned.clear();likes.clear();service.clear();Bukkit.getServicesManager().unregister(service);}
}
