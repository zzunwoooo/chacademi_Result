package school.magiccodex.paper;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.WalletProtocol;
import school.magiccodex.protocol.WalletProtocol.Snapshot;

/** Optional Vault integration: cached public API reflection avoids bundling a second Vault API. */
final class WalletBridge implements PluginMessageListener,Listener,AutoCloseable {
    private static final String ECONOMY="net.milkbowl.vault.economy.Economy";
    private final JavaPlugin plugin;
    private final WalletSubscriptions subscriptions;
    private Object economy;
    private Method balance;
    private Class<?> economyType;
    private long retryAt;
    private static long now(){return System.nanoTime()/1_000_000;}
    WalletBridge(JavaPlugin plugin){
        this.plugin=plugin;
        migrateSettings();
        long interval=Math.clamp(plugin.getConfig().getLong("wallet.refresh-millis",500),250,30000);
        int budget=Math.clamp(plugin.getConfig().getInt("wallet.max-reads-per-second",128),1,1024);
        subscriptions=new WalletSubscriptions(interval,budget);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,WalletProtocol.REQUEST,this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,WalletProtocol.RESPONSE);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        findEconomy();
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::process,1,2);
        CmdKit.register(plugin,"/유저관리 돈",this::commands);
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 돈 (2026-10-20)

    private static final double MAX_MONEY=1e15;
    private void commands(){
        var root=CmdKit.literal("돈").then(CmdKit.literal("보기").executes(CmdKit.run(ctx->{
            UUID id=ChacaApi.target(ctx);double now=money(id);
            CmdKit.lines(CmdKit.sender(ctx),java.util.List.of("── "+CmdKit.name(id)+" 돈 ──",AdminCommandRules.number(now)+" G"));
        })));
        for(String change:AdminCommandRules.CHANGES)
            root.then(CmdKit.literal(change).then(CmdKit.argument("값",com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,MAX_MONEY)).executes(CmdKit.run(ctx->{
                UUID id=ChacaApi.target(ctx);double value=com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx,"값");
                double before=money(id);
                double after=switch(change){case "설정"->value;case "추가"->before+value;default->before-value;};
                if(after<0)throw ChacaApi.error(CmdKit.name(id)+" 님 돈이 "+AdminCommandRules.number(before)+" G라서 그만큼 뺄 수 없어요");
                if(after>MAX_MONEY)throw ChacaApi.error("돈은 최대 "+AdminCommandRules.number(MAX_MONEY)+" G까지예요");
                double delta=after-before;
                if(delta!=0)transfer(id,Math.abs(delta),delta>0);
                double result=money(id);
                plugin.getLogger().info("WALLET ADMIN admin="+CmdKit.sender(ctx).getName()+" player="+id+" action="+change+" value="+value+" before="+before+" after="+result);
                AuditLog.log(id,AuditLog.돈,"관리자 돈 "+change+" "+AdminCommandRules.number(value)+" G ("+AdminCommandRules.number(before)+" → "+AdminCommandRules.number(result)+") · "+CmdKit.sender(ctx).getName(),"관리자",CmdKit.sender(ctx).getName(),"변경",change,"값",value,"전",before,"후",result,"증감",delta);
                CmdKit.ok(CmdKit.sender(ctx),CmdKit.name(id)+" 님 돈 "+AdminCommandRules.number(result)+" G ("+(delta>=0?"+":"")+AdminCommandRules.number(delta)+")");
            }))));
        ChacaApi.branch(plugin,ChacaApi.유저관리,root,"돈 보기·설정·추가·빼기 (Vault)","돈 보기");
        ChacaApi.summary(plugin,"돈",id->{var p=plugin.getServer().getPlayer(id);if(p==null||economy==null||balance==null)return "";try{return AdminCommandRules.number(((Number)balance.invoke(economy,p)).doubleValue())+" G";}catch(ReflectiveOperationException|RuntimeException e){return "";}});
    }
    private Object economy()throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        if(economy==null)findEconomy();
        if(economy==null||balance==null)throw ChacaApi.error("경제 플러그인(Vault)이 연결되지 않았어요");
        return economy;
    }
    private double money(UUID id)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Object e=economy();
        try{double v=((Number)balance.invoke(e,plugin.getServer().getOfflinePlayer(id))).doubleValue();if(!Double.isFinite(v))throw new IllegalStateException("balance "+v);return v;}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Vault getBalance failed",error);}
    }
    /** Vault depositPlayer / withdrawPlayer. 거절되면 유저 실패, 결과를 알 수 없으면 예외(허브가 콘솔에 남김). */
    private void transfer(UUID id,double amount,boolean deposit)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Object e=economy();
        Object response;
        try{response=economyType.getMethod(deposit?"depositPlayer":"withdrawPlayer",OfflinePlayer.class,double.class).invoke(e,plugin.getServer().getOfflinePlayer(id),amount);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Vault "+(deposit?"deposit":"withdraw")+" failed for "+id,error);}
        boolean success;
        try{success=(boolean)response.getClass().getMethod("transactionSuccess").invoke(response);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Vault response unreadable for "+id,error);}
        if(!success){String why="";try{why=String.valueOf(response.getClass().getField("errorMessage").get(response));}catch(ReflectiveOperationException ignored){}plugin.getLogger().warning("WALLET ADMIN refused player="+id+" amount="+amount+" deposit="+deposit+" reason="+why);throw ChacaApi.error("경제 플러그인이 돈 변경을 거절했어요");}
    }
    private void migrateSettings(){
        var config=plugin.getConfig();
        if(config.contains("wallet.refresh-millis",true))return;
        double old=config.getDouble("wallet.refresh-seconds",5);
        // Upgrade the former defaults; preserve explicitly customized slower intervals/budgets.
        config.set("wallet.refresh-millis",old==5?500:Math.round(Math.clamp(old,0.25,30)*1000));
        if(config.getInt("wallet.max-reads-per-second",32)==32)config.set("wallet.max-reads-per-second",128);
        var source=plugin.getDataFolder().toPath().resolve("config.yml");
        var backup=source.resolveSibling("config.pre-wallet-0.9.1.yml");
        try{
            if(java.nio.file.Files.exists(source) && !java.nio.file.Files.exists(backup))java.nio.file.Files.copy(source,backup);
            config.set("wallet.refresh-seconds",null);
            plugin.saveConfig();
            plugin.getLogger().info("잔액 표시 설정을 갱신했습니다: "+config.getLong("wallet.refresh-millis")+"ms (기존 config 백업 보관)");
        }catch(java.io.IOException error){plugin.getLogger().warning("잔액 설정 백업 실패: 이번 실행에만 새 설정 적용. "+error.getMessage());}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void findEconomy(){
        economy=null;balance=null;retryAt=now()+30000;
        for(Class<?> service:plugin.getServer().getServicesManager().getKnownServices()){
            if(!service.getName().equals(ECONOMY))continue;
            var registration=plugin.getServer().getServicesManager().getRegistration((Class)service);
            if(registration==null)continue;
            try{balance=service.getMethod("getBalance",OfflinePlayer.class);economy=registration.getProvider();economyType=service;}
            catch(ReflectiveOperationException ignored){/* Optional provider unavailable. */}
            break;
        }
    }
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] data){
        if(channel.equals(WalletProtocol.REQUEST) && WalletProtocol.validRequest(data))
            subscriptions.subscribe(player.getUniqueId(),now());
    }
    private void process(){
        if(subscriptions.size()==0)return;
        if(economy==null && now()>=retryAt)findEconomy();
        subscriptions.process(now(),new WalletSubscriptions.Access(){
            public boolean online(UUID id){var p=plugin.getServer().getPlayer(id);return p!=null && p.isOnline();}
            public Snapshot read(UUID id){
                var player=plugin.getServer().getPlayer(id);
                if(player==null || economy==null || balance==null)return Snapshot.unavailable();
                try{return new Snapshot(true,((Number)balance.invoke(economy,player)).doubleValue());}
                catch(ReflectiveOperationException | RuntimeException error){
                    plugin.getLogger().warning("Vault 잔액 조회 실패. 30초 후 다시 연결합니다: "+error.getClass().getSimpleName());
                    economy=null;balance=null;retryAt=now()+30000;return Snapshot.unavailable();
                }
            }
            public void send(UUID id,Snapshot value){
                var player=plugin.getServer().getPlayer(id);
                if(player!=null)player.sendPluginMessage(plugin,WalletProtocol.RESPONSE,WalletProtocol.encode(value));
            }
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){subscriptions.remove(event.getPlayer().getUniqueId());}
    @EventHandler public void onService(ServiceRegisterEvent event){if(event.getProvider().getService().getName().equals(ECONOMY))findEconomy();}
    @EventHandler public void onServiceRemoved(ServiceUnregisterEvent event){if(event.getProvider().getService().getName().equals(ECONOMY))findEconomy();}
    @Override public void close(){subscriptions.clear();economy=null;balance=null;}
}
