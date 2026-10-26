package school.magiccodex.paper;

import java.util.*;
import org.bukkit.Bukkit;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.TemperatureProtocol;

final class TemperatureBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final TemperatureFeed feed;
    private final TemperatureService service;
    TemperatureService service(){return service;}
    private static long now(){return System.nanoTime()/1_000_000;}
    TemperatureBridge(MagicCodexBridge plugin){
        this.plugin=plugin;float fallback=18;
        try{fallback=readDefault();}catch(IllegalArgumentException e){plugin.getLogger().warning("기본 온도 설정 오류: 18°C 사용. "+e.getMessage());}
        feed=new TemperatureFeed(fallback);service=new TemperatureService(feed);
        var messenger=plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin,TemperatureProtocol.REQUEST,this);
        messenger.registerOutgoingPluginChannel(plugin,TemperatureProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getServicesManager().register(TemperatureService.class,service,plugin,ServicePriority.Normal);
        registerCommands();
        // Cached reads only: no biome/world scan. Coalesce future frequent API changes to two sends/second.
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,10,10);
    }
    private float readDefault(){
        float value=(float)plugin.getConfig().getDouble("temperature.default-celsius",18);
        TemperatureProtocol.validate(value);return value;
    }
    public void onPluginMessageReceived(String channel,Player player,byte[] data){
        if(!TemperatureProtocol.REQUEST.equals(channel)||!TemperatureProtocol.validRequest(data))return;
        if(!player.getListeningPluginChannels().contains(TemperatureProtocol.RESPONSE))return;
        long time=now();
        if(feed.subscribe(player.getUniqueId(),time))send(player,time);
    }
    private void send(Player player,long time){
        var snapshot=feed.poll(player.getUniqueId(),time);
        if(snapshot!=null)player.sendPluginMessage(plugin,TemperatureProtocol.RESPONSE,TemperatureProtocol.encode(snapshot));
    }
    private void tick(){
        long time=now();
        for(var id:feed.subscribers()){
            var p=Bukkit.getPlayer(id);
            if(p==null||!p.isOnline()||!p.getListeningPluginChannels().contains(TemperatureProtocol.RESPONSE)){feed.remove(id);continue;}
            send(p,time);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e){feed.remove(e.getPlayer().getUniqueId());}
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    private static String celsius(float v){return (v==Math.rint(v)?Integer.toString((int)v):String.format(Locale.ROOT,"%.1f",v))+"°C";}
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"온도"))return;
        ChacaApi.reload(plugin,"온도","기본 온도(config.yml temperature.default-celsius), 유저별 지정값은 유지",()->{plugin.reloadConfig();feed.fallback(readDefault());});
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("온도")
            .then(Commands.literal("보기").executes(ctx->{var p=ChacaApi.targetOnline(ctx);ChacaApi.ok(CodexCommands.sender(ctx),ChacaApi.name(p.getUniqueId())+" 님의 체감 온도 "+celsius(service.getTemperature(p.getUniqueId())));return Command.SINGLE_SUCCESS;}))
            .then(Commands.literal("설정").then(Commands.argument("값",FloatArgumentType.floatArg(-100,100)).executes(ctx->{
                var p=ChacaApi.targetOnline(ctx);float v=FloatArgumentType.getFloat(ctx,"값");service.setTemperature(p,v);
                ChacaApi.ok(CodexCommands.sender(ctx),ChacaApi.name(p.getUniqueId())+" 님의 온도를 "+celsius(service.getTemperature(p.getUniqueId()))+"로 고정했어요");return Command.SINGLE_SUCCESS;
            })))
            .then(Commands.literal("초기화").executes(ctx->{var p=ChacaApi.targetOnline(ctx);service.resetTemperature(p);ChacaApi.ok(CodexCommands.sender(ctx),ChacaApi.name(p.getUniqueId())+" 님의 온도를 계절·지역 기준으로 되돌렸어요");return Command.SINGLE_SUCCESS;})),
            "체감 온도 보기·고정·초기화","온도 보기");
    }
    public void close(){Bukkit.getServicesManager().unregister(TemperatureService.class,service);feed.clear();}
}
