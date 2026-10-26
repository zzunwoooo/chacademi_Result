package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.CoreAffinity;
import school.magiccodex.protocol.ReconfigurationProtocol;
import school.magiccodex.protocol.ReconfigurationProtocol.*;

/**
 * Single-use offers, exact item snapshots and credits are checked/committed on the server thread.
 * 주문서 (오너 2026-10-26): 모드 0 강화 초기화 = item:core_scroll_red (주문서 I), 모드 1 실패 횟수 복구 =
 * item:core_scroll_blue (주문서 II), 모드 2 코어 성향 변경 = item:core_scroll_purple (주문서 III).
 * 사용 가능 횟수 = 예전 PDC 크레딧(reconfig_credit_&lt;mode&gt;, 관리자 지급) + 인벤토리 저장 칸의 그 주문서 개수.
 * 적용할 때 크레딧이 있으면 크레딧 1, 없으면 주문서 1개를 결과 아이템 교체와 같은 서버 틱에서 뺀다
 * (중간에 비동기·대기 없음 → 차감만 되고 결과가 안 들어가는 경우가 없음).
 * 응답 유실 대비: 같은 토큰의 APPLY 가 다시 오면(클라 재요청) 이미 낸 결과를 그대로 다시 보내고,
 * 만료·모르는 토큰의 요청에는 무시하지 않고 새 화면 상태를 보내 창이 "요청 중…"에 멈추지 않게 한다.
 * 장신구 재구성 (오너 2026-10-26 "장신구 재구성도 필요해"): 모드 0·1 은 EnhancementBridge 가 강화하는 장신구
 * (반지·귀걸이·목걸이, accessory.enabled)도 대상. 강화와 같은 규칙으로 주 손에 든 것만 — 장착 칸·인벤토리의 다른
 * 칸 장신구는 찾지 않는다 (마법봉은 예전처럼 주 손 → 인벤토리 순). 값 키·원본 키·시도 상한은 EnhancementBridge 것을
 * 그대로 써서 강화와 어긋나지 않게 한다: 장신구 = equipment_bonus_0/2/3 (지능·최대 마나·초당 마나 회복),
 * 원본 accessory_base_*, 상한 accessory.maximum-attempts(5). 모드 2 는 대상이 코어라 장신구여도 같은 흐름이고,
 * 주 손에 장신구를 들고 있으면 창의 성향 이름만 장신구 기준(세 번째 = 마나 회복)으로 보낸다.
 */
final class ReconfigurationBridge implements Listener,PluginMessageListener,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final EnhancementBridge enhancement;
    private final Map<UUID,Offer> offers=new HashMap<>();
    private final Map<UUID,Long> limits=new HashMap<>();
    private int restoreCount=1;
    private final ItemsAdderItems itemsAdder;
    /** 모드별 주문서 ItemsAdder 아이디 (reconfiguration.yml scroll-items, 없으면 기본값). */
    private final String[] scrollItem={"item:core_scroll_red","item:core_scroll_blue","item:core_scroll_purple"};
    private static final String[] SCROLL_KEYS={"reset","restore","affinity"};
    private record Offer(Response view,ItemStack item,long expires) {}
    ReconfigurationBridge(MagicCodexBridge plugin,EnhancementBridge enhancement) {
        this.plugin=plugin;this.enhancement=enhancement;this.itemsAdder=new ItemsAdderItems(plugin.getLogger());
        File f=new File(plugin.getDataFolder(),"reconfiguration.yml");if(!f.exists())plugin.saveResource("reconfiguration.yml",false);reload();
        CmdKit.register(plugin,"/마력재구성·/유저관리 재구성권",this::commands);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,ReconfigurationProtocol.REQUEST,this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,ReconfigurationProtocol.RESPONSE);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{long t=System.currentTimeMillis();offers.values().removeIf(o->o.expires<t);limits.values().removeIf(n->n<t);},200,200);
    }
    private void reload(){
        var c=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"reconfiguration.yml"));
        int n=c.getInt("restore-count",1);if(n<1||n>100)throw new IllegalArgumentException("restore-count: 1~100");
        String[] ids={"item:core_scroll_red","item:core_scroll_blue","item:core_scroll_purple"};
        for(int i=0;i<3;i++){String v=c.getString("scroll-items."+SCROLL_KEYS[i],ids[i]).trim();if(!v.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("scroll-items."+SCROLL_KEYS[i]+" 은 아이템애더 네임스페이스:아이디");ids[i]=v;}
        if(ids[0].equals(ids[1])||ids[0].equals(ids[2])||ids[1].equals(ids[2]))throw new IllegalArgumentException("scroll-items 의 세 주문서는 서로 달라야 해요");
        restoreCount=n;System.arraycopy(ids,0,scrollItem,0,3);offers.clear();
    }
    private NamespacedKey key(String n){return new NamespacedKey(plugin,n);}
    private int integer(ItemStack i,String n,int fallback){return i==null||!i.hasItemMeta()?fallback:i.getItemMeta().getPersistentDataContainer().getOrDefault(key(n),PersistentDataType.INTEGER,fallback);}
    private double number(ItemStack i,String n){return i.getItemMeta().getPersistentDataContainer().getOrDefault(key(n),PersistentDataType.DOUBLE,0d);}
    /** held: 주 손 칸이면 장신구도 대상, 다른 칸은 마법봉만 (장신구는 강화처럼 주 손에 들어야 함). */
    private boolean target(ItemStack i,int mode,boolean held){
        if(i==null||i.getAmount()!=1||!i.hasItemMeta())return false;
        if(mode==2)return integer(i,"core_state",-1)==1&&integer(i,"core_nodes",-1)>=0&&integer(i,"core_nodes",11)<=10;
        int kind=enhancement.targetKind(i);return kind==ReconfigurationProtocol.WAND||held&&kind==ReconfigurationProtocol.ACCESSORY;
    }
    private int credits(Player p,int mode){return Math.clamp(p.getPersistentDataContainer().getOrDefault(key("reconfig_credit_"+mode),PersistentDataType.INTEGER,0),0,1000000);}
    /** 인벤토리 저장 칸(0~35)의 이 모드 주문서 개수. ItemsAdder 가 없으면 0. */
    private int scrolls(Player p,int mode){
        if(!itemsAdder.available())return 0;String id=scrollItem[mode];int n=0;
        for(int j=0;j<36;j++){ItemStack i=p.getInventory().getItem(j);if(id.equals(itemsAdder.idOf(i)))n+=i.getAmount();}
        return n;
    }
    /** 창에 보이는 사용 가능 횟수 = 크레딧 + 주문서 (프로토콜 상한 1000000). */
    private int usableCount(Player p,int mode){return (int)Math.min(1_000_000L,(long)credits(p,mode)+scrolls(p,mode));}
    /** 주문서 1개를 뺀다 (target 칸은 건드리지 않음). 못 빼면 아무것도 안 바꾸고 false. */
    private boolean takeScroll(Player p,int mode,int keep){
        if(!itemsAdder.available())return false;String id=scrollItem[mode];var inv=p.getInventory();
        for(int j=0;j<36;j++){if(j==keep)continue;ItemStack i=inv.getItem(j);if(!id.equals(itemsAdder.idOf(i)))continue;
            if(i.getAmount()<=1)inv.setItem(j,null);else{ItemStack less=i.clone();less.setAmount(i.getAmount()-1);inv.setItem(j,less);}return true;}
        return false;
    }
    void grant(Player p,int mode,int amount){
        if(!plugin.playerStateReady(p))throw new IllegalArgumentException("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        if(!Bukkit.isPrimaryThread()||mode<0||mode>2)throw new IllegalStateException("mode 0..2 on the server thread");
        if(amount<1||amount>1000000||credits(p,mode)>1000000-amount)throw new IllegalArgumentException("재구성권은 합계 1~1000000회까지예요");
        p.getPersistentDataContainer().set(key("reconfig_credit_"+mode),PersistentDataType.INTEGER,credits(p,mode)+amount);p.saveData();plugin.savePlayerState(p);
    }
    private static final List<String> MODES=List.of("초기화","복구","변경");
    private void commands(){
        var user=CmdKit.literal("마력재구성").executes(CmdKit.run(ctx->open(CmdKit.self(ctx),0)));
        var admin=CmdKit.literal("재구성권");
        for(int i=0;i<MODES.size();i++){
            int mode=i;String name=MODES.get(i);
            user.then(CmdKit.literal(name).executes(CmdKit.run(ctx->open(CmdKit.self(ctx),mode))));
            admin.then(CmdKit.literal(name).then(CmdKit.argument("횟수",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,1_000_000)).executes(CmdKit.run(ctx->{
                Player target=ChacaApi.targetOnline(ctx);int amount=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"횟수");
                CmdKit.user(()->{grant(target,mode,amount);return null;});
                CmdKit.ok(CmdKit.sender(ctx),plugin.names().name(target)+" 님에게 "+name+" 재구성권 "+amount+"회를 주었어요 (지금 "+credits(target,mode)+"회)");
            }))));
        }
        ChacaApi.playerCommand(plugin,user,"마력 재구성 창 열기 (초기화·복구·변경)",List.of());
        ChacaApi.branch(plugin,ChacaApi.유저관리,admin,"마력 재구성권 주기 (초기화·복구·변경)","재구성권 초기화 1");
        ChacaApi.reload(plugin,"재구성","reconfiguration.yml (마력 재구성)",this::reload);
    }
    private boolean usable(Player p){var t=p.getOpenInventory().getType();return !p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&(t==org.bukkit.event.inventory.InventoryType.CRAFTING||t==org.bukkit.event.inventory.InventoryType.CREATIVE)&&p.getItemOnCursor().getType().isAir()&&!enhancement.active(p);}
    void open(Player p,int mode){open(p,mode,"");}
    /** notice: 적용 가능할 때만 아래 안내 줄에 보이는 문구 (만료돼 새로 불러온 경우 등). */
    private void open(Player p,int mode,String notice){
        if(!Bukkit.isPrimaryThread()||mode<0||mode>2)throw new IllegalArgumentException();
        long t=System.currentTimeMillis();if(t<limits.getOrDefault(p.getUniqueId(),0L))return;limits.put(p.getUniqueId(),t+200);
        if(!plugin.playerStateReady(p)){warn(p,"캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");return;}
        if(!p.hasPermission("magiccodex.reconfigure"))return;
        if(!p.getListeningPluginChannels().contains(ReconfigurationProtocol.RESPONSE)){warn(p,"차카데미 모드를 업데이트해 주세요");return;}
        if(!usable(p)){warn(p,"다른 보관함이나 진행 중인 강화를 먼저 닫아 주세요");return;}
        int slot=p.getInventory().getHeldItemSlot();ItemStack item=p.getInventory().getItem(slot);
        // 모드 2 의 kind 는 표시용: 주 손에 장신구를 들고 성향을 바꾸면 장신구 기준 성향 이름을 보여 준다.
        int kind=mode==2&&enhancement.targetKind(item)==ReconfigurationProtocol.ACCESSORY?ReconfigurationProtocol.ACCESSORY:ReconfigurationProtocol.WAND;
        if(!target(item,mode,true)){slot=-1;for(int j=0;j<36;j++)if(target(p.getInventory().getItem(j),mode,false)){slot=j;break;}item=slot<0?null:p.getInventory().getItem(slot);}
        if(item!=null&&mode!=2)kind=enhancement.targetKind(item);
        String what=kind==ReconfigurationProtocol.ACCESSORY?"장신구":"마법봉";
        String message="",name="대상 없음";int attempts=0,successes=0,limit=10,nodes=0,affinity=0,nextAttempts=0;
        List<Double> before=List.of(0d,0d,0d),after=before;
        if(item!=null){ItemStack visual=plugin.coreAppearance(item);if(visual!=item){item=visual;p.getInventory().setItem(slot,item);}}
        if(item==null)message=mode==2?"인벤토리에 감정된 마력코어가 필요합니다.":"인벤토리에 등록된 마법봉이 필요합니다. 장신구는 주 손에 들어 주세요.";
        else {
            name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()!=null?item.getItemMeta().displayName():item.displayName());if(name.length()>80)name=name.substring(0,80);
            if(mode==2){nodes=integer(item,"core_nodes",0);affinity=CoreAffinity.valid(integer(item,"core_affinity",0));}
            else {
                enhancement.captureBase(item);
                String[] keys=enhancement.statKeys(kind),base=enhancement.baseStatKeys(kind);
                attempts=integer(item,"enhance_attempts",0);successes=integer(item,"enhance_successes",0);limit=integer(item,"enhance_limit",enhancement.attemptLimit(kind));
                before=List.of(number(item,keys[0]),number(item,keys[1]),number(item,keys[2]));after=before;
                if(attempts<0||attempts>100||successes<0||successes>attempts||limit<1||limit>100||before.stream().anyMatch(v->!Double.isFinite(v)||v<0||v>100000)){plugin.getLogger().warning("재구성: "+what+" 데이터 이상 player="+p.getName()+" attempts="+attempts+" successes="+successes+" limit="+limit+" stats="+before);warn(p,what+" 데이터가 올바르지 않아요. 관리자에게 알려 주세요");return;}
                nextAttempts=mode==0?0:ReconfigurationRules.restore(attempts,successes,restoreCount);
                if(mode==0){
                    if(!enhancement.hasBase(item))message="강화 전 원본 수치를 먼저 등록해 주세요.";
                    else {after=List.of(number(item,base[0]),number(item,base[1]),number(item,base[2]));for(int j=0;j<3;j++)if(!Double.isFinite(after.get(j))||after.get(j)<0||after.get(j)>before.get(j))message=what+" 원본 수치를 확인해 주세요.";}
                    if(attempts==0)message="초기화할 강화 기록이 없습니다.";
                }else if(attempts==successes)message="복구할 실패 횟수가 없습니다.";
            }
        }
        int credit=usableCount(p,mode);if(message.isEmpty()&&credit==0)message="사용 가능한 주문서가 없습니다.";
        boolean available=message.isEmpty();if(available&&!notice.isEmpty())message=notice;
        Response r=new Response(ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE),mode,0,slot,name,message,credit,nodes,affinity,-1,attempts,successes,limit,nextAttempts,before,after,available,kind);
        offers.put(p.getUniqueId(),new Offer(r,item==null?null:item.clone(),t+60000));send(p,r);
    }
    @Override public void onPluginMessageReceived(String ch,Player p,byte[] bytes){
        if(!ch.equals(ReconfigurationProtocol.REQUEST))return;Request q;try{q=ReconfigurationProtocol.request(bytes);}catch(IllegalArgumentException e){return;}
        Offer o=offers.get(p.getUniqueId());
        if(q.action()==ReconfigurationProtocol.CLOSE){if(o!=null&&o.view.token()==q.token())offers.remove(p.getUniqueId());return;}
        // 제안이 만료(60초)됐거나 토큰이 바뀐 요청: 예전엔 조용히 버려 창이 멈췄다. 지금 상태를 새로 보낸다 (200ms 제한은 open 안).
        // 결과 확정은 아래의 같은 토큰 경로에서만 하므로 여기서는 아무것도 바뀌지 않는다.
        if(o==null||o.view.token()!=q.token()){open(p,q.mode(),q.action()==ReconfigurationProtocol.APPLY?"요청이 만료돼 새로 불러왔어요. 다시 눌러 주세요.":"");return;}
        if(q.action()==ReconfigurationProtocol.SELECT){open(p,q.mode());return;}
        // 이미 처리한 토큰의 APPLY 재요청(응답 유실 → 클라 재전송): 같은 결과를 다시 보낸다. 두 번 적용되지 않음.
        if(o.view.state()!=0){send(p,o.view);return;}
        offers.remove(p.getUniqueId());Response r=o.view;
        if(!r.available()||q.mode()!=r.mode()||!p.hasPermission("magiccodex.reconfigure")||!usable(p)||System.currentTimeMillis()>o.expires||r.slot()<0||!o.item.equals(p.getInventory().getItem(r.slot()))||r.mode()!=2&&r.kind()==ReconfigurationProtocol.ACCESSORY&&p.getInventory().getHeldItemSlot()!=r.slot()){reply(p,r,2,"아이템이 변경됐거나 요청이 만료됐습니다. 다시 열어 주세요.",-1);return;}
        if(usableCount(p,r.mode())<1){reply(p,r,2,"사용 가능한 주문서가 없습니다.",-1);return;}
        ItemStack result=o.item.clone();var m=result.getItemMeta();var d=m.getPersistentDataContainer();int next=-1;
        if(r.mode()==2){next=CoreAffinity.changed(r.affinity(),ThreadLocalRandom.current().nextInt(2));d.set(key("core_affinity"),PersistentDataType.INTEGER,next);}
        else {
            d.set(key("enhance_attempts"),PersistentDataType.INTEGER,r.nextAttempts());
            if(r.mode()==0){d.set(key("enhance_successes"),PersistentDataType.INTEGER,0);String[] keys=enhancement.statKeys(r.kind());for(int j=0;j<3;j++)d.set(key(keys[j]),PersistentDataType.DOUBLE,r.after().get(j));}
        }
        result.setItemMeta(m);result=plugin.coreAppearance(result);if(r.mode()!=2)enhancement.lore(result);
        // 차감 → 결과 교체 → 저장이 한 서버 틱 안에서 끝난다. 예전 크레딧이 남아 있으면 크레딧 먼저, 없으면 주문서 아이템.
        // 주문서를 못 빼면(그 사이 사라짐) 아무것도 바꾸지 않고 거절. Duplicate packets replay the stored result above.
        int credit=credits(p,r.mode());
        if(credit<1&&!takeScroll(p,r.mode(),r.slot())){reply(p,r,2,"사용 가능한 주문서가 없습니다.",-1);return;}
        if(credit>=1)p.getPersistentDataContainer().set(key("reconfig_credit_"+r.mode()),PersistentDataType.INTEGER,credit-1);
        p.getInventory().setItem(r.slot(),result);p.saveData();plugin.savePlayerState(p);plugin.refreshEquipment(p);
        reply(p,r,1,r.mode()==0?"강화를 초기화했습니다.":r.mode()==1?"실패 횟수 "+(r.attempts()-r.nextAttempts())+"회를 복구했습니다.":"코어 성향을 변경했습니다.",next);
    }
    private void reply(Player p,Response r,int state,String message,int affinity){Response result=new Response(r.token(),r.mode(),state,r.slot(),r.name(),message,usableCount(p,r.mode()),r.nodes(),r.affinity(),affinity,r.attempts(),r.successes(),r.limit(),r.nextAttempts(),r.before(),r.after(),false,r.kind());send(p,result);offers.put(p.getUniqueId(),new Offer(result,null,System.currentTimeMillis()+60000));}
    private static void warn(Player p,String body){CmdKit.notify(p,"warning","reconfig-open","마력 재구성을 열 수 없어요",body,1500);}
    private void send(Player p,Response r){p.sendPluginMessage(plugin,ReconfigurationProtocol.RESPONSE,ReconfigurationProtocol.response(r));}
    @EventHandler public void quit(PlayerQuitEvent e){offers.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){offers.clear();limits.clear();}
}
