package school.magiccodex.paper;

import java.util.*;
import org.bukkit.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.AscensionProtocol;
import school.magiccodex.protocol.AscensionProtocol.*;

final class AscensionBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final StatsService stats;
    private final NamespacedKey key;
    private final AscensionGate gate=new AscensionGate();
    private final Map<UUID,Long> next=new HashMap<>();
    private final Map<UUID,Long> nextClaim=new HashMap<>();
    AscensionBridge(MagicCodexBridge plugin,StatsService stats){
        this.plugin=plugin;this.stats=stats;key=new NamespacedKey(plugin,"player_circle");
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,AscensionProtocol.REQUEST,this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,AscensionProtocol.RESPONSE);
        registerCommands();
    }
    int current(Player p){
        Integer stored=p.getPersistentDataContainer().get(key,PersistentDataType.INTEGER);
        Integer existing=stats.get(p.getUniqueId()).circle();
        return Math.clamp(stored!=null?stored:existing!=null?existing:1,1,9);
    }
    private String permission(int rank){return "magiccodex.ascend."+rank;}
    private boolean allowed(Player p,int rank){return rank<9&&p.hasPermission("magiccodex.ascend")&&p.hasPermission(permission(rank+1));}
    private long now(){return System.nanoTime()/1_000_000;}
    private boolean limit(Player p){long n=now();if(next.getOrDefault(p.getUniqueId(),0L)>n)return false;next.put(p.getUniqueId(),n+500);return true;}
    private void send(Player p,Response r){if(p.getListeningPluginChannels().contains(AscensionProtocol.RESPONSE))p.sendPluginMessage(plugin,AscensionProtocol.RESPONSE,AscensionProtocol.encodeResponse(r));}
    /** /클래스승급: 승급 창 열기 (조건은 magiccodex.ascend.&lt;다음 서클&gt; 권한). */
    private void open(Player p)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        if(!p.hasPermission("magiccodex.ascend"))throw ChacaApi.error("클래스 승급을 쓸 수 없어요");
        if(!plugin.playerStateReady(p))throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 후 다시 해 주세요");
        if(!p.getListeningPluginChannels().contains(AscensionProtocol.RESPONSE))throw ChacaApi.error("최신 MagicCodex 모드가 필요해요");
        if(p.isDead()||!limit(p))return;
        int from=current(p);var offer=gate.open(p.getUniqueId(),from,now());
        send(p,new Response(AscensionProtocol.OFFER,offer.token(),from,Math.min(9,from+1),allowed(p,from),from==9?"이미 최고 클래스에 도달했습니다.":allowed(p,from)?"새로운 클래스로 나아갈 준비가 되었습니다.":"아직 승급 조건을 충족하지 못했습니다."));
    }
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"/클래스승급"))return;
        ChacaApi.playerCommand(plugin,Commands.literal("클래스승급").executes(ctx->{open(CodexCommands.self(ctx));return Command.SINGLE_SUCCESS;}),"클래스(서클) 승급 창 열기",List.of());
        ChacaApi.summary(plugin,"서클",id->{var p=Bukkit.getPlayer(id);return p==null?"":current(p)+"서클";});
        // /유저관리 <닉네임> 서클 <보기|설정 <1~9>>
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("서클")
            .then(Commands.literal("보기").executes(ctx->{
                var p=ChacaApi.targetOnline(ctx);int c=current(p);var unlocked=new ArrayList<String>();
                for(int n=2;n<=9;n++)if(p.hasPermission(permission(n)))unlocked.add(Integer.toString(n));
                ChacaApi.lines(CodexCommands.sender(ctx),List.of("── "+ChacaApi.name(p.getUniqueId())+" 님의 서클 ──"," 지금 서클: "+c,
                    " 승급 조건이 열린 서클: "+(unlocked.isEmpty()?"없음":String.join(", ",unlocked)),
                    " 다음 승급: "+(c>=9?"최고 서클":allowed(p,c)?"가능":"조건 미달 ("+permission(c+1)+")")));
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("설정").then(Commands.argument("서클",IntegerArgumentType.integer(1,9)).executes(ctx->{
                var p=ChacaApi.targetOnline(ctx);int to=IntegerArgumentType.getInteger(ctx,"서클");var sender=CodexCommands.sender(ctx);
                if(!plugin.playerStateReady(p))throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 후 다시 해 주세요");
                setCircle(p,to);
                // 승급 권한 정리: magiccodex.ascend.2..<서클> 은 주고(이미 도달), 그 위는 회수한다 (다음 승급은 조건을 다시 채워야 함).
                UUID id=p.getUniqueId();var futures=new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
                for(int n=2;n<=9;n++)futures.add(ChacaApi.permission(id,permission(n),n<=to));
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((v,error)->{
                    if(error!=null)ChacaApi.fail(sender,"서클은 "+to+"(으)로 바꿨지만 승급 권한 정리에 실패했어요",error);
                    else ChacaApi.ok(sender,ChacaApi.name(id)+" 님의 서클을 "+to+"(으)로 정했어요");
                });
                return Command.SINGLE_SUCCESS;
            }))),"서클 보기·설정 (승급 권한도 함께 정리)","서클 보기");
    }
    /** 서클 저장: PDC player_circle + codex_player_state.circle(다음 저장) + 스탯창 값. */
    private void setCircle(Player p,int to){
        p.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,to);
        p.saveData();plugin.savePlayerState(p);
        var extra=stats.get(p.getUniqueId());stats.set(p.getUniqueId(),new StatsService.Additional(to,extra.dormitory(),extra.power(),extra.popularity()));
        gate.remove(p.getUniqueId());
    }
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(!channel.equals(AscensionProtocol.REQUEST))return;
        Request r;try{r=AscensionProtocol.decodeRequest(bytes);}catch(IllegalArgumentException e){return;}
        int from=current(p);
        if(r.action()==AscensionProtocol.HELLO){if(limit(p))send(p,new Response(AscensionProtocol.SNAPSHOT,0,from,from,false,""));return;}
        long n=now();if(nextClaim.getOrDefault(p.getUniqueId(),0L)>n)return;nextClaim.put(p.getUniqueId(),n+500);
        if(!gate.consume(p.getUniqueId(),r.token(),from,!p.isDead()&&allowed(p,from),now())){
            send(p,new Response(AscensionProtocol.DENIED,r.token(),from,from,false,"승급 요청이 만료됐거나 조건이 변경됐습니다. /클래스승급으로 다시 확인해 주세요."));return;
        }
        int to=from+1;
        // Persist the authoritative rank before notifying the client. UI closing cannot undo/duplicate it.
        p.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,to);
        p.saveData();
        plugin.savePlayerState(p);
        var extra=stats.get(p.getUniqueId());stats.set(p.getUniqueId(),new StatsService.Additional(to,extra.dormitory(),extra.power(),extra.popularity()));
        send(p,new Response(AscensionProtocol.SUCCESS,r.token(),from,to,true,""));
    }
    @EventHandler public void quit(PlayerQuitEvent e){gate.remove(e.getPlayer().getUniqueId());next.remove(e.getPlayer().getUniqueId());nextClaim.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){gate.clear();next.clear();nextClaim.clear();}
}
